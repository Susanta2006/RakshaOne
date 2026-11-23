package com.example.risk

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.model.AuditLogEntity
import com.example.data.model.DossierAccessEntity
import com.example.data.model.EscalationWorkflowEntity
import com.example.data.model.PoliceDispatchDossier
import com.example.data.model.RiskAssessmentEntity
import com.example.data.repository.FirestoreRiskAndRescueRepository
import com.example.util.EmergencyResponseDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Centralized, Non-Bypassable Emergency Escalation Orchestrator.
 *
 * Guarantees:
 * - Every escalation decision runs through EmergencyEscalationPolicyEngine.
 * - R0, R1, and R2 NEVER unlock police / 112 dispatch. Any unauthorized call throws PolicyViolationException
 *   and logs an immutable ESCALATION_POLICY_REJECTED audit entry.
 * - R3_CRITICAL workflows are idempotent per incidentId so retries never spawn duplicate dispatches.
 */
class EmergencyEscalationOrchestrator(
    private val context: Context,
    private val database: AppDatabase,
    private val responseDispatcher: EmergencyResponseDispatcher,
    private val firestoreRepository: FirestoreRiskAndRescueRepository? = null
) {

    companion object {
        const val HOSTING_BASE_URL = "rakshaone://dispatch"
        const val DOSSIER_TTL_MILLIS = 24L * 60L * 60L * 1000L
    }

    suspend fun recordRiskAssessment(
        assessment: RiskAssessmentResult,
        incidentId: String
    ) = withContext(Dispatchers.IO) {
        val entity = RiskAssessmentEntity(
            assessmentId = assessment.assessmentId,
            incidentId = incidentId,
            riskLevel = assessment.riskLevel.name,
            triggerOrigin = assessment.triggerOrigin.name,
            confidence = assessment.confidence,
            policeEscalationEligible = assessment.policeEscalationEligible,
            reasonCodesCsv = assessment.reasonCodes.joinToString(","),
            explanation = assessment.explanation,
            travelBumpSuppressed = assessment.context.isTravelBumpSuppressed,
            observabilityDegraded = assessment.context.isObservabilityDegraded,
            evaluatedAt = assessment.evaluatedAtMillis
        )
        database.riskAssessmentDao().insertAssessment(entity)
        if (assessment.riskLevel == RiskLevel.R3_CRITICAL) {
            com.example.service.R3CriticalMonitorWorker.triggerImmediateMonitor(
                context = context,
                incidentId = incidentId,
                riskLevel = assessment.riskLevel.name,
                reason = "ASSESSMENT_${assessment.triggerOrigin.name}"
            )
        }
    }

    /**
     * Policy-enforced police/112 dossier & escalation preparation.
     * Throws PolicyViolationException if assessment is < R3_CRITICAL or non-explicit.
     */
    suspend fun authorizeAndPreparePoliceEscalation(
        incidentId: String,
        assessment: RiskAssessmentResult,
        location: Location?,
        localityName: String,
        medicalSummary: String,
        evidenceHashesSummary: String,
        userNote: String
    ): Result<PoliceDispatchDossier> = withContext(Dispatchers.IO) {
        // Persist assessment record regardless of level
        recordRiskAssessment(assessment, incidentId)

        try {
            // NON-BYPASSABLE POLICY GATE
            EmergencyEscalationPolicyEngine.requirePoliceEscalationAuthorized(assessment, incidentId)
        } catch (violation: PolicyViolationException) {
            database.auditLogDao().insertLog(
                AuditLogEntity(
                    actor = "EmergencyEscalationPolicyEngine",
                    role = "SYSTEM",
                    action = "ESCALATION_POLICY_REJECTED",
                    targetId = incidentId,
                    details = violation.message ?: "Blocked police/112 escalation for ${assessment.riskLevel.name}."
                )
            )
            Log.w("EscalationPolicy", violation.message ?: "Escalation rejected")
            return@withContext Result.failure(violation)
        }

        // Idempotency guard: avoid creating duplicate dispatches/tokens on retry
        val existingWorkflow = database.escalationWorkflowDao().getWorkflowOnce(incidentId)
        val existingDossierAccess = database.dossierAccessDao().getActiveDossierForIncident(incidentId)
        val tokenId = existingDossierAccess?.tokenId ?: UUID.randomUUID().toString().replace("-", "").take(24)
        val expiresAt = existingDossierAccess?.expiresAt ?: (System.currentTimeMillis() + DOSSIER_TTL_MILLIS)
        val webPortalUrl = "$HOSTING_BASE_URL/$incidentId?token=$tokenId"

        val dossier = responseDispatcher.createAuthorizedPoliceDossier(
            incidentId = incidentId,
            assessment = assessment,
            location = location,
            localityName = localityName,
            medicalSummary = medicalSummary,
            evidenceHashesSummary = evidenceHashesSummary,
            webPortalUrl = webPortalUrl,
            dossierToken = tokenId,
            expiresAtMillis = expiresAt,
            existingStatus = existingWorkflow?.let {
                PoliceEscalationStatus.entries.firstOrNull { st -> st.name == it.policeStatus }
            } ?: PoliceEscalationStatus.ELIGIBLE,
            existingDeliveryState = existingWorkflow?.let {
                ReceiptDeliveryState.entries.firstOrNull { st -> st.name == it.deliveryState }
            } ?: ReceiptDeliveryState.PENDING
        )

        if (existingDossierAccess == null) {
            val jsonPayload = JSONObject().apply {
                put("incidentId", dossier.incidentId)
                put("dispatchId", dossier.dispatchId)
                put("riskLevel", dossier.riskLevel.name)
                put("triggerOrigin", assessment.triggerOrigin.name)
                put("victimName", dossier.victimName)
                put("victimPhone", dossier.victimPhone)
                put("latitude", dossier.latitude)
                put("longitude", dossier.longitude)
                put("accuracyMeters", dossier.accuracyMeters.toDouble())
                put("localityAddress", dossier.localityAddress)
                put("mapsUrl", dossier.mapsUrl)
                put("medicalSummary", dossier.medicalSummary)
                put("evidenceHashesSummary", dossier.evidenceHashesSummary)
                put("policeStatus", dossier.policeStatus.name)
                put("expiresAt", expiresAt)
            }.toString()

            database.dossierAccessDao().insertDossierAccess(
                DossierAccessEntity(
                    tokenId = tokenId,
                    incidentId = incidentId,
                    dossierJson = jsonPayload,
                    webUrl = webPortalUrl,
                    createdAt = dossier.timestamp,
                    expiresAt = expiresAt,
                    isRevoked = false
                )
            )
        }

        if (existingWorkflow == null) {
            database.escalationWorkflowDao().upsertWorkflow(
                EscalationWorkflowEntity(
                    incidentId = incidentId,
                    riskLevel = assessment.riskLevel.name,
                    policeEscalationEligible = true,
                    policeStatus = dossier.policeStatus.name,
                    deliveryState = dossier.deliveryState.name,
                    trustedSmsDispatched = false,
                    trustedWhatsAppDispatched = false,
                    bleBroadcastStarted = false,
                    hexRequestId = null,
                    createdAt = dossier.timestamp,
                    updatedAt = dossier.timestamp
                )
            )

            database.auditLogDao().insertLog(
                AuditLogEntity(
                    actor = "EmergencyEscalationOrchestrator",
                    role = "SYSTEM",
                    action = "R3_POLICE_ESCALATION_UNLOCKED",
                    targetId = incidentId,
                    details = "R3_CRITICAL authorized by ${assessment.triggerOrigin.name}. In-app dossier ready at $webPortalUrl (awaiting user 112 dialer/SMS dispatch)."
                )
            )
        }

        try {
            firestoreRepository?.syncRiskAssessmentAndEscalation(
                assessment = assessment,
                incidentId = incidentId,
                dossier = dossier,
                lat = dossier.latitude,
                lng = dossier.longitude,
                locDesc = dossier.localityAddress,
                description = userNote
            )
            com.example.service.RakshaFirebaseMessagingService.broadcastCriticalDossierToken(
                context = context,
                incidentId = incidentId,
                dossierToken = tokenId,
                dossierUrl = webPortalUrl,
                victimName = "Citizen",
                latitude = dossier.latitude,
                longitude = dossier.longitude,
                accuracyMeters = null,
                batteryPercent = 100,
                evidenceSummary = dossier.evidenceHashesSummary
            )
        } catch (_: Exception) {
        }

        Result.success(dossier)
    }

    suspend fun markChannelDispatched(
        incidentId: String,
        channel: String,
        deliveryState: ReceiptDeliveryState,
        newPoliceStatus: PoliceEscalationStatus? = null,
        details: String = ""
    ) = withContext(Dispatchers.IO) {
        val workflow = database.escalationWorkflowDao().getWorkflowOnce(incidentId)
        if (workflow != null) {
            val updated = workflow.copy(
                policeStatus = newPoliceStatus?.name ?: workflow.policeStatus,
                deliveryState = deliveryState.name,
                trustedSmsDispatched = workflow.trustedSmsDispatched || channel == "TRUSTED_SMS",
                trustedWhatsAppDispatched = workflow.trustedWhatsAppDispatched || channel == "TRUSTED_WHATSAPP",
                bleBroadcastStarted = workflow.bleBroadcastStarted || channel == "BLE_RELAY",
                updatedAt = System.currentTimeMillis()
            )
            database.escalationWorkflowDao().upsertWorkflow(updated)
        }

        if (newPoliceStatus != null) {
            responseDispatcher.updateLatestDossierStatus(incidentId, newPoliceStatus, deliveryState)
        }

        database.auditLogDao().insertLog(
            AuditLogEntity(
                actor = "DispatchTracker",
                role = "SYSTEM",
                action = "CHANNEL_$channel",
                targetId = incidentId,
                details = "Channel=$channel • State=${deliveryState.name} • $details"
            )
        )

        try {
            firestoreRepository?.recordDispatchAttemptAndReceipt(
                incidentId = incidentId,
                channel = channel,
                deliveryState = deliveryState,
                details = details
            )
        } catch (_: Exception) {
        }
    }

    suspend fun setHexRequestId(incidentId: String, hexRequestId: String) = withContext(Dispatchers.IO) {
        val workflow = database.escalationWorkflowDao().getWorkflowOnce(incidentId) ?: return@withContext
        database.escalationWorkflowDao().upsertWorkflow(
            workflow.copy(
                hexRequestId = hexRequestId,
                bleBroadcastStarted = true,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getDossierAccess(incidentId: String): DossierAccessEntity? = withContext(Dispatchers.IO) {
        database.dossierAccessDao().getActiveDossierForIncident(incidentId)
    }

    suspend fun clearResolvedDossier(incidentId: String) = withContext(Dispatchers.IO) {
        database.dossierAccessDao().deleteForIncident(incidentId)
        responseDispatcher.clearDossierForIncident(incidentId)
    }
}

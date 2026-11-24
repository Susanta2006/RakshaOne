package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.example.R
import com.example.data.model.GoodCitizenProfile
import com.example.data.model.HexAlertStatus
import com.example.data.model.HexRescueAlert
import com.example.data.model.PoliceDispatchDossier
import com.example.risk.CitizenVerificationStatus
import com.example.risk.HexRescueLifecycleState
import com.example.risk.PoliceEscalationStatus
import com.example.risk.ReceiptDeliveryState
import com.example.risk.RiskAssessmentResult
import com.example.risk.RiskLevel
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Locale

@Keep
@IgnoreExtraProperties
data class CloudCitizenProfile(
    @DocumentId val id: String = "",
    val uid: String = "",
    val displayName: String = "",
    val verificationStatus: String = CitizenVerificationStatus.UNVERIFIED.name,
    val verificationDocType: String = "",
    val docLastFourHint: String = "",
    val trustScore: Int = 0,
    val acceptedHelpsCount: Int = 0,
    val completedHelpsCount: Int = 0,
    val declinedHelpsCount: Int = 0,
    val isVolunteerActive: Boolean = false,
    val badgeLevel: String = "Unverified Citizen",
    val endorserUid: String? = null,
    val endorsementCode: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudHexRescueRequest(
    @DocumentId val id: String = "",
    val requestId: String = "",
    val alertId: String = "",
    val incidentId: String = "",
    val victimUid: String = "",
    val victimName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val currentRadiusMeters: Double = 750.0,
    val expansionStage: Int = 1,
    val dangerType: String = "R3 CRITICAL SOS",
    val locationDescription: String = "",
    val evidenceSummary: String = "",
    val lifecycleState: String = HexRescueLifecycleState.REQUEST_BROADCAST.name,
    val compassSector: String = "N-NE",
    val policeStatus: String = PoliceEscalationStatus.ELIGIBLE.name,
    val acceptedResponderUid: String? = null,
    val acceptedResponderName: String? = null,
    val acceptedResponderPhone: String? = null,
    val acceptedResponderSelfieB64: String? = null,
    val acceptedResponderLat: Double? = null,
    val acceptedResponderLng: Double? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

class FirestoreRiskAndRescueRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth get() = Firebase.auth

    private fun currentUidOrNull(): String? = try {
        auth.currentUser?.uid
    } catch (_: Exception) {
        null
    }

    suspend fun syncCitizenProfile(profile: GoodCitizenProfile): Result<Unit> {
        val uid = currentUidOrNull() ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "citizens/$uid"
        return try {
            val docRef = db.collection("citizens").document(uid)
            val existing = docRef.get().await()
            val payload = mutableMapOf<String, Any?>(
                "uid" to uid,
                "displayName" to profile.displayName.trim().take(100),
                "verificationDocType" to profile.verificationDocType.trim().take(60),
                "docLastFourHint" to profile.docLastFourHint.trim().take(8),
                "isVolunteerActive" to (profile.isVolunteerActive && profile.verificationStatus == CitizenVerificationStatus.VERIFIED),
                "endorsementCode" to profile.endorsementCode.trim().take(24),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!existing.exists()) {
                // Zero-start enforcement on initial creation
                payload["verificationStatus"] = CitizenVerificationStatus.UNVERIFIED.name
                payload["trustScore"] = 0
                payload["acceptedHelpsCount"] = 0
                payload["completedHelpsCount"] = 0
                payload["declinedHelpsCount"] = 0
                payload["badgeLevel"] = "Unverified Citizen"
                payload["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(payload).await()
            } else {
                docRef.update(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun submitKycVerification(
        docType: String,
        lastFourHint: String,
        endorsementCode: String
    ): Result<String> {
        val uid = currentUidOrNull() ?: return Result.failure(IllegalStateException("Google Sign-In required"))
        val cleanId = "VER-${uid.take(12)}-${System.currentTimeMillis() % 100000}"
        val path = "citizen_verifications/$cleanId"
        return try {
            val payload = mapOf(
                "verificationId" to cleanId,
                "ownerUid" to uid,
                "docType" to docType.trim().take(60),
                "docLastFourHint" to lastFourHint.trim().take(8),
                "endorsementCode" to endorsementCode.trim().take(24),
                "status" to CitizenVerificationStatus.PENDING.name,
                "endorserUid" to null,
                "endorserName" to null,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("citizen_verifications").document(cleanId).set(payload).await()
            db.collection("citizens").document(uid).set(
                mapOf(
                    "uid" to uid,
                    "verificationStatus" to CitizenVerificationStatus.PENDING.name,
                    "verificationDocType" to docType.trim().take(60),
                    "docLastFourHint" to lastFourHint.trim().take(8),
                    "endorsementCode" to endorsementCode.trim().take(24),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
            Result.success(cleanId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    /**
     * Trusted Guardian Peer Endorsement (non-admin):
     * A different signed-in guardian enters the citizen's endorsement code to verify their KYC submission.
     */
    suspend fun endorsePeerByCode(endorsementCode: String): Result<String> {
        val endorserUid = currentUidOrNull()
            ?: return Result.failure(IllegalStateException("Sign in required to endorse a citizen"))
        val cleanCode = endorsementCode.trim().uppercase(Locale.US)
        if (cleanCode.length < 4) {
            return Result.failure(IllegalArgumentException("Enter a valid endorsement code."))
        }
        val path = "citizen_verifications"
        return try {
            val query = db.collection("citizen_verifications")
                .whereEqualTo("endorsementCode", cleanCode)
                .whereEqualTo("status", CitizenVerificationStatus.PENDING.name)
                .limit(1)
                .get()
                .await()
            val doc = query.documents.firstOrNull()
                ?: return Result.failure(IllegalStateException("No pending KYC request matched code $cleanCode."))
            val ownerUid = doc.getString("ownerUid") ?: ""
            if (ownerUid.isBlank() || ownerUid == endorserUid) {
                return Result.failure(SecurityException("Self-endorsement is prohibited by verification policy."))
            }
            val endorserName = auth.currentUser?.displayName ?: "Verified Guardian"
            db.runBatch { batch ->
                batch.update(
                    doc.reference,
                    mapOf(
                        "status" to CitizenVerificationStatus.VERIFIED.name,
                        "endorserUid" to endorserUid,
                        "endorserName" to endorserName,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    db.collection("citizens").document(ownerUid),
                    mapOf(
                        "uid" to ownerUid,
                        "verificationStatus" to CitizenVerificationStatus.VERIFIED.name,
                        "endorserUid" to endorserUid,
                        "badgeLevel" to "Verified Community Responder",
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
            }.await()
            Result.success(ownerUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun recordPointsLedgerEntry(
        delta: Int,
        balanceAfter: Int,
        reasonCode: String,
        relatedIncidentId: String?
    ): Result<Unit> {
        val uid = currentUidOrNull() ?: return Result.failure(IllegalStateException("Not signed in"))
        val entryId = "PTS-${uid.take(8)}-${System.currentTimeMillis()}"
        val path = "points_ledger/$entryId"
        return try {
            db.collection("points_ledger").document(entryId).set(
                mapOf(
                    "entryId" to entryId,
                    "citizenUid" to uid,
                    "delta" to delta,
                    "balanceAfter" to balanceAfter.coerceAtLeast(0),
                    "reasonCode" to reasonCode.trim().take(60),
                    "relatedIncidentId" to (relatedIncidentId ?: ""),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            db.collection("citizens").document(uid).set(
                mapOf(
                    "uid" to uid,
                    "trustScore" to balanceAfter.coerceAtLeast(0),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun syncRiskAssessmentAndEscalation(
        assessment: RiskAssessmentResult,
        incidentId: String,
        dossier: PoliceDispatchDossier?,
        lat: Double,
        lng: Double,
        locDesc: String,
        description: String
    ): Result<Unit> {
        val uid = currentUidOrNull() ?: return Result.failure(IllegalStateException("Not signed in"))
        val cleanInc = incidentId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        return try {
            db.collection("incidents").document(cleanInc).set(
                mapOf(
                    "incidentId" to cleanInc,
                    "ownerUid" to uid,
                    "type" to assessment.triggerOrigin.name,
                    "riskLevel" to assessment.riskLevel.name,
                    "policeEscalationEligible" to assessment.policeEscalationEligible,
                    "status" to "CREATED",
                    "latitude" to lat.coerceIn(-90.0, 90.0),
                    "longitude" to lng.coerceIn(-180.0, 180.0),
                    "locationDescription" to locDesc.take(300),
                    "description" to description.take(500),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()

            db.collection("risk_assessments").document(assessment.assessmentId).set(
                mapOf(
                    "assessmentId" to assessment.assessmentId,
                    "incidentId" to cleanInc,
                    "ownerUid" to uid,
                    "riskLevel" to assessment.riskLevel.name,
                    "triggerOrigin" to assessment.triggerOrigin.name,
                    "confidence" to assessment.confidence,
                    "policeEscalationEligible" to assessment.policeEscalationEligible,
                    "reasonCodes" to assessment.reasonCodes.take(20),
                    "explanation" to assessment.explanation.take(500),
                    "policyVersion" to assessment.policyVersion,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()

            // STRICT NON-BYPASSABLE POLICY: Only R3_CRITICAL may write emergency_escalation_events
            if (assessment.riskLevel == RiskLevel.R3_CRITICAL && assessment.policeEscalationEligible && dossier != null) {
                db.collection("emergency_escalation_events").document(cleanInc).set(
                    mapOf(
                        "incidentId" to cleanInc,
                        "ownerUid" to uid,
                        "assessmentId" to assessment.assessmentId,
                        "riskLevel" to RiskLevel.R3_CRITICAL.name,
                        "triggerOrigin" to assessment.triggerOrigin.name,
                        "policeEscalationEligible" to true,
                        "policeStatus" to dossier.policeStatus.name,
                        "deliveryState" to dossier.deliveryState.name,
                        "webPortalUrl" to dossier.webPortalUrl.take(400),
                        "dossierToken" to dossier.dossierToken.take(64),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()

                if (dossier.dossierToken.isNotBlank()) {
                    db.collection("dossier_access").document(dossier.dossierToken).set(
                        mapOf(
                            "tokenId" to dossier.dossierToken,
                            "incidentId" to cleanInc,
                            "ownerUid" to uid,
                            "riskLevel" to RiskLevel.R3_CRITICAL.name,
                            "victimName" to dossier.victimName.take(100),
                            "victimPhone" to dossier.victimPhone.take(30),
                            "latitude" to dossier.latitude,
                            "longitude" to dossier.longitude,
                            "accuracyMeters" to dossier.accuracyMeters,
                            "localityAddress" to dossier.localityAddress.take(300),
                            "mapsUrl" to dossier.mapsUrl.take(300),
                            "medicalSummary" to dossier.medicalSummary.take(400),
                            "evidenceSummary" to dossier.evidenceHashesSummary.take(500),
                            "policeStatus" to dossier.policeStatus.name,
                            "expiresAtMillis" to dossier.expiresAtMillis,
                            "revoked" to false,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "risk_assessments/$cleanInc")
            Result.failure(e)
        }
    }

    suspend fun updateDossierResponderTelemetry(
        tokenId: String,
        victimLat: Double,
        victimLng: Double,
        responderName: String?,
        responderPhone: String?,
        responderStatus: String?,
        responderLat: Double?,
        responderLng: Double?,
        responderDistanceMeters: Float?,
        responderEtaMinutes: Int?
    ): Result<Unit> {
        val uid = currentUidOrNull() ?: return Result.failure(IllegalStateException("Not signed in"))
        val cleanTok = tokenId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        if (cleanTok.isBlank()) return Result.failure(IllegalArgumentException("Blank dossier token"))
        return try {
            val updates = hashMapOf<String, Any>(
                "ownerUid" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (victimLat != 0.0 || victimLng != 0.0) {
                updates["latitude"] = victimLat.coerceIn(-90.0, 90.0)
                updates["longitude"] = victimLng.coerceIn(-180.0, 180.0)
                updates["mapsUrl"] = "https://maps.google.com/?q=$victimLat,$victimLng"
            }
            responderName?.takeIf { it.isNotBlank() }?.let { updates["responderName"] = it.take(100) }
            responderPhone?.takeIf { it.isNotBlank() }?.let { updates["responderPhone"] = it.take(30) }
            responderStatus?.takeIf { it.isNotBlank() }?.let { updates["responderStatus"] = it.take(40) }
            if (responderLat != null && responderLng != null && (responderLat != 0.0 || responderLng != 0.0)) {
                updates["responderLat"] = responderLat.coerceIn(-90.0, 90.0)
                updates["responderLng"] = responderLng.coerceIn(-180.0, 180.0)
                updates["responderMapsUrl"] = "https://maps.google.com/?q=$responderLat,$responderLng"
            }
            responderDistanceMeters?.let { updates["responderDistanceMeters"] = it.toDouble() }
            responderEtaMinutes?.let { updates["responderEtaMinutes"] = it }
            db.collection("dossier_access").document(cleanTok).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("FirestoreRiskRepo", "Unable to update dossier responder telemetry: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun recordDispatchAttemptAndReceipt(
        incidentId: String,
        channel: String,
        deliveryState: ReceiptDeliveryState,
        details: String
    ): Result<Unit> {
        val uid = currentUidOrNull() ?: return Result.failure(IllegalStateException("Not signed in"))
        val cleanInc = incidentId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val ts = System.currentTimeMillis()
        val attemptId = "ATT-${cleanInc.take(24)}-${ts % 100000}"
        return try {
            db.collection("dispatch_attempts").document(attemptId).set(
                mapOf(
                    "attemptId" to attemptId,
                    "incidentId" to cleanInc,
                    "ownerUid" to uid,
                    "channel" to channel.take(40),
                    "deliveryState" to deliveryState.name,
                    "details" to details.take(400),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            db.collection("delivery_receipts").document("RCP-$attemptId").set(
                mapOf(
                    "receiptId" to "RCP-$attemptId",
                    "attemptId" to attemptId,
                    "incidentId" to cleanInc,
                    "ownerUid" to uid,
                    "channel" to channel.take(40),
                    "state" to deliveryState.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "dispatch_attempts/$attemptId")
            Result.failure(e)
        }
    }

    suspend fun publishHexRescueRequest(alert: HexRescueAlert): Result<Unit> {
        val uid = currentUidOrNull() ?: return Result.failure(IllegalStateException("Not signed in"))
        val cleanId = alert.alertId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val path = "hex_rescue_requests/$cleanId"
        return try {
            db.collection("hex_rescue_requests").document(cleanId).set(
                mapOf(
                    "requestId" to cleanId,
                    "alertId" to cleanId,
                    "incidentId" to alert.incidentId,
                    "victimUid" to uid,
                    "victimName" to alert.victimName.take(100),
                    "latitude" to alert.victimLatitude.coerceIn(-90.0, 90.0),
                    "longitude" to alert.victimLongitude.coerceIn(-180.0, 180.0),
                    "currentRadiusMeters" to alert.currentRadiusMeters.toDouble(),
                    "expansionStage" to alert.expansionStage,
                    "dangerType" to alert.dangerType.take(120),
                    "locationDescription" to alert.victimLocationDescription.take(300),
                    "evidenceSummary" to alert.evidenceSummary.take(400),
                    "lifecycleState" to alert.lifecycleState.name,
                    "compassSector" to alert.compassSector,
                    "policeStatus" to alert.policeStatus.name,
                    "acceptedResponderUid" to alert.acceptedResponderUid,
                    "acceptedResponderName" to alert.acceptedResponderName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun updateHexRescueLifecycle(
        requestId: String,
        newState: HexRescueLifecycleState,
        responderName: String? = null,
        responderPhone: String? = null,
        responderSelfieB64: String? = null,
        responderLat: Double? = null,
        responderLng: Double? = null
    ): Result<Unit> {
        val uid = currentUidOrNull() ?: return Result.failure(IllegalStateException("Not signed in"))
        val cleanId = requestId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val path = "hex_rescue_requests/$cleanId"
        return try {
            val updates = mutableMapOf<String, Any?>(
                "lifecycleState" to newState.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (newState == HexRescueLifecycleState.RESPONDER_ACCEPTED) {
                updates["acceptedResponderUid"] = uid
                updates["acceptedResponderName"] = (responderName ?: auth.currentUser?.displayName ?: "Verified Citizen").take(100)
                if (!responderPhone.isNullOrBlank()) {
                    updates["acceptedResponderPhone"] = responderPhone.take(30)
                }
                if (!responderSelfieB64.isNullOrBlank()) {
                    updates["acceptedResponderSelfieB64"] = responderSelfieB64.take(120000)
                }
                if (responderLat != null) updates["acceptedResponderLat"] = responderLat
                if (responderLng != null) updates["acceptedResponderLng"] = responderLng
            }
            db.collection("hex_rescue_requests").document(cleanId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateApproachingResponderLocation(
        requestId: String,
        responderLat: Double,
        responderLng: Double,
        state: HexRescueLifecycleState = HexRescueLifecycleState.EN_ROUTE
    ): Result<Unit> {
        val uid = currentUidOrNull() ?: return Result.failure(IllegalStateException("Not signed in"))
        val cleanId = requestId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val path = "hex_rescue_requests/$cleanId"
        return try {
            val safeState = if (state == HexRescueLifecycleState.ARRIVED || state == HexRescueLifecycleState.COMPLETED) {
                state
            } else {
                HexRescueLifecycleState.EN_ROUTE
            }
            val updates = mapOf<String, Any>(
                "acceptedResponderUid" to uid,
                "lifecycleState" to safeState.name,
                "acceptedResponderLat" to responderLat,
                "acceptedResponderLng" to responderLng,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("hex_rescue_requests").document(cleanId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeActiveHexRescueRequests(): Flow<List<HexRescueAlert>> = flow {
        val currentUid = currentUidOrNull()
        if (currentUid == null) {
            emit(emptyList())
            return@flow
        }
        val path = "hex_rescue_requests"
        val query = db.collection(path).whereIn(
            "lifecycleState",
            listOf(
                HexRescueLifecycleState.REQUEST_CREATED.name,
                HexRescueLifecycleState.RESPONDERS_DISCOVERED.name,
                HexRescueLifecycleState.REQUEST_BROADCAST.name,
                HexRescueLifecycleState.RESPONDER_ACCEPTED.name,
                HexRescueLifecycleState.EN_ROUTE.name,
                HexRescueLifecycleState.ARRIVED.name
            )
        )
        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        try {
                            val item = doc.toObject(
                                CloudHexRescueRequest::class.java,
                                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                            ) ?: return@mapNotNull null
                            val lifecycle = HexRescueLifecycleState.safeParse(item.lifecycleState)
                            val mappedStatus = when (lifecycle) {
                                HexRescueLifecycleState.RESPONDER_ACCEPTED -> HexAlertStatus.ACCEPTED
                                HexRescueLifecycleState.EN_ROUTE -> HexAlertStatus.EN_ROUTE
                                HexRescueLifecycleState.ARRIVED -> HexAlertStatus.ARRIVED
                                HexRescueLifecycleState.COMPLETED -> HexAlertStatus.RESOLVED
                                HexRescueLifecycleState.EXPIRED, HexRescueLifecycleState.CANCELLED, HexRescueLifecycleState.FAILED -> HexAlertStatus.EXPIRED
                                else -> HexAlertStatus.BROADCASTING
                            }
                            val polStatus = PoliceEscalationStatus.entries.firstOrNull {
                                it.name == item.policeStatus
                            } ?: PoliceEscalationStatus.ELIGIBLE
                            HexRescueAlert(
                                alertId = item.alertId.ifBlank { item.id },
                                incidentId = item.incidentId,
                                victimUid = item.victimUid,
                                victimName = item.victimName.ifBlank { "Citizen in Distress" },
                                victimLatitude = item.latitude,
                                victimLongitude = item.longitude,
                                currentRadiusMeters = item.currentRadiusMeters.toFloat(),
                                expansionStage = item.expansionStage,
                                secondsRemainingInStage = 30,
                                dangerType = item.dangerType,
                                timestamp = item.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                                status = mappedStatus,
                                lifecycleState = lifecycle,
                                compassSector = item.compassSector.ifBlank { "N-NE" },
                                sourceChannel = "FIRESTORE",
                                evidenceSummary = item.evidenceSummary,
                                victimLocationDescription = item.locationDescription,
                                policeDispatched = polStatus == PoliceEscalationStatus.INTENT_LAUNCHED ||
                                    polStatus == PoliceEscalationStatus.ACKNOWLEDGED,
                                policeStatus = polStatus,
                                acceptedResponderUid = item.acceptedResponderUid,
                                acceptedResponderName = item.acceptedResponderName,
                                acceptedResponderPhone = item.acceptedResponderPhone,
                                acceptedResponderSelfieB64 = item.acceptedResponderSelfieB64,
                                acceptedResponderLat = item.acceptedResponderLat,
                                acceptedResponderLng = item.acceptedResponderLng,
                                isOutgoingFromSelf = item.victimUid == currentUid
                            )
                        } catch (e: Exception) {
                            Log.w("FirestoreRiskRepo", "Skipping unparseable hex alert ${doc.id}: ${e.message}")
                            null
                        }
                    }.sortedByDescending { it.timestamp }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w("FirestoreRiskRepo", "Hex rescue stream error: ${error.message}")
                    emit(emptyList())
                }
        )
    }

    suspend fun fetchDossierByToken(tokenId: String): PoliceDispatchDossier? {
        val cleanTok = tokenId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        if (cleanTok.isBlank()) return null
        return try {
            val snap = db.collection("dossier_access").document(cleanTok).get().await()
            if (!snap.exists()) return null
            val incidentId = snap.getString("incidentId") ?: return null
            val lat = snap.getDouble("latitude") ?: 0.0
            val lng = snap.getDouble("longitude") ?: 0.0
            val polStatusStr = snap.getString("policeStatus") ?: PoliceEscalationStatus.ELIGIBLE.name
            val polStatus = PoliceEscalationStatus.entries.firstOrNull { it.name == polStatusStr }
                ?: PoliceEscalationStatus.ELIGIBLE
            PoliceDispatchDossier(
                dispatchId = "DISPATCH-$incidentId",
                incidentId = incidentId,
                timestamp = System.currentTimeMillis(),
                riskLevel = RiskLevel.R3_CRITICAL,
                victimName = snap.getString("victimName").orEmpty().ifBlank { "Citizen in Distress" },
                victimPhone = snap.getString("victimPhone").orEmpty(),
                emergencyNumber = "112",
                latitude = lat,
                longitude = lng,
                accuracyMeters = (snap.getDouble("accuracyMeters") ?: 0.0).toFloat(),
                localityAddress = snap.getString("localityAddress").orEmpty(),
                mapsUrl = snap.getString("mapsUrl").orEmpty().ifBlank { "https://maps.google.com/?q=$lat,$lng" },
                webPortalUrl = "rakshaone://dispatch/$incidentId?token=$cleanTok",
                dossierToken = cleanTok,
                expiresAtMillis = snap.getLong("expiresAtMillis") ?: 0L,
                medicalSummary = snap.getString("medicalSummary").orEmpty(),
                batteryPercent = snap.getLong("batteryPercent")?.toInt() ?: 100,
                speedKmh = (snap.getDouble("speedKmh") ?: 0.0).toFloat(),
                evidenceHashesSummary = snap.getString("evidenceSummary").orEmpty(),
                nearestPoliceStation = "Regional Police Control Room / ERSS 112",
                policeStatus = polStatus,
                deliveryState = ReceiptDeliveryState.PENDING,
                isTransmitted = false,
                dispatchChannel = "112_INTENT_DIALER_SMS",
                responderName = snap.getString("responderName"),
                responderPhone = snap.getString("responderPhone"),
                responderStatus = snap.getString("responderStatus"),
                responderLat = snap.getDouble("responderLat"),
                responderLng = snap.getDouble("responderLng"),
                responderDistanceMeters = snap.getDouble("responderDistanceMeters")?.toFloat(),
                responderEtaMinutes = snap.getLong("responderEtaMinutes")?.toInt()
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Frees Firestore database space once the victim confirms case resolution:
     * deletes temporary dispatch payloads, hex rescue requests, and tokenized dossier snapshots.
     */
    suspend fun purgeIncidentCloudArtifacts(
        incidentId: String,
        hexRequestId: String? = null,
        dossierToken: String? = null
    ) {
        val uid = currentUidOrNull() ?: return
        val cleanInc = incidentId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        try {
            db.collection("emergency_dispatches").document(cleanInc).delete().await()
        } catch (_: Exception) {}

        if (!dossierToken.isNullOrBlank()) {
            val cleanTok = dossierToken.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
            try {
                db.collection("dossier_access").document(cleanTok).delete().await()
            } catch (_: Exception) {}
        }

        if (!hexRequestId.isNullOrBlank()) {
            val cleanHex = hexRequestId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
            try {
                db.collection("hex_rescue_requests").document(cleanHex).delete().await()
            } catch (_: Exception) {}
        } else {
            try {
                val matchingHex = db.collection("hex_rescue_requests")
                    .whereEqualTo("victimUid", uid)
                    .whereEqualTo("incidentId", cleanInc)
                    .get()
                    .await()
                for (doc in matchingHex.documents) {
                    try {
                        doc.reference.delete().await()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }
}

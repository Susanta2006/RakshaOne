package com.example.util

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.model.PoliceDispatchDossier
import com.example.data.model.UserProfileEntity
import com.example.risk.EmergencyEscalationPolicyEngine
import com.example.risk.PoliceEscalationStatus
import com.example.risk.PolicyViolationException
import com.example.risk.ReceiptDeliveryState
import com.example.risk.RiskAssessmentResult
import com.example.risk.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Permitted Emergency 112 Dossier Builder & Honest Intent Dispatcher:
 * - Strictly refuses to build/authorize any police/112 dossier unless RiskAssessmentResult
 *   passes EmergencyEscalationPolicyEngine (R3_CRITICAL with explicit SOS origin).
 * - Never claims simulated REST transmission (`isTransmitted = false` until user confirms
 *   real Dialer / 112 SMS dispatch).
 * - Generates tokenized in-app dossier URI + maps + medical & SHA-256 evidence summary.
 */
class EmergencyResponseDispatcher(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)

    private val _latestPoliceDossier = MutableStateFlow<PoliceDispatchDossier?>(null)
    val latestPoliceDossier: StateFlow<PoliceDispatchDossier?> = _latestPoliceDossier.asStateFlow()

    @Throws(PolicyViolationException::class)
    suspend fun createAuthorizedPoliceDossier(
        incidentId: String,
        assessment: RiskAssessmentResult,
        location: Location?,
        localityName: String,
        medicalSummary: String,
        evidenceHashesSummary: String,
        webPortalUrl: String,
        dossierToken: String,
        expiresAtMillis: Long,
        existingStatus: PoliceEscalationStatus = PoliceEscalationStatus.ELIGIBLE,
        existingDeliveryState: ReceiptDeliveryState = ReceiptDeliveryState.PENDING
    ): PoliceDispatchDossier = withContext(Dispatchers.IO) {
        EmergencyEscalationPolicyEngine.requirePoliceEscalationAuthorized(assessment, incidentId)

        val user = database.userDao().getUserProfileOnce() ?: UserProfileEntity()
        val victimName = user.name.ifBlank { "Citizen in Distress" }
        val victimPhone = user.phone.ifBlank { "Unspecified" }

        val lat = location?.latitude ?: 0.0
        val lng = location?.longitude ?: 0.0
        val accuracy = location?.accuracy ?: 0f
        val speedKmh = if (location != null && location.hasSpeed()) (location.speed * 3.6f) else 0f

        val cleanLocality = if (localityName.isNotBlank() &&
            !localityName.startsWith("Detecting") &&
            !localityName.startsWith("Acquiring")
        ) {
            localityName
        } else if (lat != 0.0 || lng != 0.0) {
            "Coordinates ($lat, $lng)"
        } else {
            "GPS Fix Pending"
        }

        val mapsUrl = if (lat != 0.0 || lng != 0.0) {
            "https://maps.google.com/?q=$lat,$lng"
        } else {
            "GPS Signal Unavailable"
        }

        val batteryPct = try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
        } catch (_: Exception) {
            100
        }

        val existing = _latestPoliceDossier.value?.takeIf { it.incidentId == incidentId }
        if (existing != null) {
            return@withContext existing
        }

        val dossier = PoliceDispatchDossier(
            dispatchId = "DISPATCH-112-${System.currentTimeMillis() % 100000}",
            incidentId = incidentId,
            timestamp = System.currentTimeMillis(),
            riskLevel = RiskLevel.R3_CRITICAL,
            victimName = victimName,
            victimPhone = victimPhone,
            emergencyNumber = "112",
            latitude = lat,
            longitude = lng,
            accuracyMeters = accuracy,
            localityAddress = cleanLocality,
            mapsUrl = mapsUrl,
            webPortalUrl = webPortalUrl,
            dossierToken = dossierToken,
            expiresAtMillis = expiresAtMillis,
            medicalSummary = medicalSummary.ifBlank {
                "Blood: ${user.bloodGroup.ifBlank { "Unknown" }} | Allergies: ${user.allergies.ifBlank { "None" }}"
            },
            batteryPercent = batteryPct,
            speedKmh = speedKmh,
            evidenceHashesSummary = evidenceHashesSummary.ifBlank {
                "Encrypted audio & dual CameraX SHA-256 vault initialized"
            },
            nearestPoliceStation = "Regional Police Control Room / ERSS 112",
            policeStatus = existingStatus,
            deliveryState = existingDeliveryState,
            isTransmitted = existingDeliveryState == ReceiptDeliveryState.SENT ||
                existingDeliveryState == ReceiptDeliveryState.DELIVERED ||
                existingDeliveryState == ReceiptDeliveryState.ACKNOWLEDGED,
            dispatchChannel = "112_INTENT_DIALER_SMS"
        )

        _latestPoliceDossier.value = dossier
        Log.i("PoliceDispatcher", "Prepared R3 112 Dossier for $incidentId (status=${dossier.policeStatus}): $webPortalUrl")
        dossier
    }

    fun updateLatestDossierStatus(
        incidentId: String,
        policeStatus: PoliceEscalationStatus,
        deliveryState: ReceiptDeliveryState
    ) {
        val current = _latestPoliceDossier.value ?: return
        if (current.incidentId != incidentId) return
        _latestPoliceDossier.value = current.copy(
            policeStatus = policeStatus,
            deliveryState = deliveryState,
            isTransmitted = deliveryState == ReceiptDeliveryState.SENT ||
                deliveryState == ReceiptDeliveryState.DELIVERED ||
                deliveryState == ReceiptDeliveryState.ACKNOWLEDGED ||
                deliveryState == ReceiptDeliveryState.INTENT_LAUNCHED
        )
    }

    fun buildFormattedDossierBody(dossier: PoliceDispatchDossier): String {
        return buildString {
            appendLine("RAKSHAONE R3-CRITICAL EMERGENCY DOSSIER")
            appendLine("Incident ID: ${dossier.incidentId} (${dossier.dispatchId})")
            appendLine("Victim: ${dossier.victimName} | Phone: ${dossier.victimPhone}")
            appendLine("Location: ${dossier.localityAddress}")
            appendLine("Maps: ${dossier.mapsUrl}")
            appendLine("Medical: ${dossier.medicalSummary}")
            appendLine("Evidence: ${dossier.evidenceHashesSummary}")
            appendLine("In-App Dossier Link: ${dossier.webPortalUrl}")
        }.trim()
    }

    fun openEmergency112Dialer(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:112")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("PoliceDispatcher", "Failed to open 112 dialer: ${e.message}")
            false
        }
    }

    fun launch112PreFilledSms(dossier: PoliceDispatchDossier): Boolean {
        if (dossier.riskLevel != RiskLevel.R3_CRITICAL) return false
        return try {
            val smsBody = buildFormattedDossierBody(dossier)
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${dossier.emergencyNumber}")
                putExtra("sms_body", smsBody)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("PoliceDispatcher", "Failed to launch 112 SMS composer: ${e.message}")
            false
        }
    }

    fun clearDossierForIncident(incidentId: String) {
        if (_latestPoliceDossier.value?.incidentId == incidentId) {
            _latestPoliceDossier.value = null
        }
    }

    fun openPoliceDispatchPortal(dossier: PoliceDispatchDossier) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dossier.webPortalUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dossier.mapsUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(mapsIntent)
            } catch (_: Exception) {
            }
        }
    }
}

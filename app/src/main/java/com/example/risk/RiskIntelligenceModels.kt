package com.example.risk

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Four-tier RakshaOne Risk Classification Model.
 * Policy Invariant: ONLY R3_CRITICAL may initiate police / 112 escalation.
 */
enum class RiskLevel(val code: String, val label: String) {
    R0_NORMAL("R0", "R0 — NORMAL"),
    R1_ATTENTION("R1", "R1 — ATTENTION"),
    R2_HIGH("R2", "R2 — HIGH"),
    R3_CRITICAL("R3", "R3 — CRITICAL");

    companion object {
        fun fromCodeOrName(raw: String?): RiskLevel {
            if (raw.isNullOrBlank()) return R0_NORMAL
            return entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) || it.code.equals(raw, ignoreCase = true)
            } ?: R0_NORMAL
        }
    }
}

enum class TriggerOrigin {
    NONE,
    TRAVEL_BUMP_OBSERVED,
    STATIONARY_ANOMALY_CHECK,
    GEOFENCE_ADVISORY,
    ROUTE_DEVIATION_ADVISORY,
    USER_EXPLICIT_SOS_COUNTDOWN,
    USER_EXPLICIT_INSTANT_SOS,
    USER_EXPLICIT_DURESS_PIN
}

enum class LocationFreshness {
    LIVE,        // <= 30s old, usable accuracy
    RECENT,      // 31s .. 180s
    STALE,       // 181s .. 900s
    LAST_KNOWN,  // > 900s
    UNAVAILABLE  // null / no fix
}

enum class PoliceEscalationStatus {
    NOT_ELIGIBLE,
    ELIGIBLE,
    AWAITING_USER_ACTION,
    ATTEMPTED,
    INTENT_LAUNCHED,
    ACKNOWLEDGED,
    FAILED,
    CLOSED
}

enum class ReceiptDeliveryState {
    PENDING,
    INTENT_LAUNCHED,
    SENT,
    DELIVERED,
    ACKNOWLEDGED,
    FAILED
}

enum class CitizenVerificationStatus {
    UNVERIFIED,
    PENDING,
    VERIFIED,
    SUSPENDED,
    REVOKED;

    companion object {
        fun safeParse(raw: String?): CitizenVerificationStatus {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNVERIFIED
        }
    }
}

enum class HexRescueLifecycleState {
    REQUEST_CREATED,
    RESPONDERS_DISCOVERED,
    REQUEST_BROADCAST,
    RESPONDER_ACCEPTED,
    EN_ROUTE,
    ARRIVED,
    COMPLETED,
    EXPIRED,
    CANCELLED,
    FAILED;

    companion object {
        fun safeParse(raw: String?): HexRescueLifecycleState {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: REQUEST_CREATED
        }
    }
}

@Keep
data class LocationIntelligenceSnapshot(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float = 0f,
    val speedKmh: Float = 0f,
    val capturedAtMillis: Long = 0L,
    val ageSeconds: Long = Long.MAX_VALUE,
    val freshness: LocationFreshness = LocationFreshness.UNAVAILABLE,
    val batteryPercent: Int = 100,
    val isLowBatteryPreSync: Boolean = false
) {
    val hasCoordinates: Boolean
        get() = latitude != null && longitude != null && (latitude != 0.0 || longitude != 0.0)
}

/**
 * Typed telemetry & explicit trigger observations entering Subsystem 1 (Perception Engine).
 */
@Keep
data class PerceptionObservation(
    val observationId: String = "OBS-${System.currentTimeMillis()}",
    val timestampMillis: Long = System.currentTimeMillis(),
    val locationSnapshot: LocationIntelligenceSnapshot = LocationIntelligenceSnapshot(),
    val dynamicJerkG: Float = 0f,
    val sustainedTravelSpeedKmh: Float = 0f,
    val inHighRiskZone: Boolean = false,
    val highRiskZoneName: String? = null,
    val routeCorridorBreached: Boolean = false,
    val prolongedStopSeconds: Long = 0L,
    val isNetworkOnline: Boolean = true,
    val explicitSosButtonClicked: Boolean = false,
    val explicitInstantSosClicked: Boolean = false,
    val explicitDuressPinEntered: Boolean = false,
    val travelBumpFilterEnabled: Boolean = true
)

@Keep
data class ContextAssessment(
    val isTravelingInVehicle: Boolean,
    val isTravelBumpSuppressed: Boolean,
    val isObservabilityDegraded: Boolean,
    val observabilityNotes: List<String>,
    val contextSummary: String
)

@Keep
data class PolicyActionFlags(
    val policeEscalationEligible: Boolean,
    val notifyTrustedContactsSmsWhatsApp: Boolean,
    val enableTrustedMediaSharing: Boolean,
    val enableBluetoothHexBroadcast: Boolean,
    val enableFirestoreHexBroadcast: Boolean,
    val enableEvidenceCapture: Boolean,
    val requireUser112Interaction: Boolean
)

@Keep
data class RiskAssessmentResult(
    val assessmentId: String,
    val incidentId: String? = null,
    val riskLevel: RiskLevel,
    val triggerOrigin: TriggerOrigin,
    val confidence: Float,
    val reasonCodes: List<String>,
    val explanation: String,
    val preventiveGuidance: String,
    val context: ContextAssessment,
    val actionFlags: PolicyActionFlags,
    val evaluatedAtMillis: Long = System.currentTimeMillis(),
    val policyVersion: String = "2026.1-EXPLICIT-SOS-R3"
) {
    val policeEscalationEligible: Boolean
        get() = riskLevel == RiskLevel.R3_CRITICAL && actionFlags.policeEscalationEligible

    fun toJsonString(): String {
        val reasons = JSONArray()
        reasonCodes.forEach { reasons.put(it) }
        val obsNotes = JSONArray()
        context.observabilityNotes.forEach { obsNotes.put(it) }

        return JSONObject().apply {
            put("assessmentId", assessmentId)
            put("incidentId", incidentId ?: "")
            put("riskLevel", riskLevel.name)
            put("triggerOrigin", triggerOrigin.name)
            put("confidence", confidence.toDouble())
            put("policeEscalationEligible", policeEscalationEligible)
            put("reasonCodes", reasons)
            put("explanation", explanation)
            put("preventiveGuidance", preventiveGuidance)
            put("isTravelingInVehicle", context.isTravelingInVehicle)
            put("isTravelBumpSuppressed", context.isTravelBumpSuppressed)
            put("isObservabilityDegraded", context.isObservabilityDegraded)
            put("observabilityNotes", obsNotes)
            put("notifyTrustedContacts", actionFlags.notifyTrustedContactsSmsWhatsApp)
            put("enableBluetoothHexBroadcast", actionFlags.enableBluetoothHexBroadcast)
            put("evaluatedAtMillis", evaluatedAtMillis)
            put("policyVersion", policyVersion)
        }.toString()
    }
}

class PolicyViolationException(message: String) : SecurityException(message)

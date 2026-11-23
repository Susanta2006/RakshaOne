package com.example.data.model

import androidx.annotation.Keep
import com.example.risk.CitizenVerificationStatus
import com.example.risk.HexRescueLifecycleState
import com.example.risk.PoliceEscalationStatus
import com.example.risk.ReceiptDeliveryState
import com.example.risk.RiskLevel

@Keep
data class GoodCitizenProfile(
    val userId: String = "USER-001",
    val displayName: String = "",
    val isIdVerified: Boolean = false,
    val isPhoneVerified: Boolean = false,
    val workEmailBadge: String = "",
    val linkedinBadge: String = "",
    val verificationStatus: CitizenVerificationStatus = CitizenVerificationStatus.UNVERIFIED,
    val verificationDocType: String = "",
    val docLastFourHint: String = "",
    val trustScore: Int = 0, // Zero-start, ledger-backed (0 - 1000+)
    val acceptedHelpsCount: Int = 0,
    val completedHelpsCount: Int = 0,
    val declinedHelpsCount: Int = 0,
    val isVolunteerActive: Boolean = false,
    val badgeLevel: String = "Unverified Citizen",
    val volunteerRadiusMeters: Float = 2500f,
    val endorserUid: String? = null,
    val endorsementCode: String = ""
) {
    val isEligibleForHexRescue: Boolean
        get() = (verificationStatus == CitizenVerificationStatus.VERIFIED || isIdVerified) &&
            isVolunteerActive
}

enum class HexAlertStatus {
    BROADCASTING,
    ACCEPTED,
    EN_ROUTE,
    ARRIVED,
    DECLINED,
    RESOLVED,
    EXPIRED
}

@Keep
data class HexRescueAlert(
    val alertId: String,
    val incidentId: String,
    val victimUid: String = "",
    val victimName: String,
    val victimLatitude: Double,
    val victimLongitude: Double,
    val currentRadiusMeters: Float = 750f,
    val expansionStage: Int = 1, // 1 = 750m, 2 = 1500m, 3 = 3000m, 4 = 5000m
    val secondsRemainingInStage: Int = 30,
    val dangerType: String = "R3 CRITICAL SOS",
    val timestamp: Long = System.currentTimeMillis(),
    val status: HexAlertStatus = HexAlertStatus.BROADCASTING,
    val lifecycleState: HexRescueLifecycleState = HexRescueLifecycleState.REQUEST_BROADCAST,
    val compassSector: String = "N-NE",
    val sourceChannel: String = "FIRESTORE", // "FIRESTORE" | "BLE" | "FIRESTORE+BLE"
    val evidenceSummary: String = "",
    val victimLocationDescription: String = "",
    val frontCameraThumbnailB64: String = "",
    val distanceMeters: Float = 0f,
    val estimatedWalkMinutes: Int = 1,
    val policeDispatched: Boolean = false,
    val policeStatus: PoliceEscalationStatus = PoliceEscalationStatus.ELIGIBLE,
    val acceptedResponderUid: String? = null,
    val acceptedResponderName: String? = null,
    val acceptedResponderPhone: String? = null,
    val acceptedResponderSelfieB64: String? = null,
    val acceptedResponderLat: Double? = null,
    val acceptedResponderLng: Double? = null,
    val isOutgoingFromSelf: Boolean = false
)

@Keep
data class PoliceDispatchDossier(
    val dispatchId: String,
    val incidentId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val riskLevel: RiskLevel = RiskLevel.R3_CRITICAL,
    val victimName: String,
    val victimPhone: String,
    val emergencyNumber: String = "112",
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val localityAddress: String,
    val mapsUrl: String,
    val webPortalUrl: String,
    val dossierToken: String = "",
    val expiresAtMillis: Long = 0L,
    val medicalSummary: String,
    val batteryPercent: Int,
    val speedKmh: Float,
    val evidenceHashesSummary: String,
    val nearestPoliceStation: String,
    val policeStatus: PoliceEscalationStatus = PoliceEscalationStatus.ELIGIBLE,
    val deliveryState: ReceiptDeliveryState = ReceiptDeliveryState.PENDING,
    val isTransmitted: Boolean = false,
    val dispatchChannel: String = "112_INTENT_DIALER_SMS_EMAIL",
    val responderName: String? = null,
    val responderPhone: String? = null,
    val responderStatus: String? = null,
    val responderLat: Double? = null,
    val responderLng: Double? = null,
    val responderDistanceMeters: Float? = null,
    val responderEtaMinutes: Int? = null
)


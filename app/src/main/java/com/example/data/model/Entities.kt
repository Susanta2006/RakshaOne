package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String = "USER-001",
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val language: String = "English",
    val bloodGroup: String = "",
    val allergies: String = "",
    val medicalNotes: String = "",
    val homeAddress: String = "",
    val workAddress: String = "",
    val autoSendSmsOnSos: Boolean = true,
    val sirenBeaconEnabled: Boolean = true,
    val silentSosMode: Boolean = false,
    val antiSnatchEnabled: Boolean = true,
    val autoLockOnSos: Boolean = false
)

object RelationshipTrustRules {
    val CORE_FAMILY_ROLES = setOf("father", "mother", "brother", "sister", "spouse", "spouce")
    val EXTENDED_CIRCLE_ROLES = setOf("relative", "friend", "colleague", "guardian", "other")

    fun normalizeRole(relationship: String): String {
        val clean = relationship.trim().lowercase(java.util.Locale.US)
        return if (clean == "spouce") "spouse" else clean
    }

    fun isCoreFamily(relationship: String): Boolean {
        return normalizeRole(relationship) in CORE_FAMILY_ROLES
    }

    fun displayRole(relationship: String): String {
        val clean = relationship.trim()
        if (clean.equals("spouce", ignoreCase = true)) return "Spouse"
        return clean.ifBlank { "Friend" }
    }
}

@Entity(tableName = "trusted_contacts")
data class TrustedContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relationship: String,
    val phone: String,
    val email: String = "",
    val priority: Int, // 1 = Highest, 2 = Secondary, 3 = Friend
    val isEnabled: Boolean = true,
    val hasWhatsApp: Boolean = true,
    val allow24x7Location: Boolean = false,
    val isMutuallyAccepted: Boolean = true
) {
    fun isCoreFamily(): Boolean = RelationshipTrustRules.isCoreFamily(relationship)

    fun hasAlwaysOnLocationPermission(): Boolean = isEnabled && (isCoreFamily() || allow24x7Location)
}

@Entity(tableName = "offline_emergency_alerts")
data class OfflineAlertEntity(
    @PrimaryKey val alertId: String,
    val incidentId: String,
    val alertType: String, // "SOS", "INCIDENT_REPORT", "TRIP_HELP", "GPS_BROADCAST"
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 10f,
    val messageText: String,
    val recipientPhones: String = "", // semicolon separated
    val recipientEmails: String = "", // semicolon separated
    val isDispatched: Boolean = false,
    val dispatchAttempts: Int = 0,
    val lastAttemptTime: Long = 0L,
    val status: String = "PENDING_CONNECTIVITY" // "PENDING_CONNECTIVITY", "DISPATCHED", "FAILED"
)

@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey val incidentId: String, // e.g. "INC-2026-000123"
    val userId: String = "USER-001",
    val type: String, // "SOS", "HARASSMENT", "THEFT", "ACCIDENT", "SUSPICIOUS", "VIOLENCE", "MEDICAL", "FIRE", "OTHER"
    val priority: String, // "CRITICAL", "HIGH", "MEDIUM", "LOW"
    val status: String, // "CREATED", "ACKNOWLEDGED", "ASSIGNED", "RESPONDING", "ON_SCENE", "RESOLVED", "CLOSED", "CANCELLED"
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 12f,
    val locationDescription: String = "",
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val assignedUnit: String? = null, // e.g. "PCR-102", "AMB-04", "QRT-01"
    val operatorNotes: String? = null,
    val videoEvidencePath: String? = null,
    val isSynced: Boolean = true
)

@Entity(tableName = "incident_events")
data class IncidentEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val incidentId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val oldStatus: String?,
    val newStatus: String,
    val actor: String, // "Citizen", "Authorized Operator", "System"
    val note: String
)

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val tripId: String,
    val origin: String,
    val destination: String,
    val plannedDurationMinutes: Int,
    val checkInIntervalMinutes: Int,
    val status: String, // "PLANNED", "STARTED", "MONITORING", "ARRIVED", "CANCELLED", "EXPIRED"
    val startTime: Long,
    val lastCheckInTime: Long,
    val nextCheckInDueTime: Long,
    val notes: String = "",
    val currentLatitude: Double = 0.0,
    val currentLongitude: Double = 0.0,
    val speedKmh: Float = 0f,
    val sharedContactPhones: String = ""
)

@Entity(tableName = "trip_waypoints")
data class TripWaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 0f,
    val speedKmh: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "evidence")
data class EvidenceEntity(
    @PrimaryKey val evidenceId: String,
    val incidentId: String,
    val userId: String = "USER-001",
    val fileType: String, // "IMAGE", "AUDIO", "LOCATION_LOG", "REPORT_TEXT"
    val title: String,
    val fileSizeBytes: Long,
    val sha256Hash: String,
    val uploadTime: Long = System.currentTimeMillis(),
    val storageLocation: String = "Encrypted Local Vault / S3-Sync",
    val previewText: String = ""
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val actor: String,
    val role: String, // "ADMIN", "OPERATOR", "DISPATCHER", "ANALYST", "AUDITOR"
    val action: String,
    val targetId: String,
    val details: String
)

@Entity(tableName = "safety_guides")
data class SafetyGuideEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String, // "Personal Safety", "First Aid", "Disaster", "Legal Rights"
    val description: String,
    val steps: String, // Semicolon-separated guidance points
    val emergencyDialNumber: String = "112"
)

data class NearbyFacility(
    val name: String,
    val type: String, // "Police", "Hospital", "Fire"
    val distanceKm: Double,
    val phone: String,
    val address: String,
    val isOpen24x7: Boolean = true
)

@Entity(tableName = "high_risk_zones")
data class HighRiskZoneEntity(
    @PrimaryKey val zoneId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 300f,
    val riskLevel: String = "HIGH", // "HIGH", "CRITICAL", "MODERATE"
    val advisoryMessage: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_routines")
data class SavedRoutineEntity(
    @PrimaryKey val routineId: String,
    val name: String,
    val originName: String,
    val destinationName: String,
    val waypointsJson: String, // format: "lat,lng;lat,lng;lat,lng"
    val maxDeviationMeters: Float = 250f,
    val isMonitoringActive: Boolean = false,
    val lastKnownDeviationMeters: Float = 0f,
    val lastCheckTimestamp: Long = 0L
)

data class LocationEmergencyInfo(
    val countryCode: String = "IN",
    val countryName: String = "India",
    val adminArea: String = "",
    val locality: String = "Detecting Location...",
    val addressLine: String = "",
    val primarySosNumber: String = "112",
    val policeNumber: String = "112",
    val ambulanceNumber: String = "108",
    val fireNumber: String = "101",
    val womenHelpline: String = "1091",
    val authorityName: String = "Emergency Response Support System (ERSS 112)",
    val isRealGpsFix: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

data class EncryptedAudioMeta(
    val evidenceId: String,
    val incidentId: String,
    val filePath: String,
    val durationSeconds: Int,
    val sha256Hash: String,
    val fileSizeBytes: Long,
    val recordedAt: Long = System.currentTimeMillis(),
    val isEncrypted: Boolean = true
)

@Entity(tableName = "risk_assessments")
data class RiskAssessmentEntity(
    @PrimaryKey val assessmentId: String,
    val incidentId: String,
    val riskLevel: String,
    val triggerOrigin: String,
    val confidence: Float,
    val policeEscalationEligible: Boolean,
    val reasonCodesCsv: String,
    val explanation: String,
    val travelBumpSuppressed: Boolean = false,
    val observabilityDegraded: Boolean = false,
    val evaluatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "escalation_workflows")
data class EscalationWorkflowEntity(
    @PrimaryKey val incidentId: String,
    val riskLevel: String,
    val policeEscalationEligible: Boolean,
    val policeStatus: String,
    val deliveryState: String,
    val trustedSmsDispatched: Boolean = false,
    val trustedWhatsAppDispatched: Boolean = false,
    val bleBroadcastStarted: Boolean = false,
    val hexRequestId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "points_ledger")
data class PointsLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val citizenUid: String,
    val delta: Int,
    val balanceAfter: Int,
    val reasonCode: String,
    val relatedIncidentId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "dossier_access")
data class DossierAccessEntity(
    @PrimaryKey val tokenId: String,
    val incidentId: String,
    val dossierJson: String,
    val webUrl: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val isRevoked: Boolean = false
)

@Entity(tableName = "location_breadcrumbs")
data class LocationBreadcrumbEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 0f,
    val batteryPercent: Int = 100,
    val geohash: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val incidentId: String? = null
)

@Entity(tableName = "learned_places")
data class LearnedPlaceEntity(
    @PrimaryKey val placeId: String,
    val label: String, // e.g., "HOME", "WORK", "SAFE_CORRIDOR"
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 200f,
    val visitCount: Int = 1,
    val typicalHourStart: Int = 0,
    val typicalHourEnd: Int = 23,
    val lastVisitedAt: Long = System.currentTimeMillis()
)

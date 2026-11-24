package com.example.data.model

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.repository.CloudEmergencyDispatch
import com.example.risk.HexRescueLifecycleState
import com.example.risk.PoliceEscalationStatus

/**
 * Local Room Entity for caching incoming emergency alerts and dispatches.
 * Enables zero-connectivity offline operation, instant responder navigation,
 * and high-durability rescue awareness during complete network outages.
 */
@Keep
@Entity(tableName = "cached_incoming_alerts")
data class CachedIncomingAlertEntity(
    @PrimaryKey val alertId: String,
    val incidentId: String,
    val alertType: String = "HEX_RESCUE", // "HEX_RESCUE" | "GUARDIAN_DISPATCH" | "BLE_BEACON"
    val victimUid: String = "",
    val victimName: String = "",
    val victimPhone: String = "",
    val victimLatitude: Double = 0.0,
    val victimLongitude: Double = 0.0,
    val currentRadiusMeters: Float = 750f,
    val expansionStage: Int = 1,
    val secondsRemainingInStage: Int = 30,
    val dangerType: String = "R3 CRITICAL SOS",
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "BROADCASTING",
    val lifecycleState: String = "REQUEST_BROADCAST",
    val compassSector: String = "N-NE",
    val sourceChannel: String = "FIRESTORE",
    val evidenceSummary: String = "",
    val victimLocationDescription: String = "",
    val frontCameraThumbnailB64: String = "",
    val rearPhotoBase64: String = "",
    val distanceMeters: Float = 0f,
    val estimatedWalkMinutes: Int = 1,
    val policeDispatched: Boolean = false,
    val policeStatus: String = "ELIGIBLE",
    val acceptedResponderUid: String? = null,
    val acceptedResponderName: String? = null,
    val acceptedResponderPhone: String? = null,
    val acceptedResponderSelfieB64: String? = null,
    val acceptedResponderLat: Double? = null,
    val acceptedResponderLng: Double? = null,
    val isOutgoingFromSelf: Boolean = false,
    val batteryPercent: Int = 80,
    val medicalSummary: String = "",
    val mapsUrl: String = "",
    val authorizedEmailsCsv: String = "",
    val authorizedPhonesCsv: String = "",
    val authorizedUidsCsv: String = "",
    val deviceLocked: Boolean = false,
    val cachedAtMillis: Long = System.currentTimeMillis()
)

fun HexRescueAlert.toCachedEntity(): CachedIncomingAlertEntity {
    return CachedIncomingAlertEntity(
        alertId = alertId,
        incidentId = incidentId,
        alertType = if (sourceChannel.contains("BLE")) "BLE_BEACON" else "HEX_RESCUE",
        victimUid = victimUid,
        victimName = victimName,
        victimLatitude = victimLatitude,
        victimLongitude = victimLongitude,
        currentRadiusMeters = currentRadiusMeters,
        expansionStage = expansionStage,
        secondsRemainingInStage = secondsRemainingInStage,
        dangerType = dangerType,
        timestamp = timestamp,
        status = status.name,
        lifecycleState = lifecycleState.name,
        compassSector = compassSector,
        sourceChannel = sourceChannel,
        evidenceSummary = evidenceSummary,
        victimLocationDescription = victimLocationDescription,
        frontCameraThumbnailB64 = frontCameraThumbnailB64,
        distanceMeters = distanceMeters,
        estimatedWalkMinutes = estimatedWalkMinutes,
        policeDispatched = policeDispatched,
        policeStatus = policeStatus.name,
        acceptedResponderUid = acceptedResponderUid,
        acceptedResponderName = acceptedResponderName,
        acceptedResponderPhone = acceptedResponderPhone,
        acceptedResponderSelfieB64 = acceptedResponderSelfieB64,
        acceptedResponderLat = acceptedResponderLat,
        acceptedResponderLng = acceptedResponderLng,
        isOutgoingFromSelf = isOutgoingFromSelf,
        cachedAtMillis = System.currentTimeMillis()
    )
}

fun CachedIncomingAlertEntity.toHexRescueAlert(): HexRescueAlert {
    val parsedStatus = try {
        HexAlertStatus.valueOf(status)
    } catch (_: Exception) {
        if (status == "ACTIVE" || status == "CAPTURED") HexAlertStatus.BROADCASTING else HexAlertStatus.BROADCASTING
    }
    val parsedLifecycle = try {
        HexRescueLifecycleState.valueOf(lifecycleState)
    } catch (_: Exception) {
        HexRescueLifecycleState.REQUEST_BROADCAST
    }
    val parsedPoliceStatus = try {
        PoliceEscalationStatus.valueOf(policeStatus)
    } catch (_: Exception) {
        PoliceEscalationStatus.ELIGIBLE
    }

    return HexRescueAlert(
        alertId = alertId,
        incidentId = incidentId,
        victimUid = victimUid,
        victimName = victimName.ifBlank { "Citizen in Need" },
        victimLatitude = victimLatitude,
        victimLongitude = victimLongitude,
        currentRadiusMeters = currentRadiusMeters,
        expansionStage = expansionStage,
        secondsRemainingInStage = secondsRemainingInStage,
        dangerType = dangerType,
        timestamp = timestamp,
        status = parsedStatus,
        lifecycleState = parsedLifecycle,
        compassSector = compassSector,
        sourceChannel = sourceChannel,
        evidenceSummary = evidenceSummary,
        victimLocationDescription = victimLocationDescription,
        frontCameraThumbnailB64 = frontCameraThumbnailB64,
        distanceMeters = distanceMeters,
        estimatedWalkMinutes = estimatedWalkMinutes,
        policeDispatched = policeDispatched,
        policeStatus = parsedPoliceStatus,
        acceptedResponderUid = acceptedResponderUid,
        acceptedResponderName = acceptedResponderName,
        acceptedResponderPhone = acceptedResponderPhone,
        acceptedResponderSelfieB64 = acceptedResponderSelfieB64,
        acceptedResponderLat = acceptedResponderLat,
        acceptedResponderLng = acceptedResponderLng,
        isOutgoingFromSelf = isOutgoingFromSelf
    )
}

fun CloudEmergencyDispatch.toCachedEntity(): CachedIncomingAlertEntity {
    return CachedIncomingAlertEntity(
        alertId = "GUARDIAN_${dispatchId.ifBlank { id }}",
        incidentId = dispatchId.ifBlank { id },
        alertType = "GUARDIAN_DISPATCH",
        victimUid = ownerUid,
        victimName = ownerName.ifBlank { "Trusted Circle Member" },
        victimPhone = ownerPhone,
        victimLatitude = latitude,
        victimLongitude = longitude,
        currentRadiusMeters = 5000f,
        dangerType = alertType,
        timestamp = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        status = status,
        lifecycleState = if (status in listOf("ACTIVE", "CAPTURED")) "REQUEST_BROADCAST" else "COMPLETED",
        compassSector = "N-NE",
        sourceChannel = "GUARDIAN_CLOUD",
        evidenceSummary = evidenceSummary,
        victimLocationDescription = locationSummary,
        frontCameraThumbnailB64 = frontPhotoBase64,
        rearPhotoBase64 = rearPhotoBase64,
        batteryPercent = batteryPercent,
        medicalSummary = medicalSummary,
        mapsUrl = mapsUrl,
        authorizedEmailsCsv = authorizedEmails.joinToString(";"),
        authorizedPhonesCsv = authorizedPhones.joinToString(";"),
        authorizedUidsCsv = authorizedUids.joinToString(";"),
        deviceLocked = deviceLocked,
        cachedAtMillis = System.currentTimeMillis()
    )
}

fun CachedIncomingAlertEntity.toCloudEmergencyDispatch(): CloudEmergencyDispatch {
    return CloudEmergencyDispatch(
        id = incidentId,
        dispatchId = incidentId,
        ownerUid = victimUid,
        ownerName = victimName,
        ownerPhone = victimPhone,
        alertType = dangerType,
        status = status,
        latitude = victimLatitude,
        longitude = victimLongitude,
        accuracyMeters = 10.0,
        mapsUrl = mapsUrl,
        locationSummary = victimLocationDescription,
        batteryPercent = batteryPercent,
        medicalSummary = medicalSummary,
        evidenceSummary = evidenceSummary,
        frontPhotoBase64 = frontCameraThumbnailB64,
        rearPhotoBase64 = rearPhotoBase64,
        authorizedEmails = if (authorizedEmailsCsv.isNotBlank()) authorizedEmailsCsv.split(";").filter { it.isNotBlank() } else emptyList(),
        authorizedPhones = if (authorizedPhonesCsv.isNotBlank()) authorizedPhonesCsv.split(";").filter { it.isNotBlank() } else emptyList(),
        authorizedUids = if (authorizedUidsCsv.isNotBlank()) authorizedUidsCsv.split(";").filter { it.isNotBlank() } else emptyList(),
        deviceLocked = deviceLocked
    )
}

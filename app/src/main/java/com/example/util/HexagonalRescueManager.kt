package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.HexAlertStatus
import com.example.data.model.HexRescueAlert
import com.example.data.repository.FirestoreRiskAndRescueRepository
import com.example.risk.HexRescueLifecycleState
import com.example.risk.PoliceEscalationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real Six-Directional Hexagonal Geo-Fenced Rescue Dispatch:
 * - Broadcaster: Explicit R3 SOS victims emit an expanding hexagonal perimeter
 *   (750m -> 1500m -> 3000m -> 5000m every 30s) over Firestore + Bluetooth LE.
 * - Responder: Only VERIFIED citizens with volunteer duty ON receive nearby Help Requests
 *   on their device & Dispatch Screen with Uber-style Accept -> En Route -> Arrived -> Complete flow.
 * - NO mock or simulated alerts.
 */
class HexagonalRescueManager(
    private val context: Context,
    private val goodCitizenManager: GoodCitizenManager,
    private val bleEmergencyRelay: BleEmergencyRelay? = null,
    private val firestoreRepository: FirestoreRiskAndRescueRepository? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var countdownJob: Job? = null
    private val notifiedAlertIds = mutableSetOf<String>()

    private val _activeIncomingAlert = MutableStateFlow<HexRescueAlert?>(null)
    val activeIncomingAlert: StateFlow<HexRescueAlert?> = _activeIncomingAlert.asStateFlow()

    private val _activeOutgoingAlert = MutableStateFlow<HexRescueAlert?>(null)
    val activeOutgoingAlert: StateFlow<HexRescueAlert?> = _activeOutgoingAlert.asStateFlow()

    private val _nearbyHexAlerts = MutableStateFlow<List<HexRescueAlert>>(emptyList())
    val nearbyHexAlerts: StateFlow<List<HexRescueAlert>> = _nearbyHexAlerts.asStateFlow()

    private var latestCloudAlerts: List<HexRescueAlert> = emptyList()
    private var latestBleDiscoveries: List<BleDiscoveredEmergency> = emptyList()
    private var latestResponderLocation: Location? = null

    companion object {
        private const val CHANNEL_ID = "rakshaone_hex_rescue_channel"
        val COMPASS_SECTORS = listOf("N-NE", "E", "S-SE", "S-SW", "W", "N-NW")

        fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val deltaLambda = Math.toRadians(lon2 - lon1)
            val y = sin(deltaLambda) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
            val theta = Math.toDegrees(atan2(y, x))
            return (theta + 360.0) % 360.0
        }

        fun bearingToCompassSector(bearingDeg: Double): String {
            val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
            val index = ((normalized / 60.0).toInt()).coerceIn(0, COMPASS_SECTORS.lastIndex)
            return COMPASS_SECTORS[index]
        }

        fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return (r * c).toFloat()
        }
    }

    fun broadcastOutgoingHexSos(
        incidentId: String,
        victimName: String,
        victimLat: Double,
        victimLng: Double,
        locationDescription: String,
        evidenceSummary: String = "Audio + Dual CameraX evidence captured",
        policeAuthorized: Boolean = false,
        victimUid: String = ""
    ): HexRescueAlert {
        countdownJob?.cancel()
        val initialAlert = HexRescueAlert(
            alertId = "HEX-${System.currentTimeMillis() % 100000}",
            incidentId = incidentId,
            victimUid = victimUid,
            victimName = victimName,
            victimLatitude = victimLat,
            victimLongitude = victimLng,
            currentRadiusMeters = 750f,
            expansionStage = 1,
            secondsRemainingInStage = 30,
            dangerType = "R3 CRITICAL SOS",
            status = HexAlertStatus.BROADCASTING,
            lifecycleState = HexRescueLifecycleState.REQUEST_BROADCAST,
            compassSector = "ALL-6-SECTORS",
            sourceChannel = "FIRESTORE+BLE",
            evidenceSummary = evidenceSummary,
            victimLocationDescription = locationDescription,
            policeDispatched = false,
            policeStatus = if (policeAuthorized) PoliceEscalationStatus.ELIGIBLE else PoliceEscalationStatus.NOT_ELIGIBLE,
            isOutgoingFromSelf = true
        )
        _activeOutgoingAlert.value = initialAlert

        bleEmergencyRelay?.broadcastEmergencyBeacon(
            incidentId = incidentId,
            lat = victimLat,
            lng = victimLng,
            evidenceSummary = evidenceSummary,
            sectorHint = "ALL-6-SECTORS"
        )

        scope.launch {
            try {
                firestoreRepository?.publishHexRescueRequest(initialAlert)
            } catch (_: Exception) {
            }
        }

        // 30s outward hexagonal radius expansion: 750m -> 1500m -> 3000m -> 5000m
        countdownJob = scope.launch {
            var stage = 1
            var radius = 750f
            while (isActive && stage <= 4) {
                for (sec in 30 downTo 1) {
                    if (!isActive) break
                    val current = _activeOutgoingAlert.value ?: break
                    if (current.status == HexAlertStatus.RESOLVED || current.status == HexAlertStatus.ACCEPTED) {
                        return@launch
                    }
                    _activeOutgoingAlert.value = current.copy(
                        secondsRemainingInStage = sec,
                        expansionStage = stage,
                        currentRadiusMeters = radius
                    )
                    delay(1000)
                }
                if (stage >= 4) break
                stage++
                radius = when (stage) {
                    2 -> 1500f
                    3 -> 3000f
                    else -> 5000f
                }
                val expanded = _activeOutgoingAlert.value?.copy(
                    expansionStage = stage,
                    currentRadiusMeters = radius,
                    secondsRemainingInStage = 30
                )
                _activeOutgoingAlert.value = expanded
                if (expanded != null) {
                    try {
                        firestoreRepository?.publishHexRescueRequest(expanded)
                    } catch (_: Exception) {
                    }
                }
                Log.i("HexRescueManager", "Hexagonal perimeter expanded to Stage $stage ($radius m)")
            }
        }
        return initialAlert
    }

    fun cancelOrResolveOutgoingSos(incidentId: String) {
        val current = _activeOutgoingAlert.value
        if (current != null && current.incidentId == incidentId) {
            countdownJob?.cancel()
            val resolved = current.copy(
                status = HexAlertStatus.RESOLVED,
                lifecycleState = HexRescueLifecycleState.COMPLETED
            )
            _activeOutgoingAlert.value = null
            bleEmergencyRelay?.stopEmergencyBleBroadcast()
            scope.launch {
                try {
                    firestoreRepository?.updateHexRescueLifecycle(
                        requestId = resolved.alertId,
                        newState = HexRescueLifecycleState.COMPLETED
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    fun updateResponderInputs(
        cloudAlerts: List<HexRescueAlert>? = null,
        bleDiscoveries: List<BleDiscoveredEmergency>? = null,
        myLocation: Location? = latestResponderLocation
    ) {
        if (cloudAlerts != null) latestCloudAlerts = cloudAlerts
        if (bleDiscoveries != null) latestBleDiscoveries = bleDiscoveries
        latestResponderLocation = myLocation
        recomputeNearbyAlerts()
    }

    private fun recomputeNearbyAlerts() {
        val myLoc = latestResponderLocation
        val profile = goodCitizenManager.citizenProfile.value
        val outgoingIncidentId = _activeOutgoingAlert.value?.incidentId
        val mergedByIncident = linkedMapOf<String, HexRescueAlert>()

        for (cloud in latestCloudAlerts) {
            if (cloud.incidentId == outgoingIncidentId || cloud.isOutgoingFromSelf) continue
            val dist = if (myLoc != null && (cloud.victimLatitude != 0.0 || cloud.victimLongitude != 0.0)) {
                calculateDistanceMeters(myLoc.latitude, myLoc.longitude, cloud.victimLatitude, cloud.victimLongitude)
            } else {
                cloud.distanceMeters
            }
            val sector = if (myLoc != null && (cloud.victimLatitude != 0.0 || cloud.victimLongitude != 0.0)) {
                bearingToCompassSector(
                    calculateBearingDegrees(myLoc.latitude, myLoc.longitude, cloud.victimLatitude, cloud.victimLongitude)
                )
            } else {
                cloud.compassSector
            }
            val walkMins = (dist / 80f).toInt().coerceAtLeast(1)
            mergedByIncident[cloud.incidentId] = cloud.copy(
                distanceMeters = dist,
                estimatedWalkMinutes = walkMins,
                compassSector = sector
            )
        }

        for (ble in latestBleDiscoveries) {
            val shortBleKey = ble.incidentId.removePrefix("BLE-")
            val matchingExisting = mergedByIncident.values.firstOrNull {
                it.incidentId.endsWith(shortBleKey) || it.incidentId == ble.incidentId
            }
            if (matchingExisting != null) {
                mergedByIncident[matchingExisting.incidentId] = matchingExisting.copy(
                    sourceChannel = "FIRESTORE+BLE",
                    distanceMeters = minOf(matchingExisting.distanceMeters, ble.estimatedDistanceMeters)
                )
            } else {
                val sector = if (myLoc != null && (ble.victimLatitude != 0.0 || ble.victimLongitude != 0.0)) {
                    bearingToCompassSector(
                        calculateBearingDegrees(myLoc.latitude, myLoc.longitude, ble.victimLatitude, ble.victimLongitude)
                    )
                } else {
                    ble.sectorHint
                }
                mergedByIncident[ble.incidentId] = HexRescueAlert(
                    alertId = ble.incidentId,
                    incidentId = ble.incidentId,
                    victimName = "Nearby Citizen (Bluetooth LE)",
                    victimLatitude = ble.victimLatitude,
                    victimLongitude = ble.victimLongitude,
                    currentRadiusMeters = 250f,
                    expansionStage = 1,
                    secondsRemainingInStage = 30,
                    dangerType = "R3 CRITICAL SOS (BLE Relay)",
                    timestamp = ble.discoveredAtMillis,
                    status = HexAlertStatus.BROADCASTING,
                    lifecycleState = HexRescueLifecycleState.REQUEST_BROADCAST,
                    compassSector = sector,
                    sourceChannel = "BLE",
                    evidenceSummary = ble.evidenceSummary,
                    victimLocationDescription = "BLE Proximity (~${ble.estimatedDistanceMeters.toInt()}m)",
                    distanceMeters = ble.estimatedDistanceMeters,
                    estimatedWalkMinutes = (ble.estimatedDistanceMeters / 80f).toInt().coerceAtLeast(1),
                    policeDispatched = false,
                    policeStatus = PoliceEscalationStatus.ELIGIBLE
                )
            }
        }

        val allSorted = mergedByIncident.values.sortedBy { it.distanceMeters }
        _nearbyHexAlerts.value = allSorted

        // Only alert responders who are verified citizens with active duty
        if (!profile.isEligibleForHexRescue) {
            return
        }

        val inPerimeterAlerts = allSorted.filter { alert ->
            alert.status == HexAlertStatus.BROADCASTING &&
                (alert.distanceMeters <= alert.currentRadiusMeters.coerceAtLeast(profile.volunteerRadiusMeters) || alert.sourceChannel.contains("BLE"))
        }.take(5)

        if (inPerimeterAlerts.isNotEmpty()) {
            _activeIncomingAlert.value = inPerimeterAlerts.first()
            inPerimeterAlerts.forEach { alert ->
                if (notifiedAlertIds.add(alert.alertId)) {
                    postHexRescueNotification(alert)
                }
            }
        }
    }

    fun receiveIncomingHexAlert(alert: HexRescueAlert, myLocation: Location?): Boolean {
        val profile = goodCitizenManager.citizenProfile.value
        if (!profile.isEligibleForHexRescue) {
            Log.w("HexRescueManager", "Incoming alert skipped: Citizen not eligible (Status=${profile.verificationStatus}, Duty=${profile.isVolunteerActive})")
            return false
        }

        val distance = if (myLocation != null && (alert.victimLatitude != 0.0 || alert.victimLongitude != 0.0)) {
            calculateDistanceMeters(myLocation.latitude, myLocation.longitude, alert.victimLatitude, alert.victimLongitude)
        } else {
            alert.distanceMeters
        }
        val sector = if (myLocation != null && (alert.victimLatitude != 0.0 || alert.victimLongitude != 0.0)) {
            bearingToCompassSector(calculateBearingDegrees(myLocation.latitude, myLocation.longitude, alert.victimLatitude, alert.victimLongitude))
        } else {
            alert.compassSector
        }

        if (distance <= alert.currentRadiusMeters.coerceAtLeast(1500f)) {
            val walkMins = (distance / 80f).toInt().coerceAtLeast(1)
            val resolved = alert.copy(
                distanceMeters = distance,
                estimatedWalkMinutes = walkMins,
                compassSector = sector
            )
            _activeIncomingAlert.value = resolved
            val list = _nearbyHexAlerts.value.toMutableList()
            val idx = list.indexOfFirst { it.alertId == resolved.alertId }
            if (idx >= 0) list[idx] = resolved else list.add(0, resolved)
            _nearbyHexAlerts.value = list
            if (notifiedAlertIds.add(resolved.alertId)) {
                postHexRescueNotification(resolved)
            }
            return true
        }
        return false
    }

    fun acceptIncomingHelpRequest(
        alertId: String,
        responderPhone: String? = null,
        responderSelfieB64: String? = null,
        responderLat: Double? = null,
        responderLng: Double? = null
    ): Boolean {
        val current = _activeIncomingAlert.value?.takeIf { it.alertId == alertId }
            ?: _nearbyHexAlerts.value.firstOrNull { it.alertId == alertId }
            ?: return false

        goodCitizenManager.recordAcceptedHelp(current.incidentId)
        val responderName = goodCitizenManager.citizenProfile.value.displayName.ifBlank { "Verified Citizen" }
        val updated = current.copy(
            status = HexAlertStatus.ACCEPTED,
            lifecycleState = HexRescueLifecycleState.RESPONDER_ACCEPTED,
            acceptedResponderUid = goodCitizenManager.citizenProfile.value.userId,
            acceptedResponderName = responderName,
            acceptedResponderPhone = responderPhone,
            acceptedResponderSelfieB64 = responderSelfieB64,
            acceptedResponderLat = responderLat,
            acceptedResponderLng = responderLng
        )
        applyAlertUpdate(updated)
        syncLifecycleToCloud(updated, HexRescueLifecycleState.RESPONDER_ACCEPTED)
        return true
    }

    fun markResponderEnRoute(alertId: String): Boolean {
        val current = _activeIncomingAlert.value?.takeIf { it.alertId == alertId }
            ?: _nearbyHexAlerts.value.firstOrNull { it.alertId == alertId }
            ?: return false
        val updated = current.copy(
            status = HexAlertStatus.EN_ROUTE,
            lifecycleState = HexRescueLifecycleState.EN_ROUTE
        )
        applyAlertUpdate(updated)
        syncLifecycleToCloud(updated, HexRescueLifecycleState.EN_ROUTE)
        return true
    }

    fun markResponderArrived(alertId: String): Boolean {
        val current = _activeIncomingAlert.value?.takeIf { it.alertId == alertId }
            ?: _nearbyHexAlerts.value.firstOrNull { it.alertId == alertId }
            ?: return false
        val updated = current.copy(
            status = HexAlertStatus.ARRIVED,
            lifecycleState = HexRescueLifecycleState.ARRIVED
        )
        applyAlertUpdate(updated)
        syncLifecycleToCloud(updated, HexRescueLifecycleState.ARRIVED)
        return true
    }

    fun completePickup(alertId: String): Boolean {
        val current = _activeIncomingAlert.value?.takeIf { it.alertId == alertId }
            ?: _nearbyHexAlerts.value.firstOrNull { it.alertId == alertId }
            ?: return false
        goodCitizenManager.recordCompletedPickup(current.incidentId)
        val updated = current.copy(
            status = HexAlertStatus.RESOLVED,
            lifecycleState = HexRescueLifecycleState.COMPLETED
        )
        applyAlertUpdate(updated)
        _activeIncomingAlert.value = null
        syncLifecycleToCloud(updated, HexRescueLifecycleState.COMPLETED)
        return true
    }

    fun declineIncomingHelpRequest(alertId: String): Boolean {
        val current = _activeIncomingAlert.value?.takeIf { it.alertId == alertId }
            ?: _nearbyHexAlerts.value.firstOrNull { it.alertId == alertId }
            ?: return false

        goodCitizenManager.recordDeclinedHelp(current.incidentId)
        _activeIncomingAlert.value = null
        _nearbyHexAlerts.value = _nearbyHexAlerts.value.filterNot { it.alertId == alertId }
        return true
    }

    fun dismissIncomingAlert() {
        _activeIncomingAlert.value = null
    }

    private fun applyAlertUpdate(updated: HexRescueAlert) {
        if (_activeIncomingAlert.value?.alertId == updated.alertId) {
            _activeIncomingAlert.value = updated
        }
        val list = _nearbyHexAlerts.value.toMutableList()
        val idx = list.indexOfFirst { it.alertId == updated.alertId }
        if (idx >= 0) {
            list[idx] = updated
        } else {
            list.add(0, updated)
        }
        _nearbyHexAlerts.value = list
    }

    private fun syncLifecycleToCloud(alert: HexRescueAlert, state: HexRescueLifecycleState) {
        if (alert.sourceChannel == "BLE") return
        scope.launch {
            try {
                firestoreRepository?.updateHexRescueLifecycle(
                    requestId = alert.alertId,
                    newState = state,
                    responderName = alert.acceptedResponderName,
                    responderPhone = alert.acceptedResponderPhone,
                    responderSelfieB64 = alert.acceptedResponderSelfieB64,
                    responderLat = alert.acceptedResponderLat,
                    responderLng = alert.acceptedResponderLng
                )
            } catch (_: Exception) {
            }
        }
    }

    fun updateOwnApproachingLocation(responderLat: Double, responderLng: Double, myUid: String?) {
        if (myUid.isNullOrBlank() || (responderLat == 0.0 && responderLng == 0.0)) return
        val activeAccepted = _nearbyHexAlerts.value.filter {
            !it.isOutgoingFromSelf &&
                it.acceptedResponderUid == myUid &&
                (it.status == HexAlertStatus.ACCEPTED || it.status == HexAlertStatus.EN_ROUTE)
        }
        if (activeAccepted.isEmpty()) return

        for (alert in activeAccepted) {
            val nextState = if (alert.lifecycleState == HexRescueLifecycleState.ARRIVED) {
                HexRescueLifecycleState.ARRIVED
            } else {
                HexRescueLifecycleState.EN_ROUTE
            }
            val updated = alert.copy(
                status = if (nextState == HexRescueLifecycleState.ARRIVED) HexAlertStatus.ARRIVED else HexAlertStatus.EN_ROUTE,
                lifecycleState = nextState,
                acceptedResponderLat = responderLat,
                acceptedResponderLng = responderLng
            )
            applyAlertUpdate(updated)
            if (alert.sourceChannel != "BLE") {
                scope.launch {
                    try {
                        firestoreRepository?.updateApproachingResponderLocation(
                            requestId = alert.alertId,
                            responderLat = responderLat,
                            responderLng = responderLng,
                            state = nextState
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun postHexRescueNotification(alert: HexRescueAlert) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Hexagonal Citizen Rescue Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Nearby verified citizen emergency help requests"
                }
                nm.createNotificationChannel(ch)
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                alert.alertId.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("HELP REQUEST • ${alert.compassSector} (${alert.distanceMeters.toInt()}m)")
                .setContentText("${alert.victimName}: ${alert.dangerType} • ${alert.sourceChannel}")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(alert.alertId.hashCode(), notification)
        } catch (_: Exception) {
        }
    }
}

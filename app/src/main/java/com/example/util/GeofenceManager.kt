package com.example.util

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.model.AuditLogEntity
import com.example.data.model.HighRiskZoneEntity
import com.example.data.model.SavedRoutineEntity
import com.example.data.model.TrustedContactEntity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GeofenceManager(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val deviceEmailService: DeviceEmailService,
    private val audioRecorder: AudioEvidenceRecorder
) {
    private val tag = "GeofenceManager"
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    private val _activeRoutine = MutableStateFlow<SavedRoutineEntity?>(null)
    val activeRoutine: StateFlow<SavedRoutineEntity?> = _activeRoutine.asStateFlow()

    private val _currentDeviationMeters = MutableStateFlow(0f)
    val currentDeviationMeters: StateFlow<Float> = _currentDeviationMeters.asStateFlow()

    private val _nearestHighRiskZone = MutableStateFlow<Pair<HighRiskZoneEntity, Float>?>(null)
    val nearestHighRiskZone: StateFlow<Pair<HighRiskZoneEntity, Float>?> = _nearestHighRiskZone.asStateFlow()

    // Alert event flows
    private val _deviationAlertEvent = MutableSharedFlow<DeviationAlert>(extraBufferCapacity = 5)
    val deviationAlertEvent: SharedFlow<DeviationAlert> = _deviationAlertEvent.asSharedFlow()

    private val _highRiskAlertEvent = MutableSharedFlow<HighRiskAlert>(extraBufferCapacity = 5)
    val highRiskAlertEvent: SharedFlow<HighRiskAlert> = _highRiskAlertEvent.asSharedFlow()

    data class DeviationAlert(
        val routineName: String,
        val deviationMeters: Float,
        val thresholdMeters: Float,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class HighRiskAlert(
        val zoneName: String,
        val riskLevel: String,
        val advisory: String,
        val distanceMeters: Float,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long = System.currentTimeMillis()
    )

    init {
        createNotificationChannel()
    }

    /**
     * Registers high-risk zones as Google Play Services Geofences.
     */
    @SuppressLint("MissingPermission")
    suspend fun registerGoogleGeofences(zones: List<HighRiskZoneEntity>) = withContext(Dispatchers.IO) {
        val activeZones = zones.filter { it.isEnabled }
        if (activeZones.isEmpty()) return@withContext

        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            Log.w(tag, "Skipping Google Geofence registration until ACCESS_FINE_LOCATION is granted.")
            return@withContext
        }

        try {
            val geofenceList = activeZones.map { zone ->
                Geofence.Builder()
                    .setRequestId(zone.zoneId)
                    .setCircularRegion(zone.latitude, zone.longitude, zone.radiusMeters)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setLoiteringDelay(15000)
                    .build()
            }

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(geofenceList)
                .build()

            val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            geofencingClient.addGeofences(request, pendingIntent).addOnSuccessListener {
                Log.d(tag, "Successfully registered ${geofenceList.size} Google Location Services Geofences.")
            }.addOnFailureListener { e ->
                Log.w(tag, "Geofence registration note: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception configuring Google Geofences: ${e.message}")
        }
    }

    /**
     * Start monitoring deviation against a saved routine.
     */
    suspend fun startRoutineMonitoring(routine: SavedRoutineEntity) = withContext(Dispatchers.IO) {
        database.savedRoutineDao().setMonitoringActive(routine.routineId, true)
        _activeRoutine.value = routine.copy(isMonitoringActive = true)
        _currentDeviationMeters.value = 0f

        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = System.currentTimeMillis(),
                actor = "User",
                role = "CITIZEN",
                action = "ROUTINE_MONITORING_STARTED",
                targetId = routine.routineId,
                details = "Monitoring route deviation for '${routine.name}'. Allowed threshold: ${routine.maxDeviationMeters}m"
            )
        )
    }

    /**
     * Stop routine monitoring.
     */
    suspend fun stopRoutineMonitoring(routineId: String) = withContext(Dispatchers.IO) {
        database.savedRoutineDao().setMonitoringActive(routineId, false)
        if (_activeRoutine.value?.routineId == routineId) {
            _activeRoutine.value = null
            _currentDeviationMeters.value = 0f
        }
    }

    /**
     * Process new live real GPS coordinates for both:
     * 1. Deviation from saved routine.
     * 2. Proximity and entry into High-Risk Geofences.
     */
    suspend fun evaluateLocation(
        location: Location,
        contacts: List<TrustedContactEntity>,
        userName: String
    ) = withContext(Dispatchers.IO) {
        val lat = location.latitude
        val lng = location.longitude

        // 1. Check High-Risk Zones
        val highRiskZones = database.highRiskZoneDao().getActiveZonesOnce()
        var closestZone: Pair<HighRiskZoneEntity, Float>? = null

        for (zone in highRiskZones) {
            val dist = calculateDistanceMeters(lat, lng, zone.latitude, zone.longitude)
            if (closestZone == null || dist < closestZone.second) {
                closestZone = Pair(zone, dist)
            }

            // User has entered a high-risk area
            if (dist <= zone.radiusMeters) {
                val alert = HighRiskAlert(
                    zoneName = zone.name,
                    riskLevel = zone.riskLevel,
                    advisory = zone.advisoryMessage,
                    distanceMeters = dist,
                    latitude = lat,
                    longitude = lng
                )
                _highRiskAlertEvent.tryEmit(alert)
                triggerSystemNotification(
                    title = "⚠️ High-Risk Area Alert: ${zone.name}",
                    message = "You entered a high-risk zone. ${zone.advisoryMessage}"
                )
            }
        }
        _nearestHighRiskZone.value = closestZone

        // 2. Check Saved Routine Deviation
        val routine = _activeRoutine.value ?: database.savedRoutineDao().getActiveRoutinesOnce().firstOrNull()
        if (routine != null) {
            _activeRoutine.value = routine
            val waypoints = parseWaypoints(routine.waypointsJson)
            if (waypoints.isNotEmpty()) {
                val minDistanceToCorridor = calculateMinDistanceToPath(lat, lng, waypoints)
                _currentDeviationMeters.value = minDistanceToCorridor
                database.savedRoutineDao().updateDeviation(routine.routineId, minDistanceToCorridor, System.currentTimeMillis())

                if (minDistanceToCorridor > routine.maxDeviationMeters) {
                    val alert = DeviationAlert(
                        routineName = routine.name,
                        deviationMeters = minDistanceToCorridor,
                        thresholdMeters = routine.maxDeviationMeters,
                        latitude = lat,
                        longitude = lng
                    )
                    _deviationAlertEvent.tryEmit(alert)

                    triggerSystemNotification(
                        title = "🚨 Routine Deviation Alert!",
                        message = "You deviated ${minDistanceToCorridor.toInt()}m from saved routine '${routine.name}'."
                    )

                    // Auto-start encrypted background audio recording as potential evidence
                    audioRecorder.startAutomaticSosRecording(
                        incidentId = "DEV-${routine.routineId.takeLast(6)}",
                        durationSeconds = 30
                    )

                    database.auditLogDao().insertLog(
                        AuditLogEntity(
                            timestamp = System.currentTimeMillis(),
                            actor = "GeofenceEngine",
                            role = "SYSTEM",
                            action = "ROUTE_DEVIATION_TRIGGERED",
                            targetId = routine.routineId,
                            details = "Deviation of ${minDistanceToCorridor.toInt()}m detected. Notification triggered & encrypted audio recording started."
                        )
                    )
                }
            }
        }
    }

    private fun parseWaypoints(json: String): List<Pair<Double, Double>> {
        if (json.isBlank()) return emptyList()
        return try {
            json.split(";").mapNotNull { item ->
                val parts = item.split(",")
                if (parts.size == 2) {
                    val lat = parts[0].trim().toDoubleOrNull()
                    val lng = parts[1].trim().toDoubleOrNull()
                    if (lat != null && lng != null) Pair(lat, lng) else null
                } else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Calculates minimum perpendicular distance in meters from a point to a segmented path.
     */
    private fun calculateMinDistanceToPath(
        lat: Double,
        lng: Double,
        path: List<Pair<Double, Double>>
    ): Float {
        if (path.isEmpty()) return 0f
        if (path.size == 1) return calculateDistanceMeters(lat, lng, path[0].first, path[0].second)

        var minDistance = Float.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val p1 = path[i]
            val p2 = path[i + 1]
            val d = distanceToSegment(lat, lng, p1.first, p1.second, p2.first, p2.second)
            if (d < minDistance) {
                minDistance = d
            }
        }
        return minDistance
    }

    private fun distanceToSegment(
        px: Double, py: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) {
            return calculateDistanceMeters(px, py, x1, y1)
        }

        // Project point onto line segment
        val t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
        val clampedT = t.coerceIn(0.0, 1.0)
        val projX = x1 + clampedT * dx
        val projY = y1 + clampedT * dy
        return calculateDistanceMeters(px, py, projX, projY)
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "geofence_safety_channel",
                "Geofence & Routine Safety Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency alerts for route deviations and high-risk zones"
                enableVibration(true)
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerSystemNotification(title: String, message: String) {
        try {
            val openAppIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, "geofence_safety_channel")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.notify((1000..9999).random(), builder.build())
        } catch (_: Exception) {}
    }
}

/**
 * BroadcastReceiver for Google Play Services Geofencing triggers.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) {
            Log.e("GeofenceReceiver", "Geofence error code: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()
        if (triggeringGeofences.isEmpty()) return

        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER || transition == Geofence.GEOFENCE_TRANSITION_DWELL) {
            val pendingResult = goAsync()
            val zoneIds = triggeringGeofences.map { it.requestId }
            val triggeringLoc = geofencingEvent.triggeringLocation
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val matchedZones = db.highRiskZoneDao().getActiveZonesOnce()
                        .filter { it.zoneId in zoneIds }
                    val zoneNames = matchedZones.joinToString(", ") { it.name }
                        .ifBlank { zoneIds.joinToString(", ") }
                    val advisory = matchedZones.firstOrNull()?.advisoryMessage
                        ?.ifBlank { "Stay alert and keep RakshaOne SOS ready." }
                        ?: "Entered marked high-risk geofence zone."

                    db.auditLogDao().insertLog(
                        AuditLogEntity(
                            timestamp = System.currentTimeMillis(),
                            actor = "GeofenceReceiver",
                            role = "SYSTEM",
                            action = "OS_GEOFENCE_ENTERED",
                            targetId = zoneIds.firstOrNull() ?: "GEOFENCE",
                            details = "Google Location Services triggered high-risk zone alert for: $zoneNames"
                        )
                    )

                    postGeofenceNotification(
                        context = context,
                        title = "⚠️ High-Risk Zone: $zoneNames",
                        message = advisory
                    )
                } catch (e: Exception) {
                    Log.e("GeofenceReceiver", "Error handling geofence trigger: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun postGeofenceNotification(context: Context, title: String, message: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNotif = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasNotif) return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "geofence_safety_channel",
                    "Geofence & Routine Safety Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Emergency alerts for route deviations and high-risk zones"
                    enableVibration(true)
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(channel)
            }

            val openAppIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, "geofence_safety_channel")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.notify((2000..9999).random(), builder.build())
        } catch (_: Exception) {}
    }
}

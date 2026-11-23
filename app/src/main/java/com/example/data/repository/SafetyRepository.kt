package com.example.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.model.AuditLogEntity
import com.example.data.model.EvidenceEntity
import com.example.data.model.IncidentEntity
import com.example.data.model.IncidentEventEntity
import com.example.data.model.NearbyFacility
import com.example.data.model.SafetyGuideEntity
import com.example.data.model.TripEntity
import com.example.data.model.TripWaypointEntity
import com.example.data.model.TrustedContactEntity
import com.example.data.model.UserProfileEntity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.example.data.model.HighRiskZoneEntity
import com.example.data.model.LocationEmergencyInfo
import com.example.data.model.OfflineAlertEntity
import com.example.data.model.SavedRoutineEntity
import com.example.risk.EmergencyEscalationOrchestrator
import com.example.risk.EmergencyEscalationPolicyEngine
import com.example.risk.PerceptionObservation
import com.example.risk.PoliceEscalationStatus
import com.example.risk.ReceiptDeliveryState
import com.example.risk.RiskAssessmentResult
import com.example.risk.RiskIntelligenceEngine
import com.example.risk.TriggerOrigin
import com.example.service.DualCameraCaptureResult
import com.example.service.SosAlertService
import com.example.service.TripMonitoringService
import com.example.util.AcousticThreatDetector
import com.example.util.AudioEvidenceRecorder
import com.example.util.BatteryOptimizationManager
import com.example.util.BleEmergencyRelay
import com.example.util.DeviceEmailService
import com.example.util.DeviceLockManager
import com.example.util.DeviceSensorSnapshot
import com.example.util.DuressPinManager
import com.example.util.EmergencyHapticManager
import com.example.util.EmergencyResponseDispatcher
import com.example.util.FakeCallManager
import com.example.util.FirestoreEvidenceSyncManager
import com.example.util.GeofenceManager
import com.example.util.GoodCitizenManager
import com.example.util.HexagonalRescueManager
import com.example.util.LiveRadarManager
import com.example.util.LiveRadarPin
import com.example.util.NearbySupportUser
import com.example.util.NetworkMonitor
import com.example.util.PhoneNormalizer
import com.example.util.PlaceAndTrafficIntel
import com.example.util.PlaceAndTrafficIntelManager
import com.example.util.PredictiveThreatAssessment
import com.example.util.RealtimeEmergencyService
import com.example.util.SafetySensorsManager
import com.example.util.StorageHealthStatus
import com.example.util.StorageWardenManager
import com.example.util.UserRoutineLearner
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OfflineConnectivityAlert(
    val message: String,
    val phonesJoined: String,
    val batteryPercent: Int,
    val latitude: Double?,
    val longitude: Double?,
    val directSmsSent: Boolean = false
)

data class TripAnomalyState(
    val tripId: String = "",
    val isMonitoring: Boolean = false,
    val waypointCount: Int = 0,
    val anchorLat: Double? = null,
    val anchorLng: Double? = null,
    val distanceFromAnchorMeters: Float = 0f,
    val allowedCorridorMeters: Float = 500f,
    val stationaryDurationSeconds: Long = 0L,
    val prolongedStopThresholdSeconds: Long = 180L,
    val deviationAlertTriggered: Boolean = false,
    val prolongedStopAlertTriggered: Boolean = false,
    val lastAlertMessage: String? = null,
    val lastAlertTimestamp: Long? = null
)

class SafetyRepository(private val context: Context) {
    val database = AppDatabase.getDatabase(context)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    val realtimeEmergencyService = RealtimeEmergencyService(context, fusedLocationClient)
    val batteryOptimizationManager = BatteryOptimizationManager(context)
    val sensorsManager = SafetySensorsManager(context)
    val hapticManager = EmergencyHapticManager(context)
    val fakeCallManager = FakeCallManager(context)
    val deviceEmailService = DeviceEmailService(context, database)
    val audioRecorder = AudioEvidenceRecorder(context, database, repositoryScope)
    val cameraVideoRecorder = com.example.service.SosCameraVideoRecorder(context)
    val videoEvidenceManager = com.example.util.EmergencyVideoEvidenceManager(context, database, repositoryScope)
    val geofenceManager = GeofenceManager(context, database, repositoryScope, deviceEmailService, audioRecorder)
    val deviceLockManager = DeviceLockManager(context)
    val duressPinManager = DuressPinManager(context)
    // Acoustic monitoring is passive telemetry & predictive AI input; SOS/R3 requires explicit user button confirmation
    val acousticThreatDetector = AcousticThreatDetector(
        context = context,
        isMicOccupied = {
            audioRecorder.isRecording.value ||
                fakeCallManager.callState.value == com.example.util.FakeCallState.CONNECTED
        }
    ) { _ ->
        userRoutineLearner.updateSensorContext(acousticDbSpike = true)
        Log.i("SafetyRepository", "Acoustic observation logged for TFLite predictive AI; explicit SOS button required for R3.")
    }
    val emergencyDispatchRepository by lazy { FirestoreEmergencyDispatchRepository(context) }
    val firestoreProfileRepository by lazy { FirestoreProfileRepository(context) }
    val firestoreRiskRepository by lazy { FirestoreRiskAndRescueRepository(context) }
    val circleAndAiSyncRepository by lazy { FirestoreCircleAndAiSyncRepository(context) }
    val evidenceSyncManager by lazy { FirestoreEvidenceSyncManager(context) }
    val bleEmergencyRelay = BleEmergencyRelay(context)
    val goodCitizenManager = GoodCitizenManager(context, firestoreRiskRepository)
    val emergencyResponseDispatcher = EmergencyResponseDispatcher(context)
    val hexagonalRescueManager = HexagonalRescueManager(
        context = context,
        goodCitizenManager = goodCitizenManager,
        bleEmergencyRelay = bleEmergencyRelay,
        firestoreRepository = firestoreRiskRepository
    )
    val riskEngine = RiskIntelligenceEngine()
    val escalationOrchestrator by lazy {
        EmergencyEscalationOrchestrator(
            context = context,
            database = database,
            responseDispatcher = emergencyResponseDispatcher,
            firestoreRepository = firestoreRiskRepository
        )
    }

    val liveRadarManager = LiveRadarManager(context)
    val placeTrafficIntelManager = PlaceAndTrafficIntelManager(context)
    val storageWardenManager = StorageWardenManager(context)
    val userRoutineLearner = UserRoutineLearner(context)

    val radarPins: StateFlow<Map<String, LiveRadarPin>> = liveRadarManager.radarPins
    val nearbyUsers: StateFlow<List<NearbySupportUser>> = liveRadarManager.nearbyUsers
    val placeIntel: StateFlow<PlaceAndTrafficIntel> = placeTrafficIntelManager.intel
    val disconnectAlertMessage: StateFlow<String?> = liveRadarManager.disconnectAlertMessage
    val storageHealth: StateFlow<StorageHealthStatus> = storageWardenManager.storageHealth
    val predictiveThreat: StateFlow<PredictiveThreatAssessment> = userRoutineLearner.assessment

    private val _offlineConnectivityAlert = MutableSharedFlow<OfflineConnectivityAlert>(extraBufferCapacity = 4)
    val offlineConnectivityAlert: SharedFlow<OfflineConnectivityAlert> = _offlineConnectivityAlert.asSharedFlow()

    @Volatile
    private var liveRadarWatchEnabled: Boolean = true

    private val _isDecoyStealthActive = MutableStateFlow(false)
    val isDecoyStealthActive: StateFlow<Boolean> = _isDecoyStealthActive.asStateFlow()

    private var _lowBatteryBlackBoxTriggered = false
    private var lastBreadcrumbSavedAt = 0L

    private val _travelBumpFilterEnabled = MutableStateFlow(true)
    val travelBumpFilterEnabled: StateFlow<Boolean> = _travelBumpFilterEnabled.asStateFlow()

    private val _currentRiskAssessment = MutableStateFlow(
        riskEngine.evaluate(PerceptionObservation(travelBumpFilterEnabled = true))
    )
    val currentRiskAssessment: StateFlow<RiskAssessmentResult> = _currentRiskAssessment.asStateFlow()

    val frontPhotoPaths: StateFlow<List<String>> = cameraVideoRecorder.frontPhotoPaths
    val rearPhotoPaths: StateFlow<List<String>> = cameraVideoRecorder.rearPhotoPaths
    val capturedVideoPaths: StateFlow<List<String>> = cameraVideoRecorder.capturedVideoPaths
    val capturedAudioPaths: StateFlow<List<String>> = audioRecorder.capturedAudioPaths
    val latestShareableAudioFile: StateFlow<File?> = audioRecorder.latestShareableAudioFile

    private val _lastDualCameraResult = MutableStateFlow<DualCameraCaptureResult?>(null)
    val lastDualCameraResult: StateFlow<DualCameraCaptureResult?> = _lastDualCameraResult.asStateFlow()

    val isHapticsEnabled: StateFlow<Boolean> = hapticManager.isHapticsEnabled
    val isIgnoringBatteryOptimizations: StateFlow<Boolean> =
        batteryOptimizationManager.isIgnoringBatteryOptimizations

    val networkMonitor = NetworkMonitor(
        context = context,
        onNetworkRestored = { dispatchPendingOfflineAlerts() },
        onNetworkLost = { handleConnectivityLost() }
    )
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val pendingOfflineAlerts: Flow<List<OfflineAlertEntity>> = database.offlineAlertDao().getPendingAlerts()
    val pendingOfflineCount: Flow<Int> = database.offlineAlertDao().getPendingCount()

    val emergencyInfo: StateFlow<LocationEmergencyInfo> = realtimeEmergencyService.emergencyInfo
    val realLocation: StateFlow<Location?> = realtimeEmergencyService.realLocation

    val allHighRiskZones: Flow<List<HighRiskZoneEntity>> = database.highRiskZoneDao().getAllZones()
    val allSavedRoutines: Flow<List<SavedRoutineEntity>> = database.savedRoutineDao().getAllRoutines()
    val activeRoutine: StateFlow<SavedRoutineEntity?> = geofenceManager.activeRoutine
    val currentDeviationMeters: StateFlow<Float> = geofenceManager.currentDeviationMeters
    val nearestHighRiskZone: StateFlow<Pair<HighRiskZoneEntity, Float>?> = geofenceManager.nearestHighRiskZone

    val userProfile: Flow<UserProfileEntity?> = database.userDao().getUserProfile()
    val trustedContacts: Flow<List<TrustedContactEntity>> = database.trustedContactDao().getAllContacts()
    val allIncidents: Flow<List<IncidentEntity>> = database.incidentDao().getAllIncidents()
    val activeIncidents: Flow<List<IncidentEntity>> = database.incidentDao().getActiveIncidents()
    val latestActiveIncident: Flow<IncidentEntity?> = database.incidentDao().getLatestActiveIncident()
    val activeTrip: Flow<TripEntity?> = database.tripDao().getActiveTrip()
    val allTrips: Flow<List<TripEntity>> = database.tripDao().getAllTrips()
    val allEvidence: Flow<List<EvidenceEntity>> = database.evidenceDao().getAllEvidence()
    val auditLogs: Flow<List<AuditLogEntity>> = database.auditLogDao().getAllLogs()
    val safetyGuides: Flow<List<SafetyGuideEntity>> = database.safetyGuideDao().getAllGuides()
    val riskAssessments = database.riskAssessmentDao().getRecentAssessments()
    val escalationWorkflows = database.escalationWorkflowDao().getAllWorkflows()
    val pointsLedger = database.pointsLedgerDao().getAllEntries()

    init {
        com.example.service.R3CriticalMonitorWorker.schedulePeriodicMonitor(context)
        repositoryScope.launch {
            val zones = database.highRiskZoneDao().getActiveZonesOnce()
            if (zones.isNotEmpty()) {
                geofenceManager.registerGoogleGeofences(zones)
            }
        }
        repositoryScope.launch {
            bleEmergencyRelay.discoveredEmergencies.collect { bleList ->
                hexagonalRescueManager.updateResponderInputs(
                    bleDiscoveries = bleList,
                    myLocation = realLocation.value
                )
            }
        }
        repositoryScope.launch {
            try {
                firestoreRiskRepository.observeActiveHexRescueRequests().collect { cloudAlerts ->
                    hexagonalRescueManager.updateResponderInputs(
                        cloudAlerts = cloudAlerts,
                        myLocation = realLocation.value
                    )
                }
            } catch (_: Exception) {
            }
        }
        repositoryScope.launch {
            networkMonitor.isOnline.collect { online ->
                liveRadarManager.setConnectivityOnline(online)
            }
        }
        // Automatically sync finalized .m4a voice recordings (SOS & Fake Call Guard) to Evidence Vault + Guardians
        audioRecorder.onRecordingFinalized = { incId, shareableFile ->
            repositoryScope.launch {
                try {
                    val synced = syncEvidenceFilesToCloud(
                        incidentId = incId,
                        extraMediaFiles = listOf("AUDIO" to shareableFile)
                    )
                    if (incId.startsWith("FAKECALL-", ignoreCase = true)) {
                        val cfg = fakeCallManager.config.value
                        if (cfg.autoRecordAndShare) {
                            dispatchFakeCallAutoProtection(
                                config = cfg.copy(incidentId = incId),
                                location = getCurrentLocationOrNull(),
                                audioFile = shareableFile
                            )
                        }
                    } else if (synced && networkMonitor.isInternetCurrentlyAvailable()) {
                        val inc = database.incidentDao().getIncidentById(incId)
                        if (inc != null) {
                            publishEmergencyDispatchToGuardians(
                                incidentId = incId,
                                alertType = inc.type,
                                status = "CAPTURED",
                                lat = inc.latitude,
                                lng = inc.longitude,
                                accuracyMeters = inc.accuracyMeters,
                                locDesc = inc.locationDescription,
                                dualResult = _lastDualCameraResult.value,
                                deviceLocked = false
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SafetyRepository", "Auto audio vault sync note: ${e.message}")
                }
            }
        }
    }

    fun setTravelBumpFilterEnabled(enabled: Boolean) {
        _travelBumpFilterEnabled.value = enabled
        val snap = riskEngine.classifyLocationIntelligence(
            location = realLocation.value,
            batteryPercent = sensorsManager.captureDeviceSensorSnapshot(realLocation.value).batteryPercent
        )
        _currentRiskAssessment.value = riskEngine.evaluate(
            PerceptionObservation(
                locationSnapshot = snap,
                dynamicJerkG = sensorsManager.lastShakeGForce.value,
                isNetworkOnline = networkMonitor.isInternetCurrentlyAvailable(),
                travelBumpFilterEnabled = enabled
            )
        )
    }

    fun refreshNominalOrTelemetryRiskAssessment(
        routeCorridorBreached: Boolean = false,
        prolongedStopSeconds: Long = 0L,
        inHighRiskZone: Boolean = nearestHighRiskZone.value != null,
        highRiskZoneName: String? = nearestHighRiskZone.value?.first?.name
    ): RiskAssessmentResult {
        val loc = realLocation.value
        val sensorSnap = sensorsManager.captureDeviceSensorSnapshot(loc)
        val locSnap = riskEngine.classifyLocationIntelligence(loc, sensorSnap.batteryPercent)
        val result = riskEngine.evaluate(
            PerceptionObservation(
                locationSnapshot = locSnap,
                dynamicJerkG = sensorSnap.dynamicJerkG,
                sustainedTravelSpeedKmh = locSnap.speedKmh,
                inHighRiskZone = inHighRiskZone,
                highRiskZoneName = highRiskZoneName,
                routeCorridorBreached = routeCorridorBreached,
                prolongedStopSeconds = prolongedStopSeconds,
                isNetworkOnline = networkMonitor.isInternetCurrentlyAvailable(),
                explicitSosButtonClicked = false,
                travelBumpFilterEnabled = _travelBumpFilterEnabled.value
            )
        )
        _currentRiskAssessment.value = result
        return result
    }

    suspend fun addHighRiskZone(zone: HighRiskZoneEntity) = withContext(Dispatchers.IO) {
        database.highRiskZoneDao().insertZone(zone)
        val activeZones = database.highRiskZoneDao().getActiveZonesOnce()
        geofenceManager.registerGoogleGeofences(activeZones)
    }

    suspend fun deleteHighRiskZone(zoneId: String) = withContext(Dispatchers.IO) {
        database.highRiskZoneDao().deleteZone(zoneId)
    }

    suspend fun saveRoutine(routine: SavedRoutineEntity) = withContext(Dispatchers.IO) {
        database.savedRoutineDao().insertRoutine(routine)
    }

    fun getIncidentEvents(incidentId: String): Flow<List<IncidentEventEntity>> {
        return database.incidentEventDao().getEventsForIncident(incidentId)
    }

    suspend fun saveUserProfile(profile: UserProfileEntity) = withContext(Dispatchers.IO) {
        database.userDao().saveUserProfile(profile)
    }

    suspend fun setSilentSosMode(enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = database.userDao().getUserProfileOnce() ?: UserProfileEntity()
        database.userDao().saveUserProfile(
            current.copy(
                silentSosMode = enabled,
                sirenBeaconEnabled = !enabled
            )
        )
    }

    suspend fun setAntiSnatchEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = database.userDao().getUserProfileOnce() ?: UserProfileEntity()
        database.userDao().saveUserProfile(current.copy(antiSnatchEnabled = enabled))
    }

    suspend fun setAutoLockOnSos(enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = database.userDao().getUserProfileOnce() ?: UserProfileEntity()
        database.userDao().saveUserProfile(current.copy(autoLockOnSos = enabled))
    }

    fun captureEvidencePhotoNow(
        incidentId: String,
        onCaptured: (File?) -> Unit
    ) {
        cameraVideoRecorder.captureSingleSnapshot(incidentId) { photoFile ->
            if (photoFile != null) {
                repositoryScope.launch {
                    val paths = (cameraVideoRecorder.frontPhotoPaths.value + cameraVideoRecorder.rearPhotoPaths.value + photoFile.absolutePath).distinct()
                    for (path in paths) {
                        val f = File(path)
                        if (f.exists() && f.length() > 0L) {
                            persistCameraEvidenceFile(
                                incidentId = incidentId,
                                file = f,
                                fileType = "PHOTO_EVIDENCE",
                                title = "Emergency Snapshot (${f.name})"
                            )
                        }
                    }
                }
            }
            onCaptured(photoFile)
        }
    }

    internal suspend fun persistCameraEvidenceFile(
        incidentId: String,
        file: File,
        fileType: String,
        title: String
    ) {
        try {
            if (!file.exists() || file.length() <= 0L) return
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            val evidence = EvidenceEntity(
                evidenceId = "EVD-${if (fileType.contains("VIDEO")) "VID" else "IMG"}-${System.currentTimeMillis() % 100000}-${(10..99).random()}",
                incidentId = incidentId,
                userId = "USER-001",
                fileType = fileType,
                title = title,
                fileSizeBytes = file.length(),
                sha256Hash = hash,
                uploadTime = System.currentTimeMillis(),
                storageLocation = file.absolutePath,
                previewText = "Cached locally at ${file.name} (${file.length() / 1024} KB)"
            )
            database.evidenceDao().insertEvidence(evidence)
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Failed to save $fileType evidence to vault: ${e.message}")
        }
    }

    suspend fun addTrustedContact(contact: TrustedContactEntity) = withContext(Dispatchers.IO) {
        val id = database.trustedContactDao().insertContact(contact)
        if (contact.isCoreFamily()) {
            goodCitizenManager.addCoreFamilyTrustBonus(
                contactKey = "${contact.phone}_${contact.relationship}",
                relationship = contact.relationship
            )
        }
        val all = database.trustedContactDao().getAllContactsOnce()
        val coreCount = all.count { it.isCoreFamily() }
        goodCitizenManager.checkAutoVerificationFromCircle(all.size, coreCount)
        if (Firebase.auth.currentUser != null) {
            runCatching { circleAndAiSyncRepository.syncUpsertTrustedContact(contact) }
        }
        id
    }

    suspend fun updateTrustedContact(contact: TrustedContactEntity) = withContext(Dispatchers.IO) {
        database.trustedContactDao().updateContact(contact)
        if (contact.isCoreFamily()) {
            goodCitizenManager.addCoreFamilyTrustBonus(
                contactKey = "${contact.phone}_${contact.relationship}",
                relationship = contact.relationship
            )
        }
        val all = database.trustedContactDao().getAllContactsOnce()
        val coreCount = all.count { it.isCoreFamily() }
        goodCitizenManager.checkAutoVerificationFromCircle(all.size, coreCount)
        if (Firebase.auth.currentUser != null) {
            runCatching { circleAndAiSyncRepository.syncUpsertTrustedContact(contact) }
        }
    }

    suspend fun deleteTrustedContact(id: Long) = withContext(Dispatchers.IO) {
        val target = database.trustedContactDao().getAllContactsOnce().firstOrNull { it.id == id }
        database.trustedContactDao().deleteContact(id)
        if (target != null && Firebase.auth.currentUser != null) {
            runCatching { circleAndAiSyncRepository.syncDeleteTrustedContact(target) }
        }
    }

    suspend fun syncWithCloudOnSignIn() = withContext(Dispatchers.IO) {
        if (Firebase.auth.currentUser == null) return@withContext
        try {
            // 1. Two-way Trusted Contacts sync (preserve local & restore any cloud contacts after reinstall)
            val localContacts = database.trustedContactDao().getAllContactsOnce()
            val localPhones = localContacts.mapNotNull {
                PhoneNormalizer.canonicalPhoneKey(it.phone).takeIf { k -> k.isNotBlank() }
            }.toMutableSet()

            val cloudContacts = circleAndAiSyncRepository.fetchCloudTrustedContacts().getOrNull().orEmpty()
            for (cloudContact in cloudContacts) {
                val key = PhoneNormalizer.canonicalPhoneKey(cloudContact.phone)
                if (key.isNotBlank() && localPhones.add(key)) {
                    database.trustedContactDao().insertContact(cloudContact)
                }
            }
            for (local in localContacts) {
                circleAndAiSyncRepository.syncUpsertTrustedContact(local)
            }

            // 2. Two-way Sovereign TFLite AI learning calibration & tier sync
            val cloudAi = circleAndAiSyncRepository.fetchCloudAiSafetyState().getOrNull()
            if (cloudAi != null) {
                userRoutineLearner.applyCloudAiState(cloudAi)
            }
            circleAndAiSyncRepository.syncPushAiSafetyState(userRoutineLearner.exportAiState())
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Cloud circle/AI sync note: ${e.message}")
        }
    }

    suspend fun syncEvidenceFilesToCloud(
        incidentId: String,
        extraMediaFiles: List<Pair<String, File>> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        if (Firebase.auth.currentUser == null || !networkMonitor.isInternetCurrentlyAvailable()) {
            return@withContext false
        }
        val contacts = database.trustedContactDao().getActiveContacts()
        val authorizedEmails = contacts.map { it.email.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.distinct()
        val rawPhones = contacts.map { it.phone.trim() }.filter { it.isNotBlank() }.distinct()
        val (authorizedUids, _) = try {
            firestoreProfileRepository.resolveInstalledGuardiansByPhones(rawPhones)
        } catch (_: Exception) {
            emptyList<String>() to emptyMap()
        }
        evidenceSyncManager.syncPendingEvidenceToCloud(
            incidentId = incidentId,
            authorizedUids = authorizedUids,
            authorizedEmails = authorizedEmails,
            extraMediaFiles = extraMediaFiles
        )
    }

    suspend fun dispatchFakeCallAutoProtection(
        config: com.example.util.FakeCallConfig,
        location: Location? = null,
        audioFile: File? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val incId = config.incidentId.ifBlank { "FAKECALL-${System.currentTimeMillis() % 100000}" }
        val liveLoc = location ?: getCurrentLocationOrNull()
        val hasGps = liveLoc != null
        val lat = liveLoc?.latitude ?: 0.0
        val lng = liveLoc?.longitude ?: 0.0
        val acc = liveLoc?.accuracy ?: 0f
        val area = emergencyInfo.value.locality
        val locDesc = if (hasGps) {
            "${area.takeIf { it.isNotBlank() && !it.startsWith("Acquiring") && !it.startsWith("Detecting") }?.let { "$it • " } ?: ""}Lat: ${String.format(Locale.US, "%.5f", lat)}, Lng: ${String.format(Locale.US, "%.5f", lng)}"
        } else {
            "GPS signal pending"
        }

        val isOnlineNow = networkMonitor.isInternetCurrentlyAvailable()
        var anySent = false

        if (isOnlineNow) {
            if (audioFile != null && audioFile.exists()) {
                syncEvidenceFilesToCloud(incId, listOf("AUDIO" to audioFile))
            }
            val pushed = publishEmergencyDispatchToGuardians(
                incidentId = incId,
                alertType = "FAKE_CALL_GUARD",
                status = if (audioFile != null) "CAPTURED" else "ACTIVE",
                lat = lat,
                lng = lng,
                accuracyMeters = acc,
                locDesc = locDesc,
                dualResult = null,
                deviceLocked = false
            )
            if (pushed) anySent = true
        }

        val contacts = database.trustedContactDao().getActiveContacts()
        val phones = contacts.map { it.phone.trim() }.filter { it.isNotBlank() }.distinct()
        if (phones.isNotEmpty() && config.autoRecordAndShare) {
            val user = database.userDao().getUserProfileOnce()
            val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
            val battery = sensorsManager.captureDeviceSensorSnapshot(liveLoc).batteryPercent
            val mapUrl = if (hasGps) "https://maps.google.com/?q=$lat,$lng" else "GPS acquiring"
            val audioNote = if (audioFile != null) "Voice audio (.m4a) recorded & uploaded to Cloud Vault." else "Live microphone guard & call shield active."
            val smsBody = "[RAKSHAONE FAKE CALL GUARD] $userName activated Fake Call (${if (config.isAiProactive) "AI Proactive" else "Manual"}). $audioNote Battery: $battery%. GPS: $mapUrl"

            postPreFilledSmsNotification(
                notificationId = ("FC_${incId}").hashCode(),
                title = "Tap to Send Fake Call SMS (${phones.size} Contacts)",
                subtitle = "RakshaOne app high alert + audio synced • Tap to confirm SMS",
                phonesJoined = phones.joinToString(";"),
                smsBody = smsBody
            )
        }
        anySent
    }

    suspend fun downloadGuardianAudio(incidentId: String): File? =
        evidenceSyncManager.downloadAndAssembleMediaFile(incidentId, "AUDIO")

    suspend fun downloadGuardianVideo(incidentId: String): File? =
        evidenceSyncManager.downloadAndAssembleMediaFile(incidentId, "VIDEO")

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationOrNull(): Location? = withContext(Dispatchers.IO) {
        try {
            realtimeEmergencyService.refreshRealLocation()
                ?: fusedLocationClient.lastLocation.await()
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val loc = getCurrentLocationOrNull()
        if (loc != null) Pair(loc.latitude, loc.longitude) else Pair(0.0, 0.0)
    }

    private val _lastCameraCaptureCompleteIncidentId = MutableStateFlow<String?>(null)
    val lastCameraCaptureCompleteIncidentId: StateFlow<String?> = _lastCameraCaptureCompleteIncidentId.asStateFlow()

    data class SosDispatchResult(
        val incident: IncidentEntity,
        val hasRealGps: Boolean,
        val audioRecordingStarted: Boolean,
        val smsServiceStarted: Boolean,
        val contactsCount: Int,
        val isOnlineAtTrigger: Boolean,
        val isWhatsAppAvailable: Boolean,
        val smsFallbackTriggered: Boolean,
        val fallbackReason: String,
        val queuedOfflineBackup: Boolean,
        val cloudGuardianDispatched: Boolean = false
    )

    /**
     * Activate SOS using real-time coordinates from FusedLocationWrapper & SosAlertService:
     * 1. Check live internet & WhatsApp availability for automatic SMS Fallback routing
     * 2. Use resolved real Location (never hardcoded fake coordinates)
     * 3. Start encrypted background audio recording + automated CameraX 30s silent video & photos
     * 4. Create Incident, IncidentEvent, OfflineAlert backup (when offline), and AuditLog records in Room
     * 5. Start SosAlertService to dispatch emergency carrier SMS with live GPS + SHA-256 vault info
     */
    suspend fun triggerSosWithResult(
        userComment: String = "Emergency SOS Triggered",
        resolvedLocation: Location? = null,
        isSnatchTrigger: Boolean = false,
        triggerOrigin: TriggerOrigin = TriggerOrigin.USER_EXPLICIT_SOS_COUNTDOWN,
        silentStealth: Boolean? = null,
        deviceLocked: Boolean = false
    ): SosDispatchResult = withContext(Dispatchers.IO) {
        _lastCameraCaptureCompleteIncidentId.value = null
        _lastDualCameraResult.value = null
        val isOnlineNow = networkMonitor.isInternetCurrentlyAvailable()
        val isWhatsAppAvailable = videoEvidenceManager.isWhatsAppInstalled
        val fallbackReason = when {
            !isOnlineNow -> "INTERNET_UNAVAILABLE"
            !isWhatsAppAvailable -> "WHATSAPP_UNAVAILABLE"
            else -> "TRUSTED_DUAL_CHANNEL_ACTIVE"
        }
        val smsFallbackTriggered = !isOnlineNow || !isWhatsAppAvailable

        val liveLocation = resolvedLocation ?: getCurrentLocationOrNull()
        val hasRealGps = liveLocation != null
        val lat = liveLocation?.latitude ?: 0.0
        val lng = liveLocation?.longitude ?: 0.0
        val accuracy = liveLocation?.accuracy ?: 0f

        if (liveLocation != null) {
            realtimeEmergencyService.updateFromLocation(liveLocation)
        }

        val user = database.userDao().getUserProfileOnce()
        val isSilentMode = if (isSnatchTrigger) false else (silentStealth ?: (user?.silentSosMode == true))
        if (isSilentMode) {
            sensorsManager.stopSiren()
            sensorsManager.stopStrobe()
        }
        val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
        val timestamp = System.currentTimeMillis()
        val idSeq = (100000..999999).random()
        val incidentId = "INC-${SimpleDateFormat("yyyy", Locale.US).format(Date(timestamp))}-$idSeq"
        val alertType = "SOS"

        val emergencyLocality = realtimeEmergencyService.emergencyInfo.value.locality
        val locDesc = if (hasRealGps) {
            val baseCoords = "Lat: ${String.format(Locale.US, "%.5f", lat)}, Lng: ${String.format(Locale.US, "%.5f", lng)} (±${accuracy.toInt()}m)"
            if (emergencyLocality.isNotBlank() && !emergencyLocality.startsWith("Acquiring") && !emergencyLocality.startsWith("Detecting")) {
                "$emergencyLocality • $baseCoords"
            } else {
                baseCoords
            }
        } else {
            "GPS signal unavailable (Location disabled or permission denied)"
        }

        // Run Risk Intelligence Engine for explicit user SOS -> R3_CRITICAL
        val sensorSnap = sensorsManager.captureDeviceSensorSnapshot(liveLocation)
        val locSnap = riskEngine.classifyLocationIntelligence(liveLocation, sensorSnap.batteryPercent, timestamp)
        val observation = PerceptionObservation(
            timestampMillis = timestamp,
            locationSnapshot = locSnap,
            dynamicJerkG = sensorSnap.dynamicJerkG,
            sustainedTravelSpeedKmh = locSnap.speedKmh,
            inHighRiskZone = nearestHighRiskZone.value != null,
            highRiskZoneName = nearestHighRiskZone.value?.first?.name,
            isNetworkOnline = isOnlineNow,
            explicitSosButtonClicked = triggerOrigin == TriggerOrigin.USER_EXPLICIT_SOS_COUNTDOWN,
            explicitInstantSosClicked = triggerOrigin == TriggerOrigin.USER_EXPLICIT_INSTANT_SOS,
            explicitDuressPinEntered = triggerOrigin == TriggerOrigin.USER_EXPLICIT_DURESS_PIN,
            travelBumpFilterEnabled = _travelBumpFilterEnabled.value
        )
        val assessment = riskEngine.evaluate(observation, incidentId = incidentId)
        _currentRiskAssessment.value = assessment

        val incident = IncidentEntity(
            incidentId = incidentId,
            userId = user?.id ?: "USER-001",
            type = alertType,
            priority = "CRITICAL",
            status = "CREATED",
            latitude = lat,
            longitude = lng,
            accuracyMeters = accuracy,
            locationDescription = locDesc,
            description = userComment,
            createdAt = timestamp,
            updatedAt = timestamp,
            assignedUnit = null,
            operatorNotes = "Risk: ${assessment.riskLevel.name} (${assessment.triggerOrigin.name})",
            isSynced = isOnlineNow
        )

        database.incidentDao().insertIncident(incident)

        database.incidentEventDao().insertEvent(
            IncidentEventEntity(
                incidentId = incidentId,
                timestamp = timestamp,
                oldStatus = null,
                newStatus = "CREATED",
                actor = "$userName (Citizen)",
                note = buildString {
                    append(if (hasRealGps) "${assessment.riskLevel.label} activated at $locDesc" else "${assessment.riskLevel.label} activated (GPS fix pending)")
                    append(" • Trigger: ${assessment.triggerOrigin.name} • Channel: $fallbackReason")
                }
            )
        )

        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = timestamp,
                actor = userName,
                role = "CITIZEN",
                action = "R3_EXPLICIT_SOS_ACTIVATED",
                targetId = incidentId,
                details = "${assessment.riskLevel.name} via ${assessment.triggerOrigin.name}. GPS=($lat,$lng,±${accuracy}m) Internet=$isOnlineNow WhatsApp=$isWhatsAppAvailable"
            )
        )

        // STEP 0: Tactile confirmation (subtle single tick in Silent Stealth Mode; full Morse pattern otherwise)
        if (isSilentMode) {
            hapticManager.vibrateSosButtonTouch()
        } else {
            hapticManager.vibrateEmergencySosTriggered()
        }

        if (hasRealGps) {
            try {
                liveRadarManager.publishLocation(
                    lat = lat,
                    lng = lng,
                    bearing = liveLocation?.bearing ?: 0f,
                    speed = liveLocation?.speed ?: 0f,
                    battery = sensorSnap.batteryPercent,
                    sharingActive = true,
                    ownerName = userName,
                    isEmergencyVictim = true,
                    forceImmediate = true
                )
            } catch (_: Exception) {
            }
        }

        // Publish immediate zero-manual-interference Guardian Cloud Dispatch if online + Google signed in
        val initialCloudDispatched = if (isOnlineNow) {
            publishEmergencyDispatchToGuardians(
                incidentId = incidentId,
                alertType = alertType,
                status = "ACTIVE",
                lat = lat,
                lng = lng,
                accuracyMeters = accuracy,
                locDesc = locDesc,
                dualResult = null,
                deviceLocked = deviceLocked
            )
        } else false

        // STEP 0.5: Non-Bypassable R3 Policy Gate -> Prepare 112 Dossier & Tokenized Portal + Hex & BLE Broadcast
        try {
            val medicalText = user?.let {
                "Blood: ${it.bloodGroup.ifBlank { "Unknown" }} | Allergies: ${it.allergies.ifBlank { "None" }}"
            } ?: "Medical Info Pending"
            val dossierResult = escalationOrchestrator.authorizeAndPreparePoliceEscalation(
                incidentId = incidentId,
                assessment = assessment,
                location = liveLocation,
                localityName = emergencyLocality,
                medicalSummary = medicalText,
                evidenceHashesSummary = "Audio (.m4a) + Dual CameraX (.mp4/.jpg) SHA-256 capture active",
                userNote = userComment
            )
            val policeAuthorized = dossierResult.isSuccess &&
                EmergencyEscalationPolicyEngine.isPoliceEscalationEligible(assessment)

            val hexAlert = hexagonalRescueManager.broadcastOutgoingHexSos(
                incidentId = incidentId,
                victimName = userName,
                victimLat = lat,
                victimLng = lng,
                locationDescription = locDesc,
                evidenceSummary = "Audio + Dual CameraX evidence capturing",
                policeAuthorized = policeAuthorized,
                victimUid = Firebase.auth.currentUser?.uid.orEmpty()
            )
            escalationOrchestrator.setHexRequestId(incidentId, hexAlert.alertId)
            escalationOrchestrator.markChannelDispatched(
                incidentId = incidentId,
                channel = "BLE_RELAY",
                deliveryState = if (bleEmergencyRelay.relayStatus.value.isAdvertising) ReceiptDeliveryState.SENT else ReceiptDeliveryState.PENDING,
                details = bleEmergencyRelay.relayStatus.value.statusMessage
            )
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Escalation orchestrator step note: ${e.message}")
        }

        // STEP 1: Automatic 12s encrypted + shareable .m4a audio evidence recording
        val audioDuration = if (isSnatchTrigger) 11 else 12
        val audioStarted = try {
            audioRecorder.startAutomaticSosRecording(
                incidentId = incidentId,
                durationSeconds = audioDuration
            )
        } catch (_: Exception) {
            false
        }

        // STEP 1.5: Automated Dual-Camera CameraX capture (Front face + Rear scene videos & photos)
        try {
            cameraVideoRecorder.startAutomatedSosCapture(
                incidentId = incidentId,
                frontDurationSec = if (isSnatchTrigger) 5 else 8,
                rearDurationSec = if (isSnatchTrigger) 6 else 12,
                onPhotoCaptured = { photoFile ->
                    repositoryScope.launch {
                        persistCameraEvidenceFile(
                            incidentId = incidentId,
                            file = photoFile,
                            fileType = "PHOTO_EVIDENCE",
                            title = if (photoFile.name.startsWith("FRONT_", true)) {
                                "Front Camera Snapshot (${photoFile.name})"
                            } else {
                                "Rear Camera Snapshot (${photoFile.name})"
                            }
                        )
                        val photoKind = if (photoFile.name.startsWith("FRONT_", true)) "PHOTO_FRONT" else "PHOTO_REAR"
                        syncEvidenceFilesToCloud(incidentId, listOf(photoKind to photoFile))
                        database.incidentEventDao().insertEvent(
                            IncidentEventEntity(
                                incidentId = incidentId,
                                timestamp = System.currentTimeMillis(),
                                oldStatus = "ACTIVE",
                                newStatus = "EVIDENCE_PHOTO",
                                actor = "Dual CameraX Manager",
                                note = "Captured & auto-synced ${if (photoFile.name.startsWith("FRONT_", true)) "Front" else "Rear"} snapshot (${photoFile.length() / 1024} KB)."
                            )
                        )
                    }
                },
                onVideoRecorded = { _ -> },
                onDualSequenceCompleted = { dualResult ->
                    _lastDualCameraResult.value = dualResult
                    repositoryScope.launch {
                        for (videoPath in dualResult.videoPaths) {
                            val videoFile = File(videoPath)
                            val lensLabel = if (videoFile.name.startsWith("FRONT_", true)) "Front Camera" else "Rear Camera"
                            persistCameraEvidenceFile(
                                incidentId = incidentId,
                                file = videoFile,
                                fileType = "VIDEO_EVIDENCE",
                                title = "$lensLabel Silent Video (${videoFile.name})"
                            )
                        }
                        dualResult.primaryVideoPath?.let { primaryPath ->
                            database.incidentDao().getIncidentById(incidentId)?.let { inc ->
                                database.incidentDao().updateIncident(
                                    inc.copy(
                                        videoEvidencePath = primaryPath,
                                        operatorNotes = ((inc.operatorNotes ?: "").trim() +
                                            " • Dual CameraX: ${dualResult.videoPaths.size} video(s), F:${dualResult.frontPhotoPaths.size}/R:${dualResult.rearPhotoPaths.size} photos").trimStart(' ', '•')
                                    )
                                )
                            }
                        }
                        if (dualResult.videoPaths.isNotEmpty() || dualResult.allPhotoPaths.isNotEmpty()) {
                            database.incidentEventDao().insertEvent(
                                IncidentEventEntity(
                                    incidentId = incidentId,
                                    timestamp = System.currentTimeMillis(),
                                    oldStatus = "ACTIVE",
                                    newStatus = "EVIDENCE_VIDEO",
                                    actor = "Dual CameraX Manager",
                                    note = "Dual-camera capture & Cloud Vault sync finished: ${dualResult.videoPaths.size} video(s), ${dualResult.frontPhotoPaths.size} front + ${dualResult.rearPhotoPaths.size} rear photo(s)."
                                )
                            )
                        }
                        if (networkMonitor.isInternetCurrentlyAvailable()) {
                            syncEvidenceFilesToCloud(incidentId)
                            publishEmergencyDispatchToGuardians(
                                incidentId = incidentId,
                                alertType = alertType,
                                status = "CAPTURED",
                                lat = lat,
                                lng = lng,
                                accuracyMeters = accuracy,
                                locDesc = locDesc,
                                dualResult = dualResult,
                                deviceLocked = deviceLocked
                            )
                        }
                        _lastCameraCaptureCompleteIncidentId.value = incidentId
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Failed to start automated Dual-Camera CameraX capture: ${e.message}")
        }

        // STEP 2: Start SosAlertService background service (Fallback SMS / Dual-Channel GPS SMS + Offline Queue)
        var smsServiceStarted = false
        var contactsCount = 0
        var queuedOfflineBackup = false
        try {
            val contacts = database.trustedContactDao().getActiveContacts()
            contactsCount = contacts.size
            val phones = contacts.map { it.phone }.filter { it.isNotBlank() }
            val emails = contacts.map { it.email }.filter { it.isNotBlank() }

            val liveBattery = sensorsManager.captureDeviceSensorSnapshot(liveLocation).batteryPercent
            SosAlertService.start(
                context = context,
                incidentId = incidentId,
                userNote = userComment,
                fallbackLocation = liveLocation,
                fallbackReason = fallbackReason,
                batteryPercent = liveBattery,
                riskLevel = assessment.riskLevel.name
            )
            smsServiceStarted = true

            if (phones.isNotEmpty() || emails.isNotEmpty()) {
                val backupBody = SosAlertService.buildEmergencySmsBody(
                    userName = userName,
                    incidentId = incidentId,
                    latitude = if (hasRealGps) lat else null,
                    longitude = if (hasRealGps) lng else null,
                    accuracyMeters = if (hasRealGps) accuracy else null,
                    userNote = userComment,
                    timestampMillis = timestamp,
                    fallbackReason = fallbackReason,
                    batteryPercent = liveBattery
                )

                if (!isOnlineNow) {
                    database.offlineAlertDao().insertAlert(
                        OfflineAlertEntity(
                            alertId = "OFFLINE-SOS-$timestamp-$idSeq",
                            incidentId = incidentId,
                            alertType = "SOS_OFFLINE_BACKUP",
                            latitude = lat,
                            longitude = lng,
                            accuracyMeters = accuracy,
                            messageText = backupBody,
                            recipientPhones = phones.joinToString(";"),
                            recipientEmails = emails.joinToString(";"),
                            status = "PENDING_CONNECTIVITY"
                        )
                    )
                    queuedOfflineBackup = true
                }
            }

            if (isOnlineNow) {
                syncEvidenceFilesToCloud(incidentId)
            }
            // Always broadcast BLE emergency beacon for severe R3 SOS (per nearby Bluetooth relay policy)
            bleEmergencyRelay.broadcastEmergencyBeacon(
                incidentId = incidentId,
                lat = lat,
                lng = lng,
                evidenceSummary = "R3 SOS Audio + CameraX active"
            )
            // Arm WorkManager R3_CRITICAL Monitor to guarantee audio/video capture + Firestore upload completion
            if (assessment.riskLevel.name == "R3_CRITICAL") {
                com.example.service.R3CriticalMonitorWorker.triggerImmediateMonitor(
                    context = context,
                    incidentId = incidentId,
                    riskLevel = assessment.riskLevel.name,
                    reason = "SOS_${assessment.triggerOrigin.name}"
                )
            }
        } catch (_: Exception) {
        }

        if (!isSilentMode) {
            triggerEmergencyHaptic()
        }
        SosDispatchResult(
            incident = incident,
            hasRealGps = hasRealGps,
            audioRecordingStarted = audioStarted,
            smsServiceStarted = smsServiceStarted,
            contactsCount = contactsCount,
            isOnlineAtTrigger = isOnlineNow,
            isWhatsAppAvailable = isWhatsAppAvailable,
            smsFallbackTriggered = smsFallbackTriggered,
            fallbackReason = fallbackReason,
            queuedOfflineBackup = queuedOfflineBackup,
            cloudGuardianDispatched = initialCloudDispatched
        )
    }

    suspend fun publishEmergencyDispatchToGuardians(
        incidentId: String,
        alertType: String,
        status: String,
        lat: Double,
        lng: Double,
        accuracyMeters: Float,
        locDesc: String,
        dualResult: DualCameraCaptureResult? = null,
        deviceLocked: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Firebase.auth.currentUser == null) return@withContext false
            val user = database.userDao().getUserProfileOnce()
            val ownerName = user?.name?.takeIf { it.isNotBlank() }
                ?: Firebase.auth.currentUser?.displayName
                ?: "Citizen"
            val ownerPhone = user?.phone.orEmpty()
            val contacts = database.trustedContactDao().getActiveContacts()
            val authorizedEmails = contacts.map { it.email.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.distinct()
            val rawPhones = contacts.map { it.phone.trim() }.filter { it.isNotBlank() }.distinct()
            val canonicalPhones = rawPhones.mapNotNull { PhoneNormalizer.canonicalPhoneKey(it).takeIf { k -> k.isNotBlank() } }
            val authorizedPhones = (canonicalPhones + rawPhones).distinct().take(15)
            val (authorizedUids, _) = try {
                firestoreProfileRepository.resolveInstalledGuardiansByPhones(rawPhones)
            } catch (_: Exception) {
                emptyList<String>() to emptyMap()
            }

            val bundle = videoEvidenceManager.resolveIncidentMediaBundle(
                incidentId = incidentId,
                videoPath = dualResult?.primaryVideoPath ?: cameraVideoRecorder.lastVideoPath.value,
                photoPaths = dualResult?.allPhotoPaths ?: cameraVideoRecorder.capturedPhotoPaths.value,
                audioPath = audioRecorder.latestShareableAudioFile.value?.absolutePath,
                videoPaths = dualResult?.videoPaths ?: cameraVideoRecorder.capturedVideoPaths.value,
                audioPaths = audioRecorder.capturedAudioPaths.value
            )
            val frontPath = dualResult?.frontPhotoPaths?.firstOrNull()
                ?: cameraVideoRecorder.frontPhotoPaths.value.firstOrNull()
                ?: bundle.frontPhotoFiles.firstOrNull()?.absolutePath
            val rearPath = dualResult?.rearPhotoPaths?.firstOrNull()
                ?: cameraVideoRecorder.rearPhotoPaths.value.firstOrNull()
                ?: bundle.rearPhotoFiles.firstOrNull()?.absolutePath

            val frontB64 = emergencyDispatchRepository.compressPhotoToBase64(frontPath)
            val rearB64 = emergencyDispatchRepository.compressPhotoToBase64(rearPath)

            val medicalSummary = buildString {
                if (!user?.bloodGroup.isNullOrBlank()) append("Blood: ${user?.bloodGroup}")
                if (!user?.allergies.isNullOrBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append("Allergies: ${user?.allergies}")
                }
                if (!user?.medicalNotes.isNullOrBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append("Notes: ${user?.medicalNotes}")
                }
            }.ifBlank { "No medical notes provided" }

            val mapsUrl = if (lat != 0.0 || lng != 0.0) "https://maps.google.com/?q=$lat,$lng" else "GPS acquiring"
            val batteryPct = sensorsManager.captureDeviceSensorSnapshot(null).batteryPercent

            val res = emergencyDispatchRepository.publishOrUpdateDispatch(
                dispatchId = incidentId,
                ownerName = ownerName,
                ownerPhone = ownerPhone,
                alertType = alertType,
                status = status,
                latitude = lat,
                longitude = lng,
                accuracyMeters = accuracyMeters.toDouble(),
                mapsUrl = mapsUrl,
                locationSummary = locDesc,
                batteryPercent = batteryPct,
                medicalSummary = medicalSummary,
                evidenceSummary = bundle.sha256DigestSummary,
                frontPhotoBase64 = frontB64,
                rearPhotoBase64 = rearB64,
                authorizedEmails = authorizedEmails,
                authorizedPhones = authorizedPhones,
                authorizedUids = authorizedUids,
                deviceLocked = deviceLocked
            )
            res.isSuccess
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Cloud Guardian dispatch skipped/failed: ${e.message}")
            false
        }
    }

    suspend fun logMediaDispatchOutcome(
        incidentId: String,
        result: com.example.util.EmergencyVideoEvidenceManager.MediaFallbackDispatchResult,
        triggerCarrierSmsIfWhatsAppFailed: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.incidentEventDao().insertEvent(
            IncidentEventEntity(
                incidentId = incidentId,
                timestamp = now,
                oldStatus = "ACTIVE",
                newStatus = if (result.usedSmsMmsFallback) "MEDIA_SMS_FALLBACK" else "MEDIA_WHATSAPP_SENT",
                actor = "RakshaOne Evidence Router",
                note = "${result.statusMessage} (${result.videoCount} video, ${result.photoCount} photo(s), reason=${result.fallbackReason})"
            )
        )
        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = now,
                actor = "RakshaOne Evidence Router",
                role = "SYSTEM",
                action = if (result.usedSmsMmsFallback) "SOS_MEDIA_SMS_FALLBACK" else "SOS_MEDIA_WHATSAPP_SENT",
                targetId = incidentId,
                details = result.statusMessage
            )
        )

        if (triggerCarrierSmsIfWhatsAppFailed && result.fallbackReason == "WHATSAPP_FAILED") {
            val incident = database.incidentDao().getIncidentById(incidentId)
            val fallbackLoc = if (incident != null && (incident.latitude != 0.0 || incident.longitude != 0.0)) {
                Location("incident").apply {
                    latitude = incident.latitude
                    longitude = incident.longitude
                    accuracy = incident.accuracyMeters
                }
            } else {
                getCurrentLocationOrNull()
            }
            SosAlertService.start(
                context = context,
                incidentId = incidentId,
                userNote = "WhatsApp failed — Carrier SMS Location + Evidence Vault Fallback (${result.videoCount} video, ${result.photoCount} photos saved)",
                fallbackLocation = fallbackLoc,
                fallbackReason = "WHATSAPP_FAILED"
            )
        }
    }

    suspend fun triggerSos(
        userComment: String = "Emergency SOS Triggered",
        resolvedLocation: Location? = null
    ): IncidentEntity = triggerSosWithResult(userComment, resolvedLocation).incident

    suspend fun decoyCancelSos(incidentId: String) = withContext(Dispatchers.IO) {
        _isDecoyStealthActive.value = true
        database.incidentEventDao().insertEvent(
            IncidentEventEntity(
                incidentId = incidentId,
                timestamp = System.currentTimeMillis(),
                oldStatus = "ACTIVE",
                newStatus = "COERCED_DECOY_ACTIVE",
                actor = "Citizen (Stealth Protocol)",
                note = "Decoy standby UI engaged; live GPS telemetry & silent audio evidence collection remain active."
            )
        )
    }

    suspend fun resolveOrCancelIncident(
        incidentId: String,
        isCancelled: Boolean,
        resolutionNotes: String
    ) = withContext(Dispatchers.IO) {
        _isDecoyStealthActive.value = false
        cameraVideoRecorder.stopRecording()
        hexagonalRescueManager.cancelOrResolveOutgoingSos(incidentId)
        bleEmergencyRelay.stopEmergencyBleBroadcast()
        refreshNominalOrTelemetryRiskAssessment()

        val newStatus = if (isCancelled) "CANCELLED" else "RESOLVED"
        val user = database.userDao().getUserProfileOnce()
        val actorName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
        val timestamp = System.currentTimeMillis()

        // Capture workflow + dossier token references before clearing Room
        val workflow = database.escalationWorkflowDao().getWorkflowOnce(incidentId)
        val dossierAccess = database.dossierAccessDao().getActiveDossierForIncident(incidentId)

        // Purge physical media files from device storage so zero extra space remains used
        val incidentEvidence = database.evidenceDao().getEvidenceForIncidentOnce(incidentId)
        var purgedFilesCount = 0
        for (item in incidentEvidence) {
            val path = item.storageLocation
            if (path.startsWith("/")) {
                try {
                    val file = File(path)
                    if (file.exists() && file.delete()) {
                        purgedFilesCount++
                    }
                } catch (_: Exception) {}
            }
        }
        val safeIncidentId = incidentId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        try {
            val incidentCacheDir = File(context.cacheDir, "emergency_evidence/$safeIncidentId")
            if (incidentCacheDir.exists()) {
                incidentCacheDir.deleteRecursively()
            }
        } catch (_: Exception) {}
        cameraVideoRecorder.clearIncidentMediaState(incidentId)
        audioRecorder.clearIncidentAudioState(incidentId)

        // Empty Vault Room records & pending offline queue for this incident
        database.evidenceDao().deleteEvidenceForIncident(incidentId)
        database.offlineAlertDao().deleteAlertsForIncident(incidentId)
        escalationOrchestrator.clearResolvedDossier(incidentId)

        database.incidentDao().updateIncidentStatus(incidentId, newStatus, timestamp)
        database.incidentDao().updateOperatorNotes(incidentId, resolutionNotes, timestamp)

        database.incidentEventDao().insertEvent(
            IncidentEventEntity(
                incidentId = incidentId,
                timestamp = timestamp,
                oldStatus = "ACTIVE",
                newStatus = newStatus,
                actor = "$actorName (Victim Confirmed)",
                note = "$resolutionNotes • Evidence Vault & cloud dispatches purged ($purgedFilesCount local files deleted)."
            )
        )

        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = timestamp,
                actor = actorName,
                role = "CITIZEN_VICTIM",
                action = if (isCancelled) "SOS_CANCELLED_VAULT_PURGED" else "SOS_RESOLVED_VAULT_PURGED",
                targetId = incidentId,
                details = "Victim confirmed $newStatus. Vault emptied ($purgedFilesCount files). Notes: $resolutionNotes"
            )
        )

        if (Firebase.auth.currentUser != null) {
            try {
                emergencyDispatchRepository.deleteDispatchForIncident(incidentId)
            } catch (_: Exception) {}
            try {
                evidenceSyncManager.purgeIncidentVaultChunks(incidentId)
            } catch (_: Exception) {}
            try {
                firestoreRiskRepository.purgeIncidentCloudArtifacts(
                    incidentId = incidentId,
                    hexRequestId = workflow?.hexRequestId,
                    dossierToken = dossierAccess?.tokenId
                )
            } catch (_: Exception) {}
        }
    }

    // Government Operator Actions (PRD Section 17-20)
    suspend fun operatorAcknowledgeIncident(
        incidentId: String,
        operatorName: String = "Authorized Operator"
    ) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        database.incidentDao().updateIncidentStatus(incidentId, "ACKNOWLEDGED", timestamp)
        database.incidentEventDao().insertEvent(
            IncidentEventEntity(
                incidentId = incidentId,
                timestamp = timestamp,
                oldStatus = "CREATED",
                newStatus = "ACKNOWLEDGED",
                actor = operatorName,
                note = "Incident acknowledged by emergency dispatch center."
            )
        )
        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = timestamp,
                actor = operatorName,
                role = "OPERATOR",
                action = "INCIDENT_ACKNOWLEDGED",
                targetId = incidentId,
                details = "Operator took ownership of incident."
            )
        )
    }

    suspend fun operatorAssignUnit(
        incidentId: String,
        unitName: String,
        operatorName: String = "Authorized Dispatcher"
    ) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        database.incidentDao().assignUnit(incidentId, unitName, timestamp)
        database.incidentEventDao().insertEvent(
            IncidentEventEntity(
                incidentId = incidentId,
                timestamp = timestamp,
                oldStatus = "ACKNOWLEDGED",
                newStatus = "ASSIGNED",
                actor = operatorName,
                note = "Assigned tactical responder unit: $unitName. Dispatched to coordinates."
            )
        )
        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = timestamp,
                actor = operatorName,
                role = "DISPATCHER",
                action = "UNIT_ASSIGNED",
                targetId = incidentId,
                details = "Assigned unit: $unitName"
            )
        )
    }

    suspend fun operatorUpdateStatus(
        incidentId: String,
        newStatus: String, // "RESPONDING", "ON_SCENE" (RESOLVED/CLOSED restricted to victim confirmation)
        notes: String,
        operatorName: String = "Authorized Operator"
    ) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val normalizedStatus = newStatus.uppercase(Locale.US).trim()
        if (normalizedStatus in setOf("RESOLVED", "CLOSED", "CANCELLED")) {
            database.auditLogDao().insertLog(
                AuditLogEntity(
                    timestamp = timestamp,
                    actor = operatorName,
                    role = "OPERATOR",
                    action = "OPERATOR_CLOSE_BLOCKED_VICTIM_ONLY",
                    targetId = incidentId,
                    details = "Blocked operator attempt to mark $incidentId as $normalizedStatus — only the victim may confirm safety & close the case."
                )
            )
            return@withContext
        }
        val currentIncident = database.incidentDao().getIncidentById(incidentId)
        val oldStatus = currentIncident?.status ?: "UNKNOWN"

        database.incidentDao().updateIncidentStatus(incidentId, normalizedStatus, timestamp)
        if (notes.isNotBlank()) {
            database.incidentDao().updateOperatorNotes(incidentId, notes, timestamp)
        }

        database.incidentEventDao().insertEvent(
            IncidentEventEntity(
                incidentId = incidentId,
                timestamp = timestamp,
                oldStatus = oldStatus,
                newStatus = normalizedStatus,
                actor = operatorName,
                note = notes.ifBlank { "Status updated to $normalizedStatus by dispatch." }
            )
        )

        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = timestamp,
                actor = operatorName,
                role = "OPERATOR",
                action = "STATUS_UPDATED",
                targetId = incidentId,
                details = "Transition from $oldStatus to $newStatus. Notes: $notes"
            )
        )
    }

    // Live location tracking & Trip Anomaly Detection state via Google FusedLocationProviderClient
    private val _liveTripLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val liveTripLocation: StateFlow<Pair<Double, Double>?> = _liveTripLocation.asStateFlow()

    private val _tripTrackingActive = MutableStateFlow(false)
    val tripTrackingActive: StateFlow<Boolean> = _tripTrackingActive.asStateFlow()

    private val _tripAnomalyState = MutableStateFlow(TripAnomalyState())
    val tripAnomalyState: StateFlow<TripAnomalyState> = _tripAnomalyState.asStateFlow()

    private var tripLocationCallback: LocationCallback? = null
    private var tripAnchorLat: Double? = null
    private var tripAnchorLng: Double? = null
    private var tripAllowedCorridorMeters: Float = 500f
    private var tripProlongedStopThresholdSeconds: Long = 180L
    private var lastMovementLat: Double? = null
    private var lastMovementLng: Double? = null
    private var lastMovementTimeMs: Long = 0L

    fun observeTripWaypoints(tripId: String): Flow<List<TripWaypointEntity>> =
        database.tripWaypointDao().getWaypointsForTrip(tripId)

    private fun hasTripLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startLiveTripTracking() {
        if (!hasTripLocationPermission()) {
            _tripTrackingActive.value = false
            return
        }
        TripMonitoringService.startOrUpdate(
            context = context,
            title = "RakshaOne Trip Escort Active",
            body = "Logging GPS checkpoints every 5s • Guarding route corridor & unexpected stops"
        )
        if (tripLocationCallback != null) {
            _tripTrackingActive.value = true
            return
        }
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setMinUpdateIntervalMillis(5000L)
                .setMinUpdateDistanceMeters(0f)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    recordTripLocationFixAsync(location)
                }
            }
            tripLocationCallback = callback
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            )
            _tripTrackingActive.value = true
        } catch (se: SecurityException) {
            Log.e("SafetyRepository", "Location permission revoked during trip tracking", se)
            tripLocationCallback = null
            _tripTrackingActive.value = false
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Failed to start FusedLocationProviderClient trip tracking", e)
            tripLocationCallback = null
            _tripTrackingActive.value = false
        }
    }

    private fun parseTripCorridorConfig(trip: TripEntity): Pair<Float, Long> {
        val corridorRegex = Regex("corridor=(\\d+)")
        val stopRegex = Regex("stopSec=(\\d+)")
        val corridor = corridorRegex.find(trip.notes)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: tripAllowedCorridorMeters
        val stopSec = stopRegex.find(trip.notes)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: tripProlongedStopThresholdSeconds
        return Pair(corridor.coerceAtLeast(100f), stopSec.coerceAtLeast(30L))
    }

    private fun recordTripLocationFixAsync(location: Location) {
        repositoryScope.launch {
            recordTripLocationFixNow(location)
        }
    }

    suspend fun recordTripLocationFixNow(
        location: Location,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val active = database.tripDao().getActiveTripOnce()
        if (active == null) {
            withContext(Dispatchers.Main) {
                stopLiveTripTracking()
            }
            return@withContext false
        }
        val lat = location.latitude
        val lng = location.longitude
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        _liveTripLocation.value = Pair(lat, lng)

        database.tripDao().updateTrip(
            active.copy(
                currentLatitude = lat,
                currentLongitude = lng,
                speedKmh = speedKmh
            )
        )
        database.tripWaypointDao().insertWaypoint(
            TripWaypointEntity(
                tripId = active.tripId,
                latitude = lat,
                longitude = lng,
                accuracyMeters = accuracy,
                speedKmh = speedKmh,
                timestamp = nowMillis
            )
        )

        evaluateTripAnomaliesInternal(
            trip = active,
            lat = lat,
            lng = lng,
            speedKmh = speedKmh,
            accuracyMeters = accuracy,
            nowMillis = nowMillis
        )
        recordBreadcrumbAndLowBatteryBlackBox(
            lat = lat,
            lng = lng,
            accuracy = accuracy,
            batteryPercent = sensorsManager.captureDeviceSensorSnapshot(location).batteryPercent,
            areaName = realtimeEmergencyService.emergencyInfo.value.locality,
            activeIncidentId = null
        )
        true
    }

    suspend fun recordBreadcrumbAndLowBatteryBlackBox(
        lat: Double,
        lng: Double,
        accuracy: Float,
        batteryPercent: Int,
        areaName: String,
        activeIncidentId: String?
    ) = withContext(Dispatchers.IO) {
        if (lat == 0.0 && lng == 0.0) return@withContext
        val now = System.currentTimeMillis()
        if (now - lastBreadcrumbSavedAt >= 15_000L) {
            lastBreadcrumbSavedAt = now
            try {
                val geohash = StorageWardenManager.encodeGeohash(lat, lng, precision = 6)
                database.locationBreadcrumbDao().insertBreadcrumb(
                    com.example.data.model.LocationBreadcrumbEntity(
                        latitude = lat,
                        longitude = lng,
                        accuracyMeters = accuracy,
                        batteryPercent = batteryPercent,
                        geohash = geohash,
                        timestamp = now,
                        incidentId = activeIncidentId
                    )
                )
                userRoutineLearner.recordVisit(lat, lng, now)
            } catch (_: Exception) {}
        }

        if (batteryPercent in 1..5 && !_lowBatteryBlackBoxTriggered) {
            _lowBatteryBlackBoxTriggered = true
            try {
                val contacts = database.trustedContactDao().getActiveContacts()
                val user = database.userDao().getUserProfileOnce()
                val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
                val corePhones = contacts.filter { it.isCoreFamily() }.map { it.phone }.filter { it.isNotBlank() }
                val blackBoxAlert = "🔋 LOW BATTERY BLACK-BOX ($batteryPercent%): $userName last seen at ${areaName.ifBlank { "$lat, $lng" }} • https://maps.google.com/?q=$lat,$lng"
                if (corePhones.isNotEmpty()) {
                    database.offlineAlertDao().insertAlert(
                        OfflineAlertEntity(
                            alertId = "BLACKBOX-$now",
                            incidentId = activeIncidentId ?: "BATT-$now",
                            alertType = "BATTERY_BLACK_BOX",
                            latitude = lat,
                            longitude = lng,
                            accuracyMeters = accuracy,
                            messageText = blackBoxAlert,
                            recipientPhones = corePhones.joinToString(";"),
                            recipientEmails = "",
                            status = if (networkMonitor.isOnline.value) "READY_FOR_DISPATCH" else "PENDING_CONNECTIVITY"
                        )
                    )
                }
                liveRadarManager.publishLocation(
                    lat = lat,
                    lng = lng,
                    bearing = 0f,
                    speed = 0f,
                    battery = batteryPercent,
                    sharingActive = true,
                    ownerName = userName,
                    areaName = areaName,
                    hasActiveTrip = database.tripDao().getActiveTripOnce() != null,
                    isSosActive = activeIncidentId != null,
                    contacts = contacts,
                    forceImmediate = true
                )
            } catch (_: Exception) {}
        } else if (batteryPercent > 10) {
            _lowBatteryBlackBoxTriggered = false
        }
    }

    suspend fun tickStationaryDuration(nowMillis: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val active = database.tripDao().getActiveTripOnce() ?: return@withContext
        if (lastMovementTimeMs <= 0L) {
            lastMovementTimeMs = active.startTime
        }
        val (corridorM, stopSecThreshold) = parseTripCorridorConfig(active)
        tripAllowedCorridorMeters = corridorM
        tripProlongedStopThresholdSeconds = stopSecThreshold

        val stationarySec = ((nowMillis - lastMovementTimeMs) / 1000L).coerceAtLeast(0L)
        val currentState = _tripAnomalyState.value
        var stopTriggered = currentState.prolongedStopAlertTriggered
        var alertMsg = currentState.lastAlertMessage
        var alertTs = currentState.lastAlertTimestamp

        if (!stopTriggered && stationarySec >= stopSecThreshold) {
            stopTriggered = true
            alertTs = nowMillis
            val lat = active.currentLatitude ?: tripAnchorLat ?: 0.0
            val lng = active.currentLongitude ?: tripAnchorLng ?: 0.0
            alertMsg = "Prolonged unexpected stop (${stationarySec}s) detected on trip to ${active.destination}"
            dispatchTripAnomalyAlert(
                trip = active,
                anomalyType = "PROLONGED_STOP",
                summary = alertMsg,
                lat = lat,
                lng = lng,
                accuracy = 0f,
                nowMillis = nowMillis
            )
        }

        _tripAnomalyState.value = currentState.copy(
            tripId = active.tripId,
            isMonitoring = true,
            allowedCorridorMeters = corridorM,
            stationaryDurationSeconds = stationarySec,
            prolongedStopThresholdSeconds = stopSecThreshold,
            prolongedStopAlertTriggered = stopTriggered,
            lastAlertMessage = alertMsg,
            lastAlertTimestamp = alertTs
        )
    }

    fun resetTripAnomalyAlertTimer() {
        lastMovementTimeMs = System.currentTimeMillis()
        lastMovementLat = _liveTripLocation.value?.first ?: lastMovementLat
        lastMovementLng = _liveTripLocation.value?.second ?: lastMovementLng
        val current = _tripAnomalyState.value
        _tripAnomalyState.value = current.copy(
            stationaryDurationSeconds = 0L,
            deviationAlertTriggered = false,
            prolongedStopAlertTriggered = false,
            lastAlertMessage = null
        )
    }

    private suspend fun evaluateTripAnomaliesInternal(
        trip: TripEntity,
        lat: Double,
        lng: Double,
        speedKmh: Float,
        accuracyMeters: Float,
        nowMillis: Long
    ) {
        val (corridorM, stopSecThreshold) = parseTripCorridorConfig(trip)
        tripAllowedCorridorMeters = corridorM
        tripProlongedStopThresholdSeconds = stopSecThreshold

        if (tripAnchorLat == null || tripAnchorLng == null || _tripAnomalyState.value.tripId != trip.tripId) {
            tripAnchorLat = lat
            tripAnchorLng = lng
            lastMovementLat = lat
            lastMovementLng = lng
            lastMovementTimeMs = nowMillis
        }

        val anchorDistRes = FloatArray(1)
        Location.distanceBetween(tripAnchorLat!!, tripAnchorLng!!, lat, lng, anchorDistRes)
        val distanceFromAnchor = anchorDistRes[0]

        val moveDistRes = FloatArray(1)
        Location.distanceBetween(
            lastMovementLat ?: lat,
            lastMovementLng ?: lng,
            lat,
            lng,
            moveDistRes
        )
        val movedMeters = moveDistRes[0]

        // Reset prolonged-stop clock whenever device moves >= 25m or travels faster than walking stop (>= 2 km/h)
        if (movedMeters >= 25f || speedKmh >= 2.0f) {
            lastMovementLat = lat
            lastMovementLng = lng
            lastMovementTimeMs = nowMillis
        }

        val stationarySec = ((nowMillis - lastMovementTimeMs) / 1000L).coerceAtLeast(0L)
        val waypointCount = database.tripWaypointDao().getWaypointsForTripOnce(trip.tripId).size

        val prev = _tripAnomalyState.value
        var deviationTriggered = prev.deviationAlertTriggered
        var stopTriggered = prev.prolongedStopAlertTriggered
        var alertMsg = prev.lastAlertMessage
        var alertTs = prev.lastAlertTimestamp

        if (!deviationTriggered && distanceFromAnchor > corridorM) {
            deviationTriggered = true
            alertTs = nowMillis
            alertMsg = "Route deviation (${distanceFromAnchor.toInt()}m outside ${corridorM.toInt()}m safe corridor)"
            dispatchTripAnomalyAlert(
                trip = trip,
                anomalyType = "ROUTE_DEVIATION",
                summary = alertMsg,
                lat = lat,
                lng = lng,
                accuracy = accuracyMeters,
                nowMillis = nowMillis
            )
        }

        if (!stopTriggered && stationarySec >= stopSecThreshold) {
            stopTriggered = true
            alertTs = nowMillis
            alertMsg = "Prolonged unexpected stop (${stationarySec}s without movement)"
            dispatchTripAnomalyAlert(
                trip = trip,
                anomalyType = "PROLONGED_STOP",
                summary = alertMsg,
                lat = lat,
                lng = lng,
                accuracy = accuracyMeters,
                nowMillis = nowMillis
            )
        }

        _tripAnomalyState.value = TripAnomalyState(
            tripId = trip.tripId,
            isMonitoring = true,
            waypointCount = waypointCount,
            anchorLat = tripAnchorLat,
            anchorLng = tripAnchorLng,
            distanceFromAnchorMeters = distanceFromAnchor,
            allowedCorridorMeters = corridorM,
            stationaryDurationSeconds = stationarySec,
            prolongedStopThresholdSeconds = stopSecThreshold,
            deviationAlertTriggered = deviationTriggered,
            prolongedStopAlertTriggered = stopTriggered,
            lastAlertMessage = alertMsg,
            lastAlertTimestamp = alertTs
        )

        if (_tripTrackingActive.value) {
            TripMonitoringService.startOrUpdate(
                context = context,
                title = "Trip to ${trip.destination} • ${String.format(Locale.US, "%.1f", speedKmh)} km/h",
                body = "Waypoints: $waypointCount • Corridor dist: ${distanceFromAnchor.toInt()}m / ${corridorM.toInt()}m • Stop: ${stationarySec}s"
            )
        }
    }

    private suspend fun dispatchTripAnomalyAlert(
        trip: TripEntity,
        anomalyType: String,
        summary: String,
        lat: Double,
        lng: Double,
        accuracy: Float,
        nowMillis: Long
    ) {
        // Evaluate through RiskIntelligenceEngine — Trip anomalies reach at most R1/R2 (NEVER R3/Police 112)
        val tripRisk = refreshNominalOrTelemetryRiskAssessment(
            routeCorridorBreached = anomalyType == "ROUTE_DEVIATION",
            prolongedStopSeconds = if (anomalyType == "PROLONGED_STOP") 180L else 0L,
            inHighRiskZone = nearestHighRiskZone.value != null,
            highRiskZoneName = nearestHighRiskZone.value?.first?.name
        )

        val user = database.userDao().getUserProfileOnce()
        val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
        val mapUrl = if (lat != 0.0 || lng != 0.0) "https://maps.google.com/?q=$lat,$lng" else "GPS acquiring"
        val alertText = "[RAKSHAONE ${tripRisk.riskLevel.code} TRIP ALERT: $anomalyType] $userName • $summary. Destination: ${trip.destination}. Live GPS: $mapUrl"

        val configuredPhones = trip.sharedContactPhones.split(";").map { it.trim() }.filter { it.isNotBlank() }
        val activeContacts = database.trustedContactDao().getActiveContacts()
        val targetPhones = if (configuredPhones.isNotEmpty()) configuredPhones else activeContacts.map { it.phone.trim() }.filter { it.isNotBlank() }
        val emailContacts = activeContacts.filter { it.email.isNotBlank() }

        val incidentId = "TRIP-ALT-${nowMillis % 100000}"
        escalationOrchestrator.recordRiskAssessment(tripRisk, incidentId)

        database.incidentDao().insertIncident(
            IncidentEntity(
                incidentId = incidentId,
                userId = user?.id ?: "USER-001",
                type = "TRIP_$anomalyType",
                priority = if (tripRisk.riskLevel.name == "R2_HIGH") "HIGH" else "MEDIUM",
                status = "CREATED",
                latitude = lat,
                longitude = lng,
                accuracyMeters = accuracy,
                locationDescription = "$summary • Dest: ${trip.destination}",
                description = alertText,
                createdAt = nowMillis,
                updatedAt = nowMillis,
                operatorNotes = "Risk ${tripRisk.riskLevel.name} (112 blocked below R3): Trusted Contacts advisory only.",
                isSynced = networkMonitor.isOnline.value
            )
        )

        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = nowMillis,
                actor = "RakshaOne Trip Guard",
                role = "SYSTEM",
                action = "TRIP_${anomalyType}_ALERT_${tripRisk.riskLevel.code}",
                targetId = trip.tripId,
                details = "$summary -> ${tripRisk.riskLevel.name} notifying ${targetPhones.size} trusted contact(s); police/112 gated OFF."
            )
        )

        if (targetPhones.isNotEmpty()) {
            database.offlineAlertDao().insertAlert(
                OfflineAlertEntity(
                    alertId = "OFFLINE-TRIP-$nowMillis",
                    incidentId = incidentId,
                    alertType = "TRIP_$anomalyType",
                    latitude = lat,
                    longitude = lng,
                    accuracyMeters = accuracy,
                    messageText = alertText,
                    recipientPhones = targetPhones.joinToString(";"),
                    recipientEmails = "",
                    status = if (networkMonitor.isOnline.value) "READY_FOR_DISPATCH" else "PENDING_CONNECTIVITY"
                )
            )
            // Prepare Play-Protect-safe pre-filled SMS notification + Cloud Guardian Dispatch (user taps Send in Messages app)
            com.example.service.CriticalSmsDispatchWorker.enqueue(
                context = context,
                incidentId = incidentId,
                riskLevel = tripRisk.riskLevel.name,
                userNote = summary,
                latitude = if (lat != 0.0 || lng != 0.0) lat else null,
                longitude = if (lat != 0.0 || lng != 0.0) lng else null,
                accuracyMeters = accuracy,
                batteryPercent = sensorsManager.captureDeviceSensorSnapshot(null).batteryPercent,
                fallbackReason = "TRIP_ANOMALY_AUTO",
                phonesJoined = targetPhones.joinToString(";"),
                overrideBody = alertText
            )
        }

        if (networkMonitor.isOnline.value) {
            publishEmergencyDispatchToGuardians(
                incidentId = incidentId,
                alertType = "TRIP_ANOMALY",
                status = "ACTIVE",
                lat = lat,
                lng = lng,
                accuracyMeters = accuracy,
                locDesc = "$summary • Dest: ${trip.destination}",
                dualResult = null,
                deviceLocked = false
            )
        }

        postTripAlertNotification(
            tripId = trip.tripId,
            title = if (anomalyType == "ROUTE_DEVIATION") "Route Deviation Alert (${tripRisk.riskLevel.code})" else "Unexpected Stop Alert (${tripRisk.riskLevel.code})",
            message = summary,
            phonesJoined = targetPhones.joinToString(";"),
            smsBody = alertText
        )
    }

    private fun postTripAlertNotification(
        tripId: String,
        title: String,
        message: String,
        phonesJoined: String,
        smsBody: String
    ) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channelId = "rakshaone_trip_anomalies"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Trip Route Deviation & Stop Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phonesJoined")).apply {
                putExtra("sms_body", smsBody)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val smsPendingIntent = PendingIntent.getActivity(
                context,
                tripId.hashCode(),
                smsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText("$message — Tap to alert trusted contacts")
                .setStyle(NotificationCompat.BigTextStyle().bigText(smsBody))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(smsPendingIntent)
                .addAction(android.R.drawable.ic_menu_send, "Send Alert SMS", smsPendingIntent)
                .build()
            nm.notify(tripId.hashCode(), notification)
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Unable to post trip anomaly notification: ${e.message}")
        }
    }

    fun stopLiveTripTracking() {
        tripLocationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (_: Exception) {}
            tripLocationCallback = null
        }
        TripMonitoringService.stop(context)
        tripAnchorLat = null
        tripAnchorLng = null
        lastMovementLat = null
        lastMovementLng = null
        lastMovementTimeMs = 0L
        _tripTrackingActive.value = false
        _tripAnomalyState.value = TripAnomalyState()
    }

    // Trip Monitoring (PRD Section 14)
    suspend fun startTrip(
        origin: String,
        destination: String,
        plannedMinutes: Int,
        checkInIntervalMinutes: Int,
        sharedContactPhones: List<String> = emptyList(),
        allowedCorridorMeters: Float = 500f,
        prolongedStopThresholdMinutes: Int = 3
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dueTime = now + (checkInIntervalMinutes * 60 * 1000L)
        val tripId = "TRIP-${(1000..9999).random()}"
        val loc = getCurrentLocationOrNull()
        val currentLat = loc?.latitude ?: 0.0
        val currentLng = loc?.longitude ?: 0.0
        val initialAccuracy = if (loc != null && loc.hasAccuracy()) loc.accuracy else 0f
        val initialSpeedKmh = if (loc != null && loc.hasSpeed()) loc.speed * 3.6f else 0f
        val stopSec = (prolongedStopThresholdMinutes.coerceAtLeast(1) * 60L)
        val corridorM = allowedCorridorMeters.coerceAtLeast(100f)

        tripAllowedCorridorMeters = corridorM
        tripProlongedStopThresholdSeconds = stopSec
        if (loc != null) {
            tripAnchorLat = currentLat
            tripAnchorLng = currentLng
            lastMovementLat = currentLat
            lastMovementLng = currentLng
        }
        lastMovementTimeMs = now

        val trip = TripEntity(
            tripId = tripId,
            origin = origin,
            destination = destination,
            plannedDurationMinutes = plannedMinutes,
            checkInIntervalMinutes = checkInIntervalMinutes,
            status = "STARTED",
            startTime = now,
            lastCheckInTime = now,
            nextCheckInDueTime = dueTime,
            notes = "corridor=${corridorM.toInt()};stopSec=$stopSec | Active journey to $destination",
            currentLatitude = currentLat,
            currentLongitude = currentLng,
            speedKmh = initialSpeedKmh,
            sharedContactPhones = sharedContactPhones.joinToString(";")
        )
        database.tripDao().insertTrip(trip)

        if (loc != null) {
            _liveTripLocation.value = Pair(currentLat, currentLng)
            database.tripWaypointDao().insertWaypoint(
                TripWaypointEntity(
                    tripId = tripId,
                    latitude = currentLat,
                    longitude = currentLng,
                    accuracyMeters = initialAccuracy,
                    speedKmh = initialSpeedKmh,
                    timestamp = now
                )
            )
        }

        _tripAnomalyState.value = TripAnomalyState(
            tripId = tripId,
            isMonitoring = true,
            waypointCount = if (loc != null) 1 else 0,
            anchorLat = if (loc != null) currentLat else null,
            anchorLng = if (loc != null) currentLng else null,
            allowedCorridorMeters = corridorM,
            stationaryDurationSeconds = 0L,
            prolongedStopThresholdSeconds = stopSec
        )

        // Start real-time FusedLocationProviderClient interval updates & Foreground Service on main looper
        withContext(Dispatchers.Main) {
            startLiveTripTracking()
        }

        // Pre-filled trip escort SMS draft (Play Protect compliant: zero SEND_SMS)
        if (sharedContactPhones.isNotEmpty()) {
            val mapLink = if (currentLat != 0.0 || currentLng != 0.0) {
                "https://maps.google.com/?q=$currentLat,$currentLng"
            } else {
                "GPS fix acquiring"
            }
            val initialMsg = "[RakshaOne Trip Escort] Started trip from $origin to $destination (${plannedMinutes}m). Live GPS: $mapLink. Check-in every ${checkInIntervalMinutes}m (Corridor: ${corridorM.toInt()}m, Stop alert: ${prolongedStopThresholdMinutes}m)."
            postPreFilledSmsNotification(
                notificationId = ("TRIP_START_$tripId").hashCode(),
                title = "Tap to Send Trip Start SMS (${sharedContactPhones.size} Contacts)",
                subtitle = "Pre-filled trip GPS link ready in Messages",
                phonesJoined = sharedContactPhones.joinToString(";"),
                smsBody = initialMsg
            )
        }
    }

    suspend fun checkInTrip(tripId: String, isSafe: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val current = database.tripDao().getActiveTripOnce()
        if (isSafe) {
            val intervalMinutes = (current?.checkInIntervalMinutes ?: 5).coerceAtLeast(1)
            val nextDue = now + (intervalMinutes * 60 * 1000L)
            resetTripAnomalyAlertTimer()
            current?.let {
                database.tripDao().updateTrip(
                    it.copy(
                        lastCheckInTime = now,
                        nextCheckInDueTime = nextDue,
                        status = "MONITORING"
                    )
                )
            }
        } else {
            // Need help triggered during trip!
            triggerSos("Emergency help requested during trip to ${current?.destination ?: "destination"}")
        }
    }

    suspend fun completeTrip(trip: TripEntity) = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            stopLiveTripTracking()
        }
        database.tripDao().updateTrip(trip.copy(status = "ARRIVED"))
        val phones = trip.sharedContactPhones.split(";").filter { it.isNotBlank() }
        if (phones.isNotEmpty()) {
            val safeArrivalMsg = "[RakshaOne Trip Completed] Safely arrived at ${trip.destination}."
            postPreFilledSmsNotification(
                notificationId = ("TRIP_DONE_${trip.tripId}").hashCode(),
                title = "Tap to Send Safe Arrival SMS",
                subtitle = "Pre-filled safe arrival message ready for Messages",
                phonesJoined = phones.joinToString(";"),
                smsBody = safeArrivalMsg
            )
        }
    }

    suspend fun cancelTrip(trip: TripEntity) = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            stopLiveTripTracking()
        }
        database.tripDao().updateTrip(trip.copy(status = "CANCELLED"))
    }

    // Share live GPS coordinates update via pre-filled Messages SMS composer (or fallback Share Sheet if no phones)
    suspend fun shareLiveTripLocationUpdate(
        trip: TripEntity,
        contacts: List<TrustedContactEntity>,
        resolvedLocation: Location? = null
    ) = withContext(Dispatchers.IO) {
        val loc = resolvedLocation ?: getCurrentLocationOrNull()
        val waypointCount = database.tripWaypointDao().getWaypointsForTripOnce(trip.tripId).size
        val trailNote = if (waypointCount > 0) " ($waypointCount GPS checkpoints logged)" else ""
        val message = if (loc != null) {
            val mapLink = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            "[RakshaOne Live GPS Update] En route to ${trip.destination}$trailNote. Current coordinates: (${loc.latitude}, ${loc.longitude}). Track live: $mapLink"
        } else {
            "[RakshaOne Trip Update] En route to ${trip.destination}$trailNote (GPS fix pending)."
        }

        val phones = contacts.map { it.phone }.filter { it.isNotBlank() }
        if (phones.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                openSmsMessenger(phones.joinToString(";"), message)
            }
        } else {
            shareTextContent("RakshaOne Live Trip Update", message)
        }
    }

    fun sendDirectOrIntentSms(phoneNumber: String, messageText: String, allowUiFallback: Boolean = false) {
        if (allowUiFallback) {
            openSmsMessenger(phoneNumber, messageText)
        } else {
            postPreFilledSmsNotification(
                notificationId = ("SMS_${phoneNumber.hashCode()}").hashCode(),
                title = "Tap to Send Safety SMS",
                subtitle = "Pre-filled safety alert ready for Messages app",
                phonesJoined = phoneNumber,
                smsBody = messageText
            )
        }
    }

    // Incident Reporting with Automated Timestamps, Real Device Sensor Snapshots & SHA-256 Hashing
    suspend fun reportIncident(
        category: String,
        description: String,
        locationText: String,
        evidenceTitle: String,
        evidenceType: String,
        resolvedLocation: Location? = null,
        attachmentBytes: ByteArray? = null,
        attachmentStoragePath: String? = null,
        capturedSensorSnapshot: DeviceSensorSnapshot? = null
    ): String = withContext(Dispatchers.IO) {
        val loc = resolvedLocation ?: getCurrentLocationOrNull()
        val snapshot = capturedSensorSnapshot ?: sensorsManager.captureDeviceSensorSnapshot(loc)
        val lat = loc?.latitude ?: snapshot.latitude ?: 0.0
        val lng = loc?.longitude ?: snapshot.longitude ?: 0.0
        val hasRealGps = loc != null || (snapshot.latitude != null && snapshot.longitude != null)
        val timestamp = snapshot.capturedAtMillis
        val incidentId = "INC-${SimpleDateFormat("yyyy", Locale.US).format(Date(timestamp))}-${(100000..999999).random()}"
        val user = database.userDao().getUserProfileOnce()
        val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"

        val finalLocDesc = locationText.ifBlank {
            if (hasRealGps) {
                "Lat: ${String.format(Locale.US, "%.5f", lat)}, Lng: ${String.format(Locale.US, "%.5f", lng)}"
            } else {
                "Location not provided (GPS unavailable)"
            }
        }

        val telemetryReportText = snapshot.toFormattedSummary()
        val canonicalPayload = "$incidentId|$category|$finalLocDesc|$description|${snapshot.toJsonPayload()}".toByteArray(Charsets.UTF_8)
        val combinedBytesForHash = if (attachmentBytes != null) canonicalPayload + attachmentBytes else canonicalPayload
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(combinedBytesForHash)
            .joinToString("") { "%02x".format(it) }

        val operatorTelemetryBlock = buildString {
            appendLine("[DISPATCHER SENSOR TELEMETRY SNAPSHOT]")
            appendLine(telemetryReportText)
            append("SHA-256 Evidence Seal: $hash")
        }

        val incident = IncidentEntity(
            incidentId = incidentId,
            userId = user?.id ?: "USER-001",
            type = category.uppercase(),
            priority = if (category.equals("Medical emergency", true) || category.equals("Violence", true) || category.equals("Fire", true)) "CRITICAL" else "HIGH",
            status = "CREATED",
            latitude = lat,
            longitude = lng,
            accuracyMeters = loc?.accuracy ?: snapshot.accuracyMeters ?: 0f,
            locationDescription = finalLocDesc,
            description = description,
            createdAt = timestamp,
            updatedAt = timestamp,
            assignedUnit = null,
            operatorNotes = operatorTelemetryBlock,
            isSynced = networkMonitor.isOnline.value
        )
        database.incidentDao().insertIncident(incident)

        database.incidentEventDao().insertEvent(
            IncidentEventEntity(
                incidentId = incidentId,
                timestamp = timestamp,
                oldStatus = null,
                newStatus = "SUBMITTED_TO_DISPATCHER",
                actor = "$userName (Citizen)",
                note = "Submitted $category with automated timestamp ${snapshot.capturedAtIso} & device sensor snapshot (Battery ${snapshot.batteryPercent}%, ${snapshot.networkType})."
            )
        )

        val evidence = EvidenceEntity(
            evidenceId = "EVD-${(10000..99999).random()}",
            incidentId = incidentId,
            userId = user?.id ?: "USER-001",
            fileType = evidenceType,
            title = evidenceTitle.ifBlank { "$category Report + Sensor Telemetry" },
            fileSizeBytes = combinedBytesForHash.size.toLong(),
            sha256Hash = hash,
            uploadTime = timestamp,
            storageLocation = attachmentStoragePath ?: "Local Room Dispatcher Telemetry Vault",
            previewText = telemetryReportText
        )
        database.evidenceDao().insertEvidence(evidence)

        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = timestamp,
                actor = userName,
                role = "CITIZEN",
                action = "INCIDENT_REPORTED",
                targetId = incidentId,
                details = "Reported $category at ${snapshot.capturedAtIso} with sensor telemetry & evidence ${evidence.evidenceId} (${combinedBytesForHash.size} bytes, SHA-256: ${hash.take(12)}...)"
            )
        )

        val isOnlineNow = networkMonitor.isOnline.value
        val contacts = database.trustedContactDao().getActiveContacts()
        val phones = contacts.map { it.phone }.filter { it.isNotBlank() }
        val emails = contacts.map { it.email }.filter { it.isNotBlank() }
        val reportSummary = "[INCIDENT LOGGED] $category at $finalLocDesc. Time: ${snapshot.capturedAtIso}. Details: $description. Incident ID: $incidentId. SHA-256: ${hash.take(12)}..."

        if (!isOnlineNow && (phones.isNotEmpty() || emails.isNotEmpty())) {
            val offlineAlert = OfflineAlertEntity(
                alertId = "OFFLINE-REP-${System.currentTimeMillis()}-${(1000..9999).random()}",
                incidentId = incidentId,
                alertType = "INCIDENT_REPORT",
                latitude = lat,
                longitude = lng,
                accuracyMeters = loc?.accuracy ?: snapshot.accuracyMeters ?: 0f,
                messageText = reportSummary,
                recipientPhones = phones.joinToString(";"),
                recipientEmails = emails.joinToString(";"),
                status = "PENDING_CONNECTIVITY"
            )
            database.offlineAlertDao().insertAlert(offlineAlert)
        }

        incidentId
    }

    data class OfflineSyncResult(
        val dispatchedCount: Int,
        val requiresUserActionCount: Int,
        val totalPending: Int
    )

    // Auto-dispatch pending offline emergency alerts once connectivity is restored
    suspend fun dispatchPendingOfflineAlerts(initiatedFromUserAction: Boolean = false): OfflineSyncResult = withContext(Dispatchers.IO) {
        val pending = database.offlineAlertDao().getPendingAlertsOnce()
        if (pending.isEmpty()) return@withContext OfflineSyncResult(0, 0, 0)

        val user = database.userDao().getUserProfileOnce()
        val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
        val contacts = database.trustedContactDao().getActiveContacts()
        var dispatchedCount = 0
        var requiresUserActionCount = 0

        for (alert in pending) {
            try {
                val phones = alert.recipientPhones.split(";").filter { it.isNotBlank() }
                if (initiatedFromUserAction && phones.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        openSmsMessenger(phones.joinToString(";"), alert.messageText)
                    }
                    database.offlineAlertDao().markDispatched(alert.alertId)
                    dispatchedCount++
                    database.auditLogDao().insertLog(
                        AuditLogEntity(
                            actor = "RakshaOne Sync Engine",
                            role = "SYSTEM",
                            action = "OFFLINE_ALERT_COMPOSER_OPENED",
                            targetId = alert.alertId,
                            details = "Opened pre-filled SMS composer for queued offline alert ${alert.alertId} (${alert.alertType})."
                        )
                    )
                } else if (phones.isNotEmpty()) {
                    postPreFilledSmsNotification(
                        notificationId = ("OFFLINE_${alert.alertId}").hashCode(),
                        title = "Tap to Send Offline Alert SMS (${phones.size} Contacts)",
                        subtitle = "Internet restored • Tap to confirm sending queued SOS SMS",
                        phonesJoined = phones.joinToString(";"),
                        smsBody = alert.messageText
                    )
                    requiresUserActionCount++
                    database.offlineAlertDao().updateAlert(
                        alert.copy(
                            dispatchAttempts = alert.dispatchAttempts + 1,
                            lastAttemptTime = System.currentTimeMillis()
                        )
                    )
                } else {
                    requiresUserActionCount++
                }
            } catch (_: Exception) {
                requiresUserActionCount++
                database.offlineAlertDao().updateAlert(
                    alert.copy(
                        dispatchAttempts = alert.dispatchAttempts + 1,
                        lastAttemptTime = System.currentTimeMillis()
                    )
                )
            }
        }
        OfflineSyncResult(
            dispatchedCount = dispatchedCount,
            requiresUserActionCount = requiresUserActionCount,
            totalPending = pending.size
        )
    }

    fun setLiveRadarWatchEnabled(enabled: Boolean) {
        liveRadarWatchEnabled = enabled
    }

    /**
     * Autonomous Internet-Loss Dead-Man's Black-Box:
     * Immediately captures current/last GPS + Battery %, logs Room offline queue,
     * broadcasts BLE emergency beacon (0 taps, 0 internet), posts actionable Messages SMS notification,
     * and emits foreground UI alert event.
     */
    suspend fun handleConnectivityLost() = withContext(Dispatchers.IO) {
        try {
            val contacts = database.trustedContactDao().getActiveContacts()
            val activeInc = database.incidentDao().getLatestActiveIncidentOnce()
            val activeTripNow = database.tripDao().getActiveTripOnce()
            if (contacts.isEmpty()) return@withContext
            if (!liveRadarWatchEnabled && activeInc == null && activeTripNow == null) return@withContext

            val user = database.userDao().getUserProfileOnce()
            val userName = user?.name?.takeIf { it.isNotBlank() }
                ?: Firebase.auth.currentUser?.displayName
                ?: "Citizen"

            val lastSelfPin = liveRadarManager.getLastSelfPin()
            val currentLoc = realtimeEmergencyService.realLocation.value
                ?: getCurrentLocationOrNull()
                ?: lastSelfPin?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let { pin ->
                    Location("last_radar").apply {
                        latitude = pin.latitude
                        longitude = pin.longitude
                        accuracy = 50f
                    }
                }
            val sensorSnap = sensorsManager.captureDeviceSensorSnapshot(currentLoc)
            val battery = sensorSnap.batteryPercent
            val lat = currentLoc?.latitude ?: activeTripNow?.currentLatitude ?: 0.0
            val lng = currentLoc?.longitude ?: activeTripNow?.currentLongitude ?: 0.0
            val area = placeIntel.value.areaName.takeIf { it.isNotBlank() && !it.startsWith("Locating") }
                ?: emergencyInfo.value.locality

            val hasCoords = lat != 0.0 || lng != 0.0
            val locText = if (hasCoords) {
                "${String.format(Locale.US, "%.5f", lat)}, ${String.format(Locale.US, "%.5f", lng)} (https://maps.google.com/?q=$lat,$lng)"
            } else {
                "GPS fix unavailable (last area: ${area.ifBlank { "Unknown" }})"
            }
            val offlineMsg = "⚠️ OFFLINE SAFETY ALERT - $userName\n" +
                "Internet turned off / signal lost at ${area.ifBlank { "Current Area" }}.\n" +
                "Last/Current Location: $locText\n" +
                "Battery: $battery%\n" +
                "Auto-Sent by RakshaOne Offline Black-Box"

            liveRadarManager.recordOfflineNetworkLossSnapshot(lat, lng, battery, userName, area)

            // Core family first; fallback to all enabled contacts with phones
            val corePhones = contacts.filter { it.isCoreFamily() && it.phone.isNotBlank() }.map { it.phone.trim() }
            val targetPhones = corePhones.ifEmpty {
                contacts.filter { it.phone.isNotBlank() }.map { it.phone.trim() }
            }.distinct()
            val phonesJoined = targetPhones.joinToString(";")
            val targetEmails = contacts.map { it.email.trim() }.filter { it.isNotBlank() }.distinct()

            val now = System.currentTimeMillis()
            val incidentRef = activeInc?.incidentId ?: "OFFLINE-NET-$now"

            if (targetPhones.isNotEmpty() || targetEmails.isNotEmpty()) {
                database.offlineAlertDao().insertAlert(
                    OfflineAlertEntity(
                        alertId = "NETLOST-$now-${(100..999).random()}",
                        incidentId = incidentRef,
                        alertType = "NETWORK_LOSS_BLACKBOX",
                        latitude = lat,
                        longitude = lng,
                        accuracyMeters = currentLoc?.accuracy ?: 0f,
                        messageText = offlineMsg,
                        recipientPhones = phonesJoined,
                        recipientEmails = targetEmails.joinToString(";"),
                        status = "PENDING_CONNECTIVITY"
                    )
                )
            }

            database.auditLogDao().insertLog(
                AuditLogEntity(
                    timestamp = now,
                    actor = userName,
                    role = "SYSTEM",
                    action = "NETWORK_LOSS_BLACKBOX_TRIGGERED",
                    targetId = incidentRef,
                    details = "Internet disconnected at Battery $battery% ($lat, $lng). BLE Relay + Offline SMS alert prepared for ${targetPhones.size} guardian(s)."
                )
            )

            // 1. Zero-tap autonomous Bluetooth LE Mesh broadcast (works offline without internet!)
            try {
                bleEmergencyRelay.broadcastEmergencyBeacon(
                    incidentId = incidentRef,
                    lat = lat,
                    lng = lng,
                    evidenceSummary = "OFFLINE_ALERT Battery:$battery%"
                )
            } catch (_: Exception) {
            }

            // 2. Post actionable pre-filled SMS notification + notify UI cleanly (user taps Send in Messages app)
            if (phonesJoined.isNotBlank()) {
                postOfflineNetworkSmsNotification(phonesJoined, offlineMsg, battery, targetPhones.size)
                _offlineConnectivityAlert.tryEmit(
                    OfflineConnectivityAlert(
                        message = offlineMsg,
                        phonesJoined = phonesJoined,
                        batteryPercent = battery,
                        latitude = if (hasCoords) lat else null,
                        longitude = if (hasCoords) lng else null,
                        directSmsSent = false
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Connectivity loss handler note: ${e.message}")
        }
    }

    private fun postPreFilledSmsNotification(
        notificationId: Int,
        title: String,
        subtitle: String,
        phonesJoined: String,
        smsBody: String
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channelId = "rakshaone_offline_blackbox"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    channelId,
                    "Emergency & Offline Pre-Filled SMS Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Instant pre-filled location + safety SMS drafts"
                }
                nm.createNotificationChannel(ch)
            }
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phonesJoined")).apply {
                putExtra("sms_body", smsBody)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pi = PendingIntent.getActivity(
                context,
                notificationId,
                smsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(smsBody))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_send, "Send SMS Now", pi)
                .build()
            nm.notify(notificationId, notification)
        } catch (_: Exception) {
        }
    }

    private fun postOfflineNetworkSmsNotification(
        phonesJoined: String,
        smsBody: String,
        batteryPercent: Int,
        contactCount: Int
    ) {
        postPreFilledSmsNotification(
            notificationId = 4911,
            title = "Internet Off • Battery $batteryPercent% ($contactCount Family)",
            subtitle = "Tap to send pre-filled GPS + Battery SMS (BLE Mesh active)",
            phonesJoined = phonesJoined,
            smsBody = smsBody
        )
    }

    // Cost-free emergency email dispatch with ALL audio, video, and photo attachments
    fun sendEmergencyEmail(
        recipientEmails: List<String>,
        subject: String,
        body: String,
        attachmentUris: ArrayList<Uri> = ArrayList()
    ) {
        if (recipientEmails.isEmpty()) return
        if (attachmentUris.isNotEmpty()) {
            try {
                val multiIntent = if (attachmentUris.size > 1) {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachmentUris)
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_STREAM, attachmentUris.first())
                    }
                }
                multiIntent.apply {
                    putExtra(Intent.EXTRA_EMAIL, recipientEmails.toTypedArray())
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val matches = context.packageManager.queryIntentActivities(
                    multiIntent,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )
                matches.forEach { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@forEach
                    attachmentUris.forEach { uri ->
                        try { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    }
                }
                context.startActivity(
                    Intent.createChooser(multiIntent, "Email ALL ${attachmentUris.size} Evidence File(s)").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                )
                return
            } catch (_: Exception) {}
        }
        try {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, recipientEmails.toTypedArray())
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(emailIntent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, recipientEmails.toTypedArray())
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(Intent.createChooser(fallbackIntent, "Send Emergency Alert via Email").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Exception) {}
        }
    }

    // Pre-formatted Emergency SMS
    fun buildEmergencySmsMessage(incident: IncidentEntity, userName: String): String {
        val timeStr = SimpleDateFormat("HH:mm:ss dd-MMM", Locale.getDefault()).format(Date(incident.createdAt))
        val hasCoords = incident.latitude != 0.0 || incident.longitude != 0.0
        val locationLine = if (hasCoords) {
            "Location: ${String.format(Locale.US, "%.5f", incident.latitude)}, ${String.format(Locale.US, "%.5f", incident.longitude)}\n" +
                "Map: https://maps.google.com/?q=${incident.latitude},${incident.longitude}"
        } else {
            "Location: GPS fix pending (${incident.locationDescription})"
        }
        return "RakshaOne Emergency Alert\n" +
                "$userName has activated an emergency alert.\n" +
                "$locationLine\n" +
                "Time: $timeStr\n" +
                "Incident ID: ${incident.incidentId}"
    }

    // Launch Android SMS app with pre-filled message (supports single or semicolon-separated numbers)
    fun openSmsMessenger(phoneNumber: String, messageText: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", messageText)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    type = "vnd.android-dir/mms-sms"
                    putExtra("address", phoneNumber)
                    putExtra("sms_body", messageText)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // Zero-Permission Dialer Launch (Google Play Protect compliant — no CALL_PHONE required)
    fun dialEmergencyNumber(number: String, @Suppress("UNUSED_PARAMETER") allowDirectCall: Boolean = false): Boolean {
        val cleanNumber = number.trim()
        if (cleanNumber.isBlank()) return false

        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$cleanNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // Open Android System Share Sheet with real location/text
    fun shareTextContent(subject: String, text: String): Boolean {
        return try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, subject).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    // Open real Google Maps / geo search around current real GPS coordinates
    fun openMapsSearch(query: String, latitude: Double?, longitude: Double?): Boolean {
        val uri = if (latitude != null && longitude != null) {
            Uri.parse("geo:$latitude,$longitude?q=${Uri.encode(query)}")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        }
        return try {
            val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(mapIntent)
            true
        } catch (_: Exception) {
            try {
                val webUrl = if (latitude != null && longitude != null) {
                    "https://www.google.com/maps/search/${Uri.encode(query)}/@$latitude,$longitude,14z"
                } else {
                    "https://www.google.com/maps/search/${Uri.encode(query)}"
                }
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun openCoordinatesInMaps(latitude: Double, longitude: Double, label: String = "SOS Location"): Boolean {
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            true
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$latitude,$longitude")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // Haptic vibration feedback
    fun triggerEmergencyHaptic() {
        hapticManager.vibrateEmergencySosTriggered()
    }

    // Real regional emergency service numbers based on current GPS / SIM / Locale
    fun getNearbyFacilities(): List<NearbyFacility> {
        return realtimeEmergencyService.getDynamicNearbyFacilities(realLocation.value)
    }

    companion object {
        @Volatile
        private var INSTANCE: SafetyRepository? = null

        fun getInstance(context: Context): SafetyRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SafetyRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

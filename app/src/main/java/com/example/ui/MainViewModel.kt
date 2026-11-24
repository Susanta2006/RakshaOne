package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiMessage
import com.example.data.ai.AiService
import com.example.data.ai.SurakshaAiUiState
import com.example.data.ai.SurakshaStreamEvent
import com.example.data.auth.AuthManager
import com.example.data.auth.AuthState
import com.example.data.model.AuditLogEntity
import com.example.data.model.EncryptedAudioMeta
import com.example.data.model.EscalationWorkflowEntity
import com.example.data.model.EvidenceEntity
import com.example.data.model.GoodCitizenProfile
import com.example.data.model.HexRescueAlert
import com.example.data.model.HighRiskZoneEntity
import com.example.data.model.IncidentEntity
import com.example.data.model.IncidentEventEntity
import com.example.data.model.LocationEmergencyInfo
import com.example.data.model.OfflineAlertEntity
import com.example.data.model.PointsLedgerEntity
import com.example.data.model.PoliceDispatchDossier
import com.example.data.model.RiskAssessmentEntity
import com.example.data.model.SafetyGuideEntity
import com.example.data.model.SavedRoutineEntity
import com.example.data.model.TripEntity
import com.example.data.model.TripWaypointEntity
import com.example.data.model.TrustedContactEntity
import com.example.data.model.UserProfileEntity
import com.example.data.repository.CloudEmergencyDispatch
import com.example.data.repository.FirestoreProfileRepository
import com.example.data.repository.SafetyRepository
import com.example.data.repository.TripAnomalyState
import com.example.risk.CitizenVerificationStatus
import com.example.risk.HexRescueLifecycleState
import com.example.risk.PoliceEscalationStatus
import com.example.risk.ReceiptDeliveryState
import com.example.risk.RiskAssessmentResult
import com.example.risk.RiskLevel
import com.example.risk.TriggerOrigin
import com.example.service.LockscreenVolumeSosService
import com.example.service.SosAlertService
import com.example.service.SosSmsDispatchState
import com.example.util.AiLearningStateSnapshot
import com.example.util.AppCheckDiagnosticInfo
import com.example.util.AutonomousAiDecision
import com.example.util.BleDiscoveredEmergency
import com.example.util.BleRelayStatus
import com.example.util.DeviceSensorSnapshot
import com.example.util.FakeCallConfig
import com.example.util.FakeCallState
import com.example.util.FusedLocationWrapper
import com.example.util.GuardianHighAlertNotifier
import com.example.util.GuardianVoicePersona
import com.example.util.HexagonalRescueManager
import com.example.util.LanguageOption
import com.example.util.LiveRadarPin
import com.example.util.LocationState
import com.example.util.VolumeDownTripleClickDetector
import com.example.util.MLServiceManager
import com.example.util.NearbySupportUser
import com.example.util.PhoneNormalizer
import com.example.util.PlaceAndTrafficIntel
import com.example.util.PlateScanResult
import com.example.util.PredictiveThreatAssessment
import com.example.util.StorageHealthStatus
import com.example.util.VoiceGuardianManager
import com.example.util.ZeroFirTimelineBuilder
import com.example.util.getAppCheckDiagnosticInfo
import com.example.util.saveCustomAppCheckDebugToken
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

enum class SosCountdownState {
    IDLE,
    COUNTDOWN,
    ACTIVE
}

data class SosChannelStatus(
    val incidentId: String,
    val isOnline: Boolean,
    val isWhatsAppAvailable: Boolean,
    val smsFallbackTriggered: Boolean,
    val fallbackReason: String,
    val summary: String,
    val mediaDispatchSummary: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = SafetyRepository.getInstance(application)
    val authManager = AuthManager(application)
    private val firestoreProfileRepository by lazy { FirestoreProfileRepository(application) }
    val locationWrapper = FusedLocationWrapper(application)
    private val aiService = AiService()
    val mlServiceManager = MLServiceManager(application)
    val voiceGuardianManager = VoiceGuardianManager(application)
    val zeroFirBuilder = ZeroFirTimelineBuilder(application)

    val authState: StateFlow<AuthState> = authManager.authState
    val currentUser: StateFlow<FirebaseUser?> = authManager.currentUser
    val liveTripLocation: StateFlow<Pair<Double, Double>?> = repository.liveTripLocation

    // Live Radar, Storage Warden, Threat Engine & Voice Co-Pilot StateFlows
    val radarPins: StateFlow<Map<String, LiveRadarPin>> = repository.radarPins
    val nearbyUsers: StateFlow<List<NearbySupportUser>> = repository.nearbyUsers
    val placeIntel: StateFlow<PlaceAndTrafficIntel> = repository.placeIntel
    val disconnectAlertMessage: StateFlow<String?> = repository.disconnectAlertMessage
    val storageHealth: StateFlow<StorageHealthStatus> = repository.storageHealth
    val predictiveThreat: StateFlow<PredictiveThreatAssessment> = repository.predictiveThreat
    val selectedVoiceLanguage: StateFlow<LanguageOption> = voiceGuardianManager.selectedLanguage
    val voicePersona: StateFlow<GuardianVoicePersona> = voiceGuardianManager.voicePersona
    val autoReadOut: StateFlow<Boolean> = voiceGuardianManager.autoReadOut
    val isVoiceSpeaking: StateFlow<Boolean> = voiceGuardianManager.isSpeaking
    val isVoiceListening: StateFlow<Boolean> = voiceGuardianManager.isListening
    val secretDistressPhrase: StateFlow<String> = voiceGuardianManager.secretDistressPhrase
    val availableVoiceLanguages: StateFlow<List<LanguageOption>> = voiceGuardianManager.availableLanguages
    val voiceStatusNote: StateFlow<String> = voiceGuardianManager.voiceStatusNote
    val needsTtsDataInstall: StateFlow<Boolean> = voiceGuardianManager.needsTtsDataInstall
    val activeVoiceName: StateFlow<String> = voiceGuardianManager.activeVoiceName
    private val _lastVoiceTranscript = MutableStateFlow("")
    val lastVoiceTranscript: StateFlow<String> = _lastVoiceTranscript.asStateFlow()
    val isDecoyStealthActive: StateFlow<Boolean> = repository.isDecoyStealthActive

    // Real-time FusedLocationProviderClient StateFlows
    val locationState: StateFlow<LocationState> = locationWrapper.locationState
    val realTimeLocation: StateFlow<Location?> = locationWrapper.currentLocation
    val emergencyInfo: StateFlow<LocationEmergencyInfo> = repository.emergencyInfo

    val userProfile: StateFlow<UserProfileEntity?> = repository.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val trustedContacts: StateFlow<List<TrustedContactEntity>> = repository.trustedContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Hex Rescue & Guardian Cloud Dispatch StateFlows initialized directly
    val allCloudHexAlerts: StateFlow<List<HexRescueAlert>> = repository.hexagonalRescueManager.allCloudHexAlerts

    private val _activeGuardianHighAlert = MutableStateFlow<CloudEmergencyDispatch?>(null)
    val activeGuardianHighAlert: StateFlow<CloudEmergencyDispatch?> = _activeGuardianHighAlert.asStateFlow()

    private val _incomingGuardianAlerts = MutableStateFlow<List<CloudEmergencyDispatch>>(emptyList())
    val incomingGuardianAlerts: StateFlow<List<CloudEmergencyDispatch>> = _incomingGuardianAlerts.asStateFlow()

    val guardianResponderAlertsByIncident: StateFlow<Map<String, HexRescueAlert>> = combine(
        incomingGuardianAlerts,
        allCloudHexAlerts
    ) { guardianAlerts, hexAlerts ->
        if (guardianAlerts.isEmpty() || hexAlerts.isEmpty()) {
            emptyMap()
        } else {
            val byIncident = hexAlerts.associateBy { it.incidentId }
            guardianAlerts.mapNotNull { dispatch ->
                byIncident[dispatch.dispatchId]?.let { dispatch.dispatchId to it }
            }.toMap()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val activeGuardianResponderAlert: StateFlow<HexRescueAlert?> = combine(
        _activeGuardianHighAlert,
        allCloudHexAlerts
    ) { bannerAlert, hexAlerts ->
        if (bannerAlert == null) null
        else hexAlerts.firstOrNull { it.incidentId == bannerAlert.dispatchId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allIncidents: StateFlow<List<IncidentEntity>> = repository.allIncidents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeIncidents: StateFlow<List<IncidentEntity>> = repository.activeIncidents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val latestActiveIncident: StateFlow<IncidentEntity?> = repository.latestActiveIncident
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeTrip: StateFlow<TripEntity?> = repository.activeTrip
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeTripWaypoints: StateFlow<List<TripWaypointEntity>> = activeTrip
        .flatMapLatest { trip ->
            if (trip == null) flowOf(emptyList())
            else repository.observeTripWaypoints(trip.tripId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tripTrackingActive: StateFlow<Boolean> = repository.tripTrackingActive
    val tripAnomalyState: StateFlow<TripAnomalyState> = repository.tripAnomalyState

    private val _latestSensorSnapshot = MutableStateFlow<DeviceSensorSnapshot?>(null)
    val latestSensorSnapshot: StateFlow<DeviceSensorSnapshot?> = _latestSensorSnapshot.asStateFlow()

    val allTrips: StateFlow<List<TripEntity>> = repository.allTrips
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allEvidence: StateFlow<List<EvidenceEntity>> = repository.allEvidence
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLogs: StateFlow<List<AuditLogEntity>> = repository.auditLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val safetyGuides: StateFlow<List<SafetyGuideEntity>> = repository.safetyGuides
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val highRiskZones: StateFlow<List<HighRiskZoneEntity>> = repository.allHighRiskZones
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedRoutines: StateFlow<List<SavedRoutineEntity>> = repository.allSavedRoutines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeRoutine: StateFlow<SavedRoutineEntity?> = repository.activeRoutine
    val currentDeviationMeters: StateFlow<Float> = repository.currentDeviationMeters

    val goodCitizenProfile: StateFlow<GoodCitizenProfile> = repository.goodCitizenManager.citizenProfile
    val activeIncomingHexAlert: StateFlow<HexRescueAlert?> = repository.hexagonalRescueManager.activeIncomingAlert
    val activeOutgoingHexAlert: StateFlow<HexRescueAlert?> = repository.hexagonalRescueManager.activeOutgoingAlert
    val nearbyHexAlerts: StateFlow<List<HexRescueAlert>> = repository.hexagonalRescueManager.nearbyHexAlerts
    val latestPoliceDossier: StateFlow<PoliceDispatchDossier?> = repository.emergencyResponseDispatcher.latestPoliceDossier

    val currentRiskAssessment: StateFlow<RiskAssessmentResult> = repository.currentRiskAssessment
    val travelBumpFilterEnabled: StateFlow<Boolean> = repository.travelBumpFilterEnabled
    val travelBumpCount: StateFlow<Int> = repository.sensorsManager.travelBumpCount
    val bleRelayStatus: StateFlow<BleRelayStatus> = repository.bleEmergencyRelay.relayStatus
    val bleDiscoveries: StateFlow<List<BleDiscoveredEmergency>> = repository.bleEmergencyRelay.discoveredEmergencies

    val riskAssessments: StateFlow<List<RiskAssessmentEntity>> = repository.riskAssessments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val escalationWorkflows: StateFlow<List<EscalationWorkflowEntity>> = repository.escalationWorkflows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pointsLedger: StateFlow<List<PointsLedgerEntity>> = repository.pointsLedger
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeLiveDossier = MutableStateFlow<PoliceDispatchDossier?>(null)
    val activeLiveDossier: StateFlow<PoliceDispatchDossier?> = _activeLiveDossier.asStateFlow()

    // UI & Hardware State
    private val _sosState = MutableStateFlow(SosCountdownState.IDLE)
    val sosState: StateFlow<SosCountdownState> = _sosState.asStateFlow()

    private val _countdownRemaining = MutableStateFlow(5)
    val countdownRemaining: StateFlow<Int> = _countdownRemaining.asStateFlow()

    val isOnline: StateFlow<Boolean> = repository.isOnline
    val pendingOfflineAlertsCount: StateFlow<Int> = repository.pendingOfflineCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val pendingOfflineAlerts: StateFlow<List<OfflineAlertEntity>> = repository.pendingOfflineAlerts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isSirenActive: StateFlow<Boolean> = repository.sensorsManager.isSirenActive
    val isStrobeActive: StateFlow<Boolean> = repository.sensorsManager.isStrobeActive
    val hasHardwareFlash: Boolean = repository.sensorsManager.hasHardwareFlash
    val hasAccelerometer: Boolean = repository.sensorsManager.hasAccelerometer
    val isShakeSosEnabled: StateFlow<Boolean> = repository.sensorsManager.isShakeDetectionActive
    val currentShakeCount: StateFlow<Int> = repository.sensorsManager.currentShakeCount
    val lastShakeGForce: StateFlow<Float> = repository.sensorsManager.lastShakeGForce

    val isIgnoringBatteryOptimizations: StateFlow<Boolean> = repository.isIgnoringBatteryOptimizations
    val manualRegionOverride: StateFlow<String?> = repository.realtimeEmergencyService.manualRegionOverride
    val supportedRegions = repository.realtimeEmergencyService.supportedRegions

    // Haptic feedback & tactile confirmation state
    val isHapticsEnabled: StateFlow<Boolean> = repository.hapticManager.isHapticsEnabled
    val isHapticVibrating: StateFlow<Boolean> = repository.hapticManager.isVibrating
    val lastHapticPattern: StateFlow<String> = repository.hapticManager.lastTriggeredPattern
    val hasVibratorHardware: Boolean = repository.hapticManager.hasVibratorHardware
    val hasAmplitudeSupport: Boolean = repository.hapticManager.hasAmplitudeSupport

    private val _tripCheckInRemainingSeconds = MutableStateFlow<Long?>(null)
    val tripCheckInRemainingSeconds: StateFlow<Long?> = _tripCheckInRemainingSeconds.asStateFlow()

    private val _isTripCheckInOverdue = MutableStateFlow(false)
    val isTripCheckInOverdue: StateFlow<Boolean> = _isTripCheckInOverdue.asStateFlow()

    val isAudioRecording: StateFlow<Boolean> = repository.audioRecorder.isRecording
    val audioSecondsRemaining: StateFlow<Int> = repository.audioRecorder.secondsRemaining
    val latestAudioEvidence: StateFlow<EncryptedAudioMeta?> = repository.audioRecorder.latestAudioEvidence
    val sosSmsDispatchState: StateFlow<SosSmsDispatchState> = SosAlertService.dispatchState

    // Automated Dual-Camera CameraX + Audio Evidence Manager StateFlows
    val isCameraRecording: StateFlow<Boolean> = repository.cameraVideoRecorder.isRecording
    val cameraSecondsRemaining: StateFlow<Int> = repository.cameraVideoRecorder.secondsRemaining
    val lastVideoPath: StateFlow<String?> = repository.cameraVideoRecorder.lastVideoPath
    val capturedPhotoPaths: StateFlow<List<String>> = repository.cameraVideoRecorder.capturedPhotoPaths
    val frontPhotoPaths: StateFlow<List<String>> = repository.frontPhotoPaths
    val rearPhotoPaths: StateFlow<List<String>> = repository.rearPhotoPaths
    val capturedVideoPaths: StateFlow<List<String>> = repository.capturedVideoPaths
    val capturedAudioPaths: StateFlow<List<String>> = repository.capturedAudioPaths
    val latestShareableAudioFile: StateFlow<File?> = repository.latestShareableAudioFile
    val cameraCaptureStatus: StateFlow<String> = repository.cameraVideoRecorder.captureStatus

    private val _isLiveLocationSharingEnabled = MutableStateFlow(true)
    val isLiveLocationSharingEnabled: StateFlow<Boolean> = _isLiveLocationSharingEnabled.asStateFlow()

    private val _isDispatchTabVisible = MutableStateFlow(false)
    val isDispatchTabVisible: StateFlow<Boolean> = _isDispatchTabVisible.asStateFlow()

    private val _isProtectorWalkActive = MutableStateFlow(false)
    val isProtectorWalkActive: StateFlow<Boolean> = _isProtectorWalkActive.asStateFlow()

    private val _isPocketGestureArmed = MutableStateFlow(false)
    val isPocketGestureArmed: StateFlow<Boolean> = _isPocketGestureArmed.asStateFlow()

    private val lockscreenPrefs = application.getSharedPreferences(
        LockscreenVolumeSosService.PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val _isVolumeDownSilentSosEnabled = MutableStateFlow(
        lockscreenPrefs.getBoolean(LockscreenVolumeSosService.PREF_LOCKSCREEN_VOL_SOS_ENABLED, true)
    )
    val isVolumeDownSilentSosEnabled: StateFlow<Boolean> = _isVolumeDownSilentSosEnabled.asStateFlow()

    private val _lastDetectedPlate = MutableStateFlow<String?>(null)
    val lastDetectedPlate: StateFlow<String?> = _lastDetectedPlate.asStateFlow()

    private val volumePressTimestamps = mutableListOf<Long>()
    private val volumeDownDetector = VolumeDownTripleClickDetector()

    // Anti-Snatch Shield & Device Admin Screen Auto-Lock StateFlows
    val isDeviceAdminActive: StateFlow<Boolean> = repository.deviceLockManager.isDeviceAdminActive
    val isSnatchProtectionActive: StateFlow<Boolean> = repository.sensorsManager.isSnatchDetectionActive
    val snatchAlertCount: StateFlow<Int> = repository.sensorsManager.snatchAlertCount

    private val _selectedWhatsAppContactIndex = MutableStateFlow(0)
    val selectedWhatsAppContactIndex: StateFlow<Int> = _selectedWhatsAppContactIndex.asStateFlow()

    private val _installedContactsMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val installedContactsMap: StateFlow<Map<String, Boolean>> = _installedContactsMap.asStateFlow()

    private val notifiedGuardianAlertIds = mutableSetOf<String>()

    // Silent SOS (Stealth) Mode StateFlow — suppresses siren & flashlight when noise/light increases risk
    private val _isSilentMode = MutableStateFlow(false)
    val isSilentMode: StateFlow<Boolean> = _isSilentMode.asStateFlow()

    // Live Channel Fallback & Automated Video+Photo+Audio Dispatch Tracking
    private val _sosChannelStatus = MutableStateFlow<SosChannelStatus?>(null)
    val sosChannelStatus: StateFlow<SosChannelStatus?> = _sosChannelStatus.asStateFlow()

    private val _autoDispatchMediaOnCapture = MutableStateFlow(true)
    val autoDispatchMediaOnCapture: StateFlow<Boolean> = _autoDispatchMediaOnCapture.asStateFlow()

    private var currentAutoDispatchPendingIncidentId: String? = null
    private var pendingAutoLockIncidentId: String? = null
    private var pendingAutoLockAlertType: String = "SOS"
    private var pendingAutoLockIsSnatch: Boolean = false
    private var pendingAutoLockLat: Double = 0.0
    private var pendingAutoLockLng: Double = 0.0
    private var pendingAutoLockAcc: Float = 0f
    private var pendingAutoLockLocDesc: String = ""
    private var autoLockFailsafeJob: Job? = null

    val fakeCallState: StateFlow<FakeCallState> = repository.fakeCallManager.callState
    val fakeCallConfig: StateFlow<FakeCallConfig> = repository.fakeCallManager.config
    val fakeCallCountdown: StateFlow<Int> = repository.fakeCallManager.countdownSeconds
    val fakeCallDuration: StateFlow<Int> = repository.fakeCallManager.callDurationSeconds

    private val _fakeCallDefaultAutoRecordAndShare = MutableStateFlow(true)
    val fakeCallDefaultAutoRecordAndShare: StateFlow<Boolean> = _fakeCallDefaultAutoRecordAndShare.asStateFlow()

    private val _playingAudioIncidentId = MutableStateFlow<String?>(null)
    val playingAudioIncidentId: StateFlow<String?> = _playingAudioIncidentId.asStateFlow()

    private var guardianAudioPlayer: MediaPlayer? = null
    private var lastProactiveAiTriggerMs: Long = 0L

    private val _statusEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val statusEvents: SharedFlow<String> = _statusEvents.asSharedFlow()

    private val _isOperatorMode = MutableStateFlow(false)
    val isOperatorMode: StateFlow<Boolean> = _isOperatorMode.asStateFlow()

    private val _selectedIncidentEvents = MutableStateFlow<List<IncidentEventEntity>>(emptyList())
    val selectedIncidentEvents: StateFlow<List<IncidentEventEntity>> = _selectedIncidentEvents.asStateFlow()

    // AI Chat state
    private val _aiMessages = MutableStateFlow<List<AiMessage>>(
        listOf(
            AiMessage(
                sender = "assistant",
                text = "Hello! I am Suraksha AI, your personal safety companion on RakshaOne.\n\n" +
                    "I can help you navigate the app, explain safety protocols (e.g. if you suspect you are being followed), or assist during emergencies.\n\n" +
                    "How can I help protect you today?"
            )
        )
    )
    val aiMessages: StateFlow<List<AiMessage>> = _aiMessages.asStateFlow()

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()

    private val _aiUiState = MutableStateFlow<SurakshaAiUiState>(SurakshaAiUiState.Idle)
    val aiUiState: StateFlow<SurakshaAiUiState> = _aiUiState.asStateFlow()

    private var aiGenerationJob: Job? = null
    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            repository.latestActiveIncident.collect { active ->
                if (active != null && _sosState.value == SosCountdownState.IDLE) {
                    _sosState.value = SosCountdownState.ACTIVE
                }
            }
        }

        viewModelScope.launch {
            repository.userProfile.collect { profile ->
                if (profile != null) {
                    _isSilentMode.value = profile.silentSosMode
                    if (repository.sensorsManager.hasAccelerometer) {
                        if (profile.antiSnatchEnabled && !repository.sensorsManager.isSnatchDetectionActive.value) {
                            repository.sensorsManager.startSnatchDetection { handleSnatchTelemetryObserved() }
                        } else if (!profile.antiSnatchEnabled && repository.sensorsManager.isSnatchDetectionActive.value) {
                            repository.sensorsManager.stopSnatchDetection()
                        }
                    }
                }
            }
        }

        // Stream incoming guardian emergency dispatches with offline-resilient local Room cache
        viewModelScope.launch {
            // Cold-boot offline hydration from local Room database
            try {
                val cached = repository.getCachedGuardianDispatchesOnce()
                if (cached.isNotEmpty() && _incomingGuardianAlerts.value.isEmpty()) {
                    _incomingGuardianAlerts.value = cached
                }
            } catch (_: Exception) {}

            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            currentUser.flatMapLatest { user ->
                val email = user?.email.orEmpty()
                if (user == null || !authManager.isFirebaseConfigured()) {
                    repository.cachedGuardianDispatches
                } else {
                    repository.emergencyDispatchRepository.observeIncomingGuardianAlerts(
                        currentUid = user.uid,
                        guardianEmail = email
                    )
                }
            }.collect { alerts ->
                if (alerts.isNotEmpty()) {
                    _incomingGuardianAlerts.value = alerts
                    repository.cacheIncomingGuardianDispatches(alerts)
                } else {
                    val localCached = repository.getCachedGuardianDispatchesOnce()
                    if (localCached.isNotEmpty()) {
                        _incomingGuardianAlerts.value = localCached
                    } else {
                        _incomingGuardianAlerts.value = emptyList()
                    }
                }
            }
        }

        // Deliver OS high-priority notifications & in-app banner whenever a Trusted Circle contact activates SOS
        viewModelScope.launch {
            incomingGuardianAlerts.collect { alerts ->
                val myUid = currentUser.value?.uid
                val activeIncoming = alerts.filter { it.ownerUid != myUid && it.status in listOf("ACTIVE", "CAPTURED") }
                for (alert in activeIncoming) {
                    val key = "${alert.dispatchId}_${alert.status}"
                    if (notifiedGuardianAlertIds.add(key)) {
                        GuardianHighAlertNotifier.postGuardianHighAlert(getApplication(), alert)
                        _activeGuardianHighAlert.value = alert
                    }
                }
                val currentBanner = _activeGuardianHighAlert.value
                if (currentBanner != null) {
                    val updated = activeIncoming.firstOrNull { it.dispatchId == currentBanner.dispatchId }
                    if (updated != null) {
                        _activeGuardianHighAlert.value = updated
                    } else if (activeIncoming.isEmpty()) {
                        _activeGuardianHighAlert.value = null
                    }
                }
            }
        }

        // Resolve which Trusted Circle contacts have RakshaOne installed via canonical Phone ID directory
        viewModelScope.launch {
            trustedContacts.collectLatest { contacts ->
                if (currentUser.value != null && authManager.isFirebaseConfigured() && contacts.isNotEmpty()) {
                    try {
                        val details = firestoreProfileRepository.resolveInstalledGuardianDetails(
                            contacts.map { it.phone }
                        )
                        _installedContactsMap.value = details.installedByRawPhone
                        repository.liveRadarManager.updateTrustedContactsContext(
                            contacts = contacts,
                            resolvedUids = details.resolvedUids,
                            uidToPhoneMap = details.uidToPhoneKey,
                            uidToEmailMap = details.uidToEmail
                        )
                    } catch (_: Exception) {}
                } else {
                    _installedContactsMap.value = emptyMap()
                }
            }
        }

        if (locationWrapper.hasFineLocationPermission()) {
            startLocationTracking()
        }

        // Startup Storage Warden pass & ML Kit OCR/Face client initialization
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            mlServiceManager.initialize()
        }
        viewModelScope.launch {
            repository.storageWardenManager.runOptimizationPass()
        }

        viewModelScope.launch {
            com.example.service.FakeIncomingCallService.lastUploadedIncidentId.collect { uploadedId ->
                if (!uploadedId.isNullOrBlank()) {
                    _statusEvents.tryEmit("Call ended — voice audio automatically encrypted & uploaded to Firestore ($uploadedId)")
                }
            }
        }

        // Dynamically switch GPS polling power mode: high-accuracy ONLY during active SOS, active Trip, or visible Dispatch tab
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _sosState,
                activeTrip,
                _isDispatchTabVisible
            ) { sos, trip, dispatchVisible ->
                sos != SosCountdownState.IDLE || trip != null || dispatchVisible
            }.collectLatest { highAccuracy ->
                locationWrapper.setHighAccuracyMode(highAccuracy)
            }
        }

        // Feed shake / jerk telemetry spikes & trip anomalies into TFLite AI brain
        viewModelScope.launch {
            repository.sensorsManager.lastShakeGForce.collect { jerkG ->
                if (jerkG >= 2.2f) {
                    repository.userRoutineLearner.updateSensorContext(dynamicJerkG = jerkG)
                }
            }
        }
        viewModelScope.launch {
            tripAnomalyState.collect { anomaly ->
                repository.userRoutineLearner.updateSensorContext(
                    tripAnomalyActive = anomaly.deviationAlertTriggered || anomaly.prolongedStopAlertTriggered
                )
            }
        }

        // Autonomous Sovereign TFLite AI Protection: pre-emptively launches realistic Google Phone Fake Call & arms audio vault on rising risk trajectory (strictly never dials 112)
        viewModelScope.launch {
            predictiveThreat.collect { threat ->
                val now = System.currentTimeMillis()
                val shouldIntervene = threat.autonomousGuardEnabled &&
                    (threat.autonomousAction == AutonomousAiDecision.PROACTIVE_FAKE_CALL ||
                        threat.autonomousAction == AutonomousAiDecision.PRE_ARM_VAULT_AND_GUARDIAN_ALERT) &&
                    fakeCallState.value == FakeCallState.IDLE &&
                    _sosState.value == SosCountdownState.IDLE &&
                    now - lastProactiveAiTriggerMs >= 10 * 60 * 1000L
                if (shouldIntervene) {
                    lastProactiveAiTriggerMs = now
                    repository.userRoutineLearner.tfliteBrain.noteAiTriggeredFakeCall()
                    scheduleFakeCall(
                        delaySeconds = 0,
                        name = null,
                        number = null,
                        isAiProactive = true,
                        autoRecordAndShare = true
                    )
                    _statusEvents.tryEmit("Suraksha TFLite Guard predicted elevated risk (${threat.threatScore}/100) — incoming protective call initiated")
                    if (currentUser.value != null && authManager.isFirebaseConfigured()) {
                        repository.circleAndAiSyncRepository.syncPushAiSafetyState(
                            repository.userRoutineLearner.exportAiState()
                        )
                    }
                }
            }
        }

        // Dispatch 3D Map 4-second live pin cadence while Dispatch GPS tab is visible
        viewModelScope.launch {
            isDispatchTabVisible.collectLatest { dispatchVisible ->
                while (dispatchVisible) {
                    publishLiveRadarTick(isForegroundDispatch = true, forceImmediate = false)
                    delay(4_000L)
                }
            }
        }

        // Forward real-time FusedLocationWrapper updates to emergency country resolver, geofencing, live radar & 15s breadcrumbs
        viewModelScope.launch {
            locationWrapper.currentLocation.collectLatest { location ->
                if (location != null) {
                    repository.realtimeEmergencyService.updateFromLocation(location)
                    repository.hexagonalRescueManager.updateResponderInputs(myLocation = location)

                    // If user is accepted responder en route, stream live coordinates so victim sees them approaching!
                    val myAuthUid = currentUser.value?.uid
                    repository.hexagonalRescueManager.updateOwnApproachingLocation(
                        responderLat = location.latitude,
                        responderLng = location.longitude,
                        myUid = myAuthUid
                    )

                    // Refresh street names, traffic corridors & crowd density intelligence
                    val speedKmh = location.speed * 3.6f
                    val nearbyCount = repository.nearbyUsers.value.size
                    repository.placeTrafficIntelManager.refreshIfNeeded(
                        lat = location.latitude,
                        lng = location.longitude,
                        speedKmh = speedKmh,
                        nearbyUsersCount = nearbyCount,
                        isOnline = repository.isOnline.value
                    )

                    repository.geofenceManager.evaluateLocation(
                        location = location,
                        contacts = trustedContacts.value,
                        userName = userProfile.value?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
                    )
                    val snap = repository.sensorsManager.captureDeviceSensorSnapshot(location)
                    val area = emergencyInfo.value.locality
                    repository.recordBreadcrumbAndLowBatteryBlackBox(
                        lat = location.latitude,
                        lng = location.longitude,
                        accuracy = location.accuracy,
                        batteryPercent = snap.batteryPercent,
                        areaName = area,
                        activeIncidentId = latestActiveIncident.value?.incidentId
                    )
                    publishLiveRadarTick(isForegroundDispatch = _isDispatchTabVisible.value, forceImmediate = false)
                }
            }
        }

        // Listen for offline connectivity alert events (BLE Mesh + Black-Box + Play-safe Pre-filled SMS notification)
        viewModelScope.launch {
            repository.offlineConnectivityAlert.collect { alert ->
                _statusEvents.tryEmit(
                    "Internet lost: Black-Box saved (Battery ${alert.batteryPercent}%) • Pre-filled SMS ready (tap notification) • BLE Mesh active"
                )
            }
        }

        // Start passive BLE scan if volunteer duty is already active and permission is granted
        if (goodCitizenProfile.value.isEligibleForHexRescue) {
            repository.bleEmergencyRelay.startScanningForNearbySos()
        }

        // Surface real SosAlertService pre-filled SMS notification status cleanly
        viewModelScope.launch {
            SosAlertService.dispatchState.collect { state ->
                if (state is SosSmsDispatchState.Completed) {
                    _statusEvents.tryEmit(state.summary)
                    _sosChannelStatus.value?.let { current ->
                        if (current.incidentId == state.incidentId) {
                            _sosChannelStatus.value = current.copy(summary = state.summary)
                        }
                    }
                }
            }
        }

        // Stream accepted responder live details & coordinates into victim's dossier/portal + status banner
        viewModelScope.launch {
            var lastDossierSyncKey = ""
            var lastAnnouncedResponderKey = ""
            combine(
                activeOutgoingHexAlert,
                latestPoliceDossier,
                realTimeLocation
            ) { outgoing, dossier, loc ->
                Triple(outgoing, dossier, loc)
            }.collect { (outgoing, dossier, loc) ->
                if (outgoing != null && !outgoing.acceptedResponderName.isNullOrBlank() &&
                    (outgoing.lifecycleState == HexRescueLifecycleState.RESPONDER_ACCEPTED ||
                        outgoing.lifecycleState == HexRescueLifecycleState.EN_ROUTE ||
                        outgoing.lifecycleState == HexRescueLifecycleState.ARRIVED)
                ) {
                    val vLat = loc?.latitude ?: outgoing.victimLatitude
                    val vLng = loc?.longitude ?: outgoing.victimLongitude
                    val rLat = outgoing.acceptedResponderLat
                    val rLng = outgoing.acceptedResponderLng
                    val distMeters = if (rLat != null && rLng != null && (rLat != 0.0 || rLng != 0.0) && (vLat != 0.0 || vLng != 0.0)) {
                        HexagonalRescueManager.calculateDistanceMeters(vLat, vLng, rLat, rLng)
                    } else {
                        outgoing.distanceMeters.takeIf { it > 0f }
                    }
                    val etaMinutes = distMeters?.let { (it / 80f).roundToInt().coerceAtLeast(1) }

                    val announceKey = "${outgoing.alertId}_${outgoing.lifecycleState.name}_${outgoing.acceptedResponderName}"
                    if (announceKey != lastAnnouncedResponderKey) {
                        lastAnnouncedResponderKey = announceKey
                        val distNote = distMeters?.let { " (~${it.roundToInt()}m, ~${etaMinutes ?: 1}m walk)" } ?: ""
                        _statusEvents.tryEmit(
                            "Responder ${outgoing.acceptedResponderName} (${outgoing.lifecycleState.name})$distNote"
                        )
                    }

                    _activeLiveDossier.value?.let { openDossier ->
                        if (openDossier.incidentId == outgoing.incidentId) {
                            _activeLiveDossier.value = openDossier.copy(
                                latitude = if (vLat != 0.0 || vLng != 0.0) vLat else openDossier.latitude,
                                longitude = if (vLat != 0.0 || vLng != 0.0) vLng else openDossier.longitude,
                                responderName = outgoing.acceptedResponderName,
                                responderPhone = outgoing.acceptedResponderPhone,
                                responderStatus = outgoing.lifecycleState.name,
                                responderLat = rLat,
                                responderLng = rLng,
                                responderDistanceMeters = distMeters,
                                responderEtaMinutes = etaMinutes
                            )
                        }
                    }

                    val targetToken = dossier?.takeIf { it.incidentId == outgoing.incidentId }?.dossierToken
                        ?: _activeLiveDossier.value?.takeIf { it.incidentId == outgoing.incidentId }?.dossierToken
                    if (!targetToken.isNullOrBlank()) {
                        val syncKey = "$targetToken|${outgoing.lifecycleState.name}|${rLat?.let { String.format(Locale.US, "%.4f", it) }}|${rLng?.let { String.format(Locale.US, "%.4f", it) }}|${String.format(Locale.US, "%.4f", vLat)}|${String.format(Locale.US, "%.4f", vLng)}"
                        if (syncKey != lastDossierSyncKey) {
                            lastDossierSyncKey = syncKey
                            repository.firestoreRiskRepository.updateDossierResponderTelemetry(
                                tokenId = targetToken,
                                victimLat = vLat,
                                victimLng = vLng,
                                responderName = outgoing.acceptedResponderName,
                                responderPhone = outgoing.acceptedResponderPhone,
                                responderStatus = outgoing.lifecycleState.name,
                                responderLat = rLat,
                                responderLng = rLng,
                                responderDistanceMeters = distMeters,
                                responderEtaMinutes = etaMinutes
                            )
                        }
                    }
                }
            }
        }

        // Notify Trusted Contacts in-app when a verified responder accepts or updates location on their friend's SOS
        viewModelScope.launch {
            var lastGuardianRespKey = ""
            activeGuardianResponderAlert.collect { respAlert ->
                if (respAlert != null && !respAlert.acceptedResponderName.isNullOrBlank()) {
                    val key = "${respAlert.incidentId}_${respAlert.lifecycleState.name}_${respAlert.acceptedResponderName}"
                    if (key != lastGuardianRespKey) {
                        lastGuardianRespKey = key
                        _statusEvents.tryEmit(
                            "Verified Responder ${respAlert.acceptedResponderName} (${respAlert.lifecycleState.name}) tracking ${respAlert.victimName}"
                        )
                    }
                }
            }
        }

        // Automatically upload captured Dual-Camera videos/photos/audio to Guardian Cloud Vault + auto-launch WhatsApp with GPS & ALL media once CameraX finishes
        viewModelScope.launch {
            repository.lastCameraCaptureCompleteIncidentId.collect { completedIncidentId ->
                if (completedIncidentId != null) {
                    val shouldAutoLock = completedIncidentId == pendingAutoLockIncidentId
                    if (shouldAutoLock) {
                        autoLockFailsafeJob?.cancel()
                    }

                    val synced = repository.syncEvidenceFilesToCloud(completedIncidentId)

                    if (completedIncidentId == currentAutoDispatchPendingIncidentId &&
                        _sosState.value == SosCountdownState.ACTIVE &&
                        _autoDispatchMediaOnCapture.value
                    ) {
                        currentAutoDispatchPendingIncidentId = null
                        _sosChannelStatus.value = _sosChannelStatus.value?.copy(
                            mediaDispatchSummary = if (synced) {
                                "Audio (.m4a) + Front/Rear Photos + Video (.mp4) auto-synced to Guardians! Launching WhatsApp..."
                            } else {
                                "Evidence captured locally — launching WhatsApp with media bundle..."
                            }
                        )
                        _statusEvents.tryEmit(
                            if (synced) "ALL SOS GPS, Audio, Video & Photos synced to Trusted Guardians — opening WhatsApp"
                            else "SOS Audio, Video & Photos captured locally — opening WhatsApp"
                        )
                        dispatchSosEvidenceWithFallback(
                            incidentId = completedIncidentId,
                            preferWhatsApp = true
                        )
                    }

                    if (shouldAutoLock) {
                        performPendingAutoLock(completedIncidentId)
                    }
                }
            }
        }

        // Real-time Trip Safety Check-In countdown, overdue monitor & stationary stop evaluator
        viewModelScope.launch {
            var overdueNotifiedForTripId: String? = null
            activeTrip.collectLatest { trip ->
                if (trip == null) {
                    _tripCheckInRemainingSeconds.value = null
                    _isTripCheckInOverdue.value = false
                    overdueNotifiedForTripId = null
                } else {
                    while (true) {
                        val remainingMs = trip.nextCheckInDueTime - System.currentTimeMillis()
                        val remainingSec = (remainingMs / 1000L).coerceAtLeast(0L)
                        _tripCheckInRemainingSeconds.value = remainingSec
                        val overdue = remainingMs <= 0L
                        _isTripCheckInOverdue.value = overdue
                        if (overdue && overdueNotifiedForTripId != trip.tripId) {
                            overdueNotifiedForTripId = trip.tripId
                            _statusEvents.tryEmit("Trip safety check-in is overdue — tap \"I'm Safe\" or \"Need Help\"!")
                        }
                        repository.tickStationaryDuration()
                        delay(1000L)
                    }
                }
            }
        }

        // Surface automated Trip Deviation & Prolonged Stop alerts immediately in UI
        viewModelScope.launch {
            var lastAlertTs: Long? = null
            tripAnomalyState.collect { anomaly ->
                val ts = anomaly.lastAlertTimestamp
                val msg = anomaly.lastAlertMessage
                if (ts != null && msg != null && ts != lastAlertTs) {
                    lastAlertTs = ts
                    _statusEvents.tryEmit("TRIP GUARD ALERT: $msg")
                }
            }
        }

        // Auto-resume FusedLocationProviderClient interval Room tracking if an active trip exists
        viewModelScope.launch {
            activeTrip.collect { trip ->
                if (trip != null && locationWrapper.hasFineLocationPermission()) {
                    repository.startLiveTripTracking()
                }
            }
        }

        // Sync Firebase-authenticated user safety profile with Firestore when signed in
        viewModelScope.launch {
            currentUser.collectLatest { user ->
                if (user == null || !authManager.isFirebaseConfigured()) return@collectLatest
                val currentLocal = repository.database.userDao().getUserProfileOnce() ?: UserProfileEntity()
                val mergedLocal = currentLocal.copy(
                    name = currentLocal.name.ifBlank { user.displayName.orEmpty() },
                    email = currentLocal.email.ifBlank { user.email.orEmpty() }
                )
                if (mergedLocal != currentLocal) {
                    repository.saveUserProfile(mergedLocal)
                }

                // Pull initial cloud safety profile if present; otherwise create initial user doc
                val cloudResult = firestoreProfileRepository.getProfileOnce(user.uid)
                val cloud = cloudResult.getOrNull()
                if (cloud != null) {
                    repository.saveUserProfile(
                        mergedLocal.copy(
                            name = cloud.displayName.ifBlank { mergedLocal.name },
                            email = cloud.email.ifBlank { mergedLocal.email },
                            phone = cloud.phone.ifBlank { mergedLocal.phone },
                            bloodGroup = cloud.bloodGroup.ifBlank { mergedLocal.bloodGroup },
                            allergies = cloud.allergies.ifBlank { mergedLocal.allergies },
                            medicalNotes = cloud.medicalNotes.ifBlank { mergedLocal.medicalNotes },
                            homeAddress = cloud.homeAddress.ifBlank { mergedLocal.homeAddress },
                            workAddress = cloud.workAddress.ifBlank { mergedLocal.workAddress },
                            autoSendSmsOnSos = cloud.autoSendSmsOnSos,
                            sirenBeaconEnabled = cloud.sirenBeaconEnabled
                        )
                    )
                } else if (cloudResult.isSuccess) {
                    firestoreProfileRepository.upsertProfile(
                        profile = mergedLocal,
                        authDisplayName = user.displayName,
                        authEmail = user.email
                    )
                }

                // Two-way sync Trusted Circle & Sovereign TFLite AI state across sign-ins
                repository.syncWithCloudOnSignIn()

                firestoreProfileRepository.observeProfile(user.uid)
                    .catch { err ->
                        if (err is CancellationException) throw err
                    }
                    .collect { observed ->
                        if (observed != null) {
                            val latestLocal = repository.database.userDao().getUserProfileOnce() ?: UserProfileEntity()
                            repository.saveUserProfile(
                                latestLocal.copy(
                                    name = observed.displayName.ifBlank { latestLocal.name },
                                    email = observed.email.ifBlank { latestLocal.email },
                                    phone = observed.phone.ifBlank { latestLocal.phone },
                                    bloodGroup = observed.bloodGroup.ifBlank { latestLocal.bloodGroup },
                                    allergies = observed.allergies.ifBlank { latestLocal.allergies },
                                    medicalNotes = observed.medicalNotes.ifBlank { latestLocal.medicalNotes },
                                    homeAddress = observed.homeAddress.ifBlank { latestLocal.homeAddress },
                                    workAddress = observed.workAddress.ifBlank { latestLocal.workAddress },
                                    autoSendSmsOnSos = observed.autoSendSmsOnSos,
                                    sirenBeaconEnabled = observed.sirenBeaconEnabled
                                )
                            )
                        }
                    }
            }
        }
    }

    fun startLocationTracking() {
        if (!locationWrapper.hasFineLocationPermission()) {
            locationWrapper.startRealtimeUpdates() // Sets LocationState.PermissionRequired cleanly without querying FusedLocationProviderClient
            return
        }
        locationWrapper.startRealtimeUpdates()
        if (activeTrip.value != null) {
            repository.startLiveTripTracking()
        }
        viewModelScope.launch {
            val loc = locationWrapper.getImmediateLocation(timeoutMs = 4000L)
            if (loc != null) {
                repository.realtimeEmergencyService.updateFromLocation(loc)
                if (activeTrip.value != null) {
                    repository.recordTripLocationFixNow(loc)
                }
            }
        }
    }

    fun refreshLocation() {
        viewModelScope.launch {
            locationWrapper.startRealtimeUpdates()
            val loc = locationWrapper.getImmediateLocation(timeoutMs = 5000L)
            if (loc != null) {
                repository.realtimeEmergencyService.updateFromLocation(loc)
                _statusEvents.tryEmit(
                    "GPS updated: ${String.format(Locale.US, "%.5f", loc.latitude)}, ${String.format(Locale.US, "%.5f", loc.longitude)} (±${loc.accuracy.toInt()}m)"
                )
            } else {
                when (locationWrapper.locationState.value) {
                    LocationState.PermissionRequired -> _statusEvents.tryEmit("Location permission required for real GPS coordinates")
                    LocationState.GpsDisabled -> _statusEvents.tryEmit("Device GPS is turned off — enable Location Services")
                    else -> _statusEvents.tryEmit("Acquiring satellite GPS signal...")
                }
            }
        }
    }

    fun startSosCountdown() {
        if (_sosState.value != SosCountdownState.IDLE) return
        _sosState.value = SosCountdownState.COUNTDOWN
        _countdownRemaining.value = 5

        // Tactile confirmation on button press for low-visibility awareness
        repository.hapticManager.vibrateSosButtonTouch()

        // Pre-warm GPS fix during the 5-second countdown so real coordinates are ready when SOS fires
        viewModelScope.launch {
            locationWrapper.getImmediateLocation(timeoutMs = 4500L)
        }

        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in 5 downTo 1) {
                _countdownRemaining.value = i
                repository.hapticManager.vibrateCountdownTick(i)
                delay(1000)
            }
            triggerImmediateSos(
                customNote = "Urgent SOS Activated",
                triggerOrigin = TriggerOrigin.USER_EXPLICIT_SOS_COUNTDOWN
            )
        }
    }

    fun cancelSosCountdown() {
        countdownJob?.cancel()
        _sosState.value = SosCountdownState.IDLE
        _countdownRemaining.value = 5
        repository.hapticManager.cancelVibration()
        repository.hapticManager.vibrateSosCancelled()
        _statusEvents.tryEmit("SOS countdown cancelled")
    }

    fun setSilentMode(enabled: Boolean) {
        _isSilentMode.value = enabled
        if (enabled) {
            repository.sensorsManager.stopSiren()
            repository.sensorsManager.stopStrobe()
        }
        viewModelScope.launch {
            repository.setSilentSosMode(enabled)
        }
        _statusEvents.tryEmit(
            if (enabled) "Silent SOS Stealth Mode ON — SOS will suppress siren & flash light"
            else "Silent SOS Mode OFF — SOS will activate siren & strobe deterrence"
        )
    }

    fun toggleSilentMode() {
        setSilentMode(!_isSilentMode.value)
    }

    fun setAutoDispatchMediaOnCapture(enabled: Boolean) {
        _autoDispatchMediaOnCapture.value = enabled
        _statusEvents.tryEmit(
            if (enabled) "Auto-send Video & Photos after 30s SOS capture enabled"
            else "Auto-send disabled — evidence stays in cache until manually shared"
        )
    }

    fun triggerImmediateSos(
        customNote: String = "Urgent SOS Activated",
        forceSilent: Boolean? = null,
        isSnatchTrigger: Boolean = false,
        triggerOrigin: TriggerOrigin = TriggerOrigin.USER_EXPLICIT_INSTANT_SOS,
        deviceLocked: Boolean = false
    ) {
        countdownJob?.cancel()
        val useSilent = if (isSnatchTrigger) false else (forceSilent ?: _isSilentMode.value)
        _sosState.value = SosCountdownState.ACTIVE
        if (useSilent) {
            _isSilentMode.value = true
            repository.sensorsManager.stopSiren()
            repository.sensorsManager.stopStrobe()
            voiceGuardianManager.stopSpeaking()
            repository.hapticManager.vibrateSosButtonTouch()
        } else {
            repository.sensorsManager.startSiren()
            repository.sensorsManager.startStrobe()
            repository.hapticManager.vibrateEmergencySosTriggered()
        }

        viewModelScope.launch {
            val liveLocation = locationWrapper.getImmediateLocation(timeoutMs = 3000L)
                ?: locationWrapper.currentLocation.value

            val finalNote = if (useSilent && !customNote.contains("Silent", ignoreCase = true)) {
                "$customNote (Silent Stealth Mode)"
            } else {
                customNote
            }

            val result = repository.triggerSosWithResult(
                userComment = finalNote,
                resolvedLocation = liveLocation,
                isSnatchTrigger = isSnatchTrigger,
                triggerOrigin = triggerOrigin,
                silentStealth = useSilent,
                deviceLocked = deviceLocked
            )
            val incident = result.incident
            currentAutoDispatchPendingIncidentId = incident.incidentId
            loadIncidentEvents(incident.incidentId)

            val shouldLockAfterCapture = userProfile.value?.autoLockOnSos == true
            if (shouldLockAfterCapture) {
                pendingAutoLockIncidentId = incident.incidentId
                pendingAutoLockAlertType = incident.type
                pendingAutoLockIsSnatch = isSnatchTrigger
                pendingAutoLockLat = incident.latitude
                pendingAutoLockLng = incident.longitude
                pendingAutoLockAcc = incident.accuracyMeters
                pendingAutoLockLocDesc = incident.locationDescription

                autoLockFailsafeJob?.cancel()
                autoLockFailsafeJob = viewModelScope.launch {
                    delay(if (isSnatchTrigger) 15_000L else 24_000L)
                    if (pendingAutoLockIncidentId == incident.incidentId) {
                        performPendingAutoLock(incident.incidentId)
                    }
                }
            } else {
                pendingAutoLockIncidentId = null
                pendingAutoLockIsSnatch = false
            }

            val waCount = repository.videoEvidenceManager.selectWhatsAppEnabledContacts(trustedContacts.value).size
            val channelSummary = when {
                result.cloudGuardianDispatched && waCount > 0 ->
                    "Cloud Guardian Dispatch Sent • WhatsApp Targeted ($waCount) + SMS Backup"
                result.cloudGuardianDispatched ->
                    "Cloud Guardian Dispatch Sent • SMS Fallback Active (${result.contactsCount} contact(s))"
                !result.isOnlineAtTrigger ->
                    "No Internet -> Fallback SMS triggered (${result.contactsCount} contact(s)) + Offline Queue"
                !result.isWhatsAppAvailable ->
                    "WhatsApp unavailable -> Fallback SMS triggered (${result.contactsCount} contact(s))"
                else ->
                    "Dual-Channel Active: SMS Alert + Dual-Camera Evidence ready"
            }

            _sosChannelStatus.value = SosChannelStatus(
                incidentId = incident.incidentId,
                isOnline = result.isOnlineAtTrigger,
                isWhatsAppAvailable = result.isWhatsAppAvailable,
                smsFallbackTriggered = result.smsFallbackTriggered,
                fallbackReason = result.fallbackReason,
                summary = channelSummary,
                mediaDispatchSummary = if (repository.cameraVideoRecorder.hasCameraPermission()) {
                    buildString {
                        append("Capturing Front + Rear Camera video & photos + .m4a voice...")
                        if (shouldLockAfterCapture && isDeviceAdminActive.value) {
                            append(" Auto-Lock armed.")
                        }
                    }
                } else {
                    "Grant Camera permission to attach Front+Rear video & photos"
                }
            )

            val gpsText = if (result.hasRealGps) {
                "GPS (${String.format(Locale.US, "%.4f", incident.latitude)}, ${String.format(Locale.US, "%.4f", incident.longitude)})"
            } else {
                "GPS unavailable"
            }
            val modeText = when {
                isSnatchTrigger -> "ANTI-SNATCH SIREN + LOCK"
                useSilent -> "Silent Stealth"
                else -> "Siren + Flash"
            }
            val audioText = if (result.audioRecordingStarted) " • Audio" else ""
            val camText = if (repository.cameraVideoRecorder.hasCameraPermission()) " • Dual Cam" else ""
            val cloudText = if (result.cloudGuardianDispatched) " • Guardians Notified" else ""
            _statusEvents.tryEmit("SOS Active ($modeText) [$gpsText$audioText$camText$cloudText]")

            // If Camera permission is not granted, auto-launch WhatsApp once audio recording finishes (or immediately with GPS)
            if (!repository.cameraVideoRecorder.hasCameraPermission() && _autoDispatchMediaOnCapture.value) {
                viewModelScope.launch {
                    delay(if (result.audioRecordingStarted) 12_500L else 1_500L)
                    if (_sosState.value == SosCountdownState.ACTIVE &&
                        currentAutoDispatchPendingIncidentId == incident.incidentId
                    ) {
                        currentAutoDispatchPendingIncidentId = null
                        repository.syncEvidenceFilesToCloud(incident.incidentId)
                        dispatchSosEvidenceWithFallback(
                            incidentId = incident.incidentId,
                            preferWhatsApp = true
                        )
                    }
                }
            }
        }
    }

    private fun handleSnatchTelemetryObserved() {
        repository.refreshNominalOrTelemetryRiskAssessment()
        _statusEvents.tryEmit("Sudden motion spike filtered by Travel Bump Guard — press SOS button if you need help.")
    }

    private suspend fun performPendingAutoLock(incidentId: String) {
        if (pendingAutoLockIncidentId != incidentId) return
        val alertType = pendingAutoLockAlertType
        val lat = pendingAutoLockLat
        val lng = pendingAutoLockLng
        val acc = pendingAutoLockAcc
        val locDesc = pendingAutoLockLocDesc

        pendingAutoLockIncidentId = null
        pendingAutoLockIsSnatch = false
        autoLockFailsafeJob?.cancel()

        val locked = repository.deviceLockManager.lockDeviceNow()
        if (locked) {
            repository.publishEmergencyDispatchToGuardians(
                incidentId = incidentId,
                alertType = alertType,
                status = "CAPTURED",
                lat = lat,
                lng = lng,
                accuracyMeters = acc,
                locDesc = locDesc,
                dualResult = repository.lastDualCameraResult.value,
                deviceLocked = true
            )
            _statusEvents.tryEmit("SECURITY LOCK ENGAGED: Device locked & Front+Rear evidence synced to guardians")
        } else {
            _statusEvents.tryEmit("Evidence captured & dispatched — activate Screen Lock Admin for automatic theft lock")
        }
    }

    fun toggleSiren() {
        repository.sensorsManager.toggleSiren()
        _statusEvents.tryEmit(if (isSirenActive.value) "Emergency Siren Active" else "Siren Stopped")
    }

    fun toggleStrobe() {
        repository.sensorsManager.toggleStrobe()
        if (isStrobeActive.value) {
            val mode = if (hasHardwareFlash) "Camera Flash + Screen Strobe Active" else "Screen Strobe Active (no camera flash hardware)"
            _statusEvents.tryEmit(mode)
        } else {
            _statusEvents.tryEmit("Strobe Stopped")
        }
    }

    fun toggleTravelBumpFilter(enabled: Boolean) {
        repository.setTravelBumpFilterEnabled(enabled)
        _statusEvents.tryEmit(
            if (enabled) "Travel Bump Filter ON — vehicle shakes never trigger SOS"
            else "Travel Bump Filter OFF (SOS still requires explicit button confirmation)"
        )
    }

    fun toggleShakeSos() {
        if (isShakeSosEnabled.value) {
            repository.sensorsManager.stopShakeDetection()
            _statusEvents.tryEmit("Motion bump telemetry monitor paused")
        } else {
            if (!hasAccelerometer) {
                _statusEvents.tryEmit("Accelerometer sensor unavailable on this device")
                return
            }
            val started = repository.sensorsManager.startShakeDetection { }
            _statusEvents.tryEmit(
                if (started) "Motion bump telemetry armed (suppressed during travel; explicit SOS required)"
                else "Unable to register accelerometer sensor listener"
            )
        }
    }

    fun toggleAntiSnatchProtection() {
        val nextEnabled = !isSnatchProtectionActive.value
        if (nextEnabled) {
            if (!hasAccelerometer) {
                _statusEvents.tryEmit("Accelerometer sensor unavailable on this device")
                return
            }
            repository.sensorsManager.startSnatchDetection { handleSnatchTelemetryObserved() }
        } else {
            repository.sensorsManager.stopSnatchDetection()
        }
        viewModelScope.launch {
            repository.setAntiSnatchEnabled(nextEnabled)
        }
        _statusEvents.tryEmit(
            if (nextEnabled) "Motion telemetry guard active (auto-SOS disabled during travel bumps)"
            else "Motion telemetry guard paused"
        )
    }

    fun setAutoLockOnSos(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAutoLockOnSos(enabled)
        }
        _statusEvents.tryEmit(
            if (enabled) "Auto-Lock Screen after SOS evidence capture enabled"
            else "Auto-Lock on manual SOS disabled (Anti-Snatch still locks automatically)"
        )
    }

    fun refreshDeviceAdminStatus(): Boolean = repository.deviceLockManager.refreshStatus()

    fun createDeviceAdminActivationIntent(): Intent = repository.deviceLockManager.createActivationIntent()

    fun getAppCheckStatus(): AppCheckDiagnosticInfo = getAppCheckDiagnosticInfo(getApplication())

    fun saveAppCheckDebugToken(token: String) {
        val ok = saveCustomAppCheckDebugToken(getApplication(), token)
        _statusEvents.tryEmit(
            if (ok) {
                if (token.trim().isNotEmpty()) "App Check debug token saved for this device"
                else "Restored reCAPTCHA Enterprise App Check provider"
            } else {
                "Failed to update App Check provider configuration"
            }
        )
    }

    // Battery Optimization Exemption for Background Location & Emergency SMS
    fun requestIgnoreBatteryOptimizations(activityContext: Context) {
        val ok = repository.batteryOptimizationManager.requestIgnoreBatteryOptimizations(activityContext)
        if (ok) {
            _statusEvents.tryEmit("Prompted system battery optimization exemption for background SOS")
        } else {
            _statusEvents.tryEmit("Unable to launch battery optimization settings on this device")
        }
    }

    fun refreshBatteryOptimizationStatus() {
        repository.batteryOptimizationManager.refresh()
    }

    // Real-time Emergency Region & Numbers
    fun setEmergencyRegionOverride(countryCode: String) {
        repository.realtimeEmergencyService.setManualRegionOverride(countryCode)
        _statusEvents.tryEmit("Emergency hotlines set to ${countryCode.uppercase(Locale.US)}")
    }

    fun clearEmergencyRegionOverride() {
        repository.realtimeEmergencyService.clearManualRegionOverride()
        refreshLocation()
        _statusEvents.tryEmit("Restored automatic real-time GPS location emergency hotlines")
    }

    // Haptic Feedback & Tactile Confirmation Controls
    fun setHapticsEnabled(enabled: Boolean) {
        repository.hapticManager.setHapticsEnabled(enabled)
        _statusEvents.tryEmit(
            if (enabled) "Tactile haptic feedback enabled (Morse code SOS pattern)"
            else "Tactile haptic feedback disabled"
        )
    }

    fun previewHapticPattern(patternName: String) {
        repository.hapticManager.previewPattern(patternName)
        _statusEvents.tryEmit("Tactile pattern triggered: $patternName")
    }

    fun onSosButtonTouchTactile() {
        repository.hapticManager.vibrateSosButtonTouch()
    }

    fun toggleManualAudioRecording() {
        viewModelScope.launch {
            if (repository.audioRecorder.isRecording.value) {
                val meta = repository.audioRecorder.stopAndEncryptRecording()
                if (meta != null) {
                    _statusEvents.tryEmit("Encrypted audio evidence saved (${meta.durationSeconds}s, SHA-256 verified)")
                } else {
                    _statusEvents.tryEmit("Audio recording stopped")
                }
            } else {
                val incId = latestActiveIncident.value?.incidentId ?: "VAULT-${System.currentTimeMillis() % 100000}"
                val started = repository.audioRecorder.startAutomaticSosRecording(incId, durationSeconds = 60)
                if (started) {
                    _statusEvents.tryEmit("Recording AES-256 encrypted audio evidence...")
                } else {
                    _statusEvents.tryEmit(repository.audioRecorder.recordingError.value ?: "Microphone permission required")
                }
            }
        }
    }

    fun shareLiveLocationNow() {
        viewModelScope.launch {
            val loc = locationWrapper.getImmediateLocation(timeoutMs = 3000L)
                ?: locationWrapper.currentLocation.value
            if (loc == null) {
                _statusEvents.tryEmit("Waiting for real GPS fix — check Location permission & GPS toggle")
                return@launch
            }
            val userName = userProfile.value?.name?.takeIf { it.isNotBlank() } ?: "RakshaOne User"
            val mapLink = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            val shareBody = "LIVE LOCATION FROM $userName (RakshaOne Safety)\n" +
                "Coordinates: ${String.format(Locale.US, "%.5f", loc.latitude)}, ${String.format(Locale.US, "%.5f", loc.longitude)} (±${loc.accuracy.toInt()}m)\n" +
                "Google Maps: $mapLink"
            val opened = repository.shareTextContent("Live Emergency Location", shareBody)
            if (!opened) {
                _statusEvents.tryEmit("No sharing application available")
            }
        }
    }

    fun openNearbyEmergencyOnMaps(query: String = "police station and hospital") {
        viewModelScope.launch {
            val loc = locationWrapper.currentLocation.value
                ?: locationWrapper.getImmediateLocation(timeoutMs = 2500L)
            val opened = repository.openMapsSearch(query, loc?.latitude, loc?.longitude)
            if (!opened) {
                _statusEvents.tryEmit("No maps or browser app available to search nearby help")
            }
        }
    }

    fun openIncidentInMaps(lat: Double, lng: Double, label: String = "Incident Location") {
        val opened = repository.openCoordinatesInMaps(lat, lng, label)
        if (!opened) {
            _statusEvents.tryEmit("No maps application available")
        }
    }

    fun dialNumber(number: String, allowDirectCall: Boolean = false) {
        val ok = repository.dialEmergencyNumber(number, allowDirectCall)
        if (!ok) {
            _statusEvents.tryEmit("No phone dialer app available on this device (Number: $number)")
        }
    }

    fun resolveActiveIncident(incidentId: String, notes: String) {
        viewModelScope.launch {
            if (repository.audioRecorder.isRecording.value) {
                repository.audioRecorder.stopAndEncryptRecording()
            }
            repository.resolveOrCancelIncident(incidentId, isCancelled = false, resolutionNotes = notes)
            _sosState.value = SosCountdownState.IDLE
            _sosChannelStatus.value = null
            repository.sensorsManager.stopSiren()
            repository.sensorsManager.stopStrobe()
            _statusEvents.tryEmit("Case closed by victim — Evidence Vault & cloud dispatches purged")
        }
    }

    fun cancelActiveIncident(incidentId: String, reason: String) {
        viewModelScope.launch {
            if (repository.audioRecorder.isRecording.value) {
                repository.audioRecorder.stopAndEncryptRecording()
            }
            repository.resolveOrCancelIncident(incidentId, isCancelled = true, resolutionNotes = reason)
            _sosState.value = SosCountdownState.IDLE
            _sosChannelStatus.value = null
            repository.sensorsManager.stopSiren()
            repository.sensorsManager.stopStrobe()
            _statusEvents.tryEmit("Incident $incidentId cancelled & Evidence Vault purged")
        }
    }

    fun loadIncidentEvents(incidentId: String) {
        viewModelScope.launch {
            repository.getIncidentEvents(incidentId).collect { events ->
                _selectedIncidentEvents.value = events
            }
        }
    }

    // Operator actions
    fun setOperatorMode(enabled: Boolean) {
        _isOperatorMode.value = enabled
    }

    private fun currentOperatorName(): String {
        val authEmail = currentUser.value?.email?.takeIf { it.isNotBlank() }
        val profileName = userProfile.value?.name?.takeIf { it.isNotBlank() }
        return profileName ?: authEmail ?: "Citizen Operator"
    }

    fun operatorAcknowledge(incidentId: String) {
        viewModelScope.launch {
            repository.operatorAcknowledgeIncident(incidentId, currentOperatorName())
            loadIncidentEvents(incidentId)
        }
    }

    fun operatorAssignUnit(incidentId: String, unitName: String) {
        viewModelScope.launch {
            repository.operatorAssignUnit(incidentId, unitName, currentOperatorName())
            loadIncidentEvents(incidentId)
        }
    }

    fun operatorUpdateStatus(incidentId: String, status: String, notes: String) {
        viewModelScope.launch {
            repository.operatorUpdateStatus(incidentId, status, notes, currentOperatorName())
            loadIncidentEvents(incidentId)
        }
    }

    // Contacts
    fun addContact(
        name: String,
        relation: String,
        phone: String,
        email: String = "",
        priority: Int,
        hasWhatsApp: Boolean = true,
        allow24x7Location: Boolean = false
    ) {
        viewModelScope.launch {
            val isCore = com.example.data.model.RelationshipTrustRules.isCoreFamily(relation)
            val entity = TrustedContactEntity(
                name = name.trim(),
                relationship = relation.trim(),
                phone = phone.trim(),
                email = email.trim(),
                priority = priority,
                hasWhatsApp = hasWhatsApp,
                allow24x7Location = allow24x7Location || isCore
            )
            repository.addTrustedContact(entity)
            if (isCore) {
                repository.goodCitizenManager.addCoreFamilyTrustBonus(phone.trim(), relation.trim())
            }
            val currentAll = repository.database.trustedContactDao().getActiveContacts()
            val coreCount = currentAll.count { it.isCoreFamily() }
            repository.goodCitizenManager.checkAutoVerificationFromCircle(currentAll.size, coreCount)
            refreshInstalledContactsStatus()
            publishLiveRadarTick(isForegroundDispatch = _isDispatchTabVisible.value, forceImmediate = true)
            _statusEvents.tryEmit(
                if (isCore) "Core Family Guardian ${name.trim()} saved (+15 Trust Pts • 24/7 Live Radar enabled)"
                else "Trusted contact ${name.trim()} saved"
            )
        }
    }

    fun updateContact(contact: TrustedContactEntity) {
        viewModelScope.launch {
            repository.updateTrustedContact(contact)
            refreshInstalledContactsStatus()
            publishLiveRadarTick(isForegroundDispatch = _isDispatchTabVisible.value, forceImmediate = true)
        }
    }

    fun deleteContact(id: Long) {
        viewModelScope.launch {
            repository.deleteTrustedContact(id)
            refreshInstalledContactsStatus()
            publishLiveRadarTick(isForegroundDispatch = _isDispatchTabVisible.value, forceImmediate = true)
            _statusEvents.tryEmit("Trusted contact removed & cloud Circle updated")
        }
    }

    // Trip
    fun startTrip(
        origin: String,
        destination: String,
        duration: Int,
        interval: Int,
        contactPhones: List<String> = emptyList(),
        allowedCorridorMeters: Float = 500f,
        prolongedStopThresholdMinutes: Int = 3
    ) {
        viewModelScope.launch {
            repository.startTrip(
                origin = origin,
                destination = destination,
                plannedMinutes = duration,
                checkInIntervalMinutes = interval,
                sharedContactPhones = contactPhones,
                allowedCorridorMeters = allowedCorridorMeters,
                prolongedStopThresholdMinutes = prolongedStopThresholdMinutes
            )
        }
    }

    fun resetTripAnomalyTimer() {
        repository.resetTripAnomalyAlertTimer()
        _statusEvents.tryEmit("Trip Guard anomaly alert cleared & stationary timer reset")
    }

    fun shareCurrentTripGps(contacts: List<TrustedContactEntity>) {
        val trip = activeTrip.value ?: return
        viewModelScope.launch {
            val loc = locationWrapper.getImmediateLocation(3000L) ?: locationWrapper.currentLocation.value
            repository.shareLiveTripLocationUpdate(trip, contacts, loc)
        }
    }

    fun syncTripGpsNow() {
        viewModelScope.launch {
            if (!locationWrapper.hasFineLocationPermission()) {
                _statusEvents.tryEmit("Fine location permission required to record GPS checkpoint")
                return@launch
            }
            val loc = locationWrapper.getImmediateLocation(4000L) ?: locationWrapper.currentLocation.value
            if (loc != null) {
                val saved = repository.recordTripLocationFixNow(loc)
                if (saved) {
                    _statusEvents.tryEmit(
                        "GPS checkpoint persisted to Room (${String.format(Locale.US, "%.5f", loc.latitude)}, ${String.format(Locale.US, "%.5f", loc.longitude)})"
                    )
                }
            } else {
                _statusEvents.tryEmit("Waiting for FusedLocationProviderClient fix...")
            }
        }
    }

    fun checkInTrip(tripId: String, isSafe: Boolean) {
        viewModelScope.launch {
            repository.checkInTrip(tripId, isSafe)
        }
    }

    fun completeTrip(trip: TripEntity) {
        viewModelScope.launch {
            repository.completeTrip(trip)
        }
    }

    fun cancelTrip(trip: TripEntity) {
        viewModelScope.launch {
            repository.cancelTrip(trip)
        }
    }

    // High-Risk Zones & Routines
    fun markCurrentLocationAsHighRisk(name: String, advisory: String, radiusMeters: Float = 300f) {
        viewModelScope.launch {
            val loc = locationWrapper.getImmediateLocation(3000L) ?: locationWrapper.currentLocation.value
            if (loc == null) {
                _statusEvents.tryEmit("Need active GPS fix to mark current zone")
                return@launch
            }
            repository.addHighRiskZone(
                HighRiskZoneEntity(
                    zoneId = "ZONE-${System.currentTimeMillis() % 100000}",
                    name = name.trim(),
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    radiusMeters = radiusMeters,
                    riskLevel = "HIGH",
                    advisoryMessage = advisory.trim().ifBlank { "User-marked caution area" }
                )
            )
            _statusEvents.tryEmit("High-risk geofence registered at current GPS")
        }
    }

    fun deleteHighRiskZone(zoneId: String) {
        viewModelScope.launch {
            repository.deleteHighRiskZone(zoneId)
            _statusEvents.tryEmit("High-risk zone removed")
        }
    }

    fun saveAndStartRoutineFromCurrentGps(
        name: String,
        maxDeviationMeters: Float = 250f
    ) {
        viewModelScope.launch {
            val loc = locationWrapper.getImmediateLocation(3000L) ?: locationWrapper.currentLocation.value
            if (loc == null) {
                _statusEvents.tryEmit("Active GPS fix required to lock routine checkpoint")
                return@launch
            }
            val routineName = name.trim().ifBlank { "Safe Corridor" }
            val routine = SavedRoutineEntity(
                routineId = "RTN-${System.currentTimeMillis() % 100000}",
                name = routineName,
                originName = "Locked GPS Checkpoint",
                destinationName = routineName,
                waypointsJson = "${loc.latitude},${loc.longitude}",
                maxDeviationMeters = maxDeviationMeters,
                isMonitoringActive = true,
                lastKnownDeviationMeters = 0f,
                lastCheckTimestamp = System.currentTimeMillis()
            )
            repository.saveRoutine(routine)
            repository.geofenceManager.startRoutineMonitoring(routine)
            _statusEvents.tryEmit("Routine '${routine.name}' locked at GPS (${String.format(Locale.US, "%.4f", loc.latitude)}, ${String.format(Locale.US, "%.4f", loc.longitude)})")
        }
    }

    fun toggleRoutineMonitoring(routine: SavedRoutineEntity) {
        viewModelScope.launch {
            if (activeRoutine.value?.routineId == routine.routineId) {
                repository.geofenceManager.stopRoutineMonitoring(routine.routineId)
                _statusEvents.tryEmit("Stopped monitoring '${routine.name}'")
            } else {
                repository.geofenceManager.startRoutineMonitoring(routine)
                _statusEvents.tryEmit("Monitoring route deviation for '${routine.name}'")
            }
        }
    }

    suspend fun decryptAudioForPlayback(filePath: String): java.io.File? {
        return repository.audioRecorder.decryptToTempForPlayback(filePath)
    }

    // Auth actions (Google Sign-In / Registration + Guest Offline Vault)
    fun attemptAutoSignIn(activityContext: Context) {
        viewModelScope.launch {
            authManager.attemptAutoSignIn(activityContext)
        }
    }

    fun signInOrRegisterWithGoogle(
        activityContext: Context,
        pendingPhone: String = "",
        pendingBloodGroup: String = "",
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = authManager.signInWithGoogle(activityContext)
            val signedInUser = result.getOrNull()
            if (signedInUser != null) {
                val existingLocal = repository.database.userDao().getUserProfileOnce() ?: UserProfileEntity()
                val updatedLocal = existingLocal.copy(
                    name = existingLocal.name.ifBlank { signedInUser.displayName.orEmpty() },
                    email = existingLocal.email.ifBlank { signedInUser.email.orEmpty() },
                    phone = pendingPhone.trim().ifBlank { existingLocal.phone },
                    bloodGroup = pendingBloodGroup.trim().ifBlank { existingLocal.bloodGroup }
                )
                repository.saveUserProfile(updatedLocal)
                if (authManager.isFirebaseConfigured()) {
                    firestoreProfileRepository.upsertProfile(
                        profile = updatedLocal,
                        authDisplayName = signedInUser.displayName,
                        authEmail = signedInUser.email
                    )
                }
                _statusEvents.tryEmit("Signed in as ${signedInUser.email ?: signedInUser.displayName ?: "Verified User"}")
                onSuccess()
            }
        }
    }

    fun clearAuthError() {
        authManager.clearAuthError()
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            _statusEvents.tryEmit("Signed out — Guest Offline Vault remains active")
        }
    }

    // Sensor Telemetry & Report Submission
    fun startReportingTelemetry() {
        repository.sensorsManager.startSnapshotMonitoring()
        refreshSensorSnapshot()
    }

    fun stopReportingTelemetry() {
        repository.sensorsManager.stopSnapshotMonitoring()
    }

    fun refreshSensorSnapshot() {
        val loc = locationWrapper.currentLocation.value
        _latestSensorSnapshot.value = repository.sensorsManager.captureDeviceSensorSnapshot(loc)
    }

    fun fileReport(
        category: String,
        desc: String,
        loc: String,
        evTitle: String,
        evType: String,
        attachmentBytes: ByteArray? = null,
        attachmentStoragePath: String? = null,
        onDone: (String) -> Unit
    ) {
        viewModelScope.launch {
            val currentGps = locationWrapper.currentLocation.value ?: locationWrapper.getImmediateLocation(2500L)
            val snapshot = repository.sensorsManager.captureDeviceSensorSnapshot(currentGps)
            _latestSensorSnapshot.value = snapshot
            val incId = repository.reportIncident(
                category = category,
                description = desc,
                locationText = loc,
                evidenceTitle = evTitle,
                evidenceType = evType,
                resolvedLocation = currentGps,
                attachmentBytes = attachmentBytes,
                attachmentStoragePath = attachmentStoragePath,
                capturedSensorSnapshot = snapshot
            )
            _statusEvents.tryEmit("Incident $incId submitted to Dispatcher Portal with SHA-256 sensor snapshot")
            onDone(incId)
        }
    }

    // Profile
    fun updateProfile(profile: UserProfileEntity) {
        viewModelScope.launch {
            repository.saveUserProfile(profile)
            val user = currentUser.value
            if (user != null && authManager.isFirebaseConfigured()) {
                val syncResult = firestoreProfileRepository.upsertProfile(
                    profile = profile,
                    authDisplayName = user.displayName,
                    authEmail = user.email
                )
                if (syncResult.isFailure) {
                    _statusEvents.tryEmit("Saved offline locally; cloud sync will retry later")
                }
            }
        }
    }

    // AI Chat (Zero-API-key Firebase AI Logic + SurakshaContextBuilder Offline Survival Brain + Auto Read-Out)
    fun sendAiMessage(query: String, appendUserMessage: Boolean = true) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        aiGenerationJob?.cancel()
        val priorHistory = _aiMessages.value
        if (appendUserMessage) {
            _aiMessages.value = priorHistory + AiMessage(sender = "user", text = trimmed)
        }
        _isAiLoading.value = true
        _aiUiState.value = SurakshaAiUiState.Loading

        aiGenerationJob = viewModelScope.launch {
            val info = emergencyInfo.value
            val threat = predictiveThreat.value
            val langName = voiceGuardianManager.selectedLanguage.value.name
            val persona = voiceGuardianManager.voicePersona.value
            val batteryPct = repository.sensorsManager.captureDeviceSensorSnapshot(locationWrapper.currentLocation.value).batteryPercent
            val runtimeCtx = buildString {
                append("UserCountry=${info.countryName}; Locality=${info.locality.ifBlank { "Unknown" }}; ")
                append("Battery=${batteryPct}%; ActiveTrip=${activeTrip.value != null}; ")
                append("SosActive=${latestActiveIncident.value != null}; ")
                append("RiskScore=${threat.threatScore}/100 (${threat.tier.name}, ${threat.sovereigntyTier.title})")
            }
            aiService.streamAiResponse(
                userQuery = trimmed,
                conversationHistory = priorHistory,
                primarySosNumber = info.primarySosNumber,
                runtimeContext = runtimeCtx,
                languageName = langName,
                persona = persona
            ).collect { event ->
                when (event) {
                    is SurakshaStreamEvent.Partial -> {
                        _aiUiState.value = SurakshaAiUiState.Streaming(event.accumulatedText)
                    }
                    is SurakshaStreamEvent.Completed -> {
                        _aiMessages.value = _aiMessages.value + event.message
                        _aiUiState.value = SurakshaAiUiState.Idle
                        _isAiLoading.value = false
                        val lowerQ = trimmed.lowercase(Locale.US)
                        val requestedCall = lowerQ.contains("fake call") ||
                            lowerQ.contains("call me") ||
                            lowerQ.contains("rescue call") ||
                            lowerQ.contains("trigger call")
                        if (requestedCall && fakeCallState.value == FakeCallState.IDLE) {
                            scheduleFakeCall(
                                delaySeconds = 0,
                                isAiProactive = true,
                                autoRecordAndShare = true
                            )
                            _statusEvents.tryEmit("Suraksha AI initiated a Fake Incoming Call via FakeIncomingCallService")
                        } else if (voiceGuardianManager.autoReadOut.value) {
                            voiceGuardianManager.speak(event.message.text)
                        }
                    }
                    is SurakshaStreamEvent.Error -> {
                        _aiUiState.value = SurakshaAiUiState.Error(
                            message = event.errorMessage,
                            failedQuery = trimmed
                        )
                        _isAiLoading.value = false
                    }
                }
            }
        }
    }

    fun retryLastAiMessage() {
        val errorState = _aiUiState.value as? SurakshaAiUiState.Error ?: return
        sendAiMessage(query = errorState.failedQuery, appendUserMessage = false)
    }

    fun dismissAiError() {
        if (_aiUiState.value is SurakshaAiUiState.Error) {
            _aiUiState.value = SurakshaAiUiState.Idle
        }
    }

    fun clearAiChat() {
        aiGenerationJob?.cancel()
        _isAiLoading.value = false
        _aiUiState.value = SurakshaAiUiState.Idle
        _aiMessages.value = listOf(
            AiMessage(
                sender = "assistant",
                text = "Chat history cleared. I am Suraksha AI, ready to assist with your safety."
            )
        )
    }

    // Offline alert synchronization
    fun syncPendingOfflineAlertsNow() {
        viewModelScope.launch {
            if (!isOnline.value) {
                _statusEvents.tryEmit("Still offline — alerts stay safely queued in Room until network returns")
                return@launch
            }
            val result = repository.dispatchPendingOfflineAlerts(initiatedFromUserAction = true)
            val msg = when {
                result.totalPending == 0 -> "No pending offline alerts to sync"
                result.dispatchedCount > 0 -> "Synced ${result.dispatchedCount}/${result.totalPending} queued alert(s)"
                else -> "Could not dispatch ${result.requiresUserActionCount} queued alert(s) — check trusted contact phone numbers"
            }
            _statusEvents.tryEmit(msg)
        }
    }

    // Fake call controls: Google Phone UI + Auto Voice Recording & Auto Circle Share
    fun toggleFakeCallDefaultAutoRecordAndShare(enabled: Boolean) {
        _fakeCallDefaultAutoRecordAndShare.value = enabled
        _statusEvents.tryEmit(
            if (enabled) "Fake Call Auto-Record & Auto-Send to Circle ON"
            else "Fake Call Auto-Record & Share paused for next call"
        )
    }

    fun scheduleFakeCall(
        delaySeconds: Int,
        name: String? = null,
        number: String? = null,
        isAiProactive: Boolean = false,
        autoRecordAndShare: Boolean = _fakeCallDefaultAutoRecordAndShare.value
    ) {
        val primaryContact = trustedContacts.value.firstOrNull()
        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: primaryContact?.name
            ?: "Emergency Guardian"
        val resolvedNumber = number?.takeIf { it.isNotBlank() }
            ?: primaryContact?.phone?.takeIf { it.isNotBlank() }
            ?: "Mobile • HD Voice"
        val resolvedRole = if (isAiProactive) "AI Guard • HD Voice" else "Mobile • HD Voice"
        val appContext = getApplication<Application>()
        try {
            com.example.service.FakeIncomingCallService.startCall(
                context = appContext,
                callerName = resolvedName,
                callerNumber = resolvedNumber,
                callerRole = resolvedRole,
                delaySeconds = delaySeconds,
                isAiProactive = isAiProactive,
                autoRecordAndShare = autoRecordAndShare,
                triggerSource = if (isAiProactive) "AI_PROACTIVE" else "USER_MANUAL"
            )
        } catch (_: Exception) {
            repository.fakeCallManager.setCaller(
                name = resolvedName,
                number = resolvedNumber,
                role = resolvedRole,
                isAiProactive = isAiProactive,
                autoRecordAndShare = autoRecordAndShare
            )
            repository.fakeCallManager.scheduleCall(delaySeconds)
        }
    }

    fun answerFakeCall() {
        com.example.service.FakeIncomingCallService.answerCall(getApplication())
    }

    fun endFakeCall() {
        com.example.service.FakeIncomingCallService.endOrDeclineCall(getApplication())
    }

    fun cancelFakeCall() {
        repository.fakeCallManager.cancelCall()
        com.example.service.FakeIncomingCallService.endOrDeclineCall(getApplication())
    }

    fun toggleFakeCallHold() {
        com.example.service.FakeIncomingCallService.toggleHold(getApplication())
    }

    fun startFakeCallVoiceCaptureNow() {
        com.example.service.FakeIncomingCallService.startAudioCaptureNow(getApplication())
        if (!repository.audioRecorder.hasAudioPermission()) {
            _statusEvents.tryEmit("Grant Microphone permission to record voice evidence")
        }
    }

    fun dispatchFakeCallAutoEvidenceNow(reason: String = "Fake Call Auto Guard") {
        viewModelScope.launch {
            val cfg = fakeCallConfig.value
            val loc = locationWrapper.getImmediateLocation(2500L) ?: locationWrapper.currentLocation.value
            val audioFile = repository.latestShareableAudioFile.value
            val ok = repository.dispatchFakeCallAutoProtection(
                config = cfg.copy(autoRecordAndShare = true),
                location = loc,
                audioFile = audioFile
            )
            _statusEvents.tryEmit(
                if (ok) "Trusted Circle alerted silently ($reason)"
                else "Circle alert queued ($reason)"
            )
        }
    }

    fun captureEvidenceSnapshotNow() {
        val incId = latestActiveIncident.value?.incidentId ?: "SNAP-${System.currentTimeMillis() % 100000}"
        repository.captureEvidencePhotoNow(incId) { file ->
            if (file != null) {
                _statusEvents.tryEmit("Emergency snapshot saved in cache (${file.length() / 1024} KB)")
            } else {
                _statusEvents.tryEmit(repository.cameraVideoRecorder.captureStatus.value)
            }
        }
    }

    // Trusted-Contacts-Only SMS & MMS Evidence Dispatch (Play Protect safe: zero restricted SEND_SMS permission)
    fun shareSosSmsNow() {
        viewModelScope.launch {
            val contacts = repository.database.trustedContactDao().getActiveContacts()
            val phones = contacts.map { it.phone.trim() }.filter { it.isNotBlank() }
            if (phones.isEmpty()) {
                _statusEvents.tryEmit("No Trusted Contacts saved — add contacts in Circle tab before sending SMS.")
                return@launch
            }
            val loc = locationWrapper.getImmediateLocation(3000L) ?: locationWrapper.currentLocation.value
            val user = repository.database.userDao().getUserProfileOnce()
            val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
            val activeInc = latestActiveIncident.value
            val incidentId = activeInc?.incidentId ?: "SOS-${System.currentTimeMillis() % 100000}"
            val isOnlineNow = repository.networkMonitor.isInternetCurrentlyAvailable()
            val reason = when {
                !isOnlineNow -> "INTERNET_UNAVAILABLE"
                !repository.videoEvidenceManager.isWhatsAppInstalled -> "WHATSAPP_UNAVAILABLE"
                else -> "TRUSTED_DUAL_CHANNEL_ACTIVE"
            }

            val smsText = com.example.service.SosAlertService.buildEmergencySmsBody(
                userName = userName,
                incidentId = incidentId,
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                accuracyMeters = loc?.accuracy,
                userNote = if (_isSilentMode.value) "SILENT SOS ALERT - Reply or Call Caretaker" else "URGENT SOS ALERT",
                fallbackReason = reason
            )

            val bundle = repository.videoEvidenceManager.resolveIncidentMediaBundle(
                incidentId = activeInc?.incidentId,
                videoPath = lastVideoPath.value ?: activeInc?.videoEvidencePath,
                photoPaths = capturedPhotoPaths.value,
                audioPath = latestShareableAudioFile.value?.absolutePath,
                videoPaths = capturedVideoPaths.value,
                audioPaths = capturedAudioPaths.value
            )
            val ok = repository.videoEvidenceManager.sendMediaBundleViaMmsAndSms(
                dossierText = smsText,
                bundle = bundle,
                targetPhones = phones
            )
            if (ok && activeInc != null) {
                repository.escalationOrchestrator.markChannelDispatched(
                    incidentId = activeInc.incidentId,
                    channel = "TRUSTED_SMS",
                    deliveryState = ReceiptDeliveryState.INTENT_LAUNCHED,
                    details = "Launched SMS/MMS to ${phones.size} trusted contact(s) with ${bundle.uris.size} evidence file(s)"
                )
            }
            _statusEvents.tryEmit(repository.videoEvidenceManager.lastDispatchStatus.value)
        }
    }

    fun cycleNextWhatsAppContact() {
        val waContacts = repository.videoEvidenceManager.selectWhatsAppEnabledContacts(trustedContacts.value)
        if (waContacts.isEmpty()) {
            _selectedWhatsAppContactIndex.value = 0
            _statusEvents.tryEmit("No WhatsApp-enabled trusted contacts saved in Circle tab")
            return
        }
        val nextIdx = (_selectedWhatsAppContactIndex.value + 1) % waContacts.size
        _selectedWhatsAppContactIndex.value = nextIdx
        val nextContact = waContacts[nextIdx]
        _statusEvents.tryEmit("Selected WhatsApp recipient: ${nextContact.name} (${nextContact.phone})")
    }

    // Master Secured Evidence Dispatch: sends ALL audio, video, and photo files
    fun dispatchSosEvidenceWithFallback(
        incidentId: String? = latestActiveIncident.value?.incidentId,
        preferWhatsApp: Boolean = true
    ) {
        viewModelScope.launch {
            val contacts = repository.database.trustedContactDao().getActiveContacts()
            val phones = contacts.map { it.phone.trim() }.filter { it.isNotBlank() }
            val waPhones = repository.videoEvidenceManager.selectWhatsAppEnabledContacts(contacts).map { it.phone }
            if (phones.isEmpty() && waPhones.isEmpty()) {
                _statusEvents.tryEmit("Add Trusted Contacts in Circle tab to share SOS audio/video/photos via SMS & WhatsApp.")
                return@launch
            }

            val activeInc = latestActiveIncident.value
            val resolvedIncidentId = incidentId ?: activeInc?.incidentId
            val videoPath = lastVideoPath.value ?: activeInc?.videoEvidencePath
            val photos = capturedPhotoPaths.value
            val audioPath = latestShareableAudioFile.value?.absolutePath
            val loc = locationWrapper.getImmediateLocation(3000L) ?: locationWrapper.currentLocation.value
            val user = repository.database.userDao().getUserProfileOnce()
            val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
            val isOnlineNow = repository.networkMonitor.isInternetCurrentlyAvailable()

            val outcome = repository.videoEvidenceManager.dispatchEvidenceWithFallback(
                incidentId = resolvedIncidentId,
                userName = userName,
                location = loc,
                locality = emergencyInfo.value.locality.takeIf { !it.startsWith("Detecting") && !it.startsWith("Acquiring") },
                customNote = "Trusted Contacts Only • ALL Evidence (Videos + Front/Rear Photos + Voice Audio)",
                videoPath = videoPath,
                photoPaths = photos,
                audioPath = audioPath,
                targetPhones = phones,
                whatsAppTargetPhones = waPhones,
                contactIndex = _selectedWhatsAppContactIndex.value,
                isOnline = isOnlineNow,
                preferWhatsApp = preferWhatsApp,
                videoPaths = capturedVideoPaths.value,
                audioPaths = capturedAudioPaths.value
            )

            if (resolvedIncidentId != null) {
                repository.logMediaDispatchOutcome(
                    incidentId = resolvedIncidentId,
                    result = outcome,
                    triggerCarrierSmsIfWhatsAppFailed = true
                )
                repository.escalationOrchestrator.markChannelDispatched(
                    incidentId = resolvedIncidentId,
                    channel = if (outcome.whatsAppLaunched) "TRUSTED_WHATSAPP" else "TRUSTED_SMS",
                    deliveryState = ReceiptDeliveryState.INTENT_LAUNCHED,
                    details = outcome.statusMessage
                )
                _sosChannelStatus.value = _sosChannelStatus.value?.copy(
                    isOnline = isOnlineNow,
                    smsFallbackTriggered = _sosChannelStatus.value?.smsFallbackTriggered == true || outcome.usedSmsMmsFallback,
                    fallbackReason = outcome.fallbackReason,
                    mediaDispatchSummary = outcome.statusMessage
                ) ?: SosChannelStatus(
                    incidentId = resolvedIncidentId,
                    isOnline = isOnlineNow,
                    isWhatsAppAvailable = repository.videoEvidenceManager.isWhatsAppInstalled,
                    smsFallbackTriggered = outcome.usedSmsMmsFallback,
                    fallbackReason = outcome.fallbackReason,
                    summary = outcome.statusMessage,
                    mediaDispatchSummary = outcome.statusMessage
                )
            }
            _statusEvents.tryEmit(outcome.statusMessage)
        }
    }

    fun sharePhotosViaWhatsApp() {
        viewModelScope.launch {
            val contacts = repository.database.trustedContactDao().getActiveContacts()
            val waContacts = repository.videoEvidenceManager.selectWhatsAppEnabledContacts(contacts)
            if (waContacts.isEmpty()) {
                _statusEvents.tryEmit("Add a WhatsApp-enabled Trusted Contact in Circle tab first.")
                return@launch
            }
            val activeInc = latestActiveIncident.value
            val bundle = repository.videoEvidenceManager.resolveIncidentMediaBundle(
                incidentId = activeInc?.incidentId,
                videoPath = lastVideoPath.value ?: activeInc?.videoEvidencePath,
                photoPaths = capturedPhotoPaths.value,
                audioPath = latestShareableAudioFile.value?.absolutePath
            )
            if (bundle.photoUris.isEmpty()) {
                _statusEvents.tryEmit("No captured Front/Rear photos yet — tap Snap Photo or trigger SOS")
                return@launch
            }
            val chosenPhone = waContacts[_selectedWhatsAppContactIndex.value.coerceIn(0, waContacts.lastIndex) % waContacts.size].phone
            val user = repository.database.userDao().getUserProfileOnce()
            val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
            val loc = locationWrapper.getImmediateLocation(2500L) ?: locationWrapper.currentLocation.value
            val dossier = repository.videoEvidenceManager.buildEmergencyDossier(
                incidentId = activeInc?.incidentId ?: "SOS-LIVE",
                userName = userName,
                location = loc,
                locality = emergencyInfo.value.locality.takeIf { !it.startsWith("Detecting") && !it.startsWith("Acquiring") },
                customNote = "Front + Rear Camera Emergency Snapshots (F:${bundle.frontPhotoFiles.size}, R:${bundle.rearPhotoFiles.size})",
                evidenceSummary = bundle.sha256DigestSummary
            )
            val ok = repository.videoEvidenceManager.sharePhotosViaWhatsApp(bundle, dossier, chosenPhone)
            if (ok) {
                activeInc?.incidentId?.let {
                    repository.escalationOrchestrator.markChannelDispatched(it, "TRUSTED_WHATSAPP", ReceiptDeliveryState.INTENT_LAUNCHED, details = "Photos sent to $chosenPhone")
                }
                _statusEvents.tryEmit(repository.videoEvidenceManager.lastDispatchStatus.value)
            } else {
                shareSosSmsNow()
            }
        }
    }

    fun shareAudioViaWhatsApp() {
        viewModelScope.launch {
            val contacts = repository.database.trustedContactDao().getActiveContacts()
            val waContacts = repository.videoEvidenceManager.selectWhatsAppEnabledContacts(contacts)
            if (waContacts.isEmpty()) {
                _statusEvents.tryEmit("Add a WhatsApp-enabled Trusted Contact in Circle tab first.")
                return@launch
            }
            val activeInc = latestActiveIncident.value
            val bundle = repository.videoEvidenceManager.resolveIncidentMediaBundle(
                incidentId = activeInc?.incidentId,
                videoPath = lastVideoPath.value ?: activeInc?.videoEvidencePath,
                photoPaths = capturedPhotoPaths.value,
                audioPath = latestShareableAudioFile.value?.absolutePath,
                videoPaths = capturedVideoPaths.value,
                audioPaths = capturedAudioPaths.value
            )
            if (bundle.audioUris.isEmpty()) {
                _statusEvents.tryEmit("No .m4a voice evidence recorded yet — tap Record Audio or trigger SOS")
                return@launch
            }
            val chosenPhone = waContacts[_selectedWhatsAppContactIndex.value.coerceIn(0, waContacts.lastIndex) % waContacts.size].phone
            val user = repository.database.userDao().getUserProfileOnce()
            val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
            val loc = locationWrapper.getImmediateLocation(2500L) ?: locationWrapper.currentLocation.value
            val dossier = repository.videoEvidenceManager.buildEmergencyDossier(
                incidentId = activeInc?.incidentId ?: "SOS-LIVE",
                userName = userName,
                location = loc,
                locality = emergencyInfo.value.locality.takeIf { !it.startsWith("Detecting") && !it.startsWith("Acquiring") },
                customNote = "ALL Emergency Voice Audio Recordings (.m4a)",
                evidenceSummary = bundle.sha256DigestSummary
            )
            val ok = repository.videoEvidenceManager.shareAudioViaWhatsApp(bundle, dossier, chosenPhone)
            if (ok) {
                activeInc?.incidentId?.let {
                    repository.escalationOrchestrator.markChannelDispatched(it, "TRUSTED_WHATSAPP", ReceiptDeliveryState.INTENT_LAUNCHED, details = "All audio (${bundle.audioFiles.size}) sent to $chosenPhone")
                }
                _statusEvents.tryEmit(repository.videoEvidenceManager.lastDispatchStatus.value)
            } else {
                shareSosSmsNow()
            }
        }
    }

    fun shareAllEvidenceBundleEverywhere() {
        viewModelScope.launch {
            val contacts = repository.database.trustedContactDao().getActiveContacts()
            val emails = contacts.map { it.email.trim() }.filter { it.isNotBlank() }
            val activeInc = latestActiveIncident.value
            val user = repository.database.userDao().getUserProfileOnce()
            val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
            val loc = locationWrapper.getImmediateLocation(2500L) ?: locationWrapper.currentLocation.value
            val bundle = repository.videoEvidenceManager.resolveIncidentMediaBundle(
                incidentId = activeInc?.incidentId,
                videoPath = lastVideoPath.value ?: activeInc?.videoEvidencePath,
                photoPaths = capturedPhotoPaths.value,
                audioPath = latestShareableAudioFile.value?.absolutePath,
                videoPaths = capturedVideoPaths.value,
                audioPaths = capturedAudioPaths.value
            )
            val dossier = repository.videoEvidenceManager.buildEmergencyDossier(
                incidentId = activeInc?.incidentId ?: "SOS-LIVE",
                userName = userName,
                location = loc,
                locality = emergencyInfo.value.locality.takeIf { !it.startsWith("Detecting") && !it.startsWith("Acquiring") },
                customNote = "Complete Multi-Evidence Bundle (Audio + Video + Front/Rear Photos)",
                evidenceSummary = bundle.sha256DigestSummary
            )
            repository.videoEvidenceManager.shareEvidenceBundleViaApps(
                dossierText = dossier,
                videoPath = lastVideoPath.value ?: activeInc?.videoEvidencePath,
                photoPaths = capturedPhotoPaths.value,
                audioPath = latestShareableAudioFile.value?.absolutePath,
                recipientEmails = emails,
                subject = "CRITICAL SOS: ALL Evidence (${bundle.videoFiles.size} Vid, ${bundle.photoFiles.size} Img, ${bundle.audioFiles.size} Aud)",
                incidentId = activeInc?.incidentId,
                videoPaths = capturedVideoPaths.value,
                audioPaths = capturedAudioPaths.value
            )
            _statusEvents.tryEmit(repository.videoEvidenceManager.lastDispatchStatus.value)
        }
    }

    fun emailAllEvidenceToGuardians() {
        viewModelScope.launch {
            val contacts = repository.database.trustedContactDao().getActiveContacts()
            val emails = contacts.map { it.email.trim() }.filter { it.isNotBlank() }.distinct()
            if (emails.isEmpty()) {
                _statusEvents.tryEmit("Add an Email address to Trusted Contacts in Circle tab to send Email evidence.")
                return@launch
            }
            val activeInc = latestActiveIncident.value
            val user = repository.database.userDao().getUserProfileOnce()
            val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
            val loc = locationWrapper.getImmediateLocation(2500L) ?: locationWrapper.currentLocation.value
            val bundle = repository.videoEvidenceManager.resolveIncidentMediaBundle(
                incidentId = activeInc?.incidentId,
                videoPath = lastVideoPath.value ?: activeInc?.videoEvidencePath,
                photoPaths = capturedPhotoPaths.value,
                audioPath = latestShareableAudioFile.value?.absolutePath,
                videoPaths = capturedVideoPaths.value,
                audioPaths = capturedAudioPaths.value
            )
            val dossier = repository.videoEvidenceManager.buildEmergencyDossier(
                incidentId = activeInc?.incidentId ?: "SOS-LIVE",
                userName = userName,
                location = loc,
                locality = emergencyInfo.value.locality.takeIf { !it.startsWith("Detecting") && !it.startsWith("Acquiring") },
                customNote = "ALL Recorded Evidence Attached (${bundle.videoFiles.size} Video, ${bundle.photoFiles.size} Photo, ${bundle.audioFiles.size} Audio)",
                evidenceSummary = bundle.sha256DigestSummary
            )
            repository.sendEmergencyEmail(
                recipientEmails = emails,
                subject = "CRITICAL SOS: ${activeInc?.incidentId ?: "LIVE"} (${bundle.uris.size} Attachments)",
                body = dossier,
                attachmentUris = bundle.uris
            )
            _statusEvents.tryEmit("Opened Email with ALL ${bundle.uris.size} audio/video/photo file(s)")
        }
    }

    // Trusted-Contact-Only WhatsApp Live Location
    fun shareLiveLocationWhatsApp() {
        viewModelScope.launch {
            val contacts = repository.database.trustedContactDao().getActiveContacts()
            if (contacts.isEmpty()) {
                _statusEvents.tryEmit("Add Trusted Contacts in Circle tab to send WhatsApp/SMS alerts.")
                return@launch
            }
            val waContacts = repository.videoEvidenceManager.selectWhatsAppEnabledContacts(contacts)
            val chosenContact = if (waContacts.isNotEmpty()) {
                waContacts[_selectedWhatsAppContactIndex.value.coerceIn(0, waContacts.lastIndex) % waContacts.size]
            } else null
            val loc = locationWrapper.getImmediateLocation(3000L) ?: locationWrapper.currentLocation.value
            val user = repository.database.userDao().getUserProfileOnce()
            val userName = user?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
            val isOnlineNow = repository.networkMonitor.isInternetCurrentlyAvailable()
            val whatsAppPkg = repository.videoEvidenceManager.resolveWhatsAppPackage()

            val locStr = if (loc != null) {
                "URGENT SAFETY ALERT from $userName\nMy Live GPS Location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}\nAccuracy: ±${loc.accuracy.toInt()}m\nPlease verify my safety immediately!"
            } else {
                "URGENT SAFETY ALERT from $userName\nMy Live GPS Location fix pending.\nPlease verify my safety immediately!"
            }

            if (isOnlineNow && whatsAppPkg != null && chosenContact != null) {
                try {
                    val launched = repository.videoEvidenceManager.launchWhatsAppMediaIntent(
                        whatsAppPkg = whatsAppPkg,
                        dossierText = locStr,
                        uris = ArrayList(),
                        mimeType = "text/plain",
                        trustedPhone = chosenContact.phone
                    )
                    if (launched) {
                        _statusEvents.tryEmit("Opened WhatsApp live GPS chat with trusted contact ${chosenContact.name}")
                        return@launch
                    }
                } catch (_: Exception) {
                }
            }

            shareSosSmsNow()
        }
    }

    fun shareVideoEvidenceViaWhatsApp() {
        dispatchSosEvidenceWithFallback(
            incidentId = latestActiveIncident.value?.incidentId,
            preferWhatsApp = true
        )
    }

    fun shareVaultEvidenceFile(evidence: EvidenceEntity) {
        val file = File(evidence.storageLocation)
        if (!file.exists()) {
            _statusEvents.tryEmit("Local cache file no longer exists on device")
            return
        }
        viewModelScope.launch {
            val contacts = repository.database.trustedContactDao().getActiveContacts()
            val waContacts = repository.videoEvidenceManager.selectWhatsAppEnabledContacts(contacts)
            val phones = contacts.map { it.phone.trim() }.filter { it.isNotBlank() }
            if (phones.isEmpty() && waContacts.isEmpty()) {
                _statusEvents.tryEmit("Add Trusted Contacts in Circle tab to share vault evidence files.")
                return@launch
            }
            val isVideo = evidence.fileType.contains("VIDEO", ignoreCase = true)
            val isAudio = evidence.fileType.contains("AUDIO", ignoreCase = true)
            dispatchSosEvidenceWithFallback(
                incidentId = evidence.incidentId,
                preferWhatsApp = waContacts.isNotEmpty()
            )
            if (!isVideo && !isAudio) {
                _statusEvents.tryEmit("Prepared ${evidence.title} for trusted contacts")
            }
        }
    }

    fun deleteEvidenceItem(incidentId: String) {
        viewModelScope.launch {
            try {
                repository.deleteEvidenceForIncident(incidentId)
                _statusEvents.tryEmit("Evidence removed from vault")
            } catch (e: Exception) {
                _statusEvents.tryEmit("Error removing evidence: ${e.message}")
            }
        }
    }

    fun refreshInstalledContactsStatus() {
        viewModelScope.launch {
            val contacts = repository.database.trustedContactDao().getActiveContacts()
            if (currentUser.value != null && authManager.isFirebaseConfigured() && contacts.isNotEmpty()) {
                try {
                    val details = firestoreProfileRepository.resolveInstalledGuardianDetails(
                        contacts.map { it.phone }
                    )
                    _installedContactsMap.value = details.installedByRawPhone
                    repository.liveRadarManager.updateTrustedContactsContext(
                        contacts = contacts,
                        resolvedUids = details.resolvedUids,
                        uidToPhoneMap = details.uidToPhoneKey,
                        uidToEmailMap = details.uidToEmail
                    )
                } catch (_: Exception) {}
            }
        }
    }

    fun dismissGuardianHighAlertBanner() {
        _activeGuardianHighAlert.value = null
    }

    fun selectGuardianAlert(dispatchId: String) {
        val match = incomingGuardianAlerts.value.firstOrNull { it.dispatchId == dispatchId }
        if (match != null) {
            _activeGuardianHighAlert.value = match
        }
    }

    fun handleDeepLinkUri(uri: android.net.Uri?, guardianDispatchId: String? = null) {
        if (!guardianDispatchId.isNullOrBlank()) {
            selectGuardianAlert(guardianDispatchId)
        }
        if (uri == null) return
        if (uri.scheme == "rakshaone" && uri.host == "verify-peer") {
            val peerUid = uri.getQueryParameter("uid").orEmpty()
            val token = uri.getQueryParameter("code").orEmpty()
            verifyPeerByDeepLink(peerUid, token)
            return
        }
        if (uri.scheme == "rakshaone" && uri.host == "dispatch") {
            val incidentId = uri.pathSegments.firstOrNull().orEmpty()
            val token = uri.getQueryParameter("token").orEmpty()
            viewModelScope.launch {
                val live = latestPoliceDossier.value?.takeIf { it.incidentId == incidentId }
                if (live != null) {
                    _activeLiveDossier.value = live
                    return@launch
                }
                if (token.isNotBlank()) {
                    val cloudDossier = repository.firestoreRiskRepository.fetchDossierByToken(token)
                    if (cloudDossier != null) {
                        _activeLiveDossier.value = cloudDossier
                        return@launch
                    }
                }
                val localDossier = if (incidentId.isNotBlank()) {
                    repository.escalationOrchestrator.getDossierAccess(incidentId)
                } else null
                if (localDossier != null) {
                    val parsed = repository.firestoreRiskRepository.fetchDossierByToken(localDossier.tokenId)
                    if (parsed != null) {
                        _activeLiveDossier.value = parsed
                        return@launch
                    }
                }
                _statusEvents.tryEmit("Dossier $incidentId is unavailable or was already purged after victim resolution.")
            }
        }
    }

    // Hexagonal Six-Directional Rescue Pickup Lifecycle (Fixed real GPS coordinates + Anti-Predator Selfie)
    fun acceptHexHelpRequest(
        alertId: String,
        responderPhone: String? = null,
        responderSelfieB64: String? = null
    ) {
        val loc = locationWrapper.currentLocation.value
        val lat = loc?.latitude
        val lng = loc?.longitude
        val phone = responderPhone ?: userProfile.value?.phone?.ifBlank { repository.goodCitizenManager.getVerifiedPhoneNumber() }
        if (repository.hexagonalRescueManager.acceptIncomingHelpRequest(
                alertId = alertId,
                responderPhone = phone,
                responderSelfieB64 = responderSelfieB64,
                responderLat = lat,
                responderLng = lng
            )
        ) {
            _statusEvents.tryEmit("Rescue pickup accepted! +15 Trust Points awarded. Live selfie, phone & GPS shared with victim & family.")
        }
    }

    fun markHexEnRoute(alertId: String) {
        if (repository.hexagonalRescueManager.markResponderEnRoute(alertId)) {
            _statusEvents.tryEmit("Status updated: EN ROUTE to victim.")
        }
    }

    fun markHexArrived(alertId: String) {
        if (repository.hexagonalRescueManager.markResponderArrived(alertId)) {
            _statusEvents.tryEmit("Status updated: ARRIVED at rescue sector.")
        }
    }

    fun completeHexRescue(alertId: String) {
        if (repository.hexagonalRescueManager.completePickup(alertId)) {
            _statusEvents.tryEmit("Rescue pickup completed! +25 Trust Points awarded.")
        }
    }

    fun declineHexHelpRequest(alertId: String) {
        if (repository.hexagonalRescueManager.declineIncomingHelpRequest(alertId)) {
            _statusEvents.tryEmit("Help request declined (-5 Trust Points deducted).")
        }
    }

    fun dismissHexHelpRequest() {
        repository.hexagonalRescueManager.dismissIncomingAlert()
    }

    private var dossierPollingJob: Job? = null

    // Live Dossier & Permitted 112 Escalation Launchers (Policy-Gated to R3_CRITICAL)
    fun openLiveDossier(dossier: PoliceDispatchDossier) {
        _activeLiveDossier.value = dossier
        startDossierPolling(dossier.incidentId)
    }

    fun dismissLiveDossier() {
        dossierPollingJob?.cancel()
        dossierPollingJob = null
        _activeLiveDossier.value = null
    }

    fun refreshActiveLiveDossier() {
        val currentDossier = _activeLiveDossier.value ?: return
        viewModelScope.launch {
            val freshLoc = locationWrapper.getImmediateLocation(2000L) ?: locationWrapper.currentLocation.value
            val profile = repository.database.userDao().getUserProfileOnce()
            val medicalSummary = buildString {
                if (profile != null && (profile.bloodGroup.isNotBlank() || profile.allergies.isNotBlank() || profile.medicalNotes.isNotBlank())) {
                    append("Blood: ${profile.bloodGroup.ifBlank { "N/A" }}, Allergies: ${profile.allergies.ifBlank { "None" }}, Notes: ${profile.medicalNotes.ifBlank { "None" }}")
                } else if (currentDossier.medicalSummary.isNotBlank()) {
                    append(currentDossier.medicalSummary)
                } else {
                    append("No medical notes recorded")
                }
            }

            val updatedLat = freshLoc?.latitude ?: currentDossier.latitude
            val updatedLng = freshLoc?.longitude ?: currentDossier.longitude
            val updatedAccuracy = freshLoc?.accuracy ?: currentDossier.accuracyMeters
            val outgoing = activeOutgoingHexAlert.value?.takeIf { it.incidentId == currentDossier.incidentId }
            val rLat = outgoing?.acceptedResponderLat ?: currentDossier.responderLat
            val rLng = outgoing?.acceptedResponderLng ?: currentDossier.responderLng
            val rDist = if (rLat != null && rLng != null && (rLat != 0.0 || rLng != 0.0) && (updatedLat != 0.0 || updatedLng != 0.0)) {
                HexagonalRescueManager.calculateDistanceMeters(updatedLat, updatedLng, rLat, rLng)
            } else {
                outgoing?.distanceMeters?.takeIf { it > 0f } ?: currentDossier.responderDistanceMeters
            }
            val rEta = rDist?.let { (it / 80f).roundToInt().coerceAtLeast(1) } ?: currentDossier.responderEtaMinutes

            val refreshed = currentDossier.copy(
                latitude = updatedLat,
                longitude = updatedLng,
                accuracyMeters = updatedAccuracy,
                medicalSummary = medicalSummary,
                timestamp = System.currentTimeMillis(),
                responderName = outgoing?.acceptedResponderName ?: currentDossier.responderName,
                responderPhone = outgoing?.acceptedResponderPhone ?: currentDossier.responderPhone,
                responderStatus = outgoing?.lifecycleState?.name ?: currentDossier.responderStatus,
                responderLat = rLat,
                responderLng = rLng,
                responderDistanceMeters = rDist,
                responderEtaMinutes = rEta
            )
            _activeLiveDossier.value = refreshed
            if (currentDossier.dossierToken.isNotBlank()) {
                repository.firestoreRiskRepository.updateDossierResponderTelemetry(
                    tokenId = currentDossier.dossierToken,
                    victimLat = updatedLat,
                    victimLng = updatedLng,
                    responderName = refreshed.responderName,
                    responderPhone = refreshed.responderPhone,
                    responderStatus = refreshed.responderStatus,
                    responderLat = refreshed.responderLat,
                    responderLng = refreshed.responderLng,
                    responderDistanceMeters = refreshed.responderDistanceMeters,
                    responderEtaMinutes = refreshed.responderEtaMinutes
                )
            }
            _statusEvents.tryEmit("Dossier telemetry updated with fresh GPS, responder & medical records.")
        }
    }

    private fun startDossierPolling(incidentId: String) {
        dossierPollingJob?.cancel()
        dossierPollingJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000L) // poll every 10 seconds
                val currentDossier = _activeLiveDossier.value ?: break
                if (currentDossier.incidentId != incidentId) break

                val freshLoc = locationWrapper.getImmediateLocation(2000L) ?: locationWrapper.currentLocation.value
                val profile = repository.database.userDao().getUserProfileOnce()
                val medicalSummary = buildString {
                    if (profile != null && (profile.bloodGroup.isNotBlank() || profile.allergies.isNotBlank() || profile.medicalNotes.isNotBlank())) {
                        append("Blood: ${profile.bloodGroup.ifBlank { "N/A" }}, Allergies: ${profile.allergies.ifBlank { "None" }}, Notes: ${profile.medicalNotes.ifBlank { "None" }}")
                    } else if (currentDossier.medicalSummary.isNotBlank()) {
                        append(currentDossier.medicalSummary)
                    } else {
                        append("No medical notes recorded")
                    }
                }

                val updatedLat = freshLoc?.latitude ?: currentDossier.latitude
                val updatedLng = freshLoc?.longitude ?: currentDossier.longitude
                val updatedAccuracy = freshLoc?.accuracy ?: currentDossier.accuracyMeters
                val outgoing = activeOutgoingHexAlert.value?.takeIf { it.incidentId == incidentId }
                val rLat = outgoing?.acceptedResponderLat ?: currentDossier.responderLat
                val rLng = outgoing?.acceptedResponderLng ?: currentDossier.responderLng
                val rDist = if (rLat != null && rLng != null && (rLat != 0.0 || rLng != 0.0) && (updatedLat != 0.0 || updatedLng != 0.0)) {
                    HexagonalRescueManager.calculateDistanceMeters(updatedLat, updatedLng, rLat, rLng)
                } else {
                    outgoing?.distanceMeters?.takeIf { it > 0f } ?: currentDossier.responderDistanceMeters
                }
                val rEta = rDist?.let { (it / 80f).roundToInt().coerceAtLeast(1) } ?: currentDossier.responderEtaMinutes

                val updatedDossier = currentDossier.copy(
                    latitude = updatedLat,
                    longitude = updatedLng,
                    accuracyMeters = updatedAccuracy,
                    medicalSummary = medicalSummary,
                    timestamp = System.currentTimeMillis(),
                    responderName = outgoing?.acceptedResponderName ?: currentDossier.responderName,
                    responderPhone = outgoing?.acceptedResponderPhone ?: currentDossier.responderPhone,
                    responderStatus = outgoing?.lifecycleState?.name ?: currentDossier.responderStatus,
                    responderLat = rLat,
                    responderLng = rLng,
                    responderDistanceMeters = rDist,
                    responderEtaMinutes = rEta
                )
                _activeLiveDossier.value = updatedDossier
                if (currentDossier.dossierToken.isNotBlank()) {
                    repository.firestoreRiskRepository.updateDossierResponderTelemetry(
                        tokenId = currentDossier.dossierToken,
                        victimLat = updatedLat,
                        victimLng = updatedLng,
                        responderName = updatedDossier.responderName,
                        responderPhone = updatedDossier.responderPhone,
                        responderStatus = updatedDossier.responderStatus,
                        responderLat = updatedDossier.responderLat,
                        responderLng = updatedDossier.responderLng,
                        responderDistanceMeters = updatedDossier.responderDistanceMeters,
                        responderEtaMinutes = updatedDossier.responderEtaMinutes
                    )
                }
            }
        }
    }

    fun open112Dialer() {
        val opened = repository.emergencyResponseDispatcher.openEmergency112Dialer()
        val dossier = latestPoliceDossier.value
        if (opened && dossier != null) {
            viewModelScope.launch {
                repository.escalationOrchestrator.markChannelDispatched(
                    incidentId = dossier.incidentId,
                    channel = "112_DIALER",
                    deliveryState = ReceiptDeliveryState.INTENT_LAUNCHED,
                    newPoliceStatus = PoliceEscalationStatus.INTENT_LAUNCHED,
                    details = "User opened pre-filled 112 Emergency Dialer"
                )
            }
        }
    }

    fun launch112SmsDossier(dossier: PoliceDispatchDossier) {
        if (repository.emergencyResponseDispatcher.launch112PreFilledSms(dossier)) {
            viewModelScope.launch {
                repository.escalationOrchestrator.markChannelDispatched(
                    incidentId = dossier.incidentId,
                    channel = "112_SMS",
                    deliveryState = ReceiptDeliveryState.INTENT_LAUNCHED,
                    newPoliceStatus = PoliceEscalationStatus.INTENT_LAUNCHED,
                    details = "User opened pre-filled 112 SMS composer"
                )
            }
            _statusEvents.tryEmit("Opened pre-filled 112 SMS dossier")
        } else {
            _statusEvents.tryEmit("Unable to open 112 SMS app (R3 authorization required)")
        }
    }

    fun openPoliceDossierPortal(dossier: PoliceDispatchDossier) {
        _activeLiveDossier.value = dossier
    }

    fun isBluetoothMeshEnabled(): Boolean = repository.bleEmergencyRelay.isBluetoothEnabled()

    fun hasBluetoothMeshPermissions(): Boolean = repository.bleEmergencyRelay.hasAllMeshPermissions()

    fun hasBluetoothConnectPermission(): Boolean = repository.bleEmergencyRelay.hasBluetoothConnectPermission()

    fun refreshBluetoothMeshState(autoStartIfEligible: Boolean = goodCitizenProfile.value.isEligibleForHexRescue) {
        repository.bleEmergencyRelay.refreshBluetoothState(autoStartScanIfEligible = autoStartIfEligible)
    }

    fun onBluetoothEnablePromptResult(enabled: Boolean) {
        val eligible = goodCitizenProfile.value.isEligibleForHexRescue
        repository.bleEmergencyRelay.refreshBluetoothState(autoStartScanIfEligible = enabled && eligible)
        val activeIncident = latestActiveIncident.value
        if (enabled && activeIncident != null) {
            val loc = locationWrapper.currentLocation.value
            repository.bleEmergencyRelay.broadcastEmergencyBeacon(
                incidentId = activeIncident.incidentId,
                lat = loc?.latitude ?: activeIncident.latitude,
                lng = loc?.longitude ?: activeIncident.longitude,
                evidenceSummary = activeIncident.description,
                sectorHint = "N-NE"
            )
            _statusEvents.tryEmit("Bluetooth ON — Offline Nearby Mesh SOS beacon broadcasting!")
        } else if (enabled) {
            _statusEvents.tryEmit("Bluetooth ON — Nearby Mesh relay is ready.")
        } else {
            _statusEvents.tryEmit("Bluetooth remains OFF — Nearby offline mesh alerts paused.")
        }
    }

    // Non-Admin Citizen Verification & Responder Duty
    fun toggleVolunteerDuty(enabled: Boolean) {
        val allowed = repository.goodCitizenManager.setVolunteerActive(enabled)
        if (!allowed && enabled) {
            _statusEvents.tryEmit("Complete Citizen KYC & Trusted Guardian endorsement before activating responder duty.")
            return
        }
        if (enabled) {
            val scanning = repository.bleEmergencyRelay.startScanningForNearbySos()
            if (scanning) {
                _statusEvents.tryEmit("Responder duty ACTIVE — Firestore + Bluetooth LE rescue scanner running")
            } else {
                _statusEvents.tryEmit("Responder duty saved — enable Bluetooth & permissions to scan Nearby Mesh")
            }
        } else {
            repository.bleEmergencyRelay.stopScanning()
            _statusEvents.tryEmit("Responder duty paused")
        }
    }

    fun generateSelfSmsCode(): String {
        return repository.goodCitizenManager.generateSelfSmsVerificationPin()
    }

    fun verifySelfSmsCode(code: String): Boolean {
        val count = trustedContacts.value.size
        val ok = repository.goodCitizenManager.verifySelfSmsPin(code, count)
        if (ok) {
            _statusEvents.tryEmit("Phone verified! +15 Trust Points awarded.")
            if (count >= 2) {
                _statusEvents.tryEmit("2+ Circle contacts active — Community Responder duty unlocked!")
            }
        } else {
            _statusEvents.tryEmit("Invalid 6-digit verification code. Please check SMS and retry.")
        }
        return ok
    }

    fun verifyPeerByDeepLink(peerUid: String, token: String) {
        val ok = repository.goodCitizenManager.verifyPeerByToken(peerUid, token)
        if (ok) {
            _statusEvents.tryEmit("Peer phone verified via WhatsApp link! +15 Trust Points awarded.")
        }
    }

    fun checkAutoVerificationFromCircle(count: Int) {
        if (repository.goodCitizenManager.checkAutoVerificationFromCircle(count)) {
            _statusEvents.tryEmit("2+ Circle contacts detected — Community Responder duty unlocked!")
        }
    }

    fun redeemReferralCode(code: String) {
        val res = repository.goodCitizenManager.redeemReferralCode(code)
        res.onSuccess { msg ->
            _statusEvents.tryEmit(msg)
        }.onFailure { err ->
            _statusEvents.tryEmit(err.message ?: "Invalid referral code")
        }
    }

    fun setWorkEmailBadge(email: String) {
        repository.goodCitizenManager.setWorkEmailBadge(email)
        _statusEvents.tryEmit("Work email badge updated.")
    }

    fun setLinkedinBadge(url: String) {
        repository.goodCitizenManager.setLinkedinBadge(url)
        _statusEvents.tryEmit("LinkedIn badge updated.")
    }

    fun submitCitizenKyc(docType: String, lastFourHint: String) {
        val updated = repository.goodCitizenManager.submitKycMetadata(docType, lastFourHint)
        _statusEvents.tryEmit(
            "KYC submitted (${updated.verificationStatus.name}). Share endorsement code ${updated.endorsementCode} with a Trusted Guardian."
        )
    }

    fun endorsePeerByCode(endorsementCode: String) {
        redeemReferralCode(endorsementCode)
    }

    // --- New Upgrade Feature Controls ---
    fun setDispatchTabVisible(visible: Boolean) {
        _isDispatchTabVisible.value = visible
        if (visible) {
            publishLiveRadarTick(isForegroundDispatch = true, forceImmediate = true)
        }
    }

    fun setLiveLocationSharing(enabled: Boolean) {
        _isLiveLocationSharingEnabled.value = enabled
        repository.setLiveRadarWatchEnabled(enabled)
        val alertText = publishLiveRadarTick(isForegroundDispatch = _isDispatchTabVisible.value, forceImmediate = true)
        if (!enabled && alertText != null) {
            _statusEvents.tryEmit(alertText)
            val corePhones = trustedContacts.value
                .filter { it.isEnabled && it.isCoreFamily() && it.phone.isNotBlank() }
                .map { it.phone.trim() }
            corePhones.forEach { phone ->
                repository.sendDirectOrIntentSms(phone, alertText, allowUiFallback = false)
            }
        } else if (enabled) {
            _statusEvents.tryEmit("Live family radar sharing active")
        }
    }

    fun publishLiveRadarTick(isForegroundDispatch: Boolean, forceImmediate: Boolean): String? {
        val loc = locationWrapper.currentLocation.value ?: return null
        val snap = repository.sensorsManager.captureDeviceSensorSnapshot(loc)
        val ownerName = userProfile.value?.name?.ifBlank { currentUser.value?.displayName ?: "You" } ?: "You"
        val contacts = trustedContacts.value
        val hasTrip = activeTrip.value != null
        val hasSos = latestActiveIncident.value != null
        val isVerified = goodCitizenProfile.value.verificationStatus == CitizenVerificationStatus.VERIFIED
        val isDuty = goodCitizenProfile.value.isVolunteerActive
        return repository.liveRadarManager.publishLocation(
            lat = loc.latitude,
            lng = loc.longitude,
            bearing = loc.bearing,
            speed = loc.speed,
            battery = snap.batteryPercent,
            sharingActive = _isLiveLocationSharingEnabled.value,
            ownerName = ownerName,
            areaName = emergencyInfo.value.locality,
            hasActiveTrip = hasTrip,
            isSosActive = hasSos,
            contacts = contacts,
            isForegroundDispatch = isForegroundDispatch,
            forceImmediate = forceImmediate,
            isVerifiedResponder = isVerified,
            isResponderDutyActive = isDuty
        )
    }

    fun toggleContact24x7Location(contact: TrustedContactEntity, allow24x7: Boolean) {
        updateContact(contact.copy(allow24x7Location = allow24x7))
        _statusEvents.tryEmit(
            if (allow24x7) "${contact.name} granted 24/7 live location permission"
            else "${contact.name} set to Trip / Battery<10% / SOS sharing only"
        )
    }

    fun verifyPhoneHintResult(rawPhone: String) {
        val all = trustedContacts.value
        val coreCount = all.count { it.isCoreFamily() }
        val ok = repository.goodCitizenManager.verifyPhoneByPlayHint(
            phoneNumber = rawPhone,
            circleContactCount = all.size,
            coreFamilyCount = coreCount
        )
        if (ok) {
            val currentLocal = userProfile.value ?: UserProfileEntity()
            if (currentLocal.phone.isBlank()) {
                updateProfile(currentLocal.copy(phone = rawPhone.trim()))
            }
            _statusEvents.tryEmit("Active SIM verified ($rawPhone) via Google Play Hint! +15 Trust Points.")
        } else {
            _statusEvents.tryEmit("Invalid phone number returned from SIM selector")
        }
    }

    fun verifyResponderSelfie(
        bitmap: android.graphics.Bitmap,
        onResult: (Boolean, String?, String) -> Unit
    ) {
        viewModelScope.launch {
            val res = mlServiceManager.verifySelfieFaceLiveness(bitmap)
            onResult(res.verified, res.selfieBase64, res.reason)
        }
    }

    fun scanVehiclePlateFromBitmap(
        bitmap: android.graphics.Bitmap,
        onResult: (PlateScanResult) -> Unit
    ) {
        viewModelScope.launch {
            val res = mlServiceManager.detectLicensePlate(bitmap)
            if (!res.detectedPlate.isNullOrBlank()) {
                _lastDetectedPlate.value = res.detectedPlate
            }
            onResult(res)
        }
    }

    fun scanVehiclePlateFromUri(
        uri: android.net.Uri,
        onResult: (PlateScanResult) -> Unit
    ) {
        viewModelScope.launch {
            val res = mlServiceManager.detectLicensePlateFromUri(uri)
            if (!res.detectedPlate.isNullOrBlank()) {
                _lastDetectedPlate.value = res.detectedPlate
            }
            onResult(res)
        }
    }

    fun shareCabRideWithCircle(plateNumber: String, rideNote: String = "") {
        viewModelScope.launch {
            val cleanPlate = plateNumber.trim().uppercase(Locale.US)
            if (cleanPlate.isBlank()) return@launch
            _lastDetectedPlate.value = cleanPlate
            val loc = locationWrapper.getImmediateLocation(2500L) ?: locationWrapper.currentLocation.value
            val userName = userProfile.value?.name?.takeIf { it.isNotBlank() } ?: "Citizen"
            val mapUrl = if (loc != null) "https://maps.google.com/?q=${loc.latitude},${loc.longitude}" else "GPS Acquiring"
            val msg = "🚕 RAKSHAONE RIDE BOARDED ($userName)\nVehicle Plate: $cleanPlate\n${if (rideNote.isNotBlank()) "Note: $rideNote\n" else ""}Live GPS: $mapUrl"

            val contacts = repository.database.trustedContactDao().getActiveContacts()
            val phones = contacts.map { it.phone.trim() }.filter { it.isNotBlank() }
            if (phones.isNotEmpty()) {
                repository.openSmsMessenger(phones.joinToString(";"), msg)
            } else {
                repository.shareTextContent("Cab Ride Details: $cleanPlate", msg)
            }
            _statusEvents.tryEmit("Shared cab plate $cleanPlate with Circle")
        }
    }

    fun submitResolvePinOrDecoy(incidentId: String, enteredPin: String, notes: String): Boolean {
        return if (repository.duressPinManager.isDecoyCancelPin(enteredPin)) {
            viewModelScope.launch {
                repository.decoyCancelSos(incidentId)
                repository.sensorsManager.stopSiren()
                repository.sensorsManager.stopStrobe()
                _sosState.value = SosCountdownState.IDLE
                _statusEvents.tryEmit("Standby Ready")
            }
            true
        } else {
            resolveActiveIncident(incidentId, notes)
            false
        }
    }

    fun setAutonomousAiGuardEnabled(enabled: Boolean) {
        repository.userRoutineLearner.setAutonomousGuardEnabled(enabled)
        viewModelScope.launch {
            if (currentUser.value != null && authManager.isFirebaseConfigured()) {
                repository.circleAndAiSyncRepository.syncPushAiSafetyState(
                    repository.userRoutineLearner.exportAiState()
                )
            }
        }
        _statusEvents.tryEmit(
            if (enabled) "Sovereign TFLite AI Guard ON — proactive Fake Call & vault protection enabled"
            else "Autonomous AI intervention paused (Advisory mode only)"
        )
    }

    fun submitAiLearningFeedback(wasGenuineThreat: Boolean) {
        viewModelScope.launch {
            val updated = repository.userRoutineLearner.recordUserAiFeedback(wasGenuineThreat)
            if (currentUser.value != null && authManager.isFirebaseConfigured()) {
                repository.circleAndAiSyncRepository.syncPushAiSafetyState(updated)
            }
            _statusEvents.tryEmit(
                if (wasGenuineThreat) "TFLite Brain calibrated: Threat sensitivity boosted (Tier ${updated.sovereigntyTier})"
                else "TFLite Brain calibrated: Safe pattern learned (Tier ${updated.sovereigntyTier})"
            )
        }
    }

    fun playGuardianAudio(incidentId: String) {
        if (_playingAudioIncidentId.value == incidentId) {
            stopGuardianAudio()
            return
        }
        viewModelScope.launch {
            stopGuardianAudio()
            _statusEvents.tryEmit("Downloading guardian voice audio (.m4a)...")
            val audioFile = repository.downloadGuardianAudio(incidentId)
            if (audioFile == null || !audioFile.exists()) {
                _statusEvents.tryEmit("No synced .m4a voice recording found yet for $incidentId")
                return@launch
            }
            try {
                val player = MediaPlayer().apply {
                    setDataSource(audioFile.absolutePath)
                    setOnCompletionListener {
                        _playingAudioIncidentId.value = null
                        release()
                        guardianAudioPlayer = null
                    }
                    prepare()
                    start()
                }
                guardianAudioPlayer = player
                _playingAudioIncidentId.value = incidentId
                _statusEvents.tryEmit("Playing synced victim voice recording...")
            } catch (e: Exception) {
                _statusEvents.tryEmit("Unable to play audio: ${e.message}")
            }
        }
    }

    fun stopGuardianAudio() {
        try {
            guardianAudioPlayer?.stop()
        } catch (_: Exception) {}
        try {
            guardianAudioPlayer?.release()
        } catch (_: Exception) {}
        guardianAudioPlayer = null
        _playingAudioIncidentId.value = null
    }

    fun openGuardianVideo(incidentId: String) {
        viewModelScope.launch {
            _statusEvents.tryEmit("Downloading synced emergency video (.mp4)...")
            val videoFile = repository.downloadGuardianVideo(incidentId)
            if (videoFile == null || !videoFile.exists()) {
                _statusEvents.tryEmit("No synced .mp4 video found yet for $incidentId")
                return@launch
            }
            try {
                val ctx = getApplication<Application>()
                val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", videoFile)
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(viewIntent)
            } catch (_: Exception) {
                _statusEvents.tryEmit("No video player app found (Saved at ${videoFile.name})")
            }
        }
    }

    fun toggleProtectorWalk(enabled: Boolean) {
        _isProtectorWalkActive.value = enabled
        if (enabled) {
            val userName = userProfile.value?.name?.ifBlank { emergencyInfo.value.locality } ?: emergencyInfo.value.locality
            voiceGuardianManager.speakProtectorCheckIn(userName)
            startVoiceListening()
            _statusEvents.tryEmit("Protector Walk ACTIVE — Spoken Voice Co-Pilot & Distress Phrase Ready")
        } else {
            voiceGuardianManager.stopSpeaking()
            voiceGuardianManager.stopListening()
            _statusEvents.tryEmit("Protector Walk ended")
        }
    }

    fun togglePocketGestureArmed(enabled: Boolean) {
        _isPocketGestureArmed.value = enabled
        _statusEvents.tryEmit(
            if (enabled) "Pocket SOS Armed — 4x Vol-Up OR Headset Unplug triggers SOS (Lockscreen 3x Vol-Down stays active)"
            else "Pocket Headset/Vol-Up gesture disarmed (Lockscreen 3x Vol-Down stays active)"
        )
    }

    fun syncLockscreenVolumeSosService() {
        if (_isVolumeDownSilentSosEnabled.value) {
            LockscreenVolumeSosService.start(getApplication())
        } else {
            LockscreenVolumeSosService.stop(getApplication())
        }
    }

    fun setVolumeDownSilentSosEnabled(enabled: Boolean) {
        _isVolumeDownSilentSosEnabled.value = enabled
        lockscreenPrefs.edit()
            .putBoolean(LockscreenVolumeSosService.PREF_LOCKSCREEN_VOL_SOS_ENABLED, enabled)
            .apply()
        volumeDownDetector.reset()
        syncLockscreenVolumeSosService()
        _statusEvents.tryEmit(
            if (enabled) "Lockscreen 3x Volume Down -> Silent SOS ARMED (only when locked or screen off)"
            else "Lockscreen 3x Volume Down shortcut paused"
        )
    }

    fun registerVolumeDownPress(
        nowMillis: Long = System.currentTimeMillis(),
        isRepeat: Boolean = false,
        overrideLockedOrScreenOff: Boolean? = null
    ): Boolean {
        if (!_isVolumeDownSilentSosEnabled.value) {
            volumeDownDetector.reset()
            return false
        }
        val lockedOrOff = overrideLockedOrScreenOff
            ?: VolumeDownTripleClickDetector.isDeviceLockedOrScreenOff(getApplication())
        val triggered = volumeDownDetector.onVolumeDownEvent(
            nowMillis = nowMillis,
            isPhoneLockedOrScreenOff = lockedOrOff,
            isRepeat = isRepeat
        )
        if (triggered) {
            triggerSilentVolumeDownSos(
                reason = "3x Volume Down Stealth SOS (Locked/Screen Off — Silent Mode)",
                deviceLocked = lockedOrOff
            )
            return true
        }
        return false
    }

    fun registerVolumeButtonPress(nowMillis: Long = System.currentTimeMillis()) {
        if (!_isPocketGestureArmed.value) return
        volumePressTimestamps.removeAll { nowMillis - it > 2_500L }
        volumePressTimestamps.add(nowMillis)
        if (volumePressTimestamps.size >= 4) {
            volumePressTimestamps.clear()
            triggerPocketGestureSos("4x Volume Micro-Gesture SOS (Silent)", silentStealth = true)
        }
    }

    fun triggerSilentVolumeDownSos(
        reason: String = "3x Volume Down Stealth SOS (Locked/Screen Off — Silent Mode)",
        deviceLocked: Boolean = true
    ) {
        // Immediately silence siren, strobe flashlight, and spoken TTS while keeping stealth activities active
        _isSilentMode.value = true
        repository.sensorsManager.stopSiren()
        repository.sensorsManager.stopStrobe()
        voiceGuardianManager.stopSpeaking()
        _isLiveLocationSharingEnabled.value = true
        if (locationWrapper.hasFineLocationPermission()) {
            startLocationTracking()
        }

        when (_sosState.value) {
            SosCountdownState.IDLE, SosCountdownState.COUNTDOWN -> {
                viewModelScope.launch {
                    repository.setSilentSosMode(true)
                }
                triggerImmediateSos(
                    customNote = reason,
                    forceSilent = true,
                    triggerOrigin = TriggerOrigin.USER_EXPLICIT_INSTANT_SOS,
                    deviceLocked = deviceLocked
                )
                _statusEvents.tryEmit("3x Volume Down (Locked): Silent Mode SOS + Stealth Cam/Audio/GPS + Guardian Alerts triggered")
            }
            SosCountdownState.ACTIVE -> {
                // Additional stealth pulse while SOS is already active: snap stealth evidence, update radar, and re-sync Guardians
                viewModelScope.launch {
                    val activeId = latestActiveIncident.value?.incidentId
                    publishLiveRadarTick(isForegroundDispatch = _isDispatchTabVisible.value, forceImmediate = true)
                    if (repository.cameraVideoRecorder.hasCameraPermission()) {
                        captureEvidenceSnapshotNow()
                    }
                    if (!activeId.isNullOrBlank()) {
                        repository.syncEvidenceFilesToCloud(activeId)
                    }
                    repository.hapticManager.vibrateSosButtonTouch()
                    _statusEvents.tryEmit("3x Volume Down: Silent stealth pulse — GPS, snapshot & Guardians refreshed")
                }
            }
        }
    }

    fun triggerPocketGestureSos(
        reason: String = "Pocket Hardware Micro-Gesture SOS",
        silentStealth: Boolean = true
    ) {
        if (_sosState.value != SosCountdownState.ACTIVE) {
            triggerImmediateSos(
                customNote = reason,
                forceSilent = silentStealth || _isSilentMode.value,
                triggerOrigin = TriggerOrigin.USER_EXPLICIT_INSTANT_SOS
            )
        }
    }

    fun refreshPredictiveThreatNow() {
        viewModelScope.launch {
            val loc = locationWrapper.getImmediateLocation(2500L) ?: locationWrapper.currentLocation.value
            if (loc != null) {
                repository.userRoutineLearner.evaluatePredictiveThreat(loc.latitude, loc.longitude)
            }
        }
    }

    fun exportAndShareZeroFirTimeline(incidentId: String? = null) {
        viewModelScope.launch {
            val inc = incidentId?.let { id -> allIncidents.value.firstOrNull { it.incidentId == id } }
                ?: latestActiveIncident.value
                ?: allIncidents.value.firstOrNull()
            val targetId = inc?.incidentId ?: "GENERAL"
            val ok = zeroFirBuilder.exportAndShareZeroFirReport(targetId)
            if (ok) {
                _statusEvents.tryEmit("Zero-FIR Legal Timeline exported for sharing")
            } else {
                val report = zeroFirBuilder.buildIncidentTimelineReport(targetId)
                repository.shareTextContent("Zero-FIR Safety Timeline", report)
            }
        }
    }

    fun getAvailableVoiceLanguages(): List<LanguageOption> = voiceGuardianManager.getAllAvailableLanguages()
    fun setVoicePersona(persona: GuardianVoicePersona) = voiceGuardianManager.setVoicePersona(persona, speakConfirmation = true)
    fun setVoiceLanguage(language: LanguageOption) = voiceGuardianManager.setLanguage(language, speakConfirmation = true)
    fun setAutoReadOut(enabled: Boolean) = voiceGuardianManager.setAutoReadOut(enabled)
    fun speakVoiceSample() = voiceGuardianManager.speakPersonaConfirmation()
    fun buildInstallTtsDataIntent(): android.content.Intent = voiceGuardianManager.buildInstallTtsDataIntent()
    fun stopVoiceSpeaking() = voiceGuardianManager.stopSpeaking()
    fun startVoiceListening(onTranscript: (String) -> Unit = {}) {
        voiceGuardianManager.startListening(
            onResult = { spoken ->
                _lastVoiceTranscript.value = spoken
                onTranscript(spoken)
            },
            onError = { err ->
                _statusEvents.tryEmit(err)
            },
            onDistressPhraseDetected = {
                triggerImmediateSos(
                    customNote = "Voice Distress Trigger",
                    triggerOrigin = TriggerOrigin.USER_EXPLICIT_INSTANT_SOS
                )
            }
        )
    }
    fun stopVoiceListening() = voiceGuardianManager.stopListening()
    fun speakTextNow(text: String) = voiceGuardianManager.speak(text, force = true)
    fun clearDisconnectAlert() = repository.liveRadarManager.clearDisconnectAlert()

    override fun onCleared() {
        super.onCleared()
        stopGuardianAudio()
        locationWrapper.cleanup()
        repository.sensorsManager.cleanup()
        repository.hapticManager.cancelVibration()
        repository.stopLiveTripTracking()
        repository.bleEmergencyRelay.cleanup()
        repository.liveRadarManager.cleanup()
        repository.userRoutineLearner.close()
        mlServiceManager.close()
        voiceGuardianManager.release()
    }
}

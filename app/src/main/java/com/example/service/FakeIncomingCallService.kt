package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.FakeIncomingCallActivity
import com.example.R
import com.example.data.model.AuditLogEntity
import com.example.data.repository.SafetyRepository
import com.example.util.FakeCallConfig
import com.example.util.FakeCallState
import com.example.util.VoiceGuardianManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Dedicated Android Foreground Service managing the Fake Incoming Call experience:
 * - Can be started manually (Quick Trigger / Preset / Delay) OR proactively by AI (TFLite Sovereign AI / Chat).
 * - Posts an Android CallStyle incoming/ongoing notification + Full-Screen System Phone UI launch (`FakeIncomingCallActivity`).
 * - Automatically captures victim & surrounding audio (.m4a) as soon as the call connects (or immediately when armed),
 *   and uploads the finalized audio + GPS + `FAKE_CALL_GUARD` alert to Firestore (`evidence_vault` & `emergency_dispatches`)
 *   without any manual interaction.
 */
class FakeIncomingCallService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: SafetyRepository
    private var voiceGuardian: VoiceGuardianManager? = null
    private var stateObserverJob: Job? = null
    private var lastObservedState: FakeCallState = FakeCallState.IDLE
    @Volatile
    private var activeCallIncidentId: String = ""

    companion object {
        private const val TAG = "FakeIncomingCallService"
        private const val CHANNEL_ID_INCOMING = "rakshaone_fake_incoming_call_v2"
        private const val CHANNEL_ID_ONGOING = "rakshaone_fake_ongoing_call_v2"
        private const val NOTIFICATION_ID = 4208

        const val ACTION_START_CALL = "com.example.action.FAKE_CALL_START"
        const val ACTION_ANSWER_CALL = "com.example.action.FAKE_CALL_ANSWER"
        const val ACTION_DECLINE_OR_END_CALL = "com.example.action.FAKE_CALL_END"
        const val ACTION_TOGGLE_HOLD = "com.example.action.FAKE_CALL_HOLD"
        const val ACTION_START_RECORDING = "com.example.action.FAKE_CALL_RECORD"
        const val ACTION_CIRCLE_ALERT = "com.example.action.FAKE_CALL_CIRCLE_ALERT"

        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_CALLER_NUMBER = "extra_caller_number"
        const val EXTRA_CALLER_ROLE = "extra_caller_role"
        const val EXTRA_DELAY_SECONDS = "extra_delay_seconds"
        const val EXTRA_IS_AI_PROACTIVE = "extra_is_ai_proactive"
        const val EXTRA_AUTO_RECORD_SHARE = "extra_auto_record_share"
        const val EXTRA_TRIGGER_SOURCE = "extra_trigger_source"

        private val _isAudioUploading = MutableStateFlow(false)
        val isAudioUploading: StateFlow<Boolean> = _isAudioUploading.asStateFlow()

        private val _lastUploadedIncidentId = MutableStateFlow<String?>(null)
        val lastUploadedIncidentId: StateFlow<String?> = _lastUploadedIncidentId.asStateFlow()

        fun startCall(
            context: Context,
            callerName: String,
            callerNumber: String,
            callerRole: String = "Mobile • HD Voice",
            delaySeconds: Int = 0,
            isAiProactive: Boolean = false,
            autoRecordAndShare: Boolean = true,
            triggerSource: String = if (isAiProactive) "AI_PROACTIVE" else "USER_MANUAL"
        ) {
            val intent = Intent(context.applicationContext, FakeIncomingCallService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALLER_NUMBER, callerNumber)
                putExtra(EXTRA_CALLER_ROLE, callerRole)
                putExtra(EXTRA_DELAY_SECONDS, delaySeconds.coerceAtLeast(0))
                putExtra(EXTRA_IS_AI_PROACTIVE, isAiProactive)
                putExtra(EXTRA_AUTO_RECORD_SHARE, autoRecordAndShare)
                putExtra(EXTRA_TRIGGER_SOURCE, triggerSource)
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun answerCall(context: Context) {
            val intent = Intent(context.applicationContext, FakeIncomingCallService::class.java).apply {
                action = ACTION_ANSWER_CALL
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Exception) {
                SafetyRepository.getInstance(context).fakeCallManager.answerCall()
            }
        }

        fun endOrDeclineCall(context: Context) {
            val intent = Intent(context.applicationContext, FakeIncomingCallService::class.java).apply {
                action = ACTION_DECLINE_OR_END_CALL
            }
            try {
                context.applicationContext.startService(intent)
            } catch (_: Exception) {
                SafetyRepository.getInstance(context).fakeCallManager.declineOrEndCall()
            }
        }

        fun toggleHold(context: Context) {
            val intent = Intent(context.applicationContext, FakeIncomingCallService::class.java).apply {
                action = ACTION_TOGGLE_HOLD
            }
            try {
                context.applicationContext.startService(intent)
            } catch (_: Exception) {
                SafetyRepository.getInstance(context).fakeCallManager.toggleHold()
            }
        }

        fun startAudioCaptureNow(context: Context) {
            val intent = Intent(context.applicationContext, FakeIncomingCallService::class.java).apply {
                action = ACTION_START_RECORDING
            }
            try {
                context.applicationContext.startService(intent)
            } catch (_: Exception) {
                val repo = SafetyRepository.getInstance(context)
                val incId = repo.fakeCallManager.config.value.incidentId
                    .ifBlank { "FAKECALL-${System.currentTimeMillis() % 100000}" }
                repo.audioRecorder.startAutomaticSosRecording(incId, durationSeconds = 45)
            }
        }

        fun dispatchCircleAlertNow(context: Context) {
            val intent = Intent(context.applicationContext, FakeIncomingCallService::class.java).apply {
                action = ACTION_CIRCLE_ALERT
            }
            try {
                context.applicationContext.startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SafetyRepository.getInstance(applicationContext)
        voiceGuardian = VoiceGuardianManager(applicationContext)
        createNotificationChannels()
        observeCallLifecycle()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                val name = intent.getStringExtra(EXTRA_CALLER_NAME).orEmpty().ifBlank { "Emergency Guardian" }
                val number = intent.getStringExtra(EXTRA_CALLER_NUMBER).orEmpty()
                val role = intent.getStringExtra(EXTRA_CALLER_ROLE).orEmpty().ifBlank { "Mobile • HD Voice" }
                val delaySec = intent.getIntExtra(EXTRA_DELAY_SECONDS, 0).coerceAtLeast(0)
                val isAiProactive = intent.getBooleanExtra(EXTRA_IS_AI_PROACTIVE, false)
                val autoRecord = intent.getBooleanExtra(EXTRA_AUTO_RECORD_SHARE, true)
                val source = intent.getStringExtra(EXTRA_TRIGGER_SOURCE)
                    ?: if (isAiProactive) "AI_PROACTIVE" else "USER_MANUAL"

                repository.fakeCallManager.setCaller(
                    name = name,
                    number = number,
                    role = role,
                    isAiProactive = isAiProactive,
                    autoRecordAndShare = autoRecord
                )
                activeCallIncidentId = repository.fakeCallManager.config.value.incidentId

                val initialStatus = if (delaySec > 0) {
                    "Scheduled call from $name in ${delaySec}s"
                } else {
                    "Incoming call • $name"
                }
                promoteToForeground(
                    notification = buildCallNotification(
                        config = repository.fakeCallManager.config.value,
                        callState = if (delaySec > 0) FakeCallState.SCHEDULED else FakeCallState.RINGING,
                        statusOverride = initialStatus
                    )
                )

                repository.fakeCallManager.scheduleCall(delaySec)

                serviceScope.launch {
                    repository.database.auditLogDao().insertLog(
                        AuditLogEntity(
                            actor = if (isAiProactive) "Suraksha AI" else "Citizen",
                            role = if (isAiProactive) "AI" else "CITIZEN",
                            action = "FAKE_CALL_SERVICE_STARTED",
                            targetId = activeCallIncidentId,
                            details = "Dedicated FakeIncomingCallService triggered ($source) • Caller=$name, Delay=${delaySec}s, AutoRecord=$autoRecord"
                        )
                    )
                }

                if (delaySec == 0) {
                    launchSystemCallUiScreen()
                }
            }

            ACTION_ANSWER_CALL -> {
                promoteToForeground(
                    notification = buildCallNotification(
                        config = repository.fakeCallManager.config.value,
                        callState = FakeCallState.CONNECTED
                    )
                )
                repository.fakeCallManager.answerCall()
                launchSystemCallUiScreen()
            }

            ACTION_DECLINE_OR_END_CALL -> {
                handleCallEndedAndUpload()
            }

            ACTION_TOGGLE_HOLD -> {
                repository.fakeCallManager.toggleHold()
                updateNotification(
                    buildCallNotification(
                        config = repository.fakeCallManager.config.value,
                        callState = repository.fakeCallManager.callState.value
                    )
                )
            }

            ACTION_START_RECORDING -> {
                val cfg = repository.fakeCallManager.config.value
                val incId = cfg.incidentId.ifBlank {
                    activeCallIncidentId.ifBlank { "FAKECALL-${System.currentTimeMillis() % 100000}" }
                }
                repository.audioRecorder.startAutomaticSosRecording(incId, durationSeconds = 45)
                updateNotification(
                    buildCallNotification(
                        config = cfg,
                        callState = repository.fakeCallManager.callState.value,
                        statusOverride = "Recording call voice • Auto-uploading to Circle"
                    )
                )
            }

            ACTION_CIRCLE_ALERT -> {
                val cfg = repository.fakeCallManager.config.value
                serviceScope.launch {
                    val loc = repository.getCurrentLocationOrNull()
                    val audioFile = repository.latestShareableAudioFile.value
                    repository.dispatchFakeCallAutoProtection(
                        config = cfg.copy(autoRecordAndShare = true),
                        location = loc,
                        audioFile = audioFile
                    )
                }
            }

            else -> {
                promoteToForeground(
                    notification = buildCallNotification(
                        config = repository.fakeCallManager.config.value,
                        callState = repository.fakeCallManager.callState.value
                    )
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun observeCallLifecycle() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            repository.fakeCallManager.callState.collect { state ->
                if (state == lastObservedState) return@collect
                val prev = lastObservedState
                lastObservedState = state
                val cfg = repository.fakeCallManager.config.value

                when (state) {
                    FakeCallState.RINGING -> {
                        activeCallIncidentId = cfg.incidentId.ifBlank {
                            "FAKECALL-${System.currentTimeMillis() % 100000}"
                        }
                        updateNotification(buildCallNotification(cfg, FakeCallState.RINGING))
                        launchSystemCallUiScreen()
                    }

                    FakeCallState.CONNECTED -> {
                        val incId = cfg.incidentId.ifBlank {
                            activeCallIncidentId.ifBlank { "FAKECALL-${System.currentTimeMillis() % 100000}" }
                        }
                        activeCallIncidentId = incId
                        updateNotification(buildCallNotification(cfg, FakeCallState.CONNECTED))
                        voiceGuardian?.speakFakeCallScript(cfg.callerName)
                        if (!repository.audioRecorder.isRecording.value) {
                            repository.audioRecorder.startAutomaticSosRecording(incId, durationSeconds = 45)
                        }
                        if (cfg.autoRecordAndShare) {
                            val loc = repository.getCurrentLocationOrNull()
                            repository.dispatchFakeCallAutoProtection(
                                config = cfg.copy(incidentId = incId),
                                location = loc,
                                audioFile = null
                            )
                        }
                    }

                    FakeCallState.ENDED -> {
                        if (prev == FakeCallState.CONNECTED || prev == FakeCallState.RINGING) {
                            finalizeAudioAndUploadToFirestore(cfg)
                        }
                    }

                    FakeCallState.IDLE -> {
                        if (prev == FakeCallState.CONNECTED || prev == FakeCallState.RINGING || prev == FakeCallState.ENDED) {
                            if (repository.audioRecorder.isRecording.value) {
                                finalizeAudioAndUploadToFirestore(cfg)
                            } else {
                                stopServiceCleanly()
                            }
                        }
                    }

                    FakeCallState.SCHEDULED -> Unit
                }
            }
        }
    }

    private fun handleCallEndedAndUpload() {
        val cfg = repository.fakeCallManager.config.value
        voiceGuardian?.stopSpeaking()
        if (repository.fakeCallManager.callState.value != FakeCallState.IDLE &&
            repository.fakeCallManager.callState.value != FakeCallState.ENDED
        ) {
            repository.fakeCallManager.declineOrEndCall()
        } else {
            serviceScope.launch {
                finalizeAudioAndUploadToFirestore(cfg)
            }
        }
    }

    private suspend fun finalizeAudioAndUploadToFirestore(cfg: FakeCallConfig) {
        voiceGuardian?.stopSpeaking()
        val incId = cfg.incidentId.ifBlank {
            activeCallIncidentId.ifBlank { "FAKECALL-${System.currentTimeMillis() % 100000}" }
        }
        try {
            if (repository.audioRecorder.isRecording.value) {
                _isAudioUploading.value = true
                updateNotification(
                    buildCallNotification(
                        config = cfg,
                        callState = FakeCallState.ENDED,
                        statusOverride = "Encrypting & uploading call audio to Firestore..."
                    )
                )
                val meta = repository.audioRecorder.stopAndEncryptRecording()
                val shareableFile = repository.latestShareableAudioFile.value
                if (shareableFile != null && shareableFile.exists()) {
                    repository.syncEvidenceFilesToCloud(
                        incidentId = incId,
                        extraMediaFiles = listOf("AUDIO" to shareableFile)
                    )
                }
                if (cfg.autoRecordAndShare) {
                    val loc = repository.getCurrentLocationOrNull()
                    repository.dispatchFakeCallAutoProtection(
                        config = cfg.copy(incidentId = incId),
                        location = loc,
                        audioFile = shareableFile
                    )
                }
                if (meta != null) {
                    _lastUploadedIncidentId.value = incId
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fake call audio finalize/upload error: ${e.message}")
        } finally {
            _isAudioUploading.value = false
            stopServiceCleanly()
        }
    }

    private fun stopServiceCleanly() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun launchSystemCallUiScreen() {
        try {
            val fullScreenIntent = Intent(applicationContext, FakeIncomingCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
            applicationContext.startActivity(fullScreenIntent)
        } catch (e: Exception) {
            Log.i(TAG, "Background activity launch relied on CallStyle full-screen notification: ${e.message}")
        }
    }

    private fun promoteToForeground(notification: Notification) {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val serviceType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (hasMicPermission) {
                    mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                mask
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateNotification(notification: Notification) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun buildCallNotification(
        config: FakeCallConfig,
        callState: FakeCallState,
        statusOverride: String? = null
    ): Notification {
        val fullScreenIntent = Intent(this, FakeIncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answerPendingIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, FakeIncomingCallService::class.java).apply { action = ACTION_ANSWER_CALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declineOrEndPendingIntent = PendingIntent.getService(
            this,
            103,
            Intent(this, FakeIncomingCallService::class.java).apply { action = ACTION_DECLINE_OR_END_CALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder()
            .setName(config.callerName.ifBlank { "Emergency Guardian" })
            .setImportant(true)
            .build()

        val channelId = if (callState == FakeCallState.RINGING) CHANNEL_ID_INCOMING else CHANNEL_ID_ONGOING
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(fullScreenPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (statusOverride != null) {
            builder
                .setContentTitle(config.callerName.ifBlank { "Phone Call" })
                .setContentText(statusOverride)
            if (callState == FakeCallState.RINGING) {
                builder.setFullScreenIntent(fullScreenPendingIntent, true)
                builder.setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(
                        callerPerson,
                        declineOrEndPendingIntent,
                        answerPendingIntent
                    )
                )
            }
            return builder.build()
        }

        when (callState) {
            FakeCallState.RINGING -> {
                builder
                    .setFullScreenIntent(fullScreenPendingIntent, true)
                    .setStyle(
                        NotificationCompat.CallStyle.forIncomingCall(
                            callerPerson,
                            declineOrEndPendingIntent,
                            answerPendingIntent
                        )
                    )
            }
            FakeCallState.CONNECTED -> {
                builder
                    .setUsesChronometer(true)
                    .setStyle(
                        NotificationCompat.CallStyle.forOngoingCall(
                            callerPerson,
                            declineOrEndPendingIntent
                        )
                    )
            }
            else -> {
                builder
                    .setContentTitle(config.callerName.ifBlank { "Phone Call" })
                    .setContentText(
                        if (config.isAiProactive) "AI Guard call active • Audio vault ready"
                        else "Scheduled incoming call armed"
                    )
            }
        }
        return builder.build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val incoming = NotificationChannel(
                CHANNEL_ID_INCOMING,
                "Incoming Phone Call",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen Android system incoming call alert"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val ongoing = NotificationChannel(
                CHANNEL_ID_ONGOING,
                "Ongoing Call & Audio Vault",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active call timer and encrypted call audio upload"
            }
            nm.createNotificationChannel(incoming)
            nm.createNotificationChannel(ongoing)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceGuardian?.release()
        stateObserverJob?.cancel()
        serviceJob.cancel()
        serviceScope.cancel()
    }
}

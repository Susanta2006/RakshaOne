package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.AuditLogEntity
import com.example.data.repository.SafetyRepository
import com.example.risk.TriggerOrigin
import com.example.util.VolumeDownTripleClickDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Play-Protect-compliant Foreground Service (`foregroundServiceType="mediaPlayback"`)
 * that enables 3x rapid Volume-Down Stealth SOS detection when the screen is off or locked.
 *
 * Uses zero-CPU silent [AudioTrack] (`MODE_STATIC` silent buffer) + [MediaSession] + [VolumeProvider]
 * **ONLY while the screen is off or the device is locked** so Android routes lockscreen/standby
 * hardware volume presses directly to RakshaOne without disturbing unlocked device media/battery.
 * Immediately pauses silent playback on `ACTION_USER_PRESENT` (unlock) and ignores unlocked presses.
 */
class LockscreenVolumeSosService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: SafetyRepository
    private val detector = VolumeDownTripleClickDetector()

    private var mediaSession: MediaSession? = null
    private var silentAudioTrack: AudioTrack? = null
    private var isReceiverRegistered = false
    private var lastObservedMusicVolume: Int = -1
    private var lastVolumeProviderEventElapsedMs: Long = 0L
    private var isLockscreenSessionActive: Boolean = false

    private val lockAndVolumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    syncSessionWithLockState()
                }
                ACTION_VOLUME_CHANGED -> {
                    val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                    if (streamType != AudioManager.STREAM_MUSIC && streamType != AudioManager.STREAM_RING) {
                        return
                    }
                    val newVol = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                    val prevVol = intent.getIntExtra(EXTRA_VOLUME_STREAM_PREV_VALUE, -1)
                    if (newVol >= 0 && prevVol >= 0 && newVol < prevVol) {
                        val nowElapsed = SystemClock.elapsedRealtime()
                        // Deduplicate against VolumeProvider.onAdjustVolume which fires for the same press
                        if (nowElapsed - lastVolumeProviderEventElapsedMs > 45L) {
                            handleVolumeDownHardwareStep(System.currentTimeMillis())
                        }
                    }
                    if (streamType == AudioManager.STREAM_MUSIC && newVol >= 0) {
                        lastObservedMusicVolume = newVol
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SafetyRepository.getInstance(applicationContext)
        createNotificationChannels()
        registerBroadcastListeners()
        initMediaSession()
        _isGuardRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_GUARD) {
            deactivateLockscreenSession()
            _isGuardRunning.value = false
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }

        promoteToForeground("Armed for Lockscreen / Screen-Off 3x Volume-Down Silent SOS")
        syncSessionWithLockState()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _isGuardRunning.value = false
        deactivateLockscreenSession()
        try {
            mediaSession?.release()
        } catch (_: Exception) {
        }
        mediaSession = null
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(lockAndVolumeReceiver)
            } catch (_: Exception) {
            }
            isReceiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerBroadcastListeners() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_VOLUME_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(
                this,
                lockAndVolumeReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering lock/volume receiver: ${e.message}")
        }
    }

    private fun initMediaSession() {
        try {
            val session = MediaSession(this, "RakshaOneLockscreenSosGuard")
            val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction < 0) {
                        lastVolumeProviderEventElapsedMs = SystemClock.elapsedRealtime()
                        handleVolumeDownHardwareStep(System.currentTimeMillis())
                    }
                }
            }
            session.setPlaybackToRemote(volumeProvider)
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PAUSED, 0L, 1f)
                    .build()
            )
            session.isActive = false
            mediaSession = session
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession init fallback: ${e.message}")
        }
    }

    private fun syncSessionWithLockState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_LOCKSCREEN_VOL_SOS_ENABLED, true)
        val lockedOrOff = VolumeDownTripleClickDetector.isDeviceLockedOrScreenOff(this)
        if (enabled && lockedOrOff) {
            activateLockscreenSession()
        } else {
            detector.reset()
            deactivateLockscreenSession()
        }
    }

    private fun activateLockscreenSession() {
        if (isLockscreenSessionActive) return
        isLockscreenSessionActive = true
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            lastObservedMusicVolume = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        } catch (_: Exception) {
        }
        try {
            mediaSession?.let { session ->
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                        .build()
                )
                session.isActive = true
            }
        } catch (_: Exception) {
        }
        startSilentZeroLoopTrack()
    }

    private fun deactivateLockscreenSession() {
        isLockscreenSessionActive = false
        stopSilentAudioTrack()
        try {
            mediaSession?.let { session ->
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PAUSED, 0L, 1f)
                        .build()
                )
                session.isActive = false
            }
        } catch (_: Exception) {
        }
    }

    private fun startSilentZeroLoopTrack() {
        if (silentAudioTrack != null) return
        try {
            val sampleRate = 8000
            val channelMask = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding).coerceAtLeast(1024)
            val byteLen = if (minBuf % 2 == 0) minBuf else minBuf + 1
            val silentBytes = ByteArray(byteLen)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(byteLen)
                .build()
            track.write(silentBytes, 0, silentBytes.size)
            track.setLoopPoints(0, byteLen / 2, -1)
            track.setVolume(0f)
            track.play()
            silentAudioTrack = track
        } catch (e: Throwable) {
            Log.d(TAG, "Silent static track skipped on device: ${e.message}")
            silentAudioTrack = null
        }
    }

    private fun stopSilentAudioTrack() {
        val track = silentAudioTrack ?: return
        silentAudioTrack = null
        try {
            track.pause()
            track.flush()
            track.stop()
            track.release()
        } catch (_: Throwable) {
        }
    }

    private fun handleVolumeDownHardwareStep(nowMillis: Long) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_LOCKSCREEN_VOL_SOS_ENABLED, true)) {
            detector.reset()
            return
        }
        val lockedOrOff = VolumeDownTripleClickDetector.isDeviceLockedOrScreenOff(this)
        val triggered = detector.onVolumeDownEvent(
            nowMillis = nowMillis,
            isPhoneLockedOrScreenOff = lockedOrOff,
            isRepeat = false
        )
        if (triggered) {
            triggerStealthLockedSosNow(nowMillis)
        }
    }

    private fun triggerStealthLockedSosNow(triggeredAtMs: Long) {
        _lastTriggeredAtMillis.value = triggeredAtMs
        // Ensure silent mode is enforced immediately before SOS capture starts
        repository.sensorsManager.stopSiren()
        repository.sensorsManager.stopStrobe()

        serviceScope.launch {
            try {
                repository.setSilentSosMode(true)
                val activeIncident = repository.database.incidentDao().getLatestActiveIncidentOnce()
                if (activeIncident == null) {
                    val result = repository.triggerSosWithResult(
                        userComment = "3x Volume Down Stealth SOS (Locked/Screen Off — Silent Mode)",
                        isSnatchTrigger = false,
                        triggerOrigin = TriggerOrigin.USER_EXPLICIT_INSTANT_SOS,
                        silentStealth = true,
                        deviceLocked = true
                    )
                    repository.database.auditLogDao().insertLog(
                        AuditLogEntity(
                            timestamp = triggeredAtMs,
                            actor = "Lockscreen Hardware Guard",
                            role = "SYSTEM_STEALTH",
                            action = "TRIPLE_VOL_DOWN_LOCKED_SOS",
                            targetId = result.incident.incidentId,
                            details = "Triggered Silent Mode SOS while screen off/locked -> GPS, Audio, Dual-Cam, Guardian High Alert & BLE active."
                        )
                    )
                    updateNotification(
                        "Silent Stealth SOS ACTIVE (${result.incident.incidentId}) • Capturing & notifying Guardians"
                    )
                } else {
                    // Already active: capture instant photo + refresh cloud sync & guardian live radar silently
                    repository.syncEvidenceFilesToCloud(activeIncident.incidentId)
                    repository.publishEmergencyDispatchToGuardians(
                        incidentId = activeIncident.incidentId,
                        alertType = activeIncident.type,
                        status = "ACTIVE",
                        lat = activeIncident.latitude,
                        lng = activeIncident.longitude,
                        accuracyMeters = activeIncident.accuracyMeters,
                        locDesc = activeIncident.locationDescription,
                        deviceLocked = true
                    )
                    updateNotification("Silent SOS pulse sent • Live GPS & Guardian alerts refreshed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Locked 3x Vol-Down SOS error: ${e.message}")
            }
        }
    }

    private fun promoteToForeground(statusText: String) {
        val notification = buildNotification(statusText)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateNotification(statusText: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            4510,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("RakshaOne Lockscreen Stealth Guard")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lockscreen Hardware SOS Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Allows quick 3x Volume-Down Silent SOS when your phone is locked or screen is off"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "LockscreenVolSosSvc"
        private const val CHANNEL_ID = "rakshaone_lockscreen_vol_guard"
        private const val NOTIFICATION_ID = 4512
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_VOLUME_STREAM_PREV_VALUE = "android.media.EXTRA_VOLUME_STREAM_PREV_VALUE"

        const val PREFS_NAME = "rakshaone_prefs"
        const val PREF_LOCKSCREEN_VOL_SOS_ENABLED = "volume_down_lockscreen_sos_enabled"

        const val ACTION_START_GUARD = "com.example.action.START_LOCKSCREEN_VOL_SOS_GUARD"
        const val ACTION_STOP_GUARD = "com.example.action.STOP_LOCKSCREEN_VOL_SOS_GUARD"

        private val _isGuardRunning = MutableStateFlow(false)
        val isGuardRunning: StateFlow<Boolean> = _isGuardRunning.asStateFlow()

        private val _lastTriggeredAtMillis = MutableStateFlow<Long?>(null)
        val lastTriggeredAtMillis: StateFlow<Long?> = _lastTriggeredAtMillis.asStateFlow()

        fun start(context: Context) {
            try {
                val intent = Intent(context.applicationContext, LockscreenVolumeSosService::class.java).apply {
                    action = ACTION_START_GUARD
                }
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Deferred lockscreen guard start until foreground: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context.applicationContext, LockscreenVolumeSosService::class.java).apply {
                    action = ACTION_STOP_GUARD
                }
                context.applicationContext.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}

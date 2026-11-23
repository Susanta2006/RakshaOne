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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.AuditLogEntity
import com.example.data.model.IncidentEventEntity
import com.example.data.repository.SafetyRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedicated Android 14+ Foreground Capture Service (`camera|microphone|dataSync`) invoked automatically
 * by [R3CriticalMonitorWorker] when an `R3_CRITICAL` state is detected in background or foreground.
 * Automatically captures encrypted audio (`.m4a`) and dual-camera video/photos (`.mp4`/`.jpg`)
 * and uploads them directly to Firestore (`evidence_vault` + `emergency_dispatches`) with zero manual trigger.
 */
class R3CriticalCaptureService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: SafetyRepository

    companion object {
        private const val TAG = "R3CaptureService"
        private const val CHANNEL_ID = "rakshaone_r3_critical_capture"
        private const val NOTIFICATION_ID = 4305

        const val ACTION_CAPTURE_AND_UPLOAD = "com.example.action.R3_CAPTURE_AND_UPLOAD"
        const val EXTRA_INCIDENT_ID = "extra_incident_id"
        const val EXTRA_REASON = "extra_reason"

        private val activeIncidentIds = ConcurrentHashMap.newKeySet<String>()

        fun startCaptureAndUpload(
            context: Context,
            incidentId: String,
            reason: String = "R3_CRITICAL_AUTO_MONITOR"
        ) {
            val intent = Intent(context.applicationContext, R3CriticalCaptureService::class.java).apply {
                action = ACTION_CAPTURE_AND_UPLOAD
                putExtra(EXTRA_INCIDENT_ID, incidentId)
                putExtra(EXTRA_REASON, reason)
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SafetyRepository.getInstance(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incidentId = intent?.getStringExtra(EXTRA_INCIDENT_ID).orEmpty()
            .ifBlank { "R3-${System.currentTimeMillis() % 100000}" }
        val reason = intent?.getStringExtra(EXTRA_REASON).orEmpty()
            .ifBlank { "R3_CRITICAL_AUTO_MONITOR" }

        promoteToForeground("R3 Critical active • Auto-capturing audio & video for Firestore...")

        if (!activeIncidentIds.add(incidentId)) {
            Log.i(TAG, "R3 critical capture already in progress for $incidentId")
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                executeCaptureAndUploadPipeline(
                    repository = repository,
                    context = applicationContext,
                    incidentId = incidentId,
                    reason = reason,
                    onStageUpdate = { updateNotification(it) }
                )
            } finally {
                activeIncidentIds.remove(incidentId)
                try {
                    ServiceCompat.stopForeground(this@R3CriticalCaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {
                }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(statusText: String) {
        val notification = buildNotification(statusText)
        val hasCamera = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val serviceType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                var mask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else 0
                if (hasCamera) mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (hasMic) mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                mask
            }
            else -> 0
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
            205,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("RakshaOne R3 Critical Shield")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "R3 Critical Auto-Capture",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Automatic audio/video capture and Firestore evidence vault sync on R3_CRITICAL"
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        serviceScope.cancel()
    }

    /**
     * Shared capture + Firestore upload pipeline usable both from [R3CriticalCaptureService]
     * and directly inside [R3CriticalMonitorWorker] if service promotion is constrained.
     */
    object Pipeline {
        suspend fun executeCaptureAndUpload(
            repository: SafetyRepository,
            context: Context,
            incidentId: String,
            reason: String,
            onStageUpdate: (String) -> Unit = {}
        ): Boolean = executeCaptureAndUploadPipeline(
            repository = repository,
            context = context,
            incidentId = incidentId,
            reason = reason,
            onStageUpdate = onStageUpdate
        )
    }
}

private suspend fun executeCaptureAndUploadPipeline(
    repository: SafetyRepository,
    context: Context,
    incidentId: String,
    reason: String,
    onStageUpdate: (String) -> Unit
): Boolean {
    val audioStarted = try {
        if (!repository.audioRecorder.isRecording.value) {
            repository.audioRecorder.startAutomaticSosRecording(incidentId, durationSeconds = 11)
        } else {
            true
        }
    } catch (_: Exception) {
        false
    }

    val videoCompletion = CompletableDeferred<DualCameraCaptureResult?>()
    val cameraAlreadyBusy = repository.cameraVideoRecorder.isRecording.value
    if (!cameraAlreadyBusy && repository.cameraVideoRecorder.hasCameraPermission()) {
        try {
            repository.cameraVideoRecorder.startAutomatedSosCapture(
                incidentId = incidentId,
                frontDurationSec = 5,
                rearDurationSec = 7,
                onPhotoCaptured = { file ->
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.persistCameraEvidenceFile(
                            incidentId = incidentId,
                            file = file,
                            fileType = "PHOTO_EVIDENCE",
                            title = if (file.name.startsWith("FRONT_", true)) {
                                "R3 Auto Front Snapshot (${file.name})"
                            } else {
                                "R3 Auto Rear Snapshot (${file.name})"
                            }
                        )
                        val kind = if (file.name.startsWith("FRONT_", true)) "PHOTO_FRONT" else "PHOTO_REAR"
                        repository.syncEvidenceFilesToCloud(incidentId, listOf(kind to file))
                    }
                },
                onVideoRecorded = { _ -> },
                onDualSequenceCompleted = { result ->
                    videoCompletion.complete(result)
                }
            )
        } catch (_: Exception) {
            videoCompletion.complete(null)
        }
    } else {
        videoCompletion.complete(null)
    }

    // Wait for audio recording completion cleanly (up to ~14s)
    if (audioStarted) {
        var elapsed = 0L
        while (repository.audioRecorder.isRecording.value && elapsed < 14_000L) {
            delay(500L)
            elapsed += 500L
        }
        if (repository.audioRecorder.isRecording.value) {
            runCatching { repository.audioRecorder.stopAndEncryptRecording() }
        }
    }

    val dualResult = withTimeoutOrNull(22_000L) { videoCompletion.await() }
    if (dualResult != null) {
        for (videoPath in dualResult.videoPaths) {
            val f = File(videoPath)
            if (f.exists() && f.length() > 0L) {
                repository.persistCameraEvidenceFile(
                    incidentId = incidentId,
                    file = f,
                    fileType = "VIDEO_EVIDENCE",
                    title = "R3 Auto Video (${f.name})"
                )
            }
        }
    }

    onStageUpdate("Uploading captured audio & video evidence to Firestore...")
    val extraMedia = mutableListOf<Pair<String, File>>()
    repository.latestShareableAudioFile.value?.takeIf { it.exists() && it.length() > 0L }?.let {
        extraMedia.add("AUDIO" to it)
    }
    dualResult?.videoPaths?.forEach { path ->
        val vf = File(path)
        if (vf.exists() && vf.length() > 0L) extraMedia.add("VIDEO" to vf)
    }

    val synced = repository.syncEvidenceFilesToCloud(
        incidentId = incidentId,
        extraMediaFiles = extraMedia
    )

    val loc = repository.getCurrentLocationOrNull()
    val incident = repository.database.incidentDao().getIncidentById(incidentId)
    val lat = loc?.latitude ?: incident?.latitude ?: 0.0
    val lng = loc?.longitude ?: incident?.longitude ?: 0.0
    val acc = loc?.accuracy ?: incident?.accuracyMeters ?: 0f
    val locDesc = incident?.locationDescription?.takeIf { it.isNotBlank() }
        ?: if (lat != 0.0 || lng != 0.0) "Lat: $lat, Lng: $lng" else "GPS acquiring"

    val dispatchOk = if (repository.networkMonitor.isInternetCurrentlyAvailable()) {
        repository.publishEmergencyDispatchToGuardians(
            incidentId = incidentId,
            alertType = incident?.type ?: "SOS",
            status = "CAPTURED",
            lat = lat,
            lng = lng,
            accuracyMeters = acc,
            locDesc = locDesc,
            dualResult = dualResult,
            deviceLocked = false
        )
    } else false

    if (synced || dispatchOk) {
        R3CriticalMonitorWorker.markIncidentCapturedAndUploaded(context, incidentId)
    } else {
        R3CriticalMonitorWorker.enqueueConnectedUploadRetry(context, incidentId)
    }

    repository.database.incidentEventDao().insertEvent(
        IncidentEventEntity(
            incidentId = incidentId,
            timestamp = System.currentTimeMillis(),
            oldStatus = "ACTIVE",
            newStatus = if (synced) "AUTO_VAULT_UPLOADED" else "AUTO_VAULT_QUEUED",
            actor = "R3CriticalCaptureService",
            note = "WorkManager/Service R3_CRITICAL auto-capture ($reason): Audio=${audioStarted}, Video=${dualResult?.videoPaths?.size ?: 0}, FirestoreSynced=$synced."
        )
    )
    repository.database.auditLogDao().insertLog(
        AuditLogEntity(
            actor = "R3CriticalCaptureService",
            role = "SYSTEM",
            action = if (synced) "R3_AUTO_MEDIA_UPLOADED" else "R3_AUTO_MEDIA_QUEUED",
            targetId = incidentId,
            details = "Reason=$reason, AudioStarted=$audioStarted, Videos=${dualResult?.videoPaths?.size ?: 0}, CloudSynced=$synced"
        )
    )
    return synced
}

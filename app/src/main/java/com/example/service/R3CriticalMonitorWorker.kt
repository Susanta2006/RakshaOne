package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.AppDatabase
import com.example.data.model.AuditLogEntity
import com.example.data.repository.SafetyRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Background Android WorkManager service that continuously monitors for an `R3_CRITICAL` state
 * (via local Room `incidents` / `risk_assessments`, reactive triggers, and Firestore state when online)
 * and automatically initiates audio/video capture and Firestore Evidence Vault upload with ZERO manual triggers.
 */
class R3CriticalMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "R3CriticalMonitorWorker"
        private const val PERIODIC_WORK_NAME = "rakshaone_r3_critical_periodic_monitor"
        private const val PREFS_NAME = "raksha_r3_monitor_prefs"
        private const val KEY_UPLOADED_IDS = "uploaded_r3_incident_ids"
        private const val FOREGROUND_CHANNEL_ID = "rakshaone_r3_workmanager_monitor"
        private const val FOREGROUND_NOTIFICATION_ID = 4306

        const val KEY_INCIDENT_ID = "incident_id"
        const val KEY_RISK_LEVEL = "risk_level"
        const val KEY_REASON = "reason"
        const val KEY_UPLOAD_RETRY_ONLY = "upload_retry_only"

        fun isR3CriticalState(riskLevel: String?, activeIncidentPriority: String?, activeIncidentStatus: String?): Boolean {
            if (riskLevel.equals("R3_CRITICAL", ignoreCase = true)) return true
            val isOpenStatus = activeIncidentStatus != null &&
                activeIncidentStatus !in setOf("RESOLVED", "CLOSED", "CANCELLED")
            return isOpenStatus && activeIncidentPriority.equals("CRITICAL", ignoreCase = true)
        }

        fun hasIncidentBeenCapturedAndUploaded(context: Context, incidentId: String): Boolean {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val set = prefs.getStringSet(KEY_UPLOADED_IDS, emptySet()) ?: emptySet()
            return set.contains(incidentId)
        }

        fun markIncidentCapturedAndUploaded(context: Context, incidentId: String) {
            if (incidentId.isBlank()) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val updated = (prefs.getStringSet(KEY_UPLOADED_IDS, emptySet()) ?: emptySet()).toMutableSet()
            updated.add(incidentId)
            // Keep bounded to last 50 IDs
            val bounded = if (updated.size > 50) updated.toList().takeLast(50).toSet() else updated
            prefs.edit().putStringSet(KEY_UPLOADED_IDS, bounded).apply()
        }

        /**
         * Registers a 15-minute background WorkManager sweep so any uncaptured/unsynced `R3_CRITICAL`
         * state is automatically caught even when the UI is closed.
         */
        fun schedulePeriodicMonitor(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<R3CriticalMonitorWorker>(15L, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
                    .addTag("raksha_r3_critical_monitor")
                    .build()

                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(
                        PERIODIC_WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request
                    )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to schedule periodic R3 monitor: ${e.message}")
            }
        }

        /**
         * Immediately dispatches an expedited WorkManager monitor check whenever the device enters
         * `R3_CRITICAL`, initiates capture automatically, and uploads to Firestore.
         */
        fun triggerImmediateMonitor(
            context: Context,
            incidentId: String = "",
            riskLevel: String = "R3_CRITICAL",
            reason: String = "R3_CRITICAL_STATE_DETECTED"
        ) {
            if (!riskLevel.equals("R3_CRITICAL", ignoreCase = true)) return
            try {
                val input = Data.Builder()
                    .putString(KEY_INCIDENT_ID, incidentId)
                    .putString(KEY_RISK_LEVEL, riskLevel)
                    .putString(KEY_REASON, reason)
                    .putBoolean(KEY_UPLOAD_RETRY_ONLY, false)
                    .build()

                val request = OneTimeWorkRequestBuilder<R3CriticalMonitorWorker>()
                    .setInputData(input)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                    .addTag("raksha_r3_critical_immediate")
                    .build()

                val uniqueName = "r3_critical_immediate_${incidentId.ifBlank { "latest" }}"
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enqueue immediate R3 monitor: ${e.message}")
            }
        }

        /**
         * Queues automatic Firestore upload retry once internet connectivity returns.
         */
        fun enqueueConnectedUploadRetry(context: Context, incidentId: String) {
            if (incidentId.isBlank()) return
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val input = Data.Builder()
                    .putString(KEY_INCIDENT_ID, incidentId)
                    .putString(KEY_RISK_LEVEL, "R3_CRITICAL")
                    .putString(KEY_REASON, "CONNECTIVITY_RESTORED_UPLOAD")
                    .putBoolean(KEY_UPLOAD_RETRY_ONLY, true)
                    .build()
                val request = OneTimeWorkRequestBuilder<R3CriticalMonitorWorker>()
                    .setConstraints(constraints)
                    .setInputData(input)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
                    .addTag("raksha_r3_upload_retry")
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork("r3_upload_retry_$incidentId", ExistingWorkPolicy.REPLACE, request)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enqueue connected Firestore retry: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(applicationContext)
        val repository = SafetyRepository.getInstance(applicationContext)
        val uploadRetryOnly = inputData.getBoolean(KEY_UPLOAD_RETRY_ONLY, false)
        val inputIncidentId = inputData.getString(KEY_INCIDENT_ID).orEmpty().trim()
        val inputRiskLevel = inputData.getString(KEY_RISK_LEVEL).orEmpty().trim()
        val reason = inputData.getString(KEY_REASON).orEmpty().ifBlank { "R3_CRITICAL_MONITOR_SWEEP" }

        if (uploadRetryOnly && inputIncidentId.isNotBlank()) {
            val synced = repository.syncEvidenceFilesToCloud(inputIncidentId)
            return if (synced) {
                markIncidentCapturedAndUploaded(applicationContext, inputIncidentId)
                Result.success()
            } else if (runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

        // 1. Detect R3_CRITICAL from input data, local Room incidents, local Room risk assessments, or Firestore
        val activeIncidents = db.incidentDao().getActiveIncidentsOnce()
        val latestCriticalIncident = activeIncidents.firstOrNull {
            it.priority.equals("CRITICAL", ignoreCase = true)
        }
        val latestCriticalAssessment = db.riskAssessmentDao().getLatestCriticalAssessmentOnce()
        val recentAssessmentCutoff = System.currentTimeMillis() - 45 * 60 * 1000L
        val hasRecentCriticalAssessment = latestCriticalAssessment != null &&
            latestCriticalAssessment.evaluatedAt >= recentAssessmentCutoff

        val remoteCriticalIncidentId = findRemoteR3CriticalIncidentIfOnline(repository)

        val resolvedIncidentId = when {
            inputIncidentId.isNotBlank() && inputRiskLevel.equals("R3_CRITICAL", ignoreCase = true) -> inputIncidentId
            latestCriticalIncident != null -> latestCriticalIncident.incidentId
            hasRecentCriticalAssessment -> latestCriticalAssessment!!.incidentId
            !remoteCriticalIncidentId.isNullOrBlank() -> remoteCriticalIncidentId
            inputRiskLevel.equals("R3_CRITICAL", ignoreCase = true) -> "R3-AUTO-${System.currentTimeMillis() % 100000}"
            else -> null
        }

        if (resolvedIncidentId == null) {
            return Result.success()
        }

        // 2. Skip duplicate capture if both audio and video (or upload completion) already recorded
        val existingEvidence = db.evidenceDao().getEvidenceForIncidentOnce(resolvedIncidentId)
        val hasAudio = existingEvidence.any { it.fileType.contains("AUDIO", ignoreCase = true) }
        val hasVideo = existingEvidence.any { it.fileType.contains("VIDEO", ignoreCase = true) }
        if (hasIncidentBeenCapturedAndUploaded(applicationContext, resolvedIncidentId) || (hasAudio && hasVideo)) {
            val synced = repository.syncEvidenceFilesToCloud(resolvedIncidentId)
            if (synced) {
                markIncidentCapturedAndUploaded(applicationContext, resolvedIncidentId)
                return Result.success()
            }
            enqueueConnectedUploadRetry(applicationContext, resolvedIncidentId)
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }

        // 3. Promote Worker cleanly to Foreground before launching Capture Service
        runCatching { setForeground(createForegroundInfo(resolvedIncidentId)) }

        db.auditLogDao().insertLog(
            AuditLogEntity(
                actor = "R3CriticalMonitorWorker",
                role = "SYSTEM",
                action = "WORKMANAGER_R3_CRITICAL_DETECTED",
                targetId = resolvedIncidentId,
                details = "WorkManager detected uncaptured R3_CRITICAL ($reason); initiating automatic audio/video capture + Firestore upload."
            )
        )

        // 4. Trigger dedicated Android 14+ Foreground Capture Service (camera|microphone|dataSync)
        var serviceStarted = false
        try {
            R3CriticalCaptureService.startCaptureAndUpload(
                context = applicationContext,
                incidentId = resolvedIncidentId,
                reason = reason
            )
            serviceStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "R3CriticalCaptureService start note (${e.message}); running inline pipeline")
        }

        // Also run inline pipeline safely if service could not be started (e.g., restricted test env)
        val synced = if (!serviceStarted) {
            R3CriticalCaptureService.Pipeline.executeCaptureAndUpload(
                repository = repository,
                context = applicationContext,
                incidentId = resolvedIncidentId,
                reason = reason
            )
        } else {
            // Ensure any existing un-synced files also sync cleanly from WorkManager
            repository.syncEvidenceFilesToCloud(resolvedIncidentId)
        }

        return if (synced || repository.networkMonitor.isInternetCurrentlyAvailable()) {
            Result.success()
        } else {
            enqueueConnectedUploadRetry(applicationContext, resolvedIncidentId)
            Result.success()
        }
    }

    private suspend fun findRemoteR3CriticalIncidentIfOnline(repository: SafetyRepository): String? {
        if (!repository.networkMonitor.isInternetCurrentlyAvailable()) return null
        val uid = Firebase.auth.currentUser?.uid ?: return null
        return try {
            val firestore = FirebaseFirestore.getInstance(
                applicationContext.getString(R.string.firestore_database_id)
            )
            val snap = firestore.collection("emergency_dispatches")
                .whereEqualTo("senderUid", uid)
                .whereEqualTo("status", "ACTIVE")
                .limit(5)
                .get()
                .await()
            snap.documents.firstOrNull { doc ->
                val alertType = doc.getString("alertType").orEmpty()
                alertType.equals("SOS", ignoreCase = true) || alertType.equals("ANTI_SNATCH", ignoreCase = true)
            }?.getString("incidentId")
        } catch (_: Exception) {
            null
        }
    }

    private fun createForegroundInfo(incidentId: String): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    FOREGROUND_CHANNEL_ID,
                    "R3 Critical Background Monitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background WorkManager safeguard monitoring R3_CRITICAL state"
                }
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("RakshaOne R3 Critical Monitor")
            .setContentText("Securing automatic audio & video evidence ($incidentId)")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }
}

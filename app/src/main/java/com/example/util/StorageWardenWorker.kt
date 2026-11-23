package com.example.util

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for autonomous RakshaOne storage hygiene:
 * - Enforces the strict 25 MB hard total storage budget.
 * - Compresses raw GPS breadcrumb logs older than 24h into compact daily geohash summaries.
 * - Prunes and rotates the 3-minute circular buffer for audio/video evidence.
 */
class StorageWardenWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_WORK_MODE) ?: MODE_FULL_OPTIMIZATION
        return executeWorkPass(applicationContext, mode, runAttemptCount)
    }

    companion object {
        private const val TAG = "StorageWardenWorker"

        suspend fun executeWorkPass(
            context: Context,
            mode: String = MODE_FULL_OPTIMIZATION,
            runAttemptCount: Int = 0
        ): Result {
            return try {
                val warden = StorageWardenManager(context)
                val includeDailyGeohash = mode != MODE_CIRCULAR_BUFFER_SWEEP
                val report = warden.runOptimizationWithReport(
                    now = System.currentTimeMillis(),
                    includeDailyGeohashCompaction = includeDailyGeohash
                )

                Result.success(
                    workDataOf(
                        OUT_WORK_MODE to mode,
                        OUT_TOTAL_USED_BYTES to report.status.totalUsedBytes,
                        OUT_BUDGET_BYTES to report.status.budgetBytes,
                        OUT_CIRCULAR_BUFFER_BYTES to report.status.rollingBufferBytes,
                        OUT_LOGS_BYTES to report.status.logsBytes,
                        OUT_COMPRESSED_BREADCRUMBS to report.compressedBreadcrumbs,
                        OUT_PRUNED_CIRCULAR_FILES to report.prunedCircularFiles,
                        OUT_TRIMMED_CAP_FILES to report.trimmedCapFiles,
                        OUT_LOW_SPACE_WARNING to report.status.lowSpaceWarning
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "StorageWardenWorker failed (attempt=$runAttemptCount): ${e.message}", e)
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf(OUT_ERROR to (e.message ?: "Storage optimization failed")))
                }
            }
        }

        const val DAILY_WORK_NAME = "RakshaStorageWardenDailyWork"
        const val CIRCULAR_BUFFER_WORK_NAME = "RakshaStorageCircularBufferWork"
        const val IMMEDIATE_WORK_NAME = "RakshaStorageImmediateWork"

        const val KEY_WORK_MODE = "work_mode"
        const val MODE_FULL_OPTIMIZATION = "FULL_OPTIMIZATION"
        const val MODE_DAILY_GEOHASH_COMPACTION = "DAILY_GEOHASH_COMPACTION"
        const val MODE_CIRCULAR_BUFFER_SWEEP = "CIRCULAR_BUFFER_SWEEP"

        const val OUT_WORK_MODE = "out_work_mode"
        const val OUT_TOTAL_USED_BYTES = "out_total_used_bytes"
        const val OUT_BUDGET_BYTES = "out_budget_bytes"
        const val OUT_CIRCULAR_BUFFER_BYTES = "out_circular_buffer_bytes"
        const val OUT_LOGS_BYTES = "out_logs_bytes"
        const val OUT_COMPRESSED_BREADCRUMBS = "out_compressed_breadcrumbs"
        const val OUT_PRUNED_CIRCULAR_FILES = "out_pruned_circular_files"
        const val OUT_TRIMMED_CAP_FILES = "out_trimmed_cap_files"
        const val OUT_LOW_SPACE_WARNING = "out_low_space_warning"
        const val OUT_ERROR = "out_error"

        /**
         * Schedules daily (24h) GPS log -> geohash compaction + 25 MB hard cap enforcement.
         */
        fun scheduleDailyWarden(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<StorageWardenWorker>(24, TimeUnit.HOURS)
                    .setInputData(workDataOf(KEY_WORK_MODE to MODE_DAILY_GEOHASH_COMPACTION))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(false)
                            .setRequiresStorageNotLow(false)
                            .build()
                    )
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    DAILY_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (_: Exception) {
            }
        }

        /**
         * Schedules rolling periodic maintenance (15m minimum WorkManager cadence) to keep the
         * 3-minute audio/video circular buffer swept and the 25 MB total cap enforced.
         */
        fun scheduleCircularBufferSweep(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<StorageWardenWorker>(
                    PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                    .setInputData(workDataOf(KEY_WORK_MODE to MODE_CIRCULAR_BUFFER_SWEEP))
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    CIRCULAR_BUFFER_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (_: Exception) {
            }
        }

        /**
         * Enqueues an immediate one-time pass (e.g., right after an SOS/Trip completes or user taps Optimize).
         */
        fun runImmediateCleanup(
            context: Context,
            mode: String = MODE_FULL_OPTIMIZATION
        ) {
            try {
                val request = OneTimeWorkRequestBuilder<StorageWardenWorker>()
                    .setInputData(workDataOf(KEY_WORK_MODE to mode))
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (_: Exception) {
            }
        }
    }
}

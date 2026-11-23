package com.example.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.model.AuditLogEntity
import com.example.data.model.IncidentEventEntity
import java.util.concurrent.TimeUnit

/**
 * Autonomous WorkManager background task that dispatches carrier SMS via [SmsManager]
 * whenever the device enters an active `R3_CRITICAL` state or suffers an internet drop
 * (`NETWORK_LOSS_BLACKBOX` / `SOS_OFFLINE_BACKUP`), bypassing manual user interaction and
 * retrying across temporary radio/connectivity drops without requiring a NetworkType.CONNECTED constraint.
 */
class CriticalSmsDispatchWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "CriticalSmsWorker"
        const val KEY_INCIDENT_ID = "incident_id"
        const val KEY_RISK_LEVEL = "risk_level"
        const val KEY_USER_NOTE = "user_note"
        const val KEY_LAT = "latitude"
        const val KEY_LNG = "longitude"
        const val KEY_ACC = "accuracy"
        const val KEY_BATTERY = "battery_percent"
        const val KEY_FALLBACK_REASON = "fallback_reason"
        const val KEY_PHONES_CSV = "phones_csv"
        const val KEY_OVERRIDE_BODY = "override_body"

        fun isAuthorizedCriticalContext(riskLevel: String, fallbackReason: String): Boolean {
            return riskLevel.equals("R3_CRITICAL", ignoreCase = true) ||
                fallbackReason.equals("NETWORK_LOSS_BLACKBOX", ignoreCase = true) ||
                fallbackReason.equals("SOS_OFFLINE_BACKUP", ignoreCase = true) ||
                fallbackReason.equals("INTERNET_UNAVAILABLE", ignoreCase = true) ||
                fallbackReason.equals("WHATSAPP_FAILED", ignoreCase = true) ||
                fallbackReason.equals("FAKE_CALL_GUARD_AUTO", ignoreCase = true) ||
                fallbackReason.equals("TRIP_ANOMALY_AUTO", ignoreCase = true)
        }

        fun enqueue(
            context: Context,
            incidentId: String,
            riskLevel: String = "R3_CRITICAL",
            userNote: String = "Urgent SOS Activated",
            latitude: Double? = null,
            longitude: Double? = null,
            accuracyMeters: Float? = null,
            batteryPercent: Int? = null,
            fallbackReason: String = "R3_CRITICAL_AUTO",
            phonesJoined: String = "",
            overrideBody: String = ""
        ) {
            if (!isAuthorizedCriticalContext(riskLevel, fallbackReason)) {
                Log.i(TAG, "Suppressed WorkManager SMS enqueue: riskLevel=$riskLevel, reason=$fallbackReason")
                return
            }
            val input = Data.Builder()
                .putString(KEY_INCIDENT_ID, incidentId)
                .putString(KEY_RISK_LEVEL, riskLevel)
                .putString(KEY_USER_NOTE, userNote)
                .putDouble(KEY_LAT, latitude ?: 0.0)
                .putDouble(KEY_LNG, longitude ?: 0.0)
                .putFloat(KEY_ACC, accuracyMeters ?: 0f)
                .putInt(KEY_BATTERY, batteryPercent ?: 100)
                .putString(KEY_FALLBACK_REASON, fallbackReason)
                .putString(KEY_PHONES_CSV, phonesJoined)
                .putString(KEY_OVERRIDE_BODY, overrideBody)
                .build()

            val request = OneTimeWorkRequestBuilder<CriticalSmsDispatchWorker>()
                .setInputData(input)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                .addTag("raksha_critical_sms")
                .build()

            val uniqueName = "critical_sms_${incidentId}_${fallbackReason}"
            try {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enqueue CriticalSmsDispatchWorker: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        val incidentId = inputData.getString(KEY_INCIDENT_ID).orEmpty().ifBlank { "R3-WORK" }
        val riskLevel = inputData.getString(KEY_RISK_LEVEL).orEmpty().ifBlank { "R3_CRITICAL" }
        val fallbackReason = inputData.getString(KEY_FALLBACK_REASON).orEmpty().ifBlank { "R3_CRITICAL_AUTO" }

        if (!isAuthorizedCriticalContext(riskLevel, fallbackReason)) {
            return Result.success()
        }

        val database = AppDatabase.getDatabase(applicationContext)
        val activeIncident = database.incidentDao().getIncidentById(incidentId)
        val pendingAlerts = database.offlineAlertDao().getPendingAlertsOnce()
            .filter { it.incidentId == incidentId }

        val userProfile = database.userDao().getUserProfileOnce()
        val userName = userProfile?.name?.takeIf { it.isNotBlank() } ?: "Citizen"

        val phonesInput = inputData.getString(KEY_PHONES_CSV).orEmpty()
        val rawPhones = if (phonesInput.isNotBlank()) {
            phonesInput.split(";").map { it.trim() }.filter { it.isNotBlank() }.distinct()
        } else {
            database.trustedContactDao().getActiveContacts()
                .map { it.phone.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }

        if (rawPhones.isEmpty()) {
            return Result.success()
        }

        val hasSmsPermission = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsPermission) {
            database.auditLogDao().insertLog(
                AuditLogEntity(
                    actor = "CriticalSmsWorker",
                    role = "SYSTEM",
                    action = "SMS_PERMISSION_MISSING",
                    targetId = incidentId,
                    details = "SEND_SMS permission not granted; queued alert awaits user permission or manual composer."
                )
            )
            return Result.failure()
        }

        val lat = inputData.getDouble(KEY_LAT, 0.0).takeIf { it != 0.0 }
            ?: activeIncident?.latitude?.takeIf { it != 0.0 }
        val lng = inputData.getDouble(KEY_LNG, 0.0).takeIf { it != 0.0 }
            ?: activeIncident?.longitude?.takeIf { it != 0.0 }
        val acc = inputData.getFloat(KEY_ACC, 0f).takeIf { it > 0f }
            ?: activeIncident?.accuracyMeters
        val battery = inputData.getInt(KEY_BATTERY, 100)
        val userNote = inputData.getString(KEY_USER_NOTE).orEmpty()
        val overrideBody = inputData.getString(KEY_OVERRIDE_BODY).orEmpty()

        val messageBody = overrideBody.ifBlank {
            SosAlertService.buildEmergencySmsBody(
                userName = userName,
                incidentId = incidentId,
                latitude = lat,
                longitude = lng,
                accuracyMeters = acc,
                userNote = userNote,
                fallbackReason = fallbackReason,
                batteryPercent = battery
            )
        }

        var sentCount = 0
        for (phone in rawPhones) {
            if (sendViaSmsManager(phone, messageBody)) {
                sentCount++
            }
        }

        return if (sentCount > 0) {
            pendingAlerts.forEach { alert ->
                database.offlineAlertDao().markDispatched(alert.alertId)
            }
            database.incidentEventDao().insertEvent(
                IncidentEventEntity(
                    incidentId = incidentId,
                    timestamp = System.currentTimeMillis(),
                    oldStatus = "ACTIVE",
                    newStatus = "AUTO_SMS_SENT",
                    actor = "CriticalSmsDispatchWorker",
                    note = "WorkManager auto-dispatched carrier SMS via SmsManager to $sentCount/${rawPhones.size} contact(s) ($fallbackReason)."
                )
            )
            database.auditLogDao().insertLog(
                AuditLogEntity(
                    actor = userName,
                    role = "SYSTEM",
                    action = "WORKMANAGER_SMS_DISPATCHED",
                    targetId = incidentId,
                    details = "SmsManager sent $sentCount/${rawPhones.size} critical SMS alerts ($fallbackReason)."
                )
            )
            Result.success()
        } else if (runAttemptCount < 5) {
            pendingAlerts.forEach { alert ->
                database.offlineAlertDao().updateAlert(
                    alert.copy(
                        dispatchAttempts = alert.dispatchAttempts + 1,
                        lastAttemptTime = System.currentTimeMillis()
                    )
                )
            }
            Result.retry()
        } else {
            Result.failure()
        }
    }

    private fun sendViaSmsManager(phoneNumber: String, messageText: String): Boolean {
        return try {
            val cleaned = phoneNumber.replace(Regex("[^+\\d]"), "")
            if (cleaned.isBlank()) return false
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applicationContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return false
            val parts = smsManager.divideMessage(messageText)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleaned, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleaned, null, messageText, null, null)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "SmsManager send failed: ${e.message}")
            false
        }
    }
}

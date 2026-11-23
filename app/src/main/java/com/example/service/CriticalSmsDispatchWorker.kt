package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
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
 * Play-Protect-compliant WorkManager task that prepares a pre-filled emergency SMS/MMS draft
 * (`Intent.ACTION_SENDTO` `smsto:`) via an actionable high-priority notification so the user
 * can tap Send manually in their carrier Messages app without requiring restricted `SEND_SMS`.
 */
class CriticalSmsDispatchWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "CriticalSmsWorker"
        private const val CHANNEL_ID = "rakshaone_prefilled_sms_channel"
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

        postPreFilledSmsNotification(
            incidentId = incidentId,
            phonesJoined = rawPhones.joinToString(";"),
            smsBody = messageBody,
            contactCount = rawPhones.size
        )

        database.incidentEventDao().insertEvent(
            IncidentEventEntity(
                incidentId = incidentId,
                timestamp = System.currentTimeMillis(),
                oldStatus = "ACTIVE",
                newStatus = "PREFILLED_SMS_READY",
                actor = "CriticalSmsDispatchWorker",
                note = "Pre-filled SMS notification ready for ${rawPhones.size} trusted contact(s) ($fallbackReason); tap notification to confirm Send in Messages."
            )
        )
        database.auditLogDao().insertLog(
            AuditLogEntity(
                actor = userName,
                role = "SYSTEM",
                action = "WORKMANAGER_PREFILLED_SMS_READY",
                targetId = incidentId,
                details = "Prepared pre-filled SMS composer notification for ${rawPhones.size} contact(s) ($fallbackReason)."
            )
        )
        return Result.success()
    }

    private fun postPreFilledSmsNotification(
        incidentId: String,
        phonesJoined: String,
        smsBody: String,
        contactCount: Int
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Pre-Filled Emergency SMS Backup",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "One-tap pre-filled Messages SMS draft for Trusted Contacts"
                }
                nm.createNotificationChannel(ch)
            }
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phonesJoined")).apply {
                putExtra("sms_body", smsBody)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pi = PendingIntent.getActivity(
                applicationContext,
                ("PREFILL_$incidentId").hashCode(),
                smsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Tap to Send SOS SMS ($contactCount Contacts)")
                .setContentText("Pre-filled live GPS message ready in Messages app")
                .setStyle(NotificationCompat.BigTextStyle().bigText(smsBody))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_send, "Open Messages to Send", pi)
                .build()
            nm.notify(("PREFILL_$incidentId").hashCode(), notification)
        } catch (_: Exception) {
        }
    }
}

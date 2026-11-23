package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.CloudEmergencyDispatch

/**
 * Posts high-priority OS notifications when a trusted circle member with RakshaOne
 * triggers an SOS/Anti-Snatch emergency so installed contacts get immediate push-style alerts.
 */
object GuardianHighAlertNotifier {
    private const val CHANNEL_ID = "rakshaone_guardian_high_alerts"
    const val EXTRA_GUARDIAN_DISPATCH_ID = "extra_guardian_dispatch_id"

    fun postGuardianHighAlert(context: Context, dispatch: CloudEmergencyDispatch) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Trusted Circle High-Alert SOS",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Immediate high-priority alerts when a trusted contact triggers RakshaOne SOS"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 350, 150, 350, 150, 500)
                }
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_GUARDIAN_DISPATCH_ID, dispatch.dispatchId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                dispatch.dispatchId.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val sender = dispatch.ownerName.ifBlank { dispatch.ownerPhone.ifBlank { "Trusted Contact" } }
            val locationLine = dispatch.locationSummary.ifBlank {
                if (dispatch.latitude != 0.0 || dispatch.longitude != 0.0) {
                    "GPS: ${dispatch.latitude}, ${dispatch.longitude}"
                } else {
                    "Live GPS updating"
                }
            }

            val alertLabel = when (dispatch.alertType) {
                "FAKE_CALL_GUARD" -> "Protective Fake Call + Auto Audio"
                "ANTI_SNATCH" -> "Anti-Snatch Theft Alert"
                "TRIP_ANOMALY" -> "Trip Safety Anomaly"
                else -> dispatch.alertType
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("HIGH ALERT: $sender ($alertLabel)")
                .setContentText("$locationLine • Battery ${dispatch.batteryPercent}% • Tap for live audio, video & map")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$sender triggered $alertLabel!\n" +
                            "Location: $locationLine\n" +
                            "Evidence: ${dispatch.evidenceSummary.ifBlank { "Auto audio, photo & video vault syncing" }}\n" +
                            "Tap to play voice recording, view captures & open live GPS."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(dispatch.dispatchId.hashCode(), notification)
        } catch (_: Exception) {
        }
    }
}

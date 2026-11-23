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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity

/**
 * Dedicated Foreground Service (`foregroundServiceType="location"`) that keeps live
 * FusedLocationProviderClient trip monitoring, route deviation checks, and prolonged
 * unexpected stop alerts active while an escort journey is underway without requiring
 * restricted ACCESS_BACKGROUND_LOCATION permission.
 */
class TripMonitoringService : Service() {

    companion object {
        private const val CHANNEL_ID = "rakshaone_trip_monitor"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START_TRIP_MONITOR = "com.example.action.START_TRIP_MONITOR"
        const val ACTION_UPDATE_TRIP_MONITOR = "com.example.action.UPDATE_TRIP_MONITOR"
        const val ACTION_STOP_TRIP_MONITOR = "com.example.action.STOP_TRIP_MONITOR"
        const val EXTRA_STATUS_TITLE = "extra_status_title"
        const val EXTRA_STATUS_BODY = "extra_status_body"

        fun startOrUpdate(
            context: Context,
            title: String = "RakshaOne Live Trip Escort Active",
            body: String = "Monitoring GPS coordinates, route corridor & unexpected stops..."
        ) {
            val intent = Intent(context, TripMonitoringService::class.java).apply {
                action = ACTION_START_TRIP_MONITOR
                putExtra(EXTRA_STATUS_TITLE, title)
                putExtra(EXTRA_STATUS_BODY, body)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TripMonitoringService::class.java).apply {
                action = ACTION_STOP_TRIP_MONITOR
            }
            try {
                context.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TRIP_MONITOR) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_STATUS_TITLE) ?: "RakshaOne Live Trip Escort Active"
        val body = intent?.getStringExtra(EXTRA_STATUS_BODY)
            ?: "FusedLocationProviderClient monitoring active"

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = buildNotification(title, body)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        } catch (_: Exception) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Trip Escort & Route Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification while RakshaOne monitors active journey coordinates and anomalies"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }
}

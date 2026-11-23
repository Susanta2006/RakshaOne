package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.model.AuditLogEntity
import com.example.data.repository.SafetyRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Dedicated Firebase Cloud Messaging (FCM) Service that:
 * 1. Automatically receives critical incident alerts & dossier token broadcasts from Guardians / Network.
 * 2. Broadcasts and syncs incident dossier tokens, live GPS, audio (.m4a), video (.mp4), and photo evidence
 *    to all designated Trusted Contacts immediately upon any critical state change (`R3_CRITICAL`).
 */
class RakshaFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        private const val TAG = "RakshaFCMService"
        private const val CHANNEL_ID = "rakshaone_fcm_alerts"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_BROADCAST_DOSSIER = "com.example.action.BROADCAST_DOSSIER"
        const val EXTRA_INCIDENT_ID = "extra_incident_id"
        const val EXTRA_DOSSIER_TOKEN = "extra_dossier_token"
        const val EXTRA_DOSSIER_URL = "extra_dossier_url"
        const val EXTRA_VICTIM_NAME = "extra_victim_name"

        /**
         * Broadcasts the incident dossier token and comprehensive evidence summary (audio, video, photos, live GPS)
         * to all designated trusted contacts and Firestore emergency dispatches immediately upon any critical state change.
         */
        fun broadcastCriticalDossierToken(
            context: Context,
            incidentId: String,
            dossierToken: String,
            dossierUrl: String,
            victimName: String,
            latitude: Double?,
            longitude: Double?,
            accuracyMeters: Float?,
            batteryPercent: Int,
            evidenceSummary: String
        ) {
            val appContext = context.applicationContext
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    val db = AppDatabase.getDatabase(appContext)
                    val contacts = db.trustedContactDao().getActiveContacts()
                    val phones = contacts.map { it.phone.trim() }.filter { it.isNotBlank() }.distinct()
                    val emails = contacts.map { it.email.trim() }.filter { it.isNotBlank() }.distinct()

                    val mapLink = if (latitude != null && longitude != null && (latitude != 0.0 || longitude != 0.0)) {
                        "https://maps.google.com/?q=$latitude,$longitude"
                    } else {
                        "GPS pending"
                    }

                    val fullMessage = buildString {
                        append("🚨 URGENT SOS DOSSIER • $victimName\n")
                        append("Incident ID: $incidentId\n")
                        append("Live GPS: $mapLink (±${accuracyMeters?.toInt() ?: 0}m)\n")
                        append("Battery: $batteryPercent%\n")
                        append("Evidence Vault: $evidenceSummary\n")
                        append("Official 112 Police Dossier & Live Portal Token:\n$dossierUrl")
                    }

                    // 1. Send via carrier SMS / SmsManager to trusted contacts
                    for (phone in phones) {
                        runCatching {
                            CriticalSmsDispatchWorker.enqueue(
                                context = appContext,
                                incidentId = incidentId,
                                riskLevel = "R3_CRITICAL",
                                userNote = "Critical Dossier Broadcast",
                                latitude = latitude,
                                longitude = longitude,
                                accuracyMeters = accuracyMeters,
                                batteryPercent = batteryPercent,
                                fallbackReason = "DOSSIER_TOKEN_BROADCAST",
                                phonesJoined = phone,
                                overrideBody = fullMessage
                            )
                        }
                    }

                    // 2. Publish emergency dispatch to Firestore so installed Guardians see live dossier token & evidence instantly
                    val uid = Firebase.auth.currentUser?.uid
                    if (!uid.isNullOrBlank()) {
                        val firestore = FirebaseFirestore.getInstance(
                            appContext.getString(R.string.firestore_database_id)
                        )
                        val dispatchPayload = mapOf(
                            "incidentId" to incidentId,
                            "senderUid" to uid,
                            "victimName" to victimName,
                            "alertType" to "R3_DOSSIER_BROADCAST",
                            "status" to "ACTIVE",
                            "latitude" to (latitude ?: 0.0),
                            "longitude" to (longitude ?: 0.0),
                            "accuracyMeters" to (accuracyMeters ?: 0f),
                            "batteryPercent" to batteryPercent,
                            "dossierToken" to dossierToken,
                            "dossierUrl" to dossierUrl,
                            "evidenceSummary" to evidenceSummary,
                            "mapsUrl" to mapLink,
                            "createdAt" to com.google.firebase.Timestamp.now()
                        )
                        firestore.collection("emergency_dispatches")
                            .document("DOSSIER_$incidentId")
                            .set(dispatchPayload)
                            .await()
                    }

                    db.auditLogDao().insertLog(
                        AuditLogEntity(
                            actor = victimName,
                            role = "SYSTEM",
                            action = "DOSSIER_TOKEN_BROADCASTED",
                            targetId = incidentId,
                            details = "Broadcasted dossier token to ${phones.size} trusted phone(s) & Firestore emergency_dispatches. URL: $dossierUrl"
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to broadcast critical dossier token: ${e.message}")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        serviceScope.launch {
            try {
                val uid = Firebase.auth.currentUser?.uid
                if (!uid.isNullOrBlank()) {
                    val firestore = FirebaseFirestore.getInstance(
                        applicationContext.getString(R.string.firestore_database_id)
                    )
                    firestore.collection("users")
                        .document(uid)
                        .update("fcmToken", token)
                        .await()
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val incidentId = data["incidentId"] ?: "REMOTE-INC"
        val victimName = data["victimName"] ?: "Victim"
        val dossierUrl = data["dossierUrl"] ?: ""
        val evidenceSummary = data["evidenceSummary"] ?: "Audio + Video + Photos + GPS secured"

        showNotification(
            title = "🚨 EMERGENCY ALERT: $victimName",
            body = "Critical SOS triggered! Tap to open live police dossier & evidence vault.",
            incidentId = incidentId,
            dossierUrl = dossierUrl
        )

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                db.auditLogDao().insertLog(
                    AuditLogEntity(
                        actor = victimName,
                        role = "GUARDIAN",
                        action = "REMOTE_FCM_SOS_RECEIVED",
                        targetId = incidentId,
                        details = "Received FCM emergency push. Evidence: $evidenceSummary. Dossier: $dossierUrl"
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun showNotification(title: String, body: String, incidentId: String, dossierUrl: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INCIDENT_ID, incidentId)
            putExtra(EXTRA_DOSSIER_URL, dossierUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            incidentId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Guardian Emergency FCM Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Real-time emergency push alerts for trusted guardians"
                    enableVibration(true)
                }
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID + incidentId.hashCode() % 1000, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        serviceScope.cancel()
    }
}

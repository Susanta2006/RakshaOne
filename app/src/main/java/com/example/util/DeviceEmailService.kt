package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.model.AuditLogEntity
import com.example.data.model.OfflineAlertEntity
import com.example.data.model.TrustedContactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceEmailService(
    private val context: Context,
    private val database: AppDatabase
) {
    private val tag = "DeviceEmailService"

    private val _lastDispatchStatus = MutableStateFlow("Ready")
    val lastDispatchStatus: StateFlow<String> = _lastDispatchStatus.asStateFlow()

    /**
     * Prepare an emergency email with real GPS coordinates and open the user's native
     * device email app when triggered interactively, or queue in Room when triggered from background.
     */
    suspend fun sendEmergencyEmail(
        incidentId: String,
        userName: String,
        latitude: Double?,
        longitude: Double?,
        contacts: List<TrustedContactEntity>,
        alertType: String = "SOS_EMERGENCY",
        customNote: String = "Emergency alert triggered from RakshaOne Safety Shield",
        launchEmailClient: Boolean = true,
        recordPendingCopy: Boolean = true,
        attachmentUris: ArrayList<Uri> = arrayListOf()
    ): Boolean = withContext(Dispatchers.IO) {
        val hasCoords = latitude != null && longitude != null
        val mapsLink = if (hasCoords) {
            "https://maps.google.com/?q=$latitude,$longitude"
        } else {
            "GPS coordinates unavailable at trigger time"
        }

        val emailRecipients = contacts.filter { it.email.isNotBlank() }
        val recipientEmails = emailRecipients.map { it.email }
        val emailsJoined = recipientEmails.joinToString(";")

        if (recipientEmails.isEmpty()) {
            _lastDispatchStatus.value = "No trusted contacts with email addresses configured"
            return@withContext false
        }

        var composerOpened = false
        if (launchEmailClient) {
            composerOpened = withContext(Dispatchers.Main) {
                triggerDirectDeviceEmail(
                    recipientEmails = recipientEmails,
                    userName = userName,
                    alertType = alertType,
                    mapsLink = mapsLink,
                    customNote = customNote,
                    incidentId = incidentId,
                    attachmentUris = attachmentUris
                )
            }
        }

        if (recordPendingCopy) {
            val alertRecord = OfflineAlertEntity(
                alertId = "ALT-${System.currentTimeMillis()}",
                incidentId = incidentId,
                alertType = alertType,
                timestamp = System.currentTimeMillis(),
                latitude = latitude ?: 0.0,
                longitude = longitude ?: 0.0,
                accuracyMeters = 0f,
                messageText = "RAKSHAONE EMERGENCY ALERT ($alertType)\nUser: $userName\nLocation: $mapsLink\nNote: $customNote",
                recipientPhones = contacts.joinToString(";") { it.phone },
                recipientEmails = emailsJoined,
                isDispatched = composerOpened,
                dispatchAttempts = if (launchEmailClient) 1 else 0,
                lastAttemptTime = System.currentTimeMillis(),
                status = if (composerOpened) "OPENED_IN_DEVICE_EMAIL" else "PENDING_CONNECTIVITY"
            )
            database.offlineAlertDao().insertAlert(alertRecord)
        }

        database.auditLogDao().insertLog(
            AuditLogEntity(
                timestamp = System.currentTimeMillis(),
                actor = userName,
                role = "CITIZEN",
                action = if (composerOpened) "DEVICE_EMAIL_COMPOSER_OPENED" else "EMERGENCY_EMAIL_QUEUED",
                targetId = incidentId,
                details = "Recipients: [${recipientEmails.joinToString(", ")}]. Attachments: ${attachmentUris.size}. Location: $mapsLink"
            )
        )

        _lastDispatchStatus.value = when {
            composerOpened -> "Device email app opened for ${recipientEmails.size} contact(s) with ${attachmentUris.size} attachment(s)"
            !launchEmailClient -> "Alert queued locally in Room — tap Sync Pending Alerts to email"
            else -> "No email app found on device — alert queued locally"
        }

        composerOpened
    }

    fun triggerDirectDeviceEmail(
        recipientEmails: List<String>,
        userName: String,
        alertType: String,
        mapsLink: String,
        customNote: String,
        incidentId: String,
        attachmentUris: ArrayList<Uri> = arrayListOf()
    ): Boolean {
        val subject = "URGENT: $alertType Alert for $userName [Incident $incidentId]"
        val body = """
            EMERGENCY ALERT FROM RAKSHAONE SAFETY SHIELD
            --------------------------------------------
            User: $userName
            Incident ID: $incidentId
            Alert Type: $alertType
            Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}

            LIVE GOOGLE MAPS LOCATION:
            $mapsLink

            DETAILS / NOTE:
            $customNote

            Attached Evidence Files: ${attachmentUris.size}
            This emergency alert was generated by RakshaOne. Please verify safety immediately.
        """.trimIndent()

        if (attachmentUris.isNotEmpty()) {
            try {
                val multiIntent = if (attachmentUris.size > 1) {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachmentUris)
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_STREAM, attachmentUris.first())
                    }
                }
                multiIntent.apply {
                    putExtra(Intent.EXTRA_EMAIL, recipientEmails.toTypedArray())
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val pm = context.packageManager
                val matches = pm.queryIntentActivities(multiIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                matches.forEach { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@forEach
                    attachmentUris.forEach { uri ->
                        try { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    }
                }
                context.startActivity(
                    Intent.createChooser(multiIntent, "Send Emergency Email + ${attachmentUris.size} Evidence File(s)").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                )
                return true
            } catch (e: Exception) {
                Log.w(tag, "Multi-attachment email intent failed, falling back to mailto: ${e.message}")
            }
        }

        return try {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, recipientEmails.toTypedArray())
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(emailIntent)
            true
        } catch (_: Exception) {
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, recipientEmails.toTypedArray())
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                context.startActivity(
                    Intent.createChooser(shareIntent, "Send Emergency Email").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                true
            } catch (e2: Exception) {
                Log.e(tag, "Failed to launch device email client: ${e2.message}")
                false
            }
        }
    }
}

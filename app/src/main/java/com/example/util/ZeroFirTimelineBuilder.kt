package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates an official, shareable Zero-FIR Chronological Incident Timeline
 * containing tamper-evident SHA-256 evidence hashes, coordinates, timeline logs,
 * and responder details ready for police or legal review.
 */
class ZeroFirTimelineBuilder(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)

    suspend fun buildIncidentTimelineReport(incidentId: String): String = withContext(Dispatchers.IO) {
        val incident = database.incidentDao().getIncidentById(incidentId)
        val user = database.userDao().getUserProfileOnce()
        val evidence = database.evidenceDao().getEvidenceForIncidentOnce(incidentId)
        val assessment = database.riskAssessmentDao().getLatestForIncident(incidentId)
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm:ss z", Locale.US)

        buildString {
            appendLine("====================================================")
            appendLine("RAKSHAONE ZERO-FIR CHRONOLOGICAL INCIDENT TIMELINE")
            appendLine("====================================================")
            appendLine("Generated At : ${fmt.format(Date())}")
            appendLine("Incident ID  : $incidentId")
            appendLine("Citizen Name : ${user?.name?.ifBlank { "Citizen User" } ?: "Citizen User"}")
            appendLine("Contact Phone: ${user?.phone?.ifBlank { "Not specified" } ?: "Not specified"}")
            if (incident != null) {
                appendLine("Incident Type: ${incident.type} (${incident.priority})")
                appendLine("Status       : ${incident.status}")
                appendLine("Triggered At : ${fmt.format(Date(incident.createdAt))}")
                appendLine("GPS Location : ${incident.latitude}, ${incident.longitude} (±${incident.accuracyMeters.toInt()}m)")
                appendLine("Maps Link    : https://maps.google.com/?q=${incident.latitude},${incident.longitude}")
                appendLine("Locality     : ${incident.locationDescription}")
                if (incident.description.isNotBlank()) {
                    appendLine("Details      : ${incident.description}")
                }
            }
            if (assessment != null) {
                appendLine()
                appendLine("--- RISK ENGINE VERIFICATION ---")
                appendLine("Risk Classification : ${assessment.riskLevel}")
                appendLine("Trigger Origin      : ${assessment.triggerOrigin}")
                appendLine("Reason Codes        : ${assessment.reasonCodesCsv}")
                appendLine("Summary             : ${assessment.explanation}")
            }
            appendLine()
            appendLine("--- TAMPER-EVIDENT EVIDENCE INVENTORY (SHA-256) ---")
            if (evidence.isEmpty()) {
                appendLine("No multimedia evidence items recorded for this incident.")
            } else {
                evidence.forEachIndexed { idx, item ->
                    appendLine("${idx + 1}. [${item.fileType}] ${item.title}")
                    appendLine("   Captured: ${fmt.format(Date(item.uploadTime))} | Size: ${item.fileSizeBytes / 1024} KB")
                    appendLine("   SHA-256 : ${item.sha256Hash}")
                }
            }
            appendLine()
            appendLine("Note: Under Section 173 BNSS (formerly Zero-FIR rules), an emergency complaint can be filed at ANY police station regardless of jurisdiction.")
        }
    }

    suspend fun exportAndShareZeroFirReport(incidentId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val report = buildIncidentTimelineReport(incidentId)
            val safeId = incidentId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(context.cacheDir, "ZeroFIR_${safeId}.txt")
            file.writeText(report)
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            withContext(Dispatchers.Main) {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Zero-FIR Incident Timeline: $incidentId")
                    putExtra(Intent.EXTRA_TEXT, report)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(share, "Share Zero-FIR Incident Timeline").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

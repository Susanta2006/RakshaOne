package com.example.util

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import com.example.R
import com.example.data.AppDatabase
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Keep
@IgnoreExtraProperties
data class CloudEvidenceChunk(
    @DocumentId val id: String = "",
    val chunkId: String = "",
    val incidentId: String = "",
    val ownerUid: String = "",
    val mediaKind: String = "AUDIO", // AUDIO, PHOTO_FRONT, PHOTO_REAR, VIDEO
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val sha256: String = "",
    val dataBase64: String = "",
    val authorizedUids: List<String> = emptyList(),
    val authorizedEmails: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class CloudMediaItemSummary(
    val incidentId: String,
    val mediaKind: String,
    val fileName: String,
    val mimeType: String,
    val totalChunks: Int,
    val receivedChunks: Int
)

/**
 * Real Chunked Cloud Evidence Vault (`evidence_vault/{chunkId}`):
 * Automatically uploads and shares victim Audio (`.m4a`), Front/Rear Photos (`.jpg`),
 * and compact Video (`.mp4`) directly to Trusted Guardians with ZERO manual interference,
 * and allows Guardians to stream/reassemble and play audio/video right inside RakshaOne.
 */
class FirestoreEvidenceSyncManager(private val context: Context) {

    private val tag = "EvidenceSyncMgr"
    private val database = AppDatabase.getDatabase(context)
    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(context.applicationContext.getString(R.string.firestore_database_id))
    }
    private val uploadedFileSignatures = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val RAW_CHUNK_SIZE_BYTES = 135 * 1024 // ~180 KB Base64 (< 1 MB doc limit)
        private const val MAX_CHUNKS_PER_FILE = 18         // Up to ~2.4 MB per media file
    }

    suspend fun syncPendingEvidenceToCloud(
        incidentId: String,
        authorizedUids: List<String> = emptyList(),
        authorizedEmails: List<String> = emptyList(),
        extraMediaFiles: List<Pair<String, File>> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        val uid = Firebase.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            Log.i(tag, "Skipping cloud vault upload (user offline / not signed in)")
            return@withContext false
        }
        val cleanInc = sanitizeId(incidentId)
        val safeUids = authorizedUids.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(15)
        val safeEmails = authorizedEmails.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.distinct().take(15)

        try {
            val candidates = linkedMapOf<String, Pair<String, File>>()
            extraMediaFiles.forEach { (kind, file) ->
                if (file.exists() && file.length() > 0L) {
                    candidates[file.absolutePath] = kind to file
                }
            }

            // Collect cached incident directory files automatically (.m4a, .jpg, .mp4)
            val cacheDir = File(context.cacheDir, "emergency_evidence/$cleanInc")
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0L) {
                        val kind = inferMediaKind(file)
                        if (kind != null) {
                            candidates[file.absolutePath] = kind to file
                        }
                    }
                }
            }

            // Also inspect Room evidence records pointing to readable media files
            val dbEvidence = database.evidenceDao().getEvidenceForIncidentOnce(incidentId)
            dbEvidence.forEach { ev ->
                val f = File(ev.storageLocation)
                if (f.exists() && f.length() > 0L && !f.name.endsWith(".enc", true)) {
                    val kind = inferMediaKind(f)
                    if (kind != null) {
                        candidates[f.absolutePath] = kind to f
                    }
                }
            }

            if (candidates.isEmpty()) return@withContext true

            var allOk = true
            for ((_, pair) in candidates) {
                val (kind, file) = pair
                val sig = "${cleanInc}_${file.name}_${file.length()}"
                if (uploadedFileSignatures.contains(sig)) continue

                val uploaded = uploadChunkedFile(
                    incidentId = cleanInc,
                    ownerUid = uid,
                    mediaKind = kind,
                    file = file,
                    authorizedUids = safeUids,
                    authorizedEmails = safeEmails
                )
                if (uploaded) {
                    uploadedFileSignatures.add(sig)
                } else {
                    allOk = false
                }
            }
            allOk
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "Failed syncing evidence to cloud vault: ${e.message}")
            false
        }
    }

    private fun inferMediaKind(file: File): String? {
        val lower = file.name.lowercase(Locale.US)
        return when {
            lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".mp3") -> "AUDIO"
            lower.endsWith(".mp4") -> "VIDEO"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ->
                if (lower.startsWith("front_")) "PHOTO_FRONT" else "PHOTO_REAR"
            else -> null
        }
    }

    private fun mimeForKind(kind: String, file: File): String {
        return when {
            kind == "AUDIO" -> "audio/mp4"
            kind == "VIDEO" -> "video/mp4"
            file.name.endsWith(".png", true) -> "image/png"
            else -> "image/jpeg"
        }
    }

    private suspend fun uploadChunkedFile(
        incidentId: String,
        ownerUid: String,
        mediaKind: String,
        file: File,
        authorizedUids: List<String>,
        authorizedEmails: List<String>
    ): Boolean {
        val rawBytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return false
        }
        if (rawBytes.isEmpty()) return false

        val sha256 = sha256Hex(rawBytes)
        val totalChunks = ((rawBytes.size + RAW_CHUNK_SIZE_BYTES - 1) / RAW_CHUNK_SIZE_BYTES)
            .coerceIn(1, MAX_CHUNKS_PER_FILE)
        val mime = mimeForKind(mediaKind, file)
        val safeName = file.name.take(96)
        val kindPrefix = sanitizeId(mediaKind.lowercase(Locale.US)).take(16)

        var successChunks = 0
        for (idx in 0 until totalChunks) {
            val start = idx * RAW_CHUNK_SIZE_BYTES
            val end = minOf(rawBytes.size, start + RAW_CHUNK_SIZE_BYTES)
            if (start >= rawBytes.size) break
            val slice = rawBytes.copyOfRange(start, end)
            val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
            val chunkId = sanitizeId("${incidentId}_${kindPrefix}_${sha256.take(8)}_$idx").take(128)
            val payload = mapOf(
                "chunkId" to chunkId,
                "incidentId" to incidentId,
                "ownerUid" to ownerUid,
                "mediaKind" to mediaKind,
                "fileName" to safeName,
                "mimeType" to mime,
                "chunkIndex" to idx,
                "totalChunks" to totalChunks,
                "sha256" to sha256,
                "dataBase64" to b64,
                "authorizedUids" to authorizedUids,
                "authorizedEmails" to authorizedEmails,
                "createdAt" to FieldValue.serverTimestamp()
            )
            try {
                firestore.collection("evidence_vault")
                    .document(chunkId)
                    .set(payload, SetOptions.merge())
                    .await()
                successChunks++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Chunk upload failed for $chunkId: ${e.message}")
            }
        }
        Log.i(tag, "Uploaded $successChunks/$totalChunks chunks for $safeName ($mediaKind, ${rawBytes.size / 1024} KB)")
        return successChunks == totalChunks
    }

    /**
     * Queries Firestore `evidence_vault` for all uploaded media summaries for a specific incident,
     * accessible to the owner or authorized Trusted Guardians.
     */
    suspend fun fetchMediaSummaryForIncident(incidentId: String): List<CloudMediaItemSummary> =
        withContext(Dispatchers.IO) {
            val uid = Firebase.auth.currentUser?.uid ?: return@withContext emptyList()
            val cleanInc = sanitizeId(incidentId)
            return@withContext try {
                val chunks = queryAuthorizedChunks(cleanInc, uid, Firebase.auth.currentUser?.email.orEmpty())
                chunks.groupBy { "${it.mediaKind}::${it.fileName}" }
                    .values
                    .mapNotNull { group ->
                        val first = group.firstOrNull() ?: return@mapNotNull null
                        CloudMediaItemSummary(
                            incidentId = cleanInc,
                            mediaKind = first.mediaKind,
                            fileName = first.fileName,
                            mimeType = first.mimeType,
                            totalChunks = first.totalChunks,
                            receivedChunks = group.size
                        )
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Reassembles a specific media kind (`AUDIO`, `VIDEO`, `PHOTO_FRONT`, `PHOTO_REAR`) from
     * Firestore `evidence_vault` chunks into a local playable/viewable cache file for Guardians.
     */
    suspend fun downloadAndAssembleMediaFile(
        incidentId: String,
        mediaKind: String
    ): File? = withContext(Dispatchers.IO) {
        val uid = Firebase.auth.currentUser?.uid ?: return@withContext null
        val cleanInc = sanitizeId(incidentId)
        val chunks = queryAuthorizedChunks(cleanInc, uid, Firebase.auth.currentUser?.email.orEmpty())
            .filter { it.mediaKind.equals(mediaKind, ignoreCase = true) }
        if (chunks.isEmpty()) return@withContext null

        val firstFileName = chunks.first().fileName
        val fileChunks = chunks
            .filter { it.fileName == firstFileName }
            .sortedBy { it.chunkIndex }
        if (fileChunks.isEmpty()) return@withContext null

        val out = ByteArrayOutputStream()
        for (chunk in fileChunks) {
            val raw = Base64.decode(chunk.dataBase64, Base64.NO_WRAP)
            out.write(raw)
        }
        val ext = when {
            firstFileName.contains('.') -> firstFileName.substringAfterLast('.')
            mediaKind == "AUDIO" -> "m4a"
            mediaKind == "VIDEO" -> "mp4"
            else -> "jpg"
        }
        val dir = File(context.cacheDir, "guardian_vault_playback/$cleanInc").apply { mkdirs() }
        val target = File(dir, "guardian_${mediaKind.lowercase(Locale.US)}.$ext")
        target.writeBytes(out.toByteArray())
        target
    }

    private suspend fun queryAuthorizedChunks(
        cleanIncidentId: String,
        currentUid: String,
        currentEmail: String
    ): List<CloudEvidenceChunk> {
        val col = firestore.collection("evidence_vault")
        val collected = linkedMapOf<String, CloudEvidenceChunk>()

        suspend fun runQuery(field: String, value: Any) {
            try {
                val snap = col.whereEqualTo("incidentId", cleanIncidentId)
                    .let { q ->
                        if (field == "ownerUid") q.whereEqualTo("ownerUid", value)
                        else q.whereArrayContains(field, value)
                    }
                    .get()
                    .await()
                snap.documents.forEach { d ->
                    d.toObject(CloudEvidenceChunk::class.java)?.let { c ->
                        collected[d.id] = c
                    }
                }
            } catch (_: Exception) {
            }
        }

        runQuery("ownerUid", currentUid)
        runQuery("authorizedUids", currentUid)
        val normalizedEmail = currentEmail.trim().lowercase(Locale.US)
        if (normalizedEmail.isNotBlank()) {
            runQuery("authorizedEmails", normalizedEmail)
        }
        return collected.values.toList()
    }

    suspend fun purgeIncidentVaultChunks(incidentId: String) = withContext(Dispatchers.IO) {
        val uid = Firebase.auth.currentUser?.uid ?: return@withContext
        val cleanInc = sanitizeId(incidentId)
        try {
            val snap = firestore.collection("evidence_vault")
                .whereEqualTo("incidentId", cleanInc)
                .whereEqualTo("ownerUid", uid)
                .get()
                .await()
            snap.documents.forEach { doc ->
                runCatching { doc.reference.delete().await() }
            }
            uploadedFileSignatures.removeIf { it.startsWith("${cleanInc}_") }
        } catch (_: Exception) {
        }
    }

    private fun sanitizeId(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128).ifBlank { "INC" }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.AppDatabase
import com.example.data.model.EncryptedAudioMeta
import com.example.data.model.EvidenceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AudioEvidenceRecorder(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope
) {
    private val tag = "AudioEvidenceRecorder"

    private var mediaRecorder: MediaRecorder? = null
    private var recordingJob: Job? = null
    private var tempRawAudioFile: File? = null
    private var activeIncidentId: String? = null
    private var recordingStartTimeMs: Long = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private val _latestAudioEvidence = MutableStateFlow<EncryptedAudioMeta?>(null)
    val latestAudioEvidence: StateFlow<EncryptedAudioMeta?> = _latestAudioEvidence.asStateFlow()

    private val _latestShareableAudioFile = MutableStateFlow<File?>(null)
    val latestShareableAudioFile: StateFlow<File?> = _latestShareableAudioFile.asStateFlow()

    private val _capturedAudioPaths = MutableStateFlow<List<String>>(emptyList())
    val capturedAudioPaths: StateFlow<List<String>> = _capturedAudioPaths.asStateFlow()

    private val _recordingError = MutableStateFlow<String?>(null)
    val recordingError: StateFlow<String?> = _recordingError.asStateFlow()

    var onRecordingFinalized: ((incidentId: String, shareableFile: File) -> Unit)? = null

    companion object {
        private const val AES_KEY_PREF = "rakshaone_audio_aes_key"
        private const val DEFAULT_RECORD_DURATION_SECONDS = 12
        private const val COMPACT_BIT_RATE = 24000
        private const val COMPACT_SAMPLE_RATE = 16000
        private const val GCM_TAG_LENGTH = 128
        private const val IV_LENGTH = 12
    }

    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun evidenceCacheDir(incidentId: String): File {
        val safeId = incidentId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(context.cacheDir, "emergency_evidence/$safeId").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Start automatic background audio recording upon SOS, Anti-Snatch, or Fake Call connection.
     * Uses compact voice AAC (`24kbps / 16kHz`) so clips upload cleanly via Firestore Evidence Vault.
     */
    @SuppressLint("MissingPermission")
    fun startAutomaticSosRecording(
        incidentId: String,
        durationSeconds: Int = DEFAULT_RECORD_DURATION_SECONDS
    ): Boolean {
        if (_isRecording.value) return true
        if (!hasAudioPermission()) {
            _recordingError.value = "Microphone permission not granted for audio evidence"
            return false
        }

        _recordingError.value = null
        activeIncidentId = incidentId
        val evidenceDir = File(context.filesDir, "evidence_vault").apply { mkdirs() }
        tempRawAudioFile = File(evidenceDir, "temp_raw_${incidentId}_${System.currentTimeMillis()}.m4a")

        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(COMPACT_BIT_RATE)
                setAudioSamplingRate(COMPACT_SAMPLE_RATE)
                setOutputFile(tempRawAudioFile!!.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartTimeMs = System.currentTimeMillis()
            _isRecording.value = true
            _secondsRemaining.value = durationSeconds

            recordingJob?.cancel()
            recordingJob = scope.launch(Dispatchers.IO) {
                for (sec in durationSeconds downTo 1) {
                    _secondsRemaining.value = sec
                    delay(1000L)
                }
                stopAndEncryptRecording(fromTimer = true)
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start audio recording: ${e.message}", e)
            _recordingError.value = e.localizedMessage ?: "Audio hardware unavailable"
            cleanUpRecorder(cancelTimerJob = true)
            _isRecording.value = false
            false
        }
    }

    /**
     * Stops active audio recording, writes BOTH:
     * 1. Playable/shareable `.m4a` voice clip in `cacheDir/emergency_evidence/<incidentId>/AUD_*.m4a`
     *    so WhatsApp, Email & MMS can attach real audio evidence.
     * 2. AES-256-GCM encrypted `.enc` file + SHA-256 checksum inside tamper-evident vault.
     */
    suspend fun stopAndEncryptRecording(fromTimer: Boolean = false): EncryptedAudioMeta? =
        withContext(Dispatchers.IO + NonCancellable) {
            val rawFile = tempRawAudioFile
            val incId = activeIncidentId ?: "SOS-INCIDENT"
            val elapsedSec = if (recordingStartTimeMs > 0L) {
                ((System.currentTimeMillis() - recordingStartTimeMs) / 1000L).toInt().coerceAtLeast(1)
            } else {
                1
            }

            cleanUpRecorder(cancelTimerJob = !fromTimer)
            _isRecording.value = false
            _secondsRemaining.value = 0

            if (rawFile == null || !rawFile.exists() || rawFile.length() == 0L) {
                return@withContext null
            }

            try {
                val rawBytes = rawFile.readBytes()

                // 1. Export shareable .m4a clip to emergency_evidence/<incidentId> for WhatsApp/Email/Share
                val shareableDir = evidenceCacheDir(incId)
                val shareableFile = File(shareableDir, "AUD_${System.currentTimeMillis()}_$incId.m4a")
                shareableFile.writeBytes(rawBytes)
                _latestShareableAudioFile.value = shareableFile
                _capturedAudioPaths.value = (_capturedAudioPaths.value + shareableFile.absolutePath).distinct()

                // 2. Encrypt using AES-256-GCM into private vault
                val secretKey = getOrCreateMasterAesKey()
                val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                val cipherText = cipher.doFinal(rawBytes)

                val encryptedBytes = ByteArray(iv.size + cipherText.size)
                System.arraycopy(iv, 0, encryptedBytes, 0, iv.size)
                System.arraycopy(cipherText, 0, encryptedBytes, iv.size, cipherText.size)

                val encryptedDir = File(context.filesDir, "encrypted_evidence").apply { mkdirs() }
                val encFileName = "enc_${incId}_${System.currentTimeMillis()}.enc"
                val encFile = File(encryptedDir, encFileName)
                encFile.writeBytes(encryptedBytes)

                val sha256 = calculateSha256(encryptedBytes)

                // Erase raw vault temp file
                rawFile.writeBytes(ByteArray(rawBytes.size))
                rawFile.delete()
                tempRawAudioFile = null

                val evidenceMeta = EncryptedAudioMeta(
                    evidenceId = "EVD-${System.currentTimeMillis()}",
                    incidentId = incId,
                    filePath = encFile.absolutePath,
                    durationSeconds = elapsedSec,
                    sha256Hash = sha256,
                    fileSizeBytes = encFile.length(),
                    recordedAt = System.currentTimeMillis(),
                    isEncrypted = true
                )

                database.evidenceDao().insertEvidence(
                    EvidenceEntity(
                        evidenceId = evidenceMeta.evidenceId,
                        incidentId = evidenceMeta.incidentId,
                        userId = "USER-001",
                        fileType = "AUDIO_ENCRYPTED",
                        title = "Encrypted Audio Evidence (${elapsedSec}s)",
                        fileSizeBytes = evidenceMeta.fileSizeBytes,
                        sha256Hash = evidenceMeta.sha256Hash,
                        uploadTime = evidenceMeta.recordedAt,
                        storageLocation = evidenceMeta.filePath,
                        previewText = "AES-256-GCM Encrypted Audio • ${elapsedSec}s • SHA-256: ${sha256.take(16)}..."
                    )
                )

                _latestAudioEvidence.value = evidenceMeta
                try {
                    onRecordingFinalized?.invoke(incId, shareableFile)
                } catch (_: Exception) {
                }
                evidenceMeta
            } catch (e: Exception) {
                Log.e(tag, "Failed to encrypt audio evidence: ${e.message}", e)
                _recordingError.value = e.localizedMessage ?: "Failed to encrypt audio"
                null
            }
        }

    suspend fun decryptToTempForPlayback(encFilePath: String): File? = withContext(Dispatchers.IO) {
        val encFile = File(encFilePath)
        if (!encFile.exists()) return@withContext null

        try {
            val encBytes = encFile.readBytes()
            if (encBytes.size <= IV_LENGTH) return@withContext null

            val iv = ByteArray(IV_LENGTH)
            System.arraycopy(encBytes, 0, iv, 0, IV_LENGTH)

            val cipherTextSize = encBytes.size - IV_LENGTH
            val cipherText = ByteArray(cipherTextSize)
            System.arraycopy(encBytes, IV_LENGTH, cipherText, 0, cipherTextSize)

            val secretKey = getOrCreateMasterAesKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decryptedBytes = cipher.doFinal(cipherText)

            val tempPlayFile = File(context.cacheDir, "preview_playback_${System.currentTimeMillis()}.m4a")
            tempPlayFile.writeBytes(decryptedBytes)
            tempPlayFile.deleteOnExit()
            tempPlayFile
        } catch (e: Exception) {
            Log.e(tag, "Failed to decrypt audio for preview: ${e.message}", e)
            null
        }
    }

    private fun cleanUpRecorder(cancelTimerJob: Boolean) {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        if (cancelTimerJob) {
            recordingJob?.cancel()
        }
        recordingJob = null
    }

    /**
     * Permanently deletes local audio vault files & resets in-memory audio paths
     * when the victim confirms case resolution.
     */
    fun clearIncidentAudioState(incidentId: String) {
        try {
            val safeId = incidentId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            if (_latestShareableAudioFile.value?.absolutePath?.contains(safeId) == true ||
                _latestAudioEvidence.value?.incidentId == incidentId
            ) {
                _latestShareableAudioFile.value?.delete()
                _latestShareableAudioFile.value = null
                _latestAudioEvidence.value = null
            }
            _capturedAudioPaths.value = _capturedAudioPaths.value.filterNot {
                it.contains(safeId) || it.contains(incidentId)
            }
            File(context.cacheDir, "emergency_evidence/$safeId").deleteRecursively()
            File(context.filesDir, "encrypted_evidence").listFiles()
                ?.filter { it.name.contains(incidentId) || it.name.contains(safeId) }
                ?.forEach { it.delete() }
            File(context.filesDir, "evidence_vault").listFiles()
                ?.filter { it.name.contains(incidentId) || it.name.contains(safeId) }
                ?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun getOrCreateMasterAesKey(): SecretKey {
        val prefs = context.getSharedPreferences("rakshaone_crypto_prefs", Context.MODE_PRIVATE)
        val savedKeyHex = prefs.getString(AES_KEY_PREF, null)

        if (savedKeyHex != null) {
            val keyBytes = hexToByteArray(savedKeyHex)
            return SecretKeySpec(keyBytes, "AES")
        }

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, SecureRandom())
        val newKey = keyGen.generateKey()
        prefs.edit().putString(AES_KEY_PREF, byteArrayToHex(newKey.encoded)).apply()
        return newKey
    }

    private fun calculateSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return byteArrayToHex(hash)
    }

    private fun byteArrayToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun hexToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

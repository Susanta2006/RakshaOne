package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Battery-efficient duty-cycled acoustic threat detector:
 * Samples short 320ms ambient audio bursts every ~2 seconds instead of spinning at 100% mic duty cycle,
 * and yields automatically whenever SOS or Fake Call voice recording owns the microphone.
 */
class AcousticThreatDetector(
    private val context: Context,
    private val isMicOccupied: () -> Boolean = { false },
    private val onAcousticThreatDetected: (String) -> Unit
) {
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile
    private var isListening = false

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isListening) return
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        isListening = true
        recordingJob = scope.launch {
            val sampleRate = 8000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(2048)
            val buffer = ByteArray(minBuf)

            while (isActive && isListening) {
                if (isMicOccupied()) {
                    delay(2200)
                    continue
                }

                var burstMaxDb = 0.0
                var burstPeak = 0
                try {
                    val record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        minBuf
                    )
                    audioRecord = record
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        record.startRecording()
                        // Short ~320ms burst read
                        val endAt = System.currentTimeMillis() + 320L
                        while (isActive && isListening && !isMicOccupied() && System.currentTimeMillis() < endAt) {
                            val readSize = record.read(buffer, 0, buffer.size)
                            if (readSize > 0) {
                                var sumAmplitude = 0.0
                                var peak = 0
                                var i = 0
                                while (i < readSize - 1) {
                                    val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                    val signedSample = if (sample > 32767) sample - 65536 else sample
                                    sumAmplitude += (signedSample * signedSample)
                                    val mag = abs(signedSample)
                                    if (mag > peak) peak = mag
                                    i += 2
                                }
                                val rms = kotlin.math.sqrt(sumAmplitude / (readSize / 2).coerceAtLeast(1))
                                val db = if (rms > 0) 20 * kotlin.math.log10(rms) else 0.0
                                if (db > burstMaxDb) burstMaxDb = db
                                if (peak > burstPeak) burstPeak = peak
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AcousticDetector", "Acoustic burst error: ${e.message}")
                } finally {
                    try { audioRecord?.stop() } catch (_: Exception) {}
                    try { audioRecord?.release() } catch (_: Exception) {}
                    audioRecord = null
                }

                if (burstMaxDb > 82.0 || burstPeak > 28000) {
                    Log.w("AcousticDetector", "High decibel acoustic threat detected! RMS dB: $burstMaxDb, Peak: $burstPeak")
                    onAcousticThreatDetected("Acoustic Threat Detected (Decibel level: ${burstMaxDb.toInt()} dB)")
                    delay(12000)
                } else {
                    delay(2000)
                }
            }
            isListening = false
        }
    }

    private fun abs(v: Int): Int = if (v < 0) -v else v

    fun stopListening() {
        isListening = false
        recordingJob?.cancel()
        recordingJob = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
}

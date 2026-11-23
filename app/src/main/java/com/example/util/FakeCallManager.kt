package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

enum class FakeCallState {
    IDLE,
    SCHEDULED,
    RINGING,
    CONNECTED,
    ENDED
}

data class FakeCallConfig(
    val callerName: String = "Emergency Guardian",
    val callerNumber: String = "",
    val simulatedRole: String = "Mobile • HD Voice",
    val isAiProactive: Boolean = false,
    val autoRecordAndShare: Boolean = true,
    val isOnHold: Boolean = false,
    val incidentId: String = ""
)

class FakeCallManager(private val context: Context) {
    private val _callState = MutableStateFlow(FakeCallState.IDLE)
    val callState: StateFlow<FakeCallState> = _callState.asStateFlow()

    private val _config = MutableStateFlow(FakeCallConfig())
    val config: StateFlow<FakeCallConfig> = _config.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(0)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0)
    val callDurationSeconds: StateFlow<Int> = _callDurationSeconds.asStateFlow()

    private var scheduledJob: Job? = null
    private var ringingToneJob: Job? = null
    private var callTimerJob: Job? = null
    @Volatile
    private var ringingAudioTrack: AudioTrack? = null
    @Volatile
    private var systemRingtone: Ringtone? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    var onCallAnswered: ((FakeCallConfig) -> Unit)? = null
    var onCallEnded: ((FakeCallConfig) -> Unit)? = null

    fun setCaller(
        name: String,
        number: String,
        role: String = "Mobile • HD Voice",
        isAiProactive: Boolean = false,
        autoRecordAndShare: Boolean = true
    ) {
        _config.value = FakeCallConfig(
            callerName = name,
            callerNumber = number,
            simulatedRole = role,
            isAiProactive = isAiProactive,
            autoRecordAndShare = autoRecordAndShare,
            isOnHold = false,
            incidentId = "FAKECALL-${System.currentTimeMillis() % 100000}"
        )
    }

    fun toggleHold() {
        _config.value = _config.value.copy(isOnHold = !_config.value.isOnHold)
    }

    fun scheduleCall(delaySeconds: Int) {
        cancelCall()
        if (delaySeconds <= 0) {
            triggerRinging()
            return
        }

        _callState.value = FakeCallState.SCHEDULED
        _countdownSeconds.value = delaySeconds

        scheduledJob = scope.launch {
            for (remaining in delaySeconds downTo 1) {
                _countdownSeconds.value = remaining
                delay(1000)
            }
            _countdownSeconds.value = 0
            triggerRinging()
        }
    }

    fun triggerRinging() {
        if (_config.value.incidentId.isBlank()) {
            _config.value = _config.value.copy(incidentId = "FAKECALL-${System.currentTimeMillis() % 100000}")
        }
        _callState.value = FakeCallState.RINGING
        startRingtoneVibration()
    }

    fun answerCall() {
        stopRingtoneVibration()
        _callState.value = FakeCallState.CONNECTED
        _callDurationSeconds.value = 0
        try {
            onCallAnswered?.invoke(_config.value)
        } catch (_: Exception) {
        }

        callTimerJob?.cancel()
        callTimerJob = scope.launch {
            while (isActive && _callState.value == FakeCallState.CONNECTED) {
                delay(1000)
                _callDurationSeconds.value += 1
            }
        }
    }

    fun declineOrEndCall() {
        val currentCfg = _config.value
        val wasActive = _callState.value == FakeCallState.CONNECTED || _callState.value == FakeCallState.RINGING
        stopRingtoneVibration()
        callTimerJob?.cancel()
        scheduledJob?.cancel()
        _callState.value = FakeCallState.ENDED
        if (wasActive) {
            try {
                onCallEnded?.invoke(currentCfg)
            } catch (_: Exception) {
            }
        }

        scope.launch {
            delay(800)
            if (_callState.value == FakeCallState.ENDED) {
                _callState.value = FakeCallState.IDLE
            }
        }
    }

    fun cancelCall() {
        stopRingtoneVibration()
        scheduledJob?.cancel()
        callTimerJob?.cancel()
        val wasActive = _callState.value == FakeCallState.CONNECTED
        _callState.value = FakeCallState.IDLE
        _countdownSeconds.value = 0
        if (wasActive) {
            try {
                onCallEnded?.invoke(_config.value)
            } catch (_: Exception) {
            }
        }
    }

    private fun startRingtoneVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 900, 700, 900, 700), 0)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(longArrayOf(0, 900, 700, 900, 700), 0)
            }
        } catch (_: Exception) {}

        // Try authentic system default ringtone first; fall back to clean synthesized ring if unavailable
        val playedSystemRingtone = try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val rt = RingtoneManager.getRingtone(context, uri)
            if (rt != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    rt.isLooping = true
                }
                rt.play()
                systemRingtone = rt
                true
            } else false
        } catch (_: Exception) {
            false
        }

        if (playedSystemRingtone) return

        ringingToneJob = CoroutineScope(Dispatchers.Default).launch {
            val sampleRate = 22050
            val numSamples = 22050
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(numSamples * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            ringingAudioTrack = track

            val samples = ShortArray(numSamples)
            for (i in samples.indices) {
                val val1 = sin(2.0 * Math.PI * i / (sampleRate / 440.0))
                val val2 = sin(2.0 * Math.PI * i / (sampleRate / 480.0))
                samples[i] = ((val1 + val2) * 0.38 * Short.MAX_VALUE).toInt().toShort()
            }

            try {
                track.play()
                while (isActive && _callState.value == FakeCallState.RINGING) {
                    track.write(samples, 0, samples.size)
                    track.write(samples, 0, samples.size)
                    delay(1800)
                }
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                if (ringingAudioTrack === track) ringingAudioTrack = null
            }
        }
    }

    private fun stopRingtoneVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.cancel()
            }
        } catch (_: Exception) {}

        try {
            systemRingtone?.stop()
        } catch (_: Exception) {}
        systemRingtone = null

        ringingToneJob?.cancel()
        ringingToneJob = null
        try { ringingAudioTrack?.stop() } catch (_: Exception) {}
        try { ringingAudioTrack?.release() } catch (_: Exception) {}
        ringingAudioTrack = null
    }
}

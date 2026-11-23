package com.example.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages tactile haptic feedback and multi-stage vibration patterns
 * for emergency SOS interactions in stressful, low-visibility conditions.
 */
class EmergencyHapticManager(private val context: Context) {

    private val tag = "EmergencyHapticManager"
    private val scope = CoroutineScope(Dispatchers.Default)
    private var activeVibrationJob: Job? = null

    private val _isHapticsEnabled = MutableStateFlow(true)
    val isHapticsEnabled: StateFlow<Boolean> = _isHapticsEnabled.asStateFlow()

    private val _isVibrating = MutableStateFlow(false)
    val isVibrating: StateFlow<Boolean> = _isVibrating.asStateFlow()

    private val _lastTriggeredPattern = MutableStateFlow("NONE")
    val lastTriggeredPattern: StateFlow<String> = _lastTriggeredPattern.asStateFlow()

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize Vibrator", e)
            null
        }
    }

    val hasVibratorHardware: Boolean
        get() = try {
            vibrator?.hasVibrator() ?: false
        } catch (_: Exception) {
            false
        }

    val hasAmplitudeSupport: Boolean
        get() = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.hasAmplitudeControl() ?: false
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

    fun setHapticsEnabled(enabled: Boolean) {
        _isHapticsEnabled.value = enabled
        if (!enabled) {
            cancelVibration()
        }
    }

    /**
     * Tactile confirmation when the SOS button is first touched/tapped.
     * Crisp, sharp pulse giving immediate feedback even if the user is in the dark.
     */
    fun vibrateSosButtonTouch() {
        if (!_isHapticsEnabled.value) return
        _lastTriggeredPattern.value = "SOS_BUTTON_TOUCH"
        executeVibration(
            timings = longArrayOf(0, 75),
            amplitudes = intArrayOf(0, 255),
            fallbackDuration = 75L
        )
    }

    /**
     * Countdown tick vibration giving tactile feedback every second during countdown.
     * Progressively increases in urgency as the countdown approaches 1.
     */
    fun vibrateCountdownTick(secondsRemaining: Int) {
        if (!_isHapticsEnabled.value) return
        _lastTriggeredPattern.value = "COUNTDOWN_TICK_$secondsRemaining"

        when (secondsRemaining) {
            5, 4 -> {
                // Single clear pulse
                executeVibration(
                    timings = longArrayOf(0, 70),
                    amplitudes = intArrayOf(0, 180),
                    fallbackDuration = 70L
                )
            }
            3, 2 -> {
                // Urgent double heartbeat
                executeVibration(
                    timings = longArrayOf(0, 80, 70, 90),
                    amplitudes = intArrayOf(0, 220, 0, 240),
                    fallbackDuration = 240L
                )
            }
            1 -> {
                // Imminent trigger triple burst
                executeVibration(
                    timings = longArrayOf(0, 90, 50, 90, 50, 140),
                    amplitudes = intArrayOf(0, 255, 0, 255, 0, 255),
                    fallbackDuration = 420L
                )
            }
            else -> {
                executeVibration(
                    timings = longArrayOf(0, 60),
                    amplitudes = intArrayOf(0, 160),
                    fallbackDuration = 60L
                )
            }
        }
    }

    /**
     * Unmistakable Emergency SOS confirmation pattern.
     * International Morse code for S-O-S (··· --- ···):
     * 3 short bursts, 3 long bursts, 3 short bursts.
     * Delivers unambiguous tactile awareness in low-visibility or covert situations.
     */
    fun vibrateEmergencySosTriggered() {
        if (!_isHapticsEnabled.value) return
        _lastTriggeredPattern.value = "EMERGENCY_SOS_MORSE"

        // International Morse Code for S-O-S:
        // S: 140ms on, 90ms off, 140ms on, 90ms off, 140ms on
        // Gap: 200ms off
        // O: 350ms on, 110ms off, 350ms on, 110ms off, 350ms on
        // Gap: 200ms off
        // S: 140ms on, 90ms off, 140ms on, 90ms off, 140ms on
        val timings = longArrayOf(
            0,
            140, 90, 140, 90, 140,   // S
            200,                     // Gap
            350, 110, 350, 110, 350, // O
            200,                     // Gap
            140, 90, 140, 90, 140    // S
        )

        val amplitudes = intArrayOf(
            0,
            255, 0, 255, 0, 255,
            0,
            255, 0, 255, 0, 255,
            0,
            255, 0, 255, 0, 255
        )

        executeVibration(
            timings = timings,
            amplitudes = amplitudes,
            fallbackDuration = 2600L
        )
    }

    /**
     * Reassuring descending dual-pulse pattern confirming the SOS countdown or alert
     * was safely cancelled.
     */
    fun vibrateSosCancelled() {
        if (!_isHapticsEnabled.value) return
        _lastTriggeredPattern.value = "SOS_CANCELLED"

        // Descending intensity dual pulse
        val timings = longArrayOf(0, 120, 90, 70)
        val amplitudes = intArrayOf(0, 180, 0, 100)

        executeVibration(
            timings = timings,
            amplitudes = amplitudes,
            fallbackDuration = 280L
        )
    }

    /**
     * Periodic reminder pulse for active SOS tracking.
     */
    fun vibrateActiveSosHeartbeat() {
        if (!_isHapticsEnabled.value) return
        _lastTriggeredPattern.value = "ACTIVE_SOS_HEARTBEAT"
        executeVibration(
            timings = longArrayOf(0, 60, 50, 60),
            amplitudes = intArrayOf(0, 160, 0, 180),
            fallbackDuration = 170L
        )
    }

    /**
     * Preview test vibration pattern for settings or UI testing.
     */
    fun previewPattern(patternName: String) {
        when (patternName.uppercase()) {
            "TOUCH" -> vibrateSosButtonTouch()
            "TICK" -> vibrateCountdownTick(3)
            "MORSE_SOS", "SOS" -> vibrateEmergencySosTriggered()
            "CANCELLED" -> vibrateSosCancelled()
            "HEARTBEAT" -> vibrateActiveSosHeartbeat()
            else -> vibrateEmergencySosTriggered()
        }
    }

    /**
     * Cancels any active ongoing vibration pattern immediately.
     */
    fun cancelVibration() {
        activeVibrationJob?.cancel()
        activeVibrationJob = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
        _isVibrating.value = false
    }

    private fun executeVibration(
        timings: LongArray,
        amplitudes: IntArray,
        fallbackDuration: Long
    ) {
        val vib = vibrator ?: return
        cancelVibration()

        activeVibrationJob = scope.launch {
            _isVibrating.value = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (hasAmplitudeSupport && amplitudes.size == timings.size) {
                        VibrationEffect.createWaveform(timings, amplitudes, -1)
                    } else {
                        VibrationEffect.createWaveform(timings, -1)
                    }
                    vib.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(timings, -1)
                }

                val totalDuration = timings.sum().coerceAtLeast(fallbackDuration)
                delay(totalDuration)
            } catch (e: Exception) {
                Log.w(tag, "Vibration execution exception", e)
                try {
                    @Suppress("DEPRECATION")
                    vib.vibrate(fallbackDuration)
                } catch (_: Exception) {}
            } finally {
                _isVibrating.value = false
            }
        }
    }
}

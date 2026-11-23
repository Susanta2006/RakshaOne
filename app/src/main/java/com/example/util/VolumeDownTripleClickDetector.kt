package com.example.util

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Deterministic state machine for the hardware Volume-Down 3-press Stealth SOS trigger.
 *
 * Strict activation rules:
 * 1. Phone MUST be locked OR screen off ([isPhoneLockedOrScreenOff] == true). When unlocked & awake,
 *    hardware volume-down controls behave normally and NEVER trigger SOS.
 * 2. Requires [requiredPresses] (3) discrete, quick presses within [maxTotalWindowMs] (1500 ms),
 *    with each consecutive press arriving within [maxGapBetweenPressesMs] (650 ms).
 * 3. Rejects constant long-press holds (`isRepeat == true` OR rapid bursts `< minTapIntervalMs` / 90 ms)
 *    and clears accumulated presses until released (`>= holdReleaseGapMs` / 180 ms).
 * 4. Rejects 2 presses or sequences interrupted by a slow pause (`> maxGapBetweenPressesMs`).
 */
class VolumeDownTripleClickDetector(
    val requiredPresses: Int = 3,
    val minTapIntervalMs: Long = 90L,
    val maxGapBetweenPressesMs: Long = 650L,
    val maxTotalWindowMs: Long = 1_500L,
    val holdReleaseGapMs: Long = 180L
) {
    private val timestamps = mutableListOf<Long>()
    private var lastEventMillis: Long = -1L
    private var inConstantHold: Boolean = false

    @Synchronized
    fun reset() {
        timestamps.clear()
        lastEventMillis = -1L
        inConstantHold = false
    }

    @Synchronized
    fun currentPendingPressCount(nowMillis: Long): Int {
        timestamps.removeAll { nowMillis - it > maxTotalWindowMs }
        return timestamps.size
    }

    /**
     * Feed a discrete Volume-Down event (or key-repeat tick) into the detector.
     * @return `true` if and only if 3 valid discrete quick presses completed while locked/screen-off.
     */
    @Synchronized
    fun onVolumeDownEvent(
        nowMillis: Long,
        isPhoneLockedOrScreenOff: Boolean,
        isRepeat: Boolean = false
    ): Boolean {
        if (!isPhoneLockedOrScreenOff) {
            reset()
            return false
        }

        if (isRepeat) {
            inConstantHold = true
            timestamps.clear()
            lastEventMillis = nowMillis
            return false
        }

        val gap = if (lastEventMillis >= 0L) {
            (nowMillis - lastEventMillis).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        lastEventMillis = nowMillis

        if (inConstantHold) {
            if (gap < holdReleaseGapMs) {
                timestamps.clear()
                return false
            }
            // Button was released long enough; treat this as a fresh tap #1
            inConstantHold = false
        }

        if (gap < minTapIntervalMs) {
            // Hardware key-hold repeat burst (~30-70ms apart) -> reject constant press
            inConstantHold = true
            timestamps.clear()
            return false
        }

        if (gap > maxGapBetweenPressesMs) {
            // Pause/gap between presses exceeded threshold -> start fresh sequence from tap #1
            timestamps.clear()
        }

        timestamps.add(nowMillis)
        timestamps.removeAll { nowMillis - it > maxTotalWindowMs }

        if (timestamps.size >= requiredPresses) {
            reset()
            return true
        }
        return false
    }

    companion object {
        /**
         * Returns true ONLY when the display is off/asleep OR the device keyguard/lockscreen is active.
         * While powered completely off, Android OS cannot run background code; "turned off" screen
         * standby and lockscreen states are both covered accurately here.
         */
        fun isDeviceLockedOrScreenOff(context: Context): Boolean {
            return try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val isScreenOff = powerManager != null && !powerManager.isInteractive
                val isLocked = keyguardManager?.isKeyguardLocked == true
                isScreenOff || isLocked
            } catch (_: Exception) {
                false
            }
        }
    }
}

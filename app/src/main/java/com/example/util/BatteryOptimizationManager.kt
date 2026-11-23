package com.example.util

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Doze mode battery optimization exemption for the background location
 * and emergency SMS service to guarantee high-priority background execution during SOS alerts.
 */
class BatteryOptimizationManager(private val context: Context) {

    private val tag = "BatteryOptManager"
    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _isIgnoringBatteryOptimizations = MutableStateFlow(checkIfIgnoring())
    val isIgnoringBatteryOptimizations: StateFlow<Boolean> = _isIgnoringBatteryOptimizations.asStateFlow()

    fun checkIfIgnoring(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } else {
            true
        }
    }

    fun refresh() {
        _isIgnoringBatteryOptimizations.value = checkIfIgnoring()
    }

    /**
     * Opens Android App Details / Battery Settings cleanly without requesting the restricted
     * REQUEST_IGNORE_BATTERY_OPTIMATIONS permission that triggers sideload Play Protect blocks.
     */
    fun requestIgnoreBatteryOptimizations(activityContext: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            _isIgnoringBatteryOptimizations.value = true
            return true
        }

        if (checkIfIgnoring()) {
            _isIgnoringBatteryOptimizations.value = true
            return true
        }

        return try {
            val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activityContext.startActivity(appDetailsIntent)
            true
        } catch (e: Exception) {
            Log.w(tag, "ACTION_APPLICATION_DETAILS_SETTINGS failed, falling back to settings list", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activityContext.startActivity(fallbackIntent)
                true
            } catch (fallbackEx: Exception) {
                Log.e(tag, "Failed to launch battery optimization settings", fallbackEx)
                false
            }
        }
    }
}

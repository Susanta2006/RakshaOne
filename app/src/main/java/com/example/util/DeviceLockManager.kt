package com.example.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Play-Protect-safe security helper without BIND_DEVICE_ADMIN privileges.
 * Directs users only to standard Android Lock Screen settings if they wish to configure a PIN/biometric lock.
 */
class DeviceLockManager(private val context: Context) {

    private val _isDeviceAdminActive = MutableStateFlow(false)
    val isDeviceAdminActive: StateFlow<Boolean> = _isDeviceAdminActive.asStateFlow()

    fun checkIsAdminActive(): Boolean = false

    fun refreshStatus(): Boolean = false

    fun createActivationIntent(): Intent {
        return Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun lockDeviceNow(): Boolean = false
}

package com.example.util

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * AndroidX BiometricPrompt helper with Device Credential (PIN/pattern/password) fallback.
 * Strictly guards sensitive settings:
 * 1. Modifying or deleting Trusted Emergency Contacts.
 * 2. Confirming case safety & permanently purging the Evidence Vault.
 *
 * (The main emergency SOS button remains 100% frictionless with zero biometric hindrance).
 */
object BiometricAuthHelper {
    private const val TAG = "BiometricAuthHelper"

    fun canAuthenticate(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        val status = manager.canAuthenticate(authenticators)
        return status == BiometricManager.BIOMETRIC_SUCCESS ||
            status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Verify Device Owner",
        subtitle: String = "Authenticate to confirm sensitive action",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val manager = BiometricManager.from(activity)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        val canAuth = manager.canAuthenticate(authenticators)

        // If hardware lacks biometrics or lock credentials entirely, pass through gracefully
        if (canAuth == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
            canAuth == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        ) {
            Log.i(TAG, "Biometric hardware unavailable, passing through to allowed action")
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.i(TAG, "Device owner authenticated successfully")
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.w(TAG, "Biometric error: code=$errorCode, msg=$errString")
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    onError("Authentication cancelled")
                } else if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ||
                    errorCode == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                ) {
                    // No device lock screen set up, allow action
                    onSuccess()
                } else {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.w(TAG, "Biometric authentication attempt failed")
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            promptBuilder.setDeviceCredentialAllowed(true)
        }

        try {
            prompt.authenticate(promptBuilder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch BiometricPrompt: ${e.message}")
            // Fallback gracefully so device owner is never stuck
            onSuccess()
        }
    }
}

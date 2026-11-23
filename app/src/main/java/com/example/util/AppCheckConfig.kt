package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

enum class AppCheckActiveMode {
    DEBUG_INTENT_TOKEN,
    DEBUG_PERSISTED_TOKEN,
    RECAPTCHA_ENTERPRISE,
    DEBUG_FALLBACK_UNREGISTERED
}

data class AppCheckDiagnosticInfo(
    val mode: AppCheckActiveMode,
    val hasRecaptchaSiteKey: Boolean,
    val hasPersistedDebugToken: Boolean,
    val lastTokenError: String? = null
)

object AppCheckRuntimeState {
    @Volatile
    var activeMode: AppCheckActiveMode = AppCheckActiveMode.RECAPTCHA_ENTERPRISE
        internal set

    @Volatile
    var lastTokenError: String? = null
        internal set
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        val siteKey = resolveRecaptchaSiteKey(context)
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.setTokenAutoRefreshEnabled(true)

        if (BuildConfig.DEBUG) {
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            val prefs = context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )
            val intentDebugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!intentDebugToken.isNullOrEmpty()) {
                // Synchronous commit() is required before installing DebugAppCheckProviderFactory
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, intentDebugToken)
                    .commit()
                appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                AppCheckRuntimeState.activeMode = AppCheckActiveMode.DEBUG_INTENT_TOKEN
                Log.d(TAG, "DebugAppCheckProviderFactory installed from launch intent token.")
            } else {
                val persistedDebugToken = prefs.getString(DEBUG_SECRET_KEY, null)
                if (!persistedDebugToken.isNullOrEmpty()) {
                    appCheck.installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance()
                    )
                    AppCheckRuntimeState.activeMode = AppCheckActiveMode.DEBUG_PERSISTED_TOKEN
                    Log.d(TAG, "DebugAppCheckProviderFactory installed from persisted debug token.")
                } else if (!siteKey.isNullOrBlank()) {
                    // Standalone sideloaded debug APK on physical devices (no harness intent token):
                    // Use reCAPTCHA Enterprise attestation bound to app signing certificate SHA-1/SHA-256.
                    appCheck.installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    AppCheckRuntimeState.activeMode = AppCheckActiveMode.RECAPTCHA_ENTERPRISE
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed for standalone sideloaded build.")
                } else {
                    Log.w(TAG, "No debug token or reCAPTCHA site key; falling back to DebugAppCheckProviderFactory.")
                    appCheck.installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance()
                    )
                    AppCheckRuntimeState.activeMode = AppCheckActiveMode.DEBUG_FALLBACK_UNREGISTERED
                }
            }
        } else {
            if (!siteKey.isNullOrBlank()) {
                appCheck.installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
                AppCheckRuntimeState.activeMode = AppCheckActiveMode.RECAPTCHA_ENTERPRISE
                Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
            } else {
                Log.e(TAG, "recaptcha_site_key string resource missing or blank.")
                AppCheckRuntimeState.activeMode = AppCheckActiveMode.DEBUG_FALLBACK_UNREGISTERED
            }
        }

        // Pre-warm App Check token asynchronously so reCAPTCHA attestation is ready before first AI prompt
        appCheck.getAppCheckToken(false)
            .addOnSuccessListener {
                AppCheckRuntimeState.lastTokenError = null
                Log.d(TAG, "App Check token pre-warmed successfully (${AppCheckRuntimeState.activeMode}).")
            }
            .addOnFailureListener { ex ->
                AppCheckRuntimeState.lastTokenError = ex.localizedMessage
                Log.w(TAG, "App Check pre-warm warning (${AppCheckRuntimeState.activeMode}): ${ex.localizedMessage}")
            }
    } catch (e: Exception) {
        AppCheckRuntimeState.lastTokenError = e.localizedMessage
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

fun saveCustomAppCheckDebugToken(context: Context, token: String): Boolean {
    return try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        val cleaned = token.trim()
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
        )
        if (cleaned.isNotEmpty()) {
            prefs.edit().putString(DEBUG_SECRET_KEY, cleaned).commit()
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            AppCheckRuntimeState.activeMode = AppCheckActiveMode.DEBUG_PERSISTED_TOKEN
        } else {
            prefs.edit().remove(DEBUG_SECRET_KEY).commit()
            val siteKey = resolveRecaptchaSiteKey(context)
            if (!siteKey.isNullOrBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
                AppCheckRuntimeState.activeMode = AppCheckActiveMode.RECAPTCHA_ENTERPRISE
            }
        }
        AppCheckRuntimeState.lastTokenError = null
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed saving custom App Check debug token", e)
        false
    }
}

fun getAppCheckDiagnosticInfo(context: Context): AppCheckDiagnosticInfo {
    val siteKeyPresent = !resolveRecaptchaSiteKey(context).isNullOrBlank()
    val hasToken = try {
        if (FirebaseApp.getApps(context).isEmpty()) false
        else {
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            val prefs = context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )
            !prefs.getString(DEBUG_SECRET_KEY, null).isNullOrEmpty()
        }
    } catch (_: Exception) {
        false
    }
    return AppCheckDiagnosticInfo(
        mode = AppCheckRuntimeState.activeMode,
        hasRecaptchaSiteKey = siteKeyPresent,
        hasPersistedDebugToken = hasToken,
        lastTokenError = AppCheckRuntimeState.lastTokenError
    )
}

private fun resolveRecaptchaSiteKey(context: Context): String? {
    return try {
        val resId = context.resources.getIdentifier(
            RECAPTCHA_SITE_KEY_RES,
            "string",
            context.packageName
        )
        if (resId != 0) context.getString(resId).takeIf { it.isNotBlank() } else null
    } catch (_: Exception) {
        null
    }
}

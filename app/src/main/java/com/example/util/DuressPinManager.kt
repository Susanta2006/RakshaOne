package com.example.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages Duress PIN configuration (silent SOS trigger) and Decoy Cancel PIN (default "0000")
 * so coerced cancellation visually enters standby while secretly keeping GPS & evidence vault active.
 */
class DuressPinManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("rakshaone_duress_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_DECOY_CANCEL_PIN = "0000"
    }

    fun getDuressPin(): String {
        return prefs.getString("duress_pin", "") ?: ""
    }

    fun setDuressPin(pin: String) {
        prefs.edit().putString("duress_pin", pin.trim()).apply()
    }

    fun getDecoyCancelPin(): String {
        return prefs.getString("decoy_cancel_pin", DEFAULT_DECOY_CANCEL_PIN) ?: DEFAULT_DECOY_CANCEL_PIN
    }

    fun setDecoyCancelPin(pin: String) {
        val clean = pin.trim()
        if (clean.isNotEmpty()) {
            prefs.edit().putString("decoy_cancel_pin", clean).apply()
        }
    }

    fun isDecoyCancelPin(enteredPin: String): Boolean {
        val clean = enteredPin.trim()
        return clean.isNotEmpty() && clean == getDecoyCancelPin()
    }

    fun isDuressPinConfigured(): Boolean {
        return getDuressPin().isNotBlank()
    }

    fun verifyAndTriggerIfDuress(enteredPin: String, onDuressTriggered: () -> Unit): Boolean {
        val configured = getDuressPin()
        if (configured.isNotBlank() && enteredPin.trim() == configured) {
            onDuressTriggered()
            return true
        }
        return false
    }
}

package com.example.util

/**
 * Canonical phone normalization for matching Trusted Contacts across RakshaOne
 * direct push directory, WhatsApp JID routing, and SMS fallback without duplicates.
 */
object PhoneNormalizer {

    /**
     * Strips formatting characters and produces a stable digits-only canonical key.
     * Uses the trailing 10 subscriber digits when >= 10 digits exist so "+91 81190-98684",
     * "08119098684", "918119098684", and "8119098684" all resolve to the same app user.
     */
    fun canonicalPhoneKey(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

    /**
     * Returns all valid digits-only directory lookup keys (canonical 10-digit suffix plus
     * full digits when length > 10) safe for Firestore document IDs (`^[a-zA-Z0-9_-]+$`).
     */
    fun directoryLookupKeys(rawPhone: String): List<String> {
        val digits = rawPhone.filter { it.isDigit() }
        if (digits.length < 8) return emptyList()
        val keys = LinkedHashSet<String>()
        if (digits.length >= 10) {
            keys.add(digits.takeLast(10))
        }
        keys.add(digits)
        return keys.toList()
    }

    fun isValidPhoneNumber(rawPhone: String): Boolean {
        return rawPhone.filter { it.isDigit() }.length >= 10
    }

    fun formatForDisplay(rawPhone: String): String {
        val trimmed = rawPhone.trim()
        val digits = trimmed.filter { it.isDigit() }
        return when {
            trimmed.startsWith("+") && digits.length >= 10 -> "+$digits"
            digits.length == 10 -> "+91$digits"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            digits.length > 10 -> "+$digits"
            else -> trimmed
        }
    }
}

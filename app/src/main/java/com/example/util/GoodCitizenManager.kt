package com.example.util

import android.content.Context
import android.content.SharedPreferences
import com.example.data.AppDatabase
import com.example.data.model.GoodCitizenProfile
import com.example.data.model.PointsLedgerEntity
import com.example.data.model.RelationshipTrustRules
import com.example.data.repository.FirestoreRiskAndRescueRepository
import com.example.risk.CitizenVerificationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Manages Good Citizen identity, zero-cost phone verification (Google Play Phone Hint / Self-SMS / WhatsApp),
 * Core Family relationship trust bonuses (+15 per verified Core Family member),
 * universal inclusive Responder Duty unlock, and Trust Points ledger.
 */
class GoodCitizenManager(
    context: Context,
    private val firestoreRepository: FirestoreRiskAndRescueRepository? = null
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("rakshaone_good_citizen_prefs_v2", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val pointsDao by lazy { AppDatabase.getDatabase(appContext).pointsLedgerDao() }

    private val _citizenProfile = MutableStateFlow(loadProfile())
    val citizenProfile: StateFlow<GoodCitizenProfile> = _citizenProfile.asStateFlow()

    fun getOrCreateEndorsementCode(): String {
        val existing = prefs.getString("endorsement_code", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "RK-${(1000..9999).random()}-${('A'..'Z').random()}${('A'..'Z').random()}"
        prefs.edit().putString("endorsement_code", generated).apply()
        return generated
    }

    fun getVerifiedPhoneNumber(): String = prefs.getString("verified_phone_number", "") ?: ""

    private fun loadProfile(): GoodCitizenProfile {
        val statusRaw = prefs.getString("verification_status", CitizenVerificationStatus.UNVERIFIED.name)
        val status = CitizenVerificationStatus.safeParse(statusRaw)
        val verified = status == CitizenVerificationStatus.VERIFIED
        val isPhoneVerified = prefs.getBoolean("is_phone_verified", false)
        val workEmail = prefs.getString("work_email_badge", "") ?: ""
        val linkedin = prefs.getString("linkedin_badge", "") ?: ""
        val score = prefs.getInt("trust_score", 0).coerceAtLeast(0)
        val accepted = prefs.getInt("accepted_count", 0).coerceAtLeast(0)
        val completed = prefs.getInt("completed_count", 0).coerceAtLeast(0)
        val declined = prefs.getInt("declined_count", 0).coerceAtLeast(0)
        val isVolunteer = (verified || isPhoneVerified) && prefs.getBoolean("is_volunteer_active", false)
        val endorserUid = prefs.getString("endorser_uid", null)
        val displayName = prefs.getString("display_name", "") ?: ""
        val code = getOrCreateEndorsementCode()
        val citizenCode = prefs.getString("citizen_code", null) ?: run {
            val c = "${(1000..9999).random()}"
            prefs.edit().putString("citizen_code", c).apply()
            c
        }

        return GoodCitizenProfile(
            userId = "CITIZEN-$citizenCode",
            displayName = displayName,
            isIdVerified = verified,
            isPhoneVerified = isPhoneVerified,
            workEmailBadge = workEmail,
            linkedinBadge = linkedin,
            verificationStatus = status,
            verificationDocType = if (isPhoneVerified) "Active SIM Verified" else "",
            docLastFourHint = getVerifiedPhoneNumber().takeLast(4),
            trustScore = score,
            acceptedHelpsCount = accepted,
            completedHelpsCount = completed,
            declinedHelpsCount = declined,
            isVolunteerActive = isVolunteer,
            badgeLevel = calculateBadge(status, score, completed, isPhoneVerified),
            endorserUid = endorserUid,
            endorsementCode = code
        )
    }

    private fun calculateBadge(
        status: CitizenVerificationStatus,
        score: Int,
        completed: Int,
        isPhoneVerified: Boolean = false
    ): String {
        if (status != CitizenVerificationStatus.VERIFIED) {
            return if (isPhoneVerified) {
                "Verified Citizen"
            } else if (status == CitizenVerificationStatus.PENDING) {
                "Pending Circle Verification"
            } else {
                "Community Member"
            }
        }
        return when {
            score >= 120 && completed >= 3 -> "Platinum Protector"
            score >= 60 && completed >= 1 -> "Golden Shield Guardian"
            else -> "Verified Community Responder"
        }
    }

    fun updateIdentityName(displayName: String) {
        val clean = displayName.trim().take(100)
        prefs.edit().putString("display_name", clean).apply()
        _citizenProfile.value = _citizenProfile.value.copy(displayName = clean)
    }

    /**
     * Zero-cost verification via Google Play Services PhoneNumberHintIntent (active SIM).
     */
    fun verifyPhoneByPlayHint(
        phoneNumber: String,
        circleContactCount: Int = 0,
        coreFamilyCount: Int = 0
    ): Boolean {
        val clean = PhoneNormalizer.canonicalPhoneKey(phoneNumber)
        if (clean.length < 7) return false
        val formatted = if (phoneNumber.trim().startsWith("+")) phoneNumber.trim() else PhoneNormalizer.formatForDisplay(phoneNumber)

        val alreadyVerified = prefs.getBoolean("is_phone_verified", false)
        val bonus = if (alreadyVerified) 0 else 15
        val current = _citizenProfile.value
        val shouldUnlockResponder = coreFamilyCount >= 1 || circleContactCount >= 2 || current.endorserUid != null
        val nextStatus = if (shouldUnlockResponder) CitizenVerificationStatus.VERIFIED else current.verificationStatus
        val newScore = current.trustScore + bonus

        prefs.edit()
            .putBoolean("is_phone_verified", true)
            .putString("verified_phone_number", formatted)
            .putInt("trust_score", newScore)
            .apply()
        if (shouldUnlockResponder) {
            prefs.edit().putString("verification_status", CitizenVerificationStatus.VERIFIED.name).apply()
        }

        val updated = current.copy(
            isPhoneVerified = true,
            isIdVerified = shouldUnlockResponder || current.isIdVerified,
            verificationStatus = nextStatus,
            verificationDocType = "Active SIM Verified",
            docLastFourHint = formatted.takeLast(4),
            trustScore = newScore,
            badgeLevel = calculateBadge(nextStatus, newScore, current.completedHelpsCount, true)
        )
        _citizenProfile.value = updated

        if (bonus > 0) {
            appendLedgerEntry(updated.userId, bonus, newScore, "GOOGLE_PHONE_HINT_VERIFIED", null)
        }
        scope.launch {
            try { firestoreRepository?.syncCitizenProfile(updated) } catch (_: Exception) {}
        }
        return true
    }

    /**
     * Awards +15 Trust Points per verified Core Family member (Father, Mother, Brother, Sister, Spouse), deduplicated.
     */
    fun addCoreFamilyTrustBonus(contactKey: String, relationship: String): Boolean {
        if (!RelationshipTrustRules.isCoreFamily(relationship)) return false
        val dedupKey = "core_family_bonus_${contactKey.trim().lowercase(Locale.US)}"
        if (prefs.getBoolean(dedupKey, false)) return false

        val current = _citizenProfile.value
        val bonus = 15
        val newScore = current.trustScore + bonus
        val unlockResponder = current.isPhoneVerified || current.verificationStatus == CitizenVerificationStatus.VERIFIED
        val nextStatus = if (unlockResponder) CitizenVerificationStatus.VERIFIED else current.verificationStatus

        prefs.edit()
            .putBoolean(dedupKey, true)
            .putInt("trust_score", newScore)
            .apply()
        if (unlockResponder) {
            prefs.edit().putString("verification_status", CitizenVerificationStatus.VERIFIED.name).apply()
        }

        val updated = current.copy(
            isIdVerified = unlockResponder || current.isIdVerified,
            verificationStatus = nextStatus,
            trustScore = newScore,
            badgeLevel = calculateBadge(nextStatus, newScore, current.completedHelpsCount, current.isPhoneVerified)
        )
        _citizenProfile.value = updated
        appendLedgerEntry(
            citizenUid = updated.userId,
            delta = bonus,
            balanceAfter = newScore,
            reasonCode = "CORE_FAMILY_GUARDIAN_${RelationshipTrustRules.normalizeRole(relationship).uppercase(Locale.US)}",
            incidentId = null
        )
        scope.launch {
            try { firestoreRepository?.syncCitizenProfile(updated) } catch (_: Exception) {}
        }
        return true
    }

    fun generateSelfSmsVerificationPin(): String {
        val pin = (100000..999999).random().toString()
        prefs.edit().putString("pending_self_sms_pin", pin).apply()
        return pin
    }

    @JvmOverloads
    fun verifySelfSmsPin(
        enteredPin: String,
        circleContactCount: Int = 0,
        coreFamilyCount: Int = 0
    ): Boolean {
        val savedPin = prefs.getString("pending_self_sms_pin", null)
        val cleanInput = enteredPin.trim()
        if (cleanInput.length != 6 || (savedPin != null && cleanInput != savedPin)) {
            return false
        }

        prefs.edit()
            .putBoolean("is_phone_verified", true)
            .remove("pending_self_sms_pin")
            .apply()

        val current = _citizenProfile.value
        val shouldAutoUnlock = coreFamilyCount >= 1 || circleContactCount >= 2 || current.endorserUid != null
        val nextStatus = if (shouldAutoUnlock) CitizenVerificationStatus.VERIFIED else current.verificationStatus
        val nextVerified = shouldAutoUnlock || current.isIdVerified

        if (shouldAutoUnlock) {
            prefs.edit().putString("verification_status", CitizenVerificationStatus.VERIFIED.name).apply()
        }

        val bonus = 15
        val newScore = current.trustScore + bonus
        prefs.edit().putInt("trust_score", newScore).apply()

        val updated = current.copy(
            isPhoneVerified = true,
            isIdVerified = nextVerified,
            verificationStatus = nextStatus,
            trustScore = newScore,
            badgeLevel = calculateBadge(nextStatus, newScore, current.completedHelpsCount, true)
        )
        _citizenProfile.value = updated

        appendLedgerEntry(updated.userId, bonus, newScore, "PHONE_SMS_VERIFIED", null)
        scope.launch {
            try { firestoreRepository?.syncCitizenProfile(updated) } catch (_: Exception) {}
        }
        return true
    }

    fun verifyPeerByToken(peerUid: String, token: String): Boolean {
        if (peerUid.isBlank() || token.isBlank()) return false
        prefs.edit()
            .putBoolean("is_phone_verified", true)
            .putString("verification_status", CitizenVerificationStatus.VERIFIED.name)
            .putString("endorser_uid", peerUid)
            .apply()

        val current = _citizenProfile.value
        val bonus = 15
        val newScore = current.trustScore + bonus
        prefs.edit().putInt("trust_score", newScore).apply()

        val updated = current.copy(
            isPhoneVerified = true,
            isIdVerified = true,
            verificationStatus = CitizenVerificationStatus.VERIFIED,
            endorserUid = peerUid,
            trustScore = newScore,
            badgeLevel = calculateBadge(CitizenVerificationStatus.VERIFIED, newScore, current.completedHelpsCount, true)
        )
        _citizenProfile.value = updated

        appendLedgerEntry(updated.userId, bonus, newScore, "WHATSAPP_CIRCLE_VERIFIED", null)
        scope.launch {
            try { firestoreRepository?.syncCitizenProfile(updated) } catch (_: Exception) {}
        }
        return true
    }

    @JvmOverloads
    fun checkAutoVerificationFromCircle(contactCount: Int, coreFamilyCount: Int = 0): Boolean {
        val current = _citizenProfile.value
        val qualifiedByCircle = coreFamilyCount >= 1 || contactCount >= 2
        if (qualifiedByCircle && current.verificationStatus != CitizenVerificationStatus.VERIFIED) {
            prefs.edit()
                .putString("verification_status", CitizenVerificationStatus.VERIFIED.name)
                .apply()

            val bonus = 15
            val newScore = current.trustScore + bonus
            prefs.edit().putInt("trust_score", newScore).apply()

            val updated = current.copy(
                isIdVerified = true,
                verificationStatus = CitizenVerificationStatus.VERIFIED,
                trustScore = newScore,
                badgeLevel = calculateBadge(CitizenVerificationStatus.VERIFIED, newScore, current.completedHelpsCount, current.isPhoneVerified)
            )
            _citizenProfile.value = updated

            appendLedgerEntry(updated.userId, bonus, newScore, "CIRCLE_AUTO_VERIFIED", null)
            scope.launch {
                try { firestoreRepository?.syncCitizenProfile(updated) } catch (_: Exception) {}
            }
            return true
        }
        return false
    }

    fun redeemReferralCode(code: String): Result<String> {
        val clean = code.trim().uppercase(Locale.US)
        if (clean.length < 5) {
            return Result.failure(IllegalArgumentException("Please enter a valid referral code (e.g. RK-XXXX-XX)"))
        }

        val current = _citizenProfile.value
        if (clean == current.endorsementCode) {
            return Result.failure(IllegalArgumentException("Cannot use your own referral code! Share it with a friend."))
        }

        prefs.edit()
            .putString("verification_status", CitizenVerificationStatus.VERIFIED.name)
            .putString("endorser_uid", clean)
            .apply()

        val bonus = 15
        val newScore = current.trustScore + bonus
        prefs.edit().putInt("trust_score", newScore).apply()

        val updated = current.copy(
            isIdVerified = true,
            verificationStatus = CitizenVerificationStatus.VERIFIED,
            endorserUid = clean,
            trustScore = newScore,
            badgeLevel = calculateBadge(CitizenVerificationStatus.VERIFIED, newScore, current.completedHelpsCount, current.isPhoneVerified)
        )
        _citizenProfile.value = updated

        appendLedgerEntry(updated.userId, bonus, newScore, "REFERRAL_CODE_VERIFIED", null)
        scope.launch {
            try { firestoreRepository?.syncCitizenProfile(updated) } catch (_: Exception) {}
        }

        return Result.success("Referral verified! Community Responder duty unlocked.")
    }

    fun setWorkEmailBadge(email: String) {
        val clean = email.trim().lowercase(Locale.US).take(120)
        prefs.edit().putString("work_email_badge", clean).apply()
        _citizenProfile.value = _citizenProfile.value.copy(workEmailBadge = clean)
    }

    fun setLinkedinBadge(url: String) {
        val clean = url.trim().take(150)
        prefs.edit().putString("linkedin_badge", clean).apply()
        _citizenProfile.value = _citizenProfile.value.copy(linkedinBadge = clean)
    }

    fun applyPeerEndorsementVerification(endorserUid: String): GoodCitizenProfile {
        redeemReferralCode(endorserUid)
        return _citizenProfile.value
    }

    fun submitKycMetadata(docType: String, lastFourHint: String): GoodCitizenProfile {
        return checkAutoVerificationFromCircle(2).let { _citizenProfile.value }
    }

    fun recordAcceptedHelp(incidentId: String? = null): GoodCitizenProfile {
        return applyTrustDelta(15, "RESCUE_ACCEPTED", incidentId, acceptedIncrement = 1)
    }

    fun recordCompletedPickup(incidentId: String? = null): GoodCitizenProfile {
        return applyTrustDelta(25, "RESCUE_COMPLETED", incidentId, completedIncrement = 1)
    }

    fun recordDeclinedHelp(incidentId: String? = null): GoodCitizenProfile {
        return applyTrustDelta(-5, "RESCUE_DECLINED", incidentId, declinedIncrement = 1)
    }

    private fun applyTrustDelta(
        delta: Int,
        reasonCode: String,
        incidentId: String?,
        acceptedIncrement: Int = 0,
        completedIncrement: Int = 0,
        declinedIncrement: Int = 0
    ): GoodCitizenProfile {
        val current = _citizenProfile.value
        val newScore = (current.trustScore + delta).coerceAtLeast(0)
        val newAccepted = current.acceptedHelpsCount + acceptedIncrement
        val newCompleted = current.completedHelpsCount + completedIncrement
        val newDeclined = current.declinedHelpsCount + declinedIncrement
        val newBadge = calculateBadge(current.verificationStatus, newScore, newCompleted, current.isPhoneVerified)

        prefs.edit()
            .putInt("trust_score", newScore)
            .putInt("accepted_count", newAccepted)
            .putInt("completed_count", newCompleted)
            .putInt("declined_count", newDeclined)
            .apply()

        val updated = current.copy(
            trustScore = newScore,
            acceptedHelpsCount = newAccepted,
            completedHelpsCount = newCompleted,
            declinedHelpsCount = newDeclined,
            badgeLevel = newBadge
        )
        _citizenProfile.value = updated

        appendLedgerEntry(updated.userId, delta, newScore, reasonCode, incidentId)
        return updated
    }

    private fun appendLedgerEntry(
        citizenUid: String,
        delta: Int,
        balanceAfter: Int,
        reasonCode: String,
        incidentId: String?
    ) {
        scope.launch {
            try {
                pointsDao.insertEntry(
                    PointsLedgerEntity(
                        citizenUid = citizenUid,
                        delta = delta,
                        balanceAfter = balanceAfter,
                        reasonCode = reasonCode,
                        relatedIncidentId = incidentId
                    )
                )
                firestoreRepository?.recordPointsLedgerEntry(
                    delta = delta,
                    balanceAfter = balanceAfter,
                    reasonCode = reasonCode,
                    relatedIncidentId = incidentId
                )
            } catch (_: Exception) {
            }
        }
    }

    fun setVolunteerActive(active: Boolean): Boolean {
        val current = _citizenProfile.value
        val isEligible = current.verificationStatus == CitizenVerificationStatus.VERIFIED || current.isIdVerified
        if (active && !isEligible) {
            prefs.edit().putBoolean("is_volunteer_active", false).apply()
            _citizenProfile.value = current.copy(isVolunteerActive = false)
            return false
        }
        prefs.edit().putBoolean("is_volunteer_active", active).apply()
        _citizenProfile.value = current.copy(isVolunteerActive = active)
        scope.launch {
            try {
                firestoreRepository?.syncCitizenProfile(_citizenProfile.value)
            } catch (_: Exception) {
            }
        }
        return true
    }
}

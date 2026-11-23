package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.AppDatabase
import com.example.data.model.IncidentEntity
import com.example.data.repository.SafetyRepository
import com.example.service.CriticalSmsDispatchWorker
import com.example.service.FakeIncomingCallService
import com.example.service.R3CriticalMonitorWorker
import com.example.util.AiSovereigntyTier
import com.example.util.FakeCallManager
import com.example.util.FakeCallState
import com.example.util.SafetyTelemetryFeatures
import com.example.util.TfliteSafetyBrain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SovereignAiAndAutoVaultTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("raksha_tflite_ai_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun tfliteModelAssetIsValidFlatBuffer() {
        val bytes = context.assets.open("raksha_safety_brain.tflite").use { it.readBytes() }
        assertTrue("Expected non-empty TFLite model", bytes.size > 1024)
        val magic = String(bytes, 4, 4, Charsets.US_ASCII)
        assertEquals("TFL3", magic)
    }

    @Test
    fun tfliteSafetyBrainPredictsPreIncidentRiskAndEvolvesSovereignty() = runBlocking {
        val brain = TfliteSafetyBrain(context)
        try {
            val safeFeatures = SafetyTelemetryFeatures(
                lateNightRisk = 0f,
                unfamiliarZone = 0f,
                riskZoneProximity = 0f,
                lowBattery = 0f,
                offlineMode = 0f,
                jerkMotion = 0f,
                darkEnvironment = 0f,
                isolatedArea = 0f,
                abnormalStopOrSpeed = 0f,
                acousticDistress = 0f
            )
            val safeEval = brain.evaluateFeatures(safeFeatures)

            val dangerousFeatures = SafetyTelemetryFeatures(
                lateNightRisk = 0.95f,
                unfamiliarZone = 0.85f,
                riskZoneProximity = 0.80f,
                lowBattery = 0.70f,
                offlineMode = 0.25f,
                jerkMotion = 0.85f,
                darkEnvironment = 0.90f,
                isolatedArea = 0.90f,
                abnormalStopOrSpeed = 0.75f,
                acousticDistress = 0.90f
            )
            val dangerousEval = brain.evaluateFeatures(dangerousFeatures)

            assertTrue(
                "Dangerous score (${dangerousEval.riskScore}) should significantly exceed safe score (${safeEval.riskScore})",
                dangerousEval.riskScore > safeEval.riskScore + 25
            )
            assertTrue(
                "Expected elevated proactive fake call or critical probability under severe cues",
                (dangerousEval.proactiveFakeCallProb + dangerousEval.criticalDistressProb) >
                    (safeEval.proactiveFakeCallProb + safeEval.criticalDistressProb)
            )

            // Calibrate via confirmed interactions until Tier 3 Sovereign Protector unlocks
            repeat(6) {
                brain.recordFeedback(wasGenuineThreat = true)
            }
            val updated = brain.evaluateFeatures(dangerousFeatures)
            assertEquals(AiSovereigntyTier.TIER_3_SOVEREIGN_PROTECTOR, updated.sovereigntyTier)
            assertTrue(updated.confirmedInteractions >= 6)

            // Safe calibration decreases sensitivity cleanly without crashing
            val beforeSafeBias = brain.exportSnapshot().calibrationBiases.sum()
            brain.evaluateFeatures(dangerousFeatures)
            brain.recordFeedback(wasGenuineThreat = false)
            val afterSafeBias = brain.exportSnapshot().calibrationBiases.sum()
            assertTrue(afterSafeBias <= beforeSafeBias)
        } finally {
            brain.close()
        }
    }

    @Test
    fun criticalSmsWorkerAuthorizesSosFakeCallGuardAndTripAnomaly() {
        assertTrue(CriticalSmsDispatchWorker.isAuthorizedCriticalContext("R3_CRITICAL", "USER_EXPLICIT_INSTANT_SOS"))
        assertTrue(CriticalSmsDispatchWorker.isAuthorizedCriticalContext("R2_HIGH", "FAKE_CALL_GUARD_AUTO"))
        assertTrue(CriticalSmsDispatchWorker.isAuthorizedCriticalContext("R2_HIGH", "TRIP_ANOMALY_AUTO"))
        assertFalse(CriticalSmsDispatchWorker.isAuthorizedCriticalContext("R0_NORMAL", "IDLE_CHECK"))
    }

    @Test
    fun fakeCallManagerHandlesProactiveAiAnswerHoldAndEndLifecycle() {
        val manager = FakeCallManager(context)
        manager.setCaller(
            name = "Maa",
            number = "+91 98111 22334",
            isAiProactive = true,
            autoRecordAndShare = true
        )
        assertNotNull(manager.config.value)
        assertTrue(manager.config.value.isAiProactive)
        assertTrue(manager.config.value.autoRecordAndShare)

        manager.triggerRinging()
        assertEquals(FakeCallState.RINGING, manager.callState.value)

        manager.answerCall()
        assertEquals(FakeCallState.CONNECTED, manager.callState.value)
        assertFalse(manager.config.value.isOnHold)

        manager.toggleHold()
        assertTrue(manager.config.value.isOnHold)

        manager.declineOrEndCall()
        assertEquals(FakeCallState.ENDED, manager.callState.value)
        manager.cancelCall()
        assertEquals(FakeCallState.IDLE, manager.callState.value)
    }

    @Test
    fun surakshaAiNeverRepeatsRepliesAndHandlesEmotionalAssuranceAndSafeCheckIn() {
        val firstPanicReply = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse(
            query = "I am scared please calm me down",
            primarySosNumber = "112",
            languageName = "English",
            previousAssistantReplies = emptyList()
        )
        val secondPanicReply = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse(
            query = "Still feeling scared, please reassure me",
            primarySosNumber = "112",
            languageName = "English",
            previousAssistantReplies = listOf(firstPanicReply)
        )
        assertTrue(firstPanicReply != secondPanicReply)
        assertFalse(firstPanicReply.contains("Live Context:"))

        val safeReply = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse(
            query = "I reached home safely thank you",
            primarySosNumber = "112",
            languageName = "English",
            previousAssistantReplies = listOf(firstPanicReply, secondPanicReply)
        )
        assertTrue(safeReply.contains("safe", ignoreCase = true) || safeReply.contains("relief", ignoreCase = true))
    }

    @Test
    fun voiceGuardianUsesHumanizedGenderPitchAndBreathChunking() {
        val maleNative = com.example.util.VoiceGuardianManager.computeAcousticProfile(wantMale = true, matchedNativeGender = true)
        val femaleNative = com.example.util.VoiceGuardianManager.computeAcousticProfile(wantMale = false, matchedNativeGender = true)
        val maleFallback = com.example.util.VoiceGuardianManager.computeAcousticProfile(wantMale = true, matchedNativeGender = false)
        assertTrue(maleNative.pitch in 0.90f..0.99f)
        assertTrue(femaleNative.pitch in 0.98f..1.04f)
        assertTrue(maleFallback.pitch in 0.80f..0.90f)
        assertTrue(maleFallback.pitch < femaleNative.pitch)

        val chunks = com.example.util.VoiceGuardianManager.humanizeAndChunkForSpeech(
            "[Hindi Mode] **Take a deep breath.** https://maps.google.com/?q=1,2\n1. Stay on a bright street.\n2. Keep Fake Call Shield ready for quick exit — I am right here with you walking home safely together."
        )
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.none { it.contains("https://") || it.contains("[Hindi Mode]") || it.contains("**") })
    }

    @Test
    fun fakeIncomingCallServiceIntentsAndManagerLifecycle() {
        val manager = FakeCallManager(context)
        manager.setCaller(
            name = "Maa",
            number = "+91 98111 22334",
            role = "AI Guard • HD Voice",
            isAiProactive = true,
            autoRecordAndShare = true
        )
        assertEquals("Maa", manager.config.value.callerName)
        assertTrue(manager.config.value.isAiProactive)

        manager.triggerRinging()
        assertEquals(FakeCallState.RINGING, manager.callState.value)

        manager.answerCall()
        assertEquals(FakeCallState.CONNECTED, manager.callState.value)

        manager.declineOrEndCall()
        assertTrue(
            manager.callState.value == FakeCallState.ENDED ||
                manager.callState.value == FakeCallState.IDLE
        )
    }

    @Test
    fun r3CriticalMonitorWorkerDetectsR3StateTracksIdempotencyAndSchedulesWorkManager() = runBlocking {
        assertTrue(R3CriticalMonitorWorker.isR3CriticalState("R3_CRITICAL", null, null))
        assertTrue(R3CriticalMonitorWorker.isR3CriticalState(null, "CRITICAL", "CREATED"))
        assertFalse(R3CriticalMonitorWorker.isR3CriticalState("R1_LOW", "CRITICAL", "RESOLVED"))

        val testIncidentId = "INC-R3-AUTO-991"
        assertFalse(R3CriticalMonitorWorker.hasIncidentBeenCapturedAndUploaded(context, testIncidentId))
        R3CriticalMonitorWorker.markIncidentCapturedAndUploaded(context, testIncidentId)
        assertTrue(R3CriticalMonitorWorker.hasIncidentBeenCapturedAndUploaded(context, testIncidentId))

        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Already initialized
        }
        R3CriticalMonitorWorker.schedulePeriodicMonitor(context)
        R3CriticalMonitorWorker.triggerImmediateMonitor(context, "INC-R3-AUTO-992", "R3_CRITICAL", "TEST_R3")

        val db = AppDatabase.getDatabase(context)
        db.incidentDao().insertIncident(
            IncidentEntity(
                incidentId = "INC-R3-AUTO-992",
                userId = "USER-TEST",
                type = "SOS",
                priority = "CRITICAL",
                status = "CREATED",
                latitude = 23.83,
                longitude = 91.28,
                accuracyMeters = 5f,
                locationDescription = "Test Spot",
                description = "Auto R3 test",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                assignedUnit = null,
                operatorNotes = "R3_CRITICAL"
            )
        )
        val active = db.incidentDao().getActiveIncidentsOnce()
        assertTrue(active.any { it.incidentId == "INC-R3-AUTO-992" && it.priority == "CRITICAL" })
    }
}

package com.example.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.InterpreterApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.exp

enum class AiSovereigntyTier(
    val level: Int,
    val title: String,
    val authoritySummary: String
) {
    TIER_1_ADVISORY(
        level = 1,
        title = "Tier 1 • Advisory",
        authoritySummary = "Learns daily routine & advises precautions before risk escalates"
    ),
    TIER_2_PROACTIVE_GUARD(
        level = 2,
        title = "Tier 2 • Proactive Guard",
        authoritySummary = "Pre-arms encrypted audio/location vault & launches Fake Call on high-risk trajectories"
    ),
    TIER_3_SOVEREIGN_PROTECTOR(
        level = 3,
        title = "Tier 3 • Sovereign Protector",
        authoritySummary = "Autonomous pre-threat Fake Call + silent vault + instant guardian alert (112 stays R3-explicit)"
    )
}

enum class AutonomousAiDecision {
    NONE,
    ELEVATE_WATCH,
    PROACTIVE_FAKE_CALL,
    PRE_ARM_VAULT_AND_GUARDIAN_ALERT
}

data class SafetyTelemetryFeatures(
    val lateNightRisk: Float = 0f,
    val unfamiliarZone: Float = 0f,
    val riskZoneProximity: Float = 0f,
    val lowBattery: Float = 0f,
    val offlineMode: Float = 0f,
    val jerkMotion: Float = 0f,
    val darkEnvironment: Float = 0f,
    val isolatedArea: Float = 0f,
    val abnormalStopOrSpeed: Float = 0f,
    val acousticDistress: Float = 0f
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        lateNightRisk.coerceIn(0f, 1f),
        unfamiliarZone.coerceIn(0f, 1f),
        riskZoneProximity.coerceIn(0f, 1f),
        lowBattery.coerceIn(0f, 1f),
        offlineMode.coerceIn(0f, 1f),
        jerkMotion.coerceIn(0f, 1f),
        darkEnvironment.coerceIn(0f, 1f),
        isolatedArea.coerceIn(0f, 1f),
        abnormalStopOrSpeed.coerceIn(0f, 1f),
        acousticDistress.coerceIn(0f, 1f)
    )
}

data class TfliteInferenceResult(
    val riskScore: Int = 10,
    val safeProb: Float = 1f,
    val watchProb: Float = 0f,
    val proactiveFakeCallProb: Float = 0f,
    val criticalDistressProb: Float = 0f,
    val predictedTrajectoryDelta: Int = 0,
    val decision: AutonomousAiDecision = AutonomousAiDecision.NONE,
    val sovereigntyTier: AiSovereigntyTier = AiSovereigntyTier.TIER_1_ADVISORY,
    val autonomousGuardEnabled: Boolean = true,
    val modelBackend: String = "LiteRT-CPU",
    val confirmedInteractions: Int = 0,
    val explanation: String = "TFLite Safety Brain active",
    val updatedAt: Long = System.currentTimeMillis()
)

data class AiLearningStateSnapshot(
    val sovereigntyTier: Int = 1,
    val autonomousGuardEnabled: Boolean = true,
    val confirmedSafeCount: Int = 0,
    val confirmedThreatCount: Int = 0,
    val fakeCallsTriggeredByAi: Int = 0,
    val calibrationBiases: List<Float> = List(10) { 0f },
    val updatedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Sovereign On-Device Neural Safety Brain powered by Google AI Edge LiteRT (`raksha_safety_brain.tflite`).
 * - Runs 10->16(ReLU)->4(Softmax) neural inference on a single-thread background dispatcher with reused TensorBuffers.
 * - Adapts locally via bounded online feature calibration from victim feedback ("Helpful" vs "False Alarm")
 *   so AI sovereignty progresses smoothly from Tier 1 (Advisory) -> Tier 2 (Proactive Guard) -> Tier 3 (Sovereign Protector).
 * - Syncs calibration & sovereignty state seamlessly with Firestore `users/{uid}/ai_safety_state`.
 * - Non-bypassable R3 safety boundary: Autonomous AI triggers protective Fake Calls, vault pre-arming,
 *   and Trusted Circle notifications, but NEVER places an unconfirmed 112 police call.
 */
class TfliteSafetyBrain(private val context: Context) {

    private val tag = "TfliteSafetyBrain"
    private val prefs = context.getSharedPreferences("raksha_tflite_ai_prefs", Context.MODE_PRIVATE)
    private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RakshaLiteRTThread").apply { isDaemon = true }
    }
    private val inferenceDispatcher = singleThreadExecutor.asCoroutineDispatcher()

    private var interpreter: InterpreterApi? = null
    private val outputBuffer = Array(1) { FloatArray(4) }
    private var modelInitialized = false
    private var modelInitFailed = false
    private var usingNativeLiteRt = false

    private val featureBiases = FloatArray(10)
    private var confirmedSafeCount = prefs.getInt("confirmed_safe_count", 0)
    private var confirmedThreatCount = prefs.getInt("confirmed_threat_count", 0)
    private var fakeCallsTriggeredByAi = prefs.getInt("ai_fake_calls_count", 0)
    private var lastScoreHistory = ArrayDeque<Int>(6)
    private var lastFeatures = SafetyTelemetryFeatures()

    private val _autonomousGuardEnabled = MutableStateFlow(
        prefs.getBoolean("autonomous_guard_enabled", true)
    )
    val autonomousGuardEnabled: StateFlow<Boolean> = _autonomousGuardEnabled.asStateFlow()

    private val _sovereigntyTier = MutableStateFlow(computeTier())
    val sovereigntyTier: StateFlow<AiSovereigntyTier> = _sovereigntyTier.asStateFlow()

    private val _latestInference = MutableStateFlow(TfliteInferenceResult())
    val latestInference: StateFlow<TfliteInferenceResult> = _latestInference.asStateFlow()

    init {
        loadPersistedBiases()
    }

    fun setAutonomousGuardEnabled(enabled: Boolean) {
        _autonomousGuardEnabled.value = enabled
        prefs.edit().putBoolean("autonomous_guard_enabled", enabled).apply()
        _sovereigntyTier.value = computeTier()
    }

    fun exportSnapshot(): AiLearningStateSnapshot {
        return AiLearningStateSnapshot(
            sovereigntyTier = _sovereigntyTier.value.level,
            autonomousGuardEnabled = _autonomousGuardEnabled.value,
            confirmedSafeCount = confirmedSafeCount,
            confirmedThreatCount = confirmedThreatCount,
            fakeCallsTriggeredByAi = fakeCallsTriggeredByAi,
            calibrationBiases = featureBiases.toList(),
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun applyCloudSnapshot(snapshot: AiLearningStateSnapshot) {
        if (snapshot.calibrationBiases.size == 10) {
            for (i in 0 until 10) {
                featureBiases[i] = snapshot.calibrationBiases[i].coerceIn(-0.35f, 0.45f)
            }
            saveBiases()
        }
        confirmedSafeCount = maxOf(confirmedSafeCount, snapshot.confirmedSafeCount)
        confirmedThreatCount = maxOf(confirmedThreatCount, snapshot.confirmedThreatCount)
        fakeCallsTriggeredByAi = maxOf(fakeCallsTriggeredByAi, snapshot.fakeCallsTriggeredByAi)
        _autonomousGuardEnabled.value = snapshot.autonomousGuardEnabled
        prefs.edit()
            .putBoolean("autonomous_guard_enabled", snapshot.autonomousGuardEnabled)
            .putInt("confirmed_safe_count", confirmedSafeCount)
            .putInt("confirmed_threat_count", confirmedThreatCount)
            .putInt("ai_fake_calls_count", fakeCallsTriggeredByAi)
            .apply()
        _sovereigntyTier.value = computeTier()
    }

    suspend fun evaluateFeatures(features: SafetyTelemetryFeatures): TfliteInferenceResult =
        withContext(inferenceDispatcher) {
            lastFeatures = features
            ensureModelLoadedOnWorkerThread()

            val raw = features.toFloatArray()
            val calibrated = FloatArray(10) { idx ->
                (raw[idx] + featureBiases[idx]).coerceIn(0f, 1f)
            }

            val probs = runInferenceOnWorkerThread(calibrated)
            val safeProb = probs.getOrElse(0) { 1f }.coerceIn(0f, 1f)
            val watchProb = probs.getOrElse(1) { 0f }.coerceIn(0f, 1f)
            val callProb = probs.getOrElse(2) { 0f }.coerceIn(0f, 1f)
            val critProb = probs.getOrElse(3) { 0f }.coerceIn(0f, 1f)

            val neuralScore = ((watchProb * 44f) + (callProb * 74f) + (critProb * 98f)).toInt().coerceIn(0, 100)
            val previousAvg = if (lastScoreHistory.isNotEmpty()) lastScoreHistory.average().toInt() else neuralScore
            val trajectoryDelta = (neuralScore - previousAvg).coerceIn(-50, 50)
            if (lastScoreHistory.size >= 5) lastScoreHistory.removeFirst()
            lastScoreHistory.addLast(neuralScore)

            val tier = computeTier()
            _sovereigntyTier.value = tier

            val autonomous = _autonomousGuardEnabled.value
            val decision = when {
                !autonomous -> if (neuralScore >= 42) AutonomousAiDecision.ELEVATE_WATCH else AutonomousAiDecision.NONE
                critProb >= 0.58f || neuralScore >= 78 -> {
                    if (tier.level >= 2) AutonomousAiDecision.PRE_ARM_VAULT_AND_GUARDIAN_ALERT
                    else AutonomousAiDecision.PROACTIVE_FAKE_CALL
                }
                callProb >= 0.45f || (neuralScore >= 58 && (trajectoryDelta >= 8 || features.lateNightRisk > 0.6f)) -> {
                    if (tier.level >= 2) AutonomousAiDecision.PROACTIVE_FAKE_CALL
                    else AutonomousAiDecision.ELEVATE_WATCH
                }
                watchProb >= 0.42f || neuralScore >= 38 -> AutonomousAiDecision.ELEVATE_WATCH
                else -> AutonomousAiDecision.NONE
            }

            val explanation = buildString {
                append("Neural Brain (${if (usingNativeLiteRt) "LiteRT .tflite" else "CPU Matrix"}): ")
                append("Safe ${(safeProb * 100).toInt()}% • Watch ${(watchProb * 100).toInt()}% • FakeCall ${(callProb * 100).toInt()}% • Threat ${(critProb * 100).toInt()}%")
                if (trajectoryDelta >= 8) append(" • Rising trajectory (+$trajectoryDelta)")
            }

            val result = TfliteInferenceResult(
                riskScore = neuralScore,
                safeProb = safeProb,
                watchProb = watchProb,
                proactiveFakeCallProb = callProb,
                criticalDistressProb = critProb,
                predictedTrajectoryDelta = trajectoryDelta,
                decision = decision,
                sovereigntyTier = tier,
                autonomousGuardEnabled = autonomous,
                modelBackend = if (usingNativeLiteRt) "LiteRT CompiledModel (.tflite)" else "LiteRT Fallback Matrix",
                confirmedInteractions = confirmedSafeCount + confirmedThreatCount + fakeCallsTriggeredByAi,
                explanation = explanation,
                updatedAt = System.currentTimeMillis()
            )
            _latestInference.value = result
            result
        }

    /**
     * Online personal learning feedback from the victim:
     * - `wasGenuineThreat = true`: nudges active context feature sensitivity slightly upward & promotes Sovereignty Tier.
     * - `wasGenuineThreat = false`: reduces false-alarm bias for the active context features while keeping critical distress rules intact.
     */
    suspend fun recordFeedback(wasGenuineThreat: Boolean): AiLearningStateSnapshot = withContext(inferenceDispatcher) {
        val lastVec = lastFeatures.toFloatArray()
        val lr = if (wasGenuineThreat) 0.06f else -0.05f
        for (i in 0 until 10) {
            if (lastVec[i] >= 0.25f) {
                featureBiases[i] = (featureBiases[i] + lr * lastVec[i]).coerceIn(-0.35f, 0.45f)
            }
        }
        saveBiases()

        if (wasGenuineThreat) {
            confirmedThreatCount++
            prefs.edit().putInt("confirmed_threat_count", confirmedThreatCount).apply()
        } else {
            confirmedSafeCount++
            prefs.edit().putInt("confirmed_safe_count", confirmedSafeCount).apply()
        }
        _sovereigntyTier.value = computeTier()
        // Re-evaluate immediately with calibrated weights
        evaluateFeatures(lastFeatures)
        exportSnapshot()
    }

    fun noteAiTriggeredFakeCall() {
        fakeCallsTriggeredByAi++
        prefs.edit().putInt("ai_fake_calls_count", fakeCallsTriggeredByAi).apply()
        _sovereigntyTier.value = computeTier()
    }

    private fun computeTier(): AiSovereigntyTier {
        if (!_autonomousGuardEnabled.value) return AiSovereigntyTier.TIER_1_ADVISORY
        val total = confirmedSafeCount + confirmedThreatCount * 2 + fakeCallsTriggeredByAi
        return when {
            total >= 6 -> AiSovereigntyTier.TIER_3_SOVEREIGN_PROTECTOR
            total >= 2 || confirmedThreatCount >= 1 -> AiSovereigntyTier.TIER_2_PROACTIVE_GUARD
            else -> AiSovereigntyTier.TIER_2_PROACTIVE_GUARD // Ready for protective fake-call intervention by default once guard enabled
        }
    }

    private fun ensureModelLoadedOnWorkerThread() {
        if (modelInitialized || modelInitFailed) return
        try {
            val modelBytes = context.assets.open("raksha_safety_brain.tflite").use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
                .apply {
                    put(modelBytes)
                    rewind()
                }
            val options = InterpreterApi.Options().setNumThreads(2)
            val interp = InterpreterApi.create(modelBuffer, options)
            // Warmup pass on worker thread
            val zeroInput = Array(1) { FloatArray(10) }
            interp.run(zeroInput, outputBuffer)

            interpreter = interp
            modelInitialized = true
            usingNativeLiteRt = true
            Log.i(tag, "Loaded LiteRT TFLite InterpreterApi (raksha_safety_brain.tflite) cleanly")
        } catch (t: Throwable) {
            // Host JVM Robolectric runs without Android arm64/x86 JNI .so; clean fallback keeps tests deterministic
            Log.w(tag, "LiteRT native runtime unavailable (${t.javaClass.simpleName}: ${t.message}); using identical calibrated forward matrix")
            cleanupModelResourcesLocked()
            modelInitFailed = true
            usingNativeLiteRt = false
        }
    }

    private fun runInferenceOnWorkerThread(features: FloatArray): FloatArray {
        val interp = interpreter
        if (modelInitialized && interp != null) {
            try {
                outputBuffer[0].fill(0f)
                interp.run(arrayOf(features), outputBuffer)
                return outputBuffer[0].copyOf()
            } catch (e: Exception) {
                Log.w(tag, "LiteRT Interpreter run failed (${e.message}), using mathematical mirror")
            }
        }
        return runMathematicalMirror(features)
    }

    private fun runMathematicalMirror(x: FloatArray): FloatArray {
        val h = FloatArray(16)
        for (i in 0 until 16) {
            var sum = B1[i]
            for (j in 0 until 10) {
                sum += W1[i][j] * x[j]
            }
            h[i] = if (sum > 0f) sum else 0f
        }
        val logits = FloatArray(4)
        var maxLogit = Float.NEGATIVE_INFINITY
        for (k in 0 until 4) {
            var sum = B2[k]
            for (i in 0 until 16) {
                sum += W2[k][i] * h[i]
            }
            logits[k] = sum
            if (sum > maxLogit) maxLogit = sum
        }
        var expSum = 0.0
        val exps = DoubleArray(4)
        for (k in 0 until 4) {
            val e = exp((logits[k] - maxLogit).toDouble())
            exps[k] = e
            expSum += e
        }
        return FloatArray(4) { k -> (exps[k] / expSum).toFloat() }
    }

    private fun loadPersistedBiases() {
        val raw = prefs.getString("calibration_biases_json", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until minOf(10, arr.length())) {
                featureBiases[i] = arr.optDouble(i, 0.0).toFloat().coerceIn(-0.35f, 0.45f)
            }
        } catch (_: Exception) {
        }
    }

    private fun saveBiases() {
        val arr = JSONArray()
        featureBiases.forEach { arr.put(it.toDouble()) }
        prefs.edit().putString("calibration_biases_json", arr.toString()).apply()
    }

    private fun cleanupModelResourcesLocked() {
        runCatching { interpreter?.close() }
        interpreter = null
    }

    fun close() {
        singleThreadExecutor.execute {
            cleanupModelResourcesLocked()
            modelInitialized = false
        }
        singleThreadExecutor.shutdown()
    }

    companion object {
        // Exact mirror weights matching app/src/main/assets/raksha_safety_brain.tflite
        private val W1 = arrayOf(
            floatArrayOf(-1.2f, -1.0f, -1.3f, -0.8f, -0.4f, -1.4f, -0.7f, -0.9f, -1.2f, -1.8f),
            floatArrayOf(-0.9f, -1.4f, -1.0f, -0.6f, -0.3f, -1.0f, -0.9f, -1.1f, -1.0f, -1.4f),
            floatArrayOf(-0.6f, -0.5f, -1.6f, -1.2f, -0.5f, -1.5f, -0.5f, -0.6f, -0.8f, -1.9f),
            floatArrayOf(-1.1f, -0.8f, -0.9f, -0.7f, -0.7f, -0.9f, -0.8f, -1.0f, -1.1f, -1.3f),
            floatArrayOf(1.2f, 1.0f, 0.6f, 1.1f, 0.8f, -0.5f, 0.9f, 0.7f, 0.5f, -0.8f),
            floatArrayOf(0.9f, 1.3f, 0.8f, 0.9f, 0.6f, 0.1f, 1.1f, 1.0f, 0.7f, -0.5f),
            floatArrayOf(1.0f, 0.7f, 1.1f, 0.8f, 0.9f, 0.2f, 0.6f, 0.8f, 0.9f, -0.4f),
            floatArrayOf(0.6f, 1.1f, 0.9f, 1.2f, 0.7f, -0.3f, 0.8f, 0.9f, 0.8f, -0.7f),
            floatArrayOf(1.5f, 1.3f, 1.4f, 0.6f, 0.5f, 0.7f, 1.3f, 1.5f, 1.4f, 0.3f),
            floatArrayOf(1.3f, 1.1f, 1.6f, 0.9f, 0.6f, 0.9f, 1.1f, 1.4f, 1.6f, 0.4f),
            floatArrayOf(1.1f, 1.4f, 1.2f, 1.1f, 0.8f, 0.8f, 1.2f, 1.6f, 1.3f, 0.5f),
            floatArrayOf(1.4f, 0.9f, 1.5f, 0.8f, 0.5f, 1.0f, 1.4f, 1.2f, 1.5f, 0.6f),
            floatArrayOf(0.8f, 0.6f, 1.4f, 0.7f, 0.5f, 2.2f, 0.8f, 0.9f, 1.5f, 2.4f),
            floatArrayOf(0.7f, 0.8f, 1.5f, 0.8f, 0.4f, 2.0f, 0.7f, 1.0f, 1.7f, 2.1f),
            floatArrayOf(1.0f, 0.7f, 1.7f, 0.6f, 0.6f, 1.8f, 1.0f, 1.1f, 1.4f, 2.2f),
            floatArrayOf(0.6f, 0.5f, 1.3f, 0.9f, 0.5f, 2.4f, 0.6f, 0.8f, 1.8f, 2.5f)
        )
        private val B1 = floatArrayOf(
            1.8f, 1.6f, 1.7f, 1.5f, -0.9f, -1.0f, -0.95f, -0.85f,
            -2.2f, -2.3f, -2.35f, -2.25f, -2.9f, -2.85f, -3.0f, -2.95f
        )
        private val W2 = arrayOf(
            floatArrayOf(1.3f, 1.2f, 1.2f, 1.1f, -0.5f, -0.5f, -0.5f, -0.4f, -1.0f, -1.0f, -1.0f, -1.0f, -1.5f, -1.5f, -1.5f, -1.5f),
            floatArrayOf(-0.4f, -0.4f, -0.4f, -0.4f, 1.3f, 1.2f, 1.2f, 1.1f, 0.2f, 0.2f, 0.2f, 0.2f, -0.7f, -0.7f, -0.7f, -0.7f),
            floatArrayOf(-0.8f, -0.8f, -0.8f, -0.8f, 0.2f, 0.2f, 0.2f, 0.2f, 1.4f, 1.4f, 1.3f, 1.3f, 0.1f, 0.1f, 0.1f, 0.1f),
            floatArrayOf(-1.2f, -1.2f, -1.2f, -1.2f, -0.5f, -0.5f, -0.5f, -0.5f, 0.3f, 0.3f, 0.3f, 0.3f, 1.6f, 1.5f, 1.5f, 1.6f)
        )
        private val B2 = floatArrayOf(0.2f, 0.1f, -0.1f, -0.25f)
    }
}

package com.example.util

import android.content.Context
import android.location.Location
import com.example.data.AppDatabase
import com.example.data.model.LearnedPlaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class PredictiveThreatTier { SAFE, ELEVATED, HIGH }

data class SafeHavenSpot(
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int
)

data class PredictiveThreatAssessment(
    val threatScore: Int = 12,
    val tier: PredictiveThreatTier = PredictiveThreatTier.SAFE,
    val headline: String = "Routine Safe Zone • All Sensors Nominal",
    val reasonSummary: String = "Current area & schedule align with normal safe patterns.",
    val tacticalSteps: List<String> = listOf(
        "Keep Family 24/7 Live Map active while commuting.",
        "Tap Protector Walk if entering poorly lit streets.",
        "Emergency SOS remains 1-tap instant with Dual CameraX."
    ),
    val nearbySafeHavens: List<SafeHavenSpot> = emptyList(),
    val weatherNote: String = "Clear conditions",
    val sovereigntyTier: AiSovereigntyTier = AiSovereigntyTier.TIER_2_PROACTIVE_GUARD,
    val autonomousAction: AutonomousAiDecision = AutonomousAiDecision.NONE,
    val autonomousGuardEnabled: Boolean = true,
    val tfliteBackend: String = "LiteRT-CPU",
    val proactiveFakeCallProb: Float = 0f,
    val criticalDistressProb: Float = 0f,
    val predictedTrajectoryDelta: Int = 0,
    val confirmedInteractions: Int = 0,
    val neuralSummary: String = "TFLite Safety Brain active",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Predictive Threat Engine + Sovereign TFLite Neural Brain + User Routine Learner:
 * - Learns frequent Home/Work/corridor points locally inside Room (`learned_places`).
 * - Runs on-device Google AI Edge LiteRT (`raksha_safety_brain.tflite`) over real sensor telemetry.
 * - Queries keyless OpenStreetMap Overpass API + Open-Meteo weather API when online (with 15-min cache).
 * - STRICT 112 POLICY: Predictive threat alerts and autonomous AI protection NEVER dial 112 or unlock R3 police escalation automatically.
 */
class UserRoutineLearner(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    val tfliteBrain = TfliteSafetyBrain(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val _assessment = MutableStateFlow(PredictiveThreatAssessment())
    val assessment: StateFlow<PredictiveThreatAssessment> = _assessment.asStateFlow()

    private var lastExternalQueryMs = 0L
    private var cachedHavens: List<SafeHavenSpot> = emptyList()
    private var cachedWeatherNote: String = "Weather check standby"
    private var cachedWeatherRiskDelta: Int = 0
    private var cachedStreetLitDelta: Int = 0

    // Live transient risk boosts injected from sensors / acoustic / trip anomalies
    @Volatile
    private var recentJerkBoost: Float = 0f
    @Volatile
    private var recentAcousticBoost: Float = 0f
    @Volatile
    private var recentTripAnomalyBoost: Float = 0f
    @Volatile
    private var recentLightLux: Float? = null
    @Volatile
    private var boostUpdatedAtMs: Long = 0L

    fun updateSensorContext(
        dynamicJerkG: Float? = null,
        lightLux: Float? = null,
        acousticDbSpike: Boolean = false,
        tripAnomalyActive: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (dynamicJerkG != null) {
            val normalizedJerk = ((dynamicJerkG - 1.5f) / 2.2f).coerceIn(0f, 1f)
            if (normalizedJerk > recentJerkBoost) {
                recentJerkBoost = normalizedJerk
                boostUpdatedAtMs = now
            }
        }
        if (lightLux != null) {
            recentLightLux = lightLux
        }
        if (acousticDbSpike) {
            recentAcousticBoost = 0.92f
            boostUpdatedAtMs = now
        }
        recentTripAnomalyBoost = if (tripAnomalyActive) 0.85f else 0f
    }

    suspend fun recordVisit(
        lat: Double,
        lng: Double,
        timestampMillis: Long = System.currentTimeMillis()
    ): PredictiveThreatAssessment {
        val loc = Location("raksha_routine").apply {
            latitude = lat
            longitude = lng
            time = timestampMillis
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val battery = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 1..100 } ?: 80
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val online = cm?.activeNetwork != null
        return recordVisitAndEvaluate(loc, battery, online)
    }

    suspend fun evaluatePredictiveThreat(lat: Double, lng: Double): PredictiveThreatAssessment {
        lastExternalQueryMs = 0L
        return recordVisit(lat, lng, System.currentTimeMillis())
    }

    suspend fun recordVisitAndEvaluate(
        location: Location?,
        batteryPercent: Int,
        isOnline: Boolean
    ): PredictiveThreatAssessment = withContext(Dispatchers.IO) {
        val lat = location?.latitude ?: 0.0
        val lng = location?.longitude ?: 0.0
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hasFix = lat != 0.0 || lng != 0.0
        val now = System.currentTimeMillis()

        // Decay transient sensor spikes gently after 90s
        if (now - boostUpdatedAtMs > 90_000L) {
            recentJerkBoost = (recentJerkBoost * 0.4f).takeIf { it > 0.08f } ?: 0f
            recentAcousticBoost = (recentAcousticBoost * 0.4f).takeIf { it > 0.08f } ?: 0f
        }

        // 1. Update local learned routine cluster in Room (geohash precision 6 ~= 600m place)
        val places = db.learnedPlaceDao().getAllPlacesOnce()
        var isFamiliarArea = false
        if (hasFix) {
            val hash6 = StorageWardenManager.encodeGeohash(lat, lng, 6)
            val existing = places.firstOrNull { it.placeId == hash6 }
            if (existing != null) {
                val nextCount = existing.visitCount + 1
                isFamiliarArea = nextCount >= 3
                val label = when {
                    hour in 21..23 || hour in 0..5 -> if (nextCount >= 4) "HOME_CORRIDOR" else existing.label
                    hour in 9..17 -> if (nextCount >= 4) "WORK_STUDY_ZONE" else existing.label
                    else -> existing.label
                }
                db.learnedPlaceDao().upsertPlace(
                    existing.copy(
                        label = label,
                        visitCount = nextCount,
                        lastVisitedAt = now
                    )
                )
            } else {
                db.learnedPlaceDao().upsertPlace(
                    LearnedPlaceEntity(
                        placeId = hash6,
                        label = "VISITED_ZONE",
                        latitude = lat,
                        longitude = lng,
                        visitCount = 1,
                        typicalHourStart = hour,
                        typicalHourEnd = (hour + 2) % 24
                    )
                )
            }
        }

        // 2. Refresh free OpenStreetMap Overpass + Open-Meteo telemetry every 15 minutes when online
        if (isOnline && hasFix && (now - lastExternalQueryMs > 15 * 60 * 1000L)) {
            lastExternalQueryMs = now
            fetchOpenMeteoContext(lat, lng)
            fetchOverpassSafeHavens(lat, lng)
        }

        // 3. Evaluate Risk Zones & Community Incidents
        val highRiskZones = db.highRiskZoneDao().getActiveZonesOnce()
        val nearHighRiskZone = if (hasFix) {
            highRiskZones.firstOrNull { zone ->
                HexagonalRescueManager.calculateDistanceMeters(lat, lng, zone.latitude, zone.longitude) <= zone.radiusMeters
            }
        } else null

        val lateNightNorm = when (hour) {
            in 0..4, 23 -> 0.95f
            22, 5 -> 0.72f
            in 20..21 -> 0.42f
            else -> 0.05f
        }
        val unfamiliarNorm = if (hasFix && !isFamiliarArea && places.size >= 2) 0.85f else 0f
        val riskZoneNorm = if (nearHighRiskZone != null) 0.95f else 0f
        val lowBattNorm = when {
            batteryPercent <= 10 -> 1.0f
            batteryPercent <= 20 -> 0.65f
            batteryPercent <= 30 -> 0.30f
            else -> 0f
        }
        val offlineNorm = if (!isOnline) 0.75f else 0f
        val luxDarkNorm = when {
            recentLightLux != null && recentLightLux!! < 8f -> 0.85f
            cachedWeatherRiskDelta >= 10 -> 0.65f
            lateNightNorm >= 0.7f -> 0.75f
            else -> 0.1f
        }
        val isolatedNorm = if (cachedStreetLitDelta > 0 || (hasFix && cachedHavens.isEmpty() && isOnline)) 0.8f else 0.1f

        val features = SafetyTelemetryFeatures(
            lateNightRisk = lateNightNorm,
            unfamiliarZone = unfamiliarNorm,
            riskZoneProximity = riskZoneNorm,
            lowBattery = lowBattNorm,
            offlineMode = offlineNorm,
            jerkMotion = recentJerkBoost,
            darkEnvironment = luxDarkNorm,
            isolatedArea = isolatedNorm,
            abnormalStopOrSpeed = recentTripAnomalyBoost,
            acousticDistress = recentAcousticBoost
        )

        val nn = tfliteBrain.evaluateFeatures(features)

        // Blend rule anchors with neural score so critical physical spikes are never missed
        val reasons = mutableListOf<String>()
        if (lateNightNorm >= 0.6f) reasons += "Late-night hours (${hour}:00)"
        if (unfamiliarNorm >= 0.5f) reasons += "Unfamiliar area away from usual Home/Work routine"
        if (batteryPercent <= 10) reasons += "Critical battery ($batteryPercent%)"
        else if (batteryPercent <= 20) reasons += "Low battery ($batteryPercent%)"
        if (nearHighRiskZone != null) reasons += "Near marked risk zone: ${nearHighRiskZone.name}"
        if (cachedWeatherRiskDelta > 0) reasons += cachedWeatherNote
        if (cachedStreetLitDelta > 0) reasons += "Sparse lit POIs nearby"
        if (!isOnline) reasons += "Offline network mode"
        if (recentJerkBoost >= 0.5f) reasons += "Sudden motion/jerk telemetry"
        if (recentAcousticBoost >= 0.5f) reasons += "High ambient decibel spike"
        if (recentTripAnomalyBoost >= 0.5f) reasons += "Route corridor / prolonged stop anomaly"

        val clampedScore = nn.riskScore.coerceIn(0, 100)
        val tier = when {
            clampedScore >= 62 || nn.criticalDistressProb >= 0.45f -> PredictiveThreatTier.HIGH
            clampedScore >= 36 || nn.proactiveFakeCallProb >= 0.35f -> PredictiveThreatTier.ELEVATED
            else -> PredictiveThreatTier.SAFE
        }

        val headline = when (nn.decision) {
            AutonomousAiDecision.PRE_ARM_VAULT_AND_GUARDIAN_ALERT ->
                "AI Sovereign Guard • Pre-Threat Protection Activated"
            AutonomousAiDecision.PROACTIVE_FAKE_CALL ->
                "AI Predicting Escalation • Protective Fake Call Ready"
            AutonomousAiDecision.ELEVATE_WATCH ->
                "Elevated Awareness • Stay Connected with Circle"
            AutonomousAiDecision.NONE -> when (tier) {
                PredictiveThreatTier.HIGH -> "High Predictive Alert • Precautionary Action Advised"
                PredictiveThreatTier.ELEVATED -> "Elevated Awareness • Stay Connected with Circle"
                PredictiveThreatTier.SAFE -> if (isFamiliarArea) "Familiar Safe Routine Zone" else "Nominal Safety Conditions"
            }
        }

        val steps = when (tier) {
            PredictiveThreatTier.HIGH -> listOf(
                "AI Fake Call Shield & encrypted mic vault can de-escalate & alert Trusted Circle silently.",
                "Move toward a lit public space (${cachedHavens.firstOrNull()?.name ?: "nearest police/hospital/24h shop"}).",
                "Tap Emergency SOS button if you need immediate R3 Police 112 escalation."
            )
            PredictiveThreatTier.ELEVATED -> listOf(
                "Share active trip corridor or trigger 5s Fake Call cover with Father/Mother/Guardian.",
                "Scan any cab/auto plate with Ride Guardian before boarding.",
                "Conserve battery and keep GPS sharing active."
            )
            PredictiveThreatTier.SAFE -> listOf(
                "On-device TFLite Brain (${nn.sovereigntyTier.title}) watching quietly with minimal battery.",
                "Use Scan Vehicle Plate before any solo cab/auto ride.",
                "Zero-quota offline survival brain stays available anytime."
            )
        }

        val result = PredictiveThreatAssessment(
            threatScore = clampedScore,
            tier = tier,
            headline = headline,
            reasonSummary = if (reasons.isEmpty()) "All local sensors, battery & routine checks normal." else reasons.joinToString(" • "),
            tacticalSteps = steps,
            nearbySafeHavens = cachedHavens,
            weatherNote = cachedWeatherNote,
            sovereigntyTier = nn.sovereigntyTier,
            autonomousAction = nn.decision,
            autonomousGuardEnabled = nn.autonomousGuardEnabled,
            tfliteBackend = nn.modelBackend,
            proactiveFakeCallProb = nn.proactiveFakeCallProb,
            criticalDistressProb = nn.criticalDistressProb,
            predictedTrajectoryDelta = nn.predictedTrajectoryDelta,
            confirmedInteractions = nn.confirmedInteractions,
            neuralSummary = nn.explanation,
            updatedAt = now
        )
        _assessment.value = result
        result
    }

    suspend fun recordUserAiFeedback(wasGenuineThreat: Boolean): AiLearningStateSnapshot {
        val snap = tfliteBrain.recordFeedback(wasGenuineThreat)
        val nn = tfliteBrain.latestInference.value
        _assessment.value = _assessment.value.copy(
            threatScore = nn.riskScore,
            sovereigntyTier = nn.sovereigntyTier,
            autonomousAction = nn.decision,
            proactiveFakeCallProb = nn.proactiveFakeCallProb,
            criticalDistressProb = nn.criticalDistressProb,
            confirmedInteractions = nn.confirmedInteractions,
            neuralSummary = nn.explanation,
            updatedAt = System.currentTimeMillis()
        )
        return snap
    }

    fun setAutonomousGuardEnabled(enabled: Boolean) {
        tfliteBrain.setAutonomousGuardEnabled(enabled)
        _assessment.value = _assessment.value.copy(
            autonomousGuardEnabled = enabled,
            sovereigntyTier = tfliteBrain.sovereigntyTier.value
        )
    }

    fun applyCloudAiState(snapshot: AiLearningStateSnapshot) {
        tfliteBrain.applyCloudSnapshot(snapshot)
        _assessment.value = _assessment.value.copy(
            autonomousGuardEnabled = snapshot.autonomousGuardEnabled,
            sovereigntyTier = tfliteBrain.sovereigntyTier.value
        )
    }

    fun exportAiState(): AiLearningStateSnapshot = tfliteBrain.exportSnapshot()

    fun close() {
        tfliteBrain.close()
    }

    private fun fetchOpenMeteoContext(lat: Double, lng: Double) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current=precipitation,weather_code,is_day"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val current = JSONObject(body).optJSONObject("current") ?: return
                val precip = current.optDouble("precipitation", 0.0)
                val code = current.optInt("weather_code", 0)
                val isDay = current.optInt("is_day", 1) == 1
                cachedWeatherRiskDelta = when {
                    code >= 95 -> 16
                    precip >= 2.0 || code >= 61 -> 10
                    !isDay -> 8
                    else -> 0
                }
                cachedWeatherNote = when {
                    code >= 95 -> "Storm/Severe weather nearby"
                    precip > 0.2 -> "Rainy conditions (${precip}mm)"
                    !isDay -> "Nighttime low-visibility window"
                    else -> "Clear daylight weather"
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun fetchOverpassSafeHavens(lat: Double, lng: Double) {
        try {
            val query = "[out:json][timeout:6];(node[\"amenity\"~\"police|hospital|clinic|fuel\"](around:1800,$lat,$lng););out body 8;"
            val url = "https://overpass-api.de/api/interpreter?data=${URLEncoder.encode(query, "UTF-8")}"
            val req = Request.Builder().url(url).header("User-Agent", "RakshaOneSafetyApp/1.0").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val elements = JSONObject(body).optJSONArray("elements") ?: return
                val havens = mutableListOf<SafeHavenSpot>()
                for (i in 0 until elements.length()) {
                    val obj = elements.optJSONObject(i) ?: continue
                    val eLat = obj.optDouble("lat", 0.0)
                    val eLng = obj.optDouble("lon", 0.0)
                    val tags = obj.optJSONObject("tags") ?: continue
                    val amenity = tags.optString("amenity", "safe_spot")
                    val name = tags.optString("name").ifBlank {
                        when (amenity) {
                            "police" -> "Local Police Post"
                            "hospital", "clinic" -> "24/7 Medical Clinic"
                            else -> "Lit Fuel / Service Station"
                        }
                    }
                    val dist = HexagonalRescueManager.calculateDistanceMeters(lat, lng, eLat, eLng).roundToInt()
                    havens.add(SafeHavenSpot(name, amenity.uppercase(), eLat, eLng, dist))
                }
                cachedHavens = havens.sortedBy { it.distanceMeters }.take(5)
                cachedStreetLitDelta = if (cachedHavens.isEmpty()) 12 else 0
            }
        } catch (_: Exception) {
        }
    }
}

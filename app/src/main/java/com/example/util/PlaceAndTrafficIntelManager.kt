package com.example.util

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

enum class PlacePoiCategory {
    POLICE, HOSPITAL, TRANSIT, CROWD_HUB, PHARMACY, LANDMARK
}

data class PlaceMapPoi(
    val id: String,
    val name: String,
    val category: PlacePoiCategory,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float
)

data class TrafficCorridorSegment(
    val id: String,
    val roadName: String,
    val congestionLevel: String, // "SMOOTH", "MODERATE", "BUSY"
    val colorHex: String,
    val coordinates: List<Pair<Double, Double>>
)

data class PlaceAndTrafficIntel(
    val areaName: String = "Locating neighborhood…",
    val streetName: String = "",
    val crowdLevel: String = "MODERATE",
    val crowdSummary: String = "Scanning surrounding streets & public hubs…",
    val trafficLevel: String = "MODERATE",
    val trafficSummary: String = "Estimating live corridor flow…",
    val nearestPoliceSummary: String = "Police scan pending",
    val nearestHospitalSummary: String = "Medical scan pending",
    val pois: List<PlaceMapPoi> = emptyList(),
    val trafficCorridors: List<TrafficCorridorSegment> = emptyList(),
    val updatedAtMillis: Long = 0L,
    val isOfflineFallback: Boolean = false
)

/**
 * 100% Free, Zero-API-Key Spatial Intelligence Engine:
 * - Reverse-geocodes exact street, neighborhood, and city via Android Geocoder.
 * - Queries OpenStreetMap Overpass API (650m radius, cached per 400m move / 8 min) for real
 *   Police Stations, Hospitals, Markets, Bus/Transit Hubs, Traffic Signals, Named Shops, and
 *   Arterial Road Geometries to display Traffic Flow, Crowd Density, and Place Names on the 3D Map.
 * - Falls back cleanly offline using Geocoder + local movement telemetry + nearby active users.
 */
class PlaceAndTrafficIntelManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _intel = MutableStateFlow(PlaceAndTrafficIntel())
    val intel: StateFlow<PlaceAndTrafficIntel> = _intel.asStateFlow()

    private var lastFetchLat = 0.0
    private var lastFetchLng = 0.0
    private var lastFetchTimeMs = 0L
    @Volatile
    private var isFetching = false

    fun refreshIfNeeded(
        lat: Double,
        lng: Double,
        speedKmh: Float,
        nearbyUsersCount: Int,
        isOnline: Boolean,
        force: Boolean = false
    ) {
        if (lat == 0.0 && lng == 0.0) return
        val now = System.currentTimeMillis()
        val movedMeters = if (lastFetchLat != 0.0 || lastFetchLng != 0.0) {
            val out = FloatArray(1)
            Location.distanceBetween(lastFetchLat, lastFetchLng, lat, lng, out)
            out[0]
        } else {
            Float.MAX_VALUE
        }
        val stale = now - lastFetchTimeMs > 8 * 60_000L
        if (!force && !isFetching && movedMeters < 350f && !stale) {
            // Still update speed/crowd telemetry reactively without hitting network
            updateDynamicMetrics(speedKmh = speedKmh, nearbyUsersCount = nearbyUsersCount)
            return
        }
        if (isFetching) return
        isFetching = true
        lastFetchLat = lat
        lastFetchLng = lng
        lastFetchTimeMs = now

        scope.launch {
            try {
                val (streetName, areaName) = reverseGeocodePlace(lat, lng)
                val osmData = if (isOnline) queryOsmOverpass(lat, lng) else null

                if (osmData != null) {
                    val parsed = parseOsmIntel(
                        root = osmData,
                        centerLat = lat,
                        centerLng = lng,
                        streetName = streetName,
                        areaName = areaName,
                        speedKmh = speedKmh,
                        nearbyUsersCount = nearbyUsersCount
                    )
                    _intel.value = parsed
                } else {
                    _intel.value = buildOfflineIntel(
                        lat = lat,
                        lng = lng,
                        streetName = streetName,
                        areaName = areaName,
                        speedKmh = speedKmh,
                        nearbyUsersCount = nearbyUsersCount
                    )
                }
            } catch (_: Exception) {
                val current = _intel.value
                _intel.value = current.copy(
                    isOfflineFallback = true,
                    updatedAtMillis = System.currentTimeMillis()
                )
            } finally {
                isFetching = false
            }
        }
    }

    private fun updateDynamicMetrics(speedKmh: Float, nearbyUsersCount: Int) {
        val current = _intel.value
        val (trafficLevel, trafficSummary) = estimateTrafficSummary(
            speedKmh = speedKmh,
            signalCount = current.trafficCorridors.size,
            corridorCount = current.trafficCorridors.size,
            poiDensity = current.pois.size
        )
        val extraUserNote = if (nearbyUsersCount > 0) " • $nearbyUsersCount active RakshaOne user(s)" else ""
        if (trafficSummary != current.trafficSummary || (nearbyUsersCount > 0 && !current.crowdSummary.contains("RakshaOne"))) {
            _intel.value = current.copy(
                trafficLevel = trafficLevel,
                trafficSummary = trafficSummary,
                crowdSummary = current.crowdSummary.substringBefore(" • active") + extraUserNote
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodePlace(lat: Double, lng: Double): Pair<String, String> {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Use synchronous-compatible helper when off main thread
                geocoder.getFromLocation(lat, lng, 1)
            } else {
                geocoder.getFromLocation(lat, lng, 1)
            }
            val addr = addresses?.firstOrNull()
            if (addr != null) {
                val street = addr.thoroughfare ?: addr.featureName ?: ""
                val parts = listOfNotNull(
                    addr.thoroughfare?.takeIf { it.isNotBlank() },
                    addr.subLocality?.takeIf { it.isNotBlank() && it != addr.thoroughfare },
                    addr.locality?.takeIf { it.isNotBlank() }
                ).distinct()
                val joined = parts.joinToString(", ").ifBlank {
                    addr.getAddressLine(0)?.take(65)
                        ?: String.format(Locale.US, "%.4f, %.4f", lat, lng)
                }
                street to joined
            } else {
                "" to String.format(Locale.US, "GPS %.4f, %.4f", lat, lng)
            }
        } catch (_: Exception) {
            "" to String.format(Locale.US, "GPS %.4f, %.4f", lat, lng)
        }
    }

    private fun queryOsmOverpass(lat: Double, lng: Double): JSONObject? {
        val query = """
            [out:json][timeout:8];
            (
              node(around:650,$lat,$lng)[amenity~"police|hospital|clinic|pharmacy|marketplace|bus_station|fuel|bank|school|college|restaurant"];
              node(around:450,$lat,$lng)[shop];
              node(around:500,$lat,$lng)[highway~"bus_stop|traffic_signals"];
              way(around:450,$lat,$lng)[highway~"trunk|primary|secondary|tertiary"];
            );
            out body geom 120;
        """.trimIndent()

        val conn = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("User-Agent", "RakshaOneSafetyAndroid/1.0")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write("data=${URLEncoder.encode(query, "UTF-8")}")
            }
            if (conn.responseCode in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                JSONObject(body)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseOsmIntel(
        root: JSONObject,
        centerLat: Double,
        centerLng: Double,
        streetName: String,
        areaName: String,
        speedKmh: Float,
        nearbyUsersCount: Int
    ): PlaceAndTrafficIntel {
        val elements = root.optJSONArray("elements") ?: return buildOfflineIntel(
            centerLat, centerLng, streetName, areaName, speedKmh, nearbyUsersCount
        )

        val pois = mutableListOf<PlaceMapPoi>()
        val corridors = mutableListOf<TrafficCorridorSegment>()
        var shopCount = 0
        var transitCount = 0
        var signalCount = 0
        var firstNamedRoad = streetName
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isDayCommercialHours = hour in 8..21

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val type = el.optString("type")
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name").trim()

            if (type == "node") {
                val nLat = el.optDouble("lat", Double.NaN)
                val nLng = el.optDouble("lon", Double.NaN)
                if (nLat.isNaN() || nLng.isNaN()) continue
                val amenity = tags.optString("amenity")
                val shop = tags.optString("shop")
                val highway = tags.optString("highway")

                if (highway == "traffic_signals") {
                    signalCount++
                }
                if (shop.isNotBlank()) shopCount++
                if (highway == "bus_stop" || amenity == "bus_station") transitCount++

                val distOut = FloatArray(1)
                Location.distanceBetween(centerLat, centerLng, nLat, nLng, distOut)
                val distM = distOut[0]

                val category = when {
                    amenity == "police" -> PlacePoiCategory.POLICE
                    amenity == "hospital" || amenity == "clinic" -> PlacePoiCategory.HOSPITAL
                    amenity == "pharmacy" -> PlacePoiCategory.PHARMACY
                    highway == "bus_stop" || amenity == "bus_station" -> PlacePoiCategory.TRANSIT
                    amenity == "marketplace" || amenity == "fuel" || amenity == "bank" || shop.isNotBlank() ->
                        PlacePoiCategory.CROWD_HUB
                    else -> PlacePoiCategory.LANDMARK
                }

                val resolvedName = name.ifBlank {
                    when (category) {
                        PlacePoiCategory.POLICE -> "Police Post"
                        PlacePoiCategory.HOSPITAL -> "Medical Center"
                        PlacePoiCategory.PHARMACY -> "Pharmacy"
                        PlacePoiCategory.TRANSIT -> "Transit Stop"
                        PlacePoiCategory.CROWD_HUB -> shop.replaceFirstChar { it.uppercase() }.ifBlank { "Market/Shop" }
                        PlacePoiCategory.LANDMARK -> amenity.replaceFirstChar { it.uppercase() }
                    }
                }

                // Keep named landmarks + emergency services + key crowd hubs (capped so map remains crisp)
                if (category == PlacePoiCategory.POLICE ||
                    category == PlacePoiCategory.HOSPITAL ||
                    category == PlacePoiCategory.TRANSIT ||
                    name.isNotBlank()
                ) {
                    pois.add(
                        PlaceMapPoi(
                            id = "osm_${el.optLong("id")}",
                            name = resolvedName.take(28),
                            category = category,
                            latitude = nLat,
                            longitude = nLng,
                            distanceMeters = distM
                        )
                    )
                }
            } else if (type == "way") {
                val geom = el.optJSONArray("geometry") ?: continue
                if (geom.length() < 2) continue
                val coords = mutableListOf<Pair<Double, Double>>()
                for (g in 0 until geom.length()) {
                    val pt = geom.optJSONObject(g) ?: continue
                    val pLat = pt.optDouble("lat", Double.NaN)
                    val pLng = pt.optDouble("lon", Double.NaN)
                    if (!pLat.isNaN() && !pLng.isNaN()) {
                        coords.add(pLat to pLng)
                    }
                }
                if (coords.size < 2) continue
                val roadClass = tags.optString("highway")
                val roadName = name.ifBlank { roadClass.replaceFirstChar { it.uppercase() } + " Road" }
                if (firstNamedRoad.isBlank() && name.isNotBlank()) {
                    firstNamedRoad = name
                }

                val isBusyArterial = roadClass == "trunk" || roadClass == "primary"
                val congestion = when {
                    speedKmh > 0f && speedKmh < 10f && isBusyArterial -> "BUSY"
                    isBusyArterial && isDayCommercialHours && (shopCount >= 6 || signalCount >= 1) -> "MODERATE"
                    roadClass == "secondary" && shopCount >= 10 && isDayCommercialHours -> "MODERATE"
                    else -> "SMOOTH"
                }
                val hex = when (congestion) {
                    "BUSY" -> "#EF4444"
                    "MODERATE" -> "#F59E0B"
                    else -> "#10B981"
                }
                corridors.add(
                    TrafficCorridorSegment(
                        id = "way_${el.optLong("id")}",
                        roadName = roadName.take(32),
                        congestionLevel = congestion,
                        colorHex = hex,
                        coordinates = coords
                    )
                )
            }
        }

        val sortedPois = pois
            .sortedWith(
                compareBy<PlaceMapPoi> {
                    when (it.category) {
                        PlacePoiCategory.POLICE, PlacePoiCategory.HOSPITAL -> 0
                        PlacePoiCategory.TRANSIT -> 1
                        PlacePoiCategory.CROWD_HUB -> 2
                        else -> 3
                    }
                }.thenBy { it.distanceMeters }
            )
            .take(22)

        val nearestPolice = sortedPois.firstOrNull { it.category == PlacePoiCategory.POLICE }
        val nearestHospital = sortedPois.firstOrNull { it.category == PlacePoiCategory.HOSPITAL }

        val crowdScore = shopCount + transitCount * 3 + nearbyUsersCount * 2 + (if (isDayCommercialHours) 3 else 0)
        val (crowdLevel, crowdBase) = when {
            crowdScore >= 14 -> "CROWDED" to "Crowded Area (${shopCount + transitCount} shops/stops nearby)"
            crowdScore >= 5 -> "MODERATE" to "Active Neighborhood (${shopCount + transitCount} places nearby)"
            else -> "QUIET" to "Quiet Stretch — Stay near main road & light"
        }
        val crowdFull = if (nearbyUsersCount > 0) {
            "$crowdBase • $nearbyUsersCount nearby user(s)"
        } else {
            crowdBase
        }

        val (trafficLevel, trafficSummary) = estimateTrafficSummary(
            speedKmh = speedKmh,
            signalCount = signalCount,
            corridorCount = corridors.size,
            poiDensity = shopCount + transitCount
        )

        val finalArea = if (areaName.startsWith("GPS ") && firstNamedRoad.isNotBlank()) {
            "Near $firstNamedRoad"
        } else {
            areaName
        }

        return PlaceAndTrafficIntel(
            areaName = finalArea,
            streetName = firstNamedRoad,
            crowdLevel = crowdLevel,
            crowdSummary = crowdFull,
            trafficLevel = trafficLevel,
            trafficSummary = trafficSummary,
            nearestPoliceSummary = nearestPolice?.let { "${it.name} (${it.distanceMeters.roundToInt()}m)" }
                ?: "Police >650m",
            nearestHospitalSummary = nearestHospital?.let { "${it.name} (${it.distanceMeters.roundToInt()}m)" }
                ?: "Hospital >650m",
            pois = sortedPois,
            trafficCorridors = corridors.take(18),
            updatedAtMillis = System.currentTimeMillis(),
            isOfflineFallback = false
        )
    }

    internal fun buildOfflineIntel(
        lat: Double,
        lng: Double,
        streetName: String,
        areaName: String,
        speedKmh: Float,
        nearbyUsersCount: Int
    ): PlaceAndTrafficIntel {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val (crowdLevel, crowdBase) = when {
            nearbyUsersCount >= 3 -> "MODERATE" to "Active Area ($nearbyUsersCount nearby users detected)"
            hour in 9..20 -> "MODERATE" to "Daytime Activity (${streetName.ifBlank { "Local Street" }})"
            else -> "QUIET" to "Night / Low-Crowd Hours — Stay alert"
        }
        val (trafficLevel, trafficSummary) = estimateTrafficSummary(
            speedKmh = speedKmh,
            signalCount = 0,
            corridorCount = 1,
            poiDensity = 0
        )
        return PlaceAndTrafficIntel(
            areaName = areaName.ifBlank { String.format(Locale.US, "%.4f, %.4f", lat, lng) },
            streetName = streetName,
            crowdLevel = crowdLevel,
            crowdSummary = crowdBase,
            trafficLevel = trafficLevel,
            trafficSummary = trafficSummary,
            nearestPoliceSummary = "Tap 112 / Maps for Police",
            nearestHospitalSummary = "Tap Maps for Hospital",
            pois = emptyList(),
            trafficCorridors = emptyList(),
            updatedAtMillis = System.currentTimeMillis(),
            isOfflineFallback = true
        )
    }

    private fun estimateTrafficSummary(
        speedKmh: Float,
        signalCount: Int,
        corridorCount: Int,
        poiDensity: Int
    ): Pair<String, String> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val rushHour = hour in 8..11 || hour in 17..21
        return when {
            speedKmh in 0.5f..8f && (rushHour || signalCount > 0 || poiDensity >= 6) ->
                "BUSY" to "Heavy / Slow Traffic (~${speedKmh.roundToInt()} km/h)"
            rushHour && (corridorCount >= 2 || poiDensity >= 6) ->
                "MODERATE" to "Moderate Street Traffic ($signalCount signal(s))"
            speedKmh >= 20f ->
                "SMOOTH" to "Smooth Traffic (~${speedKmh.roundToInt()} km/h)"
            else ->
                "SMOOTH" to "Light / Steady Flow"
        }
    }
}

package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Streetview
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.HexAlertStatus
import com.example.data.model.HexRescueAlert
import com.example.data.model.IncidentEntity
import com.example.data.model.RelationshipTrustRules
import com.example.data.model.HighRiskZoneEntity
import com.example.data.model.TrustedContactEntity
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.TacticalBorder
import com.example.ui.theme.TacticalCard
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.WarningAmber
import com.example.util.LiveRadarManager
import com.example.util.LiveRadarPin
import com.example.util.NearbySupportUser
import com.example.util.PlaceAndTrafficIntel
import com.example.util.PlacePoiCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import java.util.Locale
import kotlin.math.roundToInt

private val TacticalNavy = Color(0xFF0A111A)
private const val OPENFREEMAP_3D_LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val RADAR_SOURCE_ID = "raksha_live_radar_source"
private const val RADAR_CIRCLE_LAYER_ID = "raksha_live_radar_circles"
private const val RADAR_SYMBOL_LAYER_ID = "raksha_live_radar_symbols"
private const val RADAR_LABEL_LAYER_ID = "raksha_live_radar_labels"
private const val SELF_PULSE_SOURCE_ID = "raksha_self_pulse_source"
private const val SELF_PULSE_CIRCLE_LAYER_ID = "raksha_self_pulse_circle"
private const val TRAFFIC_SOURCE_ID = "raksha_live_traffic_source"
private const val TRAFFIC_LINE_LAYER_ID = "raksha_live_traffic_lines"
private const val APPROACH_SOURCE_ID = "raksha_approach_line_source"
private const val APPROACH_LINE_LAYER_ID = "raksha_approach_line_layer"
private const val DANGER_ZONE_SOURCE_ID = "raksha_danger_zone_source"
private const val DANGER_ZONE_FILL_LAYER_ID = "raksha_danger_zone_fill"
private const val DANGER_ZONE_LINE_LAYER_ID = "raksha_danger_zone_line"

data class ApproachingResponderInfo(
    val alertId: String,
    val responderName: String,
    val responderPhone: String?,
    val responderLat: Double,
    val responderLng: Double,
    val distanceMeters: Float
)

@Composable
fun Dispatch3DMapSection(
    radarPins: Map<String, LiveRadarPin>,
    selfLocation: Location?,
    activeIncidents: List<IncidentEntity>,
    nearbyHexAlerts: List<HexRescueAlert>,
    nearbyUsers: List<NearbySupportUser> = emptyList(),
    placeIntel: PlaceAndTrafficIntel = PlaceAndTrafficIntel(),
    trustedContacts: List<TrustedContactEntity> = emptyList(),
    installedContactsMap: Map<String, Boolean> = emptyMap(),
    highRiskZones: List<HighRiskZoneEntity> = emptyList(),
    isOnline: Boolean = true,
    isLiveSharingEnabled: Boolean,
    disconnectAlertMessage: String?,
    onToggleLiveSharing: (Boolean) -> Unit,
    onDismissDisconnectAlert: () -> Unit,
    onOpenExternalMap: (Double, Double, String) -> Unit,
    onDialEmergency: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var is3dTiltEnabled by remember { mutableStateOf(true) }
    var followSelf by remember { mutableStateOf(true) }
    var focusedPinUid by remember { mutableStateOf<String?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var mapLibreRef by remember { mutableStateOf<MapLibreMap?>(null) }

    val mapView = remember {
        try {
            MapLibre.getInstance(context)
            MapView(context).apply {
                onCreate(null)
            }
        } catch (e: Exception) {
            mapError = e.message ?: "3D Map engine initializing"
            null
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            try {
                when (event) {
                    Lifecycle.Event.ON_START -> mapView?.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                    Lifecycle.Event.ON_STOP -> mapView?.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                    else -> Unit
                }
            } catch (_: Exception) {
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                mapView?.onPause()
                mapView?.onStop()
                mapView?.onDestroy()
            } catch (_: Exception) {
            }
        }
    }

    // Identify active approaching responder to the victim (if any accepted rescue request exists)
    val approachingResponder = remember(nearbyHexAlerts, selfLocation) {
        val acceptedAlert = nearbyHexAlerts.firstOrNull { alert ->
            (alert.status == HexAlertStatus.ACCEPTED || alert.status == HexAlertStatus.EN_ROUTE) &&
                alert.acceptedResponderLat != null && alert.acceptedResponderLng != null &&
                (alert.acceptedResponderLat != 0.0 || alert.acceptedResponderLng != 0.0)
        }
        if (acceptedAlert != null) {
            val rLat = acceptedAlert.acceptedResponderLat!!
            val rLng = acceptedAlert.acceptedResponderLng!!
            val dist = if (selfLocation != null) {
                val out = FloatArray(1)
                Location.distanceBetween(selfLocation.latitude, selfLocation.longitude, rLat, rLng, out)
                out[0]
            } else {
                acceptedAlert.distanceMeters
            }
            ApproachingResponderInfo(
                alertId = acceptedAlert.alertId,
                responderName = acceptedAlert.acceptedResponderName?.ifBlank { "Verified Citizen" } ?: "Verified Citizen",
                responderPhone = acceptedAlert.acceptedResponderPhone,
                responderLat = rLat,
                responderLng = rLng,
                distanceMeters = dist
            )
        } else {
            null
        }
    }

    // Build canonical deduplicated pin list (peer pins visible ONLY when both self & peer are online)
    val canonicalSelf = remember(radarPins, selfLocation, isLiveSharingEnabled, isOnline) {
        val liveSelfInRadar = radarPins.values.firstOrNull { it.relationship.equals("Self", ignoreCase = true) }
        when {
            selfLocation != null -> LiveRadarPin(
                uid = liveSelfInRadar?.uid ?: "LOCAL-SELF",
                name = liveSelfInRadar?.name?.ifBlank { "You" } ?: "You",
                relationship = "Self",
                latitude = selfLocation.latitude,
                longitude = selfLocation.longitude,
                bearing = selfLocation.bearing,
                speed = selfLocation.speed,
                battery = liveSelfInRadar?.battery ?: 100,
                sharingActive = isLiveSharingEnabled,
                isSosActive = liveSelfInRadar?.isSosActive ?: activeIncidents.isNotEmpty(),
                isCoreFamily = true,
                isPeerOnline = isOnline
            )
            liveSelfInRadar != null -> liveSelfInRadar.copy(isPeerOnline = isOnline)
            else -> null
        }
    }

    val allPinsList = remember(radarPins, canonicalSelf, isOnline) {
        val list = mutableListOf<LiveRadarPin>()
        if (canonicalSelf != null) {
            list.add(canonicalSelf)
        }

        // 2. Peer pins ONLY when BOTH self & peer have active internet connection
        if (isOnline) {
            val cutoff = System.currentTimeMillis() - LiveRadarManager.PEER_ONLINE_FRESHNESS_MS
            radarPins.values.forEach { pin ->
                if (!pin.relationship.equals("Self", ignoreCase = true) &&
                    pin.uid != canonicalSelf?.uid &&
                    pin.isPeerOnline &&
                    pin.sharingActive &&
                    pin.updatedAt >= cutoff &&
                    (pin.latitude != 0.0 || pin.longitude != 0.0)
                ) {
                    list.add(pin)
                }
            }
        }

        list.sortedWith(
            compareByDescending<LiveRadarPin> { it.relationship.equals("Self", true) }
                .thenByDescending { it.isCoreFamily }
                .thenBy { it.name }
        )
    }

    val offlineContacts = remember(trustedContacts, allPinsList) {
        trustedContacts.filter { contact ->
            allPinsList.none { pin ->
                pin.name.equals(contact.name, ignoreCase = true) ||
                    (contact.phone.isNotBlank() && pin.name.contains(contact.phone))
            }
        }
    }

    val closestDangerZone = remember(highRiskZones, selfLocation) {
        if (selfLocation == null || highRiskZones.isEmpty()) null
        else {
            var minDistance = Float.MAX_VALUE
            var closest: HighRiskZoneEntity? = null
            val out = FloatArray(1)
            highRiskZones.forEach { zone ->
                Location.distanceBetween(selfLocation.latitude, selfLocation.longitude, zone.latitude, zone.longitude, out)
                if (out[0] < minDistance) {
                    minDistance = out[0]
                    closest = zone
                }
            }
            if (closest != null && minDistance <= (closest.radiusMeters + 400f)) {
                closest to minDistance
            } else null
        }
    }

    val geoJsonPinsString = remember(allPinsList, activeIncidents, nearbyHexAlerts, nearbyUsers, placeIntel.pois, approachingResponder, highRiskZones, isOnline) {
        buildAllMapFeaturesGeoJson(
            pins = allPinsList,
            activeIncidents = activeIncidents,
            nearbyHexAlerts = nearbyHexAlerts,
            nearbyUsers = nearbyUsers,
            pois = placeIntel.pois,
            approachingResponder = approachingResponder,
            highRiskZones = highRiskZones,
            isOnline = isOnline
        )
    }

    val dangerZonesGeoJson = remember(highRiskZones) {
        buildDangerZonesGeoJson(highRiskZones)
    }

    val trafficLinesGeoJson = remember(placeIntel.trafficCorridors) {
        buildTrafficCorridorsGeoJson(placeIntel.trafficCorridors)
    }

    val approachLineGeoJson = remember(selfLocation, approachingResponder) {
        if (selfLocation != null && approachingResponder != null) {
            buildApproachLineGeoJson(
                fromLat = approachingResponder.responderLat,
                fromLng = approachingResponder.responderLng,
                toLat = selfLocation.latitude,
                toLng = selfLocation.longitude
            )
        } else {
            buildEmptyFeatureCollectionGeoJson()
        }
    }

    val selfPulseGeoJson = remember(canonicalSelf, isOnline, activeIncidents) {
        if (isOnline && canonicalSelf != null && (canonicalSelf.latitude != 0.0 || canonicalSelf.longitude != 0.0)) {
            val isVictimDistress = canonicalSelf.isSosActive || activeIncidents.isNotEmpty()
            val pulseColor = if (isVictimDistress) "#DC2626" else "#38BDF8"
            JSONObject().apply {
                put("type", "FeatureCollection")
                put("features", JSONArray().apply {
                    put(createGeoJsonPoint(canonicalSelf.longitude, canonicalSelf.latitude, "", pulseColor, "avatar_self_blue"))
                })
            }.toString()
        } else {
            buildEmptyFeatureCollectionGeoJson()
        }
    }

    val latestGeoJsonPins by rememberUpdatedState(geoJsonPinsString)
    val latestDangerZones by rememberUpdatedState(dangerZonesGeoJson)
    val latestTrafficLines by rememberUpdatedState(trafficLinesGeoJson)
    val latestApproachLine by rememberUpdatedState(approachLineGeoJson)
    val latestSelfPulseGeoJson by rememberUpdatedState(selfPulseGeoJson)

    // Update GeoJSON sources & animate camera smoothly every 4 seconds
    LaunchedEffect(mapLibreRef, mapReady, geoJsonPinsString, dangerZonesGeoJson, trafficLinesGeoJson, approachLineGeoJson, selfPulseGeoJson, is3dTiltEnabled, focusedPinUid, followSelf) {
        val map = mapLibreRef ?: return@LaunchedEffect
        map.getStyle { style ->
            ensureAllMapLayers(
                context = context,
                style = style,
                geoJsonPins = geoJsonPinsString,
                dangerZonesGeoJson = dangerZonesGeoJson,
                trafficLinesGeoJson = trafficLinesGeoJson,
                approachLineGeoJson = approachLineGeoJson,
                selfPulseGeoJson = selfPulseGeoJson
            )
        }

        val targetPin = when {
            focusedPinUid != null -> allPinsList.firstOrNull { it.uid == focusedPinUid }
            followSelf -> allPinsList.firstOrNull { it.relationship == "Self" } ?: allPinsList.firstOrNull()
            else -> null
        }

        if (targetPin != null && (targetPin.latitude != 0.0 || targetPin.longitude != 0.0)) {
            val camPos = CameraPosition.Builder()
                .target(LatLng(targetPin.latitude, targetPin.longitude))
                .zoom(16.2)
                .tilt(if (is3dTiltEnabled) 45.0 else 0.0)
                .bearing(targetPin.bearing.toDouble())
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(camPos), 900)
        }
    }

    // Animated pulsing ring around self-avatar when live internet connection is active
    LaunchedEffect(mapLibreRef, mapReady, isOnline, canonicalSelf != null) {
        val map = mapLibreRef ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        if (!isOnline || canonicalSelf == null) {
            map.getStyle { style ->
                val pulseLayer = style.getLayerAs<CircleLayer>(SELF_PULSE_CIRCLE_LAYER_ID)
                pulseLayer?.setProperties(
                    PropertyFactory.circleRadius(0f),
                    PropertyFactory.circleOpacity(0f),
                    PropertyFactory.circleStrokeOpacity(0f)
                )
            }
            return@LaunchedEffect
        }
        val startMs = System.currentTimeMillis()
        while (isActive) {
            val elapsed = (System.currentTimeMillis() - startMs) % 1400L
            val t = elapsed / 1400f
            val radius = 14f + t * 26f
            val fillAlpha = 0.42f * (1f - t)
            val strokeAlpha = 0.90f * (1f - t)
            map.getStyle { style ->
                val pulseLayer = style.getLayerAs<CircleLayer>(SELF_PULSE_CIRCLE_LAYER_ID)
                pulseLayer?.setProperties(
                    PropertyFactory.circleRadius(radius),
                    PropertyFactory.circleOpacity(fillAlpha),
                    PropertyFactory.circleStrokeOpacity(strokeAlpha)
                )
            }
            delay(60L)
        }
    }

    fun focusCoordinates(lat: Double, lng: Double, bearing: Float = 0f, zoom: Double = 16.5) {
        val map = mapLibreRef ?: return
        val camPos = CameraPosition.Builder()
            .target(LatLng(lat, lng))
            .zoom(zoom)
            .tilt(if (is3dTiltEnabled) 45.0 else 0.0)
            .bearing(bearing.toDouble())
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camPos), 800)
    }

    fun fitAllCommunityPins() {
        val map = mapLibreRef ?: return
        val builder = LatLngBounds.Builder()
        var count = 0
        allPinsList.filter { it.latitude != 0.0 || it.longitude != 0.0 }.forEach {
            builder.include(LatLng(it.latitude, it.longitude))
            count++
        }
        if (isOnline) {
            approachingResponder?.let {
                builder.include(LatLng(it.responderLat, it.responderLng))
                count++
            }
            nearbyUsers.filter { it.isOnline }.take(8).forEach {
                builder.include(LatLng(it.latitude, it.longitude))
                count++
            }
        }
        if (count == 0) return
        if (count == 1) {
            val single = allPinsList.firstOrNull()
            if (single != null) focusCoordinates(single.latitude, single.longitude, zoom = 15.8)
            return
        }
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 90), 900)
        } catch (_: Exception) {
        }
    }

    fun openStreetView(lat: Double, lng: Double) {
        val svIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.streetview:cbll=$lat,$lng")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(svIntent)
        } catch (_: Exception) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://maps.google.com/?cbll=$lat,$lng&layer=c")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(webIntent)
            } catch (_: Exception) {
                onOpenExternalMap(lat, lng, "Street View Spot")
            }
        }
    }

    val activeTargetLat: Double? = focusedPinUid?.let { id -> allPinsList.firstOrNull { it.uid == id }?.latitude }
        ?: selfLocation?.latitude
        ?: allPinsList.firstOrNull()?.latitude
    val activeTargetLng: Double? = focusedPinUid?.let { id -> allPinsList.firstOrNull { it.uid == id }?.longitude }
        ?: selfLocation?.longitude
        ?: allPinsList.firstOrNull()?.longitude
    val latestTargetLat by rememberUpdatedState(activeTargetLat)
    val latestTargetLng by rememberUpdatedState(activeTargetLng)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dispatch_3d_map_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Live Sharing + Disconnect Alert Banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TacticalCard),
                border = BorderStroke(1.dp, TacticalBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isLiveSharingEnabled) "24/7 LIVE FAMILY RADAR ACTIVE" else "LIVE LOCATION PAUSED",
                                color = if (isLiveSharingEnabled) SafetyTeal else WarningAmber,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Core Family (Father, Mother, Brother, Sister, Spouse): 24/7 • Extended: Trip/SOS",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = isLiveSharingEnabled,
                            onCheckedChange = onToggleLiveSharing,
                            modifier = Modifier.testTag("toggle_live_location_sharing")
                        )
                    }

                    AnimatedVisibility(visible = !disconnectAlertMessage.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = WarningAmber.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, WarningAmber),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = disconnectAlertMessage.orEmpty(),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = onDismissDisconnectAlert, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss alert", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1b. Reported Danger Zone Proximity Alert
        if (closestDangerZone != null) {
            val (zone, dist) = closestDangerZone
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = EmergencyRed.copy(alpha = 0.22f)),
                    border = BorderStroke(1.5.dp, EmergencyRed)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Danger Warning",
                            tint = EmergencyRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚠️ REPORTED DANGER ZONE: ${zone.name.uppercase()}",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${zone.advisoryMessage.ifBlank { "Prior harassment / hazard reported at this spot." }} • ${dist.toInt()}m away. Stay in illuminated corridors.",
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        // 2. Real-Time Street Names, Traffic Flow & Crowd Density Intelligence HUD
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TacticalCard),
                border = BorderStroke(1.dp, PoliceBlue.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Street and Area Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (placeIntel.streetName.isNotBlank()) placeIntel.streetName else placeIntel.areaName,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (placeIntel.streetName.isNotBlank() && placeIntel.areaName != placeIntel.streetName) {
                                Text(
                                    text = placeIntel.areaName,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Live Crowd Density & Traffic Flow Indicator Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when (placeIntel.crowdLevel) {
                                "CROWDED" -> SafetyTeal.copy(alpha = 0.18f)
                                "MODERATE" -> PoliceBlue.copy(alpha = 0.2f)
                                else -> WarningAmber.copy(alpha = 0.16f)
                            },
                            border = BorderStroke(
                                1.dp,
                                when (placeIntel.crowdLevel) {
                                    "CROWDED" -> SafetyTeal
                                    "MODERATE" -> PoliceBlue
                                    else -> WarningAmber
                                }
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.People,
                                    contentDescription = null,
                                    tint = when (placeIntel.crowdLevel) {
                                        "CROWDED" -> SafetyTeal
                                        "MODERATE" -> PoliceBlue
                                        else -> WarningAmber
                                    },
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(
                                        text = "CROWD: ${placeIntel.crowdLevel}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                    Text(
                                        text = placeIntel.crowdSummary,
                                        fontSize = 9.sp,
                                        color = Color.White.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when (placeIntel.trafficLevel) {
                                "BUSY" -> EmergencyRed.copy(alpha = 0.18f)
                                "MODERATE" -> WarningAmber.copy(alpha = 0.18f)
                                else -> SafetyTeal.copy(alpha = 0.18f)
                            },
                            border = BorderStroke(
                                1.dp,
                                when (placeIntel.trafficLevel) {
                                    "BUSY" -> EmergencyRed
                                    "MODERATE" -> WarningAmber
                                    else -> SafetyTeal
                                }
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Traffic,
                                    contentDescription = null,
                                    tint = when (placeIntel.trafficLevel) {
                                        "BUSY" -> EmergencyRed
                                        "MODERATE" -> WarningAmber
                                        else -> SafetyTeal
                                    },
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(
                                        text = "TRAFFIC: ${placeIntel.trafficLevel}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                    Text(
                                        text = placeIntel.trafficSummary,
                                        fontSize = 9.sp,
                                        color = Color.White.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Nearest Safe Havens (Police & Hospital distance)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalPolice, contentDescription = null, tint = PoliceBlue, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = placeIntel.nearestPoliceSummary,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalHospital, contentDescription = null, tint = EmergencyRed, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = placeIntel.nearestHospitalSummary,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        // 3. Approaching Responder Card (Victim Relief: see accepted helper approaching live!)
        approachingResponder?.let { resp ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1035)),
                    border = BorderStroke(1.2.dp, Color(0xFF8B5CF6))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF8B5CF6)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "HELPER APPROACHING • ${resp.distanceMeters.roundToInt()}m away",
                                    color = Color(0xFFA78BFA),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "${resp.responderName} is en route to your location",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!resp.responderPhone.isNullOrBlank()) {
                                IconButton(
                                    onClick = { onDialEmergency(resp.responderPhone) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = "Call Responder", tint = SafetyTeal, modifier = Modifier.size(18.dp))
                                }
                            }
                            Button(
                                onClick = { focusCoordinates(resp.responderLat, resp.responderLng, zoom = 17.0) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Track", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // 4. Embedded 3D MapLibre + OpenFreeMap Liberty Canvas with 3D buildings & POIs
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.2.dp, PoliceBlue.copy(alpha = 0.65f))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (mapView != null && mapError == null) {
                        AndroidView(
                            factory = {
                                mapView.apply {
                                    getMapAsync { map ->
                                        mapLibreRef = map
                                        map.uiSettings.isCompassEnabled = true
                                        map.uiSettings.isRotateGesturesEnabled = true
                                        map.uiSettings.isTiltGesturesEnabled = true
                                         map.setStyle(Style.Builder().fromUri(OPENFREEMAP_3D_LIBERTY_STYLE)) { style ->
                                            ensureAllMapLayers(
                                                context = context,
                                                style = style,
                                                geoJsonPins = latestGeoJsonPins,
                                                dangerZonesGeoJson = latestDangerZones,
                                                trafficLinesGeoJson = latestTrafficLines,
                                                approachLineGeoJson = latestApproachLine,
                                                selfPulseGeoJson = latestSelfPulseGeoJson
                                            )
                                            mapReady = true
                                            val startLat = latestTargetLat
                                            val startLng = latestTargetLng
                                            if (startLat != null && startLng != null && (startLat != 0.0 || startLng != 0.0)) {
                                                map.cameraPosition = CameraPosition.Builder()
                                                    .target(LatLng(startLat, startLng))
                                                    .zoom(16.2)
                                                    .tilt(if (is3dTiltEnabled) 45.0 else 0.0)
                                                    .build()
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(34.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Live 3D Radar Loading", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                text = mapError ?: "OpenFreeMap tiles loading…",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Overlay top-left badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TacticalNavy.copy(alpha = 0.86f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = if (is3dTiltEnabled) "3D STREET VIEW • 45° TILT" else "2D TOP-DOWN",
                            color = SafetyTeal,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Overlay top-right connection / avatar status pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TacticalNavy.copy(alpha = 0.88f),
                        border = BorderStroke(
                            1.dp,
                            when {
                                canonicalSelf == null -> WarningAmber.copy(alpha = 0.65f)
                                isOnline -> SafetyTeal.copy(alpha = 0.75f)
                                else -> WarningAmber.copy(alpha = 0.75f)
                            }
                        ),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            canonicalSelf == null -> WarningAmber
                                            isOnline -> SafetyTeal
                                            else -> WarningAmber
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = when {
                                    canonicalSelf == null -> "ACQUIRING GPS"
                                    isOnline -> "LIVE • SELF + PEERS"
                                    else -> "OFFLINE • SELF ONLY"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }

        // 5. Map Quick Controls Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        is3dTiltEnabled = !is3dTiltEnabled
                        if (activeTargetLat != null && activeTargetLng != null) {
                            focusCoordinates(activeTargetLat, activeTargetLng)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("map_toggle_3d_button")
                ) {
                    Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (is3dTiltEnabled) "3D 45°" else "2D", fontSize = 11.sp, maxLines = 1)
                }
                FilledTonalButton(
                    onClick = {
                        followSelf = false
                        focusedPinUid = null
                        fitAllCommunityPins()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1.1f)
                        .testTag("map_fit_all_button")
                ) {
                    Icon(Icons.Default.FitScreen, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Fit All", fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        if (activeTargetLat != null && activeTargetLng != null) {
                            onOpenExternalMap(activeTargetLat, activeTargetLng, "Live Radar Pin")
                        }
                    },
                    enabled = activeTargetLat != null && activeTargetLng != null,
                    border = BorderStroke(1.dp, TacticalBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Nav", color = Color.White, fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        if (activeTargetLat != null && activeTargetLng != null) {
                            openStreetView(activeTargetLat, activeTargetLng)
                        }
                    },
                    enabled = activeTargetLat != null && activeTargetLng != null,
                    border = BorderStroke(1.dp, SafetyTeal),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1.1f)
                        .testTag("map_street_view_button")
                ) {
                    Icon(Icons.Default.Streetview, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Street", color = SafetyTeal, fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        // 6. Community Support Banner (Victim Solace: "You are not alone")
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TacticalCard),
                border = BorderStroke(1.dp, SafetyTeal.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Group, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                !isOnline -> "Offline Radar • Self GPS Only"
                                nearbyUsers.any { it.isOnline } -> "${nearbyUsers.count { it.isOnline }} Verified Citizens & Users Nearby"
                                else -> "RakshaOne Community Shield Active"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = when {
                                !isOnline -> "Internet offline — peer markers paused until both self & peers reconnect. Autonomous carrier SMS & BLE Mesh active."
                                nearbyUsers.any { it.isOnline } -> "Active online responders & citizens are in your sector. You are protected and never alone!"
                                else -> "Scanning 6 sectors via Bluetooth LE Mesh, Firestore & RTDB. Emergency triggers alert up to 5 nearby responders immediately."
                            },
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // 7. Trusted Circle Live Pins List
        item {
            Text(
                text = "TRUSTED CIRCLE PINS (${allPinsList.size})",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp
            )
        }

        items(allPinsList, key = { it.uid }) { pin ->
            val distanceLabel = remember(pin.latitude, pin.longitude, selfLocation) {
                if (pin.relationship == "Self") "Current Device"
                else if (selfLocation != null) {
                    val out = FloatArray(1)
                    Location.distanceBetween(selfLocation.latitude, selfLocation.longitude, pin.latitude, pin.longitude, out)
                    if (out[0] < 1000f) "${out[0].toInt()} m away" else String.format(Locale.US, "%.1f km away", out[0] / 1000f)
                } else {
                    String.format(Locale.US, "%.4f, %.4f", pin.latitude, pin.longitude)
                }
            }
            val tierText = when {
                pin.relationship == "Self" -> "Your Pin"
                pin.isCoreFamily -> "Core Family • 24/7"
                pin.isSosActive -> "Extended • SOS Active"
                pin.hasActiveTrip -> "Extended • On Trip"
                else -> "Extended Circle"
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TacticalCard),
                border = BorderStroke(1.dp, if (pin.isSosActive) EmergencyRed else TacticalBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        pin.isSosActive -> EmergencyRed
                                        pin.sharingActive -> SafetyTeal
                                        else -> WarningAmber
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "${pin.name} (${pin.relationship})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$tierText • Battery ${pin.battery}% • $distanceLabel",
                                color = if (pin.battery < 10) WarningAmber else Color.White.copy(alpha = 0.78f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            focusedPinUid = pin.uid
                            followSelf = pin.relationship == "Self"
                            focusCoordinates(pin.latitude, pin.longitude, pin.bearing)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Focus", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 8. Saved Trusted Contacts Pending Live Broadcast (e.g. Spouse added but app not yet streaming)
        if (offlineContacts.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "OTHER TRUSTED CIRCLE MEMBERS (${offlineContacts.size})",
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            items(offlineContacts, key = { "offline_${it.id}" }) { contact ->
                val isCore = contact.isCoreFamily()
                val isInstalled = installedContactsMap[contact.phone] == true
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = TacticalCard.copy(alpha = 0.7f)),
                    border = BorderStroke(1.dp, TacticalBorder.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isInstalled) PoliceBlue else Color.Gray)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "${contact.name} (${contact.relationship})",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = if (isInstalled) {
                                        "${if (isCore) "Core Family" else "Extended Circle"} • App installed • Awaiting GPS broadcast"
                                    } else {
                                        "${if (isCore) "Core Family" else "Extended Circle"} • SMS/WhatsApp channel ready"
                                    },
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

private fun ensureCustomPinBitmaps(context: Context, style: Style) {
    val icons = listOf(
        // Dynamic Situation Avatars
        "avatar_self_blue" to (0xFF2563EB.toInt() to ("YOU" to "SELF")),
        "avatar_victim_red" to (0xFFDC2626.toInt() to ("SOS" to "VICTIM")),
        "avatar_savior_green" to (0xFF10B981.toInt() to ("HELP" to "SAVIOR")),
        "avatar_family_gold" to (0xFFF59E0B.toInt() to ("FAM" to "FAMILY")),
        "avatar_spouse_heart" to (0xFF059669.toInt() to ("LOVE" to "SPOUSE")),
        "dot_nearby_green" to (0xFF22C55E.toInt() to ("USER" to "DOT")),
        "hazard_danger_zone" to (0xFFDC2626.toInt() to ("DANGER" to "HAZARD")),
        // POI teardrop pins
        "poi_police" to (0xFF1D4ED8.toInt() to ("POLICE" to "POI")),
        "poi_hospital" to (0xFFBE123C.toInt() to ("MED" to "POI")),
        "poi_transit" to (0xFFD97706.toInt() to ("BUS" to "POI")),
        "poi_hub" to (0xFF4338CA.toInt() to ("HUB" to "POI")),
        // Aliases for compatibility
        "pin_self" to (0xFF2563EB.toInt() to ("YOU" to "SELF")),
        "pin_family" to (0xFFF59E0B.toInt() to ("FAM" to "FAMILY")),
        "pin_spouse" to (0xFF059669.toInt() to ("LOVE" to "SPOUSE")),
        "pin_responder" to (0xFF10B981.toInt() to ("HELP" to "SAVIOR")),
        "pin_nearby" to (0xFF22C55E.toInt() to ("USER" to "DOT")),
        "pin_sos" to (0xFFDC2626.toInt() to ("SOS" to "VICTIM"))
    )
    icons.forEach { (id, spec) ->
        if (style.getImage(id) == null) {
            val bmp = if (spec.second.second == "POI") {
                createTeardropBitmap(spec.first, spec.second.first)
            } else {
                createCustomAvatarBitmap(spec.first, spec.second.first, spec.second.second)
            }
            style.addImage(id, bmp)
        }
    }
}

private fun createCustomAvatarBitmap(
    primaryColor: Int,
    badgeText: String,
    avatarType: String
): Bitmap {
    val size = if (avatarType == "DOT") 36 else 66
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    if (avatarType == "DOT") {
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            alpha = 110
        }
        canvas.drawCircle(cx, cy, 16f, glowPaint)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 10f, fillPaint)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(cx, cy, 10f, strokePaint)
        return bitmap
    }

    if (avatarType == "HAZARD") {
        val path = Path().apply {
            moveTo(cx, 6f)
            lineTo(size - 6f, size - 10f)
            lineTo(6f, size - 10f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("!", cx, cy + 12f, textPaint)
        return bitmap
    }

    // Outer radar glow
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryColor
        alpha = 90
    }
    canvas.drawCircle(cx, cy, cx - 2f, glowPaint)

    // Base circle
    val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, cx - 7f, basePaint)

    // Outer contrast ring
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    canvas.drawCircle(cx, cy, cx - 7f, ringPaint)

    // Silhouette icon
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy - 6f, 7f, iconPaint)
    val bodyRect = RectF(cx - 12f, cy + 3f, cx + 12f, cy + 18f)
    canvas.drawRoundRect(bodyRect, 8f, 8f, iconPaint)

    // Directional chevron for SELF or SAVIOR
    if (avatarType == "SELF" || avatarType == "SAVIOR") {
        val arrowPath = Path().apply {
            moveTo(cx, 3f)
            lineTo(cx - 5f, 9f)
            lineTo(cx + 5f, 9f)
            close()
        }
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawPath(arrowPath, arrowPaint)
    }

    // Bottom badge overlay
    val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when (avatarType) {
            "VICTIM" -> android.graphics.Color.parseColor("#7F1D1D")
            "SAVIOR" -> android.graphics.Color.parseColor("#065F46")
            "SELF" -> android.graphics.Color.parseColor("#1E3A8A")
            else -> android.graphics.Color.parseColor("#78350F")
        }
        style = Paint.Style.FILL
    }
    val badgeRect = RectF(cx - 16f, size - 14f, cx + 16f, size - 2f)
    canvas.drawRoundRect(badgeRect, 4f, 4f, badgeBg)

    val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 8.5f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(badgeText, cx, size - 4.5f, badgeTextPaint)

    return bitmap
}

private fun addDangerZoneLayers(style: Style, geoJsonString: String) {
    try {
        style.addSource(GeoJsonSource(DANGER_ZONE_SOURCE_ID, FeatureCollection.fromJson(geoJsonString)))
        style.addLayer(
            FillLayer(DANGER_ZONE_FILL_LAYER_ID, DANGER_ZONE_SOURCE_ID).withProperties(
                PropertyFactory.fillColor(toColor(get("color"))),
                PropertyFactory.fillOpacity(0.25f)
            )
        )
        style.addLayer(
            LineLayer(DANGER_ZONE_LINE_LAYER_ID, DANGER_ZONE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(toColor(get("color"))),
                PropertyFactory.lineWidth(2.5f),
                PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
                PropertyFactory.lineOpacity(0.85f)
            )
        )
    } catch (_: Exception) {
    }
}

private fun buildDangerZonesGeoJson(zones: List<HighRiskZoneEntity>): String {
    val features = JSONArray()
    zones.forEach { zone ->
        if (zone.isEnabled && (zone.latitude != 0.0 || zone.longitude != 0.0)) {
            val coordinates = JSONArray()
            val ring = JSONArray()
            val steps = 24
            val latRad = Math.toRadians(zone.latitude)
            val earthRadius = 6378137.0
            val dLat = zone.radiusMeters / earthRadius
            val dLng = zone.radiusMeters / (earthRadius * Math.cos(latRad))

            for (i in 0..steps) {
                val angle = 2.0 * Math.PI * i / steps
                val ptLat = zone.latitude + Math.toDegrees(dLat * Math.sin(angle))
                val ptLng = zone.longitude + Math.toDegrees(dLng * Math.cos(angle))
                ring.put(JSONArray().apply {
                    put(ptLng)
                    put(ptLat)
                })
            }
            coordinates.put(ring)

            features.put(JSONObject().apply {
                put("type", "Feature")
                put("geometry", JSONObject().apply {
                    put("type", "Polygon")
                    put("coordinates", coordinates)
                })
                put("properties", JSONObject().apply {
                    put("zoneId", zone.zoneId)
                    put("name", zone.name)
                    put("riskLevel", zone.riskLevel)
                    put("color", if (zone.riskLevel == "CRITICAL") "#DC2626" else "#EA580C")
                })
            })
        }
    }
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

private fun createTeardropBitmap(color: Int, badge: String): Bitmap {
    val w = 54
    val h = 68
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = color
    paint.style = Paint.Style.FILL

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Draw teardrop path
    val path = Path()
    val r = 24f
    val cx = w / 2f
    val cy = r + 4f
    path.addCircle(cx, cy, r, Path.Direction.CW)
    path.moveTo(cx - r * 0.7f, cy + r * 0.7f)
    path.lineTo(cx, h - 4f)
    path.lineTo(cx + r * 0.7f, cy + r * 0.7f)
    path.close()

    canvas.drawPath(path, paint)
    canvas.drawPath(path, strokePaint)

    // Inner white dot
    val whiteDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 6f, whiteDot)

    // Badge text inside or at top
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = 9f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(badge.take(4), cx, cy - 8f, textPaint)

    return bitmap
}

private fun addTrafficLayers(style: Style, geoJsonString: String) {
    try {
        style.addSource(GeoJsonSource(TRAFFIC_SOURCE_ID, FeatureCollection.fromJson(geoJsonString)))
        style.addLayer(
            LineLayer(TRAFFIC_LINE_LAYER_ID, TRAFFIC_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(toColor(get("color"))),
                PropertyFactory.lineWidth(4.5f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    } catch (_: Exception) {
    }
}

private fun addApproachLayers(style: Style, geoJsonString: String) {
    try {
        style.addSource(GeoJsonSource(APPROACH_SOURCE_ID, FeatureCollection.fromJson(geoJsonString)))
        style.addLayer(
            LineLayer(APPROACH_LINE_LAYER_ID, APPROACH_SOURCE_ID).withProperties(
                PropertyFactory.lineColor("#8B5CF6"),
                PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                PropertyFactory.lineOpacity(0.9f)
            )
        )
    } catch (_: Exception) {
    }
}

private fun addSelfPulseLayer(style: Style, geoJsonString: String) {
    try {
        style.addSource(GeoJsonSource(SELF_PULSE_SOURCE_ID, FeatureCollection.fromJson(geoJsonString)))
        style.addLayer(
            CircleLayer(SELF_PULSE_CIRCLE_LAYER_ID, SELF_PULSE_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(18f),
                PropertyFactory.circleColor(toColor(get("color"))),
                PropertyFactory.circleOpacity(0.35f),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleStrokeColor(toColor(get("color"))),
                PropertyFactory.circleStrokeOpacity(0.85f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_VIEWPORT)
            )
        )
    } catch (_: Exception) {
    }
}

private fun addRadarLayers(style: Style, geoJsonString: String) {
    try {
        style.addSource(GeoJsonSource(RADAR_SOURCE_ID, FeatureCollection.fromJson(geoJsonString)))
        // CircleLayer aligned to viewport with toColor() guarantees colored bases always face screen over 3D buildings
        style.addLayer(
            CircleLayer(RADAR_CIRCLE_LAYER_ID, RADAR_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleColor(toColor(get("color"))),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_VIEWPORT)
            )
        )
        // Icon-only SymbolLayer: ZERO textField font-glyph dependency -> ALWAYS renders custom Canvas avatars & green dots!
        style.addLayer(
            SymbolLayer(RADAR_SYMBOL_LAYER_ID, RADAR_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)
            )
        )
        // Separate optional text label layer so glyph fetch failures never hide avatar icons
        style.addLayer(
            SymbolLayer(RADAR_LABEL_LAYER_ID, RADAR_SOURCE_ID).withProperties(
                PropertyFactory.textField(get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textOptional(true),
                PropertyFactory.textSize(10f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#0A111A"),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textOffset(arrayOf(0f, 1.9f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true)
            )
        )
    } catch (_: Exception) {
    }
}

private fun ensureAllMapLayers(
    context: Context,
    style: Style,
    geoJsonPins: String,
    dangerZonesGeoJson: String,
    trafficLinesGeoJson: String,
    approachLineGeoJson: String,
    selfPulseGeoJson: String
) {
    ensureCustomPinBitmaps(context, style)

    val dangerSrc = style.getSourceAs<GeoJsonSource>(DANGER_ZONE_SOURCE_ID)
    if (dangerSrc != null) {
        dangerSrc.setGeoJson(FeatureCollection.fromJson(dangerZonesGeoJson))
    } else {
        addDangerZoneLayers(style, dangerZonesGeoJson)
    }

    val trafficSrc = style.getSourceAs<GeoJsonSource>(TRAFFIC_SOURCE_ID)
    if (trafficSrc != null) {
        trafficSrc.setGeoJson(FeatureCollection.fromJson(trafficLinesGeoJson))
    } else {
        addTrafficLayers(style, trafficLinesGeoJson)
    }

    val approachSrc = style.getSourceAs<GeoJsonSource>(APPROACH_SOURCE_ID)
    if (approachSrc != null) {
        approachSrc.setGeoJson(FeatureCollection.fromJson(approachLineGeoJson))
    } else {
        addApproachLayers(style, approachLineGeoJson)
    }

    val pulseSrc = style.getSourceAs<GeoJsonSource>(SELF_PULSE_SOURCE_ID)
    if (pulseSrc != null) {
        pulseSrc.setGeoJson(FeatureCollection.fromJson(selfPulseGeoJson))
    } else {
        addSelfPulseLayer(style, selfPulseGeoJson)
    }

    val radarSrc = style.getSourceAs<GeoJsonSource>(RADAR_SOURCE_ID)
    if (radarSrc != null) {
        radarSrc.setGeoJson(FeatureCollection.fromJson(geoJsonPins))
    } else {
        addRadarLayers(style, geoJsonPins)
    }
}

internal fun buildAllMapFeaturesGeoJson(
    pins: List<LiveRadarPin>,
    activeIncidents: List<IncidentEntity>,
    nearbyHexAlerts: List<HexRescueAlert>,
    nearbyUsers: List<NearbySupportUser>,
    pois: List<com.example.util.PlaceMapPoi>,
    approachingResponder: ApproachingResponderInfo?,
    highRiskZones: List<HighRiskZoneEntity> = emptyList(),
    isOnline: Boolean = true,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val features = JSONArray()
    val peerCutoff = nowMillis - LiveRadarManager.PEER_ONLINE_FRESHNESS_MS

    // 1. Live Circle Pins: Self ALWAYS renders; Peers ONLY render when BOTH self & peer have active internet
    val selfPin = pins.firstOrNull { it.relationship.equals("Self", true) }
    pins.forEach { pin ->
        if (pin.latitude != 0.0 || pin.longitude != 0.0) {
            val isSelf = pin.relationship.equals("Self", true)
            val peerConnected = isOnline && pin.sharingActive && pin.isPeerOnline && pin.updatedAt >= peerCutoff
            if (!isSelf && !peerConnected) return@forEach

            val isSpouse = pin.relationship.equals("Spouse", ignoreCase = true)
            val isDistress = pin.isSosActive || (isSelf && activeIncidents.isNotEmpty())
            val icon = when {
                isDistress -> "avatar_victim_red"
                isSelf -> "avatar_self_blue"
                isSpouse -> "avatar_spouse_heart"
                else -> "avatar_family_gold"
            }
            val speedText = if (pin.speed > 0.8f) " • ${(pin.speed * 3.6f).toInt()} km/h" else ""
            val connTag = if (isSelf && !isOnline) " • Offline" else ""
            val statusTag = when {
                isDistress -> "⚠️ SOS VICTIM: ${pin.name}"
                isSelf -> "You (Self • ${pin.battery}%$speedText$connTag)"
                else -> "${pin.name} (${pin.relationship} • ${pin.battery}%)"
            }
            val color = when {
                isDistress -> "#DC2626"
                isSelf -> "#2563EB"
                isSpouse -> "#059669"
                pin.sharingActive -> "#10B981"
                else -> "#F59E0B"
            }
            features.put(createGeoJsonPoint(pin.longitude, pin.latitude, statusTag, color, icon))
        }
    }

    // 2. Peer & Cloud Real-Time Overlays: strictly gated on active internet connection for both self and peers
    if (isOnline) {
        // Approaching Responder (Hero / Savior)
        approachingResponder?.let { resp ->
            val label = "🛡️ SAVIOR: ${resp.responderName} (${resp.distanceMeters.roundToInt()}m away)"
            features.put(createGeoJsonPoint(resp.responderLng, resp.responderLat, label, "#10B981", "avatar_savior_green"))
        }

        // Nearby Active Users (Green Dots) & RTDB Victim Avatars (Red Avatar)
        nearbyUsers.forEach { user ->
            if (user.isOnline && user.updatedAtMillis >= peerCutoff) {
                if (user.isEmergencyVictim) {
                    features.put(
                        createGeoJsonPoint(
                            user.longitude,
                            user.latitude,
                            "⚠️ SOS VICTIM: ${user.displayName}",
                            "#DC2626",
                            "avatar_victim_red"
                        )
                    )
                } else {
                    val role = if (user.isVerifiedResponder) "Responder" else "Active Citizen"
                    val label = "${user.displayName} ($role)"
                    features.put(createGeoJsonPoint(user.longitude, user.latitude, label, "#22C55E", "dot_nearby_green"))
                }
            }
        }

        // Peer Emergency & Rescue Requests (Red Victim Avatar)
        nearbyHexAlerts.forEach { hex ->
            if (hex.victimLatitude != 0.0 || hex.victimLongitude != 0.0) {
                features.put(createGeoJsonPoint(hex.victimLongitude, hex.victimLatitude, "⚠️ RESCUE: ${hex.victimName}", "#DC2626", "avatar_victim_red"))
            }
        }
    }

    // Local Active SOS Incidents (skip duplicating if self pin already flipped to avatar_victim_red at same spot)
    activeIncidents.forEach { inc ->
        if ((inc.latitude != 0.0 || inc.longitude != 0.0) && selfPin == null) {
            features.put(createGeoJsonPoint(inc.longitude, inc.latitude, "⚠️ SOS: ${inc.incidentId}", "#DC2626", "avatar_victim_red"))
        }
    }

    // 5. Reported High-Risk Danger Zones
    highRiskZones.forEach { zone ->
        if (zone.isEnabled && (zone.latitude != 0.0 || zone.longitude != 0.0)) {
            val label = "⚠️ DANGER: ${zone.name} (${zone.riskLevel})"
            features.put(createGeoJsonPoint(zone.longitude, zone.latitude, label, "#DC2626", "hazard_danger_zone"))
        }
    }

    // 6. Place POIs (Police, Hospitals, Transit, Named Hubs)
    pois.forEach { poi ->
        val icon = when (poi.category) {
            PlacePoiCategory.POLICE -> "poi_police"
            PlacePoiCategory.HOSPITAL -> "poi_hospital"
            PlacePoiCategory.TRANSIT -> "poi_transit"
            else -> "poi_hub"
        }
        val color = when (poi.category) {
            PlacePoiCategory.POLICE -> "#1D4ED8"
            PlacePoiCategory.HOSPITAL -> "#BE123C"
            PlacePoiCategory.TRANSIT -> "#D97706"
            else -> "#4338CA"
        }
        val catName = when (poi.category) {
            PlacePoiCategory.POLICE -> "Police Station"
            PlacePoiCategory.HOSPITAL -> "Hospital / Medical"
            PlacePoiCategory.TRANSIT -> "Transit Station"
            else -> "Safety Hub"
        }
        features.put(createGeoJsonPoint(poi.longitude, poi.latitude, "${poi.name} ($catName)", color, icon))
    }

    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

private fun buildTrafficCorridorsGeoJson(corridors: List<com.example.util.TrafficCorridorSegment>): String {
    val features = JSONArray()
    corridors.forEach { corr ->
        if (corr.coordinates.size >= 2) {
            val coordsArr = JSONArray()
            corr.coordinates.forEach { (lat, lng) ->
                coordsArr.put(JSONArray().apply {
                    put(lng)
                    put(lat)
                })
            }
            features.put(JSONObject().apply {
                put("type", "Feature")
                put("geometry", JSONObject().apply {
                    put("type", "LineString")
                    put("coordinates", coordsArr)
                })
                put("properties", JSONObject().apply {
                    put("roadName", corr.roadName)
                    put("color", corr.colorHex)
                })
            })
        }
    }
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

private fun buildApproachLineGeoJson(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): String {
    val features = JSONArray()
    val coords = JSONArray().apply {
        put(JSONArray().apply {
            put(fromLng)
            put(fromLat)
        })
        put(JSONArray().apply {
            put(toLng)
            put(toLat)
        })
    }
    features.put(JSONObject().apply {
        put("type", "Feature")
        put("geometry", JSONObject().apply {
            put("type", "LineString")
            put("coordinates", coords)
        })
        put("properties", JSONObject().apply {
            put("color", "#8B5CF6")
        })
    })
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

private fun buildEmptyFeatureCollectionGeoJson(): String {
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", JSONArray())
    }.toString()
}

private fun createGeoJsonPoint(lng: Double, lat: Double, label: String, colorHex: String, iconName: String): JSONObject {
    return JSONObject().apply {
        put("type", "Feature")
        put("geometry", JSONObject().apply {
            put("type", "Point")
            put("coordinates", JSONArray().apply {
                put(lng)
                put(lat)
            })
        })
        put("properties", JSONObject().apply {
            put("label", label)
            put("color", colorHex)
            put("icon", iconName)
        })
    }
}

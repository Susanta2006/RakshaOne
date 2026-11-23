package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.components.FineLocationPermissionBanner
import com.example.ui.components.rememberFineLocationPermissionState
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripMonitoringScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val activeTrip by viewModel.activeTrip.collectAsStateWithLifecycle()
    val activeTripWaypoints by viewModel.activeTripWaypoints.collectAsStateWithLifecycle()
    val tripTrackingActive by viewModel.tripTrackingActive.collectAsStateWithLifecycle()
    val liveGps by viewModel.liveTripLocation.collectAsStateWithLifecycle()
    val realTimeLocation by viewModel.realTimeLocation.collectAsStateWithLifecycle()
    val emergencyInfo by viewModel.emergencyInfo.collectAsStateWithLifecycle()
    val trustedContacts by viewModel.trustedContacts.collectAsStateWithLifecycle()
    val highRiskZones by viewModel.highRiskZones.collectAsStateWithLifecycle()
    val savedRoutines by viewModel.savedRoutines.collectAsStateWithLifecycle()
    val activeRoutine by viewModel.activeRoutine.collectAsStateWithLifecycle()
    val currentDeviationMeters by viewModel.currentDeviationMeters.collectAsStateWithLifecycle()
    val tripCheckInRemainingSeconds by viewModel.tripCheckInRemainingSeconds.collectAsStateWithLifecycle()
    val isTripCheckInOverdue by viewModel.isTripCheckInOverdue.collectAsStateWithLifecycle()
    val tripAnomalyState by viewModel.tripAnomalyState.collectAsStateWithLifecycle()
    var showAllWaypoints by remember { mutableStateOf(false) }

    val fineLocationPermissionState = rememberFineLocationPermissionState(
        onFineLocationGranted = { viewModel.startLocationTracking() }
    )

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.statusEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val facilities = remember(emergencyInfo) {
        viewModel.repository.getNearbyFacilities()
    }

    var originInput by remember { mutableStateOf("Current GPS Location") }
    var destinationInput by remember { mutableStateOf("") }
    var intervalMinutes by remember { mutableIntStateOf(5) }
    var corridorRadiusMeters by remember { mutableIntStateOf(500) }
    var stopThresholdMinutes by remember { mutableIntStateOf(3) }
    val selectedContactPhones = remember { mutableStateListOf<String>() }

    var showMarkZoneDialog by remember { mutableStateOf(false) }
    var zoneNameInput by remember { mutableStateOf("") }
    var zoneAdvisoryInput by remember { mutableStateOf("") }

    var showRoutineDialog by remember { mutableStateOf(false) }
    var routineNameInput by remember { mutableStateOf("") }

    remember(trustedContacts) {
        if (selectedContactPhones.isEmpty() && trustedContacts.isNotEmpty()) {
            trustedContacts.forEach { selectedContactPhones.add(it.phone) }
        }
        true
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Safe Journey & Live Geofencing",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "Real-time Google Location Services GPS tracking, routine deviation alerts, and user-marked risk geofences.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!fineLocationPermissionState.isFineLocationGranted) {
                item {
                    FineLocationPermissionBanner(permissionState = fineLocationPermissionState)
                }
            }

            // Active Trip Card or Trip Starter
            item {
                if (activeTrip != null) {
                    val trip = activeTrip!!
                    val tripHasStoredCoords = (trip.currentLatitude ?: 0.0) != 0.0 || (trip.currentLongitude ?: 0.0) != 0.0
                    val currentLat: Double? = liveGps?.first
                        ?: realTimeLocation?.latitude
                        ?: if (tripHasStoredCoords) trip.currentLatitude else null
                    val currentLng: Double? = liveGps?.second
                        ?: realTimeLocation?.longitude
                        ?: if (tripHasStoredCoords) trip.currentLongitude else null

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("active_trip_card"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(SafetyTeal, PoliceBlue)))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Navigation, contentDescription = null, tint = SafetyTeal)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "LIVE TRIP ESCORT ACTIVE",
                                        fontWeight = FontWeight.Black,
                                        color = SafetyTeal,
                                        fontSize = 13.sp
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SafetyTeal.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = trip.status,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SafetyTeal
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Destination: ${trip.destination}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Origin: ${trip.origin}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = SafetyTeal,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = "Live GPS (Fused Location)",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = if (currentLat != null && currentLng != null) {
                                                    "${String.format(Locale.US, "%.5f", currentLat)}, ${String.format(Locale.US, "%.5f", currentLng)}"
                                                } else {
                                                    "Acquiring real GPS fix..."
                                                },
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Speed,
                                            contentDescription = null,
                                            tint = PoliceBlue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = "Speed",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "${String.format(Locale.US, "%.1f", trip.speedKmh)} km/h",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val contactsToNotify = trustedContacts.filter { it.phone in trip.sharedContactPhones.split(";") }
                                        viewModel.shareCurrentTripGps(contactsToNotify)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1.4f)
                                        .testTag("share_live_gps_button")
                                ) {
                                    Icon(Icons.Default.ShareLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share GPS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                FilledTonalButton(
                                    onClick = { viewModel.syncTripGpsNow() },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .testTag("sync_trip_gps_button")
                                ) {
                                    Icon(Icons.Default.MyLocation, contentDescription = "Save GPS Checkpoint", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Log Fix", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }

                                if (currentLat != null && currentLng != null) {
                                    OutlinedButton(
                                        onClick = { viewModel.openIncidentInMaps(currentLat, currentLng, "Live Trip GPS") },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(0.9f)
                                    ) {
                                        Icon(Icons.Default.Map, contentDescription = "Open Trip Location on Maps", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Map", fontSize = 12.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Live Automated Route Deviation & Prolonged Stop Guard Telemetry
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (tripAnomalyState.deviationAlertTriggered || tripAnomalyState.prolongedStopAlertTriggered) {
                                    EmergencyRed.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("trip_anomaly_guard_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Trip Guard (Deviation & Stop Detector)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (tripAnomalyState.deviationAlertTriggered || tripAnomalyState.prolongedStopAlertTriggered) EmergencyRed else PoliceBlue
                                        )
                                        Text(
                                            text = if (tripAnomalyState.deviationAlertTriggered || tripAnomalyState.prolongedStopAlertTriggered) "ALERT SENT" else "ACTIVE",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (tripAnomalyState.deviationAlertTriggered || tripAnomalyState.prolongedStopAlertTriggered) EmergencyRed else SafetyTeal
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Corridor Distance", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                text = "${tripAnomalyState.distanceFromAnchorMeters.toInt()}m / ${tripAnomalyState.allowedCorridorMeters.toInt()}m",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (tripAnomalyState.deviationAlertTriggered) EmergencyRed else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Stationary Timer", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                text = "${tripAnomalyState.stationaryDurationSeconds}s / ${tripAnomalyState.prolongedStopThresholdSeconds}s",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (tripAnomalyState.prolongedStopAlertTriggered) EmergencyRed else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                    tripAnomalyState.lastAlertMessage?.let { alertMsg ->
                                        Text(
                                            text = "Alert Dispatched: $alertMsg",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = EmergencyRed
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { viewModel.resetTripAnomalyTimer() },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("reset_trip_anomaly_button")
                                            ) {
                                                Text("I'm Okay (Reset)", fontSize = 11.sp)
                                            }
                                            Button(
                                                onClick = { viewModel.triggerImmediateSos("Trip Alert Escalated to SOS") },
                                                colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Trigger SOS", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Non-critical trip anomalies notify Trusted Contacts only (R1/R2). 112 escalation requires explicit SOS (R3).",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Room-persisted FusedLocationProviderClient coordinate breadcrumb trail
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("trip_waypoint_trail_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Room GPS Trail (5s Intervals)",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = if (tripTrackingActive) {
                                                    "FusedLocationProviderClient active"
                                                } else if (!fineLocationPermissionState.isFineLocationGranted) {
                                                    "Grant Fine Location to record intervals"
                                                } else {
                                                    "Starting GPS interval listener..."
                                                },
                                                fontSize = 11.sp,
                                                color = if (tripTrackingActive) SafetyTeal else WarningAmber
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = SafetyTeal.copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = "${activeTripWaypoints.size} saved",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SafetyTeal,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    if (activeTripWaypoints.isEmpty()) {
                                        Text(
                                            text = "Waiting for first interval GPS coordinate fix to persist in Room...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                                        val visibleWaypoints = if (showAllWaypoints) activeTripWaypoints.take(15) else activeTripWaypoints.take(3)
                                        visibleWaypoints.forEachIndexed { idx, wp ->
                                            if (idx > 0) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "${String.format(Locale.US, "%.5f", wp.latitude)}, ${String.format(Locale.US, "%.5f", wp.longitude)}",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = "Accuracy ±${wp.accuracyMeters.toInt()}m • ${String.format(Locale.US, "%.1f", wp.speedKmh)} km/h",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Text(
                                                    text = timeFormatter.format(Date(wp.timestamp)),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (activeTripWaypoints.size > 3) {
                                            TextButton(
                                                onClick = { showAllWaypoints = !showAllWaypoints },
                                                modifier = Modifier.align(Alignment.End)
                                            ) {
                                                Text(
                                                    text = if (showAllWaypoints) "Show fewer" else "Show more (${activeTripWaypoints.size} total)",
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (isTripCheckInOverdue) "CHECK-IN OVERDUE!" else "Periodic Safety Check-In",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = if (isTripCheckInOverdue) EmergencyRed else MaterialTheme.colorScheme.onSurface
                                    )
                                    val timerText = tripCheckInRemainingSeconds?.let { remSec ->
                                        val mins = remSec / 60
                                        val secs = remSec % 60
                                        if (isTripCheckInOverdue) {
                                            "Scheduled check-in expired — confirm status immediately"
                                        } else {
                                            "Next check-in due in ${String.format(Locale.US, "%02d:%02d", mins, secs)} (every ${trip.checkInIntervalMinutes}m)"
                                        }
                                    } ?: "Scheduled interval: every ${trip.checkInIntervalMinutes} minutes"
                                    Text(
                                        text = timerText,
                                        fontSize = 12.sp,
                                        fontWeight = if (isTripCheckInOverdue) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isTripCheckInOverdue) EmergencyRed else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.checkInTrip(trip.tripId, isSafe = true) },
                                            colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("I'm Safe", fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = { viewModel.checkInTrip(trip.tripId, isSafe = false) },
                                            colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Need Help", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { viewModel.completeTrip(trip) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Arrived Safely")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.cancelTrip(trip) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("End Trip")
                                }
                            }
                        }
                    }
                }
            }

            // Safe Routes & Harmful Routes Real-Time Advisory Component
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(SafetyTeal, WarningAmber)))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Route, contentDescription = null, tint = SafetyTeal)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Safe Routes & Harmful Routes Advisory",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = SafetyTeal
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Real-time AI telemetry evaluates geofenced high-risk zones, active incidents, and lighting corridors to recommend the safest navigation path.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = SafetyTeal.copy(alpha = 0.15f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Safe Route Corridor", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = SafetyTeal)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Main Street Patrol • High Lighting • 0 Risk Deviations", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = EmergencyRed.copy(alpha = 0.12f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = EmergencyRed, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Harmful Route Alert", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = EmergencyRed)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("${highRiskZones.size} High-Risk Zone(s) Avoided • Low Lighting Area", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Start Monitored Journey",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = originInput,
                                onValueChange = { originInput = it },
                                label = { Text("Starting Point") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = destinationInput,
                                onValueChange = { destinationInput = it },
                                label = { Text("Destination (e.g. Metro Station, Home)") },
                                placeholder = { Text("Enter destination address") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Check-in Frequency: Every $intervalMinutes min",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(1, 5, 15).forEach { mins ->
                                    Button(
                                        onClick = { intervalMinutes = mins },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (intervalMinutes == mins) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            contentColor = if (intervalMinutes == mins) Color.White else MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("${mins}m")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Safe Corridor Radius: ${corridorRadiusMeters}m",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(250, 500, 1000).forEach { radiusM ->
                                    Button(
                                        onClick = { corridorRadiusMeters = radiusM },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (corridorRadiusMeters == radiusM) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            contentColor = if (corridorRadiusMeters == radiusM) Color.White else MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("${radiusM}m")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Unexpected Stop Alert Threshold: ${stopThresholdMinutes} min",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(1, 3, 5).forEach { stopMin ->
                                    Button(
                                        onClick = { stopThresholdMinutes = stopMin },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (stopThresholdMinutes == stopMin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            contentColor = if (stopThresholdMinutes == stopMin) Color.White else MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("${stopMin}m stop")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Share Live GPS with Emergency Contacts:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            if (trustedContacts.isEmpty()) {
                                Text(
                                    text = "No trusted contacts configured yet. Go to Circle tab to add contacts.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            } else {
                                trustedContacts.forEach { contact ->
                                    val isSelected = contact.phone in selectedContactPhones
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isSelected) selectedContactPhones.remove(contact.phone)
                                                else selectedContactPhones.add(contact.phone)
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                if (checked) selectedContactPhones.add(contact.phone)
                                                else selectedContactPhones.remove(contact.phone)
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = SafetyTeal)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(contact.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            Text("${contact.relationship} • ${contact.phone}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    if (destinationInput.isNotBlank()) {
                                        viewModel.startTrip(
                                            origin = originInput,
                                            destination = destinationInput,
                                            duration = 30,
                                            interval = intervalMinutes,
                                            contactPhones = selectedContactPhones.toList(),
                                            allowedCorridorMeters = corridorRadiusMeters.toFloat(),
                                            prolongedStopThresholdMinutes = stopThresholdMinutes
                                        )
                                    }
                                },
                                enabled = destinationInput.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("start_trip_button")
                            ) {
                                Icon(Icons.Default.AddRoad, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start Trip Escort & Live GPS Sharing", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

            // Routine Deviation Geofencing
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Route, contentDescription = null, tint = PoliceBlue)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Routine Deviation Guard", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            FilledTonalButton(onClick = { showRoutineDialog = true }) {
                                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Lock GPS Zone", fontSize = 11.sp)
                            }
                        }

                        if (activeRoutine != null) {
                            val r = activeRoutine!!
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (currentDeviationMeters > r.maxDeviationMeters) EmergencyRed.copy(alpha = 0.14f) else SafetyTeal.copy(alpha = 0.14f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "Active: ${r.name} (Allowed radius: ${r.maxDeviationMeters.toInt()}m)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Current deviation: ${currentDeviationMeters.toInt()}m",
                                        fontSize = 12.sp,
                                        color = if (currentDeviationMeters > r.maxDeviationMeters) EmergencyRed else SafetyTeal,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        if (savedRoutines.isEmpty()) {
                            Text(
                                text = "No routine geofences saved yet. Tap 'Lock GPS Zone' to alert contacts if you move outside a 250m safety corridor.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            savedRoutines.forEach { routine ->
                                val isMonitoring = activeRoutine?.routineId == routine.routineId
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(routine.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(
                                            "Max deviation: ${routine.maxDeviationMeters.toInt()}m",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(onClick = { viewModel.toggleRoutineMonitoring(routine) }) {
                                        Text(if (isMonitoring) "Stop" else "Monitor", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // User-Marked High-Risk Geofence Zones
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("High-Risk Geofence Zones", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            FilledTonalButton(
                                onClick = { showMarkZoneDialog = true },
                                modifier = Modifier.testTag("mark_high_risk_button")
                            ) {
                                Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Mark Current GPS", fontSize = 11.sp)
                            }
                        }

                        if (highRiskZones.isEmpty()) {
                            Text(
                                text = "No high-risk zones marked. Mark unsafe streets or dark areas at your real GPS position to trigger automatic proximity alerts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            highRiskZones.forEach { zone ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(zone.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text(
                                                "${zone.radiusMeters.toInt()}m radius • ${String.format(Locale.US, "%.4f", zone.latitude)}, ${String.format(Locale.US, "%.4f", zone.longitude)}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(zone.advisoryMessage, fontSize = 11.sp, color = WarningAmber)
                                        }
                                        Row {
                                            IconButton(onClick = { viewModel.openIncidentInMaps(zone.latitude, zone.longitude, zone.name) }) {
                                                Icon(Icons.Default.Map, contentDescription = "Open in Maps", tint = PoliceBlue)
                                            }
                                            IconButton(onClick = { viewModel.deleteHighRiskZone(zone.zoneId) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete zone", tint = EmergencyRed)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Regional Emergency Dispatch & Live Google Maps Search
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Regional Emergency Dispatch",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = emergencyInfo.countryName,
                        style = MaterialTheme.typography.labelSmall,
                        color = SafetyTeal,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            items(facilities) { facility ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (facility.type) {
                                                "Police" -> PoliceBlue.copy(alpha = 0.15f)
                                                "Hospital" -> EmergencyRed.copy(alpha = 0.15f)
                                                else -> WarningAmber.copy(alpha = 0.15f)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = when (facility.type) {
                                            "Police" -> Icons.Default.LocalPolice
                                            "Hospital" -> Icons.Default.LocalHospital
                                            else -> Icons.Default.LocationOn
                                        },
                                        contentDescription = facility.type,
                                        tint = when (facility.type) {
                                            "Police" -> PoliceBlue
                                            "Hospital" -> EmergencyRed
                                            else -> WarningAmber
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = facility.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = facility.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { viewModel.dialNumber(facility.phone) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "Call", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Call ${facility.phone}", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val searchQuery = when (facility.type) {
                                        "Police" -> "police station"
                                        "Hospital" -> "hospital emergency room"
                                        else -> "fire station"
                                    }
                                    viewModel.openNearbyEmergencyOnMaps(searchQuery)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Find on Maps", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showMarkZoneDialog) {
        AlertDialog(
            onDismissRequest = { showMarkZoneDialog = false },
            title = { Text("Mark High-Risk Geofence", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Registers a 300m Google Location Services geofence at your exact live GPS coordinates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = zoneNameInput,
                        onValueChange = { zoneNameInput = it },
                        label = { Text("Zone Label") },
                        placeholder = { Text("e.g., Unlit Underpass") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = zoneAdvisoryInput,
                        onValueChange = { zoneAdvisoryInput = it },
                        label = { Text("Safety Advisory (optional)") },
                        placeholder = { Text("e.g., Low lighting after 9 PM") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (zoneNameInput.isNotBlank()) {
                            viewModel.markCurrentLocationAsHighRisk(zoneNameInput, zoneAdvisoryInput)
                            zoneNameInput = ""
                            zoneAdvisoryInput = ""
                            showMarkZoneDialog = false
                        }
                    },
                    enabled = zoneNameInput.isNotBlank()
                ) {
                    Text("Save Zone")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showMarkZoneDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRoutineDialog) {
        AlertDialog(
            onDismissRequest = { showRoutineDialog = false },
            title = { Text("Lock Safe Routine GPS Zone", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Saves your current GPS position as a 250m safety corridor. If you leave this radius, alerts trigger automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = routineNameInput,
                        onValueChange = { routineNameInput = it },
                        label = { Text("Routine Name") },
                        placeholder = { Text("e.g., Campus Perimeter, Night Shift Walk") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (routineNameInput.isNotBlank()) {
                            viewModel.saveAndStartRoutineFromCurrentGps(routineNameInput)
                            routineNameInput = ""
                            showRoutineDialog = false
                        }
                    },
                    enabled = routineNameInput.isNotBlank()
                ) {
                    Text("Lock & Monitor")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRoutineDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

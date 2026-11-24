package com.example.ui.screens

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.HexAlertStatus
import com.example.data.model.HexRescueAlert
import com.example.data.model.IncidentEntity
import com.example.risk.HexRescueLifecycleState
import com.example.ui.MainViewModel
import com.example.ui.components.Dispatch3DMapSection
import com.example.ui.components.ResponderSelfieVerifyDialog
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.TacticalBorder
import com.example.ui.theme.TacticalCard
import com.example.ui.theme.TacticalNavy
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.WarningAmber
import com.example.util.BleEmergencyRelay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GovernmentDashboardScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val allIncidents by viewModel.allIncidents.collectAsState()
    val activeIncidents by viewModel.activeIncidents.collectAsState()
    val auditLogs by viewModel.auditLogs.collectAsState()
    val allEvidence by viewModel.allEvidence.collectAsState()
    val realTimeLocation by viewModel.realTimeLocation.collectAsState()
    val nearbyHexAlerts by viewModel.nearbyHexAlerts.collectAsState()
    val allCloudHexAlerts by viewModel.allCloudHexAlerts.collectAsState()
    val activeIncomingHexAlert by viewModel.activeIncomingHexAlert.collectAsState()
    val activeOutgoingHexAlert by viewModel.activeOutgoingHexAlert.collectAsState()
    val activeGuardianHighAlert by viewModel.activeGuardianHighAlert.collectAsState()
    val bleDiscoveries by viewModel.bleDiscoveries.collectAsState()
    val bleRelayStatus by viewModel.bleRelayStatus.collectAsState()
    val latestPoliceDossier by viewModel.latestPoliceDossier.collectAsState()
    val currentRiskAssessment by viewModel.currentRiskAssessment.collectAsState()
    val radarPins by viewModel.radarPins.collectAsState()
    val nearbyUsers by viewModel.nearbyUsers.collectAsState()
    val placeIntel by viewModel.placeIntel.collectAsState()
    val trustedContacts by viewModel.trustedContacts.collectAsState()
    val installedContactsMap by viewModel.installedContactsMap.collectAsState()
    val highRiskZones by viewModel.highRiskZones.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val isLiveLocationSharingEnabled by viewModel.isLiveLocationSharingEnabled.collectAsState()
    val disconnectAlertMessage by viewModel.disconnectAlertMessage.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()

    val context = LocalContext.current
    var selectedSection by remember { mutableIntStateOf(0) }
    var selectedIncident by remember { mutableStateOf<IncidentEntity?>(null) }
    var filterPriority by remember { mutableStateOf("ALL") }
    var pendingHexAcceptAlert by remember { mutableStateOf<HexRescueAlert?>(null) }

    LaunchedEffect(selectedSection) {
        viewModel.setDispatchTabVisible(selectedSection == 2)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.setDispatchTabVisible(false) }
    }

    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onBluetoothEnablePromptResult(
            result.resultCode == Activity.RESULT_OK || viewModel.isBluetoothMeshEnabled()
        )
    }

    val meshPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshBluetoothMeshState()
        if (viewModel.hasBluetoothConnectPermission() && !viewModel.isBluetoothMeshEnabled()) {
            try {
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {}
        }
    }

    fun requestDispatchMeshBluetoothEnable() {
        val required = BleEmergencyRelay.requiredMeshPermissions()
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            meshPermissionLauncher.launch(missing.toTypedArray())
        } else if (!viewModel.isBluetoothMeshEnabled()) {
            try {
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {}
        } else {
            viewModel.refreshBluetoothMeshState(autoStartIfEligible = true)
        }
    }

    BackHandler {
        viewModel.setOperatorMode(false)
    }

    val criticalCount = activeIncidents.count { it.priority == "CRITICAL" }
    val resolvedCount = allIncidents.count { it.status == "RESOLVED" || it.status == "CLOSED" }
    val availableUnits = listOf("PCR Patrol", "Ambulance Unit", "Quick Response Team", "Fire & Rescue")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TacticalNavy),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Dispatch Header Card (functional controls only: exit back to Citizen SOS)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, TacticalBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PoliceBlue.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalPolice,
                                contentDescription = null,
                                tint = PoliceBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "EMERGENCY & HEX DISPATCH",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                letterSpacing = 0.6.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Hexagonal Pickup Queue, BLE Relay & 112 Dossier (${currentRiskAssessment.riskLevel.code})",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextButton(
                        onClick = { viewModel.setOperatorMode(false) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("exit_operator_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Return to Citizen SOS",
                            tint = SafetyTeal,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Citizen",
                            color = SafetyTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live Room-Backed Metrics Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricBox("TOTAL", "${allIncidents.size}", PoliceBlue, Modifier.weight(1f))
                MetricBox("ACTIVE", "${activeIncidents.size}", WarningAmber, Modifier.weight(1f))
                MetricBox("CRITICAL", "$criticalCount", EmergencyRed, Modifier.weight(1f))
                MetricBox("RESOLVED", "$resolvedCount", SafetyTeal, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Navigation Tabs
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = TacticalSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryTabRow(
                    selectedTabIndex = selectedSection,
                    containerColor = TacticalSurface,
                    contentColor = Color.White
                ) {
                    Tab(
                        selected = selectedSection == 0,
                        onClick = { selectedSection = 0 },
                        text = {
                            Text(
                                "Queue (${allIncidents.size})",
                                fontSize = 11.sp,
                                fontWeight = if (selectedSection == 0) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedSection == 0) Color.White else Color.White.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    )
                    Tab(
                        selected = selectedSection == 1,
                        onClick = { selectedSection = 1 },
                        text = {
                            val hexCount = (nearbyHexAlerts.size + bleDiscoveries.size).coerceAtLeast(
                                if (activeIncomingHexAlert != null || activeOutgoingHexAlert != null) 1 else 0
                            )
                            Text(
                                "Hex ($hexCount)",
                                fontSize = 11.sp,
                                fontWeight = if (selectedSection == 1) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedSection == 1) Color.White else Color.White.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    )
                    Tab(
                        selected = selectedSection == 2,
                        onClick = { selectedSection = 2 },
                        text = {
                            Text(
                                "GPS",
                                fontSize = 11.sp,
                                fontWeight = if (selectedSection == 2) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedSection == 2) Color.White else Color.White.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    )
                    Tab(
                        selected = selectedSection == 3,
                        onClick = { selectedSection = 3 },
                        text = {
                            Text(
                                "Vaults (${allEvidence.size})",
                                fontSize = 11.sp,
                                fontWeight = if (selectedSection == 3) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedSection == 3) Color.White else Color.White.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (selectedSection) {
                0 -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("ALL", "CRITICAL", "ACTIVE", "RESOLVED").forEach { filter ->
                            val selected = filterPriority == filter
                            FilterChip(
                                selected = selected,
                                onClick = { filterPriority = filter },
                                label = {
                                    Text(
                                        text = filter,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = TacticalSurface,
                                    labelColor = Color.White.copy(alpha = 0.85f),
                                    selectedContainerColor = PoliceBlue,
                                    selectedLabelColor = Color.White
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = TacticalBorder,
                                    selectedBorderColor = PoliceBlue
                                )
                            )
                        }
                    }

                    val filteredList = allIncidents.filter { incident ->
                        when (filterPriority) {
                            "CRITICAL" -> incident.priority == "CRITICAL"
                            "ACTIVE" -> !listOf("RESOLVED", "CLOSED", "CANCELLED").contains(incident.status)
                            "RESOLVED" -> listOf("RESOLVED", "CLOSED").contains(incident.status)
                            else -> true
                        }
                    }

                    if (filteredList.isEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                            border = BorderStroke(1.dp, TacticalBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = PoliceBlue,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = "No Incidents in '$filterPriority' Queue",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Real SOS triggers and Incident Reports appear here automatically from the encrypted Room incident ledger.",
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 17.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = { viewModel.setOperatorMode(false) },
                                    border = BorderStroke(1.dp, SafetyTeal)
                                ) {
                                    Text(
                                        text = "Return to Citizen SOS View",
                                        color = SafetyTeal,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredList, key = { it.incidentId }) { incident ->
                                GovernmentIncidentItem(
                                    incident = incident,
                                    onClick = {
                                        selectedIncident = incident
                                        viewModel.loadIncidentEvents(incident.incidentId)
                                    }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(16.dp)) }
                        }
                    }
                }

                1 -> {
                    val combinedAlerts = buildList {
                        activeIncomingHexAlert?.let { add(it) }
                        nearbyHexAlerts.forEach { alert ->
                            if (none { it.alertId == alert.alertId }) add(alert)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("dispatch_hex_rescue_list"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = TacticalCard),
                                border = BorderStroke(1.dp, TacticalBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.BluetoothConnected,
                                        contentDescription = null,
                                        tint = if (bleRelayStatus.isScanning || bleRelayStatus.isAdvertising) SafetyTeal else WarningAmber
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "HEXAGONAL RESCUE + BLE MESH RELAY",
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = bleRelayStatus.statusMessage,
                                            color = Color.White.copy(alpha = 0.75f),
                                            fontSize = 11.sp
                                        )
                                    }
                                    if (!bleRelayStatus.isBluetoothEnabled || bleRelayStatus.permissionMissing) {
                                        OutlinedButton(
                                            onClick = { requestDispatchMeshBluetoothEnable() },
                                            border = BorderStroke(1.dp, WarningAmber),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.testTag("dispatch_enable_bt_button")
                                        ) {
                                            Text(
                                                text = if (bleRelayStatus.permissionMissing) "Grant BT" else "Enable BT",
                                                fontSize = 11.sp,
                                                color = WarningAmber,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else if (latestPoliceDossier != null) {
                                        OutlinedButton(
                                            onClick = { latestPoliceDossier?.let { viewModel.openLiveDossier(it) } },
                                            border = BorderStroke(1.dp, PoliceBlue),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Dossier", fontSize = 11.sp, color = PoliceBlue)
                                        }
                                    }
                                }
                            }
                        }

                        activeOutgoingHexAlert?.let { outgoing ->
                            item {
                                DispatchHexAlertCard(
                                    alert = outgoing,
                                    isOutgoingOwner = true,
                                    onAccept = {},
                                    onMarkEnRoute = {},
                                    onMarkArrived = {},
                                    onComplete = {},
                                    onNavigate = { lat, lng, label -> viewModel.openIncidentInMaps(lat, lng, label) },
                                    onExportZeroFir = { viewModel.exportAndShareZeroFirTimeline(outgoing.incidentId) }
                                )
                            }
                        }

                        val guardianLinkedHexAlert = activeGuardianHighAlert?.let { guardianAlert ->
                            allCloudHexAlerts.firstOrNull { hex ->
                                hex.incidentId == guardianAlert.dispatchId &&
                                    combinedAlerts.none { it.alertId == hex.alertId } &&
                                    activeOutgoingHexAlert?.alertId != hex.alertId
                            }
                        }
                        guardianLinkedHexAlert?.let { friendHex ->
                            item {
                                DispatchHexAlertCard(
                                    alert = friendHex,
                                    isOutgoingOwner = true,
                                    onAccept = {},
                                    onMarkEnRoute = {},
                                    onMarkArrived = {},
                                    onComplete = {},
                                    onNavigate = { lat, lng, label -> viewModel.openIncidentInMaps(lat, lng, label) },
                                    onExportZeroFir = { viewModel.exportAndShareZeroFirTimeline(friendHex.incidentId) }
                                )
                            }
                        }

                        if (combinedAlerts.isEmpty() && bleDiscoveries.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                                    border = BorderStroke(1.dp, TacticalBorder)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.Explore, contentDescription = null, tint = PoliceBlue, modifier = Modifier.size(36.dp))
                                        Text("No Nearby Hexagonal Rescue Requests", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(
                                            text = "Verified responders on active duty automatically receive Firestore + Bluetooth LE SOS pickup requests across 6 directional sectors.",
                                            color = Color.White.copy(alpha = 0.72f),
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            items(combinedAlerts, key = { "hex_${it.alertId}" }) { alert ->
                                DispatchHexAlertCard(
                                    alert = alert,
                                    isOutgoingOwner = false,
                                    onAccept = { pendingHexAcceptAlert = alert },
                                    onMarkEnRoute = { viewModel.markHexEnRoute(alert.alertId) },
                                    onMarkArrived = { viewModel.markHexArrived(alert.alertId) },
                                    onComplete = { viewModel.completeHexRescue(alert.alertId) },
                                    onNavigate = { lat, lng, label -> viewModel.openIncidentInMaps(lat, lng, label) },
                                    onExportZeroFir = { viewModel.exportAndShareZeroFirTimeline(alert.incidentId) }
                                )
                            }

                            items(bleDiscoveries, key = { "ble_${it.incidentId}" }) { ble ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = TacticalCard),
                                    border = BorderStroke(1.dp, EmergencyRed.copy(alpha = 0.65f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "BLE PROXIMITY SOS • RSSI ${ble.rssiDbm} dBm",
                                            color = EmergencyRed,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = "ID: ${ble.incidentId} • Est: ${ble.estimatedDistanceMeters.toInt()}m • ${ble.sectorHint}",
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 11.sp
                                        )
                                        if (ble.evidenceSummary.isNotBlank()) {
                                            Text(
                                                text = "Evidence Manifest: ${ble.evidenceSummary}",
                                                color = SafetyTeal,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    Dispatch3DMapSection(
                        radarPins = radarPins,
                        selfLocation = realTimeLocation,
                        activeIncidents = activeIncidents,
                        nearbyHexAlerts = nearbyHexAlerts,
                        nearbyUsers = nearbyUsers,
                        placeIntel = placeIntel,
                        trustedContacts = trustedContacts,
                        installedContactsMap = installedContactsMap,
                        highRiskZones = highRiskZones,
                        isOnline = isOnline,
                        isLiveSharingEnabled = isLiveLocationSharingEnabled,
                        disconnectAlertMessage = disconnectAlertMessage,
                        onToggleLiveSharing = { viewModel.setLiveLocationSharing(it) },
                        onDismissDisconnectAlert = { viewModel.clearDisconnectAlert() },
                        onOpenExternalMap = { lat, lng, label -> viewModel.openIncidentInMaps(lat, lng, label) },
                        onDialEmergency = { phone -> viewModel.dialNumber(phone) },
                        activeOutgoingHexAlert = activeOutgoingHexAlert,
                        allCloudHexAlerts = allCloudHexAlerts,
                        activeGuardianAlert = activeGuardianHighAlert
                    )
                }

                3 -> {
                    val acceptedHelpers = allCloudHexAlerts.filter { !it.acceptedResponderUid.isNullOrBlank() || it.status in listOf(HexAlertStatus.ACCEPTED, HexAlertStatus.EN_ROUTE, HexAlertStatus.ARRIVED) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Text(
                                "Connected Responders & Helpers (${acceptedHelpers.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        if (acceptedHelpers.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = TacticalCard),
                                    border = BorderStroke(1.dp, TacticalBorder)
                                ) {
                                    Text(
                                        "No active responders assigned yet. When nearby verified citizens accept via Bluetooth Hex mesh or Cloud, their contact info and live avatar will appear here.",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        } else {
                            items(acceptedHelpers, key = { "helper_${it.alertId}" }) { helper ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = TacticalCard),
                                    border = BorderStroke(1.dp, SafetyTeal)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = helper.acceptedResponderName ?: "Verified Helper",
                                                fontWeight = FontWeight.Bold,
                                                color = SafetyTeal,
                                                fontSize = 14.sp
                                            )
                                            Surface(
                                                color = SafetyTeal.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = helper.status.name,
                                                    color = SafetyTeal,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Phone: ${helper.acceptedResponderPhone ?: "Securely Linked"} • Distance: ${helper.distanceMeters.toInt()}m (${helper.estimatedWalkMinutes} min walk)",
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 12.sp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (!helper.acceptedResponderPhone.isNullOrBlank()) {
                                                Button(
                                                    onClick = { viewModel.dialNumber(helper.acceptedResponderPhone!!) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Call Helper", fontSize = 12.sp)
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    if (helper.acceptedResponderLat != null && helper.acceptedResponderLng != null) {
                                                        viewModel.openIncidentInMaps(helper.acceptedResponderLat!!, helper.acceptedResponderLng!!, helper.acceptedResponderName ?: "Helper")
                                                    }
                                                },
                                                border = BorderStroke(1.dp, SafetyTeal),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Navigation, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Track on Map", color = SafetyTeal, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Evidence Vault (${allEvidence.size}) • Audio, Photos & Videos",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        if (allEvidence.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = TacticalCard),
                                    border = BorderStroke(1.dp, TacticalBorder)
                                ) {
                                    Text(
                                        "No evidence files in vault. Captured audio recordings, dual-camera snapshots, and video clips from emergency activations are automatically transferred here and synced to Firebase.",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        } else {
                            items(allEvidence, key = { "ev_${it.evidenceId}" }) { ev ->
                                val evDate = SimpleDateFormat("HH:mm:ss dd-MMM", Locale.getDefault()).format(Date(ev.uploadTime))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = TacticalCard),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, TacticalBorder)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${ev.fileType} • ${ev.incidentId}",
                                                fontWeight = FontWeight.Bold,
                                                color = EmergencyRed,
                                                fontSize = 12.sp
                                            )
                                            Text(text = evDate, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = ev.storageLocation.ifBlank { "Cloud Synced Evidence Asset" },
                                            color = Color.White.copy(alpha = 0.8f),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { viewModel.shareVaultEvidenceFile(ev) },
                                                colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Download / Share", fontSize = 11.sp)
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.deleteEvidenceItem(ev.incidentId) },
                                                border = BorderStroke(1.dp, EmergencyRed),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = null, tint = EmergencyRed, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Delete", color = EmergencyRed, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }

    if (selectedIncident != null) {
        val incident = selectedIncident!!
        val events by viewModel.selectedIncidentEvents.collectAsState()
        var noteInput by remember { mutableStateOf("") }
        var showAssignUnitDialog by remember { mutableStateOf(false) }
        val hasCoords = incident.latitude != 0.0 || incident.longitude != 0.0

        AlertDialog(
            onDismissRequest = { selectedIncident = null },
            containerColor = TacticalSurface,
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "#${incident.incidentId}",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (incident.priority == "CRITICAL") EmergencyRed else WarningAmber
                    ) {
                        Text(
                            text = incident.priority,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            },
            text = {
                val createdStr = remember(incident.createdAt) {
                    SimpleDateFormat("dd MMM yyyy, HH:mm:ss z", Locale.getDefault()).format(Date(incident.createdAt))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Type: ${incident.type} • Status: ${incident.status}", fontWeight = FontWeight.Bold, color = SafetyTeal, fontSize = 13.sp)
                    Text("Timestamp: $createdStr", fontSize = 11.sp, color = Color.White.copy(alpha = 0.78f))
                    Text("Location: ${incident.locationDescription}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                    if (incident.description.isNotBlank()) {
                        Text("Details: ${incident.description}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.78f))
                    }
                    if (!incident.operatorNotes.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = TacticalCard,
                            border = BorderStroke(1.dp, TacticalBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = incident.operatorNotes,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = SafetyTeal,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    if (incident.assignedUnit != null) {
                        Text("Assigned Unit: ${incident.assignedUnit}", fontWeight = FontWeight.Bold, color = PoliceBlue, fontSize = 12.sp)
                    }

                    if (hasCoords) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.openIncidentInMaps(incident.latitude, incident.longitude, "Incident ${incident.incidentId}")
                            },
                            border = BorderStroke(1.dp, TacticalBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Coordinates in Maps", fontSize = 12.sp, color = Color.White)
                        }
                    }

                    if (latestPoliceDossier != null && latestPoliceDossier?.incidentId == incident.incidentId) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { latestPoliceDossier?.let { viewModel.openLiveDossier(it) } },
                            colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LocalPolice, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Live 112 Dossier", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Action Pipeline:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (incident.status == "CREATED") {
                            Button(
                                onClick = {
                                    viewModel.operatorAcknowledge(incident.incidentId)
                                    selectedIncident = incident.copy(status = "ACKNOWLEDGED")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Acknowledge", fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        Button(
                            onClick = { showAssignUnitDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Assign Unit", fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.operatorUpdateStatus(incident.incidentId, "RESPONDING", noteInput)
                                selectedIncident = incident.copy(status = "RESPONDING")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("En Route", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }

                        Button(
                            onClick = {
                                viewModel.operatorUpdateStatus(incident.incidentId, "ON_SCENE", noteInput)
                                selectedIncident = incident.copy(status = "ON_SCENE")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDB2777)),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("On-Scene", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    Text(
                        text = "Policy: Only the victim confirming resolution may close the case & purge the evidence vault.",
                        fontSize = 10.sp,
                        color = Color(0xFF93C5FD),
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        placeholder = { Text("Add Responder notes...", color = Color.White.copy(alpha = 0.55f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PoliceBlue,
                            unfocusedBorderColor = TacticalBorder,
                            cursorColor = PoliceBlue
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Timeline Logs:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                    if (events.isEmpty()) {
                        Text("• Waiting for timeline updates", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
                    } else {
                        events.takeLast(3).forEach { evt ->
                            Text(
                                text = "• ${evt.newStatus} by ${evt.actor}: ${evt.note}",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { selectedIncident = null },
                    colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue)
                ) {
                    Text("Done")
                }
            }
        )

        if (showAssignUnitDialog) {
            AlertDialog(
                onDismissRequest = { showAssignUnitDialog = false },
                containerColor = TacticalSurface,
                titleContentColor = Color.White,
                textContentColor = Color.White,
                title = { Text("Assign Responder Unit", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableUnits.forEach { unit ->
                            Button(
                                onClick = {
                                    viewModel.operatorAssignUnit(incident.incidentId, unit)
                                    selectedIncident = incident.copy(assignedUnit = unit, status = "ASSIGNED")
                                    showAssignUnitDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.DirectionsCar, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(unit)
                            }
                        }
                    }
                },
                confirmButton = {
                    OutlinedButton(
                        onClick = { showAssignUnitDialog = false },
                        border = BorderStroke(1.dp, TacticalBorder)
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                }
            )
        }
    }

    pendingHexAcceptAlert?.let { targetAlert ->
        ResponderSelfieVerifyDialog(
            victimName = targetAlert.victimName,
            defaultPhone = userProfile?.phone?.ifBlank { viewModel.repository.goodCitizenManager.getVerifiedPhoneNumber() }
                ?: viewModel.repository.goodCitizenManager.getVerifiedPhoneNumber(),
            onVerifyBitmap = { bmp, cb -> viewModel.verifyResponderSelfie(bmp, cb) },
            onDismiss = { pendingHexAcceptAlert = null },
            onVerifiedConfirm = { phone, selfieB64 ->
                viewModel.acceptHexHelpRequest(targetAlert.alertId, phone, selfieB64)
                pendingHexAcceptAlert = null
            }
        )
    }
}

@Composable
fun GovernmentIncidentItem(
    incident: IncidentEntity,
    onClick: () -> Unit
) {
    val dateStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(incident.createdAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("incident_item_${incident.incidentId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TacticalCard),
        border = BorderStroke(1.dp, TacticalBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when (incident.status) {
                            "CREATED" -> EmergencyRed
                            "ACKNOWLEDGED" -> PoliceBlue
                            "ASSIGNED", "RESPONDING" -> WarningAmber
                            "ON_SCENE" -> Color(0xFFEC4899)
                            else -> SafetyTeal
                        }
                    )
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = incident.incidentId,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (incident.priority == "CRITICAL") EmergencyRed.copy(alpha = 0.25f) else WarningAmber.copy(alpha = 0.25f)
                    ) {
                        Text(
                            text = incident.priority,
                            color = if (incident.priority == "CRITICAL") EmergencyRed else WarningAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "${incident.type} • ${incident.locationDescription}",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (incident.assignedUnit != null) {
                    Text(
                        text = "Unit: ${incident.assignedUnit}",
                        color = PoliceBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TacticalSurface
                ) {
                    Text(
                        text = incident.status,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = dateStr, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun MetricBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(1.dp, TacticalBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, fontWeight = FontWeight.Black, fontSize = 17.sp, color = color)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.78f),
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun RealGpsBeaconListView(
    activeIncidents: List<IncidentEntity>,
    operatorLocation: Location?,
    onOpenInMaps: (Double, Double, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TacticalCard),
                border = BorderStroke(1.dp, TacticalBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, tint = SafetyTeal)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "LIVE TELEMETRY POSITION",
                            color = SafetyTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = if (operatorLocation != null) {
                                "${String.format(Locale.US, "%.5f", operatorLocation.latitude)}, ${String.format(Locale.US, "%.5f", operatorLocation.longitude)} (±${operatorLocation.accuracy.toInt()}m)"
                            } else {
                                "Acquiring real GPS coordinates..."
                            },
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        if (activeIncidents.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = TacticalCard),
                    border = BorderStroke(1.dp, TacticalBorder)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No active distress beacons right now.",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            items(activeIncidents, key = { it.incidentId }) { incident ->
                val hasCoords = incident.latitude != 0.0 || incident.longitude != 0.0
                val distanceLabel = if (hasCoords && operatorLocation != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        operatorLocation.latitude,
                        operatorLocation.longitude,
                        incident.latitude,
                        incident.longitude,
                        results
                    )
                    val meters = results[0]
                    if (meters < 1000f) "${meters.toInt()} m away"
                    else "${String.format(Locale.US, "%.2f", meters / 1000f)} km away"
                } else if (hasCoords) {
                    "Coords recorded"
                } else {
                    "GPS fix unavailable"
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = TacticalCard),
                    border = BorderStroke(1.dp, TacticalBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = EmergencyRed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "${incident.incidentId} • ${incident.type}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = if (hasCoords) {
                                        "${String.format(Locale.US, "%.5f", incident.latitude)}, ${String.format(Locale.US, "%.5f", incident.longitude)} • $distanceLabel"
                                    } else {
                                        incident.locationDescription
                                    },
                                    color = Color.White.copy(alpha = 0.78f),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (hasCoords) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    onOpenInMaps(incident.latitude, incident.longitude, "Incident ${incident.incidentId}")
                                },
                                border = BorderStroke(1.dp, TacticalBorder)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Map", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DispatchHexAlertCard(
    alert: HexRescueAlert,
    isOutgoingOwner: Boolean,
    onAccept: () -> Unit,
    onMarkEnRoute: () -> Unit,
    onMarkArrived: () -> Unit,
    onComplete: () -> Unit,
    onNavigate: (Double, Double, String) -> Unit,
    onExportZeroFir: () -> Unit = {}
) {
    val responderSelfieBitmap: ImageBitmap? = remember(alert.acceptedResponderSelfieB64) {
        val b64 = alert.acceptedResponderSelfieB64
        if (b64.isNullOrBlank()) null
        else try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dispatch_hex_card_${alert.alertId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TacticalCard),
        border = BorderStroke(
            1.2.dp,
            if (isOutgoingOwner) WarningAmber else if (alert.status == HexAlertStatus.ACCEPTED) SafetyTeal else EmergencyRed
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isOutgoingOwner) {
                        "OUTGOING SOS • Sector ${alert.compassSector}"
                    } else {
                        "${alert.victimName} • Sector ${alert.compassSector}"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (alert.status == HexAlertStatus.ACCEPTED) SafetyTeal else EmergencyRed
                ) {
                    Text(
                        text = alert.lifecycleState.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Channel: ${alert.sourceChannel} • Radius Stage ${alert.expansionStage} (${alert.currentRadiusMeters}m) • ~${alert.distanceMeters.toInt()}m",
                color = PoliceBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (alert.victimLocationDescription.isNotBlank()) {
                Text(
                    text = alert.victimLocationDescription,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }

            if (alert.evidenceSummary.isNotBlank()) {
                Text(
                    text = "Evidence: ${alert.evidenceSummary}",
                    color = SafetyTeal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!alert.acceptedResponderName.isNullOrBlank() || responderSelfieBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TacticalSurface,
                    border = BorderStroke(1.dp, SafetyTeal.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        responderSelfieBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = "Verified Responder Selfie",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Responder: ${alert.acceptedResponderName ?: "Verified Citizen"}",
                                color = SafetyTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            if (!alert.acceptedResponderPhone.isNullOrBlank()) {
                                Text(
                                    text = "Phone: ${alert.acceptedResponderPhone}",
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                            if (alert.acceptedResponderLat != null && alert.acceptedResponderLng != null) {
                                TextButton(
                                    onClick = {
                                        onNavigate(
                                            alert.acceptedResponderLat,
                                            alert.acceptedResponderLng,
                                            "Responder ${alert.acceptedResponderName ?: ""}"
                                        )
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Open Responder GPS", color = PoliceBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (!isOutgoingOwner) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (alert.lifecycleState) {
                        HexRescueLifecycleState.REQUEST_CREATED,
                        HexRescueLifecycleState.RESPONDERS_DISCOVERED,
                        HexRescueLifecycleState.REQUEST_BROADCAST -> {
                            Button(
                                onClick = onAccept,
                                colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Accept (+15)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        HexRescueLifecycleState.RESPONDER_ACCEPTED -> {
                            Button(
                                onClick = onMarkEnRoute,
                                colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Mark En Route", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        HexRescueLifecycleState.EN_ROUTE -> {
                            Button(
                                onClick = onMarkArrived,
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Mark Arrived", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                        HexRescueLifecycleState.ARRIVED -> {
                            Button(
                                onClick = onComplete,
                                colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Complete (+25)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        else -> Unit
                    }

                    OutlinedButton(
                        onClick = { onNavigate(alert.victimLatitude, alert.victimLongitude, "Victim ${alert.victimName}") },
                        border = BorderStroke(1.dp, TacticalBorder),
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Navigate", color = Color.White, fontSize = 11.sp)
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onExportZeroFir,
                    border = BorderStroke(1.dp, PoliceBlue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = PoliceBlue, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export Zero-FIR Timeline", color = PoliceBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

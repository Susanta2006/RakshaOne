package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.data.model.HexRescueAlert
import com.example.risk.RiskLevel
import com.example.risk.TriggerOrigin
import com.example.service.SosSmsDispatchState
import com.example.ui.MainViewModel
import com.example.ui.SosCountdownState
import com.example.ui.components.FineLocationPermissionBanner
import com.example.ui.components.GoodCitizenShieldCard
import com.example.ui.components.GuardianHighAlertCard
import com.example.ui.components.HexRescueAlertCard
import com.example.ui.components.PlateScannerDialog
import com.example.ui.components.Police112DispatchBanner
import com.example.ui.components.ResponderSelfieVerifyDialog
import com.example.ui.components.rememberFineLocationPermissionState
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.EmergencyRedDark
import com.example.ui.theme.EmergencyRedLight
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.WarningAmber
import com.example.util.BleEmergencyRelay
import com.example.util.FakeCallState
import com.example.util.LocationState
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun SosHomeScreen(
    viewModel: MainViewModel,
    onNavigateToAI: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sosState by viewModel.sosState.collectAsState()
    val countdownRemaining by viewModel.countdownRemaining.collectAsState()
    val locationState by viewModel.locationState.collectAsState()
    val emergencyInfo by viewModel.emergencyInfo.collectAsState()
    val activeIncident by viewModel.latestActiveIncident.collectAsState()
    val trustedContacts by viewModel.trustedContacts.collectAsState()
    val isSirenActive by viewModel.isSirenActive.collectAsState()
    val isStrobeActive by viewModel.isStrobeActive.collectAsState()
    val isShakeSosEnabled by viewModel.isShakeSosEnabled.collectAsState()
    val currentShakeCount by viewModel.currentShakeCount.collectAsState()
    val lastShakeGForce by viewModel.lastShakeGForce.collectAsState()
    val isIgnoringBatteryOptimizations by viewModel.isIgnoringBatteryOptimizations.collectAsState()
    val manualRegionOverride by viewModel.manualRegionOverride.collectAsState()
    val isAudioRecording by viewModel.isAudioRecording.collectAsState()
    val audioSecondsRemaining by viewModel.audioSecondsRemaining.collectAsState()
    val latestAudioEvidence by viewModel.latestAudioEvidence.collectAsState()
    val isCameraRecording by viewModel.isCameraRecording.collectAsState()
    val cameraSecondsRemaining by viewModel.cameraSecondsRemaining.collectAsState()
    val capturedPhotoPaths by viewModel.capturedPhotoPaths.collectAsState()
    val frontPhotoPaths by viewModel.frontPhotoPaths.collectAsState()
    val rearPhotoPaths by viewModel.rearPhotoPaths.collectAsState()
    val capturedVideoPaths by viewModel.capturedVideoPaths.collectAsState()
    val latestShareableAudioFile by viewModel.latestShareableAudioFile.collectAsState()
    val cameraCaptureStatus by viewModel.cameraCaptureStatus.collectAsState()
    val lastVideoPath by viewModel.lastVideoPath.collectAsState()
    val isSilentMode by viewModel.isSilentMode.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val pendingAlertsCount by viewModel.pendingOfflineAlertsCount.collectAsState()
    val smsDispatchState by viewModel.sosSmsDispatchState.collectAsState()
    val sosChannelStatus by viewModel.sosChannelStatus.collectAsState()
    val autoDispatchMediaOnCapture by viewModel.autoDispatchMediaOnCapture.collectAsState()
    val selectedWaContactIdx by viewModel.selectedWhatsAppContactIndex.collectAsState()
    val incomingHexAlert by viewModel.activeIncomingHexAlert.collectAsState()
    val activeOutgoingHexAlert by viewModel.activeOutgoingHexAlert.collectAsState()
    val activeGuardianHighAlert by viewModel.activeGuardianHighAlert.collectAsState()
    val activeGuardianResponderAlert by viewModel.activeGuardianResponderAlert.collectAsState()
    val latestPoliceDossier by viewModel.latestPoliceDossier.collectAsState()
    val goodCitizenProfile by viewModel.goodCitizenProfile.collectAsState()
    val currentRiskAssessment by viewModel.currentRiskAssessment.collectAsState()
    val travelBumpFilterEnabled by viewModel.travelBumpFilterEnabled.collectAsState()
    val travelBumpCount by viewModel.travelBumpCount.collectAsState()
    val bleRelayStatus by viewModel.bleRelayStatus.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val isDecoyStealthActive by viewModel.isDecoyStealthActive.collectAsState()
    val isProtectorWalkActive by viewModel.isProtectorWalkActive.collectAsState()
    val isPocketGestureArmed by viewModel.isPocketGestureArmed.collectAsState()
    val isVolumeDownSilentSosEnabled by viewModel.isVolumeDownSilentSosEnabled.collectAsState()
    val capturedAudioPaths by viewModel.capturedAudioPaths.collectAsState()
    val lastDetectedPlate by viewModel.lastDetectedPlate.collectAsState()
    val fakeCallState by viewModel.fakeCallState.collectAsState()
    val fakeCallCountdown by viewModel.fakeCallCountdown.collectAsState()
    val fakeCallAutoRecord by viewModel.fakeCallDefaultAutoRecordAndShare.collectAsState()
    val predictiveThreat by viewModel.predictiveThreat.collectAsState()
    val playingAudioIncidentId by viewModel.playingAudioIncidentId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showTimelineDialog by remember { mutableStateOf(false) }
    var showResolveDialog by remember { mutableStateOf(false) }
    var resolveNotes by remember { mutableStateOf("") }
    var resolvePinInput by remember { mutableStateOf("") }
    var showRegionDialog by remember { mutableStateOf(false) }
    var pendingHexAcceptAlert by remember { mutableStateOf<HexRescueAlert?>(null) }
    var showPlateScannerDialog by remember { mutableStateOf(false) }
    var showFakeCallDialog by remember { mutableStateOf(false) }

    val fineLocationPermissionState = rememberFineLocationPermissionState(
        onFineLocationGranted = { viewModel.startLocationTracking() }
    )

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleManualAudioRecording()
        }
    }

    val protectorWalkAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleProtectorWalk(true)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.captureEvidenceSnapshotNow()
        }
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

    fun requestNearbyMeshBluetoothEnable() {
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

    LaunchedEffect(Unit) {
        viewModel.statusEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val pulseScale = if (sosState != SosCountdownState.IDLE) {
        val infiniteTransition = rememberInfiniteTransition(label = "sos_pulse")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        animatedScale
    } else {
        1.0f
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Direct In-App High Alert received via Phone ID from a Trusted Circle contact
            GuardianHighAlertCard(
                alert = activeGuardianHighAlert,
                onOpenMap = { lat, lng, label -> viewModel.openIncidentInMaps(lat, lng, label) },
                onDialContact = { phone -> viewModel.dialNumber(phone) },
                onDismiss = { viewModel.dismissGuardianHighAlertBanner() },
                playingAudioIncidentId = playingAudioIncidentId,
                onPlayAudio = { incidentId -> viewModel.playGuardianAudio(incidentId) },
                onStopAudio = { viewModel.stopGuardianAudio() },
                onWatchVideo = { incidentId -> viewModel.openGuardianVideo(incidentId) },
                responderAlert = activeGuardianResponderAlert,
                onTrackDispatchMap = { viewModel.setOperatorMode(true) }
            )

            // Incoming Hexagonal Rescue Help Request Alert (Uber/Rapido style + Anti-Predator Selfie Lock)
            HexRescueAlertCard(
                incomingAlert = incomingHexAlert,
                onAccept = { alertId ->
                    incomingHexAlert?.takeIf { it.alertId == alertId }?.let { pendingHexAcceptAlert = it }
                },
                onDecline = { alertId -> viewModel.declineHexHelpRequest(alertId) },
                onNavigateToVictim = { lat, lng, label -> viewModel.openIncidentInMaps(lat, lng, label) },
                onDial112 = { viewModel.open112Dialer() },
                onMarkEnRoute = { alertId -> viewModel.markHexEnRoute(alertId) },
                onMarkArrived = { alertId -> viewModel.markHexArrived(alertId) },
                onCompleteRescue = { alertId -> viewModel.completeHexRescue(alertId) }
            )

            if (isDecoyStealthActive) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PoliceBlue.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("decoy_stealth_indicator")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = PoliceBlue, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Standby Guard Enabled • Background Vault Active",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PoliceBlue
                        )
                    }
                }
            }

            // Active 112 ERSS Police Escalation Dossier Banner (R3-CRITICAL ONLY)
            Police112DispatchBanner(
                dossier = latestPoliceDossier,
                onOpen112Dialer = { viewModel.open112Dialer() },
                onOpenDispatchPortal = { dossier -> viewModel.openPoliceDossierPortal(dossier) },
                onLaunch112Sms = { dossier -> viewModel.launch112SmsDossier(dossier) }
            )

            if (activeGuardianHighAlert != null || incomingHexAlert != null || latestPoliceDossier != null) {
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Top Security & Real-Time FusedLocationWrapper Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        when (locationState) {
                            LocationState.PermissionRequired -> {
                                if (fineLocationPermissionState.isPermanentlyDenied) {
                                    fineLocationPermissionState.openAppSettings()
                                } else {
                                    fineLocationPermissionState.requestFineLocation()
                                }
                            }
                            LocationState.GpsDisabled -> {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    )
                                } catch (_: Exception) {
                                }
                            }
                            else -> viewModel.refreshLocation()
                        }
                    }
                    .testTag("status_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (activeIncident != null) EmergencyRed else SafetyTeal)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (activeIncident != null) "SOS ALERT ACTIVE" else "SHIELD ACTIVE",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (activeIncident != null) EmergencyRed else SafetyTeal
                            )
                            Text(
                                text = "${emergencyInfo.countryName} (${emergencyInfo.primarySosNumber})",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "GPS Status",
                            tint = when (locationState) {
                                is LocationState.Available -> SafetyTeal
                                LocationState.PermissionRequired, LocationState.GpsDisabled -> WarningAmber
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val gpsLabel = when (val st = locationState) {
                            is LocationState.Available ->
                                "${String.format(Locale.US, "%.4f", st.latitude)}, ${String.format(Locale.US, "%.4f", st.longitude)} (±${st.accuracyMeters.toInt()}m)"
                            LocationState.Acquiring -> "Acquiring GPS..."
                            LocationState.PermissionRequired -> "Grant GPS Access"
                            LocationState.GpsDisabled -> "GPS Off • Tap"
                            is LocationState.Error -> "GPS Retry"
                        }
                        Text(
                            text = gpsLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh GPS",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (!fineLocationPermissionState.isFineLocationGranted) {
                FineLocationPermissionBanner(permissionState = fineLocationPermissionState)
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Offline Alert Queue & Connectivity Synchronization Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("network_sync_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (!isOnline || pendingAlertsCount > 0)
                        WarningAmber.copy(alpha = 0.14f)
                    else
                        SafetyTeal.copy(alpha = 0.12f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (!isOnline) Icons.Default.CloudOff else if (pendingAlertsCount > 0) Icons.Default.Sync else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (!isOnline || pendingAlertsCount > 0) WarningAmber else SafetyTeal,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (!isOnline) "Offline Mode • Room Database Queue Active"
                                else if (pendingAlertsCount > 0) "Network Restored • Unsynced Alerts Pending"
                                else "Network Online • Real-Time Dispatch Ready",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (!isOnline || pendingAlertsCount > 0) WarningAmber else SafetyTeal
                            )
                            if (!isOnline || pendingAlertsCount > 0) {
                                Text(
                                    text = "$pendingAlertsCount alert(s) queued locally.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (pendingAlertsCount > 0 || !isOnline) {
                        FilledTonalButton(
                            onClick = { viewModel.syncPendingOfflineAlertsNow() },
                            modifier = Modifier.testTag("sync_now_button"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sync", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Risk Intelligence Engine & Travel Bump Immunity Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("risk_intelligence_status_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (currentRiskAssessment.riskLevel) {
                        RiskLevel.R3_CRITICAL -> EmergencyRed.copy(alpha = 0.14f)
                        RiskLevel.R2_HIGH -> WarningAmber.copy(alpha = 0.14f)
                        RiskLevel.R1_ATTENTION -> PoliceBlue.copy(alpha = 0.12f)
                        RiskLevel.R0_NORMAL -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = when (currentRiskAssessment.riskLevel) {
                                    RiskLevel.R3_CRITICAL -> EmergencyRed
                                    RiskLevel.R2_HIGH -> WarningAmber
                                    RiskLevel.R1_ATTENTION -> PoliceBlue
                                    RiskLevel.R0_NORMAL -> SafetyTeal
                                }
                            ) {
                                Text(
                                    text = currentRiskAssessment.riskLevel.name,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (currentRiskAssessment.policeEscalationEligible) {
                                    "112 Escalation Authorized"
                                } else {
                                    "112 Blocked (R3 Explicit SOS Only)"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (currentRiskAssessment.policeEscalationEligible) EmergencyRed else SafetyTeal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (travelBumpFilterEnabled) {
                                "Travel Bump Guard ON ($travelBumpCount filtered) • Bumps/jolts never fire SOS"
                            } else {
                                "Origin: ${currentRiskAssessment.triggerOrigin.name} • ${currentRiskAssessment.context.contextSummary}"
                            },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Countdown Banner
            AnimatedVisibility(visible = sosState == SosCountdownState.COUNTDOWN) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = EmergencyRed),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "EMERGENCY ACTIVATION IN",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "$countdownRemaining",
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = "Acquiring live GPS & alerting ${trustedContacts.size} trusted contact(s)",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.cancelSosCountdown() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = EmergencyRed),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cancel_countdown_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("CANCEL SOS (FALSE ALARM)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Active Emergency Card
            AnimatedVisibility(visible = activeIncident != null) {
                activeIncident?.let { incident ->
                    val hasCoords = incident.latitude != 0.0 || incident.longitude != 0.0
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .testTag("active_incident_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, EmergencyRed)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "INCIDENT #${incident.incidentId}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Black,
                                        color = EmergencyRed
                                    )
                                    Text(
                                        text = "Priority: ${incident.priority}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (incident.status) {
                                        "CREATED" -> WarningAmber
                                        "ACKNOWLEDGED" -> PoliceBlue
                                        "ASSIGNED", "RESPONDING" -> Color(0xFF8B5CF6)
                                        "ON_SCENE" -> Color(0xFFEC4899)
                                        else -> SafetyTeal
                                    }
                                ) {
                                    Text(
                                        text = incident.status,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = EmergencyRed.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.ShareLocation,
                                            contentDescription = null,
                                            tint = EmergencyRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (hasCoords) "REAL-TIME COORDINATES RECORDED" else "GPS FIX STATUS",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp,
                                            color = EmergencyRed
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = incident.locationDescription,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isAudioRecording) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Mic, contentDescription = null, tint = EmergencyRed, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Recording AES-256 Encrypted Audio (${audioSecondsRemaining}s left)",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EmergencyRed
                                            )
                                        }
                                    } else if (latestAudioEvidence != null && latestAudioEvidence?.incidentId == incident.incidentId) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Audio Encrypted (${latestAudioEvidence!!.durationSeconds}s) • SHA-256: ${latestAudioEvidence!!.sha256Hash.take(12)}...",
                                            fontSize = 11.sp,
                                            color = SafetyTeal,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    // Automated Dual CameraX Evidence Live Status
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val videoTotal = capturedVideoPaths.size.coerceAtLeast(
                                        if (lastVideoPath != null || incident.videoEvidencePath != null) 1 else 0
                                    )
                                    val audioTag = if (latestShareableAudioFile != null) " • Voice .m4a ready" else ""
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Videocam,
                                            contentDescription = null,
                                            tint = if (isCameraRecording) EmergencyRed else SafetyTeal,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isCameraRecording) {
                                                "Dual Cam (${cameraSecondsRemaining}s) • F:${frontPhotoPaths.size} R:${rearPhotoPaths.size} img • $videoTotal vid$audioTag"
                                            } else if (videoTotal > 0 || capturedPhotoPaths.isNotEmpty() || latestShareableAudioFile != null) {
                                                "Vault Ready: $videoTotal vid • Front:${frontPhotoPaths.size} Rear:${rearPhotoPaths.size} img$audioTag"
                                            } else {
                                                cameraCaptureStatus
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isCameraRecording) EmergencyRed else SafetyTeal
                                        )
                                    }

                                    when (val sms = smsDispatchState) {
                                        is SosSmsDispatchState.Dispatching -> if (sms.incidentId == incident.incidentId) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "SMS Service: ${sms.stage}",
                                                fontSize = 11.sp,
                                                color = WarningAmber,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        is SosSmsDispatchState.Completed -> if (sms.incidentId == incident.incidentId) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = sms.summary,
                                                fontSize = 11.sp,
                                                color = if (sms.sentCount > 0) SafetyTeal else WarningAmber,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        SosSmsDispatchState.Idle -> Unit
                                    }

                                    sosChannelStatus?.takeIf { it.incidentId == incident.incidentId }?.let { channel ->
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (channel.smsFallbackTriggered) WarningAmber.copy(alpha = 0.16f) else SafetyTeal.copy(alpha = 0.15f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.setAutoDispatchMediaOnCapture(!autoDispatchMediaOnCapture) }
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = if (channel.smsFallbackTriggered) {
                                                            "FALLBACK SMS ACTIVE • ${channel.fallbackReason.replace('_', ' ')}"
                                                        } else {
                                                            "AUTO CLOUD GUARDIAN + DUAL-CHANNEL VAULT"
                                                        },
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Black,
                                                        color = if (channel.smsFallbackTriggered) WarningAmber else SafetyTeal
                                                    )
                                                    Text(
                                                        text = if (autoDispatchMediaOnCapture) "Auto-Send ON" else "Manual Share",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                if (channel.mediaDispatchSummary.isNotBlank()) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = channel.mediaDispatchSummary,
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Live Uber-Style Responder & Hexagonal Dispatch Tracking for the Victim
                                    activeOutgoingHexAlert?.takeIf { it.incidentId == incident.incidentId }?.let { outgoing ->
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val hasResponder = !outgoing.acceptedResponderName.isNullOrBlank()
                                        val respSelfie: ImageBitmap? = remember(outgoing.acceptedResponderSelfieB64) {
                                            val b64 = outgoing.acceptedResponderSelfieB64
                                            if (b64.isNullOrBlank()) null
                                            else try {
                                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                            } catch (_: Exception) {
                                                null
                                            }
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (hasResponder) Color(0xFF1E1035) else PoliceBlue.copy(alpha = 0.14f),
                                            border = BorderStroke(1.dp, if (hasResponder) Color(0xFF8B5CF6) else PoliceBlue),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("victim_responder_tracking_card")
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (hasResponder) {
                                                    val rLat = outgoing.acceptedResponderLat
                                                    val rLng = outgoing.acceptedResponderLng
                                                    val distMeters = if (rLat != null && rLng != null && (rLat != 0.0 || rLng != 0.0) &&
                                                        (incident.latitude != 0.0 || incident.longitude != 0.0)
                                                    ) {
                                                        com.example.util.HexagonalRescueManager.calculateDistanceMeters(
                                                            incident.latitude,
                                                            incident.longitude,
                                                            rLat,
                                                            rLng
                                                        ).toInt()
                                                    } else null
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        respSelfie?.let { bmp ->
                                                            Image(
                                                                bitmap = bmp,
                                                                contentDescription = "Responder Selfie",
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier
                                                                    .size(40.dp)
                                                                    .clip(CircleShape)
                                                            )
                                                        }
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = "HELPER ${outgoing.lifecycleState.name} • ${outgoing.acceptedResponderName}",
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Black,
                                                                color = Color(0xFFA78BFA)
                                                            )
                                                            Text(
                                                                text = buildString {
                                                                    append(outgoing.acceptedResponderPhone ?: "Verified Citizen ID")
                                                                    if (distMeters != null) {
                                                                        append(" • ~${distMeters}m (~${(distMeters / 80).coerceAtLeast(1)}m walk)")
                                                                    }
                                                                },
                                                                fontSize = 11.sp,
                                                                color = Color.White,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                        }
                                                    }
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        if (!outgoing.acceptedResponderPhone.isNullOrBlank()) {
                                                            OutlinedButton(
                                                                onClick = { viewModel.dialNumber(outgoing.acceptedResponderPhone) },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text("Call", fontSize = 10.sp, color = Color.White)
                                                            }
                                                        }
                                                        if (rLat != null && rLng != null && (rLat != 0.0 || rLng != 0.0)) {
                                                            OutlinedButton(
                                                                onClick = { viewModel.openIncidentInMaps(rLat, rLng, "Responder ${outgoing.acceptedResponderName}") },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                Text("Helper GPS", fontSize = 10.sp, color = SafetyTeal)
                                                            }
                                                        }
                                                        Button(
                                                            onClick = { viewModel.setOperatorMode(true) },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                            modifier = Modifier.weight(1.1f)
                                                        ) {
                                                            Text("Uber Live Map", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                } else {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "Hex Rescue Scanning Stage ${outgoing.expansionStage} (${outgoing.currentRadiusMeters.toInt()}m)",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = PoliceBlue,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        FilledTonalButton(
                                                            onClick = { viewModel.setOperatorMode(true) },
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                        ) {
                                                            Text("Live Map", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            val waContacts = remember(trustedContacts) {
                                viewModel.repository.videoEvidenceManager.selectWhatsAppEnabledContacts(trustedContacts)
                            }
                            val activeWaTarget = if (waContacts.isNotEmpty()) {
                                waContacts[selectedWaContactIdx.coerceIn(0, waContacts.lastIndex) % waContacts.size]
                            } else null

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = activeWaTarget?.let { "WA Target: ${it.name} (${it.phone})" }
                                        ?: "WA Target: Add WhatsApp-enabled trusted contact",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (activeWaTarget != null) SafetyTeal else WarningAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (waContacts.size > 1) {
                                    FilledTonalButton(
                                        onClick = { viewModel.cycleNextWhatsAppContact() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.testTag("cycle_wa_contact_button")
                                    ) {
                                        Text("Next Contact", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.dispatchSosEvidenceWithFallback(incident.incidentId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("active_incident_send_media_button")
                                ) {
                                    Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Video -> WA", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.sharePhotosViaWhatsApp() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("active_incident_send_photos_button")
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Photos -> WA", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.shareAudioViaWhatsApp() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("active_incident_send_audio_button")
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Audio -> WA", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (trustedContacts.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = WarningAmber.copy(alpha = 0.16f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Add Trusted Contacts in Circle tab to send SMS, WhatsApp, and media evidence.",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = WarningAmber,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { viewModel.shareSosSmsNow() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("active_incident_resend_sms_button")
                                ) {
                                    Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Pre-Fill SMS", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.emailAllEvidenceToGuardians() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("active_incident_email_all_button")
                                ) {
                                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Email ALL", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Button(
                                    onClick = { viewModel.shareAllEvidenceBundleEverywhere() },
                                    colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .testTag("active_incident_send_bundle_button")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("ALL (${capturedVideoPaths.size}V/${capturedPhotoPaths.size}P/${capturedAudioPaths.size}A)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasCoords) {
                                    FilledTonalButton(
                                        onClick = {
                                            viewModel.openIncidentInMaps(
                                                incident.latitude,
                                                incident.longitude,
                                                "SOS ${incident.incidentId}"
                                            )
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Map", fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                                if (latestPoliceDossier != null && latestPoliceDossier?.incidentId == incident.incidentId) {
                                    FilledTonalButton(
                                        onClick = { latestPoliceDossier?.let { viewModel.openLiveDossier(it) } },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1.1f)
                                    ) {
                                        Icon(Icons.Default.LocalPolice, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Dossier", fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                                FilledTonalButton(
                                    onClick = {
                                        viewModel.loadIncidentEvents(incident.incidentId)
                                        showTimelineDialog = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Timeline", fontSize = 11.sp, maxLines = 1)
                                }
                                Button(
                                    onClick = { showResolveDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Resolve", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            // Main SOS Button Section
            Text(
                text = "EMERGENCY SOS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Text(
                text = "Tap to start 5-second countdown with live GPS capture & alert dispatch",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(230.dp)
                    .testTag("sos_button_container")
            ) {
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .scale(if (sosState == SosCountdownState.IDLE) pulseScale else 1.18f)
                        .clip(CircleShape)
                        .background(EmergencyRed.copy(alpha = 0.12f))
                )
                Box(
                    modifier = Modifier
                        .size(192.dp)
                        .scale(if (sosState == SosCountdownState.IDLE) (pulseScale * 0.96f) else 1.08f)
                        .clip(CircleShape)
                        .background(EmergencyRed.copy(alpha = 0.22f))
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(156.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(EmergencyRedLight, EmergencyRed, EmergencyRedDark)
                            )
                        )
                        .border(4.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                        .semantics {
                            contentDescription = "Emergency SOS Button. Tap to initiate 5-second emergency sequence."
                            role = Role.Button
                        }
                        .clickable {
                            if (sosState == SosCountdownState.IDLE) {
                                viewModel.startSosCountdown()
                            } else if (sosState == SosCountdownState.COUNTDOWN) {
                                viewModel.triggerImmediateSos()
                            } else {
                                viewModel.refreshLocation()
                            }
                        }
                        .testTag("sos_button")
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "SOS",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SOS",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = when (sosState) {
                                SosCountdownState.COUNTDOWN -> "TAP TO FIRE NOW"
                                SosCountdownState.ACTIVE -> "ACTIVE • REFRESH GPS"
                                SosCountdownState.IDLE -> "TAP FOR HELP"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Instant SOS Button
            Button(
                onClick = {
                    viewModel.triggerImmediateSos("Immediate 1-tap SOS triggered without countdown")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmergencyRedDark,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("instant_sos_button")
            ) {
                Icon(Icons.Default.Emergency, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isSilentMode) "INSTANT SILENT SOS: STEALTH ALERT NOW" else "INSTANT SOS: CAPTURE GPS & ALERT NOW",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dedicated 1-Tap Stealth Silent SOS (suppresses Siren & Flashlight when noise/light increases threat)
            OutlinedButton(
                onClick = {
                    viewModel.triggerImmediateSos(
                        customNote = "Stealth Silent SOS activated (no siren, no flash)",
                        forceSilent = true
                    )
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.2.dp, PoliceBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("silent_sos_button")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Silent Stealth SOS",
                    tint = PoliceBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SILENT SOS (NO SIREN • NO FLASH)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PoliceBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Silent Stealth Default Preference Switch
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("silent_sos_mode_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSilentMode) PoliceBlue.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
                ),
                border = if (isSilentMode) BorderStroke(1.dp, PoliceBlue.copy(alpha = 0.45f)) else null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (isSilentMode) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                                contentDescription = "Silent SOS Stealth Mode",
                                tint = if (isSilentMode) PoliceBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isSilentMode) "Silent SOS Mode: ON (Stealth)" else "Silent SOS Mode: OFF",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isSilentMode) PoliceBlue else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Silences siren, flash & voice alerts during SOS",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isSilentMode,
                            onCheckedChange = { viewModel.setSilentMode(it) },
                            modifier = Modifier.testTag("silent_sos_switch")
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Lockscreen 3x Volume Down SOS",
                                tint = if (isVolumeDownSilentSosEnabled) SafetyTeal else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isVolumeDownSilentSosEnabled) {
                                        "Locked 3x Vol-Down Stealth SOS: ARMED"
                                    } else {
                                        "Locked 3x Vol-Down Stealth SOS: OFF"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isVolumeDownSilentSosEnabled) SafetyTeal else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Triggers Silent SOS ONLY when locked/screen off (ignores holds, 2 taps & gaps)",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isVolumeDownSilentSosEnabled,
                            onCheckedChange = { viewModel.setVolumeDownSilentSosEnabled(it) },
                            modifier = Modifier.testTag("lockscreen_vol_down_sos_switch")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dynamic Real-time Location-Based Emergency Services
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("direct_emergency_services_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Direct Emergency Services",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (emergencyInfo.isRealGpsFix) SafetyTeal.copy(alpha = 0.2f) else WarningAmber.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = if (manualRegionOverride != null) "Manual ${emergencyInfo.countryCode}"
                                        else if (emergencyInfo.isRealGpsFix) "GPS ${emergencyInfo.countryCode}"
                                        else "Locale ${emergencyInfo.countryCode}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (emergencyInfo.isRealGpsFix) SafetyTeal else WarningAmber,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Real-time Location: ${emergencyInfo.locality}, ${emergencyInfo.countryName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (emergencyInfo.latitude != null && emergencyInfo.longitude != null) {
                                Text(
                                    text = "Live GPS: ${String.format(Locale.US, "%.5f", emergencyInfo.latitude)}, ${String.format(Locale.US, "%.5f", emergencyInfo.longitude)} (±${emergencyInfo.accuracyMeters.toInt()}m)",
                                    fontSize = 10.sp,
                                    color = SafetyTeal,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { showRegionDialog = true },
                                modifier = Modifier.testTag("change_region_button")
                            ) {
                                Icon(
                                    Icons.Default.Public,
                                    contentDescription = "Select Emergency Region",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = { viewModel.refreshLocation() },
                                modifier = Modifier.testTag("refresh_emergency_location_button")
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh GPS Location",
                                    tint = SafetyTeal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        EmergencyHotlineChip(
                            number = emergencyInfo.primarySosNumber,
                            label = "Primary SOS",
                            icon = Icons.Default.Security,
                            color = EmergencyRed,
                            onClick = { viewModel.dialNumber(emergencyInfo.primarySosNumber) },
                            modifier = Modifier.weight(1f).testTag("dial_primary_sos")
                        )
                        EmergencyHotlineChip(
                            number = emergencyInfo.policeNumber,
                            label = "Police",
                            icon = Icons.Default.LocalPolice,
                            color = PoliceBlue,
                            onClick = { viewModel.dialNumber(emergencyInfo.policeNumber) },
                            modifier = Modifier.weight(1f).testTag("dial_police")
                        )
                        EmergencyHotlineChip(
                            number = emergencyInfo.ambulanceNumber,
                            label = "Ambulance",
                            icon = Icons.Default.MedicalServices,
                            color = SafetyTeal,
                            onClick = { viewModel.dialNumber(emergencyInfo.ambulanceNumber) },
                            modifier = Modifier.weight(1f).testTag("dial_ambulance")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Battery Optimization Exemption for Background Location & SMS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("battery_optimization_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isIgnoringBatteryOptimizations) SafetyTeal.copy(alpha = 0.10f) else WarningAmber.copy(alpha = 0.12f)
                ),
                border = BorderStroke(1.dp, if (isIgnoringBatteryOptimizations) SafetyTeal.copy(alpha = 0.35f) else WarningAmber.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isIgnoringBatteryOptimizations) Icons.Default.CheckCircle else Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = if (isIgnoringBatteryOptimizations) SafetyTeal else WarningAmber,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isIgnoringBatteryOptimizations) "Foreground SOS: Unrestricted" else "Battery Saver Check",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (isIgnoringBatteryOptimizations) SafetyTeal else WarningAmber
                            )
                            Text(
                                text = if (isIgnoringBatteryOptimizations)
                                    "Battery optimization exempted — foreground GPS and SOS alerts stay unrestricted."
                                else
                                    "Optional: Set Battery to Unrestricted in App Info so long trips stay accurate while locked.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!isIgnoringBatteryOptimizations) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.requestIgnoreBatteryOptimizations(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("request_battery_exemption_button")
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open App Battery Settings", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Real Actionable Tactical & Safety Tools (Uniform 2-Column Responsive Grid)
            Text(
                text = "Safety & Deterrence Tools",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            )
            Text(
                text = "Siren, strobe, stealth mode, keypad SMS alerts, WhatsApp GPS & auto camera evidence",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )

            // Row 1: Loud Siren & Strobe Torch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SosToolCard(
                    title = if (isSirenActive) "Siren Playing" else "Loud Siren",
                    subtitle = if (isSirenActive) "Tap to Stop" else "Dual-tone alarm",
                    icon = if (isSirenActive) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.NotificationsActive,
                    accentColor = EmergencyRed,
                    isActive = isSirenActive,
                    onClick = { viewModel.toggleSiren() },
                    modifier = Modifier.weight(1f).testTag("tool_siren_button")
                )
                SosToolCard(
                    title = if (isStrobeActive) "Strobe Active" else "Strobe Light",
                    subtitle = if (isStrobeActive) "Tap to Stop" else if (viewModel.hasHardwareFlash) "LED flash strobe" else "Screen flash strobe",
                    icon = Icons.Default.FlashOn,
                    accentColor = WarningAmber,
                    isActive = isStrobeActive,
                    onClick = { viewModel.toggleStrobe() },
                    modifier = Modifier.weight(1f).testTag("tool_strobe_button")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 2: Silent Stealth Mode & Nearby Bluetooth Mesh Activation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SosToolCard(
                    title = if (isSilentMode) "Silent Stealth ON" else "Silent Mode",
                    subtitle = if (isSilentMode) "No siren or flash" else "Tap to silence SOS",
                    icon = if (isSilentMode) Icons.AutoMirrored.Filled.VolumeOff else Icons.Default.NotificationsOff,
                    accentColor = PoliceBlue,
                    isActive = isSilentMode,
                    onClick = { viewModel.toggleSilentMode() },
                    modifier = Modifier.weight(1f).testTag("tool_silent_mode_button")
                )
                val meshReady = bleRelayStatus.isBluetoothEnabled && !bleRelayStatus.permissionMissing
                SosToolCard(
                    title = if (meshReady) "Nearby Mesh ON" else "Enable Mesh BT",
                    subtitle = if (meshReady) {
                        if (bleRelayStatus.isScanning || bleRelayStatus.isAdvertising) "BLE Relay Active" else "Bluetooth Mesh Ready"
                    } else {
                        "Tap to turn on Bluetooth"
                    },
                    icon = Icons.Default.BluetoothConnected,
                    accentColor = if (meshReady) SafetyTeal else WarningAmber,
                    isActive = meshReady,
                    onClick = { requestNearbyMeshBluetoothEnable() },
                    modifier = Modifier.weight(1f).testTag("tool_nearby_mesh_bluetooth_button")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 3: Universal SMS Alert (Works on Keypad Phones & Offline Fallback) & WhatsApp Live GPS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SosToolCard(
                    title = "Send SOS SMS",
                    subtitle = "Fallback & keypad safe",
                    icon = Icons.Default.Sms,
                    accentColor = EmergencyRed,
                    isActive = false,
                    onClick = { viewModel.shareSosSmsNow() },
                    modifier = Modifier.weight(1f).testTag("tool_send_sms_button")
                )
                SosToolCard(
                    title = "WhatsApp GPS",
                    subtitle = "Auto SMS if offline",
                    icon = Icons.Default.ShareLocation,
                    accentColor = SafetyTeal,
                    isActive = false,
                    onClick = { viewModel.shareLiveLocationWhatsApp() },
                    modifier = Modifier.weight(1f).testTag("tool_whatsapp_gps_button")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 4: Share ALL Videos + Photos + Audio Bundle + Instant CameraX Snapshot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SosToolCard(
                    title = "Send ALL Evidence",
                    subtitle = "${capturedVideoPaths.size.coerceAtLeast(if (lastVideoPath != null) 1 else 0)} Vid • ${capturedPhotoPaths.size} Img • ${capturedAudioPaths.size} Aud",
                    icon = Icons.Default.Videocam,
                    accentColor = SafetyTeal,
                    isActive = isCameraRecording,
                    onClick = { viewModel.dispatchSosEvidenceWithFallback() },
                    modifier = Modifier.weight(1f).testTag("tool_share_video_button")
                )
                SosToolCard(
                    title = "Snap Photo",
                    subtitle = "Cache CameraX image",
                    icon = Icons.Default.CameraAlt,
                    accentColor = PoliceBlue,
                    isActive = false,
                    onClick = {
                        val hasCamera = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasCamera) {
                            viewModel.captureEvidenceSnapshotNow()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("tool_capture_photo_button")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 5: Encrypted Audio Recording
            SosToolCard(
                title = if (isAudioRecording) "Stop Rec (${audioSecondsRemaining}s)" else "Record Audio",
                subtitle = if (isAudioRecording) "Encrypting AES-256" else "Encrypted vault mic",
                icon = if (isAudioRecording) Icons.Default.Stop else Icons.Default.Mic,
                accentColor = EmergencyRed,
                isActive = isAudioRecording,
                onClick = {
                    val hasMic = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasMic) {
                        viewModel.toggleManualAudioRecording()
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("tool_audio_record_button")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Row 6: Survival Toolkit — Protective Google Phone Fake Call Shield & Cab Plate Scanner
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isScheduledFakeCall = fakeCallState == FakeCallState.SCHEDULED
                SosToolCard(
                    title = if (isScheduledFakeCall) "Cancel Call (${fakeCallCountdown}s)" else "Fake Call Shield",
                    subtitle = if (isScheduledFakeCall) "Tap to abort ring" else if (fakeCallAutoRecord) "Auto Rec & Send • Pixel UI" else "Google Phone Escape UI",
                    icon = Icons.Default.PhoneInTalk,
                    accentColor = SafetyTeal,
                    isActive = fakeCallState != FakeCallState.IDLE,
                    onClick = {
                        if (isScheduledFakeCall) {
                            viewModel.cancelFakeCall()
                        } else {
                            showFakeCallDialog = true
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("tool_fake_call_button")
                )
                SosToolCard(
                    title = "Cab Plate Scan",
                    subtitle = lastDetectedPlate ?: "Log & share vehicle",
                    icon = Icons.Default.DirectionsCar,
                    accentColor = PoliceBlue,
                    isActive = lastDetectedPlate != null,
                    onClick = { showPlateScannerDialog = true },
                    modifier = Modifier.weight(1f).testTag("tool_cab_plate_scanner_button")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 7: Protector Walk & Pocket SOS Micro-Gesture (4x Vol / Headset Unplug)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SosToolCard(
                    title = if (isProtectorWalkActive) "Walk Guard ON" else "Protector Walk",
                    subtitle = if (isProtectorWalkActive) "Voice co-pilot active" else "Spoken check-in + STT",
                    icon = Icons.Default.DirectionsWalk,
                    accentColor = SafetyTeal,
                    isActive = isProtectorWalkActive,
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!isProtectorWalkActive && !hasMic) {
                            protectorWalkAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            viewModel.toggleProtectorWalk(!isProtectorWalkActive)
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("tool_protector_walk_button")
                )
                SosToolCard(
                    title = if (isPocketGestureArmed) "Pocket SOS ARMED" else "Pocket SOS",
                    subtitle = "4x Vol-Up / Headset Jack",
                    icon = Icons.Default.Hearing,
                    accentColor = WarningAmber,
                    isActive = isPocketGestureArmed,
                    onClick = { viewModel.togglePocketGestureArmed(!isPocketGestureArmed) },
                    modifier = Modifier.weight(1f).testTag("tool_pocket_sos_button")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Export Zero-FIR Legal Timeline
            FilledTonalButton(
                onClick = { viewModel.exportAndShareZeroFirTimeline() },
                modifier = Modifier.fillMaxWidth().testTag("tool_export_zero_fir_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export Zero-FIR Legal Timeline", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 9: Real Maps Search for Nearby Police & Hospitals
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = { viewModel.openNearbyEmergencyOnMaps("police station") },
                    modifier = Modifier.weight(1f).testTag("tool_nearby_police_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.LocalPolice, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Nearby Police", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { viewModel.openNearbyEmergencyOnMaps("hospital emergency") },
                    modifier = Modifier.weight(1f).testTag("tool_nearby_hospital_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.LocalHospital, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Nearby Hospital", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Travel Bump Guard & Hardware Telemetry (NO automatic SOS from bumps or jerks)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shake_sos_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (travelBumpFilterEnabled) SafetyTeal.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant
                ),
                border = if (travelBumpFilterEnabled) BorderStroke(1.dp, SafetyTeal.copy(alpha = 0.45f)) else null
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = "Travel Bump Guard",
                                tint = if (travelBumpFilterEnabled) SafetyTeal else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Travel Bump Immunity Guard",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = if (!viewModel.hasAccelerometer) {
                                        "Accelerometer unavailable • SOS activates via SOS button only"
                                    } else {
                                        "Road bumps & vehicle jolts NEVER fire SOS ($travelBumpCount bump(s) filtered). Explicit SOS button required."
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SafetyTeal.copy(alpha = 0.2f),
                            modifier = Modifier.testTag("travel_bump_guard_badge")
                        ) {
                            Text(
                                text = "PROTECTED",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = SafetyTeal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live dynamic G-force: ${String.format(Locale.US, "%.2f", lastShakeGForce)}g • Passively logged ($currentShakeCount jolts)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Good Citizen Rescue Network & Hexagonal Alert Shield
            GoodCitizenShieldCard(
                profile = goodCitizenProfile,
                bleStatus = bleRelayStatus,
                onToggleVolunteerDuty = { enabled ->
                    viewModel.toggleVolunteerDuty(enabled)
                    if (enabled && (!bleRelayStatus.isBluetoothEnabled || bleRelayStatus.permissionMissing)) {
                        requestNearbyMeshBluetoothEnable()
                    }
                },
                onEnableBluetoothMesh = { requestNearbyMeshBluetoothEnable() }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Suraksha AI Safety Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToAI() }
                    .testTag("ai_safety_banner"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.FlashOn,
                            contentDescription = "Suraksha AI",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Suraksha AI Safety Assistant",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Ask for threat de-escalation or emergency protocols",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onNavigateToAI) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = "Ask AI")
                    }
                }
            }
        }
    }

    // Screen Strobe Visual Fallback when hardware flash is absent (or alongside flash)
    if (isStrobeActive && !viewModel.hasHardwareFlash) {
        var flashOn by remember { mutableStateOf(true) }
        LaunchedEffect(isStrobeActive) {
            while (isStrobeActive) {
                flashOn = !flashOn
                delay(120)
            }
        }
        Dialog(
            onDismissRequest = { viewModel.toggleStrobe() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (flashOn) Color.White else Color.Black)
                    .clickable { viewModel.toggleStrobe() },
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { viewModel.toggleStrobe() },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed)
                ) {
                    Icon(Icons.Default.FlashOn, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("TAP TO STOP SCREEN STROBE", fontWeight = FontWeight.Black)
                }
            }
        }
    }

    // Incident Timeline Dialog
    if (showTimelineDialog) {
        val events by viewModel.selectedIncidentEvents.collectAsState()
        AlertDialog(
            onDismissRequest = { showTimelineDialog = false },
            title = { Text("Incident Timeline & Chain of Custody", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (events.isEmpty()) {
                        Text("No timeline logs available.")
                    } else {
                        events.forEach { event ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 5.dp)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "${event.newStatus} • ${event.actor}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = event.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showTimelineDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Victim-Only Resolve Incident Dialog (with Decoy Cancel PIN Anti-Coercion Shield)
    if (showResolveDialog && activeIncident != null) {
        AlertDialog(
            onDismissRequest = {
                showResolveDialog = false
                resolvePinInput = ""
            },
            title = { Text("Confirm Safe & Close Case", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Only you (the victim) can close this active SOS case. Confirming will purge the local Evidence Vault. If coerced, enter your Decoy PIN (default 0000) to enter hidden Stealth Standby.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = resolveNotes,
                        onValueChange = { resolveNotes = it },
                        placeholder = { Text("e.g. Safe at destination / Threat passed") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = resolvePinInput,
                        onValueChange = { if (it.length <= 6) resolvePinInput = it.filter(Char::isDigit) },
                        label = { Text("Optional PIN (0000 = Decoy Stealth)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("resolve_decoy_pin_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        activeIncident?.let {
                            val noteText = resolveNotes.ifBlank { "Resolved by victim confirmation" }
                            if (resolvePinInput.isNotBlank()) {
                                viewModel.submitResolvePinOrDecoy(it.incidentId, resolvePinInput, noteText)
                            } else {
                                viewModel.resolveActiveIncident(it.incidentId, noteText)
                            }
                        }
                        resolvePinInput = ""
                        showResolveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal)
                ) {
                    Text("Confirm Safe / Submit PIN")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        activeIncident?.let {
                            viewModel.cancelActiveIncident(it.incidentId, "Cancelled by victim")
                        }
                        resolvePinInput = ""
                        showResolveDialog = false
                    }
                ) {
                    Text("False Alarm & Purge")
                }
            }
        )
    }

    pendingHexAcceptAlert?.let { alert ->
        val defaultPhone = userProfile?.phone?.ifBlank {
            viewModel.repository.goodCitizenManager.getVerifiedPhoneNumber()
        } ?: ""
        ResponderSelfieVerifyDialog(
            victimName = alert.victimName,
            defaultPhone = defaultPhone,
            onVerifyBitmap = { bmp, cb -> viewModel.verifyResponderSelfie(bmp, cb) },
            onDismiss = { pendingHexAcceptAlert = null },
            onVerifiedConfirm = { responderPhone, selfieB64 ->
                viewModel.acceptHexHelpRequest(
                    alertId = alert.alertId,
                    responderPhone = responderPhone,
                    responderSelfieB64 = selfieB64
                )
                pendingHexAcceptAlert = null
            }
        )
    }

    if (showPlateScannerDialog) {
        PlateScannerDialog(
            initialPlate = lastDetectedPlate,
            onScanBitmap = { bmp, cb -> viewModel.scanVehiclePlateFromBitmap(bmp, cb) },
            onScanUri = { uri, cb -> viewModel.scanVehiclePlateFromUri(uri, cb) },
            onSharePlateWithCircle = { plate, note ->
                viewModel.shareCabRideWithCircle(plate, note)
                showPlateScannerDialog = false
            },
            onDismiss = { showPlateScannerDialog = false }
        )
    }

    if (showRegionDialog) {
        AlertDialog(
            onDismissRequest = { showRegionDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Emergency Region", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "RakshaOne automatically resolves emergency numbers from real-time GPS coordinates. You can also preview regional hotlines manually:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Option 0: Auto-detect from GPS
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (manualRegionOverride == null) SafetyTeal.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.clearEmergencyRegionOverride()
                                showRegionDialog = false
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = manualRegionOverride == null,
                                onClick = {
                                    viewModel.clearEmergencyRegionOverride()
                                    showRegionDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Auto-Detect from Realtime GPS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Dynamic coordinates & offline geofence resolution", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Region Options
                    viewModel.supportedRegions.forEach { region ->
                        val isSelected = manualRegionOverride == region.countryCode
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) SafetyTeal.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setEmergencyRegionOverride(region.countryCode)
                                    showRegionDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        viewModel.setEmergencyRegionOverride(region.countryCode)
                                        showRegionDialog = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("${region.countryName} (${region.countryCode})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("SOS: ${region.primarySos} • Police: ${region.police} • Amb: ${region.ambulance}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showRegionDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showFakeCallDialog) {
        val defaultContact = trustedContacts.firstOrNull()
        var callerName by remember { mutableStateOf(defaultContact?.name?.ifBlank { null } ?: "Papa") }
        var callerNumber by remember { mutableStateOf(defaultContact?.phone?.ifBlank { null } ?: "+91 98765 43210") }
        var selectedDelaySec by remember { mutableIntStateOf(0) }
        var autoRecAndSend by remember(fakeCallAutoRecord) { mutableStateOf(fakeCallAutoRecord) }

        val fakeCallMicLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) {
            viewModel.scheduleFakeCall(
                delaySeconds = selectedDelaySec,
                name = callerName,
                number = callerNumber,
                isAiProactive = false,
                autoRecordAndShare = autoRecAndSend
            )
            showFakeCallDialog = false
        }

        @OptIn(ExperimentalLayoutApi::class)
        AlertDialog(
            onDismissRequest = { showFakeCallDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = SafetyTeal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Protective Fake Call", fontWeight = FontWeight.Black)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Renders a realistic Google Phone call screen. When answered, spoken audio is automatically recorded and silently dispatched to Trusted Contacts without popups.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val presets = buildList {
                            defaultContact?.let { add(it.name to it.phone) }
                            add("Papa" to "+91 98765 43210")
                            add("Maa" to "+91 98111 22334")
                            add("Brother" to "+91 97480 11223")
                        }.distinctBy { it.first }
                        presets.forEach { (presetName, presetPhone) ->
                            FilterChip(
                                selected = callerName == presetName,
                                onClick = {
                                    callerName = presetName
                                    callerNumber = presetPhone
                                },
                                label = { Text(presetName, fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = callerName,
                        onValueChange = { callerName = it },
                        label = { Text("Caller Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fake_call_name_input")
                    )
                    OutlinedTextField(
                        value = callerNumber,
                        onValueChange = { callerNumber = it },
                        label = { Text("Caller Phone") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fake_call_number_input")
                    )
                    Text("Ring Delay:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "Now", 5 to "5s", 15 to "15s", 30 to "30s").forEach { (sec, label) ->
                            FilterChip(
                                selected = selectedDelaySec == sec,
                                onClick = { selectedDelaySec = sec },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Record & Auto-Send Audio", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Uploads .m4a + sends direct alert to Trusted Circle",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoRecAndSend,
                            onCheckedChange = {
                                autoRecAndSend = it
                                viewModel.toggleFakeCallDefaultAutoRecordAndShare(it)
                            },
                            modifier = Modifier.testTag("fake_call_autorecord_switch")
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SafetyTeal.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "AI Sovereignty: ${predictiveThreat.sovereigntyTier.title}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SafetyTeal,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (autoRecAndSend && !hasMic) {
                            fakeCallMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            viewModel.scheduleFakeCall(
                                delaySeconds = selectedDelaySec,
                                name = callerName,
                                number = callerNumber,
                                isAiProactive = false,
                                autoRecordAndShare = autoRecAndSend
                            )
                            showFakeCallDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                    modifier = Modifier.testTag("confirm_schedule_fake_call_button")
                ) {
                    Text(if (selectedDelaySec == 0) "Ring Now" else "Schedule (${selectedDelaySec}s)")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFakeCallDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SosToolCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .heightIn(min = 124.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) accentColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isActive) BorderStroke(2.dp, accentColor) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isActive) accentColor else accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isActive) Color.White else accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isActive) accentColor else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EmergencyHotlineChip(
    number: String,
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, color)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = number, fontWeight = FontWeight.Black, fontSize = 16.sp, color = color)
            Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

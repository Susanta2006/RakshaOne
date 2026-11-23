package com.example

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.MainViewModel
import com.example.ui.components.AppGuideDialog
import com.example.ui.components.DeveloperInfoDialog
import com.example.ui.components.FakeCallOverlay
import com.example.ui.components.LiveDossierDialog
import com.example.ui.components.hasFineLocationPermission
import com.example.ui.components.rememberFineLocationPermissionState
import com.example.ui.screens.AiAssistantScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.GovernmentDashboardScreen
import com.example.ui.screens.IncidentReportingScreen
import com.example.ui.screens.ProfileSettingsScreen
import com.example.ui.screens.SafetyNetworkScreen
import com.example.ui.screens.SosHomeScreen
import com.example.ui.screens.TripMonitoringScreen
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.TacticalSurface
import com.example.util.GuardianHighAlertNotifier
import com.example.util.PhoneNormalizer
import com.example.util.configureAppCheck

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var headsetWasPlugged = false

    private val headsetUnplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                if (state == 1) {
                    headsetWasPlugged = true
                } else if (state == 0 && headsetWasPlugged) {
                    headsetWasPlugged = false
                    if (viewModel.isPocketGestureArmed.value) {
                        viewModel.triggerPocketGestureSos("Headset Unplug Micro-Gesture SOS")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Step 1: Configure Firebase App Check with launch intent before UI or Firebase operations
        configureAppCheck(this, intent)
        com.example.util.StorageWardenManager.schedulePeriodicCleanup(this)
        enableEdgeToEdge()
        viewModel.handleDeepLinkUri(
            uri = intent?.data,
            guardianDispatchId = intent?.getStringExtra(GuardianHighAlertNotifier.EXTRA_GUARDIAN_DISPATCH_ID)
        )
        setContent {
            MyApplicationTheme {
                RakshaOneApp(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            registerReceiver(headsetUnplugReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(headsetUnplugReceiver)
        } catch (_: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isRepeat = (event?.repeatCount ?: 0) > 0
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (viewModel.registerVolumeDownPress(isRepeat = isRepeat)) {
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!isRepeat) {
                    viewModel.registerVolumeButtonPress()
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleDeepLinkUri(
            uri = intent.data,
            guardianDispatchId = intent.getStringExtra(GuardianHighAlertNotifier.EXTRA_GUARDIAN_DISPATCH_ID)
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBatteryOptimizationStatus()
        viewModel.refreshBluetoothMeshState()
        viewModel.syncLockscreenVolumeSosService()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RakshaOneApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rakshaone_prefs", Context.MODE_PRIVATE) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showDeveloperInfo by rememberSaveable { mutableStateOf(false) }
    var showAppGuide by rememberSaveable { mutableStateOf(!prefs.getBoolean("has_seen_guide_v1", false)) }
    var showAuthScreen by rememberSaveable { mutableStateOf(false) }
    var hasPromptedBluetoothEnable by rememberSaveable { mutableStateOf(false) }
    val isOperatorMode by viewModel.isOperatorMode.collectAsState()
    val activeIncident by viewModel.latestActiveIncident.collectAsState()
    val activeLiveDossier by viewModel.activeLiveDossier.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()

    val emergencyInfo by viewModel.emergencyInfo.collectAsState()

    val fineLocationPermissionState = rememberFineLocationPermissionState(
        onFineLocationGranted = { viewModel.startLocationTracking() }
    )

    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onBluetoothEnablePromptResult(
            result.resultCode == Activity.RESULT_OK || viewModel.isBluetoothMeshEnabled()
        )
    }

    // Request fine location and companion emergency permissions on startup, then ask to turn on Bluetooth for Nearby Mesh
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocationPermission(context)) {
            viewModel.startLocationTracking()
        }
        viewModel.refreshBluetoothMeshState()
        viewModel.syncLockscreenVolumeSosService()
        if (!hasPromptedBluetoothEnable &&
            viewModel.hasBluetoothConnectPermission() &&
            !viewModel.isBluetoothMeshEnabled()
        ) {
            hasPromptedBluetoothEnable = true
            try {
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            if (fineLocationPermissionState.isFineLocationGranted) {
                viewModel.startLocationTracking()
            }
            viewModel.refreshBluetoothMeshState()
            if (!hasPromptedBluetoothEnable && !viewModel.isBluetoothMeshEnabled()) {
                hasPromptedBluetoothEnable = true
                try {
                    bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (_: Exception) {}
            }
        }
        viewModel.attemptAutoSignIn(context)
    }

    if (showAuthScreen) {
        AuthScreen(
            viewModel = viewModel,
            onAuthSuccess = { showAuthScreen = false },
            onContinueOffline = { showAuthScreen = false }
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = { showDeveloperInfo = true },
                        modifier = Modifier.testTag("top_bar_developer_info")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Developer Details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isOperatorMode) PoliceBlue else EmergencyRed),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isOperatorMode) Icons.Default.LocalPolice else Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isOperatorMode) "RakshaOne Dispatch" else "RakshaOne",
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                },
                actions = {
                    // App User Guide manual
                    IconButton(
                        onClick = { showAppGuide = true },
                        modifier = Modifier.testTag("top_bar_app_guide")
                    ) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = "How to Use RakshaOne Guide",
                            tint = PoliceBlue
                        )
                    }

                    // Quick dial regional emergency number
                    IconButton(
                        onClick = { viewModel.dialNumber(emergencyInfo.primarySosNumber) },
                        modifier = Modifier.testTag("top_bar_dial_112")
                    ) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Dial ${emergencyInfo.primarySosNumber}",
                            tint = EmergencyRed
                        )
                    }

                    // Hex & Emergency Dispatch screen switch
                    IconButton(
                        onClick = { viewModel.setOperatorMode(!isOperatorMode) },
                        modifier = Modifier.testTag("toggle_operator_portal")
                    ) {
                        Icon(
                            Icons.Default.LocalPolice,
                            contentDescription = "Hex & Emergency Dispatch",
                            tint = if (isOperatorMode) PoliceBlue else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = if (isOperatorMode) TacticalSurface else MaterialTheme.colorScheme.surface,
                    titleContentColor = if (isOperatorMode) Color.White else MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            if (!isOperatorMode) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = {
                            if (activeIncident != null) {
                                BadgedBox(badge = { Badge { Text("!") } }) {
                                    Icon(Icons.Default.Shield, contentDescription = "SOS")
                                }
                            } else {
                                Icon(Icons.Default.Shield, contentDescription = "SOS")
                            }
                        },
                        label = { Text("SOS", fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EmergencyRed,
                            selectedTextColor = EmergencyRed,
                            indicatorColor = EmergencyRed.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.testTag("nav_sos")
                    )

                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.People, contentDescription = "Network") },
                        label = { Text("Circle") },
                        modifier = Modifier.testTag("nav_circle")
                    )

                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "Trips") },
                        label = { Text("Trips") },
                        modifier = Modifier.testTag("nav_trips")
                    )

                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.ReportProblem, contentDescription = "Reports") },
                        label = { Text("Reports") },
                        modifier = Modifier.testTag("nav_reports")
                    )

                    NavigationBarItem(
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 },
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "AI") },
                        label = { Text("Suraksha") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SafetyTeal,
                            selectedTextColor = SafetyTeal,
                            indicatorColor = SafetyTeal.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.testTag("nav_ai")
                    )

                    NavigationBarItem(
                        selected = selectedTab == 5,
                        onClick = { selectedTab = 5 },
                        icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                        label = { Text("Profile") },
                        modifier = Modifier.testTag("nav_profile")
                    )
                }
            }
        }
    ) { innerPadding ->
        if (isOperatorMode) {
            GovernmentDashboardScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            when (selectedTab) {
                0 -> SosHomeScreen(
                    viewModel = viewModel,
                    onNavigateToAI = { selectedTab = 4 },
                    modifier = Modifier.padding(innerPadding)
                )
                1 -> SafetyNetworkScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
                2 -> TripMonitoringScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
                3 -> IncidentReportingScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
                4 -> AiAssistantScreen(
                    viewModel = viewModel,
                    onNavigateToSos = { selectedTab = 0 },
                    modifier = Modifier.padding(innerPadding)
                )
                5 -> ProfileSettingsScreen(
                    viewModel = viewModel,
                    onOpenAuthScreen = { showAuthScreen = true },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }

    if (showAppGuide) {
        AppGuideDialog(
            onDismiss = {
                prefs.edit().putBoolean("has_seen_guide_v1", true).apply()
                showAppGuide = false
            }
        )
    }

    if (showDeveloperInfo) {
        DeveloperInfoDialog(onDismiss = { showDeveloperInfo = false })
    }

    // Require signed-in users without a phone number to register their Unique Phone ID
    val profile = userProfile
    if (!showAppGuide && currentUser != null && profile != null && !PhoneNormalizer.isValidPhoneNumber(profile.phone)) {
        var phoneDraft by remember(profile.phone) { mutableStateOf(profile.phone) }
        val isPhoneValid = PhoneNormalizer.isValidPhoneNumber(phoneDraft)
        AlertDialog(
            onDismissRequest = {},
            modifier = Modifier.testTag("phone_registration_required_dialog"),
            title = {
                Text("Register Mobile Number ID", fontWeight = FontWeight.Black)
            },
            text = {
                Column {
                    Text(
                        text = "Your phone number acts as your unique RakshaOne ID so contacts with the app receive direct in-app push high alerts.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = phoneDraft,
                        onValueChange = { phoneDraft = it },
                        label = { Text("Mobile Number *") },
                        placeholder = { Text("e.g. +919876543210") },
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mandatory_phone_completion_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateProfile(profile.copy(phone = phoneDraft.trim()))
                    },
                    enabled = isPhoneValid,
                    modifier = Modifier.testTag("save_mandatory_phone_button")
                ) {
                    Text("Save Phone ID", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    activeLiveDossier?.let { dossier ->
        LiveDossierDialog(
            dossier = dossier,
            onDismiss = { viewModel.dismissLiveDossier() },
            onDial112 = { viewModel.open112Dialer() },
            onLaunch112Sms = { viewModel.launch112SmsDossier(it) },
            onOpenMaps = { lat, lng, label -> viewModel.openIncidentInMaps(lat, lng, label) },
            onRefreshTelemetry = { viewModel.refreshActiveLiveDossier() }
        )
    }

    FakeCallOverlay(viewModel = viewModel)
}

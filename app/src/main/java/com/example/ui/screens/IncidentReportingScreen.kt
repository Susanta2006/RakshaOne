package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.EvidenceEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.util.LocationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentReportingScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    val allEvidence by viewModel.allEvidence.collectAsState()
    val locationState by viewModel.locationState.collectAsState()
    val emergencyInfo by viewModel.emergencyInfo.collectAsState()
    val isAudioRecording by viewModel.isAudioRecording.collectAsState()
    val audioSecondsRemaining by viewModel.audioSecondsRemaining.collectAsState()
    val sensorSnapshot by viewModel.latestSensorSnapshot.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startReportingTelemetry()
        viewModel.statusEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopReportingTelemetry()
        }
    }

    // Form fields
    val categories = listOf(
        "Harassment",
        "Theft",
        "Accident",
        "Suspicious activity",
        "Violence",
        "Medical emergency",
        "Fire",
        "Other"
    )
    var selectedCategory by remember { mutableStateOf(categories[0]) }
    var isCategoryExpanded by remember { mutableStateOf(false) }

    var descriptionText by remember { mutableStateOf("") }
    var locationInput by remember { mutableStateOf("") }
    var hasAutoFilledLocation by remember { mutableStateOf(false) }

    // Populate initial real GPS location text once resolved (never hardcoded fake locations)
    LaunchedEffect(locationState, emergencyInfo) {
        if (!hasAutoFilledLocation && locationInput.isBlank()) {
            val state = locationState
            if (state is LocationState.Available) {
                val localityPrefix = emergencyInfo.locality
                    .takeIf { it.isNotBlank() && !it.startsWith("Acquiring") && !it.startsWith("Real GPS") }
                locationInput = if (localityPrefix != null) {
                    "$localityPrefix (${String.format(Locale.US, "%.5f", state.latitude)}, ${String.format(Locale.US, "%.5f", state.longitude)})"
                } else {
                    "GPS: ${String.format(Locale.US, "%.5f", state.latitude)}, ${String.format(Locale.US, "%.5f", state.longitude)}"
                }
                hasAutoFilledLocation = true
            }
        }
    }

    var evidenceTitle by remember { mutableStateOf("") }
    var evidenceType by remember { mutableStateOf("INCIDENT_REPORT") }
    var attachedFileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var attachedFileName by remember { mutableStateOf<String?>(null) }
    var attachedUriString by remember { mutableStateOf<String?>(null) }
    var submittedIncidentId by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Audio playback state in Evidence Vault
    var playingEvidenceId by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var tempDecryptedFile by remember { mutableStateOf<File?>(null) }

    fun stopPlayback() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        playingEvidenceId = null
        try {
            tempDecryptedFile?.delete()
        } catch (_: Exception) {}
        tempDecryptedFile = null
    }

    DisposableEffect(Unit) {
        onDispose { stopPlayback() }
    }

    // Zero-permission Android Photo Picker (Play Policy compliant)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    try {
                        var displayName = "photo_evidence.jpg"
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIdx >= 0) {
                                displayName = cursor.getString(nameIdx) ?: displayName
                            }
                        }
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null && bytes.isNotEmpty()) Triple(displayName, bytes, uri.toString()) else null
                    } catch (_: Exception) {
                        null
                    }
                }
                if (loaded != null) {
                    attachedFileName = loaded.first
                    attachedFileBytes = loaded.second
                    attachedUriString = loaded.third
                    evidenceType = "PHOTO_EVIDENCE"
                    if (evidenceTitle.isBlank()) {
                        evidenceTitle = loaded.first
                    }
                    snackbarHostState.showSnackbar("Attached ${loaded.first} (${loaded.second.size / 1024} KB)")
                } else {
                    snackbarHostState.showSnackbar("Unable to read selected image")
                }
            }
        }
    }

    val recordAudioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleManualAudioRecording()
        } else {
            scope.launch { snackbarHostState.showSnackbar("Microphone permission is required for encrypted audio recording") }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Incidents & Evidence Vault",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Document safety events with real SHA-256 tamper-evident evidence hashing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("File Report") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Evidence Vault (${allEvidence.size})") }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "Incident Category",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        ExposedDropdownMenuBox(
                            expanded = isCategoryExpanded,
                            onExpandedChange = { isCategoryExpanded = !isCategoryExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedCategory,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isCategoryExpanded) },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = isCategoryExpanded,
                                onDismissRequest = { isCategoryExpanded = false }
                            ) {
                                categories.forEach { category ->
                                    DropdownMenuItem(
                                        text = { Text(category) },
                                        onClick = {
                                            selectedCategory = category
                                            isCategoryExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Incident Location",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            FilledTonalButton(
                                onClick = {
                                    viewModel.refreshLocation()
                                    val state = locationState
                                    if (state is LocationState.Available) {
                                        val locality = emergencyInfo.locality
                                            .takeIf { it.isNotBlank() && !it.startsWith("Acquiring") && !it.startsWith("Real GPS") }
                                        locationInput = if (locality != null) {
                                            "$locality (${String.format(Locale.US, "%.5f", state.latitude)}, ${String.format(Locale.US, "%.5f", state.longitude)})"
                                        } else {
                                            "GPS: ${String.format(Locale.US, "%.5f", state.latitude)}, ${String.format(Locale.US, "%.5f", state.longitude)}"
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("use_current_gps_button")
                            ) {
                                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Use Current GPS", fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = locationInput,
                            onValueChange = { locationInput = it },
                            placeholder = { Text("Enter landmark or tap Use Current GPS") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Text(
                            text = "Description & Details",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = descriptionText,
                            onValueChange = { descriptionText = it },
                            placeholder = { Text("Provide factual details of the occurrence, individuals involved, or immediate risks...") },
                            minLines = 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("report_description_field")
                        )
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AttachFile, contentDescription = null, tint = PoliceBlue)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Evidence Attachment (Real Bytes & SHA-256)", fontWeight = FontWeight.Bold)
                                }

                                OutlinedTextField(
                                    value = evidenceTitle,
                                    onValueChange = { evidenceTitle = it },
                                    label = { Text("Evidence Title (optional)") },
                                    placeholder = { Text("e.g., Street camera photo or witness log") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("pick_photo_evidence_button")
                                    ) {
                                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Attach Photo", fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val hasMic = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (hasMic) {
                                                viewModel.toggleManualAudioRecording()
                                            } else {
                                                recordAudioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = if (isAudioRecording) EmergencyRed else MaterialTheme.colorScheme.primary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("record_vault_audio_button")
                                    ) {
                                        Icon(
                                            if (isAudioRecording) Icons.Default.Stop else Icons.Default.Mic,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            if (isAudioRecording) "Stop (${audioSecondsRemaining}s)" else "Record Audio",
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                if (attachedFileName != null && attachedFileBytes != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = SafetyTeal.copy(alpha = 0.14f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                Icon(Icons.Default.Image, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "$attachedFileName (${formatByteSize(attachedFileBytes!!.size.toLong())})",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = SafetyTeal
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    attachedFileName = null
                                                    attachedFileBytes = null
                                                    attachedUriString = null
                                                    evidenceType = "INCIDENT_REPORT"
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Clear, contentDescription = "Remove attachment", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = "Cryptographic SHA-256 checksum is computed directly from the attached image bytes or exact report payload.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sensor_snapshot_card"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Sensors, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Automated Timestamp & Sensor Snapshot",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = SafetyTeal
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.refreshSensorSnapshot() },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("refresh_sensor_snapshot_button")
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Sensor Telemetry", tint = PoliceBlue, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Text(
                                    text = sensorSnapshot?.toFormattedSummary()
                                        ?: "Reading accelerometer, battery, network & live GPS telemetry...",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                if (descriptionText.isNotBlank()) {
                                    isSubmitting = true
                                    viewModel.fileReport(
                                        category = selectedCategory,
                                        desc = descriptionText.trim(),
                                        loc = locationInput.trim(),
                                        evTitle = evidenceTitle.trim(),
                                        evType = evidenceType,
                                        attachmentBytes = attachedFileBytes,
                                        attachmentStoragePath = attachedUriString
                                    ) { id ->
                                        isSubmitting = false
                                        submittedIncidentId = id
                                        descriptionText = ""
                                        evidenceTitle = ""
                                        attachedFileBytes = null
                                        attachedFileName = null
                                        attachedUriString = null
                                        evidenceType = "INCIDENT_REPORT"
                                    }
                                }
                            },
                            enabled = descriptionText.isNotBlank() && !isSubmitting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("submit_report_button")
                        ) {
                            Icon(Icons.Default.ReportProblem, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isSubmitting) "Sealing Telemetry & Submitting..." else "Submit Securely to Dispatcher Portal", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } else {
                // Evidence Vault View
                if (allEvidence.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No evidence records found in vault.\nRecord encrypted audio or file an incident report to see SHA-256 records.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(allEvidence, key = { it.evidenceId }) { evidence ->
                            val isPlaying = playingEvidenceId == evidence.evidenceId
                            EvidenceCard(
                                evidence = evidence,
                                isPlayingAudio = isPlaying,
                                onShareFile = { viewModel.shareVaultEvidenceFile(evidence) },
                                onTogglePlayAudio = {
                                    if (isPlaying) {
                                        stopPlayback()
                                    } else {
                                        stopPlayback()
                                        scope.launch {
                                            val tempFile = viewModel.decryptAudioForPlayback(evidence.storageLocation)
                                            if (tempFile == null) {
                                                snackbarHostState.showSnackbar("Unable to decrypt audio file from vault")
                                            } else {
                                                try {
                                                    val player = MediaPlayer().apply {
                                                        setDataSource(tempFile.absolutePath)
                                                        prepare()
                                                        setOnCompletionListener {
                                                            stopPlayback()
                                                        }
                                                        start()
                                                    }
                                                    tempDecryptedFile = tempFile
                                                    mediaPlayer = player
                                                    playingEvidenceId = evidence.evidenceId
                                                } catch (e: Exception) {
                                                    stopPlayback()
                                                    snackbarHostState.showSnackbar("Playback failed: ${e.localizedMessage ?: "Unsupported audio"}")
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (submittedIncidentId != null) {
        AlertDialog(
            onDismissRequest = { submittedIncidentId = null },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(36.dp)) },
            title = { Text("Incident Logged Successfully", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Your report has been persisted to the RakshaOne local safety vault.")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Incident ID: $submittedIncidentId",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "SHA-256 cryptographic hash computed and stored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    submittedIncidentId = null
                    selectedTab = 1
                }) {
                    Text("View in Vault")
                }
            }
        )
    }
}

@Composable
fun EvidenceCard(
    evidence: EvidenceEntity,
    isPlayingAudio: Boolean = false,
    onShareFile: () -> Unit = {},
    onTogglePlayAudio: () -> Unit = {}
) {
    val dateStr = SimpleDateFormat("HH:mm:ss dd-MMM-yyyy", Locale.getDefault()).format(Date(evidence.uploadTime))
    val isEncryptedAudio = evidence.fileType == "AUDIO_ENCRYPTED"
    val isShareableCameraFile = (evidence.fileType.contains("VIDEO", ignoreCase = true) ||
        evidence.fileType.contains("PHOTO", ignoreCase = true)) &&
        evidence.storageLocation.startsWith("/") &&
        remember(evidence.storageLocation) { File(evidence.storageLocation).exists() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = when {
                            isEncryptedAudio -> Icons.Default.Mic
                            evidence.fileType.contains("VIDEO", ignoreCase = true) -> Icons.Default.Videocam
                            evidence.fileType.contains("PHOTO", ignoreCase = true) -> Icons.Default.Image
                            else -> Icons.Default.Description
                        },
                        contentDescription = null,
                        tint = if (isEncryptedAudio || evidence.fileType.contains("VIDEO", ignoreCase = true)) EmergencyRed else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = evidence.title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(SafetyTeal.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isEncryptedAudio) "AES-256-GCM" else "SHA-256",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = SafetyTeal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Incident ID: ${evidence.incidentId} • Size: ${formatByteSize(evidence.fileSizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Logged: $dateStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isEncryptedAudio) {
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onTogglePlayAudio,
                    modifier = Modifier.testTag("play_audio_${evidence.evidenceId}")
                ) {
                    Icon(
                        if (isPlayingAudio) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isPlayingAudio) "Stop Playback" else "Decrypt & Play Audio",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (isShareableCameraFile) {
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onShareFile,
                    modifier = Modifier.testTag("share_evidence_${evidence.evidenceId}")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (evidence.fileType.contains("VIDEO", ignoreCase = true)) "Share Cached Video" else "Share Cached Photo",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "SHA-256 Integrity Hash:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = evidence.sha256Hash,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun formatByteSize(bytes: Long): String {
    return if (bytes < 1024L) "$bytes B" else "${bytes / 1024L} KB"
}

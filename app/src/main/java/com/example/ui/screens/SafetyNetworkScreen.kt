package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.data.model.RelationshipTrustRules
import com.example.data.model.TrustedContactEntity
import com.example.data.repository.CloudEmergencyDispatch
import com.example.ui.MainViewModel
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.WarningAmber
import com.example.util.LocationState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SafetyNetworkScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val contacts by viewModel.trustedContacts.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val locationState by viewModel.locationState.collectAsState()
    val incomingGuardianAlerts by viewModel.incomingGuardianAlerts.collectAsState()
    val installedContactsMap by viewModel.installedContactsMap.collectAsState()
    val playingAudioIncidentId by viewModel.playingAudioIncidentId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.refreshInstalledContactsStatus()
        viewModel.statusEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var draftRelation by rememberSaveable { mutableStateOf("") }
    var draftPhone by rememberSaveable { mutableStateOf("") }
    var draftEmail by rememberSaveable { mutableStateOf("") }
    var draftPriority by rememberSaveable { mutableIntStateOf(1) }
    var draftHasWhatsApp by rememberSaveable { mutableStateOf(true) }
    var draftAllow24x7 by rememberSaveable { mutableStateOf(false) }

    var showPayloadPreviewDialog by remember { mutableStateOf(false) }
    var selectedContactForPreview by remember { mutableStateOf<TrustedContactEntity?>(null) }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val phoneUri = result.data!!.data!!
            try {
                context.contentResolver.query(
                    phoneUri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
                    ),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val normIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)

                        if (nameIdx >= 0) {
                            draftName = cursor.getString(nameIdx)?.trim().orEmpty()
                        }
                        val rawNorm = if (normIdx >= 0) cursor.getString(normIdx)?.trim().orEmpty() else ""
                        val rawDisplay = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                        val candidate = rawDisplay.ifBlank { rawNorm }
                        val cleaned = candidate.replace(Regex("[\\s\\-().]"), "")
                        val digitsOnly = cleaned.filter(Char::isDigit)
                        draftPhone = when {
                            rawNorm.startsWith("+") && rawNorm.filter(Char::isDigit).length >= 7 ->
                                "+${rawNorm.filter(Char::isDigit)}"
                            cleaned.startsWith("+") && digitsOnly.length >= 7 ->
                                "+$digitsOnly"
                            digitsOnly.length == 10 ->
                                "+91$digitsOnly"
                            digitsOnly.length == 11 && digitsOnly.startsWith("0") ->
                                "+91${digitsOnly.drop(1)}"
                            digitsOnly.length == 12 && digitsOnly.startsWith("91") ->
                                "+$digitsOnly"
                            else -> cleaned.ifBlank { candidate }
                        }
                    }
                }
            } catch (e: Exception) {
                uiScope.launch {
                    snackbarHostState.showSnackbar("Could not read picked contact")
                }
            }
            if (draftName.isNotBlank() || draftPhone.isNotBlank()) {
                uiScope.launch {
                    snackbarHostState.showSnackbar("Loaded: $draftName ($draftPhone)")
                }
            }
            showAddDialog = true
        }
    }

    val liveCoordsText = when (val st = locationState) {
        is LocationState.Available ->
            "${String.format(Locale.US, "%.5f", st.latitude)}, ${String.format(Locale.US, "%.5f", st.longitude)}"
        else -> "Live GPS (pending fix)"
    }
    val liveMapsUrl = when (val st = locationState) {
        is LocationState.Available -> "https://maps.google.com/?q=${st.latitude},${st.longitude}"
        else -> "https://maps.google.com/ (attached on GPS fix)"
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    draftName = ""
                    draftRelation = ""
                    draftPhone = ""
                    draftEmail = ""
                    draftPriority = 1
                    draftHasWhatsApp = true
                    draftAllow24x7 = false
                    showAddDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier.testTag("add_contact_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Trusted Contact")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Trusted Emergency Contacts",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "Contacts with RakshaOne installed receive direct push high-alerts via Phone ID. All contacts receive live GPS, audio, video & photos via WhatsApp + SMS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SafetyTeal.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Local Room Emergency List (Offline Ready)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = SafetyTeal
                            )
                        }
                        Text(
                            text = "${contacts.size} saved",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = SafetyTeal
                        )
                    }
                }
            }

            if (incomingGuardianAlerts.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = EmergencyRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Live Incoming Guardian Alerts (${incomingGuardianAlerts.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = EmergencyRed
                        )
                    }
                }
                items(incomingGuardianAlerts, key = { "alert_${it.dispatchId}" }) { alert ->
                    IncomingGuardianAlertCard(
                        alert = alert,
                        isAudioPlaying = playingAudioIncidentId == alert.dispatchId,
                        onOpenMap = {
                            viewModel.openIncidentInMaps(
                                alert.latitude,
                                alert.longitude,
                                "${alert.ownerName} ${alert.alertType}"
                            )
                        },
                        onPlayAudio = { viewModel.playGuardianAudio(alert.dispatchId) },
                        onStopAudio = { viewModel.stopGuardianAudio() },
                        onWatchVideo = { viewModel.openGuardianVideo(alert.dispatchId) }
                    )
                }
            }

            if (contacts.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No Trusted Contacts Added",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Tap + to add family or friends manually or import from your phone contacts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    draftName = ""
                                    draftRelation = ""
                                    draftPhone = ""
                                    draftEmail = ""
                                    draftPriority = 1
                                    draftHasWhatsApp = true
                                    showAddDialog = true
                                },
                                modifier = Modifier.testTag("empty_add_contact_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add First Trusted Contact")
                            }
                        }
                    }
                }
            } else {
                items(contacts, key = { it.id }) { contact ->
                    val isAppInstalled = com.example.util.PhoneNormalizer
                        .directoryLookupKeys(contact.phone)
                        .any { installedContactsMap[it] == true }
                    TrustedContactCard(
                        contact = contact,
                        isRakshaOneInstalled = isAppInstalled,
                        onCall = { viewModel.dialNumber(contact.phone) },
                        onTestSms = {
                            selectedContactForPreview = contact
                            showPayloadPreviewDialog = true
                        },
                        onToggle24x7 = { enabled ->
                            viewModel.toggleContact24x7Location(contact, enabled)
                        },
                        onDelete = { viewModel.deleteContact(contact.id) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = EmergencyRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Live SOS Dispatch Payload Preview", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "URGENT SOS - RakshaOne\n" +
                                "${userProfile?.name?.takeIf { it.isNotBlank() } ?: "Citizen"} needs immediate help.\n" +
                                "Live GPS: $liveCoordsText\n" +
                                "Map: $liveMapsUrl",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (showAddDialog) {
        val isFormValid = draftName.isNotBlank() && draftPhone.isNotBlank()
        val isCoreSelected = RelationshipTrustRules.isCoreFamily(draftRelation)
        val relationshipPresets = listOf(
            "Father", "Mother", "Brother", "Sister", "Spouse",
            "Relative", "Friend", "Colleague", "Other"
        )
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Trusted Contact", fontWeight = FontWeight.Bold) },
            text = {
                @OptIn(ExperimentalLayoutApi::class)
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { contactPickerLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pick_phone_contact_button")
                    ) {
                        Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick from Phone Contacts", fontSize = 12.sp)
                    }

                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text("Full Name *") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contact_name_input")
                    )
                    OutlinedTextField(
                        value = draftPhone,
                        onValueChange = { draftPhone = it },
                        label = { Text("Phone Number *") },
                        placeholder = { Text("e.g. +919876543210") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contact_phone_input")
                    )
                    OutlinedTextField(
                        value = draftEmail,
                        onValueChange = { draftEmail = it },
                        label = { Text("Email (For All-Evidence Dispatch)") },
                        placeholder = { Text("optional@email.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Select Relationship:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        relationshipPresets.forEach { preset ->
                            val selected = draftRelation.equals(preset, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    draftRelation = preset
                                    if (RelationshipTrustRules.isCoreFamily(preset)) {
                                        draftAllow24x7 = true
                                    }
                                },
                                label = { Text(preset, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = if (RelationshipTrustRules.isCoreFamily(preset)) SafetyTeal else PoliceBlue,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    OutlinedTextField(
                        value = draftRelation,
                        onValueChange = { draftRelation = it },
                        label = { Text("Relationship *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCoreSelected) SafetyTeal.copy(alpha = 0.14f) else PoliceBlue.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isCoreSelected) {
                                "Core Family Guardian • 24/7 Live Radar +15 Trust Pts"
                            } else {
                                "Extended Circle • Live GPS during Active Trips, Battery <10%, or SOS"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCoreSelected) SafetyTeal else PoliceBlue,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Text("Priority Level:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(1, 2, 3).forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = draftPriority == p,
                                    onClick = { draftPriority = p }
                                )
                                Text("P$p", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("24/7 Always-On Live Location", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                if (isCoreSelected) "Automatic for Core Family" else "Optional 24/7 access for Extended Circle",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isCoreSelected || draftAllow24x7,
                            enabled = !isCoreSelected,
                            onCheckedChange = { draftAllow24x7 = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Receives WhatsApp Alerts", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Disable if this guardian uses SMS-only keypad phone", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = draftHasWhatsApp,
                            onCheckedChange = { draftHasWhatsApp = it },
                            modifier = Modifier.testTag("contact_whatsapp_switch")
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isFormValid) {
                            viewModel.addContact(
                                name = draftName,
                                relation = draftRelation.ifBlank { "Friend" },
                                phone = draftPhone,
                                email = draftEmail,
                                priority = draftPriority,
                                hasWhatsApp = draftHasWhatsApp,
                                allow24x7Location = isCoreSelected || draftAllow24x7
                            )
                            showAddDialog = false
                        }
                    },
                    enabled = isFormValid,
                    modifier = Modifier.testTag("save_contact_button")
                ) {
                    Text("Save Contact")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPayloadPreviewDialog && selectedContactForPreview != null) {
        val contact = selectedContactForPreview!!
        val senderName = userProfile?.name?.takeIf { it.isNotBlank() } ?: "RakshaOne User"
        val realMessage = "RakshaOne Emergency Alert\n" +
            "$senderName is sharing their live safety check.\n" +
            "Location: $liveCoordsText\n" +
            "Map: $liveMapsUrl"

        AlertDialog(
            onDismissRequest = { showPayloadPreviewDialog = false },
            title = { Text("Send Alert to ${contact.name}", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Message to be sent to ${contact.phone}:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = realMessage,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.repository.openSmsMessenger(contact.phone, realMessage)
                        showPayloadPreviewDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed)
                ) {
                    Text("Open SMS Messenger")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPayloadPreviewDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun TrustedContactCard(
    contact: TrustedContactEntity,
    isRakshaOneInstalled: Boolean = false,
    onCall: () -> Unit,
    onTestSms: () -> Unit,
    onToggle24x7: (Boolean) -> Unit = {},
    onDelete: () -> Unit
) {
    val isCore = contact.isCoreFamily()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("contact_card_${contact.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            when (contact.priority) {
                                1 -> EmergencyRed.copy(alpha = 0.15f)
                                2 -> WarningAmber.copy(alpha = 0.15f)
                                else -> PoliceBlue.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "P${contact.priority}",
                        fontWeight = FontWeight.Black,
                        color = when (contact.priority) {
                            1 -> EmergencyRed
                            2 -> WarningAmber
                            else -> PoliceBlue
                        }
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contact.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = buildString {
                            append(RelationshipTrustRules.displayRole(contact.relationship))
                            if (contact.phone.isNotBlank()) append(" • ${contact.phone}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isCore) SafetyTeal.copy(alpha = 0.18f) else PoliceBlue.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = if (isCore) "Core Family • 24/7 Live" else if (contact.allow24x7Location) "Extended • 24/7 ON" else "Extended • Trip/SOS",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isCore) SafetyTeal else PoliceBlue
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (contact.hasWhatsApp) SafetyTeal.copy(alpha = 0.16f) else WarningAmber.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = if (contact.hasWhatsApp) "WhatsApp" else "SMS Only",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (contact.hasWhatsApp) SafetyTeal else WarningAmber
                            )
                        }
                        if (isRakshaOneInstalled) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = PoliceBlue.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = "Direct Push",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PoliceBlue
                                )
                            }
                        }
                    }
                }

                Row {
                    if (contact.phone.isNotBlank()) {
                        IconButton(onClick = onCall) {
                            Icon(Icons.Default.Call, contentDescription = "Call ${contact.name}", tint = SafetyTeal)
                        }
                        IconButton(onClick = onTestSms) {
                            Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "Send Alert SMS", tint = PoliceBlue)
                        }
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_contact_${contact.id}")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${contact.name}", tint = EmergencyRed)
                    }
                }
            }

            if (!isCore) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Allow 24/7 Live Location (Extended Circle)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = contact.allow24x7Location,
                        onCheckedChange = onToggle24x7
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomingGuardianAlertCard(
    alert: CloudEmergencyDispatch,
    isAudioPlaying: Boolean,
    onOpenMap: () -> Unit,
    onPlayAudio: () -> Unit,
    onStopAudio: () -> Unit,
    onWatchVideo: () -> Unit
) {
    val isFakeCallGuard = alert.alertType == "FAKE_CALL_GUARD"
    val accentColor = if (isFakeCallGuard) SafetyTeal else EmergencyRed
    val frontBitmap: ImageBitmap? = remember(alert.frontPhotoBase64) {
        decodeBase64Thumbnail(alert.frontPhotoBase64)
    }
    val rearBitmap: ImageBitmap? = remember(alert.rearPhotoBase64) {
        decodeBase64Thumbnail(alert.rearPhotoBase64)
    }
    val timeLabel = remember(alert.createdAt) {
        val ms = alert.createdAt?.toDate()?.time
        if (ms != null) SimpleDateFormat("dd MMM, HH:mm:ss", Locale.US).format(Date(ms)) else "Just now"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("guardian_alert_card_${alert.dispatchId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.08f)),
        border = BorderStroke(1.2.dp, accentColor.copy(alpha = 0.65f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${alert.ownerName.ifBlank { "Trusted Contact" }} • ${alert.ownerPhone}",
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        color = accentColor
                    )
                    Text(
                        text = "Dispatch #${alert.dispatchId} • $timeLabel",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when (alert.alertType) {
                            "ANTI_SNATCH" -> EmergencyRed
                            "FAKE_CALL_GUARD" -> SafetyTeal
                            else -> PoliceBlue
                        }
                    ) {
                        Text(
                            text = if (isFakeCallGuard) "FAKE CALL GUARD" else alert.alertType,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SafetyTeal
                    ) {
                        Text(
                            text = alert.status,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            if (alert.deviceLocked) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = PoliceBlue.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = PoliceBlue, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SENDER DEVICE SCREEN LOCKED (ANTI-THEFT PROTECTED)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PoliceBlue
                        )
                    }
                }
            }

            Text(
                text = "GPS: ${alert.locationSummary.ifBlank { alert.mapsUrl }} • Battery ${alert.batteryPercent}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (alert.medicalSummary.isNotBlank()) {
                Text(
                    text = "Medical: ${alert.medicalSummary}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (alert.evidenceSummary.isNotBlank()) {
                Text(
                    text = "Vault: ${alert.evidenceSummary}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = SafetyTeal
                )
            }

            if (frontBitmap != null || rearBitmap != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    frontBitmap?.let { bmp ->
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "Front Camera Snapshot",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Text("Front Cam", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    rearBitmap?.let { bmp ->
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "Rear Camera Snapshot",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Text("Rear Cam", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (alert.latitude != 0.0 || alert.longitude != 0.0) {
                Button(
                    onClick = onOpenMap,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Live GPS in Maps", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { if (isAudioPlaying) onStopAudio() else onPlayAudio() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("network_play_audio_${alert.dispatchId}")
                ) {
                    Icon(
                        imageVector = if (isAudioPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isAudioPlaying) "Stop Audio" else "Play Voice Audio",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (!isFakeCallGuard) {
                    OutlinedButton(
                        onClick = onWatchVideo,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("network_watch_video_${alert.dispatchId}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Watch Video", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun decodeBase64Thumbnail(b64: String): ImageBitmap? {
    if (b64.isBlank()) return null
    return try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

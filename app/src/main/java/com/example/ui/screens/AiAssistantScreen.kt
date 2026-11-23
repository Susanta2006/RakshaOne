package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ai.AiMessage
import com.example.data.ai.SurakshaAiUiState
import com.example.ui.MainViewModel
import com.example.ui.components.PlateScannerDialog
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.EmergencyRedDark
import com.example.ui.theme.EmergencyRedLight
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.WarningAmber
import com.example.util.GuardianVoicePersona
import com.example.util.LanguageOption
import com.example.util.PredictiveThreatTier
import com.example.util.VoiceGuardianManager

@Composable
fun AiAssistantScreen(
    viewModel: MainViewModel,
    onNavigateToSos: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messages by viewModel.aiMessages.collectAsStateWithLifecycle()
    val aiUiState by viewModel.aiUiState.collectAsStateWithLifecycle()
    val emergencyInfo by viewModel.emergencyInfo.collectAsStateWithLifecycle()
    val offlineGuides by viewModel.safetyGuides.collectAsStateWithLifecycle()
    val storageHealth by viewModel.storageHealth.collectAsStateWithLifecycle()
    val predictiveThreat by viewModel.predictiveThreat.collectAsStateWithLifecycle()
    val selectedLanguage by viewModel.selectedVoiceLanguage.collectAsStateWithLifecycle()
    val voicePersona by viewModel.voicePersona.collectAsStateWithLifecycle()
    val autoReadOut by viewModel.autoReadOut.collectAsStateWithLifecycle()
    val isVoiceListening by viewModel.isVoiceListening.collectAsStateWithLifecycle()
    val isVoiceSpeaking by viewModel.isVoiceSpeaking.collectAsStateWithLifecycle()
    val availableLanguages by viewModel.availableVoiceLanguages.collectAsStateWithLifecycle()
    val voiceStatusNote by viewModel.voiceStatusNote.collectAsStateWithLifecycle()
    val needsTtsDataInstall by viewModel.needsTtsDataInstall.collectAsStateWithLifecycle()
    val isProtectorWalkActive by viewModel.isProtectorWalkActive.collectAsStateWithLifecycle()
    val lastDetectedPlate by viewModel.lastDetectedPlate.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    var showOfflineGuidesDialog by remember { mutableStateOf(false) }
    var showAllLanguagesDialog by remember { mutableStateOf(false) }
    var langSearchQuery by remember { mutableStateOf("") }
    var showSafeHavensDialog by remember { mutableStateOf(false) }
    var showPlateScannerDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val voiceMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceListening { spoken ->
                inputText = spoken
            }
        }
    }

    // Auto-scroll when new messages or streaming chunks arrive
    LaunchedEffect(messages.size, aiUiState) {
        val targetIndex = when (aiUiState) {
            is SurakshaAiUiState.Loading,
            is SurakshaAiUiState.Streaming,
            is SurakshaAiUiState.Error -> messages.size
            SurakshaAiUiState.Idle -> (messages.size - 1).coerceAtLeast(0)
        }
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    val promptSuggestions = listOf(
        "Someone is following me",
        "First aid for bleeding",
        "What to do in an accident?",
        "Late night solo commute tips",
        "National Emergency Numbers",
        "De-escalate verbal harassment",
        "Help me start a monitored trip"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Header with AI Model Badge & Clear Action
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(SafetyTeal, PoliceBlue)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.FlashOn,
                            contentDescription = "Suraksha AI",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Suraksha AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "Safety AI • Real-Time",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Instant emergency guidance & threat assessment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = { viewModel.clearAiChat() },
                    modifier = Modifier.testTag("clear_chat_button")
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Clear Chat History",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Storage Warden & Predictive Threat Telemetry + Safe Havens + Cab Plate Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SafetyTeal.copy(alpha = 0.14f)
            ) {
                Text(
                    text = storageHealth.badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = SafetyTeal,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }

            val threatColor = when (predictiveThreat.tier) {
                PredictiveThreatTier.HIGH -> EmergencyRed
                PredictiveThreatTier.ELEVATED -> WarningAmber
                PredictiveThreatTier.SAFE -> SafetyTeal
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = threatColor.copy(alpha = 0.16f),
                modifier = Modifier.clickable {
                    viewModel.refreshPredictiveThreatNow()
                    showSafeHavensDialog = true
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, tint = threatColor, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Risk ${predictiveThreat.threatScore}/100 • Safe Havens",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = threatColor
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = PoliceBlue.copy(alpha = 0.14f),
                modifier = Modifier
                    .clickable { showPlateScannerDialog = true }
                    .testTag("ai_open_plate_scanner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = PoliceBlue, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = lastDetectedPlate?.let { "Cab: $it" } ?: "Scan Cab Plate",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PoliceBlue
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // On-Device Google AI Edge LiteRT (TFLite) Sovereign Safety Brain Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tflite_sovereign_brain_card"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = SafetyTeal,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = predictiveThreat.sovereigntyTier.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                color = SafetyTeal
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PoliceBlue.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = predictiveThreat.tfliteBackend,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PoliceBlue
                                )
                            }
                        }
                        Text(
                            text = predictiveThreat.neuralSummary,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = predictiveThreat.autonomousGuardEnabled,
                        onCheckedChange = { viewModel.setAutonomousAiGuardEnabled(it) },
                        modifier = Modifier.testTag("ai_autonomous_guard_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val callPct = (predictiveThreat.proactiveFakeCallProb * 100).toInt().coerceIn(0, 100)
                    val distressPct = (predictiveThreat.criticalDistressProb * 100).toInt().coerceIn(0, 100)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = WarningAmber.copy(alpha = 0.14f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "FakeCall Prob: $callPct%",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarningAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = EmergencyRed.copy(alpha = 0.14f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Distress: $distressPct% (${if (predictiveThreat.predictedTrajectoryDelta >= 0) "+" else ""}${predictiveThreat.predictedTrajectoryDelta})",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmergencyRed,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.submitAiLearningFeedback(true) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_feedback_threat_button")
                    ) {
                        Text("Accurate Alert", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.submitAiLearningFeedback(false) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_feedback_safe_button")
                    ) {
                        Text("I'm Safe (Calibrate)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Multilingual Voice Co-Pilot Bar (Male/Female, EN|HI|BN + All Languages, Test Voice, Install Voice Pack, Protector Walk)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GuardianVoicePersona.entries.forEach { persona ->
                        FilterChip(
                            selected = voicePersona == persona,
                            onClick = { viewModel.setVoicePersona(persona) },
                            label = {
                                Text(
                                    if (persona == GuardianVoicePersona.FEMALE) "Female" else "Male",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.speakVoiceSample() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("ai_test_voice_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Test Voice", modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Voice", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    VoiceGuardianManager.QUICK_PILLS.forEach { pill ->
                        FilterChip(
                            selected = selectedLanguage.code == pill.code,
                            onClick = { viewModel.setVoiceLanguage(pill) },
                            label = { Text(pill.code, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        )
                    }

                    OutlinedButton(
                        onClick = { showAllLanguagesDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("ai_all_languages_button")
                    ) {
                        Icon(Icons.Default.Language, contentDescription = "All Languages", modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(selectedLanguage.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    FilterChip(
                        selected = isProtectorWalkActive,
                        onClick = {
                            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (!isProtectorWalkActive && !hasMic) {
                                voiceMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                viewModel.toggleProtectorWalk(!isProtectorWalkActive)
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.DirectionsWalk, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                        label = {
                            Text(
                                if (isProtectorWalkActive) "Protector Walk ON" else "Protector Walk",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Auto Read-Out (${selectedLanguage.nativeName} • ${if (voicePersona == GuardianVoicePersona.MALE) "Male" else "Female"})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isVoiceSpeaking) {
                                Spacer(modifier = Modifier.width(6.dp))
                                TextButton(
                                    onClick = { viewModel.stopVoiceSpeaking() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop Voice", tint = EmergencyRed, modifier = Modifier.size(14.dp))
                                    Text("Mute", fontSize = 10.sp, color = EmergencyRed, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (needsTtsDataInstall && voiceStatusNote.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    try {
                                        context.startActivity(viewModel.buildInstallTtsDataIntent())
                                    } catch (_: Exception) {}
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "$voiceStatusNote (Tap here)",
                                    fontSize = 10.sp,
                                    color = WarningAmber,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Switch(
                        checked = autoReadOut,
                        onCheckedChange = { viewModel.setAutoReadOut(it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Quick Suggestion Chips (PRD Section 14 & 21)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(promptSuggestions) { chip ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { viewModel.sendAiMessage(chip) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = SafetyTeal,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = chip,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Chat Conversation Stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { message ->
                SurakshaAiMessageBubble(
                    message = message,
                    primarySosNumber = emergencyInfo.primarySosNumber,
                    onTriggerSos = {
                        viewModel.triggerImmediateSos("Critical emergency triggered via Suraksha AI safety guidance")
                        onNavigateToSos()
                    },
                    onDialHotline = {
                        viewModel.dialNumber(emergencyInfo.primarySosNumber)
                    },
                    onSpeakText = { textToSpeak ->
                        viewModel.speakTextNow(textToSpeak)
                    }
                )
            }

            when (val uiState = aiUiState) {
                is SurakshaAiUiState.Loading -> {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = SafetyTeal
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Suraksha AI (Gemini) is evaluating your safety question...",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                is SurakshaAiUiState.Streaming -> {
                    item {
                        SurakshaStreamingBubble(partialText = uiState.partialText)
                    }
                }
                is SurakshaAiUiState.Error -> {
                    item {
                        SurakshaErrorCard(
                            errorMessage = uiState.message,
                            primarySosNumber = emergencyInfo.primarySosNumber,
                            onRetry = { viewModel.retryLastAiMessage() },
                            onDismiss = { viewModel.dismissAiError() },
                            onDialHotline = { viewModel.dialNumber(emergencyInfo.primarySosNumber) },
                            onOpenOfflineGuides = { showOfflineGuidesDialog = true }
                        )
                    }
                }
                SurakshaAiUiState.Idle -> Unit
            }
        }

        if (showOfflineGuidesDialog) {
            AlertDialog(
                onDismissRequest = { showOfflineGuidesDialog = false },
                icon = {
                    Icon(
                        Icons.Default.HealthAndSafety,
                        contentDescription = null,
                        tint = SafetyTeal
                    )
                },
                title = {
                    Text("Offline First Aid & Safety Guides", fontWeight = FontWeight.Bold)
                },
                text = {
                    LazyColumn(
                        modifier = Modifier.height(340.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(offlineGuides) { guide ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "${guide.title} (${guide.category})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    guide.steps.split(";").forEach { step ->
                                        if (step.isNotBlank()) {
                                            Text(
                                                text = "• ${step.trim()}",
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showOfflineGuidesDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Accessible Chat Input Row with Hands-Free STT Mic & Send
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (isVoiceListening) {
                        viewModel.stopVoiceListening()
                    } else {
                        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            viewModel.startVoiceListening { spoken -> inputText = spoken }
                        } else {
                            voiceMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isVoiceListening) EmergencyRed else MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("ai_voice_mic_button")
            ) {
                Icon(
                    imageVector = if (isVoiceListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (isVoiceListening) "Stop Voice Input" else "Speak Safety Question",
                    tint = if (isVoiceListening) Color.White else SafetyTeal,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        if (isVoiceListening) "Listening (${selectedLanguage.name})..." else "Ask safety question or describe your situation...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_input_field"),
                shape = RoundedCornerShape(28.dp),
                maxLines = 3,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = SafetyTeal,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendAiMessage(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (inputText.isNotBlank()) SafetyTeal else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .testTag("ai_send_button")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send Message to Suraksha AI",
                    tint = if (inputText.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    // Searchable All-Languages Dialog (Indian + Global Foreign Languages + Device TTS Locales)
    if (showAllLanguagesDialog) {
        val filtered = remember(langSearchQuery, availableLanguages) {
            val q = langSearchQuery.trim()
            if (q.isEmpty()) availableLanguages
            else availableLanguages.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.nativeName.contains(q, ignoreCase = true) ||
                    it.code.contains(q, ignoreCase = true)
            }
        }
        AlertDialog(
            onDismissRequest = { showAllLanguagesDialog = false },
            title = { Text("Select Voice & AI Language (${availableLanguages.size})", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = langSearchQuery,
                        onValueChange = { langSearchQuery = it },
                        placeholder = { Text("Search Hindi, Bengali, Spanish, Japanese, Arabic...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.height(290.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filtered) { lang ->
                            val isChosen = selectedLanguage.tag == lang.tag
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isChosen) SafetyTeal.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setVoiceLanguage(lang)
                                        showAllLanguagesDialog = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("${lang.name} (${lang.code})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(lang.nativeName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isChosen) {
                                        Text("ACTIVE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = SafetyTeal)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAllLanguagesDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    // Predictive Threat & Safe Havens Dialog
    if (showSafeHavensDialog) {
        AlertDialog(
            onDismissRequest = { showSafeHavensDialog = false },
            title = { Text("Predictive Threat & Safe Havens", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${predictiveThreat.headline} (${predictiveThreat.threatScore}/100)",
                        fontWeight = FontWeight.Bold,
                        color = PoliceBlue,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "${predictiveThreat.reasonSummary}\nWeather: ${predictiveThreat.weatherNote}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    predictiveThreat.tacticalSteps.forEach { step ->
                        Text("• $step", fontSize = 11.sp)
                    }
                    if (predictiveThreat.nearbySafeHavens.isNotEmpty()) {
                        Text("Nearby 24/7 Safe Havens:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        predictiveThreat.nearbySafeHavens.take(5).forEach { haven ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SafetyTeal.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.openIncidentInMaps(haven.latitude, haven.longitude, haven.name)
                                    }
                            ) {
                                Text(
                                    text = "${haven.name} (${haven.category}) • ${haven.distanceMeters}m • Tap for Maps",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No cached Overpass Safe Havens yet — tap Refresh with GPS & Internet active.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSafeHavensDialog = false }) { Text("Close") }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.refreshPredictiveThreatNow() }) { Text("Refresh") }
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
}

@Composable
fun SurakshaAiMessageBubble(
    message: AiMessage,
    primarySosNumber: String,
    onTriggerSos: () -> Unit,
    onDialHotline: () -> Unit,
    onSpeakText: (String) -> Unit = {}
) {
    val isUser = message.sender == "user"

    // Pulsing animation ONLY for active emergency alert bubbles (zero idle CPU/battery draw)
    val pulseAlpha = if (message.isEmergencyAlert) {
        val infiniteTransition = rememberInfiniteTransition(label = "danger_pulse")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )
        animatedAlpha
    } else {
        1.0f
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (message.isEmergencyAlert) EmergencyRed else SafetyTeal
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (message.isEmergencyAlert) Icons.Default.Warning else Icons.Default.FlashOn,
                        contentDescription = "Suraksha AI",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = when {
                    isUser -> MaterialTheme.colorScheme.primary
                    message.isEmergencyAlert -> EmergencyRed.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                border = if (message.isEmergencyAlert) {
                    BorderStroke(1.5.dp, EmergencyRed.copy(alpha = pulseAlpha))
                } else null,
                modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.90f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (message.isEmergencyAlert) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = EmergencyRed,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Threat Detected",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "THREAT DETECTED • IMMEDIATE ACTION ADVISED",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    Text(
                        text = message.text,
                        color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    )

                    if (!isUser) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { onSpeakText(message.text) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Read message aloud",
                                    tint = SafetyTeal,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Replay Voice", fontSize = 11.sp, color = SafetyTeal, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Mandatory Emergency Action Affordance (PRD Section 21 & 22)
                    if (message.showSosAction) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onTriggerSos,
                                colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("ai_activate_sos_button")
                            ) {
                                Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SOS NOW",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Button(
                                onClick = onDialHotline,
                                colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PhoneInTalk, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Call $primarySosNumber",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            if (isUser) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "User",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SurakshaStreamingBubble(partialText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_streaming_bubble"),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(SafetyTeal),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = "Suraksha AI Streaming",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.90f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.8.dp,
                        color = SafetyTeal
                    )
                    Text(
                        text = "Streaming response...",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SafetyTeal
                    )
                }
                Text(
                    text = partialText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
            }
        }
    }
}

@Composable
private fun SurakshaErrorCard(
    errorMessage: String,
    primarySosNumber: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onDialHotline: () -> Unit,
    onOpenOfflineGuides: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_error_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EmergencyRed.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, EmergencyRed.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Suraksha AI Error",
                    tint = EmergencyRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Suraksha AI Connection Error",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = EmergencyRed
                )
            }
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("ai_retry_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onDialHotline,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PhoneInTalk, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Call $primarySosNumber", maxLines = 1)
                }
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("Dismiss", maxLines = 1)
                }
            }
            OutlinedButton(
                onClick = onOpenOfflineGuides,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_offline_guides_button"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp), tint = SafetyTeal)
                Spacer(modifier = Modifier.width(6.dp))
                Text("View Offline First Aid & Safety Guides", color = SafetyTeal, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

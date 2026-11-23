package com.example.ui.components

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.FakeIncomingCallService
import com.example.ui.MainViewModel
import com.example.util.FakeCallConfig
import com.example.util.FakeCallState
import java.util.Locale

/**
 * In-app Dialog wrapper for [SystemPhoneCallScreen], synchronized with [FakeIncomingCallService].
 */
@Composable
fun FakeCallOverlay(viewModel: MainViewModel) {
    val callState by viewModel.fakeCallState.collectAsState()
    val config by viewModel.fakeCallConfig.collectAsState()
    val durationSeconds by viewModel.fakeCallDuration.collectAsState()
    val isAudioRecording by viewModel.isAudioRecording.collectAsState()
    val isAudioUploading by FakeIncomingCallService.isAudioUploading.collectAsState()

    val isVisible = callState == FakeCallState.RINGING || callState == FakeCallState.CONNECTED

    if (isVisible) {
        Dialog(
            onDismissRequest = { viewModel.endFakeCall() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            SystemPhoneCallScreen(
                callState = callState,
                config = config,
                durationSeconds = durationSeconds,
                isAudioRecording = isAudioRecording,
                isAudioUploading = isAudioUploading,
                onAnswer = { viewModel.answerFakeCall() },
                onDeclineOrEnd = { viewModel.endFakeCall() },
                onToggleHold = { viewModel.toggleFakeCallHold() },
                onStartRecordNow = { viewModel.startFakeCallVoiceCaptureNow() },
                onCircleAlertNow = {
                    viewModel.dispatchFakeCallAutoEvidenceNow(
                        reason = "User tapped Circle Alert inside Call"
                    )
                }
            )
        }
    }
}

/**
 * Full-Screen Android System Phone / Pixel Dialer Call Interface:
 * - Mimics Android 14/15/16 System Phone incoming & ongoing call screen (`#131314`, `#A8C7FA`, `#1EA861`, `#DC362E`).
 * - Shared identically by [FakeIncomingCallActivity] (lockscreen/background FGS launch) and [FakeCallOverlay].
 * - Supports both manual victim triggers and proactive TFLite / AI Guardian triggers, automatically
 *   capturing call audio and uploading to Firestore without user prompts.
 */
@Composable
fun SystemPhoneCallScreen(
    callState: FakeCallState,
    config: FakeCallConfig,
    durationSeconds: Int,
    isAudioRecording: Boolean,
    isAudioUploading: Boolean = false,
    onAnswer: () -> Unit,
    onDeclineOrEnd: () -> Unit,
    onToggleHold: () -> Unit,
    onStartRecordNow: () -> Unit,
    onCircleAlertNow: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
    }
    val dtmfGenerator = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { dtmfGenerator?.release() }
        }
    }

    val callerInitial = config.callerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G"
    val pulseScale = if (callState == FakeCallState.RINGING) {
        val infiniteTransition = rememberInfiniteTransition(label = "android_phone_ring")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(920, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ring_pulse"
        )
        scale
    } else 1.0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF162133),
                        Color(0xFF131314),
                        Color(0xFF0C0D10)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("fake_call_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top System Phone Carrier / Status + Caller Identity
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Hd,
                        contentDescription = "HD Voice",
                        tint = Color(0xFFA8C7FA),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            callState == FakeCallState.RINGING -> "Incoming call • Wi-Fi Calling"
                            config.isOnHold -> "Call on hold"
                            else -> {
                                val mins = durationSeconds / 60
                                val secs = durationSeconds % 60
                                String.format(Locale.US, "%02d:%02d • HD Voice", mins, secs)
                            }
                        },
                        fontSize = 14.sp,
                        color = if (callState == FakeCallState.CONNECTED && !config.isOnHold) {
                            Color(0xFFA8C7FA)
                        } else {
                            Color(0xFFC4C7C5)
                        },
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = config.callerName.ifBlank { "Emergency Guardian" },
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFFE3E3E3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = config.callerNumber.ifBlank { config.simulatedRole },
                    fontSize = 16.sp,
                    color = Color(0xFFC4C7C5)
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Material You Monogram Avatar with dual pulsing wave halos while ringing
                Box(contentAlignment = Alignment.Center) {
                    if (callState == FakeCallState.RINGING) {
                        Box(
                            modifier = Modifier
                                .size(138.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(Color(0xFFA8C7FA).copy(alpha = 0.12f))
                        )
                        Box(
                            modifier = Modifier
                                .size(118.dp)
                                .scale(1f + (pulseScale - 1f) * 0.6f)
                                .clip(CircleShape)
                                .background(Color(0xFFA8C7FA).copy(alpha = 0.20f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF004A77)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = callerInitial,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFC2E7FF)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Discreet Call Guard & Auto-Upload Pill
                if (config.autoRecordAndShare || config.isAiProactive || isAudioUploading) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF1E293B).copy(alpha = 0.85f),
                        modifier = Modifier.testTag("fake_call_auto_guard_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isAudioRecording) Icons.Default.FiberManualRecord else Icons.Default.Shield,
                                contentDescription = null,
                                tint = if (isAudioRecording) Color(0xFFEF4444) else Color(0xFFA8C7FA),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    isAudioUploading -> "Uploading call audio to Firestore Vault..."
                                    isAudioRecording && config.autoRecordAndShare ->
                                        "Recording call audio • Auto-upload to Circle"
                                    config.isAiProactive ->
                                        "AI Guard Proactive Call • Auto-Record Ready"
                                    else ->
                                        "Call Audio Vault & Circle Sync Armed"
                                },
                                fontSize = 11.sp,
                                color = Color(0xFFD3E3FD),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Middle Google Dialer Controls (when connected)
            if (callState == FakeCallState.CONNECTED) {
                var isMuted by remember { mutableStateOf(false) }
                var isSpeaker by remember { mutableStateOf(false) }
                var showKeypad by remember { mutableStateOf(false) }
                var dialedDigits by remember { mutableStateOf("") }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (showKeypad) {
                        Text(
                            text = dialedDigits.ifBlank { "Enter number" },
                            color = Color(0xFFE3E3E3),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                        listOf(
                            listOf("1" to ToneGenerator.TONE_DTMF_1, "2" to ToneGenerator.TONE_DTMF_2, "3" to ToneGenerator.TONE_DTMF_3),
                            listOf("4" to ToneGenerator.TONE_DTMF_4, "5" to ToneGenerator.TONE_DTMF_5, "6" to ToneGenerator.TONE_DTMF_6),
                            listOf("7" to ToneGenerator.TONE_DTMF_7, "8" to ToneGenerator.TONE_DTMF_8, "9" to ToneGenerator.TONE_DTMF_9),
                            listOf("*" to ToneGenerator.TONE_DTMF_S, "0" to ToneGenerator.TONE_DTMF_0, "#" to ToneGenerator.TONE_DTMF_P)
                        ).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                row.forEach { (digit, tone) ->
                                    Surface(
                                        onClick = {
                                            dialedDigits += digit
                                            runCatching { dtmfGenerator?.startTone(tone, 110) }
                                        },
                                        shape = CircleShape,
                                        color = Color(0xFF282A2F),
                                        modifier = Modifier.size(56.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(digit, color = Color(0xFFE3E3E3), fontSize = 20.sp)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                GoogleDialerActionButton(
                                    icon = Icons.Default.MicOff,
                                    label = "Mute",
                                    isActive = isMuted,
                                    onClick = {
                                        isMuted = !isMuted
                                        try { audioManager?.isMicrophoneMute = isMuted } catch (_: Exception) {}
                                    }
                                )
                                GoogleDialerActionButton(
                                    icon = Icons.Default.Dialpad,
                                    label = "Keypad",
                                    isActive = showKeypad,
                                    onClick = { showKeypad = !showKeypad }
                                )
                                GoogleDialerActionButton(
                                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                                    label = "Speaker",
                                    isActive = isSpeaker,
                                    onClick = {
                                        isSpeaker = !isSpeaker
                                        try {
                                            @Suppress("DEPRECATION")
                                            audioManager?.isSpeakerphoneOn = isSpeaker
                                        } catch (_: Exception) {}
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                GoogleDialerActionButton(
                                    icon = Icons.Default.NotificationsActive,
                                    label = "Circle Alert",
                                    isActive = config.autoRecordAndShare,
                                    onClick = onCircleAlertNow
                                )
                                GoogleDialerActionButton(
                                    icon = Icons.Default.Pause,
                                    label = if (config.isOnHold) "Resume" else "Hold",
                                    isActive = config.isOnHold,
                                    onClick = onToggleHold
                                )
                                GoogleDialerActionButton(
                                    icon = Icons.Default.FiberManualRecord,
                                    label = if (isAudioRecording) "Rec ON" else "Record",
                                    isActive = isAudioRecording,
                                    activeTint = Color(0xFFEF4444),
                                    onClick = {
                                        if (!isAudioRecording) {
                                            onStartRecordNow()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(64.dp))
            }

            // Bottom System Phone Call Action Pills
            if (callState == FakeCallState.RINGING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            onClick = onDeclineOrEnd,
                            shape = RoundedCornerShape(36.dp),
                            color = Color(0xFFDC362E),
                            modifier = Modifier
                                .size(width = 84.dp, height = 68.dp)
                                .testTag("decline_fake_call")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "Decline Call",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Decline", color = Color(0xFFE3E3E3), fontSize = 13.sp)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            onClick = onAnswer,
                            shape = RoundedCornerShape(36.dp),
                            color = Color(0xFF1EA861),
                            modifier = Modifier
                                .size(width = 84.dp, height = 68.dp)
                                .testTag("accept_fake_call")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "Answer Call",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Answer", color = Color(0xFFE3E3E3), fontSize = 13.sp)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        onClick = onDeclineOrEnd,
                        shape = RoundedCornerShape(38.dp),
                        color = Color(0xFFDC362E),
                        modifier = Modifier
                            .size(width = 96.dp, height = 68.dp)
                            .testTag("end_fake_call")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoogleDialerActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeTint: Color = Color(0xFF003258),
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(78.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (isActive) Color(0xFFD3E3FD) else Color(0xFF282A2F),
            modifier = Modifier.size(60.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) activeTint else Color(0xFFE3E3E3),
                    modifier = Modifier.size(25.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (isActive) Color(0xFFD3E3FD) else Color(0xFFC4C7C5),
            maxLines = 1
        )
    }
}

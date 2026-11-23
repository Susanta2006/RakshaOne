package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.auth.AuthState
import com.example.ui.MainViewModel
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.util.PhoneNormalizer

@Composable
fun AuthScreen(
    viewModel: MainViewModel,
    onAuthSuccess: () -> Unit,
    onContinueOffline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authState by viewModel.authState.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()

    var isRegisterMode by rememberSaveable { mutableStateOf(false) }
    var phoneInput by rememberSaveable(userProfile) { mutableStateOf(userProfile?.phone ?: "") }
    var bloodGroupInput by rememberSaveable(userProfile) { mutableStateOf(userProfile?.bloodGroup ?: "") }
    var phoneValidationError by rememberSaveable { mutableStateOf<String?>(null) }

    val isLoading = authState is AuthState.Loading
    val errorMessage = phoneValidationError ?: (authState as? AuthState.Error)?.message

    BackHandler {
        viewModel.clearAuthError()
        onContinueOffline()
    }

    val accentGlow = if (isRegisterMode) SafetyTeal else PoliceBlue
    val bgTop = MaterialTheme.colorScheme.background
    val glowColor = accentGlow.copy(alpha = 0.14f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgTop)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.16f),
                        radius = size.width * 0.85f
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.16f),
                    radius = size.width * 0.85f
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar back navigation to SOS Home
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        viewModel.clearAuthError()
                        onContinueOffline()
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("auth_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Return to RakshaOne"
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = SafetyTeal,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Firebase Auth Protected",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hero Shield Emblem
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(EmergencyRed, accentGlow)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "RakshaOne Shield",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isRegisterMode) "Register Safety Account" else "Sign In to RakshaOne",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (isRegisterMode) {
                    "Link your Google account to create your verified emergency identity and sync your medical profile."
                } else {
                    "Sign in with Google to restore your verified RakshaOne profile, or stay in Guest Offline Vault mode."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Mode Switcher: Sign In vs Register
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AuthModeTab(
                        label = "Sign In",
                        icon = Icons.Default.AccountCircle,
                        selected = !isRegisterMode,
                        onClick = {
                            isRegisterMode = false
                            viewModel.clearAuthError()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("auth_tab_sign_in")
                    )
                    AuthModeTab(
                        label = "Register",
                        icon = Icons.Default.PersonAdd,
                        selected = isRegisterMode,
                        onClick = {
                            isRegisterMode = true
                            viewModel.clearAuthError()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("auth_tab_register")
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Main Auth Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Unique RakshaOne Phone ID (Required)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isRegisterMode) SafetyTeal else PoliceBlue
                        )
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = {
                                phoneInput = it
                                if (PhoneNormalizer.isValidPhoneNumber(it)) {
                                    phoneValidationError = null
                                }
                            },
                            label = { Text("Mobile Number *") },
                            placeholder = { Text("With country code e.g. +91...") },
                            supportingText = {
                                Text("Used as your unique ID so trusted contacts receive direct in-app high alerts")
                            },
                            isError = phoneValidationError != null,
                            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("register_phone_input")
                        )

                        AnimatedVisibility(
                            visible = isRegisterMode,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            OutlinedTextField(
                                value = bloodGroupInput,
                                onValueChange = { bloodGroupInput = it },
                                label = { Text("Blood Group (Optional)") },
                                placeholder = { Text("e.g. O+, A-, AB+") },
                                leadingIcon = { Icon(Icons.Default.Bloodtype, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("register_blood_group_input")
                            )
                        }
                    }

                    if (!isRegisterMode) {
                        BenefitRow(
                            icon = Icons.Default.VerifiedUser,
                            tint = PoliceBlue,
                            title = "Phone Number = Unique RakshaOne ID",
                            subtitle = "Contacts who have RakshaOne receive direct in-app push alerts + live Front/Rear evidence."
                        )
                        BenefitRow(
                            icon = Icons.Default.CloudDone,
                            tint = SafetyTeal,
                            title = "Zero-Deploy Real-Time Sync",
                            subtitle = "Safety profile, medical ID & high alerts sync directly without external servers."
                        )
                        BenefitRow(
                            icon = Icons.Default.CheckCircle,
                            tint = EmergencyRed,
                            title = "WhatsApp, SMS & Bluetooth Hex Rescue",
                            subtitle = "Shares evidence only with Trusted Contacts and nearby peer-verified citizens."
                        )
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = EmergencyRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, EmergencyRed.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = errorMessage,
                                color = EmergencyRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (!PhoneNormalizer.isValidPhoneNumber(phoneInput)) {
                                phoneValidationError = "Enter a valid 10+ digit mobile number — used as your unique RakshaOne ID."
                                return@Button
                            }
                            phoneValidationError = null
                            viewModel.signInOrRegisterWithGoogle(
                                activityContext = context,
                                pendingPhone = phoneInput,
                                pendingBloodGroup = if (isRegisterMode) bloodGroupInput else "",
                                onSuccess = onAuthSuccess
                            )
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRegisterMode) SafetyTeal else PoliceBlue,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("google_auth_primary_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Connecting Google Account...",
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = if (isRegisterMode) Icons.Default.PersonAdd else Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isRegisterMode) "Register with Google" else "Sign in with Google",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.clearAuthError()
                            onContinueOffline()
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("continue_guest_offline_button")
                    ) {
                        Text(
                            text = "Continue in Guest Offline Vault",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            TextButton(
                onClick = {
                    isRegisterMode = !isRegisterMode
                    viewModel.clearAuthError()
                },
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(
                    text = if (isRegisterMode) {
                        "Already registered? Switch to Sign In"
                    } else {
                        "New to RakshaOne? Switch to Register"
                    },
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AuthModeTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = modifier.minimumInteractiveComponentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BenefitRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

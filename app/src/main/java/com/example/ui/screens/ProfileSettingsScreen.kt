package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.auth.AuthState
import com.example.data.model.SafetyGuideEntity
import com.example.data.model.UserProfileEntity
import com.example.risk.CitizenVerificationStatus
import com.example.ui.MainViewModel
import com.example.ui.components.AppGuideDialog
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.WarningAmber

@Composable
fun ProfileSettingsScreen(
    viewModel: MainViewModel,
    onOpenAuthScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val profile by viewModel.userProfile.collectAsState()
    val guides by viewModel.safetyGuides.collectAsState()
    val isOperatorMode by viewModel.isOperatorMode.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val goodCitizenProfile by viewModel.goodCitizenProfile.collectAsState()
    val pointsLedger by viewModel.pointsLedger.collectAsState()
    val isVolumeDownSilentSosEnabled by viewModel.isVolumeDownSilentSosEnabled.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.statusEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(authState) {
        if (authState is AuthState.Error) {
            snackbarHostState.showSnackbar((authState as AuthState.Error).message)
        }
    }

    var name by remember(profile, currentUser) {
        mutableStateOf(profile?.name?.takeIf { it.isNotBlank() } ?: currentUser?.displayName ?: "")
    }
    var phone by remember(profile) { mutableStateOf(profile?.phone ?: "") }
    val internalEmail = profile?.email?.takeIf { it.isNotBlank() } ?: currentUser?.email ?: ""
    var bloodGroup by remember(profile) { mutableStateOf(profile?.bloodGroup ?: "") }
    var allergies by remember(profile) { mutableStateOf(profile?.allergies ?: "") }
    var medicalNotes by remember(profile) { mutableStateOf(profile?.medicalNotes ?: "") }
    var homeAddress by remember(profile) { mutableStateOf(profile?.homeAddress ?: "") }
    var workAddress by remember(profile) { mutableStateOf(profile?.workAddress ?: "") }
    var autoSms by remember(profile) { mutableStateOf(profile?.autoSendSmsOnSos ?: true) }
    var silentSos by remember(profile) { mutableStateOf(profile?.silentSosMode ?: false) }

    var selectedGuide by remember { mutableStateOf<SafetyGuideEntity?>(null) }
    var showAppManualDialog by remember { mutableStateOf(false) }
    var showSavedMessage by remember { mutableStateOf(false) }
    var peerEndorsementCodeInput by remember { mutableStateOf("") }
    var decoyPinInput by remember {
        mutableStateOf(viewModel.repository.duressPinManager.getDecoyCancelPin())
    }

    val phoneHintLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            try {
                val hintPhone = Identity.getSignInClient(context).getPhoneNumberFromIntent(res.data)
                if (hintPhone.isNotBlank()) {
                    phone = hintPhone
                    viewModel.verifyPhoneHintResult(hintPhone)
                }
            } catch (_: Exception) {}
        }
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
                    text = "Profile & Safety Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "Your Google account, unique Phone ID, medical card, and safety manual.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Interactive User Manual Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAppManualDialog = true }
                        .testTag("profile_open_app_guide_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PoliceBlue.copy(alpha = 0.12f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = PoliceBlue, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("How to Use RakshaOne (Manual)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PoliceBlue)
                                Text(
                                    "Phone ID alerts, Travel Bump immunity, Hex rescue & vault purge guide",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        OutlinedButton(onClick = { showAppManualDialog = true }) {
                            Text("Read", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Google Firebase Auth Account Management Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_account_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Account & Authentication",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (currentUser != null) SafetyTeal.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant
                            ) {
                                Text(
                                    text = if (currentUser != null) "GOOGLE VERIFIED" else "GUEST OFFLINE",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currentUser != null) SafetyTeal else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (currentUser != null) {
                            val displayIdentity = currentUser?.displayName?.takeIf { it.isNotBlank() }
                            if (displayIdentity != null) {
                                Text(
                                    text = displayIdentity,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = currentUser?.email ?: currentUser?.uid.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Google Firebase Authentication active • Profile synced to Firestore",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { viewModel.signOut() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sign_out_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sign Out")
                            }
                        } else {
                            Text(
                                text = "Guest Offline Vault is active. Sign in or register with your Google account to verify your identity and sync your medical safety profile.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onOpenAuthScreen,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("open_auth_screen_button")
                            ) {
                                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sign In / Register with Google", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 100% Free Phone Verification & Community Responder Progress
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("free_phone_verification_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Citizen & Free Phone Verification",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (goodCitizenProfile.isPhoneVerified || goodCitizenProfile.isIdVerified) SafetyTeal.copy(alpha = 0.2f) else WarningAmber.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = if (goodCitizenProfile.isPhoneVerified || goodCitizenProfile.isIdVerified) "VERIFIED" else "PENDING",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (goodCitizenProfile.isPhoneVerified || goodCitizenProfile.isIdVerified) SafetyTeal else WarningAmber
                                )
                            }
                        }

                        // Responder Progress Indicator
                        val isPhoneOk = goodCitizenProfile.isPhoneVerified || goodCitizenProfile.isIdVerified
                        val trustPoints = goodCitizenProfile.trustScore
                        val progressText = when {
                            trustPoints >= 30 && isPhoneOk -> "Responder Duty Unlocked! (30/30 pts)"
                            isPhoneOk -> "Nearly There! Add 1 Core Family Member (Father/Mother/Brother/Sister/Spouse) OR 2 Circle Contacts OR Referral Code (+15 pts)"
                            else -> "Requires 30 Trust Points: Verify Phone (+15) + 1 Core Family Member or 2 Circle Contacts (+15)"
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (trustPoints >= 30) SafetyTeal.copy(alpha = 0.15f) else PoliceBlue.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Status: $progressText",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = if (trustPoints >= 30) SafetyTeal else PoliceBlue
                                )
                                Text(
                                    text = "• Phone Verified: ${if (isPhoneOk) "Yes (+15 pts)" else "Pending"}\n• Trust Score: $trustPoints / 30 pts required",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (goodCitizenProfile.endorsementCode.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = PoliceBlue.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Your Referral Code: ${goodCitizenProfile.endorsementCode}",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 13.sp,
                                        color = PoliceBlue
                                    )
                                    Text(
                                        text = "Share this code with a friend or add 2+ contacts to your Trusted Circle to unlock Responder duty.",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Free Phone Verification via SIM Number Hint, Self-SMS or WhatsApp
                        if (!goodCitizenProfile.isPhoneVerified && !goodCitizenProfile.isIdVerified) {
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Step 1: ₹0 Free Phone Verification", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PoliceBlue)
                                    Text(
                                        "Select your active SIM number instantly or use Self-SMS loopback.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    var selfPinInput by remember { mutableStateOf("") }
                                    var generatedPin by remember { mutableStateOf("") }

                                    // Real Google Play Services PhoneNumberHintIntent (₹0, zero OTP SMS cost)
                                    Button(
                                        onClick = {
                                            val request = GetPhoneNumberHintIntentRequest.builder().build()
                                            Identity.getSignInClient(context).getPhoneNumberHintIntent(request)
                                                .addOnSuccessListener { pendingIntent ->
                                                    try {
                                                        phoneHintLauncher.launch(
                                                            IntentSenderRequest.Builder(pendingIntent).build()
                                                        )
                                                    } catch (_: Exception) {
                                                        if (phone.trim().length >= 10) {
                                                            viewModel.verifyPhoneHintResult(phone.trim())
                                                        }
                                                    }
                                                }
                                                .addOnFailureListener {
                                                    if (phone.trim().length >= 10) {
                                                        viewModel.verifyPhoneHintResult(phone.trim())
                                                    } else {
                                                        generatedPin = viewModel.generateSelfSmsCode()
                                                        val smsUri = Uri.parse("smsto:${phone.ifBlank { "+919999999999" }}")
                                                        val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                                                            putExtra("sms_body", "RakshaOne Verification Code: $generatedPin")
                                                        }
                                                        try {
                                                            context.startActivity(intent)
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("verify_sim_hint_button"),
                                        colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal)
                                    ) {
                                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Verify Active SIM Number (Google Play Hint)", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                                    }

                                    // Single-line buttons with identical height
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = {
                                                generatedPin = viewModel.generateSelfSmsCode()
                                                val smsUri = Uri.parse("smsto:${phone.ifBlank { "+919999999999" }}")
                                                val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                                                    putExtra("sms_body", "RakshaOne Verification Code: $generatedPin")
                                                }
                                                try {
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue)
                                        ) {
                                            Text("Self-SMS", fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                val link = "https://wa.me/?text=" + Uri.encode("Verify my RakshaOne Responder ID! Open link: rakshaone://verify-peer?uid=${currentUser?.uid ?: "user"}&code=${goodCitizenProfile.endorsementCode}")
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                                try {
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("WhatsApp", fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    OutlinedTextField(
                                        value = selfPinInput,
                                        onValueChange = { if (it.length <= 6) selfPinInput = it.filter(Char::isDigit) },
                                        label = { Text("Enter 6-Digit Code") },
                                        placeholder = { Text(generatedPin.ifBlank { "e.g. 482910" }) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Button(
                                        onClick = {
                                            viewModel.verifySelfSmsCode(selfPinInput)
                                            selfPinInput = ""
                                        },
                                        enabled = selfPinInput.length == 6,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal)
                                    ) {
                                        Text("Confirm 6-Digit Code (+15 Pts)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // Referral Code Redemption
                        OutlinedTextField(
                            value = peerEndorsementCodeInput,
                            onValueChange = { peerEndorsementCodeInput = it.uppercase() },
                            label = { Text("Enter Friend's Referral Code") },
                            placeholder = { Text("e.g. RK-7956-FH") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("peer_endorsement_code_input")
                        )
                        OutlinedButton(
                            onClick = {
                                viewModel.redeemReferralCode(peerEndorsementCodeInput)
                                peerEndorsementCodeInput = ""
                            },
                            enabled = peerEndorsementCodeInput.trim().length >= 5,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("endorse_peer_button")
                        ) {
                            Text("Redeem Referral Code (+15 Pts)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }

                        if (pointsLedger.isNotEmpty()) {
                            Text("Recent Trust Points Ledger:", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            pointsLedger.take(4).forEach { entry ->
                                val sign = if (entry.delta >= 0) "+" else ""
                                Text(
                                    text = "• $sign${entry.delta} pts — ${entry.reasonCode} (Total: ${entry.balanceAfter})",
                                    fontSize = 11.sp,
                                    color = if (entry.delta >= 0) SafetyTeal else EmergencyRed
                                )
                            }
                        }
                    }
                }
            }

            // Decoy Cancel PIN Card (Coerced Cancellation Stealth Protection)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("decoy_cancel_pin_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = EmergencyRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Decoy Cancel PIN (Anti-Coercion Shield)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "If forced to cancel an active SOS, entering this PIN visually switches RakshaOne to Standby while secretly keeping GPS tracking & evidence recording running.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = decoyPinInput,
                                onValueChange = { if (it.length <= 6) decoyPinInput = it.filter(Char::isDigit) },
                                label = { Text("Decoy PIN (default 0000)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("decoy_pin_input")
                            )
                            Button(
                                onClick = {
                                    viewModel.repository.duressPinManager.setDecoyCancelPin(decoyPinInput)
                                    showSavedMessage = true
                                },
                                enabled = decoyPinInput.length >= 4,
                                colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                                modifier = Modifier.testTag("save_decoy_pin_button")
                            ) {
                                Text("Save PIN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Hexagonal & Emergency Dispatch Screen Switch (Not an admin page)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isOperatorMode) PoliceBlue.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.LocalPolice,
                                contentDescription = null,
                                tint = PoliceBlue,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Emergency & Hex Dispatch Screen",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "View nearby Hexagonal pickup requests, BLE relays & live incidents",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = isOperatorMode,
                            onCheckedChange = { viewModel.setOperatorMode(it) },
                            modifier = Modifier.testTag("operator_mode_switch")
                        )
                    }
                }
            }

            // Medical Emergency ID Card
            item {
                Text(
                    text = "Emergency Medical Identity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Full Name") },
                                placeholder = { Text("Enter your name") },
                                modifier = Modifier.weight(1.5f)
                            )
                            OutlinedTextField(
                                value = bloodGroup,
                                onValueChange = { bloodGroup = it },
                                label = { Text("Blood Group") },
                                placeholder = { Text("e.g., O+") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Primary Mobile Number (Unique RakshaOne ID)") },
                            placeholder = { Text("With country code e.g. +91...") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = allergies,
                            onValueChange = { allergies = it },
                            label = { Text("Known Allergies") },
                            placeholder = { Text("e.g., Penicillin, Peanuts, or None") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = medicalNotes,
                            onValueChange = { medicalNotes = it },
                            label = { Text("Critical Medical Notes") },
                            placeholder = { Text("Conditions, medications, emergency notes") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = homeAddress,
                            onValueChange = { homeAddress = it },
                            label = { Text("Home Safe Address") },
                            placeholder = { Text("Your home street address") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = workAddress,
                            onValueChange = { workAddress = it },
                            label = { Text("Work / Campus Address") },
                            placeholder = { Text("Regular workplace or college") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-SMS Alert on SOS", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Dispatches GPS link to trusted contacts (works on keypad phones)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = autoSms, onCheckedChange = { autoSms = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Silent SOS Stealth Mode", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Suppresses alarm siren, flash & voice • Auto-activates on Lockscreen 3x Vol-Down", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = silentSos,
                                onCheckedChange = {
                                    silentSos = it
                                    viewModel.setSilentMode(it)
                                },
                                modifier = Modifier.testTag("profile_silent_sos_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Lockscreen 3x Vol-Down Stealth SOS", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("ONLY when locked or screen off • Ignores long-hold, 2 taps & pauses", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = isVolumeDownSilentSosEnabled,
                                onCheckedChange = { viewModel.setVolumeDownSilentSosEnabled(it) },
                                modifier = Modifier.testTag("profile_lockscreen_vol_down_switch")
                            )
                        }

                        Button(
                            onClick = {
                                viewModel.updateProfile(
                                    UserProfileEntity(
                                        name = name.trim(),
                                        phone = phone.trim(),
                                        email = internalEmail.trim(),
                                        bloodGroup = bloodGroup.trim(),
                                        allergies = allergies.trim(),
                                        medicalNotes = medicalNotes.trim(),
                                        homeAddress = homeAddress.trim(),
                                        workAddress = workAddress.trim(),
                                        autoSendSmsOnSos = autoSms,
                                        sirenBeaconEnabled = !silentSos,
                                        silentSosMode = silentSos,
                                        antiSnatchEnabled = profile?.antiSnatchEnabled ?: false,
                                        autoLockOnSos = false
                                    )
                                )
                                showSavedMessage = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_profile_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Profile & Medical Card")
                        }
                    }
                }
            }

            // Offline Emergency Protocols
            item {
                Text(
                    text = "Offline Emergency Procedures",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Locally cached emergency instructions accessible with zero network connectivity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(guides) { guide ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedGuide = guide },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Book,
                            contentDescription = null,
                            tint = SafetyTeal,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = guide.title,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "${guide.category} • Tap to view steps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (selectedGuide != null) {
        val guide = selectedGuide!!
        AlertDialog(
            onDismissRequest = { selectedGuide = null },
            icon = { Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(32.dp)) },
            title = { Text(guide.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(guide.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    guide.steps.split(";").forEach { step ->
                        Text(
                            text = "• ${step.trim()}",
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { selectedGuide = null }) {
                    Text("Got It")
                }
            }
        )
    }

    if (showSavedMessage) {
        AlertDialog(
            onDismissRequest = { showSavedMessage = false },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SafetyTeal) },
            title = { Text("Profile Updated") },
            text = { Text("Your medical card and emergency preferences have been saved locally.") },
            confirmButton = {
                Button(onClick = { showSavedMessage = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showAppManualDialog) {
        AppGuideDialog(onDismiss = { showAppManualDialog = false })
    }
}

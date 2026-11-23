package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.WarningAmber

private data class GuideItem(
    val number: String,
    val category: String,
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val description: String
)

@Composable
fun AppGuideDialog(
    onDismiss: () -> Unit
) {
    val categories = listOf("All", "Suraksha AI", "SOS & Stealth", "Map & Circle", "Trips & Rescue")
    var selectedCategory by remember { mutableStateOf("All") }

    val guideItems = remember {
        listOf(
            GuideItem(
                number = "1",
                category = "Suraksha AI",
                title = "How to Use Suraksha AI (Zero-API-Key)",
                icon = Icons.Default.AutoAwesome,
                accent = SafetyTeal,
                description = "Open the AI tab anytime—even with ZERO API key or no internet. Ask about first aid, self-defense, harassment de-escalation, or legal rights. Danger phrases like 'Someone is following me' automatically trigger an emergency override card with SOS & 112 dialer buttons."
            ),
            GuideItem(
                number = "2",
                category = "Suraksha AI",
                title = "Male / Female Voice & All Languages",
                icon = Icons.Default.RecordVoiceOver,
                accent = PoliceBlue,
                description = "Use the Female / Male chips at the top of Suraksha AI to switch between Female Guardian and Male Protector voices, and tap 'Test Voice' to preview immediately. Pick EN / HI / BN or tap 'All Languages' for Indian & foreign languages. Enable 'Auto Read-Out' for spoken replies or tap the Mic button for hands-free voice questions."
            ),
            GuideItem(
                number = "3",
                category = "Suraksha AI",
                title = "Protector Walk & Predictive Safe Havens",
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                accent = SafetyTeal,
                description = "Activate 'Protector Walk' when walking alone so RakshaOne periodically speaks realistic check-in lines and listens for your secret distress phrase ('help raksha', customizable in Profile). Tap 'Risk /100 • Safe Havens' in the AI header to view real-time danger telemetry and nearby 24/7 police booths, hospitals, or crowded landmarks."
            ),
            GuideItem(
                number = "4",
                category = "Trips & Rescue",
                title = "Cab / Auto Plate Scanner & Trip Monitor",
                icon = Icons.Default.DirectionsCar,
                accent = PoliceBlue,
                description = "Before boarding a cab or auto, tap 'Scan Cab Plate' inside Suraksha AI or Trips to run offline ML Kit OCR on the vehicle number plate (camera or photo) and share the plate + live GPS directly to your Circle. Start Trip Monitor to auto-detect route deviations or prolonged stationary stops."
            ),
            GuideItem(
                number = "5",
                category = "SOS & Stealth",
                title = "One-Tap SOS, Dual CameraX & All-Media Dispatch",
                icon = Icons.Default.Shield,
                accent = EmergencyRed,
                description = "Press SOS (5s countdown) or Instant SOS to silently record BOTH Front & Rear CameraX photos + videos and AES-256 encrypted audio. ALL captured photos, videos, audio clips, and live coordinates are dispatched together via WhatsApp, MMS, Email, and Firestore."
            ),
            GuideItem(
                number = "6",
                category = "SOS & Stealth",
                title = "Decoy Cancel PIN (0000) & Pocket Gestures",
                icon = Icons.Default.Lock,
                accent = WarningAmber,
                description = "Under coercion, enter your Decoy PIN (default 0000; customize in Profile) when cancelling SOS: the screen appears calm and paused while silently keeping live GPS & audio recording active. You can also trigger SOS secretly by pressing Volume-Up 4 times quickly or unplugging wired earphones when Pocket Gesture is armed."
            ),
            GuideItem(
                number = "7",
                category = "SOS & Stealth",
                title = "Realistic Fake Call & Travel Bump Immunity",
                icon = Icons.Default.PhoneInTalk,
                accent = PoliceBlue,
                description = "Trigger an instant or delayed Fake Call from Home using your chosen Female or Male voice script to exit uncomfortable situations. Meanwhile, Speed-breaker & road-bump filtering ensures bumpy vehicle rides (R0) NEVER cause false SOS alarms."
            ),
            GuideItem(
                number = "8",
                category = "Map & Circle",
                title = "Free Phone Verification & Core Family Circle",
                icon = Icons.Default.People,
                accent = SafetyTeal,
                description = "Verify your active SIM in 1 tap using Google Play Phone Hint (zero OTP fees) or Peer Referral code (RK-XXXX-XX). Import contacts cleanly from your phonebook: Core Family (Father, Mother, Brother, Sister, Spouse) get automatic 24/7 live GPS access (+15 Trust Points each); Extended Circle gets location automatically during Trips, <10% battery, or SOS."
            ),
            GuideItem(
                number = "9",
                category = "Map & Circle",
                title = "3D Street Radar Map & Disconnect Alerts",
                icon = Icons.Default.Map,
                accent = PoliceBlue,
                description = "Open Dispatch to view a 100% free OpenFreeMap 3D vector map with tilting 3D buildings, smoothly moving family pins, battery badges, Fit All Family, and instant Street View / Google Maps launch. If you pause location or battery drops below 10%, Core Family immediately receives your final GPS alert."
            ),
            GuideItem(
                number = "10",
                category = "Trips & Rescue",
                title = "6-Sector Hex Rescue, BLE Mesh & Selfie Lock",
                icon = Icons.Default.Face,
                accent = EmergencyRed,
                description = "Verified citizens can enable Responder Duty to assist nearby R3 SOS alerts via Firestore + offline Bluetooth LE Mesh across 6 compass sectors. Anti-Predator Safety Lock requires an on-device ML Kit front-camera face liveness check before accepting any rescue."
            ),
            GuideItem(
                number = "11",
                category = "Trips & Rescue",
                title = "Zero-FIR Export & 25 MB Storage Warden",
                icon = Icons.Default.Gavel,
                accent = WarningAmber,
                description = "Export a court-ready SHA-256 hashed Zero-FIR timeline report from Reports anytime. Autonomous Storage Warden runs daily via WorkManager to compact >24h GPS logs into geohashes, keep a 3-minute audio/video circular buffer, enforce a 25 MB hard cap, and purge resolved SOS vaults when you tap 'Confirm Safe & Purge Vault'."
            )
        )
    }

    val visibleItems = remember(selectedCategory, guideItems) {
        if (selectedCategory == "All") guideItems
        else guideItems.filter { it.category == selectedCategory }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("app_guide_dialog"),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(PoliceBlue.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = PoliceBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "RakshaOne Field Manual",
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Suraksha AI, SOS, 3D Map & All Features",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = {
                                Text(cat, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 390.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    visibleItems.forEach { item ->
                        GuideStepCard(
                            stepNumber = item.number,
                            title = item.title,
                            icon = item.icon,
                            accent = item.accent,
                            description = item.description
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = EmergencyRed.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, EmergencyRed.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = EmergencyRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Strict Policy: Automatic 112 escalation is locked strictly to R3-CRITICAL SOS triggers—never R0, R1, or R2 advisories.",
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                modifier = Modifier.testTag("close_app_guide_button")
            ) {
                Text("Got It • Start Protected", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun GuideStepCard(
    stepNumber: String,
    title: String,
    icon: ImageVector,
    accent: Color,
    description: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$stepNumber. $title",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GoodCitizenProfile
import com.example.data.model.HexAlertStatus
import com.example.data.model.HexRescueAlert
import com.example.data.model.PoliceDispatchDossier
import com.example.data.repository.CloudEmergencyDispatch
import com.example.risk.CitizenVerificationStatus
import com.example.risk.HexRescueLifecycleState
import com.example.risk.PoliceEscalationStatus
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.WarningAmber
import com.example.util.BleRelayStatus

/**
 * Uber/Rapido-style Six-Directional Hexagonal Rescue Alert Overlay:
 * - Displays real incoming Firestore + Bluetooth LE emergency pickup requests.
 * - Shows 6-sector compass direction (N-NE, E, S-SE, S-SW, W, N-NW), radius stage, & 30s countdown.
 * - Full responder pickup lifecycle: Accept (+15 pts) -> En Route -> Arrived -> Complete (+25 pts).
 */
@Composable
fun HexRescueAlertCard(
    incomingAlert: HexRescueAlert?,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onNavigateToVictim: (Double, Double, String) -> Unit,
    onDial112: () -> Unit,
    onMarkEnRoute: (String) -> Unit = {},
    onMarkArrived: (String) -> Unit = {},
    onCompleteRescue: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = incomingAlert != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        if (incomingAlert == null) return@AnimatedVisibility

        val isAcceptedOrInProgress = incomingAlert.status == HexAlertStatus.ACCEPTED ||
            incomingAlert.lifecycleState in listOf(
                HexRescueLifecycleState.RESPONDER_ACCEPTED,
                HexRescueLifecycleState.EN_ROUTE,
                HexRescueLifecycleState.ARRIVED,
                HexRescueLifecycleState.COMPLETED
            )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("hex_rescue_alert_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isAcceptedOrInProgress) {
                    SafetyTeal.copy(alpha = 0.12f)
                } else {
                    EmergencyRed.copy(alpha = 0.12f)
                }
            ),
            border = BorderStroke(1.5.dp, SolidColor(if (isAcceptedOrInProgress) SafetyTeal else EmergencyRed))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAcceptedOrInProgress) SafetyTeal else EmergencyRed
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isAcceptedOrInProgress) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (incomingAlert.lifecycleState) {
                                    HexRescueLifecycleState.RESPONDER_ACCEPTED -> "RESCUE ACCEPTED"
                                    HexRescueLifecycleState.EN_ROUTE -> "RESPONDER EN ROUTE"
                                    HexRescueLifecycleState.ARRIVED -> "ARRIVED AT SECTOR"
                                    HexRescueLifecycleState.COMPLETED -> "RESCUE COMPLETED"
                                    else -> "R3 SOS IN HEX ZONE (${incomingAlert.sourceChannel})"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (!isAcceptedOrInProgress) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                tint = EmergencyRed,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${incomingAlert.secondsRemainingInStage}s • R${incomingAlert.currentRadiusMeters}m",
                                fontWeight = FontWeight.Bold,
                                color = EmergencyRed,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isAcceptedOrInProgress) SafetyTeal.copy(alpha = 0.2f) else EmergencyRed.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsRun,
                            contentDescription = null,
                            tint = if (isAcceptedOrInProgress) SafetyTeal else EmergencyRed,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${incomingAlert.victimName} needs help!",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Explore,
                                contentDescription = null,
                                tint = PoliceBlue,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Sector ${incomingAlert.compassSector} • ~${incomingAlert.distanceMeters.toInt()}m (${incomingAlert.estimatedWalkMinutes} min)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isAcceptedOrInProgress) SafetyTeal else EmergencyRed
                            )
                        }
                    }
                }

                if (incomingAlert.victimLocationDescription.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Location: ${incomingAlert.victimLocationDescription}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (incomingAlert.evidenceSummary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Captured Evidence: ${incomingAlert.evidenceSummary}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PoliceBlue
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.LocalPolice,
                            contentDescription = null,
                            tint = PoliceBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "R3-CRITICAL Incident (${incomingAlert.policeStatus.name}). Responder identity & GPS logged for mutual safety.",
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    !isAcceptedOrInProgress -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onAccept(incomingAlert.alertId) },
                                colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .testTag("accept_hex_rescue_button")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Accept (+15 Pts)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { onDecline(incomingAlert.alertId) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = EmergencyRed),
                                modifier = Modifier
                                    .weight(0.9f)
                                    .testTag("decline_hex_rescue_button")
                            ) {
                                Text("Decline (-5)", fontSize = 12.sp)
                            }
                        }
                    }

                    incomingAlert.lifecycleState == HexRescueLifecycleState.RESPONDER_ACCEPTED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onMarkEnRoute(incomingAlert.alertId) },
                                colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("hex_en_route_button")
                            ) {
                                Text("Mark En Route", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            FilledTonalButton(
                                onClick = {
                                    onNavigateToVictim(
                                        incomingAlert.victimLatitude,
                                        incomingAlert.victimLongitude,
                                        "Victim: ${incomingAlert.victimName}"
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("navigate_to_victim_button")
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Navigate", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    incomingAlert.lifecycleState == HexRescueLifecycleState.EN_ROUTE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onMarkArrived(incomingAlert.alertId) },
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("hex_arrived_button")
                            ) {
                                Text("Arrived at Scene", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                            }
                            FilledTonalButton(
                                onClick = {
                                    onNavigateToVictim(
                                        incomingAlert.victimLatitude,
                                        incomingAlert.victimLongitude,
                                        "Victim: ${incomingAlert.victimName}"
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Route", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onCompleteRescue(incomingAlert.alertId) },
                                colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .testTag("hex_complete_button")
                            ) {
                                Text("Complete Rescue (+25)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            FilledTonalButton(
                                onClick = onDial112,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(0.9f)
                                    .testTag("dial_112_backup_button")
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmergencyRed)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Call 112", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Good Citizen Trust Score & Responder Card:
 * - Citizens start UNVERIFIED with 0 Trust Points.
 * - Peer guardian endorsement in Profile unlocks VERIFIED responder duty + real BLE & Firestore listening.
 */
@Composable
fun GoodCitizenShieldCard(
    profile: GoodCitizenProfile,
    bleStatus: BleRelayStatus = BleRelayStatus(),
    onToggleVolunteerDuty: (Boolean) -> Unit,
    onEnableBluetoothMesh: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isVerified = profile.verificationStatus == CitizenVerificationStatus.VERIFIED && profile.isIdVerified

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("good_citizen_shield_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = if (isVerified) SafetyTeal else WarningAmber,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hexagonal Responder Network",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (profile.verificationStatus) {
                        CitizenVerificationStatus.VERIFIED -> SafetyTeal.copy(alpha = 0.2f)
                        CitizenVerificationStatus.PENDING -> PoliceBlue.copy(alpha = 0.2f)
                        else -> WarningAmber.copy(alpha = 0.2f)
                    }
                ) {
                    Text(
                        text = profile.verificationStatus.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = when (profile.verificationStatus) {
                            CitizenVerificationStatus.VERIFIED -> SafetyTeal
                            CitizenVerificationStatus.PENDING -> PoliceBlue
                            else -> WarningAmber
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Ledger Trust Score",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${profile.trustScore}",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = if (profile.trustScore >= 70) SafetyTeal else if (profile.trustScore > 0) PoliceBlue else WarningAmber
                        )
                        Text(
                            text = "/100 pts",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Honor Badge",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = profile.badgeLevel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PoliceBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BluetoothConnected,
                    contentDescription = null,
                    tint = if (bleStatus.isScanning || bleStatus.isAdvertising) SafetyTeal else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = bleStatus.statusMessage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Mission History: ${profile.acceptedHelpsCount} rescues • ${profile.declinedHelpsCount} declined" +
                    if (!isVerified) " • Complete KYC + Peer Endorsement in Profile" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (onEnableBluetoothMesh != null && (!bleStatus.isBluetoothEnabled || bleStatus.permissionMissing)) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onEnableBluetoothMesh,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, WarningAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("enable_ble_mesh_button")
                ) {
                    Icon(
                        Icons.Default.BluetoothConnected,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (bleStatus.permissionMissing) {
                            "Grant Nearby Bluetooth Permission"
                        } else {
                            "Turn On Bluetooth for Nearby Mesh"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarningAmber
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            FilledTonalButton(
                onClick = { onToggleVolunteerDuty(!profile.isVolunteerActive) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("toggle_responder_duty_button")
            ) {
                Text(
                    text = when {
                        !isVerified -> "Responder Locked (Verify ID in Profile)"
                        profile.isVolunteerActive -> "Responder Duty ACTIVE (Scanning BLE + Hex)"
                        else -> "Activate Verified Responder Duty"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Police 112 Escalation Banner (Strictly gated to R3_CRITICAL):
 * Honestly tracks PoliceEscalationStatus without claiming unconfirmed transmission.
 */
@Composable
fun Police112DispatchBanner(
    dossier: PoliceDispatchDossier?,
    onOpen112Dialer: () -> Unit,
    onOpenDispatchPortal: (PoliceDispatchDossier) -> Unit,
    onLaunch112Sms: (PoliceDispatchDossier) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (dossier == null) return

    val statusTitle = when (dossier.policeStatus) {
        PoliceEscalationStatus.INTENT_LAUNCHED -> "112 ERSS Escalation Launched"
        PoliceEscalationStatus.ACKNOWLEDGED -> "112 ERSS Escalation Acknowledged"
        else -> "112 Escalation Ready (R3-CRITICAL)"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("police_112_dispatch_banner"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.2.dp, SolidColor(PoliceBlue))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.LocalPolice,
                        contentDescription = null,
                        tint = PoliceBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = PoliceBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = EmergencyRed.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = dossier.riskLevel.code,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = EmergencyRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Target: ${dossier.nearestPoliceStation} • Channel: ${dossier.dispatchChannel}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "In-App Dossier: ${dossier.webPortalUrl}",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = PoliceBlue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onOpen112Dialer,
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Dial 112", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }

                FilledTonalButton(
                    onClick = { onLaunch112Sms(dossier) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("112 SMS", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = { onOpenDispatchPortal(dossier) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1.1f)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Live Dossier", fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Direct In-App Trusted Guardian High-Alert Banner:
 * Displays live SOS GPS, Front+Rear camera captures, battery, and SHA-256 metadata
 * received directly by installed contacts via phone-number unique ID.
 */
@Composable
fun GuardianHighAlertCard(
    alert: CloudEmergencyDispatch?,
    onOpenMap: (Double, Double, String) -> Unit,
    onDialContact: (String) -> Unit,
    onDismiss: (() -> Unit)? = null,
    playingAudioIncidentId: String? = null,
    onPlayAudio: ((String) -> Unit)? = null,
    onStopAudio: (() -> Unit)? = null,
    onWatchVideo: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = alert != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        if (alert == null) return@AnimatedVisibility
        val isFakeCallGuard = alert.alertType == "FAKE_CALL_GUARD"
        val accentColor = if (isFakeCallGuard) SafetyTeal else EmergencyRed
        val isAudioPlaying = playingAudioIncidentId == alert.dispatchId
        val frontBitmap: ImageBitmap? = remember(alert.frontPhotoBase64) {
            decodeDispatchThumbnail(alert.frontPhotoBase64)
        }
        val rearBitmap: ImageBitmap? = remember(alert.rearPhotoBase64) {
            decodeDispatchThumbnail(alert.rearPhotoBase64)
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .testTag("guardian_high_alert_banner_${alert.dispatchId}"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.11f)),
            border = BorderStroke(1.8.dp, SolidColor(accentColor))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(accentColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isFakeCallGuard) Icons.Default.PhoneInTalk else Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (isFakeCallGuard) "FAKE CALL GUARD ALERT" else "TRUSTED CIRCLE HIGH ALERT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = accentColor
                                )
                                if (isFakeCallGuard) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = SafetyTeal
                                    ) {
                                        Text(
                                            text = "FAKE CALL GUARD",
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${alert.ownerName.ifBlank { "Trusted Contact" }} • ${alert.ownerPhone.ifBlank { "RakshaOne ID" }}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (onDismiss != null) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Minimize alert")
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
                                text = "DEVICE AUTO-LOCKED (ANTI-THEFT PROTECTED)",
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
                        text = "Evidence Seal: ${alert.evidenceSummary}",
                        fontSize = 10.sp,
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
                                        .height(96.dp)
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
                                        .height(96.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Text("Rear Cam", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (alert.latitude != 0.0 || alert.longitude != 0.0) {
                        Button(
                            onClick = { onOpenMap(alert.latitude, alert.longitude, "${alert.ownerName} SOS") },
                            colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Live GPS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (alert.ownerPhone.isNotBlank()) {
                        OutlinedButton(
                            onClick = { onDialContact(alert.ownerPhone) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Call", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (onPlayAudio != null || onWatchVideo != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onPlayAudio != null) {
                            FilledTonalButton(
                                onClick = {
                                    if (isAudioPlaying && onStopAudio != null) {
                                        onStopAudio()
                                    } else {
                                        onPlayAudio(alert.dispatchId)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("guardian_banner_play_audio_${alert.dispatchId}")
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
                        }
                        if (onWatchVideo != null && !isFakeCallGuard) {
                            FilledTonalButton(
                                onClick = { onWatchVideo(alert.dispatchId) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("guardian_banner_watch_video_${alert.dispatchId}")
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
    }
}

private fun decodeDispatchThumbnail(b64: String): ImageBitmap? {
    if (b64.isBlank()) return null
    return try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

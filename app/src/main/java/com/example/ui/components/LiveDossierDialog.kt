package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PoliceDispatchDossier
import com.example.risk.PoliceEscalationStatus
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton

@Composable
fun LiveDossierDialog(
    dossier: PoliceDispatchDossier,
    onDismiss: () -> Unit,
    onDial112: () -> Unit,
    onLaunch112Sms: (PoliceDispatchDossier) -> Unit,
    onOpenMaps: (Double, Double, String) -> Unit,
    onRefreshTelemetry: () -> Unit = {}
) {
    val timeStr = remember(dossier.timestamp) {
        SimpleDateFormat("dd MMM yyyy, HH:mm:ss z", Locale.getDefault()).format(Date(dossier.timestamp))
    }
    val expiresStr = remember(dossier.expiresAtMillis) {
        if (dossier.expiresAtMillis > 0L) {
            SimpleDateFormat("HH:mm:ss z", Locale.getDefault()).format(Date(dossier.expiresAtMillis))
        } else {
            "24h session"
        }
    }
    val isLaunched = dossier.policeStatus == PoliceEscalationStatus.INTENT_LAUNCHED ||
        dossier.policeStatus == PoliceEscalationStatus.ACKNOWLEDGED

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("live_dossier_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.LocalPolice, contentDescription = null, tint = PoliceBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live 112 Dossier",
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRefreshTelemetry,
                        modifier = Modifier.size(32.dp).testTag("refresh_dossier_telemetry_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Telemetry",
                            tint = SafetyTeal,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = EmergencyRed
                    ) {
                        Text(
                            text = dossier.riskLevel.code,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isLaunched) SafetyTeal.copy(alpha = 0.14f) else WarningAmber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, if (isLaunched) SafetyTeal else WarningAmber),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Escalation Status: ${dossier.policeStatus.name}",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            color = if (isLaunched) SafetyTeal else WarningAmber
                        )
                        Text(
                            text = "Channel: ${dossier.dispatchChannel}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Delivery State: ${dossier.deliveryState.name}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DossierRow("Incident ID", dossier.incidentId)
                DossierRow("Timestamp", timeStr)
                DossierRow("Target Control Room", dossier.nearestPoliceStation)
                DossierRow("Citizen", "${dossier.victimName} (${dossier.victimPhone.ifBlank { "No phone" }})")
                DossierRow("Medical Card", dossier.medicalSummary.ifBlank { "None recorded" })
                DossierRow(
                    "GPS Coordinates",
                    if (dossier.latitude != 0.0 || dossier.longitude != 0.0) {
                        "${String.format(Locale.US, "%.5f", dossier.latitude)}, ${String.format(Locale.US, "%.5f", dossier.longitude)}"
                    } else {
                        "GPS Fix Pending"
                    }
                )
                DossierRow("SHA-256 Digest", dossier.evidenceHashesSummary.ifBlank { "Pending capture" })

                if (!dossier.responderName.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SafetyTeal.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, SafetyTeal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dossier_responder_section")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "VERIFIED RESPONDER ASSIGNED (${dossier.responderStatus ?: "ACTIVE"})",
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                color = SafetyTeal
                            )
                            Text(
                                text = "${dossier.responderName} • ${dossier.responderPhone ?: "RakshaOne ID"}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            val distEta = buildString {
                                dossier.responderDistanceMeters?.let { append("${it.toInt()}m away") }
                                dossier.responderEtaMinutes?.let {
                                    if (isNotEmpty()) append(" • ")
                                    append("~${it}m walk ETA")
                                }
                            }
                            if (distEta.isNotBlank()) {
                                Text(text = distEta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (dossier.responderLat != null && dossier.responderLng != null &&
                                (dossier.responderLat != 0.0 || dossier.responderLng != 0.0)
                            ) {
                                Text(
                                    text = "Responder GPS: ${String.format(Locale.US, "%.5f, %.5f", dossier.responderLat, dossier.responderLng)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                OutlinedButton(
                                    onClick = {
                                        onOpenMaps(
                                            dossier.responderLat,
                                            dossier.responderLng,
                                            "Responder ${dossier.responderName}"
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Track Responder Live GPS", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = PoliceBlue, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "In-App Responder Dossier Link (expires $expiresStr)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = PoliceBlue
                    )
                }
                Text(
                    text = dossier.webPortalUrl,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onDial112,
                        colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dossier_dial_112_button")
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Dial 112", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onLaunch112Sms(dossier) },
                        colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dossier_sms_112_button")
                    ) {
                        Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("112 SMS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (dossier.latitude != 0.0 || dossier.longitude != 0.0) {
                    OutlinedButton(
                        onClick = { onOpenMaps(dossier.latitude, dossier.longitude, "Incident ${dossier.incidentId}") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Location on Google Maps", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                OutlinedButton(
                    onClick = onRefreshTelemetry,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("dossier_manual_refresh_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = SafetyTeal, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refresh Telemetry Now (Auto-polls every 10s)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun DossierRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

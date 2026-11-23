package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.util.PlateScanResult
import java.util.Locale

@Composable
fun PlateScannerDialog(
    initialPlate: String? = null,
    onScanBitmap: (Bitmap, (PlateScanResult) -> Unit) -> Unit,
    onScanUri: (Uri, (PlateScanResult) -> Unit) -> Unit,
    onSharePlateWithCircle: (plate: String, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var plateInput by remember(initialPlate) { mutableStateOf(initialPlate.orEmpty()) }
    var rideNote by remember { mutableStateOf("") }
    var ocrHint by remember { mutableStateOf("Scan or pick a photo of cab/auto number plate") }
    var isScanning by remember { mutableStateOf(false) }

    val cameraPreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            isScanning = true
            ocrHint = "Running offline ML Kit OCR..."
            onScanBitmap(bitmap) { res ->
                isScanning = false
                if (!res.detectedPlate.isNullOrBlank()) {
                    plateInput = res.detectedPlate
                }
                ocrHint = res.confidenceHint
            }
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraPreviewLauncher.launch(null)
        } else {
            ocrHint = "Camera permission denied — pick photo from gallery or type manually"
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isScanning = true
            ocrHint = "Extracting license plate offline..."
            onScanUri(uri) { res ->
                isScanning = false
                if (!res.detectedPlate.isNullOrBlank()) {
                    plateInput = res.detectedPlate
                }
                ocrHint = res.confidenceHint
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = PoliceBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cab / Auto Plate Scanner", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Uses offline ML Kit Text Recognition to read vehicle registration plates before boarding solo rides.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                cameraPreviewLauncher.launch(null)
                            } else {
                                cameraPermLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("plate_camera_scan_button")
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Camera", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("plate_gallery_pick_button")
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pick Photo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SafetyTeal)
                        Text(ocrHint, fontSize = 11.sp, color = SafetyTeal)
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PoliceBlue.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, PoliceBlue.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = ocrHint,
                            fontSize = 11.sp,
                            color = PoliceBlue,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = plateInput,
                    onValueChange = { plateInput = it.uppercase(Locale.US) },
                    label = { Text("Vehicle Number Plate *") },
                    placeholder = { Text("e.g. DL 8C AF 5031") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("plate_number_input")
                )

                OutlinedTextField(
                    value = rideNote,
                    onValueChange = { rideNote = it },
                    label = { Text("Ride Note (Optional)") },
                    placeholder = { Text("e.g. Airport Uber Auto to Home") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (plateInput.isNotBlank()) {
                        onSharePlateWithCircle(plateInput.trim(), rideNote.trim())
                        onDismiss()
                    }
                },
                enabled = plateInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                modifier = Modifier.testTag("share_plate_circle_button")
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Share Cab Plate with Circle", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

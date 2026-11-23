package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.SafetyTeal
import com.example.ui.theme.WarningAmber

@Composable
fun ResponderSelfieVerifyDialog(
    victimName: String,
    defaultPhone: String,
    onVerifyBitmap: (Bitmap, (Boolean, String?, String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onVerifiedConfirm: (phone: String, selfieB64: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
    }

    var responderPhone by remember(defaultPhone) { mutableStateOf(defaultPhone) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var verifiedSelfieB64 by remember { mutableStateOf<String?>(null) }
    var verificationStatus by remember { mutableStateOf("Tap Capture Live Selfie to unlock SOS pickup") }
    var isVerifying by remember { mutableStateOf(false) }
    var isPassed by remember { mutableStateOf(false) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = SafetyTeal)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Anti-Predator Selfie Lock", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Before responding to $victimName, RakshaOne captures your live front selfie and shares your Name, Phone, Selfie & Live GPS with the victim's Trusted Circle.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = responderPhone,
                    onValueChange = { responderPhone = it },
                    label = { Text("Your Active Phone Number *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (!hasCameraPerm) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CameraFront, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { cameraPermLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("Grant Camera Permission")
                            }
                        }
                    } else if (capturedBitmap != null) {
                        Image(
                            bitmap = capturedBitmap!!.asImageBitmap(),
                            contentDescription = "Captured Responder Selfie",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                    cameraProviderFuture.addListener({
                                        try {
                                            val provider = cameraProviderFuture.get()
                                            val preview = Preview.Builder().build().also {
                                                it.surfaceProvider = surfaceProvider
                                            }
                                            val capture = ImageCapture.Builder()
                                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                                .build()
                                            imageCaptureRef = capture
                                            provider.unbindAll()
                                            provider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                                preview,
                                                capture
                                            )
                                        } catch (e: Exception) {
                                            verificationStatus = "Camera unavailable: ${e.message}"
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (isVerifying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = SafetyTeal)
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isPassed -> SafetyTeal.copy(alpha = 0.16f)
                        capturedBitmap != null -> EmergencyRed.copy(alpha = 0.14f)
                        else -> PoliceBlue.copy(alpha = 0.12f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isPassed) SafetyTeal else if (capturedBitmap != null) EmergencyRed else PoliceBlue
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = verificationStatus,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPassed) SafetyTeal else if (capturedBitmap != null) EmergencyRed else PoliceBlue,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                if (hasCameraPerm) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val capture = imageCaptureRef ?: return@Button
                                isVerifying = true
                                verificationStatus = "Capturing & checking human face liveness..."
                                capture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val bmp = imageProxyToRotatedBitmap(image)
                                            image.close()
                                            if (bmp != null) {
                                                capturedBitmap = bmp
                                                onVerifyBitmap(bmp) { ok, b64, reason ->
                                                    isVerifying = false
                                                    isPassed = ok && !b64.isNullOrBlank()
                                                    verifiedSelfieB64 = b64
                                                    verificationStatus = reason
                                                }
                                            } else {
                                                isVerifying = false
                                                verificationStatus = "Could not capture frame — tap Retake"
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            isVerifying = false
                                            verificationStatus = "Capture failed: ${exception.message}"
                                        }
                                    }
                                )
                            },
                            enabled = !isVerifying && capturedBitmap == null,
                            colors = ButtonDefaults.buttonColors(containerColor = PoliceBlue),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("capture_responder_selfie_button")
                        ) {
                            Icon(Icons.Default.CameraFront, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Capture Selfie", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        if (capturedBitmap != null) {
                            OutlinedButton(
                                onClick = {
                                    capturedBitmap = null
                                    verifiedSelfieB64 = null
                                    isPassed = false
                                    verificationStatus = "Camera ready — center your face and capture"
                                },
                                modifier = Modifier.weight(0.8f)
                            ) {
                                Text("Retake", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val b64 = verifiedSelfieB64
                    if (isPassed && !b64.isNullOrBlank() && responderPhone.isNotBlank()) {
                        onVerifiedConfirm(responderPhone.trim(), b64)
                    }
                },
                enabled = isPassed && !verifiedSelfieB64.isNullOrBlank() && responderPhone.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SafetyTeal),
                modifier = Modifier.testTag("confirm_responder_selfie_button")
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Respond to SOS", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun imageProxyToRotatedBitmap(image: ImageProxy): Bitmap? {
    return try {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } else {
            raw
        }
    } catch (_: Exception) {
        null
    }
}

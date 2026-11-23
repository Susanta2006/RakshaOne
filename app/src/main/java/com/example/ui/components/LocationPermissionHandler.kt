package com.example.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.EmergencyRed
import com.example.ui.theme.PoliceBlue
import com.example.ui.theme.WarningAmber

fun hasFineLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Stable
class FineLocationPermissionState(
    val isFineLocationGranted: Boolean,
    val shouldShowRationale: Boolean,
    val isPermanentlyDenied: Boolean,
    val requestFineLocation: () -> Unit,
    val openAppSettings: () -> Unit
)

/**
 * Standard Android ActivityResult-backed Compose permission handler ensuring
 * ACCESS_FINE_LOCATION is requested and verified before FusedLocationProviderClient starts.
 */
@Composable
fun rememberFineLocationPermissionState(
    onFineLocationGranted: () -> Unit
): FineLocationPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }

    var isGranted by remember { mutableStateOf(hasFineLocationPermission(context)) }
    var hasAttemptedRequest by rememberSaveable { mutableStateOf(false) }
    var shouldShowRationale by remember {
        mutableStateOf(
            activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
            } ?: false
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAttemptedRequest = true
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            hasFineLocationPermission(context)
        isGranted = fineGranted
        shouldShowRationale = !fineGranted && (activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
        } ?: false)

        if (fineGranted) {
            onFineLocationGranted()
        }
    }

    // Re-check on resume in case the user granted Precise Location in System Settings
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowGranted = hasFineLocationPermission(context)
                val transitionedToGranted = !isGranted && nowGranted
                isGranted = nowGranted
                shouldShowRationale = !nowGranted && (activity?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
                } ?: false)
                if (transitionedToGranted) {
                    onFineLocationGranted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(isGranted) {
        if (isGranted) {
            onFineLocationGranted()
        }
    }

    val requestFn = remember(launcher) {
        {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val openSettingsFn = remember(context) {
        {
            try {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    val isPermanentlyDenied = !isGranted && hasAttemptedRequest && !shouldShowRationale

    return FineLocationPermissionState(
        isFineLocationGranted = isGranted,
        shouldShowRationale = shouldShowRationale,
        isPermanentlyDenied = isPermanentlyDenied,
        requestFineLocation = requestFn,
        openAppSettings = openSettingsFn
    )
}

@Composable
fun FineLocationPermissionBanner(
    permissionState: FineLocationPermissionState,
    modifier: Modifier = Modifier
) {
    if (permissionState.isFineLocationGranted) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("fine_location_permission_banner"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = WarningAmber.copy(alpha = 0.16f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = EmergencyRed,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Precise GPS Permission Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Black,
                    color = EmergencyRed
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (permissionState.isPermanentlyDenied) {
                    "Fine location permission was denied. Enable 'Precise' location in App Settings so RakshaOne can attach exact coordinates to SOS alerts."
                } else {
                    "RakshaOne requires Fine Location (ACCESS_FINE_LOCATION) before starting FusedLocationProviderClient to broadcast accurate SOS coordinates."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (permissionState.isPermanentlyDenied) {
                            permissionState.openAppSettings()
                        } else {
                            permissionState.requestFineLocation()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PoliceBlue,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("grant_fine_location_button")
                ) {
                    Icon(
                        imageVector = if (permissionState.isPermanentlyDenied) Icons.Default.Settings else Icons.Default.GpsFixed,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (permissionState.isPermanentlyDenied) "Open App Settings" else "Allow Precise GPS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                if (permissionState.shouldShowRationale) {
                    OutlinedButton(
                        onClick = permissionState.openAppSettings,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Settings", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

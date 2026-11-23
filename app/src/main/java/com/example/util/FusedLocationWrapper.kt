package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed class LocationState {
    data object Acquiring : LocationState()
    data class Available(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val speedMps: Float,
        val timestampMillis: Long,
        val provider: String
    ) : LocationState()

    data object PermissionRequired : LocationState()
    data object GpsDisabled : LocationState()
    data class Error(val message: String) : LocationState()
}

/**
 * Production-grade wrapper around Google Play Services FusedLocationProviderClient.
 * Designed to live inside a ViewModel to stream real-time GPS coordinates and
 * capture immediate high-accuracy fixes for SOS triggers without fabricated coordinates.
 */
class FusedLocationWrapper(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
) {
    private val _locationState = MutableStateFlow<LocationState>(
        if (hasLocationPermission()) LocationState.Acquiring else LocationState.PermissionRequired
    )
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private var locationCallback: LocationCallback? = null
    private var isTrackingActive = false
    private var isHighAccuracyMode = false

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(): Boolean {
        return hasFineLocationPermission()
    }

    fun isLocationProviderEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Switches between battery-saving balanced location checks (when idle) and
     * high-accuracy 3s live GPS updates (when SOS active, Trip active, or Map visible).
     */
    fun setHighAccuracyMode(highAccuracy: Boolean) {
        if (isHighAccuracyMode == highAccuracy && isTrackingActive) return
        isHighAccuracyMode = highAccuracy
        if (isTrackingActive) {
            stopRealtimeUpdates()
            startRealtimeUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    fun startRealtimeUpdates(
        intervalMs: Long = if (isHighAccuracyMode) 3000L else 18000L,
        minUpdateIntervalMs: Long = if (isHighAccuracyMode) 1500L else 10000L,
        minDistanceMeters: Float = if (isHighAccuracyMode) 2f else 25f
    ) {
        if (!hasLocationPermission()) {
            _locationState.value = LocationState.PermissionRequired
            return
        }

        if (!isLocationProviderEnabled()) {
            _locationState.value = LocationState.GpsDisabled
            return
        }

        if (isTrackingActive) return

        if (_currentLocation.value == null) {
            _locationState.value = LocationState.Acquiring
        }

        val priority = if (isHighAccuracyMode) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(minUpdateIntervalMs)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                publishLocation(loc)
            }
        }

        locationCallback = callback
        try {
            // Immediately seed with genuine cached hardware fix (if any) while live stream warms up
            if (_currentLocation.value == null) {
                fusedLocationClient.lastLocation.addOnSuccessListener { cached ->
                    if (cached != null && _currentLocation.value == null) {
                        publishLocation(cached)
                    }
                }
            }
            fusedLocationClient.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper()
            )
            isTrackingActive = true
        } catch (se: SecurityException) {
            isTrackingActive = false
            _locationState.value = LocationState.PermissionRequired
        } catch (e: Exception) {
            isTrackingActive = false
            _locationState.value = LocationState.Error(e.localizedMessage ?: "Location tracking failed")
        }
    }

    fun stopRealtimeUpdates() {
        locationCallback?.let { callback ->
            try {
                fusedLocationClient.removeLocationUpdates(callback)
            } catch (_: Exception) {
            }
        }
        locationCallback = null
        isTrackingActive = false
    }

    /**
     * Fetches an immediate high-accuracy real-time location for critical operations such as SOS activation.
     * Falls back to lastLocation only if hardware returns null within timeout, and never fabricates coordinates.
     */
    @SuppressLint("MissingPermission")
    suspend fun getImmediateLocation(timeoutMs: Long = 5000L): Location? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            _locationState.value = LocationState.PermissionRequired
            return@withContext null
        }

        if (!isLocationProviderEnabled()) {
            _locationState.value = LocationState.GpsDisabled
            return@withContext _currentLocation.value
        }

        // Start continuous updates if not already active so future reads stay warm
        if (!isTrackingActive) {
            withContext(Dispatchers.Main) {
                startRealtimeUpdates()
            }
        }

        val cts = CancellationTokenSource()
        val freshLocation: Location? = withTimeoutOrNull(timeoutMs) {
            try {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await()
            } catch (_: Exception) {
                null
            }
        }
        if (freshLocation == null) {
            cts.cancel()
        }

        val resolved = freshLocation ?: try {
            fusedLocationClient.lastLocation.await()
        } catch (_: Exception) {
            null
        } ?: _currentLocation.value

        if (resolved != null) {
            publishLocation(resolved)
        } else if (!isLocationProviderEnabled()) {
            _locationState.value = LocationState.GpsDisabled
        } else {
            _locationState.value = LocationState.Acquiring
        }

        resolved
    }

    private fun publishLocation(location: Location) {
        _currentLocation.value = location
        _locationState.value = LocationState.Available(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            speedMps = location.speed,
            timestampMillis = location.time,
            provider = location.provider ?: "fused"
        )
    }

    fun cleanup() {
        stopRealtimeUpdates()
    }
}

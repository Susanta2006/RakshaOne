package com.example.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sin
import kotlin.math.sqrt

data class DeviceSensorSnapshot(
    val capturedAtMillis: Long,
    val capturedAtIso: String,
    val capturedAtLocal: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val speedKmh: Float?,
    val hasAccelerometer: Boolean,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val dynamicJerkG: Float,
    val hasLightSensor: Boolean,
    val lightLux: Float?,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: String
) {
    fun toFormattedSummary(): String {
        val gpsText = if (latitude != null && longitude != null) {
            val acc = accuracyMeters?.toInt() ?: 0
            val spd = speedKmh ?: 0f
            "${String.format(Locale.US, "%.5f", latitude)}, ${String.format(Locale.US, "%.5f", longitude)} (±${acc}m, ${String.format(Locale.US, "%.1f", spd)} km/h)"
        } else {
            "GPS signal unavailable"
        }
        val accelText = if (hasAccelerometer) {
            "X=${String.format(Locale.US, "%.2f", accelX)}, Y=${String.format(Locale.US, "%.2f", accelY)}, Z=${String.format(Locale.US, "%.2f", accelZ)} m/s² (Jerk ${String.format(Locale.US, "%.2f", dynamicJerkG)}g)"
        } else {
            "N/A (No accelerometer hardware)"
        }
        val lightText = if (hasLightSensor && lightLux != null) {
            "${String.format(Locale.US, "%.1f", lightLux)} lux"
        } else if (hasLightSensor) {
            "Reading..."
        } else {
            "N/A (No light sensor hardware)"
        }
        val chargeTag = if (isCharging) "Charging" else "Discharging"
        return buildString {
            appendLine("Timestamp (ISO-8601): $capturedAtIso ($capturedAtLocal)")
            appendLine("GPS Telemetry: $gpsText")
            appendLine("Accelerometer: $accelText")
            appendLine("Ambient Light: $lightText")
            appendLine("Power: $batteryPercent% ($chargeTag) • Network: $networkType")
        }.trimEnd()
    }

    fun toJsonPayload(): String {
        val esc: (String) -> String = { it.replace("\"", "\\\"") }
        return buildString {
            append("{")
            append("\"capturedAtMillis\":$capturedAtMillis,")
            append("\"capturedAtIso\":\"${esc(capturedAtIso)}\",")
            append("\"latitude\":${latitude ?: "null"},")
            append("\"longitude\":${longitude ?: "null"},")
            append("\"accuracyMeters\":${accuracyMeters ?: "null"},")
            append("\"speedKmh\":${speedKmh ?: "null"},")
            append("\"hasAccelerometer\":$hasAccelerometer,")
            append("\"accelX\":$accelX,\"accelY\":$accelY,\"accelZ\":$accelZ,")
            append("\"dynamicJerkG\":$dynamicJerkG,")
            append("\"hasLightSensor\":$hasLightSensor,")
            append("\"lightLux\":${lightLux ?: "null"},")
            append("\"batteryPercent\":$batteryPercent,")
            append("\"isCharging\":$isCharging,")
            append("\"networkType\":\"${esc(networkType)}\"")
            append("}")
        }
    }
}

class SafetySensorsManager(private val context: Context) : SensorEventListener {

    private val tag = "SafetySensorsManager"

    // Strobe Flashlight state
    private val _isStrobeActive = MutableStateFlow(false)
    val isStrobeActive: StateFlow<Boolean> = _isStrobeActive.asStateFlow()
    private var strobeJob: Job? = null

    val hasHardwareFlash: Boolean by lazy {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return@lazy false
        try {
            cm.cameraIdList.any { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    // Siren Audio state
    private val _isSirenActive = MutableStateFlow(false)
    val isSirenActive: StateFlow<Boolean> = _isSirenActive.asStateFlow()
    private var sirenJob: Job? = null
    private var audioTrack: AudioTrack? = null

    // Sharp Repeated Shake & Device Telemetry Snapshot Sensors
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lightSensor: Sensor? = null
    private var lastShakeTimestamp = 0L
    private var onShakeDetectedCallback: (() -> Unit)? = null

    private var latestAccelX = 0f
    private var latestAccelY = 0f
    private var latestAccelZ = 0f
    private var latestLightLux: Float? = null
    private var isSnapshotMonitoringActive = false

    private val _isShakeDetectionActive = MutableStateFlow(false)
    val isShakeDetectionActive: StateFlow<Boolean> = _isShakeDetectionActive.asStateFlow()

    private val _currentShakeCount = MutableStateFlow(0)
    val currentShakeCount: StateFlow<Int> = _currentShakeCount.asStateFlow()

    private val _lastShakeGForce = MutableStateFlow(0f)
    val lastShakeGForce: StateFlow<Float> = _lastShakeGForce.asStateFlow()

    private val _travelBumpCount = MutableStateFlow(0)
    val travelBumpCount: StateFlow<Int> = _travelBumpCount.asStateFlow()

    // Passive Anti-Snatch telemetry state (never auto-triggers SOS or 112 escalation)
    private var onSnatchDetectedCallback: (() -> Unit)? = null
    private val _isSnatchDetectionActive = MutableStateFlow(false)
    val isSnatchDetectionActive: StateFlow<Boolean> = _isSnatchDetectionActive.asStateFlow()

    private val _snatchAlertCount = MutableStateFlow(0)
    val snatchAlertCount: StateFlow<Int> = _snatchAlertCount.asStateFlow()

    private var snatchJerkPeakTimestamp = 0L
    private var postSnatchMotionReadings = 0
    private var lastSnatchTriggerTimestamp = 0L

    // Gravity baseline filter for high-pass isolation of sharp motions
    private val gravity = floatArrayOf(0f, 0f, 0f)
    private var hasGravityBaseline = false

    val hasAccelerometer: Boolean
        get() = accelerometer != null

    val hasLightSensor: Boolean
        get() = lightSensor != null

    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        try {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize device sensors", e)
        }
    }

    fun startSnapshotMonitoring() {
        isSnapshotMonitoringActive = true
        try {
            if (accelerometer != null && !_isShakeDetectionActive.value && !_isSnatchDetectionActive.value) {
                sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            }
            if (lightSensor != null) {
                sensorManager?.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not start snapshot sensor listeners: ${e.message}")
        }
    }

    fun stopSnapshotMonitoring() {
        isSnapshotMonitoringActive = false
        try {
            if (lightSensor != null) {
                sensorManager?.unregisterListener(this, lightSensor)
            }
            if (!_isShakeDetectionActive.value && !_isSnatchDetectionActive.value && accelerometer != null) {
                sensorManager?.unregisterListener(this, accelerometer)
            }
        } catch (_: Exception) {}
    }

    fun captureDeviceSensorSnapshot(location: Location?): DeviceSensorSnapshot {
        val now = System.currentTimeMillis()
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val localFormatter = SimpleDateFormat("dd MMM yyyy, HH:mm:ss z", Locale.getDefault())
        val (batteryPct, isCharging) = readBatteryStatus()
        val networkType = readNetworkTransport()

        return DeviceSensorSnapshot(
            capturedAtMillis = now,
            capturedAtIso = isoFormatter.format(Date(now)),
            capturedAtLocal = localFormatter.format(Date(now)),
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyMeters = if (location != null && location.hasAccuracy()) location.accuracy else null,
            speedKmh = if (location != null && location.hasSpeed()) location.speed * 3.6f else null,
            hasAccelerometer = hasAccelerometer,
            accelX = latestAccelX,
            accelY = latestAccelY,
            accelZ = latestAccelZ,
            dynamicJerkG = _lastShakeGForce.value,
            hasLightSensor = hasLightSensor,
            lightLux = latestLightLux,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            networkType = networkType
        )
    }

    private fun readBatteryStatus(): Pair<Int, Boolean> {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val propPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val fallbackPct = if (propPct in 0..100) {
                propPct
            } else {
                val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else 100
            }
            Pair(fallbackPct.coerceIn(0, 100), isCharging)
        } catch (_: Exception) {
            Pair(100, false)
        }
    }

    private fun readNetworkTransport(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Unavailable"
            val network = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(network) ?: return "Offline"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "Connected"
                else -> "Offline"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    // Toggle Siren
    fun toggleSiren() {
        if (_isSirenActive.value) {
            stopSiren()
        } else {
            startSiren()
        }
    }

    fun startSiren() {
        if (_isSirenActive.value) return
        _isSirenActive.value = true
        sirenJob = scope.launch {
            val sampleRate = 44100
            val numSamplesPerChunk = 44100 / 4 // 0.25 seconds chunk
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, numSamplesPerChunk * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()

            val toneLow = 720.0
            val toneHigh = 980.0
            var highTone = false

            val samples = ShortArray(numSamplesPerChunk)

            while (isActive && _isSirenActive.value) {
                val currentFreq = if (highTone) toneHigh else toneLow
                highTone = !highTone

                for (i in samples.indices) {
                    val angle = 2.0 * Math.PI * i / (sampleRate / currentFreq)
                    samples[i] = (sin(angle) * Short.MAX_VALUE * 0.85).toInt().toShort()
                }

                track.write(samples, 0, samples.size)
            }

            try {
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
    }

    fun stopSiren() {
        _isSirenActive.value = false
        sirenJob?.cancel()
        sirenJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (_: Exception) {}
    }

    // Toggle Strobe Flashlight
    fun toggleStrobe() {
        if (_isStrobeActive.value) {
            stopStrobe()
        } else {
            startStrobe()
        }
    }

    fun startStrobe() {
        if (_isStrobeActive.value) return
        _isStrobeActive.value = true

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        var cameraId: String? = null

        try {
            for (id in cameraManager.cameraIdList) {
                val hasFlash = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (hasFlash) {
                    cameraId = id
                    break
                }
            }
        } catch (_: Exception) {}

        if (cameraId == null) return

        strobeJob = scope.launch {
            var torchOn = false
            try {
                while (isActive && _isStrobeActive.value) {
                    torchOn = !torchOn
                    cameraManager.setTorchMode(cameraId, torchOn)
                    delay(120)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    cameraManager.setTorchMode(cameraId, false)
                } catch (_: Exception) {}
            }
        }
    }

    fun stopStrobe() {
        _isStrobeActive.value = false
        strobeJob?.cancel()
        strobeJob = null
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            for (id in cameraManager.cameraIdList) {
                val hasFlash = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (hasFlash) {
                    cameraManager.setTorchMode(id, false)
                    break
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Ensures accelerometer is registered at the required sampling rate whenever Shake-to-SOS,
     * Anti-Snatch Shield, or telemetry snapshot monitoring is enabled.
     */
    private fun syncAccelerometerRegistration(): Boolean {
        if (accelerometer == null) return false
        val needActiveMotion = _isSnatchDetectionActive.value
        val needAny = needActiveMotion || _isShakeDetectionActive.value || isSnapshotMonitoringActive
        try {
            sensorManager?.unregisterListener(this, accelerometer)
            if (!needAny) return false
            // Hardware FIFO batching keeps CPU asleep and prevents thermal drain/overheating
            val samplingPeriodUs = if (needActiveMotion) SensorManager.SENSOR_DELAY_UI else SensorManager.SENSOR_DELAY_NORMAL
            val batchLatencyUs = if (needActiveMotion) 400_000 else 1_000_000
            return sensorManager?.registerListener(
                this,
                accelerometer,
                samplingPeriodUs,
                batchLatencyUs
            ) ?: false
        } catch (e: Exception) {
            Log.w(tag, "Could not sync accelerometer listener: ${e.message}")
            return false
        }
    }

    /**
     * Registers a device sensor listener that detects sharp, repeated shaking motions
     * to trigger the emergency SOS functionality automatically.
     */
    fun startShakeDetection(onShake: () -> Unit): Boolean {
        onShakeDetectedCallback = onShake
        hasGravityBaseline = false
        _currentShakeCount.value = 0
        _lastShakeGForce.value = 0f
        if (accelerometer == null) {
            _isShakeDetectionActive.value = false
            return false
        }
        _isShakeDetectionActive.value = true
        val registered = syncAccelerometerRegistration()
        _isShakeDetectionActive.value = registered
        return registered
    }

    fun stopShakeDetection() {
        onShakeDetectedCallback = null
        _isShakeDetectionActive.value = false
        _currentShakeCount.value = 0
        syncAccelerometerRegistration()
    }

    /**
     * Arms Anti-Snatch motion detection: detects a violent grab/yank spike (>= 3.4g)
     * followed immediately by sustained running/escape motion (>= 1.85g).
     */
    fun startSnatchDetection(onSnatchDetected: () -> Unit): Boolean {
        onSnatchDetectedCallback = onSnatchDetected
        snatchJerkPeakTimestamp = 0L
        postSnatchMotionReadings = 0
        if (accelerometer == null) {
            _isSnatchDetectionActive.value = false
            return false
        }
        _isSnatchDetectionActive.value = true
        val registered = syncAccelerometerRegistration()
        _isSnatchDetectionActive.value = registered
        return registered
    }

    fun stopSnatchDetection() {
        onSnatchDetectedCallback = null
        _isSnatchDetectionActive.value = false
        snatchJerkPeakTimestamp = 0L
        postSnatchMotionReadings = 0
        syncAccelerometerRegistration()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor?.type == Sensor.TYPE_LIGHT) {
            latestLightLux = event.values.firstOrNull()
            return
        }
        if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        latestAccelX = event.values[0]
        latestAccelY = event.values[1]
        latestAccelZ = event.values[2]

        // High-pass filter to isolate sudden jerks/shakes from gravity
        val alpha = 0.8f
        if (!hasGravityBaseline) {
            gravity[0] = event.values[0]
            gravity[1] = event.values[1]
            gravity[2] = event.values[2]
            hasGravityBaseline = true
        } else {
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
        }

        val linearX = event.values[0] - gravity[0]
        val linearY = event.values[1] - gravity[1]
        val linearZ = event.values[2] - gravity[2]

        val dynamicAcceleration = sqrt((linearX * linearX + linearY * linearY + linearZ * linearZ).toDouble()).toFloat()
        val gForce = dynamicAcceleration / SensorManager.GRAVITY_EARTH
        _lastShakeGForce.value = gForce

        if (!_isShakeDetectionActive.value && !_isSnatchDetectionActive.value) return

        val now = System.currentTimeMillis()

        // 1. Travel vibration & bump telemetry monitoring (strictly suppressed from triggering SOS)
        if (_isShakeDetectionActive.value) {
            if (gForce >= 2.0f && now - lastShakeTimestamp > 600L) {
                lastShakeTimestamp = now
                _travelBumpCount.value = _travelBumpCount.value + 1
            }
        }

        // 2. Sudden extreme jerk telemetry observation (logged only; explicit SOS button required for emergencies)
        if (_isSnatchDetectionActive.value) {
            val cooldownElapsed = now - lastSnatchTriggerTimestamp > 8000L
            if (cooldownElapsed) {
                if (gForce >= 3.4f && snatchJerkPeakTimestamp == 0L) {
                    snatchJerkPeakTimestamp = now
                    postSnatchMotionReadings = 0
                } else if (snatchJerkPeakTimestamp > 0L) {
                    val sincePeak = now - snatchJerkPeakTimestamp
                    if (sincePeak in 150L..1200L && gForce >= 1.85f) {
                        postSnatchMotionReadings++
                        if (postSnatchMotionReadings >= 2) {
                            snatchJerkPeakTimestamp = 0L
                            postSnatchMotionReadings = 0
                            lastSnatchTriggerTimestamp = now
                            _snatchAlertCount.value = _snatchAlertCount.value + 1
                            // Telemetry notification only — never auto-triggers SOS
                            onSnatchDetectedCallback?.invoke()
                        }
                    } else if (sincePeak > 1200L) {
                        snatchJerkPeakTimestamp = 0L
                        postSnatchMotionReadings = 0
                    }
                }
            }
        }
    }

    private fun triggerShakeVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 150, 100, 200, 100, 250),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(500)
            }
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun cleanup() {
        stopSiren()
        stopStrobe()
        stopShakeDetection()
        stopSnatchDetection()
        stopSnapshotMonitoring()
    }
}

package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

@Keep
data class BleRelayStatus(
    val isBluetoothEnabled: Boolean = false,
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val gattEvidenceManifestReady: Boolean = false,
    val permissionMissing: Boolean = false,
    val statusMessage: String = "BLE standby"
)

@Keep
data class BleDiscoveredEmergency(
    val incidentId: String,
    val victimLatitude: Double,
    val victimLongitude: Double,
    val rssiDbm: Int,
    val estimatedDistanceMeters: Float,
    val sectorHint: String,
    val evidenceSummary: String,
    val discoveredAtMillis: Long = System.currentTimeMillis()
)

/**
 * Real Android Bluetooth Low Energy (BLE) Emergency Relay:
 * - Victim side: Starts BLE Advertiser with compact R3 emergency packet + GATT Server hosting JSON evidence manifest.
 * - Nearby Verified Citizen side: Runs BLE Scanner filtering for RakshaOne Service UUID and parses real peer beacons.
 */
class BleEmergencyRelay(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FD11-0000-1000-8000-00805F9B34FB")
        val EVIDENCE_CHAR_UUID: UUID = UUID.fromString("A1B2C3D4-7261-6B73-6861-313132000002")
        private const val TAG = "BleEmergencyRelay"

        fun requiredMeshPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private val _relayStatus = MutableStateFlow(BleRelayStatus())
    val relayStatus: StateFlow<BleRelayStatus> = _relayStatus.asStateFlow()

    private val _discoveredEmergencies = MutableStateFlow<List<BleDiscoveredEmergency>>(emptyList())
    val discoveredEmergencies: StateFlow<List<BleDiscoveredEmergency>> = _discoveredEmergencies.asStateFlow()

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var currentEvidencePayloadBytes: ByteArray = ByteArray(0)

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    init {
        refreshBluetoothState(autoStartScanIfEligible = false)
    }

    private fun ensureAdapter(): BluetoothAdapter? {
        if (bluetoothManager == null) {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        }
        if (bluetoothAdapter == null) {
            bluetoothAdapter = bluetoothManager?.adapter
        }
        return bluetoothAdapter
    }

    fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasBleAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasAllMeshPermissions(): Boolean = hasBleScanPermission() && hasBleAdvertisePermission()

    fun isBluetoothEnabled(): Boolean {
        val adapter = ensureAdapter()
        return try {
            adapter != null && adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
    }

    fun refreshBluetoothState(autoStartScanIfEligible: Boolean = false) {
        val adapter = ensureAdapter()
        val hasPerms = hasAllMeshPermissions()
        val btOn = try {
            adapter != null && adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }

        if (!hasPerms) {
            _relayStatus.value = _relayStatus.value.copy(
                isBluetoothEnabled = btOn,
                isAdvertising = false,
                isScanning = false,
                permissionMissing = true,
                statusMessage = "Grant Nearby Devices permission to enable Bluetooth Mesh"
            )
            return
        }

        if (adapter == null || !btOn) {
            _relayStatus.value = _relayStatus.value.copy(
                isBluetoothEnabled = false,
                isAdvertising = false,
                isScanning = false,
                permissionMissing = false,
                statusMessage = "Bluetooth is OFF — turn on Bluetooth to join Nearby Mesh"
            )
            return
        }

        val current = _relayStatus.value
        _relayStatus.value = current.copy(
            isBluetoothEnabled = true,
            permissionMissing = false,
            statusMessage = when {
                current.isAdvertising -> current.statusMessage
                current.isScanning -> current.statusMessage
                else -> "Bluetooth Mesh ON & ready for offline nearby relay"
            }
        )
        if (autoStartScanIfEligible && !current.isScanning) {
            startScanningForNearbySos()
        }
    }

    fun broadcastEmergencyBeacon(
        incidentId: String,
        lat: Double,
        lng: Double,
        evidenceSummary: String = "",
        sectorHint: String = "N-NE"
    ): Boolean {
        val adapter = ensureAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _relayStatus.value = _relayStatus.value.copy(
                isBluetoothEnabled = false,
                isAdvertising = false,
                statusMessage = "Bluetooth disabled; enable Bluetooth for offline nearby citizen relay"
            )
            return false
        }

        if (!hasBleAdvertisePermission()) {
            _relayStatus.value = _relayStatus.value.copy(
                isBluetoothEnabled = true,
                isAdvertising = false,
                permissionMissing = true,
                statusMessage = "Grant Nearby Devices permission to broadcast BLE SOS"
            )
            return false
        }

        return try {
            startGattEvidenceServer(incidentId, lat, lng, evidenceSummary, sectorHint)
            startLeAdvertising(incidentId, lat, lng)
        } catch (se: SecurityException) {
            Log.w(TAG, "Missing BLE permission: ${se.message}")
            _relayStatus.value = _relayStatus.value.copy(
                permissionMissing = true,
                statusMessage = "Nearby Devices permission required for BLE broadcast"
            )
            false
        } catch (e: Exception) {
            Log.w(TAG, "BLE broadcast error: ${e.message}")
            _relayStatus.value = _relayStatus.value.copy(
                isAdvertising = false,
                statusMessage = "BLE error: ${e.message}"
            )
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattEvidenceServer(
        incidentId: String,
        lat: Double,
        lng: Double,
        evidenceSummary: String,
        sectorHint: String
    ) {
        val manifest = JSONObject().apply {
            put("incidentId", incidentId)
            put("riskLevel", "R3_CRITICAL")
            put("lat", lat)
            put("lng", lng)
            put("sector", sectorHint)
            put("evidence", evidenceSummary.take(180))
            put("ts", System.currentTimeMillis())
        }.toString()
        currentEvidencePayloadBytes = manifest.toByteArray(StandardCharsets.UTF_8)

        try {
            gattServer?.close()
            gattServer = bluetoothManager?.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic?
                ) {
                    if (characteristic?.uuid == EVIDENCE_CHAR_UUID) {
                        val slice = if (offset < currentEvidencePayloadBytes.size) {
                            currentEvidencePayloadBytes.copyOfRange(offset, currentEvidencePayloadBytes.size)
                        } else {
                            ByteArray(0)
                        }
                        gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, slice)
                    } else {
                        gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                }

                override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "Nearby responder connected to BLE SOS GATT server: ${device?.address}")
                    }
                }
            })

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val charac = BluetoothGattCharacteristic(
                EVIDENCE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(charac)
            gattServer?.addService(service)
            _relayStatus.value = _relayStatus.value.copy(gattEvidenceManifestReady = true)
        } catch (e: Exception) {
            Log.w(TAG, "GATT evidence server failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLeAdvertising(incidentId: String, lat: Double, lng: Double): Boolean {
        val adapter = bluetoothAdapter ?: return false
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            _relayStatus.value = _relayStatus.value.copy(
                isBluetoothEnabled = true,
                isAdvertising = false,
                statusMessage = "Device hardware does not support BLE Peripheral advertising; Firestore Hex relay active"
            )
            return false
        }

        stopAdvertisingOnly()

        val shortInc = incidentId.takeLast(8)
        val payloadStr = String.format(Locale.US, "R3|%s|%.3f|%.3f", shortInc, lat, lng)
        val payloadBytes = payloadStr.toByteArray(StandardCharsets.UTF_8).take(22).toByteArray()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), payloadBytes)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE SOS advertising active: $payloadStr")
                _relayStatus.value = _relayStatus.value.copy(
                    isBluetoothEnabled = true,
                    isAdvertising = true,
                    permissionMissing = false,
                    statusMessage = "BLE SOS broadcasting ($shortInc) + GATT evidence manifest active"
                )
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE advertising start failed with code=$errorCode")
                _relayStatus.value = _relayStatus.value.copy(
                    isBluetoothEnabled = true,
                    isAdvertising = false,
                    statusMessage = "BLE advertise failure code=$errorCode"
                )
            }
        }
        advertiseCallback = cb
        advertiser?.startAdvertising(settings, data, cb)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopEmergencyBleBroadcast() {
        stopAdvertisingOnly()
        try {
            gattServer?.close()
            gattServer = null
        } catch (_: Exception) {
        }
        _relayStatus.value = _relayStatus.value.copy(
            isAdvertising = false,
            gattEvidenceManifestReady = false,
            statusMessage = if (_relayStatus.value.isScanning) "BLE responder scanner active" else "BLE standby"
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingOnly() {
        try {
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
            advertiseCallback = null
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanningForNearbySos(): Boolean {
        val adapter = ensureAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _relayStatus.value = _relayStatus.value.copy(
                isBluetoothEnabled = false,
                isScanning = false,
                statusMessage = "Enable Bluetooth to scan nearby BLE SOS beacons"
            )
            return false
        }
        if (!hasBleScanPermission()) {
            _relayStatus.value = _relayStatus.value.copy(
                isBluetoothEnabled = true,
                isScanning = false,
                permissionMissing = true,
                statusMessage = "Grant Nearby Devices permission to scan BLE alerts"
            )
            return false
        }

        return try {
            scanner = adapter.bluetoothLeScanner
            if (scanner == null) return false
            stopScanning()

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()

            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let { processScanResult(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE scan failed errorCode=$errorCode")
                    _relayStatus.value = _relayStatus.value.copy(
                        isScanning = false,
                        statusMessage = "BLE scan error=$errorCode"
                    )
                }
            }
            scanCallback = cb
            scanner?.startScan(listOf(filter), settings, cb)
            _relayStatus.value = _relayStatus.value.copy(
                isBluetoothEnabled = true,
                isScanning = true,
                permissionMissing = false,
                statusMessage = "Scanning nearby Bluetooth LE hexagonal rescue beacons"
            )
            true
        } catch (se: SecurityException) {
            _relayStatus.value = _relayStatus.value.copy(
                permissionMissing = true,
                statusMessage = "Nearby Devices permission required to scan BLE alerts"
            )
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start BLE scan: ${e.message}")
            false
        }
    }

    private fun processScanResult(result: ScanResult) {
        val rawBytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
        val text = String(rawBytes, StandardCharsets.UTF_8)
        // Format: R3|<shortInc>|<lat3>|<lng3>
        val parts = text.split("|")
        if (parts.size < 4 || parts[0] != "R3") return
        val shortId = parts[1].ifBlank { "BLE-SOS" }
        val lat = parts[2].toDoubleOrNull() ?: 0.0
        val lng = parts[3].toDoubleOrNull() ?: 0.0
        val estDist = estimateBleDistanceMeters(result.rssi)

        val discovered = BleDiscoveredEmergency(
            incidentId = "BLE-$shortId",
            victimLatitude = lat,
            victimLongitude = lng,
            rssiDbm = result.rssi,
            estimatedDistanceMeters = estDist,
            sectorHint = "BLE Proximity",
            evidenceSummary = "Bluetooth LE Emergency Packet (${result.rssi} dBm)"
        )

        val current = _discoveredEmergencies.value.toMutableList()
        val idx = current.indexOfFirst { it.incidentId == discovered.incidentId }
        if (idx >= 0) {
            current[idx] = discovered
        } else {
            current.add(0, discovered)
        }
        _discoveredEmergencies.value = current.take(15)
    }

    private fun estimateBleDistanceMeters(rssi: Int): Float {
        if (rssi >= 0) return 5f
        val txPower = -59.0
        val ratio = (txPower - rssi) / 20.0
        return Math.pow(10.0, ratio).toFloat().coerceIn(2f, 250f)
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        try {
            scanCallback?.let { scanner?.stopScan(it) }
            scanCallback = null
        } catch (_: Exception) {
        }
        _relayStatus.value = _relayStatus.value.copy(isScanning = false)
    }

    fun cleanup() {
        stopEmergencyBleBroadcast()
        stopScanning()
    }
}

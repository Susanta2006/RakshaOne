package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.telephony.TelephonyManager
import com.example.data.model.LocationEmergencyInfo
import com.example.data.model.NearbyFacility
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class SupportedRegion(
    val countryCode: String,
    val countryName: String,
    val primarySos: String,
    val police: String,
    val ambulance: String,
    val fire: String,
    val authorityName: String
)

class RealtimeEmergencyService(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) {
    private val _emergencyInfo = MutableStateFlow(
        LocationEmergencyInfo(
            countryCode = "IN",
            countryName = "India",
            locality = "Detecting Real GPS...",
            primarySosNumber = "112",
            policeNumber = "112",
            ambulanceNumber = "108",
            fireNumber = "101",
            womenHelpline = "1091",
            authorityName = "Emergency Response Support System (ERSS 112)",
            isRealGpsFix = false
        )
    )
    val emergencyInfo: StateFlow<LocationEmergencyInfo> = _emergencyInfo.asStateFlow()

    private val _realLocation = MutableStateFlow<Location?>(null)
    val realLocation: StateFlow<Location?> = _realLocation.asStateFlow()

    private val _manualRegionOverride = MutableStateFlow<String?>(null)
    val manualRegionOverride: StateFlow<String?> = _manualRegionOverride.asStateFlow()

    val supportedRegions: List<SupportedRegion> = listOf(
        SupportedRegion("IN", "India", "112", "112", "108", "101", "National ERSS 112 Dispatch"),
        SupportedRegion("US", "United States", "911", "911", "911", "911", "E911 Public Safety Answering Point"),
        SupportedRegion("GB", "United Kingdom", "999", "999", "999", "999", "UK 999/112 Joint Emergency Operations"),
        SupportedRegion("CA", "Canada", "911", "911", "911", "911", "Canada 911 Emergency Communications"),
        SupportedRegion("AU", "Australia", "000", "000", "000", "000", "Triple Zero (000) National Dispatch"),
        SupportedRegion("NZ", "New Zealand", "111", "111", "111", "111", "111 Emergency Service"),
        SupportedRegion("DE", "Germany", "112", "110", "112", "112", "Integrierte Leitstelle (112/110)"),
        SupportedRegion("FR", "France", "112", "17", "15", "18", "SAMU / Police Secours / Pompiers"),
        SupportedRegion("AE", "United Arab Emirates", "999", "999", "998", "997", "UAE MOI Emergency Dispatch"),
        SupportedRegion("JP", "Japan", "110", "110", "119", "119", "National Police & Fire-Disaster Bureau"),
        SupportedRegion("SG", "Singapore", "999", "999", "995", "995", "Singapore Police Force & SCDF")
    )

    /**
     * Determines the country code directly from geographic coordinate bounds
     * even when the network Geocoder is unavailable or offline in emulators.
     */
    fun inferCountryFromCoordinates(latitude: Double, longitude: Double): String? {
        return when {
            // India
            latitude in 6.0..37.5 && longitude in 68.0..97.5 -> "IN"

            // United States (Contiguous, Alaska, Hawaii)
            (latitude in 24.5..49.5 && longitude in -125.0..-66.9) ||
                (latitude in 51.0..71.5 && longitude in -179.0..-129.0) ||
                (latitude in 18.8..22.3 && longitude in -160.5..-154.5) -> "US"

            // Canada
            latitude in 45.0..83.0 && longitude in -141.0..-52.5 -> "CA"

            // United Kingdom
            latitude in 49.8..60.9 && longitude in -8.5..1.9 -> "GB"

            // Australia
            latitude in -44.0..-10.0 && longitude in 112.5..154.5 -> "AU"

            // New Zealand
            latitude in -47.5..-34.0 && longitude in 166.0..178.6 -> "NZ"

            // Germany
            latitude in 47.2..55.1 && longitude in 5.8..15.1 -> "DE"

            // France
            latitude in 41.3..51.1 && longitude in -5.2..9.6 -> "FR"

            // Japan
            latitude in 24.0..46.0 && longitude in 122.5..146.0 -> "JP"

            // Singapore
            latitude in 1.15..1.48 && longitude in 103.6..104.1 -> "SG"

            // United Arab Emirates
            latitude in 22.5..26.5 && longitude in 51.0..56.5 -> "AE"

            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun refreshRealLocation(): Location? = withContext(Dispatchers.IO) {
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val cts = CancellationTokenSource()
        var loc: Location? = null

        if (hasFine) {
            try {
                loc = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await()
            } catch (_: Exception) {}

            if (loc == null) {
                try {
                    loc = fusedLocationClient.lastLocation.await()
                } catch (_: Exception) {}
            }
        }

        if (loc != null) {
            updateFromLocation(loc)
        } else {
            val fallbackCountry = _manualRegionOverride.value ?: detectCountryCodeFromTelephonyOrLocale()
            val baseRules = getEmergencyRulesForCountry(fallbackCountry)
            _emergencyInfo.value = _emergencyInfo.value.copy(
                countryCode = fallbackCountry,
                countryName = Locale.Builder().setRegion(fallbackCountry).build().displayCountry.ifBlank { baseRules.defaultCountryName },
                locality = "Acquiring GPS fix...",
                primarySosNumber = baseRules.primarySos,
                policeNumber = baseRules.police,
                ambulanceNumber = baseRules.ambulance,
                fireNumber = baseRules.fire,
                womenHelpline = baseRules.womenHelpline,
                authorityName = baseRules.authorityName,
                isRealGpsFix = false,
                latitude = null,
                longitude = null
            )
        }

        loc
    }

    suspend fun updateFromLocation(location: Location) {
        _realLocation.value = location
        val resolved = resolveLocationEmergencyInfo(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy
        )
        _emergencyInfo.value = resolved
    }

    suspend fun resolveLocationEmergencyInfo(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float = 0f
    ): LocationEmergencyInfo = withContext(Dispatchers.IO) {
        val geocoder = Geocoder(context, Locale.getDefault())
        var countryCode: String? = null
        var countryName: String? = null
        var adminArea = ""
        var locality = ""
        var addressLine = ""

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val addresses = suspendCancellableCoroutine<List<Address>> { cont ->
                    try {
                        geocoder.getFromLocation(latitude, longitude, 1) { addrs ->
                            cont.resume(addrs)
                        }
                    } catch (e: Exception) {
                        cont.resume(emptyList())
                    }
                }
                if (addresses.isNotEmpty()) {
                    val addr = addresses[0]
                    countryCode = addr.countryCode
                    countryName = addr.countryName
                    adminArea = addr.adminArea ?: ""
                    locality = addr.locality ?: addr.subAdminArea ?: ""
                    addressLine = addr.getAddressLine(0) ?: ""
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    countryCode = addr.countryCode
                    countryName = addr.countryName
                    adminArea = addr.adminArea ?: ""
                    locality = addr.locality ?: addr.subAdminArea ?: ""
                    addressLine = addr.getAddressLine(0) ?: ""
                }
            }
        } catch (_: Exception) {}

        // Priority 1: User's manual region selection if actively chosen
        // Priority 2: Geocoder-detected country from real GPS
        // Priority 3: Offline coordinate boundary inference from real latitude/longitude
        // Priority 4: Telephony SIM / Network / Locale fallback
        val inferredByCoords = inferCountryFromCoordinates(latitude, longitude)
        val finalCountry = _manualRegionOverride.value
            ?: countryCode?.uppercase(Locale.US)
            ?: inferredByCoords
            ?: detectCountryCodeFromTelephonyOrLocale()

        val rules = getEmergencyRulesForCountry(finalCountry)

        val resolvedCountryName = countryName?.takeIf { it.isNotBlank() }
            ?: Locale.Builder().setRegion(finalCountry).build().displayCountry.takeIf { it.isNotBlank() }
            ?: rules.defaultCountryName

        val resolvedLocality = when {
            locality.isNotBlank() -> locality
            adminArea.isNotBlank() -> adminArea
            inferredByCoords != null -> "GPS Location ($resolvedCountryName)"
            else -> "Real GPS Active"
        }

        LocationEmergencyInfo(
            countryCode = finalCountry,
            countryName = resolvedCountryName,
            adminArea = adminArea,
            locality = resolvedLocality,
            addressLine = addressLine.ifBlank {
                "GPS: ${String.format(Locale.US, "%.5f", latitude)}, ${String.format(Locale.US, "%.5f", longitude)} (±${accuracyMeters.toInt()}m)"
            },
            primarySosNumber = rules.primarySos,
            policeNumber = rules.police,
            ambulanceNumber = rules.ambulance,
            fireNumber = rules.fire,
            womenHelpline = rules.womenHelpline,
            authorityName = rules.authorityName,
            isRealGpsFix = true,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            timestamp = System.currentTimeMillis()
        )
    }

    fun setManualRegionOverride(countryCode: String) {
        val clean = countryCode.uppercase(Locale.US)
        _manualRegionOverride.value = clean
        val rules = getEmergencyRulesForCountry(clean)
        val currentLoc = _realLocation.value
        val countryName = Locale.Builder().setRegion(clean).build().displayCountry.ifBlank { rules.defaultCountryName }

        _emergencyInfo.value = _emergencyInfo.value.copy(
            countryCode = clean,
            countryName = countryName,
            locality = if (currentLoc != null) "GPS: $countryName" else countryName,
            primarySosNumber = rules.primarySos,
            policeNumber = rules.police,
            ambulanceNumber = rules.ambulance,
            fireNumber = rules.fire,
            womenHelpline = rules.womenHelpline,
            authorityName = rules.authorityName
        )
    }

    fun clearManualRegionOverride() {
        _manualRegionOverride.value = null
        val loc = _realLocation.value
        if (loc != null) {
            val inferred = inferCountryFromCoordinates(loc.latitude, loc.longitude) ?: detectCountryCodeFromTelephonyOrLocale()
            val rules = getEmergencyRulesForCountry(inferred)
            val countryName = Locale.Builder().setRegion(inferred).build().displayCountry.ifBlank { rules.defaultCountryName }
            _emergencyInfo.value = _emergencyInfo.value.copy(
                countryCode = inferred,
                countryName = countryName,
                primarySosNumber = rules.primarySos,
                policeNumber = rules.police,
                ambulanceNumber = rules.ambulance,
                fireNumber = rules.fire,
                womenHelpline = rules.womenHelpline,
                authorityName = rules.authorityName
            )
        }
    }

    fun getDynamicNearbyFacilities(userLocation: Location?): List<NearbyFacility> {
        val info = _emergencyInfo.value
        val locality = info.locality.ifBlank { info.countryName }
        return listOf(
            NearbyFacility(
                name = "$locality Police Dispatch",
                type = "Police",
                distanceKm = 0.0,
                phone = info.policeNumber,
                address = info.authorityName,
                isOpen24x7 = true
            ),
            NearbyFacility(
                name = "$locality Emergency Medical / Ambulance",
                type = "Hospital",
                distanceKm = 0.0,
                phone = info.ambulanceNumber,
                address = "24/7 Emergency Medical Services (${info.countryName})",
                isOpen24x7 = true
            ),
            NearbyFacility(
                name = "$locality Fire & Rescue",
                type = "Fire",
                distanceKm = 0.0,
                phone = info.fireNumber,
                address = "Municipal Fire & Rescue (${info.countryName})",
                isOpen24x7 = true
            )
        )
    }

    private fun detectCountryCodeFromTelephonyOrLocale(): String {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val simCountry = tm?.simCountryIso?.uppercase(Locale.US)
            if (!simCountry.isNullOrBlank() && simCountry.length == 2) {
                return simCountry
            }
            val netCountry = tm?.networkCountryIso?.uppercase(Locale.US)
            if (!netCountry.isNullOrBlank() && netCountry.length == 2) {
                return netCountry
            }
        } catch (_: Exception) {}

        val localeCountry = Locale.getDefault().country.uppercase(Locale.US)
        return if (localeCountry.length == 2) localeCountry else "IN"
    }

    private fun getEmergencyRulesForCountry(countryCode: String): CountryEmergencyRules {
        return when (countryCode) {
            "IN" -> CountryEmergencyRules(
                defaultCountryName = "India",
                primarySos = "112",
                police = "112",
                ambulance = "108",
                fire = "101",
                womenHelpline = "1091",
                authorityName = "National Emergency Response Support System (ERSS 112)"
            )
            "US" -> CountryEmergencyRules(
                defaultCountryName = "United States",
                primarySos = "911",
                police = "911",
                ambulance = "911",
                fire = "911",
                womenHelpline = "1-800-799-7233",
                authorityName = "E911 Public Safety Answering Point (PSAP)"
            )
            "CA" -> CountryEmergencyRules(
                defaultCountryName = "Canada",
                primarySos = "911",
                police = "911",
                ambulance = "911",
                fire = "911",
                womenHelpline = "1-866-863-0511",
                authorityName = "Canada 911 Emergency Communications"
            )
            "GB", "UK" -> CountryEmergencyRules(
                defaultCountryName = "United Kingdom",
                primarySos = "999",
                police = "999",
                ambulance = "999",
                fire = "999",
                womenHelpline = "0808 2000 247",
                authorityName = "UK 999/112 Emergency Joint Operations"
            )
            "AU" -> CountryEmergencyRules(
                defaultCountryName = "Australia",
                primarySos = "000",
                police = "000",
                ambulance = "000",
                fire = "000",
                womenHelpline = "1800 737 732",
                authorityName = "Triple Zero (000) National Dispatch"
            )
            "NZ" -> CountryEmergencyRules(
                defaultCountryName = "New Zealand",
                primarySos = "111",
                police = "111",
                ambulance = "111",
                fire = "111",
                womenHelpline = "0800 456 450",
                authorityName = "111 Emergency Service"
            )
            "DE" -> CountryEmergencyRules(
                defaultCountryName = "Germany",
                primarySos = "112",
                police = "110",
                ambulance = "112",
                fire = "112",
                womenHelpline = "08000 116 016",
                authorityName = "Integrierte Leitstelle (112 / 110)"
            )
            "FR" -> CountryEmergencyRules(
                defaultCountryName = "France",
                primarySos = "112",
                police = "17",
                ambulance = "15",
                fire = "18",
                womenHelpline = "3919",
                authorityName = "SAMU / Police Secours / Pompiers (112)"
            )
            "JP" -> CountryEmergencyRules(
                defaultCountryName = "Japan",
                primarySos = "110",
                police = "110",
                ambulance = "119",
                fire = "119",
                womenHelpline = "0570-0-55210",
                authorityName = "National Police Agency & Fire-Disaster Bureau (110/119)"
            )
            "SG" -> CountryEmergencyRules(
                defaultCountryName = "Singapore",
                primarySos = "999",
                police = "999",
                ambulance = "995",
                fire = "995",
                womenHelpline = "1800 777 0000",
                authorityName = "Singapore Police Force & SCDF"
            )
            "AE" -> CountryEmergencyRules(
                defaultCountryName = "United Arab Emirates",
                primarySos = "999",
                police = "999",
                ambulance = "998",
                fire = "997",
                womenHelpline = "800 111",
                authorityName = "UAE MOI Emergency Dispatch"
            )
            else -> CountryEmergencyRules(
                defaultCountryName = "International",
                primarySos = "112",
                police = "112",
                ambulance = "112",
                fire = "112",
                womenHelpline = "112",
                authorityName = "Universal Emergency Dispatch (112)"
            )
        }
    }

    private data class CountryEmergencyRules(
        val defaultCountryName: String,
        val primarySos: String,
        val police: String,
        val ambulance: String,
        val fire: String,
        val womenHelpline: String,
        val authorityName: String
    )
}

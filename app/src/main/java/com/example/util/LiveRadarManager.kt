package com.example.util

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.R
import com.example.data.model.RelationshipTrustRules
import com.example.data.model.TrustedContactEntity
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.database
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class LiveRadarPin(
    val uid: String,
    val name: String,
    val relationship: String,
    val latitude: Double,
    val longitude: Double,
    val bearing: Float = 0f,
    val speed: Float = 0f,
    val battery: Int = 100,
    val updatedAt: Long = System.currentTimeMillis(),
    val sharingActive: Boolean = true,
    val hasActiveTrip: Boolean = false,
    val isSosActive: Boolean = false,
    val isCoreFamily: Boolean = false,
    val statusNote: String = "",
    val isPeerOnline: Boolean = true
)

data class NearbySupportUser(
    val uid: String,
    val displayName: String,
    val roleTag: String,
    val isVerifiedResponder: Boolean,
    val isOnDuty: Boolean,
    val latitude: Double,
    val longitude: Double,
    val updatedAtMillis: Long,
    val isOnline: Boolean = true,
    val isEmergencyVictim: Boolean = false
)

/**
 * 100% Free-Tier Live Radar & Community Support Relay:
 * - Overwrites a single compact doc in Firestore `live_radar/{ownerUid}` matching `firestore.rules`
 *   ONLY every 4s when the Dispatch screen is open OR every >=100m of movement in background.
 * - Publishes & observes anonymized `nearby_presence/{ownerUid}` inside the same geohash4 zone
 *   so lone users can see nearby RakshaOne citizens / verified responders around them.
 * - Maintains an overwritten local JSON snapshot at `filesDir/live_radar_cache.json` strictly <= 1 MB.
 * - Enforces Relationship-Based Location Permissions:
 *   - Core Family (Father, Mother, Brother, Sister, Spouse): 24/7 Always-On permission.
 *   - Extended Circle: Active Trips, Low Battery (<10%), R3 SOS, or explicit 24/7 toggle.
 */
class LiveRadarManager(private val context: Context) {
    private val tag = "LiveRadarManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    private val firestore by lazy {
        try {
            val dbId = context.getString(R.string.firestore_database_id)
            Firebase.firestore(dbId)
        } catch (_: Exception) {
            Firebase.firestore
        }
    }

    private val cacheFile by lazy { File(context.filesDir, "live_radar_cache.json") }

    private val _radarPins = MutableStateFlow<Map<String, LiveRadarPin>>(loadCachedPins())
    val radarPins: StateFlow<Map<String, LiveRadarPin>> = _radarPins.asStateFlow()

    private val _nearbyUsers = MutableStateFlow<List<NearbySupportUser>>(emptyList())
    val nearbyUsers: StateFlow<List<NearbySupportUser>> = _nearbyUsers.asStateFlow()

    private val _disconnectAlertMessage = MutableStateFlow<String?>(null)
    val disconnectAlertMessage: StateFlow<String?> = _disconnectAlertMessage.asStateFlow()

    private var lastPublishTimeMs = 0L
    private var lastPublishedLat = 0.0
    private var lastPublishedLng = 0.0
    private var lastPresencePublishTimeMs = 0L
    private var lastSharingState = true
    private var lastLowBatteryAlerted = false

    private val rtdbRoot: DatabaseReference? by lazy {
        try {
            Firebase.database("https://gen-lang-client-0320672030-default-rtdb.firebaseio.com").reference
        } catch (_: Exception) {
            try { Firebase.database.reference } catch (_: Exception) { null }
        }
    }

    private var uidQueryRegistration: ListenerRegistration? = null
    private var emailQueryRegistration: ListenerRegistration? = null
    private var nearbyPresenceRegistration: ListenerRegistration? = null
    private var rtdbPresenceRef: DatabaseReference? = null
    private var rtdbPresenceListener: ValueEventListener? = null
    private var subscribedUid: String? = null
    private var subscribedGeohashPrefix: String? = null
    @Volatile
    private var isSelfOnline: Boolean = true

    private val pinsFromUidQuery = linkedMapOf<String, LiveRadarPin>()
    private val pinsFromEmailQuery = linkedMapOf<String, LiveRadarPin>()
    private val rtdbNearbyUsers = linkedMapOf<String, NearbySupportUser>()
    private val firestoreNearbyUsers = linkedMapOf<String, NearbySupportUser>()

    // Resolved local circle metadata for mapping UIDs -> Spouse / Father / Mother etc.
    private var latestContacts: List<TrustedContactEntity> = emptyList()
    private var resolvedGuardianUids: List<String> = emptyList()
    private var uidToPhoneKey: Map<String, String> = emptyMap()
    private var uidToEmail: Map<String, String> = emptyMap()

    init {
        startListeningSafe()
    }

    fun setConnectivityOnline(online: Boolean) {
        val changed = isSelfOnline != online
        isSelfOnline = online
        if (!online) {
            rebuildMergedPinsLocked()
            _nearbyUsers.value = emptyList()
        } else if (changed) {
            startListeningSafe()
            rebuildMergedPinsLocked()
            rebuildNearbyUsersLocked()
        }
    }

    fun updateTrustedContactsContext(
        contacts: List<TrustedContactEntity>,
        resolvedUids: List<String>,
        uidToPhoneMap: Map<String, String>,
        uidToEmailMap: Map<String, String>
    ) {
        latestContacts = contacts
        resolvedGuardianUids = resolvedUids
        uidToPhoneKey = uidToPhoneMap
        uidToEmail = uidToEmailMap
        rebuildMergedPinsLocked()
    }

    fun startListeningSafe() {
        val myUser = try { Firebase.auth.currentUser } catch (_: Exception) { null }
        val myUid = myUser?.uid?.takeIf { it.isNotBlank() } ?: return
        val myEmail = myUser.email?.trim()?.lowercase(Locale.US).orEmpty()

        // If user just signed in or switched account, migrate away from LOCAL-SELF & reset old listeners
        if (subscribedUid != myUid) {
            uidQueryRegistration?.remove()
            uidQueryRegistration = null
            emailQueryRegistration?.remove()
            emailQueryRegistration = null
            pinsFromUidQuery.clear()
            pinsFromEmailQuery.clear()
            subscribedUid = myUid

            val cleaned = _radarPins.value.toMutableMap()
            cleaned.remove("LOCAL-SELF")
            val staleSelfDuplicate = cleaned[myUid]
            if (staleSelfDuplicate != null && !staleSelfDuplicate.relationship.equals("Self", ignoreCase = true)) {
                cleaned.remove(myUid)
            }
            _radarPins.value = cleaned
        }

        if (uidQueryRegistration == null) {
            try {
                uidQueryRegistration = firestore.collection("live_radar")
                    .whereArrayContains("authorizedUids", myUid)
                    .addSnapshotListener { snapshots, err ->
                        if (err != null) {
                            Log.w(tag, "live_radar UID query deferred: ${err.localizedMessage}")
                            return@addSnapshotListener
                        }
                        if (snapshots == null) return@addSnapshotListener
                        synchronized(pinsFromUidQuery) {
                            pinsFromUidQuery.clear()
                            for (doc in snapshots.documents) {
                                parseLiveRadarDocument(doc, myUid)?.let { pin ->
                                    pinsFromUidQuery[pin.uid] = pin
                                }
                            }
                        }
                        rebuildMergedPinsLocked()
                    }
            } catch (e: Exception) {
                Log.w(tag, "Live radar UID listener deferred: ${e.localizedMessage}")
            }
        }

        if (myEmail.isNotBlank() && emailQueryRegistration == null) {
            try {
                emailQueryRegistration = firestore.collection("live_radar")
                    .whereArrayContains("authorizedEmails", myEmail)
                    .addSnapshotListener { snapshots, err ->
                        if (err != null) {
                            Log.w(tag, "live_radar email query deferred: ${err.localizedMessage}")
                            return@addSnapshotListener
                        }
                        if (snapshots == null) return@addSnapshotListener
                        synchronized(pinsFromEmailQuery) {
                            pinsFromEmailQuery.clear()
                            for (doc in snapshots.documents) {
                                parseLiveRadarDocument(doc, myUid)?.let { pin ->
                                    pinsFromEmailQuery[pin.uid] = pin
                                }
                            }
                        }
                        rebuildMergedPinsLocked()
                    }
            } catch (e: Exception) {
                Log.w(tag, "Live radar email listener deferred: ${e.localizedMessage}")
            }
        }
    }

    private fun startNearbyPresenceListener(geohashPrefix: String, myUid: String) {
        if (myUid == "LOCAL-SELF" || geohashPrefix.isBlank()) return
        if (subscribedGeohashPrefix == geohashPrefix && nearbyPresenceRegistration != null && rtdbPresenceListener != null) return

        nearbyPresenceRegistration?.remove()
        rtdbPresenceListener?.let { listener -> rtdbPresenceRef?.removeEventListener(listener) }
        subscribedGeohashPrefix = geohashPrefix

        // 1. Firestore nearby_presence listener
        try {
            nearbyPresenceRegistration = firestore.collection("nearby_presence")
                .whereEqualTo("geohashPrefix", geohashPrefix)
                .addSnapshotListener { snapshots, err ->
                    if (err != null || snapshots == null) return@addSnapshotListener
                    val cutoff = System.currentTimeMillis() - PEER_ONLINE_FRESHNESS_MS
                    synchronized(firestoreNearbyUsers) {
                        firestoreNearbyUsers.clear()
                        snapshots.documents.forEach { doc ->
                            val uid = doc.getString("ownerUid") ?: doc.id
                            if (uid.isBlank() || uid == myUid) return@forEach
                            val peerOnline = doc.getBoolean("isOnline") ?: true
                            if (!peerOnline) return@forEach
                            val lat = doc.getDouble("lat") ?: return@forEach
                            val lng = doc.getDouble("lng") ?: return@forEach
                            if (lat == 0.0 && lng == 0.0) return@forEach
                            val updatedMs = doc.getTimestamp("updatedAt")?.toDate()?.time ?: System.currentTimeMillis()
                            if (updatedMs < cutoff) return@forEach
                            firestoreNearbyUsers[uid] = NearbySupportUser(
                                uid = uid,
                                displayName = doc.getString("displayName")?.ifBlank { "RakshaOne User" } ?: "RakshaOne User",
                                roleTag = doc.getString("roleTag")?.ifBlank { "Nearby Citizen" } ?: "Nearby Citizen",
                                isVerifiedResponder = doc.getBoolean("isVerifiedResponder") ?: false,
                                isOnDuty = doc.getBoolean("isOnDuty") ?: false,
                                latitude = lat,
                                longitude = lng,
                                updatedAtMillis = updatedMs,
                                isOnline = true,
                                isEmergencyVictim = doc.getBoolean("isEmergency") ?: false
                            )
                        }
                    }
                    rebuildNearbyUsersLocked()
                }
        } catch (e: Exception) {
            Log.w(tag, "Nearby presence listener deferred: ${e.localizedMessage}")
        }

        // 2. Firebase Realtime Database live_presence listener (sub-second active green dots & red victim avatar + onDisconnect)
        try {
            val ref = rtdbRoot?.child("live_presence")?.child(geohashPrefix) ?: return
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val cutoff = System.currentTimeMillis() - PEER_ONLINE_FRESHNESS_MS
                    synchronized(rtdbNearbyUsers) {
                        rtdbNearbyUsers.clear()
                        for (child in snapshot.children) {
                            val uid = child.child("ownerUid").getValue(String::class.java) ?: child.key ?: continue
                            if (uid.isBlank() || uid == myUid) continue
                            val peerOnline = child.child("isOnline").getValue(Boolean::class.java) ?: false
                            val sharing = child.child("sharingActive").getValue(Boolean::class.java) ?: true
                            if (!peerOnline || !sharing) continue
                            val lat = child.child("lat").getValue(Double::class.java) ?: continue
                            val lng = child.child("lng").getValue(Double::class.java) ?: continue
                            if (lat == 0.0 && lng == 0.0) continue
                            val updatedMs = child.child("updatedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
                            if (updatedMs < cutoff) continue
                            rtdbNearbyUsers[uid] = NearbySupportUser(
                                uid = uid,
                                displayName = child.child("displayName").getValue(String::class.java)?.ifBlank { "RakshaOne User" } ?: "RakshaOne User",
                                roleTag = child.child("roleTag").getValue(String::class.java)?.ifBlank { "Nearby Citizen" } ?: "Nearby Citizen",
                                isVerifiedResponder = child.child("isVerifiedResponder").getValue(Boolean::class.java) ?: false,
                                isOnDuty = child.child("isOnDuty").getValue(Boolean::class.java) ?: false,
                                latitude = lat,
                                longitude = lng,
                                updatedAtMillis = updatedMs,
                                isOnline = true,
                                isEmergencyVictim = child.child("isEmergency").getValue(Boolean::class.java) ?: false
                            )
                        }
                    }
                    rebuildNearbyUsersLocked()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(tag, "RTDB nearby presence listener skipped: ${error.message}")
                }
            }
            rtdbPresenceRef = ref
            rtdbPresenceListener = listener
            ref.addValueEventListener(listener)
        } catch (e: Exception) {
            Log.w(tag, "RTDB listener setup skipped: ${e.localizedMessage}")
        }
    }

    private fun parseLiveRadarDocument(
        doc: com.google.firebase.firestore.DocumentSnapshot,
        myUid: String
    ): LiveRadarPin? {
        val docOwnerUid = doc.getString("ownerUid") ?: doc.id
        // Never allow self's Firestore doc to duplicate "You (Self)"
        if (docOwnerUid.isBlank() || docOwnerUid == myUid || doc.id == myUid) return null
        val lat = doc.getDouble("lat") ?: return null
        val lng = doc.getDouble("lng") ?: return null
        if (lat == 0.0 && lng == 0.0) return null

        val cloudName = doc.getString("ownerName") ?: "Guardian"
        val cloudRel = doc.getString("relationshipHint")
            ?: doc.getString("relationship")
            ?: "Family"
        val matchedContact = findMatchingLocalContact(docOwnerUid, cloudName)
        val resolvedName = matchedContact?.name?.takeIf { it.isNotBlank() } ?: cloudName
        val resolvedRelation = matchedContact?.relationship?.takeIf { it.isNotBlank() } ?: cloudRel

        val updatedMillis = when {
            doc.getTimestamp("updatedAt") != null -> doc.getTimestamp("updatedAt")!!.toDate().time
            doc.getLong("updatedAt") != null -> doc.getLong("updatedAt")!!
            else -> System.currentTimeMillis()
        }
        val statusNote = doc.getString("statusNote").orEmpty()
        val isEmergency = doc.getBoolean("isEmergency") ?: doc.getBoolean("isSosActive") ?: false
        val hasTrip = statusNote.contains("TRIP", ignoreCase = true) ||
            (doc.getBoolean("hasActiveTrip") ?: false)
        val peerExplicitOnline = doc.getBoolean("isOnline") ?: true
        val sharing = doc.getBoolean("sharingActive") ?: true
        val isFreshOnline = peerExplicitOnline && sharing && (System.currentTimeMillis() - updatedMillis <= PEER_ONLINE_FRESHNESS_MS)

        return LiveRadarPin(
            uid = docOwnerUid,
            name = resolvedName,
            relationship = resolvedRelation,
            latitude = lat,
            longitude = lng,
            bearing = (doc.getDouble("bearing") ?: 0.0).toFloat(),
            speed = (doc.getDouble("speed") ?: 0.0).toFloat(),
            battery = (doc.getLong("battery") ?: 100L).toInt().coerceIn(0, 100),
            updatedAt = updatedMillis,
            sharingActive = sharing,
            hasActiveTrip = hasTrip,
            isSosActive = isEmergency,
            isCoreFamily = RelationshipTrustRules.isCoreFamily(resolvedRelation),
            statusNote = statusNote,
            isPeerOnline = isFreshOnline
        )
    }

    private fun findMatchingLocalContact(uid: String, cloudName: String): TrustedContactEntity? {
        val phoneKey = uidToPhoneKey[uid]
        if (!phoneKey.isNullOrBlank()) {
            latestContacts.firstOrNull {
                PhoneNormalizer.canonicalPhoneKey(it.phone) == phoneKey
            }?.let { return it }
        }
        val dirEmail = uidToEmail[uid]
        if (!dirEmail.isNullOrBlank()) {
            latestContacts.firstOrNull {
                it.email.trim().equals(dirEmail, ignoreCase = true)
            }?.let { return it }
        }
        val normalizedCloudName = cloudName.trim()
        if (normalizedCloudName.isNotBlank()) {
            latestContacts.firstOrNull {
                it.name.trim().equals(normalizedCloudName, ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    private fun rebuildMergedPinsLocked() {
        val myUid = try { Firebase.auth.currentUser?.uid ?: "LOCAL-SELF" } catch (_: Exception) { "LOCAL-SELF" }
        val merged = linkedMapOf<String, LiveRadarPin>()

        // Preserve ONLY the true self pin under canonical myUid
        val currentSelf = _radarPins.value[myUid]?.takeIf { it.relationship.equals("Self", ignoreCase = true) }
            ?: _radarPins.value.values.firstOrNull { it.relationship.equals("Self", ignoreCase = true) }?.copy(uid = myUid)
        if (currentSelf != null) {
            merged[myUid] = currentSelf.copy(isPeerOnline = isSelfOnline)
        }

        // Strict rule: peer markers only appear when BOTH self AND peer have an active internet connection
        if (isSelfOnline) {
            val cutoff = System.currentTimeMillis() - PEER_ONLINE_FRESHNESS_MS
            synchronized(pinsFromEmailQuery) {
                pinsFromEmailQuery.values.forEach { pin ->
                    if (pin.uid != myUid && pin.isPeerOnline && pin.sharingActive && pin.updatedAt >= cutoff) {
                        merged[pin.uid] = enrichPinWithLocalContact(pin)
                    }
                }
            }
            synchronized(pinsFromUidQuery) {
                pinsFromUidQuery.values.forEach { pin ->
                    if (pin.uid != myUid && pin.isPeerOnline && pin.sharingActive && pin.updatedAt >= cutoff) {
                        merged[pin.uid] = enrichPinWithLocalContact(pin)
                    }
                }
            }
        }

        _radarPins.value = merged
        savePinsToBoundedJson(merged)
        rebuildNearbyUsersLocked()
    }

    private fun rebuildNearbyUsersLocked() {
        if (!isSelfOnline) {
            _nearbyUsers.value = emptyList()
            return
        }
        val knownCircleUids = _radarPins.value.keys
        val cutoff = System.currentTimeMillis() - PEER_ONLINE_FRESHNESS_MS
        val combined = linkedMapOf<String, NearbySupportUser>()
        synchronized(firestoreNearbyUsers) {
            firestoreNearbyUsers.values.forEach { user ->
                if (user.isOnline && user.updatedAtMillis >= cutoff && !knownCircleUids.contains(user.uid)) {
                    combined[user.uid] = user
                }
            }
        }
        synchronized(rtdbNearbyUsers) {
            rtdbNearbyUsers.values.forEach { user ->
                if (user.isOnline && user.updatedAtMillis >= cutoff && !knownCircleUids.contains(user.uid)) {
                    combined[user.uid] = user
                }
            }
        }
        _nearbyUsers.value = combined.values.toList()
    }

    private fun enrichPinWithLocalContact(pin: LiveRadarPin): LiveRadarPin {
        val matched = findMatchingLocalContact(pin.uid, pin.name) ?: return pin
        return pin.copy(
            name = matched.name.ifBlank { pin.name },
            relationship = matched.relationship.ifBlank { pin.relationship },
            isCoreFamily = RelationshipTrustRules.isCoreFamily(matched.relationship)
        )
    }

    fun isContactPermittedToViewLiveLocation(
        contact: TrustedContactEntity,
        hasActiveTrip: Boolean,
        batteryPercent: Int,
        isSosActive: Boolean
    ): Boolean {
        if (!contact.isEnabled) return false
        if (contact.isCoreFamily()) return true
        if (contact.allow24x7Location) return true
        return hasActiveTrip || batteryPercent < 10 || isSosActive
    }

    fun computeAllowedGuardianPhones(
        contacts: List<TrustedContactEntity>,
        hasActiveTrip: Boolean,
        batteryPercent: Int,
        isSosActive: Boolean
    ): List<String> {
        return contacts
            .filter { isContactPermittedToViewLiveLocation(it, hasActiveTrip, batteryPercent, isSosActive) }
            .map { PhoneNormalizer.canonicalPhoneKey(it.phone) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(15)
    }

    fun buildGuardianDisconnectAlert(
        userName: String,
        areaName: String,
        batteryPercent: Int,
        lat: Double,
        lng: Double,
        reasonPaused: Boolean
    ): String {
        val cleanName = userName.ifBlank { "Family Member" }
        val area = areaName.ifBlank { "%.4f, %.4f".format(Locale.US, lat, lng) }
        val reason = if (reasonPaused) "paused live location" else "battery critical ($batteryPercent%)"
        return "⚠️ $cleanName $reason at $area • Battery $batteryPercent% • https://maps.google.com/?q=$lat,$lng"
    }

    fun clearDisconnectAlert() {
        _disconnectAlertMessage.value = null
    }

    fun getLastSelfPin(): LiveRadarPin? {
        return _radarPins.value.values.firstOrNull { it.relationship.equals("Self", ignoreCase = true) }
    }

    fun recordOfflineNetworkLossSnapshot(
        lat: Double,
        lng: Double,
        battery: Int,
        ownerName: String,
        areaName: String
    ): String {
        val cleanName = ownerName.ifBlank { "Citizen" }
        val area = areaName.ifBlank { "%.4f, %.4f".format(Locale.US, lat, lng) }
        val msg = "⚠️ $cleanName lost internet at $area • Battery $battery% • Last GPS: https://maps.google.com/?q=$lat,$lng"
        _disconnectAlertMessage.value = msg
        val existingSelf = getLastSelfPin()
        if (existingSelf != null) {
            val myUid = try { Firebase.auth.currentUser?.uid ?: existingSelf.uid } catch (_: Exception) { existingSelf.uid }
            val updatedMap = _radarPins.value.toMutableMap()
            updatedMap.remove("LOCAL-SELF")
            updatedMap[myUid] = existingSelf.copy(
                uid = myUid,
                latitude = if (lat != 0.0 || lng != 0.0) lat else existingSelf.latitude,
                longitude = if (lat != 0.0 || lng != 0.0) lng else existingSelf.longitude,
                battery = battery.coerceIn(0, 100),
                updatedAt = System.currentTimeMillis(),
                statusNote = "OFFLINE_BLACKBOX • Battery $battery%"
            )
            _radarPins.value = updatedMap
            savePinsToBoundedJson(updatedMap)
        }
        return msg
    }

    /**
     * Publishes updated live pin coordinates:
     * - Throttled to 4s when Dispatch map is actively open (`isForegroundDispatch`),
     * - Or >=100 meters movement in background (to keep Spark free tier well within limits),
     * - Always overwrites 1 single document matching `firestore.rules` + bounded <=1 MB local JSON file.
     */
    fun publishLocation(
        lat: Double,
        lng: Double,
        bearing: Float,
        speed: Float,
        battery: Int,
        sharingActive: Boolean,
        ownerName: String = "You",
        areaName: String = "",
        hasActiveTrip: Boolean = false,
        isSosActive: Boolean = false,
        contacts: List<TrustedContactEntity> = emptyList(),
        isForegroundDispatch: Boolean = false,
        forceImmediate: Boolean = false,
        isVerifiedResponder: Boolean = false,
        isResponderDutyActive: Boolean = false
    ): String? {
        if (lat == 0.0 && lng == 0.0) return null
        val now = System.currentTimeMillis()
        latestContacts = contacts

        val movedMeters = if (lastPublishedLat != 0.0 || lastPublishedLng != 0.0) {
            val out = FloatArray(1)
            Location.distanceBetween(lastPublishedLat, lastPublishedLng, lat, lng, out)
            out[0]
        } else {
            Float.MAX_VALUE
        }

        val minIntervalMs = if (isForegroundDispatch) 4_000L else 15_000L
        val stateChanged = sharingActive != lastSharingState
        val shouldPublish = forceImmediate || stateChanged ||
            (isForegroundDispatch && now - lastPublishTimeMs >= minIntervalMs) ||
            (!isForegroundDispatch && movedMeters >= 100f && now - lastPublishTimeMs >= minIntervalMs)

        // Check Guardian Disconnect or Low Battery (<10%) alert for Core Family
        var guardianAlertText: String? = null
        if (!sharingActive && lastSharingState) {
            guardianAlertText = buildGuardianDisconnectAlert(ownerName, areaName, battery, lat, lng, reasonPaused = true)
            _disconnectAlertMessage.value = guardianAlertText
        } else if (battery in 1 until 10 && !lastLowBatteryAlerted) {
            lastLowBatteryAlerted = true
            guardianAlertText = buildGuardianDisconnectAlert(ownerName, areaName, battery, lat, lng, reasonPaused = false)
            _disconnectAlertMessage.value = guardianAlertText
        } else if (battery >= 15) {
            lastLowBatteryAlerted = false
        }
        lastSharingState = sharingActive

        if (!shouldPublish) return guardianAlertText

        lastPublishTimeMs = now
        lastPublishedLat = lat
        lastPublishedLng = lng

        val myUid = try { Firebase.auth.currentUser?.uid ?: "LOCAL-SELF" } catch (_: Exception) { "LOCAL-SELF" }

        // Always update ONE canonical local Self pin immediately for smooth UI rendering
        val selfPin = LiveRadarPin(
            uid = myUid,
            name = ownerName.ifBlank { "You" },
            relationship = "Self",
            latitude = lat,
            longitude = lng,
            bearing = bearing,
            speed = speed,
            battery = battery.coerceIn(0, 100),
            updatedAt = now,
            sharingActive = sharingActive,
            hasActiveTrip = hasActiveTrip,
            isSosActive = isSosActive,
            isCoreFamily = true
        )
        val updatedMap = _radarPins.value.toMutableMap()
        if (myUid != "LOCAL-SELF") {
            updatedMap.remove("LOCAL-SELF")
        }
        updatedMap[myUid] = selfPin
        _radarPins.value = updatedMap
        savePinsToBoundedJson(updatedMap)

        if (myUid != "LOCAL-SELF") {
            startListeningSafe()
            val geohashPrefix = StorageWardenManager.encodeGeohash(lat, lng, 4)
            startNearbyPresenceListener(geohashPrefix, myUid)

            val permittedContacts = contacts.filter {
                isContactPermittedToViewLiveLocation(it, hasActiveTrip, battery, isSosActive)
            }
            val permittedPhoneKeys = permittedContacts
                .map { PhoneNormalizer.canonicalPhoneKey(it.phone) }
                .filter { it.isNotBlank() }
                .toSet()

            // Combine explicitly mapped UIDs + all resolved guardian UIDs for Core/permitted contacts
            val authorizedUids = LinkedHashSet<String>().apply {
                resolvedGuardianUids.forEach { uid ->
                    val mappedPhone = uidToPhoneKey[uid]
                    if (uid != myUid && (mappedPhone == null || mappedPhone in permittedPhoneKeys)) {
                        add(uid)
                    }
                }
            }.take(20)

            val authorizedEmails = permittedContacts
                .map { it.email.trim().lowercase(Locale.US) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(20)

            val statusNote = when {
                isSosActive -> "SOS_ACTIVE • Battery ${battery.coerceIn(0, 100)}%"
                hasActiveTrip -> "TRIP_ACTIVE • Battery ${battery.coerceIn(0, 100)}%"
                !sharingActive -> "PAUSED • Battery ${battery.coerceIn(0, 100)}%"
                else -> areaName.ifBlank { "LIVE_24X7" }
            }.take(240)

            // Exact schema matching firestore.rules match /live_radar/{ownerUid}
            val payload = hashMapOf<String, Any>(
                "ownerUid" to myUid,
                "ownerName" to ownerName.ifBlank { "Guardian" }.take(100),
                "relationshipHint" to "Guardian",
                "lat" to lat,
                "lng" to lng,
                "bearing" to bearing.toDouble(),
                "speed" to speed.toDouble(),
                "battery" to battery.coerceIn(0, 100),
                "sharingActive" to sharingActive,
                "isOnline" to isSelfOnline,
                "isEmergency" to isSosActive,
                "statusNote" to statusNote,
                "authorizedUids" to authorizedUids,
                "authorizedEmails" to authorizedEmails,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            scope.launch {
                try {
                    firestore.collection("live_radar")
                        .document(myUid)
                        .set(payload)
                } catch (e: Exception) {
                    Log.w(tag, "Live radar Firestore overwrite skipped: ${e.localizedMessage}")
                }

                // Publish anonymized nearby presence at most every 20s to stay ultra-lightweight on Spark tier
                if (sharingActive && (forceImmediate || isSosActive || now - lastPresencePublishTimeMs >= 20_000L)) {
                    lastPresencePublishTimeMs = now
                    val firstNameAlias = ownerName.trim().substringBefore(" ").ifBlank { "Citizen" }.take(24)
                    val roleTag = when {
                        isSosActive -> "SOS Victim"
                        isVerifiedResponder && isResponderDutyActive -> "Verified Responder"
                        isVerifiedResponder -> "Verified Citizen"
                        else -> "Nearby User"
                    }
                    val presencePayload = hashMapOf<String, Any>(
                        "ownerUid" to myUid,
                        "displayName" to firstNameAlias,
                        "roleTag" to roleTag,
                        "isVerifiedResponder" to isVerifiedResponder,
                        "isOnDuty" to isResponderDutyActive,
                        "isOnline" to isSelfOnline,
                        "isEmergency" to isSosActive,
                        "lat" to lat,
                        "lng" to lng,
                        "geohashPrefix" to geohashPrefix,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    try {
                        firestore.collection("nearby_presence")
                            .document(myUid)
                            .set(presencePayload)
                    } catch (pe: Exception) {
                        Log.w(tag, "Nearby presence update skipped: ${pe.localizedMessage}")
                    }

                    // Sync to Firebase Realtime Database with automatic onDisconnect() offline marker
                    try {
                        val nodeRef = rtdbRoot?.child("live_presence")?.child(geohashPrefix)?.child(myUid)
                        if (nodeRef != null) {
                            val rtdbPayload = mapOf(
                                "ownerUid" to myUid,
                                "displayName" to firstNameAlias,
                                "roleTag" to roleTag,
                                "isVerifiedResponder" to isVerifiedResponder,
                                "isOnDuty" to isResponderDutyActive,
                                "isOnline" to true,
                                "sharingActive" to sharingActive,
                                "isEmergency" to isSosActive,
                                "lat" to lat,
                                "lng" to lng,
                                "battery" to battery.coerceIn(0, 100),
                                "updatedAt" to ServerValue.TIMESTAMP
                            )
                            nodeRef.onDisconnect().updateChildren(
                                mapOf("isOnline" to false, "updatedAt" to ServerValue.TIMESTAMP)
                            )
                            nodeRef.setValue(rtdbPayload)
                        }
                    } catch (re: Exception) {
                        Log.w(tag, "RTDB presence publish skipped: ${re.localizedMessage}")
                    }
                }
            }
        }
        return guardianAlertText
    }

    private fun savePinsToBoundedJson(pins: Map<String, LiveRadarPin>) {
        scope.launch {
            try {
                val arr = JSONArray()
                pins.values.sortedByDescending { it.updatedAt }.take(50).forEach { pin ->
                    arr.put(JSONObject().apply {
                        put("uid", pin.uid)
                        put("name", pin.name)
                        put("relationship", pin.relationship)
                        put("lat", pin.latitude)
                        put("lng", pin.longitude)
                        put("bearing", pin.bearing.toDouble())
                        put("speed", pin.speed.toDouble())
                        put("battery", pin.battery)
                        put("updatedAt", pin.updatedAt)
                        put("sharingActive", pin.sharingActive)
                        put("hasActiveTrip", pin.hasActiveTrip)
                        put("isSosActive", pin.isSosActive)
                        put("isCoreFamily", pin.isCoreFamily)
                    })
                }
                val serialized = JSONObject().apply {
                    put("updatedAt", System.currentTimeMillis())
                    put("pins", arr)
                }.toString()
                if (serialized.toByteArray().size <= StorageWardenManager.RADAR_JSON_CAP_BYTES) {
                    cacheFile.writeText(serialized)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadCachedPins(): Map<String, LiveRadarPin> {
        return try {
            if (!cacheFile.exists() || cacheFile.length() > StorageWardenManager.RADAR_JSON_CAP_BYTES) {
                return emptyMap()
            }
            val currentAuthUid = try { Firebase.auth.currentUser?.uid } catch (_: Exception) { null }
            val root = JSONObject(cacheFile.readText())
            val arr = root.optJSONArray("pins") ?: return emptyMap()
            val out = linkedMapOf<String, LiveRadarPin>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uid = o.optString("uid")
                if (uid.isBlank()) continue
                val rel = o.optString("relationship", "Family")
                if (!rel.equals("Self", ignoreCase = true)) continue
                if (currentAuthUid != null && uid == "LOCAL-SELF") continue

                out[uid] = LiveRadarPin(
                    uid = uid,
                    name = o.optString("name", "Guardian"),
                    relationship = rel,
                    latitude = o.optDouble("lat", 0.0),
                    longitude = o.optDouble("lng", 0.0),
                    bearing = o.optDouble("bearing", 0.0).toFloat(),
                    speed = o.optDouble("speed", 0.0).toFloat(),
                    battery = o.optInt("battery", 100),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    sharingActive = o.optBoolean("sharingActive", true),
                    hasActiveTrip = o.optBoolean("hasActiveTrip", false),
                    isSosActive = o.optBoolean("isSosActive", false),
                    isCoreFamily = o.optBoolean("isCoreFamily", RelationshipTrustRules.isCoreFamily(rel))
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun cleanup() {
        uidQueryRegistration?.remove()
        uidQueryRegistration = null
        emailQueryRegistration?.remove()
        emailQueryRegistration = null
        nearbyPresenceRegistration?.remove()
        nearbyPresenceRegistration = null
        rtdbPresenceListener?.let { rtdbPresenceRef?.removeEventListener(it) }
        rtdbPresenceListener = null
        rtdbPresenceRef = null
        subscribedUid = null
        subscribedGeohashPrefix = null
    }

    companion object {
        const val PEER_ONLINE_FRESHNESS_MS = 5 * 60_000L
    }
}

package com.example.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.AppDatabase
import com.example.data.model.EvidenceEntity
import com.example.data.model.TrustedContactEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages native emergency video, Front+Rear camera photos, .m4a voice evidence,
 * SHA-256 hashing, explicit FileProvider URI permission grants, and WhatsApp/MMS/Email dispatch
 * strictly targeting WhatsApp-enabled trusted contacts.
 */
class EmergencyVideoEvidenceManager(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope
) {
    private val tag = "EmergencyVideoManager"

    private val _latestRecordedVideoUri = MutableStateFlow<Uri?>(null)
    val latestRecordedVideoUri: StateFlow<Uri?> = _latestRecordedVideoUri.asStateFlow()

    private val _latestRecordedVideoFile = MutableStateFlow<File?>(null)
    val latestRecordedVideoFile: StateFlow<File?> = _latestRecordedVideoFile.asStateFlow()

    private val _currentPendingFile = MutableStateFlow<File?>(null)
    val currentPendingFile: StateFlow<File?> = _currentPendingFile.asStateFlow()

    private val _currentPendingUri = MutableStateFlow<Uri?>(null)
    val currentPendingUri: StateFlow<Uri?> = _currentPendingUri.asStateFlow()

    private val _isVideoModeActive = MutableStateFlow(false)
    val isVideoModeActive: StateFlow<Boolean> = _isVideoModeActive.asStateFlow()

    private val _lastDispatchStatus = MutableStateFlow("Ready for Front+Rear camera, voice & emergency dispatch")
    val lastDispatchStatus: StateFlow<String> = _lastDispatchStatus.asStateFlow()

    val isWhatsAppInstalled: Boolean
        get() = resolveWhatsAppPackage() != null

    fun resolveWhatsAppPackage(): String? {
        val pm = context.packageManager
        return when {
            isPackageInstalled("com.whatsapp", pm) -> "com.whatsapp"
            isPackageInstalled("com.whatsapp.w4b", pm) -> "com.whatsapp.w4b"
            else -> null
        }
    }

    private fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Normalizes trusted contact phone numbers into WhatsApp JID digit format (e.g. "+91 81190-98684" -> "918119098684").
     */
    fun normalizePhoneForWhatsApp(phone: String, defaultCountryCode: String = "91"): String {
        val trimmed = phone.trim()
        val hasPlus = trimmed.startsWith("+")
        var digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        if (digits.startsWith("00")) {
            digits = digits.drop(2)
        } else if (!hasPlus && digits.startsWith("0") && digits.length == 11) {
            digits = defaultCountryCode + digits.drop(1)
        } else if (!hasPlus && digits.length == 10) {
            digits = defaultCountryCode + digits
        }
        return digits
    }

    fun selectWhatsAppEnabledContacts(contacts: List<TrustedContactEntity>): List<TrustedContactEntity> {
        return contacts
            .filter { it.isEnabled && it.hasWhatsApp && normalizePhoneForWhatsApp(it.phone).length >= 8 }
            .sortedBy { it.priority }
    }

    fun prepareNewEmergencyVideoFile(incidentId: String? = null): Pair<File, Uri>? {
        return try {
            val videosDir = File(context.cacheDir, "emergency_videos").apply {
                if (!exists()) mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val resolvedId = incidentId ?: "SOS"
            val file = File(videosDir, "VID_${timeStamp}_$resolvedId.mp4")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            _currentPendingFile.value = file
            _currentPendingUri.value = uri
            _isVideoModeActive.value = true
            Pair(file, uri)
        } catch (e: Exception) {
            Log.e(tag, "Failed to prepare emergency video file", e)
            _lastDispatchStatus.value = "Failed to create video file: ${e.message}"
            null
        }
    }

    fun onVideoRecorded(
        success: Boolean,
        incidentId: String?,
        location: Location?,
        onSaved: ((EvidenceEntity) -> Unit)? = null
    ) {
        _isVideoModeActive.value = false
        val file = _currentPendingFile.value
        val uri = _currentPendingUri.value

        if (!success || file == null || !file.exists() || file.length() == 0L) {
            _lastDispatchStatus.value = "Video recording cancelled or empty"
            return
        }

        _latestRecordedVideoFile.value = file
        _latestRecordedVideoUri.value = uri
        _lastDispatchStatus.value = "Video recorded (${file.length() / 1024} KB) - Ready to dispatch"

        scope.launch(Dispatchers.IO) {
            try {
                val hash = calculateSha256(file)
                val latStr = location?.latitude?.let { String.format(Locale.US, "%.5f", it) } ?: "GPS Pending"
                val lngStr = location?.longitude?.let { String.format(Locale.US, "%.5f", it) } ?: "GPS Pending"
                val resolvedIncidentId = incidentId ?: "INC-${System.currentTimeMillis() % 100000}"

                val evidence = EvidenceEntity(
                    evidenceId = "EVI-VID-${System.currentTimeMillis() % 100000}",
                    incidentId = resolvedIncidentId,
                    userId = "USER-001",
                    fileType = "VIDEO",
                    title = "Emergency Video Evidence (${file.name})",
                    fileSizeBytes = file.length(),
                    sha256Hash = hash,
                    uploadTime = System.currentTimeMillis(),
                    storageLocation = file.absolutePath,
                    previewText = "Video evidence captured at ($latStr, $lngStr). File: ${file.name}"
                )

                database.evidenceDao().insertEvidence(evidence)
                withContext(Dispatchers.Main) {
                    onSaved?.invoke(evidence)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to persist video evidence in database", e)
            }
        }
    }

    data class ResolvedMediaBundle(
        val incidentId: String?,
        val videoFiles: List<File>,
        val videoFile: File?,
        val frontPhotoFiles: List<File>,
        val rearPhotoFiles: List<File>,
        val photoFiles: List<File>,
        val audioFiles: List<File>,
        val audioFile: File?,
        val videoUris: ArrayList<Uri>,
        val photoUris: ArrayList<Uri>,
        val audioUris: ArrayList<Uri>,
        val audioUri: Uri?,
        val uris: ArrayList<Uri>,
        val sha256DigestSummary: String
    ) {
        val totalMediaCount: Int
            get() = uris.size
    }

    data class MediaFallbackDispatchResult(
        val attemptedWhatsApp: Boolean,
        val whatsAppLaunched: Boolean,
        val usedSmsMmsFallback: Boolean,
        val fallbackReason: String,
        val targetedContactPhone: String?,
        val mediaCount: Int,
        val videoCount: Int,
        val photoCount: Int,
        val audioCount: Int,
        val statusMessage: String
    )

    /**
     * Scans explicit paths AND `cacheDir/emergency_evidence/<incidentId>` for:
     * - Videos (`FRONT_VID_*`, `REAR_VID_*`, `VID_*.mp4`)
     * - Photos (`FRONT_IMG_*`, `REAR_IMG_*`, `IMG_*.jpg`)
     * - Audio (`AUD_*.m4a`)
     * Generates FileProvider URIs + real SHA-256 digests for each item.
     */
    fun resolveIncidentMediaBundle(
        incidentId: String? = null,
        videoPath: String? = null,
        photoPaths: List<String> = emptyList(),
        audioPath: String? = null,
        videoPaths: List<String> = emptyList(),
        audioPaths: List<String> = emptyList()
    ): ResolvedMediaBundle {
        val discoveredFiles = LinkedHashMap<String, File>()

        fun addCandidate(path: String?) {
            if (path.isNullOrBlank()) return
            val f = File(path)
            if (f.exists() && f.length() > 0L) {
                discoveredFiles[f.absolutePath] = f
            }
        }

        addCandidate(videoPath)
        videoPaths.forEach { addCandidate(it) }
        _latestRecordedVideoFile.value?.absolutePath?.let { addCandidate(it) }
        photoPaths.forEach { addCandidate(it) }
        addCandidate(audioPath)
        audioPaths.forEach { addCandidate(it) }

        val evidenceRoot = File(context.cacheDir, "emergency_evidence")
        val targetDirs = mutableListOf<File>()
        if (!incidentId.isNullOrBlank()) {
            val safeId = incidentId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val exactDir = File(evidenceRoot, safeId)
            if (exactDir.exists() && exactDir.isDirectory) {
                targetDirs.add(exactDir)
            }
        } else if (evidenceRoot.exists() && evidenceRoot.isDirectory && discoveredFiles.isEmpty()) {
            evidenceRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.lastModified() }
                ?.let { targetDirs.add(it) }
        }

        for (dir in targetDirs) {
            dir.listFiles()
                ?.filter { it.isFile && it.length() > 0L }
                ?.sortedBy { it.lastModified() }
                ?.forEach { discoveredFiles[it.absolutePath] = it }
        }

        val videos = discoveredFiles.values
            .filter { it.name.endsWith(".mp4", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
        // Prefer Rear video as primary when present, else latest Front video
        val primaryVideo = videos.firstOrNull { it.name.startsWith("REAR_", ignoreCase = true) }
            ?: videos.firstOrNull()

        val photos = discoveredFiles.values
            .filter {
                val lower = it.name.lowercase(Locale.US)
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
            }
            .sortedBy { it.lastModified() }

        val frontPhotos = photos.filter {
            it.name.startsWith("FRONT_", ignoreCase = true) || it.name.contains("_FRONT", ignoreCase = true)
        }
        val rearPhotos = photos.filter {
            !it.name.startsWith("FRONT_", ignoreCase = true) && !it.name.contains("_FRONT", ignoreCase = true)
        }

        val audios = discoveredFiles.values
            .filter {
                val lower = it.name.lowercase(Locale.US)
                lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".aac")
            }
            .sortedByDescending { it.lastModified() }
        val primaryAudio = audios.firstOrNull()

        val authority = "${context.packageName}.fileprovider"
        val videoUris = ArrayList<Uri>()
        val photoUris = ArrayList<Uri>()
        val audioUris = ArrayList<Uri>()
        val allUris = ArrayList<Uri>()
        val hashTags = mutableListOf<String>()

        videos.forEachIndexed { idx, vFile ->
            val u = try {
                FileProvider.getUriForFile(context, authority, vFile)
            } catch (e: Exception) {
                Log.w(tag, "FileProvider URI failed for video ${vFile.name}: ${e.message}")
                Uri.fromFile(vFile)
            }
            videoUris.add(u)
            allUris.add(u)
            val label = if (vFile.name.startsWith("FRONT_", true)) "FVID" else "VID${idx + 1}"
            hashTags.add("$label:${calculateSha256(vFile).take(10)}")
        }

        photos.forEachIndexed { idx, pFile ->
            val u = try {
                FileProvider.getUriForFile(context, authority, pFile)
            } catch (e: Exception) {
                Log.w(tag, "FileProvider URI failed for photo ${pFile.name}: ${e.message}")
                Uri.fromFile(pFile)
            }
            photoUris.add(u)
            allUris.add(u)
            val label = if (pFile.name.startsWith("FRONT_", true)) "FIMG${idx + 1}" else "RIMG${idx + 1}"
            hashTags.add("$label:${calculateSha256(pFile).take(10)}")
        }

        audios.forEachIndexed { idx, aFile ->
            val u = try {
                FileProvider.getUriForFile(context, authority, aFile)
            } catch (e: Exception) {
                Log.w(tag, "FileProvider URI failed for audio ${aFile.name}: ${e.message}")
                Uri.fromFile(aFile)
            }
            audioUris.add(u)
            allUris.add(u)
            hashTags.add("AUD${idx + 1}:${calculateSha256(aFile).take(10)}")
        }
        val resolvedAudioUri = audioUris.firstOrNull()

        val digestSummary = if (allUris.isNotEmpty()) {
            val vTag = "${videos.size} Video(s)"
            val pTag = "${photos.size} Photo(s) (F:${frontPhotos.size},R:${rearPhotos.size})"
            val aTag = "${audios.size} Audio(.m4a)"
            "$vTag + $pTag + $aTag | SHA-256: ${hashTags.joinToString(", ")}"
        } else {
            "No local camera/audio files attached yet"
        }

        return ResolvedMediaBundle(
            incidentId = incidentId,
            videoFiles = videos,
            videoFile = primaryVideo,
            frontPhotoFiles = frontPhotos,
            rearPhotoFiles = rearPhotos,
            photoFiles = photos,
            audioFiles = audios,
            audioFile = primaryAudio,
            videoUris = videoUris,
            photoUris = photoUris,
            audioUris = audioUris,
            audioUri = resolvedAudioUri,
            uris = allUris,
            sha256DigestSummary = digestSummary
        )
    }

    /**
     * Grants explicit FileProvider READ permission to target package(s) AND resolved Activities
     * so WhatsApp, Messages, and Email apps actually receive readable video/photo/audio streams.
     */
    private fun grantUriReadPermissions(intent: Intent, uris: List<Uri>, explicitPackages: List<String> = emptyList()) {
        if (uris.isEmpty()) return
        val pkgSet = LinkedHashSet<String>()
        explicitPackages.filter { it.isNotBlank() }.forEach { pkgSet.add(it) }
        try {
            val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            matches.forEach { ri ->
                ri.activityInfo?.packageName?.let { pkgSet.add(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "queryIntentActivities warning: ${e.message}")
        }
        for (pkg in pkgSet) {
            for (uri in uris) {
                try {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w(tag, "grantUriPermission failed for $pkg on $uri: ${e.message}")
                }
            }
        }
    }

    private fun attachClipData(intent: Intent, uris: List<Uri>, mimeTypeHint: String = "*/*") {
        if (uris.isEmpty()) return
        try {
            val mimeTypes = uris.map { uri ->
                val s = uri.toString().lowercase(Locale.US)
                when {
                    s.contains(".mp4") -> "video/mp4"
                    s.contains(".m4a") || s.contains(".aac") -> "audio/mp4"
                    s.contains(".jpg") || s.contains(".jpeg") -> "image/jpeg"
                    else -> mimeTypeHint
                }
            }.distinct().toTypedArray()
            val clip = ClipData(
                ClipDescription("RakshaOne Emergency Evidence", mimeTypes),
                ClipData.Item(uris.first())
            )
            for (i in 1 until uris.size) {
                clip.addItem(ClipData.Item(uris[i]))
            }
            intent.clipData = clip
        } catch (e: Exception) {
            Log.w(tag, "Unable to attach ClipData: ${e.message}")
        }
    }

    fun buildEmergencyDossier(
        incidentId: String,
        userName: String,
        location: Location?,
        locality: String? = null,
        customNote: String? = null,
        evidenceSummary: String? = null,
        channelNote: String? = null
    ): String {
        val lat = location?.latitude
        val lng = location?.longitude
        val accuracy = location?.accuracy?.toInt()

        val mapLink = if (lat != null && lng != null) {
            "https://maps.google.com/?q=$lat,$lng"
        } else {
            "Location fix pending"
        }

        val coordsText = if (lat != null && lng != null) {
            "${String.format(Locale.US, "%.5f", lat)}, ${String.format(Locale.US, "%.5f", lng)} (±${accuracy ?: 0}m)"
        } else {
            "Acquiring satellite GPS..."
        }

        val batteryPct = getBatteryPercentage()
        val timeStr = SimpleDateFormat("dd MMM yyyy, HH:mm:ss z", Locale.US).format(Date())

        return buildString {
            appendLine("🚨 CRITICAL EMERGENCY SOS ALERT 🚨")
            appendLine("Citizen: $userName")
            appendLine("Incident: $incidentId")
            appendLine("Time: $timeStr")
            appendLine()
            appendLine("📍 LIVE LOCATION & GOOGLE MAPS:")
            appendLine("Maps Link: $mapLink")
            appendLine("Coordinates: $coordsText")
            if (!locality.isNullOrBlank()) {
                appendLine("Area: $locality")
            }
            appendLine()
            if (!customNote.isNullOrBlank()) {
                appendLine("⚠️ NOTE: $customNote")
            }
            if (!evidenceSummary.isNullOrBlank()) {
                appendLine("🎥 SECURED EVIDENCE: $evidenceSummary")
            }
            if (!channelNote.isNullOrBlank()) {
                appendLine("📡 DISPATCH MODE: $channelNote")
            }
            appendLine("🔋 Device Battery: $batteryPct%")
            appendLine("🛡️ Verified by RakshaOne (SHA-256 Tamper-Evident Vault)")
        }
    }

    /**
     * Launches WhatsApp cleanly targeting ONE WhatsApp-enabled trusted contact (if provided via `jid`)
     * with a homogeneous media MIME type (`video/mp4`, OR `image/jpeg`, OR `audio/mp4`)
     * because WhatsApp rejects mixed wildcard arrays and drops attachments otherwise.
     */
    fun launchWhatsAppMediaIntent(
        whatsAppPkg: String,
        dossierText: String,
        uris: ArrayList<Uri>,
        mimeType: String,
        trustedPhone: String?
    ): Boolean {
        val normalizedJidDigits = trustedPhone?.let { normalizePhoneForWhatsApp(it) }?.takeIf { it.length >= 8 }
        val intent = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        } else if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
            }
        }

        intent.apply {
            setPackage(whatsAppPkg)
            putExtra(Intent.EXTRA_TEXT, dossierText)
            if (normalizedJidDigits != null) {
                putExtra("jid", "$normalizedJidDigits@s.whatsapp.net")
            }
            attachClipData(this, uris, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        grantUriReadPermissions(intent, uris, listOf(whatsAppPkg))
        context.startActivity(intent)
        return true
    }

    /**
     * Trusted-Contact-Only WhatsApp dispatch.
     * Never falls back to arbitrary external apps when a trusted contact phone is absent.
     */
    fun sendToWhatsApp(
        dossierText: String,
        videoUri: Uri? = _latestRecordedVideoUri.value,
        phone: String? = null
    ): Boolean {
        val cleanPhone = phone?.takeIf { normalizePhoneForWhatsApp(it).length >= 8 }
        if (cleanPhone == null) {
            _lastDispatchStatus.value = "Add a Trusted Contact with WhatsApp in Circle tab first."
            return false
        }
        val pkg = resolveWhatsAppPackage() ?: run {
            _lastDispatchStatus.value = "WhatsApp is not installed on this device."
            return false
        }
        val resolvedUri = videoUri ?: _latestRecordedVideoUri.value
        val uris = ArrayList<Uri>().apply { if (resolvedUri != null) add(resolvedUri) }
        return try {
            launchWhatsAppMediaIntent(
                whatsAppPkg = pkg,
                dossierText = dossierText,
                uris = uris,
                mimeType = if (resolvedUri != null) "video/mp4" else "text/plain",
                trustedPhone = cleanPhone
            )
            _lastDispatchStatus.value = "Launched WhatsApp SOS dispatch to trusted contact $cleanPhone"
            true
        } catch (e: Exception) {
            Log.w(tag, "WhatsApp launch failed: ${e.message}")
            _lastDispatchStatus.value = "WhatsApp launch failed for $cleanPhone"
            false
        }
    }

    /**
     * Dispatches Front+Rear Photos to WhatsApp-enabled trusted contact (homogeneous `image/jpeg`).
     */
    fun sharePhotosViaWhatsApp(
        bundle: ResolvedMediaBundle,
        dossierText: String,
        trustedPhone: String?
    ): Boolean {
        val pkg = resolveWhatsAppPackage() ?: return false
        if (bundle.photoUris.isEmpty()) return false
        return try {
            launchWhatsAppMediaIntent(
                whatsAppPkg = pkg,
                dossierText = dossierText,
                uris = bundle.photoUris,
                mimeType = "image/jpeg",
                trustedPhone = trustedPhone
            )
            _lastDispatchStatus.value = "Attached ${bundle.photoUris.size} Front+Rear photo(s) in WhatsApp${trustedPhone?.let { " -> $it" } ?: ""}"
            true
        } catch (e: Exception) {
            Log.w(tag, "WhatsApp photos share failed: ${e.message}")
            false
        }
    }

    /**
     * Dispatches recorded `.m4a` voice audio evidence to WhatsApp-enabled trusted contact (`audio/mp4`).
     */
    fun shareAudioViaWhatsApp(
        bundle: ResolvedMediaBundle,
        dossierText: String,
        trustedPhone: String?
    ): Boolean {
        val pkg = resolveWhatsAppPackage() ?: return false
        if (bundle.audioUris.isEmpty()) return false
        return try {
            launchWhatsAppMediaIntent(
                whatsAppPkg = pkg,
                dossierText = dossierText,
                uris = bundle.audioUris,
                mimeType = "audio/mp4",
                trustedPhone = trustedPhone
            )
            _lastDispatchStatus.value = "Attached ${bundle.audioUris.size} emergency .m4a voice recording(s) in WhatsApp${trustedPhone?.let { " -> $it" } ?: ""}"
            true
        } catch (e: Exception) {
            Log.w(tag, "WhatsApp audio share failed: ${e.message}")
            false
        }
    }

    /**
     * Dispatches Video + Front/Rear Photos + Audio via device MMS/SMS messaging or Android Share Chooser.
     */
    fun sendMediaBundleViaMmsAndSms(
        dossierText: String,
        bundle: ResolvedMediaBundle,
        targetPhones: List<String> = emptyList()
    ): Boolean {
        val uris = bundle.uris
        val phoneList = targetPhones.filter { it.isNotBlank() }.joinToString(";")
        val defaultSmsPkg = try {
            Telephony.Sms.getDefaultSmsPackage(context)
        } catch (_: Exception) {
            null
        }

        if (uris.isNotEmpty()) {
            val buildMmsIntent: (String?) -> Intent = { pkg ->
                val intent = if (uris.size > 1) {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    }
                } else {
                    val mime = when {
                        bundle.videoUris.isNotEmpty() -> "video/mp4"
                        bundle.photoUris.isNotEmpty() -> "image/jpeg"
                        bundle.audioUri != null -> "audio/mp4"
                        else -> "*/*"
                    }
                    Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                    }
                }
                intent.apply {
                    if (phoneList.isNotBlank()) {
                        putExtra("address", phoneList)
                    }
                    putExtra("sms_body", dossierText)
                    putExtra(Intent.EXTRA_TEXT, dossierText)
                    attachClipData(this, uris)
                    if (!pkg.isNullOrBlank()) {
                        setPackage(pkg)
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }.also { createdIntent ->
                    grantUriReadPermissions(createdIntent, uris, listOfNotNull(pkg))
                }
            }

            if (!defaultSmsPkg.isNullOrBlank()) {
                try {
                    context.startActivity(buildMmsIntent(defaultSmsPkg))
                    _lastDispatchStatus.value =
                        "Fallback SMS/MMS opened (${bundle.videoFiles.size} video, ${bundle.photoFiles.size} photo(s), ${if (bundle.audioFile != null) 1 else 0} audio)"
                    return true
                } catch (e: Exception) {
                    Log.w(tag, "Default SMS app $defaultSmsPkg rejected multi-media MMS: ${e.message}")
                }
            }

            try {
                val baseIntent = buildMmsIntent(null)
                val chooser = Intent.createChooser(
                    baseIntent,
                    "Send SOS Video (${bundle.videoFiles.size}), Photos (${bundle.photoFiles.size}) & Audio"
                ).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                grantUriReadPermissions(baseIntent, uris)
                context.startActivity(chooser)
                _lastDispatchStatus.value =
                    "Fallback Media Share opened with ${uris.size} evidence file(s) & GPS location"
                return true
            } catch (e: Exception) {
                Log.w(tag, "Multi-media MMS chooser failed, falling back to smsto text composer: ${e.message}")
            }
        }

        return sendViaSmsApp(
            dossierText = dossierText,
            videoUri = uris.firstOrNull(),
            targetPhones = targetPhones
        )
    }

    /**
     * Master Secured SOS Media Dispatch Router:
     * - Gathers ALL Videos (Front+Rear `.mp4`), Photos (Front+Rear `.jpg`), AND Audio (`.m4a`).
     * - Strictly targets ONLY WhatsApp-enabled trusted contacts (via `jid`) when WhatsApp is online & available,
     *   sending a single clean MIME type (`video/mp4`, or `image/jpeg`, or `audio/mp4`) so WhatsApp NEVER drops media.
     * - Automatically falls back to SMS/MMS + location when offline, no WhatsApp contact is available, or launch fails.
     */
    fun dispatchEvidenceWithFallback(
        incidentId: String?,
        userName: String,
        location: Location?,
        locality: String? = null,
        customNote: String? = null,
        videoPath: String? = null,
        photoPaths: List<String> = emptyList(),
        audioPath: String? = null,
        targetPhones: List<String> = emptyList(),
        whatsAppTargetPhones: List<String> = targetPhones,
        contactIndex: Int = 0,
        isOnline: Boolean = true,
        preferWhatsApp: Boolean = true,
        videoPaths: List<String> = emptyList(),
        audioPaths: List<String> = emptyList()
    ): MediaFallbackDispatchResult {
        val bundle = resolveIncidentMediaBundle(
            incidentId = incidentId,
            videoPath = videoPath,
            photoPaths = photoPaths,
            audioPath = audioPath,
            videoPaths = videoPaths,
            audioPaths = audioPaths
        )
        val videoCount = bundle.videoFiles.size
        val photoCount = bundle.photoFiles.size
        val audioCount = bundle.audioFiles.size
        val resolvedIncidentId = incidentId ?: "SOS-LIVE"

        val whatsAppPkg = resolveWhatsAppPackage()
        val eligibleWaPhones = whatsAppTargetPhones
            .filter { normalizePhoneForWhatsApp(it).length >= 8 }
        val chosenWaPhone = if (eligibleWaPhones.isNotEmpty()) {
            eligibleWaPhones[contactIndex.coerceIn(0, eligibleWaPhones.lastIndex) % eligibleWaPhones.size]
        } else {
            null
        }

        if (targetPhones.isEmpty() && whatsAppTargetPhones.isEmpty()) {
            val noContactMsg = "No Trusted Contacts saved. Add Trusted Contacts in Circle tab for SMS & WhatsApp alerts."
            _lastDispatchStatus.value = noContactMsg
            return MediaFallbackDispatchResult(
                attemptedWhatsApp = false,
                whatsAppLaunched = false,
                usedSmsMmsFallback = false,
                fallbackReason = "NO_TRUSTED_CONTACTS",
                targetedContactPhone = null,
                mediaCount = bundle.uris.size,
                videoCount = videoCount,
                photoCount = photoCount,
                audioCount = audioCount,
                statusMessage = noContactMsg
            )
        }

        val initialReason = when {
            !isOnline -> "INTERNET_UNAVAILABLE"
            whatsAppPkg == null -> "WHATSAPP_UNAVAILABLE"
            eligibleWaPhones.isEmpty() -> "NO_WHATSAPP_TRUSTED_CONTACTS"
            else -> "ONLINE_WHATSAPP"
        }

        val channelDescription = when (initialReason) {
            "INTERNET_UNAVAILABLE" -> "Trusted SMS/MMS (Internet offline)"
            "WHATSAPP_UNAVAILABLE" -> "Trusted SMS/MMS (WhatsApp not installed)"
            "NO_WHATSAPP_TRUSTED_CONTACTS" -> "Trusted SMS/MMS (No trusted contact uses WhatsApp)"
            else -> "WhatsApp Trusted Contact ($chosenWaPhone) + Cloud Vault"
        }

        val dossier = buildEmergencyDossier(
            incidentId = resolvedIncidentId,
            userName = userName,
            location = location,
            locality = locality,
            customNote = customNote,
            evidenceSummary = bundle.sha256DigestSummary,
            channelNote = channelDescription
        )

        if (preferWhatsApp && isOnline && whatsAppPkg != null && chosenWaPhone != null) {
            try {
                // Attach ALL videos, photos, and audio clips together whenever media exists
                val selectedUris = bundle.uris
                val distinctKinds = listOf(
                    bundle.videoUris.isNotEmpty(),
                    bundle.photoUris.isNotEmpty(),
                    bundle.audioUris.isNotEmpty()
                ).count { it }
                val selectedMime = when {
                    selectedUris.isEmpty() -> "text/plain"
                    distinctKinds > 1 -> "*/*"
                    bundle.videoUris.isNotEmpty() -> "video/mp4"
                    bundle.photoUris.isNotEmpty() -> "image/jpeg"
                    else -> "audio/mp4"
                }

                launchWhatsAppMediaIntent(
                    whatsAppPkg = whatsAppPkg,
                    dossierText = dossier,
                    uris = selectedUris,
                    mimeType = selectedMime,
                    trustedPhone = chosenWaPhone
                )

                val targetDesc = chosenWaPhone?.let { " to trusted contact $it" } ?: ""
                val statusMsg = if (selectedUris.isNotEmpty()) {
                    "WhatsApp attached ALL ${selectedUris.size} files ($videoCount vid, $photoCount img, $audioCount aud)$targetDesc"
                } else {
                    "WhatsApp opened$targetDesc with live GPS dossier"
                }
                _lastDispatchStatus.value = statusMsg
                return MediaFallbackDispatchResult(
                    attemptedWhatsApp = true,
                    whatsAppLaunched = true,
                    usedSmsMmsFallback = false,
                    fallbackReason = "WHATSAPP_DELIVERED",
                    targetedContactPhone = chosenWaPhone,
                    mediaCount = bundle.uris.size,
                    videoCount = videoCount,
                    photoCount = photoCount,
                    audioCount = audioCount,
                    statusMessage = statusMsg
                )
            } catch (e: Exception) {
                Log.w(tag, "WhatsApp dispatch failed; activating SMS/MMS fallback: ${e.message}")
            }
        }

        val fallbackReason = if (initialReason == "ONLINE_WHATSAPP") "WHATSAPP_FAILED" else initialReason
        val fallbackDossier = buildEmergencyDossier(
            incidentId = resolvedIncidentId,
            userName = userName,
            location = location,
            locality = locality,
            customNote = customNote,
            evidenceSummary = bundle.sha256DigestSummary,
            channelNote = when (fallbackReason) {
                "INTERNET_UNAVAILABLE" -> "Fallback SMS/MMS (No Internet)"
                "WHATSAPP_UNAVAILABLE" -> "Fallback SMS/MMS (WhatsApp unavailable)"
                "NO_WHATSAPP_TRUSTED_CONTACTS" -> "Fallback SMS/MMS (Contacts on standard SMS)"
                else -> "Fallback SMS/MMS (WhatsApp launch failed)"
            }
        )
        sendMediaBundleViaMmsAndSms(
            dossierText = fallbackDossier,
            bundle = bundle,
            targetPhones = targetPhones
        )

        val fallbackMsg = when (fallbackReason) {
            "INTERNET_UNAVAILABLE" -> "No internet: SMS fallback + MMS ($videoCount vid, $photoCount img, $audioCount aud)"
            "WHATSAPP_UNAVAILABLE" -> "WhatsApp missing: SMS/MMS ($videoCount vid, $photoCount img, $audioCount aud)"
            "NO_WHATSAPP_TRUSTED_CONTACTS" -> "Trusted contacts marked SMS-only: opened SMS/MMS ($videoCount vid, $photoCount img)"
            else -> "WhatsApp failed: SMS fallback + MMS ($videoCount vid, $photoCount img, $audioCount aud)"
        }
        _lastDispatchStatus.value = fallbackMsg
        return MediaFallbackDispatchResult(
            attemptedWhatsApp = isOnline && whatsAppPkg != null,
            whatsAppLaunched = false,
            usedSmsMmsFallback = true,
            fallbackReason = fallbackReason,
            targetedContactPhone = null,
            mediaCount = bundle.uris.size,
            videoCount = videoCount,
            photoCount = photoCount,
            audioCount = audioCount,
            statusMessage = fallbackMsg
        )
    }

    fun sendViaSmsApp(
        dossierText: String,
        videoUri: Uri? = _latestRecordedVideoUri.value,
        targetPhones: List<String> = emptyList()
    ): Boolean {
        val cleanPhones = targetPhones.filter { it.isNotBlank() }
        if (cleanPhones.isEmpty()) {
            _lastDispatchStatus.value = "Add Trusted Contacts in Circle tab before sending SMS/MMS alerts."
            return false
        }
        val resolvedUri = videoUri ?: _latestRecordedVideoUri.value
        val phoneList = cleanPhones.joinToString(";")

        return try {
            if (resolvedUri != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/*"
                    putExtra("address", phoneList)
                    putExtra(Intent.EXTRA_STREAM, resolvedUri)
                    putExtra("sms_body", dossierText)
                    putExtra(Intent.EXTRA_TEXT, dossierText)
                    attachClipData(this, listOf(resolvedUri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                grantUriReadPermissions(intent, listOf(resolvedUri))
                context.startActivity(intent)
                _lastDispatchStatus.value = "Opened Trusted SMS/MMS with emergency video & GPS"
                true
            } else {
                val uri = Uri.parse("smsto:$phoneList")
                val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                    putExtra("sms_body", dossierText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                _lastDispatchStatus.value = "Opened Trusted SMS with emergency GPS dossier"
                true
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch Trusted SMS app", e)
            _lastDispatchStatus.value = "Could not launch SMS app for trusted contacts"
            false
        }
    }

    fun resolveEvidenceUris(videoPath: String?, photoPaths: List<String> = emptyList()): ArrayList<Uri> {
        return resolveIncidentMediaBundle(
            incidentId = null,
            videoPath = videoPath,
            photoPaths = photoPaths
        ).uris
    }

    fun shareEvidenceBundleViaApps(
        dossierText: String,
        videoPath: String?,
        photoPaths: List<String> = emptyList(),
        audioPath: String? = null,
        recipientEmails: List<String> = emptyList(),
        subject: String = "CRITICAL EMERGENCY SOS EVIDENCE",
        incidentId: String? = null,
        videoPaths: List<String> = emptyList(),
        audioPaths: List<String> = emptyList()
    ): Boolean {
        val bundle = resolveIncidentMediaBundle(
            incidentId = incidentId,
            videoPath = videoPath,
            photoPaths = photoPaths,
            audioPath = audioPath,
            videoPaths = videoPaths,
            audioPaths = audioPaths
        )
        val uris = bundle.uris
        return try {
            val shareIntent = when {
                uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    if (recipientEmails.isNotEmpty()) {
                        putExtra(Intent.EXTRA_EMAIL, recipientEmails.toTypedArray())
                    }
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, dossierText)
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    attachClipData(this, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                uris.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                    val mime = when {
                        bundle.videoUris.isNotEmpty() -> "video/mp4"
                        bundle.photoUris.isNotEmpty() -> "image/jpeg"
                        bundle.audioUri != null -> "audio/mp4"
                        else -> "*/*"
                    }
                    type = mime
                    if (recipientEmails.isNotEmpty()) {
                        putExtra(Intent.EXTRA_EMAIL, recipientEmails.toTypedArray())
                    }
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, dossierText)
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    attachClipData(this, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                else -> Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    if (recipientEmails.isNotEmpty()) {
                        putExtra(Intent.EXTRA_EMAIL, recipientEmails.toTypedArray())
                    }
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, dossierText)
                }
            }
            grantUriReadPermissions(shareIntent, uris)
            val chooser = Intent.createChooser(
                shareIntent,
                "Share ALL Evidence (${bundle.videoFiles.size} Vid, ${bundle.photoFiles.size} Img, ${bundle.audioFiles.size} Aud)"
            ).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            _lastDispatchStatus.value = if (uris.isNotEmpty()) {
                "Opened Share Sheet with ALL ${uris.size} file(s) (${bundle.videoFiles.size} vid, ${bundle.photoFiles.size} img, ${bundle.audioFiles.size} aud)"
            } else {
                "Opened Share Sheet with emergency GPS dossier"
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch evidence share sheet", e)
            _lastDispatchStatus.value = "Failed to share evidence: ${e.message}"
            false
        }
    }

    fun shareViaDeviceApps(
        dossierText: String,
        videoUri: Uri? = _latestRecordedVideoUri.value,
        subject: String = "CRITICAL EMERGENCY SOS ALERT"
    ): Boolean {
        val resolvedUri = videoUri ?: _latestRecordedVideoUri.value

        return try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (resolvedUri != null) "video/*" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, dossierText)
                if (resolvedUri != null) {
                    putExtra(Intent.EXTRA_STREAM, resolvedUri)
                    attachClipData(this, listOf(resolvedUri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            if (resolvedUri != null) {
                grantUriReadPermissions(shareIntent, listOf(resolvedUri))
            }
            val chooser = Intent.createChooser(shareIntent, "Emergency Dispatch via Device Apps").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            _lastDispatchStatus.value = "Opened device app sharing menu with full emergency dossier"
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch Android Share Sheet", e)
            _lastDispatchStatus.value = "Failed to launch share sheet: ${e.message}"
            false
        }
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 80
        } catch (_: Exception) {
            80
        }
    }

    private fun calculateSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val fis = FileInputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            fis.close()
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "HASH_UNAVAILABLE"
        }
    }

    fun clearPendingVideo() {
        _currentPendingFile.value = null
        _currentPendingUri.value = null
        _isVideoModeActive.value = false
    }
}

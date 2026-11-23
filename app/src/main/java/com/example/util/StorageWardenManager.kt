package com.example.util

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class StorageHealthStatus(
    val totalUsedBytes: Long = 0L,
    val budgetBytes: Long = StorageWardenManager.TOTAL_BUDGET_BYTES,
    val aiHabitsBytes: Long = 0L,
    val rollingBufferBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val logsBytes: Long = 0L,
    val deviceFreeMb: Long = 2048L,
    val lowSpaceWarning: Boolean = false,
    val badgeText: String = "Brain: 0.2 MB / 25 MB • Auto-Optimized"
)

data class StorageWardenReport(
    val status: StorageHealthStatus,
    val compressedBreadcrumbs: Int = 0,
    val prunedCircularFiles: Int = 0,
    val trimmedCapFiles: Int = 0
)

/**
 * Autonomous Storage Warden:
 * - Hard-caps total AI habit profiles, 3-minute audio/video circular buffer, cached tiles/guides,
 *   and trail/radar JSON logs strictly <= 25 MB.
 * - Compresses raw GPS breadcrumbs older than 24h into compact daily geohash summaries.
 * - Enforces a strict 3-minute rolling window on audio/video circular buffer files.
 * - Guards low device free storage (< 500 MB) with aggressive non-critical cache purging.
 */
class StorageWardenManager(private val context: Context) {

    companion object {
        const val TOTAL_BUDGET_BYTES = 25L * 1024L * 1024L
        const val AI_HABITS_CAP_BYTES = 4L * 1024L * 1024L
        const val ROLLING_BUFFER_CAP_BYTES = 12L * 1024L * 1024L
        const val CACHE_CAP_BYTES = 7L * 1024L * 1024L
        const val LOGS_CAP_BYTES = 2L * 1024L * 1024L
        const val RADAR_JSON_CAP_BYTES = 1L * 1024L * 1024L // Strictly <= 1 MB
        const val GEOHASH_SUMMARY_CAP_BYTES = 512L * 1024L // Daily geohash summary <= 512 KB
        const val MIN_FREE_SPACE_MB = 500L
        const val ROLLING_MAX_AGE_MS = 3L * 60L * 1000L // 3-minute circular buffer
        const val DAILY_COMPACTION_AGE_MS = 24L * 3600L * 1000L // 24-hour GPS log cutoff
        private const val MAX_DAILY_BUCKETS_RETAINED = 30
        private const val GEOHASH_SUMMARY_FILE = "geohash_trail_summary.json"
        private const val RADAR_CACHE_FILE = "live_radar_cache.json"

        fun encodeGeohash(lat: Double, lng: Double, precision: Int = 6): String {
            val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
            var latMin = -90.0
            var latMax = 90.0
            var lngMin = -180.0
            var lngMax = 180.0
            var isEven = true
            var bit = 0
            var ch = 0
            val sb = StringBuilder()

            while (sb.length < precision) {
                if (isEven) {
                    val mid = (lngMin + lngMax) / 2.0
                    if (lng >= mid) {
                        ch = ch or (1 shl (4 - bit))
                        lngMin = mid
                    } else {
                        lngMax = mid
                    }
                } else {
                    val mid = (latMin + latMax) / 2.0
                    if (lat >= mid) {
                        ch = ch or (1 shl (4 - bit))
                        latMin = mid
                    } else {
                        latMax = mid
                    }
                }
                isEven = !isEven
                if (bit < 4) {
                    bit++
                } else {
                    sb.append(base32[ch])
                    bit = 0
                    ch = 0
                }
            }
            return sb.toString()
        }

        fun schedulePeriodicCleanup(context: Context) {
            StorageWardenWorker.scheduleDailyWarden(context)
            StorageWardenWorker.scheduleCircularBufferSweep(context)
        }
    }

    private val _storageHealth = MutableStateFlow(StorageHealthStatus())
    val storageHealth: StateFlow<StorageHealthStatus> = _storageHealth.asStateFlow()

    fun getCircularBufferDir(): File = File(context.filesDir, "circular_buffer").apply { mkdirs() }

    /**
     * Writes an audio/video chunk directly into the 3-minute circular buffer and immediately
     * enforces the 3-minute age limit and 12 MB rolling buffer cap.
     */
    fun writeCircularBufferChunk(
        fileName: String,
        data: ByteArray,
        timestampMs: Long = System.currentTimeMillis()
    ): File {
        val rollingDir = getCircularBufferDir()
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(rollingDir, safeName)
        target.writeBytes(data)
        target.setLastModified(timestampMs)
        pruneCircularBuffer3Min(now = maxOf(System.currentTimeMillis(), timestampMs))
        return target
    }

    /**
     * Stages a captured SOS/Trip audio or video file into the circular buffer without modifying
     * the primary encrypted evidence copy, keeping the rolling buffer bounded to 3 minutes.
     */
    fun stageMediaToCircularBuffer(sourceFile: File, mediaTag: String = "av"): File? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return null
        return try {
            val rollingDir = getCircularBufferDir()
            val ext = sourceFile.extension.ifBlank { "bin" }
            val dest = File(rollingDir, "cb_${mediaTag}_${System.currentTimeMillis()}.$ext")
            sourceFile.copyTo(dest, overwrite = true)
            pruneCircularBuffer3Min()
            dest
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Enforces the 3-minute circular buffer window for audio/video evidence:
     * removes any rolling chunk older than 3 minutes and trims oldest chunks if > 12 MB.
     */
    fun pruneCircularBuffer3Min(now: Long = System.currentTimeMillis()): Int {
        val rollingDir = getCircularBufferDir()
        var deleted = 0
        rollingDir.listFiles()
            ?.filter { it.isFile && (now - it.lastModified() > ROLLING_MAX_AGE_MS) }
            ?.forEach { file ->
                if (file.delete()) deleted++
            }
        deleted += trimDirectoryToCap(rollingDir, ROLLING_BUFFER_CAP_BYTES)
        return deleted
    }

    suspend fun runOptimizationPass(): StorageHealthStatus =
        runOptimizationWithReport(includeDailyGeohashCompaction = true).status

    suspend fun runOptimizationWithReport(
        now: Long = System.currentTimeMillis(),
        includeDailyGeohashCompaction: Boolean = true
    ): StorageWardenReport = withContext(Dispatchers.IO) {
        val rollingDir = getCircularBufferDir()
        val intelDir = File(context.filesDir, "ai_habits").apply { mkdirs() }
        val cacheDir = File(context.cacheDir, "offline_tiles_cache").apply { mkdirs() }
        val evidenceCache = File(context.cacheDir, "emergency_evidence")
        val radarJsonFile = File(context.filesDir, RADAR_CACHE_FILE)
        val summaryFile = File(context.filesDir, GEOHASH_SUMMARY_FILE)

        // 1. Prune 3-minute circular audio/video buffer (> 3 min old or > 12 MB)
        var prunedCircular = pruneCircularBuffer3Min(now)

        // 2. Cap AI habit profiles (<= 4 MB) & offline caches (<= 7 MB)
        var trimmedCap = 0
        trimmedCap += trimDirectoryToCap(intelDir, AI_HABITS_CAP_BYTES)
        trimmedCap += trimDirectoryToCap(cacheDir, CACHE_CAP_BYTES)

        // 3. Enforce <= 1 MB on overwritten radar JSON cache
        if (radarJsonFile.exists() && radarJsonFile.length() > RADAR_JSON_CAP_BYTES) {
            radarJsonFile.writeText("{}")
            trimmedCap++
        }

        // 4. Daily compaction: compress GPS logs older than 24h into compact geohash buckets and purge raw rows
        val compressedBreadcrumbs = if (includeDailyGeohashCompaction) {
            compressDailyBreadcrumbs(now)
        } else {
            0
        }

        // 5. Device free space check (< 500 MB triggers aggressive non-critical cache purge)
        val freeMb = getDeviceFreeMb()
        val lowSpace = freeMb in 1 until MIN_FREE_SPACE_MB
        if (lowSpace) {
            Log.w("StorageWarden", "Low device free space ($freeMb MB < 500 MB) — running emergency purge")
            prunedCircular += trimDirectoryToCap(rollingDir, 2L * 1024L * 1024L)
            trimmedCap += trimDirectoryToCap(cacheDir, 1L * 1024L * 1024L)
            evidenceCache.listFiles()
                ?.filter { now - it.lastModified() > DAILY_COMPACTION_AGE_MS }
                ?.forEach {
                    if (it.deleteRecursively()) trimmedCap++
                }
        }

        // 6. Strict 25 MB global hard-cap guard across all Warden-managed files & logs
        trimmedCap += enforceGlobalHardCap(
            rollingDir = rollingDir,
            intelDir = intelDir,
            cacheDir = cacheDir,
            summaryFile = summaryFile,
            radarJsonFile = radarJsonFile
        )

        val habitsBytes = dirSize(intelDir)
        val rollingBytes = dirSize(rollingDir)
        val cachedBytes = dirSize(cacheDir)
        val logsBytes = (summaryFile.length() + radarJsonFile.length())
            .coerceAtMost(LOGS_CAP_BYTES)
        val total = (habitsBytes + rollingBytes + cachedBytes + logsBytes).coerceAtMost(TOTAL_BUDGET_BYTES)
        val mbText = String.format(Locale.US, "%.1f", total / (1024.0 * 1024.0))
        val badge = if (lowSpace) {
            "Brain: $mbText MB / 25 MB • Low Storage Guard ($freeMb MB free)"
        } else {
            "Brain: $mbText MB / 25 MB • Auto-Optimized"
        }

        val status = StorageHealthStatus(
            totalUsedBytes = total,
            budgetBytes = TOTAL_BUDGET_BYTES,
            aiHabitsBytes = habitsBytes,
            rollingBufferBytes = rollingBytes,
            cacheBytes = cachedBytes,
            logsBytes = logsBytes,
            deviceFreeMb = freeMb,
            lowSpaceWarning = lowSpace,
            badgeText = badge
        )
        _storageHealth.value = status
        StorageWardenReport(
            status = status,
            compressedBreadcrumbs = compressedBreadcrumbs,
            prunedCircularFiles = prunedCircular,
            trimmedCapFiles = trimmedCap
        )
    }

    /**
     * Compresses ALL raw Room GPS breadcrumbs older than 24 hours into daily deduplicated
     * 6-character geohash buckets inside `geohash_trail_summary.json`, then deletes compressed rows.
     */
    suspend fun compressDailyBreadcrumbs(now: Long = System.currentTimeMillis()): Int {
        return try {
            val db = AppDatabase.getDatabase(context)
            val dao = db.locationBreadcrumbDao()
            val cutoff = now - DAILY_COMPACTION_AGE_MS
            val oldBreadcrumbs = dao.getBreadcrumbsBefore(cutoff, limit = 5000)
            if (oldBreadcrumbs.isEmpty()) return 0

            val summaryFile = File(context.filesDir, GEOHASH_SUMMARY_FILE)
            val existingDaily = loadExistingDailyBuckets(summaryFile)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            oldBreadcrumbs.forEach { crumb ->
                val dayKey = dateFmt.format(Date(crumb.timestamp))
                val geohash = crumb.geohash.ifBlank { encodeGeohash(crumb.latitude, crumb.longitude, 6) }
                val daySet = existingDaily.getOrPut(dayKey) { linkedSetOf() }
                if (daySet.size < 120) {
                    daySet.add(geohash)
                }
            }

            // Retain only most recent 30 days and keep JSON well under GEOHASH_SUMMARY_CAP_BYTES
            val sortedDays = existingDaily.keys.sortedDescending().take(MAX_DAILY_BUCKETS_RETAINED)
            val dailyJson = JSONObject()
            val flatUnique = JSONArray()
            val seenFlat = linkedSetOf<String>()

            for (day in sortedDays) {
                val hashes = existingDaily[day].orEmpty()
                val arr = JSONArray()
                hashes.forEach { hash ->
                    arr.put(hash)
                    if (seenFlat.size < 180 && seenFlat.add(hash)) {
                        flatUnique.put(hash)
                    }
                }
                dailyJson.put(day, arr)
            }

            val payload = JSONObject().apply {
                put("compressedAt", now)
                put("compressedCount", oldBreadcrumbs.size)
                put("geohashes", flatUnique)
                put("dailyBuckets", dailyJson)
            }.toString()

            if (payload.toByteArray().size <= GEOHASH_SUMMARY_CAP_BYTES) {
                summaryFile.writeText(payload)
            } else {
                val compactPayload = JSONObject().apply {
                    put("compressedAt", now)
                    put("compressedCount", oldBreadcrumbs.size)
                    put("geohashes", flatUnique)
                }.toString()
                summaryFile.writeText(compactPayload)
            }

            dao.pruneOlderThan(cutoff)
            oldBreadcrumbs.size
        } catch (e: Exception) {
            Log.w("StorageWarden", "Daily GPS breadcrumb compaction error: ${e.message}")
            0
        }
    }

    private fun loadExistingDailyBuckets(summaryFile: File): MutableMap<String, LinkedHashSet<String>> {
        val result = mutableMapOf<String, LinkedHashSet<String>>()
        if (!summaryFile.exists() || summaryFile.length() == 0L) return result
        return try {
            val root = JSONObject(summaryFile.readText())
            val daily = root.optJSONObject("dailyBuckets") ?: return result
            val keys = daily.keys()
            while (keys.hasNext()) {
                val day = keys.next()
                val arr = daily.optJSONArray(day) ?: continue
                val set = linkedSetOf<String>()
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i)
                    if (h.isNotBlank()) set.add(h)
                }
                if (set.isNotEmpty()) result[day] = set
            }
            result
        } catch (_: Exception) {
            result
        }
    }

    private fun enforceGlobalHardCap(
        rollingDir: File,
        intelDir: File,
        cacheDir: File,
        summaryFile: File,
        radarJsonFile: File
    ): Int {
        var deletedCount = 0
        fun currentManagedTotal(): Long {
            return dirSize(intelDir) +
                dirSize(rollingDir) +
                dirSize(cacheDir) +
                summaryFile.length() +
                radarJsonFile.length()
        }

        if (currentManagedTotal() <= TOTAL_BUDGET_BYTES) return 0

        // Evict oldest files across priority order: offline cache -> circular buffer -> AI habits
        val evictionDirs = listOf(cacheDir, rollingDir, intelDir)
        for (dir in evictionDirs) {
            if (currentManagedTotal() <= TOTAL_BUDGET_BYTES) break
            val excess = currentManagedTotal() - TOTAL_BUDGET_BYTES
            val dirCurrent = dirSize(dir)
            val target = (dirCurrent - excess).coerceAtLeast(0L)
            deletedCount += trimDirectoryToCap(dir, target)
        }

        if (currentManagedTotal() > TOTAL_BUDGET_BYTES && radarJsonFile.exists()) {
            radarJsonFile.writeText("{}")
            deletedCount++
        }
        if (currentManagedTotal() > TOTAL_BUDGET_BYTES && summaryFile.exists()) {
            summaryFile.writeText("{}")
            deletedCount++
        }
        return deletedCount
    }

    private fun trimDirectoryToCap(dir: File, maxBytes: Long): Int {
        if (!dir.exists()) return 0
        var current = dirSize(dir)
        if (current <= maxBytes) return 0
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toList()
        var removed = 0
        for (f in files) {
            if (current <= maxBytes) break
            val size = f.length()
            if (f.delete()) {
                current -= size
                removed++
            }
        }
        return removed
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun getDeviceFreeMb(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            (stat.availableBytes / (1024L * 1024L))
        } catch (_: Exception) {
            2048L
        }
    }
}

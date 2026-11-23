package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

@Keep
@IgnoreExtraProperties
data class CloudEmergencyDispatch(
    @DocumentId val id: String = "",
    val dispatchId: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val ownerPhone: String = "",
    val alertType: String = "SOS",
    val status: String = "ACTIVE",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Double = 0.0,
    val mapsUrl: String = "",
    val locationSummary: String = "",
    val batteryPercent: Int = 80,
    val medicalSummary: String = "",
    val evidenceSummary: String = "",
    val frontPhotoBase64: String = "",
    val rearPhotoBase64: String = "",
    val authorizedEmails: List<String> = emptyList(),
    val authorizedPhones: List<String> = emptyList(),
    val authorizedUids: List<String> = emptyList(),
    val deviceLocked: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Publishes automated, zero-manual-interference SOS & Anti-Snatch dispatches
 * (GPS, Google Maps link, Front+Rear compressed JPEG evidence, SHA-256 digests, and medical card)
 * to Firestore so signed-in trusted guardians receive direct push alerts by phone UID or email.
 */
class FirestoreEmergencyDispatchRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth get() = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Google Sign-In required for automated cloud guardian dispatch.")
    }

    /**
     * Compresses an evidence image file to a compact Base64 JPEG thumbnail (<= ~45 KB)
     * so both Front + Rear attacker/scene photos sync instantly inside the dispatch document.
     */
    suspend fun compressPhotoToBase64(imagePath: String?): String = withContext(Dispatchers.IO) {
        if (imagePath.isNullOrBlank()) return@withContext ""
        val file = File(imagePath)
        if (!file.exists() || file.length() <= 0L) return@withContext ""
        return@withContext try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            val sampleSize = (maxDim / 360).coerceAtLeast(1)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return@withContext ""

            val ratio = minOf(360f / bitmap.width.coerceAtLeast(1), 360f / bitmap.height.coerceAtLeast(1), 1f)
            val scaled = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            var quality = 72
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            while (baos.size() > 80_000 && quality > 30) {
                baos.reset()
                quality -= 15
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()

            val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            if (encoded.length <= 118_000) encoded else encoded.take(118_000)
        } catch (e: Exception) {
            Log.w("FirestoreDispatchRepo", "Failed compressing photo thumbnail: ${e.message}")
            ""
        }
    }

    suspend fun publishOrUpdateDispatch(
        dispatchId: String,
        ownerName: String,
        ownerPhone: String,
        alertType: String,
        status: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        mapsUrl: String,
        locationSummary: String,
        batteryPercent: Int,
        medicalSummary: String,
        evidenceSummary: String,
        frontPhotoBase64: String,
        rearPhotoBase64: String,
        authorizedEmails: List<String>,
        authorizedPhones: List<String>,
        authorizedUids: List<String> = emptyList(),
        deviceLocked: Boolean
    ): Result<Unit> {
        val cleanId = dispatchId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val path = "emergency_dispatches/$cleanId"
        return try {
            val uid = requireUserId()
            val normalizedType = when (alertType.uppercase(Locale.US)) {
                "ANTI_SNATCH", "TRIP_ANOMALY", "FAKE_CALL_GUARD" -> alertType.uppercase(Locale.US)
                else -> "SOS"
            }
            val normalizedStatus = when (status.uppercase(Locale.US)) {
                "CAPTURED", "RESOLVED", "CANCELLED" -> status.uppercase(Locale.US)
                else -> "ACTIVE"
            }
            val safeLat = latitude.coerceIn(-90.0, 90.0)
            val safeLng = longitude.coerceIn(-180.0, 180.0)
            val safeAcc = accuracyMeters.coerceAtLeast(0.0)
            val safeBattery = batteryPercent.coerceIn(0, 100)
            val safeEmails = authorizedEmails
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(15)
            val safePhones = authorizedPhones
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(15)
            val safeUids = authorizedUids
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(15)

            val docRef = db.collection("emergency_dispatches").document(cleanId)
            val existingSnap = docRef.get().await()

            if (existingSnap.exists()) {
                val existingStatus = existingSnap.getString("status") ?: ""
                if (existingStatus == "RESOLVED" || existingStatus == "CANCELLED") {
                    return Result.success(Unit)
                }
                val updatePayload = mapOf(
                    "ownerName" to ownerName.trim().take(100),
                    "ownerPhone" to ownerPhone.trim().take(30),
                    "alertType" to normalizedType,
                    "status" to normalizedStatus,
                    "latitude" to safeLat,
                    "longitude" to safeLng,
                    "accuracyMeters" to safeAcc,
                    "mapsUrl" to mapsUrl.trim().take(300),
                    "locationSummary" to locationSummary.trim().take(300),
                    "batteryPercent" to safeBattery,
                    "medicalSummary" to medicalSummary.trim().take(500),
                    "evidenceSummary" to evidenceSummary.trim().take(800),
                    "frontPhotoBase64" to frontPhotoBase64.take(118_000),
                    "rearPhotoBase64" to rearPhotoBase64.take(118_000),
                    "authorizedEmails" to safeEmails,
                    "authorizedPhones" to safePhones,
                    "authorizedUids" to safeUids,
                    "deviceLocked" to deviceLocked,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            } else {
                val createPayload = mapOf(
                    "dispatchId" to cleanId,
                    "ownerUid" to uid,
                    "ownerName" to ownerName.trim().take(100),
                    "ownerPhone" to ownerPhone.trim().take(30),
                    "alertType" to normalizedType,
                    "status" to normalizedStatus,
                    "latitude" to safeLat,
                    "longitude" to safeLng,
                    "accuracyMeters" to safeAcc,
                    "mapsUrl" to mapsUrl.trim().take(300),
                    "locationSummary" to locationSummary.trim().take(300),
                    "batteryPercent" to safeBattery,
                    "medicalSummary" to medicalSummary.trim().take(500),
                    "evidenceSummary" to evidenceSummary.trim().take(800),
                    "frontPhotoBase64" to frontPhotoBase64.take(118_000),
                    "rearPhotoBase64" to rearPhotoBase64.take(118_000),
                    "authorizedEmails" to safeEmails,
                    "authorizedPhones" to safePhones,
                    "authorizedUids" to safeUids,
                    "deviceLocked" to deviceLocked,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun updateDispatchStatus(dispatchId: String, newStatus: String): Result<Unit> {
        val cleanId = dispatchId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val path = "emergency_dispatches/$cleanId"
        return try {
            requireUserId()
            val normalizedStatus = when (newStatus.uppercase(Locale.US)) {
                "CAPTURED", "RESOLVED", "CANCELLED" -> newStatus.uppercase(Locale.US)
                else -> "ACTIVE"
            }
            val docRef = db.collection("emergency_dispatches").document(cleanId)
            val snap = docRef.get().await()
            if (!snap.exists()) return Result.success(Unit)
            val current = snap.getString("status") ?: ""
            if (current == "RESOLVED" || current == "CANCELLED") return Result.success(Unit)

            docRef.update(
                mapOf(
                    "status" to normalizedStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteDispatchForIncident(dispatchId: String): Result<Unit> {
        val cleanId = dispatchId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val path = "emergency_dispatches/$cleanId"
        return try {
            requireUserId()
            db.collection("emergency_dispatches").document(cleanId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    /**
     * Real-time stream of active emergency alerts where this signed-in user is matched
     * either via normalized phone UID (`authorizedUids`) OR email (`authorizedEmails`).
     */
    fun observeIncomingGuardianAlerts(currentUid: String, guardianEmail: String): Flow<List<CloudEmergencyDispatch>> = callbackFlow {
        val cleanUid = currentUid.trim()
        val normalizedEmail = guardianEmail.trim().lowercase(Locale.US)
        if (auth.currentUser == null || (cleanUid.isBlank() && normalizedEmail.isBlank())) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val uidMap = linkedMapOf<String, CloudEmergencyDispatch>()
        val emailMap = linkedMapOf<String, CloudEmergencyDispatch>()
        val registrations = mutableListOf<ListenerRegistration>()

        fun emitMerged() {
            val combined = linkedMapOf<String, CloudEmergencyDispatch>()
            uidMap.values.forEach { combined[it.dispatchId.ifBlank { it.id }] = it }
            emailMap.values.forEach { combined[it.dispatchId.ifBlank { it.id }] = it }
            val activeSorted = combined.values
                .filter { it.status !in listOf("RESOLVED", "CANCELLED") }
                .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
            trySend(activeSorted)
        }

        val path = "emergency_dispatches"
        if (cleanUid.isNotBlank()) {
            registrations += db.collection(path)
                .whereArrayContains("authorizedUids", cleanUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (auth.currentUser != null) {
                            handleFirestoreError(error, OperationType.LIST, path)
                        }
                        return@addSnapshotListener
                    }
                    uidMap.clear()
                    snapshot?.documents?.forEach { doc ->
                        val item = doc.toObject(
                            CloudEmergencyDispatch::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                        if (item != null) {
                            uidMap[doc.id] = item
                        }
                    }
                    emitMerged()
                }
        }

        if (normalizedEmail.isNotBlank()) {
            registrations += db.collection(path)
                .whereArrayContains("authorizedEmails", normalizedEmail)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (auth.currentUser != null) {
                            handleFirestoreError(error, OperationType.LIST, path)
                        }
                        return@addSnapshotListener
                    }
                    emailMap.clear()
                    snapshot?.documents?.forEach { doc ->
                        val item = doc.toObject(
                            CloudEmergencyDispatch::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                        if (item != null) {
                            emailMap[doc.id] = item
                        }
                    }
                    emitMerged()
                }
        }

        awaitClose {
            registrations.forEach { it.remove() }
        }
    }
}

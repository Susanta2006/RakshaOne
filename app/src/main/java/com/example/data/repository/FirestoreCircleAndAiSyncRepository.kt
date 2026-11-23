package com.example.data.repository

import android.content.Context
import androidx.annotation.Keep
import com.example.R
import com.example.data.model.TrustedContactEntity
import com.example.util.AiLearningStateSnapshot
import com.example.util.PhoneNormalizer
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@Keep
@IgnoreExtraProperties
data class CloudTrustedContactDoc(
    @DocumentId val id: String = "",
    val contactDocId: String = "",
    val ownerUid: String = "",
    val name: String = "",
    val relationship: String = "",
    val phone: String = "",
    val email: String = "",
    val priority: Int = 1,
    val hasWhatsApp: Boolean = true,
    val allow24x7Location: Boolean = false,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudAiSafetyStateDoc(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val sovereigntyTier: Int = 1,
    val autonomousGuardEnabled: Boolean = true,
    val confirmedSafeCount: Int = 0,
    val confirmedThreatCount: Int = 0,
    val fakeCallsTriggeredByAi: Int = 0,
    val calibrationBiases: List<Double> = emptyList(),
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Keeps Trusted Contacts (`users/{uid}/trusted_contacts`) and Sovereign TFLite AI
 * calibration/state (`users/{uid}/ai_safety_state`) durably synced in Firestore
 * across reinstalls and signed-in devices.
 */
class FirestoreCircleAndAiSyncRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth get() = Firebase.auth

    private fun contactDocKey(phone: String, fallbackName: String): String {
        val canonical = PhoneNormalizer.canonicalPhoneKey(phone)
        val raw = if (canonical.isNotBlank()) "p_$canonical" else "n_${fallbackName.lowercase()}"
        return raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(120).ifBlank { "contact_default" }
    }

    suspend fun syncUpsertTrustedContact(contact: TrustedContactEntity): Result<Unit> =
        withContext(Dispatchers.IO) {
            val uid = auth.currentUser?.uid ?: return@withContext Result.success(Unit)
            val docId = contactDocKey(contact.phone, contact.name)
            val path = "users/$uid/trusted_contacts/$docId"
            return@withContext try {
                val payload = mapOf(
                    "contactDocId" to docId,
                    "ownerUid" to uid,
                    "name" to contact.name.trim().take(100),
                    "relationship" to contact.relationship.trim().take(40),
                    "phone" to contact.phone.trim().take(30),
                    "email" to contact.email.trim().lowercase().take(200),
                    "priority" to contact.priority.coerceIn(1, 20),
                    "hasWhatsApp" to contact.hasWhatsApp,
                    "allow24x7Location" to contact.allow24x7Location,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                db.collection("users").document(uid)
                    .collection("trusted_contacts").document(docId)
                    .set(payload, SetOptions.merge())
                    .await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, path)
                Result.failure(e)
            }
        }

    suspend fun syncDeleteTrustedContact(contact: TrustedContactEntity): Result<Unit> =
        withContext(Dispatchers.IO) {
            val uid = auth.currentUser?.uid ?: return@withContext Result.success(Unit)
            val docId = contactDocKey(contact.phone, contact.name)
            val path = "users/$uid/trusted_contacts/$docId"
            return@withContext try {
                db.collection("users").document(uid)
                    .collection("trusted_contacts").document(docId)
                    .delete()
                    .await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.DELETE, path)
                Result.failure(e)
            }
        }

    suspend fun fetchCloudTrustedContacts(): Result<List<TrustedContactEntity>> =
        withContext(Dispatchers.IO) {
            val uid = auth.currentUser?.uid ?: return@withContext Result.success(emptyList())
            val path = "users/$uid/trusted_contacts"
            return@withContext try {
                val snap = db.collection("users").document(uid)
                    .collection("trusted_contacts")
                    .get()
                    .await()
                val items = snap.documents.mapNotNull { doc ->
                    val dto = doc.toObject(CloudTrustedContactDoc::class.java) ?: return@mapNotNull null
                    if (dto.phone.isBlank() && dto.name.isBlank()) return@mapNotNull null
                    TrustedContactEntity(
                        name = dto.name,
                        relationship = dto.relationship,
                        phone = dto.phone,
                        email = dto.email,
                        priority = dto.priority,
                        isEnabled = true,
                        hasWhatsApp = dto.hasWhatsApp,
                        allow24x7Location = dto.allow24x7Location
                    )
                }
                Result.success(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, path)
                Result.failure(e)
            }
        }

    suspend fun syncPushAiSafetyState(snapshot: AiLearningStateSnapshot): Result<Unit> =
        withContext(Dispatchers.IO) {
            val uid = auth.currentUser?.uid ?: return@withContext Result.success(Unit)
            val path = "users/$uid/ai_safety_state/profile"
            return@withContext try {
                val payload = mapOf(
                    "ownerUid" to uid,
                    "sovereigntyTier" to snapshot.sovereigntyTier.coerceIn(1, 3),
                    "autonomousGuardEnabled" to snapshot.autonomousGuardEnabled,
                    "confirmedSafeCount" to snapshot.confirmedSafeCount.coerceAtLeast(0),
                    "confirmedThreatCount" to snapshot.confirmedThreatCount.coerceAtLeast(0),
                    "fakeCallsTriggeredByAi" to snapshot.fakeCallsTriggeredByAi.coerceAtLeast(0),
                    "calibrationBiases" to snapshot.calibrationBiases.take(10).map { it.toDouble() },
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                db.collection("users").document(uid)
                    .collection("ai_safety_state").document("profile")
                    .set(payload, SetOptions.merge())
                    .await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, path)
                Result.failure(e)
            }
        }

    suspend fun fetchCloudAiSafetyState(): Result<AiLearningStateSnapshot?> =
        withContext(Dispatchers.IO) {
            val uid = auth.currentUser?.uid ?: return@withContext Result.success(null)
            val path = "users/$uid/ai_safety_state/profile"
            return@withContext try {
                val snap = db.collection("users").document(uid)
                    .collection("ai_safety_state").document("profile")
                    .get()
                    .await()
                if (!snap.exists()) return@withContext Result.success(null)
                val dto = snap.toObject(CloudAiSafetyStateDoc::class.java) ?: return@withContext Result.success(null)
                Result.success(
                    AiLearningStateSnapshot(
                        sovereigntyTier = dto.sovereigntyTier.coerceIn(1, 3),
                        autonomousGuardEnabled = dto.autonomousGuardEnabled,
                        confirmedSafeCount = dto.confirmedSafeCount,
                        confirmedThreatCount = dto.confirmedThreatCount,
                        fakeCallsTriggeredByAi = dto.fakeCallsTriggeredByAi,
                        calibrationBiases = dto.calibrationBiases.take(10).map { it.toFloat() }.let { list ->
                            if (list.size == 10) list else List(10) { idx -> list.getOrElse(idx) { 0f } }
                        }
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.GET, path)
                Result.failure(e)
            }
        }
}

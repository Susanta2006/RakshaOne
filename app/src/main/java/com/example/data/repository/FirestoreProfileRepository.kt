package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.example.R
import com.example.data.model.UserProfileEntity
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write")
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val currentUser = try {
        Firebase.auth.currentUser
    } catch (_: Exception) {
        null
    }

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@Keep
@IgnoreExtraProperties
data class CloudUserSafetyProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val phone: String = "",
    val bloodGroup: String = "",
    val allergies: String = "",
    val medicalNotes: String = "",
    val homeAddress: String = "",
    val workAddress: String = "",
    val autoSendSmsOnSos: Boolean = true,
    val sirenBeaconEnabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PhoneDirectoryEntry(
    @DocumentId val id: String = "",
    val phoneKey: String = "",
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val appInstalled: Boolean = true,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

class FirestoreProfileRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth get() = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeProfile(userId: String): Flow<CloudUserSafetyProfile?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing to observe cross-user profile." }
        val docRef = db.collection("users").document(uid)
        val path = docRef.path
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(
                        CloudUserSafetyProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getProfileOnce(userId: String): Result<CloudUserSafetyProfile?> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user profile read." }
            val snap = db.collection("users").document(uid).get().await()
            val model = if (snap.exists()) {
                snap.toObject(
                    CloudUserSafetyProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            } else {
                null
            }
            Result.success(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun upsertProfile(
        profile: UserProfileEntity,
        authDisplayName: String? = null,
        authEmail: String? = null
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
            docPath = docRef.path

            val displayName = profile.name.ifBlank { authDisplayName.orEmpty() }.trim().take(100)
            val email = profile.email.ifBlank { authEmail.orEmpty() }.trim().take(200)
            val phone = profile.phone.trim().take(30)
            val bloodGroup = profile.bloodGroup.trim().take(16)
            val allergies = profile.allergies.trim().take(300)
            val medicalNotes = profile.medicalNotes.trim().take(500)
            val homeAddress = profile.homeAddress.trim().take(300)
            val workAddress = profile.workAddress.trim().take(300)

            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val updatePayload = mapOf(
                    "displayName" to displayName,
                    "email" to email,
                    "phone" to phone,
                    "bloodGroup" to bloodGroup,
                    "allergies" to allergies,
                    "medicalNotes" to medicalNotes,
                    "homeAddress" to homeAddress,
                    "workAddress" to workAddress,
                    "autoSendSmsOnSos" to profile.autoSendSmsOnSos,
                    "sirenBeaconEnabled" to profile.sirenBeaconEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            } else {
                val createPayload = mapOf(
                    "userId" to uid,
                    "displayName" to displayName,
                    "email" to email,
                    "phone" to phone,
                    "bloodGroup" to bloodGroup,
                    "allergies" to allergies,
                    "medicalNotes" to medicalNotes,
                    "homeAddress" to homeAddress,
                    "workAddress" to workAddress,
                    "autoSendSmsOnSos" to profile.autoSendSmsOnSos,
                    "sirenBeaconEnabled" to profile.sirenBeaconEnabled,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            }

            // Register canonical digits-only phone keys so trusted contacts discover this app user directly
            val lookupKeys = com.example.util.PhoneNormalizer.directoryLookupKeys(phone)
            for (phoneKey in lookupKeys) {
                try {
                    val dirRef = db.collection("phone_directory").document(phoneKey)
                    dirRef.set(
                        mapOf(
                            "phoneKey" to phoneKey,
                            "uid" to uid,
                            "displayName" to displayName.ifBlank { "RakshaOne User" },
                            "email" to email.lowercase(),
                            "appInstalled" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    ).await()
                } catch (dirEx: Exception) {
                    Log.w("FirestoreProfileRepo", "Phone directory upsert skipped for $phoneKey: ${dirEx.message}")
                }
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    data class ResolvedGuardiansDirectoryResult(
        val resolvedUids: List<String>,
        val installedByRawPhone: Map<String, Boolean>,
        val uidToPhoneKey: Map<String, String>,
        val uidToEmail: Map<String, String>
    )

    /**
     * Resolves installed RakshaOne user UIDs for a list of Trusted Contact phone numbers.
     * Uses exact document `get` on canonical digits-only phone keys (no broad enumeration).
     */
    suspend fun resolveInstalledGuardiansByPhones(phones: List<String>): Pair<List<String>, Map<String, Boolean>> {
        val details = resolveInstalledGuardianDetails(phones)
        return details.resolvedUids to details.installedByRawPhone
    }

    suspend fun resolveInstalledGuardianDetails(phones: List<String>): ResolvedGuardiansDirectoryResult {
        val currentUid = auth.currentUser?.uid
            ?: return ResolvedGuardiansDirectoryResult(emptyList(), emptyMap(), emptyMap(), emptyMap())
        val resolvedUids = LinkedHashSet<String>()
        val installedByRawPhone = LinkedHashMap<String, Boolean>()
        val uidToPhoneKey = LinkedHashMap<String, String>()
        val uidToEmail = LinkedHashMap<String, String>()

        for (rawPhone in phones.distinct().take(20)) {
            var matched = false
            val canonical = com.example.util.PhoneNormalizer.canonicalPhoneKey(rawPhone)
            for (key in com.example.util.PhoneNormalizer.directoryLookupKeys(rawPhone)) {
                try {
                    val snap = db.collection("phone_directory").document(key).get().await()
                    if (snap.exists()) {
                        val targetUid = snap.getString("uid").orEmpty()
                        if (targetUid.isNotBlank() && targetUid != currentUid) {
                            resolvedUids.add(targetUid)
                            if (canonical.isNotBlank()) {
                                uidToPhoneKey[targetUid] = canonical
                            }
                            val dirEmail = snap.getString("email").orEmpty().trim().lowercase()
                            if (dirEmail.isNotBlank()) {
                                uidToEmail[targetUid] = dirEmail
                            }
                        }
                        if (targetUid.isNotBlank()) {
                            matched = true
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }
            installedByRawPhone[rawPhone] = matched
        }
        return ResolvedGuardiansDirectoryResult(
            resolvedUids = resolvedUids.toList(),
            installedByRawPhone = installedByRawPhone,
            uidToPhoneKey = uidToPhoneKey,
            uidToEmail = uidToEmail
        )
    }
}

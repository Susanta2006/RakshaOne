package com.example.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Authenticated(val user: FirebaseUser) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthManager(private val context: Context) {
    private val tag = "Auth"
    private val appContext = context.applicationContext
    private val auth: FirebaseAuth by lazy { Firebase.auth }
    private val credentialManager: CredentialManager by lazy { CredentialManager.create(appContext) }

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _currentUser.value = user
        _authState.value = if (user != null) {
            AuthState.Authenticated(user)
        } else {
            AuthState.Idle
        }
    }

    fun isFirebaseConfigured(): Boolean {
        return try {
            FirebaseApp.getApps(appContext).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    init {
        if (isFirebaseConfigured()) {
            try {
                val existingUser = auth.currentUser
                _currentUser.value = existingUser
                if (existingUser != null) {
                    _authState.value = AuthState.Authenticated(existingUser)
                }
                auth.addAuthStateListener(authListener)
            } catch (e: Exception) {
                Log.w(tag, "Failed to attach FirebaseAuth listener: ${e.message}")
            }
        }
    }

    private fun resolveWebClientId(): String? {
        return try {
            appContext.getString(R.string.default_web_client_id).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Silent auto-sign-in attempt on startup using GetGoogleIdOption (single credential option).
     */
    suspend fun attemptAutoSignIn(activityContext: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isFirebaseConfigured()) return@withContext false
        if (auth.currentUser != null) {
            _currentUser.value = auth.currentUser
            _authState.value = AuthState.Authenticated(auth.currentUser!!)
            return@withContext true
        }
        val clientId = resolveWebClientId() ?: return@withContext false
        return@withContext try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = credentialManager.getCredential(activityContext, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val authResult = auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                val signedInUser = authResult.user
                _currentUser.value = signedInUser
                if (signedInUser != null) {
                    _authState.value = AuthState.Authenticated(signedInUser)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Interactive Google Sign-In / Registration using GetSignInWithGoogleOption (single credential option).
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<FirebaseUser> = withContext(Dispatchers.IO) {
        if (!isFirebaseConfigured()) {
            val msg = "Firebase Authentication is not configured; Guest Offline Vault remains active."
            _authState.value = AuthState.Error(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        val clientId = resolveWebClientId()
        if (clientId == null) {
            val msg = "Google Sign-In configuration missing: default_web_client_id not found."
            _authState.value = AuthState.Error(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        _authState.value = AuthState.Loading
        try {
            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val response = credentialManager.getCredential(
                context = activityContext,
                request = request
            )

            val cred = response.credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(authCredential).await()
                val user = authResult.user ?: throw IllegalStateException("Google sign-in returned null user")
                _currentUser.value = user
                _authState.value = AuthState.Authenticated(user)
                Result.success(user)
            } else {
                val msg = "Unexpected credential type returned by Google Sign-In."
                _authState.value = AuthState.Error(msg)
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(tag, "Google Sign-In cancelled or dismissed: ${e.message}", e)
            _authState.value = AuthState.Idle
            Result.failure(e)
        } catch (e: GetCredentialException) {
            Log.e(tag, "Credential Manager sign-in failed", e)
            val msg = e.localizedMessage ?: "Google Sign-In could not complete."
            _authState.value = AuthState.Error(msg)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(tag, "Firebase Google sign-in failed", e)
            val msg = e.localizedMessage ?: "Google Sign-In failed."
            _authState.value = AuthState.Error(msg)
            Result.failure(e)
        }
    }

    fun clearAuthError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = if (_currentUser.value != null) {
                AuthState.Authenticated(_currentUser.value!!)
            } else {
                AuthState.Idle
            }
        }
    }

    suspend fun signOut() {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(tag, "Failed to clear credential state: ${e.message}")
            }
            try {
                if (isFirebaseConfigured()) {
                    auth.signOut()
                }
            } catch (e: Exception) {
                Log.w(tag, "Firebase signOut error: ${e.message}")
            }
            _currentUser.value = null
            _authState.value = AuthState.Idle
        }
    }
}

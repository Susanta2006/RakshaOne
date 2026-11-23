package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NetworkMonitor(
    private val context: Context,
    private val onNetworkRestored: suspend () -> Unit = {},
    private val onNetworkLost: suspend () -> Unit = {}
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val _isOnline = MutableStateFlow(checkInitialConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var wasOffline = !checkInitialConnectivity()
    private var lastNetworkLostTriggeredMs = 0L
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        registerNetworkCallback()
    }

    fun isInternetCurrentlyAvailable(): Boolean {
        val online = checkInitialConnectivity()
        _isOnline.value = online
        if (!online) wasOffline = true
        return online
    }

    private fun checkInitialConnectivity(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun handleConnectivityDrop() {
        val previouslyOnline = _isOnline.value && !wasOffline
        _isOnline.value = false
        wasOffline = true
        if (previouslyOnline) {
            val now = System.currentTimeMillis()
            if (now - lastNetworkLostTriggeredMs >= 8_000L) {
                lastNetworkLostTriggeredMs = now
                scope.launch {
                    try {
                        onNetworkLost()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != false
                if (validated) {
                    val previouslyOffline = !_isOnline.value || wasOffline
                    _isOnline.value = true
                    wasOffline = false
                    if (previouslyOffline) {
                        scope.launch {
                            onNetworkRestored()
                        }
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val previouslyOffline = !_isOnline.value || wasOffline
                val previouslyOnline = _isOnline.value && !wasOffline
                _isOnline.value = hasInternet
                wasOffline = !hasInternet
                if (hasInternet && previouslyOffline) {
                    scope.launch {
                        onNetworkRestored()
                    }
                } else if (!hasInternet && previouslyOnline && !checkInitialConnectivity()) {
                    handleConnectivityDrop()
                }
            }

            override fun onLost(network: Network) {
                if (!checkInitialConnectivity()) {
                    handleConnectivityDrop()
                }
            }

            override fun onUnavailable() {
                handleConnectivityDrop()
            }
        })
    }
}

package com.synheart.core.modules.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.synheart.core.SynheartLogger

/**
 * Network connectivity monitor
 *
 * Monitors network state and emits connectivity changes via Flow.
 * Used to trigger auto-flush when network becomes available.
 */
class NetworkMonitor(context: Context?) {

    private val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager

    private val _connectivityFlow = MutableStateFlow(false)
    val connectivityFlow: StateFlow<Boolean> = _connectivityFlow.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val isOnline: Boolean
        get() = _connectivityFlow.value

    init {
        initializeNetworkMonitoring()
    }

    private fun initializeNetworkMonitoring() {
        if (connectivityManager == null) {
            SynheartLogger.log("[NetworkMonitor] ConnectivityManager not available")
            return
        }

        // Check initial connectivity
        _connectivityFlow.value = isConnected()

        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasOnline = _connectivityFlow.value
                _connectivityFlow.value = true

                if (!wasOnline) {
                    SynheartLogger.log("[NetworkMonitor] Network available")
                }
            }

            override fun onLost(network: Network) {
                val wasOnline = _connectivityFlow.value
                _connectivityFlow.value = isConnected()

                if (wasOnline && !_connectivityFlow.value) {
                    SynheartLogger.log("[NetworkMonitor] Network lost")
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val isConnected = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                _connectivityFlow.value = isConnected
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            SynheartLogger.log("[NetworkMonitor] Failed to register network callback: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Check if device is currently connected to network
     */
    private fun isConnected(): Boolean {
        if (connectivityManager == null) return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Dispose of network monitoring resources
     */
    fun dispose() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                SynheartLogger.log("[NetworkMonitor] Failed to unregister network callback: ${e.message}")
            }
        }
        networkCallback = null
    }
}

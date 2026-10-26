package com.arrivelog.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.arrivelog.domain.model.WifiNetwork
import com.arrivelog.domain.wifi.WifiMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

data class ScannedNetwork(val ssid: String, val bssid: String?, val levelDbm: Int)

/**
 * Foreground-only Wi-Fi reads for setup and "Test now". (Background detection in Phase 3 uses a
 * PendingIntent callback instead; nothing here runs when the screen is closed.)
 */
@Singleton
class WifiSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceStatus: DeviceStatus,
) {
    private val wifiManager get() = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    /**
     * The connected Wi-Fi network while collected. On API 31+ the SSID is only unredacted for a
     * callback registered with FLAG_INCLUDE_LOCATION_INFO (and location permission granted).
     */
    fun connected(): Flow<WifiNetwork?> = callbackFlow {
        val cm = connectivity
        if (cm == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    trySend(caps.toWifiNetwork())
                }

                override fun onLost(network: Network) {
                    trySend(null)
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    trySend(caps.toWifiNetwork() ?: legacyConnectionInfo())
                }

                override fun onLost(network: Network) {
                    trySend(null)
                }
            }
        }
        trySend(null)
        cm.registerNetworkCallback(request, callback)
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    /**
     * One-shot read of the current Wi-Fi connection, usable from a background receiver.
     * On API 31+ capabilities from getNetworkCapabilities() have the SSID redacted, so this falls
     * back to WifiManager.getConnectionInfo() (deprecated but still functional with background
     * location granted). Returns null when not on Wi-Fi.
     */
    fun currentConnection(network: Network? = null): WifiNetwork? {
        val cm = connectivity ?: return null
        val target = network ?: cm.activeNetwork
        val caps = target?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val fromCaps = caps?.toWifiNetwork()
        if (fromCaps != null && WifiMatcher.normalizeSsid(fromCaps.ssid) != null) return fromCaps
        return legacyConnectionInfo()?.takeIf { WifiMatcher.normalizeSsid(it.ssid) != null } ?: fromCaps
    }

    /** Cached results only: free, no radio work. */
    @SuppressLint("MissingPermission")
    fun cachedScanResults(): List<ScannedNetwork> {
        if (!deviceStatus.granted(Manifest.permission.ACCESS_FINE_LOCATION)) return emptyList()
        val results = runCatching { wifiManager?.scanResults.orEmpty() }.getOrDefault(emptyList())
        // One entry per access point (BSSID); callers group by SSID for display.
        return results.mapNotNull { it.toScanned() }.sortedByDescending { it.levelDbm }
    }

    /**
     * Asks for a fresh scan. Only ever called from a user tap with the screen open; Android limits
     * foreground apps to 4 scans per 2 minutes, and background scans are never used (section 5b).
     */
    @Suppress("DEPRECATION")
    fun requestScan(): Boolean = runCatching { wifiManager?.startScan() == true }.getOrDefault(false)

    private fun NetworkCapabilities.toWifiNetwork(): WifiNetwork? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val info = transportInfo as? WifiInfo ?: return null
        return WifiNetwork(info.ssid, info.bssid)
    }

    @Suppress("DEPRECATION")
    private fun legacyConnectionInfo(): WifiNetwork? =
        runCatching { wifiManager?.connectionInfo?.let { WifiNetwork(it.ssid, it.bssid) } }.getOrNull()

    @Suppress("DEPRECATION")
    private fun ScanResult.toScanned(): ScannedNetwork? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wifiSsid?.toString() else SSID
        val ssid = WifiMatcher.normalizeSsid(raw) ?: return null
        return ScannedNetwork(ssid, WifiMatcher.normalizeBssid(BSSID), level)
    }
}

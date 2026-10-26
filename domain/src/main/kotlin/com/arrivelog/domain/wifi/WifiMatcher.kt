package com.arrivelog.domain.wifi

import com.arrivelog.domain.model.WifiNetwork
import com.arrivelog.domain.model.Workplace

sealed interface WifiMatch {
    data class Matched(val workplaceIds: Set<Long>, val ssid: String) : WifiMatch

    /**
     * SSID redacted by the OS. Almost always means the location permission / location toggle is
     * off, or the read happened without FLAG_INCLUDE_LOCATION_INFO. Logged so the Health screen
     * can point at it.
     */
    data object UnknownSsid : WifiMatch
    data object NotOffice : WifiMatch
    data object BssidMismatch : WifiMatch
}

object WifiMatcher {
    const val UNKNOWN_SSID = "<unknown ssid>"

    /** Android reports this BSSID when it is redacted. */
    private const val REDACTED_BSSID = "02:00:00:00:00:00"

    /** WifiInfo.getSSID() wraps UTF-8 names in quotes and returns "<unknown ssid>" when redacted. */
    fun normalizeSsid(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals(UNKNOWN_SSID, ignoreCase = true) || trimmed == "0x") return null
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.length - 1).takeIf { it.isNotEmpty() }
        } else {
            trimmed
        }
    }

    fun normalizeBssid(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != REDACTED_BSSID }

    fun match(network: WifiNetwork, workplaces: List<Workplace>, matchBssid: Boolean): WifiMatch {
        val ssid = normalizeSsid(network.ssid) ?: return WifiMatch.UnknownSsid
        val bySsid = workplaces.filter { ssid in it.ssids }
        if (bySsid.isEmpty()) return WifiMatch.NotOffice
        if (!matchBssid) return WifiMatch.Matched(bySsid.mapTo(mutableSetOf()) { it.id }, ssid)

        val bssid = normalizeBssid(network.bssid) ?: return WifiMatch.BssidMismatch
        // A workplace with no BSSIDs saved cannot satisfy strict matching.
        val byBssid = bySsid.filter { bssid in it.bssids }
        return if (byBssid.isEmpty()) {
            WifiMatch.BssidMismatch
        } else {
            WifiMatch.Matched(byBssid.mapTo(mutableSetOf()) { it.id }, ssid)
        }
    }

    /** First office network among cached scan results, if any. */
    fun matchAny(networks: List<WifiNetwork>, workplaces: List<Workplace>, matchBssid: Boolean): WifiMatch {
        var sawUnknown = false
        for (network in networks) {
            when (val m = match(network, workplaces, matchBssid)) {
                is WifiMatch.Matched -> return m
                WifiMatch.UnknownSsid -> sawUnknown = true
                else -> Unit
            }
        }
        return if (sawUnknown && networks.all { normalizeSsid(it.ssid) == null }) WifiMatch.UnknownSsid else WifiMatch.NotOffice
    }
}

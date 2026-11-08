package com.multiroute.wifi

import android.content.Context
import com.multiroute.R

/**
 * Pure functions and heuristic calculation rules for Wi-Fi bands, frequencies,
 * collision avoidance, and interface name resolution.
 */
object DualWifiBandHelper {

    /**
     * Checks whether an AP frequency matches the reference connected frequency band.
     */
    fun isSameBand(f1: Int, f2: Int): Boolean {
        if (f1 <= 0 || f2 <= 0) return true
        val is24_1 = f1 in 2400..2500
        val is24_2 = f2 in 2400..2500
        if (is24_1 || is24_2) return is24_1 == is24_2

        val is5_1 = f1 in 4900..5900
        val is5_2 = f2 in 4900..5900
        if (is5_1 || is5_2) {
            if (is5_1 != is5_2) return false
            val low1 = f1 in 4900..5350
            val low2 = f2 in 4900..5350
            return low1 == low2
        }

        val is6_1 = f1 in 5925..7125
        val is6_2 = f2 in 5925..7125
        if (is6_1 || is6_2) return is6_1 == is6_2

        return false
    }

    /**
     * Accurately determines if a scanned Wi-Fi candidate corresponds to the connected network,
     * resolving same-SSID multi-band disambiguation via BSSID and frequency band.
     */
    fun matchesConnected(
        candidateSsid: String,
        candidateBssid: String,
        candidateFreq: Int,
        connectedSsid: String?,
        connectedBssid: String?,
        connectedFreq: Int
    ): Boolean {
        if (connectedSsid.isNullOrEmpty() || !candidateSsid.equals(connectedSsid, ignoreCase = true)) {
            return false
        }
        // 1. If valid connected BSSID is known, BSSID comparison is authoritative
        val hasValidConnectedBssid = !connectedBssid.isNullOrEmpty() &&
            connectedBssid != "02:00:00:00:00:00" &&
            connectedBssid != "00:00:00:00:00:00"
        if (hasValidConnectedBssid) {
            return candidateBssid.equals(connectedBssid, ignoreCase = true)
        }
        // 2. Fallback: Frequency band match if BSSID was masked by Android framework
        if (connectedFreq > 0 && candidateFreq > 0) {
            return isSameBand(candidateFreq, connectedFreq)
        }
        // 3. Fallback when neither BSSID nor frequency is available
        return true
    }

    /**
     * Checks if a candidate secondary Wi-Fi AP collides with the currently active primary Wi-Fi,
     * preventing conflicts that could tear down the primary STA connection.
     */
    fun isPrimaryCollision(
        candidateSsid: String,
        candidateBssid: String?,
        primarySsid: String?,
        primaryBssid: String?
    ): Boolean {
        if (primarySsid.isNullOrEmpty()) return false
        if (candidateSsid.equals(primarySsid, ignoreCase = true)) return true
        if (!candidateBssid.isNullOrEmpty() && !primaryBssid.isNullOrEmpty() &&
            primaryBssid != "02:00:00:00:00:00" && primaryBssid != "00:00:00:00:00:00"
        ) {
            return candidateBssid.equals(primaryBssid, ignoreCase = true)
        }
        return false
    }

    /**
     * Checks whether two Wi-Fi frequencies share the same physical radio band (e.g. both 2.4GHz, both low 5GHz).
     */
    fun isBandConflicted(itemFreq: Int, primaryFreq: Int): Boolean {
        if (primaryFreq <= 0 || itemFreq <= 0) return false
        val primLow5G = primaryFreq in 5150..5350
        val targetLow5G = itemFreq in 5150..5350
        val primHigh5G = primaryFreq in 5470..5850
        val targetHigh5G = itemFreq in 5470..5850
        val prim24 = primaryFreq in 2400..2500
        val target24 = itemFreq in 2400..2500
        val prim6G = primaryFreq in 5925..7125
        val target6G = itemFreq in 5925..7125
        return (primLow5G && targetLow5G) || (primHigh5G && targetHigh5G) || (prim24 && target24) || (prim6G && target6G)
    }

    /**
     * Checks if the target AP is an optimal complementary radio band for concurrent dual-Wi-Fi.
     */
    fun isBandRecommended(itemFreq: Int, primaryFreq: Int): Boolean {
        if (primaryFreq <= 0 || itemFreq <= 0) return false
        return !isBandConflicted(itemFreq, primaryFreq)
    }

    /**
     * Formats frequency into user-friendly Wi-Fi band label.
     */
    fun formatBand(freq: Int, context: Context? = null): String = when (freq) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5320 -> context?.getString(R.string.wifi_i18n_band_5_low) ?: "5 GHz (低频)"
        in 5321..5900 -> context?.getString(R.string.wifi_i18n_band_5_high) ?: "5 GHz (高频)"
        in 5925..7125 -> "6 GHz"
        else -> if (freq > 0) context?.getString(R.string.wifi_i18n_frequency_mhz, freq) ?: "${freq}MHz" else context?.getString(R.string.wifi_i18n_band_enabled) ?: "已开启"
    }

    /**
     * Resolves security type for cmd wifi add-network / add-request.
     * Supports: open, owe, wpa2, wpa3, wep.
     */
    fun resolveSecurityType(capabilities: String?, passphrase: String?): String {
        if (capabilities == null) {
            return if (passphrase.isNullOrEmpty()) "open" else "wpa2"
        }
        val caps = capabilities.uppercase()
        return when {
            caps.contains("OWE") -> "owe"
            caps.contains("SAE") && !caps.contains("PSK") -> "wpa3"
            caps.contains("WEP") -> "wep"
            caps.contains("PSK") || caps.contains("WPA") -> "wpa2"
            passphrase.isNullOrEmpty() -> "open"
            else -> "wpa2"
        }
    }

    /**
     * Resolves primary and secondary Wi-Fi interface names deterministically.
     * Guaranteed: primary and secondary are distinct; wlan0 is primary when present.
     */
    fun resolvePrimarySecondaryIfaces(
        ifaces: List<String>,
        activeDefaultIface: String? = null
    ): Pair<String, String> {
        val primary = when {
            ifaces.contains("wlan0") -> "wlan0"
            activeDefaultIface?.startsWith("wlan") == true -> activeDefaultIface
            else -> ifaces.firstOrNull() ?: "wlan0"
        }
        val secondary = when {
            primary != "wlan1" && ifaces.contains("wlan1") -> "wlan1"
            else -> ifaces.firstOrNull { it != primary } ?: if (primary == "wlan0") "wlan1" else "wlan0"
        }
        return Pair(primary, secondary)
    }
}

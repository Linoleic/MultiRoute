package com.multiroute.wifi

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import com.multiroute.R
import com.multiroute.util.SuHelper

/**
 * Handles hotspot (SoftAP) state probing, kernel interface detection,
 * operating frequency extraction, and channel conflict checks.
 */
object HotspotDetector {

    /**
     * Parses kernel nl80211 interface dump from `iw dev` to detect active SoftAP interface and operating frequency.
     */
    fun parseHotspotFromIwDev(output: List<String>, context: Context? = null): HotspotInfo {
        var curIface: String? = null
        var isAp = false
        var freq = 0
        val freqRegex = Regex("""\((\d{4})\s*MHz\)""")

        for (line in output) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Interface ")) {
                if (isAp && curIface != null) {
                    return HotspotInfo(
                        isEnabled = true,
                        iface = curIface,
                        frequencyMhz = freq,
                        bandRes = DualWifiBandHelper.bandLabelRes(freq),
                        bandLabel = context?.let { DualWifiBandHelper.formatBand(freq, it) }
                    )
                }
                curIface = trimmed.substringAfter("Interface ").trim()
                isAp = false
                freq = 0
            } else if (trimmed == "type AP" || trimmed.startsWith("type AP ")) {
                isAp = true
            } else if (isAp && trimmed.startsWith("channel ") && freq == 0) {
                freq = freqRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }
        }
        if (isAp && curIface != null) {
            return HotspotInfo(
                isEnabled = true,
                iface = curIface,
                frequencyMhz = freq,
                bandRes = DualWifiBandHelper.bandLabelRes(freq),
                bandLabel = context?.let { DualWifiBandHelper.formatBand(freq, it) }
            )
        }
        return HotspotInfo(isEnabled = false)
    }

    /**
     * Checks if a candidate secondary Wi-Fi AP frequency conflicts with the active hotspot's frequency band.
     */
    fun isHotspotConflicted(candidateFreq: Int, hotspotFreq: Int): Boolean {
        if (hotspotFreq <= 0 || candidateFreq <= 0) return false
        return DualWifiBandHelper.isBandConflicted(candidateFreq, hotspotFreq)
    }

    /**
     * Probes current personal hotspot (SoftAP) state and operating frequency.
     */
    fun checkHotspotState(context: Context): HotspotInfo {
        // 1. Check sticky broadcast android.net.wifi.WIFI_AP_STATE_CHANGED
        val stickyIntent = runCatching {
            context.registerReceiver(null, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))
        }.getOrNull()
        val apState = stickyIntent?.getIntExtra("wifi_state", 11) ?: 11
        val isApBroadcastActive = (apState == 13 || apState == 12)

        // 2. Fallback check via WifiManager reflection if broadcast is absent
        val isApManagerActive = if (!isApBroadcastActive) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            runCatching {
                val method = wm?.javaClass?.getMethod("isWifiApEnabled")
                method?.isAccessible = true
                (method?.invoke(wm) as? Boolean) ?: false
            }.getOrDefault(false)
        } else true

        if (!isApBroadcastActive && !isApManagerActive) {
            return HotspotInfo(isEnabled = false)
        }

        // 3. AP is active -> query active interface and frequency via `iw dev`
        val iwLines = runCatching {
            SuHelper.executeShellWithOutput("iw dev 2>/dev/null", timeoutSeconds = 2)
        }.getOrDefault(emptyList())

        val fromIw = parseHotspotFromIwDev(iwLines, context)
        if (fromIw.isEnabled) {
            return fromIw
        }

        // 4. Fallback for devices without `iw` (e.g. minimal AOSP / Pixel factory images): dumpsys wifi
        val dumpsysLines = runCatching {
            SuHelper.executeShellWithOutput("dumpsys wifi 2>/dev/null | grep -i -E 'SoftApInfo|channel_frequency|mApInterfaceName' | head -n 10", timeoutSeconds = 2)
        }.getOrDefault(emptyList())
        val fromDumpsys = parseHotspotFromDumpsysWifi(dumpsysLines, context)
        if (fromDumpsys.isEnabled) {
            return fromDumpsys
        }

        return HotspotInfo(
            isEnabled = true,
            bandRes = R.string.wifi_i18n_band_enabled,
            bandLabel = context.getString(R.string.wifi_i18n_band_enabled)
        )
    }

    /**
     * Parses `dumpsys wifi` output as fallback when `iw` is not installed (e.g. clean AOSP/Pixel builds).
     */
    fun parseHotspotFromDumpsysWifi(lines: List<String>, context: Context? = null): HotspotInfo {
        var iface: String? = null
        var freq = 0
        val freqRegex = Regex("""(?:frequency|channel_frequency)[=:\s]+(\d{4})""", RegexOption.IGNORE_CASE)
        val ifaceRegex = Regex("""(?:SoftApInfo on\s+|mApInterfaceName\s*=\s*|iface=|\{)(\w+)(?=[=,}])""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val trimmed = line.trim()
            if (iface == null) {
                val match = ifaceRegex.find(trimmed)?.groupValues?.getOrNull(1)
                if (match != null && (match.startsWith("wlan") || match.startsWith("ap") || match.startsWith("softap"))) {
                    iface = match
                }
            }
            if (freq == 0) {
                val matchFreq = freqRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (matchFreq != null && matchFreq > 0) {
                    freq = matchFreq
                }
            }
            if (iface != null && freq > 0) break
        }

        return if (freq > 0 || iface != null) {
            HotspotInfo(
                isEnabled = true,
                iface = iface ?: "ap0",
                frequencyMhz = freq,
                bandRes = DualWifiBandHelper.bandLabelRes(freq),
                bandLabel = context?.let { DualWifiBandHelper.formatBand(freq, it) }
            )
        } else {
            HotspotInfo(isEnabled = false)
        }
    }
}

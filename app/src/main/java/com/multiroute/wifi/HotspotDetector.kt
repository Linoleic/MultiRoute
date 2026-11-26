package com.multiroute.wifi

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import com.multiroute.R
import com.multiroute.util.RouteRuleBuilder
import com.multiroute.util.SuHelper

/**
 * Handles hotspot (SoftAP) state probing, kernel interface detection,
 * operating frequency extraction, and channel conflict checks.
 */
object HotspotDetector {

    private fun isValidSoftApIface(name: String?): Boolean =
        !name.isNullOrBlank() &&
            !name.equals("null", ignoreCase = true) &&
            !name.equals("none", ignoreCase = true) &&
            RouteRuleBuilder.isValidInterfaceName(name)

    /**
     * Parses kernel nl80211 interface dump from `iw dev` to detect active SoftAP interfaces (`type AP`) and operating frequency.
     */
    fun parseHotspotFromIwDev(output: List<String>, context: Context? = null): HotspotInfo {
        var curIface: String? = null
        var isAp = false
        var curFreq = 0
        var resolvedFreq = 0
        val apIfaces = linkedSetOf<String>()
        val freqRegex = Regex("""\((\d{4})\s*MHz\)""")

        fun flushCurrent() {
            val iface = curIface
            if (isAp && isValidSoftApIface(iface)) {
                apIfaces.add(iface!!)
                if (resolvedFreq == 0 && curFreq > 0) {
                    resolvedFreq = curFreq
                }
            }
        }

        for (line in output) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Interface ")) {
                flushCurrent()
                curIface = trimmed.substringAfter("Interface ").trim()
                isAp = false
                curFreq = 0
            } else if (trimmed == "type AP" || trimmed.startsWith("type AP ")) {
                isAp = true
            } else if (isAp && trimmed.startsWith("channel ") && curFreq == 0) {
                curFreq = freqRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }
        }
        flushCurrent()

        if (apIfaces.isNotEmpty()) {
            return HotspotInfo(
                isEnabled = true,
                iface = apIfaces.first(),
                frequencyMhz = resolvedFreq,
                bandRes = DualWifiBandHelper.bandLabelRes(resolvedFreq),
                bandLabel = context?.let { DualWifiBandHelper.formatBand(resolvedFreq, it) },
                ifaces = apIfaces
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

        // 3. AP is active -> query nl80211 AP interfaces via `iw dev`
        val iwLines = runCatching {
            SuHelper.executeShellWithOutput("iw dev 2>/dev/null", timeoutSeconds = 2)
        }.getOrDefault(emptyList())
        val fromIw = parseHotspotFromIwDev(iwLines, context)

        // 4. Query `dumpsys wifi` if `iw` missed interfaces or frequency (e.g. clean AOSP / Pixel without `iw`)
        val fromDumpsys = if (!fromIw.isEnabled || fromIw.frequencyMhz == 0) {
            val dumpsysLines = runCatching {
                SuHelper.executeShellWithOutput(
                    "dumpsys wifi 2>/dev/null | grep -i -E 'SoftApInfo|channel_frequency|mApInterfaceName|mCurrentSoftApInfoMap' | head -n 20",
                    timeoutSeconds = 2
                )
            }.getOrDefault(emptyList())
            parseHotspotFromDumpsysWifi(dumpsysLines, context)
        } else HotspotInfo(isEnabled = false)

        // 5. Query `dumpsys tethering` using platform-declared tetherableWifiRegexs (captures L3 bridges like ap_br_*)
        val tetheringLines = runCatching {
            SuHelper.executeShellWithOutput(
                "dumpsys tethering 2>/dev/null | grep -E 'tetherableWifiRegexs|TetheredState' | head -n 20",
                timeoutSeconds = 2
            )
        }.getOrDefault(emptyList())
        val tetheredWifiIfaces = parseHotspotFromDumpsysTethering(tetheringLines)

        val allIfaces = linkedSetOf<String>().apply {
            addAll(tetheredWifiIfaces)
            addAll(fromIw.activeIfaces)
            addAll(fromDumpsys.activeIfaces)
        }
        val freq = if (fromIw.frequencyMhz > 0) fromIw.frequencyMhz else fromDumpsys.frequencyMhz
        val primaryIface = allIfaces.firstOrNull()

        return HotspotInfo(
            isEnabled = true,
            iface = primaryIface,
            frequencyMhz = freq,
            bandRes = if (freq > 0) DualWifiBandHelper.bandLabelRes(freq) else R.string.wifi_i18n_band_enabled,
            bandLabel = if (freq > 0) DualWifiBandHelper.formatBand(freq, context) else context.getString(R.string.wifi_i18n_band_enabled),
            ifaces = allIfaces
        )
    }

    /**
     * Parses `dumpsys wifi` SoftAP fields without relying on hardcoded interface name prefixes.
     */
    fun parseHotspotFromDumpsysWifi(lines: List<String>, context: Context? = null): HotspotInfo {
        val apIfaces = linkedSetOf<String>()
        var freq = 0
        val freqRegex = Regex("""(?:frequency|channel_frequency)\s*[:=]\s*(\d{4})""", RegexOption.IGNORE_CASE)
        val apIfaceFieldRegex = Regex("""mApInterfaceName\s*[:=]\s*([a-zA-Z0-9_.-]+)""")
        val softApMapRegex = Regex("""mCurrentSoftApInfoMap\s*\{([^}]*)\}""")
        val softApMapKeyRegex = Regex("""([a-zA-Z0-9_.-]+)=SoftApInfo""")
        val softApOnRegex = Regex("""SoftApInfo on\s+([a-zA-Z0-9_.-]+)""", RegexOption.IGNORE_CASE)
        val instanceIdRegex = Regex("""mApInstanceIdentifier\s*[:=]\s*([a-zA-Z0-9_.-]+)""")

        for (line in lines) {
            val trimmed = line.trim()
            apIfaceFieldRegex.findAll(trimmed).forEach { m ->
                val candidate = m.groupValues[1]
                if (isValidSoftApIface(candidate)) apIfaces.add(candidate)
            }
            softApMapRegex.findAll(trimmed).forEach { block ->
                softApMapKeyRegex.findAll(block.groupValues[1]).forEach { keyMatch ->
                    val candidate = keyMatch.groupValues[1]
                    if (isValidSoftApIface(candidate)) apIfaces.add(candidate)
                }
            }
            softApOnRegex.findAll(trimmed).forEach { m ->
                val candidate = m.groupValues[1]
                if (isValidSoftApIface(candidate)) apIfaces.add(candidate)
            }
            instanceIdRegex.findAll(trimmed).forEach { m ->
                val candidate = m.groupValues[1]
                if (isValidSoftApIface(candidate)) apIfaces.add(candidate)
            }
            if (freq == 0) {
                val matchFreq = freqRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (matchFreq != null && matchFreq > 0) {
                    freq = matchFreq
                }
            }
        }

        return if (freq > 0 || apIfaces.isNotEmpty()) {
            HotspotInfo(
                isEnabled = true,
                iface = apIfaces.firstOrNull(),
                frequencyMhz = freq,
                bandRes = DualWifiBandHelper.bandLabelRes(freq),
                bandLabel = context?.let { DualWifiBandHelper.formatBand(freq, it) },
                ifaces = apIfaces
            )
        } else {
            HotspotInfo(isEnabled = false)
        }
    }

    /**
     * Parses `dumpsys tethering` to find active tethered interfaces matching the platform's
     * declared `tetherableWifiRegexs`, avoiding hardcoded interface name assumptions.
     */
    internal fun parseHotspotFromDumpsysTethering(lines: List<String>): Set<String> {
        val wifiRegexes = mutableListOf<Regex>()
        val tetheredIfaces = linkedSetOf<String>()
        val regexListPattern = Regex("""tetherableWifiRegexs:\s*\[([^\]]*)\]""")
        val tetheredStatePattern = Regex("""^\s*([a-zA-Z0-9_.-]+)\s+-\s+TetheredState\b""")

        for (line in lines) {
            regexListPattern.find(line)?.let { m ->
                m.groupValues[1].split(',').forEach { raw ->
                    val trimmed = raw.trim()
                    if (trimmed.isNotEmpty()) {
                        runCatching { Regex(trimmed) }.getOrNull()?.let(wifiRegexes::add)
                    }
                }
            }
            tetheredStatePattern.find(line)?.let { m ->
                val iface = m.groupValues[1]
                if (isValidSoftApIface(iface)) tetheredIfaces.add(iface)
            }
        }

        if (wifiRegexes.isEmpty() || tetheredIfaces.isEmpty()) return emptySet()
        return tetheredIfaces.filterTo(linkedSetOf()) { iface ->
            wifiRegexes.any { regex -> regex.matches(iface) }
        }
    }
}

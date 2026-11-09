package com.multiroute.wifi

import com.multiroute.util.NetworkUtils
import com.multiroute.util.SuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Robust heuristics for parsing Wi-Fi scan output (`cmd wifi list-scan-results` / `wpa_cli`)
 * and system saved network credentials (`WifiConfigStore.xml` / `cmd wifi list-networks`).
 */
object DualWifiScanParser {

    fun tryParseRssi(token: String): Int? {
        // Xiaomi HyperOS / Qualcomm devices append per-antenna MIMO metrics: -79(0:-80/1:-87)
        val withoutMimo = token.substringBefore("(")
        val clean = withoutMimo
            .replace("dBm", "", ignoreCase = true)
            .replace("RSSI:", "", ignoreCase = true)
            .replace("Level:", "", ignoreCase = true)
            .replace("Signal:", "", ignoreCase = true)
            .trim()
        val intVal = clean.toIntOrNull()
        if (intVal != null && intVal in -120..0) return intVal
        val floatVal = clean.toFloatOrNull()
        if (floatVal != null && floatVal >= -120f && floatVal <= 0f) return floatVal.toInt()

        // Fallback: extract leading signed integer (e.g. -79 from -79...)
        val match = Regex("""^(-?\d+)""").find(clean)
        if (match != null) {
            val parsed = match.groupValues[1].toIntOrNull()
            if (parsed != null && parsed in -120..0) return parsed
        }
        return null
    }

    /**
     * Parses Wi-Fi scan results from shell command output (`cmd wifi list-scan-results` or `wpa_cli`)
     * using an adaptive header-aware and token-classified heuristic parser.
     */
    fun parseScanResultLines(
        lines: List<String>,
        savedPasswords: Map<String, String> = emptyMap(),
        connectedDetails: Map<String, NetworkUtils.WifiConnectionDetails> = emptyMap(),
        primarySsid: String? = null,
        primaryBssid: String? = null,
        primaryFreq: Int = 0,
        secondarySsid: String? = null,
        secondaryBssid: String? = null,
        secondaryFreq: Int = 0,
        isSecConnected: Boolean = false,
        frameworkScanResults: List<android.net.wifi.ScanResult>? = null,
        savedNetworkSsids: Set<String> = savedPasswords.keys
    ): List<WifiScanItem> {
        val itemsMap = mutableMapOf<String, WifiScanItem>()

        // 1. Index framework ScanResults by lowercased BSSID
        val frameworkRssiMap = mutableMapOf<String, Int>()
        frameworkScanResults?.forEach { sr ->
            val b = sr.BSSID?.lowercase()
            if (!b.isNullOrEmpty() && sr.level in -120..0) {
                frameworkRssiMap[b] = sr.level
            }
        }

        // 2. Identify header column indices if header exists
        val headerLine = lines.firstOrNull {
            val upper = it.uppercase()
            upper.contains("BSSID") && (upper.contains("SSID") || upper.contains("FREQ") || upper.contains("RSSI"))
        }
        val headerCols = headerLine?.trim()?.split(Regex("""[\s/|\t]+"""))?.filter { it.isNotBlank() }?.map { it.uppercase() } ?: emptyList()
        val headerBssidIdx = headerCols.indexOfFirst { it.contains("BSSID") }
        val headerFreqIdx = headerCols.indexOfFirst { it.contains("FREQ") }
        val headerRssiIdx = headerCols.indexOfFirst { it.contains("RSSI") || it.contains("LEVEL") || it.contains("SIGNAL") }
        val headerFlagsIdx = headerCols.indexOfFirst { it.contains("FLAG") || it.contains("CAPAB") }
        val headerAgeIdx = headerCols.indexOfFirst { it.contains("AGE") }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Must contain a valid MAC address (BSSID)
            val bssidMatch = Regex("""\b([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b""").find(trimmed) ?: continue
            val bssid = bssidMatch.value.lowercase()
            val tokens = trimmed.split(Regex("""\s+""")).toMutableList()
            if (tokens.size < 3) continue

            // Skip if this is the header line itself
            if (trimmed.equals(headerLine?.trim(), ignoreCase = true)) continue

            // A. Locate Frequency
            var freq = 0
            var freqTokenIndex = -1
            if (headerFreqIdx in tokens.indices) {
                val f = tokens[headerFreqIdx].toIntOrNull()
                if (f != null && (f in 2400..7200 || f in 1..14)) {
                    freq = if (f in 1..14) (if (f == 14) 2484 else 2407 + f * 5) else f
                    freqTokenIndex = headerFreqIdx
                }
            }
            if (freq == 0) {
                for (i in tokens.indices) {
                    if (tokens[i].equals(bssid, ignoreCase = true)) continue
                    val f = tokens[i].toIntOrNull()
                    if (f != null && f in 2400..7200) {
                        freq = f
                        freqTokenIndex = i
                        break
                    }
                }
            }

            // B. Locate RSSI
            var parsedRssi: Int? = null
            var rssiTokenIndex = -1
            if (headerRssiIdx in tokens.indices) {
                parsedRssi = tryParseRssi(tokens[headerRssiIdx])
                if (parsedRssi != null) {
                    rssiTokenIndex = headerRssiIdx
                }
            }
            if (parsedRssi == null) {
                for (i in tokens.indices) {
                    if (i == freqTokenIndex) continue
                    if (tokens[i].equals(bssid, ignoreCase = true)) continue
                    val r = tryParseRssi(tokens[i])
                    if (r != null) {
                        parsedRssi = r
                        rssiTokenIndex = i
                        break
                    }
                }
            }

            // C. Locate Capabilities / Flags
            var flags = ""
            var flagsTokenIndex = -1
            if (headerFlagsIdx in tokens.indices && tokens[headerFlagsIdx].startsWith("[")) {
                flags = tokens[headerFlagsIdx]
                flagsTokenIndex = headerFlagsIdx
            } else {
                for (i in tokens.indices.reversed()) {
                    if (tokens[i].startsWith("[") && tokens[i].endsWith("]")) {
                        flags = tokens[i]
                        flagsTokenIndex = i
                        break
                    }
                }
                if (flags.isEmpty()) {
                    for (i in tokens.indices.reversed()) {
                        val u = tokens[i].uppercase()
                        if (u.contains("WPA") || u.contains("RSN") || u.contains("ESS") || u.contains("PSK") || u.contains("SAE")) {
                            flags = tokens[i]
                            flagsTokenIndex = i
                            break
                        }
                    }
                }
            }
            if (flags.isEmpty()) flags = "[ESS]"

            // D. Locate Age token if present
            var ageTokenIndex = -1
            if (headerAgeIdx in tokens.indices) {
                ageTokenIndex = headerAgeIdx
            } else {
                for (i in tokens.indices) {
                    if (i == freqTokenIndex || i == rssiTokenIndex || i == flagsTokenIndex) continue
                    val tok = tokens[i]
                    if (tok.startsWith(">") || (tok.contains(".") && tok.toFloatOrNull() != null && tok.toFloatOrNull()!! > 0)) {
                        ageTokenIndex = i
                        break
                    }
                }
            }

            // E. Extract SSID from all remaining tokens
            val bssidIndex = tokens.indexOfFirst { it.equals(bssid, ignoreCase = true) }
            val excludedIndices = setOf(bssidIndex, freqTokenIndex, rssiTokenIndex, flagsTokenIndex, ageTokenIndex)
            val ssidTokens = tokens.filterIndexed { index, _ -> index !in excludedIndices }
            val ssid = ssidTokens.joinToString(" ").trim('"', ' ')

            if (ssid.isEmpty() || ssid == "<unknown ssid>") {
                continue
            }

            // F. Secondary enrichment for RSSI:
            val isPrimary = DualWifiBandHelper.matchesConnected(
                candidateSsid = ssid,
                candidateBssid = bssid,
                candidateFreq = freq,
                connectedSsid = primarySsid,
                connectedBssid = primaryBssid,
                connectedFreq = primaryFreq
            )
            val isSecondary = !isPrimary && isSecConnected && DualWifiBandHelper.matchesConnected(
                candidateSsid = ssid,
                candidateBssid = bssid,
                candidateFreq = freq,
                connectedSsid = secondarySsid,
                connectedBssid = secondaryBssid,
                connectedFreq = secondaryFreq
            )

            var finalRssi = parsedRssi
                ?: frameworkRssiMap[bssid]
                ?: -99

            // If this is an active primary/secondary connection, merge live connected RSSI from cmd wifi status
            if (isPrimary || isSecondary) {
                val connRssi = connectedDetails.values.firstOrNull {
                    (!it.bssid.isNullOrEmpty() && it.bssid.equals(bssid, ignoreCase = true)) ||
                    it.ssid.equals(ssid, ignoreCase = true)
                }?.rssi
                if (connRssi != null && connRssi in -120..-1) {
                    finalRssi = connRssi
                }
            }

            // Key by SSID + Radio Band (allows both 2.4G and 5G bands of the same SSID to be selectable)
            val bandKey = when {
                freq in 2400..2500 -> "2.4G"
                freq in 4900..5350 -> "5G_LOW"
                freq in 5351..5900 -> "5G_HIGH"
                freq in 5925..7125 -> "6G"
                else -> "5G"
            }
            val dedupKey = "$ssid#$bandKey"

            val isSaved = ssid in savedNetworkSsids
            val savedPass = savedPasswords[ssid]

            val candidate = WifiScanItem(
                ssid = ssid,
                bssid = bssid,
                frequencyMhz = freq,
                rssi = finalRssi,
                capabilities = flags,
                isSaved = isSaved,
                savedPassword = savedPass,
                isPrimary = isPrimary,
                isSecondary = isSecondary
            )

            val existing = itemsMap[dedupKey]
            if (existing == null ||
                candidate.isPrimary || candidate.isSecondary ||
                (!existing.isPrimary && !existing.isSecondary && candidate.rssi > existing.rssi)) {
                itemsMap[dedupKey] = candidate
            }
        }

        // G. Fallback: If primary connected Wi-Fi was not in scan results, synthesize it so it's always visible
        if (!primarySsid.isNullOrEmpty() && primarySsid != "<unknown ssid>") {
            val primBandKey = when {
                primaryFreq in 2400..2500 -> "2.4G"
                primaryFreq in 4900..5350 -> "5G_LOW"
                primaryFreq in 5351..5900 -> "5G_HIGH"
                primaryFreq in 5925..7125 -> "6G"
                else -> "5G"
            }
            val primDedupKey = "$primarySsid#$primBandKey"
            if (!itemsMap.containsKey(primDedupKey)) {
                val connRssi = connectedDetails.values.firstOrNull { it.ssid == primarySsid }?.rssi
                    ?.takeIf { it in -120..0 } ?: -60
                itemsMap[primDedupKey] = WifiScanItem(
                    ssid = primarySsid,
                    bssid = primaryBssid ?: "02:00:00:00:00:00",
                    frequencyMhz = primaryFreq,
                    rssi = connRssi,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    isSaved = true,
                    savedPassword = savedPasswords[primarySsid],
                    isPrimary = true,
                    isSecondary = false
                )
            }
        }

        // H. Fallback: If secondary connected Wi-Fi was not in scan results, synthesize it so it's always visible
        if (isSecConnected && !secondarySsid.isNullOrEmpty() && secondarySsid != "<unknown ssid>") {
            val secBandKey = when {
                secondaryFreq in 2400..2500 -> "2.4G"
                secondaryFreq in 4900..5350 -> "5G_LOW"
                secondaryFreq in 5351..5900 -> "5G_HIGH"
                secondaryFreq in 5925..7125 -> "6G"
                else -> "5G"
            }
            val secDedupKey = "$secondarySsid#$secBandKey"
            if (!itemsMap.containsKey(secDedupKey)) {
                val connRssi = connectedDetails.values.firstOrNull { it.ssid == secondarySsid }?.rssi
                    ?.takeIf { it in -120..0 } ?: -60
                itemsMap[secDedupKey] = WifiScanItem(
                    ssid = secondarySsid,
                    bssid = secondaryBssid ?: "02:00:00:00:00:00",
                    frequencyMhz = secondaryFreq,
                    rssi = connRssi,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    isSaved = true,
                    savedPassword = savedPasswords[secondarySsid],
                    isPrimary = false,
                    isSecondary = true
                )
            }
        }

        return itemsMap.values.toList()
    }

    /** Sorts scanned networks with active secondary/primary first, then complementary saved networks. */
    fun sortScanResults(items: List<WifiScanItem>, primaryFreq: Int): List<WifiScanItem> =
        items.sortedWith(
            compareByDescending<WifiScanItem> { it.isSecondary }
                .thenByDescending { it.isPrimary }
                .thenByDescending { DualWifiBandHelper.isBandRecommended(it.frequencyMhz, primaryFreq) && it.isSaved }
                .thenByDescending { DualWifiBandHelper.isBandRecommended(it.frequencyMhz, primaryFreq) }
                .thenByDescending { it.isSaved }
                .thenByDescending { it.rssi }
        )

    /**
     * Re-evaluates primary/secondary flags and ordering on cached scan items after connection state changes
     * (e.g., switching to another secondary AP or disconnecting) without waiting for another scan.
     */
    fun reconcileConnectedStates(
        items: List<WifiScanItem>,
        primarySsid: String?,
        primaryBssid: String?,
        primaryFreq: Int,
        secondarySsid: String?,
        secondaryBssid: String?,
        secondaryFreq: Int,
        isSecondaryConnected: Boolean
    ): List<WifiScanItem> {
        if (items.isEmpty()) return items
        var rolesChanged = false
        val updated = items.map { item ->
            val isPrimary = DualWifiBandHelper.matchesConnected(
                candidateSsid = item.ssid,
                candidateBssid = item.bssid,
                candidateFreq = item.frequencyMhz,
                connectedSsid = primarySsid,
                connectedBssid = primaryBssid,
                connectedFreq = primaryFreq
            )
            val isSecondary = !isPrimary && isSecondaryConnected && DualWifiBandHelper.matchesConnected(
                candidateSsid = item.ssid,
                candidateBssid = item.bssid,
                candidateFreq = item.frequencyMhz,
                connectedSsid = secondarySsid,
                connectedBssid = secondaryBssid,
                connectedFreq = secondaryFreq
            )
            if (item.isPrimary == isPrimary && item.isSecondary == isSecondary) item
            else {
                rolesChanged = true
                item.copy(isPrimary = isPrimary, isSecondary = isSecondary)
            }
        }
        return if (rolesChanged) sortScanResults(updated, primaryFreq) else items
    }

    /**
     * Reads saved Wi-Fi networks and passwords from WifiConfigStore.xml using root.
     */
    suspend fun getSavedWifiPasswords(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        val paths = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml"
        )
        for (path in paths) {
            val content = SuHelper.readTextAsRoot(path) ?: continue
            if (content.isNotEmpty()) {
                val networkBlocks = content.split("<Network>")
                for (block in networkBlocks) {
                    val ssidMatch = Regex("""<string name="SSID">&quot;([^&]+)&quot;</string>""").find(block)
                    val pskMatch = Regex("""<string name="PreSharedKey">&quot;([^&]+)&quot;</string>""").find(block)
                    val ssid = ssidMatch?.groupValues?.get(1)
                    val psk = pskMatch?.groupValues?.get(1)
                    if (!ssid.isNullOrEmpty() && !psk.isNullOrEmpty()) {
                        result[ssid] = psk
                    }
                }
            }
            if (result.isNotEmpty()) break
        }
        result
    }

    /**
     * Retrieves the framework network ID for a given SSID from `cmd wifi list-networks`.
     */
    suspend fun getNetworkIdForSsid(ssid: String): Int? = withContext(Dispatchers.IO) {
        getSavedNetworkIds()[ssid.trim().trim('"')]
    }

    suspend fun getSavedNetworkIds(): Map<String, Int> {
        val lines = SuHelper.executeShellWithOutput("cmd wifi list-networks", timeoutSeconds = 3)
        return parseSavedNetworkListing(lines)
    }

    fun parseSavedNetworkListing(lines: List<String>): Map<String, Int> {
        check(lines.any { it.trim() == "No networks" || Regex("^Network\\s+Id\\s+SSID", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }) {
            "Unable to read system Wi-Fi configurations"
        }
        return parseSavedNetworkIds(lines)
    }

    /**
     * Parses the network ID for the specified SSID from `cmd wifi list-networks` output lines.
     */
    fun parseNetworkIdFromList(lines: List<String>, targetSsid: String): Int? {
        return parseSavedNetworkIds(lines)[targetSsid.trim().trim('"')]
    }

    fun parseSavedNetworkIds(lines: List<String>): Map<String, Int> {
        val networks = mutableMapOf<String, Int>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Network Id", ignoreCase = true)) continue
            // Security is the final column; words such as "open" may also occur inside an SSID.
            val match = Regex("""^(\d+)\s+(.+?)\s+\S+$""").matchEntire(trimmed)
            if (match != null) {
                val id = match.groupValues[1].toIntOrNull()
                val netSsid = match.groupValues[2].trim().trim('"')
                if (id != null) networks[netSsid] = id
            }
        }
        return networks
    }
}

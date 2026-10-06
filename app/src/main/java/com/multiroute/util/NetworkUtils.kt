package com.multiroute.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import com.multiroute.data.TestServerManager
import com.multiroute.model.NetworkChannel
import com.multiroute.model.TestServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtils {

    /**
     * Band label of a Wi-Fi frequency, used as-is because "2.4 GHz" / "5 GHz" / "6 GHz" read the same in
     * every language. Empty when the frequency is unknown.
     */
    fun wifiBandLabel(frequencyMhz: Int): String = when {
        frequencyMhz in 2400..2500 -> "2.4 GHz"
        frequencyMhz in 4900..5895 -> "5 GHz"
        frequencyMhz in 5925..7125 -> "6 GHz"
        else -> ""
    }

    /** Channel number of a Wi-Fi frequency, or 0 when it does not map to a known band. */
    fun wifiChannelNumber(frequencyMhz: Int): Int = when {
        frequencyMhz == 2484 -> 14 // Channel 14 is the one 2.4 GHz exception (Japan).
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5000..5895 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5925..7125 -> (frequencyMhz - 5950) / 5
        else -> 0
    }

    /**
     * Frequencies of the connected Wi-Fi interfaces, parsed from `dumpsys connectivity`.
     *
     * Only used to fill gaps: `WifiInfo.frequency` is the primary source, but this app holds no location
     * permission, so on some ROMs the transport info is redacted. The dump carries the same LinkProperties
     * the platform uses and needs nothing beyond root, which the app already has.
     */
    fun getWifiFrequencies(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        try {
            val process = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "dumpsys connectivity 2>/dev/null | grep -E 'InterfaceName|Frequency'"))
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            var iface: String? = null
            for (line in text.split("InterfaceName: ").drop(1)) {
                iface = line.substringBefore(' ').trim().ifEmpty { null } ?: continue
                val mhz = Regex("""Frequency:\s*(\d+)\s*MHz""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (mhz != null && mhz > 0) result[iface] = mhz
            }
        } catch (_: Exception) {
        }
        return result
    }

    /**
     * Resolves currently connected Wi-Fi SSIDs per interface (wlan0, wlan1)
     * using dumpsys / shell status with fallback.
     */
    fun getConnectedWifiSsids(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys wifi | grep 'current SSID(s)'"))
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            for (line in lines) {
                if (line.isNotEmpty()) {
                    val regex = Regex("""\{iface=([^,]+),ssid="([^"]+)"\}""")
                    regex.findAll(line).forEach { match ->
                        val iface = match.groupValues[1].trim()
                        val ssid = match.groupValues[2].trim()
                        if (iface.isNotEmpty() && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                            result[iface] = ssid
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: If still empty, try parsing "cmd wifi status"
        if (result.isEmpty()) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd wifi status"))
                val lines = process.inputStream.bufferedReader().use { it.readLines() }
                process.errorStream.bufferedReader().use { it.readText() }
                process.waitFor()
                var currentIface: String? = null
                lines.forEach { line ->
                    if (line.contains("iface=")) {
                        val iface = line.substringAfter("iface=").substringBefore(" ").substringBefore("}")
                        currentIface = iface
                    } else if (line.contains("Wifi is connected to \"") && currentIface != null) {
                        val ssid = line.substringAfter("Wifi is connected to \"").substringBefore("\"")
                        if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                            result[currentIface] = ssid
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return result
    }

    /**
     * Dynamically discovers all active network channels present on the device
     * (WLAN0, WLAN1, Cellular 5G/4G, Ethernet, USB tethering, VPN, etc.).
     */
    suspend fun getActiveChannels(context: Context): List<NetworkChannel> = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeDefault = cm.activeNetwork
        val result = mutableListOf<NetworkChannel>()
        val wifiSsids = getConnectedWifiSsids()

        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val link = cm.getLinkProperties(network) ?: continue
            val iface = link.interfaceName ?: continue
            if (iface.isEmpty() || iface == "lo" || iface.startsWith("dummy") || iface.startsWith("ifb")) continue

            val transportType: String
            val shortName: String
            var wifiSsid: String? = null
            var wifiFrequency = 0

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    transportType = "WLAN"
                    val wifiInfo = caps.transportInfo as? WifiInfo
                    val directSsid = wifiInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                    val detectedSsid = directSsid ?: wifiSsids[iface]
                    wifiSsid = detectedSsid
                    // Frequency is not redacted for the app's own networks; 0 means "not reported".
                    wifiFrequency = wifiInfo?.frequency ?: 0
                    shortName = if (detectedSsid != null) "$iface ($detectedSsid)" else iface
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val isInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (!isInternet) continue // Ignore internal IMS bearer
                    transportType = "cellular"
                    shortName = iface
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    transportType = "ethernet"
                    shortName = iface
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                    transportType = "vpn"
                    shortName = iface
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> {
                    transportType = "bluetooth"
                    shortName = iface
                }
                else -> {
                    transportType = "other"
                    shortName = iface
                }
            }

            val allAddrs = link.linkAddresses.map { it.toString() }
            val primaryIp = link.linkAddresses
                .firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
                ?: link.linkAddresses.firstOrNull()?.address?.hostAddress
            val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.hasGateway() }?.gateway?.hostAddress
            val dns = link.dnsServers.mapNotNull { it.hostAddress }
            val domains = link.domains
            val mtu = link.mtu
            val downBandwidth = caps.linkDownstreamBandwidthKbps
            val upBandwidth = caps.linkUpstreamBandwidthKbps
            val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val isDefault = (network == activeDefault)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            result.add(
                NetworkChannel(
                    id = iface,
                    interfaceName = iface,
                    transportType = transportType,
                    shortName = shortName,
                    ssid = wifiSsid,
                    frequencyMhz = wifiFrequency,
                    ipAddress = primaryIp,
                    allIpAddresses = allAddrs,
                    gateway = gateway,
                    dnsServers = dns,
                    domains = domains,
                    mtu = mtu,
                    downlinkBps = downBandwidth,
                    uplinkBps = upBandwidth,
                    isMetered = isMetered,
                    isDefault = isDefault,
                    isValidated = isValidated
                )
            )
        }

        // Fill missing Wi-Fi frequencies from the shell dump, but only when needed: `dumpsys` costs a
        // process spawn, and the platform usually reports the frequency itself.
        if (result.any { it.transportType == "WLAN" && it.frequencyMhz <= 0 }) {
            val fromShell = getWifiFrequencies()
            for (i in result.indices) {
                val ch = result[i]
                val mhz = fromShell[ch.interfaceName] ?: continue
                if (ch.transportType == "WLAN" && ch.frequencyMhz <= 0) {
                    result[i] = ch.copy(frequencyMhz = mhz)
                }
            }
        }

        // Sort: default network first, then wifi, cellular, ethernet
        result.sortWith(
            compareByDescending<NetworkChannel> { it.isDefault }
                .thenBy { it.transportType }
                .thenBy { it.interfaceName }
        )

        result
    }

    /**
     * Parses the response from public IP query servers (ipip, cip.cc, ipify, ifconfig, json, custom).
     */
    fun parseServerResponse(url: String, rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return ERR_EMPTY_BODY

        // 1. myip.ipip.net 格式
        if (trimmed.contains("当前 IP：")) {
            val ip = trimmed.substringAfter("当前 IP：", "").substringBefore(" ").trim()
            val loc = trimmed.substringAfter("来自于：", "").trim()
            return if (ip.isNotEmpty() && loc.isNotEmpty()) "$ip ($loc)" else trimmed
        }

        // 2. cip.cc 格式
        if (trimmed.contains("IP\t:") || trimmed.contains("IP :") || trimmed.contains("IP:")) {
            val lines = trimmed.lines()
            val ip = lines.firstOrNull { it.contains("IP") }?.substringAfter(":")?.trim()
            val addr = lines.firstOrNull { it.contains("地址") }?.substringAfter(":")?.trim()
            val isp = lines.firstOrNull { it.contains("数据二") }?.substringAfter(":")?.trim()
            val loc = listOfNotNull(addr?.takeIf { it.isNotEmpty() }, isp?.takeIf { it.isNotEmpty() }).joinToString(" · ")
            return if (!ip.isNullOrEmpty()) {
                if (loc.isNotEmpty()) "$ip ($loc)" else ip
            } else trimmed
        }

        // 3. JSON 格式: {"ip":"..."} 或 {"origin":"..."} 或 {"client_ip":"..."}
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val jsonIpMatch = Regex(""""(?:ip|origin|query|client_ip)"\s*:\s*"([^"]+)"""").find(trimmed)
            if (jsonIpMatch != null) {
                return jsonIpMatch.groupValues[1]
            }
        }

        // 4. 单行纯 IP 或简短文本
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size == 1 && lines[0].length <= 80) {
            return lines[0]
        }

        // 5. 正则提取 IPv4 地址
        val ipRegex = Regex("""\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b""")
        val match = ipRegex.find(trimmed)
        if (match != null) {
            return match.value
        }

        // 6. 截取前 80 字符作为结果
        return trimmed.take(80)
    }

    /**
     * Tests public internet connectivity on a single specific network channel.
     */
    suspend fun testSingleChannelPublicIp(
        context: Context,
        channel: NetworkChannel,
        config: TestServerConfig? = null
    ): String = withContext(Dispatchers.IO) {
        val serverConfig = config ?: TestServerManager.getConfig(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val targetNetwork = cm.allNetworks.firstOrNull { net ->
            cm.getLinkProperties(net)?.interfaceName == channel.interfaceName
        }
        if (targetNetwork == null) {
            ERR_IFACE_OFFLINE
        } else {
            fetchPublicIpViaNetwork(targetNetwork, serverConfig)
        }
    }

    /**
     * Concurrently tests public internet connectivity on all dynamically detected channels.
     */
    suspend fun testChannelsConcurrently(
        context: Context,
        channels: List<NetworkChannel>,
        config: TestServerConfig? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val serverConfig = config ?: TestServerManager.getConfig(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ifaceToNetworkMap = mutableMapOf<String, Network>()

        for (network in cm.allNetworks) {
            val link = cm.getLinkProperties(network)
            val iface = link?.interfaceName
            if (iface != null) {
                ifaceToNetworkMap[iface] = network
            }
        }

        val deferredList = channels.map { channel ->
            async {
                val net = ifaceToNetworkMap[channel.interfaceName]
                val ipOrError = if (net != null) {
                    fetchPublicIpViaNetwork(net, serverConfig)
                } else {
                    ERR_IFACE_OFFLINE
                }
                channel.id to ipOrError
            }
        }

        deferredList.awaitAll().toMap()
    }

    private fun fetchPublicIpViaNetwork(network: Network, config: TestServerConfig): String {
        val targetUrl = config.activeUrl
        val isCustom = config.isCustom

        // 1. 优先使用用户配置的主测试节点
        try {
            val url = URL(targetUrl)
            val conn = network.openConnection(url) as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "curl/7.88.1")
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseServerResponse(targetUrl, text)
                if (parsed.isNotEmpty()) return parsed
            } else {
                if (isCustom) {
                    return ERR_CUSTOM_HTTP + code
                }
            }
        } catch (e: Exception) {
            if (isCustom) {
                return ERR_CUSTOM_CONNECT + (e.localizedMessage ?: e.message ?: "")
            }
        }

        // 2. 预设服务器容错回退机制（仅在非自定义模式或预设节点临时故障时触发）
        val fallbackUrls = listOf("https://myip.ipip.net", "https://cip.cc", "https://ifconfig.me/ip")
            .filter { it != targetUrl }

        for (fallbackUrl in fallbackUrls) {
            try {
                val url = URL(fallbackUrl)
                val conn = network.openConnection(url) as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "curl/7.88.1")
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val parsed = parseServerResponse(fallbackUrl, text)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (_: Exception) {}
        }

        return ERR_TIMEOUT
    }

    /**
     * Display label of a channel.
     *
     * The transport values above are a contract with the code that compares them (icons, sorting), so
     * they stay language-neutral and the label is built here instead, following the app language.
     */
    fun channelLabel(
        context: Context,
        transportType: String,
        iface: String,
        ssid: String? = null
    ): String {
        val base = when (transportType) {
            "WLAN" -> context.getString(com.multiroute.R.string.transport_wlan, iface)
            "cellular" -> context.getString(com.multiroute.R.string.transport_cellular, iface)
            "ethernet" -> context.getString(com.multiroute.R.string.transport_ethernet, iface)
            "vpn" -> context.getString(com.multiroute.R.string.transport_vpn, iface)
            "bluetooth" -> context.getString(com.multiroute.R.string.transport_bluetooth, iface)
            else -> context.getString(com.multiroute.R.string.transport_other, iface)
        }
        return if (ssid.isNullOrEmpty()) base else "$base · $ssid"
    }

    // Egress test results are language-neutral codes so the UI can colour and translate them; anything
    // that is not a code is a real result (an IP, possibly with a location).
    const val ERR_PREFIX = "ERR_"
    const val ERR_EMPTY_BODY = "ERR_EMPTY_BODY"
    const val ERR_IFACE_OFFLINE = "ERR_IFACE_OFFLINE"
    const val ERR_TIMEOUT = "ERR_TIMEOUT"
    const val ERR_CUSTOM_HTTP = "ERR_CUSTOM_HTTP:"
    const val ERR_CUSTOM_CONNECT = "ERR_CUSTOM_CONNECT:"
    const val ERR_EXCEPTION = "ERR_EXCEPTION:"

    fun isFailure(result: String?): Boolean = result != null && result.startsWith(ERR_PREFIX)

    /** Renders a result for display: codes become a translated message, anything else is shown as is. */
    fun egressResultLabel(context: Context, result: String): String = when {
        result == ERR_EMPTY_BODY -> context.getString(com.multiroute.R.string.egress_err_empty)
        result == ERR_IFACE_OFFLINE -> context.getString(com.multiroute.R.string.egress_err_iface_offline)
        result == ERR_TIMEOUT -> context.getString(com.multiroute.R.string.egress_err_timeout)
        result.startsWith(ERR_CUSTOM_HTTP) ->
            context.getString(com.multiroute.R.string.egress_err_custom_http, result.removePrefix(ERR_CUSTOM_HTTP))
        result.startsWith(ERR_CUSTOM_CONNECT) -> {
            val detail = result.removePrefix(ERR_CUSTOM_CONNECT)
            context.getString(
                com.multiroute.R.string.egress_err_custom_connect,
                detail.ifEmpty { context.getString(com.multiroute.R.string.egress_err_unknown) }
            )
        }
        result.startsWith(ERR_EXCEPTION) -> {
            val detail = result.removePrefix(ERR_EXCEPTION)
            context.getString(
                com.multiroute.R.string.egress_err_failed,
                detail.ifEmpty { context.getString(com.multiroute.R.string.egress_err_unknown) }
            )
        }
        else -> result
    }
}
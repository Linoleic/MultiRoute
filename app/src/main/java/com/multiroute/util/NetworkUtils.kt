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
            val displayName: String
            val shortName: String
            var wifiSsid: String? = null

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    transportType = "WLAN"
                    val wifiInfo = caps.transportInfo as? WifiInfo
                    val directSsid = wifiInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                    val detectedSsid = directSsid ?: wifiSsids[iface]
                    wifiSsid = detectedSsid

                    displayName = if (detectedSsid != null) "WLAN ($iface) · $detectedSsid" else "WLAN ($iface)"
                    shortName = if (detectedSsid != null) "$iface ($detectedSsid)" else iface
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val isInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (!isInternet) continue // Ignore internal IMS bearer
                    transportType = "蜂窝"
                    displayName = "蜂窝网络 ($iface)"
                    shortName = iface
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    transportType = "以太网"
                    displayName = "有线以太网 ($iface)"
                    shortName = iface
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                    transportType = "VPN"
                    displayName = "VPN通道 ($iface)"
                    shortName = iface
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> {
                    transportType = "蓝牙"
                    displayName = "蓝牙网络 ($iface)"
                    shortName = iface
                }
                else -> {
                    transportType = "其他"
                    displayName = "通道 ($iface)"
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
                    displayName = displayName,
                    shortName = shortName,
                    ssid = wifiSsid,
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
        if (trimmed.isEmpty()) return "返回内容为空"

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
            "接口离线"
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
                    "接口离线"
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
                    return "自定义节点 HTTP $code"
                }
            }
        } catch (e: Exception) {
            if (isCustom) {
                return "自定义节点连接失败: ${e.localizedMessage ?: e.message ?: "未知异常"}"
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

        return "测试超时或接口无公网连通性"
    }
}

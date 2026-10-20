package com.multiroute.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import com.multiroute.util.NetworkUtils
import com.multiroute.util.SuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

enum class DualWifiScheme(val id: String, val label: String) {
    AUTO("auto", "自动"),
    AOSP_OVERLAY("aosp_overlay", "AOSP 标准多互联网"),
    XIAOMI_SLAVE("xiaomi_slave", "小米 / HyperOS 专用"),
    ROOT_DIRECT("root_direct", "通用 Root 直连");

    companion object {
        fun fromId(id: String?): DualWifiScheme =
            values().firstOrNull { it.id == id } ?: AUTO
    }
}

enum class RomType(val label: String) {
    AOSP_PIXEL("AOSP / Pixel 类原生"),
    XIAOMI_HYPEROS("小米 MIUI / HyperOS"),
    COLOROS_OPPO("OPPO / 一加 ColorOS"),
    ORIGINOS_VIVO("vivo / iQOO OriginOS"),
    SAMSUNG_ONEUI("三星 One UI"),
    GENERIC_OEM("通用 Android OEM")
}

data class DeviceRomInfo(
    val romType: RomType,
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val apiLevel: Int,
    val socModel: String
)

data class DualWifiCapability(
    val hardwareSupported: Boolean = false,
    val configEnabled: Boolean = false,
    val modeActive: Boolean = false,
    val primaryIface: String? = null,
    val secondaryIface: String? = null,
    val detail: String = "",
    val romInfo: DeviceRomInfo = DeviceRomInfo(
        romType = RomType.AOSP_PIXEL,
        model = runCatching { Build.MODEL }.getOrNull().orEmpty(),
        manufacturer = runCatching { Build.MANUFACTURER }.getOrNull().orEmpty(),
        androidVersion = runCatching { Build.VERSION.RELEASE }.getOrNull().orEmpty(),
        apiLevel = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0),
        socModel = ""
    ),
    val configuredScheme: DualWifiScheme = DualWifiScheme.AUTO,
    val effectiveScheme: DualWifiScheme = DualWifiScheme.AUTO,
    val wifiChipset: String = "",
    val overlayStatus: String = "",
    val primaryDetails: String = "",
    val secondaryDetails: String = ""
) {
    val isFullyReady: Boolean
        get() = hardwareSupported && (configEnabled || effectiveScheme == DualWifiScheme.XIAOMI_SLAVE || effectiveScheme == DualWifiScheme.ROOT_DIRECT) && (modeActive || effectiveScheme == DualWifiScheme.XIAOMI_SLAVE || effectiveScheme == DualWifiScheme.ROOT_DIRECT)
}

data class WifiScanItem(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val rssi: Int,
    val capabilities: String,
    val isSaved: Boolean = false,
    val savedPassword: String? = null,
    val isPrimary: Boolean = false,
    val isSecondary: Boolean = false
) {
    val is24GHz: Boolean get() = frequencyMhz in 2400..2500
    val is5GHz: Boolean get() = frequencyMhz in 4900..5900
    val is6GHz: Boolean get() = frequencyMhz in 5925..7125
    val isSecure: Boolean get() = capabilities.contains("PSK") || capabilities.contains("SAE") || capabilities.contains("EAP")

    val bandLabel: String get() = when {
        frequencyMhz in 2400..2500 -> "2.4 GHz"
        frequencyMhz in 4900..5320 -> "5 GHz (低频)"
        frequencyMhz in 5321..5900 -> "5 GHz (高频)"
        frequencyMhz in 5925..7125 -> "6 GHz"
        else -> "5 GHz"
    }

    val channel: Int get() = when {
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz == 2484 -> 14
        frequencyMhz in 5160..5885 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5 + 1
        else -> 0
    }

    val signalQualityText: String get() = when (level) {
        4 -> "极佳 (满格)"
        3 -> "良好"
        2 -> "一般"
        1 -> "较弱"
        else -> "微弱"
    }

    /** Returns signal strength level 0..4 */
    val level: Int get() = when {
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
}

data class DualWifiStatus(
    val capability: DualWifiCapability = DualWifiCapability(),
    val isScanning: Boolean = false,
    val scanResults: List<WifiScanItem> = emptyList(),
    val isConnecting: Boolean = false,
    val connectingSsid: String? = null,
    val connectingBssid: String? = null,
    val secondaryConnected: Boolean = false,
    val secondarySsid: String? = null,
    val secondaryBssid: String? = null,
    val secondaryIp: String? = null,
    val secondaryIface: String? = null,
    val secondaryFreq: Int = 0,
    val secondaryRssi: Int = 0,
    val primarySsid: String? = null,
    val primaryBssid: String? = null,
    val primaryIp: String? = null,
    val primaryIface: String? = null,
    val primaryFreq: Int = 0,
    val primaryRssi: Int = 0,
    val errorMessage: String? = null
)

object DualWifiManager {
    private const val TAG = "MultiRoute-DualWifi"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(DualWifiStatus())
    val status: StateFlow<DualWifiStatus> = _status.asStateFlow()

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null

    /**
     * Detects current device ROM / system customization layer.
     * Supports parameter overrides for test simulations.
     */
    fun detectRomType(
        manufacturer: String? = null,
        brand: String? = null,
        model: String? = null,
        androidRelease: String? = null,
        apiLevel: Int? = null,
        socModelOverride: String? = null,
        miuiPropOverride: String? = null,
        oplusPropOverride: String? = null,
        vivoPropOverride: String? = null
    ): DeviceRomInfo {
        val manu = (manufacturer ?: runCatching { Build.MANUFACTURER }.getOrNull()).orEmpty()
        val b = (brand ?: runCatching { Build.BRAND }.getOrNull()).orEmpty()
        val mdl = (model ?: runCatching { Build.MODEL }.getOrNull()).orEmpty()
        val rel = (androidRelease ?: runCatching { Build.VERSION.RELEASE }.getOrNull()).orEmpty()
        val api = apiLevel ?: runCatching { Build.VERSION.SDK_INT }.getOrDefault(0)

        val soc = socModelOverride ?: runCatching {
            SuHelper.executeShellWithOutput("getprop ro.soc.model 2>/dev/null", timeoutSeconds = 2).firstOrNull().orEmpty()
                .ifEmpty { SuHelper.executeShellWithOutput("getprop ro.board.platform 2>/dev/null", timeoutSeconds = 2).firstOrNull().orEmpty() }
        }.getOrDefault("")

        val miuiProp = miuiPropOverride ?: runCatching {
            SuHelper.executeShellWithOutput("getprop ro.miui.ui.version.name 2>/dev/null", timeoutSeconds = 2).firstOrNull().orEmpty()
        }.getOrDefault("")
        val oplusProp = oplusPropOverride ?: runCatching {
            SuHelper.executeShellWithOutput("getprop ro.build.version.oplusrom 2>/dev/null", timeoutSeconds = 2).firstOrNull().orEmpty()
        }.getOrDefault("")
        val vivoProp = vivoPropOverride ?: runCatching {
            SuHelper.executeShellWithOutput("getprop ro.vivo.os.version 2>/dev/null", timeoutSeconds = 2).firstOrNull().orEmpty()
        }.getOrDefault("")

        val manuLower = manu.lowercase()
        val bLower = b.lowercase()

        val romType = when {
            manuLower.contains("xiaomi") || manuLower.contains("redmi") || bLower.contains("poco") || miuiProp.isNotEmpty() -> RomType.XIAOMI_HYPEROS
            manuLower.contains("oppo") || manuLower.contains("oneplus") || bLower.contains("realme") || oplusProp.isNotEmpty() -> RomType.COLOROS_OPPO
            manuLower.contains("vivo") || bLower.contains("iqoo") || vivoProp.isNotEmpty() -> RomType.ORIGINOS_VIVO
            manuLower.contains("samsung") -> RomType.SAMSUNG_ONEUI
            manuLower.contains("google") || bLower.contains("google") -> RomType.AOSP_PIXEL
            else -> RomType.GENERIC_OEM
        }

        return DeviceRomInfo(
            romType = romType,
            model = mdl,
            manufacturer = manu,
            androidVersion = rel,
            apiLevel = api,
            socModel = soc
        )
    }

    /**
     * Determines effective connection scheme given user preference and detected ROM.
     */
    fun getEffectiveScheme(configured: DualWifiScheme, rom: DeviceRomInfo): DualWifiScheme {
        return if (configured != DualWifiScheme.AUTO) {
            configured
        } else {
            when (rom.romType) {
                RomType.XIAOMI_HYPEROS -> DualWifiScheme.XIAOMI_SLAVE
                RomType.AOSP_PIXEL -> DualWifiScheme.AOSP_OVERLAY
                RomType.COLOROS_OPPO, RomType.ORIGINOS_VIVO -> DualWifiScheme.AOSP_OVERLAY
                RomType.SAMSUNG_ONEUI -> DualWifiScheme.ROOT_DIRECT
                RomType.GENERIC_OEM -> DualWifiScheme.AOSP_OVERLAY
            }
        }
    }

    /**
     * Probes Wi-Fi chipset architecture.
     * Supports parameter overrides for test simulations.
     */
    fun detectWifiChipset(lsmodOutput: String? = null): String {
        val lsmodStr = lsmodOutput ?: runCatching {
            SuHelper.executeShellWithOutput("lsmod 2>/dev/null", timeoutSeconds = 3).joinToString(" ")
        }.getOrDefault("")
        return when {
            lsmodStr.contains("bcmdhd4389", ignoreCase = true) -> "Broadcom BCM4389 (DBS 双射频)"
            lsmodStr.contains("bcmdhd", ignoreCase = true) -> "Broadcom Wi-Fi (DBS 双射频)"
            lsmodStr.contains("qca", ignoreCase = true) || lsmodStr.contains("wlan", ignoreCase = true) -> "Qualcomm FastConnect (DBS 并发)"
            lsmodStr.contains("wmt_drv", ignoreCase = true) || lsmodStr.contains("wlan_mt", ignoreCase = true) -> "MediaTek Filogic (DBS 并发)"
            else -> "标准双射频 Wi-Fi 芯片 (DBS 架构)"
        }
    }

    /**
     * Checks whether the device hardware and framework support Dual Wi-Fi (STA + STA Concurrency).
     */
    suspend fun checkCapability(context: Context): DualWifiCapability = withContext(Dispatchers.IO) {
        val lines = SuHelper.executeShellWithOutput(
            "dumpsys wifi 2>/dev/null | grep -E 'STA \\+ STA Concurrency Supported|config_wifiMultiStaMultiInternetConcurrencyEnabled|mStaConcurrencyMultiInternetMode' ; " +
            "ls -d /sys/class/net/wlan* 2>/dev/null",
            timeoutSeconds = 5
        )

        var hwSupported = false
        var configEnabled = false
        var modeActive = false
        val ifaces = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains("STA + STA Concurrency Supported: true", ignoreCase = true)) {
                hwSupported = true
            }
            if (trimmed.contains("config_wifiMultiStaMultiInternetConcurrencyEnabled, value: true", ignoreCase = true)) {
                configEnabled = true
            }
            if (trimmed.contains("mStaConcurrencyMultiInternetMode 2")) {
                modeActive = true
            }
            if (trimmed.startsWith("/sys/class/net/wlan")) {
                val iface = trimmed.substringAfterLast("/")
                ifaces.add(iface)
            }
        }

        // Secondary check: if both wlan0 and wlan1 exist in kernel, hardware has dual STA interfaces
        if (!hwSupported && ifaces.size >= 2 && ifaces.contains("wlan0") && ifaces.contains("wlan1")) {
            hwSupported = true
        }

        val romInfo = detectRomType()
        val configuredScheme = DualWifiScheme.fromId(com.multiroute.data.RouteConfigProvider.getDualWifiScheme(context))
        val effectiveScheme = getEffectiveScheme(configuredScheme, romInfo)
        val wifiChipset = detectWifiChipset()

        // Determine primary and secondary interfaces
        val activeDefaultIface = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            cm?.getLinkProperties(net)?.interfaceName
        }.getOrNull()

        val primary = activeDefaultIface?.takeIf { it.startsWith("wlan") }
            ?: ifaces.firstOrNull { it == "wlan1" } ?: ifaces.firstOrNull()
        val secondary = ifaces.firstOrNull { it != primary } ?: if (primary == "wlan1") "wlan0" else "wlan1"

        // Extract physical link details for active Wi-Fi interfaces
        val statusOutput = SuHelper.executeShellWithOutput("cmd wifi status 2>/dev/null", timeoutSeconds = 3)
        val ifaceDetails = parseWifiStatus(statusOutput)
        val primDet = primary?.let { ifaceDetails[it] } ?: ""
        val secDet = secondary.let { ifaceDetails[it] } ?: ""

        val cap = DualWifiCapability(
            hardwareSupported = hwSupported,
            configEnabled = configEnabled,
            modeActive = modeActive,
            primaryIface = primary,
            secondaryIface = secondary,
            romInfo = romInfo,
            configuredScheme = configuredScheme,
            effectiveScheme = effectiveScheme,
            wifiChipset = wifiChipset,
            overlayStatus = if (configEnabled) "已开启 (DualWifi, Dual5G)" else "未开启",
            primaryDetails = primDet,
            secondaryDetails = secDet,
            detail = when {
                !hwSupported -> "当前设备硬件或驱动不支持 STA + STA 并发"
                effectiveScheme == DualWifiScheme.XIAOMI_SLAVE -> "已匹配小米/HyperOS 原厂双 WLAN 机制"
                effectiveScheme == DualWifiScheme.ROOT_DIRECT -> "已配置通用 Root 直连，通过 Linux 底层特权通道并发"
                !configEnabled -> "硬件支持双 Wi-Fi，系统尚未开启多互联网并发 Overlay"
                !modeActive -> "系统 Overlay 已开启，并发工作模式尚未切换为 Multi-AP"
                else -> "双 Wi-Fi (STA + STA 并发) 已就绪"
            }
        )

        // Auto-apply Xiaomi slave wifi screen-off keepalive when Xiaomi scheme is active
        if (effectiveScheme == DualWifiScheme.XIAOMI_SLAVE || romInfo.romType == RomType.XIAOMI_HYPEROS) {
            if (!com.multiroute.data.RouteConfigProvider.isKeepSlaveWifiScreenOff(context)) {
                com.multiroute.data.RouteConfigProvider.setKeepSlaveWifiScreenOff(context, true)
                SuHelper.setKeepSlaveWifiScreenOff(context, true)
            }
        }

        _status.update { it.copy(capability = cap) }
        refreshConnectionState(context)
        cap
    }

    private fun parseWifiStatus(lines: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val fullText = lines.joinToString("\n")
        val blocks = fullText.split("==== ClientModeManager instance:")
        for (block in blocks) {
            val ifaceMatch = Regex("""iface=(\w+)""").find(block) ?: continue
            val iface = ifaceMatch.groupValues[1]
            val ssidMatch = Regex("""SSID: "([^"]+)"""").find(block)
            val ssid = ssidMatch?.groupValues?.get(1) ?: "未连接"
            if (block.contains("Wifi is not connected") || ssid == "未连接" || ssid == "<unknown ssid>") {
                result[iface] = "待机中 (未连接网络)"
                continue
            }
            val standardMatch = Regex("""Wi-Fi standard: (\w+)""").find(block)
            val standard = standardMatch?.groupValues?.get(1)?.let { std ->
                when (std.lowercase()) {
                    "11ax" -> "Wi-Fi 6 (802.11ax)"
                    "11be" -> "Wi-Fi 7 (802.11be)"
                    "11ac" -> "Wi-Fi 5 (802.11ac)"
                    "11n" -> "Wi-Fi 4 (802.11n)"
                    else -> std
                }
            } ?: "802.11"
            val freqMatch = Regex("""Frequency: (\d+)MHz""").find(block)
            val freq = freqMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val channel = if (freq > 0) NetworkUtils.wifiChannelNumber(freq) else 0
            val rssiMatch = Regex("""RSSI: (-?\d+)""").find(block)
            val rssi = rssiMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val txMatch = Regex("""Tx Link speed: (\d+)Mbps""").find(block)
            val rxMatch = Regex("""Rx Link speed: (\d+)Mbps""").find(block)
            val txSpeed = txMatch?.groupValues?.get(1) ?: "0"
            val rxSpeed = rxMatch?.groupValues?.get(1) ?: "0"
            val isValidated = block.contains("VALIDATED")

            val freqText = if (freq > 0) "${freq}MHz (信道 $channel)" else ""
            val valText = if (isValidated) "已通过系统校验" else "未校验/局域网"
            result[iface] = "$standard • $freqText • Tx $txSpeed / Rx $rxSpeed Mbps • $rssi dBm • $valText"
        }
        return result
    }

    /**
     * Activates Dual Wi-Fi according to the effective or target scheme via Root.
     */
    suspend fun activateDualWifi(context: Context, targetScheme: DualWifiScheme? = null): Boolean = withContext(Dispatchers.IO) {
        val confScheme = targetScheme ?: DualWifiScheme.fromId(com.multiroute.data.RouteConfigProvider.getDualWifiScheme(context))
        val rom = detectRomType()
        val effective = getEffectiveScheme(confScheme, rom)

        val success = when (effective) {
            DualWifiScheme.AOSP_OVERLAY -> {
                val script = """
                    cmd overlay fabricate --target-name WifiCustomization --target com.google.android.wifi.resources --name DualWifi com.google.android.wifi.resources:bool/config_wifiMultiStaMultiInternetConcurrencyEnabled 0x12 1
                    cmd overlay enable com.android.shell:DualWifi
                    cmd overlay fabricate --target-name WifiCustomization --target com.google.android.wifi.resources --name Dual5G com.google.android.wifi.resources:bool/config_wifiAllowMultiInternetConnectDual5GFrequency 0x12 1
                    cmd overlay enable com.android.shell:Dual5G
                    cmd wifi force-overlay-config-value bool config_wifiMultiStaMultiInternetConcurrencyEnabled enabled true
                    cmd wifi force-overlay-config-value bool config_wifiAllowMultiInternetConnectDual5GFrequency enabled true
                    cmd wifi set-multi-internet-mode 2
                    cmd wifi clear-user-disabled-networks
                    
                    mkdir -p /data/adb/service.d
                    cat << 'DUALWIFI_EOF' > /data/adb/service.d/01-multiroute-dualwifi.sh
#!/system/bin/sh
cmd overlay enable com.android.shell:DualWifi
cmd overlay enable com.android.shell:Dual5G
cmd wifi force-overlay-config-value bool config_wifiMultiStaMultiInternetConcurrencyEnabled enabled true
cmd wifi force-overlay-config-value bool config_wifiAllowMultiInternetConnectDual5GFrequency enabled true
cmd wifi set-multi-internet-mode 2
DUALWIFI_EOF
                    chmod 755 /data/adb/service.d/01-multiroute-dualwifi.sh
                """.trimIndent()
                SuHelper.executeCommand(script, timeoutSeconds = 10)
            }
            DualWifiScheme.XIAOMI_SLAVE -> {
                SuHelper.setKeepSlaveWifiScreenOff(context, true)
                val script = """
                    cmd wifi set-multi-internet-mode 2 2>/dev/null
                    cmd wifi clear-user-disabled-networks 2>/dev/null
                """.trimIndent()
                SuHelper.executeCommand(script, timeoutSeconds = 5)
                true
            }
            DualWifiScheme.ROOT_DIRECT, DualWifiScheme.AUTO -> {
                val script = """
                    cmd wifi set-multi-internet-mode 2 2>/dev/null
                    cmd wifi clear-user-disabled-networks 2>/dev/null
                """.trimIndent()
                SuHelper.executeCommand(script, timeoutSeconds = 5)
            }
        }

        checkCapability(context)
        success
    }

    /**
     * Clears temporary network selection blocklist and stale requests in Wi-Fi framework.
     */
    suspend fun clearNetworkBlocklist(): Boolean = withContext(Dispatchers.IO) {
        val script = """
            cmd wifi clear-user-disabled-networks
            cmd wifi remove-all-requests
            cmd wifi allow-root-to-get-local-only-cmm enabled
            cmd wifi network-requests-set-user-approved android yes
        """.trimIndent()
        SuHelper.executeCommand(script, timeoutSeconds = 5)
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
     * Scans nearby Wi-Fi networks and matches them against saved credentials.
     */
    suspend fun scanWifiNetworks(context: Context): List<WifiScanItem> = withContext(Dispatchers.IO) {
        _status.update { it.copy(isScanning = true) }
        try {
            // Trigger scan
            SuHelper.executeCommand("cmd wifi start-scan 2>/dev/null", timeoutSeconds = 3)
            kotlinx.coroutines.delay(1200)

            val lines = SuHelper.executeShellWithOutput("cmd wifi list-scan-results 2>/dev/null", timeoutSeconds = 5)
            val savedPasswords = getSavedWifiPasswords()
            val detailsMap = NetworkUtils.getConnectedWifiDetails()
            val primIface = _status.value.capability.primaryIface ?: "wlan0"
            val secIface = _status.value.capability.secondaryIface ?: "wlan1"
            val primDetails = detailsMap[primIface]
            val secDetails = detailsMap[secIface]

            val primarySsid = _status.value.primarySsid ?: primDetails?.ssid
            val primaryBssid = _status.value.primaryBssid ?: primDetails?.bssid
            val primaryFreq = if (_status.value.primaryFreq > 0) _status.value.primaryFreq else (primDetails?.frequencyMhz ?: 0)

            val isSecConnected = _status.value.secondaryConnected ||
                (!secDetails?.ssid.isNullOrEmpty() && secDetails?.ssid != "<unknown ssid>")
            val secondarySsid = if (isSecConnected) (_status.value.secondarySsid ?: secDetails?.ssid) else null
            val secondaryBssid = if (isSecConnected) (_status.value.secondaryBssid ?: secDetails?.bssid) else null
            val secondaryFreq = if (isSecConnected) (if (_status.value.secondaryFreq > 0) _status.value.secondaryFreq else (secDetails?.frequencyMhz ?: 0)) else 0

            val frameworkScanResults = runCatching {
                (context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.scanResults
            }.getOrNull()

            val items = parseScanResultLines(
                lines = lines,
                savedPasswords = savedPasswords,
                connectedDetails = detailsMap,
                primarySsid = primarySsid,
                primaryBssid = primaryBssid,
                primaryFreq = primaryFreq,
                secondarySsid = secondarySsid,
                secondaryBssid = secondaryBssid,
                secondaryFreq = secondaryFreq,
                isSecConnected = isSecConnected,
                frameworkScanResults = frameworkScanResults
            ).toMutableList()

            // Smart Multi-Tiered Sorting:
            // 1. Currently active secondary connection (STA2)
            // 2. Currently active primary connection (STA1)
            // 3. Recommended (Non-conflicting) bands with saved credentials
            // 4. Recommended (Non-conflicting) bands sorted by signal strength (RSSI)
            // 5. Conflicting bands with saved credentials
            // 6. Conflicting bands sorted by signal strength (RSSI)
            val primFreq = _status.value.primaryFreq
            items.sortWith(
                compareByDescending<WifiScanItem> { it.isSecondary }
                    .thenByDescending { it.isPrimary }
                    .thenByDescending { isBandRecommended(it.frequencyMhz, primFreq) && it.isSaved }
                    .thenByDescending { isBandRecommended(it.frequencyMhz, primFreq) }
                    .thenByDescending { it.isSaved }
                    .thenByDescending { it.rssi }
            )

            _status.update { it.copy(isScanning = false, scanResults = items) }
            items
        } catch (t: Throwable) {
            Log.e(TAG, "Wi-Fi scan failed", t)
            _status.update { it.copy(isScanning = false, errorMessage = "扫描 Wi-Fi 失败: ${t.message}") }
            emptyList()
        }
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
        frameworkScanResults: List<android.net.wifi.ScanResult>? = null
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
            val isPrimary = matchesConnected(
                candidateSsid = ssid,
                candidateBssid = bssid,
                candidateFreq = freq,
                connectedSsid = primarySsid,
                connectedBssid = primaryBssid,
                connectedFreq = primaryFreq
            )
            val isSecondary = !isPrimary && isSecConnected && matchesConnected(
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

            val isSaved = savedPasswords.containsKey(ssid)
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

    /**
     * Checks if the target AP frequency conflicts with the primary Wi-Fi radio band.
     * Dual-Band Simultaneous (DBS) chipsets cannot concurrently bind two networks on the same radio.
     */
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
     * Refreshes the currently connected primary and secondary Wi-Fi status.
     */
    suspend fun refreshConnectionState(context: Context) = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeDefault = cm?.activeNetwork
        val detailsMap = NetworkUtils.getConnectedWifiDetails()

        val primaryTargetIface = _status.value.capability.primaryIface ?: "wlan0"
        val secondaryTargetIface = _status.value.capability.secondaryIface ?: "wlan1"

        data class IfaceLink(val iface: String, val ip: String?, val isDefault: Boolean)
        val cmIfaces = mutableMapOf<String, IfaceLink>()
        if (cm != null) {
            for (network in cm.allNetworks) {
                val link = cm.getLinkProperties(network) ?: continue
                val iface = link.interfaceName ?: continue
                if (!iface.startsWith("wlan")) continue
                val ip = link.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
                cmIfaces[iface] = IfaceLink(iface, ip, network == activeDefault)
            }
        }

        // 1. Authoritative Primary Wi-Fi resolution (Targeting wlan0 / ROLE_CLIENT_PRIMARY)
        val primDetail = detailsMap[primaryTargetIface]
            ?: detailsMap.values.firstOrNull { it.isPrimary || it.iface == primaryTargetIface }
            ?: detailsMap.values.firstOrNull { it.iface == "wlan0" }
        val primCm = cmIfaces[primaryTargetIface]
            ?: cmIfaces.values.firstOrNull { it.isDefault || it.iface == primaryTargetIface }
            ?: cmIfaces.values.firstOrNull { it.iface == "wlan0" }

        val primIface = primCm?.iface ?: primDetail?.iface ?: primaryTargetIface
        val primSsid = primDetail?.ssid
        val primBssid = primDetail?.bssid
        val primFreq = primDetail?.frequencyMhz ?: 0
        val primRssi = primDetail?.rssi ?: 0
        var primIp = primCm?.ip ?: primDetail?.ip

        // 2. Authoritative Secondary Wi-Fi resolution (Targeting wlan1 / ROLE_CLIENT_SECONDARY)
        val secDetail = detailsMap[secondaryTargetIface]
            ?: detailsMap.values.firstOrNull { !it.isPrimary && it.iface != primIface }
            ?: detailsMap.values.firstOrNull { it.iface == "wlan1" && it.iface != primIface }
        val secCm = cmIfaces[secondaryTargetIface]
            ?: cmIfaces.values.firstOrNull { it.iface != primIface }
            ?: cmIfaces.values.firstOrNull { it.iface == "wlan1" && it.iface != primIface }

        val secIface = secCm?.iface ?: secDetail?.iface ?: secondaryTargetIface
        val secSsid = secDetail?.ssid
        val secBssid = secDetail?.bssid
        val secFreq = secDetail?.frequencyMhz ?: 0
        val secRssi = secDetail?.rssi ?: 0
        var secIp = secCm?.ip ?: secDetail?.ip
        val secConnected = !secSsid.isNullOrEmpty() && secSsid != "<unknown ssid>"

        // Fallback for IP from kernel if not exposed by ConnectivityManager/WifiInfo
        if (primIp == null && !primSsid.isNullOrEmpty()) {
            primIp = SuHelper.executeShellWithOutput("ip -4 -br addr show dev $primIface 2>/dev/null", timeoutSeconds = 2)
                .firstOrNull()?.let { line ->
                    Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""").find(line)?.value
                }
        }
        if (secIp == null && secConnected) {
            secIp = SuHelper.executeShellWithOutput("ip -4 -br addr show dev $secIface 2>/dev/null", timeoutSeconds = 2)
                .firstOrNull()?.let { line ->
                    Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""").find(line)?.value
                }
        }

        _status.update {
            it.copy(
                primaryIface = primIface ?: it.capability.primaryIface,
                primarySsid = primSsid,
                primaryBssid = primBssid,
                primaryIp = primIp,
                primaryFreq = primFreq,
                primaryRssi = primRssi,
                secondaryIface = secIface ?: it.capability.secondaryIface,
                secondarySsid = secSsid,
                secondaryBssid = secBssid,
                secondaryIp = secIp,
                secondaryFreq = secFreq,
                secondaryRssi = secRssi,
                secondaryConnected = secConnected
            )
        }
    }

    /**
     * Asynchronously connects the secondary Wi-Fi using DualWifiManager.scope to prevent coroutine cancellation from UI dismissal.
     */
    fun connectSecondaryWifiAsync(
        context: Context,
        ssid: String,
        passphrase: String?,
        frequencyMhz: Int,
        bssid: String? = null,
        capabilities: String? = null
    ) {
        scope.launch {
            connectSecondaryWifi(context, ssid, passphrase, frequencyMhz, bssid, capabilities)
        }
    }

    /**
     * Connects the secondary Wi-Fi interface to the chosen access point via Root & Multi-Internet.
     */
    suspend fun connectSecondaryWifi(
        context: Context,
        ssid: String,
        passphrase: String?,
        frequencyMhz: Int,
        bssid: String? = null,
        capabilities: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        _status.update { it.copy(isConnecting = true, connectingSsid = ssid, connectingBssid = bssid, errorMessage = null) }
        try {
            // 1. Clean up any existing requests and clear blocklist to ensure fresh attempt
            val prepScript = """
                cmd wifi remove-all-requests
                cmd wifi clear-user-disabled-networks
                cmd wifi allow-root-to-get-local-only-cmm enabled
                cmd wifi network-requests-set-user-approved android yes
            """.trimIndent()
            SuHelper.executeCommand(prepScript, timeoutSeconds = 5)

            // 2. Resolve security type: open, owe, wpa2, wpa3, wep
            val secType = resolveSecurityType(capabilities, passphrase)
            val passArg = if (secType != "open" && secType != "owe" && !passphrase.isNullOrEmpty()) " \"$passphrase\"" else ""
            SuHelper.executeCommand("cmd wifi add-network \"$ssid\" $secType$passArg", timeoutSeconds = 3)

            val bssidArg = if (!bssid.isNullOrEmpty()) " -b $bssid" else ""

            // 3. Issue add-request via root without -d (frequency bands specifier rejected by WifiNetworkFactory)
            // and without -i to prevent NetworkMonitor from disconnecting wlan1 due to validation failure
            val script = """
                cmd wifi network-requests-set-user-approved android yes
                cmd wifi add-request "$ssid" $secType$passArg$bssidArg
                cmd wifi start-scan
            """.trimIndent()
            SuHelper.executeCommand(script, timeoutSeconds = 5)

            // 4. Poll for secondary connection for up to 12 seconds with auth failure detection
            var connected = false
            var failureReason: String? = null
            for (i in 0 until 12) {
                delay(1000)
                refreshConnectionState(context)
                if (_status.value.secondaryConnected) {
                    connected = true
                    break
                }

                // Check recent logcat for handshake failures
                if (i in 2..10) {
                    val logs = SuHelper.executeShellWithOutput(
                        "logcat -d -t 30 -s wpa_supplicant:I WifiClientModeImpl:D | grep -E 'WRONG_KEY|AUTH_FAILURE|Association rejection'",
                        timeoutSeconds = 2
                    )
                    for (line in logs) {
                        when {
                            line.contains("WRONG_KEY") || line.contains("AUTH_FAILURE_WRONG_PSWD") -> {
                                failureReason = "Wi-Fi 密码错误，请核对密码后重新连接"
                                break
                            }
                            line.contains("Association rejection") -> {
                                failureReason = "AP 拒绝连接：请确认副 Wi-Fi 频段未与主 Wi-Fi 冲突（同一物理信道不可并发）"
                                break
                            }
                        }
                    }
                    if (failureReason != null) break
                }
            }

            if (connected) {
                val secState = _status.value
                com.multiroute.data.RouteConfigProvider.saveLastSecondaryWifi(
                    context,
                    ssid = ssid,
                    pass = passphrase,
                    freq = frequencyMhz,
                    bssid = bssid,
                    caps = capabilities
                )
                DualWifiService.start(context, secState.secondarySsid ?: ssid, secState.secondaryIface ?: "wlan1")
                scope.launch {
                    SuHelper.syncAllRouteRules(context)
                }
                _status.update { it.copy(isConnecting = false, connectingSsid = null, connectingBssid = null) }
                true
            } else {
                val err = failureReason ?: "连接副 Wi-Fi 超时，请检查密码是否正确，且副 Wi-Fi 频段需与主 Wi-Fi 互补（如 2.4G + 5G）"
                _status.update {
                    it.copy(
                        isConnecting = false,
                        connectingSsid = null,
                        connectingBssid = null,
                        errorMessage = err
                    )
                }
                false
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "connectSecondaryWifi coroutine cancelled")
                _status.update { it.copy(isConnecting = false, connectingSsid = null, connectingBssid = null) }
                return@withContext false
            }
            Log.e(TAG, "Failed to connect secondary wifi", t)
            _status.update { it.copy(isConnecting = false, connectingSsid = null,
                        connectingBssid = null, errorMessage = "发起连接失败: ${t.message}") }
            false
        }
    }

    /**
     * Attempts to auto-reconnect the last configured secondary Wi-Fi if enabled and not currently connected.
     */
    suspend fun autoReconnectSecondary(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!com.multiroute.data.RouteConfigProvider.isDualWifiAutoReconnectEnabled(context)) {
            return@withContext false
        }
        refreshConnectionState(context)
        if (_status.value.secondaryConnected) return@withContext true

        val lastWifi = com.multiroute.data.RouteConfigProvider.getLastSecondaryWifi(context)
        val ssid = lastWifi["ssid"] as? String ?: return@withContext false
        val pass = (lastWifi["pass"] as? String)?.takeIf { it.isNotEmpty() }
        val freq = lastWifi["freq"] as? Int ?: 0
        val bssid = lastWifi["bssid"] as? String
        val caps = lastWifi["caps"] as? String

        Log.i(TAG, "Auto reconnecting secondary Wi-Fi: $ssid")
        connectSecondaryWifi(context, ssid, pass, freq, bssid, caps)
    }

    /**
     * Disconnects the secondary Wi-Fi network.
     */
    fun disconnectSecondaryWifi(context: Context, clearSavedPreference: Boolean = true) {
        if (clearSavedPreference) {
            com.multiroute.data.RouteConfigProvider.clearLastSecondaryWifi(context)
        }
        SuHelper.executeCommand("cmd wifi remove-all-requests", timeoutSeconds = 3)
        DualWifiService.stop(context)

        _status.update {
            it.copy(
                isConnecting = false,
                connectingSsid = null,
                        connectingBssid = null,
                secondaryConnected = false,
                secondarySsid = null,
                secondaryBssid = null,
                secondaryIp = null
            )
        }

        scope.launch {
            refreshConnectionState(context)
            SuHelper.syncAllRouteRules(context)
        }
    }
}

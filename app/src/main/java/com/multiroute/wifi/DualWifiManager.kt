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
import com.multiroute.util.HotspotHookState
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
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

data class HotspotInfo(
    val isEnabled: Boolean = false,
    val iface: String? = null,
    val frequencyMhz: Int = 0,
    val bandLabel: String? = null
)

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
    val isHotspotEnabled: Boolean = false,
    val hotspotIface: String? = null,
    val hotspotFreq: Int = 0,
    val hotspotBand: String? = null,
    val hotspotUpstreamTarget: String = "default",
    val hotspotUpstreamActive: String = "none",
    val hotspotHookState: HotspotHookState = HotspotHookState.UNKNOWN,
    val isHotspotSwitching: Boolean = false,
    val hotspotUpdateFailed: Boolean = false,
    val errorMessage: String? = null
) {
    val isHotspotHookLoaded: Boolean get() = hotspotHookState == HotspotHookState.LOADED
}

object DualWifiManager {
    private const val TAG = "MultiRoute-DualWifi"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val hotspotMutex = Mutex()
    private val secondaryConnections = SecondaryConnectionCoordinator()
    private const val SHELL_REQUEST_PREF = "dual_wifi_shell_request_ssid"
    internal suspend fun <T> withHotspotPreferenceLock(block: suspend () -> T): T = hotspotMutex.withLock { block() }
    private val hotspotRequestId = AtomicLong()

    private val _status = MutableStateFlow(DualWifiStatus())
    val status: StateFlow<DualWifiStatus> = _status.asStateFlow()

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null

    private fun releaseOwnedShellRequest(context: Context): Boolean {
        val prefs = context.getSharedPreferences(com.multiroute.data.RouteConfigProvider.PREF_NAME, Context.MODE_PRIVATE)
        val requestKey = prefs.getString(SHELL_REQUEST_PREF, null) ?: return true
        val removed = SuHelper.executeCommand(WifiShellCommands.removeRequest(requestKey))
        val empty = if (!removed) SuHelper.executeShellWithOutput("cmd wifi list-requests").any { it.contains("No active requests") } else true
        if (empty) prefs.edit().remove(SHELL_REQUEST_PREF).commit()
        return empty
    }

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

    @Volatile
    private var isHotspotReceiverRegistered = false

    fun ensureHotspotReceiverRegistered(context: Context) {
        if (isHotspotReceiverRegistered) return
        val appContext = context.applicationContext
        try {
            val filter = android.content.IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    val apState = intent?.getIntExtra("wifi_state", 11) ?: 11
                    scope.launch {
                        refreshConnectionState(appContext)
                        // If hotspot was just disabled, trigger secondary Wi-Fi auto-reconnection
                        // after a short delay allowing kernel nl80211 to restore STA2 mode
                        if (apState == 11 && !_status.value.secondaryConnected &&
                            com.multiroute.data.RouteConfigProvider.isDualWifiAutoReconnectEnabled(appContext)
                        ) {
                            delay(1200)
                            refreshConnectionState(appContext)
                            if (!_status.value.secondaryConnected && !_status.value.isHotspotEnabled) {
                                autoReconnectSecondary(appContext)
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            isHotspotReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register WIFI_AP_STATE_CHANGED receiver: ${t.message}")
        }
    }

    /**
     * Checks whether the device hardware and framework support Dual Wi-Fi (STA + STA Concurrency).
     */
    suspend fun checkCapability(context: Context): DualWifiCapability = withContext(Dispatchers.IO) {
        ensureHotspotReceiverRegistered(context)
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

        // In Android dual STA architectures (Qualcomm FastConnect, MediaTek, Broadcom),
        // wlan0 is the primary STA interface, and wlan1 is the dedicated secondary STA interface.
        val (primary, secondary) = resolvePrimarySecondaryIfaces(ifaces, activeDefaultIface)

        // Extract physical link details for active Wi-Fi interfaces
        val statusOutput = SuHelper.executeShellWithOutput("cmd wifi status 2>/dev/null", timeoutSeconds = 3)
        val ifaceDetails = parseWifiStatus(statusOutput)
        val primDet = ifaceDetails[primary] ?: ""
        val secDet = ifaceDetails[secondary] ?: ""

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
                """.trimIndent()
                SuHelper.executeCommand(script, timeoutSeconds = 5)
                true
            }
            DualWifiScheme.ROOT_DIRECT, DualWifiScheme.AUTO -> {
                val script = """
                    cmd wifi set-multi-internet-mode 2 2>/dev/null
                """.trimIndent()
                SuHelper.executeCommand(script, timeoutSeconds = 5)
            }
        }

        val capability = checkCapability(context)
        success && capability.isFullyReady
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
            val primIface = _status.value.capability.primaryIface?.takeIf { it != "wlan1" } ?: "wlan0"
            val secIface = _status.value.capability.secondaryIface?.takeIf { it != primIface } ?: "wlan1"
            val primDetails = detailsMap[primIface]
            val secDetails = detailsMap[secIface]?.takeIf { it.iface != primIface }

            val primarySsid = _status.value.primarySsid ?: primDetails?.ssid
            val primaryBssid = _status.value.primaryBssid ?: primDetails?.bssid
            val primaryFreq = if (_status.value.primaryFreq > 0) _status.value.primaryFreq else (primDetails?.frequencyMhz ?: 0)

            val isSecConnected = _status.value.secondaryConnected &&
                !_status.value.secondarySsid.isNullOrEmpty() &&
                _status.value.secondarySsid != "<unknown ssid>"
            val secondarySsid = if (isSecConnected) _status.value.secondarySsid else null
            val secondaryBssid = if (isSecConnected) _status.value.secondaryBssid else null
            val secondaryFreq = if (isSecConnected) _status.value.secondaryFreq else 0

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
    fun formatBand(freq: Int): String = when (freq) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5320 -> "5 GHz (低频)"
        in 5321..5900 -> "5 GHz (高频)"
        in 5925..7125 -> "6 GHz"
        else -> if (freq > 0) "${freq}MHz" else "已开启"
    }

    /**
     * Parses kernel nl80211 interface dump from `iw dev` to detect active SoftAP interface and operating frequency.
     */
    fun parseHotspotFromIwDev(output: List<String>): HotspotInfo {
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
                        bandLabel = formatBand(freq)
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
                bandLabel = formatBand(freq)
            )
        }
        return HotspotInfo(isEnabled = false)
    }

    /**
     * Checks if a candidate secondary Wi-Fi AP frequency conflicts with the active hotspot's frequency band.
     */
    fun isHotspotConflicted(candidateFreq: Int, hotspotFreq: Int): Boolean {
        if (hotspotFreq <= 0 || candidateFreq <= 0) return false
        return isBandConflicted(candidateFreq, hotspotFreq)
    }

    /**
     * Probes current personal hotspot (SoftAP) state and operating frequency.
     */
    fun checkHotspotState(context: Context): HotspotInfo {
        // 1. Check sticky broadcast android.net.wifi.WIFI_AP_STATE_CHANGED
        val stickyIntent = runCatching {
            context.registerReceiver(null, android.content.IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))
        }.getOrNull()
        val apState = stickyIntent?.getIntExtra("wifi_state", 11) ?: 11
        val isApBroadcastActive = (apState == 13 || apState == 12)

        // 2. Fallback check via WifiManager reflection if broadcast is absent
        val isApManagerActive = if (!isApBroadcastActive) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
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

        val fromIw = parseHotspotFromIwDev(iwLines)
        if (fromIw.isEnabled) {
            return fromIw
        }

        // 4. Fallback for devices without `iw` (e.g. minimal AOSP / Pixel factory images): dumpsys wifi
        val dumpsysLines = runCatching {
            SuHelper.executeShellWithOutput("dumpsys wifi 2>/dev/null | grep -i -E 'SoftApInfo|channel_frequency|mApInterfaceName' | head -n 10", timeoutSeconds = 2)
        }.getOrDefault(emptyList())
        val fromDumpsys = parseHotspotFromDumpsysWifi(dumpsysLines)
        if (fromDumpsys.isEnabled) {
            return fromDumpsys
        }

        return HotspotInfo(isEnabled = true, bandLabel = "已开启")
    }

    /**
     * Parses `dumpsys wifi` output as fallback when `iw` is not installed (e.g. clean AOSP/Pixel builds).
     */
    fun parseHotspotFromDumpsysWifi(lines: List<String>): HotspotInfo {
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
                bandLabel = formatBand(freq)
            )
        } else {
            HotspotInfo(isEnabled = false)
        }
    }

    /**
     * Retrieves the framework network ID for a given SSID from `cmd wifi list-networks`.
     */
    suspend fun getNetworkIdForSsid(ssid: String): Int? = withContext(Dispatchers.IO) {
        val lines = SuHelper.executeShellWithOutput("cmd wifi list-networks 2>/dev/null", timeoutSeconds = 3)
        parseNetworkIdFromList(lines, ssid)
    }

    /**
     * Parses the network ID for the specified SSID from `cmd wifi list-networks` output lines.
     */
    fun parseNetworkIdFromList(lines: List<String>, targetSsid: String): Int? {
        val cleanTarget = targetSsid.trim().trim('"')
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Network Id", ignoreCase = true)) continue
            // Security is the final column; words such as "open" may also occur inside an SSID.
            val match = Regex("""^(\d+)\s+(.+?)\s+\S+$""").matchEntire(trimmed)
            if (match != null) {
                val id = match.groupValues[1].toIntOrNull()
                val netSsid = match.groupValues[2].trim().trim('"')
                if (id != null && netSsid == cleanTarget) {
                    return id
                }
            }
        }
        return null
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

    /**
     * Refreshes the currently connected primary and secondary Wi-Fi status.
     */
    suspend fun refreshConnectionState(context: Context) = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeDefault = cm?.activeNetwork
        val detailsMap = NetworkUtils.getConnectedWifiDetails()

        val primaryTargetIface = _status.value.capability.primaryIface?.takeIf { it != "wlan1" } ?: "wlan0"
        val secondaryTargetIface = _status.value.capability.secondaryIface?.takeIf { it != primaryTargetIface } ?: "wlan1"

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
            ?: detailsMap.values.firstOrNull { it.isPrimary }
            ?: detailsMap.values.firstOrNull { it.iface == "wlan0" }
        val primCm = cmIfaces[primaryTargetIface]
            ?: cmIfaces.values.firstOrNull { it.isDefault }
            ?: cmIfaces.values.firstOrNull { it.iface == "wlan0" }

        val primIface = primDetail?.iface ?: primCm?.iface ?: primaryTargetIface
        val primSsid = primDetail?.ssid
        val primBssid = primDetail?.bssid
        val primFreq = primDetail?.frequencyMhz ?: 0
        val primRssi = primDetail?.rssi ?: 0
        var primIp = primCm?.ip ?: primDetail?.ip

        // 2. Authoritative Secondary Wi-Fi resolution (Targeting wlan1 / ROLE_CLIENT_SECONDARY)
        // Secondary Wi-Fi is strictly separated from primary interface (primIface)
        val secDetail = detailsMap[secondaryTargetIface]?.takeIf { it.iface != primIface }
            ?: detailsMap.values.firstOrNull { !it.isPrimary && it.iface != primIface && it.iface == "wlan1" }
        val secCm = cmIfaces[secondaryTargetIface]?.takeIf { it.iface != primIface }
            ?: cmIfaces.values.firstOrNull { it.iface != primIface && it.iface == "wlan1" }

        val secIface = secDetail?.iface ?: secCm?.iface ?: secondaryTargetIface
        val candidateSecSsid = secDetail?.ssid?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val candidateSecBssid = secDetail?.bssid
        val secFreq = secDetail?.frequencyMhz ?: 0
        val secRssi = secDetail?.rssi ?: 0
        var secIp = secCm?.ip ?: secDetail?.ip

        // Secondary is connected ONLY IF it has a distinct interface, a non-empty SSID, and does not collide with primary
        val secConnected = secDetail != null &&
            !candidateSecSsid.isNullOrEmpty() &&
            secIface != primIface &&
            (candidateSecSsid != primSsid || candidateSecBssid != primBssid || secIface != primIface)

        val secSsid = if (secConnected) candidateSecSsid else null
        val secBssid = if (secConnected) candidateSecBssid else null
        val secIpFinal = if (secConnected) secIp else null
        val secFreqFinal = if (secConnected) secFreq else 0
        val secRssiFinal = if (secConnected) secRssi else 0

        // Fallback for IP from kernel if not exposed by ConnectivityManager/WifiInfo
        if (primIp == null && !primSsid.isNullOrEmpty()) {
            primIp = SuHelper.executeShellWithOutput("ip -4 -br addr show dev $primIface 2>/dev/null", timeoutSeconds = 2)
                .firstOrNull()?.let { line ->
                    Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""").find(line)?.value
                }
        }
        val finalSecIpResolved = if (secIpFinal == null && secConnected) {
            SuHelper.executeShellWithOutput("ip -4 -br addr show dev $secIface 2>/dev/null", timeoutSeconds = 2)
                .firstOrNull()?.let { line ->
                    Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""").find(line)?.value
                }
        } else {
            secIpFinal
        }

        val hotspotInfo = checkHotspotState(context)

        hotspotMutex.withLock {
            val hotspotTarget = SuHelper.getHotspotUpstream()
            val hotspotActive = if (hotspotInfo.isEnabled) SuHelper.getHotspotActiveUpstream() else "none"
            val hookState = SuHelper.getHotspotHookStatus()
            _status.update {
                it.copy(
                    primaryIface = primIface,
                    primarySsid = primSsid,
                    primaryBssid = primBssid,
                    primaryIp = primIp,
                    primaryFreq = primFreq,
                    primaryRssi = primRssi,
                    secondaryIface = secIface,
                    secondarySsid = secSsid,
                    secondaryBssid = secBssid,
                    secondaryIp = finalSecIpResolved,
                    secondaryFreq = secFreqFinal,
                    secondaryRssi = secRssiFinal,
                    secondaryConnected = secConnected,
                    isHotspotEnabled = hotspotInfo.isEnabled,
                    hotspotIface = hotspotInfo.iface,
                    hotspotFreq = hotspotInfo.frequencyMhz,
                    hotspotBand = hotspotInfo.bandLabel,
                    hotspotUpstreamTarget = hotspotTarget,
                    hotspotUpstreamActive = hotspotActive,
                    hotspotHookState = hookState
                )
            }
        }
    }

    /** Serializes root commands off the main thread; obsolete requests cannot publish stale state. */
    suspend fun setHotspotUpstream(target: String) {
        val requestId = hotspotRequestId.incrementAndGet()
        withContext(Dispatchers.IO) {
            hotspotMutex.withLock {
                if (requestId != hotspotRequestId.get()) return@withLock
                _status.update { it.copy(isHotspotSwitching = true, hotspotUpdateFailed = false) }
                try {
                    val success = SuHelper.setHotspotUpstream(target)
                    val actualTarget = SuHelper.getHotspotUpstream()
                    val active = SuHelper.getHotspotActiveUpstream()
                    val hookState = SuHelper.getHotspotHookStatus()
                    if (requestId == hotspotRequestId.get()) {
                        _status.update { it.copy(
                            hotspotUpstreamTarget = actualTarget,
                            hotspotUpstreamActive = active,
                            hotspotHookState = hookState,
                            hotspotUpdateFailed = !success
                        ) }
                    }
                } finally {
                    _status.update { it.copy(isHotspotSwitching = false) }
                }
            }
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
        val ticket = secondaryConnections.connect()
        scope.launch {
            connectSecondaryWifi(context, ssid, passphrase, frequencyMhz, bssid, capabilities, ticket)
        }
    }

    suspend fun canConnectSecondaryNetwork(context: Context, ssid: String): Boolean = withContext(Dispatchers.IO) {
        val rom = detectRomType()
        val configured = DualWifiScheme.fromId(com.multiroute.data.RouteConfigProvider.getDualWifiScheme(context))
        val usesXiaomi = getEffectiveScheme(configured, rom) == DualWifiScheme.XIAOMI_SLAVE || rom.romType == RomType.XIAOMI_HYPEROS
        SecondaryNetworkPolicy.canConnect(usesXiaomi) { getNetworkIdForSsid(ssid) }
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
    ): Boolean = connectSecondaryWifi(context, ssid, passphrase, frequencyMhz, bssid, capabilities,
        secondaryConnections.connect())

    private suspend fun connectSecondaryWifi(
        context: Context,
        ssid: String,
        passphrase: String?,
        frequencyMhz: Int,
        bssid: String?,
        capabilities: String?,
        ticket: SecondaryConnectionCoordinator.Ticket
    ): Boolean = withContext(Dispatchers.IO) {
      secondaryConnections.serialized {
        if (!secondaryConnections.isCurrent(ticket)) return@serialized false
        // Prevent collision: Never allow connecting primary AP as secondary Wi-Fi
        val primSsid = _status.value.primarySsid
        val primBssid = _status.value.primaryBssid
        if (isPrimaryCollision(ssid, bssid, primSsid, primBssid)) {
            secondaryConnections.publish(ticket) { _status.update {
                it.copy(
                    isConnecting = false,
                    connectingSsid = null,
                    connectingBssid = null,
                    errorMessage = "不能将当前主 Wi-Fi ($primSsid) 连接为副 Wi-Fi"
                )
            } }
            return@serialized false
        }

        // Prevent radio collision: Never allow connecting secondary Wi-Fi while personal hotspot is active
        if (_status.value.isHotspotEnabled || checkHotspotState(context).isEnabled) {
            val err = context.getString(com.multiroute.R.string.dual_wifi_hotspot_error_blocked)
            secondaryConnections.publish(ticket) { _status.update {
                it.copy(
                    isConnecting = false,
                    connectingSsid = null,
                    connectingBssid = null,
                    errorMessage = err
                )
            } }
            return@serialized false
        }

        secondaryConnections.publish(ticket) {
            _status.update { it.copy(isConnecting = true, connectingSsid = ssid, connectingBssid = bssid, errorMessage = null) }
        }
        var connectionAccepted = false
        var requestChanged = false
        try {
            checkCurrentSecondaryIntent(ticket)
            check(canConnectSecondaryNetwork(context, ssid)) {
                context.getString(com.multiroute.R.string.dual_wifi_saved_network_required)
            }
            checkCurrentSecondaryIntent(ticket)
            // Only replace requests created by this app; other shell clients retain their requests.
            requestChanged = true
            check(releaseOwnedShellRequest(context)) { "无法释放之前的副 Wi-Fi 请求" }
            val existing = SuHelper.executeShellWithOutput("cmd wifi list-requests")
            check(existing.isEmpty() || existing.all { it.isBlank() || it.contains("No active requests") }) {
                "系统存在其他 Wi-Fi 连接请求，请结束后再试"
            }
            val prepScript = """
                cmd wifi set-multi-internet-mode 2 2>/dev/null
                cmd wifi network-requests-set-user-approved android yes
            """.trimIndent()
            check(SuHelper.executeCommand(prepScript, timeoutSeconds = 5))
            checkCurrentSecondaryIntent(ticket)

            // 2. Resolve security type: open, owe, wpa2, wpa3, wep
            val secType = resolveSecurityType(capabilities, passphrase)
            val passArg = if (secType != "open" && secType != "owe" && !passphrase.isNullOrEmpty()) " " + WifiShellCommands.quote(passphrase) else ""

            val rom = detectRomType()
            val configuredScheme = DualWifiScheme.fromId(com.multiroute.data.RouteConfigProvider.getDualWifiScheme(context))
            val effectiveScheme = getEffectiveScheme(configuredScheme, rom)
            val isXiaomiSlave = effectiveScheme == DualWifiScheme.XIAOMI_SLAVE || rom.romType == RomType.XIAOMI_HYPEROS

            var connectedViaSlaveService = false
            if (isXiaomiSlave) {
                // SlaveWifiService requires a saved netId. Reuse existing configurations untouched.
                if (getNetworkIdForSsid(ssid) == null) {
                    check(SuHelper.executeCommand("cmd wifi add-network ${WifiShellCommands.quote(ssid)} $secType$passArg -d",
                        timeoutSeconds = 3, sensitive = true))
                }
                val netId = getNetworkIdForSsid(ssid)
                if (netId != null) {
                    Log.i(TAG, "Connecting secondary Wi-Fi via Xiaomi SlaveWifiService: netId=$netId bssid=$bssid")
                    // 1. Send broadcast to MultiRouteModule in system_server (LSPosed path)
                    val slaveIntent = android.content.Intent(com.multiroute.util.ModuleStateParser.ACTION_CONNECT_SLAVE_WIFI).apply {
                        setPackage("android")
                        putExtra(com.multiroute.util.ModuleStateParser.EXTRA_NET_ID, netId)
                        if (!bssid.isNullOrEmpty()) {
                            putExtra(com.multiroute.util.ModuleStateParser.EXTRA_BSSID, bssid)
                        }
                        addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                    runCatching { context.sendBroadcast(slaveIntent) }

                    // 2. Execute SlaveWifiBridge via app_process as Root (universal Root path)
                    val apkPath = context.applicationInfo.sourceDir
                    val bssidEscaped = if (!bssid.isNullOrEmpty()) " \"$bssid\"" else ""
                    val bridgeCmd = "CLASSPATH=\"$apkPath\" app_process / com.multiroute.wifi.SlaveWifiBridge connect $netId$bssidEscaped"
                    SuHelper.executeCommand(bridgeCmd, timeoutSeconds = 5)
                    connectedViaSlaveService = true
                } else {
                    Log.w(TAG, "Network ID not found for SSID '$ssid' in list-networks; falling back to cmd wifi add-request")
                }
            }

            if (!connectedViaSlaveService) {
                checkCurrentSecondaryIntent(ticket)
                val request = WifiShellCommands.internetRequest(ssid, secType, passphrase, bssid, frequencyMhz)
                check(context.getSharedPreferences(com.multiroute.data.RouteConfigProvider.PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(SHELL_REQUEST_PREF, WifiShellCommands.internetRequestKey(ssid, frequencyMhz)).commit())
                checkCurrentSecondaryIntent(ticket)
                check(SuHelper.executeCommand(request, timeoutSeconds = 5, sensitive = true))
                SuHelper.executeCommand("cmd wifi start-scan", timeoutSeconds = 3)
            }

            // 4. Poll for secondary connection for up to 12 seconds with auth failure detection
            var connected = false
            var failureReason: String? = null
            for (i in 0 until 12) {
                delay(1000)
                checkCurrentSecondaryIntent(ticket)
                refreshConnectionState(context)
                checkCurrentSecondaryIntent(ticket)
                if (_status.value.secondaryConnected && _status.value.secondarySsid == ssid &&
                    (bssid.isNullOrEmpty() || _status.value.secondaryBssid.equals(bssid, ignoreCase = true))) {
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
                connectionAccepted = secondaryConnections.publish(ticket) {
                    if (ssid != secState.primarySsid) {
                        com.multiroute.data.RouteConfigProvider.saveLastSecondaryWifi(
                            context,
                            ssid = ssid,
                            pass = passphrase,
                            freq = frequencyMhz,
                            bssid = bssid,
                            caps = capabilities
                        )
                    }
                    DualWifiService.start(context, secState.secondarySsid ?: ssid, secState.secondaryIface ?: "wlan1")
                    scope.launch {
                        SuHelper.syncAllRouteRules(context)
                    }
                    _status.update { it.copy(isConnecting = false, connectingSsid = null, connectingBssid = null) }
                }
                connectionAccepted
            } else {
                val err = failureReason ?: "连接副 Wi-Fi 超时，请检查密码是否正确，且副 Wi-Fi 频段需与主 Wi-Fi 互补（如 2.4G + 5G）"
                secondaryConnections.publish(ticket) { _status.update {
                    it.copy(
                        isConnecting = false,
                        connectingSsid = null,
                        connectingBssid = null,
                        errorMessage = err
                    )
                } }
                false
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "connectSecondaryWifi coroutine cancelled")
                secondaryConnections.publish(ticket) {
                    _status.update { it.copy(isConnecting = false, connectingSsid = null, connectingBssid = null) }
                }
                throw t
            }
            Log.e(TAG, "Failed to connect secondary wifi", t)
            secondaryConnections.publish(ticket) {
                _status.update { it.copy(isConnecting = false, connectingSsid = null,
                            connectingBssid = null, errorMessage = "发起连接失败: ${t.message}") }
            }
            false
        } finally {
            if (!connectionAccepted && requestChanged) releaseOwnedShellRequest(context)
        }
      }
    }

    private fun checkCurrentSecondaryIntent(ticket: SecondaryConnectionCoordinator.Ticket) {
        if (!secondaryConnections.isCurrent(ticket)) throw kotlinx.coroutines.CancellationException("Secondary connection superseded")
    }

    /**
     * Attempts to auto-reconnect the last configured secondary Wi-Fi if enabled and not currently connected.
     */
    suspend fun autoReconnectSecondary(context: Context): Boolean {
      val ticket = secondaryConnections.retry() ?: return false
      return withContext(Dispatchers.IO) {
        if (!com.multiroute.data.RouteConfigProvider.isDualWifiAutoReconnectEnabled(context)) {
            return@withContext false
        }
        refreshConnectionState(context)
        if (!secondaryConnections.isCurrent(ticket)) return@withContext false
        if (_status.value.secondaryConnected) return@withContext true

        // Yield radio resource to personal hotspot if enabled
        if (_status.value.isHotspotEnabled || checkHotspotState(context).isEnabled) {
            Log.i(TAG, "Secondary auto-reconnect skipped: Hotspot (SoftAP) is active, yielding radio chain")
            return@withContext false
        }

        val lastWifi = com.multiroute.data.RouteConfigProvider.getLastSecondaryWifi(context)
        val ssid = lastWifi["ssid"] as? String ?: return@withContext false
        val pass = (lastWifi["pass"] as? String)?.takeIf { it.isNotEmpty() }
        val freq = lastWifi["freq"] as? Int ?: 0
        val bssid = lastWifi["bssid"] as? String
        val caps = lastWifi["caps"] as? String

        val currentPrimarySsid = _status.value.primarySsid
        val currentPrimaryBssid = _status.value.primaryBssid
        if (isPrimaryCollision(ssid, bssid, currentPrimarySsid, currentPrimaryBssid)) {
            Log.w(TAG, "Prevented secondary auto-reconnect: configured secondary '$ssid' matches active primary '$currentPrimarySsid'")
            secondaryConnections.publish(ticket) {
                com.multiroute.data.RouteConfigProvider.clearLastSecondaryWifi(context)
            }
            return@withContext false
        }

        Log.i(TAG, "Auto reconnecting secondary Wi-Fi: $ssid")
        if (!secondaryConnections.isCurrent(ticket)) return@withContext false
        connectSecondaryWifi(context, ssid, pass, freq, bssid, caps, ticket)
      }
    }

    /**
     * Disconnects the secondary Wi-Fi network.
     */
    fun disconnectSecondaryWifi(context: Context, clearSavedPreference: Boolean = true) {
        // Invalidate an in-flight attempt before waiting for any blocking root operation.
        val ticket = secondaryConnections.disconnect(suppressReconnect = clearSavedPreference)
        scope.launch {
          secondaryConnections.serialized {
            // A later explicit connect may have overtaken this queued disconnect.
            if (!secondaryConnections.isCurrent(ticket)) return@serialized
            try {
            if (clearSavedPreference) com.multiroute.data.RouteConfigProvider.clearLastSecondaryWifi(context)
            DualWifiService.stop(context)
            // Disconnecting a link must not forget saved networks or remove other clients' requests.
            releaseOwnedShellRequest(context)
            val rom = detectRomType()
            if (rom.romType == RomType.XIAOMI_HYPEROS) {
            // Broadcast disconnect to system_server
            val slaveIntent = android.content.Intent(com.multiroute.util.ModuleStateParser.ACTION_DISCONNECT_SLAVE_WIFI).apply {
                setPackage("android")
                addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            }
            runCatching { context.sendBroadcast(slaveIntent) }

            val apkPath = context.applicationInfo.sourceDir
            val disconnectScript = """
                service call SlaveWifiService 5 2>/dev/null
                CLASSPATH="$apkPath" app_process / com.multiroute.wifi.SlaveWifiBridge disconnect 2>/dev/null
            """.trimIndent()
            SuHelper.executeCommand(disconnectScript, timeoutSeconds = 5)
            }
            delay(500)
            refreshConnectionState(context)
            secondaryConnections.publish(ticket) {
                _status.update { it.copy(isConnecting = false, connectingSsid = null, connectingBssid = null) }
            }
            SuHelper.syncAllRouteRules(context)
            } finally {
                secondaryConnections.disconnected(ticket)
            }
          }
        }
    }
}

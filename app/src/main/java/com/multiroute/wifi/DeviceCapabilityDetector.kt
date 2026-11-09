package com.multiroute.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.annotation.StringRes
import com.multiroute.R
import com.multiroute.util.NetworkUtils
import com.multiroute.util.SuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Probes device ROM, hardware chipset, kernel network interfaces, and STA+STA concurrency capabilities.
 */
object DeviceCapabilityDetector {

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
     * Resolves Wi-Fi chipset architecture to a localized string resource id.
     * Supports parameter overrides for test simulations.
     */
    @StringRes
    fun detectWifiChipsetRes(lsmodOutput: String? = null): Int {
        val lsmodStr = lsmodOutput ?: runCatching {
            SuHelper.executeShellWithOutput("lsmod 2>/dev/null", timeoutSeconds = 3).joinToString(" ")
        }.getOrDefault("")
        return when {
            lsmodStr.contains("bcmdhd4389", ignoreCase = true) -> R.string.wifi_i18n_chip_broadcom_4389
            lsmodStr.contains("bcmdhd", ignoreCase = true) -> R.string.wifi_i18n_chip_broadcom
            lsmodStr.contains("qca", ignoreCase = true) || lsmodStr.contains("wlan", ignoreCase = true) -> R.string.wifi_i18n_chip_qualcomm
            lsmodStr.contains("wmt_drv", ignoreCase = true) || lsmodStr.contains("wlan_mt", ignoreCase = true) -> R.string.wifi_i18n_chip_mediatek
            else -> R.string.wifi_i18n_chip_generic
        }
    }

    /**
     * Probes Wi-Fi chipset architecture.
     * Supports parameter overrides for test simulations.
     */
    fun detectWifiChipset(context: Context, lsmodOutput: String? = null): String =
        context.getString(detectWifiChipsetRes(lsmodOutput))

    fun parseWifiStatus(context: Context, lines: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val fullText = lines.joinToString("\n")
        val blocks = fullText.split("==== ClientModeManager instance:")
        for (block in blocks) {
            val ifaceMatch = Regex("""iface=(\w+)""").find(block) ?: continue
            val iface = ifaceMatch.groupValues[1]
            val ssidMatch = Regex("""SSID: "([^"]+)"""").find(block)
            val notConnected = context.getString(R.string.wifi_i18n_ssid_not_connected)
            val ssid = ssidMatch?.groupValues?.get(1) ?: notConnected
            if (block.contains("Wifi is not connected") || ssid == notConnected || ssid == "<unknown ssid>") {
                result[iface] = context.getString(R.string.wifi_i18n_not_connected_status)
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

            val freqText = if (freq > 0) context.getString(R.string.wifi_i18n_detail_frequency_channel, freq, channel) else ""
            val valText = context.getString(if (isValidated) R.string.wifi_i18n_detail_validated else R.string.wifi_i18n_detail_unvalidated)
            result[iface] = "$standard • $freqText • Tx $txSpeed / Rx $rxSpeed Mbps • $rssi dBm • $valText"
        }
        return result
    }

    /**
     * Checks whether the device hardware and framework support Dual Wi-Fi (STA + STA Concurrency).
     */
    suspend fun checkCapability(context: Context): DualWifiCapability = withContext(Dispatchers.IO) {
        DualWifiManager.ensureHotspotReceiverRegistered(context)
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
        val wifiChipsetRes = detectWifiChipsetRes()
        val wifiChipset = context.getString(wifiChipsetRes)

        // Determine primary and secondary interfaces
        val activeDefaultIface = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            cm?.getLinkProperties(net)?.interfaceName
        }.getOrNull()

        val (primary, secondary) = DualWifiBandHelper.resolvePrimarySecondaryIfaces(ifaces, activeDefaultIface)

        // Extract physical link details for active Wi-Fi interfaces
        val statusOutput = SuHelper.executeShellWithOutput("cmd wifi status 2>/dev/null", timeoutSeconds = 3)
        val ifaceDetails = parseWifiStatus(context, statusOutput)
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
            wifiChipsetRes = wifiChipsetRes,
            wifiChipset = wifiChipset,
            overlayStatus = if (configEnabled) context.getString(R.string.wifi_i18n_overlay_enabled_detail) else context.getString(R.string.wifi_i18n_disabled),
            primaryDetails = primDet,
            secondaryDetails = secDet,
            detail = when {
                !hwSupported -> context.getString(R.string.wifi_i18n_capability_unsupported)
                effectiveScheme == DualWifiScheme.XIAOMI_SLAVE -> context.getString(R.string.wifi_i18n_capability_xiaomi)
                effectiveScheme == DualWifiScheme.ROOT_DIRECT -> context.getString(R.string.wifi_i18n_capability_root)
                !configEnabled -> context.getString(R.string.wifi_i18n_capability_overlay_off)
                !modeActive -> context.getString(R.string.wifi_i18n_capability_mode_off)
                else -> context.getString(R.string.wifi_i18n_capability_ready)
            }
        )

        // Keep Xiaomi's slave radio awake only while multi-internet mode is active. Remember
        // the previous user value so the explicit stop action can restore it.
        if (modeActive && (effectiveScheme == DualWifiScheme.XIAOMI_SLAVE || romInfo.romType == RomType.XIAOMI_HYPEROS)) {
            if (!com.multiroute.data.RouteConfigProvider.isKeepSlaveWifiScreenOff(context)) {
                com.multiroute.data.RouteConfigProvider.recordKeepSlaveWifiScreenOffBeforeDualWifi(context)
                SuHelper.setKeepSlaveWifiScreenOff(context, true)
            }
        }
        return@withContext cap
    }
}

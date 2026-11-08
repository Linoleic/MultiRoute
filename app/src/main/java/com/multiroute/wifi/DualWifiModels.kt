package com.multiroute.wifi

import android.os.Build
import com.multiroute.util.HotspotHookState

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

enum class DualWifiDeactivationResult { STOPPED, HOTSPOT_USES_SECONDARY, FAILED }

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

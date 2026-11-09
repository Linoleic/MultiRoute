package com.multiroute.wifi

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.multiroute.R
import com.multiroute.util.HotspotHookState

enum class DualWifiScheme(val id: String, @param:StringRes @get:StringRes val labelRes: Int) {
    AUTO("auto", R.string.dual_wifi_scheme_auto_title),
    AOSP_OVERLAY("aosp_overlay", R.string.dual_wifi_scheme_aosp_title),
    XIAOMI_SLAVE("xiaomi_slave", R.string.dual_wifi_scheme_xiaomi_title),
    ROOT_DIRECT("root_direct", R.string.dual_wifi_scheme_root_title);

    fun label(context: Context): String = context.getString(labelRes)

    companion object {
        fun fromId(id: String?): DualWifiScheme =
            values().firstOrNull { it.id == id } ?: AUTO
    }
}

enum class DualWifiDeactivationResult { STOPPED, HOTSPOT_USES_SECONDARY, FAILED }

enum class RomType(@param:StringRes @get:StringRes val labelRes: Int) {
    AOSP_PIXEL(R.string.wifi_i18n_rom_aosp),
    XIAOMI_HYPEROS(R.string.wifi_i18n_rom_xiaomi),
    COLOROS_OPPO(R.string.wifi_i18n_rom_coloros),
    ORIGINOS_VIVO(R.string.wifi_i18n_rom_originos),
    SAMSUNG_ONEUI(R.string.wifi_i18n_rom_oneui),
    GENERIC_OEM(R.string.wifi_i18n_rom_generic);

    fun label(context: Context): String = context.getString(labelRes)
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
    @param:StringRes @get:StringRes val wifiChipsetRes: Int = 0,
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

    val channel: Int get() = when {
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz == 2484 -> 14
        frequencyMhz in 5160..5885 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5 + 1
        else -> 0
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
    @param:StringRes @get:StringRes val bandRes: Int? = null,
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
    @param:StringRes @get:StringRes val hotspotBandRes: Int? = null,
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

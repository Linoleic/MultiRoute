package com.multiroute.model

data class TestServerPreset(
    val id: String,
    val nameRes: Int,
    val url: String,
    val descriptionRes: Int,
    val isDomestic: Boolean = false
)

data class TestServerConfig(
    val selectedPresetId: String = DEFAULT_PRESET_ID,
    val customUrl: String = "",
    val activeUrl: String = DEFAULT_URL,
    val activeDisplayName: String = DEFAULT_DISPLAY_NAME
) {
    val isCustom: Boolean get() = selectedPresetId == PRESET_CUSTOM
    val usesDomesticPreset: Boolean
        get() = !isCustom && PRESETS.firstOrNull { it.id == selectedPresetId }?.isDomestic == true

    fun effectiveForChannel(isVpn: Boolean): TestServerConfig {
        if (!isVpn || !usesDomesticPreset) return this
        return TestServerConfig(
            selectedPresetId = VPN_DEFAULT_PRESET_ID,
            activeUrl = VPN_DEFAULT_URL,
            activeDisplayName = VPN_DEFAULT_DISPLAY_NAME
        )
    }

    /**
     * Name to display: the selected preset's translated name, or the stored label (used by the custom
     * entry, and by the fallback when no preset matches).
     */
    fun displayName(context: android.content.Context): String {
        val preset = PRESETS.firstOrNull { it.id == selectedPresetId }
        return if (preset != null) context.getString(preset.nameRes) else context.getString(com.multiroute.R.string.server_custom)
    }

    companion object {
        const val PRESET_IPIP = "ipip"
        const val PRESET_CIP = "cip"
        const val PRESET_IPSB = "ipsb"
        const val PRESET_IPIFY = "ipify"
        const val PRESET_IFCONFIG = "ifconfig"
        const val PRESET_ICANHAZIP = "icanhazip"
        const val PRESET_CUSTOM = "custom"

        val PRESETS = listOf(
            TestServerPreset(
                id = PRESET_IPIP,
                nameRes = com.multiroute.R.string.preset_ipip_name,
                url = "https://myip.ipip.net",
                descriptionRes = com.multiroute.R.string.preset_ipip_desc,
                isDomestic = true
            ),
            TestServerPreset(
                id = PRESET_CIP,
                nameRes = com.multiroute.R.string.preset_cip_name,
                url = "https://cip.cc",
                descriptionRes = com.multiroute.R.string.preset_cip_desc,
                isDomestic = true
            ),
            TestServerPreset(
                id = PRESET_IPSB,
                nameRes = com.multiroute.R.string.preset_ipsb_name,
                url = "https://api.ip.sb/geoip",
                descriptionRes = com.multiroute.R.string.preset_ipsb_desc
            ),
            TestServerPreset(
                id = PRESET_IPIFY,
                nameRes = com.multiroute.R.string.preset_ipify_name,
                url = "https://api.ipify.org",
                descriptionRes = com.multiroute.R.string.preset_ipify_desc
            ),
            TestServerPreset(
                id = PRESET_IFCONFIG,
                nameRes = com.multiroute.R.string.preset_ifconfig_name,
                url = "https://ifconfig.me/ip",
                descriptionRes = com.multiroute.R.string.preset_ifconfig_desc
            ),
            TestServerPreset(
                id = PRESET_ICANHAZIP,
                nameRes = com.multiroute.R.string.preset_icanhazip_name,
                url = "https://icanhazip.com",
                descriptionRes = com.multiroute.R.string.preset_icanhazip_desc
            )
        )

        const val DEFAULT_PRESET_ID = PRESET_IPIP
        const val DEFAULT_URL = "https://myip.ipip.net"
        const val VPN_DEFAULT_PRESET_ID = PRESET_IPSB
        const val VPN_DEFAULT_URL = "https://api.ip.sb/geoip"
        const val VPN_DEFAULT_DISPLAY_NAME = "ip.sb"
        val DOMESTIC_FALLBACK_URLS = listOf("https://myip.ipip.net", "https://cip.cc", "https://icanhazip.com")
        val OVERSEAS_FALLBACK_URLS = listOf(
            "https://api.ip.sb/geoip",
            "https://cp.cloudflare.com/cdn-cgi/trace",
            "https://icanhazip.com",
            "https://api.ipify.org"
        )
        /** Stored fallback only: what gets displayed comes from the preset resource or server_custom. */
        const val DEFAULT_DISPLAY_NAME = ""
    }
}

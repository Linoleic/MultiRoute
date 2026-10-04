package com.multiroute.model

data class TestServerPreset(
    val id: String,
    val name: String,
    val url: String,
    val description: String
)

data class TestServerConfig(
    val selectedPresetId: String = DEFAULT_PRESET_ID,
    val customUrl: String = "",
    val activeUrl: String = DEFAULT_URL,
    val activeDisplayName: String = DEFAULT_DISPLAY_NAME
) {
    val isCustom: Boolean get() = selectedPresetId == PRESET_CUSTOM

    companion object {
        const val PRESET_IPIP = "ipip"
        const val PRESET_CIP = "cip"
        const val PRESET_IPIFY = "ipify"
        const val PRESET_IFCONFIG = "ifconfig"
        const val PRESET_ICANHAZIP = "icanhazip"
        const val PRESET_CUSTOM = "custom"

        val PRESETS = listOf(
            TestServerPreset(
                id = PRESET_IPIP,
                name = "IPIP.net (推荐 / 含归属地)",
                url = "https://myip.ipip.net",
                description = "国内响应极快，返回外网 IPv4 及省市运营商归属"
            ),
            TestServerPreset(
                id = PRESET_CIP,
                name = "cip.cc (含归属地)",
                url = "https://cip.cc",
                description = "轻量接口，包含公网 IP 及网络运营商与位置归属"
            ),
            TestServerPreset(
                id = PRESET_IPIFY,
                name = "api.ipify.org (纯公网 IP)",
                url = "https://api.ipify.org",
                description = "全球 Anycast CDN，仅返回公网 IPv4 纯文本"
            ),
            TestServerPreset(
                id = PRESET_IFCONFIG,
                name = "ifconfig.me (纯公网 IP)",
                url = "https://ifconfig.me/ip",
                description = "知名终端 IP 查询服务，返回纯公网 IP"
            ),
            TestServerPreset(
                id = PRESET_ICANHAZIP,
                name = "icanhazip.com (纯公网 IP)",
                url = "https://icanhazip.com",
                description = "Cloudflare 托管，轻量且高可靠"
            )
        )

        const val DEFAULT_PRESET_ID = PRESET_IPIP
        const val DEFAULT_URL = "https://myip.ipip.net"
        const val DEFAULT_DISPLAY_NAME = "IPIP.net (推荐 / 含归属地)"
    }
}

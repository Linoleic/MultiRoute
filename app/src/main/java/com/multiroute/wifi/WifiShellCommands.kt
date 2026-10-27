package com.multiroute.wifi

/** Shell arguments come from nearby APs and saved credentials, not from trusted source text. */
object WifiShellCommands {
    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    fun internetRequest(ssid: String, security: String, password: String?, bssid: String?, frequencyMhz: Int): String {
        require(ssid.isNotBlank())
        require(!ssid.startsWith("-")) { "当前系统命令不支持以 - 开头的网络名称" }
        require(security in setOf("open", "owe", "wpa2", "wpa3"))
        val secret = if (security in setOf("wpa2", "wpa3")) " " + quote(requireNotNull(password)) else ""
        val ap = bssid?.takeIf { it.isNotEmpty() }?.let { " -b " + quote(it) }.orEmpty()
        // Android 16 consumes a leading option only as -g/-s. Internet is parsed after credentials.
        return "cmd wifi add-request ${quote(ssid)} $security$secret$ap -d ${band(frequencyMhz)} -i"
    }

    fun internetRequestKey(ssid: String, frequencyMhz: Int): String = "${ssid}_${band(frequencyMhz)}g_internet"

    private fun band(frequencyMhz: Int): Int = when (frequencyMhz) {
        in 2400..2500 -> 2
        in 4900..5900 -> 5
        in 5925..7125 -> 6
        else -> throw IllegalArgumentException("无法识别副网频段，请重新扫描")
    }

    fun removeRequest(ssid: String): String = "cmd wifi remove-request ${quote(ssid)}"
}

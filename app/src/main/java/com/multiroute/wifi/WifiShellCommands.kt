package com.multiroute.wifi

/** Shell arguments come from nearby APs and saved credentials, not from trusted source text. */
object WifiShellCommands {
    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    fun savedInternetRequest(bssid: String, frequencyMhz: Int): String {
        require(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}").matches(bssid))
        require(bssid.take(2).toInt(16) and 1 == 0 &&
            bssid != "00:00:00:00:00:00" && bssid != "02:00:00:00:00:00")
        // The privileged Multi-Internet path selects saved credentials by band/BSSID.
        // -s omits SSID/security arguments; it does not create or rewrite a configuration.
        return "cmd wifi add-request -s -b ${quote(bssid)} -d ${band(frequencyMhz)} -i"
    }

    fun savedInternetRequestKey(frequencyMhz: Int): String = "NoSsid_${band(frequencyMhz)}g_internet"

    fun slaveConnect(apkPath: String, networkId: Int, bssid: String?): String {
        require(networkId >= 0)
        val ap = bssid?.takeIf { it.isNotBlank() }?.let { " " + quote(it) }.orEmpty()
        return "CLASSPATH=${quote(apkPath)} app_process / com.multiroute.wifi.SlaveWifiBridge connect $networkId$ap"
    }

    fun slaveDisconnect(apkPath: String): String =
        "CLASSPATH=${quote(apkPath)} app_process / com.multiroute.wifi.SlaveWifiBridge disconnect"

    private fun band(frequencyMhz: Int): Int = when (frequencyMhz) {
        in 2400..2500 -> 2
        in 4900..5900 -> 5
        in 5925..7125 -> 6
        else -> throw IllegalArgumentException("无法识别副网频段，请重新扫描")
    }

    fun removeRequest(ssid: String): String = "cmd wifi remove-request ${quote(ssid)}"
}

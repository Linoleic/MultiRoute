package com.multiroute.wifi

import com.multiroute.util.NetworkUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualWifiScanParsingTest {

    @Test
    fun testXiaomiHyperOsMimoRssiFormat() {
        // Real dump from Xiaomi 15 Pro (HyperOS 2) with Qualcomm FastConnect MIMO per-antenna RSSI
        val lines = listOf(
            "    BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags",
            "  d6:b3:33:cc:e7:f9       5180    -79(0:-80/1:-87)     2.401    dwjdwj                            [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]",
            "  4e:23:38:c4:d0:b5       5805    -79(0:-80/1:-85)     1.431    LZH 6952                          [WPA2-PSK-CCMP-128][RSN-PSK+SAE-CCMP-128][ESS][WPS][MFPC]",
            "  40:31:3c:04:90:2e       5260    -46(0:-50/1:-49)     0.701    Wrt-F5G                           [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]",
            "  42:1b:7f:3a:47:f1       5765    -61(0:-62/1:-67)     1.690    🐺❤️🍭好有面子😍                      [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]"
        )

        val results = DualWifiManager.parseScanResultLines(lines)

        assertEquals(4, results.size)

        val wrt = results.find { it.ssid == "Wrt-F5G" }
        assertNotNull(wrt)
        assertEquals(-46, wrt!!.rssi)
        assertEquals(4, wrt.level) // -46 >= -55 -> 4 bars

        val emojiSsid = results.find { it.ssid == "🐺❤️🍭好有面子😍" }
        assertNotNull(emojiSsid)
        assertEquals(-61, emojiSsid!!.rssi)
        assertEquals(3, emojiSsid.level) // -61 >= -65 -> 3 bars

        val dwj = results.find { it.ssid == "dwjdwj" }
        assertNotNull(dwj)
        assertEquals(-79, dwj!!.rssi)
        assertEquals(1, dwj.level) // -79 >= -85 -> 1 bar
    }

    @Test
    fun testStandardAospScanFormat() {
        // Output from modern Android (e.g. Xiaomi HyperOS / AOSP)
        val lines = listOf(
            "    BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags",
            "  ae:f2:3c:75:3a:9b       5745        -77            614.614    四个字：三个字                           [WPA2-PSK-CCMP-128][RSN-PSK+SAE-CCMP-128][ESS][WPS][MFPC]",
            "  92:8a:02:e4:3c:d8       5805        -72            614.614    REDMI K80 Ultra                   [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]",
            "  ca:e9:c8:ac:ca:15       2437        -67            614.614    OnePlus 13 D180                   [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]"
        )

        val results = DualWifiManager.parseScanResultLines(lines)

        assertEquals(3, results.size)

        val redmi = results.find { it.ssid == "REDMI K80 Ultra" }
        assertNotNull(redmi)
        assertEquals("92:8a:02:e4:3c:d8", redmi!!.bssid)
        assertEquals(5805, redmi.frequencyMhz)
        assertEquals(-72, redmi.rssi)
        assertEquals(2, redmi.level)
        assertTrue(redmi.capabilities.contains("WPA2-PSK"))

        val oneplus = results.find { it.ssid == "OnePlus 13 D180" }
        assertNotNull(oneplus)
        assertEquals(2437, oneplus!!.frequencyMhz)
        assertEquals(-67, oneplus.rssi)
        assertEquals(2, oneplus.level)
    }

    @Test
    fun testInvertedAgeAndRssiScanFormat() {
        // Some ROMs and older AOSP versions output Age before RSSI
        val lines = listOf(
            "BSSID / Frequency / Age / RSSI / SSID / Flags",
            "00:11:22:33:44:55 5260 12.345 -58 Home_5G [WPA2-PSK-CCMP][ESS]",
            "aa:bb:cc:dd:ee:ff 2412 1.5 -82 Guest_24G [WPA2-PSK-CCMP][ESS]"
        )

        val results = DualWifiManager.parseScanResultLines(lines)

        assertEquals(2, results.size)

        val home = results.find { it.ssid == "Home_5G" }
        assertNotNull(home)
        assertEquals(-58, home!!.rssi)
        assertEquals(3, home.level)
        assertEquals(5260, home.frequencyMhz)

        val guest = results.find { it.ssid == "Guest_24G" }
        assertNotNull(guest)
        assertEquals(-82, guest!!.rssi)
        assertEquals(1, guest.level)
        assertEquals(2412, guest.frequencyMhz)
    }

    @Test
    fun testFloatAndDbmSuffixRssi() {
        // Output with float RSSI or dBm suffix
        val lines = listOf(
            "bssid / frequency / signal level / flags / ssid",
            "12:34:56:78:9a:bc 5180 -65.4dBm [WPA2-PSK-CCMP][ESS] Office_Fast_WiFi",
            "fe:dc:ba:98:76:54 2462 -70.0 [WPA2-PSK-CCMP][ESS] Warehouse_AP"
        )

        val results = DualWifiManager.parseScanResultLines(lines)

        assertEquals(2, results.size)

        val office = results.find { it.ssid == "Office_Fast_WiFi" }
        assertNotNull(office)
        assertEquals(-65, office!!.rssi)
        assertEquals(3, office.level)

        val warehouse = results.find { it.ssid == "Warehouse_AP" }
        assertNotNull(warehouse)
        assertEquals(-70, warehouse!!.rssi)
        assertEquals(2, warehouse.level)
    }

    @Test
    fun testWpaCliFiveColumnFormat() {
        // wpa_cli format: bssid / frequency / signal level / flags / ssid
        val lines = listOf(
            "bssid / frequency / signal level / flags / ssid",
            "aa:bb:cc:11:22:33\t5745\t-61\t[WPA2-PSK-CCMP][ESS]\tMeeting Room AP",
            "dd:ee:ff:44:55:66\t2437\t-50\t[WPA-PSK-TKIP][ESS]\tLegacy Net"
        )

        val results = DualWifiManager.parseScanResultLines(lines)

        assertEquals(2, results.size)

        val meeting = results.find { it.ssid == "Meeting Room AP" }
        assertNotNull(meeting)
        assertEquals(5745, meeting!!.frequencyMhz)
        assertEquals(-61, meeting.rssi)
        assertEquals(3, meeting.level)
        assertEquals("Meeting Room AP", meeting.ssid)
    }

    @Test
    fun testNoHeaderRawLines() {
        // Raw dump without any header line
        val lines = listOf(
            "10:20:30:40:50:60 5200 -54 >1000 MyHomeWiFi [WPA2-PSK-CCMP][ESS]"
        )

        val results = DualWifiManager.parseScanResultLines(lines)

        assertEquals(1, results.size)
        val item = results[0]
        assertEquals("MyHomeWiFi", item.ssid)
        assertEquals(5200, item.frequencyMhz)
        assertEquals(-54, item.rssi)
        assertEquals(4, item.level)
    }

    @Test
    fun testActiveConnectionLiveRssiEnrichment() {
        val lines = listOf(
            "BSSID Frequency RSSI Age SSID Flags",
            "40:31:3c:04:90:2e 5260 -80 100 Wrt-F5G [WPA2-PSK-CCMP][ESS]"
        )
        // Simulated live connection details from cmd wifi status: RSSI is -49
        val connectedDetails = mapOf(
            "wlan0" to NetworkUtils.WifiConnectionDetails(
                ssid = "Wrt-F5G",
                bssid = "40:31:3c:04:90:2e",
                frequencyMhz = 5260,
                rssi = -49
            )
        )

        val results = DualWifiManager.parseScanResultLines(
            lines = lines,
            connectedDetails = connectedDetails,
            primarySsid = "Wrt-F5G",
            primaryBssid = "40:31:3c:04:90:2e",
            primaryFreq = 5260
        )

        assertEquals(1, results.size)
        val item = results[0]
        assertEquals("Wrt-F5G", item.ssid)
        assertTrue(item.isPrimary)
        // Live connected RSSI (-49) should override stale scan RSSI (-80)
        assertEquals(-49, item.rssi)
        assertEquals(4, item.level)
    }

    @Test
    fun testSynthesizesMissingConnectedPrimaryWifi() {
        // Scan results do not include the connected primary Wi-Fi
        val lines = listOf(
            "BSSID Frequency RSSI Age SSID Flags",
            "11:22:33:44:55:66 2412 -75 10 Neighbor_WiFi [WPA2-PSK-CCMP][ESS]"
        )
        val connectedDetails = mapOf(
            "wlan0" to NetworkUtils.WifiConnectionDetails(
                ssid = "HiddenPrimary",
                bssid = "00:11:22:33:44:55",
                frequencyMhz = 5240,
                rssi = -55
            )
        )

        val results = DualWifiManager.parseScanResultLines(
            lines = lines,
            connectedDetails = connectedDetails,
            primarySsid = "HiddenPrimary",
            primaryBssid = "00:11:22:33:44:55",
            primaryFreq = 5240
        )

        assertEquals(2, results.size)
        val primary = results.find { it.isPrimary }
        assertNotNull(primary)
        assertEquals("HiddenPrimary", primary!!.ssid)
        assertEquals(-55, primary.rssi)
        assertEquals(4, primary.level)
    }

    @Test
    fun testDisconnectedSecondaryDoesNotDesignatePrimaryAsSecondary() {
        val connectedDetails = mapOf(
            "wlan0" to NetworkUtils.WifiConnectionDetails(
                ssid = "Wrt-F5G",
                bssid = "40:31:3c:04:90:2e",
                frequencyMhz = 5260,
                rssi = -52,
                iface = "wlan0",
                isPrimary = true
            )
        )
        val lines = listOf(
            "    BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags",
            "  40:31:3c:04:90:2e       5260    -46(0:-50/1:-49)     0.701    Wrt-F5G                           [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]",
            "  40:31:3c:04:90:2d       2412    -60                  1.200    Wrt-F5G                           [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]",
            "  12:34:56:78:9a:bc       2437    -70                  2.100    Guest_24G                         [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]"
        )

        val results = DualWifiManager.parseScanResultLines(
            lines = lines,
            connectedDetails = connectedDetails,
            primarySsid = "Wrt-F5G",
            primaryBssid = "40:31:3c:04:90:2e",
            primaryFreq = 5260,
            secondarySsid = null,
            secondaryBssid = null,
            secondaryFreq = 0,
            isSecConnected = false
        )

        val primaryItem = results.find { it.isPrimary }
        assertNotNull(primaryItem)
        assertEquals("Wrt-F5G", primaryItem!!.ssid)
        assertEquals(5260, primaryItem.frequencyMhz)

        // No item should be designated as secondary when secondary is not connected
        val secondaryItems = results.filter { it.isSecondary }
        assertTrue(secondaryItems.isEmpty())
    }

    @Test
    fun testResolvePrimarySecondaryIfacesAlwaysKeepsWlan0Primary() {
        val ifaces = listOf("wlan0", "wlan1")
        // Case 1: activeDefaultIface is null (e.g. app startup / background)
        val (prim1, sec1) = DualWifiManager.resolvePrimarySecondaryIfaces(ifaces, null)
        assertEquals("wlan0", prim1)
        assertEquals("wlan1", sec1)

        // Case 2: activeDefaultIface is tun0 (VPN active)
        val (prim2, sec2) = DualWifiManager.resolvePrimarySecondaryIfaces(ifaces, "tun0")
        assertEquals("wlan0", prim2)
        assertEquals("wlan1", sec2)

        // Case 3: activeDefaultIface is wlan0
        val (prim3, sec3) = DualWifiManager.resolvePrimarySecondaryIfaces(ifaces, "wlan0")
        assertEquals("wlan0", prim3)
        assertEquals("wlan1", sec3)
    }
}

package com.multiroute.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Band and channel mapping for the Wi-Fi frequency shown in the channel details. */
class NetworkUtilsTest {

    @Test
    fun testWifiBandLabel() {
        assertEquals("2.4 GHz", NetworkUtils.wifiBandLabel(2412))
        assertEquals("2.4 GHz", NetworkUtils.wifiBandLabel(2437))
        assertEquals("5 GHz", NetworkUtils.wifiBandLabel(5180))
        assertEquals("5 GHz", NetworkUtils.wifiBandLabel(5745))
        assertEquals("5 GHz", NetworkUtils.wifiBandLabel(5895))
        assertEquals("6 GHz", NetworkUtils.wifiBandLabel(5955))
        // Unknown or unset frequencies must not be labelled as a band at all.
        assertEquals("", NetworkUtils.wifiBandLabel(0))
        assertEquals("", NetworkUtils.wifiBandLabel(600))
    }

    @Test
    fun testWifiChannelNumber() {
        assertEquals(1, NetworkUtils.wifiChannelNumber(2412))
        assertEquals(6, NetworkUtils.wifiChannelNumber(2437))
        assertEquals(13, NetworkUtils.wifiChannelNumber(2472))
        assertEquals(14, NetworkUtils.wifiChannelNumber(2484))
        assertEquals(36, NetworkUtils.wifiChannelNumber(5180))
        assertEquals(149, NetworkUtils.wifiChannelNumber(5745))
        assertEquals(1, NetworkUtils.wifiChannelNumber(5955))
        assertEquals(37, NetworkUtils.wifiChannelNumber(6135))
        assertEquals(0, NetworkUtils.wifiChannelNumber(0))
    }

    @Test
    fun testParseWifiStatusDetailsExtractsLinkSpeedsWithoutMaxPrefixCollision() {
        val sample = listOf(
            "==== ClientModeManager instance: ConcreteClientModeManager{id=1 iface=wlan0 role=ROLE_CLIENT_PRIMARY} ====",
            "Wifi is connected to \"F5G\"",
            "WifiInfo: SSID: \"F5G\", BSSID: 40:31:3c:04:90:2e, MAC: d6:8a:b7:c6:6b:bd, IP: /192.168.1.207, Security type: 2, Supplicant state: COMPLETED, Wi-Fi standard: 11ac, RSSI: -56, Link speed: 780Mbps, Max Supported Tx Link speed: 866Mbps, Tx Link speed: 780Mbps, Max Supported Rx Link speed: 866Mbps, Rx Link speed: 866Mbps, Frequency: 5280MHz, IsPrimary: 1",
            "==== ClientModeManager instance: ConcreteClientModeManager{id=2 iface=wlan1 role=ROLE_CLIENT_SECONDARY_LONG_LIVED} ====",
            "Wifi is connected to \"LegacyAP\"",
            "WifiInfo: SSID: \"LegacyAP\", BSSID: 12:34:56:78:9a:bc, RSSI: -68, Link speed: 144Mbps, Frequency: 2437MHz",
            "==== ClientModeManager instance: ConcreteClientModeManager{id=3 iface=wlan2 role=ROLE_CLIENT_LOCAL_ONLY} ====",
            "Wifi is connected to \"<unknown ssid>\"",
            "WifiInfo: SSID: \"<unknown ssid>\", BSSID: 02:00:00:00:00:00, Link speed: -1Mbps, Tx Link speed: -1Mbps, Rx Link speed: -1Mbps"
        )

        val parsed = NetworkUtils.parseWifiStatusDetails(sample)
        assertEquals(2, parsed.size)

        val wlan0 = parsed.getValue("wlan0")
        assertEquals("F5G", wlan0.ssid)
        assertEquals("40:31:3c:04:90:2e", wlan0.bssid)
        assertEquals(5280, wlan0.frequencyMhz)
        assertEquals(-56, wlan0.rssi)
        assertEquals("192.168.1.207", wlan0.ip)
        assertEquals(true, wlan0.isPrimary)
        assertEquals(866, wlan0.rxLinkSpeedMbps)
        assertEquals(780, wlan0.txLinkSpeedMbps)

        val wlan1 = parsed.getValue("wlan1")
        assertEquals("LegacyAP", wlan1.ssid)
        assertEquals(false, wlan1.isPrimary)
        assertEquals(144, wlan1.rxLinkSpeedMbps)
        assertEquals(144, wlan1.txLinkSpeedMbps)
    }
}

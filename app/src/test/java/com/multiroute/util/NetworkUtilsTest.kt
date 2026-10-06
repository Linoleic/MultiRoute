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
}

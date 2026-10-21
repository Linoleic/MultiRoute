package com.multiroute.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlaveWifiBridgeTest {

    @Test
    fun testTransactionConstants() {
        assertEquals(2, SlaveWifiBridge.TRANSACTION_CONNECT_TO_SLAVE_AP)
        assertEquals(3, SlaveWifiBridge.TRANSACTION_CONNECT_TO_SLAVE_AP_WITH_BSSID)
        assertEquals(5, SlaveWifiBridge.TRANSACTION_DISCONNECT_SLAVE_WIFI)
        assertEquals(9, SlaveWifiBridge.TRANSACTION_SET_WIFI_SLAVE_ENABLED)
        assertEquals("android.net.wifi.ISlaveWifiManager", SlaveWifiBridge.DESCRIPTOR)
    }

    @Test
    fun testParseNetworkIdFromList() {
        val sampleOutput = listOf(
            "Network Id      SSID                         Security type",
            "0            1301                             wpa2-psk",
            "0            1301                             wpa3-sae",
            "5            DESKTOP-303Q1TM 4873             wpa2-psk",
            "5            DESKTOP-303Q1TM 4873             wpa3-sae",
            "7            GZHU                             open",
            "7            GZHU                             owe ",
            "13           Hpkt                             wpa2-psk",
            "13           Hpkt                             wpa3-sae",
            "35           Wrt-F5G                          wpa2-psk",
            "39           Xiaomi Pad 6S Pro..._SXD9HSS_MI  wpa2-psk"
        )

        assertEquals(13, DualWifiManager.parseNetworkIdFromList(sampleOutput, "Hpkt"))
        assertEquals(5, DualWifiManager.parseNetworkIdFromList(sampleOutput, "DESKTOP-303Q1TM 4873"))
        assertEquals(7, DualWifiManager.parseNetworkIdFromList(sampleOutput, "GZHU"))
        assertEquals(35, DualWifiManager.parseNetworkIdFromList(sampleOutput, "Wrt-F5G"))
        assertEquals(39, DualWifiManager.parseNetworkIdFromList(sampleOutput, "Xiaomi Pad 6S Pro..._SXD9HSS_MI"))
        assertNull(DualWifiManager.parseNetworkIdFromList(sampleOutput, "NonExistentWifi"))
    }
}

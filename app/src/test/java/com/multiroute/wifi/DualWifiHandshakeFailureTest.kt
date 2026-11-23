package com.multiroute.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DualWifiHandshakeFailureTest {
    @Test
    fun detectsWpaSupplicantAssocRejectOnSecondaryInterface() {
        val logs = listOf(
            "11-23 16:44:31.512 25308 25308 I wpa_supplicant: wlan1: CTRL-EVENT-ASSOC-REJECT bssid=40:31:3c:0e:52:f2 status_code=1"
        )
        assertEquals(
            SecondaryHandshakeFailure.AP_REJECTED,
            parseSecondaryHandshakeFailure(logs, targetBssid = "40:31:3C:0E:52:F2", primaryIface = "wlan0")
        )
    }

    @Test
    fun detectsAndroid16WifiClientModeImplAssocRejectionOnWlan1() {
        val logs = listOf(
            "11-23 16:44:31.513  3525  5207 D WifiClientModeImpl[15149118:wlan1]: L2ConnectingState: Association rejection  ssid: \"merciful\" bssid: 40:31:3c:0e:52:f2 statusCode: 1 timedOut: false"
        )
        assertEquals(
            SecondaryHandshakeFailure.AP_REJECTED,
            parseSecondaryHandshakeFailure(logs, targetBssid = "40:31:3c:0e:52:f2", primaryIface = "wlan0")
        )
    }

    @Test
    fun detectsWrongPasswordVariantsOnSecondaryInterface() {
        val wrongKey = listOf(
            "11-23 16:44:31.500 25308 25308 I wpa_supplicant: wlan1: CTRL-EVENT-ASSOC-REJECT WRONG_KEY"
        )
        val fourWay = listOf(
            "11-23 16:44:31.500 25308 25308 I wpa_supplicant: wlan1: WPA: 4-Way Handshake failed - pre-shared key may be incorrect"
        )
        assertEquals(SecondaryHandshakeFailure.WRONG_PASSWORD, parseSecondaryHandshakeFailure(wrongKey))
        assertEquals(SecondaryHandshakeFailure.WRONG_PASSWORD, parseSecondaryHandshakeFailure(fourWay))
    }

    @Test
    fun ignoresPrimaryInterfaceAndMismatchedBssidFailures() {
        val primaryOnly = listOf(
            "11-23 16:44:31.512 25308 25308 I wpa_supplicant: wlan0: CTRL-EVENT-ASSOC-REJECT bssid=40:31:3c:0e:52:f2 status_code=1",
            "11-23 16:44:31.513  3525  5207 D WifiClientModeImpl[33439:wlan0]: L2ConnectingState: Association rejection  ssid: \"F5G\" bssid: 40:31:3c:0e:52:f2 statusCode: 1"
        )
        val wrongBssid = listOf(
            "11-23 16:44:31.512 25308 25308 I wpa_supplicant: wlan1: CTRL-EVENT-ASSOC-REJECT bssid=11:22:33:44:55:66 status_code=1"
        )
        assertNull(parseSecondaryHandshakeFailure(primaryOnly, targetBssid = "40:31:3c:0e:52:f2", primaryIface = "wlan0"))
        assertNull(parseSecondaryHandshakeFailure(wrongBssid, targetBssid = "40:31:3c:0e:52:f2", primaryIface = "wlan0"))
    }
}

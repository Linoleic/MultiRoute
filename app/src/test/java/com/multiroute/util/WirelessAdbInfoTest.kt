package com.multiroute.util

import org.junit.Assert.*
import org.junit.Test

class WirelessAdbInfoTest {
    @Test fun permissionFailureRemainsUnknown() {
        val info = WirelessAdbInfo.parse(listOf("su: permission denied"))
        assertNull(info.enabled)
        assertNull(info.port)
        assertNull(info.listening)
        assertTrue(info.addresses.isEmpty())
    }

    @Test fun peerPortDoesNotCountAsLocalListener() {
        val info = WirelessAdbInfo.parse(listOf("ENABLED=1", "PORT=37425", "SOCKETS_OK",
            "LISTEN 0 4 127.0.0.1:5555 *:37425"))
        assertEquals(false, info.listening)
    }

    @Test fun dualWifiCandidatesAndWildcardListener() {
        val info = WirelessAdbInfo.parse(listOf("ENABLED=1", "PORT=37425", "SOCKETS_OK",
            "LISTEN 0 4 [::]:37425 [::]:*",
            "15: wlan0    inet 192.168.1.207/24 brd 192.168.1.255 scope global wlan0",
            "20: wlan1    inet 192.168.137.73/24 brd 192.168.137.255 scope global wlan1",
            "21: tun0    inet 10.0.0.1/24 scope global tun0"))
        assertEquals(true, info.listening)
        assertEquals(listOf("wlan0" to "192.168.1.207", "wlan1" to "192.168.137.73"), info.addresses)
    }

    @Test fun invalidPortAndIncompleteReadDoNotClaimListening() {
        assertNull(WirelessAdbInfo.parse(listOf("PORT=65536", "SOCKETS_OK")).port)
        assertNull(WirelessAdbInfo.parse(listOf("PORT=37425", "LISTEN 0 4 *:37425 *:*")).listening)
        assertTrue(WirelessAdbInfo.parse(listOf("15: wlan1 inet 999.1.2.3/24")).addresses.isEmpty())
    }
}

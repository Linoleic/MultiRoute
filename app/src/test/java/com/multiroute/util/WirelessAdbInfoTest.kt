package com.multiroute.util

import com.multiroute.adb.AdbMode
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

    @Test fun ipv6GlobalScopeAcceptedAndLinkLocalRejected() {
        val lines = listOf(
            "ENABLED=1", "PORT=37425", "SOCKETS_OK",
            "LISTEN 0 4 [::]:37425 [::]:*",
            "15: wlan0    inet 192.168.1.240/24 brd 192.168.1.255 scope global wlan0",
            "15: wlan0    inet6 fd77:2c88:73a5:0:6fc0:68b6:341e:f71/64 scope global temporary dynamic wlan0",
            "15: wlan0    inet6 fd77:2c88:73a5:0:7462:12ff:fea1:ebfc/64 scope global dynamic mngtmpaddr wlan0",
            "15: wlan0    inet6 fe80::7462:12ff:fea1:ebfc/64 scope link wlan0",
            "21: tun0     inet6 2001:db8::1/64 scope global tun0"
        )
        val info = WirelessAdbInfo.parse(lines)
        assertEquals(true, info.listening)
        // Temporary SLAAC is skipped; link-local fe80 is skipped; tun0 is skipped
        assertEquals(
            listOf(
                "wlan0" to "192.168.1.240",
                "wlan0" to "fd77:2c88:73a5:0:7462:12ff:fea1:ebfc"
            ),
            info.addresses
        )
    }

    @Test fun invalidPortAndIncompleteReadDoNotClaimListening() {
        assertNull(WirelessAdbInfo.parse(listOf("PORT=65536", "SOCKETS_OK")).port)
        assertNull(WirelessAdbInfo.parse(listOf("PORT=37425", "LISTEN 0 4 *:37425 *:*")).listening)
        assertTrue(WirelessAdbInfo.parse(listOf("15: wlan1 inet 999.1.2.3/24")).addresses.isEmpty())
    }

    @Test fun tcpModeDefaultPortDetectedAndListening() {
        val info = WirelessAdbInfo.parse(listOf(
            "ENABLED=0",
            "TCP_PORT=5555",
            "SOCKETS_OK",
            "LISTEN 0 4 *:5555 *:*"
        ))
        assertEquals(AdbMode.TCP, info.mode)
        assertEquals(5555, info.port)
        assertEquals(5555, info.tcpPort)
        assertEquals(true, info.listening)
        assertEquals(true, info.enabled)
    }

    @Test fun tcpModeCustomPortNotListening() {
        val info = WirelessAdbInfo.parse(listOf(
            "ENABLED=0",
            "TCP_PORT=8888",
            "SOCKETS_OK",
            "LISTEN 0 4 *:5555 *:*"
        ))
        assertEquals(AdbMode.TCP, info.mode)
        assertEquals(8888, info.port)
        assertEquals(false, info.listening)
        assertEquals(true, info.enabled)
    }

    @Test fun tcpModeDisabledWithMinusOneDoesNotListen() {
        val info = WirelessAdbInfo.parse(listOf(
            "ENABLED=0",
            "TCP_PORT=-1",
            "PERSIST_TCP_PORT=5555",
            "SOCKETS_OK"
        ))
        assertNull(info.tcpPort)
        assertNull(info.port)
        assertEquals(false, info.enabled)
    }

    @Test fun preferredModeDisambiguatesCoexistingListeners() {
        val lines = listOf(
            "ENABLED=1",
            "PORT=36721",
            "TCP_PORT=5555",
            "SOCKETS_OK",
            "LISTEN 0 4 *:36721 *:*",
            "LISTEN 0 4 *:5555 *:*"
        )
        val tlsInfo = WirelessAdbInfo.parse(lines, preferredMode = AdbMode.TLS)
        assertEquals(AdbMode.TLS, tlsInfo.mode)
        assertEquals(36721, tlsInfo.port)
        assertEquals(true, tlsInfo.listening)

        val tcpInfo = WirelessAdbInfo.parse(lines, preferredMode = AdbMode.TCP)
        assertEquals(AdbMode.TCP, tcpInfo.mode)
        assertEquals(5555, tcpInfo.port)
        assertEquals(true, tcpInfo.listening)
    }
}

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

    @Test fun parseEndpointHandlesDifferentAddressFormats() {
        assertEquals("192.168.1.240" to 5555, WirelessAdbInfo.parseEndpoint("192.168.1.240:5555"))
        assertEquals("192.168.1.240" to 35345, WirelessAdbInfo.parseEndpoint("[::ffff:192.168.1.240]:35345"))
        assertEquals("fd77:2c88:73a5:0:7462:12ff:fea1:ebfc" to 35345,
            WirelessAdbInfo.parseEndpoint("[fd77:2c88:73a5:0:7462:12ff:fea1:ebfc]:35345"))
        assertEquals("fe80::1" to 5555, WirelessAdbInfo.parseEndpoint("[fe80::1%wlan0]:5555"))
        assertNull(WirelessAdbInfo.parseEndpoint("invalid_string"))
    }

    @Test fun establishedClientsDetectedOnActivePort() {
        val lines = listOf(
            "ENABLED=1",
            "PORT=35345",
            "SOCKETS_OK",
            "LISTEN 0 4 [::]:35345 [::]:*",
            "ESTABLISHED_START",
            "0 92 [::ffff:192.168.1.240]:35345 [::ffff:192.168.1.158]:5501",
            "0 0 [::ffff:192.168.1.240]:22 [::ffff:192.168.1.99]:4000",
            "ESTABLISHED_END",
            "15: wlan0 inet 192.168.1.240/24 brd 192.168.1.255 scope global wlan0"
        )
        val info = WirelessAdbInfo.parse(lines)
        assertTrue(info.isClientConnected)
        assertEquals(listOf("192.168.1.158"), info.connectedClients)
        assertTrue(info.connectedLocalAddresses.contains("192.168.1.240"))
    }

    @Test fun multipleAndIPv6EstablishedClientsDetected() {
        val lines = listOf(
            "ENABLED=0",
            "TCP_PORT=5555",
            "SOCKETS_OK",
            "LISTEN 0 4 *:5555 *:*",
            "ESTABLISHED_START",
            "0 0 192.168.1.207:5555 192.168.1.158:2923",
            "0 0 [fd77:2c88:73a5:0:7462:12ff:fea1:ebfc]:5555 [fd77:2c88:73a5:0:1:2:3:4]:61234",
            "ESTABLISHED_END",
            "15: wlan0 inet 192.168.1.207/24 brd 192.168.1.255 scope global wlan0",
            "15: wlan0 inet6 fd77:2c88:73a5:0:7462:12ff:fea1:ebfc/64 scope global dynamic mngtmpaddr wlan0"
        )
        val info = WirelessAdbInfo.parse(lines)
        assertTrue(info.isClientConnected)
        assertEquals(listOf("192.168.1.158", "fd77:2c88:73a5:0:1:2:3:4"), info.connectedClients)
        assertTrue(info.connectedLocalAddresses.contains("192.168.1.207"))
        assertTrue(info.connectedLocalAddresses.contains("fd77:2c88:73a5:0:7462:12ff:fea1:ebfc"))
    }

    @Test fun noEstablishedClientsWhenPortDiffers() {
        val lines = listOf(
            "ENABLED=1",
            "PORT=35345",
            "SOCKETS_OK",
            "LISTEN 0 4 [::]:35345 [::]:*",
            "ESTABLISHED_START",
            "0 0 192.168.1.207:5555 192.168.1.158:2923",
            "ESTABLISHED_END"
        )
        val info = WirelessAdbInfo.parse(lines)
        assertFalse(info.isClientConnected)
        assertTrue(info.connectedClients.isEmpty())
        assertTrue(info.connectedLocalAddresses.isEmpty())
    }

    @Test fun tlsEnabledWithoutPortReportsListeningFalse() {
        val lines = listOf(
            "ENABLED=1",
            "TCP_PORT=-1",
            "SOCKETS_OK"
        )
        val info = WirelessAdbInfo.parse(lines, preferredMode = AdbMode.TLS)
        assertEquals(AdbMode.TLS, info.mode)
        assertEquals(true, info.enabled)
        assertEquals(false, info.listening)
        assertNull(info.port)
    }
}

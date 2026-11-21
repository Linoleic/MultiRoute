package com.multiroute.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidVpnGatewayTest {
    private val vpn = "NetworkAgentInfo{network{115} ni{VPN CONNECTED} lp{{InterfaceName: tun0}} " +
        "nc{[ Transports: WIFI|VPN Uids: <{10376-10377, 99910376}> OwnerUid: 10370 UnderlyingNetworks: [100,114]]}}"

    @Test
    fun matchesOnlyUidRangesOfTheSelectedVpnOwnerAndInterface() {
        val otherVpn = vpn.replace("tun0", "tun1").replace("10370", "10371")
        assertEquals(listOf(10376..10377, 99910376..99910376), AndroidVpnGateway.capturedUids(
            listOf(otherVpn, vpn), "tun0", 10370
        ))
        assertNull(AndroidVpnGateway.capturedUids(listOf(otherVpn, vpn), "tun0", 10371))
        assertNull(AndroidVpnGateway.capturedUids(listOf(vpn.replace("WIFI|VPN", "WIFI")), "tun0", 10370))
        assertNull(AndroidVpnGateway.capturedUids(listOf(vpn, vpn), "tun0", 10370))
    }

    @Test
    fun rejectsMissingOrInvalidCoverage() {
        assertNull(AndroidVpnGateway.capturedUids(listOf(vpn.replace("10376-10377", "10377-10376")), "tun0", 10370))
        assertNull(AndroidVpnGateway.capturedUids(listOf(vpn.replace("Uids: <{10376-10377, 99910376}>", "")), "tun0", 10370))
        assertEquals(emptyList<IntRange>(), AndroidVpnGateway.capturedUids(
            listOf(vpn.replace("10376-10377, 99910376", "")), "tun0", 10370
        ))
    }
}

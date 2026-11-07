package com.multiroute.util

import com.multiroute.adb.*
import org.junit.Assert.*
import org.junit.Test

class WirelessAdbPolicyTest {
    private val policy = AdbBootPolicy(true, "F5G")
    @Test fun disabledPolicyCannotRestoreEvenOnMatchingNetwork() {
        assertEquals(AdbActionResult.INACTIVE, WirelessAdbController.restoreGate(policy.copy(enabled = false), "F5G", true, 10, 100))
    }
    @Test fun lockedOrExpiredCannotRestore() {
        assertEquals(AdbActionResult.WAIT_UNLOCK, WirelessAdbController.restoreGate(policy, "F5G", false, 10, 100))
        assertEquals(AdbActionResult.EXPIRED, WirelessAdbController.restoreGate(policy, "F5G", true, 100, 100))
    }
    @Test fun unknownOrDifferentNetworkCannotRestore() {
        for (ssid in listOf(null, "Hpkt", "<unknown ssid>")) {
            assertEquals(AdbActionResult.WAIT_NETWORK, WirelessAdbController.restoreGate(policy, ssid, true, 10, 100))
        }
        assertEquals(AdbActionResult.WAIT_NETWORK, WirelessAdbController.restoreGate(policy.copy(ssid = null), "F5G", true, 10, 100))
        assertNull(WirelessAdbController.restoreGate(policy, "F5G", true, 10, 100))
    }
    @Test fun secondaryNetworkDoesNotSatisfyPrimarySelection() {
        val lines = listOf("==== Client role=ROLE_CLIENT_SECONDARY_LONG_LIVED ====", "Wifi is connected to \"F5G\"",
            "==== Client role=ROLE_CLIENT_PRIMARY ====", "Wifi is connected to \"Hpkt\"")
        assertEquals("Hpkt", WirelessAdbController.primarySsid(lines))
    }
    @Test fun unknownSectionLayoutFailsClosed() {
        assertNull(WirelessAdbController.primarySsid(listOf("==== Unknown role ====", "Wifi is connected to \"F5G\"")))
        assertNull(WirelessAdbController.primarySsid(listOf("Wifi is not connected")))
        assertNull(WirelessAdbController.primarySsid(listOf("Wifi is connected to \"<unknown ssid>\"")))
        assertEquals("F5G", WirelessAdbController.primarySsid(listOf("Wifi is connected to \"F5G\"")))
    }
    @Test fun policyDefaultValuesSupportDualMode() {
        val defaultPolicy = AdbBootPolicy()
        assertFalse(defaultPolicy.enabled)
        assertNull(defaultPolicy.ssid)
        assertEquals(AdbMode.TLS, defaultPolicy.mode)
        assertEquals(5555, defaultPolicy.customPort)

        val tcpPolicy = defaultPolicy.copy(mode = AdbMode.TCP, customPort = 6666)
        assertEquals(AdbMode.TCP, tcpPolicy.mode)
        assertEquals(6666, tcpPolicy.customPort)
    }
}

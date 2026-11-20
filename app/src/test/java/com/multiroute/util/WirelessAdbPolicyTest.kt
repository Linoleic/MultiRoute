package com.multiroute.util

import com.multiroute.adb.*
import org.junit.Assert.*
import org.junit.Test

class WirelessAdbPolicyTest {
    private val policy = AdbBootPolicy(
        restoreOnBoot = true,
        autoEnableOnWifi = false,
        takeOverSystemAutoStart = false,
        trustedSsids = listOf("F5G", "Home5G")
    )

    @Test fun disabledPolicyReturnsInactive() {
        val offPolicy = AdbBootPolicy(trustedSsids = listOf("F5G"))
        assertEquals(
            AdbTriggerDecision.INACTIVE,
            WirelessAdbController.decideTriggerAction(offPolicy, AdbTriggerSource.BOOT, "F5G", unlocked = true)
        )
        assertEquals(
            AdbTriggerDecision.INACTIVE,
            WirelessAdbController.decideTriggerAction(offPolicy, AdbTriggerSource.WIFI, "F5G", unlocked = true)
        )
    }

    @Test fun lockedDeviceWaitsForUnlock() {
        assertEquals(
            AdbTriggerDecision.WAIT_UNLOCK,
            WirelessAdbController.decideTriggerAction(policy, AdbTriggerSource.BOOT, "F5G", unlocked = false)
        )
    }

    @Test fun bootAndUnlockRespectTrustedSsidList() {
        for (ssid in listOf(null, "Hpkt", "<unknown ssid>")) {
            assertEquals(
                AdbTriggerDecision.WAIT_NETWORK,
                WirelessAdbController.decideTriggerAction(policy, AdbTriggerSource.BOOT, ssid, unlocked = true)
            )
        }
        assertEquals(
            AdbTriggerDecision.ENABLE_TARGET,
            WirelessAdbController.decideTriggerAction(policy, AdbTriggerSource.BOOT, "Home5G", unlocked = true)
        )
        assertEquals(
            AdbTriggerDecision.ENABLE_TARGET,
            WirelessAdbController.decideTriggerAction(policy, AdbTriggerSource.UNLOCK, "F5G", unlocked = true)
        )
    }

    @Test fun wifiTriggerAndSystemTakeoverBehaveAsConfigured() {
        val wifiTakeover = policy.copy(
            restoreOnBoot = false,
            autoEnableOnWifi = true,
            takeOverSystemAutoStart = true
        )
        assertEquals(
            AdbTriggerDecision.ENABLE_TARGET,
            WirelessAdbController.decideTriggerAction(wifiTakeover, AdbTriggerSource.WIFI, "Home5G", unlocked = true)
        )
        assertEquals(
            AdbTriggerDecision.SUPPRESS_SYSTEM_TLS,
            WirelessAdbController.decideTriggerAction(wifiTakeover, AdbTriggerSource.WIFI, "CoffeeShop", unlocked = true)
        )
        assertEquals(
            AdbTriggerDecision.ENABLE_TARGET,
            WirelessAdbController.decideTriggerAction(wifiTakeover, AdbTriggerSource.BOOT, "F5G", unlocked = true)
        )
        assertEquals(
            AdbTriggerDecision.SUPPRESS_SYSTEM_TLS,
            WirelessAdbController.decideTriggerAction(wifiTakeover, AdbTriggerSource.BOOT, "CoffeeShop", unlocked = true)
        )
        val takeoverOnly = policy.copy(
            restoreOnBoot = false,
            autoEnableOnWifi = false,
            takeOverSystemAutoStart = true
        )
        assertEquals(
            AdbTriggerDecision.SUPPRESS_SYSTEM_TLS,
            WirelessAdbController.decideTriggerAction(takeoverOnly, AdbTriggerSource.BOOT, "F5G", unlocked = true)
        )

        val bootPlusTakeover = policy.copy(
            restoreOnBoot = true,
            autoEnableOnWifi = false,
            takeOverSystemAutoStart = true
        )
        assertEquals(
            AdbTriggerDecision.INACTIVE,
            WirelessAdbController.decideTriggerAction(bootPlusTakeover, AdbTriggerSource.WIFI, "F5G", unlocked = true)
        )
        assertEquals(
            AdbTriggerDecision.SUPPRESS_SYSTEM_TLS,
            WirelessAdbController.decideTriggerAction(bootPlusTakeover, AdbTriggerSource.WIFI, "CoffeeShop", unlocked = true)
        )
    }

    @Test fun trustedWifiResumesPendingBootRecoveryWithWifiAutomationDisabled() {
        val status = WirelessAdbStatusStore.parseStatus(
            """{"bootCount":19,"trigger":"BOOT","result":"WAIT_NETWORK"}"""
        )!!
        val pending = status.recoveryForBoot(19) == AdbBootRecovery.PENDING
        assertTrue(pending)
        assertNull(status.recoveryForBoot(20))
        assertNull(status.recoveryForBoot(null))
        assertNull(status.copy(bootCount = null).recoveryForBoot(null))

        fun decide(policy: AdbBootPolicy, ssid: String, unlocked: Boolean = true, recovery: Boolean = pending) =
            WirelessAdbController.decideTriggerAction(policy, AdbTriggerSource.WIFI, ssid, unlocked, recovery)

        assertEquals(AdbTriggerDecision.ENABLE_TARGET, decide(policy, "F5G"))
        assertEquals(AdbTriggerDecision.WAIT_NETWORK, decide(policy, "CoffeeShop"))
        assertEquals(AdbTriggerDecision.WAIT_UNLOCK, decide(policy, "F5G", unlocked = false))
        assertEquals(AdbTriggerDecision.INACTIVE, decide(policy, "F5G", recovery = false))
        assertEquals(AdbTriggerDecision.INACTIVE, decide(policy.copy(restoreOnBoot = false), "F5G"))
        val takeover = policy.copy(takeOverSystemAutoStart = true)
        assertEquals(AdbTriggerDecision.SUPPRESS_SYSTEM_TLS, decide(takeover, "CoffeeShop"))
        assertEquals(AdbTriggerDecision.ENABLE_TARGET, decide(takeover, "F5G"))
    }

    @Test fun secondaryNetworkDoesNotSatisfyPrimarySelection() {
        val lines = listOf(
            "==== Client role=ROLE_CLIENT_SECONDARY_LONG_LIVED ====", "Wifi is connected to \"F5G\"",
            "==== Client role=ROLE_CLIENT_PRIMARY ====", "Wifi is connected to \"Hpkt\""
        )
        assertEquals("Hpkt", WirelessAdbController.primarySsid(lines))
    }

    @Test fun unknownSectionLayoutFailsClosed() {
        assertNull(WirelessAdbController.primarySsid(listOf("==== Unknown role ====", "Wifi is connected to \"F5G\"")))
        assertNull(WirelessAdbController.primarySsid(listOf("Wifi is not connected")))
        assertNull(WirelessAdbController.primarySsid(listOf("Wifi is connected to \"<unknown ssid>\"")))
        assertEquals("F5G", WirelessAdbController.primarySsid(listOf("Wifi is connected to \"F5G\"")))
    }

    @Test fun policyJsonMigratesLegacyFormatAndRoundTripsNewSchema() {
        val legacy = """{"enabled":true,"ssid":"F5G","result":"READY","mode":"TCP","customPort":5556}"""
        val migrated = WirelessAdbController.parsePolicyJson(legacy)
        assertTrue(migrated.restoreOnBoot)
        assertFalse(migrated.autoEnableOnWifi)
        assertFalse(migrated.takeOverSystemAutoStart)
        assertEquals(listOf("F5G"), migrated.trustedSsids)
        assertEquals(AdbMode.TCP, migrated.mode)
        assertEquals(5556, migrated.customPort)

        val modern = migrated.copy(
            autoEnableOnWifi = true,
            takeOverSystemAutoStart = true,
            trustedSsids = listOf("F5G", "StudioWifi")
        )
        val decoded = WirelessAdbController.parsePolicyJson(WirelessAdbController.encodePolicyJson(modern))
        assertEquals(modern, decoded)
    }

    @Test fun statusStoreRoundTripsJson() {
        val status = AdbBootStatus(
            bootCount = 19,
            uptimeSeconds = 27L,
            source = "WifiCallback",
            trigger = AdbTriggerSource.WIFI,
            ssid = "F5G",
            mode = AdbMode.TCP,
            port = 5555,
            result = AdbActionResult.READY,
            detail = "SWITCHED_OVERRIDE",
            timestampMillis = 123456789L,
            bootRecovery = AdbBootRecovery.COMPLETE
        )
        val parsed = WirelessAdbStatusStore.parseStatus(WirelessAdbStatusStore.encodeStatus(status))
        assertEquals(status, parsed)
    }

    @Test fun cancelledRecoveryIsNotRecreatedFromLegacyWaitingStatus() {
        val legacy = """{"bootCount":19,"trigger":"UNLOCK","result":"WAIT_NETWORK"}"""
        val status = WirelessAdbStatusStore.parseStatus(legacy)!!.copy(bootRecovery = null)
        assertEquals(status, WirelessAdbStatusStore.parseStatus(WirelessAdbStatusStore.encodeStatus(status)))
    }
}

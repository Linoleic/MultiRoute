package com.multiroute.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRuleBuilderTest {

    @Test
    fun testInterfaceNameSanitization() {
        // Valid interface names
        assertTrue(RouteRuleBuilder.isValidInterfaceName("wlan0"))
        assertTrue(RouteRuleBuilder.isValidInterfaceName("wlan1"))
        assertTrue(RouteRuleBuilder.isValidInterfaceName("rmnet_data0"))
        assertTrue(RouteRuleBuilder.isValidInterfaceName("ccmni.0"))
        assertTrue(RouteRuleBuilder.isValidInterfaceName("dummy0"))

        // Malicious or invalid injection attempts
        assertFalse(RouteRuleBuilder.isValidInterfaceName("wlan0; rm -rf /"))
        assertFalse(RouteRuleBuilder.isValidInterfaceName("wlan0 && id"))
        assertFalse(RouteRuleBuilder.isValidInterfaceName("wlan0\nid"))
        assertFalse(RouteRuleBuilder.isValidInterfaceName("wlan0`id`"))
        assertFalse(RouteRuleBuilder.isValidInterfaceName("wlan0\$(id)"))
        assertFalse(RouteRuleBuilder.isValidInterfaceName("wlan0|reboot"))
        assertFalse(RouteRuleBuilder.isValidInterfaceName("wlan0 wlan1"))
        assertFalse(RouteRuleBuilder.isValidInterfaceName(""))
        assertFalse(RouteRuleBuilder.isValidInterfaceName("very_long_interface_name_that_exceeds_15_chars"))
    }

    @Test
    fun testUidValidation() {
        // System and invalid UIDs
        assertFalse(RouteRuleBuilder.isValidUid(0)) // root
        assertFalse(RouteRuleBuilder.isValidUid(1000)) // system
        assertFalse(RouteRuleBuilder.isValidUid(-1))
        assertFalse(RouteRuleBuilder.isValidUid(9999))

        // Normal Android user apps
        assertTrue(RouteRuleBuilder.isValidUid(10000))
        assertTrue(RouteRuleBuilder.isValidUid(10123))
        assertTrue(RouteRuleBuilder.isValidUid(10540))
        assertTrue(RouteRuleBuilder.isValidUid(99910123)) // Dual app / work profile
    }

    @Test
    fun testCommandInjectionRejectionInRoutingCommands() {
        val maliciousMap = mapOf(
            "wlan0; id > /tmp/pwned; #" to listOf(10100),
            "rmnet0 && reboot" to listOf(10200)
        )

        val commands = RouteRuleBuilder.buildUidRoutingCommands(maliciousMap)
        // All malicious keys should be completely rejected
        assertTrue(commands.isEmpty())
    }

    @Test
    fun testDualStackIpv4AndIpv6CommandsGenerated() {
        val rules = mapOf("wlan1" to listOf(10150))
        val commands = RouteRuleBuilder.buildUidRoutingCommands(rules, pref = 14500)

        assertEquals(2, commands.size)
        assertEquals("ip rule add uidrange 10150-10150 lookup wlan1 pref 14500;", commands[0])
        assertEquals("ip -6 rule add uidrange 10150-10150 lookup wlan1 pref 14500;", commands[1])
    }

    @Test
    fun testLanBypassCommandsIncludedInFullScript() {
        val rules = mapOf("wlan1" to listOf(10150))
        val script = RouteRuleBuilder.buildFullSyncScript(rules, pref = 14500, lanBypassPref = 14400)

        // Verify cleanup exists
        assertTrue(script.contains("while ip rule del pref 14500 2>/dev/null; do :; done;"))
        assertTrue(script.contains("while ip -6 rule del pref 14500 2>/dev/null; do :; done;"))
        assertTrue(script.contains("while ip rule del pref 14400 2>/dev/null; do :; done;"))
        assertTrue(script.contains("while ip -6 rule del pref 14400 2>/dev/null; do :; done;"))

        // Verify LAN bypass rules exist at pref 14400
        assertTrue(script.contains("ip rule add to 192.168.0.0/16 lookup main pref 14400;"))
        assertTrue(script.contains("ip rule add to 10.0.0.0/8 lookup main pref 14400;"))
        assertTrue(script.contains("ip rule add to 172.16.0.0/12 lookup main pref 14400;"))
        assertTrue(script.contains("ip rule add to 169.254.0.0/16 lookup main pref 14400;"))
        assertTrue(script.contains("ip -6 rule add to fc00::/7 lookup local pref 14400;"))
        assertTrue(script.contains("ip -6 rule add to fe80::/10 lookup local pref 14400;"))

        // Verify UID rules exist at pref 14500
        assertTrue(script.contains("ip rule add uidrange 10150-10150 lookup wlan1 pref 14500;"))
        assertTrue(script.contains("ip -6 rule add uidrange 10150-10150 lookup wlan1 pref 14500;"))
    }

    @Test
    fun testEmptyMapOnlyGeneratesCleanup() {
        val emptyMap = emptyMap<String, List<Int>>()
        val script = RouteRuleBuilder.buildFullSyncScript(emptyMap)

        assertTrue(script.contains("while ip rule del pref 14500 2>/dev/null; do :; done;"))
        assertFalse(script.contains("ip rule add"))
        assertFalse(script.contains("ip -6 rule add"))
    }

    @Test
    fun testDeduplicationAndSorting() {
        val rules = mapOf("wlan0" to listOf(10500, 10200, 10500, 10100))
        val commands = RouteRuleBuilder.buildUidRoutingCommands(rules)

        // 3 unique UIDs * 2 (IPv4 + IPv6) = 6 commands, sorted by UID
        assertEquals(6, commands.size)
        assertEquals("ip rule add uidrange 10100-10100 lookup wlan0 pref 14500;", commands[0])
        assertEquals("ip -6 rule add uidrange 10100-10100 lookup wlan0 pref 14500;", commands[1])
        assertEquals("ip rule add uidrange 10200-10200 lookup wlan0 pref 14500;", commands[2])
        assertEquals("ip -6 rule add uidrange 10200-10200 lookup wlan0 pref 14500;", commands[3])
        assertEquals("ip rule add uidrange 10500-10500 lookup wlan0 pref 14500;", commands[4])
        assertEquals("ip -6 rule add uidrange 10500-10500 lookup wlan0 pref 14500;", commands[5])
    }
}

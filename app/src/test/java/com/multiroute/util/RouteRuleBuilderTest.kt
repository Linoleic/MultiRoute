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
    fun testPrefixSanitization() {
        // Valid IPv4 prefixes
        assertTrue(RouteRuleBuilder.isValidPrefix("192.168.1.0/24"))
        assertTrue(RouteRuleBuilder.isValidPrefix("10.0.0.0/8"))
        assertTrue(RouteRuleBuilder.isValidPrefix("172.16.0.0/12"))
        assertTrue(RouteRuleBuilder.isValidPrefix("192.168.124.0/24"))
        assertTrue(RouteRuleBuilder.isValidPrefix("192.168.137.0/24"))

        // Valid IPv6 prefixes
        assertTrue(RouteRuleBuilder.isValidPrefix("240e:3b4:9244:af51::/64"))
        assertTrue(RouteRuleBuilder.isValidPrefix("fe80::/64"))
        assertTrue(RouteRuleBuilder.isValidPrefix("fc00::/7"))

        // Malicious injection in prefix
        assertFalse(RouteRuleBuilder.isValidPrefix("192.168.1.0/24; rm -rf /"))
        assertFalse(RouteRuleBuilder.isValidPrefix("192.168.1.0/24\nid"))
        assertFalse(RouteRuleBuilder.isValidPrefix("240e::/64 && reboot"))
        assertFalse(RouteRuleBuilder.isValidPrefix("999.999.999.999/24"))
        assertFalse(RouteRuleBuilder.isValidPrefix(""))
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
        assertEquals("/system/bin/ip rule add uidrange 10150-10150 lookup wlan1 pref 14500;", commands[0])
        assertEquals("/system/bin/ip -6 rule add uidrange 10150-10150 lookup wlan1 pref 14500;", commands[1])
    }

    @Test
    fun testLanBypassWithConnectedPrefixes() {
        val rules = mapOf("wlan1" to listOf(10150))
        val prefixes = mapOf(
            "wlan0" to listOf("192.168.124.0/24", "240e:3b4:9244:af51::/64"),
            "wlan1" to listOf("192.168.137.0/24")
        )
        val script = RouteRuleBuilder.buildFullSyncScript(rules, connectedPrefixes = prefixes, pref = 14500, lanBypassPref = 14400)

        // Verify direct subnet bypass rules exist at pref 14400
        assertTrue(script.contains("/system/bin/ip rule add to 192.168.124.0/24 lookup wlan0 pref 14400;"))
        assertTrue(script.contains("/system/bin/ip -6 rule add to 240e:3b4:9244:af51::/64 lookup wlan0 pref 14400;"))
        assertTrue(script.contains("/system/bin/ip rule add to 192.168.137.0/24 lookup wlan1 pref 14400;"))

        // Verify standard fallback rules exist at pref 14400
        assertTrue(script.contains("/system/bin/ip rule add to 192.168.0.0/16 lookup main pref 14400;"))
        assertTrue(script.contains("/system/bin/ip -6 rule add to fe80::/10 lookup local pref 14400;"))

        // Verify UID rules exist at pref 14500
        assertTrue(script.contains("/system/bin/ip rule add uidrange 10150-10150 lookup wlan1 pref 14500;"))
        assertTrue(script.contains("/system/bin/ip -6 rule add uidrange 10150-10150 lookup wlan1 pref 14500;"))
    }

    @Test
    fun testEmptyMapOnlyGeneratesCleanup() {
        val emptyMap = emptyMap<String, List<Int>>()
        val script = RouteRuleBuilder.buildFullSyncScript(emptyMap)

        assertTrue(script.contains("while /system/bin/ip rule del pref 14500 2>/dev/null; do :; done;"))
        assertFalse(script.contains("/system/bin/ip rule add"))
        assertFalse(script.contains("/system/bin/ip -6 rule add"))
    }

    @Test
    fun testDeduplicationAndSorting() {
        val rules = mapOf("wlan0" to listOf(10500, 10200, 10500, 10100))
        val commands = RouteRuleBuilder.buildUidRoutingCommands(rules)

        // 3 unique UIDs * 2 (IPv4 + IPv6) = 6 commands, sorted by UID
        assertEquals(6, commands.size)
        assertEquals("/system/bin/ip rule add uidrange 10100-10100 lookup wlan0 pref 14500;", commands[0])
        assertEquals("/system/bin/ip -6 rule add uidrange 10100-10100 lookup wlan0 pref 14500;", commands[1])
        assertEquals("/system/bin/ip rule add uidrange 10200-10200 lookup wlan0 pref 14500;", commands[2])
        assertEquals("/system/bin/ip -6 rule add uidrange 10200-10200 lookup wlan0 pref 14500;", commands[3])
        assertEquals("/system/bin/ip rule add uidrange 10500-10500 lookup wlan0 pref 14500;", commands[4])
        assertEquals("/system/bin/ip -6 rule add uidrange 10500-10500 lookup wlan0 pref 14500;", commands[5])
    }

    @Test
    fun testBootRestoreScriptWaitsForRoutingTablesAndWatchesChanges() {
        val apply = "/system/bin/ip rule add uidrange 10150-10150 lookup wlan1 pref 14500; /system/bin/ip -6 rule add uidrange 10150-10150 lookup wlan1 pref 14500;"
        val script = RouteRuleBuilder.buildBootRestoreScript(apply, listOf("wlan1", "wlan0"))

        // Executable POSIX shell with a shebang
        assertTrue(script.startsWith("#!/system/bin/sh"))

        // Readiness probing must exist: service.d runs before the interfaces are connected.
        // Absolute paths are required because service.d runs under busybox, whose `ip` applet does
        // not understand `ip route show table <name>`.
        assertTrue(script.contains("is_ready()"))
        assertTrue(script.contains("/system/bin/ip route show table \"\$1\" 2>/dev/null | /system/bin/grep -qm1 '^default'"))
        assertTrue(script.contains("/system/bin/ip -6 route show table \"\$1\" 2>/dev/null | /system/bin/grep -qm1 '^default'"))

        // Interfaces are validated, deduplicated and sorted
        assertTrue(script.contains("for _f in wlan0 wlan1; do"))

        // The snapshot is embedded verbatim and indented inside the function body
        assertTrue(script.contains("  $apply"))

        // Bounded wait, then apply only when every channel is ready; logging either way.
        assertTrue(script.contains("while [ \"\$_n\" -lt 36 ]; do"))
        assertTrue(script.contains("sleep 5"))
        assertTrue(script.contains("all channels ready (mask \$_state); applying rules"))
        assertTrue(script.contains("apply finished (rc=\$?)"))
        assertTrue(script.contains("skipping apply"))
        assertTrue(script.contains(RouteRuleBuilder.BOOT_LOG_PATH))
        assertTrue(script.contains("exit 0"))

        // No long-running watch loop: the shell reads the script incrementally, so the app rewriting it
        // (which happens on every sync) used to kill the running interpreter mid-flight.
        assertFalse(script.contains("_w="))
        assertFalse(script.contains("channel readiness changed"))

        // Every placeholder must have been substituted
        assertFalse(script.contains("@@"))
    }

    @Test
    fun testBootRestoreScriptRejectsUnsafeInterfaces() {
        val script = RouteRuleBuilder.buildBootRestoreScript(
            "/system/bin/ip rule add uidrange 10150-10150 lookup wlan1 pref 14500;",
            listOf("wlan1", "wlan0; rm -rf /", "wlan0 && id", "")
        )

        assertTrue(script.contains("for _f in wlan1; do"))
        assertFalse(script.contains("rm -rf"))
        assertFalse(script.contains("&& id"))
    }

    @Test
    fun testBootRestoreScriptWithoutChannelsIsANoOp() {
        val script = RouteRuleBuilder.buildBootRestoreScript("", emptyList())

        assertTrue(script.contains("nothing to restore"))
        assertTrue(script.contains("exit 0"))
        // An empty `for` list would be a shell syntax error, so it must not be emitted at all
        assertFalse(script.contains("for _f in"))
        assertFalse(script.contains("@@"))
    }

    @Test
    fun testBootRestoreScriptKeepsShellVariablesLiteral() {
        val script = RouteRuleBuilder.buildBootRestoreScript(
            "/system/bin/ip rule add uidrange 10150-10150 lookup wlan1 pref 14500;",
            listOf("wlan1")
        )

        // Shell expansions must survive generation untouched (no Kotlin-side interpolation)
        assertTrue(script.contains("\$(/system/bin/date '+%Y-%m-%d %H:%M:%S')"))
        assertTrue(script.contains("(rc=\$?)"))
        assertTrue(script.contains("_st=\"\$_st\"\"1\""))
    }

    /**
     * Emits the generated boot-restore script into the build directory so it can be validated with a
     * real shell (`sh -n`, and an actual dry run). The generated text is non-trivial POSIX sh
     * (functions, `case`, arithmetic, nested loops) and a syntax error there would silently break
     * boot-time rule recovery on every reboot.
     */
    @Test
    fun testDumpGeneratedBootRestoreScriptForShellValidation() {
        val script = RouteRuleBuilder.buildBootRestoreScript(
            applyScript = "/system/bin/ip rule add uidrange 59999-59999 lookup wlan1 pref 14500; " +
                    "/system/bin/ip -6 rule add uidrange 59999-59999 lookup wlan1 pref 14500;",
            interfaces = listOf("wlan0", "wlan1", "rmnet_data3")
        )

        assertTrue(script.contains("for _f in rmnet_data3 wlan0 wlan1; do"))

        val out = java.io.File("build/generated-boot-restore.sh")
        out.parentFile?.mkdirs()
        out.writeText(script)
        assertTrue(out.length() > 0L)
    }

    @Test
    fun testParsePackageUidOutputKeepsCloneUidsAndRejectsJunk() {
        val lines = listOf(
            "package:com.tencent.mm uid:99910309", // XSpace (user 999) clone
            "package:com.discord uid:10531",       // primary install of a user app
            "package:android uid:1000",            // system uid -> rejected
            "package:com.android.nfc uid:1027",    // system uid -> rejected (same policy as syncAllRouteRules)
            "package:com.foo uid:",                // missing uid
            "garbage line"
        )

        val parsed = RouteRuleBuilder.parsePackageUidOutput(lines)

        assertEquals(2, parsed.size)
        assertEquals(99910309, parsed["com.tencent.mm"])
        assertEquals(10531, parsed["com.discord"])
        assertFalse(parsed.containsKey("android"))
        assertFalse(parsed.containsKey("com.android.nfc"))
        assertFalse(parsed.containsKey("com.foo"))
    }

    @Test
    fun testParsePackageUidOutputFiltersToWantedPackages() {
        val lines = listOf(
            "package:com.tencent.mm uid:99910309",
            "package:com.other.app uid:10500"
        )

        val parsed = RouteRuleBuilder.parsePackageUidOutput(lines, setOf("com.tencent.mm"))

        assertEquals(1, parsed.size)
        assertEquals(99910309, parsed["com.tencent.mm"])
        assertFalse(parsed.containsKey("com.other.app"))
    }

    @Test
    fun testSecondaryUserListingQueryAndParser() {
        val query = RouteRuleBuilder.buildSecondaryUserListingQuery()
        assertTrue(query.contains("cmd user list"))
        // Only numeric ids are iterated (word-splitting safety); user names are not collected, the UI
        // shows the numeric space id instead.
        assertTrue(query.contains("for id in \$(cmd user list"))
        assertTrue(query.contains("[ \"\$id\" = \"0\" ] && continue"))
        assertTrue(query.contains("echo \"USER \$id\""))
        assertTrue(query.contains("cmd package list packages -U --user \"\$id\""))
        assertFalse(query.contains("USERNAME"))

        val parsed = RouteRuleBuilder.parseSecondaryUserListing(
            listOf(
                "USER 999",
                "package:com.tencent.mm uid:99910309",
                "package:android uid:99901000",
                "USER 10",
                "package:com.tencent.mm uid:1010309"
            )
        )

        assertEquals(2, parsed.size)
        assertEquals(999, parsed[0].userId)
        assertEquals(99910309, parsed[0].packageUids["com.tencent.mm"])
        assertEquals(10, parsed[1].userId)
        assertEquals(1010309, parsed[1].packageUids["com.tencent.mm"])
    }

    @Test
    fun testRuleKeyRoundTrip() {
        assertEquals("com.tencent.mm", RouteRuleBuilder.ruleKey("com.tencent.mm", 0))
        assertEquals("com.tencent.mm@999", RouteRuleBuilder.ruleKey("com.tencent.mm", 999))

        assertEquals("com.tencent.mm" to 0, RouteRuleBuilder.parseRuleKey("com.tencent.mm"))
        assertEquals("com.tencent.mm" to 999, RouteRuleBuilder.parseRuleKey("com.tencent.mm@999"))
        // malformed / absent suffix falls back to a primary-user key
        assertEquals("com.tencent.mm@abc" to 0, RouteRuleBuilder.parseRuleKey("com.tencent.mm@abc"))
        assertEquals("@999" to 0, RouteRuleBuilder.parseRuleKey("@999"))
    }

    @Test
    fun testRuleCacheRoundTripAndValidation() {
        val cache = RouteRuleBuilder.buildRuleCacheText(
            mapOf(
                10530 to "wlan1",
                99910309 to "wlan1",
                10309 to "rmnet_data3",
                1030 to "wlan1",           // system uid -> dropped
                10531 to "wlan0;rm -rf /"  // malformed interface -> dropped
            )
        )

        assertEquals("10309 rmnet_data3\n10530 wlan1\n99910309 wlan1", cache)

        val parsed = RouteRuleBuilder.parseRuleCache(cache)
        assertEquals(3, parsed.size)
        assertEquals("rmnet_data3", parsed[10309])
        assertEquals("wlan1", parsed[10530])
        assertEquals("wlan1", parsed[99910309])
    }

    @Test
    fun testRuleCacheParsingIgnoresJunk() {
        val parsed = RouteRuleBuilder.parseRuleCache(
            "# published by MultiRoute\n" +
                    "10530 wlan1\n" +
                    "999 wlan1\n" +              // system uid -> dropped
                    "garbage line\n" +
                    "10531\n" +                  // no interface -> dropped
                    "10532 wlan0 extra-column\n" +
                    "10533 wlan0;rm -rf /\n"     // injection attempt -> dropped
        )

        assertEquals(2, parsed.size)
        assertEquals("wlan1", parsed[10530])
        assertEquals("wlan0", parsed[10532])
        assertFalse(parsed.containsKey(10531))
        assertFalse(parsed.containsKey(10533))
        assertTrue(RouteRuleBuilder.parseRuleCache(null).isEmpty())
        assertTrue(RouteRuleBuilder.parseRuleCache("").isEmpty())
    }
}

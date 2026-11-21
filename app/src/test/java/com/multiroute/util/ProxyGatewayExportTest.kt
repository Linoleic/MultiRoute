package com.multiroute.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProxyGatewayExportTest {
    @Test
    fun keepsFullAndroidUidsAndRoutesToEachMappedOutbound() {
        val snapshot = mapOf(10301 to "wlan0", 99910301 to "wlan1", 10302 to "wlan0", 10303 to "eth0")
        val exported = checkNotNull(ProxyGatewayExport.build(
            snapshot, snapshot, mapOf("wlan0" to "Main", "wlan1" to "Secondary", "eth0" to "Wired")
        ))
        val rules = JSONObject(exported.content).getJSONObject("route").getJSONArray("rules")
        assertEquals(4, exported.uidCount)
        assertEquals(3, exported.outboundCount)
        assertEquals(setOf("wlan0", "wlan1", "eth0"), exported.directInterfaces)
        assertEquals(
            listOf(
                ProxyGatewayExport.PlannedRule(10301, "wlan0", "Main", true),
                ProxyGatewayExport.PlannedRule(10302, "wlan0", "Main", true),
                ProxyGatewayExport.PlannedRule(99910301, "wlan1", "Secondary", true),
                ProxyGatewayExport.PlannedRule(10303, "eth0", "Wired", true)
            ),
            exported.rules
        )
        assertEquals("[10301,10302]", rules.getJSONObject(0).getJSONArray("user_id").toString())
        assertEquals("Secondary", rules.getJSONObject(1).getString("outbound"))
        assertEquals(99910301, rules.getJSONObject(1).getJSONArray("user_id").getInt(0))
        assertEquals("Wired", rules.getJSONObject(2).getString("outbound"))
    }

    @Test
    fun reportsOnlyInterfacesThatContributeToTheExport() {
        val exported = checkNotNull(ProxyGatewayExport.build(
            publishedRules = mapOf(10301 to "wlan0", 10302 to "wlan1", 10303 to "eth0", 10304 to "wlan2"),
            installedRules = mapOf(10301 to "wlan0", 10302 to "wlan1", 10303 to "wlan1", 10304 to "wlan2"),
            physicalOutbounds = mapOf("wlan0" to "Shared", "wlan1" to "Shared", "eth0" to "Wired")
        ))
        assertEquals(1, exported.outboundCount)
        assertEquals(setOf("wlan0", "wlan1"), exported.directInterfaces)
    }

    @Test
    fun doesNotExportStaleRulesUnmappedInterfacesOrSystemUids() {
        assertNull(ProxyGatewayExport.build(
            publishedRules = mapOf(10301 to "wlan0", 10302 to "wlan1", 1000 to "wlan0"),
            installedRules = mapOf(10301 to "wlan1", 10302 to "wlan1", 1000 to "wlan0", 10304 to "wlan0"),
            physicalOutbounds = mapOf("wlan0" to "Main")
        ))
    }

    @Test
    fun usesIndependentGatewayTargetsOnlyAfterThePhysicalRouteIsAbsent() {
        val fragment = checkNotNull(ProxyGatewayExport.build(
            publishedRules = mapOf(10302 to "wlan0", 10303 to "wlan0"),
            installedRules = mapOf(10302 to "wlan0", 10304 to "wlan0"),
            physicalOutbounds = mapOf("wlan0" to "Main", "wlan1" to "Secondary"),
            defaultRouteInterfaces = (10301..10304).associateWith { "wlan1" }
        ))
        val rules = JSONObject(fragment.content).getJSONObject("route").getJSONArray("rules")
        assertEquals(2, fragment.uidCount)
        assertEquals(setOf("wlan0"), fragment.directInterfaces)
        assertEquals(10302, rules.getJSONObject(0).getJSONArray("user_id").getInt(0))
        assertEquals(10301, rules.getJSONObject(1).getJSONArray("user_id").getInt(0))
        assertEquals(
            listOf(
                ProxyGatewayExport.PlannedRule(10302, "wlan0", "Main", true),
                ProxyGatewayExport.PlannedRule(10301, "wlan1", "Secondary", false)
            ),
            fragment.rules
        )
    }

    @Test
    fun recognizesAnIpv6UnreachableRuleAsAnExistingPhysicalPolicy() {
        val rules = listOf("14500: from all uidrange 10301-10301 unreachable")
        assertEquals(emptyMap<Int, String>(), RouteRuleBuilder.parseKernelUidRules(rules))
        assertEquals(mapOf(10301 to "unreachable"), RouteRuleBuilder.parseKernelUidRules(rules, includeUnreachable = true))
    }

    @Test
    fun writesMihomoUidScalarsWithYamlQuoting() {
        val tag = "副网 #\" / TLS\\线路"
        val fragment = checkNotNull(ProxyGatewayExport.build(
            emptyMap(), emptyMap(), mapOf("wlan1" to tag), ProxyGatewayFormat.MIHOMO,
            mapOf(99910301 to "wlan1", 10301 to "wlan1")
        ))
        assertEquals(2, fragment.uidCount)
        val lines = fragment.content.trimEnd().lines()
        assertEquals("rules:", lines.first())
        val expected = listOf("UID,10301,$tag", "UID,99910301,$tag")
        assertEquals(expected, lines.drop(1).map { org.json.JSONArray("[${it.removePrefix("  - ")}]").getString(0) })
    }

    @Test
    fun androidVpnSerializesOnlyEligibleAppTargetsAndListsOutboundBindings() {
        val physical = mapOf(10309 to "wlan1")
        val result = checkNotNull(ProxyGatewayExport.build(
            publishedRules = physical,
            installedRules = physical,
            physicalOutbounds = mapOf("wlan0" to "Main", "wlan1" to "Secondary"),
            format = ProxyGatewayFormat.MIHOMO_ANDROID_VPN,
            defaultRouteInterfaces = mapOf(10301 to "wlan0", 10302 to "wlan1", 10309 to "wlan1")
        ))
        assertEquals(listOf(10301, 10302), result.rules.map { it.uid })
        assertEquals(emptySet<String>(), result.directInterfaces)
        assertEquals(listOf(
            "# Requires a compatible Android VPN client.",
            "# Set interface-name on each existing outbound before adding these rules:",
            "# \"Main\" -> interface-name: android-vpn:wlan0",
            "# \"Secondary\" -> interface-name: android-vpn:wlan1",
            "rules:",
            "  - \"UID,10301,Main\"",
            "  - \"UID,10302,Secondary\""
        ), result.content.trimEnd().lines())
    }

    @Test
    fun rejectsAmbiguousMihomoTags() {
        listOf("Proxy,DIRECT", "Proxy\n  - MATCH,DIRECT", " Proxy", "Proxy\u0000").forEach { tag ->
            assertThrows(ProxyGatewayExport.InvalidMihomoOutbound::class.java) {
                ProxyGatewayExport.build(emptyMap(), emptyMap(), mapOf("wlan0" to tag), ProxyGatewayFormat.MIHOMO, mapOf(10301 to "wlan0"))
            }
        }
    }
}

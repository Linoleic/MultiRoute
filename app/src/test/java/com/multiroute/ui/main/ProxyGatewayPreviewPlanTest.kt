package com.multiroute.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyGatewayPreviewPlanTest {
    @Test
    fun classifiesInvalidUidsInPhysicalRulesAsInvalidUid() {
        val rows = planPhysicalSkippedRows(
            published = mapOf(1000 to "wlan0"),
            installed4 = mapOf(1000 to "wlan0"),
            serializedUids = emptySet(),
            existingSkipped = emptyList(),
            ruleKeysByUid = emptyMap(),
            activeIfaces = setOf("wlan0"),
            physicalOutbounds = mapOf("wlan0" to "Main"),
            routeStateCertain = true
        )
        assertEquals(
            listOf(GatewayPreviewRow(null, 1000, "wlan0", "Main", GatewayPreviewSkipReason.INVALID_UID)),
            rows
        )
    }

    @Test
    fun keepsUnmappedPhysicalRouteNoticeWhenUidAlsoHasSkippedDraft() {
        val rows = planPhysicalSkippedRows(
            published = mapOf(10301 to "wlan0"),
            installed4 = mapOf(10301 to "wlan0"),
            serializedUids = emptySet(),
            existingSkipped = listOf(
                GatewayPreviewRow("com.example.app", 10301, "wlan1", "Secondary", GatewayPreviewSkipReason.PHYSICAL_ROUTE_ACTIVE)
            ),
            ruleKeysByUid = mapOf(10301 to "com.example.app"),
            activeIfaces = setOf("wlan0", "wlan1"),
            physicalOutbounds = mapOf("wlan1" to "Secondary"),
            routeStateCertain = true
        )
        assertEquals(
            listOf(GatewayPreviewRow("com.example.app", 10301, "wlan0", null, GatewayPreviewSkipReason.OUTBOUND_UNMAPPED)),
            rows
        )
    }

    @Test
    fun avoidsDuplicateExplanationsAndSkipsExportedUids() {
        val rows = planPhysicalSkippedRows(
            published = mapOf(1000 to "wlan0", 10301 to "wlan0", 10302 to "wlan0", 10303 to "wlan1"),
            installed4 = mapOf(1000 to "wlan0", 10301 to "wlan1", 10302 to "wlan0", 10303 to "wlan1"),
            serializedUids = setOf(10303),
            existingSkipped = listOf(
                GatewayPreviewRow(null, 1000, "wlan0", null, GatewayPreviewSkipReason.INVALID_UID),
                GatewayPreviewRow("com.example.stale", 10301, "wlan0", "Main", GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN),
                GatewayPreviewRow("com.example.vpn", 10302, "wlan0", "Main", GatewayPreviewSkipReason.VPN_OWNER)
            ),
            ruleKeysByUid = mapOf(10301 to "com.example.stale", 10302 to "com.example.vpn"),
            activeIfaces = setOf("wlan0", "wlan1"),
            physicalOutbounds = mapOf("wlan1" to "Secondary"),
            routeStateCertain = true
        )
        assertEquals(emptyList<GatewayPreviewRow>(), rows)
    }

    @Test
    fun keepsPhysicalUnmappedWarningWhenDifferentDraftInterfaceIsAlsoUnmapped() {
        val rows = planPhysicalSkippedRows(
            published = mapOf(10301 to "wlan0", 10302 to "wlan0"),
            installed4 = mapOf(10301 to "wlan0", 10302 to "wlan0"),
            serializedUids = emptySet(),
            existingSkipped = listOf(
                GatewayPreviewRow("com.example.a", 10301, "wlan1", null, GatewayPreviewSkipReason.OUTBOUND_UNMAPPED),
                GatewayPreviewRow("com.example.b", 10302, "wlan0", null, GatewayPreviewSkipReason.OUTBOUND_UNMAPPED)
            ),
            ruleKeysByUid = mapOf(10301 to "com.example.a", 10302 to "com.example.b"),
            activeIfaces = setOf("wlan0", "wlan1"),
            physicalOutbounds = emptyMap(),
            routeStateCertain = true
        )
        assertEquals(
            listOf(GatewayPreviewRow("com.example.a", 10301, "wlan0", null, GatewayPreviewSkipReason.OUTBOUND_UNMAPPED)),
            rows
        )
    }
}

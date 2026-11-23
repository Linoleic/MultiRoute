package com.multiroute.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyGatewayAppFilterTest {
    @Test
    fun resetsOnlyTheProxyEgressFilterWhenItsLastRuleIsRemoved() {
        assertEquals(FILTER_PROXY_EGRESS, resolveProxyEgressFilter(FILTER_PROXY_EGRESS, setOf("com.example.app")))
        assertNull(resolveProxyEgressFilter(FILTER_PROXY_EGRESS, emptySet()))
        assertEquals("wlan0", resolveProxyEgressFilter("wlan0", emptySet()))
    }

    @Test
    fun matchesOnlyConfiguredProxyGatewayRules() {
        val configured = setOf("com.example.primary", "com.example.clone@999")

        assertTrue(matchesProxyGatewayRule("com.example.primary", configured))
        assertTrue(matchesProxyGatewayRule("com.example.clone@999", configured))
        assertFalse(matchesProxyGatewayRule("com.example.clone", configured))
        assertFalse(matchesProxyGatewayRule("com.other.app", configured))
        assertFalse(matchesProxyGatewayRule("com.example.primary", emptySet()))
    }
}

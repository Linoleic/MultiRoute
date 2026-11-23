package com.multiroute.util

import com.multiroute.model.TestServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsEgressTest {
    @Test
    fun formatEgressResultClampsNonPositiveLatencyToOneMillisecond() {
        assertEquals("1.2.3.4 · 1 ms", NetworkUtils.formatEgressResult("1.2.3.4", 0L))
        assertEquals("1.2.3.4 · 1 ms", NetworkUtils.formatEgressResult("1.2.3.4", -5L))
        assertEquals("1.2.3.4 (江苏电信) · 42 ms", NetworkUtils.formatEgressResult("1.2.3.4 (江苏电信)", 42L))
    }

    @Test
    fun splitEgressLatencyExtractsDetailAndMillis() {
        val withLocation = NetworkUtils.splitEgressLatency("1.2.3.4 (江苏电信) · 42 ms")
        assertEquals("1.2.3.4 (江苏电信)", withLocation.detail)
        assertEquals(42L, withLocation.latencyMs)

        val ipOnly = NetworkUtils.splitEgressLatency("1.2.3.4 · 19 ms")
        assertEquals("1.2.3.4", ipOnly.detail)
        assertEquals(19L, ipOnly.latencyMs)

        val noLatency = NetworkUtils.splitEgressLatency("1.2.3.4 (江苏电信)")
        assertEquals("1.2.3.4 (江苏电信)", noLatency.detail)
        assertNull(noLatency.latencyMs)
    }

    @Test
    fun splitEgressLatencyIgnoresErrorAndInfoCodes() {
        val err = NetworkUtils.splitEgressLatency(NetworkUtils.ERR_TIMEOUT)
        assertEquals(NetworkUtils.ERR_TIMEOUT, err.detail)
        assertNull(err.latencyMs)

        val info = NetworkUtils.splitEgressLatency(NetworkUtils.INFO_HOTSPOT_DOWNLINK)
        assertEquals(NetworkUtils.INFO_HOTSPOT_DOWNLINK, info.detail)
        assertNull(info.latencyMs)
    }

    @Test
    fun compactEgressWithLatencyFirstPutsLatencyAtStartWhenPresent() {
        assertEquals(
            "38 ms · 1.2.3.4 (江苏电信)",
            NetworkUtils.compactEgressWithLatencyFirst("1.2.3.4 (江苏电信) · 38 ms")
        )
        assertEquals(
            "1.2.3.4",
            NetworkUtils.compactEgressWithLatencyFirst("1.2.3.4")
        )
    }

    @Test
    fun isFailureDistinguishesErrorsFromFormattedEgressResults() {
        assertFalse(NetworkUtils.isFailure(NetworkUtils.formatEgressResult("1.2.3.4", 25L)))
        assertTrue(NetworkUtils.isFailure(NetworkUtils.ERR_TIMEOUT))
        assertTrue(NetworkUtils.isFailure(NetworkUtils.ERR_IFACE_OFFLINE))
    }

    @Test
    fun vpnChannelsSwitchDomesticPresetsToOverseasDefault() {
        val ipip = TestServerConfig(
            selectedPresetId = TestServerConfig.PRESET_IPIP,
            activeUrl = "https://myip.ipip.net"
        )
        val cip = TestServerConfig(
            selectedPresetId = TestServerConfig.PRESET_CIP,
            activeUrl = "https://cip.cc"
        )

        assertTrue(ipip.usesDomesticPreset)
        assertTrue(cip.usesDomesticPreset)
        assertEquals(TestServerConfig.VPN_DEFAULT_PRESET_ID, ipip.effectiveForChannel(isVpn = true).selectedPresetId)
        assertEquals(TestServerConfig.VPN_DEFAULT_URL, cip.effectiveForChannel(isVpn = true).activeUrl)
        assertEquals(ipip, ipip.effectiveForChannel(isVpn = false))
    }

    @Test
    fun vpnChannelsKeepOverseasAndCustomConfigsUntouched() {
        val ifconfig = TestServerConfig(
            selectedPresetId = TestServerConfig.PRESET_IFCONFIG,
            activeUrl = "https://ifconfig.me/ip"
        )
        val custom = TestServerConfig(
            selectedPresetId = TestServerConfig.PRESET_CUSTOM,
            customUrl = "https://example.com/ip",
            activeUrl = "https://example.com/ip"
        )

        assertFalse(ifconfig.usesDomesticPreset)
        assertFalse(custom.usesDomesticPreset)
        assertEquals(ifconfig, ifconfig.effectiveForChannel(isVpn = true))
        assertEquals(custom, custom.effectiveForChannel(isVpn = true))
    }

    @Test
    fun fallbackUrlsForVpnExcludeDomesticEndpointsAndTargetUrl() {
        val vpnFallbacks = NetworkUtils.fallbackUrlsForChannel("https://api.ip.sb/geoip", isVpn = true)
        assertEquals(
            listOf(
                "https://cp.cloudflare.com/cdn-cgi/trace",
                "https://icanhazip.com",
                "https://api.ipify.org"
            ),
            vpnFallbacks
        )

        val physicalFallbacks = NetworkUtils.fallbackUrlsForChannel("https://myip.ipip.net", isVpn = false)
        assertEquals(listOf("https://cip.cc", "https://icanhazip.com"), physicalFallbacks)
    }

    @Test
    fun parseServerResponseExtractsGeoInfoFromIpSbAndIpInfoJson() {
        val ipSbTokyo = """
            {"region":"Tokyo","organization":"Isif","city":"Tokyo","isp":"Isif","ip":"152.175.33.75","country":"Japan"}
        """.trimIndent()
        assertEquals(
            "152.175.33.75 (Japan · Tokyo · Isif)",
            NetworkUtils.parseServerResponse("https://api.ip.sb/geoip", ipSbTokyo)
        )

        val ipSbSingapore = """
            {"city":"Singapore","isp":"Cloudflare","ip":"1.1.1.1","country":"Singapore"}
        """.trimIndent()
        assertEquals(
            "1.1.1.1 (Singapore · Cloudflare)",
            NetworkUtils.parseServerResponse("https://api.ip.sb/geoip", ipSbSingapore)
        )

        val ipInfo = """
            {"ip":"202.192.80.99","city":"Guangzhou","country":"CN","org":"AS4538 China Education"}
        """.trimIndent()
        assertEquals(
            "202.192.80.99 (CN · Guangzhou · AS4538 China Education)",
            NetworkUtils.parseServerResponse("https://ipinfo.io/json", ipInfo)
        )

        assertEquals("1.2.3.4", NetworkUtils.parseServerResponse("https://example.com", """{"ip":"1.2.3.4"}"""))
    }

    @Test
    fun parseServerResponseExtractsIpAndCountryFromCloudflareTrace() {
        val trace = """
            fl=1174f22
            h=cp.cloudflare.com
            ip=152.175.33.75
            loc=JP
            tls=TLSv1.3
        """.trimIndent()
        assertEquals(
            "152.175.33.75 (JP)",
            NetworkUtils.parseServerResponse("https://cp.cloudflare.com/cdn-cgi/trace", trace)
        )

        val traceUnknownLoc = "ip=152.175.33.75\nloc=XX"
        assertEquals(
            "152.175.33.75",
            NetworkUtils.parseServerResponse("https://cp.cloudflare.com/cdn-cgi/trace", traceUnknownLoc)
        )
    }
}

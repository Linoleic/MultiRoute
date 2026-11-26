package com.multiroute.ui.main

import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.util.ScenarioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioOverrideTargetsTest {

    private fun wifiChannel(id: String, ssid: String) = NetworkChannel(
        id = id,
        interfaceName = id,
        transportType = "WLAN",
        shortName = "Wi-Fi $ssid",
        ssid = ssid,
        ipAddress = "192.168.1.10"
    )

    private fun vpnChannel(id: String = "tun0") = NetworkChannel(
        id = id,
        interfaceName = id,
        transportType = "vpn",
        shortName = "VPN",
        ipAddress = "10.0.0.2",
        isVpn = true
    )

    private fun hotspotChannel(id: String = "wlan2") = NetworkChannel(
        id = id,
        interfaceName = id,
        transportType = "hotspot",
        shortName = "Hotspot",
        ipAddress = "192.168.43.1",
        isHotspot = true
    )

    @Test
    fun `resolveScenarioOverrideTarget distinguishes default, online, and offline targets`() {
        val channels = listOf(wifiChannel("wlan0", "Office"), vpnChannel("tun0"), hotspotChannel("wlan2"))

        val def = resolveScenarioOverrideTarget(CHANNEL_DEFAULT, channels)
        assertTrue(def.isDefault)
        assertTrue(def.isOnline)
        assertNull(def.onlineChannel)

        val onlineSsid = resolveScenarioOverrideTarget("ssid:office", channels)
        assertFalse(onlineSsid.isDefault)
        assertTrue(onlineSsid.isOnline)
        assertEquals("wlan0", onlineSsid.onlineChannel?.id)
        assertEquals("office", onlineSsid.symbolicSsid)

        val offlineSsid = resolveScenarioOverrideTarget("ssid:Home", channels)
        assertFalse(offlineSsid.isOnline)
        assertNull(offlineSsid.onlineChannel)
        assertEquals("Home", offlineSsid.symbolicSsid)

        val onlinePlain = resolveScenarioOverrideTarget("tun0", channels)
        assertTrue(onlinePlain.isOnline)
        assertEquals("tun0", onlinePlain.onlineChannel?.id)

        val offlinePlain = resolveScenarioOverrideTarget("rmnet_data2", channels)
        assertFalse(offlinePlain.isOnline)
        assertNull(offlinePlain.onlineChannel)

        // Hotspot channels must not be treated as routable online app targets.
        val hotspot = resolveScenarioOverrideTarget("wlan2", channels)
        assertFalse(hotspot.isOnline)
        assertNull(hotspot.onlineChannel)
    }

    @Test
    fun `buildScenarioTargetOptions preserves offline targets and deduplicates online ssids`() {
        val channels = listOf(wifiChannel("wlan0", "Office"), hotspotChannel("wlan2"))
        val edited = ScenarioProfile(
            id = "p1",
            name = "Home plan",
            trigger = ScenarioTrigger.SsidMatch(listOf("Home", "Office")),
            overrides = mapOf(
                "com.example.a" to ScenarioEngine.symbolicSsid("Home"),
                "com.example.b" to "tun0",
                "com.example.c" to CHANNEL_DEFAULT
            )
        )
        val other = ScenarioProfile(
            id = "p2",
            name = "Other",
            trigger = ScenarioTrigger.Always,
            overrides = mapOf("com.example.d" to ScenarioEngine.symbolicSsid("Cafe"))
        )

        val (defaultOpt, onlineOpts, offlineOpts) = buildScenarioTargetOptions(
            profile = edited,
            allProfiles = listOf(edited, other),
            channels = channels,
            savedSsids = listOf("Office", "home", "Lab")
        )

        assertTrue(defaultOpt.isDefault)
        assertEquals(listOf("ssid:Office"), onlineOpts.map { it.storedValue })
        assertEquals(
            listOf("ssid:Home", "tun0", "ssid:Cafe", "ssid:Lab"),
            offlineOpts.map { it.storedValue }
        )
    }
}

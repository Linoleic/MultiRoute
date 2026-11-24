package com.multiroute.ui.main

import com.multiroute.model.NetworkChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotUpstreamTest {
    private val wifi0 = NetworkChannel(
        id = "wlan0",
        interfaceName = "wlan0",
        transportType = "WLAN",
        shortName = "wlan0",
        ssid = "HomeWiFi",
        ipAddress = "192.168.1.10"
    )
    private val wifi1 = NetworkChannel(
        id = "wlan1",
        interfaceName = "wlan1",
        transportType = "WLAN",
        shortName = "wlan1",
        ssid = "Office5G",
        ipAddress = "192.168.2.10"
    )
    private val eth0 = NetworkChannel(
        id = "eth0",
        interfaceName = "eth0",
        transportType = "ethernet",
        shortName = "eth0",
        ipAddress = "10.0.0.5"
    )
    private val sim1 = NetworkChannel(
        id = "sim:1",
        interfaceName = "rmnet_data3",
        transportType = "cellular",
        shortName = "rmnet_data3",
        ipAddress = "10.3.37.46"
    )
    private val sim2 = NetworkChannel(
        id = "sim:2",
        interfaceName = "rmnet_data4",
        transportType = "cellular",
        shortName = "rmnet_data4",
        ipAddress = "10.4.12.8"
    )
    private val restrictedSim = sim2.copy(id = "sim:restricted", cellularRestricted = true)
    private val vpn = NetworkChannel(
        id = "tun0",
        interfaceName = "tun0",
        transportType = "VPN",
        shortName = "tun0",
        ipAddress = "10.0.0.2",
        isVpn = true
    )
    private val hotspot = NetworkChannel(
        id = "swlan0",
        interfaceName = "swlan0",
        transportType = "WLAN",
        shortName = "swlan0",
        ipAddress = "192.168.43.1",
        isHotspot = true
    )

    @Test
    fun singleOnlineSimMergesIntoRealChannelOptionWithoutAmbiguousCellularEntry() {
        val options = buildHotspotUpstreamOptions(
            channels = listOf(wifi0, wifi1, eth0, sim1, restrictedSim, vpn, hotspot),
            currentTarget = "default"
        )
        assertEquals(
            listOf("default", "wlan0", "wlan1", "eth0", "cellular"),
            options.map { it.target }
        )
        val simOption = options.last()
        assertEquals(sim1, simOption.channel)
        assertFalse(simOption.isStandbyCellular)
    }

    @Test
    fun dualOnlineSimsExposeConcreteInterfacesWithoutGenericCellularEntry() {
        val options = buildHotspotUpstreamOptions(
            channels = listOf(wifi0, sim1, sim2),
            currentTarget = "rmnet_data4"
        )
        assertEquals(
            listOf("default", "wlan0", "rmnet_data3", "rmnet_data4"),
            options.map { it.target }
        )
        assertTrue(options.none { it.isStandbyCellular })
        assertTrue(isHotspotTargetSelected("rmnet_data4", "rmnet_data4", hasSpecificCellular = true))
        assertFalse(isHotspotTargetSelected("cellular", "rmnet_data4", hasSpecificCellular = true))
    }

    @Test
    fun standbyCellularShownOnlyWhenSupportedAndNoCellularChannelOnline() {
        val phoneOfflineSim = buildHotspotUpstreamOptions(
            channels = listOf(wifi0),
            currentTarget = "default",
            supportsCellularData = true
        )
        assertEquals(listOf("default", "wlan0", "cellular"), phoneOfflineSim.map { it.target })
        assertTrue(phoneOfflineSim.last().isStandbyCellular)

        val wifiOnlyTablet = buildHotspotUpstreamOptions(
            channels = listOf(wifi0, wifi1),
            currentTarget = "default",
            supportsCellularData = false
        )
        assertEquals(listOf("default", "wlan0", "wlan1"), wifiOnlyTablet.map { it.target })
    }

    @Test
    fun preservesSavedOfflineTargetAndMatchesNormalizedAliases() {
        val options = buildHotspotUpstreamOptions(
            channels = listOf(wifi0),
            currentTarget = "eth0",
            supportsCellularData = false
        )
        assertEquals(listOf("default", "wlan0", "eth0"), options.map { it.target })
        assertTrue(options.last().isOfflineSaved)

        assertTrue(isHotspotTargetSelected("wlan0", "primary_wifi", hasSpecificCellular = false))
        assertTrue(isHotspotTargetSelected("wlan1", "slave_wifi", hasSpecificCellular = false))
        assertTrue(isHotspotTargetSelected("cellular", "mobile", hasSpecificCellular = false))
        assertTrue(isHotspotTargetSelected("cellular", "rmnet_data3", hasSpecificCellular = false))
    }
}

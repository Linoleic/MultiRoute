package com.multiroute.ui.main

import com.multiroute.cellular.CellularSubscription
import com.multiroute.model.NetworkChannel
import com.multiroute.wifi.DualWifiCapability
import com.multiroute.wifi.DualWifiStatus
import org.junit.Assert.*
import org.junit.Test

class ActivationCandidatesTest {
    private val secondary = CellularSubscription(2, 1, "Carrier", "sim2", false, emptyMap())
    private val cellular = CellularControlUiState(isLoaded = true, subscriptions = listOf(secondary))
    private val unsupported = DualWifiStatus(capability = DualWifiCapability(detail = "checked unsupported"))

    @Test fun noSimAndUnsupportedWifiAreNotOffered() {
        assertEquals(listOf(NetworkTool.HOTSPOT), activationCandidates(
            CellularControlUiState(isLoaded = true), unsupported, emptyList()).map { it.target })
    }

    @Test fun unknownSupportIsExplicit() {
        assertEquals(ActivationState.CHECK_SUPPORT, activationCandidates(
            CellularControlUiState(), DualWifiStatus(), emptyList()).single { it.target == NetworkTool.DUAL_WIFI }.state)
    }

    @Test fun enabledAndConnectingChannelsAreNotInactive() {
        val candidates = activationCandidates(cellular.copy(mode = 2), unsupported.copy(isConnecting = true), emptyList())
        assertEquals(ActivationState.WAITING, candidates.single { it.target == NetworkTool.CELLULAR }.state)
        assertEquals(ActivationState.STARTING, candidates.single { it.target == NetworkTool.DUAL_WIFI }.state)
        assertEquals(ActivationState.STARTING, activationCandidates(cellular.copy(isBusy = true), unsupported, emptyList())
            .single { it.target == NetworkTool.CELLULAR }.state)
    }

    @Test fun existingUnvalidatedLinksAreNotOfferedAgain() {
        val cellLink = NetworkChannel("sim2", "rmnet2", "cellular", "SIM2", ipAddress = null,
            isValidated = false, subscriptionKey = "sim2")
        val wifiLink = NetworkChannel("wlan1", "wlan1", "WLAN", "Wi-Fi", ipAddress = null, isValidated = false)
        val hotspot = NetworkChannel("ap0", "ap0", "WLAN", "AP", ipAddress = null, isHotspot = true)
        assertTrue(activationCandidates(cellular, unsupported.copy(secondaryIface = "wlan1"),
            listOf(cellLink, wifiLink, hotspot)).isEmpty())
    }

    @Test fun connectedWifiAndEnabledHotspotDoNotRequireInterfaceInventory() {
        assertTrue(activationCandidates(CellularControlUiState(),
            unsupported.copy(secondaryConnected = true, isHotspotEnabled = true), emptyList()).isEmpty())
    }
}

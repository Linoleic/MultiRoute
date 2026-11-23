package com.multiroute.ui.main

import com.multiroute.model.NetworkChannel
import com.multiroute.util.DiagnosticInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalVpnBypassWarningTest {
    private val wifi = NetworkChannel(
        id = "wlan0",
        interfaceName = "wlan0",
        transportType = "WLAN",
        shortName = "wlan0",
        ipAddress = "192.168.1.10"
    )
    private val vpn = NetworkChannel(
        id = "tun0",
        interfaceName = "tun0",
        transportType = "VPN",
        shortName = "tun0",
        ipAddress = "10.0.0.2",
        isVpn = true,
        vpnOwnerUid = 10294
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
    fun returnsNullWhenModuleIsActiveAndNoLockdown() {
        val state = MainUiState(
            channelProbe = ChannelProbeUiState(channels = listOf(wifi, vpn)),
            env = SystemEnvUiState(
                diagnosticInfo = DiagnosticInfo(isModuleActive = true, isVpnLockdownEnabled = false)
            )
        )
        assertNull(state.physicalVpnBypassWarning(wifi))
    }

    @Test
    fun returnsModuleInactiveWhenVpnActiveAndHookInactive() {
        val state = MainUiState(
            channelProbe = ChannelProbeUiState(channels = listOf(wifi, vpn)),
            env = SystemEnvUiState(
                diagnosticInfo = DiagnosticInfo(isModuleActive = false, isVpnLockdownEnabled = false)
            )
        )
        assertEquals(PhysicalVpnBypassWarning.MODULE_INACTIVE, state.physicalVpnBypassWarning(wifi))
    }

    @Test
    fun lockdownTakesPrecedenceEvenWhenModuleActive() {
        val state = MainUiState(
            channelProbe = ChannelProbeUiState(channels = listOf(wifi, vpn)),
            env = SystemEnvUiState(
                diagnosticInfo = DiagnosticInfo(isModuleActive = true, isVpnLockdownEnabled = true)
            )
        )
        assertEquals(PhysicalVpnBypassWarning.VPN_LOCKDOWN, state.physicalVpnBypassWarning(wifi))
    }

    @Test
    fun returnsNullWhenNoVpnOrNonPhysicalOrUnloadedDiagnostics() {
        val noVpn = MainUiState(
            channelProbe = ChannelProbeUiState(channels = listOf(wifi)),
            env = SystemEnvUiState(diagnosticInfo = DiagnosticInfo(isModuleActive = false))
        )
        assertNull(noVpn.physicalVpnBypassWarning(wifi))

        val withVpn = MainUiState(
            channelProbe = ChannelProbeUiState(channels = listOf(wifi, vpn, hotspot)),
            env = SystemEnvUiState(diagnosticInfo = DiagnosticInfo(isModuleActive = false))
        )
        assertNull(withVpn.physicalVpnBypassWarning(vpn))
        assertNull(withVpn.physicalVpnBypassWarning(hotspot))

        val noDiag = MainUiState(
            channelProbe = ChannelProbeUiState(channels = listOf(wifi, vpn)),
            env = SystemEnvUiState(diagnosticInfo = null)
        )
        assertNull(noDiag.physicalVpnBypassWarning(wifi))
    }
}

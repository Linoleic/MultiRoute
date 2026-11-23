package com.multiroute.ui.main

import com.multiroute.model.NetworkChannel
import com.multiroute.util.CgroupHookEvidence
import com.multiroute.util.DiagnosticInfo
import com.multiroute.util.SocketEvidence
import com.multiroute.util.TcInterfaceEvidence
import com.multiroute.util.TransparentProxyDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun proxyGatewayNoticeReflectsVpnAndTransparentProxyEvidence() {
        val noProxyDiag = TransparentProxyDiagnostics(
            interfaces = listOf(
                TcInterfaceEvidence(
                    interfaceName = "wlan0",
                    checked = true,
                    unattributedBpfPresent = false,
                    singBoxLocalTcPresent = false
                )
            ),
            cgroupHooks = CgroupHookEvidence(status = CgroupHookEvidence.Status.AVAILABLE),
            establishedSockets = SocketEvidence(SocketEvidence.Status.SKIPPED_NO_KNOWN_PROCESS, 0)
        )
        assertFalse(noProxyDiag.hasActiveEvidence)

        val activeTcDiag = noProxyDiag.copy(
            interfaces = listOf(
                TcInterfaceEvidence(
                    interfaceName = "wlan0",
                    checked = true,
                    unattributedBpfPresent = false,
                    singBoxLocalTcPresent = true
                )
            )
        )
        assertTrue(activeTcDiag.hasActiveEvidence)

        val unsupportedVpnState = MainUiState(
            channelProbe = ChannelProbeUiState(channels = listOf(wifi, vpn), supportedAndroidVpn = null),
            env = SystemEnvUiState(transparentProxyDiagnostics = noProxyDiag)
        )
        assertEquals(
            ProxyGatewayAvailabilityNotice.UNSUPPORTED_ANDROID_VPN,
            unsupportedVpnState.proxyGatewayAvailabilityNotice(hasSavedMappings = false)
        )

        val noProxyState = MainUiState(
            channelProbe = ChannelProbeUiState(channels = listOf(wifi), supportedAndroidVpn = null),
            env = SystemEnvUiState(transparentProxyDiagnostics = null)
        )
        assertEquals(
            ProxyGatewayAvailabilityNotice.NO_PROXY_DETECTED,
            noProxyState.proxyGatewayAvailabilityNotice(hasSavedMappings = false)
        )

        // Suppressed when compatible Android VPN, saved mappings, or transparent proxy evidence exist
        assertNull(
            unsupportedVpnState.copy(
                channelProbe = unsupportedVpnState.channelProbe.copy(supportedAndroidVpn = vpn)
            ).proxyGatewayAvailabilityNotice(hasSavedMappings = false)
        )
        assertNull(unsupportedVpnState.proxyGatewayAvailabilityNotice(hasSavedMappings = true))
        assertNull(
            unsupportedVpnState.copy(
                env = unsupportedVpnState.env.copy(transparentProxyDiagnostics = activeTcDiag)
            ).proxyGatewayAvailabilityNotice(hasSavedMappings = false)
        )
    }
}

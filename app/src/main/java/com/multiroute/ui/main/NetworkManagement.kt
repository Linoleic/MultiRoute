package com.multiroute.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.multiroute.R
import com.multiroute.model.NetworkChannel
import com.multiroute.wifi.DualWifiManager
import com.multiroute.wifi.DualWifiStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class NetworkTool { CELLULAR, DUAL_WIFI, HOTSPOT }
internal enum class ActivationState { INACTIVE, CHECK_SUPPORT, STARTING, WAITING }
internal data class ActivationCandidate(val target: NetworkTool, val state: ActivationState)

/** Physical/network presence is separate from Internet validation. Unknown support is explicit. */
internal fun activationCandidates(
    cellular: CellularControlUiState, wifi: DualWifiStatus, channels: List<NetworkChannel>
): List<ActivationCandidate> = buildList {
    val secondary = cellular.subscriptions.singleOrNull { !it.isDefault }
    val cellularOnline = secondary != null && channels.any { channel ->
        channel.transportType == "cellular" && !channel.isHotspot &&
            (secondary.key != null && secondary.key == channel.subscriptionKey ||
                channel.interfaceName in secondary.networks.values)
    }
    if (cellular.isLoaded && secondary != null && !cellularOnline) {
        add(ActivationCandidate(NetworkTool.CELLULAR, when {
            cellular.isBusy -> ActivationState.STARTING
            cellular.mode != 0 -> ActivationState.WAITING
            else -> ActivationState.INACTIVE
        }))
    }
    val secondaryWifiOnline = wifi.secondaryConnected || channels.any {
        it.transportType == "WLAN" && !it.isHotspot && it.interfaceName == wifi.secondaryIface
    }
    if (!secondaryWifiOnline) {
        when {
            wifi.isConnecting -> add(ActivationCandidate(NetworkTool.DUAL_WIFI, ActivationState.STARTING))
            wifi.capability.hardwareSupported -> add(ActivationCandidate(NetworkTool.DUAL_WIFI, ActivationState.INACTIVE))
            wifi.capability.detail.isBlank() -> add(ActivationCandidate(NetworkTool.DUAL_WIFI, ActivationState.CHECK_SUPPORT))
        }
    }
    if (!wifi.isHotspotEnabled && channels.none { it.isHotspot }) {
        add(ActivationCandidate(NetworkTool.HOTSPOT, ActivationState.INACTIVE))
    }
}

@Composable
internal fun ObserveNetworkManagement(viewModel: MainScreenViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(context, lifecycle, viewModel) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    DualWifiManager.refreshConnectionState(context)
                    delay(1500)
                }
            }
            launch {
                while (true) {
                    viewModel.refreshCellularControlState()
                    delay(3000)
                }
            }
        }
    }
}

private fun toolTitle(tool: NetworkTool) = when (tool) {
    NetworkTool.CELLULAR -> R.string.cellular_control_title
    NetworkTool.DUAL_WIFI -> R.string.channels_secondary_wifi
    NetworkTool.HOTSPOT -> R.string.channels_hotspot
}

private fun toolIcon(tool: NetworkTool) = when (tool) {
    NetworkTool.CELLULAR -> Icons.Default.SignalCellularAlt
    NetworkTool.DUAL_WIFI -> Icons.Default.Wifi
    NetworkTool.HOTSPOT -> Icons.Default.WifiTethering
}

internal fun LazyListScope.activationCandidateItems(candidates: List<ActivationCandidate>, onOpen: (NetworkTool) -> Unit) {
    val (establishing, inactive) = candidates.partition { it.state == ActivationState.STARTING || it.state == ActivationState.WAITING }
    if (inactive.isNotEmpty()) {
        item(key = "inactive-heading") { SettingsSectionLabel(R.string.channels_pending) }
        items(inactive, key = { "pending-${it.target}" }) { candidate -> ActivationEntry(candidate, onOpen) }
    }
    if (establishing.isNotEmpty()) {
        item(key = "establishing-heading") { SettingsSectionLabel(R.string.channels_establishing) }
        items(establishing, key = { "establishing-${it.target}" }) { candidate -> ActivationEntry(candidate, onOpen) }
    }
}

@Composable
private fun ActivationEntry(candidate: ActivationCandidate, onOpen: (NetworkTool) -> Unit) {
    val summary = when (candidate.state) {
        ActivationState.CHECK_SUPPORT -> R.string.channels_check_support
        ActivationState.STARTING -> R.string.channels_starting
        ActivationState.WAITING -> R.string.channels_waiting
        ActivationState.INACTIVE -> R.string.channels_open_management
    }
    SettingsEntry(stringResource(toolTitle(candidate.target)), stringResource(summary), toolIcon(candidate.target),
        onClick = { onOpen(candidate.target) })
}

@Composable
internal fun networkToolSummary(tool: NetworkTool, viewModel: MainScreenViewModel): String {
    val cellular by viewModel.cellularControlState.collectAsState()
    val wifi by DualWifiManager.status.collectAsState()
    return when (tool) {
        NetworkTool.CELLULAR -> stringResource(when {
            !cellular.isLoaded -> R.string.tools_inventory_loading
            cellular.isBusy -> R.string.channels_starting
            cellular.subscriptions.singleOrNull { !it.isDefault } == null -> R.string.tools_no_secondary_sim
            cellular.mode == 0 -> R.string.tools_not_enabled
            else -> R.string.tools_enabled
        })
        NetworkTool.DUAL_WIFI -> if (wifi.secondaryConnected) stringResource(R.string.tools_connected_network, wifi.secondarySsid.orEmpty())
            else stringResource(if (wifi.isConnecting) R.string.channels_starting else R.string.tools_secondary_disconnected)
        NetworkTool.HOTSPOT -> stringResource(if (wifi.isHotspotEnabled) R.string.tools_enabled else R.string.tools_not_enabled)
    }
}

@Composable
internal fun HotspotManagementPanel(viewModel: MainScreenViewModel) {
    val wifi by DualWifiManager.status.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.multiroute.ui.wifi.HotspotUpstreamCard(wifi, onSetUpstream = viewModel::setHotspotUpstream)
        OutlinedButton(onClick = viewModel::openHotspotSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dual_wifi_hotspot_btn))
        }
    }
}

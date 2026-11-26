package com.multiroute.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multiroute.R
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.theme.AppMotion
import com.multiroute.theme.RoutingDimensions
import com.multiroute.util.ChannelTrafficStats
import com.multiroute.util.NetworkUtils
import com.multiroute.wifi.DualWifiManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Tab 1: 在线通道、出口测试与未就绪通道的管理入口
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelsTab(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    showChannelTests: Boolean = false,
    onShowChannelTestsChange: (Boolean) -> Unit = {},
    onOpenProxyGateway: () -> Unit
) {
    val context = LocalContext.current
    val dualWifiStatus by DualWifiManager.status.collectAsState()
    val cellularState by viewModel.cellularControlState.collectAsState()
    ObserveNetworkManagement(viewModel)
    ObserveChannelTraffic(viewModel)
    LaunchedEffect(dualWifiStatus.isHotspotEnabled, dualWifiStatus.hotspotIface) {
        val hasHotspotChannel = uiState.channels.any { it.isHotspot }
        val activeIface = uiState.channels.firstOrNull { it.isHotspot }?.interfaceName
        if (dualWifiStatus.isHotspotEnabled != hasHotspotChannel ||
            (dualWifiStatus.isHotspotEnabled && !dualWifiStatus.hotspotIface.isNullOrBlank() &&
                activeIface != dualWifiStatus.hotspotIface)
        ) {
            viewModel.refreshEnvironment()
        }
    }
    val listChannels = remember(
        uiState.channels,
        dualWifiStatus.isHotspotEnabled,
        dualWifiStatus.hotspotIface,
        dualWifiStatus.hotspotFreq
    ) {
        val info = if (dualWifiStatus.isHotspotEnabled) {
            com.multiroute.wifi.HotspotInfo(
                isEnabled = true,
                iface = dualWifiStatus.hotspotIface,
                frequencyMhz = dualWifiStatus.hotspotFreq
            )
        } else null
        NetworkUtils.ensureHotspotChannel(uiState.channels, info) {
            NetworkUtils.syntheticHotspotChannel(context, it)
        }
    }
    val candidates = activationCandidates(cellularState, dualWifiStatus, listChannels)
    val listState = rememberLazyListState()
    var inspectedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTopology by rememberSaveable { mutableStateOf(false) }
    var showTopologyDetail by rememberSaveable { mutableStateOf(false) }
    var selectedTopologyNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var vpnForPicker by remember { mutableStateOf<NetworkChannel?>(null) }
    var showProxyPicker by rememberSaveable { mutableStateOf(false) }
    var showProxySetup by rememberSaveable { mutableStateOf(false) }
    var showHotspotPicker by rememberSaveable { mutableStateOf(false) }
    val proxyController = viewModel.proxyUpstream
    val proxyState by proxyController.state.collectAsStateWithLifecycle()
    val proxyProfile = proxyState.profile
    LaunchedEffect(proxyProfile) {
        if (proxyProfile != null && proxyController.state.value.selector == null) proxyController.refresh()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dualPane = maxWidth >= RoutingDimensions.dualPaneMinWidth
        val dockTopologyDetail = maxWidth >= RoutingDimensions.dualPaneMinWidth + 360.dp
        val leftPaneWidth = RoutingDimensions.dualPaneLeftWidth(
            availableWidth = maxWidth,
            minWidth = 310.dp,
            maxWidth = 430.dp,
            fraction = 0.38f
        )
        val activeDetail = if (dualPane) {
            listChannels.firstOrNull { it.id == inspectedChannelId } ?: listChannels.firstOrNull()
        } else {
            null
        }

        val listPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(modifier = paneModifier) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = RoutingDimensions.contentPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
                ) {
                    if (proxyProfile != null) {
                        item(key = "proxy-upstream") {
                            ProxyUpstreamChannelEntry(proxyState, uiState.channels) {
                                proxyController.refresh()
                                showProxyPicker = true
                            }
                        }
                    }
                    item(key = "channels-heading") {
                        SettingsSectionLabel(stringResource(R.string.ui_active_channels, listChannels.size))
                    }

                    if (listChannels.isEmpty()) {
                        item(key = "channels-empty") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.ui_no_active_network),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    } else {
                        items(listChannels, key = { it.id }) { channel ->
                            ChannelDetailCard(
                                channel = channel,
                                testResult = uiState.testResults[channel.id],
                                trafficFlow = viewModel.channelTraffic,
                                selected = dualPane && activeDetail?.id == channel.id,
                                compact = dualPane,
                                vpnUpstreamAction = when {
                                    channel.isVpn && !dualPane -> {
                                        {
                                            VpnUpstreamShortcut(channel, uiState.channels, uiState.scenario) {
                                                vpnForPicker = channel
                                            }
                                        }
                                    }
                                    channel.isHotspot && !dualPane -> {
                                        {
                                            HotspotUpstreamShortcut(dualWifiStatus, uiState.channels) {
                                                showHotspotPicker = true
                                            }
                                        }
                                    }
                                    else -> null
                                },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = AppMotion.itemFade(),
                                    fadeOutSpec = AppMotion.itemFade(),
                                    placementSpec = AppMotion.itemPlacement()
                                ),
                                onClick = {
                                    if (dualPane) {
                                        inspectedChannelId = channel.id
                                        viewModel.refreshTransparentProxyDiagnostics()
                                    } else viewModel.selectChannelForDetail(channel)
                                }
                            )
                        }
                    }

                    activationCandidateItems(candidates) { target ->
                        if (target == NetworkTool.HOTSPOT) showHotspotPicker = true
                        else viewModel.openNetworkTool(target)
                    }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            val toggleColors = ToggleButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RoutingDimensions.contentPadding, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                ButtonGroup(
                    overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    customItem(
                        buttonGroupContent = {
                            ToggleButton(
                                checked = !showTopology,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        showTopology = false
                                        showTopologyDetail = false
                                        selectedTopologyNodeId = null
                                    }
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                colors = toggleColors,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_symbol_view_agenda),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.topology_list_view),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            }
                        },
                        menuContent = {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.topology_list_view)) },
                                onClick = {
                                    showTopology = false
                                    showTopologyDetail = false
                                    selectedTopologyNodeId = null
                                }
                            )
                        }
                    )
                    customItem(
                        buttonGroupContent = {
                            ToggleButton(
                                checked = showTopology,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        showTopology = true
                                        viewModel.refreshTransparentProxyDiagnostics()
                                    }
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                colors = toggleColors,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_symbol_account_tree),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.topology_graph_view),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            }
                        },
                        menuContent = {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.topology_graph_view)) },
                                onClick = {
                                    showTopology = true
                                    viewModel.refreshTransparentProxyDiagnostics()
                                }
                            )
                        }
                    )
                }
            }
            AnimatedContent(
                targetState = showTopology,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { AppMotion.content() },
                label = "channelViewMode"
            ) { topologyMode ->
                if (topologyMode) {
                    Column(Modifier.fillMaxSize()) {
                        val vpnUpstreams = buildMap {
                            uiState.channels.filter { it.isVpn }.forEach { vpn ->
                                key(vpn.id, vpn.vpnOwnerUid) {
                                    put(vpn.id, rememberVpnUpstream(vpn, uiState.channels, uiState.scenario))
                                }
                            }
                        }
                        val hotspot = dualWifiStatus
                        val diagnostics = uiState.env.transparentProxyDiagnostics
                        val apps = uiState.routing.baseApps
                        val channels = uiState.channels
                        val compatibleVpn = uiState.supportedAndroidVpn
                        val effectiveTargets = remember(apps, channels, uiState.scenario) {
                            resolveEffectiveAppTargets(apps, channels, uiState.scenario)
                        }
                        val upstreamSnapshots = vpnUpstreams.mapValues { (_, upstream) ->
                            VpnUpstreamSnapshot(upstream.effectiveTarget, upstream.ownerName)
                        }
                        val topology = remember(channels, apps, effectiveTargets, upstreamSnapshots, hotspot, proxyState, diagnostics, compatibleVpn) {
                            buildNetworkTopology(channels, apps, upstreamSnapshots, hotspot, proxyState, diagnostics, compatibleVpn, effectiveTargets)
                        }
                        val selectedNode = remember(topology, selectedTopologyNodeId, channels.isNotEmpty(), hotspot.isHotspotEnabled) {
                            if (channels.isEmpty() && !hotspot.isHotspotEnabled) null
                            else (topology.sources + topology.relays + topology.egresses).firstOrNull { it.id == selectedTopologyNodeId }
                        }
                        LaunchedEffect(selectedTopologyNodeId, selectedNode?.id) {
                            if (selectedNode == null) selectedTopologyNodeId = null
                        }
                        val inspected = channels.firstOrNull { it.id == inspectedChannelId && !it.isVpn && !it.isHotspot }
                            .takeIf { dualPane && showTopologyDetail }
                        fun inspectChannel(channel: NetworkChannel) {
                            if (dockTopologyDetail && !channel.isVpn && !channel.isHotspot) {
                                inspectedChannelId = channel.id
                                showTopologyDetail = true
                                viewModel.refreshTransparentProxyDiagnostics()
                            } else viewModel.selectChannelForDetail(channel)
                        }
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            NetworkTopologyView(
                                topology = topology,
                                trafficFlow = viewModel.channelTraffic,
                                testResults = uiState.testResults,
                                selectedNodeId = selectedNode?.id,
                                onClearSelection = { selectedTopologyNodeId = null },
                                modifier = Modifier.weight(1f),
                                onNodeClick = { node ->
                                    if (selectedTopologyNodeId == node.id) selectedTopologyNodeId = null
                                    else {
                                        selectedTopologyNodeId = node.id
                                        if (dockTopologyDetail && node is TopologyNode.Physical) inspectChannel(node.channel)
                                    }
                                }
                            )
                            if (inspected != null && dockTopologyDetail) {
                                VerticalDivider(Modifier.fillMaxHeight())
                                ChannelInspector(
                                    inspected, uiState, viewModel,
                                    onDismiss = { showTopologyDetail = false }, modifier = Modifier.width(340.dp).fillMaxHeight()
                                )
                            }
                        }
                        if (selectedNode != null) {
                            val shortcuts = when (selectedNode) {
                                is TopologyNode.Physical -> buildList {
                                    val channel = selectedNode.channel
                                    add(TopologyShortcut(R.string.topology_action_details) { inspectChannel(channel) })
                                    add(TopologyShortcut(R.string.topology_action_test, !uiState.isTesting && !uiState.isTestingSingleChannel) {
                                        viewModel.testSingleChannel(channel)
                                    })
                                    if (topology.sources.filterIsInstance<TopologyNode.RoutedApps>().any { it.target == channel.id }) {
                                        add(TopologyShortcut(R.string.topology_action_routed_apps) { viewModel.openTopologyApps(channel.id) })
                                    }
                                }
                                is TopologyNode.Vpn -> listOf(
                                    TopologyShortcut(R.string.topology_action_vpn_upstream, vpnUpstreams[selectedNode.channel.id]?.canEdit == true) {
                                        vpnForPicker = selectedNode.channel
                                    },
                                    TopologyShortcut(R.string.topology_action_details) { inspectChannel(selectedNode.channel) }
                                )
                                is TopologyNode.ProxyGateway -> buildList {
                                    add(TopologyShortcut(R.string.topology_action_open_gateway, onClick = onOpenProxyGateway))
                                    if (selectedNode.draftAppCount > 0) {
                                        add(TopologyShortcut(R.string.topology_action_proxy_apps) { viewModel.openTopologyApps(FILTER_PROXY_EGRESS) })
                                    }
                                }
                                is TopologyNode.Hotspot -> buildList {
                                    add(TopologyShortcut(R.string.topology_action_hotspot_upstream) { showHotspotPicker = true })
                                    uiState.channels.firstOrNull { it.isHotspot }?.let { hotspotChannel ->
                                        add(TopologyShortcut(R.string.topology_action_details) { inspectChannel(hotspotChannel) })
                                    }
                                    add(TopologyShortcut(R.string.dual_wifi_hotspot_btn) { viewModel.openHotspotSettings() })
                                }
                                is TopologyNode.DefaultApps -> listOf(TopologyShortcut(R.string.topology_action_open_apps) {
                                    viewModel.openTopologyApps(CHANNEL_DEFAULT)
                                })
                                is TopologyNode.RoutedApps -> listOf(TopologyShortcut(R.string.topology_action_open_apps) {
                                    viewModel.openTopologyApps(selectedNode.target)
                                })
                                is TopologyNode.ProxyApps -> listOf(TopologyShortcut(R.string.topology_action_open_apps) {
                                    viewModel.openTopologyApps(FILTER_PROXY_EGRESS)
                                })
                                is TopologyNode.Offline -> emptyList()
                            }
                            TopologyActionBar(selectedNode, shortcuts, wide = dualPane, onDismiss = { selectedTopologyNodeId = null })
                        }
                    }
                } else {
                    ChannelListLayout(
                        Modifier.fillMaxSize(), dualPane, leftPaneWidth, activeDetail, uiState, viewModel,
                        onSelectVpn = { vpnForPicker = it },
                        onSelectHotspot = { showHotspotPicker = true },
                        list = listPane
                    )
                }
            }
        }
    }

    if (showChannelTests && !uiState.showTestServerDialog) {
        ChannelsTestSheet(
            uiState = uiState,
            onChangeServer = viewModel::openTestServerDialog,
            onRun = viewModel::runChannelTests,
            onTestSingle = viewModel::testSingleChannel,
            onDismiss = { onShowChannelTestsChange(false) }
        )
    }

    vpnForPicker?.let { selected ->
        val vpn = uiState.channels.firstOrNull {
            it.id == selected.id && it.interfaceName == selected.interfaceName &&
                it.isVpn && it.vpnOwnerUid == selected.vpnOwnerUid
        }
        if (vpn != null) {
            val upstream = rememberVpnUpstream(vpn, uiState.channels, uiState.scenario)
            val owner = upstream.ownerTarget
            if (owner != null) {
                VpnUpstreamPicker(
                    upstream = upstream,
                    channels = uiState.channels.filter { it.isVpnUpstream() },
                    onSelectChannel = { target ->
                        vpnForPicker = null
                        viewModel.updateVpnUpstream(vpn, owner, target)
                    },
                    onDismiss = { vpnForPicker = null }
                )
            } else {
                LaunchedEffect(selected) {
                    vpnForPicker = null
                    if (showTopology) viewModel.selectChannelForDetail(vpn)
                }
            }
        } else {
            LaunchedEffect(selected) { vpnForPicker = null }
        }
    }
    if (showProxyPicker && proxyProfile != null) {
        ProxyUpstreamPicker(
            state = proxyState,
            channels = uiState.channels,
            onSelectChannel = proxyController::select,
            onConfigure = { showProxyPicker = false; showProxySetup = true },
            onDismiss = { showProxyPicker = false }
        )
    }
    if (showProxySetup) ProxyUpstreamDialog(uiState.channels, proxyController) { showProxySetup = false }
    if (showHotspotPicker) {
        HotspotUpstreamPicker(
            status = dualWifiStatus,
            channels = uiState.channels,
            supportsCellularData = uiState.supportsCellularData,
            onSelectTarget = { target ->
                showHotspotPicker = false
                viewModel.setHotspotUpstream(target)
            },
            onOpenHotspotSettings = {
                showHotspotPicker = false
                viewModel.openHotspotSettings()
            },
            onDismiss = { showHotspotPicker = false }
        )
    }
}

private data class TopologyShortcut(val label: Int, val enabled: Boolean = true, val onClick: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopologyActionBar(
    node: TopologyNode,
    shortcuts: List<TopologyShortcut>,
    wide: Boolean,
    onDismiss: () -> Unit
) {
    val title = topologyNodeTitle(node)
    val target = (node as? TopologyNode.RoutedApps)?.channel?.let { topologyChannelTitle(it) }
    val summary = if (target == null) title else stringResource(R.string.topology_action_summary, title, target)
    val actions: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            for (shortcut in shortcuts) {
                TextButton(onClick = shortcut.onClick, enabled = shortcut.enabled) {
                    Text(stringResource(shortcut.label))
                }
            }
        }
    }
    val close: @Composable () -> Unit = {
        IconButton(onClick = onDismiss) {
            Icon(painterResource(R.drawable.ic_symbol_close), stringResource(R.string.topology_action_clear_focus))
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RoutingDimensions.contentPadding, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        if (wide || shortcuts.size <= 1) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                actions()
                close()
            }
        } else {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(summary, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleSmall)
                    close()
                }
                actions()
            }
        }
    }
}

@Composable
private fun ChannelListLayout(
    modifier: Modifier,
    dualPane: Boolean,
    listWidth: Dp,
    channel: NetworkChannel?,
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    onSelectVpn: (NetworkChannel) -> Unit,
    onSelectHotspot: () -> Unit,
    list: @Composable (Modifier) -> Unit
) {
    if (dualPane) {
        Row(modifier) {
            list(Modifier.width(listWidth).fillMaxHeight())
            VerticalDivider(Modifier.fillMaxHeight())
            AnimatedContent(
                targetState = channel,
                contentKey = { it?.id },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                transitionSpec = { AppMotion.content() },
                label = "channelDetailPane"
            ) { selected ->
                if (selected == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.ui_no_active_network),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    ChannelInspector(selected, uiState, viewModel, onSelectVpn = onSelectVpn, onSelectHotspot = onSelectHotspot)
                }
            }
        }
    } else {
        Box(modifier, contentAlignment = Alignment.TopCenter) {
            list(Modifier.widthIn(max = RoutingDimensions.contentMaxWidth).fillMaxSize())
        }
    }
}

@Composable
private fun ChannelInspector(
    channel: NetworkChannel,
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    modifier: Modifier = Modifier.fillMaxSize(),
    onSelectVpn: ((NetworkChannel) -> Unit)? = null,
    onSelectHotspot: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val hotspotStatus by com.multiroute.wifi.DualWifiManager.status.collectAsState()
    val routedApps = remember(channel.id, channel.interfaceName, uiState.routing.baseApps) {
        uiState.routing.baseApps.filter { it.targetChannelId == channel.id || it.targetChannelId == channel.interfaceName }
    }
    val effectiveTestServer = uiState.testServerConfig.effectiveForChannel(channel.isVpn)
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        ChannelDetailContent(
            channel = channel,
            channels = uiState.channels,
            scenario = uiState.scenario,
            routedApps = routedApps,
            testResult = uiState.testResults[channel.id],
            isTestingSingle = uiState.isTestingSingleChannel,
            trafficFlow = viewModel.channelTraffic,
            testServerName = effectiveTestServer.displayName(LocalContext.current),
            testServerUrl = effectiveTestServer.activeUrl,
            mayBypassTransparentProxy = uiState.mayBypassTransparentProxy(channel),
            physicalVpnBypassWarning = uiState.physicalVpnBypassWarning(channel),
            hotspotStatus = hotspotStatus,
            onChangeHotspotUpstream = if (channel.isHotspot && onSelectHotspot != null) onSelectHotspot else null,
            onOpenHotspotSettings = if (channel.isHotspot) ({ viewModel.openHotspotSettings() }) else null,
            showCloseButton = onDismiss != null,
            modifier = Modifier.widthIn(max = RoutingDimensions.contentMaxWidth),
            onChangeVpnUpstream = if (channel.isVpn && onSelectVpn != null) ({ onSelectVpn(channel) }) else null,
            onTestChannel = { viewModel.testSingleChannel(channel) },
            onDismiss = onDismiss ?: {}
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProxyUpstreamChannelEntry(state: ProxyUpstreamUiState, channels: List<NetworkChannel>, onClick: () -> Unit) {
    val context = LocalContext.current
    val current = state.selector?.current
    val target = state.profile?.mappings?.entries?.firstOrNull { it.value == current }?.key
    val resolved = channels.firstOrNull { target != null && it.proxyUpstreamKey() == target }
    val summary = when {
        state.busy -> stringResource(R.string.proxy_upstream_loading)
        state.failure != null -> stringResource(R.string.proxy_upstream_unavailable_short)
        current == null -> stringResource(R.string.proxy_upstream_unknown_selection)
        resolved != null -> stringResource(R.string.proxy_upstream_selected, NetworkUtils.channelLabel(context, resolved))
        else -> stringResource(R.string.proxy_upstream_selected, current)
    }
    SettingsEntry(
        title = stringResource(R.string.proxy_upstream_title),
        summary = summary,
        icon = R.drawable.ic_symbol_dns,
        onClick = onClick
    )
}

@Composable
private fun VpnUpstreamShortcut(
    channel: NetworkChannel,
    channels: List<NetworkChannel>,
    scenario: ScenarioUiState,
    onClick: () -> Unit
) {
    val upstream = rememberVpnUpstream(channel, channels, scenario)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = upstream.canEdit, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.ui_vpn_upstream_shortcut, upstream.configuredLabel()),
                style = MaterialTheme.typography.labelMedium,
                color = if (upstream.canEdit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val note = when {
                !upstream.canEdit -> stringResource(R.string.ui_vpn_upstream_owner_unavailable)
                upstream.scenarioName != null -> stringResource(R.string.ui_vpn_upstream_scenario, upstream.scenarioName)
                upstream.needsReconnect -> stringResource(R.string.ui_vpn_upstream_reconnect_short)
                else -> null
            }
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (upstream.canEdit) {
            Icon(
                painterResource(R.drawable.ic_symbol_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun HotspotUpstreamShortcut(
    status: com.multiroute.wifi.DualWifiStatus,
    channels: List<NetworkChannel>,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.ui_vpn_upstream_shortcut, hotspotUpstreamLabel(status.hotspotUpstreamTarget, channels)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val hookError = status.isHotspotEnabled && status.hotspotHookState != com.multiroute.util.HotspotHookState.LOADED
            val target = com.multiroute.util.HotspotUpstreamPolicy.normalize(status.hotspotUpstreamTarget)
            val waitingMismatch = status.isHotspotEnabled &&
                status.hotspotHookState == com.multiroute.util.HotspotHookState.LOADED &&
                target != "default" &&
                (status.hotspotUpstreamActive == "none" || (
                    if (target == "cellular") !com.multiroute.util.HotspotUpstreamPolicy.needsCellular(status.hotspotUpstreamActive)
                    else !target.equals(status.hotspotUpstreamActive, true)
                    ))
            val note = when {
                hookError -> stringResource(when (status.hotspotHookState) {
                    com.multiroute.util.HotspotHookState.VERSION_MISMATCH -> R.string.dual_wifi_hotspot_upstream_hook_old
                    com.multiroute.util.HotspotHookState.NOT_LOADED -> R.string.dual_wifi_hotspot_upstream_hook_warning
                    else -> R.string.dual_wifi_hotspot_upstream_hook_unknown
                })
                status.isHotspotSwitching -> stringResource(R.string.dual_wifi_hotspot_upstream_switching)
                waitingMismatch -> stringResource(R.string.dual_wifi_hotspot_upstream_waiting)
                else -> null
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hookError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            painterResource(R.drawable.ic_symbol_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChannelsTestSheet(
    uiState: MainUiState,
    onChangeServer: () -> Unit,
    onRun: () -> Unit,
    onTestSingle: (NetworkChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val busy = uiState.isTesting || uiState.isTestingSingleChannel
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.channel_egress_tests), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.channel_egress_sheet_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onChangeServer)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_symbol_dns),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_test_endpoint), style = MaterialTheme.typography.labelMedium)
                    Text(
                        uiState.testServerConfig.displayName(context),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    stringResource(R.string.ui_change),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (uiState.channels.any { it.isVpn } && uiState.testServerConfig.usesDomesticPreset) {
                Text(
                    stringResource(
                        R.string.channel_egress_vpn_overseas_hint,
                        uiState.testServerConfig.effectiveForChannel(isVpn = true).displayName(context)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (uiState.channels.isEmpty()) {
                Text(
                    stringResource(R.string.ui_no_active_network),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        uiState.channels.forEachIndexed { index, channel ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                            }
                            val rawResult = uiState.testResults[channel.id]
                            val failed = NetworkUtils.isFailure(rawResult)
                            val info = rawResult?.startsWith(NetworkUtils.INFO_PREFIX) == true
                            val summary = rawResult?.let { NetworkUtils.splitEgressLatency(it) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(getTransportIcon(channel.transportType)),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = NetworkUtils.channelLabel(context, channel),
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (summary?.latencyMs != null && !uiState.isTesting) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    text = "${summary.latencyMs} ms",
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFeatureSettings = "tnum"
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    }
                                    when {
                                        uiState.isTesting -> Text(
                                            text = stringResource(R.string.channel_egress_probing),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        summary != null -> Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(
                                                    when {
                                                        failed -> R.drawable.ic_symbol_error
                                                        info -> R.drawable.ic_symbol_info
                                                        else -> R.drawable.ic_symbol_check_circle_filled
                                                    }
                                                ),
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = when {
                                                    failed -> MaterialTheme.colorScheme.error
                                                    info -> MaterialTheme.colorScheme.tertiary
                                                    else -> MaterialTheme.colorScheme.primary
                                                }
                                            )
                                            Text(
                                                text = NetworkUtils.egressResultLabel(context, summary.detail),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = when {
                                                    failed -> MaterialTheme.colorScheme.error
                                                    info -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        else -> Text(
                                            text = stringResource(R.string.channel_egress_not_tested),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                if (!channel.isHotspot) {
                                    IconButton(
                                        onClick = { onTestSingle(channel) },
                                        enabled = !busy,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_symbol_refresh),
                                            contentDescription = stringResource(
                                                R.string.channel_egress_retest_cd,
                                                channel.interfaceName
                                            ),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = onRun,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && uiState.channels.isNotEmpty(),
                shapes = ButtonDefaults.shapes()
            ) {
                if (uiState.isTesting) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_testing_channels))
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_symbol_travel_explore),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_test_channels))
                }
            }
        }
    }
}

/**
 * 单个通道卡片视图 (ChannelsTab，清晰展示 WLAN SSID 网络名称，点击可查看详情)
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelDetailCard(
    channel: NetworkChannel,
    testResult: String?,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>? = null,
    selected: Boolean = false,
    compact: Boolean = false,
    vpnUpstreamAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cardContainer by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
            channel.isDefault -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "channelCardContainer"
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = cardContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(getTransportIcon(channel.transportType)),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val context = LocalContext.current
                        val channelName = NetworkUtils.channelLabel(context, channel)
                        Text(
                            text = channelName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (channel.isVpn && channel.underlyingIface != null && vpnUpstreamAction == null) {
                                stringResource(R.string.channel_iface_type, channel.interfaceName, channelName) +
                                    " · " + stringResource(R.string.channel_vpn_underlying, channel.underlyingIface)
                            } else {
                                stringResource(R.string.channel_iface_type, channel.interfaceName, channelName) +
                                    if (channel.ssid != null) " · SSID: ${channel.ssid}" else ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (channel.isVpn) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.channel_badge_vpn),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    if (channel.isDefault) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = stringResource(R.string.vm_system_default),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else if (channel.isHotspot) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.channel_badge_hotspot),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                if (!compact) {
                    Icon(
                        painter = painterResource(R.drawable.ic_symbol_chevron_right),
                        contentDescription = stringResource(R.string.ui_tap_details),
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (channel.isHotspot) {
                        stringResource(R.string.ui_hotspot_gateway_ip, channel.ipAddress ?: stringResource(R.string.ui_no_intranet_ip))
                    } else if (channel.isVpn) {
                        stringResource(R.string.ui_vpn_tunnel_ip, channel.ipAddress ?: stringResource(R.string.ui_no_intranet_ip))
                    } else {
                        stringResource(R.string.ui_intranet_ip, channel.ipAddress ?: stringResource(R.string.ui_no_intranet_ip))
                    },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (compact) {
                    if (trafficFlow != null) {
                        ChannelTrafficRateRow(
                            interfaceName = channel.interfaceName,
                            trafficFlow = trafficFlow
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.ui_tap_details),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            vpnUpstreamAction?.invoke()

            // 实时吞吐与速率波形（单栏下展示迷你波形；双栏左侧仅留紧凑速率，完整波形在右侧详情面板展示）
            if (!compact && trafficFlow != null) {
                ChannelTrafficCardSection(
                    interfaceName = channel.interfaceName,
                    trafficFlow = trafficFlow
                )
            }

            // 公网出口测试结果
            AnimatedVisibility(
                visible = testResult != null,
                enter = AppMotion.expand(),
                exit = AppMotion.collapse()
            ) {
                testResult?.let { resultText ->
                    val isFailed = NetworkUtils.isFailure(resultText)
                    val isInfo = resultText.startsWith(NetworkUtils.INFO_PREFIX)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = when {
                            isFailed -> MaterialTheme.colorScheme.errorContainer
                            isInfo -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(when {
                                    isFailed -> R.drawable.ic_symbol_error
                                    isInfo -> R.drawable.ic_symbol_info
                                    else -> R.drawable.ic_symbol_check_circle_filled
                                }),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = when {
                                    isFailed -> MaterialTheme.colorScheme.onErrorContainer
                                    isInfo -> MaterialTheme.colorScheme.onTertiaryContainer
                                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                            Text(
                                text = NetworkUtils.egressResultLabel(LocalContext.current, resultText),
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    isFailed -> MaterialTheme.colorScheme.onErrorContainer
                                    isInfo -> MaterialTheme.colorScheme.onTertiaryContainer
                                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

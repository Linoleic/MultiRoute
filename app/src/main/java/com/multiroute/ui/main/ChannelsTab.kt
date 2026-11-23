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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onOpenProxyGateway: () -> Unit
) {
    val dualWifiStatus by DualWifiManager.status.collectAsState()
    val cellularState by viewModel.cellularControlState.collectAsState()
    ObserveNetworkManagement(viewModel)
    ObserveChannelTraffic(viewModel)
    val candidates = activationCandidates(cellularState, dualWifiStatus, uiState.channels)
    val listState = rememberLazyListState()
    var inspectedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTopology by rememberSaveable { mutableStateOf(false) }
    var showTopologyDetail by rememberSaveable { mutableStateOf(false) }
    var vpnForPicker by remember { mutableStateOf<NetworkChannel?>(null) }
    var showProxyPicker by rememberSaveable { mutableStateOf(false) }
    var showProxySetup by rememberSaveable { mutableStateOf(false) }
    val proxyController = viewModel.proxyUpstream
    val proxyState by proxyController.state.collectAsStateWithLifecycle()
    val proxyProfile = proxyState.profile
    LaunchedEffect(proxyProfile) {
        if (proxyProfile != null && proxyController.state.value.selector == null) proxyController.refresh()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dualPane = maxWidth >= RoutingDimensions.dualPaneMinWidth
        val dockTopologyDetail = maxWidth >= RoutingDimensions.dualPaneMinWidth + 360.dp
        val compactLandscape = maxHeight < 500.dp && maxWidth >= 520.dp
        val leftPaneWidth = RoutingDimensions.dualPaneLeftWidth(
            availableWidth = maxWidth,
            minWidth = 310.dp,
            maxWidth = 430.dp,
            fraction = 0.38f
        )
        val activeDetail = if (dualPane) {
            uiState.channels.firstOrNull { it.id == inspectedChannelId } ?: uiState.channels.firstOrNull()
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
                        SettingsSectionLabel(stringResource(R.string.ui_active_channels, uiState.channels.size))
                    }

                    if (uiState.channels.isEmpty()) {
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
                        items(uiState.channels, key = { it.id }) { channel ->
                            ChannelDetailCard(
                                channel = channel,
                                testResult = uiState.testResults[channel.id],
                                trafficFlow = viewModel.channelTraffic,
                                selected = dualPane && activeDetail?.id == channel.id,
                                compact = dualPane,
                                vpnUpstreamAction = if (channel.isVpn && !dualPane) {
                                    {
                                        VpnUpstreamShortcut(channel, uiState.channels, uiState.scenario) {
                                            vpnForPicker = channel
                                        }
                                    }
                                } else null,
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
                        viewModel.openNetworkTool(target)
                    }
                }

                ChannelsTestFooter(
                    uiState = uiState,
                    viewModel = viewModel,
                    compactRow = compactLandscape && !dualPane
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = RoutingDimensions.contentPadding)
            ) {
                SegmentedButton(
                    selected = !showTopology,
                    onClick = { showTopology = false; showTopologyDetail = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text(stringResource(R.string.topology_list_view)) }
                SegmentedButton(
                    selected = showTopology,
                    onClick = {
                        showTopology = true
                        viewModel.refreshTransparentProxyDiagnostics()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text(stringResource(R.string.topology_graph_view)) }
            }
            if (showTopology) {
                val vpnUpstreams = buildMap {
                    uiState.channels.filter { it.isVpn }.forEach { vpn ->
                        key(vpn.id, vpn.vpnOwnerUid) {
                            val upstream = rememberVpnUpstream(vpn, uiState.channels, uiState.scenario)
                            put(vpn.id, VpnUpstreamSnapshot(upstream.effectiveTarget, upstream.ownerName))
                        }
                    }
                }
                val hotspot = dualWifiStatus
                val diagnostics = uiState.env.transparentProxyDiagnostics
                val apps = uiState.routing.baseApps
                val channels = uiState.channels
                val compatibleVpn = uiState.supportedAndroidVpn
                val topology = remember(channels, apps, vpnUpstreams, hotspot, proxyState, diagnostics, compatibleVpn) {
                    buildNetworkTopology(channels, apps, vpnUpstreams, hotspot, proxyState, diagnostics, compatibleVpn)
                }
                val inspected = channels.firstOrNull { it.id == inspectedChannelId && !it.isVpn && !it.isHotspot }
                    .takeIf { dualPane && showTopologyDetail }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    NetworkTopologyView(
                        topology = topology,
                        wide = dualPane,
                        trafficFlow = viewModel.channelTraffic,
                        testResults = uiState.testResults,
                        modifier = Modifier.weight(1f),
                        onNodeClick = { node ->
                            when (node) {
                                is TopologyNode.Physical -> {
                                    if (dualPane) {
                                        inspectedChannelId = node.channel.id
                                        if (dockTopologyDetail) showTopologyDetail = true
                                        else viewModel.selectChannelForDetail(node.channel)
                                        viewModel.refreshTransparentProxyDiagnostics()
                                    } else viewModel.selectChannelForDetail(node.channel)
                                }
                                is TopologyNode.Vpn -> vpnForPicker = node.channel
                                is TopologyNode.Hotspot -> viewModel.openNetworkTool(NetworkTool.HOTSPOT)
                                is TopologyNode.ProxyGateway -> onOpenProxyGateway()
                                is TopologyNode.DefaultApps, is TopologyNode.RoutedApps -> {
                                    val assignment = node as? TopologyNode.RoutedApps
                                    val target = assignment?.target ?: CHANNEL_DEFAULT
                                    val alias = assignment?.channel?.interfaceName
                                    viewModel.clearSearchAndChannelFilter()
                                    viewModel.setFilterChannelId(target)
                                    if (!uiState.showSystemApps && apps.any { app ->
                                            app.isSystemApp && (app.targetChannelId == target || app.targetChannelId == alias)
                                        }
                                    ) viewModel.toggleShowSystemApps()
                                    viewModel.setSelectedTab(0)
                                }
                                is TopologyNode.Offline -> Unit
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
                ChannelsTestFooter(uiState, viewModel, compactRow = compactLandscape && !dualPane)
            } else {
                ChannelListLayout(
                    Modifier.weight(1f).fillMaxWidth(), dualPane, leftPaneWidth, activeDetail, uiState, viewModel,
                    onSelectVpn = { vpnForPicker = it },
                    list = listPane
                )
            }
        }
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
                    ChannelInspector(selected, uiState, viewModel, onSelectVpn = onSelectVpn)
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
    onDismiss: (() -> Unit)? = null
) {
    val routedApps = remember(channel.id, channel.interfaceName, uiState.routing.baseApps) {
        uiState.routing.baseApps.filter { it.targetChannelId == channel.id || it.targetChannelId == channel.interfaceName }
    }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        ChannelDetailContent(
            channel = channel,
            channels = uiState.channels,
            scenario = uiState.scenario,
            routedApps = routedApps,
            testResult = uiState.testResults[channel.id],
            isTestingSingle = uiState.isTestingSingleChannel,
            trafficFlow = viewModel.channelTraffic,
            testServerName = uiState.testServerConfig.displayName(LocalContext.current),
            testServerUrl = uiState.testServerConfig.activeUrl,
            mayBypassTransparentProxy = uiState.mayBypassTransparentProxy(channel),
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChannelsTestFooter(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    compactRow: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = if (compactRow) 16.dp else 20.dp, topEnd = if (compactRow) 16.dp else 20.dp)
    ) {
        val serverRow: @Composable (Modifier) -> Unit = { rowModifier ->
            Row(
                modifier = rowModifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { viewModel.openTestServerDialog() }
                    .padding(horizontal = 6.dp, vertical = if (compactRow) 8.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_symbol_dns),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.ui_test_node_2, uiState.testServerConfig.displayName(LocalContext.current)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ui_change),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        val testButton: @Composable (Modifier) -> Unit = { btnModifier ->
            Button(
                onClick = { viewModel.runChannelTests() },
                modifier = btnModifier.heightIn(min = 42.dp),
                enabled = !uiState.isTesting && uiState.channels.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                shapes = ButtonDefaults.shapes()
            ) {
                if (uiState.isTesting) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_testing_channels), maxLines = 1)
                } else {
                    Icon(painterResource(R.drawable.ic_symbol_speed), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_test_channels), maxLines = 1)
                }
            }
        }

        if (compactRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                serverRow(Modifier.weight(1f))
                testButton(Modifier)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                serverRow(Modifier.fillMaxWidth())
                testButton(Modifier.fillMaxWidth())
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

package com.multiroute.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multiroute.R
import com.multiroute.model.NetworkChannel
import com.multiroute.theme.RoutingDimensions
import com.multiroute.util.ChannelTrafficStats
import com.multiroute.util.NetworkUtils
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NetworkTopologyView(
    topology: NetworkTopology,
    wide: Boolean,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>,
    testResults: Map<String, String>,
    onNodeClick: (TopologyNode) -> Unit,
    selectedNodeId: String?,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!topologyHasActiveNetwork(topology)) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.ui_no_active_network), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val layers = listOf(
        R.string.topology_sources to topology.sources,
        R.string.topology_relays to topology.relays,
        R.string.topology_egresses to topology.egresses
    ).filter { it.second.isNotEmpty() }
    val sourceIds = remember(topology.sources) { topology.sources.mapTo(mutableSetOf()) { it.id } }
    val egressIds = remember(topology.egresses) { topology.egresses.mapTo(mutableSetOf()) { it.id } }
    val acrossRelays = remember(topology.edges, topology.relays, sourceIds, egressIds) {
        if (topology.relays.isEmpty()) emptySet()
        else topology.edges.filter { it.from in sourceIds && it.to in egressIds }.toSet()
    }
    val egressInterfaces = remember(topology.egresses) {
        topology.egresses.filterIsInstance<TopologyNode.Physical>().associate { it.id to it.channel.interfaceName }
    }
    val bounds = remember(wide) { mutableStateMapOf<String, Rect>() }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    val trace = remember(topology, selectedNodeId) { selectedNodeId?.let { traceConnectedGraph(topology, it) } }
    val outgoing = remember(topology.edges) { topology.edges.groupBy { it.from } }
    val incoming = remember(topology.edges) { topology.edges.groupBy { it.to } }
    val edgeAlpha = buildMap {
        for (edge in topology.edges) {
            key(edge) {
                put(edge, animateFloatAsState(
                    targetValue = if (trace == null || edge in trace.second) 1f else 0.24f,
                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    label = "topologyEdgeAlpha"
                ))
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    // Read traffic in the draw phase, so samples do not rebuild or remeasure the graph.
    val rates = trafficFlow.collectAsStateWithLifecycle()
    val clearSelection = rememberUpdatedState(onClearSelection)
    val blankTap = if (selectedNodeId == null) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = { clearSelection.value() })
    }
    val onBounds: (String, LayoutCoordinates) -> Unit = { id, coordinates -> bounds[id] = coordinates.toRectInRoot() }
    val onRemove: (String) -> Unit = { bounds.remove(it) }

    Column(modifier.fillMaxSize().then(blankTap).verticalScroll(rememberScrollState())) {
        if (topology.activeScenarioNames.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = RoutingDimensions.contentPadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (name in topology.activeScenarioNames) {
                    Surface(shape = RoundedCornerShape(50), color = colors.secondaryContainer) {
                        Text(
                            stringResource(R.string.topology_active_scenario, name),
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        TopologyLegend(Modifier.padding(horizontal = RoutingDimensions.contentPadding, vertical = 8.dp))
        if (wide) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = RoutingDimensions.contentPadding),
                horizontalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                for ((title, _) in layers) {
                    TopologyHeading(title, Modifier.weight(1f))
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = RoutingDimensions.contentPadding, vertical = 8.dp)) {
            Canvas(Modifier.matchParentSize().onGloballyPositioned { canvasOrigin = it.positionInRoot() }) {
                for ((index, edge) in topology.edges.withIndex()) {
                    val from = bounds[edge.from]?.translate(-canvasOrigin) ?: continue
                    val to = bounds[edge.to]?.translate(-canvasOrigin) ?: continue
                    val color = when (edge.style) {
                        TopologyEdgeStyle.SOLID -> colors.primary
                        TopologyEdgeStyle.DASHED -> colors.outline
                        TopologyEdgeStyle.WARNING -> colors.tertiary
                    }.copy(alpha = edgeAlpha.getValue(edge).value)
                    val fromFraction = portFraction(edge, outgoing.getValue(edge.from))
                    val toFraction = portFraction(edge, incoming.getValue(edge.to))
                    val path = Path()
                    val end: Offset
                    val lastControl: Offset
                    if (wide) {
                        val start = Offset(from.right, from.top + from.height * fromFraction)
                        end = Offset(to.left, to.top + to.height * toFraction)
                        path.moveTo(start.x, start.y)
                        if (edge in acrossRelays) {
                            val turn = 11.dp.toPx()
                            val laneY = (7 + index % 4 * 4).dp.toPx()
                            path.cubicTo(start.x + turn, start.y, start.x + turn, laneY, start.x + 2 * turn, laneY)
                            path.lineTo(end.x - 2 * turn, laneY)
                            lastControl = Offset(end.x - turn, end.y)
                            path.cubicTo(end.x - turn, laneY, lastControl.x, lastControl.y, end.x, end.y)
                        } else {
                            val bend = ((end.x - start.x) / 2f).coerceAtLeast(0f)
                            lastControl = Offset(end.x - bend, end.y)
                            path.cubicTo(start.x + bend, start.y, lastControl.x, lastControl.y, end.x, end.y)
                        }
                    } else {
                        val start = Offset(from.left + from.width * fromFraction, from.bottom)
                        end = Offset(to.left + to.width * toFraction, to.top)
                        val bend = (end.y - start.y).coerceAtLeast(0f) / 2f
                        path.moveTo(start.x, start.y)
                        if (edge in acrossRelays) {
                            val laneInset = (7 + index % 3 * 4).dp.toPx()
                            val laneX = if (start.x <= size.width / 2f) laneInset else size.width - laneInset
                            // Enter the dedicated side gutter before the next row of cards.
                            val turn = minOf(6.dp.toPx(), bend / 2f)
                            path.cubicTo(start.x, start.y + turn, laneX, start.y + turn, laneX, start.y + 2 * turn)
                            path.lineTo(laneX, end.y - 2 * turn)
                            lastControl = Offset(end.x, end.y - turn)
                            path.cubicTo(laneX, end.y - turn, lastControl.x, lastControl.y, end.x, end.y)
                        } else {
                            lastControl = Offset(end.x, end.y - bend)
                            path.cubicTo(start.x, start.y + bend, lastControl.x, lastControl.y, end.x, end.y)
                        }
                    }
                    val iface = egressInterfaces[edge.to]
                    val active = edge.style == TopologyEdgeStyle.SOLID && rates.value[iface].hasTraffic()
                    val lineWidth = when {
                        active || trace != null && edge in trace.second -> 2.5.dp
                        edge.style == TopologyEdgeStyle.WARNING -> 2.25.dp
                        else -> 1.75.dp
                    }
                    drawPath(
                        path, color,
                        style = Stroke(
                            width = lineWidth.toPx(), cap = StrokeCap.Round,
                            pathEffect = if (edge.style == TopologyEdgeStyle.DASHED) {
                                PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                            } else null
                        )
                    )
                    drawArrowHead(end, lastControl, color, if (lineWidth == 2.5.dp) 8.dp.toPx() else 7.dp.toPx())
                }
            }
            if (wide) {
                Row(
                    Modifier.fillMaxWidth().padding(top = if (acrossRelays.isEmpty()) 4.dp else 26.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(26.dp)
                ) {
                    for ((_, nodes) in layers) {
                        TopologyGrid(
                            nodes, outgoing, trafficFlow, testResults, selectedNodeId, trace?.first,
                            onNodeClick, onBounds, onRemove, Modifier.weight(1f), wide = true
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(36.dp)) {
                    val gutter = if (acrossRelays.isEmpty()) 0.dp else 22.dp
                    for ((title, nodes) in layers) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TopologyHeading(title, Modifier.padding(horizontal = gutter))
                            TopologyGrid(
                                nodes, outgoing, trafficFlow, testResults, selectedNodeId, trace?.first,
                                onNodeClick, onBounds, onRemove,
                                Modifier.fillMaxWidth().padding(horizontal = gutter), wide = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyHeading(title: Int, modifier: Modifier = Modifier) {
    Text(stringResource(title), modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopologyGrid(
    nodes: List<TopologyNode>,
    outgoing: Map<String, List<TopologyEdge>>,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>,
    testResults: Map<String, String>,
    selectedNodeId: String?,
    focusedIds: Set<String>?,
    onNodeClick: (TopologyNode) -> Unit,
    onBounds: (String, LayoutCoordinates) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier,
    wide: Boolean
) {
    val card: @Composable (TopologyNode, Modifier) -> Unit = { node, cardModifier ->
        key(node.id) {
            DisposableEffect(node.id) { onDispose { onRemove(node.id) } }
            val alpha by animateFloatAsState(
                targetValue = if (focusedIds == null || node.id in focusedIds) 1f else 0.24f,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "topologyNodeAlpha"
            )
            TopologyNodeCard(
                node = node,
                reasons = outgoing[node.id].orEmpty().map { it.reason },
                trafficFlow = trafficFlow,
                testResult = (node as? TopologyNode.Physical)?.channel?.let { testResults[it.id] },
                selected = node.id == selectedNodeId,
                onClick = { onNodeClick(node) },
                modifier = cardModifier.alpha(alpha).onGloballyPositioned { onBounds(node.id, it) }
            )
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (wide) {
            for (node in nodes) card(node, Modifier.fillMaxWidth())
        } else if (nodes.size == 1) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                card(nodes.single(), Modifier.widthIn(max = 280.dp).fillMaxWidth())
            }
        } else {
            for (row in nodes.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (node in row) card(node, Modifier.weight(1f))
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun ChannelTrafficStats?.hasTraffic(): Boolean =
    this != null && hasSpeedSample && (rxBytesPerSec > 0L || txBytesPerSec > 0L)

private fun portFraction(edge: TopologyEdge, neighbors: List<TopologyEdge>): Float =
    0.2f + 0.6f * (neighbors.indexOf(edge) + 0.5f) / neighbors.size

@Composable
private fun TopologyNodeCard(
    node: TopologyNode,
    reasons: List<TopologyEdgeReason>,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>,
    testResult: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val offline = node is TopologyNode.Offline
    val iface = (node as? TopologyNode.Physical)?.channel?.interfaceName
    val active = if (iface == null) false else {
        val activeFlow = remember(iface, trafficFlow) {
            trafficFlow.map { it[iface].hasTraffic() }.distinctUntilChanged()
        }
        activeFlow.collectAsStateWithLifecycle(initialValue = trafficFlow.value[iface].hasTraffic()).value
    }
    val warning = TopologyEdgeReason.VPN_RECHECK in reasons || TopologyEdgeReason.POSSIBLE_BYPASS in reasons
    val container = when {
        offline -> MaterialTheme.colorScheme.surfaceContainerLow
        warning -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.52f)
        node is TopologyNode.Physical && node.channel.isDefault -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val border = when {
        selected -> MaterialTheme.colorScheme.primary
        active -> MaterialTheme.colorScheme.secondary
        offline -> MaterialTheme.colorScheme.outlineVariant
        warning -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        node is TopologyNode.Physical && node.channel.isDefault -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    }
    Surface(
        modifier = modifier
            .alpha(if (offline) 0.68f else 1f)
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .then(if (offline) Modifier.drawWithContent {
                drawContent()
                drawRoundRect(
                    color = border, cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
                )
            } else Modifier),
        shape = MaterialTheme.shapes.large,
        color = container,
        border = if (offline) null else androidx.compose.foundation.BorderStroke(if (selected || active) 2.dp else 1.dp, border),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(nodeIcon(node)),
                contentDescription = null,
                tint = when {
                    offline -> MaterialTheme.colorScheme.onSurfaceVariant
                    warning -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = topologyNodeTitle(node),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                nodeSubtitle(node)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (warning) {
                    val label = if (node is TopologyNode.Vpn) R.string.topology_vpn_recheck else R.string.topology_legend_warning
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (node is TopologyNode.Vpn && TopologyEdgeReason.VPN_CONFIGURED in reasons) {
                    Text(stringResource(R.string.topology_vpn_pending), style = MaterialTheme.typography.bodySmall)
                }
                if (node is TopologyNode.ProxyApps && node.unmappedCount > 0) {
                    Text(stringResource(R.string.topology_proxy_unmapped_count, node.unmappedCount), style = MaterialTheme.typography.labelSmall)
                }
                if (node is TopologyNode.Physical) {
                    PhysicalMetrics(node.channel, trafficFlow, testResult)
                }
            }
        }
    }
}

@Composable
private fun PhysicalMetrics(channel: NetworkChannel, trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>, testResult: String?) {
    val address = channel.ipAddress
    if (address != null) {
        Text(
            text = address,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    ChannelTrafficRateRow(channel.interfaceName, trafficFlow)
    if (testResult != null) {
        Text(
            text = stringResource(R.string.topology_test_result, testResult),
            style = MaterialTheme.typography.labelSmall,
            color = if (NetworkUtils.isFailure(testResult)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun topologyNodeTitle(node: TopologyNode): String = when (node) {
    is TopologyNode.DefaultApps -> pluralStringResource(R.plurals.topology_default_apps_count, node.appCount, node.appCount)
    is TopologyNode.RoutedApps -> pluralStringResource(R.plurals.topology_routed_apps_count, node.appCount, node.appCount)
    is TopologyNode.Hotspot -> stringResource(R.string.topology_hotspot_downlink)
    is TopologyNode.Vpn -> stringResource(R.string.topology_vpn)
    is TopologyNode.ProxyGateway -> stringResource(R.string.topology_proxy_gateway)
    is TopologyNode.ProxyApps -> pluralStringResource(R.plurals.topology_proxy_apps_count, node.appCount, node.appCount)
    is TopologyNode.Physical -> NetworkUtils.channelLabel(LocalContext.current, node.channel)
    is TopologyNode.Offline -> NetworkUtils.savedTargetLabel(LocalContext.current, node.target)
}

@Composable
private fun nodeSubtitle(node: TopologyNode): String? = when (node) {
    is TopologyNode.DefaultApps -> if (node.vpnCoverageUnknown) stringResource(R.string.topology_vpn_coverage_unknown) else null
    is TopologyNode.RoutedApps -> node.channel?.let {
        NetworkUtils.channelLabel(LocalContext.current, it)
    } ?: stringResource(R.string.ui_channel_offline_short, NetworkUtils.savedTargetLabel(LocalContext.current, node.target))
    is TopologyNode.Hotspot -> node.iface ?: stringResource(R.string.topology_hotspot_channel_unavailable)
    is TopologyNode.Vpn -> buildString {
        append(node.channel.interfaceName)
        node.ownerName?.let { append(" · ").append(it) }
        node.channel.underlyingIface?.let { append("\n").append(stringResource(R.string.topology_vpn_reported, it)) }
        if (node.supportsGateway) append("\n").append(stringResource(R.string.topology_vpn_gateway_supported))
    }
    is TopologyNode.ProxyGateway -> if (node.detected) stringResource(R.string.topology_proxy_detected) else null
    is TopologyNode.ProxyApps -> stringResource(R.string.topology_proxy_draft_pending)
    is TopologyNode.Physical -> buildString {
        append(node.channel.interfaceName)
        if (node.channel.isDefault) append(" · ").append(stringResource(R.string.topology_default_egress))
    }
    is TopologyNode.Offline -> stringResource(R.string.topology_offline)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopologyLegend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        LegendEntry(MaterialTheme.colorScheme.primary, R.string.topology_legend_solid, dashed = false)
        LegendEntry(MaterialTheme.colorScheme.outline, R.string.topology_legend_dashed, dashed = true)
        LegendEntry(MaterialTheme.colorScheme.tertiary, R.string.topology_legend_warning, dashed = false)
    }
}

@Composable
private fun LegendEntry(color: androidx.compose.ui.graphics.Color, label: Int, dashed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(18.dp).height(3.dp)) {
            drawLine(
                color,
                Offset(0f, center.y),
                Offset(size.width, center.y),
                strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
            )
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

private fun nodeIcon(node: TopologyNode): Int = when (node) {
    is TopologyNode.DefaultApps, is TopologyNode.RoutedApps, is TopologyNode.ProxyApps -> R.drawable.ic_symbol_route
    is TopologyNode.Hotspot -> R.drawable.ic_symbol_wifi_tethering
    is TopologyNode.Vpn -> R.drawable.ic_symbol_vpn_key
    is TopologyNode.ProxyGateway -> R.drawable.ic_symbol_dns
    is TopologyNode.Physical -> transportIcon(node.channel)
    is TopologyNode.Offline -> R.drawable.ic_symbol_cloud_off
}

private fun transportIcon(channel: NetworkChannel): Int = when (channel.transportType.lowercase()) {
    "wlan" -> R.drawable.ic_symbol_wifi
    "cellular" -> R.drawable.ic_symbol_signal_cellular_alt
    "ethernet" -> R.drawable.ic_symbol_lan
    else -> R.drawable.ic_symbol_language
}

private fun topologyHasActiveNetwork(topology: NetworkTopology): Boolean =
    topology.egresses.any { it is TopologyNode.Physical } ||
        topology.relays.any { it is TopologyNode.Vpn } ||
        topology.sources.any { it is TopologyNode.Hotspot }

private fun LayoutCoordinates.toRectInRoot(): Rect {
    val position = positionInRoot()
    return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowHead(
    start: Offset,
    control: Offset,
    color: androidx.compose.ui.graphics.Color,
    size: Float
) {
    val angle = atan2(start.y - control.y, start.x - control.x)
    val wing = size * 0.72f
    val first = Offset(
        start.x - size * cos(angle) + wing * sin(angle),
        start.y - size * sin(angle) - wing * cos(angle)
    )
    val second = Offset(
        start.x - size * cos(angle) - wing * sin(angle),
        start.y - size * sin(angle) + wing * cos(angle)
    )
    drawLine(color, start, first, strokeWidth = size * 0.28f, cap = StrokeCap.Round)
    drawLine(color, start, second, strokeWidth = size * 0.28f, cap = StrokeCap.Round)
}

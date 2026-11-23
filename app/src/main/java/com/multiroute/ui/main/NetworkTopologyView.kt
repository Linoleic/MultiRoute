package com.multiroute.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multiroute.R
import com.multiroute.model.NetworkChannel
import com.multiroute.theme.RoutingDimensions
import com.multiroute.util.ChannelTrafficStats
import com.multiroute.util.NetworkUtils
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun NetworkTopologyView(
    topology: NetworkTopology,
    wide: Boolean,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>,
    testResults: Map<String, String>,
    onNodeClick: (TopologyNode) -> Unit,
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
    )
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    val sourceIds = topology.sources.map { it.id }.toSet()
    val relayIds = topology.relays.map { it.id }.toSet()
    val egressIds = topology.egresses.map { it.id }.toSet()
    val outgoing = remember(topology.edges) { topology.edges.groupBy { it.from } }
    val incoming = remember(topology.edges) { topology.edges.groupBy { it.to } }
    val colors = MaterialTheme.colorScheme
    val layer: @Composable (Int, List<TopologyNode>, Modifier) -> Unit = { title, nodes, layerModifier ->
        TopologyLayer(title, nodes, outgoing, trafficFlow, testResults, wide, onNodeClick,
            onBounds = { id, coordinates -> bounds[id] = coordinates.toRectInRoot() },
            onRemove = { bounds.remove(it) }, modifier = layerModifier
        )
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopologyLegend(Modifier.padding(horizontal = RoutingDimensions.contentPadding, vertical = 8.dp))
        if (topology.sources.any { it is TopologyNode.DefaultApps || it is TopologyNode.RoutedApps }) {
            Text(
                stringResource(R.string.topology_rules_scope),
                modifier = Modifier.padding(horizontal = RoutingDimensions.contentPadding),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.matchParentSize().onGloballyPositioned { canvasOrigin = it.positionInRoot() }) {
                val topLane = (bounds.values.minOfOrNull { it.top } ?: return@Canvas) - canvasOrigin.y - 10.dp.toPx()
                for ((index, edge) in topology.edges.withIndex()) {
                    val from = bounds[edge.from]?.translate(-canvasOrigin) ?: continue
                    val to = bounds[edge.to]?.translate(-canvasOrigin) ?: continue
                    val color = when (edge.style) {
                        TopologyEdgeStyle.SOLID -> colors.primary
                        TopologyEdgeStyle.DASHED -> colors.outline
                        TopologyEdgeStyle.WARNING -> colors.tertiary
                    }
                    val fromFraction = portFraction(edge, outgoing.getValue(edge.from))
                    val toFraction = portFraction(edge, incoming.getValue(edge.to))
                    val fromY = from.top + from.height * fromFraction
                    val toY = to.top + to.height * toFraction
                    val path = Path()
                    val start: Offset
                    val end: Offset
                    val lastControl: Offset

                    if (wide) {
                        start = Offset(from.right, fromY)
                        end = Offset(to.left, toY)
                        val direct = relayIds.isNotEmpty() && edge.from in sourceIds && edge.to in egressIds
                        val gap = (end.x - start.x) / 2f
                        path.moveTo(start.x, start.y)
                        if (direct) {
                            // Keep direct paths out of the relay cards and use the space below the headings.
                            val turn = 11.dp.toPx()
                            val laneY = topLane - (index % 3 * 3).dp.toPx()
                            path.cubicTo(start.x + turn, start.y, start.x + turn, laneY, start.x + 2 * turn, laneY)
                            path.lineTo(end.x - 2 * turn, laneY)
                            lastControl = Offset(end.x - turn, end.y)
                            path.cubicTo(end.x - turn, laneY, lastControl.x, lastControl.y, end.x, end.y)
                        } else {
                            lastControl = Offset(end.x - gap, end.y)
                            path.cubicTo(start.x + gap, start.y, lastControl.x, lastControl.y, end.x, end.y)
                        }
                    } else {
                        // Full-width cards leave a margin on both sides for links that skip over other cards.
                        val left = edge.to in relayIds
                        val laneInset = (5 + index % 3 * 3).dp.toPx()
                        val laneX = if (left) laneInset else size.width - laneInset
                        start = Offset(if (left) from.left else from.right, fromY)
                        end = Offset(if (left) to.left else to.right, toY)
                        val bend = 12.dp.toPx().coerceAtMost((end.y - start.y) / 3f)
                        lastControl = Offset(laneX, end.y)
                        path.moveTo(start.x, start.y)
                        path.cubicTo(laneX, start.y, laneX, start.y, laneX, start.y + bend)
                        path.lineTo(laneX, end.y - bend)
                        path.cubicTo(laneX, end.y, lastControl.x, lastControl.y, end.x, end.y)
                    }

                    drawPath(
                        path, color,
                        style = Stroke(
                            width = (if (edge.style == TopologyEdgeStyle.WARNING) 2.5.dp else 1.75.dp).toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = if (edge.style == TopologyEdgeStyle.DASHED) {
                                PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                            } else null
                        )
                    )
                    drawArrowHead(end, lastControl, color, 7.dp.toPx())
                }
            }
            if (wide) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = RoutingDimensions.contentPadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(26.dp)
                ) {
                    for ((title, nodes) in layers) {
                        if (nodes.isEmpty()) Spacer(Modifier.weight(1f))
                        else layer(title, nodes, Modifier.weight(1f))
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = RoutingDimensions.contentPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    for ((title, nodes) in layers) {
                        if (nodes.isNotEmpty()) layer(title, nodes, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyLayer(
    title: Int,
    nodes: List<TopologyNode>,
    outgoing: Map<String, List<TopologyEdge>>,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>,
    testResults: Map<String, String>,
    wide: Boolean,
    onNodeClick: (TopologyNode) -> Unit,
    onBounds: (String, LayoutCoordinates) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (wide && nodes.isNotEmpty()) Spacer(Modifier.height(12.dp))
        for (node in nodes) {
            key(node.id) {
                DisposableEffect(node.id) { onDispose { onRemove(node.id) } }
                TopologyNodeCard(
                    node = node,
                    reasons = outgoing[node.id].orEmpty().map { it.reason },
                    trafficFlow = trafficFlow,
                    testResult = (node as? TopologyNode.Physical)?.channel?.let { testResults[it.id] },
                    onClick = { onNodeClick(node) },
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned { onBounds(node.id, it) }
                )
            }
        }
    }
}

private fun portFraction(edge: TopologyEdge, neighbors: List<TopologyEdge>): Float =
    0.2f + 0.6f * (neighbors.indexOf(edge) + 0.5f) / neighbors.size

@Composable
private fun TopologyNodeCard(
    node: TopologyNode,
    reasons: List<TopologyEdgeReason>,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>,
    testResult: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val offline = node is TopologyNode.Offline
    val warning = TopologyEdgeReason.VPN_RECHECK in reasons || TopologyEdgeReason.POSSIBLE_BYPASS in reasons
    val container = when {
        offline -> MaterialTheme.colorScheme.surfaceContainerLow
        warning -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.52f)
        node is TopologyNode.Physical && node.channel.isDefault -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val border = when {
        offline -> MaterialTheme.colorScheme.outlineVariant
        warning -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        node is TopologyNode.Physical && node.channel.isDefault -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    }
    Surface(
        modifier = modifier
            .alpha(if (offline) 0.68f else 1f)
            .clip(MaterialTheme.shapes.large)
            .then(if (offline) Modifier.drawWithContent {
                drawContent()
                drawRoundRect(
                    color = border, cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
                )
            } else Modifier.clickable(role = Role.Button, onClick = onClick)),
        shape = MaterialTheme.shapes.large,
        color = container,
        border = if (offline) null else androidx.compose.foundation.BorderStroke(1.dp, border),
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
                    text = nodeTitle(node),
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
                if (node is TopologyNode.ProxyGateway && node.draftAppCount > 0) {
                    Text(pluralStringResource(R.plurals.topology_proxy_draft_count, node.draftAppCount, node.draftAppCount), style = MaterialTheme.typography.bodySmall)
                    if (node.unmappedAppCount > 0) {
                        Text(stringResource(R.string.topology_proxy_unmapped_count, node.unmappedAppCount), style = MaterialTheme.typography.labelSmall)
                    }
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
private fun nodeTitle(node: TopologyNode): String = when (node) {
    is TopologyNode.DefaultApps -> pluralStringResource(R.plurals.topology_default_apps_count, node.appCount, node.appCount)
    is TopologyNode.RoutedApps -> pluralStringResource(R.plurals.topology_routed_apps_count, node.appCount, node.appCount)
    is TopologyNode.Hotspot -> stringResource(R.string.topology_hotspot_downlink)
    is TopologyNode.Vpn -> stringResource(R.string.topology_vpn)
    is TopologyNode.ProxyGateway -> stringResource(R.string.topology_proxy_gateway)
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
    is TopologyNode.DefaultApps, is TopologyNode.RoutedApps -> R.drawable.ic_symbol_route
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

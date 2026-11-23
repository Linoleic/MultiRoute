package com.multiroute.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multiroute.R
import com.multiroute.model.NetworkChannel
import com.multiroute.util.ChannelTrafficStats
import com.multiroute.util.NetworkUtils
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetworkTopologyView(
    topology: NetworkTopology,
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

    val layers = remember(topology.sources, topology.relays, topology.egresses) {
        listOf(
            R.string.topology_sources to topology.sources,
            R.string.topology_relays to topology.relays,
            R.string.topology_egresses to topology.egresses
        ).filter { it.second.isNotEmpty() }
    }
    val contentWidth = TopologyCanvasMetrics.minWidthDp(layers.size).dp
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        TopologyLegend(Modifier.padding(horizontal = TopologyCanvasMetrics.SIDE_PADDING_DP.dp, vertical = 4.dp))
        if (topology.activeScenarioNames.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = TopologyCanvasMetrics.SIDE_PADDING_DP.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (name in topology.activeScenarioNames) {
                    Surface(shape = RoundedCornerShape(50), color = colors.secondaryContainer) {
                        Text(
                            stringResource(R.string.topology_active_scenario, name),
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            ZoomableTopology(
                contentWidth = contentWidth,
                hasSelection = selectedNodeId != null,
                onClearSelection = onClearSelection,
                modifier = Modifier.weight(1f)
            ) {
                TopologyColumns(topology, layers, trafficFlow, testResults, selectedNodeId, onNodeClick)
            }
        }
    }
}

private val topologyViewportSaver = listSaver<TopologyViewport?, Float>(
    save = { if (it == null) emptyList() else listOf(it.scale, it.offset.x, it.offset.y) },
    restore = { if (it.size == 3) TopologyViewport(it[0], Offset(it[1], it[2])) else null }
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ZoomableTopology(
    contentWidth: Dp,
    hasSelection: Boolean,
    onClearSelection: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val viewport = rememberSaveable(stateSaver = topologyViewportSaver) { mutableStateOf<TopologyViewport?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val clearSelection = rememberUpdatedState(onClearSelection)
    val fabClearancePx = with(LocalDensity.current) { 72.dp.roundToPx() }
    val hasMeasured = viewportSize.width > 0 && viewportSize.height > 0 &&
        contentSize.width > 0 && contentSize.height > 0
    val fitViewport = TopologyViewport.fit(viewportSize, contentSize)
    val canZoomToActualSize = hasMeasured && fitViewport.scale < 0.999f
    val currentViewport = (viewport.value ?: fitViewport).constrained(
        viewportSize,
        contentSize,
        bottomInset = if (canZoomToActualSize || viewport.value != null) fabClearancePx else 0
    )
    val atFit = currentViewport.isCloseTo(fitViewport)
    val showViewportFab = hasMeasured && (canZoomToActualSize || !atFit)
    val bottomInset = if (showViewportFab) fabClearancePx else 0
    val activeBottomInset = rememberUpdatedState(bottomInset)
    val blankTap = if (hasSelection) Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = { clearSelection.value() })
    } else Modifier

    Box(modifier.fillMaxSize()) {
        Layout(
            content = { Box(Modifier.onSizeChanged { contentSize = it }) { content() } },
            modifier = Modifier.fillMaxSize().clipToBounds()
                .onSizeChanged { viewportSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                        val current = viewport.value ?: TopologyViewport.fit(size, contentSize)
                        val candidate = current.transformed(
                            centroid,
                            pan,
                            zoom,
                            size,
                            contentSize,
                            bottomInset = activeBottomInset.value
                        )
                        val fit = TopologyViewport.fit(size, contentSize)
                        viewport.value = if (candidate.isCloseTo(fit)) null else candidate
                    }
                }
                .then(blankTap)
        ) { measurables, constraints ->
            val layoutSize = IntSize(constraints.maxWidth, constraints.maxHeight)
            val graphWidth = maxOf(layoutSize.width, contentWidth.roundToPx())
            val graph = measurables.single().measure(Constraints.fixedWidth(graphWidth))
            val graphSize = IntSize(graph.width, graph.height)
            layout(layoutSize.width, layoutSize.height) {
                // The graph, its connectors and its touch targets share one transformation.
                graph.placeRelativeWithLayer(0, 0) {
                    val fit = TopologyViewport.fit(layoutSize, graphSize)
                    val view = (viewport.value ?: fit).constrained(layoutSize, graphSize, bottomInset = bottomInset)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = view.scale
                    scaleY = view.scale
                    translationX = view.offset.x
                    translationY = view.offset.y
                }
            }
        }
        AnimatedVisibility(
            visible = showViewportFab,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                scaleOut(MaterialTheme.motionScheme.fastSpatialSpec())
        ) {
            val switchToActualSize = atFit && canZoomToActualSize
            SmallFloatingActionButton(
                onClick = { viewport.value = if (switchToActualSize) TopologyViewport() else null },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(
                    painterResource(
                        if (switchToActualSize) R.drawable.ic_symbol_zoom_in else R.drawable.ic_symbol_fit_screen
                    ),
                    stringResource(
                        if (switchToActualSize) R.string.topology_actual_size else R.string.topology_reset_view
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopologyColumns(
    topology: NetworkTopology,
    layers: List<Pair<Int, List<TopologyNode>>>,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>,
    testResults: Map<String, String>,
    selectedNodeId: String?,
    onNodeClick: (TopologyNode) -> Unit
) {
    val trace = remember(topology, selectedNodeId) { selectedNodeId?.let { traceConnectedGraph(topology, it) } }
    val outgoing = remember(topology.edges) { topology.edges.groupBy { it.from } }
    val incoming = remember(topology.edges) { topology.edges.groupBy { it.to } }
    val directLanes = remember(topology) {
        if (topology.relays.isEmpty()) emptyMap() else {
            val sourceIds = topology.sources.mapTo(mutableSetOf()) { it.id }
            val egressIds = topology.egresses.mapTo(mutableSetOf()) { it.id }
            topology.edges.filter { it.from in sourceIds && it.to in egressIds }
                .withIndex().associate { (index, edge) -> edge to TopologyCanvasMetrics.laneOffsetDp(index) }
        }
    }
    val egressInterfaces = remember(topology.egresses) {
        topology.egresses.filterIsInstance<TopologyNode.Physical>().associate { it.id to it.channel.interfaceName }
    }
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
    val anchors = remember { TopologyAnchors() }
    // Read traffic in the draw phase so sampling doesn't recompose the whole topology.
    val rates = trafficFlow.collectAsStateWithLifecycle()
    val gap = TopologyCanvasMetrics.COLUMN_GAP_DP.dp
    val sidePadding = TopologyCanvasMetrics.SIDE_PADDING_DP.dp

    Box(Modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = 4.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for ((title, _) in layers) {
                    Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth()) {
                Canvas(Modifier.matchParentSize().onGloballyPositioned(anchors::placeCanvas)) {
                    val samples = rates.value
                    for (edge in topology.edges) {
                        val from = anchors.bounds[edge.from] ?: continue
                        val to = anchors.bounds[edge.to] ?: continue
                        val start = Offset(from.right, from.top + from.height * portFraction(edge, outgoing.getValue(edge.from)))
                        val end = Offset(to.left, to.top + to.height * portFraction(edge, incoming.getValue(edge.to)))
                        if (end.x <= start.x) continue
                        val path = Path().apply { moveTo(start.x, start.y) }
                        val lane = directLanes[edge]
                        if (lane != null) {
                            // Both turns stay in the gaps beside the relay column.
                            val turn = gap.toPx() / 3f
                            val laneY = lane.dp.toPx()
                            path.cubicTo(start.x + turn, start.y, start.x + turn, laneY, start.x + 2 * turn, laneY)
                            path.lineTo(end.x - 2 * turn, laneY)
                            path.cubicTo(end.x - turn, laneY, end.x - turn, end.y, end.x, end.y)
                        } else {
                            val bend = (end.x - start.x) / 2f
                            path.cubicTo(start.x + bend, start.y, end.x - bend, end.y, end.x, end.y)
                        }
                        val color = when (edge.style) {
                            TopologyEdgeStyle.SOLID -> colors.primary
                            TopologyEdgeStyle.DASHED -> colors.outline
                            TopologyEdgeStyle.WARNING -> colors.tertiary
                        }.copy(alpha = edgeAlpha.getValue(edge).value)
                        val active = edge.style == TopologyEdgeStyle.SOLID && samples[egressInterfaces[edge.to]].hasTraffic()
                        val lineWidth = when {
                            active || trace != null && edge in trace.second -> 2.5.dp
                            edge.style == TopologyEdgeStyle.WARNING -> 2.25.dp
                            else -> 1.75.dp
                        }
                        drawPath(
                            path, color,
                            style = Stroke(
                                width = lineWidth.toPx(),
                                cap = StrokeCap.Round,
                                pathEffect = if (edge.style == TopologyEdgeStyle.DASHED) {
                                    PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                                } else null
                            )
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = TopologyCanvasMetrics.laneHeightDp(directLanes.size).dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    for ((_, nodes) in layers) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (node in nodes) {
                                key(node.id) {
                                    DisposableEffect(anchors, node.id) { onDispose { anchors.removeNode(node.id) } }
                                    val alpha by animateFloatAsState(
                                        targetValue = if (trace == null || node.id in trace.first) 1f else 0.24f,
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
                                        modifier = Modifier.fillMaxWidth().alpha(alpha)
                                            .onGloballyPositioned { anchors.placeNode(node.id, it) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class TopologyAnchors {
    private var canvas: LayoutCoordinates? = null
    private val nodes = mutableMapOf<String, LayoutCoordinates>()
    val bounds = mutableStateMapOf<String, Rect>()

    fun placeCanvas(coordinates: LayoutCoordinates) {
        canvas = coordinates
        nodes.forEach(::updateBounds)
    }

    fun placeNode(id: String, coordinates: LayoutCoordinates) {
        nodes[id] = coordinates
        updateBounds(id, coordinates)
    }

    fun removeNode(id: String) {
        nodes.remove(id)
        bounds.remove(id)
    }

    private fun updateBounds(id: String, node: LayoutCoordinates) {
        val target = canvas?.takeIf { it.isAttached } ?: return
        if (!node.isAttached) return
        val origin = target.localPositionOf(node, Offset.Zero)
        bounds[id] = Rect(origin.x, origin.y, origin.x + node.size.width, origin.y + node.size.height)
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
    val title = topologyNodeTitle(node)
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    painter = painterResource(nodeIcon(node)),
                    contentDescription = null,
                    tint = when {
                        offline -> MaterialTheme.colorScheme.onSurfaceVariant
                        warning -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (node is TopologyNode.Physical) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (node is TopologyNode.Physical && node.channel.isDefault) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Text(
                            stringResource(R.string.topology_default_badge),
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }
            }
            nodeSubtitle(node, title)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (node is TopologyNode.Physical) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (warning) {
                val label = if (node is TopologyNode.Vpn) R.string.topology_vpn_recheck else R.string.topology_legend_warning
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (node is TopologyNode.Vpn && TopologyEdgeReason.VPN_CONFIGURED in reasons) {
                Text(stringResource(R.string.topology_vpn_pending), style = MaterialTheme.typography.labelSmall)
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

@Composable
private fun PhysicalMetrics(channel: NetworkChannel, trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>, testResult: String?) {
    val address = channel.ipAddress
    if (address != null) {
        Text(
            text = address,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
    ChannelTrafficRateRow(channel.interfaceName, trafficFlow, dense = true)
    if (testResult != null) {
        val context = LocalContext.current
        val failed = NetworkUtils.isFailure(testResult)
        val info = testResult.startsWith(NetworkUtils.INFO_PREFIX)
        val label = NetworkUtils.egressResultLabel(context, testResult)
        val text = if (failed || info) {
            label
        } else {
            stringResource(R.string.topology_test_result, NetworkUtils.compactEgressWithLatencyFirst(label))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun topologyNodeTitle(node: TopologyNode): String = when (node) {
    is TopologyNode.DefaultApps -> pluralStringResource(R.plurals.topology_default_apps_count, node.appCount, node.appCount)
    is TopologyNode.RoutedApps -> pluralStringResource(R.plurals.topology_routed_apps_count, node.appCount, node.appCount)
    is TopologyNode.Hotspot -> stringResource(R.string.topology_hotspot_downlink)
    is TopologyNode.Vpn -> stringResource(R.string.topology_vpn_iface_title, node.channel.interfaceName)
    is TopologyNode.ProxyGateway -> stringResource(R.string.topology_proxy_gateway)
    is TopologyNode.ProxyApps -> pluralStringResource(R.plurals.topology_proxy_apps_count, node.appCount, node.appCount)
    is TopologyNode.Physical -> topologyChannelTitle(node.channel)
    is TopologyNode.Offline -> NetworkUtils.savedTargetLabel(LocalContext.current, node.target)
}

@Composable
private fun nodeSubtitle(node: TopologyNode, title: String): String? = when (node) {
    is TopologyNode.DefaultApps -> if (node.vpnCoverageUnknown) stringResource(R.string.topology_vpn_coverage_unknown) else null
    is TopologyNode.RoutedApps -> node.channel?.let {
        topologyChannelTitle(it)
    } ?: stringResource(R.string.ui_channel_offline_short, NetworkUtils.savedTargetLabel(LocalContext.current, node.target))
    is TopologyNode.Hotspot -> node.iface ?: stringResource(R.string.topology_hotspot_channel_unavailable)
    is TopologyNode.Vpn -> buildList {
        compactVpnOwnerName(node.ownerName)?.let(::add)
        node.channel.underlyingIface?.let { add(stringResource(R.string.topology_vpn_reported, it)) }
        if (node.supportsGateway && size < 2) add(stringResource(R.string.topology_vpn_gateway_supported))
    }.joinToString("\n").ifEmpty { null }
    is TopologyNode.ProxyGateway -> if (node.detected) stringResource(R.string.topology_proxy_detected) else null
    is TopologyNode.ProxyApps -> stringResource(R.string.topology_proxy_draft_pending)
    is TopologyNode.Physical -> node.channel.interfaceName.takeUnless { it == title }
    is TopologyNode.Offline -> stringResource(R.string.topology_offline)
}

@Composable
internal fun topologyChannelTitle(channel: NetworkChannel): String = when (channel.transportType) {
    "WLAN" -> channel.ssid?.takeIf(String::isNotBlank)
        ?: channel.shortName.takeIf(String::isNotBlank) ?: stringResource(R.string.topology_wifi_title)
    "cellular" -> compactCellularLabel(
        channel.cellularLabel,
        stringResource(R.string.cellular_default_suffix)
    ) ?: channel.shortName
    else -> if (channel.isVpn) stringResource(R.string.topology_vpn_iface_title, channel.interfaceName) else channel.shortName
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopologyLegend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
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

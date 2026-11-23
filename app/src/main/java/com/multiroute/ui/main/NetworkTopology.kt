package com.multiroute.ui.main

import com.multiroute.cellular.CellularTargets
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.model.ScenarioObservation
import com.multiroute.util.ScenarioEngine
import com.multiroute.util.TransparentProxyDiagnostics
import com.multiroute.wifi.DualWifiStatus

internal data class NetworkTopology(
    val sources: List<TopologyNode>,
    val relays: List<TopologyNode>,
    val egresses: List<TopologyNode>,
    val edges: List<TopologyEdge>,
    val activeScenarioNames: List<String> = emptyList()
)

internal object TopologyCanvasMetrics {
    const val COLUMN_WIDTH_DP = 192
    const val COLUMN_GAP_DP = 32
    const val SIDE_PADDING_DP = 16
    const val MIN_FIT_SCALE = 0.45f

    fun minWidthDp(columnCount: Int): Int =
        SIDE_PADDING_DP * 2 + COLUMN_WIDTH_DP * columnCount + COLUMN_GAP_DP * (columnCount - 1)

    fun fitScale(availableWidthPx: Int, contentWidthPx: Int): Float =
        (availableWidthPx.toFloat() / contentWidthPx).coerceIn(MIN_FIT_SCALE, 1f)

    fun laneOffsetDp(directIndex: Int): Int = 7 + directIndex * 6

    fun laneHeightDp(directCount: Int): Int = if (directCount == 0) 0 else 14 + directCount * 6
}

/** Effective per-app destinations for the current link observation, plus the selected plans. */
internal data class EffectiveAppTargets(
    val targetsByRuleKey: Map<String, String>,
    val activeScenarioNames: List<String>
)

/** Resolves scenario overlays using the same priority, UID identity and missing-target fallback as route sync. */
internal fun resolveEffectiveAppTargets(
    apps: List<AppItem>,
    channels: List<NetworkChannel>,
    scenario: ScenarioUiState
): EffectiveAppTargets {
    val observation = ScenarioObservation.fromChannels(channels)
    val selected = ScenarioEngine.selectProfiles(
        scenario.scenarioProfiles,
        observation,
        if (scenario.scenarioManualMode) ScenarioEngine.SelectionMode.MANUAL
        else ScenarioEngine.SelectionMode.AUTOMATIC,
        scenario.manualScenarioIds
    )
    val knownChannels = channels.flatMap { listOf(it.id, it.interfaceName) }.toSet()
    val appsByRuleKey = apps.associateBy { it.ruleKey }
    val overridesByUid = mutableMapOf<Int, String>()
    for (profile in selected) {
        // Route sync collapses package keys sharing a UID before resolving that plan's destinations.
        val planByUid = profile.overrides.mapNotNull { (key, rawTarget) ->
            appsByRuleKey[key]?.uid?.takeIf { it > 0 }?.let { it to rawTarget }
        }.toMap()
        for ((uid, rawTarget) in planByUid) {
            val target = ScenarioEngine.resolveOverrideChannel(rawTarget, observation) ?: continue
            if (target == CHANNEL_DEFAULT || CellularTargets.isSim(target) || knownChannels.isEmpty() || target in knownChannels) {
                overridesByUid[uid] = target
            }
        }
    }
    return EffectiveAppTargets(
        apps.associate { it.ruleKey to (overridesByUid[it.uid] ?: it.targetChannelId) },
        selected.map { it.name }
    )
}

internal sealed interface TopologyNode {
    val id: String

    data class DefaultApps(val appCount: Int, val vpnCoverageUnknown: Boolean) : TopologyNode {
        override val id = "apps:default"
    }

    data class RoutedApps(val target: String, val appCount: Int, val channel: NetworkChannel?) : TopologyNode {
        override val id = "apps:$target"
    }

    data class ProxyApps(val appCount: Int, val unmappedCount: Int) : TopologyNode {
        override val id = "proxy-apps"
    }

    data class Hotspot(val iface: String?) : TopologyNode {
        override val id = "hotspot"
    }

    data class Vpn(val channel: NetworkChannel, val ownerName: String?, val supportsGateway: Boolean) : TopologyNode {
        override val id = "vpn:${channel.id}"
    }

    data class ProxyGateway(val detected: Boolean, val draftAppCount: Int, val unmappedAppCount: Int) : TopologyNode {
        override val id = "proxy"
    }

    data class Physical(val channel: NetworkChannel) : TopologyNode {
        override val id = "physical:${channel.id}"
    }

    data class Offline(val target: String) : TopologyNode {
        override val id = "offline:$target"
    }
}

internal data class VpnUpstreamSnapshot(val configuredTarget: String?, val ownerName: String?)

internal enum class TopologyEdgeStyle { SOLID, DASHED, WARNING }

internal enum class TopologyEdgeReason {
    SYSTEM_DEFAULT, APP_RULE, OFFLINE, HOTSPOT_ACTIVE, HOTSPOT_CONFIGURED,
    VPN_REPORTED, VPN_CONFIGURED, VPN_RECHECK, PROXY_SELECTED, PROXY_DRAFT, POSSIBLE_BYPASS
}

internal data class TopologyEdge(
    val from: String,
    val to: String,
    val style: TopologyEdgeStyle,
    val reason: TopologyEdgeReason
)

internal fun buildNetworkTopology(
    channels: List<NetworkChannel>,
    apps: List<AppItem>,
    vpnUpstreams: Map<String, VpnUpstreamSnapshot>,
    hotspotStatus: DualWifiStatus,
    proxyState: ProxyUpstreamUiState,
    transparentProxy: TransparentProxyDiagnostics?,
    supportedAndroidVpn: NetworkChannel?,
    effectiveAppTargets: EffectiveAppTargets? = null
): NetworkTopology {
    val physical = channels.filterNot { it.isVpn || it.isHotspot }.map { TopologyNode.Physical(it) }
    val vpns = channels.filter { it.isVpn && !it.isHotspot }.map { channel ->
        val snapshot = vpnUpstreams[channel.id]
        TopologyNode.Vpn(
            channel, snapshot?.ownerName,
            supportedAndroidVpn?.id == channel.id && supportedAndroidVpn.vpnOwnerUid == channel.vpnOwnerUid
        )
    }
    val physicalTargets = mutableMapOf<String, TopologyNode.Physical>()
    val vpnTargets = mutableMapOf<String, TopologyNode.Vpn>()
    physical.forEach { physicalTargets[it.channel.interfaceName] = it }
    physical.forEach { physicalTargets[it.channel.id] = it }
    vpns.forEach { vpnTargets[it.channel.interfaceName] = it }
    vpns.forEach { vpnTargets[it.channel.id] = it }
    val physicalByInterface = physical.associateBy { it.channel.interfaceName }
    val offline = linkedMapOf<String, TopologyNode.Offline>()
    val sources = mutableListOf<TopologyNode>()
    val edges = linkedSetOf<TopologyEdge>()

    fun offlineTarget(target: String) = offline.getOrPut(target) { TopologyNode.Offline(target) }

    fun connect(from: TopologyNode, to: TopologyNode, reason: TopologyEdgeReason) {
        val style = when (reason) {
            TopologyEdgeReason.POSSIBLE_BYPASS -> TopologyEdgeStyle.WARNING
            TopologyEdgeReason.OFFLINE, TopologyEdgeReason.HOTSPOT_CONFIGURED, TopologyEdgeReason.VPN_CONFIGURED,
            TopologyEdgeReason.VPN_RECHECK, TopologyEdgeReason.PROXY_DRAFT -> TopologyEdgeStyle.DASHED
            else -> TopologyEdgeStyle.SOLID
        }
        edges += TopologyEdge(from.id, to.id, style, reason)
    }

    val appTargets = effectiveAppTargets?.targetsByRuleKey
    val appCounts = apps.groupingBy { app ->
        val target = appTargets?.get(app.ruleKey) ?: app.targetChannelId
        physicalTargets[target]?.channel?.id ?: vpnTargets[target]?.channel?.id ?: target
    }.eachCount()
    appCounts[CHANNEL_DEFAULT]?.let { count ->
        val source = TopologyNode.DefaultApps(count, vpnCoverageUnknown = vpns.isNotEmpty())
        sources += source
        // The physical system default is a baseline, not an assertion about any app's VPN coverage.
        physical.singleOrNull { it.channel.isDefault }?.let { connect(source, it, TopologyEdgeReason.SYSTEM_DEFAULT) }
    }
    appCounts.toSortedMap().forEach { (target, count) ->
        if (target == CHANNEL_DEFAULT) return@forEach
        val endpoint = physicalTargets[target]
        val vpn = vpnTargets[target]
        val source = TopologyNode.RoutedApps(target, count, endpoint?.channel ?: vpn?.channel)
        sources += source
        when {
            endpoint != null -> connect(source, endpoint, if (transparentProxy?.mayBypassOn(endpoint.channel.interfaceName) == true) {
                TopologyEdgeReason.POSSIBLE_BYPASS
            } else TopologyEdgeReason.APP_RULE)
            vpn != null -> connect(source, vpn, TopologyEdgeReason.APP_RULE)
            else -> connect(source, offlineTarget(target), TopologyEdgeReason.OFFLINE)
        }
    }

    val hotspotChannel = channels.firstOrNull { it.isHotspot }
    if (hotspotChannel != null || hotspotStatus.isHotspotEnabled) {
        val source = TopologyNode.Hotspot(hotspotChannel?.interfaceName ?: hotspotStatus.hotspotIface)
        sources += source
        val active = hotspotStatus.hotspotUpstreamActive.split(',')
            .mapNotNull { physicalByInterface[it.trim()] }
        active.forEach { connect(source, it, TopologyEdgeReason.HOTSPOT_ACTIVE) }
        val target = hotspotStatus.hotspotUpstreamTarget
        // The "cellular" preference does not name one specific cellular interface.
        if (target != CHANNEL_DEFAULT && target != "cellular" && target.isNotBlank()) {
            val configured = physicalTargets[target] ?: offlineTarget(target)
            if (configured !in active) connect(source, configured, TopologyEdgeReason.HOTSPOT_CONFIGURED)
        }
    }

    vpns.forEach { vpn ->
        val reportedIface = vpn.channel.underlyingIface
        val reported = physicalByInterface[reportedIface]
        if (reported != null) connect(vpn, reported, TopologyEdgeReason.VPN_REPORTED)
        val target = vpnUpstreams[vpn.channel.id]?.configuredTarget
        if (target != null && target != CHANNEL_DEFAULT && target.isNotBlank()) {
            val configured = physicalTargets[target] ?: offlineTarget(target)
            if (configured != reported) {
                val reason = if (reportedIface == null) TopologyEdgeReason.VPN_CONFIGURED else TopologyEdgeReason.VPN_RECHECK
                connect(vpn, configured, reason)
            }
        }
    }

    val proxyDetected = transparentProxy?.let { diagnostics ->
        diagnostics.interfaces.any { it.singBoxLocalTcPresent } || diagnostics.cgroupHooks.singBoxHookCount > 0
    } ?: false
    val proxy = if (proxyDetected || proxyState.profile != null || proxyState.gatewayAppTargets.isNotEmpty()) {
        TopologyNode.ProxyGateway(
            proxyDetected, proxyState.gatewayAppTargets.size,
            proxyState.gatewayAppTargets.values.count { proxyState.gatewayMappings[it].isNullOrBlank() }
        )
    } else null
    if (proxy != null) {
        if (proxy.draftAppCount > 0) {
            val source = TopologyNode.ProxyApps(proxy.draftAppCount, proxy.unmappedAppCount)
            sources += source
            connect(source, proxy, TopologyEdgeReason.PROXY_DRAFT)
        }
        val member = proxyState.selector?.current
        val selectedTargets = proxyState.profile?.mappings.orEmpty().filterValues { it == member }.keys
        val selected = physical.singleOrNull { it.channel.proxyUpstreamKey() in selectedTargets }
        if (selected != null) connect(proxy, selected, TopologyEdgeReason.PROXY_SELECTED)
        val byMappingKey = physical.mapNotNull { node -> node.channel.proxyUpstreamKey()?.let { it to node } }.toMap()
        proxyState.gatewayAppTargets.values.toSortedSet().forEach { target ->
            connect(proxy, byMappingKey[target] ?: physicalTargets[target] ?: offlineTarget(target), TopologyEdgeReason.PROXY_DRAFT)
        }
    }

    return NetworkTopology(
        sources.toList(), vpns + listOfNotNull(proxy), physical + offline.values, edges.toList(),
        effectiveAppTargets?.activeScenarioNames.orEmpty()
    )
}

/** Returns the selected node and all nodes/edges reachable in either direction from it. */
internal fun traceConnectedGraph(
    topology: NetworkTopology,
    selectedNodeId: String
): Pair<Set<String>, Set<TopologyEdge>> {
    val allIds = (topology.sources + topology.relays + topology.egresses).mapTo(mutableSetOf()) { it.id }
    if (selectedNodeId !in allIds) return emptySet<String>() to emptySet()

    val connectedNodes = linkedSetOf(selectedNodeId)
    val connectedEdges = linkedSetOf<TopologyEdge>()

    fun traverse(adjacency: Map<String, List<TopologyEdge>>, nextNode: (TopologyEdge) -> String) {
        val queue = ArrayDeque<String>()
        queue += selectedNodeId
        val visited = mutableSetOf(selectedNodeId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            adjacency[current].orEmpty().forEach { edge ->
                val to = nextNode(edge)
                if (to in allIds) {
                    connectedEdges += edge
                    connectedNodes += to
                    if (visited.add(to)) queue += to
                }
            }
        }
    }

    traverse(topology.edges.groupBy { it.to }) { it.from }
    traverse(topology.edges.groupBy { it.from }) { it.to }
    return connectedNodes to connectedEdges
}

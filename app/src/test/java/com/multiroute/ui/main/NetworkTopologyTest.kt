package com.multiroute.ui.main

import com.multiroute.data.ProxyUpstreamProfile
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.util.CgroupHookEvidence
import com.multiroute.util.ClashProxyUpstreamClient.Selector
import com.multiroute.util.SocketEvidence
import com.multiroute.util.ScenarioEngine
import com.multiroute.util.TcInterfaceEvidence
import com.multiroute.util.TransparentProxyDiagnostics
import com.multiroute.wifi.DualWifiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTopologyTest {
    @Test
    fun minimumWidthAccountsForColumnsAndTheirGaps() {
        assertEquals(672, TopologyCanvasMetrics.minWidthDp(3))
        assertEquals(448, TopologyCanvasMetrics.minWidthDp(2))
    }

    @Test
    fun everyDirectEdgeHasItsOwnLaneBelowTheHeadingsAndAboveTheCards() {
        assertEquals(0, TopologyCanvasMetrics.laneHeightDp(0))
        val count = 7
        val offsets = (0 until count).map(TopologyCanvasMetrics::laneOffsetDp)
        assertEquals(count, offsets.toSet().size)
        assertTrue(offsets.first() > 0)
        assertTrue(offsets.last() < TopologyCanvasMetrics.laneHeightDp(count))
    }

    private val wifi = channel("wlan0").copy(isDefault = true)
    private val simKey = "sim_" + "a".repeat(64)
    private val cellular = channel("rmnet_data2").copy(id = simKey, subscriptionKey = simKey, transportType = "cellular")
    private val vpn = channel("tun0").copy(isVpn = true, vpnOwnerUid = 10101, underlyingIface = wifi.interfaceName)

    @Test
    fun groupsDefaultAndAssignedAppsAndShowsOfflineTargets() {
        val graph = topology(
            channels = listOf(wifi, cellular),
            apps = listOf(app(1), app(2, cellular.id), app(3, cellular.interfaceName), app(4, "eth0"))
        )
        val default = graph.sources.filterIsInstance<TopologyNode.DefaultApps>().single()
        val groups = graph.sources.filterIsInstance<TopologyNode.RoutedApps>().associateBy { it.target }
        assertEquals(1, default.appCount)
        assertEquals(2, groups.getValue(cellular.id).appCount)
        assertEquals(TopologyNode.Offline("eth0"), graph.egresses.last())
        assertEquals(TopologyEdgeStyle.SOLID, graph.edgeFrom(default).style)
        assertEquals(TopologyNode.Physical(wifi).id, graph.edgeFrom(default).to)
        assertEquals(TopologyNode.Physical(cellular).id, graph.edgeFrom(groups.getValue(cellular.id)).to)
        assertEquals(TopologyEdgeStyle.DASHED, graph.edgeFrom(groups.getValue("eth0")).style)
    }

    @Test
    fun vpnReportedUpstreamAndMatchingConfigurationShareOneEdge() {
        val graph = topology(
            channels = listOf(wifi, vpn),
            apps = listOf(app(1), app(2, vpn.id)),
            vpnUpstreams = mapOf(vpn.id to VpnUpstreamSnapshot(wifi.id, "Example VPN")),
            supportedAndroidVpn = vpn
        )
        val relay = graph.relays.filterIsInstance<TopologyNode.Vpn>().single()
        assertEquals("Example VPN", relay.ownerName)
        assertTrue(relay.supportsGateway)
        assertEquals(TopologyEdge(relay.id, TopologyNode.Physical(wifi).id, TopologyEdgeStyle.SOLID, TopologyEdgeReason.VPN_REPORTED), graph.edgeFrom(relay))
        assertEquals(relay.id, graph.edgeFrom(graph.sources.filterIsInstance<TopologyNode.RoutedApps>().single()).to)
        assertTrue(graph.sources.filterIsInstance<TopologyNode.DefaultApps>().single().vpnCoverageUnknown)
    }

    @Test
    fun vpnConfiguredTargetIsDashedAndMissingObservationDoesNotInventAnActiveLink() {
        val input = mapOf(vpn.id to VpnUpstreamSnapshot(cellular.id, null))
        val graph = topology(channels = listOf(wifi, cellular, vpn), vpnUpstreams = input)
        assertEquals(setOf(TopologyEdgeReason.VPN_REPORTED, TopologyEdgeReason.VPN_RECHECK), graph.edges.map { it.reason }.toSet())
        val pending = graph.edges.single { it.reason == TopologyEdgeReason.VPN_RECHECK }
        assertEquals(TopologyEdgeStyle.DASHED, pending.style)
        assertEquals(TopologyNode.Physical(cellular).id, pending.to)

        val withoutObservation = topology(
            channels = listOf(wifi.copy(isDefault = false), vpn.copy(isDefault = true, underlyingIface = null)),
            apps = listOf(app(1)), vpnUpstreams = input
        )
        val configured = withoutObservation.edges.single { it.reason == TopologyEdgeReason.VPN_CONFIGURED }
        assertEquals(TopologyNode.Offline(cellular.id).id, configured.to)
        assertFalse(withoutObservation.edges.any { it.reason == TopologyEdgeReason.VPN_REPORTED })
        assertFalse(withoutObservation.edges.any { it.from == TopologyNode.DefaultApps(1, true).id })
    }

    @Test
    fun warnsOnlyOnPhysicalAssignmentAndSeparatesSelectorReadbackFromDraft() {
        val diagnostics = TransparentProxyDiagnostics(
            listOf(TcInterfaceEvidence(wifi.interfaceName, true, false, true), TcInterfaceEvidence(cellular.interfaceName, true, false, false)),
            CgroupHookEvidence(CgroupHookEvidence.Status.AVAILABLE),
            SocketEvidence(SocketEvidence.Status.SKIPPED_NO_KNOWN_PROCESS, 0)
        )
        val state = ProxyUpstreamUiState(
            profile = ProxyUpstreamProfile(9090, "Upstream", mapOf(wifi.id to "selected", cellular.id to "cellular")),
            selector = Selector("selected", setOf("selected", "cellular")),
            gatewayAppTargets = mapOf("app2" to cellular.id, "app3" to cellular.id, "app4" to "eth0")
        )
        val graph = topology(listOf(wifi, cellular), listOf(app(1, cellular.id), app(2)), proxy = state, diagnostics = diagnostics)
        val relay = graph.relays.filterIsInstance<TopologyNode.ProxyGateway>().single()
        assertTrue(relay.detected)
        assertEquals(3, relay.draftAppCount)
        assertEquals(1, relay.unmappedAppCount)
        val risk = graph.edges.single { it.style == TopologyEdgeStyle.WARNING }
        assertEquals(TopologyEdgeReason.POSSIBLE_BYPASS, risk.reason)
        assertEquals(TopologyNode.Physical(cellular).id, risk.to)
        val proxyEdges = graph.edges.filter { it.from == relay.id }
        assertEquals(TopologyNode.Physical(wifi).id, proxyEdges.single { it.style == TopologyEdgeStyle.SOLID }.to)
        assertEquals(setOf(TopologyNode.Physical(cellular).id, TopologyNode.Offline("eth0").id),
            proxyEdges.filter { it.reason == TopologyEdgeReason.PROXY_DRAFT }.map { it.to }.toSet())
    }

    @Test
    fun hotspotUsesReportedUpstreamAndDoesNotInferOneFromTheDefaultNetwork() {
        assertTrue(topology(listOf(wifi)).sources.none { it is TopologyNode.Hotspot })
        val downlink = channel("swlan0").copy(isHotspot = true)
        val unknown = topology(listOf(wifi, downlink))
        assertEquals(TopologyNode.Hotspot("swlan0"), unknown.sources.single())
        assertTrue(unknown.edges.isEmpty())
        val status = DualWifiStatus(isHotspotEnabled = true, hotspotIface = downlink.id,
            hotspotUpstreamActive = wifi.interfaceName, hotspotUpstreamTarget = cellular.id)
        val known = topology(listOf(wifi, cellular), hotspot = status)
        assertEquals(TopologyNode.Hotspot("swlan0"), known.sources.single())
        assertEquals(TopologyEdgeStyle.SOLID, known.edges.single { it.reason == TopologyEdgeReason.HOTSPOT_ACTIVE }.style)
        assertEquals(TopologyEdgeStyle.DASHED, known.edges.single { it.reason == TopologyEdgeReason.HOTSPOT_CONFIGURED }.style)
    }

    @Test
    fun aDraftAddressingAnOnlineInterfaceStaysDashedEvenWithoutAnExportableSimIdentity() {
        val unrecognized = cellular.copy(id = cellular.interfaceName, subscriptionKey = null)
        val graph = topology(
            channels = listOf(unrecognized),
            proxy = ProxyUpstreamUiState(gatewayAppTargets = mapOf("app1" to unrecognized.interfaceName))
        )
        val egress = TopologyNode.Physical(unrecognized)
        assertEquals(listOf(egress), graph.egresses)
        assertEquals(
            setOf(
                TopologyEdge("proxy-apps", "proxy", TopologyEdgeStyle.DASHED, TopologyEdgeReason.PROXY_DRAFT),
                TopologyEdge("proxy", egress.id, TopologyEdgeStyle.DASHED, TopologyEdgeReason.PROXY_DRAFT)
            ), graph.edges.toSet()
        )
    }

    @Test
    fun activeScenarioOverridesBaseAndUnresolvedSymbolicTargetFallsBack() {
        val base = app(1, wifi.id).copy(uid = 10_001)
        val app2 = app(2, wifi.id).copy(uid = 10_002)
        val profiles = listOf(
            ScenarioProfile(
                id = "active", name = "Work", trigger = ScenarioTrigger.Always,
                overrides = mapOf(base.ruleKey to cellular.id, app2.ruleKey to ScenarioEngine.symbolicSsid("Missing"))
            )
        )
        val scenario = ScenarioUiState(scenarioProfiles = profiles)
        val resolved = resolveEffectiveAppTargets(listOf(base, app2), listOf(wifi, cellular), scenario)
        assertEquals(cellular.id, resolved.targetsByRuleKey[base.ruleKey])
        assertEquals(wifi.id, resolved.targetsByRuleKey[app2.ruleKey])
        assertEquals(listOf("Work"), resolved.activeScenarioNames)

        val graph = topology(
            channels = listOf(wifi, cellular), apps = listOf(base, app2), effectiveTargets = resolved
        )
        val groups = graph.sources.filterIsInstance<TopologyNode.RoutedApps>().associateBy { it.target }
        assertEquals(1, groups.getValue(cellular.id).appCount)
        assertEquals(1, groups.getValue(wifi.id).appCount)
        assertEquals(listOf("Work"), graph.activeScenarioNames)
    }

    @Test
    fun laterPlansOverrideSharedUidAndUnresolvedTargetsKeepAnEarlierPlan() {
        val first = app(1).copy(uid = 10_001)
        val shared = app(2).copy(uid = first.uid)
        val work = wifi.copy(ssid = "Office")
        val early = ScenarioProfile("first", "Earlier", priority = 2, trigger = ScenarioTrigger.Always,
            overrides = mapOf(first.ruleKey to ScenarioEngine.symbolicSsid("Office")))
        val later = ScenarioProfile("later", "Later", priority = 8, trigger = ScenarioTrigger.Always,
            overrides = mapOf(shared.ruleKey to CHANNEL_DEFAULT))
        val apps = listOf(first, shared)
        val channels = listOf(work, cellular)
        val state = ScenarioUiState(scenarioProfiles = listOf(later, early))
        assertEquals(mapOf(first.ruleKey to CHANNEL_DEFAULT, shared.ruleKey to CHANNEL_DEFAULT),
            resolveEffectiveAppTargets(apps, channels, state).targetsByRuleKey)

        val unresolved = later.copy(overrides = mapOf(shared.ruleKey to ScenarioEngine.symbolicSsid("Missing")))
        val observed = resolveEffectiveAppTargets(apps, channels, state.copy(scenarioProfiles = listOf(unresolved, early)))
        assertEquals(mapOf(first.ruleKey to wifi.id, shared.ruleKey to wifi.id), observed.targetsByRuleKey)
        assertEquals(listOf("Earlier", "Later"), observed.activeScenarioNames)
    }

    @Test
    fun proxyAppsSourceConnectsToGatewayAsDraft() {
        val graph = topology(
            channels = listOf(wifi),
            proxy = ProxyUpstreamUiState(
                gatewayAppTargets = mapOf("app1" to wifi.id),
                savedGatewayMappings = mapOf(wifi.id to "outbound")
            )
        )
        val source = graph.sources.filterIsInstance<TopologyNode.ProxyApps>().single()
        val relay = graph.relays.filterIsInstance<TopologyNode.ProxyGateway>().single()
        assertEquals(1, source.appCount)
        assertEquals(0, source.unmappedCount)
        assertEquals(
            TopologyEdge(source.id, relay.id, TopologyEdgeStyle.DASHED, TopologyEdgeReason.PROXY_DRAFT),
            graph.edges.single { it.from == source.id }
        )
        val egress = TopologyNode.Physical(wifi)
        val (nodes, edges) = traceConnectedGraph(graph, source.id)
        assertEquals(setOf(source.id, relay.id, egress.id), nodes)
        assertEquals(graph.edges.toSet(), edges)
    }

    @Test
    fun bypassBaseRulesClearsBaseAssignmentsAndKeepsExceptions() {
        val app1 = app(1, wifi.id).copy(uid = 10_001)
        val app2 = app(2, cellular.id).copy(uid = 10_002)
        val home = ScenarioProfile(
            id = "home",
            name = "Home",
            priority = 10,
            trigger = ScenarioTrigger.Always,
            bypassBaseRules = true,
            overrides = mapOf(app2.ruleKey to wifi.id)
        )
        val scenario = ScenarioUiState(scenarioProfiles = listOf(home))
        val resolved = resolveEffectiveAppTargets(listOf(app1, app2), listOf(wifi, cellular), scenario)
        assertEquals(CHANNEL_DEFAULT, resolved.targetsByRuleKey[app1.ruleKey])
        assertEquals(wifi.id, resolved.targetsByRuleKey[app2.ruleKey])
    }

    @Test
    fun traceConnectedGraphIncludesVpnAncestorsAndDescendantsButNotUnrelatedNodes() {
        val unrelated = channel("wlan9")
        val graph = topology(
            channels = listOf(wifi, cellular, vpn, unrelated),
            apps = listOf(app(1), app(2, vpn.id), app(3, unrelated.id)),
            vpnUpstreams = mapOf(vpn.id to VpnUpstreamSnapshot(wifi.id, null))
        )
        val vpnNode = graph.relays.filterIsInstance<TopologyNode.Vpn>().single()
        val (nodes, edges) = traceConnectedGraph(graph, vpnNode.id)
        assertEquals(
            setOf(vpnNode.id, TopologyNode.RoutedApps(vpn.id, 1, vpn).id, TopologyNode.Physical(wifi).id), nodes
        )
        assertEquals(2, edges.size)
        assertTrue(edges.any { it.from == TopologyNode.RoutedApps(vpn.id, 1, vpn).id && it.to == vpnNode.id })
        assertTrue(edges.any { it.from == vpnNode.id && it.to == TopologyNode.Physical(wifi).id })
        assertFalse(nodes.contains(TopologyNode.Physical(unrelated).id))
    }

    private fun channel(iface: String) = NetworkChannel(iface, iface, "WLAN", iface, ipAddress = null)

    private fun app(index: Int, target: String = CHANNEL_DEFAULT) = AppItem(
        packageName = "app$index", appName = "App $index", icon = null, targetChannelId = target, isSystemApp = false
    )

    private fun topology(
        channels: List<NetworkChannel>,
        apps: List<AppItem> = emptyList(),
        vpnUpstreams: Map<String, VpnUpstreamSnapshot> = emptyMap(),
        hotspot: DualWifiStatus = DualWifiStatus(),
        proxy: ProxyUpstreamUiState = ProxyUpstreamUiState(),
        diagnostics: TransparentProxyDiagnostics? = null,
        supportedAndroidVpn: NetworkChannel? = null,
        effectiveTargets: EffectiveAppTargets? = null
    ) = buildNetworkTopology(channels, apps, vpnUpstreams, hotspot, proxy, diagnostics, supportedAndroidVpn, effectiveTargets)

    private fun NetworkTopology.edgeFrom(node: TopologyNode) = edges.single { it.from == node.id }
}

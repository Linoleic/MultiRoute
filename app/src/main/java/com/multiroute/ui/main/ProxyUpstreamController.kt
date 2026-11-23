package com.multiroute.ui.main

import android.content.Context
import android.util.Log
import com.multiroute.data.ProxyUpstreamProfile
import com.multiroute.data.ProxyUpstreamStore
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.RuleTarget
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.util.AndroidVpnGateway
import com.multiroute.util.ClashProxyUpstreamClient
import com.multiroute.util.ClashProxyUpstreamClient.ControllerException
import com.multiroute.util.ClashProxyUpstreamClient.Failure
import com.multiroute.util.ClashProxyUpstreamClient.Selector
import com.multiroute.util.NetworkUtils
import com.multiroute.util.ProxyGatewayExport
import com.multiroute.util.ProxyGatewayFormat
import com.multiroute.util.RouteRuleBuilder
import com.multiroute.util.SuHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class GatewayPreviewSkipReason {
    APP_UNAVAILABLE,
    INVALID_UID,
    CHANNEL_UNAVAILABLE,
    OUTBOUND_UNMAPPED,
    NO_DEFAULT_ROUTE,
    PHYSICAL_ROUTE_ACTIVE,
    VPN_OWNER,
    VPN_CAPTURE_UNKNOWN,
    VPN_APP_EXCLUDED,
    VPN_OUTBOUND_SHARED,
    VPN_WIFI_ONLY,
    SHARED_UID_CONFLICT,
    ROUTE_STATE_UNCERTAIN
}

/** One exact exported UID route, or one saved app choice that could not be exported. */
internal data class GatewayPreviewRow(
    val ruleKey: String?,
    val uid: Int?,
    val channel: String?,
    val outbound: String?,
    val skipReason: GatewayPreviewSkipReason? = null
)

internal data class ProxyUpstreamUiState(
    val profile: ProxyUpstreamProfile? = null,
    val selector: Selector? = null,
    val busy: Boolean = true,
    val failure: Failure? = null,
    val storageFailed: Boolean = false,
    val secretSaved: Boolean = false,
    val savedGatewayMappings: Map<String, String>? = null,
    val gatewayAppTargets: Map<String, String> = emptyMap(),
    val gatewayBusy: Boolean = true
) {
    val gatewayMappings: Map<String, String>
        get() = savedGatewayMappings ?: profile?.mappings.orEmpty()
}

/** A cellular mapping requires the canonical subscription target, which survives interface changes. */
internal fun NetworkChannel.proxyUpstreamKey(): String? = when {
    !isVpnUpstream() -> null
    transportType.equals("cellular", ignoreCase = true) -> subscriptionKey?.takeIf { it == id }
    else -> id
}

internal class ProxyUpstreamController(
    context: Context,
    private val scope: CoroutineScope,
    private val channels: () -> List<NetworkChannel>,
    private val onSelectionConfirmed: () -> Unit
) {
    class AndroidVpnUnavailable : IllegalStateException()

    private data class GatewaySnapshot(
        val fragment: ProxyGatewayExport.Fragment?,
        val activeUpstreamInterfaces: List<String>,
        val rows: List<GatewayPreviewRow>
    )

    data class GatewayExport(
        val fragment: ProxyGatewayExport.Fragment?,
        val possibleBypassInterfaces: List<String>,
        val rows: List<GatewayPreviewRow>
    )

    data class GatewayPreviewRequest(val app: RuleTarget, val preferredFormat: ProxyGatewayFormat?)

    private val application = context.applicationContext
    private val store = ProxyUpstreamStore(context)
    private val _state = MutableStateFlow(ProxyUpstreamUiState())
    val state = _state.asStateFlow()
    private val _gatewayPreviewRequest = MutableStateFlow<GatewayPreviewRequest?>(null)
    val gatewayPreviewRequest = _gatewayPreviewRequest.asStateFlow()
    var secret = ""
        private set

    init {
        scope.launch {
            try {
                val (saved, gateway, appTargets) = withContext(Dispatchers.IO) {
                    Triple(store.read(), store.readGatewayMappings(), store.readGatewayAppTargets())
                }
                secret = saved?.savedSecret.orEmpty()
                _state.value = ProxyUpstreamUiState(
                    profile = saved?.profile, busy = false, secretSaved = saved?.savedSecret != null,
                    savedGatewayMappings = gateway, gatewayAppTargets = appTargets, gatewayBusy = false
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = ProxyUpstreamUiState(busy = false, storageFailed = true, gatewayBusy = false)
            }
        }
    }

    fun refresh() = updateSelection()

    fun requestGatewayPreview(app: RuleTarget, preferredFormat: ProxyGatewayFormat?) {
        _gatewayPreviewRequest.value = GatewayPreviewRequest(app, preferredFormat)
    }

    fun consumeGatewayPreviewRequest(request: GatewayPreviewRequest): Boolean =
        _gatewayPreviewRequest.compareAndSet(request, null)

    fun saveGatewayMappings(mappings: Map<String, String>, onSaved: (Boolean) -> Unit) {
        if (_state.value.gatewayBusy) {
            onSaved(false)
            return
        }
        val normalized = mappings.filterValues { it.isNotBlank() }
        _state.update { it.copy(gatewayBusy = true) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.saveGatewayMappings(normalized) }
                _state.update { it.copy(savedGatewayMappings = normalized) }
                onSaved(true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onSaved(false)
            } finally {
                _state.update { it.copy(gatewayBusy = false) }
            }
        }
    }

    /** Saves an export-only app→channel choice without changing its kernel routing assignment. */
    fun saveGatewayAppTarget(
        ruleKey: String,
        channelId: String?,
        outboundTag: String? = null,
        onSaved: (Boolean) -> Unit
    ) {
        if (_state.value.gatewayBusy || !RouteConfigProvider.isValidRuleKey(ruleKey)) {
            onSaved(false)
            return
        }
        val mappings = _state.value.gatewayMappings
        if (channelId != null && outboundTag == null && channelId !in mappings.keys) {
            onSaved(false)
            return
        }
        _state.update { it.copy(gatewayBusy = true) }
        scope.launch {
            try {
                val next = _state.value.gatewayAppTargets.toMutableMap().apply {
                    if (channelId == null) remove(ruleKey) else put(ruleKey, channelId)
                }.toMap()
                if (channelId != null && outboundTag != null) {
                    require(outboundTag.isNotBlank())
                    val nextMappings = mappings.toMutableMap().apply { put(channelId, outboundTag) }.toMap()
                    withContext(Dispatchers.IO) { store.saveGatewayMappings(nextMappings) }
                    // Persist the mapping first. If saving the app choice then fails, the mapping remains
                    // usable and the existing app choice stays untouched rather than pointing at absent data.
                    _state.update { it.copy(savedGatewayMappings = nextMappings) }
                }
                withContext(Dispatchers.IO) { store.saveGatewayAppTargets(next) }
                _state.update { it.copy(gatewayAppTargets = next) }
                onSaved(true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onSaved(false)
            } finally {
                _state.update { it.copy(gatewayBusy = false) }
            }
        }
    }

    suspend fun prepareGatewayExport(format: ProxyGatewayFormat): GatewayExport {
        val mappings = _state.value.gatewayMappings
        val appTargets = _state.value.gatewayAppTargets
        val androidVpn = format == ProxyGatewayFormat.MIHOMO_ANDROID_VPN
        return withContext(Dispatchers.IO) {
            val snapshot = SuHelper.withRouteConfigLock {
                val active = NetworkUtils.getActiveChannels(application)
                val vpn = if (androidVpn) AndroidVpnGateway.findSupportedVpn(application, active)
                    ?: throw AndroidVpnUnavailable() else null
                val capture = vpn?.let { channel ->
                    val agents = runCatching {
                        SuHelper.executeShellWithOutput("dumpsys connectivity | grep 'NetworkAgentInfo{'", timeoutSeconds = 5)
                    }.getOrDefault(emptyList())
                    AndroidVpnGateway.capturedUids(agents, channel.interfaceName, channel.vpnOwnerUid)
                }
                val gatewayChannels = if (androidVpn) active.filter { it.transportType.equals("WLAN", ignoreCase = true) } else active
                val physicalOutbounds = gatewayChannels.mapNotNull { channel ->
                    val key = channel.proxyUpstreamKey() ?: return@mapNotNull null
                    val member = mappings[key] ?: return@mapNotNull null
                    channel.interfaceName to member
                }.toMap()
                val sharedOutbounds = if (androidVpn) physicalOutbounds.values.groupingBy { it }.eachCount()
                    .filterValues { it > 1 }.keys else emptySet()
                val publishedText = SuHelper.readTextAsRoot(RouteRuleBuilder.RULE_CACHE_PATH)
                val published = RouteRuleBuilder.parseRuleCache(publishedText)
                val result4 = runCatching {
                    SuHelper.executeShellWithOutput(
                        "${RouteRuleBuilder.IP_BIN} -4 rule show pref ${RouteRuleBuilder.DEFAULT_RULE_PREF} && echo __MR_GATEWAY4_OK",
                        timeoutSeconds = 3
                    )
                }.getOrDefault(emptyList())
                val routeStateBaseCertain = "__MR_GATEWAY4_OK" in result4
                val installed4 = if (routeStateBaseCertain) RouteRuleBuilder.parseKernelUidRules(result4) else emptyMap()
                val result6 = if (appTargets.isNotEmpty() && routeStateBaseCertain) {
                    val result6 = runCatching {
                        SuHelper.executeShellWithOutput(
                            "${RouteRuleBuilder.IP_BIN} -6 rule show pref ${RouteRuleBuilder.DEFAULT_RULE_PREF} && echo __MR_GATEWAY6_OK",
                            timeoutSeconds = 3
                        )
                    }.getOrDefault(emptyList())
                    result6.takeIf { "__MR_GATEWAY6_OK" in it }
                } else null
                val routeStateCertain = routeStateBaseCertain && (appTargets.isEmpty() || result6 != null)
                val installed6 = result6?.let { RouteRuleBuilder.parseKernelUidRules(it, includeUnreachable = true) }.orEmpty()

                val baseRules = if (appTargets.isNotEmpty()) RouteConfigProvider.getUidRules(application) else emptyMap()
                val secondaryInstalls = if (appTargets.isNotEmpty()) {
                    runCatching { SuHelper.listSecondaryUserInstalls() }.getOrDefault(emptyList())
                } else emptyList()
                val scenario = if (appTargets.isNotEmpty()) {
                    SuHelper.resolveScenarioProfile(application, baseRules)
                } else null

                val allChannelsByKey = active.mapNotNull { channel ->
                    channel.proxyUpstreamKey()?.let { it to channel }
                }.toMap()
                val channelIfaces = gatewayChannels.mapNotNull { channel ->
                    val key = channel.proxyUpstreamKey() ?: return@mapNotNull null
                    key to channel.interfaceName
                }.toMap()
                val defaultRouteIfaces = if (appTargets.isNotEmpty()) runCatching {
                    SuHelper.getInterfacesWithDefaultRoute(
                        channelIfaces.values.filter { it in physicalOutbounds.keys }.distinct()
                    )
                }.getOrDefault(emptySet()) else emptySet()
                val defaultRouteCandidates = mutableMapOf<Int, MutableSet<String>>()
                val candidateKeys = mutableMapOf<Pair<Int, String>, MutableList<String>>()
                val skipped = mutableListOf<GatewayPreviewRow>()
                val ruleKeysByUid = mutableMapOf<Int, String>()
                for ((ruleKey, channelId) in appTargets) {
                    fun skip(reason: GatewayPreviewSkipReason, uid: Int? = null, channel: String? = channelId) {
                        skipped += GatewayPreviewRow(ruleKey, uid, channel, mappings[channelId], reason)
                    }
                    if (!RouteConfigProvider.isValidRuleKey(ruleKey)) {
                        skip(GatewayPreviewSkipReason.APP_UNAVAILABLE)
                        continue
                    }
                    val uid = SuHelper.resolveUidForRuleKey(application, ruleKey, secondaryInstalls)
                    if (uid < 0) {
                        skip(GatewayPreviewSkipReason.APP_UNAVAILABLE)
                        continue
                    }
                    if (!RouteRuleBuilder.isValidUid(uid)) {
                        skip(GatewayPreviewSkipReason.INVALID_UID, uid)
                        continue
                    }
                    ruleKeysByUid.putIfAbsent(uid, ruleKey)
                    val channel = allChannelsByKey[channelId]
                    if (channel == null) {
                        skip(GatewayPreviewSkipReason.CHANNEL_UNAVAILABLE, uid)
                        continue
                    }
                    if (androidVpn && !channel.transportType.equals("WLAN", ignoreCase = true)) {
                        skip(GatewayPreviewSkipReason.VPN_WIFI_ONLY, uid, channel.interfaceName)
                        continue
                    }
                    val channelIface = channel.interfaceName
                    val outbound = physicalOutbounds[channelIface]
                    if (outbound == null) {
                        skip(GatewayPreviewSkipReason.OUTBOUND_UNMAPPED, uid, channelIface)
                        continue
                    }
                    if (outbound in sharedOutbounds) {
                        skip(GatewayPreviewSkipReason.VPN_OUTBOUND_SHARED, uid, channelIface)
                        continue
                    }
                    if (!routeStateCertain || (publishedText == null && uid in baseRules)) {
                        skip(GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN, uid, channelIface)
                        continue
                    }
                    if (active.any { it.isVpn && it.vpnOwnerUid == uid }) {
                        skip(GatewayPreviewSkipReason.VPN_OWNER, uid, channelIface)
                        continue
                    }
                    val publishedIface = published[uid]
                    val installedIface = installed4[uid]
                    val effective = scenario?.effectiveRules?.get(uid)
                    // An explicit VPN target is eligible only when its tunnel route is installed.
                    if (vpn != null && (effective == vpn.id || effective == vpn.interfaceName)) {
                        val ipv6 = installed6[uid]
                        if (publishedIface != vpn.interfaceName || installedIface != vpn.interfaceName ||
                            (ipv6 != null && ipv6 != vpn.interfaceName && ipv6 != "unreachable")
                        ) {
                            skip(GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN, uid, channelIface)
                            continue
                        }
                    } else {
                        if (uid in installed6) {
                            skip(GatewayPreviewSkipReason.PHYSICAL_ROUTE_ACTIVE, uid, channelIface)
                            continue
                        }
                        if (publishedIface != null || installedIface != null) {
                            val reason = if (publishedIface != null && publishedIface == installedIface) {
                                GatewayPreviewSkipReason.PHYSICAL_ROUTE_ACTIVE
                            } else GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN
                            skip(reason, uid, channelIface)
                            continue
                        }
                        if (effective != null && effective != CHANNEL_DEFAULT) {
                            skip(GatewayPreviewSkipReason.PHYSICAL_ROUTE_ACTIVE, uid, channelIface)
                            continue
                        }
                    }
                    if (vpn != null) {
                        if (capture == null) {
                            skip(GatewayPreviewSkipReason.VPN_CAPTURE_UNKNOWN, uid, channelIface)
                            continue
                        }
                        if (capture.none { uid in it }) {
                            skip(GatewayPreviewSkipReason.VPN_APP_EXCLUDED, uid, channelIface)
                            continue
                        }
                    }
                    if (channelIface !in defaultRouteIfaces) {
                        skip(GatewayPreviewSkipReason.NO_DEFAULT_ROUTE, uid, channelIface)
                        continue
                    }
                    defaultRouteCandidates.getOrPut(uid) { mutableSetOf() }.add(channelIface)
                    candidateKeys.getOrPut(uid to channelIface) { mutableListOf() }.add(ruleKey)
                }
                val defaultRouteTargets = defaultRouteCandidates.mapNotNull { (uid, ifaces) ->
                    if (ifaces.size != 1) {
                        Log.w("ProxyUpstreamController", "Skipping gateway app UID $uid with conflicting channel selections: $ifaces")
                        for (iface in ifaces) {
                            candidateKeys[uid to iface].orEmpty().forEach { key ->
                                skipped += GatewayPreviewRow(key, uid, iface, physicalOutbounds[iface], GatewayPreviewSkipReason.SHARED_UID_CONFLICT)
                            }
                        }
                        null
                    } else uid to ifaces.single()
                }.toMap()

                val fragment = ProxyGatewayExport.build(
                    publishedRules = if (androidVpn) emptyMap() else published,
                    installedRules = if (androidVpn) emptyMap() else installed4,
                    physicalOutbounds = physicalOutbounds,
                    format = format,
                    defaultRouteInterfaces = defaultRouteTargets
                )
                val serializedUids = fragment?.rules.orEmpty().mapTo(mutableSetOf()) { it.uid }
                val activeIfaces = active.mapTo(mutableSetOf()) { it.interfaceName }
                if (!androidVpn) {
                    skipped += planPhysicalSkippedRows(
                        published = published,
                        installed4 = installed4,
                        serializedUids = serializedUids,
                        existingSkipped = skipped,
                        ruleKeysByUid = ruleKeysByUid,
                        activeIfaces = activeIfaces,
                        physicalOutbounds = physicalOutbounds,
                        routeStateCertain = routeStateCertain
                    )
                }
                val included = fragment?.rules.orEmpty().map { rule ->
                    val ruleKey = candidateKeys[rule.uid to rule.interfaceName]?.firstOrNull()
                    GatewayPreviewRow(ruleKey, rule.uid, rule.interfaceName, rule.outbound)
                }
                GatewaySnapshot(fragment, active.filter { it.isVpnUpstream() }.map { it.interfaceName }, included + skipped)
            }
            val fragment = snapshot.fragment
            if (fragment == null || fragment.directInterfaces.isEmpty()) {
                return@withContext GatewayExport(fragment, emptyList(), snapshot.rows)
            }
            val diagnostics = try {
                SuHelper.getTransparentProxyDiagnostics(snapshot.activeUpstreamInterfaces)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ProxyUpstreamController", "Could not check transparent proxy coverage for export", error)
                null
            }
            val possibleBypassInterfaces = fragment.directInterfaces.filter { diagnostics?.mayBypassOn(it) == true }.sorted()
            GatewayExport(fragment, possibleBypassInterfaces, snapshot.rows)
        }
    }

    fun select(channelKey: String) {
        if (channels().none { it.proxyUpstreamKey() == channelKey }) return
        val group = _state.value.selector ?: return
        val member = _state.value.profile?.mappings?.get(channelKey) ?: return
        if (member in group.members) updateSelection(member)
    }

    private fun updateSelection(member: String? = null) {
        val current = _state.value
        val profile = current.profile ?: return
        if (current.busy) return
        val client = ClashProxyUpstreamClient(profile.port, secret)
        _state.update { it.copy(busy = true, failure = null, storageFailed = false) }
        scope.launch {
            try {
                val selected = withContext(Dispatchers.IO) {
                    if (member == null) client.read(profile.group) else client.select(profile.group, member)
                }
                _state.update { it.copy(selector = selected) }
                if (member != null) onSelectionConfirmed()
            } catch (error: ControllerException) {
                _state.update { it.copy(selector = null, failure = error.failure) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun save(profile: ProxyUpstreamProfile, secret: String, rememberSecret: Boolean, onSaved: () -> Unit) {
        if (_state.value.busy) return
        val savedSecret = secret.takeIf { rememberSecret && it.isNotEmpty() }
        _state.update { it.copy(busy = true, storageFailed = false, failure = null) }
        scope.launch {
            try {
                val selector = withContext(Dispatchers.IO) {
                    val readback = ClashProxyUpstreamClient(profile.port, secret).read(profile.group)
                    if (!readback.members.containsAll(profile.mappings.values)) throw ControllerException(Failure.INVALID_SELECTOR)
                    store.save(profile, savedSecret)
                    readback
                }
                this@ProxyUpstreamController.secret = secret
                _state.update { it.copy(profile = profile, selector = selector, secretSaved = savedSecret != null) }
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ControllerException) {
                _state.update { it.copy(failure = error.failure) }
            } catch (_: Exception) {
                _state.update { it.copy(storageFailed = true) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun remove(onRemoved: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, storageFailed = false, failure = null) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.clear() }
                secret = ""
                _state.update { it.copy(profile = null, selector = null, failure = null, secretSaved = false) }
                onRemoved()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(storageFailed = true) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

internal fun planPhysicalSkippedRows(
    published: Map<Int, String>,
    installed4: Map<Int, String>,
    serializedUids: Set<Int>,
    existingSkipped: List<GatewayPreviewRow>,
    ruleKeysByUid: Map<Int, String>,
    activeIfaces: Set<String>,
    physicalOutbounds: Map<String, String>,
    routeStateCertain: Boolean
): List<GatewayPreviewRow> {
    val rows = mutableListOf<GatewayPreviewRow>()
    for (uid in (published.keys + installed4.keys).sorted()) {
        if (uid in serializedUids) continue
        val publishedIface = published[uid]
        val installedIface = installed4[uid]
        val iface = publishedIface ?: installedIface
        val reason = when {
            !RouteRuleBuilder.isValidUid(uid) -> GatewayPreviewSkipReason.INVALID_UID
            !routeStateCertain -> GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN
            publishedIface != null && publishedIface != installedIface -> GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN
            installedIface != null && installedIface != publishedIface -> GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN
            iface == null || iface !in activeIfaces -> GatewayPreviewSkipReason.CHANNEL_UNAVAILABLE
            iface !in physicalOutbounds -> GatewayPreviewSkipReason.OUTBOUND_UNMAPPED
            else -> GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN
        }
        val alreadyExplained = existingSkipped.any {
            it.uid == uid && (
                it.skipReason == GatewayPreviewSkipReason.INVALID_UID ||
                    it.skipReason == GatewayPreviewSkipReason.VPN_OWNER ||
                    (it.skipReason == GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN &&
                        reason == GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN) ||
                    (it.skipReason == reason && it.channel == iface)
                )
        }
        if (alreadyExplained) continue
        rows += GatewayPreviewRow(ruleKeysByUid[uid], uid, iface, iface?.let(physicalOutbounds::get), reason)
    }
    return rows
}

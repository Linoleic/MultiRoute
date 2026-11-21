package com.multiroute.ui.main

import android.content.Context
import android.util.Log
import com.multiroute.data.ProxyUpstreamProfile
import com.multiroute.data.ProxyUpstreamStore
import com.multiroute.data.RouteConfigProvider
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
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
    data class GatewayExport(val fragment: ProxyGatewayExport.Fragment, val possibleBypassInterfaces: List<String>)

    private val application = context.applicationContext
    private val store = ProxyUpstreamStore(context)
    private val _state = MutableStateFlow(ProxyUpstreamUiState())
    val state = _state.asStateFlow()
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

    fun saveGatewayMappings(mappings: Map<String, String>, onSaved: (Boolean) -> Unit) {
        if (_state.value.gatewayBusy) return
        _state.update { it.copy(gatewayBusy = true) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.saveGatewayMappings(mappings) }
                _state.update { it.copy(savedGatewayMappings = mappings) }
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
    fun saveGatewayAppTarget(ruleKey: String, channelId: String?, onSaved: (Boolean) -> Unit) {
        if (_state.value.gatewayBusy || !RouteConfigProvider.isValidRuleKey(ruleKey)) {
            onSaved(false)
            return
        }
        val mappings = _state.value.gatewayMappings
        if (channelId != null && channelId !in mappings.keys) {
            onSaved(false)
            return
        }
        _state.update { it.copy(gatewayBusy = true) }
        scope.launch {
            try {
                val next = _state.value.gatewayAppTargets.toMutableMap().apply {
                    if (channelId == null) remove(ruleKey) else put(ruleKey, channelId)
                }.toMap()
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

    suspend fun prepareGatewayExport(format: ProxyGatewayFormat): GatewayExport? {
        val mappings = _state.value.gatewayMappings
        val appTargets = _state.value.gatewayAppTargets
        if (mappings.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val (fragment, activeInterfaces) = SuHelper.withRouteConfigLock {
                val active = NetworkUtils.getActiveChannels(application)
                val physicalOutbounds = active.mapNotNull { channel ->
                    val key = channel.proxyUpstreamKey() ?: return@mapNotNull null
                    val member = mappings[key] ?: return@mapNotNull null
                    channel.interfaceName to member
                }.toMap()
                val publishedText = SuHelper.readTextAsRoot(RouteRuleBuilder.RULE_CACHE_PATH)
                val published = RouteRuleBuilder.parseRuleCache(publishedText)
                val result4 = SuHelper.executeShellWithOutput(
                    "ip -4 rule show pref ${RouteRuleBuilder.DEFAULT_RULE_PREF} && echo __MR_GATEWAY4_OK",
                    timeoutSeconds = 3
                )
                check("__MR_GATEWAY4_OK" in result4)
                val installed4 = RouteRuleBuilder.parseKernelUidRules(result4)
                val installed6 = if (appTargets.isNotEmpty()) {
                    val result6 = SuHelper.executeShellWithOutput(
                        "ip -6 rule show pref ${RouteRuleBuilder.DEFAULT_RULE_PREF} && echo __MR_GATEWAY6_OK",
                        timeoutSeconds = 3
                    )
                    check("__MR_GATEWAY6_OK" in result6)
                    RouteRuleBuilder.parseKernelUidRules(result6, includeUnreachable = true)
                } else emptyMap()

                val baseRules = if (appTargets.isNotEmpty()) RouteConfigProvider.getUidRules(application) else emptyMap()
                val secondaryInstalls = if (appTargets.isNotEmpty()) {
                    runCatching { SuHelper.listSecondaryUserInstalls() }.getOrDefault(emptyList())
                } else emptyList()
                val scenario = if (appTargets.isNotEmpty()) {
                    SuHelper.resolveScenarioProfile(application, baseRules)
                } else null

                // A local proxy choice is applicable only to an app whose effective routing intent is
                // system-default. An override that cannot currently be resolved is not proof of default.
                val channelIfaces = active.mapNotNull { channel ->
                    val key = channel.proxyUpstreamKey() ?: return@mapNotNull null
                    key to channel.interfaceName
                }.toMap()
                val defaultRouteIfaces = if (appTargets.isNotEmpty()) SuHelper.getInterfacesWithDefaultRoute(
                    channelIfaces.values.filter { it in physicalOutbounds.keys }.distinct()
                ) else emptySet()
                val defaultRouteCandidates = mutableMapOf<Int, MutableSet<String>>()
                for ((ruleKey, channelId) in appTargets) {
                    if (!RouteConfigProvider.isValidRuleKey(ruleKey)) continue
                    val channelIface = channelIfaces[channelId] ?: continue
                    if (channelIface !in defaultRouteIfaces) continue
                    val uid = SuHelper.resolveUidForRuleKey(application, ruleKey, secondaryInstalls)
                    if (!RouteRuleBuilder.isValidUid(uid)) continue
                    if (uid in installed6) continue
                    if (active.any { it.isVpn && it.vpnOwnerUid == uid }) continue
                    if (publishedText == null && uid in baseRules) continue
                    val effective = scenario?.effectiveRules?.get(uid)
                    if (effective != null && effective != CHANNEL_DEFAULT) continue
                    defaultRouteCandidates.getOrPut(uid) { mutableSetOf() }.add(channelIface)
                }
                val defaultRouteTargets = defaultRouteCandidates.mapNotNull { (uid, ifaces) ->
                    if (ifaces.size != 1) {
                        Log.w("ProxyUpstreamController", "Skipping gateway app UID $uid with conflicting channel selections: $ifaces")
                        null
                    } else uid to ifaces.single()
                }.toMap()

                val fragment = ProxyGatewayExport.build(
                    publishedRules = published,
                    installedRules = installed4,
                    physicalOutbounds = physicalOutbounds,
                    format = format,
                    defaultRouteInterfaces = defaultRouteTargets
                ) ?: return@withRouteConfigLock null
                fragment to active.filter { it.isVpnUpstream() }.map { it.interfaceName }
            } ?: return@withContext null
            if (fragment.directInterfaces.isEmpty()) return@withContext GatewayExport(fragment, emptyList())
            val diagnostics = try {
                SuHelper.getTransparentProxyDiagnostics(activeInterfaces)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ProxyUpstreamController", "Could not check transparent proxy coverage for export", error)
                null
            }
            val possibleBypassInterfaces = fragment.directInterfaces.filter { diagnostics?.mayBypassOn(it) == true }.sorted()
            GatewayExport(fragment, possibleBypassInterfaces)
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

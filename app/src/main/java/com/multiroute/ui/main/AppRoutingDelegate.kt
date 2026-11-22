package com.multiroute.ui.main

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.RuleTarget
import com.multiroute.data.ScenarioStore
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.util.NetworkUtils
import com.multiroute.util.RouteRuleBuilder
import com.multiroute.util.SuHelper
import com.multiroute.util.VpnRouteLoopGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages installed app enumeration, search/filter state, multi-selection,
 * per-app/batch channel assignment, and kernel rule synchronization.
 */
internal class AppRoutingDelegate(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiStateFlow: MutableStateFlow<MainUiState>,
    private val onRefreshEnvironment: () -> Unit,
    private val onRefreshScenario: () -> Unit,
    private val onPlanOverrideValue: (String) -> String,
    private val onAddBatchScenarioOverrides: (String, Set<String>, String) -> Unit,
    private val onFinishScenarioEdit: () -> Unit
) {
    private var allApps: List<AppItem> = emptyList()
    private var packageNamesByUid: Map<Int, List<String>> = emptyMap()
    private var loadAppsJob: Job? = null
    private var vpnUpstreamJob: Job? = null

    private companion object {
        const val TAG = "MultiRoute-AppScan"
        const val PROBE_PACKAGE = "com.multiroute.probe"
    }

    fun transformAllApps(transform: (AppItem) -> AppItem) {
        allApps = allApps.map(transform)
    }

    fun getAllAppsSnapshot(): List<AppItem> = allApps

    fun isVpnSelfLoop(targetChannel: NetworkChannel?, app: AppItem): Boolean {
        if (targetChannel?.isVpn != true) return false
        return VpnRouteLoopGuard.shouldSkip(
            isVpn = true,
            ownerUid = targetChannel.vpnOwnerUid,
            candidateUid = app.uid,
            candidatePackageNames = packageNamesByUid[app.uid] ?: listOf(app.packageName),
            vpnOwnerPackageNames = packageNamesByUid[targetChannel.vpnOwnerUid].orEmpty()
        )
    }

    fun blockedBatchVpnCounts(channels: List<NetworkChannel>, selectedRuleKeys: Set<String>): Map<String, Int> {
        val vpnChannels = channels.filter { it.isVpn }
        if (vpnChannels.isEmpty()) return emptyMap()
        val selectedApps = allApps.filter { it.ruleKey in selectedRuleKeys }
        return vpnChannels.associate { channel ->
            channel.id to selectedApps.count { isVpnSelfLoop(channel, it) }
        }
    }

    fun enterSelectionMode(initialPackage: String? = null) {
        val selected = if (initialPackage != null) setOf(initialPackage) else emptySet()
        uiStateFlow.update {
            it.copy(routing = it.routing.copy(isSelectionMode = true, selectedRuleKeys = selected))
        }
    }

    fun exitSelectionMode() {
        // Leaving the selection also ends scenario edit mode: everything assigned there is already stored
        // in the plan, so nothing is lost, and the app list goes back to the base configuration.
        if (uiStateFlow.value.scenario.scenarioEditId != null) {
            onFinishScenarioEdit()
            return
        }
        uiStateFlow.update {
            it.copy(
                routing = it.routing.copy(
                    isSelectionMode = false,
                    selectedRuleKeys = emptySet(),
                    showBatchAssignSheet = false
                )
            )
        }
    }

    fun toggleSelectApp(ruleKey: String) {
        uiStateFlow.update { state ->
            val current = state.routing.selectedRuleKeys.toMutableSet()
            if (current.contains(ruleKey)) current.remove(ruleKey) else current.add(ruleKey)
            state.copy(routing = state.routing.copy(selectedRuleKeys = current))
        }
    }

    fun selectAllFilteredApps() {
        uiStateFlow.update { state ->
            val currentFiltered = state.routing.apps.map { it.ruleKey }.toSet()
            val currentSelected = state.routing.selectedRuleKeys
            val allSelected = currentFiltered.isNotEmpty() && currentFiltered.all { it in currentSelected }
            val newSelected = if (allSelected) {
                currentSelected - currentFiltered
            } else {
                currentSelected + currentFiltered
            }
            state.copy(routing = state.routing.copy(selectedRuleKeys = newSelected))
        }
    }

    fun openBatchAssignSheet() {
        if (uiStateFlow.value.routing.selectedRuleKeys.isEmpty()) return
        uiStateFlow.update { it.copy(routing = it.routing.copy(showBatchAssignSheet = true)) }
    }

    fun closeBatchAssignSheet() {
        uiStateFlow.update { it.copy(routing = it.routing.copy(showBatchAssignSheet = false)) }
    }

    fun batchAssignChannel(channelId: String) {
        val state = uiStateFlow.value
        val channels = state.channelProbe.channels
        if (channelId != CHANNEL_DEFAULT && channels.firstOrNull { it.id == channelId }?.isHotspot == true) {
            return
        }
        val targetChannel = channels.firstOrNull { it.id == channelId }
        val rawKeys = state.routing.selectedRuleKeys
        val keys = if (targetChannel?.isVpn == true) {
            rawKeys.filter { key ->
                val app = allApps.firstOrNull { it.ruleKey == key }
                app == null || !isVpnSelfLoop(targetChannel, app)
            }.toSet()
        } else {
            rawKeys
        }
        // Selection is keyed by rule key (`pkg` or `pkg@userId`), so a clone-space install and the
        // primary install of the same package can be assigned independently.
        if (keys.isEmpty()) {
            if (rawKeys.isNotEmpty()) {
                uiStateFlow.update {
                    it.copy(snackBarMessage = application.getString(R.string.routing_batch_vpn_unavailable))
                }
            }
            return
        }

        // While a plan is being edited the selection lands in that plan instead of the base configuration -
        // that is what edit mode is for, and it keeps the routing screen meaning "the default plan".
        state.scenario.scenarioEditId?.let { editingId ->
            onAddBatchScenarioOverrides(editingId, keys, channelId)
            return
        }

        val targets = allApps.filter { it.ruleKey in keys }.map { RuleTarget(it.ruleKey, it.uid) }
        RouteConfigProvider.setTargetChannels(application, targets, channelId)

        allApps = allApps.map {
            if (it.ruleKey in keys) it.copy(targetChannelId = channelId) else it
        }

        val channelName = when {
            channelId == CHANNEL_DEFAULT -> application.getString(R.string.vm_system_default)
            else -> targetChannel?.let { NetworkUtils.channelLabel(application, it) } ?: channelId
        }

        val count = targets.size
        uiStateFlow.update {
            it.copy(
                routing = it.routing.copy(
                    isSelectionMode = false,
                    selectedRuleKeys = emptySet(),
                    showBatchAssignSheet = false
                ),
                snackBarMessage = application.getString(R.string.vm_batch_assigned, count, channelName)
            )
        }

        filterApps()

        scope.launch {
            uiStateFlow.update { it.copy(routing = it.routing.copy(isSyncing = true)) }
            val ok = SuHelper.syncAllRouteRules(application)
            uiStateFlow.update {
                it.copy(
                    routing = it.routing.copy(isSyncing = false),
                    snackBarMessage = if (ok) application.getString(R.string.vm_batch_ok, count) else application.getString(R.string.vm_batch_pending, count)
                )
            }
            onRefreshEnvironment()
        }
    }

    fun setSearchQuery(query: String) {
        uiStateFlow.update { it.copy(routing = it.routing.copy(searchQuery = query)) }
        filterApps()
    }

    fun clearSearchAndChannelFilter() {
        uiStateFlow.update { it.copy(routing = it.routing.copy(searchQuery = "", filterChannelId = null)) }
        filterApps()
    }

    fun toggleShowSystemApps() {
        uiStateFlow.update { it.copy(routing = it.routing.copy(showSystemApps = !it.routing.showSystemApps)) }
        filterApps()
    }

    fun setFilterChannelId(channelId: String?) {
        uiStateFlow.update { it.copy(routing = it.routing.copy(filterChannelId = channelId)) }
        filterApps()
    }

    fun updateRouteChannel(app: AppItem, channelId: String) {
        val state = uiStateFlow.value
        val channels = state.channelProbe.channels
        if (channelId != CHANNEL_DEFAULT && channels.firstOrNull { it.id == channelId }?.isHotspot == true) {
            return
        }
        val targetChannel = channels.firstOrNull { it.id == channelId }
        if (isVpnSelfLoop(targetChannel, app)) {
            uiStateFlow.update {
                val message = if (targetChannel?.vpnOwnerUid == app.uid) R.string.channel_vpn_self_forbidden
                else R.string.ui_vpn_loop_blocked
                it.copy(snackBarMessage = application.getString(message))
            }
            return
        }

        // Same rule as the batch path: while editing a plan, a single assignment belongs to that plan.
        state.scenario.scenarioEditId?.let { editingId ->
            val profile = ScenarioStore.load(application).firstOrNull { it.id == editingId }
            if (profile != null) {
                val overrides = profile.overrides.toMutableMap()
                overrides[app.ruleKey] = onPlanOverrideValue(channelId)
                ScenarioStore.upsert(application, profile.copy(overrides = overrides))
                allApps = allApps.map {
                    if (it.ruleKey == app.ruleKey) it.copy(targetChannelId = channelId) else it
                }
                uiStateFlow.update {
                    it.copy(
                        navigation = it.navigation.copy(selectedAppForSheet = null),
                        snackBarMessage = application.getString(R.string.scenario_override_added, 1, profile.name)
                    )
                }
                filterApps()
                onRefreshScenario()
                scope.launch { withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(application) } }
                return
            }
        }

        saveBaseRoute(RuleTarget(app.ruleKey, app.uid), channelId)
    }

    fun updateVpnUpstream(vpn: NetworkChannel, expectedOwner: RuleTarget, channelId: String) {
        vpnUpstreamJob?.cancel()
        vpnUpstreamJob = scope.launch {
            val target = withContext(Dispatchers.IO) {
                try {
                    val channels = NetworkUtils.getActiveChannels(application)
                    val stillActive = channels.any {
                        it.isVpn && it.id == vpn.id && it.interfaceName == vpn.interfaceName &&
                            it.vpnOwnerUid == vpn.vpnOwnerUid
                    }
                    val validUpstream = channelId == CHANNEL_DEFAULT ||
                        channels.any { it.id == channelId && it.isVpnUpstream() }
                    if (stillActive && validUpstream && RouteConfigProvider.isValidChannelId(channelId)) {
                        resolveVpnOwnerTarget(application, vpn.vpnOwnerUid)?.takeIf { it == expectedOwner }
                    } else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not verify VPN upstream selection", e)
                    null
                }
            }
            if (target == null) {
                uiStateFlow.update {
                    it.copy(snackBarMessage = application.getString(R.string.ui_vpn_upstream_changed))
                }
                return@launch
            }
            saveBaseRoute(target, channelId)
        }
    }

    private fun saveBaseRoute(target: RuleTarget, channelId: String) {
        RouteConfigProvider.setTargetChannel(application, target, channelId)
        if (uiStateFlow.value.scenarioEditId == null) {
            allApps = allApps.map {
                if (it.ruleKey == target.key) it.copy(targetChannelId = channelId) else it
            }
            uiStateFlow.update { state ->
                val app = state.selectedAppForSheet
                if (app?.ruleKey == target.key) {
                    state.copy(navigation = state.navigation.copy(selectedAppForSheet = app.copy(targetChannelId = channelId)))
                } else state
            }
            filterApps()
        }

        scope.launch {
            uiStateFlow.update { it.copy(routing = it.routing.copy(isSyncing = true)) }
            val ok = SuHelper.syncAllRouteRules(application)
            uiStateFlow.update {
                it.copy(
                    routing = it.routing.copy(isSyncing = false),
                    snackBarMessage = if (ok) application.getString(R.string.vm_sync_ok) else application.getString(R.string.vm_sync_pending)
                )
            }
            onRefreshEnvironment()
        }
    }

    fun forceSyncAllRules() {
        scope.launch {
            uiStateFlow.update { it.copy(routing = it.routing.copy(isSyncing = true)) }
            val ok = SuHelper.syncAllRouteRules(application)
            uiStateFlow.update {
                it.copy(
                    routing = it.routing.copy(isSyncing = false),
                    snackBarMessage = if (ok) application.getString(R.string.vm_kernel_ok) else application.getString(R.string.vm_sync_failed)
                )
            }
            onRefreshEnvironment()
        }
    }

    fun clearAllRules() {
        scope.launch {
            RouteConfigProvider.clearAllRules(application)
            allApps = allApps.map { it.copy(targetChannelId = CHANNEL_DEFAULT) }
            filterApps()
            SuHelper.syncAllRouteRules(application)
            uiStateFlow.update {
                it.copy(snackBarMessage = application.getString(R.string.vm_cleared))
            }
            onRefreshEnvironment()
        }
    }

    fun loadInstalledApps() {
        loadAppsJob?.cancel()
        loadAppsJob = scope.launch {
            if (allApps.isEmpty() || uiStateFlow.value.routing.isInstalledAppsIncomplete) {
                uiStateFlow.update { it.copy(routing = it.routing.copy(isLoadingApps = true)) }
            }

            val pm = application.packageManager
            val previousSecondary = allApps.filter { it.userId > 0 }
            val (loaded, enumerationIncomplete, secondaryScanFailed) = withContext(Dispatchers.IO) {
                val packageMap = mutableMapOf<String, AppItem>()
                var launcherScanFailed = false
                var installedAppsScanFailed = false

                try {
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                    for (ri in resolveInfos) {
                        val pkg = ri.activityInfo.packageName
                        if (pkg == application.packageName) continue
                        val appName = ri.loadLabel(pm).toString()
                        val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                        val isSystem = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val uid = ri.activityInfo.applicationInfo.uid

                        packageMap[pkg] = AppItem(
                            packageName = pkg,
                            appName = appName,
                            icon = icon,
                            uid = uid,
                            targetChannelId = CHANNEL_DEFAULT,
                            isSystemApp = isSystem
                        )
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    launcherScanFailed = true
                    Log.w(TAG, "Launcher activity enumeration failed: ${t.message}")
                }

                try {
                    val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (app in installed) {
                        if (app.packageName == application.packageName) continue
                        if (!packageMap.containsKey(app.packageName)) {
                            val appName = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                            val icon = runCatching { pm.getApplicationIcon(app) }.getOrNull()
                            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                            packageMap[app.packageName] = AppItem(
                                packageName = app.packageName,
                                appName = appName,
                                icon = icon,
                                uid = app.uid,
                                targetChannelId = CHANNEL_DEFAULT,
                                isSystemApp = isSystem
                            )
                        }
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    installedAppsScanFailed = true
                    Log.w(TAG, "Installed package enumeration failed: ${t.message}")
                }

                // OEM clone spaces (Xiaomi XSpace = user 999) and work profiles
                var secondaryFailed = false
                try {
                    val scan = SuHelper.querySecondaryUserInstalls()
                    if (scan.succeeded) {
                        for (installs in scan.installs) {
                            for ((pkg, uid) in installs.packageUids) {
                                if (pkg == application.packageName) continue
                                val key = RouteRuleBuilder.ruleKey(pkg, installs.userId)
                                if (packageMap.containsKey(key)) continue
                                val base = packageMap[pkg]
                                packageMap[key] = AppItem(
                                    packageName = pkg,
                                    appName = "${base?.appName ?: pkg} (${installs.userId})",
                                    icon = base?.icon,
                                    uid = uid,
                                    targetChannelId = CHANNEL_DEFAULT,
                                    isSystemApp = base?.isSystemApp ?: false,
                                    userId = installs.userId
                                )
                            }
                        }
                    } else {
                        secondaryFailed = true
                        for (cached in previousSecondary) {
                            packageMap.putIfAbsent(cached.ruleKey, cached)
                        }
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    secondaryFailed = true
                    Log.w(TAG, "Secondary-user enumeration failed: ${t.message}", t)
                    for (cached in previousSecondary) {
                        packageMap.putIfAbsent(cached.ruleKey, cached)
                    }
                }

                // Treat any failed source as a partial snapshot. The launcher query can
                // still provide a fallback list when installed-app access is restricted,
                // and retained clone entries are useful but cannot prove that scan complete.
                val incomplete = launcherScanFailed || installedAppsScanFailed || secondaryFailed
                Triple(packageMap.values.toList(), incomplete, secondaryFailed)
            }

            if (secondaryScanFailed) {
                Log.w(TAG, "Retained ${previousSecondary.size} cached clone app(s) after secondary-user scan failure")
            }

            val currentRules = RouteConfigProvider.getAllRules(application)
            packageNamesByUid = loaded.groupBy({ it.uid }, { it.packageName })
            allApps = loaded.map {
                it.copy(targetChannelId = currentRules[it.ruleKey] ?: CHANNEL_DEFAULT)
            }.sortedWith(
                compareByDescending<AppItem> { it.packageName == PROBE_PACKAGE }
                    .thenBy { if (it.packageName == PROBE_PACKAGE) it.userId else 0 }
                    .thenByDescending { it.targetChannelId != CHANNEL_DEFAULT }
                    .thenBy { it.isSystemApp }
                    .thenBy { it.appName.lowercase() }
            )
            filterApps()
            uiStateFlow.update {
                it.copy(routing = it.routing.copy(
                    isLoadingApps = false,
                    isInstalledAppsIncomplete = enumerationIncomplete
                ))
            }
        }
    }

    fun filterApps() {
        uiStateFlow.update { state ->
            val routing = state.routing
            val query = routing.searchQuery.trim().lowercase()
            val showSys = routing.showSystemApps
            val filterChannel = routing.filterChannelId
            val channels = state.channelProbe.channels

            val filtered = allApps.filter { app ->
                val matchesQuery = query.isEmpty() ||
                    app.appName.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query) ||
                    app.ruleKey.lowercase().contains(query)
                val matchesSys = showSys || !app.isSystemApp
                val matchesChannel = when (filterChannel) {
                    null -> true
                    "configured" -> app.targetChannelId != CHANNEL_DEFAULT
                    else -> app.targetChannelId == filterChannel || channels.any {
                        it.id == filterChannel && it.interfaceName == app.targetChannelId
                    }
                }
                matchesQuery && matchesSys && matchesChannel
            }

            val configuredCount = allApps.count { it.targetChannelId != CHANNEL_DEFAULT }

            state.copy(
                routing = routing.copy(
                    apps = filtered,
                    totalAppsCount = allApps.size,
                    configuredAppsCount = configuredCount
                )
            )
        }
    }
}

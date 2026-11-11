package com.multiroute.ui.main

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
    private var loadAppsJob: Job? = null

    fun transformAllApps(transform: (AppItem) -> AppItem) {
        allApps = allApps.map(transform)
    }

    fun getAllAppsSnapshot(): List<AppItem> = allApps

    fun isVpnSelfLoop(targetChannel: NetworkChannel?, app: AppItem): Boolean {
        if (targetChannel?.isVpn != true) return false
        return (targetChannel.vpnOwnerUid > 0 && targetChannel.vpnOwnerUid == app.uid) ||
            app.packageName.contains("clash", ignoreCase = true) ||
            app.packageName.contains("v2ray", ignoreCase = true) ||
            app.packageName.contains("wireguard", ignoreCase = true) ||
            app.packageName.contains("singbox", ignoreCase = true) ||
            app.packageName.contains("nekobox", ignoreCase = true) ||
            app.packageName.contains("shadowsocks", ignoreCase = true)
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
        if (keys.isEmpty()) return

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
                it.copy(snackBarMessage = application.getString(R.string.channel_vpn_self_forbidden))
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

        RouteConfigProvider.setTargetChannel(
            application,
            RuleTarget(app.ruleKey, app.uid),
            channelId
        )

        allApps = allApps.map {
            if (it.ruleKey == app.ruleKey) it.copy(targetChannelId = channelId) else it
        }

        uiStateFlow.update { s ->
            val currentSheetApp = s.navigation.selectedAppForSheet
            val updatedSheetApp = if (currentSheetApp?.ruleKey == app.ruleKey) {
                currentSheetApp.copy(targetChannelId = channelId)
            } else {
                currentSheetApp
            }
            s.copy(navigation = s.navigation.copy(selectedAppForSheet = updatedSheetApp))
        }

        filterApps()

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
            if (allApps.isEmpty()) {
                uiStateFlow.update { it.copy(routing = it.routing.copy(isLoadingApps = true)) }
            }

            val pm = application.packageManager
            val loaded = withContext(Dispatchers.IO) {
                val packageMap = mutableMapOf<String, AppItem>()

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
                } catch (_: Exception) {}

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
                } catch (_: Exception) {}

                // OEM clone spaces (Xiaomi XSpace = user 999) and work profiles
                try {
                    for (installs in SuHelper.listSecondaryUserInstalls()) {
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
                } catch (_: Exception) {}

                packageMap.values.toList()
            }

            val currentRules = RouteConfigProvider.getAllRules(application)
            allApps = loaded.map {
                it.copy(targetChannelId = currentRules[it.ruleKey] ?: CHANNEL_DEFAULT)
            }.sortedWith(
                compareByDescending<AppItem> { it.targetChannelId != CHANNEL_DEFAULT }
                    .thenBy { it.isSystemApp }
                    .thenBy { it.appName.lowercase() }
            )
            filterApps()
            uiStateFlow.update { it.copy(routing = it.routing.copy(isLoadingApps = false)) }
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

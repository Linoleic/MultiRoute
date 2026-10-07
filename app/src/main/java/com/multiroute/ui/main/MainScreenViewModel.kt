package com.multiroute.ui.main

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.RuleTarget
import com.multiroute.data.TestServerManager
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.model.TestServerConfig
import com.multiroute.util.DiagnosticInfo
import com.multiroute.util.NetworkUtils
import com.multiroute.data.ScenarioStore
import com.multiroute.model.ScenarioObservation
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.util.ScenarioEngine
import com.multiroute.util.SuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val selectedTab: Int = 0, // 0: 应用分流, 1: 网络通道, 2: 模块与设置
    val isLoadingApps: Boolean = true,
    val isRootGranted: Boolean = false,
    val isMobileDataAlwaysOn: Boolean = false,
    val isKeepSlaveWifiScreenOff: Boolean = false,
    val channels: List<NetworkChannel> = emptyList(),
    val testResults: Map<String, String> = emptyMap(),
    val isTesting: Boolean = false,
    val apps: List<AppItem> = emptyList(),
    val totalAppsCount: Int = 0,
    val configuredAppsCount: Int = 0,
    val searchQuery: String = "",
    val showSystemApps: Boolean = false,
    val filterChannelId: String? = null,
    val selectedAppForSheet: AppItem? = null,
    val selectedChannelForDetail: NetworkChannel? = null,
    val isTestingSingleChannel: Boolean = false,
    val isSyncing: Boolean = false,
    val snackBarMessage: String? = null,
    val diagnosticInfo: DiagnosticInfo? = null,
    val testServerConfig: TestServerConfig = TestServerConfig(),
    val showTestServerDialog: Boolean = false,
    val showLogsDialog: Boolean = false,
    val isLoadingLogs: Boolean = false,
    val logsContent: String = "",
    // 批量通道分配多选状态
    val isSelectionMode: Boolean = false,
    val selectedRuleKeys: Set<String> = emptySet(),
    val showBatchAssignSheet: Boolean = false,
    // 场景方案
    val scenarioProfiles: List<ScenarioProfile> = emptyList(),
    val activeScenarioId: String? = null,
    /** Every plan that is applying right now; more than one when several conditions match at once. */
    val activeScenarioIds: Set<String> = emptySet(),
    val activeScenarioName: String? = null,
    val activeScenarioTrigger: ScenarioTrigger? = null,
    val activeScenarioSsid: String? = null,
    val activeScenarioManual: Boolean = false,
    val manualScenarioId: String = "",
    val showScenarioSheet: Boolean = false,
    val showScenarioSaveDialog: Boolean = false,
    /** id of the plan whose overrides are being edited; null when assignments go to the base. */
    val scenarioEditId: String? = null,
    val scenarioDetailsId: String? = null,
    val showScenarioDetailsDialog: Boolean = false,
    // 配置导入导出
    val configPath: String = "",
    val pendingImportJson: String? = null,
    val showConfigImportConfirm: Boolean = false,
    val isConfigBusy: Boolean = false
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var allApps: List<AppItem> = emptyList()
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var lastNetworkChangeTime = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChanged()
        }

        override fun onLost(network: Network) {
            handleNetworkChanged()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            handleNetworkChanged()
        }
    }

    private fun handleNetworkChanged() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < 1500L) return
        lastNetworkChangeTime = now
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val activeChannels = withContext(Dispatchers.IO) { NetworkUtils.getActiveChannels(context) }
                _uiState.value = _uiState.value.copy(channels = activeChannels)
                refreshScenarioState()
                withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(context) }
            } catch (_: Exception) {}
        }
    }

    init {
        loadTestServerConfig()
        refreshEnvironment()
        loadInstalledApps()
        try {
            val request = NetworkRequest.Builder().build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    private fun loadTestServerConfig() {
        val config = TestServerManager.getConfig(getApplication())
        _uiState.value = _uiState.value.copy(testServerConfig = config)
    }

    fun openTestServerDialog() {
        _uiState.value = _uiState.value.copy(showTestServerDialog = true)
    }

    fun closeTestServerDialog() {
        _uiState.value = _uiState.value.copy(showTestServerDialog = false)
    }

    fun updateTestServerConfig(presetId: String, customUrl: String? = null) {
        val context = getApplication<Application>()
        val newConfig = TestServerManager.saveConfig(context, presetId, customUrl)
        _uiState.value = _uiState.value.copy(
            testServerConfig = newConfig,
            showTestServerDialog = false,
            snackBarMessage = getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_test_node_set, newConfig.displayName(getApplication()))
        )
    }

    fun setSelectedTab(index: Int) {
        _uiState.value = _uiState.value.copy(
            selectedTab = index,
            isSelectionMode = if (index != 0) false else _uiState.value.isSelectionMode,
            selectedRuleKeys = if (index != 0) emptySet() else _uiState.value.selectedRuleKeys,
            showBatchAssignSheet = if (index != 0) false else _uiState.value.showBatchAssignSheet
        )
        if (index == 2) {
            refreshEnvironment()
        }
    }

    fun enterSelectionMode(initialPackage: String? = null) {
        val selected = if (initialPackage != null) setOf(initialPackage) else emptySet()
        _uiState.value = _uiState.value.copy(
            isSelectionMode = true,
            selectedRuleKeys = selected
        )
    }

    fun exitSelectionMode() {
        // Leaving the selection also ends scenario edit mode: everything assigned there is already stored
        // in the plan, so nothing is lost, and the app list goes back to the base configuration.
        if (_uiState.value.scenarioEditId != null) {
            finishEditingScenario()
            return
        }
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedRuleKeys = emptySet(),
            showBatchAssignSheet = false
        )
    }

    fun toggleSelectApp(ruleKey: String) {
        val current = _uiState.value.selectedRuleKeys.toMutableSet()
        if (current.contains(ruleKey)) {
            current.remove(ruleKey)
        } else {
            current.add(ruleKey)
        }
        _uiState.value = _uiState.value.copy(selectedRuleKeys = current)
    }

    fun selectAllFilteredApps() {
        val currentFiltered = _uiState.value.apps.map { it.ruleKey }.toSet()
        val currentSelected = _uiState.value.selectedRuleKeys
        val allSelected = currentFiltered.isNotEmpty() && currentFiltered.all { it in currentSelected }
        val newSelected = if (allSelected) {
            currentSelected - currentFiltered
        } else {
            currentSelected + currentFiltered
        }
        _uiState.value = _uiState.value.copy(selectedRuleKeys = newSelected)
    }

    fun openBatchAssignSheet() {
        if (_uiState.value.selectedRuleKeys.isEmpty()) return
        _uiState.value = _uiState.value.copy(showBatchAssignSheet = true)
    }

    fun closeBatchAssignSheet() {
        _uiState.value = _uiState.value.copy(showBatchAssignSheet = false)
    }

    fun batchAssignChannel(channelId: String) {
        // Selection is keyed by rule key (`pkg` or `pkg@userId`), so a clone-space install and the
        // primary install of the same package can be assigned independently.
        val keys = _uiState.value.selectedRuleKeys
        if (keys.isEmpty()) return

        // While a plan is being edited the selection lands in that plan instead of the base configuration -
        // that is what edit mode is for, and it keeps the routing screen meaning "the default plan".
        _uiState.value.scenarioEditId?.let { editingId ->
            addOverridesToScenario(editingId, keys, channelId)
            return
        }

        val context = getApplication<Application>()
        val targets = allApps.filter { it.ruleKey in keys }
            .map { RuleTarget(it.ruleKey, it.uid) }
        RouteConfigProvider.setTargetChannels(context, targets, channelId)

        allApps = allApps.map {
            if (it.ruleKey in keys) it.copy(targetChannelId = channelId) else it
        }

        val channelName = when {
            channelId == CHANNEL_DEFAULT -> getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_system_default)
            else -> _uiState.value.channels.firstOrNull { it.id == channelId }?.let { NetworkUtils.channelLabel(getApplication(), it.transportType, it.interfaceName, it.ssid) } ?: channelId
        }

        val count = targets.size
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedRuleKeys = emptySet(),
            showBatchAssignSheet = false,
            snackBarMessage = getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_batch_assigned, count, channelName)
        )

        filterApps()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val ok = SuHelper.syncAllRouteRules(context)
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                snackBarMessage = if (ok) getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_batch_ok, count) else getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_batch_pending, count)
            )
            refreshEnvironment()
        }
    }

    fun openAppSheet(app: AppItem) {
        _uiState.value = _uiState.value.copy(selectedAppForSheet = app)
    }

    fun closeAppSheet() {
        _uiState.value = _uiState.value.copy(selectedAppForSheet = null)
    }

    fun selectChannelForDetail(channel: NetworkChannel) {
        _uiState.value = _uiState.value.copy(selectedChannelForDetail = channel)
    }

    fun closeChannelDetail() {
        _uiState.value = _uiState.value.copy(selectedChannelForDetail = null)
    }

    fun testSingleChannel(channel: NetworkChannel) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingSingleChannel = true)
            try {
                val result = NetworkUtils.testSingleChannelPublicIp(
                    context = getApplication(),
                    channel = channel,
                    config = _uiState.value.testServerConfig
                )
                val updated = _uiState.value.testResults.toMutableMap()
                updated[channel.id] = result
                _uiState.value = _uiState.value.copy(
                    testResults = updated,
                    snackBarMessage = getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_egress_done, channel.interfaceName)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(snackBarMessage = getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_egress_failed, channel.interfaceName, e.message ?: ""))
            } finally {
                _uiState.value = _uiState.value.copy(isTestingSingleChannel = false)
            }
        }
    }

    // ------------------------------------------------------------------ 场景方案

    fun openScenarioSheet() {
        refreshScenarioState()
        _uiState.value = _uiState.value.copy(showScenarioSheet = true)
    }

    fun closeScenarioSheet() {
        _uiState.value = _uiState.value.copy(showScenarioSheet = false)
    }

    fun openScenarioSaveDialog() {
        _uiState.value = _uiState.value.copy(showScenarioSheet = false, showScenarioSaveDialog = true)
    }

    fun closeScenarioSaveDialog() {
        _uiState.value = _uiState.value.copy(showScenarioSaveDialog = false)
    }

    /** Pins a profile by hand; it then applies whatever the current links look like. */
    fun applyScenarioManually(id: String) {
        val context = getApplication<Application>()
        ScenarioStore.setManualId(context, id)
        refreshScenarioState()
        _uiState.value = _uiState.value.copy(
            showScenarioSheet = false,
            snackBarMessage = context.getString(com.multiroute.R.string.scenario_applied)
        )
        syncRules()
    }

    /** Back to automatic selection by trigger. */
    fun clearManualScenario() {
        val context = getApplication<Application>()
        ScenarioStore.setManualId(context, null)
        refreshScenarioState()
        _uiState.value = _uiState.value.copy(
            showScenarioSheet = false,
            snackBarMessage = context.getString(com.multiroute.R.string.scenario_automatic)
        )
        syncRules()
    }

    fun deleteScenario(id: String) {
        val context = getApplication<Application>()
        ScenarioStore.delete(context, id)
        refreshScenarioState()
        syncRules()
    }

    /**
     * Saves the current per-app assignments as a new profile. The routing screen keeps meaning "the
     * default plan"; a profile only carries the apps it should override.
     */
    fun saveCurrentAssignmentsAsScenario(name: String, trigger: ScenarioTrigger) {
        val context = getApplication<Application>()
        val overrides = com.multiroute.data.RouteConfigProvider.getAllRules(context)
        if (name.isBlank() || overrides.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                showScenarioSaveDialog = false,
                snackBarMessage = context.getString(com.multiroute.R.string.scenario_save_failed)
            )
            return
        }
        ScenarioStore.upsert(
            context,
            ScenarioProfile(
                id = "s" + System.currentTimeMillis(),
                name = name.trim(),
                trigger = trigger,
                overrides = overrides
            )
        )
        refreshScenarioState()
        _uiState.value = _uiState.value.copy(
            showScenarioSaveDialog = false,
            snackBarMessage = context.getString(com.multiroute.R.string.scenario_saved, name.trim())
        )
    }

    // ------------------------------------------------------------------ 配置导入导出

    private fun clipboard(): android.content.ClipboardManager? =
        getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager

    /** Writes a timestamped JSON export plus a stable-named copy of the same document. */
    fun exportConfigToFile() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConfigBusy = true)
            val now = System.currentTimeMillis()
            val json = withContext(Dispatchers.IO) {
                com.multiroute.data.ConfigTransfer.buildJson(context, now)
            }
            val path = com.multiroute.data.ConfigTransfer.exportPath(now)
            val ok = withContext(Dispatchers.IO) {
                SuHelper.writeTextAsRoot(context, path, json) &&
                        SuHelper.writeTextAsRoot(
                            context,
                            "${com.multiroute.data.ConfigTransfer.EXPORT_DIR}/MultiRoute-config.json",
                            json
                        )
            }
            _uiState.value = _uiState.value.copy(
                isConfigBusy = false,
                configPath = path,
                snackBarMessage = context.getString(
                    if (ok) com.multiroute.R.string.config_exported
                    else com.multiroute.R.string.config_export_failed,
                    path
                )
            )
        }
    }

    fun exportConfigToClipboard() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                com.multiroute.data.ConfigTransfer.buildJson(context, System.currentTimeMillis())
            }
            clipboard()?.setPrimaryClip(android.content.ClipData.newPlainText("MultiRoute config", json))
            _uiState.value = _uiState.value.copy(
                snackBarMessage = context.getString(com.multiroute.R.string.config_copied)
            )
        }
    }

    fun requestImportFromClipboard() {
        val context = getApplication<Application>()
        val text = clipboard()?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        stageImport(text)
    }

    fun requestImportFromFile(path: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConfigBusy = true, configPath = path)
            val text = withContext(Dispatchers.IO) { SuHelper.readTextAsRoot(path) }
            _uiState.value = _uiState.value.copy(isConfigBusy = false)
            if (text.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    snackBarMessage = context.getString(com.multiroute.R.string.config_read_failed, path)
                )
            } else {
                stageImport(text)
            }
        }
    }

    /** Holds a document until the user confirms; nothing is touched before that. */
    private fun stageImport(json: String) {
        val context = getApplication<Application>()
        if (!json.contains(com.multiroute.data.ConfigTransfer.FORMAT)) {
            _uiState.value = _uiState.value.copy(
                snackBarMessage = context.getString(com.multiroute.R.string.config_invalid)
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            pendingImportJson = json,
            showConfigImportConfirm = true
        )
    }

    fun cancelConfigImport() {
        _uiState.value = _uiState.value.copy(
            pendingImportJson = null,
            showConfigImportConfirm = false
        )
    }

    /** Replaces the configuration with the staged document; the replaced one is backed up first. */
    fun confirmConfigImport() {
        val context = getApplication<Application>()
        val json = _uiState.value.pendingImportJson ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConfigBusy = true,
                showConfigImportConfirm = false,
                pendingImportJson = null
            )
            val result = withContext(Dispatchers.IO) {
                com.multiroute.data.ConfigTransfer.apply(context, json, System.currentTimeMillis())
            }
            withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(context) }
            loadInstalledApps()
            refreshScenarioState()
            _uiState.value = if (result == null) {
                _uiState.value.copy(
                    isConfigBusy = false,
                    snackBarMessage = context.getString(com.multiroute.R.string.config_invalid)
                )
            } else {
                _uiState.value.copy(
                    isConfigBusy = false,
                    snackBarMessage = context.getString(
                        com.multiroute.R.string.config_imported,
                        result.assignments,
                        result.scenarios,
                        result.skipped
                    )
                )
            }
        }
    }

    private fun syncRules() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(getApplication()) }
        }
    }

    /** Enters "edit this plan's overrides" mode: selections then land in the plan, not in the base. */
    fun startEditingScenario(id: String) {
        val context = getApplication<Application>()
        val profile = ScenarioStore.load(context).firstOrNull { it.id == id } ?: return
        allApps = allApps.map { app ->
            val override = profile.overrides[app.ruleKey]
            if (override != null) app.copy(targetChannelId = override) else app
        }
        _uiState.value = _uiState.value.copy(
            showScenarioSheet = false,
            scenarioEditId = id,
            isSelectionMode = true,
            selectedRuleKeys = profile.overrides.keys,
            snackBarMessage = context.getString(com.multiroute.R.string.scenario_editing, profile.name)
        )
        filterApps()
    }

    /** Leaves edit mode and puts the app list back on the base assignments. */
    fun finishEditingScenario() {
        val context = getApplication<Application>()
        val base = com.multiroute.data.RouteConfigProvider.getAllRules(context)
        allApps = allApps.map { app ->
            app.copy(targetChannelId = base[app.ruleKey] ?: com.multiroute.model.CHANNEL_DEFAULT)
        }
        _uiState.value = _uiState.value.copy(
            scenarioEditId = null,
            isSelectionMode = false,
            selectedRuleKeys = emptySet(),
            showBatchAssignSheet = false
        )
        filterApps()
        refreshScenarioState()
    }

    private fun addOverridesToScenario(id: String, ruleKeys: Set<String>, channelId: String) {
        val context = getApplication<Application>()
        val profile = ScenarioStore.load(context).firstOrNull { it.id == id } ?: return
        val overrides = profile.overrides.toMutableMap()
        ruleKeys.forEach { key -> overrides[key] = channelId }
        ScenarioStore.upsert(context, profile.copy(overrides = overrides))
        allApps = allApps.map { if (it.ruleKey in ruleKeys) it.copy(targetChannelId = channelId) else it }
        _uiState.value = _uiState.value.copy(
            showBatchAssignSheet = false,
            snackBarMessage = context.getString(
                com.multiroute.R.string.scenario_override_added, ruleKeys.size, profile.name
            )
        )
        filterApps()
        refreshScenarioState()
        syncRules()
    }

    /** Moves a plan up or down in the order that decides which of several matching plans wins. */
    fun moveScenario(id: String, delta: Int) {
        val context = getApplication<Application>()
        val list = ScenarioStore.load(context).toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (to == from) return
        list.add(to, list.removeAt(from))
        // Renumber so the stored order *is* the evaluation order, spaced so a manual priority edit stays
        // possible without colliding with the neighbours.
        ScenarioStore.save(
            context,
            list.mapIndexed { index, profile -> profile.copy(priority = (index + 1) * 10) }
        )
        _uiState.value = _uiState.value.copy(
            snackBarMessage = context.getString(com.multiroute.R.string.scenario_moved)
        )
        refreshScenarioState()
        syncRules()
    }

    fun openScenarioDetails(id: String) {
        _uiState.value = _uiState.value.copy(
            showScenarioSheet = false,
            showScenarioDetailsDialog = true,
            scenarioDetailsId = id
        )
    }

    fun closeScenarioDetails() {
        _uiState.value = _uiState.value.copy(
            showScenarioDetailsDialog = false,
            scenarioDetailsId = null
        )
    }

    /** Renames a plan and/or changes the condition it matches on. */
    fun saveScenarioDetails(id: String, name: String, trigger: ScenarioTrigger) {
        val context = getApplication<Application>()
        val profile = ScenarioStore.load(context).firstOrNull { it.id == id } ?: return
        if (name.isBlank()) return
        ScenarioStore.upsert(context, profile.copy(name = name.trim(), trigger = trigger))
        _uiState.value = _uiState.value.copy(
            showScenarioDetailsDialog = false,
            scenarioDetailsId = null,
            snackBarMessage = context.getString(com.multiroute.R.string.scenario_updated, name.trim())
        )
        refreshScenarioState()
        syncRules()
    }

    /** Which profile applies right now, for the UI only - the sync evaluates this itself. */
    private fun refreshScenarioState() {
        val context = getApplication<Application>()
        val profiles = ScenarioStore.load(context)
        val manualId = ScenarioStore.getManualId(context)
        val observation = ScenarioObservation.fromChannels(_uiState.value.channels)
        val selected = ScenarioEngine.selectProfiles(profiles, observation, manualId)
        val profile = selected.firstOrNull()
        val trigger = profile?.trigger
        _uiState.value = _uiState.value.copy(
            scenarioProfiles = profiles,
            manualScenarioId = manualId,
            activeScenarioIds = selected.map { it.id }.toSet(),
            activeScenarioId = profile?.id,
            activeScenarioName = profile?.name,
            activeScenarioTrigger = trigger,
            activeScenarioSsid = trigger?.let { ScenarioEngine.matchedSsid(it, observation) },
            activeScenarioManual = profile != null && manualId == profile.id
        )
    }

    fun dismissSnackBar() {        _uiState.value = _uiState.value.copy(snackBarMessage = null)
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val diag = withContext(Dispatchers.IO) { SuHelper.getDiagnosticInfo(context) }
                val activeChannels = withContext(Dispatchers.IO) { NetworkUtils.getActiveChannels(context) }

                _uiState.value = _uiState.value.copy(
                    isRootGranted = diag.isRootGranted,
                    isMobileDataAlwaysOn = diag.mobileDataAlwaysOn,
                    isKeepSlaveWifiScreenOff = diag.isKeepSlaveWifiScreenOff,
                    channels = activeChannels,
                    diagnosticInfo = diag
                )
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error refreshing environment", e)
            }
        }
    }

    fun openLogsDialog() {
        _uiState.value = _uiState.value.copy(showLogsDialog = true, isLoadingLogs = true, logsContent = getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_log_loading))
        viewModelScope.launch {
            try {
                val logs = withContext(Dispatchers.IO) { SuHelper.getDiagnosticLogs(getApplication()) }
                _uiState.value = _uiState.value.copy(isLoadingLogs = false, logsContent = logs)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingLogs = false, logsContent = getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_log_failed, e.message ?: ""))
            }
        }
    }

    fun closeLogsDialog() {
        _uiState.value = _uiState.value.copy(showLogsDialog = false)
    }

    fun toggleKeepSlaveWifiScreenOff() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val current = _uiState.value.isKeepSlaveWifiScreenOff
            val next = !current
            SuHelper.setKeepSlaveWifiScreenOff(context, next)
            _uiState.value = _uiState.value.copy(
                isKeepSlaveWifiScreenOff = next,
                snackBarMessage = if (next) getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_keep_on) else getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_keep_off)
            )
            refreshEnvironment()
        }
    }

    fun toggleMobileDataAlwaysOn() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val current = _uiState.value.isMobileDataAlwaysOn
            val success = if (current) {
                SuHelper.disableMobileDataAlwaysOn(context)
            } else {
                SuHelper.enableMobileDataAlwaysOn(context)
            }
            if (success) {
                refreshEnvironment()
                _uiState.value = _uiState.value.copy(
                    snackBarMessage = if (!current) getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_cellular_on) else getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_cellular_off)
                )
            }
        }
    }

    fun runChannelTests() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResults = emptyMap())
            try {
                val currentChannels = _uiState.value.channels.ifEmpty {
                    NetworkUtils.getActiveChannels(getApplication())
                }
                val results = NetworkUtils.testChannelsConcurrently(
                    context = getApplication(),
                    channels = currentChannels,
                    config = _uiState.value.testServerConfig
                )
                _uiState.value = _uiState.value.copy(
                    testResults = results,
                    snackBarMessage = getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_diag_done)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(snackBarMessage = getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_diag_failed, e.message ?: ""))
            } finally {
                _uiState.value = _uiState.value.copy(isTesting = false)
            }
            refreshEnvironment()
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        filterApps()
    }

    fun toggleShowSystemApps() {
        _uiState.value = _uiState.value.copy(showSystemApps = !_uiState.value.showSystemApps)
        filterApps()
    }

    fun setFilterChannelId(channelId: String?) {
        _uiState.value = _uiState.value.copy(filterChannelId = channelId)
        filterApps()
    }

    fun updateRouteChannel(app: AppItem, channelId: String) {
        val context = getApplication<Application>()

        // Same rule as the batch path: while editing a plan, a single assignment belongs to that plan.
        _uiState.value.scenarioEditId?.let { editingId ->
            val profile = ScenarioStore.load(context).firstOrNull { it.id == editingId }
            if (profile != null) {
                val overrides = profile.overrides.toMutableMap()
                overrides[app.ruleKey] = channelId
                ScenarioStore.upsert(context, profile.copy(overrides = overrides))
                allApps = allApps.map {
                    if (it.ruleKey == app.ruleKey) it.copy(targetChannelId = channelId) else it
                }
                _uiState.value = _uiState.value.copy(
                    selectedAppForSheet = null,
                    snackBarMessage = context.getString(
                        com.multiroute.R.string.scenario_override_added, 1, profile.name
                    )
                )
                filterApps()
                refreshScenarioState()
                syncRules()
                return
            }
        }
        RouteConfigProvider.setTargetChannel(
            context,
            RuleTarget(app.ruleKey, app.uid),
            channelId
        )

        allApps = allApps.map {
            if (it.ruleKey == app.ruleKey) it.copy(targetChannelId = channelId) else it
        }

        if (_uiState.value.selectedAppForSheet?.ruleKey == app.ruleKey) {
            _uiState.value = _uiState.value.copy(
                selectedAppForSheet = _uiState.value.selectedAppForSheet?.copy(targetChannelId = channelId)
            )
        }

        filterApps()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val ok = SuHelper.syncAllRouteRules(context)
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                snackBarMessage = if (ok) getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_sync_ok) else getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_sync_pending)
            )
            refreshEnvironment()
        }
    }

    fun forceSyncAllRules() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val ok = SuHelper.syncAllRouteRules(context)
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                snackBarMessage = if (ok) getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_kernel_ok) else getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_sync_failed)
            )
            refreshEnvironment()
        }
    }

    fun clearAllRules() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            RouteConfigProvider.clearAllRules(context)
            allApps = allApps.map { it.copy(targetChannelId = CHANNEL_DEFAULT) }
            filterApps()
            SuHelper.syncAllRouteRules(context)
            _uiState.value = _uiState.value.copy(snackBarMessage = getApplication<android.app.Application>().getString(com.multiroute.R.string.vm_cleared))
            refreshEnvironment()
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingApps = true)

            val context = getApplication<Application>()
            val pm = context.packageManager
            val currentRules = RouteConfigProvider.getAllRules(context)

            val loaded = withContext(Dispatchers.IO) {
                val packageMap = mutableMapOf<String, AppItem>()

                try {
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                    for (ri in resolveInfos) {
                        val pkg = ri.activityInfo.packageName
                        if (pkg == context.packageName) continue
                        val appName = ri.loadLabel(pm).toString()
                        val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                        val isSystem = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val targetChannel = currentRules[pkg] ?: CHANNEL_DEFAULT
                        val uid = ri.activityInfo.applicationInfo.uid

                        packageMap[pkg] = AppItem(
                            packageName = pkg,
                            appName = appName,
                            icon = icon,
                            uid = uid,
                            targetChannelId = targetChannel,
                            isSystemApp = isSystem
                        )
                    }
                } catch (_: Exception) {}

                try {
                    val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (app in installed) {
                        if (app.packageName == context.packageName) continue
                        if (!packageMap.containsKey(app.packageName)) {
                            val appName = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                            val icon = runCatching { pm.getApplicationIcon(app) }.getOrNull()
                            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            val targetChannel = currentRules[app.packageName] ?: CHANNEL_DEFAULT

                            packageMap[app.packageName] = AppItem(
                                packageName = app.packageName,
                                appName = appName,
                                icon = icon,
                                uid = app.uid,
                                targetChannelId = targetChannel,
                                isSystemApp = isSystem
                            )
                        }
                    }
                } catch (_: Exception) {}

                // OEM clone spaces (Xiaomi XSpace = user 999) and work profiles: list them as their own
                // entries so the clone can be routed independently from the primary install.
                try {
                    for (installs in SuHelper.listSecondaryUserInstalls()) {
                        for ((pkg, uid) in installs.packageUids) {
                            if (pkg == context.packageName) continue
                            val key = com.multiroute.util.RouteRuleBuilder.ruleKey(pkg, installs.userId)
                            if (packageMap.containsKey(key)) continue
                            val base = packageMap[pkg]
                            packageMap[key] = AppItem(
                                packageName = pkg,
                                // The numeric space id is shown instead of the user name: XSpace is "999"
                                // internally, which is unambiguous and needs no name parsing.
                                appName = "${base?.appName ?: pkg} (${installs.userId})",
                                icon = base?.icon,
                                uid = uid,
                                targetChannelId = currentRules[key] ?: CHANNEL_DEFAULT,
                                isSystemApp = base?.isSystemApp ?: false,
                                userId = installs.userId
                            )
                        }
                    }
                } catch (_: Exception) {}

                packageMap.values.sortedWith(
                    compareByDescending<AppItem> { it.targetChannelId != CHANNEL_DEFAULT }
                        .thenBy { it.isSystemApp }
                        .thenBy { it.appName.lowercase() }
                )
            }

            allApps = loaded
            filterApps()
            _uiState.value = _uiState.value.copy(isLoadingApps = false)
        }
    }

    private fun filterApps() {
        val query = _uiState.value.searchQuery.trim().lowercase()
        val showSys = _uiState.value.showSystemApps
        val filterChannel = _uiState.value.filterChannelId

        val filtered = allApps.filter { app ->
            val matchesQuery = query.isEmpty() ||
                    app.appName.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query) ||
                    app.ruleKey.lowercase().contains(query)
            val matchesSys = showSys || !app.isSystemApp
            val matchesChannel = when (filterChannel) {
                null -> true
                "configured" -> app.targetChannelId != CHANNEL_DEFAULT
                else -> app.targetChannelId == filterChannel
            }
            matchesQuery && matchesSys && matchesChannel
        }

        val configuredCount = allApps.count { it.targetChannelId != CHANNEL_DEFAULT }

        _uiState.value = _uiState.value.copy(
            apps = filtered,
            totalAppsCount = allApps.size,
            configuredAppsCount = configuredCount
        )
    }
}

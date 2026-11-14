package com.multiroute.ui.main

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.multiroute.data.ConfigTransfer
import com.multiroute.model.AppItem
import com.multiroute.model.NetworkChannel
import com.multiroute.model.ScenarioTrigger
import com.multiroute.util.ChannelTrafficStats
import com.multiroute.wifi.DualWifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Presentation ViewModel serving as a thin Facade for the MainScreen tabs and overlays.
 * Business domains are delegated to cohesive controllers:
 * - [AppRoutingDelegate]: App scanning, searching, selection, and rule binding
 * - [ScenarioDelegate]: Profile management, condition evaluation, and overrides
 * - [ConfigTransferDelegate]: Backup JSON export, validation, and transactional import
 * - [EnvironmentAndChannelDelegate]: Network status monitoring, diagnostics, egress tests, and cellular SIM control
 */
class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val supportsCellular =
        application.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
            (Build.VERSION.SDK_INT >= 33 && application.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA))

    private val _uiState = MutableStateFlow(
        MainUiState(env = SystemEnvUiState(supportsCellularData = supportsCellular))
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _cellularControlState = MutableStateFlow(CellularControlUiState())
    val cellularControlState: StateFlow<CellularControlUiState> = _cellularControlState.asStateFlow()

    private val _channelTraffic = MutableStateFlow<Map<String, ChannelTrafficStats>>(emptyMap())
    val channelTraffic: StateFlow<Map<String, ChannelTrafficStats>> = _channelTraffic.asStateFlow()

    private val envDelegate: EnvironmentAndChannelDelegate by lazy {
        EnvironmentAndChannelDelegate(
            application = getApplication(),
            scope = viewModelScope,
            uiStateFlow = _uiState,
            cellularControlStateFlow = _cellularControlState,
            channelTrafficFlow = _channelTraffic,
            onRefreshScenario = { scenarioDelegate.refreshScenarioState() }
        )
    }

    private val appRoutingDelegate: AppRoutingDelegate by lazy {
        AppRoutingDelegate(
            application = getApplication(),
            scope = viewModelScope,
            uiStateFlow = _uiState,
            onRefreshEnvironment = { envDelegate.refreshEnvironment() },
            onRefreshScenario = { scenarioDelegate.refreshScenarioState() },
            onPlanOverrideValue = { channelId -> scenarioDelegate.planOverrideValue(channelId) },
            onAddBatchScenarioOverrides = { editingId, keys, channelId ->
                scenarioDelegate.addOverridesToScenario(editingId, keys, channelId)
            },
            onFinishScenarioEdit = { scenarioDelegate.finishEditingScenario() }
        )
    }

    private val scenarioDelegate: ScenarioDelegate by lazy {
        ScenarioDelegate(
            application = getApplication(),
            scope = viewModelScope,
            uiStateFlow = _uiState,
            transformAllApps = { transform -> appRoutingDelegate.transformAllApps(transform) },
            onFilterApps = { appRoutingDelegate.filterApps() }
        )
    }

    private val configDelegate: ConfigTransferDelegate by lazy {
        ConfigTransferDelegate(
            application = getApplication(),
            scope = viewModelScope,
            uiStateFlow = _uiState,
            onReloadAfterImport = {
                appRoutingDelegate.loadInstalledApps()
                scenarioDelegate.refreshScenarioState()
                envDelegate.loadTestServerConfig()
                envDelegate.refreshEnvironment()
            }
        )
    }

    init {
        envDelegate.loadTestServerConfig()
        envDelegate.refreshEnvironment()
        envDelegate.registerNetworkCallbacks()
    }

    fun refreshInstalledApps() = appRoutingDelegate.loadInstalledApps()

    override fun onCleared() {
        super.onCleared()
        envDelegate.unregisterNetworkCallbacks()
    }

    // ------------------------------------------------------------------ Cellular Control
    suspend fun observeChannelTraffic() = envDelegate.observeChannelTraffic()
    suspend fun refreshCellularControlState() = envDelegate.refreshCellularControlState()
    fun setCellularDetailsExpanded(expanded: Boolean) = envDelegate.setCellularDetailsExpanded(expanded)
    fun setCellularPreferredMode(mode: Int) = envDelegate.setCellularPreferredMode(mode)
    fun selectCellularMode(mode: Int) = envDelegate.selectCellularMode(mode)

    // ------------------------------------------------------------------ Navigation & Dialogs
    fun openTestServerDialog() {
        _uiState.update { it.copy(navigation = it.navigation.copy(showTestServerDialog = true)) }
    }

    fun closeTestServerDialog() {
        _uiState.update { it.copy(navigation = it.navigation.copy(showTestServerDialog = false)) }
    }

    fun updateTestServerConfig(presetId: String, customUrl: String? = null) =
        envDelegate.updateTestServerConfig(presetId, customUrl)

    fun setSelectedTab(index: Int) {
        _uiState.update { state ->
            state.copy(
                navigation = state.navigation.copy(
                    selectedTab = index,
                    requestedNetworkTool = null,
                    networkToolReturnTab = null
                ),
                routing = if (index != 0) {
                    state.routing.copy(
                        isSelectionMode = false,
                        selectedRuleKeys = emptySet(),
                        showBatchAssignSheet = false
                    )
                } else state.routing
            )
        }
        if (index == 3) {
            envDelegate.refreshEnvironment()
        }
    }

    fun openNetworkTool(tool: NetworkTool) {
        _uiState.update {
            it.copy(
                navigation = it.navigation.copy(
                    selectedTab = 2,
                    requestedNetworkTool = tool,
                    networkToolReturnTab = 1
                )
            )
        }
    }

    fun consumeNetworkToolRequest() {
        _uiState.update { it.copy(navigation = it.navigation.copy(requestedNetworkTool = null)) }
    }

    fun returnFromNetworkTool() {
        val target = _uiState.value.navigation.networkToolReturnTab ?: return
        setSelectedTab(target)
    }

    fun openAppSheet(app: AppItem) {
        _uiState.update { it.copy(navigation = it.navigation.copy(selectedAppForSheet = app)) }
    }

    fun closeAppSheet() {
        _uiState.update { it.copy(navigation = it.navigation.copy(selectedAppForSheet = null)) }
    }

    fun selectChannelForDetail(channel: NetworkChannel) {
        _uiState.update { it.copy(navigation = it.navigation.copy(selectedChannelForDetail = channel)) }
    }

    fun closeChannelDetail() {
        _uiState.update { it.copy(navigation = it.navigation.copy(selectedChannelForDetail = null)) }
    }

    fun openLogsDialog() = envDelegate.openLogsDialog()
    fun closeLogsDialog() = envDelegate.closeLogsDialog()
    fun dismissSnackBar() {
        _uiState.update { it.copy(snackBarMessage = null) }
    }

    // ------------------------------------------------------------------ App Routing & Rules
    fun enterSelectionMode(initialPackage: String? = null) = appRoutingDelegate.enterSelectionMode(initialPackage)
    fun exitSelectionMode() = appRoutingDelegate.exitSelectionMode()
    fun toggleSelectApp(ruleKey: String) = appRoutingDelegate.toggleSelectApp(ruleKey)
    fun selectAllFilteredApps() = appRoutingDelegate.selectAllFilteredApps()
    fun openBatchAssignSheet() = appRoutingDelegate.openBatchAssignSheet()
    fun closeBatchAssignSheet() = appRoutingDelegate.closeBatchAssignSheet()
    fun batchAssignChannel(channelId: String) = appRoutingDelegate.batchAssignChannel(channelId)
    fun setSearchQuery(query: String) = appRoutingDelegate.setSearchQuery(query)
    fun toggleShowSystemApps() = appRoutingDelegate.toggleShowSystemApps()
    fun setFilterChannelId(channelId: String?) = appRoutingDelegate.setFilterChannelId(channelId)
    fun updateRouteChannel(app: AppItem, channelId: String) = appRoutingDelegate.updateRouteChannel(app, channelId)
    fun forceSyncAllRules() = appRoutingDelegate.forceSyncAllRules()
    fun clearAllRules() = appRoutingDelegate.clearAllRules()

    // ------------------------------------------------------------------ Scenarios
    fun openScenarioSheet() = scenarioDelegate.openScenarioSheet()
    fun closeScenarioSheet() = scenarioDelegate.closeScenarioSheet()
    fun openScenarioSaveDialog() = scenarioDelegate.openScenarioSaveDialog()
    fun closeScenarioSaveDialog() = scenarioDelegate.closeScenarioSaveDialog()
    fun toggleScenarioManually(id: String) = scenarioDelegate.toggleScenarioManually(id)
    fun setScenarioManualMode(ids: Set<String>) = scenarioDelegate.setScenarioManualMode(ids)
    fun setScenarioAutomatic() = scenarioDelegate.setScenarioAutomatic()
    fun clearManualScenario() = scenarioDelegate.clearManualScenario()
    fun deleteScenario(id: String) = scenarioDelegate.deleteScenario(id)
    fun saveCurrentAssignmentsAsScenario(name: String, trigger: ScenarioTrigger) =
        scenarioDelegate.saveCurrentAssignmentsAsScenario(name, trigger)
    fun startEditingScenario(id: String) = scenarioDelegate.startEditingScenario(id)
    fun finishEditingScenario() = scenarioDelegate.finishEditingScenario()
    fun moveScenario(id: String, delta: Int) = scenarioDelegate.moveScenario(id, delta)
    fun openScenarioDetails(id: String) = scenarioDelegate.openScenarioDetails(id)
    fun closeScenarioDetails() = scenarioDelegate.closeScenarioDetails()
    fun saveScenarioDetails(id: String, name: String, trigger: ScenarioTrigger) =
        scenarioDelegate.saveScenarioDetails(id, name, trigger)

    // ------------------------------------------------------------------ Config Transfer
    fun exportConfigToFile() = configDelegate.exportConfigToFile()
    fun exportConfigToClipboard() = configDelegate.exportConfigToClipboard()
    fun requestImportFromClipboard() = configDelegate.requestImportFromClipboard()
    fun requestImportFromFile(path: String) = configDelegate.requestImportFromFile(path)
    fun importConfigFromUri(uri: Uri) = configDelegate.importConfigFromUri(uri)
    fun toggleConfigImportSection(section: ConfigTransfer.Section) = configDelegate.toggleConfigImportSection(section)
    fun consumeConfigAppearanceImport() = configDelegate.consumeConfigAppearanceImport()
    fun cancelConfigImport() = configDelegate.cancelConfigImport()
    fun confirmConfigImport() = configDelegate.confirmConfigImport()

    // ------------------------------------------------------------------ Environment & Diagnostics
    fun testSingleChannel(channel: NetworkChannel) = envDelegate.testSingleChannel(channel)
    fun refreshEnvironment() = envDelegate.refreshEnvironment()
    fun runChannelTests() = envDelegate.runChannelTests()
    fun toggleDnsFollowsChannel() = envDelegate.toggleDnsFollowsChannel()
    fun toggleAutoFailover() = envDelegate.toggleAutoFailover()
    fun updateFailoverHoldOffSeconds(seconds: Int) = envDelegate.updateFailoverHoldOffSeconds(seconds)
    fun toggleFailoverProbeInternet() = envDelegate.toggleFailoverProbeInternet()
    fun toggleKeepSlaveWifiScreenOff() = envDelegate.toggleKeepSlaveWifiScreenOff()
    fun toggleMobileDataAlwaysOn() = envDelegate.toggleMobileDataAlwaysOn()

    fun setHotspotUpstream(target: String) {
        viewModelScope.launch {
            DualWifiManager.setHotspotUpstream(target)
        }
    }

    fun openHotspotSettings() {
        val app = getApplication<Application>()
        try {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            app.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                app.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}

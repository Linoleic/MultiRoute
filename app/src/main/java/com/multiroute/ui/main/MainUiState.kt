package com.multiroute.ui.main

import com.multiroute.cellular.CellularSubscription
import com.multiroute.data.ConfigTransfer
import com.multiroute.model.AppItem
import com.multiroute.model.NetworkChannel
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.model.TestServerConfig
import com.multiroute.util.DiagnosticInfo
import com.multiroute.util.RouteRuleBuilder
import com.multiroute.util.TransparentProxyDiagnostics

internal enum class PhysicalVpnBypassWarning {
    MODULE_INACTIVE,
    VPN_LOCKDOWN
}

internal enum class ProxyGatewayAvailabilityNotice {
    UNSUPPORTED_ANDROID_VPN,
    NO_PROXY_DETECTED
}

internal fun resolveProxyGatewayAvailabilityNotice(
    channels: List<NetworkChannel>,
    compatibleVpn: NetworkChannel?,
    hasTransparentProxyEvidence: Boolean,
    hasSavedMappings: Boolean
): ProxyGatewayAvailabilityNotice? {
    if (compatibleVpn != null || hasSavedMappings || hasTransparentProxyEvidence) return null
    return if (channels.any { it.isVpn }) {
        ProxyGatewayAvailabilityNotice.UNSUPPORTED_ANDROID_VPN
    } else {
        ProxyGatewayAvailabilityNotice.NO_PROXY_DETECTED
    }
}

data class CellularControlUiState(
    val mode: Int = 0,
    val subscriptions: List<CellularSubscription> = emptyList(),
    val isBusy: Boolean = false,
    val hasFailed: Boolean = false,
    val detailsExpanded: Boolean = false,
    val preferredMode: Int = 2,
    val isLoaded: Boolean = false,
    val inventoryError: String? = null
)

data class NavigationUiState(
    val requestedNetworkTool: NetworkTool? = null,
    val networkToolReturnTab: Int? = null,
    val selectedTab: Int = 0, // 0: 应用分流, 1: 网络通道, 2: 工具, 3: 设置
    val selectedAppForSheet: AppItem? = null,
    val selectedChannelForDetail: NetworkChannel? = null,
    val showTestServerDialog: Boolean = false,
    val showLogsDialog: Boolean = false,
    val isLoadingLogs: Boolean = false,
    val logsContent: String = ""
)

data class AppRoutingUiState(
    val isLoadingApps: Boolean = true,
    val isRefreshingApps: Boolean = false,
    /** True when one or more installed-app enumeration sources could not be read. */
    val isInstalledAppsIncomplete: Boolean = false,
    val apps: List<AppItem> = emptyList(),
    val baseApps: List<AppItem> = emptyList(),
    val totalAppsCount: Int = 0,
    val configuredAppsCount: Int = 0,
    val proxyGatewayRuleCount: Int = 0,
    val searchQuery: String = "",
    val showSystemApps: Boolean = false,
    val filterChannelId: String? = null,
    val filterByEffectiveChannel: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedRuleKeys: Set<String> = emptySet(),
    val showBatchAssignSheet: Boolean = false,
    val isSyncing: Boolean = false
)

data class ChannelProbeUiState(
    val channels: List<NetworkChannel> = emptyList(),
    val supportedAndroidVpn: NetworkChannel? = null,
    val testResults: Map<String, String> = emptyMap(),
    val isTesting: Boolean = false,
    val isTestingSingleChannel: Boolean = false,
    val testServerConfig: TestServerConfig = TestServerConfig()
)

data class ScenarioUiState(
    val scenarioProfiles: List<ScenarioProfile> = emptyList(),
    val activeScenarioId: String? = null,
    /** Every plan that is applying right now; more than one when several conditions match at once. */
    val activeScenarioIds: Set<String> = emptySet(),
    /** Plan id -> how many of its apps a later (higher-priority) active plan re-routed. */
    val scenarioConflicts: Map<String, Int> = emptyMap(),
    val activeScenarioName: String? = null,
    val activeScenarioNames: List<String> = emptyList(),
    val activeScenarioTrigger: ScenarioTrigger? = null,
    val activeScenarioSsid: String? = null,
    val activeScenarioManual: Boolean = false,
    val manualScenarioId: String = "",
    /** Selected scenario evaluation mode: automatic, manual, or disabled. */
    val scenarioMode: com.multiroute.util.ScenarioEngine.SelectionMode =
        com.multiroute.util.ScenarioEngine.SelectionMode.AUTOMATIC,
    /** When true, all base and scenario routing rules are paused (apps use system default). */
    val routingPaused: Boolean = false,
    /** Plans switched on by hand; several can be on at once. */
    val manualScenarioIds: Set<String> = emptySet(),
    val showScenarioSheet: Boolean = false,
    val showScenarioSaveDialog: Boolean = false,
    /** id of the plan whose dedicated overrides editor sheet is open; completely decoupled from base routing. */
    val scenarioOverridesId: String? = null,
    val scenarioDetailsId: String? = null,
    val showScenarioDetailsDialog: Boolean = false
) {
    val scenarioManualMode: Boolean
        get() = scenarioMode == com.multiroute.util.ScenarioEngine.SelectionMode.MANUAL
}

data class ConfigTransferUiState(
    val configPath: String = "",
    val pendingImportJson: String? = null,
    val configImportPreview: ConfigTransfer.Preview? = null,
    val configImportSections: Set<ConfigTransfer.Section> = emptySet(),
    val configAppearanceImported: Boolean = false,
    val showConfigImportConfirm: Boolean = false,
    val isConfigBusy: Boolean = false
)

data class SystemEnvUiState(
    val isRootGranted: Boolean = false,
    val supportsCellularData: Boolean = false,
    val isMobileDataAlwaysOn: Boolean = false,
    val isKeepSlaveWifiScreenOff: Boolean = false,
    val isDnsFollowsChannel: Boolean = true,
    val isAutoFailoverEnabled: Boolean = true,
    val failoverHoldOffSeconds: Int = 3,
    val isFailoverProbeInternet: Boolean = true,
    val diagnosticInfo: DiagnosticInfo? = null,
    val transparentProxyDiagnostics: TransparentProxyDiagnostics? = null,
    val isEnvironmentRefreshing: Boolean = false,
    val environmentRefreshFailed: Boolean = false
)

/**
 * Root UI state aggregating focused domain sub-states to isolate mutations and limit copy overhead.
 * Compatibility accessors expose sub-state properties transparently to Composables.
 */
data class MainUiState(
    val navigation: NavigationUiState = NavigationUiState(),
    val routing: AppRoutingUiState = AppRoutingUiState(),
    val channelProbe: ChannelProbeUiState = ChannelProbeUiState(),
    val scenario: ScenarioUiState = ScenarioUiState(),
    val config: ConfigTransferUiState = ConfigTransferUiState(),
    val env: SystemEnvUiState = SystemEnvUiState(),
    val snackBarMessage: String? = null
) {
    // Navigation & overlays
    val requestedNetworkTool: NetworkTool? get() = navigation.requestedNetworkTool
    val networkToolReturnTab: Int? get() = navigation.networkToolReturnTab
    val selectedTab: Int get() = navigation.selectedTab
    val selectedAppForSheet: AppItem? get() = navigation.selectedAppForSheet
    val selectedChannelForDetail: NetworkChannel? get() = navigation.selectedChannelForDetail
    val showTestServerDialog: Boolean get() = navigation.showTestServerDialog
    val showLogsDialog: Boolean get() = navigation.showLogsDialog
    val isLoadingLogs: Boolean get() = navigation.isLoadingLogs
    val logsContent: String get() = navigation.logsContent

    // App routing & selection
    val isLoadingApps: Boolean get() = routing.isLoadingApps
    val isRefreshingApps: Boolean get() = routing.isRefreshingApps
    val isInstalledAppsIncomplete: Boolean get() = routing.isInstalledAppsIncomplete
    val apps: List<AppItem> get() = routing.apps
    val totalAppsCount: Int get() = routing.totalAppsCount
    val configuredAppsCount: Int get() = routing.configuredAppsCount
    val searchQuery: String get() = routing.searchQuery
    val showSystemApps: Boolean get() = routing.showSystemApps
    val filterChannelId: String? get() = routing.filterChannelId
    val isSelectionMode: Boolean get() = routing.isSelectionMode
    val selectedRuleKeys: Set<String> get() = routing.selectedRuleKeys
    val showBatchAssignSheet: Boolean get() = routing.showBatchAssignSheet
    val isSyncing: Boolean get() = routing.isSyncing

    // Channels & testing
    val channels: List<NetworkChannel> get() = channelProbe.channels
    val supportedAndroidVpn: NetworkChannel? get() = channelProbe.supportedAndroidVpn
    val testResults: Map<String, String> get() = channelProbe.testResults
    val isTesting: Boolean get() = channelProbe.isTesting
    val isTestingSingleChannel: Boolean get() = channelProbe.isTestingSingleChannel
    val testServerConfig: TestServerConfig get() = channelProbe.testServerConfig

    // Scenario profiles
    val scenarioProfiles: List<ScenarioProfile> get() = scenario.scenarioProfiles
    val activeScenarioId: String? get() = scenario.activeScenarioId
    val activeScenarioIds: Set<String> get() = scenario.activeScenarioIds
    val scenarioConflicts: Map<String, Int> get() = scenario.scenarioConflicts
    val activeScenarioName: String? get() = scenario.activeScenarioName
    val activeScenarioNames: List<String> get() = scenario.activeScenarioNames
    val activeScenarioTrigger: ScenarioTrigger? get() = scenario.activeScenarioTrigger
    val activeScenarioSsid: String? get() = scenario.activeScenarioSsid
    val activeScenarioManual: Boolean get() = scenario.activeScenarioManual
    val manualScenarioId: String get() = scenario.manualScenarioId
    val scenarioMode: com.multiroute.util.ScenarioEngine.SelectionMode get() = scenario.scenarioMode
    val routingPaused: Boolean get() = scenario.routingPaused
    val scenarioManualMode: Boolean get() = scenario.scenarioManualMode
    val manualScenarioIds: Set<String> get() = scenario.manualScenarioIds
    val showScenarioSheet: Boolean get() = scenario.showScenarioSheet
    val showScenarioSaveDialog: Boolean get() = scenario.showScenarioSaveDialog
    val scenarioOverridesId: String? get() = scenario.scenarioOverridesId
    val scenarioDetailsId: String? get() = scenario.scenarioDetailsId
    val showScenarioDetailsDialog: Boolean get() = scenario.showScenarioDetailsDialog

    // Config transfer
    val configPath: String get() = config.configPath
    val pendingImportJson: String? get() = config.pendingImportJson
    val configImportPreview: ConfigTransfer.Preview? get() = config.configImportPreview
    val configImportSections: Set<ConfigTransfer.Section> get() = config.configImportSections
    val configAppearanceImported: Boolean get() = config.configAppearanceImported
    val showConfigImportConfirm: Boolean get() = config.showConfigImportConfirm
    val isConfigBusy: Boolean get() = config.isConfigBusy

    // System & environment
    val isRootGranted: Boolean get() = env.isRootGranted
    val supportsCellularData: Boolean get() = env.supportsCellularData
    val isMobileDataAlwaysOn: Boolean get() = env.isMobileDataAlwaysOn
    val isKeepSlaveWifiScreenOff: Boolean get() = env.isKeepSlaveWifiScreenOff
    val isDnsFollowsChannel: Boolean get() = env.isDnsFollowsChannel
    val isAutoFailoverEnabled: Boolean get() = env.isAutoFailoverEnabled
    val failoverHoldOffSeconds: Int get() = env.failoverHoldOffSeconds
    val isFailoverProbeInternet: Boolean get() = env.isFailoverProbeInternet
    val diagnosticInfo: DiagnosticInfo? get() = env.diagnosticInfo

    internal fun mayBypassTransparentProxy(channel: NetworkChannel): Boolean =
        !channel.isVpn && !channel.isHotspot &&
            channel.transportType in setOf("WLAN", "cellular", "ethernet", "bluetooth") &&
            env.transparentProxyDiagnostics?.mayBypassOn(channel.interfaceName) == true

    internal fun physicalVpnBypassWarning(channel: NetworkChannel): PhysicalVpnBypassWarning? {
        if (channel.isVpn || channel.isHotspot || RouteRuleBuilder.isTunnelInterface(channel.interfaceName)) {
            return null
        }
        if (channels.none { it.isVpn }) return null
        val diag = env.diagnosticInfo ?: return null
        return when {
            diag.isVpnLockdownEnabled -> PhysicalVpnBypassWarning.VPN_LOCKDOWN
            !diag.isModuleActive -> PhysicalVpnBypassWarning.MODULE_INACTIVE
            else -> null
        }
    }

    internal fun proxyGatewayAvailabilityNotice(hasSavedMappings: Boolean): ProxyGatewayAvailabilityNotice? =
        resolveProxyGatewayAvailabilityNotice(
            channels = channels,
            compatibleVpn = supportedAndroidVpn,
            hasTransparentProxyEvidence = env.transparentProxyDiagnostics?.hasActiveEvidence == true,
            hasSavedMappings = hasSavedMappings
        )
    val isEnvironmentRefreshing: Boolean get() = env.isEnvironmentRefreshing
    val environmentRefreshFailed: Boolean get() = env.environmentRefreshFailed
}

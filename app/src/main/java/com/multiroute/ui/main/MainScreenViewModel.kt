package com.multiroute.ui.main

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.TestServerManager
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.model.TestServerConfig
import com.multiroute.util.DiagnosticInfo
import com.multiroute.util.NetworkUtils
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
    val selectedPackageNames: Set<String> = emptySet(),
    val showBatchAssignSheet: Boolean = false
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var allApps: List<AppItem> = emptyList()

    init {
        loadTestServerConfig()
        refreshEnvironment()
        loadInstalledApps()
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
            snackBarMessage = "测试节点已配置为: ${newConfig.activeDisplayName}"
        )
    }

    fun setSelectedTab(index: Int) {
        _uiState.value = _uiState.value.copy(
            selectedTab = index,
            isSelectionMode = if (index != 0) false else _uiState.value.isSelectionMode,
            selectedPackageNames = if (index != 0) emptySet() else _uiState.value.selectedPackageNames,
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
            selectedPackageNames = selected
        )
    }

    fun exitSelectionMode() {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedPackageNames = emptySet(),
            showBatchAssignSheet = false
        )
    }

    fun toggleSelectApp(packageName: String) {
        val current = _uiState.value.selectedPackageNames.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        _uiState.value = _uiState.value.copy(selectedPackageNames = current)
    }

    fun selectAllFilteredApps() {
        val currentFiltered = _uiState.value.apps.map { it.packageName }.toSet()
        val currentSelected = _uiState.value.selectedPackageNames
        val allSelected = currentFiltered.isNotEmpty() && currentFiltered.all { it in currentSelected }
        val newSelected = if (allSelected) {
            currentSelected - currentFiltered
        } else {
            currentSelected + currentFiltered
        }
        _uiState.value = _uiState.value.copy(selectedPackageNames = newSelected)
    }

    fun openBatchAssignSheet() {
        if (_uiState.value.selectedPackageNames.isEmpty()) return
        _uiState.value = _uiState.value.copy(showBatchAssignSheet = true)
    }

    fun closeBatchAssignSheet() {
        _uiState.value = _uiState.value.copy(showBatchAssignSheet = false)
    }

    fun batchAssignChannel(channelId: String) {
        val packages = _uiState.value.selectedPackageNames
        if (packages.isEmpty()) return

        val context = getApplication<Application>()
        RouteConfigProvider.setTargetChannels(context, packages, channelId)

        val packageSet = packages.toSet()
        allApps = allApps.map {
            if (it.packageName in packageSet) it.copy(targetChannelId = channelId) else it
        }

        val channelName = when {
            channelId == CHANNEL_DEFAULT -> "系统默认"
            else -> _uiState.value.channels.firstOrNull { it.id == channelId }?.displayName ?: channelId
        }

        val count = packages.size
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedPackageNames = emptySet(),
            showBatchAssignSheet = false,
            snackBarMessage = "已为 $count 个应用批量分配至: $channelName"
        )

        filterApps()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val ok = SuHelper.syncAllRouteRules(context)
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                snackBarMessage = if (ok) "已为 $count 个应用批量生效并同步规则" else "已保存 $count 条规则 (等待提权同步)"
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
            val result = NetworkUtils.testSingleChannelPublicIp(
                context = getApplication(),
                channel = channel,
                config = _uiState.value.testServerConfig
            )
            val updated = _uiState.value.testResults.toMutableMap()
            updated[channel.id] = result
            _uiState.value = _uiState.value.copy(
                isTestingSingleChannel = false,
                testResults = updated,
                snackBarMessage = "${channel.interfaceName} 出口测试完成"
            )
        }
    }

    fun dismissSnackBar() {
        _uiState.value = _uiState.value.copy(snackBarMessage = null)
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
        _uiState.value = _uiState.value.copy(showLogsDialog = true, isLoadingLogs = true, logsContent = "正在读取系统运行与诊断日志...")
        viewModelScope.launch {
            try {
                val logs = withContext(Dispatchers.IO) { SuHelper.getDiagnosticLogs(getApplication()) }
                _uiState.value = _uiState.value.copy(isLoadingLogs = false, logsContent = logs)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingLogs = false, logsContent = "读取日志失败: ${e.message}")
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
                snackBarMessage = if (next) "副 Wi-Fi 息屏防断联已开启 (息屏常驻保持)" else "副 Wi-Fi 息屏防断联已关闭 (跟随系统休眠)"
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
                    snackBarMessage = if (!current) "蜂窝常活已开启" else "蜂窝常活已关闭"
                )
            }
        }
    }

    fun runChannelTests() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResults = emptyMap())
            val currentChannels = _uiState.value.channels.ifEmpty {
                NetworkUtils.getActiveChannels(getApplication())
            }
            val results = NetworkUtils.testChannelsConcurrently(
                context = getApplication(),
                channels = currentChannels,
                config = _uiState.value.testServerConfig
            )
            _uiState.value = _uiState.value.copy(
                isTesting = false,
                testResults = results,
                snackBarMessage = "多通道独立出口诊断已完成"
            )
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

    fun updateRouteChannel(packageName: String, channelId: String) {
        val context = getApplication<Application>()
        RouteConfigProvider.setTargetChannel(context, packageName, channelId)

        allApps = allApps.map {
            if (it.packageName == packageName) it.copy(targetChannelId = channelId) else it
        }

        if (_uiState.value.selectedAppForSheet?.packageName == packageName) {
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
                snackBarMessage = if (ok) "已同步路由规则至内核" else "规则已保存 (等待提权同步)"
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
                snackBarMessage = if (ok) "内核路由策略已全部同步" else "同步失败，请检查 Root 授权"
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
            _uiState.value = _uiState.value.copy(snackBarMessage = "已清空所有应用分流规则")
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
                    app.packageName.lowercase().contains(query)
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

package com.multiroute.ui.main

import com.multiroute.theme.AppMotion
import android.content.Intent
import com.multiroute.util.NetworkUtils
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.repeatOnLifecycle
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.model.NetworkChannel
import com.multiroute.model.TestServerConfig
import com.multiroute.model.TestServerPreset
import com.multiroute.data.RouteConfigProvider
import com.multiroute.util.ChannelTrafficStats
import com.multiroute.util.ModuleStatus
import kotlinx.coroutines.flow.StateFlow
import com.multiroute.util.SuHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (androidx.navigation3.runtime.NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDiagnostics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val importContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(uiState.configAppearanceImported) {
        if (uiState.configAppearanceImported) {
            viewModel.consumeConfigAppearanceImport()
            com.multiroute.util.AppLocaleHelper.applyLanguage(
                importContext,
                RouteConfigProvider.getLanguage(importContext)
            )
            (importContext as? android.app.Activity)?.recreate()
        }
    }

    LaunchedEffect(uiState.snackBarMessage) {
        uiState.snackBarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSnackBar()
        }
    }

    // 拦截返回键以退出多选模式
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.exitSelectionMode()
    }

    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            val editingName = uiState.scenarioEditId?.let { id ->
                                uiState.scenarioProfiles.firstOrNull { it.id == id }?.name
                            }
                            Text(
                                text = if (uiState.isSelectionMode) {
                                    editingName?.let {
                                        stringResource(com.multiroute.R.string.scenario_editing, it)
                                    } ?: stringResource(com.multiroute.R.string.ui_batch_title)
                                } else "MultiRoute",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (uiState.isSelectionMode) {
                                    stringResource(com.multiroute.R.string.ui_selected_apps_count, uiState.selectedRuleKeys.size)
                                } else {
                                    when (uiState.selectedTab) {
                                        0 -> stringResource(com.multiroute.R.string.ui_apps_summary, uiState.totalAppsCount, uiState.configuredAppsCount)
                                        1 -> stringResource(com.multiroute.R.string.ui_online_ifaces, uiState.channels.size)
                                        2 -> stringResource(com.multiroute.R.string.tools_subtitle)
                                        else -> stringResource(com.multiroute.R.string.settings_subtitle)
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    actions = {
                        if (uiState.isSelectionMode) {
                            IconButton(onClick = { viewModel.exitSelectionMode() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(com.multiroute.R.string.cd_exit_multi)
                                )
                            }
                        } else {
                            val editingName = uiState.scenarioEditId?.let { id ->
                                uiState.scenarioProfiles.firstOrNull { it.id == id }?.name
                            }
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    if (uiState.scenarioEditId != null) {
                                        viewModel.finishEditingScenario()
                                    } else {
                                        viewModel.openScenarioSheet()
                                    }
                                }
                            ) {
                                Text(
                                    text = editingName?.let {
                                        stringResource(com.multiroute.R.string.scenario_editing, it)
                                    } ?: uiState.activeScenarioName?.let {
                                        stringResource(com.multiroute.R.string.scenario_current, it)
                                    } ?: stringResource(com.multiroute.R.string.scenario_default),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (uiState.activeScenarioName != null || editingName != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    }
                                )
                            }
                            ModuleStatusButton(uiState) {
                                showDiagnostics = true
                                viewModel.refreshEnvironment()
                            }
                            if (uiState.selectedTab == 0) RuleActionsMenu(uiState, viewModel)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                if (!useRail) MainDestinations(uiState.selectedTab, viewModel::setSelectedTab, rail = false)
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Row(Modifier.fillMaxSize().padding(innerPadding)) {
                if (useRail) MainDestinations(uiState.selectedTab, viewModel::setSelectedTab, rail = true)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(targetState = uiState.selectedTab, modifier = Modifier.fillMaxSize(),
                        transitionSpec = { AppMotion.content() }, label = "mainDestination") { tab ->
                        tabStates.SaveableStateProvider(tab) {
                            when (tab) {
                                0 -> AppRulesTab(
                                    uiState = uiState,
                                    viewModel = viewModel
                                )
                                1 -> ChannelsTab(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onNavigate = onItemClick
                                )
                                2 -> ToolsTab(uiState = uiState, viewModel = viewModel, onNavigate = onItemClick)
                                3 -> SettingsTab(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onNavigate = onItemClick
                                )
                            }

                        }
                    }

                    // 选择通道的模态底部抽屉 (ModalBottomSheet - 热点下行通道不参与应用分流)
                    if (uiState.selectedAppForSheet != null) {
                        val app = uiState.selectedAppForSheet!!
                        ChannelSelectBottomSheet(
                            app = app,
                            channels = uiState.channels.filter { !it.isHotspot },
                            onSelectChannel = { channelId ->
                                viewModel.updateRouteChannel(app, channelId)
                                viewModel.closeAppSheet()
                            },
                            onDismiss = { viewModel.closeAppSheet() }
                        )
                    }

                    // 批量分配通道模态底部抽屉 (BatchChannelSelectBottomSheet - 热点下行通道不参与应用分流)
                    if (uiState.showBatchAssignSheet) {
                        BatchChannelSelectBottomSheet(
                            selectedCount = uiState.selectedRuleKeys.size,
                            channels = uiState.channels.filter { !it.isHotspot },
                            onSelectChannel = { channelId ->
                                viewModel.batchAssignChannel(channelId)
                            },
                            onDismiss = { viewModel.closeBatchAssignSheet() }
                        )
                    }

                    // 通道详细属性与诊断抽屉 (ChannelDetailBottomSheet)
                    if (uiState.selectedChannelForDetail != null) {
                        val channel = uiState.selectedChannelForDetail!!
                        val routedApps = remember(channel.id, uiState.apps) {
                            uiState.apps.filter { (it.targetChannelId == channel.id || it.targetChannelId == channel.interfaceName) }
                        }
                        ChannelDetailBottomSheet(
                            channel = channel,
                            routedApps = routedApps,
                            testResult = uiState.testResults[channel.id],
                            isTestingSingle = uiState.isTestingSingleChannel,
                            trafficFlow = viewModel.channelTraffic,
                            testServerName = uiState.testServerConfig.displayName(androidx.compose.ui.platform.LocalContext.current),
                            testServerUrl = uiState.testServerConfig.activeUrl,
                            onTestChannel = { viewModel.testSingleChannel(channel) },
                            onDismiss = { viewModel.closeChannelDetail() }
                        )
                    }

                    if (showDiagnostics) ModuleDiagnosticsOverlay(
                        uiState = uiState, viewModel = viewModel, wide = useRail,
                        onDismiss = { showDiagnostics = false },
                        onLogs = { showDiagnostics = false; viewModel.openLogsDialog() }
                    )

                    // 运行与诊断日志查看对话框
                    if (uiState.showLogsDialog) {
                        DiagnosticLogsDialog(
                            logs = uiState.logsContent,
                            isLoading = uiState.isLoadingLogs,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(uiState.logsContent))
                                viewModel.closeLogsDialog()
                            },
                            onDismiss = { viewModel.closeLogsDialog() }
                        )
                    }

                    // 公网出口测试服务器设置对话框
                    if (uiState.showTestServerDialog) {
                        TestServerConfigDialog(
                            currentConfig = uiState.testServerConfig,
                            onSave = { presetId, customUrl ->
                                viewModel.updateTestServerConfig(presetId, customUrl)
                            },
                            onDismiss = { viewModel.closeTestServerDialog() }
                        )
                    }

                    // 场景方案抽屉：手动应用 / 恢复自动 / 保存当前指派 / 删除
                    if (uiState.showScenarioSheet) {
                        ScenarioBottomSheet(
                            profiles = uiState.scenarioProfiles,
                            manualMode = uiState.scenarioManualMode,
                            manualIds = uiState.manualScenarioIds,
                            onSetManual = { viewModel.setScenarioManualMode() },
                            activeIds = uiState.activeScenarioIds,
                            conflicts = uiState.scenarioConflicts,
                            onApplyManually = { viewModel.toggleScenarioManually(it) },
                            onClearManual = { viewModel.clearManualScenario() },
                            onSaveCurrent = { viewModel.openScenarioSaveDialog() },
                            onDelete = { viewModel.deleteScenario(it) },
                            onEditOverrides = { viewModel.startEditingScenario(it) },
                            onEditDetails = { viewModel.openScenarioDetails(it) },
                            onMove = { id, delta -> viewModel.moveScenario(id, delta) },
                            onDismiss = { viewModel.closeScenarioSheet() }
                        )
                    }
                    if (uiState.showScenarioSaveDialog) {
                        ScenarioSaveDialog(
                            currentSsids = uiState.channels
                                .mapNotNull { it.ssid?.takeIf { ssid -> ssid.isNotBlank() } }
                                .distinct(),
                            onSave = { name, trigger -> viewModel.saveCurrentAssignmentsAsScenario(name, trigger) },
                            onDismiss = { viewModel.closeScenarioSaveDialog() }
                        )
                    }
                    // 修改已有方案的名称与触发条件
                    if (uiState.showScenarioDetailsDialog) {
                        val edited = uiState.scenarioDetailsId?.let { id ->
                            uiState.scenarioProfiles.firstOrNull { it.id == id }
                        }
                        if (edited != null) {
                            ScenarioSaveDialog(
                                titleRes = com.multiroute.R.string.scenario_details_title,
                                initialName = edited.name,
                                initialTrigger = edited.trigger,
                                currentSsids = uiState.channels
                                    .mapNotNull { it.ssid?.takeIf { ssid -> ssid.isNotBlank() } }
                                    .distinct(),
                                onSave = { name, trigger ->
                                    viewModel.saveScenarioDetails(edited.id, name, trigger)
                                },
                                onDismiss = { viewModel.closeScenarioDetails() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab 0: 应用分流规则列表 (支持单项配置与批量分配)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRulesTab(
    uiState: MainUiState,
    viewModel: MainScreenViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 搜索与过滤器
            item {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(com.multiroute.R.string.ui_search_hint)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(com.multiroute.R.string.cd_search),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(com.multiroute.R.string.cd_clear_search)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = uiState.filterChannelId == null,
                            onClick = { viewModel.setFilterChannelId(null) },
                            label = { Text(stringResource(com.multiroute.R.string.filter_all)) }
                        )
                        FilterChip(
                            selected = uiState.filterChannelId == "configured",
                            onClick = {
                                viewModel.setFilterChannelId(
                                    if (uiState.filterChannelId == "configured") null else "configured"
                                )
                            },
                            label = { Text(stringResource(com.multiroute.R.string.ui_filter_routed, uiState.configuredAppsCount)) },
                            leadingIcon = {
                                if (uiState.filterChannelId == "configured") {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        )

                        // 动态按在线物理通道过滤 (排除热点下行通道)
                        for (channel in uiState.channels.filter { !it.isHotspot }) {
                            FilterChip(
                                selected = uiState.filterChannelId == channel.id,
                                onClick = {
                                    viewModel.setFilterChannelId(
                                        if (uiState.filterChannelId == channel.id) null else channel.id
                                    )
                                },
                                label = { Text(channel.shortName) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = getTransportIcon(channel.transportType),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }

                        FilterChip(
                            selected = uiState.showSystemApps,
                            onClick = { viewModel.toggleShowSystemApps() },
                            label = { Text(stringResource(com.multiroute.R.string.filter_system_apps)) },
                            leadingIcon = {
                                Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }

                    // 批量管理操作栏 / 状态提示
                    if (uiState.isSelectionMode) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp)),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val allFilteredSelected = uiState.apps.isNotEmpty() && uiState.apps.all { it.ruleKey in uiState.selectedRuleKeys }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(com.multiroute.R.string.ui_selected_items, uiState.selectedRuleKeys.size),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    TextButton(
                                        onClick = { viewModel.selectAllFilteredApps() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(if (allFilteredSelected) stringResource(com.multiroute.R.string.ui_deselect_all) else stringResource(com.multiroute.R.string.ui_select_all))
                                    }
                                    TextButton(
                                        onClick = { viewModel.exitSelectionMode() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(stringResource(com.multiroute.R.string.ui_done))
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_app_list, uiState.apps.size),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.outline
                            )
                            AssistChip(
                                onClick = { viewModel.enterSelectionMode() },
                                label = { Text(stringResource(com.multiroute.R.string.ui_batch_assign)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = AssistChipDefaults.assistChipColors(
                                    labelColor = MaterialTheme.colorScheme.primary,
                                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                )
                            )
                        }
                    }
                }
            }

            // 状态列表
            when {
                uiState.isLoadingApps -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                uiState.apps.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    text = stringResource(com.multiroute.R.string.ui_no_matching_apps),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
                else -> {
                    items(uiState.apps, key = { it.ruleKey }) { app ->
                        AppItemRow(
                            app = app,
                            channels = uiState.channels,
                            isSelectionMode = uiState.isSelectionMode,
                            isSelected = uiState.selectedRuleKeys.contains(app.ruleKey),
                            onToggleSelect = { viewModel.toggleSelectApp(app.ruleKey) },
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleSelectApp(app.ruleKey)
                                } else {
                                    viewModel.openAppSheet(app)
                                }
                            },
                            onLongClick = {
                                if (!uiState.isSelectionMode) {
                                    viewModel.enterSelectionMode(app.ruleKey)
                                } else {
                                    viewModel.toggleSelectApp(app.ruleKey)
                                }
                            }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // 批量操作吸底操作栏 (在多选模式激活时显示)
        AnimatedVisibility(
            visible = uiState.isSelectionMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.exitSelectionMode() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(com.multiroute.R.string.ui_cancel))
                    }

                    Button(
                        onClick = { viewModel.openBatchAssignSheet() },
                        modifier = Modifier.weight(2f),
                        enabled = uiState.selectedRuleKeys.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (uiState.selectedRuleKeys.isEmpty()) stringResource(com.multiroute.R.string.ui_select_apps_to_assign)
                            else stringResource(com.multiroute.R.string.ui_assign_channel_n, uiState.selectedRuleKeys.size)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab 1: 在线通道、出口测试与未就绪通道的管理入口
 */
@Composable
fun ChannelsTab(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    onNavigate: (androidx.navigation3.runtime.NavKey) -> Unit = {}
) {
    val dualWifiStatus by com.multiroute.wifi.DualWifiManager.status.collectAsState()
    val cellularState by viewModel.cellularControlState.collectAsState()
    ObserveNetworkManagement(viewModel)
    ObserveChannelTraffic(viewModel)
    val candidates = activationCandidates(cellularState, dualWifiStatus, uiState.channels)

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingsSectionLabel(stringResource(com.multiroute.R.string.ui_active_channels, uiState.channels.size))
            }

            if (uiState.channels.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_no_active_network),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            } else {
                items(uiState.channels, key = { it.id }) { channel ->
                    ChannelDetailCard(
                        channel = channel,
                        testResult = uiState.testResults[channel.id],
                        trafficFlow = viewModel.channelTraffic,
                        onClick = { viewModel.selectChannelForDetail(channel) }
                    )
                }
            }

            activationCandidateItems(candidates) { target ->
                viewModel.openNetworkTool(target)
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // 界面下方：测试节点提示条与诊断操作区
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 当前测试节点提示条（精简文案并支持点击切换）
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.openTestServerDialog() },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_test_node_2, uiState.testServerConfig.displayName(androidx.compose.ui.platform.LocalContext.current)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = stringResource(com.multiroute.R.string.ui_change),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 精简文案的诊断测试按钮
                Button(
                    onClick = { viewModel.runChannelTests() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isTesting && uiState.channels.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(com.multiroute.R.string.ui_testing_channels))
                    } else {
                        Icon(Icons.Default.Speed, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(com.multiroute.R.string.ui_test_channels))
                    }
                }
            }
        }
    }
}

/**
 * Tab 2: 模块设置与维护（聚焦模块激活检测、网络运行设置与诊断日志）
 */
@Composable
fun SettingsTab(uiState: MainUiState, viewModel: MainScreenViewModel,
    onNavigate: (androidx.navigation3.runtime.NavKey) -> Unit = {}) {
    SettingsToolsHub(uiState, viewModel, tools = false)
}

@Composable
fun ToolsTab(uiState: MainUiState, viewModel: MainScreenViewModel,
    onNavigate: (androidx.navigation3.runtime.NavKey) -> Unit = {}) {
    SettingsToolsHub(uiState, viewModel, tools = true, onNavigate = onNavigate)
}

/**
 * 结构化的诊断信息显示项
 */
@Composable
fun DiagnosticItem(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

/**
 * 应用列表单项（支持长按/批量选择模式，去掉了表面的 UID 显示）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppItemRow(
    app: AppItem,
    channels: List<NetworkChannel>,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val isCustom = app.targetChannelId != CHANNEL_DEFAULT
    val currentChannel = channels.firstOrNull { (it.id == app.targetChannelId || it.interfaceName == app.targetChannelId) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelect()
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    if (isSelectionMode) {
                        onToggleSelect()
                    } else {
                        onLongClick()
                    }
                }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else if (isCustom) {
                when {
                    app.targetChannelId.startsWith("wlan") -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    (app.targetChannelId.startsWith("rmnet") || com.multiroute.cellular.CellularTargets.isSim(app.targetChannelId)) -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                    else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                }
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() }
                )
            }

            AppIcon(drawable = app.icon)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 当前通道指示 Badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isCustom) {
                    when {
                        app.targetChannelId.startsWith("wlan") -> MaterialTheme.colorScheme.primaryContainer
                        (app.targetChannelId.startsWith("rmnet") || com.multiroute.cellular.CellularTargets.isSim(app.targetChannelId)) -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isCustom) {
                        val icon = if (currentChannel != null) {
                            getTransportIcon(currentChannel.transportType)
                        } else {
                            Icons.Default.CloudOff
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = when {
                            app.targetChannelId == CHANNEL_DEFAULT -> stringResource(com.multiroute.R.string.vm_system_default)
                            currentChannel != null -> NetworkUtils.channelLabel(LocalContext.current, currentChannel)
                            else -> stringResource(com.multiroute.R.string.ui_channel_offline_short, NetworkUtils.savedTargetLabel(LocalContext.current, app.targetChannelId))
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * 配置备份卡片：整份配置（分应用指派 + 场景方案 + 相关设置）的导出与导入。
 */
@Composable
fun ConfigBackupCard(uiState: MainUiState, viewModel: MainScreenViewModel, showTitle: Boolean = true) {
    val context = LocalContext.current
    val pathState = remember {
        androidx.compose.runtime.mutableStateOf(
            uiState.configPath.ifBlank {
                "${com.multiroute.data.ConfigTransfer.EXPORT_DIR}/MultiRoute-config.json"
            }
        )
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showTitle) Text(
                text = stringResource(com.multiroute.R.string.config_card_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = stringResource(com.multiroute.R.string.config_card_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.exportConfigToFile() },
                    enabled = !uiState.isConfigBusy
                ) { Text(stringResource(com.multiroute.R.string.config_export_file)) }
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.exportConfigToClipboard() },
                    enabled = !uiState.isConfigBusy
                ) { Text(stringResource(com.multiroute.R.string.config_export_clipboard)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            // A file picker instead of a path: Storage Access Framework hands us read access to whatever
            // the user chooses, so no root and no typing are needed.
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { viewModel.importConfigFromUri(it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !uiState.isConfigBusy
                ) { Text(stringResource(com.multiroute.R.string.config_import_pick)) }
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.requestImportFromClipboard() },
                    enabled = !uiState.isConfigBusy
                ) { Text(stringResource(com.multiroute.R.string.config_import_clipboard)) }
            }
        }
    }

    if (uiState.showConfigImportConfirm) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isConfigBusy) viewModel.cancelConfigImport() },
            title = { Text(stringResource(com.multiroute.R.string.config_import_title)) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(com.multiroute.R.string.config_import_message))
                    uiState.configImportPreview?.let { preview ->
                        Text(stringResource(com.multiroute.R.string.config_preview_counts,
                            preview.document.assignments.size, preview.document.profiles.size))
                        preview.document.sections.forEach { section ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !uiState.isConfigBusy) { viewModel.toggleConfigImportSection(section) },
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = section in uiState.configImportSections, enabled = !uiState.isConfigBusy,
                                    onCheckedChange = { viewModel.toggleConfigImportSection(section) })
                                Text(stringResource(when (section) {
                                    com.multiroute.data.ConfigTransfer.Section.ASSIGNMENTS -> com.multiroute.R.string.config_section_assignments
                                    com.multiroute.data.ConfigTransfer.Section.SCENARIOS -> com.multiroute.R.string.config_section_scenarios
                                    com.multiroute.data.ConfigTransfer.Section.ROUTING -> com.multiroute.R.string.config_section_routing
                                    com.multiroute.data.ConfigTransfer.Section.DUAL_WIFI -> com.multiroute.R.string.config_section_dual_wifi
                                    com.multiroute.data.ConfigTransfer.Section.HOTSPOT -> com.multiroute.R.string.config_section_hotspot
                                    com.multiroute.data.ConfigTransfer.Section.APPEARANCE -> com.multiroute.R.string.config_section_appearance
                                    com.multiroute.data.ConfigTransfer.Section.TEST_SERVER -> com.multiroute.R.string.config_section_test_server
                                }))
                            }
                        }
                        if (preview.document.ignored > 0) Text(stringResource(com.multiroute.R.string.config_ignored_entries, preview.document.ignored))
                        if (preview.missingApps.isNotEmpty()) Text(stringResource(com.multiroute.R.string.config_missing_apps, preview.missingApps.joinToString()))
                        if (preview.unavailableChannels.isNotEmpty()) Text(stringResource(com.multiroute.R.string.config_offline_channels, preview.unavailableChannels.map { NetworkUtils.savedTargetLabel(context, it) }.joinToString()))
                        if (preview.incompatibleScheme) Text(stringResource(com.multiroute.R.string.config_scheme_fallback))
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.confirmConfigImport() }, enabled = !uiState.isConfigBusy && uiState.configImportSections.isNotEmpty()) {
                    Text(stringResource(com.multiroute.R.string.config_import_confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.cancelConfigImport() }, enabled = !uiState.isConfigBusy) {
                    Text(stringResource(com.multiroute.R.string.ui_cancel))
                }
            }
        )
    }
}

/**
 * 单个通道卡片视图 (ChannelsTab，清晰展示 WLAN SSID 网络名称，点击可查看详情)
 */
@Composable
fun ChannelDetailCard(
    channel: NetworkChannel,
    testResult: String?,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>? = null,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getTransportIcon(channel.transportType),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = NetworkUtils.channelLabel(androidx.compose.ui.platform.LocalContext.current, channel),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (channel.isVpn && channel.underlyingIface != null) {
                                stringResource(com.multiroute.R.string.channel_iface_type, channel.interfaceName, NetworkUtils.channelLabel(androidx.compose.ui.platform.LocalContext.current, channel)) + " · " + stringResource(com.multiroute.R.string.channel_vpn_underlying, channel.underlyingIface)
                            } else {
                                stringResource(com.multiroute.R.string.channel_iface_type, channel.interfaceName, NetworkUtils.channelLabel(androidx.compose.ui.platform.LocalContext.current, channel)) + if (channel.ssid != null) " · SSID: ${channel.ssid}" else ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (channel.isVpn) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.channel_badge_vpn),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    if (channel.isDefault) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.vm_system_default),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else if (channel.isHotspot) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.channel_badge_hotspot),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = stringResource(com.multiroute.R.string.ui_tap_details),
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (channel.isHotspot) {
                        stringResource(com.multiroute.R.string.ui_hotspot_gateway_ip, channel.ipAddress ?: stringResource(com.multiroute.R.string.ui_no_intranet_ip))
                    } else if (channel.isVpn) {
                        stringResource(com.multiroute.R.string.ui_vpn_tunnel_ip, channel.ipAddress ?: stringResource(com.multiroute.R.string.ui_no_intranet_ip))
                    } else {
                        stringResource(com.multiroute.R.string.ui_intranet_ip, channel.ipAddress ?: stringResource(com.multiroute.R.string.ui_no_intranet_ip))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = stringResource(com.multiroute.R.string.ui_tap_details),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 实时吞吐与速率波形
            if (trafficFlow != null) {
                ChannelTrafficCardSection(
                    interfaceName = channel.interfaceName,
                    trafficFlow = trafficFlow
                )
            }

            // 公网出口测试结果
            if (testResult != null) {
                val isFailed = NetworkUtils.isFailure(testResult)
                val isInfo = testResult.startsWith(NetworkUtils.INFO_PREFIX)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        isInfo -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                        else -> Color(0xFFE8F5E9)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                isFailed -> Icons.Default.ErrorOutline
                                isInfo -> Icons.Default.Info
                                else -> Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = when {
                                isFailed -> MaterialTheme.colorScheme.error
                                isInfo -> MaterialTheme.colorScheme.tertiary
                                else -> Color(0xFF2E7D32)
                            }
                        )
                        Text(
                            text = NetworkUtils.egressResultLabel(androidx.compose.ui.platform.LocalContext.current, testResult),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = when {
                                isFailed -> MaterialTheme.colorScheme.error
                                isInfo -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> Color(0xFF1B5E20)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 映射通道类型至官方矢量 Material 图标
 */
fun getTransportIcon(transportType: String): ImageVector {
    return when (transportType) {
        "WLAN" -> Icons.Default.Wifi
        "hotspot" -> Icons.Default.WifiTethering
        "cellular" -> Icons.Default.SignalCellularAlt
        "ethernet" -> Icons.Default.Lan
        "vpn" -> Icons.Default.VpnKey
        else -> Icons.Default.Public
    }
}

@Composable
fun AppIcon(drawable: Drawable?, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val bitmap = remember(drawable) {
        drawable?.runCatching { toBitmap(96, 96) }?.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                modifier = Modifier.size(size * 0.6f),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

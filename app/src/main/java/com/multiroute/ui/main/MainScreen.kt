package com.multiroute.ui.main

import android.content.ClipData
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import com.multiroute.theme.AppMotion
import com.multiroute.theme.RoutingDimensions
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    onItemClick: (androidx.navigation3.runtime.NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshInstalledApps()
    }
    var showDiagnostics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
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
    val pagerState = rememberPagerState(initialPage = uiState.selectedTab.coerceIn(0, 3)) { 4 }
    var inToolDetail by remember { mutableStateOf(false) }
    var isWideContent by remember { mutableStateOf(false) }
    var selectedToolName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var programmaticTarget by remember { mutableStateOf<Int?>(null) }
    val latestSelectedTab by rememberUpdatedState(uiState.selectedTab)
    val tabScrollSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    LaunchedEffect(uiState.selectedTab) {
        val target = uiState.selectedTab.coerceIn(0, 3)
        if (pagerState.currentPage != target) {
            programmaticTarget = target
            try {
                pagerState.animateScrollToPage(
                    page = target,
                    animationSpec = tabScrollSpec
                )
            } finally {
                if (programmaticTarget == target) {
                    programmaticTarget = null
                }
            }
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow {
            if (programmaticTarget != null) null else pagerState.currentPage
        }
            .filterNotNull()
            .collect { page ->
                if (page != latestSelectedTab) {
                    viewModel.setSelectedTab(page)
                }
            }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useCompactTopBar = maxWidth < 420.dp || maxHeight < 500.dp
        val useScenarioIcon = maxWidth < 400.dp ||
            (maxWidth < 480.dp && LocalDensity.current.fontScale >= 1.3f)
        val scenarioButtonMaxWidth = if (maxWidth < 480.dp) 136.dp else 180.dp
        val navigation = when {
            maxWidth >= 840.dp && maxHeight >= 480.dp -> DestinationLayout.StandardRail
            maxWidth >= 600.dp && maxWidth > maxHeight -> DestinationLayout.CompactRail
            else -> DestinationLayout.BottomBar
        }
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                val topBarContainer by animateColorAsState(
                    targetValue = if (uiState.isSelectionMode) MaterialTheme.colorScheme.surfaceContainer
                    else MaterialTheme.colorScheme.surface,
                    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    label = "topBarContainer"
                )
                TopAppBar(
                    expandedHeight = if (useCompactTopBar) 52.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                    title = {
                        Column {
                            AnimatedContent(
                                targetState = uiState.isSelectionMode,
                                transitionSpec = { AppMotion.content() using null },
                                label = "topBarTitle"
                            ) { selectionMode ->
                                val editingName = uiState.scenarioEditId?.let { id ->
                                    uiState.scenarioProfiles.firstOrNull { it.id == id }?.name
                                }
                                Text(
                                    text = if (selectionMode) {
                                        editingName?.let {
                                            stringResource(com.multiroute.R.string.scenario_editing, it)
                                        } ?: stringResource(com.multiroute.R.string.ui_batch_title)
                                    } else "MultiRoute",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    maxLines = if (selectionMode) 2 else 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val subtitleKey = if (uiState.isSelectionMode) -1 else uiState.selectedTab
                            val subtitleIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                            val subtitleOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                            AnimatedContent(
                                targetState = subtitleKey,
                                transitionSpec = {
                                    (fadeIn(subtitleIn) togetherWith fadeOut(subtitleOut)) using null
                                },
                                label = "topBarSubtitle"
                            ) { key ->
                                Text(
                                    text = if (key == -1) {
                                        stringResource(com.multiroute.R.string.ui_selected_apps_count, uiState.selectedRuleKeys.size)
                                    } else {
                                        when (key) {
                                            0 -> stringResource(com.multiroute.R.string.ui_apps_summary, uiState.totalAppsCount, uiState.configuredAppsCount)
                                            1 -> stringResource(com.multiroute.R.string.ui_online_ifaces, uiState.channels.size)
                                            2 -> stringResource(com.multiroute.R.string.tools_subtitle)
                                            else -> stringResource(com.multiroute.R.string.settings_subtitle)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        AnimatedContent(
                            targetState = uiState.isSelectionMode,
                            transitionSpec = { AppMotion.content() },
                            label = "topBarActions"
                        ) { selectionMode ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectionMode) {
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
                                    val actionLabel = editingName?.let {
                                        stringResource(R.string.scenario_editing, it)
                                    } ?: uiState.activeScenarioName?.let {
                                        stringResource(R.string.scenario_current, it)
                                    } ?: stringResource(R.string.scenario_default)
                                    val onScenarioAction: () -> Unit = {
                                        if (uiState.scenarioEditId != null) {
                                            viewModel.finishEditingScenario()
                                        } else {
                                            viewModel.openScenarioSheet()
                                        }
                                    }
                                    val scenarioColor = if (uiState.activeScenarioName != null || editingName != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    if (useScenarioIcon) {
                                        TooltipBox(
                                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                                            tooltip = { PlainTooltip { Text(actionLabel) } },
                                            state = rememberTooltipState()
                                        ) {
                                            FilledTonalIconButton(
                                                onClick = onScenarioAction,
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = if (uiState.activeScenarioName != null || editingName != null) {
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                                    },
                                                    contentColor = scenarioColor
                                                )
                                            ) {
                                                Icon(
                                                    if (uiState.scenarioEditId != null) Icons.Default.Done else Icons.Default.Layers,
                                                    contentDescription = actionLabel
                                                )
                                            }
                                        }
                                    } else {
                                        FilledTonalButton(
                                            onClick = onScenarioAction,
                                            modifier = Modifier.widthIn(max = scenarioButtonMaxWidth),
                                            shapes = ButtonDefaults.shapes(),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = if (uiState.activeScenarioName != null || editingName != null) {
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                                },
                                                contentColor = scenarioColor
                                            )
                                        ) {
                                            Icon(
                                                if (uiState.scenarioEditId != null) Icons.Default.Done else Icons.Default.Layers,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = actionLabel,
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    ModuleStatusButton(uiState) {
                                        showDiagnostics = true
                                        viewModel.refreshEnvironment()
                                    }
                                    AnimatedVisibility(
                                        visible = uiState.selectedTab == 0,
                                        enter = expandHorizontally(
                                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                            expandFrom = Alignment.End
                                        ) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                                        exit = shrinkHorizontally(
                                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                            shrinkTowards = Alignment.End
                                        ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
                                    ) {
                                        RuleActionsMenu(uiState, viewModel)
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarContainer
                    )
                )
            },
            bottomBar = {
                if (navigation == DestinationLayout.BottomBar) {
                    MainDestinations(uiState.selectedTab, viewModel::setSelectedTab, navigation)
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Row(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
                if (navigation != DestinationLayout.BottomBar) {
                    MainDestinations(uiState.selectedTab, viewModel::setSelectedTab, navigation)
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val wideContent = maxWidth >= RoutingDimensions.dualPaneMinWidth
                    SideEffect { isWideContent = wideContent }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        key = { it },
                        userScrollEnabled = !uiState.isSelectionMode && !(pagerState.currentPage == 2 && inToolDetail),
                        flingBehavior = PagerDefaults.flingBehavior(
                            state = pagerState,
                            snapAnimationSpec = tabScrollSpec
                        )
                    ) { tab ->
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
                                2 -> ToolsTab(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onNavigate = onItemClick,
                                    onDetailOpenChanged = { inToolDetail = it },
                                    controlledSelection = selectedToolName,
                                    onControlledSelectionChange = { selectedToolName = it }
                                )
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
                            isForbidden = { viewModel.isVpnSelfLoop(it, app) },
                            onSelectChannel = { channelId ->
                                viewModel.updateRouteChannel(app, channelId)
                                viewModel.closeAppSheet()
                            },
                            onDismiss = { viewModel.closeAppSheet() }
                        )
                    }

                    // 批量分配通道模态底部抽屉 (BatchChannelSelectBottomSheet - 热点下行通道不参与应用分流)
                    if (uiState.showBatchAssignSheet) {
                        val channels = uiState.channels.filter { !it.isHotspot }
                        BatchChannelSelectBottomSheet(
                            selectedCount = uiState.selectedRuleKeys.size,
                            channels = channels,
                            blockedVpnCounts = viewModel.blockedBatchVpnCounts(channels, uiState.selectedRuleKeys),
                            onSelectChannel = { channelId ->
                                viewModel.batchAssignChannel(channelId)
                            },
                            onDismiss = { viewModel.closeBatchAssignSheet() }
                        )
                    }

                    // 通道详细属性与诊断抽屉 (ChannelDetailBottomSheet)
                    val selectedChannel = uiState.selectedChannelForDetail
                        ?.let { selected ->
                            uiState.channels.firstOrNull { it.id == selected.id } ?: selected.takeUnless { it.isVpn }
                        }
                    if (selectedChannel != null) {
                        val channel = selectedChannel
                        val routedApps = remember(channel.id, channel.interfaceName, uiState.apps) {
                            uiState.apps.filter { (it.targetChannelId == channel.id || it.targetChannelId == channel.interfaceName) }
                        }
                        ChannelDetailBottomSheet(
                            channel = channel,
                            channels = uiState.channels,
                            scenario = uiState.scenario,
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
                        uiState = uiState, viewModel = viewModel, wide = wideContent,
                        onDismiss = { showDiagnostics = false },
                        onLogs = { showDiagnostics = false; viewModel.openLogsDialog() }
                    )

                    // 运行与诊断日志查看对话框
                    if (uiState.showLogsDialog) {
                        DiagnosticLogsDialog(
                            logs = uiState.logsContent,
                            isLoading = uiState.isLoadingLogs,
                            onCopy = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("logs", uiState.logsContent)))
                                }
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
                            channels = uiState.channels,
                            onSetManual = viewModel::setScenarioManualMode,
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
        val showToolOverlay = pagerState.currentPage == 2 && !isWideContent
        if (showToolOverlay || (selectedToolName != null && !isWideContent)) {
            ToolDetailOverlay(
                uiState = uiState,
                viewModel = viewModel,
                selectedToolName = if (pagerState.currentPage == 2) selectedToolName else null,
                onClose = {
                    selectedToolName = null
                    if (uiState.networkToolReturnTab != null) {
                        viewModel.returnFromNetworkTool()
                    }
                }
            )
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
fun ToolsTab(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    onNavigate: (androidx.navigation3.runtime.NavKey) -> Unit = {},
    onDetailOpenChanged: (Boolean) -> Unit = {},
    controlledSelection: String? = null,
    onControlledSelectionChange: ((String?) -> Unit)? = null
) {
    SettingsToolsHub(
        uiState = uiState,
        viewModel = viewModel,
        tools = true,
        onNavigate = onNavigate,
        onDetailOpenChanged = onDetailOpenChanged,
        controlledSelection = controlledSelection,
        onControlledSelectionChange = onControlledSelectionChange
    )
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

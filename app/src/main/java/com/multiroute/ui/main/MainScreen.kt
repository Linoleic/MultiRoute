package com.multiroute.ui.main

import com.multiroute.theme.AppMotion
import com.multiroute.theme.MultiRouteTheme
import com.multiroute.theme.RoutingDimensions
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.multiroute.R
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
        val useScenarioIcon = maxWidth < 340.dp ||
            (maxWidth < 480.dp && LocalDensity.current.fontScale >= 1.3f)
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
                    title = {
                        AnimatedContent(
                            targetState = uiState.isSelectionMode,
                            transitionSpec = { AppMotion.content() },
                            label = "topBarTitle"
                        ) { selectionMode ->
                            Column {
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
                                Text(
                                    text = if (selectionMode) {
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
                                            modifier = Modifier.widthIn(max = 180.dp),
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
                                    if (uiState.selectedTab == 0) RuleActionsMenu(uiState, viewModel)
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
                    val wideContent = maxWidth >= 840.dp
                    AnimatedContent(targetState = uiState.selectedTab, modifier = Modifier.fillMaxSize(),
                        transitionSpec = { AppMotion.tab(forward = targetState > initialState) }, label = "mainDestination") { tab ->
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
    }
}

/**
 * Tab 0: 应用分流规则列表 (支持单项配置与批量分配)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppRulesTab(
    uiState: MainUiState,
    viewModel: MainScreenViewModel
) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        val stackChannel = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f
        AppRulesContent(
            uiState = uiState,
            viewModel = viewModel,
            stackChannel = stackChannel,
            modifier = Modifier.widthIn(max = RoutingDimensions.contentMaxWidth).fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppRulesContent(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    stackChannel: Boolean,
    modifier: Modifier
) {
    val toggleColors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
    val toggleShapes = ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.MinHeight)
    val listBottomPadding by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (uiState.isSelectionMode) 96.dp else RoutingDimensions.contentPadding,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "listBottomPadding"
    )

    Box(modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = RoutingDimensions.contentPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(bottom = listBottomPadding)
        ) {
            // 搜索与过滤器
            item(key = "routing-controls") {
                Column(
                    modifier = Modifier.padding(top = RoutingDimensions.rowVerticalPadding, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.ui_search_hint)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                        shape = CircleShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        ),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToggleButton(
                            checked = uiState.filterChannelId == null,
                            onCheckedChange = { viewModel.setFilterChannelId(null) },
                            shapes = toggleShapes,
                            colors = toggleColors,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.filter_all))
                        }
                        ToggleButton(
                            checked = uiState.filterChannelId == "configured",
                            onCheckedChange = {
                                viewModel.setFilterChannelId(
                                    if (uiState.filterChannelId == "configured") null else "configured"
                                )
                            },
                            shapes = toggleShapes,
                            colors = toggleColors,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            if (uiState.filterChannelId == "configured") {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(stringResource(R.string.ui_filter_routed, uiState.configuredAppsCount))
                        }

                        // 动态按在线物理通道过滤 (排除热点下行通道)
                        for (channel in uiState.channels.filter { !it.isHotspot }) {
                            ToggleButton(
                                checked = uiState.filterChannelId == channel.id,
                                onCheckedChange = {
                                    viewModel.setFilterChannelId(
                                        if (uiState.filterChannelId == channel.id) null else channel.id
                                    )
                                },
                                shapes = toggleShapes,
                                colors = toggleColors,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = getTransportIcon(channel.transportType),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(channel.shortName)
                            }
                        }

                        ToggleButton(
                            checked = uiState.showSystemApps,
                            onCheckedChange = { viewModel.toggleShowSystemApps() },
                            shapes = toggleShapes,
                            colors = toggleColors,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.filter_system_apps))
                        }
                    }

                    AnimatedContent(
                        targetState = uiState.isSelectionMode,
                        transitionSpec = { AppMotion.content() },
                        label = "batchHeaderRow"
                    ) { selectionMode ->
                        if (selectionMode) {
                            val allFilteredSelected = uiState.apps.isNotEmpty() &&
                                uiState.apps.all { it.ruleKey in uiState.selectedRuleKeys }
                            TextButton(
                                onClick = viewModel::selectAllFilteredApps,
                                enabled = uiState.apps.isNotEmpty()
                            ) {
                                Text(stringResource(if (allFilteredSelected) R.string.ui_deselect_all else R.string.ui_select_all))
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(RoutingDimensions.rowContentGap)
                            ) {
                                Text(
                                    text = stringResource(R.string.ui_app_list, uiState.apps.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f).semantics { heading() }
                                )
                                TextButton(
                                    onClick = { viewModel.enterSelectionMode() },
                                    enabled = uiState.apps.isNotEmpty()
                                ) {
                                    Text(stringResource(R.string.ui_batch_assign))
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.isSyncing) item(key = "routing-syncing") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    RoutingListStatus(stringResource(R.string.routing_syncing))
                }
            }

            if (uiState.isLoadingApps) item(key = "routing-loading") {
                if (uiState.apps.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ContainedLoadingIndicator()
                        RoutingListStatus(stringResource(R.string.routing_loading_apps))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        RoutingListStatus(stringResource(R.string.routing_loading_apps))
                    }
                }
            } else if (uiState.isInstalledAppsIncomplete) item(key = "routing-incomplete") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RoutingDimensions.rowContentGap)
                ) {
                    RoutingListStatus(
                        stringResource(R.string.routing_apps_incomplete),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::refreshInstalledApps) {
                        Text(stringResource(R.string.routing_retry_apps))
                    }
                }
            }

            when {
                uiState.isLoadingApps && uiState.apps.isEmpty() -> Unit
                uiState.apps.isEmpty() -> item(key = "routing-empty") {
                    val hasFilters = uiState.searchQuery.isNotBlank() || uiState.filterChannelId != null
                    if (!uiState.isInstalledAppsIncomplete || hasFilters) {
                        RoutingListStatus(
                            stringResource(
                                if (hasFilters || uiState.totalAppsCount > 0) R.string.ui_no_matching_apps
                                else R.string.routing_no_apps
                            )
                        )
                    }
                    if (hasFilters) {
                        TextButton(onClick = viewModel::clearSearchAndChannelFilter) {
                            Text(stringResource(R.string.routing_clear_search_filters))
                        }
                    } else if (uiState.totalAppsCount > 0 && !uiState.showSystemApps) {
                        TextButton(onClick = viewModel::toggleShowSystemApps) {
                            Text(stringResource(R.string.filter_system_apps))
                        }
                    }
                }
                else -> itemsIndexed(uiState.apps, key = { _, app -> app.ruleKey }) { index, app ->
                    AppItemRow(
                        app = app,
                        channels = uiState.channels,
                        stackChannel = stackChannel,
                        modifier = Modifier.animateItem(
                            fadeInSpec = AppMotion.itemFade(),
                            fadeOutSpec = AppMotion.itemFade(),
                            placementSpec = AppMotion.itemPlacement()
                        ),
                        index = index,
                        count = uiState.apps.size,
                        isSelectionMode = uiState.isSelectionMode,
                        isSelected = app.ruleKey in uiState.selectedRuleKeys,
                        onToggleSelect = { viewModel.toggleSelectApp(app.ruleKey) },
                        onClick = { viewModel.openAppSheet(app) },
                        onLongClick = { viewModel.enterSelectionMode(app.ruleKey) }
                    )
                }
            }
        }

        // 批量操作悬浮工具栏 (Material 3 Expressive HorizontalFloatingToolbar)
        AnimatedVisibility(
            visible = uiState.isSelectionMode,
            enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it * 2 } +
                scaleIn(initialScale = 0.92f, animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it * 2 } +
                scaleOut(targetScale = 0.92f, animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = RoutingDimensions.contentPadding, vertical = 16.dp)
        ) {
            val selectedCount = uiState.selectedRuleKeys.size
            val hasSelection = selectedCount > 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
                ) {
                    IconButton(onClick = { viewModel.exitSelectionMode() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.ui_cancel)
                        )
                    }
                    IconButton(
                        onClick = viewModel::selectAllFilteredApps,
                        enabled = uiState.apps.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = stringResource(R.string.ui_select_all)
                        )
                    }
                }
                ElevatedButton(
                    onClick = { viewModel.openBatchAssignSheet() },
                    enabled = hasSelection,
                    modifier = Modifier.height(FloatingToolbarDefaults.ContainerSize),
                    shapes = ButtonDefaults.shapes(),
                    elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 3.dp),
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (!hasSelection) stringResource(R.string.ui_select_apps_to_assign)
                        else stringResource(R.string.ui_assign_channel_n, selectedCount),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Tab 1: 在线通道、出口测试与未就绪通道的管理入口
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = RoutingDimensions.contentMaxWidth).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = RoutingDimensions.contentPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
            ) {
                item(key = "channels-heading") {
                    SettingsSectionLabel(stringResource(com.multiroute.R.string.ui_active_channels, uiState.channels.size))
                }

                if (uiState.channels.isEmpty()) {
                    item(key = "channels-empty") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                            modifier = Modifier.animateItem(
                                fadeInSpec = AppMotion.itemFade(),
                                fadeOutSpec = AppMotion.itemFade(),
                                placementSpec = AppMotion.itemPlacement()
                            ),
                            onClick = { viewModel.selectChannelForDetail(channel) }
                        )
                    }
                }

                activationCandidateItems(candidates) { target ->
                    viewModel.openNetworkTool(target)
                }
            }

            // 界面下方：测试节点提示条与诊断操作区
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 当前测试节点提示条（点击切换）
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.openTestServerDialog() },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(com.multiroute.R.string.ui_test_node_2, uiState.testServerConfig.displayName(LocalContext.current)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_change),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Expressive 诊断测试按钮
                    Button(
                        onClick = { viewModel.runChannelTests() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !uiState.isTesting && uiState.channels.isNotEmpty(),
                        shapes = ButtonDefaults.shapes()
                    ) {
                        if (uiState.isTesting) {
                            CircularWavyProgressIndicator(
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
@Composable
private fun RoutingListStatus(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier.padding(vertical = RoutingDimensions.rowVerticalPadding)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private data class ChannelAccentColors(
    val rowContainer: Color,
    val badgeContainer: Color,
    val badgeContent: Color
)

@Composable
private fun channelAccentColors(app: AppItem, current: NetworkChannel?): ChannelAccentColors {
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    if (app.targetChannelId == CHANNEL_DEFAULT) {
        return ChannelAccentColors(
            rowContainer = base,
            badgeContainer = MaterialTheme.colorScheme.surfaceContainerHigh,
            badgeContent = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    val (tint, badgeContainer, badgeContent) = when {
        current == null -> Triple(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.36f),
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError
        )
        app.targetChannelId.startsWith("wlan") -> Triple(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary
        )
        app.targetChannelId.startsWith("rmnet") || com.multiroute.cellular.CellularTargets.isSim(app.targetChannelId) -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.onTertiary
        )
        else -> Triple(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.50f),
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.onSecondary
        )
    }
    return ChannelAccentColors(
        rowContainer = tint.compositeOver(base),
        badgeContainer = badgeContainer,
        badgeContent = badgeContent
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppItemRow(
    app: AppItem,
    channels: List<NetworkChannel>,
    stackChannel: Boolean,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val isCustom = app.targetChannelId != CHANNEL_DEFAULT
    val current = channels.firstOrNull { it.id == app.targetChannelId || it.interfaceName == app.targetChannelId }
    val accent = channelAccentColors(app, current)
    val animatedRowContainer by animateColorAsState(
        targetValue = accent.rowContainer,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "appRowContainer"
    )
    val colors = ListItemDefaults.segmentedColors(
        containerColor = animatedRowContainer,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedSupportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedLeadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTrailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
    val titleStyle = if (isCustom) {
        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.bodyLarge
    }
    val leading: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                    expandHorizontally(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        expandFrom = Alignment.Start
                    ),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                    shrinkHorizontally(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        shrinkTowards = Alignment.Start
                    )
            ) {
                // Keep trailing spacing inside AnimatedVisibility so Row.spacedBy doesn't
                // stay present until exit finishes and snap the icon/text left in a second step.
                Box(Modifier.padding(end = 12.dp)) {
                    Checkbox(checked = isSelected, onCheckedChange = null)
                }
            }
            AppIcon(drawable = app.icon, size = 36.dp)
        }
    }
    val supporting: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(RoutingDimensions.textGap)) {
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (stackChannel) AppChannelBadge(app, current, accent, isSelected)
        }
    }
    val trailing: (@Composable () -> Unit)? = if (!stackChannel) {
        { AppChannelBadge(app, current, accent, isSelected) }
    } else {
        null
    }

    // Keep a single SegmentedListItem call site across normal and selection modes so
    // the checkbox AnimatedVisibility and selection colors animate continuously without remounting.
    SegmentedListItem(
        selected = isSelectionMode && isSelected,
        onClick = { if (isSelectionMode) onToggleSelect() else onClick() },
        onLongClick = { if (isSelectionMode) onToggleSelect() else onLongClick() },
        onLongClickLabel = if (isSelectionMode) null else stringResource(R.string.routing_select_for_batch),
        shapes = shapes,
        modifier = modifier.then(
            if (isSelectionMode) {
                Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(isSelected)
                }
            } else {
                Modifier
            }
        ),
        colors = colors,
        leadingContent = leading,
        supportingContent = supporting,
        trailingContent = trailing,
        content = {
            Text(
                text = app.appName,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppChannelBadge(
    app: AppItem,
    current: NetworkChannel?,
    accent: ChannelAccentColors,
    isSelected: Boolean
) {
    val context = LocalContext.current
    AnimatedContent(
        targetState = app.targetChannelId,
        transitionSpec = { AppMotion.content() },
        label = "channelBadge"
    ) { targetChannelId ->
        if (targetChannelId == CHANNEL_DEFAULT) {
            Text(
                text = stringResource(R.string.vm_system_default),
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val badgeContainer by animateColorAsState(
                targetValue = accent.badgeContainer,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "badgeContainer"
            )
            val badgeContent by animateColorAsState(
                targetValue = accent.badgeContent,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "badgeContent"
            )
            val label = if (current != null) NetworkUtils.channelLabel(context, current)
            else stringResource(R.string.ui_channel_offline_short, NetworkUtils.savedTargetLabel(context, targetChannelId))
            Surface(
                shape = CircleShape,
                color = badgeContainer,
                contentColor = badgeContent
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (current != null) getTransportIcon(current.transportType) else Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Preview(name = "Routing rows, compact large text", locale = "zh-rCN", widthDp = 360, fontScale = 1.5f, showBackground = true)
@Preview(name = "Routing rows, tablet dark", widthDp = 760, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun AppItemRowPreview() {
    MultiRouteTheme(dynamicColor = false) {
        Surface {
            BoxWithConstraints {
                val stackChannel = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                ) {
                    AppItemRow(
                        app = AppItem("com.example.reader", "Reader", null, targetChannelId = CHANNEL_DEFAULT, isSystemApp = false),
                        channels = emptyList(),
                        stackChannel = stackChannel,
                        index = 0,
                        count = 2
                    )
                    AppItemRow(
                        app = AppItem(
                            "com.example.long.application.name",
                            "Network Diagnostics for the Work Profile",
                            null,
                            targetChannelId = "wlan1",
                            isSystemApp = false
                        ),
                        channels = emptyList(),
                        stackChannel = stackChannel,
                        index = 1,
                        count = 2,
                        isSelectionMode = true,
                        isSelected = true
                    )
                }
            }
        }
    }
}

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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelDetailCard(
    channel: NetworkChannel,
    testResult: String?,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cardContainer by animateColorAsState(
        targetValue = if (channel.isDefault) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "channelCardContainer"
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = cardContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getTransportIcon(channel.transportType),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.vm_system_default),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary,
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
            AnimatedVisibility(
                visible = testResult != null,
                enter = AppMotion.expand(),
                exit = AppMotion.collapse()
            ) {
                testResult?.let { resultText ->
                    val isFailed = NetworkUtils.isFailure(resultText)
                    val isInfo = resultText.startsWith(NetworkUtils.INFO_PREFIX)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = when {
                            isFailed -> MaterialTheme.colorScheme.errorContainer
                            isInfo -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    isFailed -> MaterialTheme.colorScheme.onErrorContainer
                                    isInfo -> MaterialTheme.colorScheme.onTertiaryContainer
                                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                            Text(
                                text = NetworkUtils.egressResultLabel(LocalContext.current, resultText),
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    isFailed -> MaterialTheme.colorScheme.onErrorContainer
                                    isInfo -> MaterialTheme.colorScheme.onTertiaryContainer
                                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                        }
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

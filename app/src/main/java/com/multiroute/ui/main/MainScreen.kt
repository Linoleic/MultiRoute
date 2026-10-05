package com.multiroute.ui.main

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.model.TestServerConfig
import com.multiroute.model.TestServerPreset
import com.multiroute.data.RouteConfigProvider
import com.multiroute.util.ModuleStatus
import com.multiroute.util.SuHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (androidx.navigation3.runtime.NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (uiState.isSelectionMode) stringResource(com.multiroute.R.string.ui_batch_title) else "MultiRoute",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (uiState.isSelectionMode) {
                                stringResource(com.multiroute.R.string.ui_selected_apps_count, uiState.selectedRuleKeys.size)
                            } else {
                                when (uiState.selectedTab) {
                                    0 -> stringResource(com.multiroute.R.string.ui_apps_summary, uiState.totalAppsCount, uiState.configuredAppsCount)
                                    1 -> stringResource(com.multiroute.R.string.ui_online_ifaces, uiState.channels.size)
                                    else -> stringResource(com.multiroute.R.string.ui_module_routing_state)
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
                        IconButton(onClick = { viewModel.refreshEnvironment() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(com.multiroute.R.string.cd_refresh)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                windowInsets = NavigationBarDefaults.windowInsets
            ) {
                NavigationBarItem(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.setSelectedTab(0) },
                    icon = { Icon(Icons.Default.Apps, contentDescription = stringResource(com.multiroute.R.string.cd_routing)) },
                    label = { Text(stringResource(com.multiroute.R.string.tab_routing)) }
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    icon = { Icon(Icons.Default.Hub, contentDescription = stringResource(com.multiroute.R.string.cd_channels)) },
                    label = { Text(stringResource(com.multiroute.R.string.tab_channels)) }
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == 2,
                    onClick = { viewModel.setSelectedTab(2) },
                    icon = { Icon(Icons.Default.Tune, contentDescription = stringResource(com.multiroute.R.string.cd_settings)) },
                    label = { Text(stringResource(com.multiroute.R.string.tab_settings)) }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState.selectedTab) {
                0 -> AppRulesTab(
                    uiState = uiState,
                    viewModel = viewModel
                )
                1 -> ChannelsTab(
                    uiState = uiState,
                    viewModel = viewModel
                )
                2 -> SettingsTab(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }

            // 选择通道的模态底部抽屉 (ModalBottomSheet)
            if (uiState.selectedAppForSheet != null) {
                val app = uiState.selectedAppForSheet!!
                ChannelSelectBottomSheet(
                    app = app,
                    channels = uiState.channels,
                    onSelectChannel = { channelId ->
                        viewModel.updateRouteChannel(app, channelId)
                        viewModel.closeAppSheet()
                    },
                    onDismiss = { viewModel.closeAppSheet() }
                )
            }

            // 批量分配通道模态底部抽屉 (BatchChannelSelectBottomSheet)
            if (uiState.showBatchAssignSheet) {
                BatchChannelSelectBottomSheet(
                    selectedCount = uiState.selectedRuleKeys.size,
                    channels = uiState.channels,
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
                    uiState.apps.filter { it.targetChannelId == channel.id }
                }
                ChannelDetailBottomSheet(
                    channel = channel,
                    routedApps = routedApps,
                    testResult = uiState.testResults[channel.id],
                    isTestingSingle = uiState.isTestingSingleChannel,
                    testServerName = uiState.testServerConfig.activeDisplayName,
                    testServerUrl = uiState.testServerConfig.activeUrl,
                    onTestChannel = { viewModel.testSingleChannel(channel) },
                    onDismiss = { viewModel.closeChannelDetail() }
                )
            }

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

                        // 动态按在线物理通道过滤 (带具体 Wi-Fi 名称展示)
                        for (channel in uiState.channels) {
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
 * Tab 1: 网络通道状态与并发出口测试（纯粹展示通道与诊断，无多余环境卡片）
 */
@Composable
fun ChannelsTab(
    uiState: MainUiState,
    viewModel: MainScreenViewModel
) {
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(com.multiroute.R.string.ui_active_channels, uiState.channels.size),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
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
                        onClick = { viewModel.selectChannelForDetail(channel) }
                    )
                }
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
                                text = stringResource(com.multiroute.R.string.ui_test_node_2, uiState.testServerConfig.activeDisplayName),
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
fun SettingsTab(
    uiState: MainUiState,
    viewModel: MainScreenViewModel
) {
    var showClearDialog by remember { mutableStateOf(false) }
    val diag = uiState.diagnosticInfo

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            // 模块与路由核心诊断卡片
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(com.multiroute.R.string.ui_module_channel_state),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // 1. LSPosed 模块状态（精确：区分未激活 / hook 未就绪 / 旧版本待重启 / 记录过期）
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(com.multiroute.R.string.ui_module_state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        val moduleStatus = diag?.moduleStatus
                        val isHealthy = moduleStatus == ModuleStatus.ACTIVE
                        val isBlocking = moduleStatus == null ||
                                moduleStatus == ModuleStatus.PARTIAL ||
                                moduleStatus == ModuleStatus.OUTDATED ||
                                moduleStatus == ModuleStatus.STALE ||
                                moduleStatus == ModuleStatus.NOT_ACTIVE
                        val statusTint = when {
                            isHealthy -> MaterialTheme.colorScheme.primary
                            isBlocking -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isHealthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = statusTint,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (moduleStatus != null) {
                                    SuHelper.moduleStatusLabel(moduleStatus)
                                } else {
                                    stringResource(com.multiroute.R.string.ui_checking_module)
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = statusTint
                                )
                            )
                        }
                        val statusDetail = diag?.moduleStatusDetail
                        if (!statusDetail.isNullOrEmpty()) {
                            Text(
                                text = statusDetail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    // 2. Root 运行环境检测
                    DiagnosticItem(
                        label = stringResource(com.multiroute.R.string.settings_root_env),
                        value = if (diag?.isRootGranted == true) stringResource(com.multiroute.R.string.diag_root_ready, diag?.suVersion?.ifEmpty { "KernelSU" } ?: "KernelSU") else stringResource(com.multiroute.R.string.diag_root_missing)
                    )

                    // 3. 内核策略路由生效规则统计
                    DiagnosticItem(
                        label = stringResource(com.multiroute.R.string.settings_kernel_rules),
                        value = if ((diag?.kernelRulesCount ?: 0) > 0) "${diag?.kernelRulesCount} 条分流规则生效中 (pref 14500)" else stringResource(com.multiroute.R.string.diag_kernel_rules_none)
                    )

                    // 4. 系统蜂窝首选 UID
                    DiagnosticItem(
                        label = stringResource(com.multiroute.R.string.settings_cellular_uids_2),
                        value = diag?.mobileDataPreferredUids?.ifEmpty { stringResource(com.multiroute.R.string.diag_not_set) } ?: stringResource(com.multiroute.R.string.diag_not_set)
                    )
                }
            }
        }

        item {
            // 外观与语言：主题（跟随系统/浅色/深色）+ 动态取色 + 语言
            val ctx = LocalContext.current
            val host = ctx as? android.app.Activity
            val themeMode = RouteConfigProvider.getThemeMode(ctx)
            val language = RouteConfigProvider.getLanguage(ctx)
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_appearance_language),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Text(
                        text = stringResource(com.multiroute.R.string.settings_theme),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to stringResource(com.multiroute.R.string.settings_follow_system), "light" to stringResource(com.multiroute.R.string.settings_theme_light), "dark" to stringResource(com.multiroute.R.string.settings_theme_dark)).forEach { (mode, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = themeMode == mode,
                                onClick = {
                                    RouteConfigProvider.setThemeMode(ctx, mode)
                                    com.multiroute.theme.ThemeSettings.mode = mode
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(com.multiroute.R.string.settings_dynamic_color),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Switch(
                            checked = RouteConfigProvider.isDynamicColor(ctx),
                            onCheckedChange = {
                                RouteConfigProvider.setDynamicColor(ctx, it)
                                com.multiroute.theme.ThemeSettings.dynamicColor = it
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Text(
                        text = stringResource(com.multiroute.R.string.settings_language),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("" to stringResource(com.multiroute.R.string.settings_follow_system), "zh-CN" to stringResource(com.multiroute.R.string.settings_language_zh), "en" to stringResource(com.multiroute.R.string.settings_language_en)).forEach { (tag, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = language == tag,
                                onClick = {
                                    RouteConfigProvider.setLanguage(ctx, tag)
                                    host?.recreate()
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_i18n_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        item {
            // 网络运行参数设置 (蜂窝常活)
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_network),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(com.multiroute.R.string.settings_keep_cellular),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = stringResource(com.multiroute.R.string.settings_keep_cellular_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = uiState.isMobileDataAlwaysOn,
                            onCheckedChange = { viewModel.toggleMobileDataAlwaysOn() }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(com.multiroute.R.string.settings_keep_slave_wifi),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = stringResource(com.multiroute.R.string.settings_keep_slave_wifi_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = stringResource(com.multiroute.R.string.settings_keep_slave_wifi_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = uiState.isKeepSlaveWifiScreenOff,
                            onCheckedChange = { viewModel.toggleKeepSlaveWifiScreenOff() }
                        )
                    }
                }
            }
        }

        item {
            // 公网出口测试设置
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_egress),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.testServerConfig.activeDisplayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = uiState.testServerConfig.activeUrl,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        FilledTonalButton(
                            onClick = { viewModel.openTestServerDialog() },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(com.multiroute.R.string.filter_configured))
                        }
                    }

                    Text(
                        text = stringResource(com.multiroute.R.string.ui_test_server_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        item {
            // 诊断日志与维护操作
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(com.multiroute.R.string.ui_diag_maintenance),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Button(
                        onClick = { viewModel.openLogsDialog() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Article, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(com.multiroute.R.string.ui_view_log))
                    }

                    OutlinedButton(
                        onClick = { viewModel.forceSyncAllRules() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !uiState.isSyncing
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (uiState.isSyncing) stringResource(com.multiroute.R.string.ui_syncing_kernel) else stringResource(com.multiroute.R.string.ui_force_resync))
                    }

                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(com.multiroute.R.string.ui_clear_all_rules))
                    }
                }
            }
        }

        item {
            // 关于卡片
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_about),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        val context = LocalContext.current
                        val packageInfo = remember(context) {
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                                } else {
                                    @Suppress("DEPRECATION")
                                    context.packageManager.getPackageInfo(context.packageName, 0)
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }
                        val versionName = packageInfo?.versionName ?: "1.0.0"
                        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            packageInfo?.longVersionCode ?: 1L
                        } else {
                            @Suppress("DEPRECATION")
                            packageInfo?.versionCode?.toLong() ?: 1L
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Text(
                                text = "v$versionName ($versionCode)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Android 多网络并发与分应用策略路由管理模块。支持主/副 Wi-Fi (双 WLAN)、移动蜂窝与有线以太网的多网并发与分应用策略分流。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        lineHeight = 18.sp
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_system_support),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "Android 10 - 17",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_open_source_license),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "GNU GPL-3.0",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    val context = LocalContext.current
                    Surface(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Linoleic/MultiRoute"))
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = stringResource(com.multiroute.R.string.ui_github_repo),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = "https://github.com/Linoleic/MultiRoute",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = stringResource(com.multiroute.R.string.cd_open_link),
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(com.multiroute.R.string.ui_confirm_clear_title)) },
            text = { Text(stringResource(com.multiroute.R.string.ui_clear_rules_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllRules()
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(com.multiroute.R.string.ui_confirm_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(com.multiroute.R.string.ui_cancel))
                }
            }
        )
    }
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
    val currentChannel = channels.firstOrNull { it.id == app.targetChannelId }

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
                    app.targetChannelId.startsWith("rmnet") -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
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
                        app.targetChannelId.startsWith("rmnet") -> MaterialTheme.colorScheme.tertiaryContainer
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
                            currentChannel != null -> currentChannel.shortName
                            else -> "${app.targetChannelId} (离线)"
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
 * 底部通道选择抽屉 (ModalBottomSheet)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSelectBottomSheet(
    app: AppItem,
    channels: List<NetworkChannel>,
    onSelectChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppIcon(drawable = app.icon, size = 48.dp)
                Column {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = stringResource(com.multiroute.R.string.ui_assign_channel),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            // 1. 系统默认选项
            SelectableChannelOption(
                title = stringResource(com.multiroute.R.string.ui_default_follow_system),
                subtitle = stringResource(com.multiroute.R.string.ui_default_follow_system_desc),
                icon = Icons.Default.Language,
                isSelected = app.targetChannelId == CHANNEL_DEFAULT,
                onClick = { onSelectChannel(CHANNEL_DEFAULT) }
            )

            // 2. 动态扫描到的活跃通道选项
            for (channel in channels) {
                val wifiBadge = if (channel.ssid != null) " [${channel.ssid}]" else ""
                SelectableChannelOption(
                    title = channel.displayName,
                    subtitle = "接口: ${channel.interfaceName}$wifiBadge · 内网: ${channel.ipAddress ?: "无IP"}${if (channel.isDefault) " (当前默认通道)" else ""}",
                    icon = getTransportIcon(channel.transportType),
                    isSelected = app.targetChannelId == channel.id,
                    onClick = { onSelectChannel(channel.id) }
                )
            }

            // 3. 如果当前应用绑定的通道目前处于离线状态
            if (app.targetChannelId != CHANNEL_DEFAULT && channels.none { it.id == app.targetChannelId }) {
                SelectableChannelOption(
                    title = "${app.targetChannelId} (当前通道已离线)",
                    subtitle = stringResource(com.multiroute.R.string.ui_iface_offline_note),
                    icon = Icons.Default.CloudOff,
                    isSelected = true,
                    onClick = {}
                )
            }
        }
    }
}

/**
 * 批量分配通道模态底部抽屉 (ModalBottomSheet)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchChannelSelectBottomSheet(
    selectedCount: Int,
    channels: List<NetworkChannel>,
    onSelectChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Column {
                    Text(
                        text = "批量分配网络通道",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = stringResource(com.multiroute.R.string.ui_batch_assign_desc, selectedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = stringResource(com.multiroute.R.string.ui_choose_target_channel),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            // 1. 系统默认选项 (清空/恢复不分流)
            SelectableChannelOption(
                title = stringResource(com.multiroute.R.string.ui_default_no_routing),
                subtitle = stringResource(com.multiroute.R.string.ui_clear_assigned_desc, selectedCount),
                icon = Icons.Default.Language,
                isSelected = false,
                onClick = { onSelectChannel(CHANNEL_DEFAULT) }
            )

            // 2. 动态扫描到的活跃通道选项
            for (channel in channels) {
                val wifiBadge = if (channel.ssid != null) " [${channel.ssid}]" else ""
                SelectableChannelOption(
                    title = channel.displayName,
                    subtitle = "接口: ${channel.interfaceName}$wifiBadge · 内网: ${channel.ipAddress ?: "无IP"}${if (channel.isDefault) " (当前默认通道)" else ""}",
                    icon = getTransportIcon(channel.transportType),
                    isSelected = false,
                    onClick = { onSelectChannel(channel.id) }
                )
            }
        }
    }
}

/**
 * 抽屉中单选通道卡片
 */
@Composable
fun SelectableChannelOption(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
        }
    }
}

/**
 * 单个通道卡片视图 (ChannelsTab，清晰展示 WLAN SSID 网络名称，点击可查看详情)
 */
@Composable
fun ChannelDetailCard(
    channel: NetworkChannel,
    testResult: String?,
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
                            text = channel.displayName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "接口: ${channel.interfaceName} · 类型: ${channel.transportType}${if (channel.ssid != null) " · SSID: ${channel.ssid}" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (channel.isDefault) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = stringResource(com.multiroute.R.string.vm_system_default),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            softWrap = false
                        )
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
                    text = "内网 IP: ${channel.ipAddress ?: "无内网地址"}",
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

            // 公网出口测试结果
            if (testResult != null) {
                val isFailed = testResult.startsWith("测试失败") || testResult == "接口离线"
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isFailed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    else Color(0xFFE8F5E9),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isFailed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isFailed) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                        )
                        Text(
                            text = stringResource(com.multiroute.R.string.ui_egress_prefix, testResult),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isFailed) MaterialTheme.colorScheme.error else Color(0xFF1B5E20)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 网络通道深度属性与独立出口诊断抽屉 (ChannelDetailBottomSheet)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailBottomSheet(
    channel: NetworkChannel,
    routedApps: List<AppItem>,
    testResult: String?,
    isTestingSingle: Boolean,
    testServerName: String = "",
    testServerUrl: String = "",
    onTestChannel: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头部：通道基本信息与状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getTransportIcon(channel.transportType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "底层网络接口: ${channel.interfaceName}${if (channel.ssid != null) " (SSID: ${channel.ssid})" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                if (channel.isDefault) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = stringResource(com.multiroute.R.string.vm_system_default),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(com.multiroute.R.string.ui_close),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 1. 网络链路与拓扑参数
            Text(
                text = stringResource(com.multiroute.R.string.ui_topology),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_iface_type), value = channel.transportType)
                    DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_kernel_iface), value = channel.interfaceName)
                    if (channel.ssid != null) {
                        DetailPropertyRow(label = "Wi-Fi SSID", value = channel.ssid)
                    }
                    DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_primary_ip), value = channel.ipAddress ?: "无")
                    if (channel.allIpAddresses.isNotEmpty()) {
                        DetailPropertyRow(
                            label = stringResource(com.multiroute.R.string.ui_assigned_ips),
                            value = channel.allIpAddresses.joinToString("\n")
                        )
                    }
                    DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_gateway), value = channel.gateway ?: stringResource(com.multiroute.R.string.ui_direct_or_auto))
                    DetailPropertyRow(
                        label = stringResource(com.multiroute.R.string.ui_dns_servers),
                        value = if (channel.dnsServers.isNotEmpty()) channel.dnsServers.joinToString("\n") else stringResource(com.multiroute.R.string.ui_dns_system)
                    )
                    if (!channel.domains.isNullOrEmpty()) {
                        DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_search_domains), value = channel.domains)
                    }
                    DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_mtu), value = if (channel.mtu > 0) "${channel.mtu} 字节" else "未知")
                    DetailPropertyRow(
                        label = stringResource(com.multiroute.R.string.ui_estimated_bandwidth),
                        value = if (channel.downlinkBps > 0 || channel.uplinkBps > 0) {
                            "下行 ${channel.downlinkBps / 1000} Mbps / 上行 ${channel.uplinkBps / 1000} Mbps"
                        } else {
                            stringResource(com.multiroute.R.string.ui_adaptive)
                        }
                    )
                    DetailPropertyRow(
                        label = stringResource(com.multiroute.R.string.ui_metered),
                        value = if (channel.isMetered) stringResource(com.multiroute.R.string.ui_metered_yes) else stringResource(com.multiroute.R.string.ui_metered_no)
                    )
                }
            }

            // 2. 当前绑定此通道的应用
            Text(
                text = stringResource(com.multiroute.R.string.ui_routed_apps, routedApps.size),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                if (routedApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(com.multiroute.R.string.ui_no_routed_apps),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        routedApps.forEach { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppIcon(drawable = app.icon, size = 32.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
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
                            }
                        }
                    }
                }
            }

            // 3. 通道独立公网出口测试
            Text(
                text = stringResource(com.multiroute.R.string.ui_egress_diag),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (testResult != null) {
                        val isFailed = testResult.startsWith("测试失败") || testResult == "接口离线"
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isFailed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            else Color(0xFFE8F5E9),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFailed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isFailed) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                                )
                                Text(
                                    text = stringResource(com.multiroute.R.string.ui_egress_result, testResult),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isFailed) MaterialTheme.colorScheme.error else Color(0xFF1B5E20)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(com.multiroute.R.string.ui_not_tested),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    if (testServerName.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_test_node_short, testServerName),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = onTestChannel,
                        enabled = !isTestingSingle,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isTestingSingle) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(com.multiroute.R.string.ui_probing_channel, channel.interfaceName))
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(com.multiroute.R.string.ui_test_channel_egress, channel.interfaceName))
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(com.multiroute.R.string.ui_close))
            }
        }
    }
}

@Composable
fun DetailPropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.widthIn(min = 100.dp, max = 130.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

/**
 * 运行与诊断日志查看对话框
 */
@Composable
fun DiagnosticLogsDialog(
    logs: String,
    isLoading: Boolean,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Article,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(com.multiroute.R.string.ui_runtime_log),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 日志内容文本框（深色背景、等宽字体）
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1E1E1E)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        val verticalScroll = rememberScrollState()
                        val horizontalScroll = rememberScrollState()

                        Text(
                            text = logs,
                            color = Color(0xFFD4D4D4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .verticalScroll(verticalScroll)
                                .horizontalScroll(horizontalScroll)
                        )
                    }
                }

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(com.multiroute.R.string.ui_close))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onCopy,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(com.multiroute.R.string.ui_copy_log))
                    }
                }
            }
        }
    }
}

/**
 * 公网出口测试服务器选择与自定义配置对话框
 */
@Composable
fun TestServerConfigDialog(
    currentConfig: TestServerConfig,
    onSave: (presetId: String, customUrl: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedId by remember { mutableStateOf(currentConfig.selectedPresetId) }
    var customUrlInput by remember { mutableStateOf(currentConfig.customUrl.ifEmpty { "http://" }) }
    var isUrlError by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 对话框标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(com.multiroute.R.string.ui_test_server),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Text(
                    text = stringResource(com.multiroute.R.string.ui_egress_diag_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 节点选项列表 (限制最大高度并支持内部滚动)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TestServerConfig.PRESETS.forEach { preset ->
                        val isSelected = selectedId == preset.id
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedId = preset.id },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedId = preset.id }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = preset.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = preset.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }

                    // 自定义节点选项
                    val isCustomSelected = selectedId == TestServerConfig.PRESET_CUSTOM
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedId = TestServerConfig.PRESET_CUSTOM },
                        color = if (isCustomSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = isCustomSelected,
                                    onClick = { selectedId = TestServerConfig.PRESET_CUSTOM }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(com.multiroute.R.string.ui_custom_test_node),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isCustomSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(com.multiroute.R.string.ui_custom_test_node_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            if (isCustomSelected) {
                                OutlinedTextField(
                                    value = customUrlInput,
                                    onValueChange = {
                                        customUrlInput = it
                                        isUrlError = false
                                    },
                                    label = { Text(stringResource(com.multiroute.R.string.ui_target_url)) },
                                    placeholder = { Text(stringResource(com.multiroute.R.string.ui_custom_url_hint)) },
                                    isError = isUrlError,
                                    supportingText = {
                                        if (isUrlError) {
                                            Text(stringResource(com.multiroute.R.string.ui_url_error))
                                        } else {
                                            Text(stringResource(com.multiroute.R.string.ui_target_url_desc))
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 对话框底部操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            selectedId = TestServerConfig.DEFAULT_PRESET_ID
                            customUrlInput = "http://"
                            isUrlError = false
                        }
                    ) {
                        Text(stringResource(com.multiroute.R.string.ui_reset_default))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(com.multiroute.R.string.ui_cancel))
                        }
                        Button(
                            onClick = {
                                if (selectedId == TestServerConfig.PRESET_CUSTOM) {
                                    val trimmed = customUrlInput.trim()
                                    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                                        isUrlError = true
                                        return@Button
                                    }
                                    onSave(selectedId, trimmed)
                                } else {
                                    onSave(selectedId, null)
                                }
                            }
                        ) {
                            Text(stringResource(com.multiroute.R.string.ui_save_apply))
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
        "蜂窝" -> Icons.Default.SignalCellularAlt
        "以太网" -> Icons.Default.Lan
        "VPN" -> Icons.Default.VpnKey
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
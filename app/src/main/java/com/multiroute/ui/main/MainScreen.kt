package com.multiroute.ui.main

import android.content.ClipData
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import com.multiroute.data.RuleTarget
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.theme.AppMotion
import com.multiroute.theme.RoutingDimensions
import com.multiroute.util.ProxyGatewayFormat
import com.multiroute.util.RouteRuleBuilder
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
        if (uiState.selectedTab == 1) viewModel.refreshTransparentProxyDiagnostics()
    }
    var showDiagnostics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showChannelTests by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
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
    var vpnForUpstreamPicker by remember { mutableStateOf<NetworkChannel?>(null) }
    var showHotspotUpstreamPicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
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
                                            painter = painterResource(R.drawable.ic_symbol_close),
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
                                    } ?: if (uiState.scenarioMode == com.multiroute.util.ScenarioEngine.SelectionMode.DISABLED) {
                                        stringResource(R.string.scenario_mode_disabled_chip)
                                    } else {
                                        stringResource(R.string.scenario_default)
                                    }
                                    val onScenarioAction: () -> Unit = {
                                        if (uiState.scenarioEditId != null) {
                                            viewModel.finishEditingScenario()
                                        } else {
                                            viewModel.openScenarioSheet()
                                        }
                                    }
                                    val isHighlighted = uiState.activeScenarioName != null || editingName != null
                                    val scenarioColor = if (isHighlighted) {
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
                                                    containerColor = if (isHighlighted) {
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                                    },
                                                    contentColor = scenarioColor
                                                )
                                            ) {
                                                Icon(
                                                    painterResource(
                                                        if (uiState.scenarioEditId != null) R.drawable.ic_symbol_check
                                                        else R.drawable.ic_symbol_layers
                                                    ),
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
                                                painterResource(
                                                    if (uiState.scenarioEditId != null) R.drawable.ic_symbol_check
                                                    else R.drawable.ic_symbol_layers
                                                ),
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
                                        viewModel.refreshTransparentProxyDiagnostics(force = true)
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
                                    AnimatedVisibility(
                                        visible = uiState.selectedTab == 1,
                                        enter = expandHorizontally(
                                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                            expandFrom = Alignment.End
                                        ) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                                        exit = shrinkHorizontally(
                                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                            shrinkTowards = Alignment.End
                                        ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
                                    ) {
                                        val testLabel = stringResource(
                                            if (uiState.isTesting) R.string.ui_testing_channels else R.string.channel_egress_tests
                                        )
                                        TooltipBox(
                                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                                            tooltip = { PlainTooltip { Text(testLabel) } },
                                            state = rememberTooltipState()
                                        ) {
                                            IconButton(onClick = { showChannelTests = true }) {
                                                if (uiState.isTesting) {
                                                    CircularWavyProgressIndicator(Modifier.size(20.dp))
                                                } else {
                                                    Icon(
                                                        painterResource(R.drawable.ic_symbol_travel_explore),
                                                        contentDescription = testLabel
                                                    )
                                                }
                                            }
                                        }
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
                                    showChannelTests = showChannelTests,
                                    onShowChannelTestsChange = { showChannelTests = it },
                                    onOpenProxyGateway = {
                                        selectedToolName = PreferencePage.PROXY_UPSTREAM.name
                                        viewModel.setSelectedTab(2)
                                    }
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
                        val proxy by viewModel.proxyUpstream.state.collectAsStateWithLifecycle()
                        val showGateway = uiState.scenarioEditId == null && RouteRuleBuilder.isValidUid(app.uid)
                        val canConfigureGateway = showGateway && uiState.channels.none { it.isVpn && it.vpnOwnerUid == app.uid }
                        fun openGatewayPreview() {
                            val vpn = uiState.supportedAndroidVpn
                            val preferredFormat = vpn?.takeIf { it.id == app.targetChannelId || it.interfaceName == app.targetChannelId }
                                ?.let { ProxyGatewayFormat.MIHOMO_ANDROID_VPN }
                            viewModel.closeAppSheet()
                            viewModel.proxyUpstream.requestGatewayPreview(RuleTarget(app.ruleKey, app.uid), preferredFormat)
                            selectedToolName = PreferencePage.PROXY_UPSTREAM.name
                            viewModel.setSelectedTab(2)
                        }
                        ChannelSelectBottomSheet(
                            app = app,
                            channels = uiState.channels.filter { !it.isHotspot },
                            isForbidden = { viewModel.isVpnSelfLoop(it, app) },
                            mayBypassTransparentProxy = uiState::mayBypassTransparentProxy,
                            physicalVpnBypassWarning = uiState::physicalVpnBypassWarning,
                            gatewayChannels = if (canConfigureGateway) uiState.channels.filter {
                                it.proxyUpstreamKey() != null
                            } else emptyList(),
                            gatewayMappings = proxy.gatewayMappings,
                            gatewayTarget = proxy.gatewayAppTargets[app.ruleKey].takeIf { showGateway },
                            gatewayBusy = proxy.gatewayBusy,
                            gatewayEnabled = canConfigureGateway,
                            gatewayNotice = uiState.proxyGatewayAvailabilityNotice(
                                hasSavedMappings = proxy.gatewayMappings.isNotEmpty()
                            ),
                            availableOutbounds = proxy.availableOutbounds,
                            compatibleVpn = uiState.supportedAndroidVpn,
                            onSelectGateway = { target, outboundTag, onSaved ->
                                viewModel.proxyUpstream.saveGatewayAppTarget(app.ruleKey, target, outboundTag) { saved ->
                                    if (saved) viewModel.closeAppSheet()
                                    onSaved(saved)
                                    scope.launch {
                                        val message = when {
                                            !saved -> R.string.proxy_gateway_app_save_failed
                                            target == null -> R.string.proxy_gateway_app_removed
                                            app.targetChannelId != CHANNEL_DEFAULT &&
                                                uiState.supportedAndroidVpn?.let {
                                                    it.id == app.targetChannelId || it.interfaceName == app.targetChannelId
                                                } != true -> R.string.proxy_gateway_app_draft_saved
                                            else -> R.string.proxy_gateway_app_saved_hint
                                        }
                                        val action = if (saved && target != null) importContext.getString(R.string.proxy_gateway_open_tool) else null
                                        val result = snackbarHostState.showSnackbar(
                                            importContext.getString(message),
                                            actionLabel = action,
                                            duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            openGatewayPreview()
                                        }
                                    }
                                }
                            },
                            onPreviewGateway = ::openGatewayPreview,
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
                            mayBypassTransparentProxy = uiState::mayBypassTransparentProxy,
                            physicalVpnBypassWarning = uiState::physicalVpnBypassWarning,
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
                        val hotspotStatus by com.multiroute.wifi.DualWifiManager.status.collectAsState()
                        val routedApps = remember(channel.id, channel.interfaceName, uiState.routing.baseApps) {
                            uiState.routing.baseApps.filter { it.targetChannelId == channel.id || it.targetChannelId == channel.interfaceName }
                        }
                        val effectiveTestServer = uiState.testServerConfig.effectiveForChannel(channel.isVpn)
                        ChannelDetailBottomSheet(
                            channel = channel,
                            channels = uiState.channels,
                            scenario = uiState.scenario,
                            routedApps = routedApps,
                            testResult = uiState.testResults[channel.id],
                            isTestingSingle = uiState.isTestingSingleChannel,
                            trafficFlow = viewModel.channelTraffic,
                            testServerName = effectiveTestServer.displayName(androidx.compose.ui.platform.LocalContext.current),
                            testServerUrl = effectiveTestServer.activeUrl,
                            mayBypassTransparentProxy = uiState.mayBypassTransparentProxy(channel),
                            physicalVpnBypassWarning = uiState.physicalVpnBypassWarning(channel),
                            hotspotStatus = hotspotStatus,
                            onChangeHotspotUpstream = if (channel.isHotspot) {
                                {
                                    viewModel.closeChannelDetail()
                                    showHotspotUpstreamPicker = true
                                }
                            } else null,
                            onOpenHotspotSettings = if (channel.isHotspot) {
                                {
                                    viewModel.closeChannelDetail()
                                    viewModel.openHotspotSettings()
                                }
                            } else null,
                            onChangeVpnUpstream = if (channel.isVpn) {
                                {
                                    viewModel.closeChannelDetail()
                                    vpnForUpstreamPicker = channel
                                }
                            } else null,
                            onTestChannel = { viewModel.testSingleChannel(channel) },
                            onDismiss = { viewModel.closeChannelDetail() }
                        )
                    }

                    vpnForUpstreamPicker?.let { selected ->
                        val vpn = uiState.channels.firstOrNull {
                            it.id == selected.id && it.interfaceName == selected.interfaceName &&
                                it.isVpn && it.vpnOwnerUid == selected.vpnOwnerUid
                        }
                        if (vpn != null) {
                            val upstream = rememberVpnUpstream(vpn, uiState.channels, uiState.scenario)
                            val owner = upstream.ownerTarget
                            if (owner != null) {
                                VpnUpstreamPicker(
                                    upstream = upstream,
                                    channels = uiState.channels.filter { it.isVpnUpstream() },
                                    onSelectChannel = { target ->
                                        vpnForUpstreamPicker = null
                                        viewModel.updateVpnUpstream(vpn, owner, target)
                                    },
                                    onDismiss = { vpnForUpstreamPicker = null }
                                )
                            } else {
                                LaunchedEffect(selected) { vpnForUpstreamPicker = null }
                            }
                        } else {
                            LaunchedEffect(selected) { vpnForUpstreamPicker = null }
                        }
                    }

                    if (showHotspotUpstreamPicker) {
                        val hotspotStatus by com.multiroute.wifi.DualWifiManager.status.collectAsState()
                        HotspotUpstreamPicker(
                            status = hotspotStatus,
                            channels = uiState.channels,
                            supportsCellularData = uiState.supportsCellularData,
                            onSelectTarget = { target ->
                                showHotspotUpstreamPicker = false
                                viewModel.setHotspotUpstream(target)
                            },
                            onOpenHotspotSettings = {
                                showHotspotUpstreamPicker = false
                                viewModel.openHotspotSettings()
                            },
                            onDismiss = { showHotspotUpstreamPicker = false }
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
                            mode = uiState.scenarioMode,
                            manualIds = uiState.manualScenarioIds,
                            channels = uiState.channels,
                            onSetManual = viewModel::setScenarioManualMode,
                            onSetDisabled = viewModel::setScenarioDisabled,
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
                            onSave = { name, trigger, bypassBaseRules ->
                                viewModel.saveCurrentAssignmentsAsScenario(name, trigger, bypassBaseRules)
                            },
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
                                initialBypassBaseRules = edited.bypassBaseRules,
                                currentSsids = uiState.channels
                                    .mapNotNull { it.ssid?.takeIf { ssid -> ssid.isNotBlank() } }
                                    .distinct(),
                                onSave = { name, trigger, bypassBaseRules ->
                                    viewModel.saveScenarioDetails(edited.id, name, trigger, bypassBaseRules)
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

@DrawableRes
fun getTransportIcon(transportType: String): Int = when (transportType) {
    "WLAN" -> R.drawable.ic_symbol_wifi
    "hotspot" -> R.drawable.ic_symbol_wifi_tethering
    "cellular" -> R.drawable.ic_symbol_signal_cellular_alt
    "ethernet" -> R.drawable.ic_symbol_lan
    "vpn" -> R.drawable.ic_symbol_vpn_key
    else -> R.drawable.ic_symbol_public
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
                painter = painterResource(R.drawable.ic_symbol_android),
                contentDescription = null,
                modifier = Modifier.size(size * 0.6f),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

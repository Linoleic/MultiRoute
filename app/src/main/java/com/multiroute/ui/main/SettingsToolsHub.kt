package com.multiroute.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.multiroute.theme.AppMotion
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multiroute.BuildConfig
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import com.multiroute.theme.RoutingDimensions
import com.multiroute.theme.ThemeSettings

internal enum class PreferencePage(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:DrawableRes val icon: Int
) {
    NETWORK(R.string.settings_network, R.string.settings_network_summary, R.drawable.ic_symbol_route),
    APPEARANCE(R.string.settings_appearance_language, R.string.settings_appearance_summary, R.drawable.ic_symbol_palette),
    BACKUP(R.string.settings_backup_title, R.string.settings_backup_summary, R.drawable.ic_symbol_backup),
    ABOUT(R.string.ui_about, R.string.settings_about_summary, R.drawable.ic_symbol_info),
    CELLULAR(R.string.cellular_control_title, R.string.tools_cellular_summary, R.drawable.ic_symbol_signal_cellular_alt),
    DUAL_WIFI(R.string.settings_dual_wifi_card_title, R.string.settings_dual_wifi_card_desc, R.drawable.ic_symbol_wifi),
    PROXY_UPSTREAM(R.string.proxy_upstream_title, R.string.proxy_upstream_tools_summary, R.drawable.ic_symbol_dns),
    WIRELESS(R.string.adb_helper_title, R.string.tools_adb_summary, R.drawable.ic_symbol_terminal),
    LOGS(R.string.settings_logs_title, R.string.tools_logs_summary, R.drawable.ic_symbol_article)
}

internal enum class DestinationLayout { BottomBar, CompactRail, StandardRail }

private data class MainDestination(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int
)

private val mainDestinations = listOf(
    MainDestination(R.string.tab_routing, R.drawable.ic_symbol_grid_view, R.drawable.ic_symbol_grid_view_filled),
    MainDestination(R.string.tab_channels, R.drawable.ic_symbol_router, R.drawable.ic_symbol_router_filled),
    MainDestination(R.string.tab_tools, R.drawable.ic_symbol_build, R.drawable.ic_symbol_build_filled),
    MainDestination(R.string.tab_settings, R.drawable.ic_symbol_settings, R.drawable.ic_symbol_settings_filled)
)

@Composable
private fun DestinationIcon(destination: MainDestination, selected: Boolean, description: String? = null) {
    Icon(
        painter = painterResource(
            if (selected) destination.selectedIcon
            else destination.icon
        ),
        contentDescription = description
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MainDestinations(selected: Int, onSelect: (Int) -> Unit, layout: DestinationLayout) {
    if (layout != DestinationLayout.BottomBar) {
        val compact = layout == DestinationLayout.CompactRail
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!compact) Spacer(Modifier.height(12.dp))
                mainDestinations.forEachIndexed { index, destination ->
                    val name = stringResource(destination.label)
                    val current = selected == index
                    if (compact) {
                        Box(
                            modifier = Modifier.size(width = 64.dp, height = 48.dp)
                                .selectable(current, role = Role.Tab, onClick = { onSelect(index) }),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (current) MaterialTheme.colorScheme.secondaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                                contentColor = if (current) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Box(Modifier.size(width = 48.dp, height = 36.dp), contentAlignment = Alignment.Center) {
                                    DestinationIcon(destination, current, name)
                                }
                            }
                        }
                    } else {
                        NavigationRailItem(
                            selected = current,
                            onClick = { onSelect(index) },
                            icon = { DestinationIcon(destination, current) },
                            label = { Text(name) }
                        )
                    }
                }
            }
        }
    } else ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        mainDestinations.forEachIndexed { index, destination ->
            val current = selected == index
            ShortNavigationBarItem(
                selected = current,
                onClick = { onSelect(index) },
                icon = { DestinationIcon(destination, current) },
                label = { Text(stringResource(destination.label)) }
            )
        }
    }
}

/** A destination is shared by phone navigation and the tablet detail pane. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsToolsHub(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    tools: Boolean,
    onNavigate: (androidx.navigation3.runtime.NavKey) -> Unit = {},
    onDetailOpenChanged: (Boolean) -> Unit = {},
    controlledSelection: String? = null,
    onControlledSelectionChange: ((String?) -> Unit)? = null
) {
    var localSelected by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = if (onControlledSelectionChange != null) controlledSelection else localSelected
    val setSelected: (String?) -> Unit = onControlledSelectionChange ?: { localSelected = it }
    val pages = if (tools) listOf(PreferencePage.CELLULAR, PreferencePage.DUAL_WIFI, PreferencePage.PROXY_UPSTREAM, PreferencePage.WIRELESS, PreferencePage.LOGS)
        else listOf(PreferencePage.NETWORK, PreferencePage.APPEARANCE, PreferencePage.BACKUP, PreferencePage.ABOUT)
    if (tools) ObserveNetworkManagement(viewModel)
    val wifiViewModel = if (tools)
        androidx.lifecycle.viewmodel.compose.viewModel<com.multiroute.ui.wifi.DualWifiViewModel>(key = "tools-dual-wifi") else null
    val request = if (tools) uiState.requestedNetworkTool else null
    LaunchedEffect(request) {
        if (request != null) {
            setSelected(request.name)
            viewModel.consumeNetworkToolRequest()
        }
    }
    val returningToChannels = tools && uiState.networkToolReturnTab != null
    val closeDetail: () -> Unit = {
        setSelected(null)
        if (returningToChannels) viewModel.returnFromNetworkTool()
    }
    val details = rememberSaveableStateHolder()
    val overviewScroll = rememberLazyListState()
    val wifiScroll = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dualPane = maxWidth >= RoutingDimensions.dualPaneMinWidth
        val leftPaneWidth = RoutingDimensions.dualPaneLeftWidth(
            availableWidth = maxWidth,
            minWidth = 260.dp,
            maxWidth = 360.dp,
            fraction = 0.32f
        )
        // A wide pane shows a useful default without changing narrow-window history.
        val current = if (!dualPane && !tools) null else pages.firstOrNull { it.name == (request?.name ?: selected) }
            ?: if (dualPane) pages.first() else null
        val detailOpen = !dualPane && current != null
        val useExternalOverlay = !dualPane && onControlledSelectionChange != null
        LaunchedEffect(detailOpen) { onDetailOpenChanged(detailOpen) }
        DisposableEffect(Unit) { onDispose { onDetailOpenChanged(false) } }

        val overview: @Composable () -> Unit = {
            PreferenceOverview(uiState, viewModel, tools, pages, current, dualPane, leftPaneWidth, overviewScroll,
                onSelect = {
                    when (it) {
                        PreferencePage.LOGS -> viewModel.openLogsDialog()
                        else -> setSelected(it.name)
                    }
                })
        }
        val detail: @Composable (PreferencePage) -> Unit = { page ->
            details.SaveableStateProvider(page.name) {
                PreferenceDetail(uiState, viewModel, page, dualPane && !returningToChannels, closeDetail, wifiScroll, wifiViewModel)
            }
        }
        if (dualPane) {
            Row(Modifier.fillMaxSize()) {
                overview()
                VerticalDivider(Modifier.fillMaxHeight())
                AnimatedContent(targetState = current!!, modifier = Modifier.weight(1f).fillMaxHeight(),
                    transitionSpec = { AppMotion.content() }, label = "preferencePane") { page ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { detail(page) }
                }
            }
        } else if (useExternalOverlay) {
            overview()
        } else {
            val canBack = returningToChannels || detailOpen
            val usePredictive = ThemeSettings.predictiveBack
            val backProgress = remember { Animatable(0f) }
            var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
            val backScope = rememberCoroutineScope()
            val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
            LaunchedEffect(current) {
                if (current != null && backProgress.value != 0f) backProgress.snapTo(0f)
            }
            LaunchedEffect(usePredictive) {
                if (!usePredictive && backProgress.value != 0f) backProgress.snapTo(0f)
            }
            BackHandler(enabled = canBack && !usePredictive) { closeDetail() }
            PredictiveBackHandler(enabled = canBack && usePredictive) { events ->
                try {
                    events.collect { event ->
                        backEdge = event.swipeEdge
                        backProgress.snapTo(event.progress.coerceIn(0f, 1f))
                    }
                    closeDetail()
                } catch (e: CancellationException) {
                    backScope.launch { backProgress.animateTo(0f, animationSpec = settleSpec) }
                    throw e
                }
            }
            AnimatedContent(
                targetState = current,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (targetState != null) AppMotion.hierarchy(forward = true)
                    else if (usePredictive) AppMotion.predictivePop()
                    else AppMotion.hierarchy(forward = false)
                },
                label = "preferenceNavigation"
            ) { page ->
                if (page == null) overview()
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { detail(page) }
            }
        }
    }
}

/**
 * Full-screen overlay for narrow-window tool subpages so they cover the main TopAppBar
 * without shifting Scaffold padding or causing vertical jumps during transitions.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ToolDetailOverlay(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    selectedToolName: String?,
    onClose: () -> Unit
) {
    val pages = listOf(
        PreferencePage.CELLULAR,
        PreferencePage.DUAL_WIFI,
        PreferencePage.PROXY_UPSTREAM,
        PreferencePage.WIRELESS
    )
    val current = pages.firstOrNull { it.name == selectedToolName }
    val returningToChannels = uiState.networkToolReturnTab != null
    val wifiViewModel = androidx.lifecycle.viewmodel.compose.viewModel<com.multiroute.ui.wifi.DualWifiViewModel>(key = "tools-dual-wifi")
    val wifiScroll = rememberLazyListState()
    val details = rememberSaveableStateHolder()

    val canBack = current != null || returningToChannels
    val usePredictive = ThemeSettings.predictiveBack
    val backProgress = remember { Animatable(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val backScope = rememberCoroutineScope()
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    LaunchedEffect(current) {
        if (current != null && backProgress.value != 0f) {
            backProgress.snapTo(0f)
        }
    }
    LaunchedEffect(usePredictive) {
        if (!usePredictive && backProgress.value != 0f) {
            backProgress.snapTo(0f)
        }
    }

    BackHandler(enabled = canBack && !usePredictive) { onClose() }
    PredictiveBackHandler(enabled = canBack && usePredictive) { events ->
        try {
            events.collect { event ->
                backEdge = event.swipeEdge
                backProgress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            onClose()
        } catch (e: CancellationException) {
            backScope.launch { backProgress.animateTo(0f, animationSpec = settleSpec) }
            throw e
        }
    }

    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val inEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val outEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = current,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val transform = if (targetState != null) {
                (slideInHorizontally(spatial) { it / 4 } + fadeIn(inEffects)) togetherWith ExitTransition.None
            } else if (usePredictive) {
                EnterTransition.None togetherWith (scaleOut(targetScale = 0.86f, animationSpec = settleSpec) +
                    slideOutHorizontally(spatial) { if (backEdge == BackEventCompat.EDGE_RIGHT) -it / 6 else it / 6 } +
                    fadeOut(outEffects))
            } else {
                EnterTransition.None togetherWith (slideOutHorizontally(spatial) { it / 4 } + fadeOut(outEffects))
            }
            transform using null
        },
        label = "toolDetailOverlay"
    ) { page ->
        if (page != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = backProgress.value
                        if (p > 0f) {
                            val scale = 1f - 0.12f * p
                            scaleX = scale
                            scaleY = scale
                            val edgeDir = if (backEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                            translationX = edgeDir * 24.dp.toPx() * p
                            shape = RoundedCornerShape(28.dp * p)
                            clip = true
                            shadowElevation = (12.dp * p).toPx()
                        }
                    }
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                details.SaveableStateProvider(page.name) {
                    PreferenceDetail(
                        uiState = uiState,
                        viewModel = viewModel,
                        page = page,
                        dualPane = false,
                        onBack = onClose,
                        wifiScroll = wifiScroll,
                        sharedWifiViewModel = wifiViewModel
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreferenceOverview(
    uiState: MainUiState, viewModel: MainScreenViewModel, tools: Boolean,
    pages: List<PreferencePage>, current: PreferencePage?, dualPane: Boolean,
    leftPaneWidth: androidx.compose.ui.unit.Dp,
    overviewScroll: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (PreferencePage) -> Unit
) {
    val groupBounds = remember(pages, tools) {
        if (!tools) pages.associateWith { pages.indexOf(it) to pages.size }
        else mapOf(
            PreferencePage.CELLULAR to (0 to 3),
            PreferencePage.DUAL_WIFI to (1 to 3),
            PreferencePage.PROXY_UPSTREAM to (2 to 3),
            PreferencePage.WIRELESS to (0 to 2),
            PreferencePage.LOGS to (1 to 2)
        )
    }
    LazyColumn(
        state = overviewScroll,
        modifier = if (dualPane) Modifier.width(leftPaneWidth).fillMaxHeight() else Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (!dualPane && !tools) 12.dp else ListItemDefaults.SegmentedGap)
    ) {
        item(key = "overview-heading") {
            Text(
                stringResource(if (tools) R.string.tab_tools else R.string.tab_settings),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        itemsIndexed(pages, key = { _, page -> page.name }) { _, page ->
            if (tools && page == PreferencePage.CELLULAR) {
                SettingsSectionLabel(R.string.tools_network_management)
            }
            if (tools && page == PreferencePage.WIRELESS) {
                Spacer(Modifier.height(10.dp))
                SettingsSectionLabel(R.string.tools_debugging)
            }
            val summary = when (page) {
                PreferencePage.APPEARANCE -> {
                    val theme = when (ThemeSettings.mode) {
                        "light" -> R.string.settings_theme_light
                        "dark" -> R.string.settings_theme_dark
                        else -> R.string.settings_follow_system
                    }
                    val language = when (RouteConfigProvider.getLanguage(LocalContext.current)) {
                        "zh-CN" -> R.string.settings_language_zh
                        "en" -> R.string.settings_language_en
                        else -> R.string.settings_follow_system
                    }
                    stringResource(R.string.settings_appearance_current, stringResource(theme), stringResource(language))
                }
                PreferencePage.CELLULAR -> networkToolSummary(NetworkTool.CELLULAR, viewModel)
                PreferencePage.DUAL_WIFI -> networkToolSummary(NetworkTool.DUAL_WIFI, viewModel)
                PreferencePage.ABOUT -> "v${BuildConfig.VERSION_NAME}"
                else -> stringResource(page.summary)
            }
            if (!dualPane && !tools && page != PreferencePage.LOGS) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionLabel(page.title)
                    when (page) {
                        PreferencePage.NETWORK -> {
                            NetworkSettingsPanel(uiState, viewModel)
                            TestNodeSettingsPanel(uiState, viewModel)
                        }
                        PreferencePage.APPEARANCE -> AppearanceSettingsPanel(uiState, viewModel)
                        PreferencePage.BACKUP -> BackupSettingsPanel(uiState, viewModel, showTitle = false)
                        PreferencePage.ABOUT -> AboutSettingsPanel(uiState, viewModel, showTitle = false)
                        else -> Unit
                    }
                }
            } else {
                val (groupIndex, groupCount) = groupBounds[page] ?: (0 to 1)
                SettingsEntry(
                    title = stringResource(page.title),
                    summary = summary,
                    icon = page.icon,
                    selected = current == page,
                    index = groupIndex,
                    count = groupCount,
                    onClick = { onSelect(page) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreferenceDetail(
    uiState: MainUiState, viewModel: MainScreenViewModel, page: PreferencePage,
    dualPane: Boolean, onBack: () -> Unit,
    wifiScroll: androidx.compose.foundation.lazy.LazyListState,
    sharedWifiViewModel: com.multiroute.ui.wifi.DualWifiViewModel?
) {
    val wifiViewModel = if (page == PreferencePage.DUAL_WIFI) sharedWifiViewModel else null
    val wifiState = wifiViewModel?.uiState?.collectAsState()?.value
    Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (!dualPane) IconButton(onClick = { onBack() }) {
                Icon(painterResource(R.drawable.ic_symbol_arrow_back), stringResource(
                        if (uiState.networkToolReturnTab != null) R.string.channels_back else R.string.settings_back))
            }
            Text(stringResource(page.title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            if (wifiViewModel != null) IconButton(onClick = wifiViewModel::checkCapability,
                enabled = wifiState?.status?.isScanning != true) {
                if (wifiState?.status?.isScanning == true) LoadingIndicator(Modifier.size(24.dp))
                else Icon(painterResource(R.drawable.ic_symbol_refresh), stringResource(R.string.dual_wifi_recheck))
            }
        }
        if (wifiViewModel != null) {
            com.multiroute.ui.wifi.DualWifiScreen(onBack = onBack,
                modifier = Modifier.fillMaxWidth().weight(1f), embedded = true, listState = wifiScroll, viewModel = wifiViewModel)
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (page) {
                PreferencePage.NETWORK -> {
                    item(key = "network") { NetworkSettingsPanel(uiState, viewModel) }
                    item(key = "test") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsSectionLabel(R.string.settings_test_endpoint)
                            TestNodeSettingsPanel(uiState, viewModel)
                        }
                    }
                }
                PreferencePage.APPEARANCE -> item { AppearanceSettingsPanel(uiState, viewModel) }
                PreferencePage.BACKUP -> item { BackupSettingsPanel(uiState, viewModel) }
                PreferencePage.ABOUT -> item { AboutSettingsPanel(uiState, viewModel) }
                PreferencePage.WIRELESS -> item { WirelessAdbCard(showTitle = false) }
                PreferencePage.CELLULAR -> item { CellularControlCard(uiState, viewModel, observeState = false) }
                PreferencePage.PROXY_UPSTREAM -> item {
    ProxyUpstreamToolPanel(
        channels = uiState.channels,
        controller = viewModel.proxyUpstream,
        compatibleVpn = uiState.supportedAndroidVpn,
        hasTransparentProxyEvidence = uiState.env.transparentProxyDiagnostics?.hasActiveEvidence == true
    )
}
                PreferencePage.DUAL_WIFI -> Unit // Rendered in the bounded content pane above.
                PreferencePage.LOGS -> Unit // The overview opens the log dialog directly.
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsEntry(
    title: String,
    summary: String,
    @DrawableRes icon: Int,
    selected: Boolean = false,
    index: Int = 0,
    count: Int = 1,
    onClick: () -> Unit
) {
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedSupportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTrailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        leadingContent = {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.primary
            )
        },
        supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(painterResource(R.drawable.ic_symbol_chevron_right), contentDescription = null) },
        content = { Text(title, style = MaterialTheme.typography.bodyLarge) }
    )
}

@Composable
internal fun PreferenceChoiceRow(title: String, choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    ListItem(
        onClick = { show = true },
        supportingContent = { Text(choices.firstOrNull { it.first == selected }?.second.orEmpty()) },
        trailingContent = { Icon(painterResource(R.drawable.ic_symbol_chevron_right), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        content = { Text(title) }
    )
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(title) }, text = {
        Column {
            choices.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().selectable(selected == value, role = Role.RadioButton,
                    onClick = { show = false; onSelect(value) }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == value, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { show = false }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
internal fun SettingsSectionLabel(@StringRes title: Int) {
    SettingsSectionLabel(stringResource(title))
}

@Composable
internal fun SettingsSectionLabel(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}


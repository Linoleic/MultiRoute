package com.multiroute.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import com.multiroute.theme.AppMotion
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multiroute.BuildConfig
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import com.multiroute.theme.ThemeSettings

private enum class PreferencePage(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector) {
    NETWORK(R.string.settings_network, R.string.settings_network_summary, Icons.Default.Route),
    APPEARANCE(R.string.settings_appearance_language, R.string.settings_appearance_summary, Icons.Default.Palette),
    BACKUP(R.string.settings_backup_title, R.string.settings_backup_summary, Icons.Default.Backup),
    ABOUT(R.string.ui_about, R.string.settings_about_summary, Icons.Default.Info),
    CELLULAR(R.string.cellular_control_title, R.string.tools_cellular_summary, Icons.Default.SignalCellularAlt),
    DUAL_WIFI(R.string.settings_dual_wifi_card_title, R.string.settings_dual_wifi_card_desc, Icons.Default.Wifi),
    HOTSPOT(R.string.settings_hotspot_card_title, R.string.settings_hotspot_card_desc_inactive, Icons.Default.WifiTethering),
    WIRELESS(R.string.adb_helper_title, R.string.tools_adb_summary, Icons.Default.Terminal),
    LOGS(R.string.settings_logs_title, R.string.tools_logs_summary, Icons.Default.Article)
}

internal enum class DestinationLayout { BottomBar, CompactRail, StandardRail }

@Composable
internal fun MainDestinations(selected: Int, onSelect: (Int) -> Unit, layout: DestinationLayout) {
    val labels = listOf(R.string.tab_routing, R.string.tab_channels, R.string.tab_tools, R.string.tab_settings)
    val icons = listOf(Icons.Default.Apps, Icons.Default.Hub, Icons.Default.Build, Icons.Default.Tune)
    if (layout != DestinationLayout.BottomBar) {
        val compact = layout == DestinationLayout.CompactRail
        NavigationRail(Modifier.fillMaxHeight()) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!compact) Spacer(Modifier.height(12.dp))
                labels.forEachIndexed { index, label ->
                    val name = stringResource(label)
                    if (compact) {
                        val current = selected == index
                        Box(
                            modifier = Modifier.size(width = 64.dp, height = 48.dp)
                                .selectable(current, role = Role.Tab, onClick = { onSelect(index) }),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                                color = if (current) MaterialTheme.colorScheme.secondaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                                contentColor = if (current) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Box(Modifier.size(width = 48.dp, height = 36.dp), contentAlignment = Alignment.Center) {
                                    Icon(icons[index], contentDescription = name)
                                }
                            }
                        }
                    } else {
                        NavigationRailItem(
                            selected = selected == index,
                            onClick = { onSelect(index) },
                            icon = { Icon(icons[index], contentDescription = null) },
                            label = { Text(name) }
                        )
                    }
                }
            }
        }
    } else NavigationBar {
        labels.forEachIndexed { index, label ->
            NavigationBarItem(selected == index, { onSelect(index) },
                icon = { Icon(icons[index], contentDescription = null) }, label = { Text(stringResource(label)) })
        }
    }
}

/** A destination is shared by phone navigation and the tablet detail pane. */
@Composable
internal fun SettingsToolsHub(uiState: MainUiState, viewModel: MainScreenViewModel, tools: Boolean,
    onNavigate: (androidx.navigation3.runtime.NavKey) -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val pages = if (tools) listOf(PreferencePage.CELLULAR, PreferencePage.DUAL_WIFI, PreferencePage.HOTSPOT, PreferencePage.WIRELESS, PreferencePage.LOGS)
        else listOf(PreferencePage.NETWORK, PreferencePage.APPEARANCE, PreferencePage.BACKUP, PreferencePage.ABOUT)
    if (tools) ObserveNetworkManagement(viewModel)
    val wifiViewModel = if (tools)
        androidx.lifecycle.viewmodel.compose.viewModel<com.multiroute.ui.wifi.DualWifiViewModel>(key = "tools-dual-wifi") else null
    val request = if (tools) uiState.requestedNetworkTool else null
    LaunchedEffect(request) {
        if (request != null) {
            selected = request.name
            viewModel.consumeNetworkToolRequest()
        }
    }
    val returningToChannels = tools && uiState.networkToolReturnTab != null
    val closeDetail: () -> Unit = {
        if (returningToChannels) viewModel.returnFromNetworkTool() else selected = null
    }
    val details = rememberSaveableStateHolder()
    val overviewScroll = rememberLazyListState()
    val wifiScroll = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dualPane = maxWidth >= 840.dp
        // A wide pane shows a useful default without changing narrow-window history.
        val current = if (!dualPane && !tools) null else pages.firstOrNull { it.name == (request?.name ?: selected) }
            ?: if (dualPane) pages.first() else null
        BackHandler(enabled = returningToChannels || !dualPane && current != null) { closeDetail() }
        val overview: @Composable () -> Unit = {
            PreferenceOverview(uiState, viewModel, tools, pages, current, dualPane, overviewScroll,
                onSelect = {
                    when (it) {
                        PreferencePage.LOGS -> viewModel.openLogsDialog()
                        else -> selected = it.name
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
        } else {
            AnimatedContent(targetState = current, modifier = Modifier.fillMaxSize(),
                transitionSpec = { AppMotion.hierarchy(forward = targetState != null) },
                label = "preferenceNavigation") { page ->
                if (page == null) overview()
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { detail(page) }
            }
        }
    }
}

@Composable
private fun PreferenceOverview(
    uiState: MainUiState, viewModel: MainScreenViewModel, tools: Boolean,
    pages: List<PreferencePage>, current: PreferencePage?, dualPane: Boolean,
    overviewScroll: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (PreferencePage) -> Unit
) {
    LazyColumn(
        state = overviewScroll,
        modifier = if (dualPane) Modifier.width(300.dp).fillMaxHeight() else Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(stringResource(if (tools) R.string.tab_tools else R.string.tab_settings),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(pages, key = { it.name }) { page ->
            if (tools && page == PreferencePage.CELLULAR) SettingsSectionLabel(R.string.tools_network_management)
            if (tools && page == PreferencePage.WIRELESS) SettingsSectionLabel(R.string.tools_debugging)
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
                PreferencePage.HOTSPOT -> networkToolSummary(NetworkTool.HOTSPOT, viewModel)
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
            } else SettingsEntry(stringResource(page.title), summary, page.icon,
                selected = current == page, onClick = { onSelect(page) })
        }
    }
}

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(
                        if (uiState.networkToolReturnTab != null) R.string.channels_back else R.string.settings_back))
            }
            Text(stringResource(page.title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            if (wifiViewModel != null) IconButton(onClick = wifiViewModel::checkCapability,
                enabled = wifiState?.status?.isScanning != true) {
                if (wifiState?.status?.isScanning == true) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, stringResource(R.string.dual_wifi_recheck))
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
                PreferencePage.HOTSPOT -> item { HotspotManagementPanel(viewModel) }
                PreferencePage.DUAL_WIFI -> Unit // Rendered in the bounded content pane above.
                PreferencePage.LOGS -> Unit // The overview opens the log dialog directly.
            }
        }
    }
}

@Composable
internal fun SettingsEntry(title: String, summary: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "preferenceSelection")
    Surface(shape = MaterialTheme.shapes.large, color = background) {
        ListItem(modifier = Modifier.clickable(onClick = onClick),
            headlineContent = { Text(title) }, supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable
internal fun PreferenceChoiceRow(title: String, choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    ListItem(modifier = Modifier.clickable { show = true }, headlineContent = { Text(title) },
        supportingContent = { Text(choices.firstOrNull { it.first == selected }?.second.orEmpty()) },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
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


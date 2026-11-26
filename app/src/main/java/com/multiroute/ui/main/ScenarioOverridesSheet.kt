package com.multiroute.ui.main

import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.multiroute.R
import com.multiroute.cellular.CellularTargets
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.theme.RoutingDimensions
import com.multiroute.util.NetworkUtils
import com.multiroute.util.ScenarioEngine
import com.multiroute.wifi.DualWifiScanParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ResolvedScenarioTarget(
    val rawTarget: String,
    val isDefault: Boolean,
    val onlineChannel: NetworkChannel?,
    val symbolicSsid: String?
) {
    val isOnline: Boolean get() = isDefault || onlineChannel != null
}

internal fun resolveScenarioOverrideTarget(
    rawTarget: String,
    channels: List<NetworkChannel>
): ResolvedScenarioTarget {
    if (rawTarget == CHANNEL_DEFAULT) {
        return ResolvedScenarioTarget(
            rawTarget = rawTarget,
            isDefault = true,
            onlineChannel = null,
            symbolicSsid = null
        )
    }
    val routable = channels.filter { !it.isHotspot }
    val ssid = ScenarioEngine.symbolicSsidOf(rawTarget)
    val online = if (ssid != null) {
        routable.firstOrNull { !it.ssid.isNullOrBlank() && it.ssid.equals(ssid, ignoreCase = true) }
    } else {
        routable.firstOrNull { it.id == rawTarget || it.interfaceName == rawTarget }
    }
    return ResolvedScenarioTarget(
        rawTarget = rawTarget,
        isDefault = false,
        onlineChannel = online,
        symbolicSsid = ssid
    )
}

internal data class ScenarioTargetOption(
    val storedValue: String,
    val onlineChannel: NetworkChannel?,
    val symbolicSsid: String?,
    val isDefault: Boolean = false,
    val isSavedWifi: Boolean = false
)

internal fun buildScenarioTargetOptions(
    profile: ScenarioProfile,
    allProfiles: List<ScenarioProfile>,
    channels: List<NetworkChannel>,
    savedSsids: List<String>
): Triple<ScenarioTargetOption, List<ScenarioTargetOption>, List<ScenarioTargetOption>> {
    val defaultOption = ScenarioTargetOption(
        storedValue = CHANNEL_DEFAULT,
        onlineChannel = null,
        symbolicSsid = null,
        isDefault = true
    )
    val routable = channels.filter { !it.isHotspot }
    val onlineOptions = routable.map { channel ->
        val ssid = channel.ssid?.takeIf { it.isNotBlank() }
        ScenarioTargetOption(
            storedValue = ssid?.let { ScenarioEngine.symbolicSsid(it) } ?: channel.id,
            onlineChannel = channel,
            symbolicSsid = ssid
        )
    }

    val onlineSsids = routable.mapNotNull { it.ssid?.takeIf { s -> s.isNotBlank() }?.lowercase() }.toSet()
    val onlineIds = routable.flatMap { listOf(it.id, it.interfaceName) }.toSet()

    val seenSsids = onlineSsids.toMutableSet()
    val seenPlainIds = onlineIds.toMutableSet()
    val offlineOptions = mutableListOf<ScenarioTargetOption>()

    fun addOfflineTarget(rawTarget: String, fromSavedWifi: Boolean) {
        if (rawTarget.isBlank() || rawTarget == CHANNEL_DEFAULT) return
        val ssid = ScenarioEngine.symbolicSsidOf(rawTarget)
        if (ssid != null) {
            val lower = ssid.lowercase()
            if (seenSsids.add(lower)) {
                offlineOptions += ScenarioTargetOption(
                    storedValue = ScenarioEngine.symbolicSsid(ssid),
                    onlineChannel = null,
                    symbolicSsid = ssid,
                    isSavedWifi = fromSavedWifi
                )
            }
        } else if (seenPlainIds.add(rawTarget)) {
            offlineOptions += ScenarioTargetOption(
                storedValue = rawTarget,
                onlineChannel = null,
                symbolicSsid = null,
                isSavedWifi = false
            )
        }
    }

    profile.overrides.values.forEach { addOfflineTarget(it, fromSavedWifi = false) }
    allProfiles.asSequence()
        .filter { it.id != profile.id }
        .flatMap { it.overrides.values.asSequence() }
        .forEach { addOfflineTarget(it, fromSavedWifi = false) }

    (profile.trigger as? ScenarioTrigger.SsidMatch)?.ssids.orEmpty()
        .filter { it.isNotBlank() }
        .forEach { ssid -> addOfflineTarget(ScenarioEngine.symbolicSsid(ssid), fromSavedWifi = true) }

    savedSsids
        .filter { it.isNotBlank() }
        .forEach { ssid -> addOfflineTarget(ScenarioEngine.symbolicSsid(ssid), fromSavedWifi = true) }

    return Triple(defaultOption, onlineOptions, offlineOptions)
}

private data class ScenarioEditorRowModel(
    val ruleKey: String,
    val app: AppItem?,
    val rawTarget: String?,
    val resolved: ResolvedScenarioTarget?
) {
    val inPlan: Boolean get() = rawTarget != null
}

private sealed interface ScenarioPickerRequest {
    val ruleKeys: Set<String>
    val singleApp: AppItem?
    val singleRuleKey: String?
    val currentTarget: String?
    val hasInPlanApps: Boolean

    data class Single(
        override val singleRuleKey: String,
        override val singleApp: AppItem?,
        override val currentTarget: String?
    ) : ScenarioPickerRequest {
        override val ruleKeys: Set<String> = setOf(singleRuleKey)
        override val hasInPlanApps: Boolean = currentTarget != null
    }

    data class Batch(
        override val ruleKeys: Set<String>,
        override val hasInPlanApps: Boolean
    ) : ScenarioPickerRequest {
        override val singleApp: AppItem? = null
        override val singleRuleKey: String? = null
        override val currentTarget: String? = null
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScenarioOverridesOverlay(
    profile: ScenarioProfile?,
    allProfiles: List<ScenarioProfile>,
    installedApps: List<AppItem>,
    channels: List<NetworkChannel>,
    snackBarMessage: String?,
    isVpnSelfLoop: (NetworkChannel, AppItem) -> Boolean,
    onSetOverrides: (String, Set<String>, String) -> Unit,
    onRemoveOverrides: (String, Set<String>) -> Unit,
    onDismissSnackBar: () -> Unit,
    onDismiss: () -> Unit
) {
    val activeProfile = profile ?: return
    var isSelectionMode by remember(activeProfile.id) { mutableStateOf(false) }
    var selectedKeys by remember(activeProfile.id) { mutableStateOf<Set<String>>(emptySet()) }
    val transitionState = remember(activeProfile.id) {
        MutableTransitionState(false).apply { targetState = true }
    }
    var isDismissing by remember(activeProfile.id) { mutableStateOf(false) }

    val requestClose: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            transitionState.targetState = false
        }
    }

    LaunchedEffect(transitionState.isIdle, transitionState.currentState, isDismissing) {
        if (isDismissing && transitionState.isIdle && !transitionState.currentState) {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            if (isSelectionMode) {
                isSelectionMode = false
                selectedKeys = emptySet()
            } else {
                requestClose()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialogWindow?.setDimAmount(0f)
            dialogWindow?.setWindowAnimations(0)
        }
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        val inEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val outEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInHorizontally(spatial) { it / 4 } + fadeIn(inEffects),
                exit = slideOutHorizontally(spatial) { it / 4 } + fadeOut(outEffects)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    ScenarioOverridesScreenContent(
                        profile = activeProfile,
                        allProfiles = allProfiles,
                        installedApps = installedApps,
                        channels = channels,
                        snackBarMessage = snackBarMessage,
                        isSelectionMode = isSelectionMode,
                        selectedKeys = selectedKeys,
                        onSelectionModeChange = { nextMode, nextSelected ->
                            isSelectionMode = nextMode
                            selectedKeys = nextSelected
                        },
                        isVpnSelfLoop = isVpnSelfLoop,
                        onSetOverrides = { keys, target -> onSetOverrides(activeProfile.id, keys, target) },
                        onRemoveOverrides = { keys -> onRemoveOverrides(activeProfile.id, keys) },
                        onDismissSnackBar = onDismissSnackBar,
                        onBack = requestClose
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ScenarioOverridesScreenContent(
    profile: ScenarioProfile,
    allProfiles: List<ScenarioProfile>,
    installedApps: List<AppItem>,
    channels: List<NetworkChannel>,
    snackBarMessage: String?,
    isSelectionMode: Boolean,
    selectedKeys: Set<String>,
    onSelectionModeChange: (Boolean, Set<String>) -> Unit,
    isVpnSelfLoop: (NetworkChannel, AppItem) -> Boolean,
    onSetOverrides: (Set<String>, String) -> Unit,
    onRemoveOverrides: (Set<String>) -> Unit,
    onDismissSnackBar: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var searchQuery by remember(profile.id) { mutableStateOf("") }
    var showAllApps by remember(profile.id) { mutableStateOf(false) }
    var showSystemApps by remember(profile.id) { mutableStateOf(false) }
    var pickerRequest by remember { mutableStateOf<ScenarioPickerRequest?>(null) }
    var savedSsids by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        savedSsids = withContext(Dispatchers.IO) {
            runCatching { DualWifiScanParser.getSavedNetworkIds().keys.sorted() }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(snackBarMessage) {
        snackBarMessage?.let {
            snackbarHostState.showSnackbar(it)
            onDismissSnackBar()
        }
    }

    val appsByRuleKey = remember(installedApps) { installedApps.associateBy { it.ruleKey } }
    val allRows = remember(profile.overrides, installedApps, appsByRuleKey, channels, showAllApps, showSystemApps) {
        if (!showAllApps) {
            profile.overrides.entries.map { (ruleKey, rawTarget) ->
                ScenarioEditorRowModel(
                    ruleKey = ruleKey,
                    app = appsByRuleKey[ruleKey],
                    rawTarget = rawTarget,
                    resolved = resolveScenarioOverrideTarget(rawTarget, channels)
                )
            }.sortedWith(
                compareBy<ScenarioEditorRowModel> { it.app == null }
                    .thenBy { it.app?.appName?.lowercase() ?: it.ruleKey.lowercase() }
            )
        } else {
            val installedRows = installedApps
                .filter { showSystemApps || !it.isSystemApp || it.ruleKey in profile.overrides }
                .map { app ->
                    val rawTarget = profile.overrides[app.ruleKey]
                    ScenarioEditorRowModel(
                        ruleKey = app.ruleKey,
                        app = app,
                        rawTarget = rawTarget,
                        resolved = rawTarget?.let { resolveScenarioOverrideTarget(it, channels) }
                    )
                }
            val uninstalledRows = profile.overrides.entries
                .filter { (key, _) -> key !in appsByRuleKey }
                .map { (ruleKey, rawTarget) ->
                    ScenarioEditorRowModel(
                        ruleKey = ruleKey,
                        app = null,
                        rawTarget = rawTarget,
                        resolved = resolveScenarioOverrideTarget(rawTarget, channels)
                    )
                }
            (installedRows + uninstalledRows).sortedWith(
                compareByDescending<ScenarioEditorRowModel> { it.inPlan }
                    .thenBy { it.app == null }
                    .thenBy { it.app?.isSystemApp == true }
                    .thenBy { it.app?.appName?.lowercase() ?: it.ruleKey.lowercase() }
            )
        }
    }

    val filteredRows = remember(allRows, searchQuery, channels) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            allRows
        } else {
            allRows.filter { row ->
                val targetLabel = row.resolved?.let { resolved ->
                    when {
                        resolved.isDefault -> context.getString(R.string.vm_system_default)
                        resolved.onlineChannel != null -> NetworkUtils.channelLabel(context, resolved.onlineChannel)
                        resolved.symbolicSsid != null -> resolved.symbolicSsid
                        else -> NetworkUtils.savedTargetLabel(context, resolved.rawTarget)
                    }.lowercase()
                }.orEmpty()
                row.ruleKey.lowercase().contains(q) ||
                    (row.app?.appName?.lowercase()?.contains(q) == true) ||
                    (row.app?.packageName?.lowercase()?.contains(q) == true) ||
                    targetLabel.contains(q)
            }
        }
    }

    val topBarContainer by animateColorAsState(
        targetValue = if (isSelectionMode) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surface,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "scenarioEditorTopBar"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarContainer),
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { onSelectionModeChange(false, emptySet()) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_symbol_close),
                                contentDescription = stringResource(R.string.cd_exit_multi)
                            )
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_symbol_arrow_back),
                                contentDescription = stringResource(R.string.ui_cancel)
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = if (isSelectionMode) {
                                stringResource(R.string.ui_selected_apps_count, selectedKeys.size)
                            } else {
                                profile.name
                            },
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (profile.bypassBaseRules) {
                                stringResource(R.string.scenario_overrides_bypass_count, profile.overrides.size)
                            } else {
                                stringResource(R.string.scenario_overrides_count, profile.overrides.size)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (!isSelectionMode) {
                        IconButton(
                            onClick = { onSelectionModeChange(true, emptySet()) },
                            enabled = filteredRows.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_symbol_done_all),
                                contentDescription = stringResource(R.string.scenario_overrides_enter_multi_cd)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = RoutingDimensions.contentPadding,
                    end = RoutingDimensions.contentPadding,
                    top = 8.dp,
                    bottom = if (isSelectionMode) 96.dp else 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
            ) {
                item(key = "controls") {
                    Column(
                        modifier = Modifier.padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.scenario_overrides_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.scenario_overrides_search_hint)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_symbol_search),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_symbol_close),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            } else null,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = !showAllApps,
                                onClick = { showAllApps = false },
                                label = {
                                    Text(stringResource(R.string.scenario_overrides_tab_in_plan, profile.overrides.size))
                                }
                            )
                            FilterChip(
                                selected = showAllApps,
                                onClick = { showAllApps = true },
                                label = { Text(stringResource(R.string.scenario_overrides_tab_all)) }
                            )
                            if (showAllApps) {
                                FilterChip(
                                    selected = showSystemApps,
                                    onClick = { showSystemApps = !showSystemApps },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_symbol_android),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text(stringResource(R.string.filter_system_apps)) }
                                )
                            }
                        }
                    }
                }

                if (filteredRows.isEmpty()) {
                    item(key = "empty") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = when {
                                        searchQuery.isNotBlank() -> stringResource(R.string.scenario_overrides_no_match)
                                        profile.bypassBaseRules -> stringResource(R.string.scenario_overrides_bypass_empty)
                                        else -> stringResource(R.string.scenario_overrides_empty)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!showAllApps && searchQuery.isBlank()) {
                                    FilledTonalButton(onClick = { showAllApps = true }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_symbol_add),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.scenario_overrides_browse_all))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(filteredRows, key = { _, row -> row.ruleKey }) { index, row ->
                        val isSelected = row.ruleKey in selectedKeys
                        ScenarioOverrideSegmentedRow(
                            row = row,
                            index = index,
                            count = filteredRows.size,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onToggleSelect = {
                                val next = if (isSelected) selectedKeys - row.ruleKey else selectedKeys + row.ruleKey
                                onSelectionModeChange(true, next)
                            },
                            onClick = {
                                pickerRequest = ScenarioPickerRequest.Single(
                                    singleRuleKey = row.ruleKey,
                                    singleApp = row.app,
                                    currentTarget = row.rawTarget
                                )
                            },
                            onLongClick = {
                                onSelectionModeChange(true, setOf(row.ruleKey))
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isSelectionMode,
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
                val selectedCount = selectedKeys.size
                val selectedInPlan = selectedKeys.filter { it in profile.overrides }.toSet()
                val visibleKeys = filteredRows.map { it.ruleKey }.toSet()
                val allVisibleSelected = visibleKeys.isNotEmpty() && visibleKeys.all { it in selectedKeys }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalFloatingToolbar(
                        expanded = true,
                        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
                    ) {
                        IconButton(onClick = { onSelectionModeChange(false, emptySet()) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_symbol_close),
                                contentDescription = stringResource(R.string.ui_cancel)
                            )
                        }
                        IconButton(
                            onClick = {
                                val next = if (allVisibleSelected) selectedKeys - visibleKeys else selectedKeys + visibleKeys
                                onSelectionModeChange(true, next)
                            },
                            enabled = visibleKeys.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_symbol_done_all),
                                contentDescription = stringResource(R.string.ui_select_all)
                            )
                        }
                        IconButton(
                            onClick = {
                                onRemoveOverrides(selectedInPlan)
                                onSelectionModeChange(false, emptySet())
                            },
                            enabled = selectedInPlan.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_symbol_delete),
                                contentDescription = stringResource(R.string.scenario_override_remove_batch_cd)
                            )
                        }
                    }
                    ElevatedButton(
                        onClick = {
                            pickerRequest = ScenarioPickerRequest.Batch(
                                ruleKeys = selectedKeys,
                                hasInPlanApps = selectedInPlan.isNotEmpty()
                            )
                        },
                        enabled = selectedCount > 0,
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
                            painter = painterResource(R.drawable.ic_symbol_tune),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (selectedCount == 0) stringResource(R.string.ui_select_apps_to_assign)
                            else stringResource(R.string.ui_assign_channel_n, selectedCount),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    pickerRequest?.let { request ->
        val (defaultOption, onlineOptions, offlineOptions) = remember(
            profile,
            allProfiles,
            channels,
            savedSsids
        ) {
            buildScenarioTargetOptions(profile, allProfiles, channels, savedSsids)
        }
        ScenarioChannelPickerSheet(
            request = request,
            defaultOption = defaultOption,
            onlineOptions = onlineOptions,
            offlineOptions = offlineOptions,
            isVpnSelfLoop = isVpnSelfLoop,
            onSelect = { targetValue ->
                onSetOverrides(request.ruleKeys, targetValue)
                pickerRequest = null
                if (isSelectionMode) onSelectionModeChange(false, emptySet())
            },
            onRemove = {
                onRemoveOverrides(request.ruleKeys)
                pickerRequest = null
                if (isSelectionMode) onSelectionModeChange(false, emptySet())
            },
            onDismiss = { pickerRequest = null }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScenarioOverrideSegmentedRow(
    row: ScenarioEditorRowModel,
    index: Int,
    count: Int,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val resolved = row.resolved
    val online = resolved?.onlineChannel

    val rowContainerTarget = when {
        resolved == null || resolved.isDefault -> base
        online == null -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f).compositeOver(base)
        online.transportType == "WLAN" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f).compositeOver(base)
        online.transportType == "cellular" || CellularTargets.isSim(online.id) ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f).compositeOver(base)
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.50f).compositeOver(base)
    }
    val animatedRowContainer by animateColorAsState(
        targetValue = rowContainerTarget,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "scenarioRowContainer"
    )
    val colors = ListItemDefaults.segmentedColors(
        containerColor = animatedRowContainer,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedSupportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedLeadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTrailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )

    SegmentedListItem(
        selected = isSelectionMode && isSelected,
        onClick = { if (isSelectionMode) onToggleSelect() else onClick() },
        onLongClick = { if (isSelectionMode) onToggleSelect() else onLongClick() },
        onLongClickLabel = if (isSelectionMode) null else stringResource(R.string.routing_select_for_batch),
        shapes = shapes,
        modifier = if (isSelectionMode) {
            Modifier.semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(isSelected)
            }
        } else Modifier,
        colors = colors,
        leadingContent = {
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
                    Box(Modifier.padding(end = 12.dp)) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                    }
                }
                AppIcon(drawable = row.app?.icon, size = 36.dp)
            }
        },
        supportingContent = {
            if (row.app != null) {
                Text(
                    text = row.app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingContent = {
            when {
                resolved == null -> {
                    Text(
                        text = stringResource(R.string.scenario_override_not_in_plan),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                resolved.isDefault -> {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.vm_system_default),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                else -> {
                    val (badgeContainer, badgeContent) = when {
                        online == null -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
                        online.transportType == "WLAN" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
                        online.transportType == "cellular" || CellularTargets.isSim(online.id) ->
                            MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
                        else -> MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary
                    }
                    val label = if (online != null) {
                        NetworkUtils.channelLabel(context, online)
                    } else {
                        val rawName = resolved.symbolicSsid ?: NetworkUtils.savedTargetLabel(context, resolved.rawTarget)
                        stringResource(R.string.ui_channel_offline_short, rawName)
                    }
                    Surface(
                        shape = CircleShape,
                        color = badgeContainer,
                        contentColor = badgeContent,
                        border = if (online == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (online != null) getTransportIcon(online.transportType)
                                    else R.drawable.ic_symbol_cloud_off
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 148.dp)
                            )
                        }
                    }
                }
            }
        },
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = row.app?.appName ?: row.ruleKey,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (row.inPlan) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (row.app == null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.scenario_override_uninstalled),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                } else if (row.app.userId > 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.scenario_override_user_badge, row.app.userId),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun ScenarioChannelPickerSheet(
    request: ScenarioPickerRequest,
    defaultOption: ScenarioTargetOption,
    onlineOptions: List<ScenarioTargetOption>,
    offlineOptions: List<ScenarioTargetOption>,
    isVpnSelfLoop: (NetworkChannel, AppItem) -> Boolean,
    onSelect: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var manualSsid by remember { mutableStateOf("") }

    fun submitManualSsid() {
        val trimmed = manualSsid.trim()
        if (trimmed.isNotEmpty()) {
            onSelect(ScenarioEngine.symbolicSsid(trimmed))
        }
    }

    fun isOptionSelected(option: ScenarioTargetOption): Boolean {
        val current = request.currentTarget ?: return false
        if (option.storedValue == current) return true
        val optionSsid = option.symbolicSsid
        val currentSsid = ScenarioEngine.symbolicSsidOf(current)
        if (optionSsid != null && currentSsid != null && optionSsid.equals(currentSsid, ignoreCase = true)) {
            return true
        }
        val ch = option.onlineChannel
        return ch != null && (ch.id == current || ch.interfaceName == current)
    }

    val isSingle = request is ScenarioPickerRequest.Single
    val topEntries = buildList {
        if (request.hasInPlanApps) {
            add(
                ChannelPickerEntry(
                    key = "remove",
                    title = stringResource(R.string.scenario_override_remove_option),
                    subtitle = stringResource(R.string.scenario_override_remove_option_desc),
                    icon = R.drawable.ic_symbol_close,
                    selected = if (isSingle) false else null,
                    onClick = onRemove
                )
            )
        }
        add(
            ChannelPickerEntry(
                key = "default",
                title = stringResource(R.string.ui_default_follow_system),
                subtitle = stringResource(R.string.ui_default_follow_system_desc),
                icon = R.drawable.ic_symbol_language,
                selected = if (isSingle) isOptionSelected(defaultOption) else null,
                onClick = { onSelect(defaultOption.storedValue) }
            )
        )
        onlineOptions.forEach { option ->
            val ch = option.onlineChannel!!
            val forbidden = request.singleApp != null && isVpnSelfLoop(ch, request.singleApp!!)
            add(
                ChannelPickerEntry(
                    key = "online:${ch.id}",
                    title = NetworkUtils.channelLabel(context, ch),
                    subtitle = when {
                        forbidden && ch.vpnOwnerUid == request.singleApp?.uid ->
                            stringResource(R.string.channel_vpn_self_forbidden)
                        forbidden -> stringResource(R.string.ui_vpn_loop_blocked)
                        else -> channelOptionSubtitle(ch)
                    },
                    icon = getTransportIcon(ch.transportType),
                    selected = if (isSingle) isOptionSelected(option) else null,
                    enabled = !forbidden,
                    onClick = { onSelect(option.storedValue) }
                )
            )
        }
    }

    val offlineEntries = offlineOptions.map { option ->
        val titleText = option.symbolicSsid
            ?: NetworkUtils.savedTargetLabel(context, option.storedValue)
        val subtitleText = stringResource(
            if (option.isSavedWifi || option.symbolicSsid != null) R.string.scenario_target_saved_wifi_sub
            else R.string.scenario_target_offline_sub
        )
        ChannelPickerEntry(
            key = "offline:${option.storedValue.lowercase()}",
            title = titleText,
            subtitle = subtitleText,
            icon = if (option.symbolicSsid != null) R.drawable.ic_symbol_wifi_off else R.drawable.ic_symbol_cloud_off,
            selected = if (isSingle) isOptionSelected(option) else null,
            onClick = { onSelect(option.storedValue) }
        )
    }

    val headerTitle = when (request) {
        is ScenarioPickerRequest.Single -> request.singleApp?.appName ?: request.singleRuleKey
        is ScenarioPickerRequest.Batch -> stringResource(R.string.ui_choose_target_channel)
    }
    val headerSubtitle = when (request) {
        is ScenarioPickerRequest.Single -> request.singleApp?.packageName ?: stringResource(R.string.scenario_override_uninstalled)
        is ScenarioPickerRequest.Batch -> stringResource(R.string.ui_batch_assign_desc, request.ruleKeys.size)
    }

    ChannelPickerSheet(singleChoice = isSingle, onDismiss = onDismiss) {
        item(key = "header") {
            ChannelPickerHeader(
                title = headerTitle,
                subtitle = headerSubtitle,
                app = request.singleApp
            )
        }
        itemsIndexed(topEntries, key = { _, entry -> entry.key }) { index, entry ->
            ChannelOption(entry = entry, index = index, count = topEntries.size)
        }
        item(key = "offline-header") {
            Text(
                text = stringResource(R.string.scenario_target_section_offline),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
            )
        }
        itemsIndexed(offlineEntries, key = { _, entry -> entry.key }) { index, entry ->
            ChannelOption(entry = entry, index = index, count = offlineEntries.size)
        }
        item(key = "manual-ssid") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualSsid,
                    onValueChange = { manualSsid = it },
                    label = { Text(stringResource(R.string.scenario_target_manual_ssid_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitManualSsid() }),
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = ::submitManualSsid,
                    enabled = manualSsid.isNotBlank()
                ) {
                    Text(stringResource(R.string.scenario_target_manual_ssid_use))
                }
            }
        }
    }
}

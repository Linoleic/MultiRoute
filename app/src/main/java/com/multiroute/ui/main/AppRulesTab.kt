package com.multiroute.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiroute.R
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.theme.AppMotion
import com.multiroute.theme.MultiRouteTheme
import com.multiroute.theme.RoutingDimensions
import com.multiroute.util.NetworkUtils

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
        val wideGrid = maxWidth >= 720.dp
        val compactControls = maxWidth >= 560.dp
        val stackChannel = (!wideGrid && maxWidth < 360.dp) || LocalDensity.current.fontScale >= 1.5f
        AppRulesContent(
            uiState = uiState,
            viewModel = viewModel,
            stackChannel = stackChannel,
            wideGrid = wideGrid,
            compactControls = compactControls,
            modifier = Modifier
                .widthIn(max = if (wideGrid) 1440.dp else RoutingDimensions.contentMaxWidth)
                .fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppRulesContent(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    stackChannel: Boolean,
    wideGrid: Boolean,
    compactControls: Boolean,
    modifier: Modifier
) {
    val proxy by viewModel.proxyUpstream.state.collectAsStateWithLifecycle()
    val toggleColors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
    val toggleShapes = ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.MinHeight)
    val gridState = rememberLazyGridState()
    val listBottomPadding by animateDpAsState(
        targetValue = if (uiState.isSelectionMode) 96.dp else RoutingDimensions.contentPadding,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "listBottomPadding"
    )

    Box(modifier) {
        LazyVerticalGrid(
            state = gridState,
            columns = if (wideGrid) GridCells.Adaptive(minSize = 350.dp) else GridCells.Fixed(1),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = RoutingDimensions.contentPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(if (wideGrid) 10.dp else ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(bottom = listBottomPadding)
        ) {
            // 搜索与过滤器
            item(key = "routing-controls", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.padding(
                        top = if (compactControls) 4.dp else RoutingDimensions.rowVerticalPadding,
                        bottom = if (compactControls) 4.dp else 6.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (compactControls) 8.dp else 10.dp)
                ) {
                    val searchField: @Composable (Modifier) -> Unit = { fieldModifier ->
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            modifier = fieldModifier,
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
                                            contentDescription = stringResource(R.string.cd_clear_search)
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
                    }
                    val batchAction: @Composable () -> Unit = {
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
                                    modifier = if (compactControls) Modifier else Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(RoutingDimensions.rowContentGap)
                                ) {
                                    Text(
                                        text = stringResource(R.string.ui_app_list, uiState.apps.size),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = (if (compactControls) Modifier else Modifier.weight(1f))
                                            .semantics { heading() }
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

                    if (compactControls) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            searchField(Modifier.weight(1f))
                            batchAction()
                        }
                    } else {
                        searchField(Modifier.fillMaxWidth())
                    }

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

                    if (!compactControls) {
                        batchAction()
                    }
                }
            }

            if (uiState.isSyncing) item(key = "routing-syncing", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    RoutingListStatus(stringResource(R.string.routing_syncing))
                }
            }

            if (uiState.isLoadingApps) item(key = "routing-loading", span = { GridItemSpan(maxLineSpan) }) {
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
            } else if (uiState.isInstalledAppsIncomplete) item(key = "routing-incomplete", span = { GridItemSpan(maxLineSpan) }) {
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
                uiState.apps.isEmpty() -> item(key = "routing-empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
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
                }
                else -> itemsIndexed(uiState.apps, key = { _, app -> app.ruleKey }) { index, app ->
                    AppItemRow(
                        app = app,
                        channels = uiState.channels,
                        stackChannel = stackChannel,
                        gatewayTarget = proxy.gatewayAppTargets[app.ruleKey].takeIf { uiState.scenarioEditId == null },
                        isVpnOwner = app.uid > 0 && uiState.channels.any { it.isVpn && it.vpnOwnerUid == app.uid },
                        modifier = Modifier.animateItem(
                            fadeInSpec = AppMotion.itemFade(),
                            fadeOutSpec = AppMotion.itemFade(),
                            placementSpec = AppMotion.itemPlacement()
                        ),
                        index = if (wideGrid) 0 else index,
                        count = if (wideGrid) 1 else uiState.apps.size,
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
    isVpnOwner: Boolean = false,
    gatewayTarget: String? = null,
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
                // Keep trailing spacing inside AnimatedVisibility so Row spacing doesn't snap on exit.
                Box(Modifier.padding(end = 12.dp)) {
                    Checkbox(checked = isSelected, onCheckedChange = null)
                }
            }
            AppIcon(drawable = app.icon, size = 36.dp)
        }
    }
    val supporting: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(RoutingDimensions.textGap)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RoutingDimensions.textGap)
            ) {
                Text(
                    text = app.packageName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isVpnOwner) {
                    AppRoutingStatusIcon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = stringResource(R.string.ui_vpn_client),
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    )
                }
                if (gatewayTarget != null) {
                    val context = LocalContext.current
                    val gatewayLabel = channels.firstOrNull { it.proxyUpstreamKey() == gatewayTarget }
                        ?.let { NetworkUtils.channelLabel(context, it) } ?: NetworkUtils.savedTargetLabel(context, gatewayTarget)
                    AppRoutingStatusIcon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = stringResource(R.string.proxy_gateway_app_saved, gatewayLabel),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            if (stackChannel) AppChannelBadge(app, current, accent, isSelected)
        }
    }
    val trailing: (@Composable () -> Unit)? = if (!stackChannel) {
        { AppChannelBadge(app, current, accent, isSelected, Modifier.widthIn(max = 152.dp)) }
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

@Composable
private fun AppRoutingStatusIcon(
    imageVector: ImageVector,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color
) {
    val density = LocalDensity.current
    // Fit inside the package text's line height in both the regular and stacked layouts.
    val size = with(density) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }
    Box(
        modifier = Modifier.size(size).background(containerColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(with(density) { 12.sp.toDp() }),
            tint = contentColor
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppChannelBadge(
    app: AppItem,
    current: NetworkChannel?,
    accent: ChannelAccentColors,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AnimatedContent(
        targetState = app.targetChannelId,
        modifier = modifier,
        transitionSpec = { AppMotion.content() },
        label = "channelBadge"
    ) { targetChannelId ->
        if (targetChannelId == CHANNEL_DEFAULT) {
            Text(
                text = stringResource(R.string.vm_system_default),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                        overflow = TextOverflow.Ellipsis
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
                        count = 2,
                        isVpnOwner = true
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
                        isSelected = true,
                        gatewayTarget = "wlan1"
                    )
                }
            }
        }
    }
}

package com.multiroute.ui.main

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.util.NetworkUtils
import com.multiroute.util.ScenarioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 场景方案抽屉：查看全部方案、拖拽调整优先级、单独启停、手动应用、恢复自动、保存/删除方案。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScenarioBottomSheet(
    profiles: List<ScenarioProfile>,
    mode: ScenarioEngine.SelectionMode,
    manualIds: Set<String>,
    channels: List<NetworkChannel>,
    onSetManual: (Set<String>) -> Unit,
    onSetDisabled: () -> Unit,
    activeIds: Set<String>,
    conflicts: Map<String, Int>,
    onApplyManually: (String) -> Unit,
    onClearManual: () -> Unit,
    onSaveCurrent: () -> Unit,
    onDelete: (String) -> Unit,
    onEditOverrides: (String) -> Unit,
    onEditDetails: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val cardSpacingPx = with(density) { 12.dp.toPx() }
    val manualMode = mode == ScenarioEngine.SelectionMode.MANUAL
    var pendingManualIds by remember { mutableStateOf<Set<String>?>(null) }
    var pendingDelete by remember { mutableStateOf<ScenarioProfile?>(null) }
    var confirmAutomatic by remember { mutableStateOf(false) }

    var displayProfiles by remember { mutableStateOf(profiles) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(profiles) {
        if (draggingId == null) displayProfiles = profiles
    }
    val currentDisplay by rememberUpdatedState(displayProfiles)
    val currentTargetIndex by rememberUpdatedState(targetIndex)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val scrollState = rememberScrollState()

    val enabledProfiles = displayProfiles.filter { it.enabled }
    val selectedManualIds = enabledProfiles.map { it.id }.filter { it in manualIds }.toSet()
    val moveUpLabel = stringResource(com.multiroute.R.string.scenario_move_up)
    val moveDownLabel = stringResource(com.multiroute.R.string.scenario_move_down)
    val dragHandleCd = stringResource(com.multiroute.R.string.scenario_drag_handle_cd)
    val draggingIndex = draggingId?.let { id -> displayProfiles.indexOfFirst { it.id == id } } ?: -1
    val draggedStepPx = if (draggingIndex >= 0) {
        (itemHeights[displayProfiles[draggingIndex].id]?.toFloat() ?: 0f) + cardSpacingPx
    } else 0f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState, enabled = draggingId == null)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(com.multiroute.R.string.scenario_sheet_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = stringResource(com.multiroute.R.string.scenario_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            // Three scenario selection modes: Automatic, Manual, Disabled (base rules only).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = mode == ScenarioEngine.SelectionMode.AUTOMATIC,
                    onClick = {
                        when (mode) {
                            ScenarioEngine.SelectionMode.MANUAL -> {
                                if (selectedManualIds.isEmpty()) onClearManual() else confirmAutomatic = true
                            }
                            ScenarioEngine.SelectionMode.DISABLED -> onClearManual()
                            ScenarioEngine.SelectionMode.AUTOMATIC -> Unit
                        }
                    },
                    label = { Text(stringResource(com.multiroute.R.string.scenario_mode_auto)) }
                )
                FilterChip(
                    selected = mode == ScenarioEngine.SelectionMode.MANUAL,
                    onClick = { if (!manualMode) pendingManualIds = selectedManualIds },
                    label = { Text(stringResource(com.multiroute.R.string.scenario_mode_manual)) }
                )
                FilterChip(
                    selected = mode == ScenarioEngine.SelectionMode.DISABLED,
                    onClick = { if (mode != ScenarioEngine.SelectionMode.DISABLED) onSetDisabled() },
                    label = { Text(stringResource(com.multiroute.R.string.scenario_mode_disabled)) }
                )
            }
            if (displayProfiles.isEmpty()) {
                Text(
                    text = stringResource(com.multiroute.R.string.scenario_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            displayProfiles.forEachIndexed { index, profile ->
                key(profile.id) {
                val isDragging = draggingId == profile.id
                val siblingShiftTarget = when {
                    draggingIndex < 0 || targetIndex < 0 || isDragging -> 0f
                    draggingIndex < targetIndex && index in (draggingIndex + 1)..targetIndex -> -draggedStepPx
                    draggingIndex > targetIndex && index in targetIndex until draggingIndex -> draggedStepPx
                    else -> 0f
                }
                val animatedSiblingShift by animateFloatAsState(
                    targetValue = if (draggingIndex >= 0) siblingShiftTarget else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "scenarioSiblingShift"
                )
                val elevation by animateDpAsState(
                    targetValue = if (isDragging) 6.dp else 0.dp,
                    label = "scenarioDragElevation"
                )
                val cardTranslationY = when {
                    isDragging -> dragOffsetY
                    draggingIndex >= 0 -> animatedSiblingShift
                    else -> 0f
                }
                val isMatched = mode == ScenarioEngine.SelectionMode.AUTOMATIC && profile.id in activeIds
                val isManualOn = manualMode && profile.id in manualIds
                val isActiveNow = profile.enabled && (isMatched || isManualOn)
                val statusText = stringResource(
                    when {
                        !profile.enabled -> com.multiroute.R.string.scenario_status_disabled
                        mode == ScenarioEngine.SelectionMode.DISABLED -> com.multiroute.R.string.scenario_status_mode_disabled
                        isManualOn -> com.multiroute.R.string.scenario_status_manual_on
                        manualMode -> com.multiroute.R.string.scenario_status_manual_off
                        profile.id in activeIds -> com.multiroute.R.string.scenario_status_matched
                        else -> com.multiroute.R.string.scenario_status_waiting
                    }
                )
                // Each plan gets its own surface: without it the name, the condition and two rows of
                // actions ran together and it was hard to tell where one plan ended.
                // Opaque container colors prevent Android elevation shadows from bleeding through the card.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { itemHeights[profile.id] = it.height }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = cardTranslationY
                            if (isDragging) {
                                scaleX = 1.015f
                                scaleY = 1.015f
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isDragging) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = if (isDragging) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    } else null,
                    tonalElevation = 0.dp,
                    shadowElevation = elevation
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (displayProfiles.size > 1) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 32.dp, height = 36.dp)
                                        .semantics {
                                            contentDescription = dragHandleCd
                                            val actions = mutableListOf<CustomAccessibilityAction>()
                                            if (index > 0) {
                                                actions += CustomAccessibilityAction(moveUpLabel) {
                                                    val next = displayProfiles.toMutableList()
                                                    next.add(index - 1, next.removeAt(index))
                                                    displayProfiles = next
                                                    onReorder(next.map { it.id })
                                                    true
                                                }
                                            }
                                            if (index < displayProfiles.lastIndex) {
                                                actions += CustomAccessibilityAction(moveDownLabel) {
                                                    val next = displayProfiles.toMutableList()
                                                    next.add(index + 1, next.removeAt(index))
                                                    displayProfiles = next
                                                    onReorder(next.map { it.id })
                                                    true
                                                }
                                            }
                                            customActions = actions
                                        }
                                        .pointerInput(profile.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    val startIdx = currentDisplay.indexOfFirst { it.id == profile.id }
                                                    if (startIdx >= 0) {
                                                        draggingId = profile.id
                                                        targetIndex = startIdx
                                                        dragOffsetY = 0f
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    if (draggingId != profile.id) return@detectDragGestures
                                                    dragOffsetY += dragAmount.y
                                                    val list = currentDisplay
                                                    val startIdx = list.indexOfFirst { it.id == profile.id }
                                                    if (startIdx < 0) return@detectDragGestures
                                                    val heights = list.map { itemHeights[it.id]?.toFloat() ?: 120f }
                                                    val nextTarget = computeDragTargetIndex(
                                                        draggingIndex = startIdx,
                                                        dragOffsetY = dragOffsetY,
                                                        heights = heights,
                                                        spacingPx = cardSpacingPx
                                                    )
                                                    if (nextTarget != currentTargetIndex) {
                                                        targetIndex = nextTarget
                                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                },
                                                onDragEnd = {
                                                    val list = currentDisplay
                                                    val fromIdx = list.indexOfFirst { it.id == profile.id }
                                                    val toIdx = currentTargetIndex
                                                    draggingId = null
                                                    targetIndex = -1
                                                    dragOffsetY = 0f
                                                    if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                                                        val reordered = list.toMutableList().apply {
                                                            add(toIdx, removeAt(fromIdx))
                                                        }
                                                        displayProfiles = reordered
                                                        currentOnReorder(reordered.map { it.id })
                                                    }
                                                },
                                                onDragCancel = {
                                                    draggingId = null
                                                    targetIndex = -1
                                                    dragOffsetY = 0f
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(com.multiroute.R.drawable.ic_symbol_drag_handle),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        alpha = if (profile.enabled || isDragging) 1f else 0.65f
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = if (isActiveNow) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (isActiveNow) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Switch(
                                checked = profile.enabled,
                                onCheckedChange = { onSetEnabled(profile.id, it) }
                            )
                        }

                        val targets = profile.overrides.values.map { id ->
                            val channel = channels.firstOrNull { it.id == id || it.interfaceName == id }
                            when {
                                id == CHANNEL_DEFAULT -> context.getString(com.multiroute.R.string.vm_system_default)
                                channel != null -> NetworkUtils.channelLabel(context, channel)
                                ScenarioEngine.isSymbolicChannel(id) -> id.removePrefix(ScenarioEngine.SYMBOLIC_SSID_PREFIX)
                                else -> NetworkUtils.savedTargetLabel(context, id)
                            }
                        }.distinct()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = if (displayProfiles.size > 1) 40.dp else 0.dp)
                                .graphicsLayer {
                                    alpha = if (profile.enabled || isDragging) 1f else 0.65f
                                },
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = scenarioTriggerLabel(profile.trigger),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    profile.bypassBaseRules && targets.isEmpty() ->
                                        stringResource(com.multiroute.R.string.scenario_bypass_summary)
                                    profile.bypassBaseRules -> pluralStringResource(
                                        com.multiroute.R.plurals.scenario_bypass_summary_with_overrides,
                                        profile.overrides.size,
                                        profile.overrides.size,
                                        targets.joinToString(" / ")
                                    )
                                    targets.isEmpty() -> stringResource(com.multiroute.R.string.scenario_no_targets)
                                    else -> pluralStringResource(
                                        com.multiroute.R.plurals.scenario_summary,
                                        profile.overrides.size,
                                        profile.overrides.size,
                                        targets.joinToString(" / ")
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            (conflicts[profile.id] ?: 0).takeIf { it > 0 }?.let { count ->
                                Text(
                                    text = stringResource(
                                        com.multiroute.R.string.scenario_conflicts, count
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val actionPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            if (profile.enabled) {
                                TextButton(
                                    onClick = {
                                        if (manualMode) onApplyManually(profile.id)
                                        else pendingManualIds = selectedManualIds + profile.id
                                    },
                                    contentPadding = actionPadding
                                ) {
                                    Text(
                                        text = stringResource(
                                            when {
                                                !manualMode -> com.multiroute.R.string.scenario_switch_and_enable_short
                                                profile.id in manualIds -> com.multiroute.R.string.scenario_turn_off
                                                else -> com.multiroute.R.string.scenario_turn_on
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            TextButton(
                                onClick = { onEditOverrides(profile.id) },
                                contentPadding = actionPadding
                            ) {
                                Text(
                                    text = stringResource(com.multiroute.R.string.scenario_edit_overrides),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            TextButton(
                                onClick = { onEditDetails(profile.id) },
                                contentPadding = actionPadding
                            ) {
                                Text(
                                    text = stringResource(com.multiroute.R.string.scenario_edit_details_short),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { pendingDelete = profile },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(com.multiroute.R.drawable.ic_symbol_delete),
                                    contentDescription = stringResource(com.multiroute.R.string.scenario_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                }
            }

            FilledTonalButton(
                onClick = onSaveCurrent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(com.multiroute.R.drawable.ic_symbol_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(com.multiroute.R.string.scenario_save))
            }
        }
    }
    pendingManualIds?.let { ids ->
        val names = enabledProfiles.filter { it.id in ids }.map { it.name }
        val selection = if (names.isEmpty()) stringResource(com.multiroute.R.string.scenario_manual_empty)
        else names.joinToString("\n")
        ScenarioModeConfirmation(
            title = stringResource(com.multiroute.R.string.scenario_confirm_manual_title),
            message = stringResource(com.multiroute.R.string.scenario_confirm_manual_body, selection),
            onDismiss = { pendingManualIds = null },
            onConfirm = {
                pendingManualIds = null
                onSetManual(ids)
            }
        )
    }
    if (confirmAutomatic) {
        ScenarioModeConfirmation(
            title = stringResource(com.multiroute.R.string.scenario_confirm_auto_title),
            message = stringResource(com.multiroute.R.string.scenario_confirm_auto_body),
            onDismiss = { confirmAutomatic = false },
            onConfirm = {
                confirmAutomatic = false
                onClearManual()
            }
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(com.multiroute.R.string.scenario_delete_confirm_title)) },
            text = { Text(stringResource(com.multiroute.R.string.scenario_delete_confirm_body, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target.id)
                }) {
                    Text(
                        text = stringResource(com.multiroute.R.string.scenario_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(com.multiroute.R.string.ui_cancel))
                }
            }
        )
    }
}

internal fun computeDragTargetIndex(
    draggingIndex: Int,
    dragOffsetY: Float,
    heights: List<Float>,
    spacingPx: Float
): Int {
    if (draggingIndex !in heights.indices || heights.size <= 1) return draggingIndex
    val tops = FloatArray(heights.size)
    val centers = FloatArray(heights.size)
    var y = 0f
    for (i in heights.indices) {
        tops[i] = y
        centers[i] = y + heights[i] * 0.5f
        y += heights[i] + spacingPx
    }
    var target = draggingIndex
    if (dragOffsetY > 0f) {
        val draggedBottom = tops[draggingIndex] + heights[draggingIndex] + dragOffsetY
        while (target < heights.lastIndex && draggedBottom > centers[target + 1]) {
            target++
        }
    } else if (dragOffsetY < 0f) {
        val draggedTop = tops[draggingIndex] + dragOffsetY
        while (target > 0 && draggedTop < centers[target - 1]) {
            target--
        }
    }
    return target
}

@Composable
private fun ScenarioModeConfirmation(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(com.multiroute.R.string.scenario_confirm_switch)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.multiroute.R.string.ui_cancel)) }
        }
    )
}

/** 触发条件的可读描述，用于方案列表。 */
@Composable
fun scenarioTriggerLabel(trigger: ScenarioTrigger): String = when (trigger) {
    is ScenarioTrigger.SsidMatch -> if (trigger.ssids.isEmpty()) {
        stringResource(com.multiroute.R.string.scenario_trigger_ssid_empty)
    } else {
        stringResource(com.multiroute.R.string.scenario_trigger_ssid) + " " +
                trigger.ssids.joinToString(" / ")
    }
    is ScenarioTrigger.WifiLinkCount -> stringResource(com.multiroute.R.string.scenario_trigger_links2)
    is ScenarioTrigger.CellularOnly -> stringResource(com.multiroute.R.string.scenario_trigger_cellular)
    is ScenarioTrigger.Always -> stringResource(com.multiroute.R.string.scenario_trigger_always)
    is ScenarioTrigger.Manual -> stringResource(com.multiroute.R.string.scenario_trigger_manual)
}

/**
 * 把当前指派保存为方案：名称 + 触发条件（Wi-Fi 名称 / 双 Wi-Fi / 仅蜂窝 / 始终 / 仅手动）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScenarioSaveDialog(
    currentSsids: List<String>,
    onSave: (String, ScenarioTrigger, Boolean) -> Unit,
    onDismiss: () -> Unit,
    titleRes: Int = com.multiroute.R.string.scenario_save_title,
    initialName: String = "",
    initialTrigger: ScenarioTrigger? = null,
    initialBypassBaseRules: Boolean = false
) {
    val name = remember { mutableStateOf(initialName) }
    val bypassBaseRules = remember { mutableStateOf(initialBypassBaseRules) }
    val kind = remember {
        mutableStateOf(
            when (initialTrigger) {
                is ScenarioTrigger.WifiLinkCount -> "links2"
                is ScenarioTrigger.CellularOnly -> "cellular"
                is ScenarioTrigger.Always -> "always"
                is ScenarioTrigger.Manual -> "manual"
                else -> "ssid"
            }
        )
    }
    val picked = remember {
        mutableStateOf(
            (initialTrigger as? ScenarioTrigger.SsidMatch)?.ssids?.toSet() ?: emptySet<String>()
        )
    }
    var manualSsid by remember { mutableStateOf("") }
    var savedSsids by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        savedSsids = withContext(Dispatchers.IO) {
            runCatching { com.multiroute.wifi.DualWifiScanParser.getSavedNetworkIds().keys.sorted() }
                .getOrDefault(emptyList())
        }
    }
    fun addManualSsid() {
        val trimmed = manualSsid.trim()
        if (trimmed.isNotEmpty()) {
            picked.value = picked.value + trimmed
            manualSsid = ""
        }
    }
    // A plan being edited may name a network that is not connected right now; keep it selectable so the
    // dialog cannot silently drop it.
    val ssidOptions = (currentSsids + picked.value + savedSsids).filter { it.isNotBlank() }.distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name.value,
                    onValueChange = { name.value = it },
                    label = { Text(stringResource(com.multiroute.R.string.scenario_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(com.multiroute.R.string.scenario_trigger),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "ssid" to com.multiroute.R.string.scenario_trigger_ssid,
                        "links2" to com.multiroute.R.string.scenario_trigger_links2,
                        "cellular" to com.multiroute.R.string.scenario_trigger_cellular,
                        "always" to com.multiroute.R.string.scenario_trigger_always,
                        "manual" to com.multiroute.R.string.scenario_trigger_manual
                    ).forEach { (id, labelRes) ->
                        FilterChip(
                            selected = kind.value == id,
                            onClick = { kind.value = id },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
                if (kind.value == "ssid") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualSsid,
                            onValueChange = { manualSsid = it },
                            label = { Text(stringResource(com.multiroute.R.string.scenario_ssid_input_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addManualSsid() }),
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalIconButton(
                            onClick = ::addManualSsid,
                            enabled = manualSsid.isNotBlank()
                        ) {
                            Icon(
                                painter = painterResource(com.multiroute.R.drawable.ic_symbol_add),
                                contentDescription = stringResource(com.multiroute.R.string.scenario_ssid_add_cd)
                            )
                        }
                    }
                    if (ssidOptions.isEmpty()) {
                        Text(
                            text = stringResource(com.multiroute.R.string.scenario_ssid_saved_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ssidOptions.forEach { ssid ->
                                FilterChip(
                                    selected = picked.value.contains(ssid),
                                    onClick = {
                                        picked.value = if (picked.value.contains(ssid)) {
                                            picked.value - ssid
                                        } else {
                                            picked.value + ssid
                                        }
                                    },
                                    label = { Text(ssid) }
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(com.multiroute.R.string.scenario_bypass_base_title),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                        Text(
                            text = stringResource(com.multiroute.R.string.scenario_bypass_base_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = bypassBaseRules.value,
                        onCheckedChange = { bypassBaseRules.value = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trigger = when (kind.value) {
                    "links2" -> ScenarioTrigger.WifiLinkCount(min = 2)
                    "cellular" -> ScenarioTrigger.CellularOnly
                    "always" -> ScenarioTrigger.Always
                    "manual" -> ScenarioTrigger.Manual
                    else -> ScenarioTrigger.SsidMatch(picked.value.toList())
                }
                onSave(name.value, trigger, bypassBaseRules.value)
            }) {
                Text(stringResource(com.multiroute.R.string.ui_save_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.multiroute.R.string.ui_cancel))
            }
        }
    )
}

package com.multiroute.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.util.NetworkUtils
import com.multiroute.util.ScenarioEngine

/**
 * 场景方案抽屉：查看全部方案、手动应用、恢复自动、把当前指派保存为方案、删除方案。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScenarioBottomSheet(
    profiles: List<ScenarioProfile>,
    manualMode: Boolean,
    manualIds: Set<String>,
    channels: List<NetworkChannel>,
    onSetManual: (Set<String>) -> Unit,
    activeIds: Set<String>,
    conflicts: Map<String, Int>,
    onApplyManually: (String) -> Unit,
    onClearManual: () -> Unit,
    onSaveCurrent: () -> Unit,
    onDelete: (String) -> Unit,
    onEditOverrides: (String) -> Unit,
    onEditDetails: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pendingManualIds by remember { mutableStateOf<Set<String>?>(null) }
    var confirmAutomatic by remember { mutableStateOf(false) }
    val enabledProfiles = profiles.filter { it.enabled }
    val selectedManualIds = enabledProfiles.map { it.id }.filter { it in manualIds }.toSet()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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

            // Side by side: the two modes are one choice, not two stacked options.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !manualMode,
                    onClick = {
                        if (manualMode) {
                            if (selectedManualIds.isEmpty()) onClearManual() else confirmAutomatic = true
                        }
                    },
                    label = { Text(stringResource(com.multiroute.R.string.scenario_mode_auto)) }
                )
                FilterChip(
                    selected = manualMode,
                    onClick = { if (!manualMode) pendingManualIds = selectedManualIds },
                    label = { Text(stringResource(com.multiroute.R.string.scenario_mode_manual)) }
                )
            }
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(com.multiroute.R.string.scenario_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            profiles.forEach { profile ->
                // Each plan gets its own surface: without it the name, the condition and two rows of
                // actions ran together and it was hard to tell where one plan ended.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Column {
                            // Indented so the name starts where the first action's label starts, instead of
                            // sitting further left than the buttons below it.
                            Column(modifier = Modifier.padding(start = 13.dp)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = scenarioTriggerLabel(profile.trigger),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                val targets = profile.overrides.values.map { id ->
                                    val channel = channels.firstOrNull { it.id == id || it.interfaceName == id }
                                    when {
                                        id == CHANNEL_DEFAULT -> context.getString(com.multiroute.R.string.vm_system_default)
                                        channel != null -> NetworkUtils.channelLabel(context, channel)
                                        ScenarioEngine.isSymbolicChannel(id) -> id.removePrefix(ScenarioEngine.SYMBOLIC_SSID_PREFIX)
                                        else -> NetworkUtils.savedTargetLabel(context, id)
                                    }
                                }.distinct()
                                Text(
                                    text = if (targets.isEmpty()) stringResource(com.multiroute.R.string.scenario_no_targets)
                                    else pluralStringResource(
                                        com.multiroute.R.plurals.scenario_summary,
                                        profile.overrides.size,
                                        profile.overrides.size,
                                        targets.joinToString(" / ")
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(
                                        when {
                                            !profile.enabled -> com.multiroute.R.string.scenario_status_disabled
                                            manualMode && profile.id in manualIds -> com.multiroute.R.string.scenario_status_manual_on
                                            manualMode -> com.multiroute.R.string.scenario_status_manual_off
                                            profile.id in activeIds -> com.multiroute.R.string.scenario_status_matched
                                            else -> com.multiroute.R.string.scenario_status_waiting
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall
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
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (profile.enabled) {
                                    TextButton(onClick = {
                                        if (manualMode) onApplyManually(profile.id)
                                        else pendingManualIds = selectedManualIds + profile.id
                                    }) {
                                        Text(stringResource(when {
                                            !manualMode -> com.multiroute.R.string.scenario_switch_and_enable
                                            profile.id in manualIds -> com.multiroute.R.string.scenario_turn_off
                                            else -> com.multiroute.R.string.scenario_turn_on
                                        }))
                                    }
                                }
                                TextButton(onClick = { onEditOverrides(profile.id) }) {
                                    Text(
                                        text = stringResource(com.multiroute.R.string.scenario_edit_overrides),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                TextButton(onClick = { onEditDetails(profile.id) }) {
                                    Text(
                                        text = stringResource(com.multiroute.R.string.scenario_edit_details),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                TextButton(onClick = { onMove(profile.id, -1) }) {
                                    Text(text = "\u2191", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { onMove(profile.id, 1) }) {
                                    Text(text = "\u2193", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { onDelete(profile.id) }) {
                                    Text(stringResource(com.multiroute.R.string.scenario_delete))
                                }
                            }
                        }
                    }
                }
            }

            TextButton(onClick = onSaveCurrent) {
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
@Composable
fun ScenarioSaveDialog(
    currentSsids: List<String>,
    onSave: (String, ScenarioTrigger) -> Unit,
    onDismiss: () -> Unit,
    titleRes: Int = com.multiroute.R.string.scenario_save_title,
    initialName: String = "",
    initialTrigger: ScenarioTrigger? = null
) {
    val name = remember { mutableStateOf(initialName) }
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
    // A plan being edited may name a network that is not connected right now; keep it selectable so the
    // dialog cannot silently drop it.
    val ssidOptions = (currentSsids + picked.value).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (ssidOptions.isEmpty()) {
                        Text(
                            text = stringResource(com.multiroute.R.string.scenario_no_ssid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                onSave(name.value, trigger)
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

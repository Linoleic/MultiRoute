package com.multiroute.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger

/**
 * 场景方案抽屉：查看全部方案、手动应用、恢复自动、把当前指派保存为方案、删除方案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioBottomSheet(
    profiles: List<ScenarioProfile>,
    manualMode: Boolean,
    manualIds: Set<String>,
    onSetManual: () -> Unit,
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    onClick = onClearManual,
                    label = { Text(stringResource(com.multiroute.R.string.scenario_mode_auto)) }
                )
                FilterChip(
                    selected = manualMode,
                    onClick = onSetManual,
                    label = { Text(stringResource(com.multiroute.R.string.scenario_mode_manual)) }
                )
            }
            Text(
                text = stringResource(com.multiroute.R.string.scenario_sheet_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
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
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
                            }
                        }
                        FilterChip(
                            selected = if (manualMode) profile.id in manualIds else profile.id in activeIds,
                            onClick = { onApplyManually(profile.id) },
                            label = {
                                Text(
                                    when {
                                        profile.id in activeIds -> stringResource(com.multiroute.R.string.scenario_active)
                                        manualMode -> stringResource(com.multiroute.R.string.scenario_turn_on)
                                        else -> stringResource(com.multiroute.R.string.scenario_apply)
                                    }
                                )
                            }
                        )
                        TextButton(onClick = { onDelete(profile.id) }) {
                            Text(stringResource(com.multiroute.R.string.scenario_delete))
                        }
                    }
                }
            }

            TextButton(onClick = onSaveCurrent) {
                Text(stringResource(com.multiroute.R.string.scenario_save))
            }
        }
    }
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

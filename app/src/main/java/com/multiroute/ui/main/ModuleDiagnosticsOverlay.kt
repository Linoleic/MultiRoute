package com.multiroute.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.multiroute.R
import com.multiroute.util.DiagnosticInfo
import com.multiroute.util.ModuleStatus
import com.multiroute.util.SuHelper

internal enum class ModuleIndicator { CHECKING, UNKNOWN, READY, RESTART_REQUIRED, ERROR }

internal fun moduleStateIndicator(status: ModuleStatus?): ModuleIndicator = when (status) {
    null -> ModuleIndicator.CHECKING
    ModuleStatus.ACTIVE -> ModuleIndicator.READY
    ModuleStatus.OUTDATED -> ModuleIndicator.RESTART_REQUIRED
    ModuleStatus.LEGACY_UNVERIFIED -> ModuleIndicator.UNKNOWN
    else -> ModuleIndicator.ERROR
}

internal fun moduleIndicator(info: DiagnosticInfo?, refreshing: Boolean, failed: Boolean): ModuleIndicator = when {
    refreshing -> ModuleIndicator.CHECKING
    failed -> ModuleIndicator.UNKNOWN
    info == null -> ModuleIndicator.CHECKING
    !info.isRootGranted -> ModuleIndicator.ERROR
    else -> moduleStateIndicator(info.moduleStatus)
}

@Composable
internal fun moduleIndicatorColor(indicator: ModuleIndicator): Color = when (indicator) {
    ModuleIndicator.ERROR -> MaterialTheme.colorScheme.error
    ModuleIndicator.RESTART_REQUIRED -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
        Color(0xFFFFD54F) else Color(0xFF9C6F00)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun ModuleIndicatorIcon(indicator: ModuleIndicator, modifier: Modifier = Modifier) {
    if (indicator == ModuleIndicator.CHECKING) CircularProgressIndicator(
        modifier.size(20.dp), color = moduleIndicatorColor(indicator), strokeWidth = 2.dp)
    else Icon(
        imageVector = when (indicator) {
            ModuleIndicator.READY -> Icons.Default.CheckCircle
            ModuleIndicator.RESTART_REQUIRED -> Icons.Default.Error
            ModuleIndicator.ERROR -> Icons.Default.Cancel
            else -> Icons.Default.Help
        }, contentDescription = null, tint = moduleIndicatorColor(indicator), modifier = modifier
    )
}

@Composable
private fun statusDescription(state: MainUiState): String = when (moduleIndicator(
    state.diagnosticInfo, state.isEnvironmentRefreshing, state.environmentRefreshFailed
)) {
    ModuleIndicator.CHECKING -> stringResource(R.string.ui_checking_module)
    ModuleIndicator.UNKNOWN -> {
        val diag = state.env.diagnosticInfo
        if (state.env.environmentRefreshFailed || diag == null)
            stringResource(R.string.module_check_failed)
        else SuHelper.moduleStatusLabel(LocalContext.current, diag.moduleStatus)
    }
    ModuleIndicator.READY -> stringResource(R.string.module_ready)
    ModuleIndicator.RESTART_REQUIRED, ModuleIndicator.ERROR -> state.diagnosticInfo!!.let {
        if (!it.isRootGranted) stringResource(R.string.diag_root_missing)
        else SuHelper.moduleStatusLabel(LocalContext.current, it.moduleStatus)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModuleStatusButton(state: MainUiState, onClick: () -> Unit) {
    val indicator = moduleIndicator(state.diagnosticInfo, state.isEnvironmentRefreshing, state.environmentRefreshFailed)
    val description = stringResource(R.string.module_status_accessibility, statusDescription(state))
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
            ModuleIndicatorIcon(indicator, Modifier.size(22.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModuleDiagnosticsOverlay(
    uiState: MainUiState, viewModel: MainScreenViewModel, wide: Boolean,
    onDismiss: () -> Unit, onLogs: () -> Unit
) {
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.module_status_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.module_close))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (uiState.isEnvironmentRefreshing || uiState.environmentRefreshFailed || uiState.diagnosticInfo == null) item {
                    Text(statusDescription(uiState), style = MaterialTheme.typography.bodyLarge,
                        color = moduleIndicatorColor(moduleIndicator(uiState.diagnosticInfo,
                            uiState.isEnvironmentRefreshing, uiState.environmentRefreshFailed)))
                }
                if (uiState.diagnosticInfo != null && !uiState.environmentRefreshFailed) {
                    item { ModuleDiagnosticsPanel(uiState, viewModel) }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::refreshEnvironment, enabled = !uiState.isEnvironmentRefreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cd_refresh))
                        }
                        TextButton(onClick = onLogs) { Text(stringResource(R.string.module_open_logs)) }
                    }
                }
            }
        }
    }
    if (wide) Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(24.dp).widthIn(max = 640.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) { content() }
    } else ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { content() }
}

package com.multiroute.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.multiroute.R
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CellularControlCard(uiState: MainUiState, viewModel: MainScreenViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val controlState by viewModel.cellularControlState.collectAsState()
    LaunchedEffect(context, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshCellularControlState()
                delay(3000)
            }
        }
    }
    val secondary = controlState.subscriptions.singleOrNull { !it.isDefault }
    val secondaryOnline = secondary?.networks?.values?.any { iface -> uiState.channels.any {
        it.interfaceName == iface && it.transportType == "cellular" && it.isValidated
    } } == true
    val statusMessage = stringResource(when {
        controlState.isBusy -> R.string.cellular_changing
        !controlState.isLoaded -> R.string.cellular_loading
        controlState.hasFailed -> R.string.cellular_failed
        controlState.mode == 0 -> R.string.cellular_stopped
        secondaryOnline -> R.string.cellular_connected
        else -> R.string.cellular_waiting
    })
    val statusColor = when {
        controlState.hasFailed -> MaterialTheme.colorScheme.error
        secondaryOnline -> MaterialTheme.colorScheme.primary
        controlState.mode == 0 -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconContainerColor = when {
        controlState.hasFailed -> MaterialTheme.colorScheme.errorContainer
        controlState.mode != 0 -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val iconTint = when {
        controlState.hasFailed -> MaterialTheme.colorScheme.onErrorContainer
        controlState.mode != 0 -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.outline
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(iconContainerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.cellular_control_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    when {
                        secondary != null -> Text(
                            text = stringResource(
                                R.string.cellular_secondary_sim_summary,
                                secondary.slot,
                                secondary.carrier,
                                stringResource(
                                    if (controlState.mode != 0) R.string.cellular_state_enabled
                                    else R.string.cellular_state_disabled
                                )
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        controlState.isLoaded -> Text(
                            text = stringResource(R.string.cellular_two_required_short),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                when {
                    controlState.isBusy -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    controlState.mode == 0 -> Button(
                        onClick = { viewModel.selectCellularMode(controlState.preferredMode) },
                        enabled = controlState.isLoaded && secondary != null
                    ) {
                        Text(stringResource(R.string.cellular_enable))
                    }
                    else -> OutlinedButton(
                        onClick = { viewModel.selectCellularMode(0) },
                        enabled = !controlState.isBusy
                    ) {
                        Text(stringResource(R.string.cellular_disable))
                    }
                }
            }

            Text(
                text = stringResource(R.string.cellular_control_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(onClick = { viewModel.setCellularDetailsExpanded(!controlState.detailsExpanded) }) {
                Text(
                    text = stringResource(
                        if (controlState.detailsExpanded) R.string.cellular_hide_details
                        else R.string.cellular_show_details
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            AnimatedVisibility(visible = controlState.detailsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = stringResource(R.string.cellular_control_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    controlState.subscriptions.forEach {
                        Text(
                            text = context.getString(R.string.cellular_sim_label, it.slot, it.carrier) +
                                if (it.isDefault) context.getString(R.string.cellular_default_suffix) else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (controlState.subscriptions.size != 2) {
                        Text(
                            text = stringResource(R.string.cellular_two_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = stringResource(R.string.cellular_advanced),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.cellular_method_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = controlState.preferredMode == 2,
                            onClick = { viewModel.setCellularPreferredMode(2) },
                            enabled = controlState.mode == 0 && !controlState.isBusy,
                            label = { Text(stringResource(R.string.cellular_request_method)) }
                        )
                        FilterChip(
                            selected = controlState.preferredMode == 1,
                            onClick = { viewModel.setCellularPreferredMode(1) },
                            enabled = controlState.mode == 0 && !controlState.isBusy,
                            label = { Text(stringResource(R.string.cellular_property_method)) }
                        )
                    }
                }
            }
        }
    }
}

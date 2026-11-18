package com.multiroute.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.multiroute.R
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.theme.RoutingDimensions
import com.multiroute.util.NetworkUtils

@Composable
internal fun ChannelSelectBottomSheet(
    app: AppItem,
    channels: List<NetworkChannel>,
    isForbidden: (NetworkChannel) -> Boolean,
    onSelectChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val offline = app.targetChannelId != CHANNEL_DEFAULT && channels.none { it.matchesTarget(app) }
    ChannelPickerSheet(singleChoice = true, onDismiss = onDismiss) {
        item {
            ChannelPickerHeader(app.appName, app.packageName, app)
            Text(
                text = stringResource(R.string.ui_assign_channel),
                modifier = Modifier.padding(
                    horizontal = RoutingDimensions.contentPadding,
                    vertical = RoutingDimensions.rowVerticalPadding
                ).semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item {
            ChannelOption(
                title = stringResource(R.string.ui_default_follow_system),
                subtitle = stringResource(R.string.ui_default_follow_system_desc),
                icon = Icons.Default.Language,
                selected = app.targetChannelId == CHANNEL_DEFAULT,
                onClick = { onSelectChannel(CHANNEL_DEFAULT) }
            )
        }
        items(channels, key = { "channel:${it.id}" }) { channel ->
            val forbidden = isForbidden(channel)
            ChannelOption(
                title = NetworkUtils.channelLabel(LocalContext.current, channel),
                subtitle = if (forbidden) stringResource(R.string.channel_vpn_self_forbidden)
                else channelOptionSubtitle(channel),
                icon = getTransportIcon(channel.transportType),
                selected = channel.matchesTarget(app),
                enabled = !forbidden,
                onClick = { onSelectChannel(channel.id) }
            )
        }
        if (offline) item {
            ChannelOption(
                title = stringResource(
                    R.string.ui_channel_offline,
                    NetworkUtils.savedTargetLabel(LocalContext.current, app.targetChannelId)
                ),
                subtitle = stringResource(R.string.ui_iface_offline_note),
                icon = Icons.Default.CloudOff,
                selected = true,
                enabled = false,
                onClick = {}
            )
        }
    }
}

@Composable
internal fun BatchChannelSelectBottomSheet(
    selectedCount: Int,
    channels: List<NetworkChannel>,
    blockedVpnCounts: Map<String, Int>,
    onSelectChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ChannelPickerSheet(singleChoice = false, onDismiss = onDismiss) {
        item {
            ChannelPickerHeader(
                title = stringResource(R.string.ui_choose_target_channel),
                subtitle = stringResource(R.string.ui_batch_assign_desc, selectedCount)
            )
        }
        item {
            ChannelOption(
                title = stringResource(R.string.ui_default_no_routing),
                subtitle = stringResource(R.string.ui_clear_assigned_desc, selectedCount),
                icon = Icons.Default.Language,
                onClick = { onSelectChannel(CHANNEL_DEFAULT) }
            )
        }
        items(channels, key = { "channel:${it.id}" }) { channel ->
            val blocked = blockedVpnCounts[channel.id] ?: 0
            val available = selectedCount > blocked
            ChannelOption(
                title = NetworkUtils.channelLabel(LocalContext.current, channel),
                subtitle = channelOptionSubtitle(channel),
                note = when {
                    !available -> stringResource(R.string.routing_batch_vpn_unavailable)
                    blocked > 0 -> stringResource(R.string.routing_batch_vpn_skipped, blocked)
                    else -> null
                },
                icon = getTransportIcon(channel.transportType),
                enabled = available,
                onClick = { onSelectChannel(channel.id) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelPickerSheet(
    singleChoice: Boolean,
    onDismiss: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().then(if (singleChoice) Modifier.selectableGroup() else Modifier),
            contentPadding = PaddingValues(bottom = RoutingDimensions.contentPadding),
            content = content
        )
    }
}

@Composable
private fun ChannelPickerHeader(title: String, subtitle: String, app: AppItem? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = RoutingDimensions.contentPadding,
            vertical = RoutingDimensions.rowVerticalPadding
        ),
        horizontalArrangement = Arrangement.spacedBy(RoutingDimensions.rowContentGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app != null) AppIcon(drawable = app.icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RoutingDimensions.textGap)
        ) {
            Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChannelOption(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean? = null,
    enabled: Boolean = true,
    note: String? = null,
    onClick: () -> Unit
) {
    val action = if (selected != null) {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    if (selected == true) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            ChannelOptionContent(title, subtitle, icon, selected, enabled, note, action)
        }
    } else {
        ChannelOptionContent(title, subtitle, icon, selected, enabled, note, action)
    }
}

@Composable
private fun ChannelOptionContent(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean?,
    enabled: Boolean,
    note: String?,
    action: Modifier
) {
    val supportingColor = if (selected == true) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().then(action).heightIn(min = RoutingDimensions.touchTarget).padding(
            horizontal = RoutingDimensions.contentPadding,
            vertical = RoutingDimensions.rowVerticalPadding
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RoutingDimensions.rowContentGap)
    ) {
        Icon(icon, contentDescription = null, tint = supportingColor)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RoutingDimensions.textGap)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = supportingColor)
            if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = supportingColor)
        }
        if (selected != null) {
            RadioButton(selected = selected, enabled = enabled, onClick = null)
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = supportingColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = RoutingDimensions.contentPadding),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

private fun NetworkChannel.matchesTarget(app: AppItem) = id == app.targetChannelId || interfaceName == app.targetChannelId

@Composable
private fun channelOptionSubtitle(channel: NetworkChannel): String {
    val ssid = channel.ssid?.let { " [$it]" }.orEmpty()
    val ip = channel.ipAddress ?: stringResource(R.string.ui_no_ip)
    val default = if (channel.isDefault) stringResource(R.string.channel_current_default) else ""
    return stringResource(R.string.channel_subtitle, channel.interfaceName, ssid, ip) + default
}

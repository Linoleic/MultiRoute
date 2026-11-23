package com.multiroute.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.multiroute.R
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.theme.RoutingDimensions
import com.multiroute.util.NetworkUtils

private data class ChannelPickerEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val note: String? = null,
    @param:DrawableRes val icon: Int,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ChannelSelectBottomSheet(
    app: AppItem,
    channels: List<NetworkChannel>,
    isForbidden: (NetworkChannel) -> Boolean,
    mayBypassTransparentProxy: (NetworkChannel) -> Boolean = { false },
    gatewayChannels: List<NetworkChannel> = emptyList(),
    gatewayMappings: Map<String, String> = emptyMap(),
    gatewayTarget: String? = null,
    gatewayBusy: Boolean = false,
    gatewayEnabled: Boolean = true,
    compatibleVpn: NetworkChannel? = null,
    onSelectGateway: (String?, String?, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onPreviewGateway: () -> Unit,
    onSelectChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pendingGatewayKey by rememberSaveable { mutableStateOf<String?>(null) }
    var outboundTag by rememberSaveable { mutableStateOf("") }
    val offline = app.targetChannelId != CHANNEL_DEFAULT && channels.none { it.matchesTarget(app) }
    val entries = buildList {
        add(
            ChannelPickerEntry(
                key = "default",
                title = stringResource(R.string.ui_default_follow_system),
                subtitle = stringResource(R.string.ui_default_follow_system_desc),
                icon = R.drawable.ic_symbol_language,
                selected = app.targetChannelId == CHANNEL_DEFAULT,
                onClick = { onSelectChannel(CHANNEL_DEFAULT) }
            )
        )
        channels.forEach { channel ->
            val forbidden = isForbidden(channel)
            add(
                ChannelPickerEntry(
                    key = "channel:${channel.id}",
                    title = NetworkUtils.channelLabel(context, channel),
                    subtitle = when {
                        forbidden && channel.vpnOwnerUid == app.uid -> stringResource(R.string.channel_vpn_self_forbidden)
                        forbidden -> stringResource(R.string.ui_vpn_loop_blocked)
                        else -> channelOptionSubtitle(channel)
                    },
                    note = if (!forbidden && mayBypassTransparentProxy(channel)) {
                        stringResource(R.string.transparent_proxy_channel_bypass)
                    } else null,
                    icon = getTransportIcon(channel.transportType),
                    selected = channel.matchesTarget(app),
                    enabled = !forbidden,
                    onClick = { onSelectChannel(channel.id) }
                )
            )
        }
        if (offline) {
            add(
                ChannelPickerEntry(
                    key = "offline",
                    title = stringResource(
                        R.string.ui_channel_offline,
                        NetworkUtils.savedTargetLabel(context, app.targetChannelId)
                    ),
                    subtitle = stringResource(R.string.ui_iface_offline_note),
                    icon = R.drawable.ic_symbol_cloud_off,
                    selected = true,
                    enabled = false,
                    onClick = {}
                )
            )
        }
    }
    val gatewayEntries = buildList {
        gatewayChannels.forEach { channel ->
            val key = checkNotNull(channel.proxyUpstreamKey())
            add(ChannelPickerEntry(
                key = "gateway:$key",
                title = NetworkUtils.channelLabel(context, channel),
                subtitle = gatewayMappings[key] ?: stringResource(R.string.proxy_gateway_app_unmapped),
                icon = R.drawable.ic_symbol_dns,
                selected = gatewayTarget == key,
                enabled = !gatewayBusy && gatewayEnabled,
                onClick = {
                    val tag = gatewayMappings[key]
                    if (tag.isNullOrBlank()) {
                        pendingGatewayKey = key
                        outboundTag = ""
                    } else onSelectGateway(key, null) {}
                }
            ))
        }
        if (gatewayTarget != null) {
            add(ChannelPickerEntry(
                key = "gateway:remove",
                title = stringResource(R.string.proxy_gateway_app_remove),
                subtitle = stringResource(R.string.proxy_gateway_app_remove_desc),
                icon = R.drawable.ic_symbol_cloud_off,
                enabled = !gatewayBusy,
                onClick = { onSelectGateway(null, null) {} }
            ))
        }
    }

    ChannelPickerSheet(singleChoice = true, onDismiss = onDismiss) {
        item(key = "header") {
            ChannelPickerHeader(app.appName, app.packageName, app)
            if (app.uid > 0 && channels.any { it.isVpn && it.vpnOwnerUid == app.uid }) {
                Text(
                    text = stringResource(R.string.ui_vpn_client_picker_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.ui_assign_channel),
                modifier = Modifier.padding(
                    top = RoutingDimensions.rowVerticalPadding,
                    bottom = RoutingDimensions.textGap
                ).semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            ChannelOption(entry = entry, index = index, count = entries.size)
        }
        if (gatewayEnabled || gatewayTarget != null) {
            item(key = "gateway-header") {
                Text(
                    text = stringResource(R.string.proxy_gateway_app_title),
                    modifier = Modifier.padding(top = 16.dp).semantics { heading() },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(when {
                        app.targetChannelId == CHANNEL_DEFAULT -> R.string.proxy_gateway_app_hint
                        compatibleVpn?.matchesTarget(app) == true -> R.string.proxy_gateway_app_vpn_hint
                        else -> R.string.proxy_gateway_app_physical_draft_hint
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (gatewayTarget != null) {
                    val current = channels.firstOrNull { it.proxyUpstreamKey() == gatewayTarget }
                    val label = current?.let { NetworkUtils.channelLabel(context, it) }
                        ?: NetworkUtils.savedTargetLabel(context, gatewayTarget)
                    Text(
                        text = stringResource(R.string.proxy_gateway_app_saved, label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val issue = when {
                        current == null -> R.string.proxy_gateway_skip_offline
                        gatewayMappings[gatewayTarget].isNullOrBlank() -> R.string.proxy_gateway_skip_mapping
                        else -> null
                    }
                    if (issue != null) {
                        Text(stringResource(issue), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onPreviewGateway, enabled = !gatewayBusy) {
                        Text(stringResource(R.string.proxy_gateway_open_tool))
                    }
                }
                if (gatewayEnabled && gatewayChannels.isEmpty()) {
                    Text(
                        stringResource(R.string.proxy_gateway_app_no_channels),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(gatewayEntries, key = { _, entry -> entry.key }) { index, entry ->
                ChannelOption(entry = entry, index = index, count = gatewayEntries.size)
            }
        }
    }

    val pendingChannel = gatewayChannels.firstOrNull { it.proxyUpstreamKey() == pendingGatewayKey }
    if (pendingGatewayKey != null) {
        AlertDialog(
            onDismissRequest = { if (!gatewayBusy) pendingGatewayKey = null },
            title = { Text(stringResource(R.string.proxy_gateway_app_outbound_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.proxy_gateway_app_outbound_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    pendingChannel?.let { Text(NetworkUtils.channelLabel(context, it), style = MaterialTheme.typography.labelMedium) }
                    OutlinedTextField(
                        value = outboundTag,
                        onValueChange = { outboundTag = it },
                        label = { Text(stringResource(R.string.proxy_gateway_outbound_tag)) },
                        singleLine = true,
                        enabled = !gatewayBusy && pendingChannel != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !gatewayBusy && pendingChannel != null && outboundTag.isNotBlank(),
                    onClick = {
                        onSelectGateway(pendingGatewayKey, outboundTag) { saved ->
                            if (saved) pendingGatewayKey = null
                        }
                    }
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(enabled = !gatewayBusy, onClick = { pendingGatewayKey = null }) {
                    Text(stringResource(R.string.ui_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VpnUpstreamPicker(
    upstream: VpnUpstream,
    channels: List<NetworkChannel>,
    onSelectChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val entries = buildList {
        add(ChannelPickerEntry(
            key = CHANNEL_DEFAULT,
            title = stringResource(R.string.vm_system_default),
            subtitle = stringResource(R.string.ui_vpn_upstream_follow_system),
            icon = R.drawable.ic_symbol_language,
            selected = upstream.baseTarget == CHANNEL_DEFAULT,
            onClick = { onSelectChannel(CHANNEL_DEFAULT) }
        ))
        channels.forEach { channel ->
            add(ChannelPickerEntry(
                key = "channel:${channel.id}",
                title = NetworkUtils.channelLabel(context, channel),
                subtitle = channelOptionSubtitle(channel),
                icon = getTransportIcon(channel.transportType),
                selected = channel.id == upstream.baseTarget || channel.interfaceName == upstream.baseTarget,
                onClick = { onSelectChannel(channel.id) }
            ))
        }
    }
    ChannelPickerSheet(singleChoice = true, onDismiss = onDismiss) {
        item(key = "header") {
            ChannelPickerHeader(
                title = stringResource(R.string.ui_vpn_upstream_picker),
                subtitle = upstream.ownerName ?: stringResource(R.string.ui_vpn_client)
            )
            if (upstream.scenarioName != null) {
                Text(
                    text = stringResource(R.string.ui_vpn_upstream_edit_base),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            ChannelOption(entry = entry, index = index, count = entries.size)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProxyUpstreamPicker(
    state: ProxyUpstreamUiState,
    channels: List<NetworkChannel>,
    onSelectChannel: (String) -> Unit,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit
) {
    val profile = state.profile ?: return
    val context = LocalContext.current
    val online = channels.mapNotNull { channel -> channel.proxyUpstreamKey()?.let { it to channel } }.toMap()
    val selected = state.selector?.current
    val entries = profile.mappings.map { (key, member) ->
        val channel = online[key]
        val title = channel?.let { NetworkUtils.channelLabel(context, it) } ?: NetworkUtils.savedTargetLabel(context, key)
        ChannelPickerEntry(
            key = key,
            title = title,
            subtitle = member,
            note = when {
                channel == null -> stringResource(R.string.proxy_upstream_inactive_channel)
                state.selector != null && member !in state.selector.members -> stringResource(R.string.proxy_upstream_missing_member)
                else -> null
            },
            icon = channel?.let { getTransportIcon(it.transportType) } ?: R.drawable.ic_symbol_language,
            selected = member == selected,
            enabled = !state.busy && channel != null && member in state.selector?.members.orEmpty(),
            onClick = { onSelectChannel(key) }
        )
    }
    ChannelPickerSheet(singleChoice = true, onDismiss = onDismiss) {
        item(key = "header") {
            ChannelPickerHeader(
                title = stringResource(R.string.proxy_upstream_apply),
                subtitle = if (selected == null) profile.group else stringResource(R.string.proxy_upstream_selected, selected)
            )
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ProxyUpstreamMessage(state.failure)
        }
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            ChannelOption(entry = entry, index = index, count = entries.size)
        }
        item(key = "configure") {
            TextButton(onClick = onConfigure, enabled = !state.busy) {
                Text(stringResource(R.string.proxy_upstream_configure))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BatchChannelSelectBottomSheet(
    selectedCount: Int,
    channels: List<NetworkChannel>,
    blockedVpnCounts: Map<String, Int>,
    mayBypassTransparentProxy: (NetworkChannel) -> Boolean = { false },
    onSelectChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val entries = buildList {
        add(
            ChannelPickerEntry(
                key = "default",
                title = stringResource(R.string.ui_default_no_routing),
                subtitle = stringResource(R.string.ui_clear_assigned_desc, selectedCount),
                icon = R.drawable.ic_symbol_language,
                onClick = { onSelectChannel(CHANNEL_DEFAULT) }
            )
        )
        channels.forEach { channel ->
            val blocked = blockedVpnCounts[channel.id] ?: 0
            val available = selectedCount > blocked
            add(
                ChannelPickerEntry(
                    key = "channel:${channel.id}",
                    title = NetworkUtils.channelLabel(context, channel),
                    subtitle = channelOptionSubtitle(channel),
                    note = listOfNotNull(
                        when {
                            !available -> stringResource(R.string.routing_batch_vpn_unavailable)
                            blocked > 0 -> stringResource(R.string.routing_batch_vpn_skipped, blocked)
                            else -> null
                        },
                        if (available && mayBypassTransparentProxy(channel)) {
                            stringResource(R.string.transparent_proxy_channel_bypass)
                        } else null
                    ).joinToString("\n").ifEmpty { null },
                    icon = getTransportIcon(channel.transportType),
                    enabled = available,
                    onClick = { onSelectChannel(channel.id) }
                )
            )
        }
    }

    ChannelPickerSheet(singleChoice = false, onDismiss = onDismiss) {
        item(key = "header") {
            ChannelPickerHeader(
                title = stringResource(R.string.ui_choose_target_channel),
                subtitle = stringResource(R.string.ui_batch_assign_desc, selectedCount)
            )
        }
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            ChannelOption(entry = entry, index = index, count = entries.size)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChannelPickerSheet(
    singleChoice: Boolean,
    onDismiss: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().then(if (singleChoice) Modifier.selectableGroup() else Modifier),
            contentPadding = PaddingValues(
                start = RoutingDimensions.contentPadding,
                end = RoutingDimensions.contentPadding,
                bottom = RoutingDimensions.contentPadding + 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            content = content
        )
    }
}

@Composable
private fun ChannelPickerHeader(title: String, subtitle: String, app: AppItem? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = RoutingDimensions.rowVerticalPadding),
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChannelOption(
    entry: ChannelPickerEntry,
    index: Int,
    count: Int
) {
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val colors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedLeadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTrailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedSupportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledLeadingContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledTrailingContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledSupportingContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
    )
    val supporting: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium)
            if (entry.note != null) {
                Text(
                    entry.note,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (entry.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
    val leading: @Composable () -> Unit = {
        Icon(painterResource(entry.icon), contentDescription = null)
    }
    val trailing: @Composable () -> Unit = {
        if (entry.selected != null) {
            RadioButton(selected = entry.selected, enabled = entry.enabled, onClick = null)
        } else {
            Icon(
                painterResource(R.drawable.ic_symbol_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    if (entry.selected != null) {
        SegmentedListItem(
            selected = entry.selected,
            onClick = entry.onClick,
            shapes = shapes,
            enabled = entry.enabled,
            colors = colors,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            content = { Text(entry.title, style = MaterialTheme.typography.bodyLarge) }
        )
    } else {
        SegmentedListItem(
            onClick = entry.onClick,
            shapes = shapes,
            enabled = entry.enabled,
            colors = colors,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            content = { Text(entry.title, style = MaterialTheme.typography.bodyLarge) }
        )
    }
}

private fun NetworkChannel.matchesTarget(app: AppItem) = id == app.targetChannelId || interfaceName == app.targetChannelId

@Composable
private fun channelOptionSubtitle(channel: NetworkChannel): String {
    val ssid = channel.ssid?.let { " [$it]" }.orEmpty()
    val ip = channel.ipAddress ?: stringResource(R.string.ui_no_ip)
    val default = if (channel.isDefault) stringResource(R.string.channel_current_default) else ""
    return stringResource(R.string.channel_subtitle, channel.interfaceName, ssid, ip) + default
}

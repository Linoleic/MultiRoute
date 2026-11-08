package com.multiroute.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.util.NetworkUtils

/**
 * 底部通道选择抽屉 (ModalBottomSheet)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSelectBottomSheet(
    app: AppItem,
    channels: List<NetworkChannel>,
    onSelectChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppIcon(drawable = app.icon, size = 48.dp)
                Column {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = stringResource(com.multiroute.R.string.ui_assign_channel),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            // 1. 系统默认选项
            SelectableChannelOption(
                title = stringResource(com.multiroute.R.string.ui_default_follow_system),
                subtitle = stringResource(com.multiroute.R.string.ui_default_follow_system_desc),
                icon = Icons.Default.Language,
                isSelected = app.targetChannelId == CHANNEL_DEFAULT,
                onClick = { onSelectChannel(CHANNEL_DEFAULT) }
            )

            // 2. 动态扫描到的活跃通道选项
            for (channel in channels) {
                val wifiBadge = if (channel.ssid != null) " [${channel.ssid}]" else ""
                val isForbidden = channel.isVpn && (
                    (channel.vpnOwnerUid > 0 && channel.vpnOwnerUid == app.uid) ||
                    app.packageName.contains("clash", ignoreCase = true) ||
                    app.packageName.contains("v2ray", ignoreCase = true) ||
                    app.packageName.contains("wireguard", ignoreCase = true) ||
                    app.packageName.contains("singbox", ignoreCase = true) ||
                    app.packageName.contains("nekobox", ignoreCase = true) ||
                    app.packageName.contains("shadowsocks", ignoreCase = true)
                )
                val subtitle = if (isForbidden) {
                    stringResource(com.multiroute.R.string.channel_vpn_self_forbidden)
                } else {
                    stringResource(com.multiroute.R.string.channel_subtitle, channel.interfaceName, wifiBadge, channel.ipAddress ?: stringResource(com.multiroute.R.string.ui_no_ip)) + if (channel.isDefault) stringResource(com.multiroute.R.string.channel_current_default) else ""
                }
                SelectableChannelOption(
                    title = NetworkUtils.channelLabel(LocalContext.current, channel),
                    subtitle = subtitle,
                    icon = getTransportIcon(channel.transportType),
                    isSelected = (app.targetChannelId == channel.id || app.targetChannelId == channel.interfaceName),
                    enabled = !isForbidden,
                    onClick = { if (!isForbidden) onSelectChannel(channel.id) }
                )
            }

            // 3. 如果当前应用绑定的通道目前处于离线状态
            if (app.targetChannelId != CHANNEL_DEFAULT && channels.none { (it.id == app.targetChannelId || it.interfaceName == app.targetChannelId) }) {
                SelectableChannelOption(
                    title = stringResource(com.multiroute.R.string.ui_channel_offline, NetworkUtils.savedTargetLabel(LocalContext.current, app.targetChannelId)),
                    subtitle = stringResource(com.multiroute.R.string.ui_iface_offline_note),
                    icon = Icons.Default.CloudOff,
                    isSelected = true,
                    onClick = {}
                )
            }
        }
    }
}

/**
 * 批量分配通道模态底部抽屉 (ModalBottomSheet)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchChannelSelectBottomSheet(
    selectedCount: Int,
    channels: List<NetworkChannel>,
    onSelectChannel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Column {
                    Text(
                        text = stringResource(com.multiroute.R.string.ui_batch_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = stringResource(com.multiroute.R.string.ui_batch_assign_desc, selectedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = stringResource(com.multiroute.R.string.ui_choose_target_channel),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            // 1. 系统默认选项 (清空/恢复不分流)
            SelectableChannelOption(
                title = stringResource(com.multiroute.R.string.ui_default_no_routing),
                subtitle = stringResource(com.multiroute.R.string.ui_clear_assigned_desc, selectedCount),
                icon = Icons.Default.Language,
                isSelected = false,
                onClick = { onSelectChannel(CHANNEL_DEFAULT) }
            )

            // 2. 动态扫描到的活跃通道选项
            for (channel in channels) {
                val wifiBadge = if (channel.ssid != null) " [${channel.ssid}]" else ""
                SelectableChannelOption(
                    title = NetworkUtils.channelLabel(LocalContext.current, channel),
                    subtitle = stringResource(com.multiroute.R.string.channel_subtitle, channel.interfaceName, wifiBadge, channel.ipAddress ?: stringResource(com.multiroute.R.string.ui_no_ip)) + if (channel.isDefault) stringResource(com.multiroute.R.string.channel_current_default) else "",
                    icon = getTransportIcon(channel.transportType),
                    isSelected = false,
                    onClick = { onSelectChannel(channel.id) }
                )
            }
        }
    }
}

/**
 * 抽屉中单选通道卡片
 */
@Composable
fun SelectableChannelOption(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.35f else 0.15f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (!enabled) MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
                else if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    else if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!enabled) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline
                )
            }

            RadioButton(
                selected = isSelected,
                enabled = enabled,
                onClick = if (enabled) onClick else null
            )
        }
    }
}

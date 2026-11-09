package com.multiroute.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multiroute.model.AppItem
import com.multiroute.model.NetworkChannel
import com.multiroute.util.ChannelTrafficStats
import com.multiroute.util.NetworkUtils
import kotlinx.coroutines.flow.StateFlow

/**
 * 网络通道深度属性与独立出口诊断抽屉 (ChannelDetailBottomSheet)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailBottomSheet(
    channel: NetworkChannel,
    routedApps: List<AppItem>,
    testResult: String?,
    isTestingSingle: Boolean,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>? = null,
    testServerName: String = "",
    testServerUrl: String = "",
    onTestChannel: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头部：通道基本信息与状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getTransportIcon(channel.transportType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = NetworkUtils.channelLabel(androidx.compose.ui.platform.LocalContext.current, channel),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = if (channel.isVpn && channel.underlyingIface != null) {
                            stringResource(com.multiroute.R.string.ui_underlying_iface, channel.interfaceName) + " · " + stringResource(com.multiroute.R.string.channel_vpn_underlying, channel.underlyingIface)
                        } else {
                            stringResource(com.multiroute.R.string.ui_underlying_iface, channel.interfaceName) + if (channel.ssid != null) " (SSID: ${channel.ssid})" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (channel.isVpn) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.channel_badge_vpn),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    if (channel.isDefault) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.vm_system_default),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    } else if (channel.isHotspot) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = stringResource(com.multiroute.R.string.channel_badge_hotspot),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(com.multiroute.R.string.ui_close),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (trafficFlow != null) {
                Text(
                    text = stringResource(com.multiroute.R.string.traffic_section_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                ChannelTrafficDetailCard(
                    interfaceName = channel.interfaceName,
                    trafficFlow = trafficFlow
                )
            }

            // 1. 网络链路与拓扑参数
            Text(
                text = stringResource(com.multiroute.R.string.ui_topology),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_iface_type), value = NetworkUtils.channelLabel(androidx.compose.ui.platform.LocalContext.current, channel))
                    DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_kernel_iface), value = channel.interfaceName)
                    if (channel.isVpn) {
                        if (channel.underlyingIface != null) {
                            DetailPropertyRow(
                                label = stringResource(com.multiroute.R.string.ui_vpn_underlying),
                                value = channel.underlyingIface
                            )
                        }
                        if (channel.vpnOwnerUid > 0) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val ownerAppName = remember(channel.vpnOwnerUid) {
                                runCatching {
                                    val pkg = context.packageManager.getPackagesForUid(channel.vpnOwnerUid)?.firstOrNull()
                                    if (pkg != null) {
                                        context.packageManager.getApplicationLabel(
                                            context.packageManager.getApplicationInfo(pkg, 0)
                                        ).toString() + " ($pkg)"
                                    } else null
                                }.getOrNull()
                            }
                            DetailPropertyRow(
                                label = stringResource(com.multiroute.R.string.ui_vpn_creator_label),
                                value = ownerAppName ?: "UID ${channel.vpnOwnerUid}"
                            )
                        }
                    }
                    if (channel.ssid != null) {
                        DetailPropertyRow(label = "Wi-Fi SSID", value = channel.ssid)
                    }
                    val bandLabel = NetworkUtils.wifiBandLabel(channel.frequencyMhz)
                    if (bandLabel.isNotEmpty()) {
                        DetailPropertyRow(
                            label = stringResource(com.multiroute.R.string.ui_wifi_band),
                            value = stringResource(
                                com.multiroute.R.string.ui_wifi_band_value,
                                bandLabel,
                                NetworkUtils.wifiChannelNumber(channel.frequencyMhz)
                            )
                        )
                    }
                    DetailPropertyRow(
                        label = if (channel.isVpn) stringResource(com.multiroute.R.string.ui_vpn_tunnel_ip_label) else stringResource(com.multiroute.R.string.ui_primary_ip),
                        value = channel.ipAddress ?: stringResource(com.multiroute.R.string.ui_none)
                    )
                    if (channel.allIpAddresses.isNotEmpty()) {
                        DetailPropertyRow(
                            label = stringResource(com.multiroute.R.string.ui_assigned_ips),
                            value = channel.allIpAddresses.joinToString("\n")
                        )
                    }
                    DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_gateway), value = channel.gateway ?: stringResource(com.multiroute.R.string.ui_direct_or_auto))
                    DetailPropertyRow(
                        label = stringResource(com.multiroute.R.string.ui_dns_servers),
                        value = if (channel.dnsServers.isNotEmpty()) channel.dnsServers.joinToString("\n") else stringResource(com.multiroute.R.string.ui_dns_system)
                    )
                    if (!channel.domains.isNullOrEmpty()) {
                        DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_search_domains), value = channel.domains)
                    }
                    DetailPropertyRow(label = stringResource(com.multiroute.R.string.ui_mtu), value = if (channel.mtu > 0) stringResource(com.multiroute.R.string.ui_mtu_value, channel.mtu) else stringResource(com.multiroute.R.string.ui_unknown))
                    DetailPropertyRow(
                        label = stringResource(com.multiroute.R.string.ui_estimated_bandwidth),
                        value = if (channel.downlinkBps > 0 || channel.uplinkBps > 0) {
                            stringResource(com.multiroute.R.string.ui_link_speed, channel.downlinkBps / 1000, channel.uplinkBps / 1000)
                        } else {
                            stringResource(com.multiroute.R.string.ui_adaptive)
                        }
                    )
                    DetailPropertyRow(
                        label = stringResource(com.multiroute.R.string.ui_metered),
                        value = if (channel.isMetered) stringResource(com.multiroute.R.string.ui_metered_yes) else stringResource(com.multiroute.R.string.ui_metered_no)
                    )
                }
            }

            // 2. 当前绑定此通道的应用
            Text(
                text = stringResource(com.multiroute.R.string.ui_routed_apps, routedApps.size),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                if (channel.isHotspot) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(com.multiroute.R.string.channel_hotspot_routing_forbidden),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else if (routedApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(com.multiroute.R.string.ui_no_routed_apps),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        routedApps.forEach { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppIcon(drawable = app.icon, size = 32.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. 通道独立公网出口测试
            Text(
                text = stringResource(com.multiroute.R.string.ui_egress_diag),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            if (channel.isHotspot) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = stringResource(com.multiroute.R.string.channel_hotspot_downlink_note),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (testResult != null) {
                            val isFailed = NetworkUtils.isFailure(testResult)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isFailed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                else Color(0xFFE8F5E9),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isFailed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (isFailed) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = NetworkUtils.egressResultLabel(androidx.compose.ui.platform.LocalContext.current, testResult),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isFailed) MaterialTheme.colorScheme.error else Color(0xFF1B5E20)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_not_tested),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        if (testServerName.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    text = stringResource(com.multiroute.R.string.ui_test_node_short, testServerName),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Button(
                            onClick = onTestChannel,
                            enabled = !isTestingSingle,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isTestingSingle) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(com.multiroute.R.string.ui_probing_channel, channel.interfaceName))
                            } else {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(com.multiroute.R.string.ui_test_channel_egress, channel.interfaceName))
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(com.multiroute.R.string.ui_close))
            }
        }
    }
}

@Composable
fun DetailPropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.widthIn(min = 100.dp, max = 130.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

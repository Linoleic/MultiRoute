package com.multiroute.ui.wifi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiroute.R
import com.multiroute.wifi.DualWifiStatus

/**
 * 个人热点出口与分流管理卡片 (Hotspot Upstream Steering Card)
 *
 * 作为与“双 Wi-Fi 激活与管理”并列的一级卡片，支持：
 * 1. 实时热点激活状态、工作射频接口与频段展示
 * 2. 毫秒级动态上行出口切换（系统默认 / 移动蜂窝 / 副 Wi-Fi）
 * 3. 实时生效上游出口指示与 eBPF 硬件加速标识
 * 4. 热点未开启时的预设上行配置与快捷跳转系统热点设置
 */
@Composable
fun HotspotUpstreamCard(
    status: DualWifiStatus,
    onOpenHotspotSettings: () -> Unit,
    onSetUpstream: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题与热点状态行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (status.isHotspotEnabled) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = if (status.isHotspotEnabled) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_hotspot_card_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (status.isHotspotEnabled) {
                            stringResource(R.string.settings_hotspot_card_desc, status.hotspotIface ?: "wlan2")
                        } else {
                            stringResource(R.string.settings_hotspot_card_desc_inactive)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (status.isHotspotEnabled) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (status.isHotspotEnabled) {
                                status.hotspotBand
                                    ?: if (status.hotspotFreq > 0) "${if (status.hotspotFreq > 4900) "5G" else "2.4G"} 已开启"
                                    else stringResource(R.string.hotspot_status_active)
                            } else {
                                stringResource(R.string.hotspot_status_inactive)
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            softWrap = false,
                            color = if (status.isHotspotEnabled) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenHotspotSettings,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.hotspot_open_settings),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 实时生效出口与硬件加速提示（热点开启时展示）
            if (status.isHotspotEnabled && status.hotspotUpstreamActive != "none") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = stringResource(
                                    R.string.dual_wifi_hotspot_upstream_active,
                                    when {
                                        status.hotspotUpstreamActive.startsWith("rmnet") -> "蜂窝 (${status.hotspotUpstreamActive})"
                                        status.hotspotUpstreamActive == "wlan1" -> "副 Wi-Fi (wlan1)"
                                        status.hotspotUpstreamActive == "wlan0" -> "主 Wi-Fi (wlan0)"
                                        else -> status.hotspotUpstreamActive
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    if (status.isHotspotHookLoaded) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = stringResource(R.string.dual_wifi_hotspot_upstream_bpf),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // 上行出口单选区域
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.dual_wifi_hotspot_upstream_title),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val currentTarget = status.hotspotUpstreamTarget.lowercase()

                    FilterChip(
                        selected = currentTarget.isEmpty() || currentTarget == "default",
                        onClick = { onSetUpstream("default") },
                        label = {
                            Text(
                                stringResource(R.string.dual_wifi_hotspot_upstream_default),
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = if (currentTarget.isEmpty() || currentTarget == "default") {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        } else null
                    )

                    val isCellularSelected = currentTarget == "cellular" || currentTarget == "mobile" || currentTarget.startsWith("rmnet")
                    FilterChip(
                        selected = isCellularSelected,
                        onClick = { onSetUpstream("cellular") },
                        label = {
                            Text(
                                stringResource(R.string.dual_wifi_hotspot_upstream_cellular),
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = if (isCellularSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        } else null
                    )

                    val isSecSelected = currentTarget == "wlan1" || currentTarget == "slave_wifi"
                    val canSelectSec = status.secondaryConnected
                    FilterChip(
                        selected = isSecSelected,
                        enabled = canSelectSec,
                        onClick = { onSetUpstream("wlan1") },
                        label = {
                            Text(
                                if (canSelectSec) stringResource(R.string.dual_wifi_hotspot_upstream_secondary)
                                else stringResource(R.string.dual_wifi_hotspot_upstream_sec_disconnected),
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = if (isSecSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        } else null
                    )
                }
            }

            // 热点未开启时的快捷操作行
            if (!status.isHotspotEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onOpenHotspotSettings,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiTethering,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.hotspot_enable_btn),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

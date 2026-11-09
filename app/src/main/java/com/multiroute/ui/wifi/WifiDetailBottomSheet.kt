package com.multiroute.ui.wifi

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multiroute.R
import com.multiroute.wifi.WifiScanItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDetailBottomSheet(
    item: WifiScanItem,
    primarySsid: String?,
    primaryFreq: Int,
    isSecondaryConnected: Boolean = false,
    isConnecting: Boolean,
    isHotspotEnabled: Boolean = false,
    hotspotBand: String? = null,
    @androidx.annotation.StringRes hotspotBandRes: Int? = null,
    hotspotFreq: Int = 0,
    onDismiss: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onOpenSystemWifi: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (item.isPrimary || item.isSecondary) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    XiaomiWifiSignalIcon(
                        level = item.level,
                        tint = if (item.isPrimary || item.isSecondary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        badgeText = when {
                            item.isSecondary -> "2"
                            item.isPrimary && isSecondaryConnected -> "1"
                            else -> null
                        },
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = item.ssid,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.isSecure) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Text(
                        text = when {
                            item.isPrimary -> stringResource(R.string.wifi_i18n_primary_channel_default)
                            item.isSecondary -> stringResource(R.string.wifi_i18n_secondary_channel_active)
                            item.isSaved -> stringResource(R.string.wifi_i18n_saved_system_network)
                            else -> stringResource(R.string.wifi_i18n_nearby_hotspot)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isPrimary || item.isSecondary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Details List
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailInfoRow(
                    icon = Icons.Default.Fingerprint,
                    label = stringResource(R.string.dual_wifi_detail_bssid),
                    value = item.bssid
                )

                DetailInfoRow(
                    icon = Icons.Default.WifiTethering,
                    label = stringResource(R.string.dual_wifi_detail_freq),
                    value = "${scanBandLabel(item.frequencyMhz)} • ${item.frequencyMhz} MHz • ${stringResource(R.string.dual_wifi_param_channel)} ${if (item.channel > 0) item.channel.toString() else stringResource(R.string.wifi_i18n_channel_unknown)}"
                )

                DetailInfoRow(
                    icon = Icons.Default.Speed,
                    label = stringResource(R.string.dual_wifi_detail_rssi),
                    value = "${item.rssi} dBm · ${signalQualityLabel(item.level)}"
                )

                DetailInfoRow(
                    icon = Icons.Default.Security,
                    label = stringResource(R.string.dual_wifi_detail_security),
                    value = item.capabilities.replace("[", "").replace("]", " ").trim()
                )

                DetailInfoRow(
                    icon = Icons.Default.Key,
                    label = stringResource(R.string.dual_wifi_detail_saved),
                    value = stringResource(if (item.isSaved) R.string.dual_wifi_saved_configuration else R.string.dual_wifi_unsaved_configuration)
                )

                if (isHotspotEnabled) {
                    DetailInfoRow(
                        icon = Icons.Default.WifiTethering,
                        label = stringResource(R.string.dual_wifi_hotspot_active_title),
                        value = stringResource(
                            R.string.dual_wifi_hotspot_conflict_desc,
                            hotspotBandLabel(hotspotFreq, hotspotBand, hotspotBandRes) ?: stringResource(R.string.wifi_i18n_unknown_band)
                        ),
                        isWarning = true
                    )
                }

                val isSameBandConflict = remember(item.frequencyMhz, primaryFreq) {
                    if (primaryFreq <= 0 || item.isPrimary) false
                    else {
                        val primLow5G = primaryFreq in 5150..5350
                        val targetLow5G = item.frequencyMhz in 5150..5350
                        val primHigh5G = primaryFreq in 5470..5850
                        val targetHigh5G = item.frequencyMhz in 5470..5850
                        val prim24 = primaryFreq in 2400..2500
                        val target24 = item.frequencyMhz in 2400..2500
                        val prim6G = primaryFreq in 5925..7125
                        val target6G = item.frequencyMhz in 5925..7125
                        (primLow5G && targetLow5G) || (primHigh5G && targetHigh5G) || (prim24 && target24) || (prim6G && target6G)
                    }
                }

                DetailInfoRow(
                    icon = Icons.Default.Hub,
                    label = stringResource(R.string.dual_wifi_detail_concurrency),
                    value = when {
                        item.isPrimary -> stringResource(R.string.wifi_i18n_primary_network_default_egress)
                        item.isSecondary -> stringResource(R.string.wifi_i18n_secondary_link_active)
                        isHotspotEnabled -> stringResource(R.string.wifi_i18n_hotspot_radio_conflict)
                        isSameBandConflict -> stringResource(R.string.wifi_i18n_same_band_conflict, primarySsid ?: stringResource(R.string.wifi_i18n_primary_network_fallback), primaryFreq)
                        primaryFreq > 0 -> stringResource(R.string.wifi_i18n_complementary_bands, primarySsid ?: stringResource(R.string.wifi_i18n_primary_network_fallback), primaryFreq)
                        else -> stringResource(R.string.wifi_i18n_unconnected_cannot_assess)
                    },
                    isHighlight = !item.isPrimary && !item.isSecondary && !isSameBandConflict && primaryFreq > 0 && !isHotspotEnabled,
                    isWarning = isSameBandConflict || isHotspotEnabled
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action Button
            when {
                item.isSecondary -> {
                    Button(
                        onClick = {
                            onDisconnectClick()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dual_wifi_disconnect))
                    }
                }
                item.isPrimary -> {
                    OutlinedButton(
                        onClick = {
                            onOpenSystemWifi()
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dual_wifi_open_system_wifi))
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            onConnectClick()
                            onDismiss()
                        },
                        enabled = !isConnecting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isHotspotEnabled) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else ButtonDefaults.buttonColors()
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dual_wifi_connecting, item.ssid))
                        } else {
                            Icon(Icons.Default.WifiFind, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isHotspotEnabled) stringResource(R.string.wifi_i18n_hotspot_running_connect_later)
                                else if (item.isSaved) stringResource(R.string.wifi_i18n_connect_one_tap_secondary)
                                else stringResource(R.string.wifi_i18n_connect_as_secondary)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    isHighlight: Boolean = false,
    isWarning: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
            tint = when {
                isWarning -> MaterialTheme.colorScheme.error
                isHighlight -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isHighlight || isWarning) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = when {
                    isWarning -> MaterialTheme.colorScheme.error
                    isHighlight -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

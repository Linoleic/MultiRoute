package com.multiroute.ui.wifi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.multiroute.R
import com.multiroute.wifi.DualWifiCapability
import com.multiroute.wifi.WifiScanItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualWifiScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DualWifiViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val status = uiState.status
    val capability = status.capability

    var passwordInput by remember { mutableStateOf("") }
    var selectedDetailItem by remember { mutableStateOf<WifiScanItem?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.dual_wifi_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = stringResource(R.string.dual_wifi_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ui_cancel)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.checkCapability()
                        },
                        enabled = !status.isScanning
                    ) {
                        if (status.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.dual_wifi_recheck)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 1. 双 Wi-Fi 适配方案选择器 (智能分层适配)
            item {
                SchemeSelectorCard(
                    capability = capability,
                    selectedScheme = uiState.selectedScheme,
                    onSelectScheme = { viewModel.onSelectScheme(it) }
                )
            }

            // 2. 软硬件支持状态与参数依据诊断 (合并卡片)
            item {
                HardwareCapabilityCard(
                    capability = capability,
                    isActivating = uiState.isActivating,
                    onActivateClick = { viewModel.activateDualWifi() },
                    onClearBlocklist = { viewModel.clearBlocklist() }
                )
            }

            // 3. 个人热点频段占用感知与冲突避让提示卡片
            if (status.isHotspotEnabled) {
                item {
                    HotspotUpstreamCard(
                        status = status,
                        onOpenHotspotSettings = { viewModel.openHotspotSettings() },
                        onSetUpstream = { viewModel.setHotspotUpstream(it) }
                    )
                }
            }

            // 4. 当前双 Wi-Fi 通道状态卡片
            item {
                ActiveChannelsCard(
                    status = status,
                    isAutoReconnect = uiState.isAutoReconnect,
                    onToggleAutoReconnect = { viewModel.setAutoReconnect(it) },
                    onOpenSystemWifi = { viewModel.openSystemWifiSettings() },
                    onDisconnectSecondary = { viewModel.disconnectSecondary() }
                )
            }

            // 激活反馈卡片
            uiState.activationResult?.let { msg ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.clearActivationResult() }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // 提示信息卡片 (如清理限制)
            uiState.infoMessage?.let { msg ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.clearInfoMessage() }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // 错误提示卡片
            status.errorMessage?.let { errorMsg ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = errorMsg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 3. 附近可用 Wi-Fi 列表标题与扫描按钮
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.dual_wifi_scan_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    OutlinedButton(
                        onClick = { viewModel.scanNetworks() },
                        enabled = !status.isScanning,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (status.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dual_wifi_scanning), fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dual_wifi_scan_btn), fontSize = 13.sp)
                        }
                    }
                }
            }

            // 4. Wi-Fi 扫描结果列表
            if (status.scanResults.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (status.isScanning) stringResource(R.string.dual_wifi_scanning)
                                else stringResource(R.string.dual_wifi_no_scan_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(status.scanResults, key = { "${it.ssid}_${it.bssid}_${it.frequencyMhz}" }) { item ->
                    WifiScanRow(
                        item = item,
                        primaryFreq = status.primaryFreq,
                        isSecondaryConnected = status.secondaryConnected,
                        isConnecting = status.isConnecting && (status.connectingBssid == item.bssid || (status.connectingBssid == null && status.connectingSsid == item.ssid)),
                        isHotspotEnabled = status.isHotspotEnabled,
                        hotspotFreq = status.hotspotFreq,
                        onCardClick = { selectedDetailItem = item },
                        onConnectClick = { viewModel.onSelectScanItem(item) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // 密码输入对话框
    uiState.pendingPasswordItem?.let { item ->
        val conflictNotice = remember(item.frequencyMhz, status.primaryFreq) {
            if (status.primaryFreq <= 0) null
            else {
                val primLow5G = status.primaryFreq in 5150..5350
                val targetLow5G = item.frequencyMhz in 5150..5350
                val primHigh5G = status.primaryFreq in 5470..5850
                val targetHigh5G = item.frequencyMhz in 5470..5850
                val prim24 = status.primaryFreq in 2400..2500
                val target24 = item.frequencyMhz in 2400..2500
                if ((primLow5G && targetLow5G) || (primHigh5G && targetHigh5G) || (prim24 && target24)) {
                    "提示：此热点与主 Wi-Fi 处于相同硬件频段（${if (prim24) "均为 2.4GHz" else "均为 5GHz 同子频段/信道"}）。手机 DBS 射频通常要求一主一副分别工作在 2.4G 与 5G（或高低 5G 分离），同时连接同频可能失败。"
                } else null
            }
        }

        AlertDialog(
            onDismissRequest = {
                passwordInput = ""
                viewModel.dismissPasswordDialog()
            },
            title = {
                Text(
                    text = "${stringResource(R.string.dual_wifi_dialog_title)}: ${item.ssid}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${item.bandLabel} • RSSI: ${item.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (conflictNotice != null) {
                        Text(
                            text = conflictNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text(stringResource(R.string.dual_wifi_dialog_password_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pass = passwordInput
                        passwordInput = ""
                        viewModel.connectSecondary(item, pass)
                    },
                    enabled = passwordInput.isNotEmpty()
                ) {
                    Text(stringResource(R.string.dual_wifi_dialog_connect))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        passwordInput = ""
                        viewModel.dismissPasswordDialog()
                    }
                ) {
                    Text(stringResource(R.string.dual_wifi_dialog_cancel))
                }
            }
        )
    }

    // 详细信息查看 BottomSheet
    selectedDetailItem?.let { item ->
        WifiDetailBottomSheet(
            item = item,
            primarySsid = status.primarySsid,
            primaryFreq = status.primaryFreq,
            isSecondaryConnected = status.secondaryConnected,
            isConnecting = status.isConnecting && (status.connectingBssid == item.bssid || (status.connectingBssid == null && status.connectingSsid == item.ssid)),
            isHotspotEnabled = status.isHotspotEnabled,
            hotspotBand = status.hotspotBand,
            hotspotFreq = status.hotspotFreq,
            onDismiss = { selectedDetailItem = null },
            onConnectClick = { viewModel.onSelectScanItem(item) },
            onDisconnectClick = { viewModel.disconnectSecondary() },
            onOpenSystemWifi = { viewModel.openSystemWifiSettings() }
        )
    }
}

@Composable
private fun HardwareCapabilityCard(
    capability: DualWifiCapability,
    isActivating: Boolean,
    onActivateClick: () -> Unit,
    onClearBlocklist: () -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (capability.isFullyReady) Icons.Default.CheckCircle else Icons.Default.Tune,
                        contentDescription = null,
                        tint = if (capability.isFullyReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "双 Wi-Fi 软硬件支持",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (capability.isFullyReady)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else if (capability.hardwareSupported)
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (capability.isFullyReady) "完全就绪"
                        else if (capability.hardwareSupported) "需要激活"
                        else "硬件不支持",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (capability.isFullyReady) MaterialTheme.colorScheme.primary
                        else if (capability.hardwareSupported) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // 1. 硬件并发能力 (STA + STA 并发)
            DiagnosticItemRow(
                icon = Icons.Default.Memory,
                title = "硬件并发能力 (STA + STA 并发)",
                statusText = if (capability.hardwareSupported) "硬件支持" else "硬件不支持",
                isOk = capability.hardwareSupported,
                details = buildList {
                    add("射频芯片: ${capability.wifiChipset}${if (capability.romInfo.socModel.isNotEmpty()) " [SoC: ${capability.romInfo.socModel}]" else ""}")
                    add("网卡接口: 主 ${capability.primaryIface ?: "wlan0"} • 副 ${capability.secondaryIface ?: "wlan1"}")
                    add(if (capability.hardwareSupported) "物理架构: 支持双物理射频独立并发 (DBS)" else "物理架构: 仅单网卡或不支持双 STA")
                }
            )

            // 2. 系统定制环境与方案
            DiagnosticItemRow(
                icon = Icons.Default.PhoneAndroid,
                title = "系统定制环境与方案",
                statusText = "已匹配: ${capability.effectiveScheme.label}",
                isOk = true,
                details = buildList {
                    add("系统环境: ${capability.romInfo.romType.label} • ${capability.romInfo.model} (Android ${capability.romInfo.androidVersion})")
                    add(when (capability.effectiveScheme) {
                        com.multiroute.wifi.DualWifiScheme.XIAOMI_SLAVE -> "适配机制: 小米双 WLAN / SlaveWifi 驱动协同"
                        com.multiroute.wifi.DualWifiScheme.AOSP_OVERLAY -> "适配机制: AOSP 原生 Multi-Internet 与 RRO Overlay"
                        com.multiroute.wifi.DualWifiScheme.ROOT_DIRECT -> "适配机制: Linux 底层特权通道直连"
                        com.multiroute.wifi.DualWifiScheme.AUTO -> "适配机制: 智能自适应推荐"
                    })
                }
            )

            // 3. 系统多网络配置 Overlay
            val isXiaomi = capability.effectiveScheme == com.multiroute.wifi.DualWifiScheme.XIAOMI_SLAVE
            val isRootDirect = capability.effectiveScheme == com.multiroute.wifi.DualWifiScheme.ROOT_DIRECT
            DiagnosticItemRow(
                icon = Icons.Default.Layers,
                title = "系统多网络配置 Overlay",
                statusText = when {
                    isXiaomi -> "原厂内建"
                    isRootDirect -> "无需 Overlay (Root 直通)"
                    capability.configEnabled -> "已开启"
                    else -> "未开启"
                },
                isOk = isXiaomi || isRootDirect || capability.configEnabled,
                details = buildList {
                    if (isRootDirect) {
                        add("直连通道: 借助 SYSTEM_UID 特权绕过 Framework Overlay 限制")
                        add("当前状态: 特权直通就绪 (支持通用 Root 并发)")
                    } else {
                        add("配置开关: MultiStaConcurrency = ${capability.configEnabled}")
                        add("当前状态: ${capability.overlayStatus}")
                    }
                }
            )

            // 4. 并发运行工作模式 (Multi-AP 并发)
            DiagnosticItemRow(
                icon = Icons.Default.Hub,
                title = "并发运行工作模式 (Multi-AP 并发)",
                statusText = if (capability.modeActive) "模式已激活" else "未激活",
                isOk = capability.modeActive,
                details = buildList {
                    add("运行模式: mStaConcurrencyMultiInternetMode")
                    add(if (capability.modeActive) "当前状态: 模式 2 运行中 (双 Wi-Fi 独立互联)" else "当前状态: 单互联网模式 (未处于 Multi-AP 并发)")
                }
            )

            // 5. 副 Wi-Fi 物理层链路
            val secLinked = capability.secondaryDetails.isNotEmpty() && !capability.secondaryDetails.contains("待机中")
            DiagnosticItemRow(
                icon = Icons.Default.WifiTethering,
                title = "副 Wi-Fi (${capability.secondaryIface ?: "wlan1"}) 物理链路",
                statusText = if (secLinked) "物理链路活跃" else "通道就绪 (待连接)",
                isOk = secLinked,
                details = buildList {
                    add(if (secLinked) "链路参数: ${capability.secondaryDetails}" else "链路参数: 接口空闲，可随时发起连接")
                }
            )

            // 一键激活按钮 (若需要激活且硬件支持)
            if (!capability.isFullyReady && capability.hardwareSupported) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onActivateClick,
                    enabled = !isActivating,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isActivating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dual_wifi_activating))
                    } else {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.dual_wifi_activate_btn))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // 清理网络临时限制按钮
            OutlinedButton(
                onClick = onClearBlocklist,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CleaningServices,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.dual_wifi_clear_blocklist),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = stringResource(R.string.dual_wifi_clear_blocklist_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticItemRow(
    icon: ImageVector,
    title: String,
    statusText: String,
    isOk: Boolean,
    details: List<String>
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isOk)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isOk) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (isOk) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = if (isOk) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 26.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                details.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Xiaomi / HyperOS style Wi-Fi signal icon.
 * Features 4 concentric curved radio wave arcs with rounded caps and a base dot.
 * Unlit arcs are subtly rendered in semi-transparent silhouette (MIUI/HyperOS style).
 * Supports dual-Wi-Fi badge ("1" or "2") at the bottom-right corner.
 */
@Composable
fun XiaomiWifiSignalIcon(
    level: Int,
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalContentColor.current,
    badgeText: String? = null
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerX = w * 0.5f
            val centerY = h * 0.84f
            val strokeWidth = (w * 0.098f).coerceAtLeast(1.5f)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val inactiveAlpha = 0.22f
            val sweepAngle = 82f
            val startAngle = 270f - (sweepAngle / 2f)

            // Level 1: Center dot at vertex
            val dotRadius = w * 0.08f
            drawCircle(
                color = if (level >= 1) tint else tint.copy(alpha = inactiveAlpha),
                radius = dotRadius,
                center = Offset(centerX, centerY)
            )

            // Level 2: Inner Arc
            val r1 = w * 0.25f
            drawArc(
                color = if (level >= 2) tint else tint.copy(alpha = inactiveAlpha),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(centerX - r1, centerY - r1),
                size = Size(r1 * 2, r1 * 2),
                style = stroke
            )

            // Level 3: Middle Arc
            val r2 = w * 0.45f
            drawArc(
                color = if (level >= 3) tint else tint.copy(alpha = inactiveAlpha),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(centerX - r2, centerY - r2),
                size = Size(r2 * 2, r2 * 2),
                style = stroke
            )

            // Level 4: Outer Arc
            val r3 = w * 0.65f
            drawArc(
                color = if (level >= 4) tint else tint.copy(alpha = inactiveAlpha),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(centerX - r3, centerY - r3),
                size = Size(r3 * 2, r3 * 2),
                style = stroke
            )
        }

        // Xiaomi HyperOS style badge for Dual Wi-Fi (e.g. "2" for secondary, "1" for primary)
        if (!badgeText.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(11.dp)
                    .background(tint, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeText,
                    color = MaterialTheme.colorScheme.surface,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 8.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ActiveChannelsCard(
    status: com.multiroute.wifi.DualWifiStatus,
    isAutoReconnect: Boolean,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onOpenSystemWifi: () -> Unit,
    onDisconnectSecondary: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.dual_wifi_status_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            // 主 Wi-Fi
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val primLevel = when {
                            status.primaryRssi >= -55 -> 4
                            status.primaryRssi >= -65 -> 3
                            status.primaryRssi >= -75 -> 2
                            status.primaryRssi >= -85 -> 1
                            status.primarySsid != null -> 3
                            else -> 0
                        }
                        XiaomiWifiSignalIcon(
                            level = primLevel,
                            tint = MaterialTheme.colorScheme.primary,
                            badgeText = if (status.secondaryConnected) "1" else null,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dual_wifi_primary_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = status.primarySsid ?: stringResource(R.string.dual_wifi_not_connected),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        if (status.primaryIp != null) {
                            Text(
                                text = "${status.primaryIface ?: "wlan0"} • ${status.primaryIp} • ${if (status.primaryFreq > 0) "${status.primaryFreq} MHz" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenSystemWifi,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dual_wifi_open_system_wifi),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // 副 Wi-Fi
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (status.secondaryConnected)
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (status.secondaryConnected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (status.secondaryConnected) {
                            val secLevel = when {
                                status.secondaryRssi >= -55 -> 4
                                status.secondaryRssi >= -65 -> 3
                                status.secondaryRssi >= -75 -> 2
                                status.secondaryRssi >= -85 -> 1
                                else -> 3
                            }
                            XiaomiWifiSignalIcon(
                                level = secLevel,
                                tint = MaterialTheme.colorScheme.secondary,
                                badgeText = "2",
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dual_wifi_secondary_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (status.secondaryConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = (if (status.secondaryConnected) status.secondarySsid else null)
                                ?: stringResource(R.string.dual_wifi_not_connected),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        if (status.secondaryConnected && status.secondaryIp != null) {
                            Text(
                                text = "${status.secondaryIface ?: "wlan1"} • ${status.secondaryIp} • ${if (status.secondaryFreq > 0) "${status.secondaryFreq} MHz" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (status.secondaryConnected) {
                        OutlinedButton(
                            onClick = onDisconnectSecondary,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.dual_wifi_disconnect),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // 自动重连开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.dual_wifi_auto_reconnect),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = stringResource(R.string.dual_wifi_auto_reconnect_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isAutoReconnect,
                    onCheckedChange = onToggleAutoReconnect
                )
            }
        }
    }
}

@Composable
private fun WifiScanRow(
    item: WifiScanItem,
    primaryFreq: Int = 0,
    isSecondaryConnected: Boolean = false,
    isConnecting: Boolean,
    isHotspotEnabled: Boolean = false,
    hotspotFreq: Int = 0,
    onCardClick: () -> Unit,
    onConnectClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                item.isSecondary -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                item.isPrimary -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Xiaomi style Wi-Fi signal icon
            XiaomiWifiSignalIcon(
                level = item.level,
                tint = when {
                    item.isSecondary -> MaterialTheme.colorScheme.secondary
                    item.isPrimary -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                badgeText = when {
                    item.isSecondary -> "2"
                    item.isPrimary && isSecondaryConnected -> "1"
                    else -> null
                },
                modifier = Modifier.size(22.dp)
            )

            // SSID and Meta
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.ssid,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.isSecure) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Band Tag
                    SuggestionChip(
                        onClick = onCardClick,
                        label = { Text(item.bandLabel, fontSize = 10.sp) },
                        modifier = Modifier.height(22.dp)
                    )

                    // Conflict & Recommendation tags
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

                    if (isHotspotEnabled && !item.isPrimary && !item.isSecondary) {
                        SuggestionChip(
                            onClick = onCardClick,
                            label = {
                                Text(
                                    stringResource(R.string.dual_wifi_hotspot_conflict_badge),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.height(22.dp)
                        )
                    } else if (isSameBandConflict) {
                        SuggestionChip(
                            onClick = onCardClick,
                            label = { Text(stringResource(R.string.dual_wifi_band_conflict), fontSize = 10.sp, color = MaterialTheme.colorScheme.error) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.height(22.dp)
                        )
                    } else if (primaryFreq > 0 && !item.isPrimary && !item.isSecondary) {
                        SuggestionChip(
                            onClick = onCardClick,
                            label = { Text(stringResource(R.string.dual_wifi_band_recommended), fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.height(22.dp)
                        )
                    }

                    // Saved Tag
                    if (item.isSaved) {
                        SuggestionChip(
                            onClick = onCardClick,
                            label = { Text(stringResource(R.string.dual_wifi_saved_badge), fontSize = 10.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.height(22.dp)
                        )
                    }
                }
            }

            // Action Button
            when {
                item.isPrimary -> {
                    Badge(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                        Text(
                            text = "主 Wi-Fi",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                item.isSecondary -> {
                    Badge(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)) {
                        Text(
                            text = "副 Wi-Fi",
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                isConnecting -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                else -> {
                    Button(
                        onClick = onConnectClick,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = if (isHotspotEnabled) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else ButtonDefaults.buttonColors()
                    ) {
                        Text(
                            text = if (isHotspotEnabled) stringResource(R.string.dual_wifi_hotspot_conflict_badge)
                            else if (item.isSaved) stringResource(R.string.dual_wifi_quick_connect)
                            else stringResource(R.string.dual_wifi_connect_btn),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemeSelectorCard(
    capability: DualWifiCapability,
    selectedScheme: com.multiroute.wifi.DualWifiScheme,
    onSelectScheme: (com.multiroute.wifi.DualWifiScheme) -> Unit
) {
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.dual_wifi_scheme_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = when (capability.effectiveScheme) {
                            com.multiroute.wifi.DualWifiScheme.XIAOMI_SLAVE -> "已匹配: 小米原厂"
                            com.multiroute.wifi.DualWifiScheme.AOSP_OVERLAY -> "已匹配: AOSP 标准"
                            com.multiroute.wifi.DualWifiScheme.ROOT_DIRECT -> "已匹配: 通用 Root"
                            com.multiroute.wifi.DualWifiScheme.AUTO -> "自动"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.dual_wifi_scheme_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            val schemes = listOf(
                Triple(
                    com.multiroute.wifi.DualWifiScheme.AUTO,
                    stringResource(R.string.dual_wifi_scheme_auto_title),
                    stringResource(R.string.dual_wifi_scheme_auto_desc)
                ),
                Triple(
                    com.multiroute.wifi.DualWifiScheme.AOSP_OVERLAY,
                    stringResource(R.string.dual_wifi_scheme_aosp_title),
                    stringResource(R.string.dual_wifi_scheme_aosp_desc)
                ),
                Triple(
                    com.multiroute.wifi.DualWifiScheme.XIAOMI_SLAVE,
                    stringResource(R.string.dual_wifi_scheme_xiaomi_title),
                    stringResource(R.string.dual_wifi_scheme_xiaomi_desc)
                ),
                Triple(
                    com.multiroute.wifi.DualWifiScheme.ROOT_DIRECT,
                    stringResource(R.string.dual_wifi_scheme_root_title),
                    stringResource(R.string.dual_wifi_scheme_root_desc)
                )
            )

            schemes.forEach { (scheme, title, desc) ->
                val isSelected = selectedScheme == scheme
                Surface(
                    onClick = { onSelectScheme(scheme) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    border = if (isSelected)
                        androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectScheme(scheme) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiDetailBottomSheet(
    item: WifiScanItem,
    primarySsid: String?,
    primaryFreq: Int,
    isSecondaryConnected: Boolean = false,
    isConnecting: Boolean,
    isHotspotEnabled: Boolean = false,
    hotspotBand: String? = null,
    hotspotFreq: Int = 0,
    onDismiss: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onOpenSystemWifi: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                            item.isPrimary -> "主 Wi-Fi 通道 (系统默认)"
                            item.isSecondary -> "副 Wi-Fi 通道 (并发活跃)"
                            item.isSaved -> "已保存系统网络"
                            else -> "附近可用热点"
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
                    value = "${item.bandLabel} • ${item.frequencyMhz} MHz • 信道 ${if (item.channel > 0) item.channel.toString() else "未知"}"
                )

                DetailInfoRow(
                    icon = Icons.Default.Speed,
                    label = stringResource(R.string.dual_wifi_detail_rssi),
                    value = "${item.rssi} dBm · ${item.signalQualityText}"
                )

                DetailInfoRow(
                    icon = Icons.Default.Security,
                    label = stringResource(R.string.dual_wifi_detail_security),
                    value = item.capabilities.replace("[", "").replace("]", " ").trim()
                )

                DetailInfoRow(
                    icon = Icons.Default.Key,
                    label = stringResource(R.string.dual_wifi_detail_saved),
                    value = if (item.isSaved) "已保存网络凭据 (可一键直连)" else "未保存凭据 (需输入密码)"
                )

                if (isHotspotEnabled) {
                    DetailInfoRow(
                        icon = Icons.Default.WifiTethering,
                        label = stringResource(R.string.dual_wifi_hotspot_active_title),
                        value = stringResource(
                            R.string.dual_wifi_hotspot_conflict_desc,
                            hotspotBand ?: "未知频段"
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
                        item.isPrimary -> "当前网络为主 Wi-Fi (wlan0)，作为系统默认互联网出口"
                        item.isSecondary -> "当前已作为副 Wi-Fi (wlan1) 建立并发物理链路"
                        isHotspotEnabled -> "⚠️ 个人热点正在占用物理射频链，硬件射频无法同时建立副 Wi-Fi 链路"
                        isSameBandConflict -> "⚠️ 与主 Wi-Fi (${primarySsid ?: "主网络"}, ${primaryFreq}MHz) 处于相同物理射频频段，DBS 芯片不支持同时并发连接"
                        primaryFreq > 0 -> "✅ 射频互补：与主 Wi-Fi (${primarySsid ?: "主网络"}, ${primaryFreq}MHz) 物理频段独立，完全支持双 Wi-Fi 硬件并发"
                        else -> "未连接主 Wi-Fi，无法评估频段并发互补性"
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
                                if (isHotspotEnabled) "热点运行中 (关闭热点后可连)"
                                else if (item.isSaved) "一键连接为副 Wi-Fi"
                                else "连接为副 Wi-Fi"
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





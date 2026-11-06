package com.multiroute.ui.main

import androidx.compose.animation.AnimatedVisibility
import com.multiroute.theme.AppMotion
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiroute.data.RouteConfigProvider
import com.multiroute.util.ModuleStatus
import com.multiroute.util.SuHelper

@Composable
internal fun ModuleDiagnosticsPanel(uiState: MainUiState, viewModel: MainScreenViewModel) {
    val diag = uiState.diagnosticInfo
    var expanded by rememberSaveable { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(4.dp))
    // 模块与路由核心诊断卡片
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(com.multiroute.R.string.ui_module_channel_state),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 1. LSPosed 模块状态（精确：区分未激活 / hook 未就绪 / 旧版本待重启 / 记录过期）
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(com.multiroute.R.string.ui_module_state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                val moduleStatus = diag?.moduleStatus
                val indicator = moduleStateIndicator(moduleStatus)
                val statusTint = moduleIndicatorColor(indicator)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModuleIndicatorIcon(indicator, Modifier.size(16.dp))
                    Text(
                        text = if (moduleStatus != null) {
                            SuHelper.moduleStatusLabel(androidx.compose.ui.platform.LocalContext.current, moduleStatus)
                        } else {
                            stringResource(com.multiroute.R.string.ui_checking_module)
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = statusTint
                        )
                    )
                }
                val statusDetail = diag?.moduleStatusDetail
                if (!statusDetail.isNullOrEmpty()) {
                    Text(
                        text = statusDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 2. Root 运行环境检测
            DiagnosticItem(
                label = stringResource(com.multiroute.R.string.settings_root_env),
                value = if (diag?.isRootGranted == true) stringResource(com.multiroute.R.string.diag_root_ready, diag?.suVersion?.ifEmpty { "KernelSU" } ?: "KernelSU") else stringResource(com.multiroute.R.string.diag_root_missing)
            )

            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) com.multiroute.R.string.module_hide_details else com.multiroute.R.string.module_show_details))
            }
            AnimatedVisibility(expanded, enter = AppMotion.expand(), exit = AppMotion.collapse()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 3. 内核策略路由生效规则统计
            DiagnosticItem(
                label = stringResource(com.multiroute.R.string.settings_kernel_rules),
                value = if ((diag?.kernelRulesCount ?: 0) > 0) stringResource(com.multiroute.R.string.diag_kernel_rules_on, diag?.kernelRulesCount ?: 0) else stringResource(com.multiroute.R.string.diag_kernel_rules_none)
            )

            // 4. 系统蜂窝首选 UID
            DiagnosticItem(
                label = stringResource(com.multiroute.R.string.settings_cellular_uids_2),
                value = diag?.mobileDataPreferredUids?.ifEmpty { stringResource(com.multiroute.R.string.diag_not_set) } ?: stringResource(com.multiroute.R.string.diag_not_set)
            )
                }
            }
        }
    }
}

@Composable
internal fun BackupSettingsPanel(uiState: MainUiState, viewModel: MainScreenViewModel, showTitle: Boolean = true) {
    ConfigBackupCard(uiState = uiState, viewModel = viewModel, showTitle = showTitle)
}

@Composable
internal fun AppearanceSettingsPanel(uiState: MainUiState, viewModel: MainScreenViewModel) {
    // 外观与语言：主题（跟随系统/浅色/深色）+ 动态取色 + 语言
    val ctx = LocalContext.current
    val host = ctx as? android.app.Activity
    val themeMode = com.multiroute.theme.ThemeSettings.mode
    val language = RouteConfigProvider.getLanguage(ctx)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {


            PreferenceChoiceRow(
                title = stringResource(com.multiroute.R.string.settings_theme),
                choices = listOf("system" to stringResource(com.multiroute.R.string.settings_follow_system),
                    "light" to stringResource(com.multiroute.R.string.settings_theme_light),
                    "dark" to stringResource(com.multiroute.R.string.settings_theme_dark)), selected = themeMode,
                onSelect = { mode ->
                    RouteConfigProvider.setThemeMode(ctx, mode)
                    com.multiroute.theme.ThemeSettings.mode = mode
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(com.multiroute.R.string.settings_dynamic_color),
                    style = MaterialTheme.typography.bodySmall
                )
                Switch(
                    checked = com.multiroute.theme.ThemeSettings.dynamicColor,
                    onCheckedChange = {
                        RouteConfigProvider.setDynamicColor(ctx, it)
                        com.multiroute.theme.ThemeSettings.dynamicColor = it
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            PreferenceChoiceRow(
                title = stringResource(com.multiroute.R.string.settings_language),
                choices = listOf("" to stringResource(com.multiroute.R.string.settings_follow_system),
                    "zh-CN" to stringResource(com.multiroute.R.string.settings_language_zh),
                    "en" to stringResource(com.multiroute.R.string.settings_language_en)), selected = language,
                onSelect = { tag ->
                    if (language != tag) {
                        RouteConfigProvider.setLanguage(ctx, tag)
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            runCatching {
                                ctx.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales =
                                    if (tag.isEmpty()) android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(tag)
                            }
                        }
                        host?.recreate()
                    }
                }
            )

        }
    }
}

@Composable
internal fun NetworkSettingsPanel(uiState: MainUiState, viewModel: MainScreenViewModel) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSectionLabel(com.multiroute.R.string.settings_connection_keepalive)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_keep_cellular),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (uiState.supportsCellularData) 1f else 0.38f)
                    )
                    Text(
                        text = stringResource(if (uiState.supportsCellularData) com.multiroute.R.string.settings_keep_cellular_desc
                            else com.multiroute.R.string.settings_cellular_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.supportsCellularData) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
                Switch(
                    checked = uiState.isMobileDataAlwaysOn,
                    enabled = uiState.supportsCellularData,
                    onCheckedChange = { viewModel.toggleMobileDataAlwaysOn() }
                )
            }



            HorizontalDivider()
            SettingsSectionLabel(com.multiroute.R.string.settings_routing_behavior)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_dns_redirect),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_dns_redirect_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = uiState.isDnsFollowsChannel,
                    onCheckedChange = { viewModel.toggleDnsFollowsChannel() }
                )
            }


            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_auto_failover),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_auto_failover_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = uiState.isAutoFailoverEnabled,
                    onCheckedChange = { viewModel.toggleAutoFailover() }
                )
            }

            AnimatedVisibility(visible = uiState.isAutoFailoverEnabled || uiState.scenarioProfiles.isNotEmpty(),
                enter = AppMotion.expand(), exit = AppMotion.collapse()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider()
                SettingsSectionLabel(com.multiroute.R.string.settings_detection_parameters)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_failover_holdoff, uiState.failoverHoldOffSeconds),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = stringResource(com.multiroute.R.string.settings_failover_holdoff_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(1, 2, 3, 5, 8).forEach { secs ->
                            FilterChip(
                                selected = uiState.failoverHoldOffSeconds == secs,
                                onClick = { viewModel.updateFailoverHoldOffSeconds(secs) },
                                label = { Text("${secs}s") }
                            )
                        }
                    }
                }

                if (uiState.isAutoFailoverEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }

            }
            AnimatedVisibility(visible = uiState.isAutoFailoverEnabled,
                enter = AppMotion.expand(), exit = AppMotion.collapse()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(com.multiroute.R.string.settings_failover_probe_internet),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = stringResource(com.multiroute.R.string.settings_failover_probe_internet_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = uiState.isFailoverProbeInternet,
                        onCheckedChange = { viewModel.toggleFailoverProbeInternet() }
                    )
                }
            }
        }
    }
}

@Composable
internal fun TestNodeSettingsPanel(uiState: MainUiState, viewModel: MainScreenViewModel) {
    // 公网出口测试设置
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(com.multiroute.R.string.settings_egress),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.testServerConfig.displayName(androidx.compose.ui.platform.LocalContext.current),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = uiState.testServerConfig.activeUrl,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                FilledTonalButton(
                    onClick = { viewModel.openTestServerDialog() },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(com.multiroute.R.string.filter_configured))
                }
            }

            Text(
                text = stringResource(com.multiroute.R.string.ui_test_server_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
internal fun RuleActionsMenu(uiState: MainUiState, viewModel: MainScreenViewModel) {
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(com.multiroute.R.string.rules_actions))
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (uiState.isSyncing) com.multiroute.R.string.ui_syncing_kernel else com.multiroute.R.string.ui_force_resync)) },
                leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                enabled = !uiState.isSyncing,
                onClick = { showMenu = false; viewModel.forceSyncAllRules() }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(com.multiroute.R.string.ui_clear_all_rules), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                enabled = !uiState.isSyncing,
                onClick = { showMenu = false; showClearDialog = true }
            )
        }
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(com.multiroute.R.string.ui_confirm_clear_title)) },
            text = { Text(stringResource(com.multiroute.R.string.ui_clear_rules_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllRules()
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(com.multiroute.R.string.ui_confirm_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(com.multiroute.R.string.ui_cancel))
                }
            }
        )
    }
}

@Composable
internal fun AboutSettingsPanel(uiState: MainUiState, viewModel: MainScreenViewModel, showTitle: Boolean = true) {
    // 关于卡片
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (showTitle) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(com.multiroute.R.string.ui_about),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                val context = LocalContext.current
                val packageInfo = remember(context) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                val versionName = packageInfo?.versionName ?: "1.0.0"
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo?.longVersionCode ?: 1L
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo?.versionCode?.toLong() ?: 1L
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = "v$versionName ($versionCode)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = stringResource(com.multiroute.R.string.about_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                lineHeight = 18.sp
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(com.multiroute.R.string.ui_system_support),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "Android 11+",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(com.multiroute.R.string.ui_open_source_license),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "GNU GPL-3.0",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            val context = LocalContext.current
            Surface(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Linoleic/MultiRoute"))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(com.multiroute.R.string.ui_github_repo),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "https://github.com/Linoleic/MultiRoute",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = stringResource(com.multiroute.R.string.cd_open_link),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

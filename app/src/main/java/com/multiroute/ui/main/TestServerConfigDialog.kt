package com.multiroute.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.multiroute.model.TestServerConfig

/**
 * 公网出口测试服务器选择与自定义配置对话框
 */
@Composable
fun TestServerConfigDialog(
    currentConfig: TestServerConfig,
    onSave: (presetId: String, customUrl: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedId by remember { mutableStateOf(currentConfig.selectedPresetId) }
    var customUrlInput by remember { mutableStateOf(currentConfig.customUrl.ifEmpty { "http://" }) }
    var isUrlError by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 对话框标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(com.multiroute.R.string.ui_test_server),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Text(
                    text = stringResource(com.multiroute.R.string.ui_egress_diag_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 节点选项列表 (限制最大高度并支持内部滚动)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TestServerConfig.PRESETS.forEach { preset ->
                        val isSelected = selectedId == preset.id
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedId = preset.id },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedId = preset.id }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(preset.nameRes),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = preset.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = stringResource(preset.descriptionRes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }

                    // 自定义节点选项
                    val isCustomSelected = selectedId == TestServerConfig.PRESET_CUSTOM
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedId = TestServerConfig.PRESET_CUSTOM },
                        color = if (isCustomSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = isCustomSelected,
                                    onClick = { selectedId = TestServerConfig.PRESET_CUSTOM }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(com.multiroute.R.string.ui_custom_test_node),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isCustomSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(com.multiroute.R.string.ui_custom_test_node_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            if (isCustomSelected) {
                                OutlinedTextField(
                                    value = customUrlInput,
                                    onValueChange = {
                                        customUrlInput = it
                                        isUrlError = false
                                    },
                                    label = { Text(stringResource(com.multiroute.R.string.ui_target_url)) },
                                    placeholder = { Text(stringResource(com.multiroute.R.string.ui_custom_url_hint)) },
                                    isError = isUrlError,
                                    supportingText = {
                                        if (isUrlError) {
                                            Text(stringResource(com.multiroute.R.string.ui_url_error))
                                        } else {
                                            Text(stringResource(com.multiroute.R.string.ui_target_url_desc))
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 对话框底部操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            selectedId = TestServerConfig.DEFAULT_PRESET_ID
                            customUrlInput = "http://"
                            isUrlError = false
                        }
                    ) {
                        Text(stringResource(com.multiroute.R.string.ui_reset_default))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(com.multiroute.R.string.ui_cancel))
                        }
                        Button(
                            onClick = {
                                if (selectedId == TestServerConfig.PRESET_CUSTOM) {
                                    val trimmed = customUrlInput.trim()
                                    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                                        isUrlError = true
                                        return@Button
                                    }
                                    onSave(selectedId, trimmed)
                                } else {
                                    onSave(selectedId, null)
                                }
                            }
                        ) {
                            Text(stringResource(com.multiroute.R.string.ui_save_apply))
                        }
                    }
                }
            }
        }
    }
}

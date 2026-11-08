package com.multiroute.ui.main

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 运行与诊断日志查看对话框
 */
@Composable
fun DiagnosticLogsDialog(
    logs: String,
    isLoading: Boolean,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(logs.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                android.widget.Toast.makeText(
                    context,
                    context.getString(com.multiroute.R.string.ui_log_exported, uri.lastPathSegment ?: ""),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }.onFailure { e ->
                android.widget.Toast.makeText(
                    context,
                    context.getString(com.multiroute.R.string.ui_log_export_failed, e.message ?: ""),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val onShare: () -> Unit = {
        if (logs.isNotBlank()) {
            runCatching {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                val cacheFile = java.io.File(context.cacheDir, "MultiRoute-diag-$timestamp.txt")
                cacheFile.writeText(logs, Charsets.UTF_8)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(com.multiroute.R.string.ui_share_chooser_title))
                    putExtra(Intent.EXTRA_TEXT, logs.take(2000))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(
                    shareIntent,
                    context.getString(com.multiroute.R.string.ui_share_chooser_title)
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }.onFailure { e ->
                android.widget.Toast.makeText(
                    context,
                    context.getString(com.multiroute.R.string.ui_log_export_failed, e.message ?: ""),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            imageVector = Icons.Default.Article,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(com.multiroute.R.string.ui_runtime_log),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1E1E1E)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        val verticalScroll = rememberScrollState()
                        val horizontalScroll = rememberScrollState()

                        Text(
                            text = logs,
                            color = Color(0xFFD4D4D4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .verticalScroll(verticalScroll)
                                .horizontalScroll(horizontalScroll)
                        )
                    }
                }

                // 操作按钮栏：关闭在左，导出/分享/复制在右
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(com.multiroute.R.string.ui_close))
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                                exportLauncher.launch("MultiRoute-diag-$timestamp.txt")
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(com.multiroute.R.string.ui_export_log))
                        }
                        OutlinedButton(
                            onClick = onShare,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(com.multiroute.R.string.ui_share_log))
                        }
                        Button(
                            onClick = onCopy,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(com.multiroute.R.string.ui_copy_log))
                        }
                    }
                }
            }
        }
    }
}

package com.multiroute.ui.main

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multiroute.R
import com.multiroute.data.ProxyUpstreamProfile
import com.multiroute.data.resolveGatewayFormat
import com.multiroute.data.RuleTarget
import com.multiroute.model.NetworkChannel
import com.multiroute.util.ClashProxyUpstreamClient
import com.multiroute.util.ClashProxyUpstreamClient.ControllerException
import com.multiroute.theme.AppMotion
import com.multiroute.util.ClashProxyUpstreamClient.Failure
import com.multiroute.util.ClashProxyUpstreamClient.Selector
import com.multiroute.util.NetworkUtils
import com.multiroute.util.ProxyGatewayExport
import com.multiroute.util.ProxyGatewayFormat
import com.multiroute.util.RouteRuleBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProxyGatewayNoticeBanner(
    notice: ProxyGatewayAvailabilityNotice,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    ) {
        Text(
            text = stringResource(when (notice) {
                ProxyGatewayAvailabilityNotice.UNSUPPORTED_ANDROID_VPN -> R.string.proxy_gateway_notice_unsupported_vpn
                ProxyGatewayAvailabilityNotice.NO_PROXY_DETECTED -> R.string.proxy_gateway_notice_no_proxy
            }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpandableProxySectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    collapsedSummary: String? = null
) {
    val containerColor by animateColorAsState(
        targetValue = if (expanded) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "proxyHeaderContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (expanded) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "proxyHeaderContent"
    )
    val titleColor by animateColorAsState(
        targetValue = if (expanded) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "proxyHeaderTitle"
    )
    val badgeColor by animateColorAsState(
        targetValue = if (expanded) {
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "proxyHeaderBadge"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "proxyHeaderChevron"
    )

    Surface(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_symbol_dns),
                contentDescription = null,
                tint = titleColor
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor
                )
                if (collapsedSummary != null) {
                    AnimatedVisibility(
                        visible = !expanded,
                        enter = AppMotion.expand(),
                        exit = AppMotion.collapse()
                    ) {
                        Text(
                            text = collapsedSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = badgeColor,
                contentColor = contentColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_symbol_expand_more),
                        contentDescription = stringResource(
                            if (expanded) R.string.proxy_gateway_collapse else R.string.proxy_gateway_expand
                        ),
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProxyUpstreamToolPanel(
    channels: List<NetworkChannel>,
    controller: ProxyUpstreamController,
    compatibleVpn: NetworkChannel? = null,
    hasTransparentProxyEvidence: Boolean = false
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val gatewayNotice = resolveProxyGatewayAvailabilityNotice(
        channels = channels,
        compatibleVpn = compatibleVpn,
        hasTransparentProxyEvidence = hasTransparentProxyEvidence,
        hasSavedMappings = state.gatewayMappings.isNotEmpty()
    )
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var showLiveSwitching by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.proxy_gateway_title), style = MaterialTheme.typography.titleMedium)
        ProxyGatewayExportSection(
            channels = channels,
            state = state,
            controller = controller,
            enabled = !state.gatewayBusy,
            compatibleVpn = compatibleVpn,
            gatewayNotice = gatewayNotice
        )
        HorizontalDivider()
        ExpandableProxySectionHeader(
            title = stringResource(R.string.proxy_upstream_live_title),
            expanded = showLiveSwitching,
            onToggle = { showLiveSwitching = !showLiveSwitching }
        )
        AnimatedVisibility(
            visible = showLiveSwitching,
            enter = AppMotion.expand(),
            exit = AppMotion.collapse()
        ) {
            val profile = state.profile
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (profile == null) stringResource(R.string.proxy_upstream_setup)
                    else stringResource(R.string.proxy_upstream_configured, profile.group, profile.mappings.size),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = { showSetup = true }, enabled = !state.busy) {
                    Text(stringResource(R.string.proxy_upstream_configure))
                }
            }
        }
    }
    if (showSetup) ProxyUpstreamDialog(channels, controller) { showSetup = false }
}

@Composable
private fun ProxyGatewayMappingDialog(
    channels: List<NetworkChannel>,
    state: ProxyUpstreamUiState,
    controller: ProxyUpstreamController,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val active = channels.mapNotNull { channel -> channel.proxyUpstreamKey()?.let { it to channel } }.toMap()
    val liveMappings = state.profile?.mappings.orEmpty()
    var draft by rememberSaveable { mutableStateOf(LinkedHashMap(state.gatewayMappings)) }
    var failed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!state.gatewayBusy) onDismiss() },
        title = { Text(stringResource(R.string.proxy_gateway_configure)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.proxy_gateway_mapping_help))
                if (liveMappings.isNotEmpty()) {
                    TextButton(
                        enabled = !state.gatewayBusy && liveMappings.any { (key, tag) -> draft[key] != tag },
                        onClick = { draft = LinkedHashMap(draft + liveMappings) }
                    ) { Text(stringResource(R.string.proxy_gateway_copy_mappings)) }
                }
                val keys = active.keys + draft.keys
                if (keys.isEmpty()) Text(stringResource(R.string.proxy_gateway_no_channels))
                keys.forEach { key ->
                    val label = active[key]?.let { NetworkUtils.channelLabel(context, it) }
                        ?: NetworkUtils.savedTargetLabel(context, key)
                    OutlinedTextField(
                        value = draft[key].orEmpty(),
                        onValueChange = { draft = LinkedHashMap(draft + (key to it)) },
                        label = { Text(label) },
                        placeholder = { Text(stringResource(R.string.proxy_gateway_outbound_tag)) },
                        singleLine = true,
                        enabled = !state.gatewayBusy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (failed) Text(stringResource(R.string.proxy_upstream_storage_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.gatewayBusy && (active.isNotEmpty() || draft.isNotEmpty()),
                onClick = {
                    failed = false
                    val mappings = draft.filterValues { it.isNotBlank() }
                    controller.saveGatewayMappings(mappings) { saved ->
                        failed = !saved
                        if (saved) onSaved()
                    }
                }
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.gatewayBusy) { Text(stringResource(R.string.ui_cancel)) }
        }
    )
}

private data class GatewayPreview(
    val export: ProxyUpstreamController.GatewayExport,
    val format: ProxyGatewayFormat,
    val focusedApp: RuleTarget?
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProxyGatewayExportSection(
    channels: List<NetworkChannel>,
    state: ProxyUpstreamUiState,
    controller: ProxyUpstreamController,
    enabled: Boolean,
    compatibleVpn: NetworkChannel?,
    gatewayNotice: ProxyGatewayAvailabilityNotice?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val supportsAndroidVpn = compatibleVpn != null
    val formats = ProxyGatewayFormat.entries.filter { it != ProxyGatewayFormat.MIHOMO_ANDROID_VPN || supportsAndroidVpn }
    val format = resolveGatewayFormat(state.preferredGatewayFormat, supportsAndroidVpn)
    val androidVpnLabel = stringResource(R.string.proxy_gateway_android_vpn_format)
    var pendingContent by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<GatewayPreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showMappingSetup by rememberSaveable { mutableStateOf(false) }
    var repreviewFormat by rememberSaveable { mutableStateOf<ProxyGatewayFormat?>(null) }
    var repreviewAppKey by rememberSaveable { mutableStateOf<String?>(null) }
    var repreviewAppUid by rememberSaveable { mutableStateOf(-1) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
        val content = pendingContent
        pendingContent = null
        if (uri != null && content != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val stream = checkNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                    }
                    message = context.getString(R.string.proxy_gateway_export_saved)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    message = context.getString(R.string.proxy_gateway_export_failed)
                } finally {
                    busy = false
                }
            }
        }
    }

    fun launchFilePicker(content: String, exportFormat: ProxyGatewayFormat) {
        preview = null
        pendingContent = content
        try {
            filePicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(exportFormat.mimeType)
                .putExtra(Intent.EXTRA_TITLE, exportFormat.fileName))
        } catch (_: IllegalStateException) {
            pendingContent = null
            message = context.getString(R.string.proxy_gateway_export_failed)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingContent = null
            message = context.getString(R.string.proxy_gateway_export_failed)
        }
    }

    fun beginPreview(exportFormat: ProxyGatewayFormat, focusedApp: RuleTarget? = null) {
        busy = true
        preview = null
        message = null
        scope.launch {
            try {
                preview = GatewayPreview(controller.prepareGatewayExport(exportFormat), exportFormat, focusedApp)
            } catch (error: CancellationException) {
                throw error
            } catch (_: ProxyUpstreamController.AndroidVpnUnavailable) {
                message = context.getString(R.string.proxy_gateway_android_vpn_unavailable)
            } catch (_: ProxyGatewayExport.InvalidMihomoOutbound) {
                message = context.getString(R.string.proxy_gateway_invalid_mihomo_tag)
            } catch (_: Exception) {
                message = context.getString(R.string.proxy_gateway_export_failed)
            } finally {
                busy = false
            }
        }
    }

    fun openMappingSetup(repreview: GatewayPreview? = null) {
        repreviewFormat = repreview?.format
        repreviewAppKey = repreview?.focusedApp?.key
        repreviewAppUid = repreview?.focusedApp?.uid ?: -1
        showMappingSetup = true
    }

    if (gatewayNotice != null) {
        ProxyGatewayNoticeBanner(notice = gatewayNotice)
    }
    Text(
        stringResource(R.string.proxy_gateway_status_summary, state.gatewayMappings.size, state.gatewayAppTargets.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        stringResource(when (format) {
            ProxyGatewayFormat.MIHOMO_ANDROID_VPN -> R.string.proxy_gateway_android_vpn_help
            ProxyGatewayFormat.MIHOMO -> R.string.proxy_gateway_export_mihomo_help
            ProxyGatewayFormat.SING_BOX -> R.string.proxy_gateway_export_help
        }),
        style = MaterialTheme.typography.bodyMedium
    )
    if (format != ProxyGatewayFormat.MIHOMO_ANDROID_VPN) {
        Text(
            stringResource(R.string.proxy_gateway_compatibility_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    val canExport = enabled && !busy && !showMappingSetup && preview == null && pendingContent == null
    val previewRequest by controller.gatewayPreviewRequest.collectAsStateWithLifecycle()
    LaunchedEffect(previewRequest, canExport, format, supportsAndroidVpn) {
        val request = previewRequest ?: return@LaunchedEffect
        if (canExport && controller.consumeGatewayPreviewRequest(request)) {
            val requestedFormat = request.preferredFormat?.takeIf { it in formats } ?: format
            beginPreview(requestedFormat, request.app)
        }
    }
    ProxyUpstreamMenu(
        selected = stringResource(R.string.proxy_gateway_format,
            if (format == ProxyGatewayFormat.MIHOMO_ANDROID_VPN) androidVpnLabel else format.label),
        options = formats.map { candidate -> candidate.name to
            if (candidate == ProxyGatewayFormat.MIHOMO_ANDROID_VPN) androidVpnLabel else candidate.label
        },
        enabled = canExport,
        onSelect = {
            ProxyGatewayFormat.valueOf(it).takeIf { candidate -> candidate in formats }?.let(controller::saveGatewayFormat)
            message = null
        }
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = canExport, onClick = { beginPreview(format) }) {
            Text(stringResource(if (busy) R.string.proxy_gateway_preview_loading else R.string.proxy_gateway_preview_open))
        }
        OutlinedButton(onClick = { openMappingSetup() }, enabled = canExport) {
            Text(stringResource(R.string.proxy_gateway_configure))
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    preview?.takeUnless { showMappingSetup }?.let { snapshot ->
        ProxyGatewayPreviewDialog(snapshot, channels, onDismiss = { preview = null },
            onSave = { launchFilePicker(it, snapshot.format) },
            onConfigure = { openMappingSetup(snapshot) })
    }
    if (showMappingSetup) {
        ProxyGatewayMappingDialog(
            channels, state, controller,
            onDismiss = {
                showMappingSetup = false
                repreviewFormat = null
                repreviewAppKey = null
            },
            onSaved = {
                showMappingSetup = false
                val pending = repreviewFormat
                val app = repreviewAppKey?.let { RuleTarget(it, repreviewAppUid) }
                repreviewFormat = null
                repreviewAppKey = null
                if (pending != null) beginPreview(pending, app)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProxyGatewayPreviewDialog(
    snapshot: GatewayPreview,
    channels: List<NetworkChannel>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onConfigure: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(snapshot) { mutableStateOf(false) }
    var showRawSource by rememberSaveable(snapshot.format, snapshot.focusedApp) { mutableStateOf(false) }
    val preview = snapshot.export
    val formatLabel = if (snapshot.format == ProxyGatewayFormat.MIHOMO_ANDROID_VPN) {
        stringResource(R.string.proxy_gateway_android_vpn_format)
    } else snapshot.format.label
    val focusedApp = snapshot.focusedApp
    val focusedRow = focusedApp?.let { app ->
        preview.rows.firstOrNull { it.ruleKey == app.key }
            ?: preview.rows.firstOrNull { it.uid == app.uid && it.skipReason == null }
    }
    val appNames by produceState(emptyMap<GatewayPreviewRow, Pair<String, Int>>(), preview.rows, focusedApp) {
        value = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            val labels = mutableMapOf<String, String>()
            preview.rows.mapNotNull { row ->
                val key = (focusedApp?.key?.takeIf { row == focusedRow }) ?: row.ruleKey ?: row.uid?.let { uid ->
                    runCatching { manager.getPackagesForUid(uid)?.singleOrNull() }.getOrNull()?.let { pkg ->
                        RouteRuleBuilder.ruleKey(pkg, uid / 100_000)
                    }
                } ?: return@mapNotNull null
                val (pkg, userId) = RouteRuleBuilder.parseRuleKey(key)
                val label = labels.getOrPut(pkg) {
                    runCatching { manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString() }
                        .getOrDefault(pkg)
                }
                row to (label to userId)
            }.toMap()
        }
    }
    val included = preview.rows.filter { it.skipReason == null && it != focusedRow }
    val skipped = preview.rows.filter { it.skipReason != null && it != focusedRow }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proxy_gateway_preview_title, formatLabel)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (focusedApp != null) {
                    item {
                        if (focusedRow == null) {
                            Text(stringResource(R.string.proxy_gateway_preview_focus_missing), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            val status = if (focusedRow.skipReason == null) R.string.proxy_gateway_preview_focus_included
                            else R.string.proxy_gateway_preview_focus_skipped
                            Text(stringResource(status), style = MaterialTheme.typography.titleSmall)
                            ProxyGatewayPreviewEntry(focusedRow.copy(ruleKey = focusedApp.key), channels, appNames[focusedRow])
                        }
                    }
                }
                item {
                    val fragment = preview.fragment
                    Text(
                        if (fragment == null) stringResource(R.string.proxy_gateway_export_empty)
                        else stringResource(R.string.proxy_gateway_export_summary, fragment.uidCount, fragment.outboundCount),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (preview.possibleBypassInterfaces.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.proxy_gateway_bypass_message, preview.possibleBypassInterfaces.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (included.isNotEmpty()) {
                    item { Text(stringResource(R.string.proxy_gateway_preview_included), style = MaterialTheme.typography.titleSmall) }
                    items(included) { row -> ProxyGatewayPreviewEntry(row, channels, appNames[row]) }
                }
                if (skipped.isNotEmpty()) {
                    item { Text(stringResource(R.string.proxy_gateway_preview_skipped), style = MaterialTheme.typography.titleSmall) }
                    items(skipped) { row -> ProxyGatewayPreviewEntry(row, channels, appNames[row]) }
                }
                item(key = "configure") { TextButton(onClick = onConfigure) { Text(stringResource(R.string.proxy_gateway_configure)) } }
                preview.fragment?.let { fragment ->
                    item(key = "source") {
                        TextButton(onClick = { showRawSource = !showRawSource }) {
                            Text(stringResource(if (showRawSource) R.string.proxy_gateway_hide_source else R.string.proxy_gateway_show_source))
                        }
                        if (showRawSource) {
                            Text(
                                fragment.content,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            preview.fragment?.let { fragment ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    TextButton(onClick = {
                        scope.launch {
                            val label = context.getString(R.string.proxy_gateway_title)
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, fragment.content)))
                            copied = true
                        }
                    }) {
                        Text(
                            stringResource(if (copied) R.string.proxy_gateway_copied else R.string.proxy_gateway_copy),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    TextButton(onClick = { onSave(fragment.content) }) {
                        Text(stringResource(if (preview.possibleBypassInterfaces.isEmpty()) R.string.proxy_gateway_export
                            else R.string.proxy_gateway_export_anyway))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

@Composable
private fun ProxyGatewayPreviewEntry(row: GatewayPreviewRow, channels: List<NetworkChannel>, appName: Pair<String, Int>?) {
    val context = LocalContext.current
    val title = (appName ?: row.ruleKey?.let(RouteRuleBuilder::parseRuleKey))?.let { (label, userId) ->
        if (userId == 0) label else stringResource(R.string.proxy_gateway_preview_user, label, userId)
    } ?: row.uid?.let { stringResource(R.string.proxy_gateway_preview_uid, it) }
        ?: stringResource(R.string.proxy_gateway_preview_unknown_app)
    val channelLabel = row.channel?.let { target ->
        channels.firstOrNull { it.proxyUpstreamKey() == target || it.interfaceName == target }
            ?.let { NetworkUtils.channelLabel(context, it) } ?: NetworkUtils.savedTargetLabel(context, target)
    }
    val reason = when (row.skipReason) {
        GatewayPreviewSkipReason.APP_UNAVAILABLE -> R.string.proxy_gateway_skip_app
        GatewayPreviewSkipReason.INVALID_UID -> R.string.proxy_gateway_skip_uid
        GatewayPreviewSkipReason.CHANNEL_UNAVAILABLE -> R.string.proxy_gateway_skip_offline
        GatewayPreviewSkipReason.OUTBOUND_UNMAPPED -> R.string.proxy_gateway_skip_mapping
        GatewayPreviewSkipReason.NO_DEFAULT_ROUTE -> R.string.proxy_gateway_skip_no_default
        GatewayPreviewSkipReason.PHYSICAL_ROUTE_ACTIVE -> R.string.proxy_gateway_skip_physical
        GatewayPreviewSkipReason.VPN_OWNER -> R.string.proxy_gateway_skip_vpn
        GatewayPreviewSkipReason.VPN_CAPTURE_UNKNOWN -> R.string.proxy_gateway_skip_vpn_capture_unknown
        GatewayPreviewSkipReason.VPN_APP_EXCLUDED -> R.string.proxy_gateway_skip_vpn_app_excluded
        GatewayPreviewSkipReason.VPN_OUTBOUND_SHARED -> R.string.proxy_gateway_skip_vpn_outbound_shared
        GatewayPreviewSkipReason.VPN_WIFI_ONLY -> R.string.proxy_gateway_skip_vpn_wifi_only
        GatewayPreviewSkipReason.SHARED_UID_CONFLICT -> R.string.proxy_gateway_skip_shared
        GatewayPreviewSkipReason.ROUTE_STATE_UNCERTAIN -> R.string.proxy_gateway_skip_stale
        null -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (channelLabel != null && row.outbound != null) {
            Text(stringResource(R.string.proxy_gateway_preview_path, channelLabel, row.outbound),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (channelLabel != null) {
            Text(channelLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (reason != null) {
            Text(stringResource(reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProxyUpstreamDialog(
    channels: List<NetworkChannel>,
    controller: ProxyUpstreamController,
    onDismiss: () -> Unit
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val original = remember { state.profile }
    var portText by remember { mutableStateOf(original?.port?.toString().orEmpty()) }
    var secret by remember { mutableStateOf(controller.secret) }
    var rememberSecret by remember { mutableStateOf(state.secretSaved) }
    var groups by remember { mutableStateOf(original?.let { listOf(it.group) }.orEmpty()) }
    var group by remember { mutableStateOf(original?.group) }
    var selector by remember { mutableStateOf(state.selector) }
    var mappings by remember { mutableStateOf(original?.mappings.orEmpty()) }
    var queryBusy by remember { mutableStateOf(false) }
    var queryFailure by remember { mutableStateOf<Failure?>(null) }
    var confirmRemoval by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val active = channels.mapNotNull { channel -> channel.proxyUpstreamKey()?.let { it to channel } }.toMap()
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    val canConnect = port != null && secret.all { it in ' '..'~' }
    val busy = queryBusy || state.busy

    fun clearDiscovery() {
        groups = emptyList()
        group = null
        selector = null
        mappings = emptyMap()
        queryFailure = null
    }

    fun query(name: String? = null) {
        if (!canConnect || busy) return
        val controllerPort = checkNotNull(port)
        val client = ClashProxyUpstreamClient(controllerPort, secret)
        val previous = group
        val knownGroups = groups
        queryBusy = true
        queryFailure = null
        scope.launch {
            try {
                val (choices, selected, snapshot) = withContext(Dispatchers.IO) {
                    val available = if (name == null) client.listSelectors() else knownGroups
                    if (available.isEmpty()) throw ControllerException(Failure.MISSING_SELECTOR)
                    val preferred = name ?: previous?.takeIf { it in available }
                        ?: ClashProxyUpstreamClient.SELECTOR.takeIf { it in available } ?: available.singleOrNull()
                    Triple(available, preferred, preferred?.let(client::read))
                }
                mappings = when {
                    snapshot == null -> emptyMap()
                    previous == selected && mappings.isNotEmpty() -> mappings.filterValues { it in snapshot.members }
                    original?.port == controllerPort && original.group == selected -> original.mappings.filterValues { it in snapshot.members }
                    else -> active.mapNotNull { (key, channel) ->
                        channel.interfaceName.takeIf { it in snapshot.members }?.let { key to it }
                    }.distinctBy { it.second }.toMap()
                }
                groups = choices
                group = selected
                selector = snapshot
            } catch (error: ControllerException) {
                selector = null
                queryFailure = error.failure
            } finally {
                queryBusy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.proxy_upstream_configure)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.proxy_upstream_setup), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it.filter { char -> char in '0'..'9' }.take(5)
                        clearDiscovery()
                    },
                    label = { Text(stringResource(R.string.proxy_upstream_port)) },
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = {
                        secret = it
                        clearDiscovery()
                    },
                    label = { Text(stringResource(R.string.proxy_upstream_secret)) },
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberSecret && secret.isNotEmpty(), onCheckedChange = { rememberSecret = it },
                        enabled = !busy && secret.isNotEmpty())
                    Text(stringResource(R.string.proxy_upstream_remember_secret))
                }
                TextButton(onClick = { query() }, enabled = !busy && canConnect) {
                    Text(stringResource(R.string.proxy_upstream_read))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ProxyUpstreamMessage(queryFailure ?: state.failure, state.storageFailed)
                if (groups.isNotEmpty()) {
                    Text(stringResource(R.string.proxy_upstream_selector_group), style = MaterialTheme.typography.labelLarge)
                    ProxyUpstreamMenu(group ?: stringResource(R.string.proxy_upstream_choose_group), groups.map { it to it }, !busy) { query(it) }
                }
                selector?.let { current ->
                    Text(stringResource(R.string.proxy_upstream_mapping_help), style = MaterialTheme.typography.bodySmall)
                    for (key in active.keys + mappings.keys) {
                        val channel = active[key]
                        val label = channel?.let { NetworkUtils.channelLabel(context, it) } ?: NetworkUtils.savedTargetLabel(context, key)
                        val chosen = mappings[key]
                        val unused = current.members.filter { it.isNotBlank() && (it == chosen || it !in mappings.values) }
                        val unmapped = stringResource(R.string.proxy_upstream_unmapped)
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        ProxyUpstreamMenu(chosen ?: unmapped, listOf("" to unmapped) + unused.map { it to it }, !busy) { member ->
                            mappings = if (member.isEmpty()) mappings - key else mappings + (key to member)
                        }
                    }
                }
                if (original != null) {
                    TextButton(onClick = { confirmRemoval = true }, enabled = !busy) {
                        Text(stringResource(R.string.proxy_upstream_remove))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val profile = ProxyUpstreamProfile(checkNotNull(port), checkNotNull(group), mappings)
                    controller.save(profile, secret, rememberSecret, onDismiss)
                },
                enabled = !busy && canConnect && group != null && selector != null && mappings.isNotEmpty()
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
        }
    )
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmRemoval = false },
            title = { Text(stringResource(R.string.proxy_upstream_remove)) },
            text = { Text(stringResource(R.string.proxy_upstream_remove_message)) },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = { controller.remove(onDismiss); confirmRemoval = false }, enabled = !busy) {
                    Text(stringResource(R.string.proxy_upstream_remove))
                }
            }
        )
    }
}

@Composable
private fun ProxyUpstreamMenu(selected: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(selected, modifier = Modifier.weight(1f, fill = false))
            Icon(painterResource(R.drawable.ic_symbol_arrow_drop_down), contentDescription = null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(key) })
            }
        }
    }
}

@Composable
internal fun ProxyUpstreamMessage(failure: Failure?, storageFailed: Boolean = false) {
    val label = if (storageFailed) R.string.proxy_upstream_storage_failed else when (failure) {
        Failure.AUTH -> R.string.proxy_upstream_auth_failed
        Failure.MISSING_SELECTOR -> R.string.proxy_upstream_missing_selector
        Failure.INVALID_SELECTOR -> R.string.proxy_upstream_invalid_selector
        Failure.CONNECTION -> R.string.proxy_upstream_connection_failed
        Failure.UNCONFIRMED -> R.string.proxy_upstream_unconfirmed
        null -> return
    }
    Text(stringResource(label), color = MaterialTheme.colorScheme.error)
}

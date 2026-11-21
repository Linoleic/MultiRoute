package com.multiroute.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multiroute.R
import com.multiroute.data.ProxyUpstreamProfile
import com.multiroute.model.NetworkChannel
import com.multiroute.util.ClashProxyUpstreamClient
import com.multiroute.util.ClashProxyUpstreamClient.ControllerException
import com.multiroute.util.ClashProxyUpstreamClient.Failure
import com.multiroute.util.ClashProxyUpstreamClient.Selector
import com.multiroute.util.NetworkUtils
import com.multiroute.util.ProxyGatewayExport
import com.multiroute.util.ProxyGatewayFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProxyUpstreamToolPanel(channels: List<NetworkChannel>, controller: ProxyUpstreamController) {
    val state by controller.state.collectAsStateWithLifecycle()
    var showSetup by remember { mutableStateOf(false) }
    var showGatewaySetup by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val profile = state.profile
        Text(stringResource(R.string.proxy_upstream_live_title), style = MaterialTheme.typography.titleMedium)
        Text(
            if (profile == null) stringResource(R.string.proxy_upstream_setup)
            else stringResource(R.string.proxy_upstream_configured, profile.group, profile.mappings.size),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = { showSetup = true }, enabled = !state.busy) {
            Text(stringResource(R.string.proxy_upstream_configure))
        }
        HorizontalDivider()
        Text(stringResource(R.string.proxy_gateway_title), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { showGatewaySetup = true }, enabled = !state.gatewayBusy) {
            Text(stringResource(R.string.proxy_gateway_configure))
        }
        ProxyGatewayExportSection(controller, enabled = !state.gatewayBusy && state.gatewayMappings.isNotEmpty())
    }
    if (showSetup) ProxyUpstreamDialog(channels, controller) { showSetup = false }
    if (showGatewaySetup) {
        ProxyGatewayMappingDialog(channels, state, controller) { showGatewaySetup = false }
    }
}

@Composable
private fun ProxyGatewayMappingDialog(
    channels: List<NetworkChannel>,
    state: ProxyUpstreamUiState,
    controller: ProxyUpstreamController,
    onDismiss: () -> Unit
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
                        if (saved) onDismiss()
                    }
                }
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.gatewayBusy) { Text(stringResource(R.string.ui_cancel)) }
        }
    )
}

@Composable
private fun ProxyGatewayExportSection(controller: ProxyUpstreamController, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var formatName by rememberSaveable { mutableStateOf(ProxyGatewayFormat.SING_BOX.name) }
    val format = ProxyGatewayFormat.valueOf(formatName)
    var pendingContent by rememberSaveable { mutableStateOf<String?>(null) }
    var possibleBypassInterfaces by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(remember(format) { ActivityResultContracts.CreateDocument(format.mimeType) }) { uri ->
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

    fun launchFilePicker() {
        possibleBypassInterfaces = null
        try {
            filePicker.launch(format.fileName)
        } catch (_: IllegalStateException) {
            pendingContent = null
            message = context.getString(R.string.proxy_gateway_export_failed)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingContent = null
            message = context.getString(R.string.proxy_gateway_export_failed)
        }
    }

    Text(
        stringResource(if (format == ProxyGatewayFormat.MIHOMO) R.string.proxy_gateway_export_mihomo_help else R.string.proxy_gateway_export_help),
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        stringResource(R.string.proxy_gateway_compatibility_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val canExport = enabled && !busy && pendingContent == null
    ProxyUpstreamMenu(
        selected = stringResource(R.string.proxy_gateway_format, format.label),
        options = ProxyGatewayFormat.entries.map { it.name to it.label },
        enabled = canExport,
        onSelect = { selected -> formatName = selected; message = null }
    )
    OutlinedButton(enabled = canExport, onClick = {
        busy = true
        message = null
        scope.launch {
            try {
                val export = controller.prepareGatewayExport(format)
                if (export == null) {
                    message = context.getString(R.string.proxy_gateway_export_empty)
                } else {
                    val fragment = export.fragment
                    pendingContent = fragment.content
                    message = context.getString(R.string.proxy_gateway_export_summary, fragment.uidCount, fragment.outboundCount)
                    if (export.possibleBypassInterfaces.isEmpty()) {
                        launchFilePicker()
                    } else {
                        possibleBypassInterfaces = export.possibleBypassInterfaces.joinToString(", ")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: ProxyGatewayExport.InvalidMihomoOutbound) {
                message = context.getString(R.string.proxy_gateway_invalid_mihomo_tag)
            } catch (_: Exception) {
                pendingContent = null
                message = context.getString(R.string.proxy_gateway_export_failed)
            } finally {
                busy = false
            }
        }
    }) { Text(stringResource(R.string.proxy_gateway_export)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    possibleBypassInterfaces?.let { interfaces ->
        val cancel = {
            possibleBypassInterfaces = null
            pendingContent = null
            message = null
        }
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(stringResource(R.string.proxy_gateway_bypass_title)) },
            text = { Text(stringResource(R.string.proxy_gateway_bypass_message, interfaces)) },
            confirmButton = { TextButton(onClick = ::launchFilePicker) { Text(stringResource(R.string.proxy_gateway_export_anyway)) } },
            dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.ui_cancel)) } }
        )
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
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
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

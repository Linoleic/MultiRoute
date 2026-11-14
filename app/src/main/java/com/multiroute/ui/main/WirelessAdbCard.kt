package com.multiroute.ui.main

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.multiroute.R
import com.multiroute.adb.*
import com.multiroute.theme.AppMotion
import com.multiroute.util.WirelessAdbInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AdbModeChoice {
    OFF,
    TLS,
    TCP
}

@Composable
fun WirelessAdbCard(showTitle: Boolean = true) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val owner = LocalLifecycleOwner.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<WirelessAdbInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var settingsFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AdbActionResult?>(null) }
    var bootPolicy by remember { mutableStateOf(AdbBootPolicy()) }
    var confirmOff by remember { mutableStateOf(false) }
    var showBootInfoDialog by remember { mutableStateOf(false) }

    var selectedMode by rememberSaveable { mutableStateOf(AdbMode.TLS) }
    var selectedChoice by rememberSaveable { mutableStateOf<AdbModeChoice?>(null) }
    var portText by rememberSaveable { mutableStateOf("5555") }
    var initializedPrefs by remember { mutableStateOf(false) }
    var expandAllAddresses by rememberSaveable { mutableStateOf(false) }
    var expandedAddressIndices by rememberSaveable { mutableStateOf(setOf<Int>()) }

    fun perform(action: suspend () -> AdbActionResult) {
        scope.launch {
            busy = true
            try { result = action() } finally { busy = false; revision++ }
        }
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(revision) {
        loading = true
        val p = withContext(Dispatchers.IO) { WirelessAdbController.policy(context) }
        bootPolicy = p
        val prefMode = if (initializedPrefs) selectedMode else p.mode
        val inf = withContext(Dispatchers.IO) { WirelessAdbInfo.read(context, preferredMode = prefMode) }
        info = inf
        if (!initializedPrefs) {
            selectedMode = inf.mode ?: p.mode
            portText = (inf.tcpPort ?: p.customPort).toString()
            selectedChoice = if (inf.enabled == true) {
                if (inf.mode == AdbMode.TCP) AdbModeChoice.TCP else AdbModeChoice.TLS
            } else {
                AdbModeChoice.OFF
            }
            initializedPrefs = true
        } else {
            if (inf.enabled == false && selectedChoice != AdbModeChoice.TLS && selectedChoice != AdbModeChoice.TCP) {
                selectedChoice = AdbModeChoice.OFF
            } else if (inf.enabled == true && selectedChoice == AdbModeChoice.OFF) {
                selectedChoice = if (inf.mode == AdbMode.TCP) AdbModeChoice.TCP else AdbModeChoice.TLS
            }
        }
        loading = false
    }

    val parsedPort = portText.toIntOrNull()
    val isPortValid = parsedPort != null && parsedPort in 1024..65535
    val isRunning = info?.enabled == true && info?.listening == true
    val isEnabled = info?.enabled == true
    val activeMode = info?.mode ?: bootPolicy.mode
    val activePort = info?.port ?: (if (activeMode == AdbMode.TCP) bootPolicy.customPort else null)
    val activeChoice = if (isEnabled) {
        if (activeMode == AdbMode.TCP) AdbModeChoice.TCP else AdbModeChoice.TLS
    } else {
        AdbModeChoice.OFF
    }
    val currentChoice = selectedChoice ?: activeChoice

    val isPendingTurnOn = !isEnabled && (currentChoice == AdbModeChoice.TLS || currentChoice == AdbModeChoice.TCP)
    val isConfigModified = isEnabled && (
        currentChoice != activeChoice ||
        (currentChoice == AdbModeChoice.TCP && isPortValid && parsedPort != activePort)
    )

    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showTitle) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.adb_helper_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
            }

            when {
                loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.adb_helper_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                info?.enabled == false -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            stringResource(R.string.adb_helper_off),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                info?.enabled == true && info?.listening == true -> {
                    val currentInfo = info!!
                    if (currentInfo.isClientConnected) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                val clientText = if (currentInfo.connectedClients.size == 1) {
                                    stringResource(R.string.adb_helper_connected_single, currentInfo.connectedClients.first())
                                } else {
                                    stringResource(R.string.adb_helper_connected_multi, currentInfo.connectedClients.size, currentInfo.connectedClients.joinToString(", "))
                                }
                                Text(
                                    text = clientText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.HourglassEmpty,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                val port = currentInfo.port
                                val waitingText = if (port != null) {
                                    if (currentInfo.mode == AdbMode.TCP) stringResource(R.string.adb_helper_waiting_tcp, port)
                                    else stringResource(R.string.adb_helper_waiting_tls, port)
                                } else stringResource(R.string.adb_helper_waiting)
                                Text(
                                    text = waitingText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                info?.enabled == true && info?.listening != true -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                stringResource(R.string.adb_helper_not_listening),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        stringResource(R.string.adb_helper_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.adb_mode_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = currentChoice == AdbModeChoice.OFF,
                        onClick = {
                            if (isEnabled) {
                                confirmOff = true
                            } else {
                                selectedChoice = AdbModeChoice.OFF
                            }
                        },
                        enabled = !busy && !loading,
                        label = {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.adb_mode_off))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = currentChoice == AdbModeChoice.TLS,
                        onClick = {
                            selectedChoice = AdbModeChoice.TLS
                            selectedMode = AdbMode.TLS
                            scope.launch(Dispatchers.IO) {
                                WirelessAdbController.updateConfig(context, AdbMode.TLS, parsedPort ?: 5555)
                            }
                        },
                        enabled = !busy && !loading,
                        label = {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.adb_mode_tls))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = currentChoice == AdbModeChoice.TCP,
                        onClick = {
                            selectedChoice = AdbModeChoice.TCP
                            selectedMode = AdbMode.TCP
                            scope.launch(Dispatchers.IO) {
                                WirelessAdbController.updateConfig(context, AdbMode.TCP, parsedPort ?: 5555)
                            }
                        },
                        enabled = !busy && !loading,
                        label = {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.adb_mode_tcp))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = when (currentChoice) {
                        AdbModeChoice.OFF -> stringResource(R.string.adb_mode_off_summary)
                        AdbModeChoice.TCP -> stringResource(R.string.adb_mode_tcp_summary)
                        else -> stringResource(R.string.adb_mode_tls_summary)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = currentChoice == AdbModeChoice.TCP,
                enter = AppMotion.expand(),
                exit = AppMotion.collapse()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(5)
                            portText = filtered
                            val port = filtered.toIntOrNull()
                            if (port != null && port in 1024..65535) {
                                scope.launch(Dispatchers.IO) {
                                    WirelessAdbController.updateConfig(context, AdbMode.TCP, port)
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.adb_custom_port_label)) },
                        isError = !isPortValid && portText.isNotEmpty(),
                        supportingText = {
                            if (!isPortValid && portText.isNotEmpty()) {
                                Text(stringResource(R.string.adb_custom_port_error))
                            }
                        },
                        trailingIcon = {
                            if (portText != "5555") {
                                TextButton(onClick = {
                                    portText = "5555"
                                    scope.launch(Dispatchers.IO) {
                                        WirelessAdbController.updateConfig(context, AdbMode.TCP, 5555)
                                    }
                                }) {
                                    Text(stringResource(R.string.adb_custom_port_reset), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { revision++ },
                    enabled = !loading && !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.adb_helper_refresh)) }

                TextButton(
                    onClick = {
                        settingsFailed = runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }.isFailure
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.adb_helper_settings)) }
            }

            if (isConfigModified || isPendingTurnOn) {
                Button(
                    onClick = {
                        val targetMode = if (currentChoice == AdbModeChoice.TCP) AdbMode.TCP else AdbMode.TLS
                        perform {
                            WirelessAdbController.setEnabled(
                                context,
                                true,
                                targetMode,
                                parsedPort ?: 5555
                            )
                        }
                    },
                    enabled = !loading && !busy && (currentChoice != AdbModeChoice.TCP || isPortValid),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (busy) R.string.adb_control_working
                            else if (isConfigModified) R.string.adb_control_apply
                            else R.string.adb_control_on
                        )
                    )
                }
            }

            val outcome = result ?: bootPolicy.result
            if (outcome != null) Text(
                stringResource(R.string.adb_control_result, stringResource(when (outcome) {
                    AdbActionResult.READY -> R.string.adb_control_ready
                    AdbActionResult.OFF -> R.string.adb_control_stopped
                    AdbActionResult.SAVED -> R.string.adb_control_saved
                    AdbActionResult.WAIT_NETWORK -> R.string.adb_boot_wait_network
                    AdbActionResult.WAIT_UNLOCK -> R.string.adb_boot_wait_unlock
                    AdbActionResult.EXPIRED -> R.string.adb_boot_expired
                    AdbActionResult.INACTIVE -> R.string.adb_boot_inactive
                    AdbActionResult.NO_NETWORK -> R.string.adb_control_no_network
                    AdbActionResult.FAILED -> R.string.adb_control_failed
                })),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (settingsFailed) Text(
                stringResource(R.string.adb_helper_settings_failed),
                style = MaterialTheme.typography.bodySmall
            )

            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(if (expanded) R.string.adb_helper_hide else R.string.adb_helper_details),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            AnimatedVisibility(visible = expanded, enter = AppMotion.expand(), exit = AppMotion.collapse()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.adb_boot_title),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(
                                onClick = { showBootInfoDialog = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = stringResource(R.string.adb_boot_dialog_title),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = bootPolicy.enabled,
                            enabled = !loading && !busy && (bootPolicy.enabled || info?.enabled == true && info?.listening == true),
                            onCheckedChange = { enabled ->
                                perform {
                                    WirelessAdbController.setBootEnabled(
                                        context,
                                        enabled,
                                        selectedMode,
                                        parsedPort ?: 5555
                                    )
                                }
                            }
                        )
                    }
                    Text(
                        stringResource(R.string.adb_boot_recovery_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (bootPolicy.enabled) {
                        bootPolicy.ssid?.let {
                            Text(stringResource(R.string.adb_boot_network, it), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (bootPolicy.enabled) {
                        val modeStr = stringResource(if (bootPolicy.mode == AdbMode.TCP) R.string.adb_mode_tcp else R.string.adb_mode_tls)
                        Text(
                            stringResource(R.string.adb_boot_mode_summary, modeStr, if (bootPolicy.mode == AdbMode.TCP) bootPolicy.customPort else (info?.port ?: bootPolicy.customPort)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.adb_candidates_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val snapshot = info
                        if (!snapshot?.addresses.isNullOrEmpty()) {
                            TextButton(
                                onClick = {
                                    expandAllAddresses = !expandAllAddresses
                                    if (!expandAllAddresses) {
                                        expandedAddressIndices = emptySet()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    stringResource(if (expandAllAddresses) R.string.adb_address_collapse_all else R.string.adb_address_expand_all),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    Text(
                        stringResource(R.string.adb_helper_candidates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val snapshot = info
                    if (snapshot?.addresses.isNullOrEmpty()) {
                        Text(stringResource(R.string.adb_helper_no_address), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            snapshot.addresses.forEachIndexed { index, (iface, address) ->
                                val isExpanded = expandAllAddresses || expandedAddressIndices.contains(index)
                                val isIpv6 = address.contains(':')
                                val formattedAddr = if (isIpv6) "[$address]" else address
                                val isConnected = snapshot.connectedLocalAddresses.contains(address)
                                val endpointText = "$formattedAddr${snapshot.port?.let { ":$it" }.orEmpty()}"
                                val copyCmd = "adb connect $endpointText"

                                Surface(
                                    color = if (isConnected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (!isExpanded) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = iface,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }

                                            if (isConnected) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.adb_address_active_badge),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                        maxLines = 1,
                                                        softWrap = false
                                                    )
                                                }
                                            }

                                            Text(
                                                text = endpointText,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )

                                            TextButton(
                                                onClick = {
                                                    expandedAddressIndices = expandedAddressIndices + index
                                                },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text(
                                                    stringResource(R.string.adb_address_expand),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    clipboard.setText(AnnotatedString(copyCmd))
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.adb_helper_copied, copyCmd),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                enabled = !loading && !busy && snapshot.enabled == true && snapshot.listening == true && snapshot.port != null,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ContentCopy,
                                                    contentDescription = stringResource(R.string.adb_helper_copy),
                                                    modifier = Modifier.size(16.dp),
                                                    tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = iface,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }

                                                if (isConnected) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = stringResource(R.string.adb_address_active_badge),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                            maxLines = 1,
                                                            softWrap = false
                                                        )
                                                    }
                                                }

                                                Spacer(Modifier.weight(1f))

                                                TextButton(
                                                    onClick = {
                                                        expandedAddressIndices = expandedAddressIndices - index
                                                        expandAllAddresses = false
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text(
                                                        stringResource(R.string.adb_address_collapse),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }

                                                IconButton(
                                                    onClick = {
                                                        clipboard.setText(AnnotatedString(copyCmd))
                                                        Toast.makeText(
                                                            context,
                                                            context.getString(R.string.adb_helper_copied, copyCmd),
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    },
                                                    enabled = !loading && !busy && snapshot.enabled == true && snapshot.listening == true && snapshot.port != null,
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.ContentCopy,
                                                        contentDescription = stringResource(R.string.adb_helper_copy),
                                                        modifier = Modifier.size(16.dp),
                                                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }

                                            Text(
                                                text = endpointText,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                softWrap = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        stringResource(if (selectedMode == AdbMode.TCP) R.string.adb_helper_tcp_note else R.string.adb_helper_pairing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text(stringResource(R.string.adb_control_off)) },
        text = { Text(stringResource(R.string.adb_control_off_warning)) },
        confirmButton = {
            TextButton(onClick = {
                confirmOff = false
                selectedChoice = AdbModeChoice.OFF
                perform { WirelessAdbController.setEnabled(context, false) }
            }) { Text(stringResource(R.string.adb_control_off)) }
        },
        dismissButton = {
            TextButton(onClick = { confirmOff = false }) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )

    if (showBootInfoDialog) AlertDialog(
        onDismissRequest = { showBootInfoDialog = false },
        title = { Text(stringResource(R.string.adb_boot_dialog_title)) },
        text = {
            Text(
                stringResource(R.string.adb_boot_dialog_content),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = { showBootInfoDialog = false }) {
                Text(stringResource(R.string.adb_boot_dialog_confirm))
            }
        }
    )
}

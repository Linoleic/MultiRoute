package com.multiroute.ui.main

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.multiroute.R
import com.multiroute.util.WirelessAdbInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WirelessAdbCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val owner = LocalLifecycleOwner.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<WirelessAdbInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var settingsFailed by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision) {
        loading = true
        info = withContext(Dispatchers.IO) { WirelessAdbInfo.read() }
        loading = false
    }
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.adb_helper_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
            }
            Text(stringResource(when {
                loading -> R.string.adb_helper_loading
                info?.enabled == false -> R.string.adb_helper_off
                info?.enabled == true && info?.listening == true -> R.string.adb_helper_listening
                info?.enabled == true && info?.listening == false -> R.string.adb_helper_not_listening
                else -> R.string.adb_helper_unknown
            }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { revision++ }, enabled = !loading, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.adb_helper_refresh)) }
                TextButton(onClick = {
                    settingsFailed = runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }.isFailure
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.adb_helper_settings)) }
            }
            if (settingsFailed) Text(stringResource(R.string.adb_helper_settings_failed), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.adb_helper_hide else R.string.adb_helper_details),
                    style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                Text(stringResource(R.string.adb_helper_candidates), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val snapshot = info
                if (snapshot?.addresses.isNullOrEmpty()) {
                    Text(stringResource(R.string.adb_helper_no_address), style = MaterialTheme.typography.bodyMedium)
                }
                snapshot?.addresses?.forEach { (iface, address) ->
                    Column(Modifier.fillMaxWidth()) {
                        Text("$iface · $address${snapshot.port?.let { ":$it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString("adb connect $address:${snapshot.port}"))
                        }, enabled = !loading && snapshot.enabled == true && snapshot.listening == true) {
                            Text(stringResource(R.string.adb_helper_copy))
                        }
                    }
                }
                Text(stringResource(R.string.adb_helper_pairing_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

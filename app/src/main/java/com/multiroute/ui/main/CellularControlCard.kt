package com.multiroute.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.multiroute.R
import com.multiroute.cellular.CellularSubscriptions
import com.multiroute.cellular.DualSimController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CellularControlCard(uiState: MainUiState, viewModel: MainScreenViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var subscriptions by remember { mutableStateOf(emptyList<com.multiroute.cellular.CellularSubscription>()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var preferred by remember { mutableIntStateOf(2) }
    LaunchedEffect(context, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!busy) {
                    mode = DualSimController.status()
                    if (mode != 0) preferred = mode
                    val current = CellularSubscriptions.read(context)
                    if (current != subscriptions) {
                        subscriptions = current
                        viewModel.refreshEnvironment()
                    }
                }
                delay(3000)
            }
        }
    }
    val secondary = subscriptions.singleOrNull { !it.isDefault }
    val secondaryOnline = secondary?.networks?.values?.any { iface -> uiState.channels.any {
        it.interfaceName == iface && it.transportType == "cellular" && it.isValidated
    } } == true
    fun select(value: Int) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                failed = !DualSimController.select(context, value)
                mode = DualSimController.status()
                viewModel.refreshEnvironment()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally { busy = false }
        }
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.cellular_control_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when {
                busy -> R.string.cellular_changing
                failed -> R.string.cellular_failed
                mode == 0 -> R.string.cellular_stopped
                secondaryOnline -> R.string.cellular_connected
                else -> R.string.cellular_waiting
            }), style = MaterialTheme.typography.bodyMedium)
            subscriptions.forEach {
                Text(context.getString(R.string.cellular_sim_label, it.slot, it.carrier) +
                    if (it.isDefault) context.getString(R.string.cellular_default_suffix) else "")
            }
            Text(stringResource(R.string.cellular_control_note), style = MaterialTheme.typography.bodySmall)
            if (subscriptions.size != 2) Text(stringResource(R.string.cellular_two_required))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { select(preferred) }, enabled = !busy && subscriptions.size == 2 && mode == 0) {
                    Text(stringResource(R.string.cellular_enable))
                }
                OutlinedButton(onClick = { select(0) }, enabled = !busy) {
                    Text(stringResource(R.string.cellular_disable))
                }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.cellular_advanced)) }
            if (advanced) {
                Text(stringResource(R.string.cellular_method_note), style = MaterialTheme.typography.bodySmall)
                Row {
                    FilterChip(selected = preferred == 2, onClick = { preferred = 2 }, enabled = mode == 0 && !busy,
                        label = { Text(stringResource(R.string.cellular_request_method)) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = preferred == 1, onClick = { preferred = 1 }, enabled = mode == 0 && !busy,
                        label = { Text(stringResource(R.string.cellular_property_method)) })
                }
            }
        }
    }
}

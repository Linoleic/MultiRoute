package com.multiroute.ui.main

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.multiroute.R
import com.multiroute.cellular.CellularSubscriptions
import com.multiroute.cellular.DualSimController
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.ScenarioStore
import com.multiroute.data.TestServerManager
import com.multiroute.model.NetworkChannel
import com.multiroute.util.NetworkUtils
import com.multiroute.util.SuHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates system network monitoring, environment diagnostics, egress channel probing,
 * dual-SIM cellular control, and system-level routing preference toggles.
 */
internal class EnvironmentAndChannelDelegate(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiStateFlow: MutableStateFlow<MainUiState>,
    private val cellularControlStateFlow: MutableStateFlow<CellularControlUiState>,
    private val onRefreshScenario: () -> Unit
) {
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var lastNetworkChangeTime = 0L
    private var recoveryJob: Job? = null
    private var environmentRefreshCount = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkAvailable()
        }

        override fun onLost(network: Network) {
            handleNetworkLost()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            handleNetworkChanged()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            handleNetworkChanged()
        }
    }

    fun registerNetworkCallbacks() {
        try {
            val request = NetworkRequest.Builder().build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {}
    }

    fun unregisterNetworkCallbacks() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    private fun handleNetworkLost() {
        // Hard failure: Disconnected or out of range. If auto failover is enabled,
        // cancel any pending recovery debounce and trigger immediate rule resync.
        recoveryJob?.cancel()
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < 300L) return
        lastNetworkChangeTime = now
        scope.launch {
            try {
                val activeChannels = withContext(Dispatchers.IO) { NetworkUtils.getActiveChannels(application) }
                uiStateFlow.update { it.copy(channelProbe = it.channelProbe.copy(channels = activeChannels)) }
                onRefreshScenario()
                withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(application) }
            } catch (_: Exception) {}
        }
    }

    private fun handleNetworkAvailable() {
        val holdOffSecs = RouteConfigProvider.getFailoverHoldOffSeconds(application)
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            val hasScenarios = ScenarioStore.load(application).isNotEmpty()
            val isFailover = RouteConfigProvider.isAutoFailoverEnabled(application)
            if ((isFailover || hasScenarios) && holdOffSecs > 0) {
                delay(holdOffSecs * 1000L)
            }
            try {
                val activeChannels = withContext(Dispatchers.IO) { NetworkUtils.getActiveChannels(application) }
                uiStateFlow.update { it.copy(channelProbe = it.channelProbe.copy(channels = activeChannels)) }
                onRefreshScenario()
                withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(application) }
            } catch (_: Exception) {}
        }
    }

    private fun handleNetworkChanged() {
        if (recoveryJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < 1500L) return
        lastNetworkChangeTime = now
        scope.launch {
            try {
                val activeChannels = withContext(Dispatchers.IO) { NetworkUtils.getActiveChannels(application) }
                uiStateFlow.update { it.copy(channelProbe = it.channelProbe.copy(channels = activeChannels)) }
                onRefreshScenario()
                withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(application) }
            } catch (_: Exception) {}
        }
    }

    fun loadTestServerConfig() {
        val config = TestServerManager.getConfig(application)
        uiStateFlow.update { it.copy(channelProbe = it.channelProbe.copy(testServerConfig = config)) }
    }

    fun updateTestServerConfig(presetId: String, customUrl: String? = null) {
        val newConfig = TestServerManager.saveConfig(application, presetId, customUrl)
        uiStateFlow.update {
            it.copy(
                channelProbe = it.channelProbe.copy(testServerConfig = newConfig),
                navigation = it.navigation.copy(showTestServerDialog = false),
                snackBarMessage = application.getString(R.string.vm_test_node_set, newConfig.displayName(application))
            )
        }
    }

    fun refreshEnvironment() {
        scope.launch {
            environmentRefreshCount++
            uiStateFlow.update {
                it.copy(
                    env = it.env.copy(isEnvironmentRefreshing = true, environmentRefreshFailed = false)
                )
            }
            try {
                val diag = withContext(Dispatchers.IO) { SuHelper.getDiagnosticInfo(application) }
                val activeChannels = withContext(Dispatchers.IO) { NetworkUtils.getActiveChannels(application) }

                uiStateFlow.update { state ->
                    state.copy(
                        channelProbe = state.channelProbe.copy(channels = activeChannels),
                        env = state.env.copy(
                            isRootGranted = diag.isRootGranted,
                            isMobileDataAlwaysOn = diag.mobileDataAlwaysOn,
                            isKeepSlaveWifiScreenOff = diag.isKeepSlaveWifiScreenOff,
                            isDnsFollowsChannel = RouteConfigProvider.isDnsFollowsChannel(application),
                            isAutoFailoverEnabled = RouteConfigProvider.isAutoFailoverEnabled(application),
                            failoverHoldOffSeconds = RouteConfigProvider.getFailoverHoldOffSeconds(application),
                            isFailoverProbeInternet = RouteConfigProvider.isFailoverProbeInternet(application),
                            diagnosticInfo = diag,
                            environmentRefreshFailed = false
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiStateFlow.update {
                    it.copy(env = it.env.copy(environmentRefreshFailed = true))
                }
                Log.e("MainViewModel", "Error refreshing environment", e)
            } finally {
                environmentRefreshCount--
                uiStateFlow.update {
                    it.copy(env = it.env.copy(isEnvironmentRefreshing = environmentRefreshCount > 0))
                }
            }
        }
    }

    fun openLogsDialog() {
        uiStateFlow.update {
            it.copy(
                navigation = it.navigation.copy(
                    showLogsDialog = true,
                    isLoadingLogs = true,
                    logsContent = application.getString(R.string.vm_log_loading)
                )
            )
        }
        scope.launch {
            try {
                val logs = withContext(Dispatchers.IO) { SuHelper.getDiagnosticLogs(application) }
                uiStateFlow.update {
                    it.copy(navigation = it.navigation.copy(isLoadingLogs = false, logsContent = logs))
                }
            } catch (e: Exception) {
                uiStateFlow.update {
                    it.copy(
                        navigation = it.navigation.copy(
                            isLoadingLogs = false,
                            logsContent = application.getString(R.string.vm_log_failed, e.message ?: "")
                        )
                    )
                }
            }
        }
    }

    fun closeLogsDialog() {
        uiStateFlow.update { it.copy(navigation = it.navigation.copy(showLogsDialog = false)) }
    }

    fun testSingleChannel(channel: NetworkChannel) {
        scope.launch {
            uiStateFlow.update {
                it.copy(channelProbe = it.channelProbe.copy(isTestingSingleChannel = true))
            }
            try {
                val result = NetworkUtils.testSingleChannelPublicIp(
                    context = application,
                    channel = channel,
                    config = uiStateFlow.value.channelProbe.testServerConfig
                )
                uiStateFlow.update { state ->
                    val updated = state.channelProbe.testResults.toMutableMap()
                    updated[channel.id] = result
                    state.copy(
                        channelProbe = state.channelProbe.copy(testResults = updated),
                        snackBarMessage = application.getString(R.string.vm_egress_done, channel.interfaceName)
                    )
                }
            } catch (e: Exception) {
                uiStateFlow.update {
                    it.copy(
                        snackBarMessage = application.getString(R.string.vm_egress_failed, channel.interfaceName, e.message ?: "")
                    )
                }
            } finally {
                uiStateFlow.update {
                    it.copy(channelProbe = it.channelProbe.copy(isTestingSingleChannel = false))
                }
            }
        }
    }

    fun runChannelTests() {
        scope.launch {
            uiStateFlow.update {
                it.copy(channelProbe = it.channelProbe.copy(isTesting = true, testResults = emptyMap()))
            }
            try {
                val currentChannels = uiStateFlow.value.channelProbe.channels.ifEmpty {
                    NetworkUtils.getActiveChannels(application)
                }
                val results = NetworkUtils.testChannelsConcurrently(
                    context = application,
                    channels = currentChannels,
                    config = uiStateFlow.value.channelProbe.testServerConfig
                )
                uiStateFlow.update {
                    it.copy(
                        channelProbe = it.channelProbe.copy(testResults = results),
                        snackBarMessage = application.getString(R.string.vm_diag_done)
                    )
                }
            } catch (e: Exception) {
                uiStateFlow.update {
                    it.copy(snackBarMessage = application.getString(R.string.vm_diag_failed, e.message ?: ""))
                }
            } finally {
                uiStateFlow.update {
                    it.copy(channelProbe = it.channelProbe.copy(isTesting = false))
                }
            }
            refreshEnvironment()
        }
    }

    fun toggleDnsFollowsChannel() {
        scope.launch {
            val next = !uiStateFlow.value.env.isDnsFollowsChannel
            RouteConfigProvider.setDnsFollowsChannel(application, next)
            uiStateFlow.update { it.copy(env = it.env.copy(isDnsFollowsChannel = next)) }
            SuHelper.syncAllRouteRules(application)
            refreshEnvironment()
        }
    }

    fun toggleAutoFailover() {
        scope.launch {
            val next = !uiStateFlow.value.env.isAutoFailoverEnabled
            RouteConfigProvider.setAutoFailoverEnabled(application, next)
            uiStateFlow.update { it.copy(env = it.env.copy(isAutoFailoverEnabled = next)) }
            refreshEnvironment()
        }
    }

    fun updateFailoverHoldOffSeconds(seconds: Int) {
        scope.launch {
            val safeSecs = seconds.coerceIn(1, 15)
            RouteConfigProvider.setFailoverHoldOffSeconds(application, safeSecs)
            uiStateFlow.update { it.copy(env = it.env.copy(failoverHoldOffSeconds = safeSecs)) }
            refreshEnvironment()
        }
    }

    fun toggleFailoverProbeInternet() {
        scope.launch {
            val next = !uiStateFlow.value.env.isFailoverProbeInternet
            RouteConfigProvider.setFailoverProbeInternet(application, next)
            uiStateFlow.update { it.copy(env = it.env.copy(isFailoverProbeInternet = next)) }
            withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(application) }
            refreshEnvironment()
        }
    }

    fun toggleKeepSlaveWifiScreenOff() {
        scope.launch {
            val next = !uiStateFlow.value.env.isKeepSlaveWifiScreenOff
            RouteConfigProvider.setKeepSlaveWifiScreenOffByUser(application, next)
            SuHelper.setKeepSlaveWifiScreenOff(application, next)
            uiStateFlow.update {
                it.copy(
                    env = it.env.copy(isKeepSlaveWifiScreenOff = next),
                    snackBarMessage = application.getString(if (next) R.string.vm_keep_on else R.string.vm_keep_off)
                )
            }
            refreshEnvironment()
        }
    }

    fun toggleMobileDataAlwaysOn() {
        if (!uiStateFlow.value.env.supportsCellularData) return
        scope.launch {
            val current = uiStateFlow.value.env.isMobileDataAlwaysOn
            val success = if (current) {
                SuHelper.disableMobileDataAlwaysOn(application)
            } else {
                SuHelper.enableMobileDataAlwaysOn(application)
            }
            if (success) {
                refreshEnvironment()
                uiStateFlow.update {
                    it.copy(
                        snackBarMessage = application.getString(if (!current) R.string.vm_cellular_on else R.string.vm_cellular_off)
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ Cellular Control

    suspend fun refreshCellularControlState() {
        if (cellularControlStateFlow.value.isBusy) return
        try {
            val mode = DualSimController.status()
            val subscriptions = CellularSubscriptions.read(application)
            val current = cellularControlStateFlow.value
            if (current.isBusy) return
            cellularControlStateFlow.value = current.copy(
                mode = mode,
                subscriptions = subscriptions,
                preferredMode = if (mode != 0) mode else current.preferredMode,
                isLoaded = true
            )
            if (subscriptions != current.subscriptions) refreshEnvironment()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val current = cellularControlStateFlow.value
            if (!current.isLoaded) cellularControlStateFlow.value = current.copy(isLoaded = true)
        }
    }

    fun setCellularDetailsExpanded(expanded: Boolean) {
        cellularControlStateFlow.update { it.copy(detailsExpanded = expanded) }
    }

    fun setCellularPreferredMode(mode: Int) {
        if (mode !in 1..2) return
        cellularControlStateFlow.update { current ->
            if (current.mode != 0 || current.isBusy) current else current.copy(preferredMode = mode)
        }
    }

    fun selectCellularMode(mode: Int) {
        val current = cellularControlStateFlow.value
        if (mode !in 0..2 || current.isBusy) return
        if (mode != 0 && (current.subscriptions.size != 2 ||
                current.subscriptions.singleOrNull { !it.isDefault } == null)) return

        cellularControlStateFlow.update { it.copy(isBusy = true, hasFailed = false) }
        scope.launch {
            try {
                val succeeded = DualSimController.select(application, mode)
                val actualMode = DualSimController.status()
                cellularControlStateFlow.update { latest ->
                    latest.copy(
                        mode = actualMode,
                        preferredMode = if (actualMode != 0) actualMode else latest.preferredMode,
                        hasFailed = !succeeded,
                        isLoaded = true
                    )
                }
                refreshEnvironment()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                cellularControlStateFlow.update { it.copy(hasFailed = true) }
            } finally {
                cellularControlStateFlow.update { it.copy(isBusy = false) }
            }
        }
    }
}

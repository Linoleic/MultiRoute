package com.multiroute.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.annotation.Keep
import com.multiroute.data.RouteConfigProvider
import com.multiroute.model.ScenarioResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified root-shell and system-integration facade for MultiRoute.
 *
 * Delegates shell execution to [RootShellExecutor] (pooled persistent `su` sessions), routing policy
 * orchestration to [RouteSyncCoordinator], Xposed/kernel diagnostics to [ModuleDiagnosticsCollector],
 * and tethering controls to [HotspotShellHelper].
 */
object SuHelper {
    private val shell: RootShellExecutor get() = RootShellExecutor.default
    private val routeSync by lazy { RouteSyncCoordinator(shell) }
    private val diagnostics by lazy { ModuleDiagnosticsCollector(shell, routeSync) }
    private val hotspot by lazy { HotspotShellHelper(shell) }

    /** Prevent a receiver sync from observing a partially applied configuration. */
    internal suspend fun <T> withRouteConfigLock(block: suspend () -> T): T =
        routeSync.withRouteConfigLock(block)

    fun isKeepSlaveWifiScreenOff(context: Context): Boolean =
        RouteConfigProvider.isKeepSlaveWifiScreenOff(context)

    suspend fun setKeepSlaveWifiScreenOff(context: Context, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            RouteConfigProvider.setKeepSlaveWifiScreenOff(context, enabled)

            // 1. Transient system property for instantaneous system_server hook access
            val propVal = if (enabled) "1" else "0"
            executeCommand("setprop sys.multiroute.keep_slave_wifi $propVal")

            // 1b. Persistent twin of the same flag. `sys.*` is wiped on reboot and RemotePreferences are
            // unreadable until this app has been started, so without this the keep-alive silently judged
            // itself "off" for the first ~90s of every boot (verified on device).
            executeCommand("setprop persist.multiroute.keep_slave_wifi $propVal")

            // 2. Xiaomi system setting: 0 = disable auto teardown, 1 = enable auto teardown
            val autoDisableVal = if (enabled) 0 else 1
            val direct = runCatching {
                Settings.System.putInt(context.contentResolver, "test_wifi_slave_auto_disable", autoDisableVal)
            }.getOrDefault(false)
            if (!direct) {
                executeCommand("settings put system test_wifi_slave_auto_disable $autoDisableVal")
            }

            // 3. Ask the running module to install (or stop installing) the Xiaomi dual-Wi-Fi hooks right away.
            runCatching {
                context.sendBroadcast(Intent(ModuleStateParser.ACTION_KEEPALIVE_CHANGED))
            }
            true
        }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        executeShellWithOutput("id", timeoutSeconds = 3).firstOrNull()?.contains("uid=0") == true
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isMobileDataAlwaysOn(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1
    } catch (_: Exception) {
        false
    }

    suspend fun enableMobileDataAlwaysOn(context: Context): Boolean = withContext(Dispatchers.IO) {
        val direct = runCatching {
            Settings.Global.putInt(context.contentResolver, "mobile_data_always_on", 1)
        }.getOrDefault(false)
        if (direct) return@withContext true
        executeCommand("settings put global mobile_data_always_on 1")
    }

    suspend fun disableMobileDataAlwaysOn(context: Context): Boolean = withContext(Dispatchers.IO) {
        val direct = runCatching {
            Settings.Global.putInt(context.contentResolver, "mobile_data_always_on", 0)
        }.getOrDefault(false)
        if (direct) return@withContext true
        executeCommand("settings put global mobile_data_always_on 0")
    }

    fun getInterfacesWithDefaultRoute(candidateIfaces: Collection<String>): Set<String> =
        routeSync.getInterfacesWithDefaultRoute(candidateIfaces)

    suspend fun filterInterfacesWithInternet(
        context: Context,
        candidateIfaces: Collection<String>
    ): Set<String> = routeSync.filterInterfacesWithInternet(context, candidateIfaces)

    fun getConnectedSubnetPrefixes(context: Context, ifaces: Collection<String>): Map<String, List<String>> =
        routeSync.getConnectedSubnetPrefixes(context, ifaces)

    fun getChannelResolvers(context: Context, ifaces: Collection<String>): Map<String, List<String>> =
        routeSync.getChannelResolvers(context, ifaces)

    fun updateBootRestoreScript(applyScript: String, interfaces: Collection<String>): Boolean =
        routeSync.updateBootRestoreScript(applyScript, interfaces)

    fun listSecondaryUserInstalls(): List<RouteRuleBuilder.SecondaryUserInstalls> =
        routeSync.listSecondaryUserInstalls()

    fun resolveUidForRuleKey(
        context: Context,
        ruleKey: String,
        secondaryInstalls: List<RouteRuleBuilder.SecondaryUserInstalls>? = null
    ): Int = routeSync.resolveUidForRuleKey(context, ruleKey, secondaryInstalls)

    fun writeTextAsRoot(context: Context, path: String, content: String): Boolean =
        shell.writeTextAsRoot(context, path, content)

    fun readTextAsRoot(path: String): String? =
        shell.readTextAsRoot(path)

    suspend fun syncAllRouteRules(context: Context): Boolean =
        routeSync.syncAllRouteRules(context)

    @Keep
    @JvmStatic
    fun isModuleActiveInLSPosed(): Boolean = false

    fun moduleStatusLabel(context: Context, status: ModuleStatus): String =
        diagnostics.moduleStatusLabel(context, status)

    fun getModuleState(context: Context): ModuleState =
        diagnostics.getModuleState(context)

    suspend fun getDiagnosticInfo(context: Context): DiagnosticInfo =
        diagnostics.getDiagnosticInfo(context)

    fun scenarioReasonText(
        context: Context,
        resolution: ScenarioResolution
    ): String = diagnostics.scenarioReasonText(context, resolution)

    suspend fun getDiagnosticLogs(context: Context): String =
        diagnostics.getDiagnosticLogs(context)

    /** Executes a root command using the pooled persistent shell. */
    fun executeCommand(cmd: String, timeoutSeconds: Long = 5, sensitive: Boolean = false): Boolean =
        shell.executeCommand(cmd, timeoutSeconds, sensitive)

    /** Executes a shell command as root and returns its combined stdout/stderr lines. */
    fun executeShellWithOutput(
        cmd: String,
        timeoutSeconds: Long = 5,
        globalMountNamespace: Boolean = false
    ): List<String> = shell.executeShellWithOutput(cmd, timeoutSeconds, globalMountNamespace)

    fun setHotspotUpstream(target: String): Boolean =
        hotspot.setHotspotUpstream(target)

    fun getHotspotUpstream(): String =
        hotspot.getHotspotUpstream()

    fun getHotspotActiveUpstream(): String =
        hotspot.getHotspotActiveUpstream()

    fun getHotspotHookStatus(): HotspotHookState =
        hotspot.getHotspotHookStatus()
}

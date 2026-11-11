package com.multiroute.hook

import android.util.Log
import com.multiroute.BuildConfig
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern LibXposed module entry point for MultiRoute.
 * Coordinates dynamic heterogeneous network routing in system_server and upstream overrides
 * in NetworkStack / Tethering.
 *
 * Domain responsibilities are delegated to cohesive hook handlers:
 * - [ConnectivityRoutingHookHandler]: ConnectivityService dynamic per-UID & cellular SIM hooks
 * - [XiaomiDualWifiHookHandler]: Xiaomi / HyperOS dual-Wi-Fi keep-alive & in-process control
 * - [TetheringUpstreamHookHandler]: NetworkStack / Tethering UpstreamNetworkMonitor overrides
 * - [BootRestoreWatchdog]: Boot-time rule restore, scenario link watcher & runtime command IPC
 * - [ModuleBeaconPublisher]: Multi-channel module activation & version-skew beacon publishing
 */
class MultiRouteModule : XposedModule() {

    private val beaconPublisher: ModuleBeaconPublisher by lazy {
        ModuleBeaconPublisher(
            xposed = this,
            connectivityHookCount = { connectivityHandler.hookCount },
            slaveWifiHookCount = { dualWifiHandler.slaveWifiHookCount },
            dualStaHookCount = { dualWifiHandler.dualStaHookCount }
        )
    }

    private val connectivityHandler: ConnectivityRoutingHookHandler by lazy {
        ConnectivityRoutingHookHandler(
            xposed = this,
            onHooksInstalled = { source -> beaconPublisher.publish(source, "hooks") },
            onConnectivityClassResolved = { loader -> vpnUidHook.install(loader) }
        )
    }

    private val vpnUidHook by lazy { VpnUidRoutingHook(this) }

    private val dualWifiHandler: XiaomiDualWifiHookHandler by lazy {
        XiaomiDualWifiHookHandler(
            xposed = this,
            onHooksInstalled = { source -> beaconPublisher.publish(source, "hooks") }
        )
    }

    private val tetheringHandler: TetheringUpstreamHookHandler by lazy {
        TetheringUpstreamHookHandler(xposed = this)
    }

    private val bootWatchdog: BootRestoreWatchdog by lazy {
        BootRestoreWatchdog(
            xposed = this,
            onPublishReady = { beaconPublisher.publish("bootRestoreWatchdog", "ready") },
            onRefreshCellularDefaults = { connectivityHandler.refreshCellularDefaults() },
            onRefreshVpnUids = { vpnUidHook.refresh() },
            onKeepAliveChanged = { dualWifiHandler.onKeepAliveChanged() },
            onConnectSlaveWifi = { netId, bssid -> dualWifiHandler.connectSlaveWifi(netId, bssid) },
            onDisconnectSlaveWifi = { dualWifiHandler.disconnectSlaveWifi() }
        )
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(
            Log.INFO,
            HOOK_TAG,
            "MultiRoute onModuleLoaded: process=${param.processName}, isSystemServer=${param.isSystemServer}"
        )
        if (param.isSystemServer) {
            beaconPublisher.publish("onModuleLoaded(system)", "loaded")
        }
        if (param.processName == BuildConfig.APPLICATION_ID) {
            hookSelfModuleActive(null, "onModuleLoaded")
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName == BuildConfig.APPLICATION_ID) {
            hookSelfModuleActive(param.defaultClassLoader, "onPackageLoaded")
        } else if (param.packageName in HookPolicyHelpers.TETHERING_PACKAGES) {
            log(Log.INFO, HOOK_TAG, "[TetheringHook] NetworkStack package detected in onPackageLoaded: ${param.packageName}")
            tetheringHandler.install(param.defaultClassLoader)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        val classLoader = param.classLoader
        log(Log.INFO, HOOK_TAG, "System server starting up, preparing Dynamic Multi-Channel hooks...")

        // 1. Mark module active immediately at system_server startup
        beaconPublisher.publish("onSystemServerStarting", "loaded")

        // 2. Hook secondary Wi-Fi screen-off keepalive with multi-stage resolution
        dualWifiHandler.installAtStartup(classLoader)

        // 3. Resolve and hook ConnectivityService directly or via SystemServiceManager interception
        connectivityHandler.resolveDirectOrServiceManager(classLoader)
        if (!connectivityHandler.isHooked || !dualWifiHandler.isFullyHooked) {
            installSystemServiceStartHook(classLoader)
        }

        // 4. Start system-level boot restoration watchdog to ensure rules restore without OEM broadcast blocking
        bootWatchdog.schedule()
    }

    private fun hookSelfModuleActive(classLoader: ClassLoader?, source: String) {
        try {
            val helperClass = if (classLoader != null) {
                Class.forName("com.multiroute.util.SuHelper", false, classLoader)
            } else {
                Class.forName("com.multiroute.util.SuHelper")
            }
            val method = helperClass.getDeclaredMethod("isModuleActiveInLSPosed")
            deoptimize(method)
            hook(method).intercept { true }
            log(Log.INFO, HOOK_TAG, "Successfully hooked isModuleActiveInLSPosed for MultiRoute app in $source")
        } catch (t: Throwable) {
            log(Log.ERROR, HOOK_TAG, "Failed to hook isModuleActiveInLSPosed in $source: ${t.message}", t)
        }
    }

    /**
     * Intercepts SystemServiceManager.startService() when APEX service-connectivity.jar or
     * Wi-Fi services are loaded after system_server startup.
     */
    private fun installSystemServiceStartHook(classLoader: ClassLoader) {
        try {
            val ssmClass = Class.forName("com.android.server.SystemServiceManager", false, classLoader)
            val startMethods = ssmClass.declaredMethods.filter { it.name == "startService" }
            for (m in startMethods) {
                m.isAccessible = true
                deoptimize(m)
                hook(m).intercept { chain ->
                    val result = chain.proceed()
                    val arg = chain.args.firstOrNull()
                    val serviceName = when (arg) {
                        is Class<*> -> arg.name
                        is String -> arg
                        else -> result?.javaClass?.name ?: ""
                    }
                    val targetLoader = when (arg) {
                        is Class<*> -> arg.classLoader
                        else -> result?.javaClass?.classLoader
                    }
                    if (targetLoader != null) {
                        connectivityHandler.onSystemServiceStarted(serviceName, targetLoader)
                        dualWifiHandler.onSystemServiceStarted(serviceName, targetLoader)
                    }
                    result
                }
            }
            log(Log.INFO, HOOK_TAG, "Successfully hooked SystemServiceManager.startService for APEX service interception")
        } catch (t: Throwable) {
            log(Log.WARN, HOOK_TAG, "Failed to hook SystemServiceManager: ${t.message}", t)
        }
    }
}

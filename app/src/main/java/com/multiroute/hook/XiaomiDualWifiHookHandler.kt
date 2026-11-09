package com.multiroute.hook

import android.util.Log
import com.multiroute.wifi.SlaveWifiBridge
import dalvik.system.PathClassLoader
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs and manages Xiaomi / HyperOS dual-Wi-Fi (SlaveWifiService / DualStaImpl) screen-off
 * keep-alive hooks and in-process secondary Wi-Fi connect/disconnect actions inside system_server.
 */
internal class XiaomiDualWifiHookHandler(
    private val xposed: XposedInterface,
    private val onHooksInstalled: (source: String) -> Unit
) {
    private val isSlaveWifiHooked = AtomicBoolean(false)
    private val isDualStaHooked = AtomicBoolean(false)

    /** Keeps the "not installed because the keep-alive is off" note to one line per boot. */
    private val keepAliveSkippedLogged = AtomicBoolean(false)

    /**
     * ClassLoader the Wi-Fi service was seen in (it lives in its own APEX/module loader, not in the one
     * this module was loaded with). Recorded during the boot-time service interception so the
     * keep-alive hooks can also be installed later, without a reboot.
     */
    @Volatile
    private var wifiServiceClassLoader: ClassLoader? = null

    @Volatile
    var slaveWifiHookCount: Int = 0
        private set

    @Volatile
    var dualStaHookCount: Int = 0
        private set

    val isFullyHooked: Boolean
        get() = isSlaveWifiHooked.get() && isDualStaHooked.get()

    fun installAtStartup(classLoader: ClassLoader) {
        xposed.log(Log.INFO, HOOK_TAG, "Setting up Dual STA / Secondary Wi-Fi screen-off prevention hooks...")

        // 1. Check if SlaveWifiService is already registered in ServiceManager
        tryHookFromServiceManager(classLoader)

        // 2. Hook android.os.ServiceManager.addService to capture runtime SlaveWifiService registration
        try {
            val smClass = Class.forName("android.os.ServiceManager", false, classLoader)
            val addMethods = smClass.declaredMethods.filter { it.name == "addService" }
            for (m in addMethods) {
                m.isAccessible = true
                xposed.deoptimize(m)
                xposed.hook(m).intercept { chain ->
                    val name = chain.args.firstOrNull() as? String
                    val binder = chain.args.getOrNull(1)
                    if (HookPolicyHelpers.isSlaveWifiBinderName(name)) {
                        xposed.log(Log.INFO, HOOK_TAG, "ServiceManager.addService intercepted for $name: ${binder?.javaClass?.name}")
                        binder?.javaClass?.classLoader?.let { loader ->
                            hookSlaveWifiFromClassLoader(loader)
                        }
                    }
                    chain.proceed()
                }
            }
            xposed.log(Log.INFO, HOOK_TAG, "Successfully hooked ServiceManager.addService for late Wi-Fi service detection")
        } catch (t: Throwable) {
            xposed.log(Log.WARN, HOOK_TAG, "Could not hook ServiceManager.addService: ${t.message}")
        }

        // 3. Background polling watchdog: check ServiceManager.checkService("SlaveWifiService") periodically
        Thread {
            for (i in 1..15) {
                if (isFullyHooked) break
                try {
                    Thread.sleep(2000)
                    tryHookFromServiceManager(classLoader)
                } catch (_: Throwable) {}
            }
        }.apply {
            isDaemon = true
            name = "MultiRoute-SlaveWifiWatchdog"
            start()
        }
    }

    /** Strategy C callback when SystemServiceManager.startService() loads a Wi-Fi / DualSta service. */
    fun onSystemServiceStarted(serviceName: String, targetLoader: ClassLoader) {
        if (isFullyHooked || !HookPolicyHelpers.isWifiServiceName(serviceName)) return
        xposed.log(
            Log.INFO,
            HOOK_TAG,
            "SystemServiceManager matched Wi-Fi service ($serviceName), checking dual Wi-Fi keepalive hooks..."
        )
        hookSlaveWifiFromClassLoader(targetLoader)
    }

    fun onKeepAliveChanged() {
        if (!isKeepSlaveWifiScreenOff()) {
            xposed.log(Log.INFO, HOOK_TAG, "[KeepAlive] Switch is off; no hooks installed.")
            return
        }
        // Never hook on the system_server main thread.
        Thread {
            xposed.log(Log.INFO, HOOK_TAG, "[KeepAlive] Switch is on; installing dual-Wi-Fi hooks now.")
            installSlaveWifiHooksOnDemand()
        }.apply {
            isDaemon = true
            name = "MultiRoute-KeepAliveHook"
            start()
        }
    }

    fun connectSlaveWifi(netId: Int, bssid: String?) {
        Thread {
            val binder = SlaveWifiBridge.getSlaveWifiBinder()
            if (binder != null) {
                xposed.log(Log.INFO, HOOK_TAG, "[SlaveWifi] Connecting secondary Wi-Fi in system_server: netId=$netId bssid=$bssid")
                SlaveWifiBridge.setWifiSlaveEnabled(binder, "android", true)
                val ok = SlaveWifiBridge.connectToSlaveAp(binder, netId, bssid)
                xposed.log(Log.INFO, HOOK_TAG, "[SlaveWifi] connectToSlaveAp returned $ok")
            } else {
                xposed.log(Log.WARN, HOOK_TAG, "[SlaveWifi] SlaveWifiService binder is null in system_server")
            }
        }.apply {
            isDaemon = true
            name = "MultiRoute-ConnectSlaveWifi"
            start()
        }
    }

    fun disconnectSlaveWifi() {
        Thread {
            val binder = SlaveWifiBridge.getSlaveWifiBinder()
            if (binder != null) {
                xposed.log(Log.INFO, HOOK_TAG, "[SlaveWifi] Disconnecting secondary Wi-Fi in system_server")
                val ok = SlaveWifiBridge.disconnectSlaveWifi(binder)
                xposed.log(Log.INFO, HOOK_TAG, "[SlaveWifi] disconnectSlaveWifi returned $ok")
            }
        }.apply {
            isDaemon = true
            name = "MultiRoute-DisconnectSlaveWifi"
            start()
        }
    }

    private fun tryHookFromServiceManager(classLoader: ClassLoader) {
        if (isFullyHooked) return
        try {
            val smClass = Class.forName("android.os.ServiceManager", false, classLoader)
            val checkMethod = smClass.getMethod("checkService", String::class.java)
            val binder = checkMethod.invoke(null, "SlaveWifiService")
            if (binder != null) {
                binder.javaClass.classLoader?.let { loader ->
                    hookSlaveWifiFromClassLoader(loader)
                }
            }
        } catch (_: Throwable) {}
    }

    /** On-demand installation: use the loader the Wi-Fi service was seen in, then fall back and give up. */
    private fun installSlaveWifiHooksOnDemand() {
        if (isFullyHooked) return
        val loaders = mutableListOf<ClassLoader>()
        wifiServiceClassLoader?.let { loaders.add(it) }
        runCatching { XiaomiDualWifiHookHandler::class.java.classLoader }.getOrNull()?.let { loaders.add(it) }
        runCatching { ClassLoader.getSystemClassLoader() }.getOrNull()?.let { loaders.add(it) }
        for (loader in loaders) {
            hookSlaveWifiFromClassLoader(loader)
            if (isFullyHooked) return
        }
        xposed.log(
            Log.WARN,
            HOOK_TAG,
            "[KeepAlive] Wi-Fi classes not resolvable yet; they will be hooked when the service loads"
        )
    }

    private fun hookSlaveWifiFromClassLoader(loader: ClassLoader) {
        // Remember the loader even while the feature is off: it is the only handle on the Wi-Fi service's
        // own classloader, and the on-demand path needs it when the switch is turned on later.
        wifiServiceClassLoader = loader

        // Not installed while the screen-off keep-alive is off: the hooks would only pass calls through, so
        // leaving them out keeps MultiRoute entirely out of the ROM's Wi-Fi code (and off ROMs that do not
        // have these Xiaomi classes at all).
        if (!isKeepSlaveWifiScreenOff()) {
            if (keepAliveSkippedLogged.compareAndSet(false, true)) {
                xposed.log(Log.INFO, HOOK_TAG, "Xiaomi dual-Wi-Fi hooks not installed: screen-off keep-alive is off")
            }
            return
        }
        // Resolve ClassLoader candidates (direct loader, and chained with miui-wifi-service.jar)
        val candidateLoaders = mutableListOf(loader)
        try {
            val jar = File("/system_ext/framework/miui-wifi-service.jar")
            if (jar.exists()) {
                candidateLoaders.add(PathClassLoader(jar.absolutePath, loader))
            }
        } catch (_: Throwable) {}

        for (cl in candidateLoaders) {
            // A. Resolve SlaveWifiService
            if (!isSlaveWifiHooked.get()) {
                try {
                    val slaveClass = Class.forName("com.android.server.wifi.SlaveWifiService", false, cl)
                    hookSlaveWifiService(slaveClass)
                } catch (_: ClassNotFoundException) {
                } catch (t: Throwable) {
                    xposed.log(Log.WARN, HOOK_TAG, "Error resolving SlaveWifiService: ${t.message}")
                }
            }

            // B. Resolve DualStaImpl
            if (!isDualStaHooked.get()) {
                try {
                    val implClass = Class.forName("com.android.server.wifi.DualStaImpl", false, cl)
                    hookDualStaImpl(implClass)
                } catch (_: ClassNotFoundException) {
                } catch (t: Throwable) {
                    xposed.log(Log.WARN, HOOK_TAG, "Error resolving DualStaImpl: ${t.message}")
                }
            }
        }
    }

    private fun hookSlaveWifiService(serviceClass: Class<*>) {
        if (isSlaveWifiHooked.get()) return
        xposed.log(Log.INFO, HOOK_TAG, "Installing keepalive hooks on ${serviceClass.name}...")

        var hookedMethods = 0

        // 1. Hook tryToDisableSlaveWifi() - Primary auto-disable routine triggered by screen-off timer
        val tryDisableMethod = serviceClass.declaredMethods.firstOrNull {
            it.name == "tryToDisableSlaveWifi" && it.parameterTypes.isEmpty()
        }
        if (tryDisableMethod != null) {
            try {
                tryDisableMethod.isAccessible = true
                xposed.deoptimize(tryDisableMethod)
                xposed.hook(tryDisableMethod).intercept { chain ->
                    if (isKeepSlaveWifiScreenOff()) {
                        xposed.log(Log.INFO, HOOK_TAG, "Blocked SlaveWifiService.tryToDisableSlaveWifi()! Secondary Wi-Fi kept alive.")
                        return@intercept null
                    }
                    chain.proceed()
                }
                hookedMethods++
                xposed.log(Log.INFO, HOOK_TAG, "Successfully hooked SlaveWifiService.tryToDisableSlaveWifi")
            } catch (t: Throwable) {
                xposed.log(Log.ERROR, HOOK_TAG, "Failed to hook SlaveWifiService.tryToDisableSlaveWifi: ${t.message}", t)
            }
        }

        // 2. Hook scheduleAutoDisableTimer(int) - Prevents scheduling screen-off alarm
        val scheduleTimerMethod = serviceClass.declaredMethods.firstOrNull {
            it.name == "scheduleAutoDisableTimer" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (scheduleTimerMethod != null) {
            try {
                scheduleTimerMethod.isAccessible = true
                xposed.deoptimize(scheduleTimerMethod)
                xposed.hook(scheduleTimerMethod).intercept { chain ->
                    if (isKeepSlaveWifiScreenOff()) {
                        xposed.log(Log.INFO, HOOK_TAG, "Suppressed SlaveWifiService.scheduleAutoDisableTimer()!")
                        return@intercept null
                    }
                    chain.proceed()
                }
                hookedMethods++
                xposed.log(Log.INFO, HOOK_TAG, "Successfully hooked SlaveWifiService.scheduleAutoDisableTimer")
            } catch (t: Throwable) {
                xposed.log(Log.ERROR, HOOK_TAG, "Failed to hook SlaveWifiService.scheduleAutoDisableTimer: ${t.message}", t)
            }
        }

        // 3. Hook setWifiSlaveEnabled overloads
        hookedMethods += hookSetWifiSlaveEnabledOverloads(serviceClass, "SlaveWifiService")

        if (hookedMethods > 0) {
            isSlaveWifiHooked.set(true)
            slaveWifiHookCount = hookedMethods
            xposed.log(Log.INFO, HOOK_TAG, "SlaveWifiService keepalive hooks installed successfully ($hookedMethods methods hooked)")
            onHooksInstalled("hookSlaveWifiService")
        } else {
            xposed.log(Log.WARN, HOOK_TAG, "SlaveWifiService: 0 methods hooked, will retry on subsequent loader events")
        }
    }

    private fun hookDualStaImpl(implClass: Class<*>) {
        if (isDualStaHooked.get()) return
        xposed.log(Log.INFO, HOOK_TAG, "Installing keepalive hooks on ${implClass.name}...")

        val hookedMethods = hookSetWifiSlaveEnabledOverloads(implClass, "DualStaImpl")

        if (hookedMethods > 0) {
            // Latch only on real success so a failed attempt can be retried on the next loader event,
            // and so the published beacon never claims hooks that were not installed.
            isDualStaHooked.set(true)
            dualStaHookCount = hookedMethods
            xposed.log(Log.INFO, HOOK_TAG, "DualStaImpl keepalive hooks installed successfully ($hookedMethods methods hooked)")
            onHooksInstalled("hookDualStaImpl")
        } else {
            xposed.log(Log.WARN, HOOK_TAG, "DualStaImpl: 0 methods hooked, will retry on subsequent loader events")
        }
    }

    private fun hookSetWifiSlaveEnabledOverloads(targetClass: Class<*>, label: String): Int {
        var count = 0
        val setMethods = targetClass.declaredMethods.filter { it.name == "setWifiSlaveEnabled" }
        for (m in setMethods) {
            try {
                m.isAccessible = true
                xposed.deoptimize(m)
                xposed.hook(m).intercept { chain ->
                    val enable = chain.args.lastOrNull() as? Boolean ?: true
                    if (!enable && isKeepSlaveWifiScreenOff() && !SystemServerEnv.isScreenInteractive()) {
                        xposed.log(Log.INFO, HOOK_TAG, "Intercepted $label.setWifiSlaveEnabled(false) during screen-off; blocking teardown.")
                        return@intercept false
                    }
                    chain.proceed()
                }
                count++
                xposed.log(Log.INFO, HOOK_TAG, "Successfully hooked $label.setWifiSlaveEnabled(${m.parameterTypes.joinToString { it.simpleName }})")
            } catch (t: Throwable) {
                xposed.log(Log.ERROR, HOOK_TAG, "Failed to hook $label.setWifiSlaveEnabled: ${t.message}", t)
            }
        }
        return count
    }

    private fun isKeepSlaveWifiScreenOff(): Boolean {
        val transient = SystemServerEnv.readSystemProperty("sys.multiroute.keep_slave_wifi")
        val persistent = SystemServerEnv.readSystemProperty("persist.multiroute.keep_slave_wifi")
        val prefs = runCatching { xposed.getRemotePreferences(PREF_NAME) }.getOrNull()
        val hasPref = prefs?.contains("keep_slave_wifi_screen_off") == true
        val prefValue = if (hasPref) prefs.getBoolean("keep_slave_wifi_screen_off", false) else false
        return HookPolicyHelpers.resolveKeepAlivePreference(transient, persistent, hasPref, prefValue)
    }
}

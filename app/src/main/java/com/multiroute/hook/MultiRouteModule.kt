package com.multiroute.hook

import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.util.SparseArray
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field

/**
 * Modern LibXposed module implementation for MultiRoute.
 * Dynamic heterogeneous network routing in system_server.
 *
 * Supports arbitrary network channels (wlan0, wlan1, rmnet_data*, eth*, tun*, etc.)
 * with zero bytecode injection into target user applications.
 */
class MultiRouteModule : XposedModule() {

    companion object {
        private const val TAG = "MultiRoute-SystemServer"
        private const val PREF_NAME = "multiroute_rules"
        private const val CHANNEL_DEFAULT = "default"

        private val CS_CLASS_CANDIDATES = listOf(
            "android.net.connectivity.com.android.server.ConnectivityService",
            "com.android.server.ConnectivityService"
        )
    }

    private var isConnectivityHooked = false

    private fun markActive(source: String) {
        try {
            val pid = android.os.Process.myPid()
            val markerFile = java.io.File("/data/system/multiroute_active")
            markerFile.writeText("$pid:${System.currentTimeMillis()}")
            markerFile.setReadable(true, false)
            markerFile.setWritable(true, false)
            log(Log.INFO, TAG, "[$source] Marked /data/system/multiroute_active with PID $pid")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "[$source] Could not write /data/system/multiroute_active: ${t.message}")
        }

        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val setMethod = spClass.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, "sys.multiroute.active", "1")
            log(Log.INFO, TAG, "[$source] Marked sys.multiroute.active = 1")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "[$source] Could not set sys.multiroute.active: ${t.message}")
        }
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(
            Log.INFO,
            TAG,
            "MultiRoute onModuleLoaded: process=${param.processName}, isSystemServer=${param.isSystemServer}"
        )
        if (param.isSystemServer) {
            markActive("onModuleLoaded(system)")
        }
        if (param.processName == "com.multiroute") {
            try {
                val helperClass = Class.forName("com.multiroute.util.SuHelper")
                val method = helperClass.getDeclaredMethod("isModuleActiveInLSPosed")
                deoptimize(method)
                hook(method).intercept { true }
                log(Log.INFO, TAG, "Successfully hooked isModuleActiveInLSPosed for MultiRoute app in onModuleLoaded")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook isModuleActiveInLSPosed: ${t.message}", t)
            }
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName == "com.multiroute") {
            try {
                val cl = param.defaultClassLoader
                val helperClass = Class.forName("com.multiroute.util.SuHelper", false, cl)
                val method = helperClass.getDeclaredMethod("isModuleActiveInLSPosed")
                deoptimize(method)
                hook(method).intercept { true }
                log(Log.INFO, TAG, "Successfully hooked isModuleActiveInLSPosed for MultiRoute app in onPackageLoaded")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook isModuleActiveInLSPosed in onPackageLoaded: ${t.message}", t)
            }
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        val classLoader = param.classLoader
        log(Log.INFO, TAG, "System server starting up, preparing Dynamic Multi-Channel hooks...")

        // 1. Mark module active immediately at system_server startup
        markActive("onSystemServerStarting")

        // 2. Hook secondary Wi-Fi screen-off keepalive in services.jar/framework
        hookDualWifiScreenOff(classLoader)

        // 3. Resolve and hook ConnectivityService across Android 14/15 APEX and base framework
        resolveAndHookConnectivityService(classLoader)
    }

    private fun resolveAndHookConnectivityService(classLoader: ClassLoader) {
        if (isConnectivityHooked) return

        // Strategy A: Direct classloader lookup
        for (className in CS_CLASS_CANDIDATES) {
            try {
                val csClass = Class.forName(className, false, classLoader)
                log(Log.INFO, TAG, "Found ConnectivityService in base classloader: $className")
                hookConnectivityService(csClass)
                return
            } catch (_: ClassNotFoundException) {
            }
        }

        // Strategy B: If connectivity binder service is already available in ServiceManager
        try {
            val smClass = Class.forName("android.os.ServiceManager", false, classLoader)
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "connectivity")
            if (binder != null && !binder.javaClass.name.contains("BinderProxy") && binder.javaClass.name.contains("ConnectivityService")) {
                val csClass = binder.javaClass
                log(Log.INFO, TAG, "Resolved ConnectivityService via ServiceManager: ${csClass.name}")
                hookConnectivityService(csClass)
                if (isConnectivityHooked) return
            }
        } catch (_: Throwable) {
        }

        // Strategy C: Intercept SystemServiceManager.startService() when APEX service-connectivity.jar is loaded
        try {
            val ssmClass = Class.forName("com.android.server.SystemServiceManager", false, classLoader)
            val startMethods = ssmClass.declaredMethods.filter { it.name == "startService" }
            for (m in startMethods) {
                m.isAccessible = true
                deoptimize(m)
                hook(m).intercept { chain ->
                    val result = chain.proceed()
                    if (!isConnectivityHooked) {
                        val arg = chain.args.firstOrNull()
                        val serviceName = when (arg) {
                            is Class<*> -> arg.name
                            is String -> arg
                            else -> result?.javaClass?.name ?: ""
                        }
                        if (serviceName.contains("ConnectivityService")) {
                            val targetLoader = when (arg) {
                                is Class<*> -> arg.classLoader
                                else -> result?.javaClass?.classLoader
                            }
                            if (targetLoader != null) {
                                for (candidate in CS_CLASS_CANDIDATES) {
                                    try {
                                        val csClass = Class.forName(candidate, false, targetLoader)
                                        log(Log.INFO, TAG, "Resolved ConnectivityService via SystemServiceManager ($serviceName): ${csClass.name}")
                                        hookConnectivityService(csClass)
                                        break
                                    } catch (_: ClassNotFoundException) {
                                    }
                                }
                            }
                        }
                    }
                    result
                }
            }
            log(Log.INFO, TAG, "Successfully hooked SystemServiceManager.startService for APEX ConnectivityService interception")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Failed to hook SystemServiceManager: ${t.message}", t)
        }
    }

    private fun hookConnectivityService(csClass: Class<*>) {
        if (isConnectivityHooked) return
        if (csClass.name.contains("BinderProxy")) return
        log(Log.INFO, TAG, "Installing dynamic multi-route hooks on ${csClass.name}...")

        var hookedCount = 0
        if (hookGetMobileDataPreferredUids(csClass)) hookedCount++
        if (hookGetDefaultNetworkForUid(csClass)) hookedCount++
        if (hookGetActiveNetworkForUidInternal(csClass)) hookedCount++

        if (hookedCount > 0) {
            isConnectivityHooked = true
            log(Log.INFO, TAG, "ConnectivityService dynamic hooks installed successfully ($hookedCount hooked)")
        } else {
            log(Log.WARN, TAG, "No methods could be hooked on ${csClass.name}")
        }
    }

    private fun hookGetMobileDataPreferredUids(csClass: Class<*>): Boolean {
        val method = csClass.declaredMethods.firstOrNull {
            it.name == "getMobileDataPreferredUids" && it.parameterTypes.isEmpty()
        } ?: return false

        return try {
            method.isAccessible = true
            deoptimize(method)
            hook(method).intercept { chain ->
                @Suppress("UNCHECKED_CAST")
                val originalSet = (chain.proceed() as? Set<Int>)?.toMutableSet() ?: mutableSetOf()

                val cellularUids = getCellularConfiguredUids()
                if (cellularUids.isNotEmpty()) {
                    originalSet.addAll(cellularUids)
                }

                originalSet
            }
            log(Log.INFO, TAG, "Successfully hooked getMobileDataPreferredUids")
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook getMobileDataPreferredUids: ${t.message}", t)
            false
        }
    }

    private fun hookGetDefaultNetworkForUid(csClass: Class<*>): Boolean {
        val method = csClass.declaredMethods.firstOrNull {
            it.name == "getDefaultNetworkForUid" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return false

        return try {
            method.isAccessible = true
            deoptimize(method)
            hook(method).intercept { chain ->
                val uid = chain.getArg(0) as Int
                val targetChannel = getTargetChannelForUid(uid)

                if (targetChannel != null && targetChannel != CHANNEL_DEFAULT) {
                    val csInstance = chain.thisObject
                    val targetAgent = findNetworkAgentForChannel(csClass, csInstance, targetChannel)
                    if (targetAgent != null) {
                        return@intercept targetAgent
                    }
                }

                chain.proceed()
            }
            log(Log.INFO, TAG, "Successfully hooked getDefaultNetworkForUid (Dynamic)")
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook getDefaultNetworkForUid: ${t.message}", t)
            false
        }
    }

    private fun hookGetActiveNetworkForUidInternal(csClass: Class<*>): Boolean {
        val method = csClass.declaredMethods.firstOrNull {
            it.name == "getActiveNetworkForUidInternal" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return false

        return try {
            method.isAccessible = true
            deoptimize(method)
            hook(method).intercept { chain ->
                val uid = chain.getArg(0) as Int
                val targetChannel = getTargetChannelForUid(uid)

                if (targetChannel != null && targetChannel != CHANNEL_DEFAULT) {
                    val csInstance = chain.thisObject
                    val targetAgent = findNetworkAgentForChannel(csClass, csInstance, targetChannel)
                    if (targetAgent != null) {
                        val networkField = findNetworkField(targetAgent.javaClass)
                        val network = networkField?.get(targetAgent) as? Network
                        if (network != null) {
                            return@intercept network
                        }
                    }
                }

                chain.proceed()
            }
            log(Log.INFO, TAG, "Successfully hooked getActiveNetworkForUidInternal (Dynamic)")
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook getActiveNetworkForUidInternal: ${t.message}", t)
            false
        }
    }

    /**
     * Dynamically finds the NetworkAgentInfo whose interfaceName matches targetChannel (e.g. wlan0, wlan1, rmnet_data*, eth0).
     */
    private fun findNetworkAgentForChannel(csClass: Class<*>, csInstance: Any, targetChannel: String): Any? {
        val networkMapField = csClass.declaredFields.firstOrNull {
            it.name == "mNetworkForNetId" || it.type == SparseArray::class.java
        } ?: return null

        networkMapField.isAccessible = true
        val sparseArray = networkMapField.get(csInstance) as? SparseArray<*> ?: return null

        for (i in 0 until sparseArray.size()) {
            val nai = sparseArray.valueAt(i) ?: continue
            val naiClass = nai.javaClass

            val lpField = naiClass.declaredFields.firstOrNull { it.name == "linkProperties" }
            lpField?.isAccessible = true
            val lp = lpField?.get(nai) as? android.net.LinkProperties
            val iface = lp?.interfaceName ?: ""

            // Exact interface match (e.g. "wlan0", "wlan1", "rmnet_data3", "eth0")
            if (iface == targetChannel) {
                return nai
            }

            // Fallback for generic cellular iface matching
            if ((targetChannel.startsWith("rmnet") || targetChannel == "cellular") && iface.startsWith("rmnet")) {
                val ncField = naiClass.declaredFields.firstOrNull { it.name == "networkCapabilities" }
                ncField?.isAccessible = true
                val nc = ncField?.get(nai) as? NetworkCapabilities
                if (nc?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                    return nai
                }
            }
        }
        return null
    }

    private var cachedNetworkField: Field? = null
    private fun findNetworkField(naiClass: Class<*>): Field? {
        if (cachedNetworkField != null) return cachedNetworkField
        val field = naiClass.declaredFields.firstOrNull { it.type == Network::class.java || it.name == "network" }
        field?.isAccessible = true
        cachedNetworkField = field
        return field
    }

    private fun getTargetChannelForUid(uid: Int): String? {
        val prefs = runCatching { getRemotePreferences(PREF_NAME) }.getOrNull() ?: return null
        val channel = prefs.getString("uid_$uid", null) ?: prefs.getString(uid.toString(), null)
        return channel
    }

    private fun getCellularConfiguredUids(): Set<Int> {
        val prefs = runCatching { getRemotePreferences(PREF_NAME) }.getOrNull() ?: return emptySet()
        val uids = mutableSetOf<Int>()

        for ((key, value) in prefs.all) {
            val channelStr = value as? String ?: continue
            if (channelStr.startsWith("rmnet") || channelStr.startsWith("ccmni") || channelStr == "cellular") {
                if (key.startsWith("uid_")) {
                    key.removePrefix("uid_").toIntOrNull()?.let { uids.add(it) }
                } else {
                    key.toIntOrNull()?.let { uids.add(it) }
                }
            }
        }
        return uids
    }

    // =========================================================================
    // Dual STA / Secondary Wi-Fi (wlan1) Screen-Off Keep-Alive Hooks
    // =========================================================================

    private fun hookDualWifiScreenOff(classLoader: ClassLoader) {
        log(Log.INFO, TAG, "Setting up Dual STA / Secondary Wi-Fi screen-off prevention hooks...")

        // 1. Resolve and hook DualStaStub
        val dualStaStubClass = resolveClass("com.android.server.wifi.DualStaStub", classLoader)
        if (dualStaStubClass != null) {
            log(Log.INFO, TAG, "Found DualStaStub class: ${dualStaStubClass.name}")
            hookDualStaStub(dualStaStubClass, classLoader)
        } else {
            log(Log.WARN, TAG, "DualStaStub class not directly found in classLoader")
        }

        // 2. Resolve and hook SlaveWifiService
        val slaveWifiClass = resolveClass(
            "com.android.server.wifi.SlaveWifiService",
            classLoader,
            dualStaStubClass?.classLoader
        )
        if (slaveWifiClass != null) {
            log(Log.INFO, TAG, "Found SlaveWifiService class: ${slaveWifiClass.name}")
            hookSlaveWifiService(slaveWifiClass)
        } else {
            log(Log.WARN, TAG, "SlaveWifiService class not directly found in classLoader")
        }

        // 3. Resolve and hook DualStaImpl
        val dualStaImplClass = resolveClass(
            "com.android.server.wifi.DualStaImpl",
            classLoader,
            dualStaStubClass?.classLoader,
            slaveWifiClass?.classLoader
        )
        if (dualStaImplClass != null) {
            log(Log.INFO, TAG, "Found DualStaImpl class: ${dualStaImplClass.name}")
            hookDualStaImpl(dualStaImplClass)
        }
    }

    private fun hookDualStaStub(stubClass: Class<*>, classLoader: ClassLoader) {
        // A. Hook setWifiSlaveEnabled(boolean)
        val setEnabledMethod = stubClass.declaredMethods.firstOrNull {
            it.name == "setWifiSlaveEnabled" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (setEnabledMethod != null) {
            try {
                setEnabledMethod.isAccessible = true
                deoptimize(setEnabledMethod)
                hook(setEnabledMethod).intercept { chain ->
                    val enable = chain.getArg(0) as Boolean
                    if (!enable && isKeepSlaveWifiScreenOff()) {
                        if (!isScreenInteractive()) {
                            log(Log.INFO, TAG, "DualStaStub.setWifiSlaveEnabled(false) intercepted during screen-off; blocking teardown.")
                            return@intercept false
                        }
                    }
                    chain.proceed()
                }
                log(Log.INFO, TAG, "Successfully hooked DualStaStub.setWifiSlaveEnabled")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook DualStaStub.setWifiSlaveEnabled: ${t.message}", t)
            }
        }

        // B. Hook initSlaveWifiService to ensure late-initialized SlaveWifiService is also hooked
        val initMethod = stubClass.declaredMethods.firstOrNull {
            it.name == "initSlaveWifiService"
        }
        if (initMethod != null) {
            try {
                initMethod.isAccessible = true
                deoptimize(initMethod)
                hook(initMethod).intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val lateSlaveWifiClass = resolveClass(
                            "com.android.server.wifi.SlaveWifiService",
                            classLoader,
                            stubClass.classLoader
                        )
                        if (lateSlaveWifiClass != null) {
                            hookSlaveWifiService(lateSlaveWifiClass)
                        }
                        val lateDualStaImplClass = resolveClass(
                            "com.android.server.wifi.DualStaImpl",
                            classLoader,
                            stubClass.classLoader
                        )
                        if (lateDualStaImplClass != null) {
                            hookDualStaImpl(lateDualStaImplClass)
                        }
                    }
                    result
                }
                log(Log.INFO, TAG, "Successfully hooked DualStaStub.initSlaveWifiService")
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "Could not hook DualStaStub.initSlaveWifiService: ${t.message}")
            }
        }
    }

    @Volatile
    private var isSlaveWifiServiceHooked = false
    private fun hookSlaveWifiService(serviceClass: Class<*>) {
        if (isSlaveWifiServiceHooked) return
        isSlaveWifiServiceHooked = true

        // 1. Hook tryToDisableSlaveWifi() - Primary auto-disable routine triggered by screen-off timer
        val tryDisableMethod = serviceClass.declaredMethods.firstOrNull {
            it.name == "tryToDisableSlaveWifi" && it.parameterTypes.isEmpty()
        }
        if (tryDisableMethod != null) {
            try {
                tryDisableMethod.isAccessible = true
                deoptimize(tryDisableMethod)
                hook(tryDisableMethod).intercept { chain ->
                    if (isKeepSlaveWifiScreenOff()) {
                        log(Log.INFO, TAG, "Blocked SlaveWifiService.tryToDisableSlaveWifi()! Secondary Wi-Fi kept alive.")
                        return@intercept null
                    }
                    chain.proceed()
                }
                log(Log.INFO, TAG, "Successfully hooked SlaveWifiService.tryToDisableSlaveWifi")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook SlaveWifiService.tryToDisableSlaveWifi: ${t.message}", t)
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
                deoptimize(scheduleTimerMethod)
                hook(scheduleTimerMethod).intercept { chain ->
                    if (isKeepSlaveWifiScreenOff()) {
                        log(Log.INFO, TAG, "Suppressed SlaveWifiService.scheduleAutoDisableTimer()!")
                        return@intercept null
                    }
                    chain.proceed()
                }
                log(Log.INFO, TAG, "Successfully hooked SlaveWifiService.scheduleAutoDisableTimer")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook SlaveWifiService.scheduleAutoDisableTimer: ${t.message}", t)
            }
        }

        // 3. Hook setWifiSlaveEnabled(String, boolean)
        val setSlaveEnabledMethod = serviceClass.declaredMethods.firstOrNull {
            it.name == "setWifiSlaveEnabled" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (setSlaveEnabledMethod != null) {
            try {
                setSlaveEnabledMethod.isAccessible = true
                deoptimize(setSlaveEnabledMethod)
                hook(setSlaveEnabledMethod).intercept { chain ->
                    val enable = chain.getArg(1) as Boolean
                    if (!enable && isKeepSlaveWifiScreenOff()) {
                        if (!isScreenInteractive()) {
                            log(Log.INFO, TAG, "Intercepted SlaveWifiService.setWifiSlaveEnabled(false) during screen-off!")
                            return@intercept false
                        }
                    }
                    chain.proceed()
                }
                log(Log.INFO, TAG, "Successfully hooked SlaveWifiService.setWifiSlaveEnabled")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook SlaveWifiService.setWifiSlaveEnabled: ${t.message}", t)
            }
        }
    }

    @Volatile
    private var isDualStaImplHooked = false
    private fun hookDualStaImpl(implClass: Class<*>) {
        if (isDualStaImplHooked) return
        isDualStaImplHooked = true

        val setEnabledMethod = implClass.declaredMethods.firstOrNull {
            it.name == "setWifiSlaveEnabled" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (setEnabledMethod != null) {
            try {
                setEnabledMethod.isAccessible = true
                deoptimize(setEnabledMethod)
                hook(setEnabledMethod).intercept { chain ->
                    val enable = chain.getArg(0) as Boolean
                    if (!enable && isKeepSlaveWifiScreenOff()) {
                        if (!isScreenInteractive()) {
                            log(Log.INFO, TAG, "Intercepted DualStaImpl.setWifiSlaveEnabled(false) during screen-off!")
                            return@intercept false
                        }
                    }
                    chain.proceed()
                }
                log(Log.INFO, TAG, "Successfully hooked DualStaImpl.setWifiSlaveEnabled")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook DualStaImpl.setWifiSlaveEnabled: ${t.message}", t)
            }
        }
    }

    private fun resolveClass(className: String, vararg loaders: ClassLoader?): Class<*>? {
        for (loader in loaders) {
            if (loader == null) continue
            try {
                return Class.forName(className, false, loader)
            } catch (_: Throwable) {}
        }
        try {
            return Class.forName(className, false, Thread.currentThread().contextClassLoader)
        } catch (_: Throwable) {}
        try {
            return Class.forName(className, false, ClassLoader.getSystemClassLoader())
        } catch (_: Throwable) {}
        return null
    }

    private fun isKeepSlaveWifiScreenOff(): Boolean {
        // 1. Transient system property (immediate cross-process IPC)
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
            val prop = getMethod.invoke(null, "sys.multiroute.keep_slave_wifi", "") as String
            if (prop == "1") return true
            if (prop == "0") return false
        } catch (_: Throwable) {}

        // 2. SharedPreferences fallback via LibXposed RemotePreferences
        val prefs = runCatching { getRemotePreferences(PREF_NAME) }.getOrNull()
        if (prefs != null && prefs.contains("keep_slave_wifi_screen_off")) {
            return prefs.getBoolean("keep_slave_wifi_screen_off", false)
        }

        return false
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "power") as? android.os.IBinder ?: return true
            val ipmClass = Class.forName("android.os.IPowerManager\$Stub")
            val asInterfaceMethod = ipmClass.getMethod("asInterface", android.os.IBinder::class.java)
            val ipm = asInterfaceMethod.invoke(null, binder) ?: return true
            val isInteractiveMethod = ipm.javaClass.getMethod("isInteractive")
            isInteractiveMethod.invoke(ipm) as Boolean
        } catch (_: Throwable) {
            true
        }
    }
}

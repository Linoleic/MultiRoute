package com.multiroute.hook

import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.util.SparseArray
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val isSlaveWifiHooked = AtomicBoolean(false)
    private val isDualStaHooked = AtomicBoolean(false)

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

        // 2. Hook secondary Wi-Fi screen-off keepalive with multi-stage resolution
        hookDualWifiScreenOff(classLoader)

        // 3. Resolve and hook ConnectivityService across Android 14/15 APEX and base framework
        resolveAndHookConnectivityService(classLoader)

        // 4. Start system-level boot restoration watchdog to ensure rules restore without OEM broadcast blocking
        scheduleBootRestoreWatchdog()
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

        // Strategy C: Intercept SystemServiceManager.startService() when APEX service-connectivity.jar or Wi-Fi services are loaded
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

                    // A. Intercept ConnectivityService
                    if (!isConnectivityHooked && serviceName.contains("ConnectivityService") && targetLoader != null) {
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

                    // B. Intercept Wi-Fi / SlaveWifiService
                    if ((!isSlaveWifiHooked.get() || !isDualStaHooked.get()) && targetLoader != null) {
                        if (serviceName.contains("Wifi", ignoreCase = true) ||
                            serviceName.contains("Slave", ignoreCase = true) ||
                            serviceName.contains("DualSta", ignoreCase = true)
                        ) {
                            log(Log.INFO, TAG, "SystemServiceManager matched Wi-Fi service ($serviceName), checking dual Wi-Fi keepalive hooks...")
                            hookSlaveWifiFromClassLoader(targetLoader)
                        }
                    }

                    result
                }
            }
            log(Log.INFO, TAG, "Successfully hooked SystemServiceManager.startService for APEX service interception")
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
     * Traverses the class hierarchy to collect all declared fields including superclasses.
     */
    private fun getAllFields(clazz: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            fields.addAll(current.declaredFields)
            current = current.superclass
        }
        return fields
    }

    private val networkMapFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private fun findNetworkMapField(csClass: Class<*>): Field? {
        return networkMapFieldCache.getOrPut(csClass) {
            val all = getAllFields(csClass)
            all.firstOrNull { it.name == "mNetworkForNetId" }
                ?: all.firstOrNull {
                    it.type == SparseArray::class.java &&
                            (it.name.contains("Network", ignoreCase = true) || it.name.contains("NetId", ignoreCase = true))
                }
        }
    }

    private val linkPropertiesFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private fun findLinkPropertiesField(naiClass: Class<*>): Field? {
        return linkPropertiesFieldCache.getOrPut(naiClass) {
            getAllFields(naiClass).firstOrNull { it.name == "linkProperties" || it.type == android.net.LinkProperties::class.java }
        }
    }

    private val networkCapabilitiesFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private fun findNetworkCapabilitiesField(naiClass: Class<*>): Field? {
        return networkCapabilitiesFieldCache.getOrPut(naiClass) {
            getAllFields(naiClass).firstOrNull { it.name == "networkCapabilities" || it.type == NetworkCapabilities::class.java }
        }
    }

    private val networkFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private fun findNetworkField(naiClass: Class<*>): Field? {
        return networkFieldCache.getOrPut(naiClass) {
            getAllFields(naiClass).firstOrNull { it.type == Network::class.java || it.name == "network" }
        }
    }

    /**
     * Dynamically finds the NetworkAgentInfo whose interfaceName matches targetChannel (e.g. wlan0, wlan1, rmnet_data*, eth0).
     */
    private fun findNetworkAgentForChannel(csClass: Class<*>, csInstance: Any, targetChannel: String): Any? {
        val networkMapField = findNetworkMapField(csClass) ?: return null
        networkMapField.isAccessible = true
        val sparseArray = networkMapField.get(csInstance) as? SparseArray<*> ?: return null

        for (i in 0 until sparseArray.size()) {
            val nai = sparseArray.valueAt(i) ?: continue
            val naiClass = nai.javaClass

            val lpField = findLinkPropertiesField(naiClass)
            lpField?.isAccessible = true
            val lp = lpField?.get(nai) as? android.net.LinkProperties
            val iface = lp?.interfaceName ?: ""

            // Exact interface match (e.g. "wlan0", "wlan1", "rmnet_data3", "eth0")
            if (iface == targetChannel) {
                return nai
            }

            // Fallback for generic cellular iface matching
            if ((targetChannel.startsWith("rmnet") || targetChannel == "cellular") && iface.startsWith("rmnet")) {
                val ncField = findNetworkCapabilitiesField(naiClass)
                ncField?.isAccessible = true
                val nc = ncField?.get(nai) as? NetworkCapabilities
                if (nc?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                    return nai
                }
            }
        }
        return null
    }

    // High-frequency in-memory preference caching in system_server (1.5s TTL)
    private val prefCacheLock = Any()
    @Volatile private var lastPrefCacheTime: Long = 0L
    private val cachedUidRoutes = ConcurrentHashMap<Int, String>()
    @Volatile private var cachedCellularUids: Set<Int> = emptySet()
    private val PREF_CACHE_TTL_MS = 1500L

    private fun reloadPreferencesCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastPrefCacheTime < PREF_CACHE_TTL_MS) return

        synchronized(prefCacheLock) {
            if (now - lastPrefCacheTime < PREF_CACHE_TTL_MS) return
            try {
                val prefs = getRemotePreferences(PREF_NAME)
                val newUidRoutes = mutableMapOf<Int, String>()
                val newCellularUids = mutableSetOf<Int>()

                for ((key, value) in prefs.all) {
                    val channelStr = value as? String ?: continue
                    val uid = if (key.startsWith("uid_")) {
                        key.removePrefix("uid_").toIntOrNull()
                    } else {
                        key.toIntOrNull()
                    }
                    if (uid != null) {
                        newUidRoutes[uid] = channelStr
                        if (channelStr.startsWith("rmnet") || channelStr.startsWith("ccmni") || channelStr == "cellular") {
                            newCellularUids.add(uid)
                        }
                    }
                }
                cachedUidRoutes.clear()
                cachedUidRoutes.putAll(newUidRoutes)
                cachedCellularUids = newCellularUids
                lastPrefCacheTime = now
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "Failed to reload remote preferences in system_server: ${t.message}")
            }
        }
    }

    private fun getTargetChannelForUid(uid: Int): String? {
        reloadPreferencesCacheIfNeeded()
        return cachedUidRoutes[uid]
    }

    private fun getCellularConfiguredUids(): Set<Int> {
        reloadPreferencesCacheIfNeeded()
        return cachedCellularUids
    }

    // =========================================================================
    // Dual STA / Secondary Wi-Fi (wlan1) Screen-Off Keep-Alive Hooks
    // =========================================================================

    private fun hookDualWifiScreenOff(classLoader: ClassLoader) {
        log(Log.INFO, TAG, "Setting up Dual STA / Secondary Wi-Fi screen-off prevention hooks...")

        // 1. Check if SlaveWifiService is already registered in ServiceManager
        tryHookFromServiceManager(classLoader)

        // 2. Hook android.os.ServiceManager.addService to capture runtime SlaveWifiService registration
        try {
            val smClass = Class.forName("android.os.ServiceManager", false, classLoader)
            val addMethods = smClass.declaredMethods.filter { it.name == "addService" }
            for (m in addMethods) {
                m.isAccessible = true
                deoptimize(m)
                hook(m).intercept { chain ->
                    val name = chain.args.firstOrNull() as? String
                    val binder = chain.args.getOrNull(1)
                    if (name != null && (name == "SlaveWifiService" || name.contains("SlaveWifi") || name.contains("DualSta"))) {
                        log(Log.INFO, TAG, "ServiceManager.addService intercepted for $name: ${binder?.javaClass?.name}")
                        binder?.javaClass?.classLoader?.let { loader ->
                            hookSlaveWifiFromClassLoader(loader)
                        }
                    }
                    chain.proceed()
                }
            }
            log(Log.INFO, TAG, "Successfully hooked ServiceManager.addService for late Wi-Fi service detection")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Could not hook ServiceManager.addService: ${t.message}")
        }

        // 3. Background polling watchdog: check ServiceManager.checkService("SlaveWifiService") periodically
        Thread {
            for (i in 1..15) {
                if (isSlaveWifiHooked.get() && isDualStaHooked.get()) break
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

    private fun tryHookFromServiceManager(classLoader: ClassLoader) {
        if (isSlaveWifiHooked.get() && isDualStaHooked.get()) return
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

    private fun hookSlaveWifiFromClassLoader(loader: ClassLoader) {
        // Resolve ClassLoader candidates (direct loader, and chained with miui-wifi-service.jar)
        val candidateLoaders = mutableListOf(loader)
        try {
            val jar = java.io.File("/system_ext/framework/miui-wifi-service.jar")
            if (jar.exists()) {
                candidateLoaders.add(dalvik.system.PathClassLoader(jar.absolutePath, loader))
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
                    log(Log.WARN, TAG, "Error resolving SlaveWifiService: ${t.message}")
                }
            }

            // B. Resolve DualStaImpl
            if (!isDualStaHooked.get()) {
                try {
                    val implClass = Class.forName("com.android.server.wifi.DualStaImpl", false, cl)
                    hookDualStaImpl(implClass)
                } catch (_: ClassNotFoundException) {
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "Error resolving DualStaImpl: ${t.message}")
                }
            }
        }
    }

    private fun hookSlaveWifiService(serviceClass: Class<*>) {
        if (isSlaveWifiHooked.get()) return
        log(Log.INFO, TAG, "Installing keepalive hooks on ${serviceClass.name}...")

        var hookedMethods = 0

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
                hookedMethods++
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
                hookedMethods++
                log(Log.INFO, TAG, "Successfully hooked SlaveWifiService.scheduleAutoDisableTimer")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook SlaveWifiService.scheduleAutoDisableTimer: ${t.message}", t)
            }
        }

        // 3. Hook setWifiSlaveEnabled overloads
        val setMethods = serviceClass.declaredMethods.filter { it.name == "setWifiSlaveEnabled" }
        for (m in setMethods) {
            try {
                m.isAccessible = true
                deoptimize(m)
                hook(m).intercept { chain ->
                    val enable = chain.args.lastOrNull() as? Boolean ?: true
                    if (!enable && isKeepSlaveWifiScreenOff()) {
                        if (!isScreenInteractive()) {
                            log(Log.INFO, TAG, "Intercepted SlaveWifiService.setWifiSlaveEnabled(false) during screen-off; blocking teardown.")
                            return@intercept false
                        }
                    }
                    chain.proceed()
                }
                hookedMethods++
                log(Log.INFO, TAG, "Successfully hooked SlaveWifiService.setWifiSlaveEnabled(${m.parameterTypes.joinToString { it.simpleName }})")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook SlaveWifiService.setWifiSlaveEnabled: ${t.message}", t)
            }
        }

        if (hookedMethods > 0) {
            isSlaveWifiHooked.set(true)
            log(Log.INFO, TAG, "SlaveWifiService keepalive hooks installed successfully ($hookedMethods methods hooked)")
        } else {
            log(Log.WARN, TAG, "SlaveWifiService: 0 methods hooked, will retry on subsequent loader events")
        }
    }


    private fun hookDualStaImpl(implClass: Class<*>) {
        if (!isDualStaHooked.compareAndSet(false, true)) return
        log(Log.INFO, TAG, "Installing keepalive hooks on ${implClass.name}...")

        var hookedMethods = 0
        val setMethods = implClass.declaredMethods.filter { it.name == "setWifiSlaveEnabled" }
        for (m in setMethods) {
            try {
                m.isAccessible = true
                deoptimize(m)
                hook(m).intercept { chain ->
                    val enable = chain.args.lastOrNull() as? Boolean ?: true
                    if (!enable && isKeepSlaveWifiScreenOff()) {
                        if (!isScreenInteractive()) {
                            log(Log.INFO, TAG, "Intercepted DualStaImpl.setWifiSlaveEnabled(false) during screen-off; blocking teardown.")
                            return@intercept false
                        }
                    }
                    chain.proceed()
                }
                hookedMethods++
                log(Log.INFO, TAG, "Successfully hooked DualStaImpl.setWifiSlaveEnabled(${m.parameterTypes.joinToString { it.simpleName }})")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook DualStaImpl.setWifiSlaveEnabled: ${t.message}", t)
            }
        }

        log(Log.INFO, TAG, "DualStaImpl keepalive hooks installed successfully ($hookedMethods methods hooked)")
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

    // =========================================================================
    // Module-side Boot Restoration Watchdog (7.3)
    // =========================================================================

    private fun scheduleBootRestoreWatchdog() {
        Thread {
            try {
                // Wait 12 seconds for system server and activity manager to stabilize
                Thread.sleep(12000)
                val prefs = runCatching { getRemotePreferences(PREF_NAME) }.getOrNull()
                val hasRules = prefs?.all?.any { (k, v) ->
                    v is String && v != CHANNEL_DEFAULT && (k.startsWith("uid_") || k.toIntOrNull() != null)
                } ?: false

                if (hasRules) {
                    log(Log.INFO, TAG, "[BootRestore] Routing rules configured. Dispatching system broadcast ACTION_RESTORE_RULES to MultiRoute...")
                    val cmd = arrayOf(
                        "am", "broadcast",
                        "-a", "com.multiroute.ACTION_RESTORE_RULES",
                        "-p", "com.multiroute",
                        "-f", "0x01000000" // FLAG_RECEIVER_INCLUDE_BACKGROUND
                    )
                    Runtime.getRuntime().exec(cmd).waitFor()
                    log(Log.INFO, TAG, "[BootRestore] System broadcast sent successfully.")
                } else {
                    log(Log.INFO, TAG, "[BootRestore] No active rules configured; skipping boot broadcast.")
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "[BootRestore] Watchdog encountered error: ${t.message}")
            }
        }.apply {
            isDaemon = true
            name = "MultiRoute-BootRestoreWatchdog"
            start()
        }
    }
}

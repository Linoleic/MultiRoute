package com.multiroute.hook

import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.util.SparseArray
import com.multiroute.BuildConfig
import com.multiroute.util.ModuleStateParser
import com.multiroute.util.RouteRuleBuilder
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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

        /** Action and explicit receiver component used to wake the app up for rule restoration. */
        private const val ACTION_RESTORE_RULES = "com.multiroute.ACTION_RESTORE_RULES"
        private const val RESTORE_PACKAGE = "com.multiroute"
        private const val RESTORE_RECEIVER_CLASS = "com.multiroute.receiver.BootCompletedReceiver"
        private const val RESTORE_RECEIVER = "$RESTORE_PACKAGE/.receiver.BootCompletedReceiver"

        /** Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND (hidden constant, needed to wake a stopped app). */
        private const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000

        private val CS_CLASS_CANDIDATES = listOf(
            "android.net.connectivity.com.android.server.ConnectivityService",
            "com.android.server.ConnectivityService"
        )
    }

    private var isConnectivityHooked = false
    private val isSlaveWifiHooked = AtomicBoolean(false)
    private val isDualStaHooked = AtomicBoolean(false)

    @Volatile private var connectivityHookCount = 0
    @Volatile private var slaveWifiHookCount = 0
    @Volatile private var dualStaHookCount = 0

    /**
     * Publishes a structured state beacon so the app can tell "module code loaded" apart from
     * "hooks actually installed", and can detect that system_server still executes an older module
     * build (a System Framework scoped module cannot be hot-reloaded, so hook updates need a reboot).
     *
     * Channels, in order of usefulness: Settings.Global (readable by the app without root or su)
     * -> marker file (root-based diagnostics) -> legacy system property flag.
     */
    private fun publishState(source: String, stage: String) {
        val beacon = ModuleStateParser.buildBeacon(
            pid = android.os.Process.myPid(),
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            buildId = BuildConfig.HOOK_BUILD_ID,
            bootElapsedMs = android.os.SystemClock.elapsedRealtime(),
            writtenAtMs = System.currentTimeMillis(),
            connectivityHooks = connectivityHookCount,
            slaveWifiHooks = slaveWifiHookCount,
            dualStaHooks = dualStaHookCount,
            stage = stage
        )

        try {
            // NOTE: ActivityThread.getSystemContext() is an INSTANCE method, so the current
            // ActivityThread must be obtained first - invoking it with a null receiver throws
            // NullPointerException("null receiver") and silently loses this channel.
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val systemContext = currentThread?.let {
                activityThreadClass.getMethod("getSystemContext").invoke(it) as? android.content.Context
            }
            if (systemContext != null) {
                android.provider.Settings.Global.putString(
                    systemContext.contentResolver,
                    ModuleStateParser.STATE_KEY,
                    beacon
                )
            } else {
                log(Log.WARN, TAG, "[$source] System context not ready; Settings.Global channel skipped")
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "[$source] Could not publish state to Settings.Global: ${t.message}")
        }

        // Compact beacon in a system property: readable by any process without root, a settings
        // provider round trip or a ContentResolver (property values are capped at 92 bytes).
        try {
            val compact = ModuleStateParser.buildCompactBeacon(
                pid = android.os.Process.myPid(),
                moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
                buildId = BuildConfig.HOOK_BUILD_ID,
                bootElapsedMs = android.os.SystemClock.elapsedRealtime(),
                connectivityHooks = connectivityHookCount,
                slaveWifiHooks = slaveWifiHookCount,
                dualStaHooks = dualStaHookCount,
                stage = stage
            )
            val spClass = Class.forName("android.os.SystemProperties")
            spClass.getMethod("set", String::class.java, String::class.java)
                .invoke(null, ModuleStateParser.STATE_PROPERTY, compact)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "[$source] Could not publish state property: ${t.message}")
        }

        try {
            val markerFile = java.io.File(ModuleStateParser.MARKER_PATH)
            markerFile.writeText(beacon)
            markerFile.setReadable(true, false)
            markerFile.setWritable(true, false)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "[$source] Could not write ${ModuleStateParser.MARKER_PATH}: ${t.message}")
        }

        try {
            val spClass = Class.forName("android.os.SystemProperties")
            spClass.getMethod("set", String::class.java, String::class.java)
                .invoke(null, "sys.multiroute.active", "1")
        } catch (_: Throwable) {
        }

        log(Log.INFO, TAG, "[$source] Published module state ($stage): $beacon")
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(
            Log.INFO,
            TAG,
            "MultiRoute onModuleLoaded: process=${param.processName}, isSystemServer=${param.isSystemServer}"
        )
        if (param.isSystemServer) {
            publishState("onModuleLoaded(system)", "loaded")
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
        publishState("onSystemServerStarting", "loaded")

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
            connectivityHookCount = hookedCount
            log(Log.INFO, TAG, "ConnectivityService dynamic hooks installed successfully ($hookedCount hooked)")
            publishState("hookConnectivityService", "hooks")
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

    /** While the app has not started yet, poll less often and stay quiet unless the cache changes. */
    private val FALLBACK_CACHE_TTL_MS = 10_000L
    @Volatile private var cacheFallbackActive = false
    private var lastCacheFileStamp = -1L
    private var lastWrittenCacheText: String? = null

    private fun reloadPreferencesCacheIfNeeded() {
        val now = System.currentTimeMillis()
        val ttl = if (cacheFallbackActive) FALLBACK_CACHE_TTL_MS else PREF_CACHE_TTL_MS
        if (now - lastPrefCacheTime < ttl) return

        synchronized(prefCacheLock) {
            if (now - lastPrefCacheTime < ttl) return
            try {
                val prefs = getRemotePreferences(PREF_NAME)
                val newUidRoutes = mutableMapOf<Int, String>()

                for ((key, value) in prefs.all) {
                    val channelStr = value as? String ?: continue
                    val uid = if (key.startsWith("uid_")) {
                        key.removePrefix("uid_").toIntOrNull()
                    } else {
                        key.toIntOrNull()
                    }
                    if (uid != null && RouteRuleBuilder.isValidInterfaceName(channelStr)) {
                        newUidRoutes[uid] = channelStr
                    }
                }

                // Routes actually handed to the hooks: the preferences, or the cached copy while the
                // app has not been started in this boot.
                var effectiveRoutes: Map<Int, String> = newUidRoutes

                if (newUidRoutes.isEmpty()) {
                    // Start of a boot: the module app has not been started yet, and LSPosed then returns
                    // an empty map rather than failing. Fall back to the cache the app publishes on
                    // every sync, otherwise the hooks would not know any rule for the first ~90s.
                    //
                    // This runs on a hot path (every getDefaultNetworkForUid call), so the file is only
                    // re-read when it actually changed and the transition is logged exactly once -
                    // otherwise the fallback produced ~50 identical log lines per boot.
                    val file = java.io.File(RouteRuleBuilder.RULE_CACHE_PATH)
                    val stamp = if (file.canRead()) file.lastModified() else -1L
                    effectiveRoutes = if (cacheFallbackActive && stamp == lastCacheFileStamp) {
                        HashMap(cachedUidRoutes)
                    } else {
                        lastCacheFileStamp = stamp
                        val cached = readRuleCache()
                        if (cached.isNotEmpty()) {
                            log(Log.INFO, TAG, "Preferences empty (app not started yet); using cached rule map (${cached.size} entries)")
                        } else {
                            log(Log.INFO, TAG, "Preferences empty and no rule cache available yet")
                        }
                        cached
                    }
                    cacheFallbackActive = true
                } else {
                    if (cacheFallbackActive) {
                        log(Log.INFO, TAG, "Preferences readable again; dropping the cached-rule fallback")
                    }
                    cacheFallbackActive = false
                    lastCacheFileStamp = -1L
                    writeRuleCacheIfChanged(newUidRoutes)
                }

                cachedUidRoutes.clear()
                cachedUidRoutes.putAll(effectiveRoutes)
                cachedCellularUids = effectiveRoutes.filterValues { isCellularChannel(it) }.keys.toSet()
                lastPrefCacheTime = now
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "Failed to reload remote preferences in system_server: ${t.message}")
            }
        }
    }

    /** Cellular channels are the ones the platform tracks in `mobile_data_preferred_uids`. */
    private fun isCellularChannel(iface: String): Boolean =
        iface.startsWith("rmnet") || iface.startsWith("ccmni") || iface == "cellular"

    /** Reads the app-published uid→interface cache; see [RouteRuleBuilder.RULE_CACHE_PATH]. */
    private fun readRuleCache(): Map<Int, String> = try {
        val file = java.io.File(RouteRuleBuilder.RULE_CACHE_PATH)
        if (file.canRead()) RouteRuleBuilder.parseRuleCache(file.readText()) else emptyMap()
    } catch (t: Throwable) {
        log(Log.WARN, TAG, "Could not read rule cache: ${t.message}")
        emptyMap()
    }

    /** Self-heals the cache whenever the preferences were readable and non-empty (only on change). */
    private fun writeRuleCacheIfChanged(uidToIface: Map<Int, String>) {
        val text = RouteRuleBuilder.buildRuleCacheText(uidToIface)
        if (text == lastWrittenCacheText) return
        try {
            val file = java.io.File(RouteRuleBuilder.RULE_CACHE_PATH)
            file.writeText(text)
            file.setReadable(true, false)
            lastWrittenCacheText = text
            lastCacheFileStamp = file.lastModified()
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Could not write rule cache: ${t.message}")
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
            slaveWifiHookCount = hookedMethods
            log(Log.INFO, TAG, "SlaveWifiService keepalive hooks installed successfully ($hookedMethods methods hooked)")
            publishState("hookSlaveWifiService", "hooks")
        } else {
            log(Log.WARN, TAG, "SlaveWifiService: 0 methods hooked, will retry on subsequent loader events")
        }
    }


    private fun hookDualStaImpl(implClass: Class<*>) {
        if (isDualStaHooked.get()) return
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

        if (hookedMethods > 0) {
            // Latch only on real success so a failed attempt can be retried on the next loader event,
            // and so the published beacon never claims hooks that were not installed.
            isDualStaHooked.set(true)
            dualStaHookCount = hookedMethods
            log(Log.INFO, TAG, "DualStaImpl keepalive hooks installed successfully ($hookedMethods methods hooked)")
            publishState("hookDualStaImpl", "hooks")
        } else {
            log(Log.WARN, TAG, "DualStaImpl: 0 methods hooked, will retry on subsequent loader events")
        }
    }

    private fun isKeepSlaveWifiScreenOff(): Boolean {
        // 1. Transient system property (immediate cross-process IPC while the app is alive)
        when (readSystemProperty("sys.multiroute.keep_slave_wifi")) {
            "1" -> return true
            "0" -> return false
        }

        // 2. Persistent twin of the flag: survives reboots, so the keep-alive is honoured from the
        // very start of a boot instead of only after the app has been started.
        when (readSystemProperty("persist.multiroute.keep_slave_wifi")) {
            "1" -> return true
            "0" -> return false
        }

        // 3. SharedPreferences fallback via LibXposed RemotePreferences (authoritative when readable)
        val prefs = runCatching { getRemotePreferences(PREF_NAME) }.getOrNull()
        if (prefs != null && prefs.contains("keep_slave_wifi_screen_off")) {
            return prefs.getBoolean("keep_slave_wifi_screen_off", false)
        }

        return false
    }

    /** Reads a system property, or null when it is unset/unreadable. */
    private fun readSystemProperty(name: String): String? = try {
        val spClass = Class.forName("android.os.SystemProperties")
        val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
        (getMethod.invoke(null, name, "") as? String)?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
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

                // Final state refresh: by now the Wi-Fi keep-alive hooks (which resolve via the
                // SystemServiceManager interception) have either installed or failed, so the beacon
                // published here is the authoritative one for this boot.
                publishState("bootRestoreWatchdog", "ready")

                // Wake the app so it re-applies its own routing rules.
                //
                // There used to be a remote-preferences gate here ("skip when no rules"). On-device
                // evidence removed it: while the module app is not running, getRemotePreferences()
                // does not throw but silently returns an EMPTY map, so the gate skipped the restore on
                // every boot. The app owns the preferences and its sync is idempotent (it just cleans
                // up when nothing is configured), so waking it unconditionally is correct and cheap -
                // and once awake, its NetworkCallback covers interfaces that only come up later.
                val prefs = runCatching { getRemotePreferences(PREF_NAME) }.getOrNull()
                val visibleRuleKeys = prefs?.all?.count { (k, v) ->
                    v is String && v != CHANNEL_DEFAULT && (k.startsWith("uid_") || k.toIntOrNull() != null)
                }
                log(
                    Log.INFO,
                    TAG,
                    "[BootRestore] Waking MultiRoute to restore routing rules " +
                            "(visible rule keys: ${visibleRuleKeys ?: "unknown"})."
                )

                // Do not broadcast before the framework considers the boot complete, otherwise the
                // broadcast is dropped outright (especially after a soft reboot).
                awaitBootCompleted(maxWaitMs = 240_000)

                dispatchRestoreBroadcast()

                // Retries, spread out: the first wake-up can still race with app/AMS startup, and the
                // interface set keeps settling. A late secondary Wi-Fi or cellular link is covered by the
                // later attempts.
                for (delayMs in longArrayOf(15_000L, 60_000L, 180_000L)) {
                    Thread.sleep(delayMs)
                    log(Log.INFO, TAG, "[BootRestore] Retrying restore broadcast (+${delayMs / 1000}s).")
                    dispatchRestoreBroadcast()
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

    /**
     * Blocks (bounded) until the framework reports the boot as completed.
     *
     * This matters for a **soft** reboot (`setprop ctl.restart zygote`), which is the standard way to
     * apply a module update: `service.d` does not run again and `BOOT_COMPLETED` is not re-broadcast, so
     * this wake-up is the only restore path - and a broadcast sent before boot completion is dropped
     * ("Cannot broadcast before boot completed", observed on device), which left the rules missing for
     * minutes. Hardware reboots are unaffected: the property is already set by the time we look.
     */
    private fun awaitBootCompleted(maxWaitMs: Long) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (readSystemProperty("sys.boot_completed") == "1") return
            Thread.sleep(2_000)
        }
        log(Log.WARN, TAG, "[BootRestore] sys.boot_completed still unset after ${maxWaitMs / 1000}s; waking anyway")
    }

    /**
     * Wakes the MultiRoute app with an explicit-component broadcast so it re-applies its rules.
     *
     * The preferred route is an **in-process** broadcast from the system context. Spawning `am` from
     * system_server fails on this platform with `EACCES` ("Cannot run program \"am\": error=13,
     * Permission denied"), which silently disabled the whole boot restore until it was observed on
     * device. The shell route is retained only as a fallback for platforms that permit it.
     */
    private fun dispatchRestoreBroadcast() {
        if (sendRestoreBroadcastInProcess()) return
        sendRestoreBroadcastViaShell()
    }

    /** Returns true when the broadcast was handed to ActivityManager in-process. */
    private fun sendRestoreBroadcastInProcess(): Boolean {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val context = currentThread?.let {
                activityThreadClass.getMethod("getSystemContext").invoke(it) as? android.content.Context
            } ?: return false

            val intent = android.content.Intent(ACTION_RESTORE_RULES).apply {
                // Explicit component: an implicit broadcast is dropped when the app is not running,
                // which is exactly the boot-time case this exists for.
                component = android.content.ComponentName(RESTORE_PACKAGE, RESTORE_RECEIVER_CLASS)
                addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND)
            }
            context.sendBroadcast(intent)
            log(Log.INFO, TAG, "[BootRestore] Restore broadcast sent in-process.")
            true
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "[BootRestore] In-process broadcast failed: ${t.message}")
            false
        }
    }

    /** Fallback: shell out to `am` (only works where system_server may exec system binaries). */
    private fun sendRestoreBroadcastViaShell() {
        try {
            // An EXPLICIT component (-n) is mandatory here: a package-only (-p) implicit broadcast
            // is dropped when the target process is not running, which is exactly the boot-time case.
            val cmd = arrayOf(
                "am", "broadcast",
                "-a", ACTION_RESTORE_RULES,
                "-n", RESTORE_RECEIVER,
                "-f", "0x01000000" // FLAG_RECEIVER_INCLUDE_BACKGROUND
            )
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                    val output = StringBuilder()
                    val drain = Thread {
                        runCatching {
                            proc.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line -> synchronized(output) { output.appendLine(line) } }
                            }
                        }
                    }
                    drain.isDaemon = true
                    drain.start()
                    val finished = proc.waitFor(10, TimeUnit.SECONDS)
                    if (finished) {
                        drain.join(500)
                        val tail = synchronized(output) { output.toString().trim().takeLast(200) }
                        log(Log.INFO, TAG, "[BootRestore] Broadcast dispatched (rc=${proc.exitValue()}): $tail")
                    } else {
                        proc.destroyForcibly()
                        log(Log.WARN, TAG, "[BootRestore] am broadcast timed out after 10s; aborting.")
                    }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "[BootRestore] Failed to dispatch restore broadcast: ${t.message}")
            }
    }
}

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

        /**
         * Package the wake-up broadcast is addressed to, and the receiver's fully qualified class name.
         *
         * Both come from the build rather than from a literal: the application id can differ from the
         * code namespace (it is `io.github.linoleic.multiroute` while the classes stay under
         * `com.multiroute`), and a stale literal here would make boot restoration fail silently - the
         * broadcast would simply be addressed to a package that does not exist.
         */
        private val RESTORE_PACKAGE = BuildConfig.APPLICATION_ID
        private const val RESTORE_RECEIVER_CLASS = "com.multiroute.receiver.BootCompletedReceiver"
        private val RESTORE_RECEIVER = "$RESTORE_PACKAGE/$RESTORE_RECEIVER_CLASS"

        /** Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND (hidden constant, needed to wake a stopped app). */
        private const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000

        private val CS_CLASS_CANDIDATES = listOf(
            "android.net.connectivity.com.android.server.ConnectivityService",
            "com.android.server.ConnectivityService"
        )

        /** Mainline APEX package names that host the Tethering / NetworkStack service on Android 11+. */
        private val TETHERING_PACKAGES = setOf(
            "com.android.networkstack.tethering",
            "com.google.android.networkstack.tethering",
            "com.android.networkstack",
            "com.google.android.networkstack"
        )

    }

    private var isConnectivityHooked = false
    @Volatile private var connectivityService: Any? = null
    private var cellularPreferenceFingerprint: String? = null
    private val cellularRequestNetworks = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<android.net.NetworkRequest, Network>())
    private val isSlaveWifiHooked = AtomicBoolean(false)
    private val isDualStaHooked = AtomicBoolean(false)
    @Volatile private var isTetheringHooked = false

    /** Keeps the "not installed because the keep-alive is off" note to one line per boot. */
    private val keepAliveSkippedLogged = AtomicBoolean(false)

    /** Set once the keep-alive broadcast receiver is registered; retried by the boot watchdog until then. */
    private val keepAliveReceiverRegistered = AtomicBoolean(false)

    /**
     * ClassLoader the Wi-Fi service was seen in (it lives in its own APEX/module loader, not in the one
     * this module was loaded with). Recorded during the boot-time service interception so the
     * keep-alive hooks can also be installed later, without a reboot.
     */
    @Volatile private var wifiServiceClassLoader: ClassLoader? = null

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
            markerFile.setWritable(true, true)
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
        if (param.processName == BuildConfig.APPLICATION_ID) {
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
        if (param.packageName == BuildConfig.APPLICATION_ID) {
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
        } else if (param.packageName in TETHERING_PACKAGES) {
            log(Log.INFO, TAG, "[TetheringHook] NetworkStack package detected in onPackageLoaded: ${param.packageName}")
            hookTetheringUpstream(param.defaultClassLoader)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        val classLoader = param.classLoader
        systemServerClassLoader = classLoader
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
        if (hookCellularDefaultRequests(csClass)) hookedCount++
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

    /**
     * A route override alone does not change netd's per-UID DNS network or socket mark.
     * Split the platform's mobile preference into subscription-specific multilayer requests;
     * ConnectivityService then updates netd and default-network callbacks itself.
     */
    private fun hookCellularDefaultRequests(csClass: Class<*>): Boolean {
        if (CellularFrameworkCompat.refreshMethod(csClass) == null) {
            log(Log.WARN, TAG, "Cellular preference refresh unsupported; retaining platform defaults")
            return false
        }
        // Subscription selectors alone cannot distinguish two Internet bearers on the same SIM.
        // Refuse to install partial pinning if the platform's ranking entry point is unsupported.
        if (!hookCellularNetworkRanking(csClass)) return false
        val factory = csClass.declaredMethods.firstOrNull {
            it.name == "createNrisFromMobileDataPreferredUids" && it.parameterTypes.size == 1 &&
                Set::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return false
        val createRequest = csClass.declaredMethods.firstOrNull {
            it.name == "createDefaultInternetRequestForTransport" && it.parameterTypes.size == 2
        } ?: return false
        val createNris = csClass.declaredMethods.firstOrNull {
            it.name == "createNrisForPreferenceOrder" && it.parameterTypes.size == 3
        } ?: return false
        val typeClass = createRequest.parameterTypes[1]
        val types = typeClass.enumConstants ?: return false
        val requestType = types.firstOrNull { (it as Enum<*>).name == "REQUEST" } ?: return false
        val fallbackType = types.firstOrNull { (it as Enum<*>).name == "TRACK_DEFAULT" } ?: return false
        val preference = runCatching {
            csClass.getDeclaredField("PREFERENCE_ORDER_MOBILE_DATA_PREFERERRED")
                .apply { isAccessible = true }.getInt(null)
        }.getOrNull() ?: return false
        listOf(factory, createRequest, createNris).forEach { it.isAccessible = true }
        return try {
            csClass.declaredMethods.filter { it.name == "handleMobileDataPreferredUidsChanged" }
                .forEach { deoptimize(it) }
            deoptimize(factory)
            hook(factory).intercept { chain ->
                connectivityService = chain.thisObject
                reloadPreferencesCacheIfNeeded()
                @Suppress("UNCHECKED_CAST")
                val allUids = (chain.getArg(0) as Set<Int>) - suspendedSimUids
                val pinned = mutableSetOf<Int>()
                val result = android.util.ArraySet<Any>()
                try {
                    val groups = CellularDefaultNetworkPolicy.groups(allUids, HashMap(cachedUidRoutes))
                    for ((iface, uids) in groups) {
                        val agent = findNetworkAgentForChannel(chain.thisObject, iface) ?: continue
                        val targetNetwork = findNetworkField(agent.javaClass)?.apply { isAccessible = true }
                            ?.get(agent) as? Network ?: continue
                        val nc = findNetworkCapabilitiesField(agent.javaClass)?.apply { isAccessible = true }
                            ?.get(agent) as? NetworkCapabilities ?: continue
                        if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                        val specifier = NetworkCapabilities::class.java.getMethod("getNetworkSpecifier")
                            .invoke(nc) as? android.net.TelephonyNetworkSpecifier ?: continue
                        val selected = createRequest.invoke(chain.thisObject,
                            NetworkCapabilities.TRANSPORT_CELLULAR, requestType)
                        val capabilities = selected.javaClass.getField("networkCapabilities")
                            .get(selected) as NetworkCapabilities
                        NetworkCapabilities::class.java.getMethod("setNetworkSpecifier",
                            android.net.NetworkSpecifier::class.java).invoke(capabilities, specifier)
                        // Mode 2 may expose a restricted Internet bearer. This is a system-owned
                        // per-UID request, not an unprivileged app request for restricted access.
                        if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) {
                            NetworkCapabilities::class.java.getMethod("removeCapability", Int::class.javaPrimitiveType)
                                .invoke(capabilities, NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                        }
                        val fallback = createRequest.invoke(chain.thisObject, -1, fallbackType)
                        @Suppress("UNCHECKED_CAST")
                        val nris = createNris.invoke(chain.thisObject, uids, listOf(selected, fallback), preference) as Set<Any>
                        // The factory mutates request UID ranges, which also changes its hash code.
                        cellularRequestNetworks[selected as android.net.NetworkRequest] = targetNetwork
                        result.addAll(nris)
                        pinned.addAll(uids)
                        log(Log.INFO, TAG, "[CellularDefault] $uids -> $iface ($specifier)")
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "[CellularDefault] Could not pin subscription; using platform preference", t)
                    result.clear()
                    pinned.clear()
                }
                // Call the original exactly once, including on unsupported ROMs.
                @Suppress("UNCHECKED_CAST")
                val unchanged = chain.proceed(arrayOf<Any>(allUids - pinned)) as Set<Any>
                result.addAll(unchanged)
                result
            }
            log(Log.INFO, TAG, "Successfully hooked subscription-specific mobile defaults")
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook subscription-specific mobile defaults", t)
            false
        }
    }

    private fun refreshCellularDefaults() {
        val service = connectivityService ?: return
        val handler = getAllFields(service.javaClass).firstOrNull { it.name == "mHandler" }
            ?.apply { isAccessible = true }?.get(service) as? android.os.Handler ?: return
        handler.post {
            try {
                lastPrefCacheTime = 0L
                reloadPreferencesCacheIfNeeded()
                val routes = cachedUidRoutes.filterValues { CellularDefaultNetworkPolicy.isCellular(it) }
                val fingerprint = routes.toSortedMap().map { (uid, iface) ->
                    val agent = findNetworkAgentForChannel(service, iface)
                    val nc = agent?.let { findNetworkCapabilitiesField(it.javaClass)
                        ?.apply { isAccessible = true }?.get(it) } as? NetworkCapabilities
                    val specifier = nc?.let { NetworkCapabilities::class.java
                        .getMethod("getNetworkSpecifier").invoke(it) }
                    val network = agent?.let { findNetworkField(it.javaClass)?.apply { isAccessible = true }?.get(it) }
                    "$uid:$iface:$network:$specifier:${nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)}"
                }.joinToString(";")
                if (fingerprint != cellularPreferenceFingerprint) {
                    // Already on ConnectivityService's Handler, including for the private handler path.
                    CellularFrameworkCompat.refresh(service)
                    cellularPreferenceFingerprint = fingerprint
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "[CellularDefault] Refresh failed", t)
            }
        }
    }

    /** Filter candidates for our selected request only; fallback and unrelated requests stay native. */
    private fun hookCellularNetworkRanking(csClass: Class<*>): Boolean = try {
        val rankerClass = getAllFields(csClass).first { it.name == "mNetworkRanker" }.type
        val rank = rankerClass.declaredMethods.first {
            it.name == "getBestNetwork" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == android.net.NetworkRequest::class.java &&
                Collection::class.java.isAssignableFrom(it.parameterTypes[1])
        }.apply { isAccessible = true }
        csClass.declaredMethods.filter { it.name == "computeNetworkReassignment" }.forEach { deoptimize(it) }
        deoptimize(rank)
        hook(rank).intercept { chain ->
            val target = cellularRequestNetworks[chain.getArg(0) as android.net.NetworkRequest]
                ?: return@intercept chain.proceed()
            @Suppress("UNCHECKED_CAST")
            val networks = chain.getArg(1) as Collection<Any>
            fun identity(agent: Any): Network? = findNetworkField(agent.javaClass)
                ?.apply { isAccessible = true }?.get(agent) as? Network
            val candidates = CellularNetworkSelection.candidates(target, networks, ::identity)
            val incumbent = chain.getArg(2)?.takeIf { identity(it) == target }
            chain.proceed(arrayOf(chain.getArg(0), candidates, incumbent))
        }
        log(Log.INFO, TAG, "Successfully hooked exact cellular network ranking")
        true
    } catch (t: Throwable) {
        log(Log.WARN, TAG, "Exact cellular ranking unsupported; retaining platform defaults", t)
        false
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
                connectivityService = chain.thisObject
                val targetChannel = getTargetChannelForUid(uid)

                if (targetChannel != null && targetChannel != CHANNEL_DEFAULT) {
                    val csInstance = chain.thisObject
                    val targetAgent = findNetworkAgentForChannel(csInstance, targetChannel)
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
                    val targetAgent = findNetworkAgentForChannel(csInstance, targetChannel)
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

    private val networkAgentRegistry = NetworkAgentRegistry()

    private val linkPropertiesFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private fun findLinkPropertiesField(naiClass: Class<*>): Field? {
        return linkPropertiesFieldCache.getOrPut(naiClass) {
            getAllFields(naiClass).filter { it.name == "linkProperties" || it.type == android.net.LinkProperties::class.java }.take(1)
        }.firstOrNull()
    }

    private val networkCapabilitiesFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private fun findNetworkCapabilitiesField(naiClass: Class<*>): Field? {
        return networkCapabilitiesFieldCache.getOrPut(naiClass) {
            getAllFields(naiClass).filter { it.name == "networkCapabilities" || it.type == NetworkCapabilities::class.java }.take(1)
        }.firstOrNull()
    }

    private val networkFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private fun findNetworkField(naiClass: Class<*>): Field? {
        return networkFieldCache.getOrPut(naiClass) {
            getAllFields(naiClass).filter { it.type == Network::class.java || it.name == "network" }.take(1)
                .onEach { it.isAccessible = true }
        }.firstOrNull()
    }

    /**
     * Dynamically finds the NetworkAgentInfo whose interfaceName matches targetChannel (e.g. wlan0, wlan1, rmnet_data*, eth0).
     */
    private fun findNetworkAgentForChannel(csInstance: Any, targetChannel: String): Any? {
        val agents = networkAgentRegistry.agents(csInstance) { registry ->
            if (registry is SparseArray<*>) synchronized(registry) {
                (0 until registry.size()).mapNotNull { registry.valueAt(it) }
            } else emptyList()
        }
        for (nai in agents) {
            val naiClass = nai.javaClass

            val lpField = findLinkPropertiesField(naiClass)
            lpField?.isAccessible = true
            val lp = lpField?.get(nai) as? android.net.LinkProperties
            val iface = lp?.interfaceName ?: ""

            // Exact interface match (e.g. "wlan0", "wlan1", "rmnet_data3", "eth0")
            if (iface == targetChannel) {
                return nai
            }

            // Only the generic alias may select another interface; IMS is not an Internet bearer.
            if (targetChannel == "cellular") {
                val ncField = findNetworkCapabilitiesField(naiClass)
                ncField?.isAccessible = true
                val nc = ncField?.get(nai) as? NetworkCapabilities
                if (nc?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                    nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return nai
                }
            }
        }
        return null
    }

    // High-frequency in-memory preference caching in system_server (1.5s TTL)
    private val prefCacheLock = Any()
    @Volatile private var lastPrefCacheTime: Long = 0L
    @Volatile private var cachedUidRoutes: Map<Int, String> = emptyMap()
    @Volatile private var cachedCellularUids: Set<Int> = emptySet()
    @Volatile private var suspendedSimUids: Set<Int> = emptySet()
    private val PREF_CACHE_TTL_MS = 1500L

    private fun reloadPreferencesCacheIfNeeded() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPrefCacheTime < PREF_CACHE_TTL_MS) return
        synchronized(prefCacheLock) {
            if (now - lastPrefCacheTime < PREF_CACHE_TTL_MS) return
            try {
                val file = java.io.File(RouteRuleBuilder.RULE_CACHE_PATH)
                // Never replace an effective scenario/online snapshot with base assignments. A
                // present but unreadable file is an error, not permission to resurrect base rules.
                val published = if (file.exists()) file.readText() else null
                val preferences = if (published == null) {
                    getRemotePreferences(PREF_NAME).all.mapNotNull { (key, value) ->
                        val uid = key.removePrefix("uid_").toIntOrNull()
                        val iface = value as? String
                        if (uid != null && iface != null) uid to iface else null
                    }.toMap()
                } else emptyMap()
                val bootCount = systemContext()?.let {
                    android.provider.Settings.Global.getInt(it.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
                } ?: -1
                val routes = EffectiveRouteSnapshot.resolve(published, preferences, bootCount)
                cachedUidRoutes = routes
                suspendedSimUids = EffectiveRouteSnapshot.suspendedSimUids(published, bootCount)
                cachedCellularUids = routes.filterValues { isCellularChannel(it) }.keys.toSet()
                lastPrefCacheTime = now
            } catch (t: Throwable) {
                lastPrefCacheTime = now
                log(Log.WARN, TAG, "Could not reload effective route snapshot: ${t.message}")
            }
        }
    }

    private fun isCellularChannel(iface: String): Boolean = CellularDefaultNetworkPolicy.isCellular(iface)

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

    /** The system_server context, used for Settings.Global writes and for receiving broadcasts. */
    private fun systemContext(): android.content.Context? = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        currentThread?.let {
            activityThreadClass.getMethod("getSystemContext").invoke(it) as? android.content.Context
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Installs (or stops installing) the Xiaomi dual-Wi-Fi hooks while system_server keeps running.
     *
     * The hooks are deliberately not installed at boot when the screen-off keep-alive is off - they would
     * only pass every call through, so leaving them out keeps MultiRoute away from the ROM's Wi-Fi code.
     * Turning the switch on sends this broadcast instead, and the hooks go in immediately; turning it off
     * only affects the next boot, because the already-installed hooks keep calling through unchanged.
     */
    private fun registerKeepAliveReceiver() {
        if (!keepAliveReceiverRegistered.compareAndSet(false, true)) return
        val context = systemContext() ?: run {
            keepAliveReceiverRegistered.set(false)
            log(Log.WARN, TAG, "[KeepAlive] No system context; hooks can only be installed at boot")
            return
        }
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    // Security: Verify that the broadcast came from MultiRoute's own UID or root/system.
                    val senderUid = runCatching {
                        val m = android.content.BroadcastReceiver::class.java.getDeclaredMethod("getSendingUid")
                        m.isAccessible = true
                        (m.invoke(this) as? Number)?.toInt()
                    }.getOrNull()

                    val allowedUids = mutableSetOf(android.os.Process.myUid(), 0, 1000)
                    ctx?.packageManager?.let { pm ->
                        runCatching {
                            val appUid = pm.getPackageUid(BuildConfig.APPLICATION_ID, 0)
                            if (appUid > 0) allowedUids.add(appUid)
                        }
                    }

                    if (senderUid != null && senderUid !in allowedUids) {
                        log(Log.WARN, TAG, "[KeepAlive] Ignored unauthorized keep-alive signal from UID $senderUid")
                        return
                    }

                    when (intent?.action) {
                        ModuleStateParser.ACTION_ROUTES_CHANGED -> refreshCellularDefaults()
                        ModuleStateParser.ACTION_KEEPALIVE_CHANGED -> {
                            if (!isKeepSlaveWifiScreenOff()) {
                                log(Log.INFO, TAG, "[KeepAlive] Switch is off; no hooks installed.")
                                return
                            }
                            // Never hook on the system_server main thread.
                            Thread {
                                log(Log.INFO, TAG, "[KeepAlive] Switch is on; installing dual-Wi-Fi hooks now.")
                                installSlaveWifiHooksOnDemand()
                            }.apply {
                                isDaemon = true
                                name = "MultiRoute-KeepAliveHook"
                                start()
                            }
                        }
                        ModuleStateParser.ACTION_CONNECT_SLAVE_WIFI -> {
                            val netId = intent.getIntExtra(ModuleStateParser.EXTRA_NET_ID, -1)
                            if (netId >= 0) {
                                val bssid = intent.getStringExtra(ModuleStateParser.EXTRA_BSSID)
                                Thread {
                                    val binder = com.multiroute.wifi.SlaveWifiBridge.getSlaveWifiBinder()
                                    if (binder != null) {
                                        log(Log.INFO, TAG, "[SlaveWifi] Connecting secondary Wi-Fi in system_server: netId=$netId bssid=$bssid")
                                        com.multiroute.wifi.SlaveWifiBridge.setWifiSlaveEnabled(binder, "android", true)
                                        val ok = com.multiroute.wifi.SlaveWifiBridge.connectToSlaveAp(binder, netId, bssid)
                                        log(Log.INFO, TAG, "[SlaveWifi] connectToSlaveAp returned $ok")
                                    } else {
                                        log(Log.WARN, TAG, "[SlaveWifi] SlaveWifiService binder is null in system_server")
                                    }
                                }.apply {
                                    isDaemon = true
                                    name = "MultiRoute-ConnectSlaveWifi"
                                    start()
                                }
                            }
                        }
                        ModuleStateParser.ACTION_DISCONNECT_SLAVE_WIFI -> {
                            Thread {
                                val binder = com.multiroute.wifi.SlaveWifiBridge.getSlaveWifiBinder()
                                if (binder != null) {
                                    log(Log.INFO, TAG, "[SlaveWifi] Disconnecting secondary Wi-Fi in system_server")
                                    val ok = com.multiroute.wifi.SlaveWifiBridge.disconnectSlaveWifi(binder)
                                    log(Log.INFO, TAG, "[SlaveWifi] disconnectSlaveWifi returned $ok")
                                }
                            }.apply {
                                isDaemon = true
                                name = "MultiRoute-DisconnectSlaveWifi"
                                start()
                            }
                        }
                    }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(ModuleStateParser.ACTION_KEEPALIVE_CHANGED)
                addAction(ModuleStateParser.ACTION_ROUTES_CHANGED)
                addAction(ModuleStateParser.ACTION_CONNECT_SLAVE_WIFI)
                addAction(ModuleStateParser.ACTION_DISCONNECT_SLAVE_WIFI)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            log(Log.INFO, TAG, "[KeepAlive] Listening for keep-alive changes")
        } catch (t: Throwable) {
            // ActivityManager does not exist this early in system_server startup, so the caller retries.
            keepAliveReceiverRegistered.set(false)
            log(Log.WARN, TAG, "[KeepAlive] Could not register receiver yet: ${t.message}")
        }
    }

    /** On-demand installation: use the loader the Wi-Fi service was seen in, then fall back and give up. */
    private fun installSlaveWifiHooksOnDemand() {
        if (isSlaveWifiHooked.get() && isDualStaHooked.get()) return
        val loaders = mutableListOf<ClassLoader>()
        wifiServiceClassLoader?.let { loaders.add(it) }
        runCatching { MultiRouteModule::class.java.classLoader }.getOrNull()?.let { loaders.add(it) }
        runCatching { ClassLoader.getSystemClassLoader() }.getOrNull()?.let { loaders.add(it) }
        for (loader in loaders) {
            hookSlaveWifiFromClassLoader(loader)
            if (isSlaveWifiHooked.get() && isDualStaHooked.get()) return
        }
        log(
            Log.WARN,
            TAG,
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
                log(Log.INFO, TAG, "Xiaomi dual-Wi-Fi hooks not installed: screen-off keep-alive is off")
            }
            return
        }
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

    @Volatile
    private var linkCallbackRegistered = false

    /** Kept so the boot watchdog (which runs later, when logs are actually visible) can use it. */
    @Volatile
    private var systemServerClassLoader: ClassLoader? = null

    /** Native Tethering still owns forwarding, DNS and the lifetime of cellular requests. */
    private fun hookTetheringUpstream(classLoader: ClassLoader) {
        if (isTetheringHooked) return
        val handles = mutableListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        val ready = AtomicBoolean(false)
        try {
            // Google Mainline builds can relocate Tethering classes under android.net.tethering.
            val type = listOf(
                "com.android.networkstack.tethering.UpstreamNetworkMonitor",
                "android.net.tethering.com.android.networkstack.tethering.UpstreamNetworkMonitor"
            ).firstNotNullOfOrNull { name ->
                runCatching { Class.forName(name, false, classLoader) }.getOrNull()
            } ?: throw ClassNotFoundException("UpstreamNetworkMonitor is absent from this package loader")
            val bool = Boolean::class.javaPrimitiveType!!
            // Resolve the required surface before installing any policy overrides.
            val select = type.getDeclaredMethod("getCurrentPreferredUpstream")
            val config = type.getDeclaredMethod("setUpstreamConfig", bool, bool)
            val tryCell = type.getDeclaredMethod("setTryCell", bool)
            val start = type.getDeclaredMethod("startObserveUpstreamNetworks")
            val stop = type.getDeclaredMethod("stop")
            type.getDeclaredMethod("isCellularUpstreamPermitted")
            val updates = listOf("handleAvailable", "handleNetCap", "handleLinkProp", "handleLost").map { name ->
                type.declaredMethods.first { it.name == name }
            }
            val controllers = java.util.IdentityHashMap<Any, TetheringUpstreamController>()
            fun controller(instance: Any?): TetheringUpstreamController? {
                if (instance == null) return null
                return runCatching {
                    synchronized(controllers) {
                        controllers.getOrPut(instance) {
                            TetheringUpstreamController(instance) { message ->
                                log(Log.INFO, TAG, "[TetheringHook] $message")
                            }
                        }
                    }
                }.onFailure { log(Log.WARN, TAG, "[TetheringHook] Monitor unavailable: $it") }.getOrNull()
            }
            fun safely(action: () -> Unit) {
                runCatching(action).onFailure { log(Log.WARN, TAG, "[TetheringHook] Policy update failed: $it") }
            }
            fun install(method: Method, callback: (io.github.libxposed.api.XposedInterface.Chain) -> Any?) {
                handles += hook(method).intercept { chain ->
                    if (ready.get()) callback(chain) else chain.proceed()
                }
            }
            listOf(select, config, tryCell, start, stop).plus(updates).forEach { deoptimize(it) }
            install(config) { chain ->
                val owner = controller(chain.thisObject)
                val args = runCatching {
                    owner?.nativeConfig(chain.args[0] as Boolean, chain.args[1] as Boolean)
                }.getOrNull()
                val result = if (args == null) chain.proceed()
                    else chain.proceed(arrayOf<Any>(args.first, args.second))
                safely { owner?.networkChanged() }
                result
            }
            install(tryCell) { chain ->
                val requested = runCatching {
                    controller(chain.thisObject)?.nativeTryCell(chain.args[0] as Boolean)
                }.getOrNull()
                if (requested == null) chain.proceed() else chain.proceed(arrayOf<Any>(requested))
            }
            install(start) { chain ->
                val result = chain.proceed()
                safely { controller(chain.thisObject)?.started() }
                result
            }
            install(stop) { chain ->
                safely { controller(chain.thisObject)?.stopping() }
                chain.proceed()
            }
            updates.forEach { method ->
                install(method) { chain ->
                    val result = chain.proceed()
                    safely { controller(chain.thisObject)?.networkChanged() }
                    result
                }
            }
            install(select) { chain ->
                val native = chain.proceed()
                runCatching { controller(chain.thisObject)?.select(native) ?: native }
                    .onFailure { log(Log.WARN, TAG, "[TetheringHook] Using native upstream: $it") }
                    .getOrDefault(native)
            }
            // Publish load evidence even before the first hotspot session.
            type.declaredMethods.firstOrNull { it.name == "startTrackDefaultNetwork" }?.let { method ->
                deoptimize(method)
                install(method) { chain ->
                    val result = chain.proceed()
                    controller(chain.thisObject)
                    result
                }
            }
            ready.set(true)
            isTetheringHooked = true
            log(Log.INFO, TAG, "[TetheringHook] Installed upstream selection, lifecycle and network-event hooks")
        } catch (t: Throwable) {
            ready.set(false)
            handles.asReversed().forEach { runCatching { it.unhook() } }
            log(Log.ERROR, TAG, "[TetheringHook] Unsupported native monitor; retaining system behavior", t)
        }
    }

    @Volatile
    private var lastScenarioWakeAt = 0L

    /**
     * Registers a ConnectivityManager callback inside system_server.
     *
     * Scenario plans (Wi-Fi name, link count, cellular only) depend on the links that are up right now, and
     * the app only learns about a change through its own ViewModel callback - which does not run while its
     * process has been killed. The module is always awake, so it watches instead and wakes the app with the
     * very same restore broadcast the boot path uses; the app then re-evaluates the plan and re-applies the
     * rules. Registration is idempotent and is retried from the boot watchdog, because an early
     * system_server Context is not always usable yet.
     */
    private fun registerLinkChangeCallback() {
        if (linkCallbackRegistered) return
        try {
            val context = systemContext() ?: return
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager ?: return
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = onLinkChanged()
                override fun onLost(network: android.net.Network) = onLinkChanged()
                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) =
                    onLinkChanged()
                override fun onLinkPropertiesChanged(
                    network: android.net.Network,
                    linkProperties: android.net.LinkProperties
                ) = onLinkChanged()
            }
            cm.registerNetworkCallback(android.net.NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).build(), callback)
            linkCallbackRegistered = true
            log(Log.INFO, TAG, "[Scenario] Watching link changes in system_server to wake the app")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "[Scenario] Could not register the link-change callback: ${t.message}", t)
        }
    }

    /** True while at least one scenario plan exists, as published by the app on every sync. */
    private fun hasScenarioPlans(): Boolean = try {
        val file = java.io.File(RouteRuleBuilder.SCENARIO_PLAN_MARKER)
        file.canRead() && (file.readText().trim().toIntOrNull() ?: 0) > 0
    } catch (t: Throwable) {
        false
    }

    /**
     * Wakes the app when a link changes, but only while a plan exists: with no plans the rule set does not
     * depend on the current links, so there is nothing to re-evaluate and the app may stay asleep. The
     * debounce is needed because one transition produces several callbacks.
     */
    private fun onLinkChanged() {
        // Network identities can change without a UID or subscription change, including without plans.
        refreshCellularDefaults()
        val now = System.currentTimeMillis()
        if (now - lastScenarioWakeAt < 3000L) return
        lastScenarioWakeAt = now
        if (!hasScenarioPlans()) return
        log(Log.INFO, TAG, "[Scenario] Link changed and plans exist; waking the app to re-evaluate")
        dispatchRestoreBroadcast()
    }

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

                // ActivityManager only exists after boot completes, and registering the keep-alive
                // receiver needs it - registering earlier fails with a null ActivityManager.
                registerKeepAliveReceiver()

                // The app's own NetworkCallback only runs while its process is alive; from here on the
                // module watches link changes itself and wakes the app when a scenario plan is stored.
                registerLinkChangeCallback()

                dispatchRestoreBroadcast()

                // Retries, spread out: the first wake-up can still race with app/AMS startup, and the
                // interface set keeps settling. A late secondary Wi-Fi or cellular link is covered by the
                // later attempts.
                for (delayMs in longArrayOf(15_000L, 60_000L, 180_000L)) {
                    Thread.sleep(delayMs)
                    log(Log.INFO, TAG, "[BootRestore] Retrying restore broadcast (+${delayMs / 1000}s).")
                    registerKeepAliveReceiver()
                    registerLinkChangeCallback()
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

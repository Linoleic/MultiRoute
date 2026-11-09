package com.multiroute.hook

import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.TelephonyNetworkSpecifier
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.ArraySet
import android.util.Log
import android.util.SparseArray
import com.multiroute.util.RouteRuleBuilder
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs and operates per-UID dynamic routing, cellular default network selection, and exact
 * cellular ranking hooks inside system_server's ConnectivityService.
 */
internal class ConnectivityRoutingHookHandler(
    private val xposed: XposedInterface,
    private val onHooksInstalled: (source: String) -> Unit
) {
    companion object {
        private const val PREF_CACHE_TTL_MS = 1500L
    }

    @Volatile
    var isHooked: Boolean = false
        private set

    @Volatile
    var hookCount: Int = 0
        private set

    @Volatile
    private var connectivityService: Any? = null

    private var cellularPreferenceFingerprint: String? = null
    private val cellularRequestNetworks = Collections.synchronizedMap(
        WeakHashMap<NetworkRequest, Network>()
    )
    private val hookInstallLock = Any()

    // High-frequency in-memory preference caching in system_server (1.5s TTL)
    private val prefCacheLock = Any()
    @Volatile private var lastPrefCacheTime: Long = 0L
    @Volatile private var cachedUidRoutes: Map<Int, String> = emptyMap()
    @Volatile private var cachedCellularUids: Set<Int> = emptySet()
    @Volatile private var suspendedSimUids: Set<Int> = emptySet()

    private val networkAgentRegistry = NetworkAgentRegistry()
    private val linkPropertiesFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val networkCapabilitiesFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val networkFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /**
     * Attempts immediate hook installation via base classloader lookup (Strategy A)
     * or an already-registered ServiceManager binder (Strategy B).
     */
    fun resolveDirectOrServiceManager(classLoader: ClassLoader) {
        if (isHooked) return

        // Strategy A: Direct classloader lookup
        for (className in HookPolicyHelpers.CS_CLASS_CANDIDATES) {
            try {
                val csClass = Class.forName(className, false, classLoader)
                xposed.log(Log.INFO, HOOK_TAG, "Found ConnectivityService in base classloader: $className")
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
            if (binder != null &&
                !binder.javaClass.name.contains("BinderProxy") &&
                binder.javaClass.name.contains("ConnectivityService")
            ) {
                val csClass = binder.javaClass
                xposed.log(Log.INFO, HOOK_TAG, "Resolved ConnectivityService via ServiceManager: ${csClass.name}")
                hookConnectivityService(csClass)
                if (isHooked) return
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Strategy C callback invoked when SystemServiceManager.startService() loads an APEX or
     * platform ConnectivityService class.
     */
    fun onSystemServiceStarted(serviceName: String, targetLoader: ClassLoader) {
        if (isHooked || !HookPolicyHelpers.isConnectivityServiceName(serviceName)) return
        for (candidate in HookPolicyHelpers.CS_CLASS_CANDIDATES) {
            try {
                val csClass = Class.forName(candidate, false, targetLoader)
                xposed.log(
                    Log.INFO,
                    HOOK_TAG,
                    "Resolved ConnectivityService via SystemServiceManager ($serviceName): ${csClass.name}"
                )
                hookConnectivityService(csClass)
                break
            } catch (_: ClassNotFoundException) {
            }
        }
    }

    private fun hookConnectivityService(csClass: Class<*>) {
        synchronized(hookInstallLock) {
            if (isHooked) return
            if (csClass.name.contains("BinderProxy")) return
            xposed.log(Log.INFO, HOOK_TAG, "Installing dynamic multi-route hooks on ${csClass.name}...")

            var hookedCount = 0
            if (hookGetMobileDataPreferredUids(csClass)) hookedCount++
            if (hookCellularDefaultRequests(csClass)) hookedCount++
            if (hookGetDefaultNetworkForUid(csClass)) hookedCount++
            if (hookGetActiveNetworkForUidInternal(csClass)) hookedCount++

            if (hookedCount > 0) {
                isHooked = true
                hookCount = hookedCount
                xposed.log(Log.INFO, HOOK_TAG, "ConnectivityService dynamic hooks installed successfully ($hookedCount hooked)")
                onHooksInstalled("hookConnectivityService")
            } else {
                xposed.log(Log.WARN, HOOK_TAG, "No methods could be hooked on ${csClass.name}")
            }
        }
    }

    private fun hookGetMobileDataPreferredUids(csClass: Class<*>): Boolean {
        val method = csClass.declaredMethods.firstOrNull {
            it.name == "getMobileDataPreferredUids" && it.parameterTypes.isEmpty()
        } ?: return false

        return try {
            method.isAccessible = true
            xposed.deoptimize(method)
            xposed.hook(method).intercept { chain ->
                @Suppress("UNCHECKED_CAST")
                val originalSet = (chain.proceed() as? Set<Int>)?.toMutableSet() ?: mutableSetOf()

                val cellularUids = getCellularConfiguredUids()
                if (cellularUids.isNotEmpty()) {
                    originalSet.addAll(cellularUids)
                }

                originalSet
            }
            xposed.log(Log.INFO, HOOK_TAG, "Successfully hooked getMobileDataPreferredUids")
            true
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, HOOK_TAG, "Failed to hook getMobileDataPreferredUids: ${t.message}", t)
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
            xposed.log(Log.WARN, HOOK_TAG, "Cellular preference refresh unsupported; retaining platform defaults")
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
                .forEach { xposed.deoptimize(it) }
            xposed.deoptimize(factory)
            xposed.hook(factory).intercept { chain ->
                connectivityService = chain.thisObject
                reloadPreferencesCacheIfNeeded()
                @Suppress("UNCHECKED_CAST")
                val allUids = (chain.getArg(0) as Set<Int>) - suspendedSimUids
                val pinned = mutableSetOf<Int>()
                val result = ArraySet<Any>()
                try {
                    val groups = CellularDefaultNetworkPolicy.groups(allUids, HashMap(cachedUidRoutes))
                    for ((iface, uids) in groups) {
                        val agent = findNetworkAgentForChannel(chain.thisObject, iface) ?: continue
                        val targetNetwork = findNetworkField(agent.javaClass)?.apply { isAccessible = true }
                            ?.get(agent) as? Network ?: continue
                        val nc = findNetworkCapabilitiesField(agent.javaClass)?.apply { isAccessible = true }
                            ?.get(agent) as? NetworkCapabilities ?: continue
                        if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        ) continue
                        val specifier = NetworkCapabilities::class.java.getMethod("getNetworkSpecifier")
                            .invoke(nc) as? TelephonyNetworkSpecifier ?: continue
                        val selected = createRequest.invoke(
                            chain.thisObject,
                            NetworkCapabilities.TRANSPORT_CELLULAR,
                            requestType
                        )
                        val capabilities = selected.javaClass.getField("networkCapabilities")
                            .get(selected) as NetworkCapabilities
                        NetworkCapabilities::class.java.getMethod(
                            "setNetworkSpecifier",
                            NetworkSpecifier::class.java
                        ).invoke(capabilities, specifier)
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
                        cellularRequestNetworks[selected as NetworkRequest] = targetNetwork
                        result.addAll(nris)
                        pinned.addAll(uids)
                        xposed.log(Log.INFO, HOOK_TAG, "[CellularDefault] $uids -> $iface ($specifier)")
                    }
                } catch (t: Throwable) {
                    xposed.log(Log.ERROR, HOOK_TAG, "[CellularDefault] Could not pin subscription; using platform preference", t)
                    result.clear()
                    pinned.clear()
                }
                // Call the original exactly once, including on unsupported ROMs.
                @Suppress("UNCHECKED_CAST")
                val unchanged = chain.proceed(arrayOf<Any>(allUids - pinned)) as Set<Any>
                result.addAll(unchanged)
                result
            }
            xposed.log(Log.INFO, HOOK_TAG, "Successfully hooked subscription-specific mobile defaults")
            true
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, HOOK_TAG, "Failed to hook subscription-specific mobile defaults", t)
            false
        }
    }

    fun refreshCellularDefaults() {
        val service = connectivityService ?: return
        val handler = getAllFields(service.javaClass).firstOrNull { it.name == "mHandler" }
            ?.apply { isAccessible = true }?.get(service) as? Handler ?: return
        handler.post {
            try {
                lastPrefCacheTime = 0L
                reloadPreferencesCacheIfNeeded()
                val routes = cachedUidRoutes.filterValues { CellularDefaultNetworkPolicy.isCellular(it) }
                val fingerprint = routes.toSortedMap().map { (uid, iface) ->
                    val agent = findNetworkAgentForChannel(service, iface)
                    val nc = agent?.let {
                        findNetworkCapabilitiesField(it.javaClass)?.apply { isAccessible = true }?.get(it)
                    } as? NetworkCapabilities
                    val specifier = nc?.let {
                        NetworkCapabilities::class.java.getMethod("getNetworkSpecifier").invoke(it)
                    }
                    val network = agent?.let {
                        findNetworkField(it.javaClass)?.apply { isAccessible = true }?.get(it)
                    }
                    "$uid:$iface:$network:$specifier:${nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)}"
                }.joinToString(";")
                if (fingerprint != cellularPreferenceFingerprint) {
                    // Already on ConnectivityService's Handler, including for the private handler path.
                    CellularFrameworkCompat.refresh(service)
                    cellularPreferenceFingerprint = fingerprint
                }
            } catch (t: Throwable) {
                xposed.log(Log.WARN, HOOK_TAG, "[CellularDefault] Refresh failed", t)
            }
        }
    }

    /** Filter candidates for our selected request only; fallback and unrelated requests stay native. */
    private fun hookCellularNetworkRanking(csClass: Class<*>): Boolean = try {
        val rankerClass = getAllFields(csClass).first { it.name == "mNetworkRanker" }.type
        val rank = rankerClass.declaredMethods.first {
            it.name == "getBestNetwork" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == NetworkRequest::class.java &&
                Collection::class.java.isAssignableFrom(it.parameterTypes[1])
        }.apply { isAccessible = true }
        csClass.declaredMethods.filter { it.name == "computeNetworkReassignment" }.forEach { xposed.deoptimize(it) }
        xposed.deoptimize(rank)
        xposed.hook(rank).intercept { chain ->
            val target = cellularRequestNetworks[chain.getArg(0) as NetworkRequest]
                ?: return@intercept chain.proceed()
            @Suppress("UNCHECKED_CAST")
            val networks = chain.getArg(1) as Collection<Any>
            fun identity(agent: Any): Network? = findNetworkField(agent.javaClass)
                ?.apply { isAccessible = true }?.get(agent) as? Network
            val candidates = CellularNetworkSelection.candidates(target, networks, ::identity)
            val incumbent = chain.getArg(2)?.takeIf { identity(it) == target }
            chain.proceed(arrayOf(chain.getArg(0), candidates, incumbent))
        }
        xposed.log(Log.INFO, HOOK_TAG, "Successfully hooked exact cellular network ranking")
        true
    } catch (t: Throwable) {
        xposed.log(Log.WARN, HOOK_TAG, "Exact cellular ranking unsupported; retaining platform defaults", t)
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
            xposed.deoptimize(method)
            xposed.hook(method).intercept { chain ->
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
            xposed.log(Log.INFO, HOOK_TAG, "Successfully hooked getDefaultNetworkForUid (Dynamic)")
            true
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, HOOK_TAG, "Failed to hook getDefaultNetworkForUid: ${t.message}", t)
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
            xposed.deoptimize(method)
            xposed.hook(method).intercept { chain ->
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
            xposed.log(Log.INFO, HOOK_TAG, "Successfully hooked getActiveNetworkForUidInternal (Dynamic)")
            true
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, HOOK_TAG, "Failed to hook getActiveNetworkForUidInternal: ${t.message}", t)
            false
        }
    }

    private fun getAllFields(clazz: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            fields.addAll(current.declaredFields)
            current = current.superclass
        }
        return fields
    }

    private fun findLinkPropertiesField(naiClass: Class<*>): Field? =
        linkPropertiesFieldCache.getOrPut(naiClass) {
            getAllFields(naiClass).filter { it.name == "linkProperties" || it.type == LinkProperties::class.java }.take(1)
        }.firstOrNull()

    private fun findNetworkCapabilitiesField(naiClass: Class<*>): Field? =
        networkCapabilitiesFieldCache.getOrPut(naiClass) {
            getAllFields(naiClass).filter { it.name == "networkCapabilities" || it.type == NetworkCapabilities::class.java }.take(1)
        }.firstOrNull()

    private fun findNetworkField(naiClass: Class<*>): Field? =
        networkFieldCache.getOrPut(naiClass) {
            getAllFields(naiClass).filter { it.type == Network::class.java || it.name == "network" }.take(1)
                .onEach { it.isAccessible = true }
        }.firstOrNull()

    /**
     * Dynamically finds the NetworkAgentInfo whose interfaceName matches targetChannel
     * (e.g. wlan0, wlan1, rmnet_data*, eth0).
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
            val lp = lpField?.get(nai) as? LinkProperties
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
                    nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    return nai
                }
            }
        }
        return null
    }

    private fun reloadPreferencesCacheIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrefCacheTime < PREF_CACHE_TTL_MS) return
        synchronized(prefCacheLock) {
            val lockedNow = SystemClock.elapsedRealtime()
            if (lockedNow - lastPrefCacheTime < PREF_CACHE_TTL_MS) return
            try {
                val file = File(RouteRuleBuilder.RULE_CACHE_PATH)
                // Never replace an effective scenario/online snapshot with base assignments. A
                // present but unreadable file is an error, not permission to resurrect base rules.
                val published = if (file.exists()) file.readText() else null
                val preferences = if (published == null) {
                    xposed.getRemotePreferences(PREF_NAME).all.mapNotNull { (key, value) ->
                        val uid = key.removePrefix("uid_").toIntOrNull()
                        val iface = value as? String
                        if (uid != null && iface != null) uid to iface else null
                    }.toMap()
                } else emptyMap()
                val bootCount = SystemServerEnv.systemContext()?.let {
                    Settings.Global.getInt(it.contentResolver, Settings.Global.BOOT_COUNT, -1)
                } ?: -1
                val routes = EffectiveRouteSnapshot.resolve(published, preferences, bootCount)
                cachedUidRoutes = routes
                suspendedSimUids = EffectiveRouteSnapshot.suspendedSimUids(published, bootCount)
                cachedCellularUids = routes.filterValues { CellularDefaultNetworkPolicy.isCellular(it) }.keys.toSet()
                lastPrefCacheTime = lockedNow
            } catch (t: Throwable) {
                lastPrefCacheTime = lockedNow
                xposed.log(Log.WARN, HOOK_TAG, "Could not reload effective route snapshot: ${t.message}")
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
}

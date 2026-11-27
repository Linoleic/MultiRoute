package com.multiroute.hook

/**
 * Pure policy and matching helpers shared across system_server and NetworkStack hooks.
 * Kept free of Android / LibXposed framework APIs so every branch is unit-testable on the JVM.
 */
internal object HookPolicyHelpers {

    val CS_CLASS_CANDIDATES = listOf(
        "android.net.connectivity.com.android.server.ConnectivityService",
        "com.android.server.ConnectivityService"
    )

    /** Mainline APEX package names that host the Tethering / NetworkStack service on Android 11+. */
    val TETHERING_PACKAGES = setOf(
        "com.android.networkstack.tethering",
        "com.google.android.networkstack.tethering",
        "com.android.networkstack",
        "com.google.android.networkstack"
    )

    val UPSTREAM_MONITOR_CLASS_CANDIDATES = listOf(
        "com.android.networkstack.tethering.UpstreamNetworkMonitor",
        "android.net.connectivity.com.android.networkstack.tethering.UpstreamNetworkMonitor",
        "android.net.tethering.com.android.networkstack.tethering.UpstreamNetworkMonitor"
    )

    fun resolveUpstreamMonitorClass(classLoader: ClassLoader): Class<*>? =
        resolveUpstreamMonitorClass { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }

    internal fun resolveUpstreamMonitorClass(loadClass: (String) -> Class<*>?): Class<*>? {
        UPSTREAM_MONITOR_CLASS_CANDIDATES.firstNotNullOfOrNull(loadClass)?.let { return it }

        // Fallback for future Mainline jarjar prefixes: follow TetheringService.makeTethering() -> Tethering.
        val serviceClass = loadClass("com.android.networkstack.tethering.TetheringService") ?: return null
        val tetheringType = serviceClass.declaredMethods
            .firstOrNull { it.name == "makeTethering" }
            ?.returnType
            ?: return null

        runCatching {
            tetheringType.declaredFields.firstOrNull { it.name == "mUpstreamNetworkMonitor" }?.type
        }.getOrNull()?.let { return it }

        val pkgPrefix = tetheringType.name.substringBeforeLast('.', "")
        val monitorName = if (pkgPrefix.isEmpty()) "UpstreamNetworkMonitor" else "$pkgPrefix.UpstreamNetworkMonitor"
        return loadClass(monitorName)
    }

    fun isConnectivityServiceName(serviceName: String): Boolean =
        serviceName.contains("ConnectivityService")

    fun isWifiServiceName(serviceName: String): Boolean =
        serviceName.contains("Wifi", ignoreCase = true) ||
            serviceName.contains("Slave", ignoreCase = true) ||
            serviceName.contains("DualSta", ignoreCase = true)

    fun isSlaveWifiBinderName(serviceName: String?): Boolean =
        serviceName != null && (
            serviceName == "SlaveWifiService" ||
                serviceName.contains("SlaveWifi") ||
                serviceName.contains("DualSta")
            )

    /**
     * Evaluates whether dual-Wi-Fi screen-off keep-alive is enabled using the priority chain:
     * transient `sys.` property -> persistent `persist.` property -> RemotePreferences fallback -> false.
     */
    fun resolveKeepAlivePreference(
        transientProp: String?,
        persistentProp: String?,
        hasRemotePref: Boolean,
        remotePrefValue: Boolean
    ): Boolean {
        when (transientProp) {
            "1" -> return true
            "0" -> return false
        }
        when (persistentProp) {
            "1" -> return true
            "0" -> return false
        }
        return if (hasRemotePref) remotePrefValue else false
    }

    /**
     * Returns true when a broadcast sender UID is permitted to send privileged MultiRoute control
     * intents into system_server (`system_server` self, root UID 0, system UID 1000, or the MultiRoute app).
     */
    fun isAuthorizedSenderUid(senderUid: Int?, myUid: Int, appUid: Int?): Boolean {
        if (senderUid == null) return true
        if (senderUid == myUid || senderUid == 0 || senderUid == 1000) return true
        return appUid != null && appUid > 0 && senderUid == appUid
    }

    /**
     * Tracks per-network Wi-Fi SSID transitions for wireless ADB wakeups so dual-Wi-Fi RSSI updates
     * on two different SSIDs do not ping-pong and transitions during locked or debounce windows are
     * not swallowed.
     */
    internal class WifiAdbTransitionTracker<K>(
        private val debounceMs: Long = 2000L
    ) {
        private val acknowledgedSsids = mutableMapOf<K, String>()
        private var lastWakeAtMs: Long = 0L

        @Synchronized
        fun onCapabilities(
            network: K,
            rawSsid: String?,
            unlocked: Boolean,
            nowMs: Long,
            shouldWakeForSsid: (String) -> Boolean
        ): String? {
            val ssid = rawSsid?.trim()?.removeSurrounding("\"")
                ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                ?: return null
            if (!unlocked) return null
            if (acknowledgedSsids[network] == ssid) return null
            if (!shouldWakeForSsid(ssid)) {
                acknowledgedSsids[network] = ssid
                return null
            }
            if (lastWakeAtMs != 0L && nowMs - lastWakeAtMs < debounceMs) {
                return null
            }
            acknowledgedSsids[network] = ssid
            lastWakeAtMs = nowMs
            return ssid
        }

        @Synchronized
        fun onLost(network: K) {
            acknowledgedSsids.remove(network)
        }

        @Synchronized
        fun reset() {
            acknowledgedSsids.clear()
        }
    }

    /**
     * Snapshot of the wireless ADB trigger policy synchronized into system_server.
     */
    internal data class AdbTriggerConfig(
        val restoreOnBoot: Boolean = false,
        val autoOnWifi: Boolean = false,
        val takeOverSystem: Boolean = false,
        val trustedSsids: Set<String> = emptySet()
    ) {
        fun shouldWakeForSsid(ssid: String): Boolean =
            autoOnWifi || takeOverSystem || (restoreOnBoot && ssid in trustedSsids)

        fun shouldWakeOnUnlock(): Boolean =
            restoreOnBoot || takeOverSystem || autoOnWifi
    }

    /**
     * Holds the latest synchronized [AdbTriggerConfig] and reports whether a config update changed
     * initialization or effective policy so callers only reset [WifiAdbTransitionTracker] when needed.
     */
    internal class AdbTriggerConfigStore {
        private var initialized = false
        private var config = AdbTriggerConfig()

        @Synchronized
        fun update(newConfig: AdbTriggerConfig): Boolean {
            val shouldReset = !initialized || config != newConfig
            config = newConfig
            initialized = true
            return shouldReset
        }

        @Synchronized
        fun isInitialized(): Boolean = initialized

        @Synchronized
        fun snapshot(): AdbTriggerConfig = config
    }
}

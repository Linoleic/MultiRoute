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
}

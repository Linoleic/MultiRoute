package com.multiroute.wifi

import android.content.Context
import android.util.Log
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import com.multiroute.util.SuHelper

/**
 * Driver abstraction for different OEM / platform Dual Wi-Fi (Multi-Internet / STA+STA) implementations.
 */
interface DualWifiDriver {
    val scheme: DualWifiScheme
    val maxPollAttempts: Int

    /** Activates the framework overlay, system settings, or boot scripts required for dual Wi-Fi concurrency. */
    suspend fun activate(context: Context): Boolean

    /** Prepares the platform before initiating a secondary STA connection (e.g. clearing previous requests). */
    suspend fun prepareConnect(context: Context): Boolean

    /** Dispatches the actual connection command (via shell request or ISlaveWifiManager binder). */
    suspend fun executeConnect(
        context: Context,
        ssid: String,
        bssid: String?,
        frequencyMhz: Int,
        passphrase: String?,
        capabilities: String?,
        onIntentCheck: () -> Unit
    ): Boolean

    /** Releases the secondary Wi-Fi link and any shell requests or binder connections held by this driver. */
    suspend fun release(context: Context): Boolean

    /** Performs teardown tasks when Dual Wi-Fi is globally deactivated (e.g. restoring screen-off policy, removing boot scripts). */
    suspend fun cleanupDeactivation(context: Context): Boolean
}

object AospDualWifiDriver : DualWifiDriver {
    private const val TAG = "AospDualWifiDriver"
    internal const val SHELL_REQUEST_PREF = "dual_wifi_shell_request_ssid"

    override val scheme: DualWifiScheme = DualWifiScheme.AOSP_OVERLAY
    override val maxPollAttempts: Int = 20

    override suspend fun activate(context: Context): Boolean {
        val script = """
            cmd overlay fabricate --target-name WifiCustomization --target com.google.android.wifi.resources --name DualWifi com.google.android.wifi.resources:bool/config_wifiMultiStaMultiInternetConcurrencyEnabled 0x12 1
            cmd overlay enable com.android.shell:DualWifi
            cmd overlay fabricate --target-name WifiCustomization --target com.google.android.wifi.resources --name Dual5G com.google.android.wifi.resources:bool/config_wifiAllowMultiInternetConnectDual5GFrequency 0x12 1
            cmd overlay enable com.android.shell:Dual5G
            cmd wifi force-overlay-config-value bool config_wifiMultiStaMultiInternetConcurrencyEnabled enabled true
            cmd wifi force-overlay-config-value bool config_wifiAllowMultiInternetConnectDual5GFrequency enabled true
            cmd wifi set-multi-internet-mode 2
            
            mkdir -p /data/adb/service.d
            cat << 'DUALWIFI_EOF' > /data/adb/service.d/01-multiroute-dualwifi.sh
#!/system/bin/sh
cmd overlay enable com.android.shell:DualWifi
cmd overlay enable com.android.shell:Dual5G
cmd wifi force-overlay-config-value bool config_wifiMultiStaMultiInternetConcurrencyEnabled enabled true
cmd wifi force-overlay-config-value bool config_wifiAllowMultiInternetConnectDual5GFrequency enabled true
cmd wifi set-multi-internet-mode 2
DUALWIFI_EOF
            chmod 755 /data/adb/service.d/01-multiroute-dualwifi.sh
        """.trimIndent()
        return SuHelper.executeCommand(script, timeoutSeconds = 10)
    }

    override suspend fun prepareConnect(context: Context): Boolean {
        check(release(context)) { context.getString(R.string.wifi_i18n_release_previous_error) }
        val existing = SuHelper.executeShellWithOutput("cmd wifi list-requests")
        check(existing.isEmpty() || existing.all { it.isBlank() || it.contains("No active requests") }) {
            context.getString(R.string.wifi_i18n_other_request_error)
        }
        val prepScript = """
            cmd wifi set-multi-internet-mode 2 2>/dev/null
            cmd wifi network-requests-set-user-approved android yes
        """.trimIndent()
        return SuHelper.executeCommand(prepScript, timeoutSeconds = 5)
    }

    override suspend fun executeConnect(
        context: Context,
        ssid: String,
        bssid: String?,
        frequencyMhz: Int,
        passphrase: String?,
        capabilities: String?,
        onIntentCheck: () -> Unit
    ): Boolean {
        val ap = bssid?.takeIf { it.isNotBlank() } ?: DualWifiScanParser.parseScanResultLines(
            SuHelper.executeShellWithOutput("cmd wifi list-scan-results 2>/dev/null", timeoutSeconds = 3)
        ).firstOrNull { it.ssid == ssid && DualWifiBandHelper.isSameBand(it.frequencyMhz, frequencyMhz) }?.bssid
        checkNotNull(ap) { context.getString(R.string.dual_wifi_rescan_required) }
        val savedRequest = WifiShellCommands.savedInternetRequest(ap, frequencyMhz)

        onIntentCheck()
        check(
            context.getSharedPreferences(RouteConfigProvider.PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(SHELL_REQUEST_PREF, WifiShellCommands.savedInternetRequestKey(frequencyMhz)).commit()
        )
        onIntentCheck()
        check(SuHelper.executeCommand(savedRequest, timeoutSeconds = 5))
        SuHelper.executeCommand("cmd wifi start-scan", timeoutSeconds = 3)
        return true
    }

    override suspend fun release(context: Context): Boolean {
        val prefs = context.getSharedPreferences(RouteConfigProvider.PREF_NAME, Context.MODE_PRIVATE)
        val requestKey = prefs.getString(SHELL_REQUEST_PREF, null) ?: return true
        val removed = SuHelper.executeCommand(WifiShellCommands.removeRequest(requestKey))
        val empty = if (!removed) SuHelper.executeShellWithOutput("cmd wifi list-requests").any { it.contains("No active requests") } else true
        if (removed || empty) {
            prefs.edit().remove(SHELL_REQUEST_PREF).commit()
            return true
        }
        return false
    }

    override suspend fun cleanupDeactivation(context: Context): Boolean {
        release(context)
        return SuHelper.executeCommand("rm -f /data/adb/service.d/01-multiroute-dualwifi.sh", timeoutSeconds = 5)
    }
}

object XiaomiSlaveDriver : DualWifiDriver {
    private const val TAG = "XiaomiSlaveDriver"

    override val scheme: DualWifiScheme = DualWifiScheme.XIAOMI_SLAVE
    override val maxPollAttempts: Int = 12

    override suspend fun activate(context: Context): Boolean {
        RouteConfigProvider.recordKeepSlaveWifiScreenOffBeforeDualWifi(context)
        val keepAwake = SuHelper.setKeepSlaveWifiScreenOff(context, true)
        val script = "cmd wifi set-multi-internet-mode 2 2>/dev/null"
        return keepAwake && SuHelper.executeCommand(script, timeoutSeconds = 5)
    }

    override suspend fun prepareConnect(context: Context): Boolean {
        AospDualWifiDriver.release(context)
        val prepScript = """
            cmd wifi set-multi-internet-mode 2 2>/dev/null
            cmd wifi network-requests-set-user-approved android yes
        """.trimIndent()
        return SuHelper.executeCommand(prepScript, timeoutSeconds = 5)
    }

    override suspend fun executeConnect(
        context: Context,
        ssid: String,
        bssid: String?,
        frequencyMhz: Int,
        passphrase: String?,
        capabilities: String?,
        onIntentCheck: () -> Unit
    ): Boolean {
        if (DualWifiScanParser.getNetworkIdForSsid(ssid) == null) {
            val secType = DualWifiBandHelper.resolveSecurityType(capabilities, passphrase)
            val passArg = if (secType != "open" && secType != "owe" && !passphrase.isNullOrEmpty()) " " + WifiShellCommands.quote(passphrase) else ""
            check(
                SuHelper.executeCommand(
                    "cmd wifi add-network ${WifiShellCommands.quote(ssid)} $secType$passArg -d",
                    timeoutSeconds = 3,
                    sensitive = true
                )
            )
        }
        val netId = DualWifiScanParser.getNetworkIdForSsid(ssid)
        checkNotNull(netId) { context.getString(R.string.dual_wifi_saved_network_required) }
        onIntentCheck()
        Log.i(TAG, "Connecting secondary Wi-Fi via Xiaomi SlaveWifiService: netId=$netId bssid=$bssid")
        val bridgeCmd = WifiShellCommands.slaveConnect(context.applicationInfo.sourceDir, netId, bssid)
        check(SuHelper.executeCommand(bridgeCmd, timeoutSeconds = 5)) {
            context.getString(R.string.dual_wifi_slave_request_failed)
        }
        return true
    }

    override suspend fun release(context: Context): Boolean {
        val apkPath = context.applicationInfo.sourceDir
        return SuHelper.executeCommand(WifiShellCommands.slaveDisconnect(apkPath), timeoutSeconds = 3)
    }

    override suspend fun cleanupDeactivation(context: Context): Boolean {
        release(context)
        val previous = RouteConfigProvider.getKeepSlaveWifiScreenOffBeforeDualWifi(context)
        return if (previous == null) true else {
            val restored = SuHelper.setKeepSlaveWifiScreenOff(context, previous)
            if (restored) RouteConfigProvider.clearKeepSlaveWifiScreenOffBeforeDualWifi(context)
            restored
        }
    }
}

object RootDirectDriver : DualWifiDriver {
    override val scheme: DualWifiScheme = DualWifiScheme.ROOT_DIRECT
    override val maxPollAttempts: Int = 20

    override suspend fun activate(context: Context): Boolean {
        val script = "cmd wifi set-multi-internet-mode 2 2>/dev/null"
        return SuHelper.executeCommand(script, timeoutSeconds = 5)
    }

    override suspend fun prepareConnect(context: Context): Boolean = AospDualWifiDriver.prepareConnect(context)

    override suspend fun executeConnect(
        context: Context,
        ssid: String,
        bssid: String?,
        frequencyMhz: Int,
        passphrase: String?,
        capabilities: String?,
        onIntentCheck: () -> Unit
    ): Boolean = AospDualWifiDriver.executeConnect(context, ssid, bssid, frequencyMhz, passphrase, capabilities, onIntentCheck)

    override suspend fun release(context: Context): Boolean = AospDualWifiDriver.release(context)

    override suspend fun cleanupDeactivation(context: Context): Boolean = AospDualWifiDriver.cleanupDeactivation(context)
}

object DualWifiDriverFactory {
    fun getDriver(scheme: DualWifiScheme): DualWifiDriver = when (scheme) {
        DualWifiScheme.XIAOMI_SLAVE -> XiaomiSlaveDriver
        DualWifiScheme.AOSP_OVERLAY -> AospDualWifiDriver
        DualWifiScheme.ROOT_DIRECT, DualWifiScheme.AUTO -> RootDirectDriver
    }
}

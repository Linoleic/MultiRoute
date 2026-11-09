package com.multiroute.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import android.util.Log
import com.multiroute.BuildConfig
import com.multiroute.util.ModuleStateParser
import com.multiroute.util.RouteRuleBuilder
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates system_server boot-time routing rule restoration, ConnectivityManager link-change
 * wakeups for scenario re-evaluation, and authorized runtime control broadcasts from the app.
 */
internal class BootRestoreWatchdog(
    private val xposed: XposedInterface,
    private val onPublishReady: () -> Unit,
    private val onRefreshCellularDefaults: () -> Unit,
    private val onKeepAliveChanged: () -> Unit,
    private val onConnectSlaveWifi: (netId: Int, bssid: String?) -> Unit,
    private val onDisconnectSlaveWifi: () -> Unit
) {
    companion object {
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
    }

    /** Set once the keep-alive broadcast receiver is registered; retried by the boot watchdog until then. */
    private val keepAliveReceiverRegistered = AtomicBoolean(false)

    @Volatile
    private var linkCallbackRegistered = false

    @Volatile
    private var lastScenarioWakeAt = 0L

    fun schedule() {
        Thread {
            try {
                // Wait 12 seconds for system server and activity manager to stabilize
                Thread.sleep(12000)

                // Final state refresh: by now the Wi-Fi keep-alive hooks (which resolve via the
                // SystemServiceManager interception) have either installed or failed, so the beacon
                // published here is the authoritative one for this boot.
                onPublishReady()

                // Wake the app so it re-applies its own routing rules.
                //
                // There used to be a remote-preferences gate here ("skip when no rules"). On-device
                // evidence removed it: while the module app is not running, getRemotePreferences()
                // does not throw but silently returns an EMPTY map, so the gate skipped the restore on
                // every boot. The app owns the preferences and its sync is idempotent (it just cleans
                // up when nothing is configured), so waking it unconditionally is correct and cheap -
                // and once awake, its NetworkCallback covers interfaces that only come up later.
                val prefs = runCatching { xposed.getRemotePreferences(PREF_NAME) }.getOrNull()
                val visibleRuleKeys = prefs?.all?.count { (k, v) ->
                    v is String && v != CHANNEL_DEFAULT && (k.startsWith("uid_") || k.toIntOrNull() != null)
                }
                xposed.log(
                    Log.INFO,
                    HOOK_TAG,
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
                    xposed.log(Log.INFO, HOOK_TAG, "[BootRestore] Retrying restore broadcast (+${delayMs / 1000}s).")
                    registerKeepAliveReceiver()
                    registerLinkChangeCallback()
                    dispatchRestoreBroadcast()
                }
            } catch (t: Throwable) {
                xposed.log(Log.WARN, HOOK_TAG, "[BootRestore] Watchdog encountered error: ${t.message}")
            }
        }.apply {
            isDaemon = true
            name = "MultiRoute-BootRestoreWatchdog"
            start()
        }
    }

    /**
     * Installs the broadcast receiver in system_server that handles runtime signals from the app
     * (cellular route refresh, on-demand keep-alive hook installation, and SlaveWifi connect/disconnect).
     */
    private fun registerKeepAliveReceiver() {
        if (!keepAliveReceiverRegistered.compareAndSet(false, true)) return
        val context = SystemServerEnv.systemContext() ?: run {
            keepAliveReceiverRegistered.set(false)
            xposed.log(Log.WARN, HOOK_TAG, "[KeepAlive] No system context; hooks can only be installed at boot")
            return
        }
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    // Security: Verify that the broadcast came from MultiRoute's own UID or root/system.
                    val senderUid = runCatching {
                        val m = BroadcastReceiver::class.java.getDeclaredMethod("getSendingUid")
                        m.isAccessible = true
                        (m.invoke(this) as? Number)?.toInt()
                    }.getOrNull()

                    val appUid = ctx?.packageManager?.let { pm ->
                        runCatching { pm.getPackageUid(BuildConfig.APPLICATION_ID, 0) }.getOrNull()
                    }
                    if (!HookPolicyHelpers.isAuthorizedSenderUid(senderUid, Process.myUid(), appUid)) {
                        xposed.log(Log.WARN, HOOK_TAG, "[KeepAlive] Ignored unauthorized keep-alive signal from UID $senderUid")
                        return
                    }

                    when (intent?.action) {
                        ModuleStateParser.ACTION_ROUTES_CHANGED -> onRefreshCellularDefaults()
                        ModuleStateParser.ACTION_KEEPALIVE_CHANGED -> onKeepAliveChanged()
                        ModuleStateParser.ACTION_CONNECT_SLAVE_WIFI -> {
                            val netId = intent.getIntExtra(ModuleStateParser.EXTRA_NET_ID, -1)
                            if (netId >= 0) {
                                onConnectSlaveWifi(netId, intent.getStringExtra(ModuleStateParser.EXTRA_BSSID))
                            }
                        }
                        ModuleStateParser.ACTION_DISCONNECT_SLAVE_WIFI -> onDisconnectSlaveWifi()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(ModuleStateParser.ACTION_KEEPALIVE_CHANGED)
                addAction(ModuleStateParser.ACTION_ROUTES_CHANGED)
                addAction(ModuleStateParser.ACTION_CONNECT_SLAVE_WIFI)
                addAction(ModuleStateParser.ACTION_DISCONNECT_SLAVE_WIFI)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            xposed.log(Log.INFO, HOOK_TAG, "[KeepAlive] Listening for keep-alive changes")
        } catch (t: Throwable) {
            // ActivityManager does not exist this early in system_server startup, so the caller retries.
            keepAliveReceiverRegistered.set(false)
            xposed.log(Log.WARN, HOOK_TAG, "[KeepAlive] Could not register receiver yet: ${t.message}")
        }
    }

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
            val context = SystemServerEnv.systemContext() ?: return
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onLinkChanged()
                override fun onLost(network: Network) = onLinkChanged()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    onLinkChanged()
                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties
                ) = onLinkChanged()
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build(),
                callback
            )
            linkCallbackRegistered = true
            xposed.log(Log.INFO, HOOK_TAG, "[Scenario] Watching link changes in system_server to wake the app")
        } catch (t: Throwable) {
            xposed.log(Log.WARN, HOOK_TAG, "[Scenario] Could not register the link-change callback: ${t.message}", t)
        }
    }

    /** True while at least one scenario plan exists, as published by the app on every sync. */
    private fun hasScenarioPlans(): Boolean = try {
        val file = File(RouteRuleBuilder.SCENARIO_PLAN_MARKER)
        file.canRead() && (file.readText().trim().toIntOrNull() ?: 0) > 0
    } catch (_: Throwable) {
        false
    }

    /**
     * Wakes the app when a link changes, but only while a plan exists: with no plans the rule set does not
     * depend on the current links, so there is nothing to re-evaluate and the app may stay asleep. The
     * debounce is needed because one transition produces several callbacks.
     */
    private fun onLinkChanged() {
        // Network identities can change without a UID or subscription change, including without plans.
        onRefreshCellularDefaults()
        val now = System.currentTimeMillis()
        if (now - lastScenarioWakeAt < 3000L) return
        lastScenarioWakeAt = now
        if (!hasScenarioPlans()) return
        xposed.log(Log.INFO, HOOK_TAG, "[Scenario] Link changed and plans exist; waking the app to re-evaluate")
        dispatchRestoreBroadcast()
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
            if (SystemServerEnv.readSystemProperty("sys.boot_completed") == "1") return
            Thread.sleep(2_000)
        }
        xposed.log(Log.WARN, HOOK_TAG, "[BootRestore] sys.boot_completed still unset after ${maxWaitMs / 1000}s; waking anyway")
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
            val context = SystemServerEnv.systemContext() ?: return false
            val intent = Intent(ACTION_RESTORE_RULES).apply {
                // Explicit component: an implicit broadcast is dropped when the app is not running,
                // which is exactly the boot-time case this exists for.
                component = ComponentName(RESTORE_PACKAGE, RESTORE_RECEIVER_CLASS)
                addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND)
            }
            context.sendBroadcast(intent)
            xposed.log(Log.INFO, HOOK_TAG, "[BootRestore] Restore broadcast sent in-process.")
            true
        } catch (t: Throwable) {
            xposed.log(Log.WARN, HOOK_TAG, "[BootRestore] In-process broadcast failed: ${t.message}")
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
                xposed.log(Log.INFO, HOOK_TAG, "[BootRestore] Broadcast dispatched (rc=${proc.exitValue()}): $tail")
            } else {
                proc.destroyForcibly()
                xposed.log(Log.WARN, HOOK_TAG, "[BootRestore] am broadcast timed out after 10s; aborting.")
            }
        } catch (t: Throwable) {
            xposed.log(Log.WARN, HOOK_TAG, "[BootRestore] Failed to dispatch restore broadcast: ${t.message}")
        }
    }
}

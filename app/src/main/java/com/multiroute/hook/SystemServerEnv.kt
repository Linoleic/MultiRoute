package com.multiroute.hook

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.multiroute.BuildConfig
import com.multiroute.util.ModuleStateParser
import io.github.libxposed.api.XposedInterface
import java.io.File

internal const val HOOK_TAG = "MultiRoute-SystemServer"
internal const val PREF_NAME = "multiroute_rules"
internal const val CHANNEL_DEFAULT = "default"

/**
 * Shared reflection utilities for interacting with the system_server runtime environment
 * (Context, SystemProperties, IPowerManager).
 */
internal object SystemServerEnv {

    /** The system_server context, used for Settings.Global writes and for receiving broadcasts. */
    fun systemContext(): Context? = try {
        // NOTE: ActivityThread.getSystemContext() is an INSTANCE method, so the current
        // ActivityThread must be obtained first - invoking it with a null receiver throws
        // NullPointerException("null receiver") and silently loses this channel.
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        currentThread?.let {
            activityThreadClass.getMethod("getSystemContext").invoke(it) as? Context
        }
    } catch (_: Throwable) {
        null
    }

    /** Reads a system property, or null when it is unset/unreadable. */
    fun readSystemProperty(name: String): String? = try {
        val spClass = Class.forName("android.os.SystemProperties")
        val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
        (getMethod.invoke(null, name, "") as? String)?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    fun setSystemProperty(name: String, value: String) {
        val spClass = Class.forName("android.os.SystemProperties")
        spClass.getMethod("set", String::class.java, String::class.java)
            .invoke(null, name, value)
    }

    fun isScreenInteractive(): Boolean {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "power") as? IBinder ?: return true
            val ipmClass = Class.forName("android.os.IPowerManager\$Stub")
            val asInterfaceMethod = ipmClass.getMethod("asInterface", IBinder::class.java)
            val ipm = asInterfaceMethod.invoke(null, binder) ?: return true
            val isInteractiveMethod = ipm.javaClass.getMethod("isInteractive")
            isInteractiveMethod.invoke(ipm) as Boolean
        } catch (_: Throwable) {
            true
        }
    }
}

/**
 * Publishes a structured state beacon so the app can tell "module code loaded" apart from
 * "hooks actually installed", and can detect that system_server still executes an older module
 * build (a System Framework scoped module cannot be hot-reloaded, so hook updates need a reboot).
 *
 * Channels, in order of usefulness: Settings.Global (readable by the app without root or su)
 * -> compact system property -> marker file (root-based diagnostics) -> legacy active flag.
 */
internal class ModuleBeaconPublisher(
    private val xposed: XposedInterface,
    private val connectivityHookCount: () -> Int,
    private val slaveWifiHookCount: () -> Int,
    private val dualStaHookCount: () -> Int
) {
    fun publish(source: String, stage: String) {
        val csCount = connectivityHookCount()
        val slaveCount = slaveWifiHookCount()
        val dualCount = dualStaHookCount()
        val pid = Process.myPid()
        val versionCode = BuildConfig.VERSION_CODE.toLong()
        val buildId = BuildConfig.HOOK_BUILD_ID
        val bootElapsedMs = SystemClock.elapsedRealtime()

        val beacon = ModuleStateParser.buildBeacon(
            pid = pid,
            moduleVersionCode = versionCode,
            buildId = buildId,
            bootElapsedMs = bootElapsedMs,
            writtenAtMs = System.currentTimeMillis(),
            connectivityHooks = csCount,
            slaveWifiHooks = slaveCount,
            dualStaHooks = dualCount,
            stage = stage
        )

        try {
            val sysCtx = SystemServerEnv.systemContext()
            if (sysCtx != null) {
                Settings.Global.putString(
                    sysCtx.contentResolver,
                    ModuleStateParser.STATE_KEY,
                    beacon
                )
            } else {
                xposed.log(Log.WARN, HOOK_TAG, "[$source] System context not ready; Settings.Global channel skipped")
            }
        } catch (t: Throwable) {
            xposed.log(Log.WARN, HOOK_TAG, "[$source] Could not publish state to Settings.Global: ${t.message}")
        }

        // Compact beacon in a system property: readable by any process without root, a settings
        // provider round trip or a ContentResolver (property values are capped at 92 bytes).
        try {
            val compact = ModuleStateParser.buildCompactBeacon(
                pid = pid,
                moduleVersionCode = versionCode,
                buildId = buildId,
                bootElapsedMs = bootElapsedMs,
                connectivityHooks = csCount,
                slaveWifiHooks = slaveCount,
                dualStaHooks = dualCount,
                stage = stage
            )
            SystemServerEnv.setSystemProperty(ModuleStateParser.STATE_PROPERTY, compact)
        } catch (t: Throwable) {
            xposed.log(Log.WARN, HOOK_TAG, "[$source] Could not publish state property: ${t.message}")
        }

        try {
            val markerFile = File(ModuleStateParser.MARKER_PATH)
            markerFile.writeText(beacon)
            markerFile.setReadable(true, false)
            markerFile.setWritable(true, true)
        } catch (t: Throwable) {
            xposed.log(Log.WARN, HOOK_TAG, "[$source] Could not write ${ModuleStateParser.MARKER_PATH}: ${t.message}")
        }

        try {
            SystemServerEnv.setSystemProperty("sys.multiroute.active", "1")
        } catch (_: Throwable) {
        }

        xposed.log(Log.INFO, HOOK_TAG, "[$source] Published module state ($stage): $beacon")
    }
}

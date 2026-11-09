package com.multiroute.util

import com.multiroute.BuildConfig

/**
 * Manages personal hotspot (SoftAP) upstream preferences and inspects the NetworkStack
 * tethering hook status beacon.
 */
internal class HotspotShellHelper(
    private val shell: RootShellExecutor
) {
    companion object {
        private const val SETTING_HOTSPOT_UPSTREAM = "multiroute_hotspot_upstream"
        private const val ACTION_UPDATE_HOTSPOT_UPSTREAM = "com.multiroute.ACTION_UPDATE_HOTSPOT_UPSTREAM"
    }

    fun setHotspotUpstream(target: String): Boolean {
        val cleanTarget = HotspotUpstreamPolicy.normalize(target)
        if (!cleanTarget.matches(Regex("[a-zA-Z0-9_.:-]+"))) return false
        val cmd = if (cleanTarget.isEmpty() || cleanTarget.equals("default", ignoreCase = true)) {
            "settings put global $SETTING_HOTSPOT_UPSTREAM default && setprop persist.multiroute.hotspot_upstream ''"
        } else {
            "settings put global $SETTING_HOTSPOT_UPSTREAM '$cleanTarget' && setprop persist.multiroute.hotspot_upstream '$cleanTarget'"
        }
        val success = shell.executeCommand(cmd)
        if (success) shell.executeCommand("am broadcast -a $ACTION_UPDATE_HOTSPOT_UPSTREAM")
        return success
    }

    fun getHotspotUpstream(): String {
        val lines = shell.executeShellWithOutput("settings get global $SETTING_HOTSPOT_UPSTREAM")
        val res = lines.firstOrNull()?.trim() ?: ""
        return if (res.isEmpty() || res == "null") "default" else res
    }

    fun getHotspotActiveUpstream(): String {
        val lines = shell.executeShellWithOutput("dumpsys tethering 2>/dev/null | grep -i 'current upstream interface'")
        val line = lines.firstOrNull() ?: return "none"
        val match = Regex("""\[(.*?)\]""").find(line)
        return match?.groupValues?.get(1)?.trim()?.ifEmpty { "none" } ?: "none"
    }

    fun getHotspotHookStatus(): HotspotHookState {
        // Query each package's real dataDir (DE on the Xiaomi test device), with CE/DE fallbacks.
        // The process identity travels with each candidate; a dumpsys result is never load evidence.
        val output = shell.executeShellWithOutput(
            """
            for pkg in com.android.networkstack.tethering com.google.android.networkstack.tethering; do
              dir=${'$'}(/system/bin/dumpsys package "${'$'}pkg" 2>/dev/null | /system/bin/sed -n 's/^[[:space:]]*dataDir=//p' | /system/bin/head -n 1)
              for base in "${'$'}dir" "/data/user_de/0/${'$'}pkg" "/data/user/0/${'$'}pkg"; do
                [ -n "${'$'}base" ] || continue
                file="${'$'}base/cache/${HotspotHookStateParser.FILE_NAME}"
                [ -r "${'$'}file" ] || continue
                beacon=${'$'}(/system/bin/cat "${'$'}file")
                pid=${'$'}(printf '%s' "${'$'}beacon" | /system/bin/sed -n 's/.*;pid=\([0-9][0-9]*\);.*/\1/p')
                case "${'$'}pid" in ''|*[!0-9]*) continue;; esac
                cmd=${'$'}(/system/bin/cat "/proc/${'$'}pid/cmdline" 2>/dev/null | /system/bin/tr '\000' ' ')
                case "${'$'}cmd" in com.android.networkstack*|com.google.android.networkstack*) ;;
                  *) continue;; esac
                echo "BEACON=${'$'}beacon"
                echo "PID=${'$'}pid"
                echo "STAT=${'$'}(/system/bin/cat /proc/${'$'}pid/stat 2>/dev/null)"
              done
            done
            echo "LIVE=${'$'}(/system/bin/pidof com.android.networkstack.process com.google.android.networkstack.process)"
            """.trimIndent(),
            timeoutSeconds = 5,
            globalMountNamespace = true
        )
        var mismatch = false
        output.indices.filter { output[it].startsWith("BEACON=") }.forEach { index ->
            val pid = output.getOrNull(index + 1)?.removePrefix("PID=")?.toIntOrNull()
            val ticks = HotspotHookStateParser.processStartTicks(output.getOrNull(index + 2)?.removePrefix("STAT="))
            when (
                val state = HotspotHookStateParser.evaluate(
                    output[index].removePrefix("BEACON="),
                    pid,
                    ticks,
                    BuildConfig.HOTSPOT_HOOK_BUILD_ID
                )
            ) {
                HotspotHookState.LOADED -> return state
                HotspotHookState.VERSION_MISMATCH -> mismatch = true
                else -> Unit
            }
        }
        if (mismatch) return HotspotHookState.VERSION_MISMATCH
        return if (output.any { it.startsWith("LIVE=") && it.removePrefix("LIVE=").isNotBlank() }) {
            HotspotHookState.NOT_LOADED
        } else {
            HotspotHookState.UNKNOWN
        }
    }
}

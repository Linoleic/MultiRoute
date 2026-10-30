package com.multiroute.cellular

import android.content.Context
import com.multiroute.util.SuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Manages only our own root process; it does not persist or auto-start at boot. */
object DualSimController {
    private const val ENTRY = "com.multiroute.cellular.DualSimDaemon"
    private val mutex = Mutex()
    private val linePattern = Regex("^\\s*(\\d+)\\s+\\S*app_process(?:32|64)?\\s+/system/bin\\s+$ENTRY\\s+([12])\\s*$")

    private fun processes(): List<Pair<Int, Int>> =
        SuHelper.executeShellWithOutput("ps -A -o PID,ARGS", timeoutSeconds = 5).mapNotNull { line ->
            linePattern.matchEntire(line)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        }

    suspend fun status(): Int = withContext(Dispatchers.IO) { processes().singleOrNull()?.second ?: 0 }

    suspend fun select(context: Context, selected: Int): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(selected in 0..2)
            if (!SuHelper.isRootAvailable()) return@withLock false
            val running = processes()
            if (running.size == 1 && running[0].second == selected) return@withLock true
            val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
            for ((pid, _) in running) {
                // Android app_process may exit on SIGTERM without running Java shutdown hooks.
                // Ask the daemon to release requests and restore properties before acknowledging.
                if (!SuHelper.executeCommand("/system/bin/env CLASSPATH='$apk' /system/bin/app_process /system/bin $ENTRY stop $pid",
                        timeoutSeconds = 8)) return@withLock false
            }
            var attempts = 0
            while (attempts++ < 30 && processes().isNotEmpty()) {
                delay(100)
            }
            if (processes().isNotEmpty()) return@withLock false
            if (selected == 0) return@withLock true
            val command = "setsid /system/bin/env CLASSPATH='$apk' /system/bin/app_process /system/bin $ENTRY $selected </dev/null >/dev/null 2>&1 &"
            if (!SuHelper.executeCommand(command, timeoutSeconds = 5)) return@withLock false
            delay(1200)
            processes().singleOrNull()?.second == selected
        }
    }
}

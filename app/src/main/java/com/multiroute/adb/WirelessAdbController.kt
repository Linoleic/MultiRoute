package com.multiroute.adb

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.util.AtomicFile
import com.multiroute.util.SuHelper
import com.multiroute.util.WirelessAdbInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

enum class AdbMode { TLS, TCP }
enum class AdbActionResult { READY, OFF, FAILED, NO_NETWORK, WAIT_NETWORK, WAIT_UNLOCK, EXPIRED, INACTIVE, SAVED }
data class AdbBootPolicy(
    val enabled: Boolean = false,
    val ssid: String? = null,
    val result: AdbActionResult? = null,
    val mode: AdbMode = AdbMode.TLS,
    val customPort: Int = 5555
)

object WirelessAdbController {
    private val mutex = Mutex()
    private val diskLock = Any()
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "wireless-adb-policy.json"))

    fun policy(context: Context): AdbBootPolicy = synchronized(diskLock) { try {
        val data = JSONObject(file(context).readFully().toString(Charsets.UTF_8))
        val mode = runCatching { AdbMode.valueOf(data.optString("mode")) }.getOrDefault(AdbMode.TLS)
        val customPort = data.optInt("customPort", 5555).let { if (it in 1024..65535) it else 5555 }
        AdbBootPolicy(
            enabled = data.optBoolean("enabled"),
            ssid = data.optString("ssid").takeIf { it.isNotEmpty() },
            result = runCatching { AdbActionResult.valueOf(data.optString("result")) }.getOrNull(),
            mode = mode,
            customPort = customPort
        )
    } catch (_: Exception) { AdbBootPolicy() } }

    private fun save(context: Context, policy: AdbBootPolicy): Boolean = synchronized(diskLock) {
        val target = file(context)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = target.startWrite()
            val json = JSONObject()
                .put("enabled", policy.enabled)
                .put("ssid", policy.ssid.orEmpty())
                .put("result", policy.result?.name.orEmpty())
                .put("mode", policy.mode.name)
                .put("customPort", policy.customPort)
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            true
        } catch (_: Exception) { stream?.let { target.failWrite(it) }; false }
    }

    fun updateConfig(context: Context, mode: AdbMode, customPort: Int): Boolean = synchronized(diskLock) {
        val current = policy(context)
        val validPort = if (customPort in 1024..65535) customPort else 5555
        save(context, current.copy(mode = mode, customPort = validPort))
    }

    internal fun primarySsid(lines: List<String>): String? {
        val section = lines.indexOfFirst { "ROLE_CLIENT_PRIMARY" in it }
        val selected = if (section >= 0) lines.drop(section + 1).takeWhile { !it.startsWith("====") }
            else if (lines.any { it.startsWith("====") }) emptyList() else lines
        return selected.firstNotNullOfOrNull {
            Regex("^Wifi is connected to \"(.*)\"$").matchEntire(it.trim())?.groupValues?.get(1)
        }?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }
    private fun wifi() = SuHelper.executeShellWithOutput("cmd wifi status && echo WIFI_QUERY_OK", timeoutSeconds = 4)

    internal fun restoreGate(policy: AdbBootPolicy, ssid: String?, unlocked: Boolean, now: Long, deadline: Long): AdbActionResult? = when {
        !policy.enabled -> AdbActionResult.INACTIVE
        now >= deadline -> AdbActionResult.EXPIRED
        !unlocked -> AdbActionResult.WAIT_UNLOCK
        policy.ssid == null || ssid != policy.ssid -> AdbActionResult.WAIT_NETWORK
        else -> null
    }

    private suspend fun switch(context: Context, enabled: Boolean, mode: AdbMode, customPort: Int): AdbActionResult {
        currentCoroutineContext().ensureActive()
        if (enabled && SuHelper.executeShellWithOutput("settings get global development_settings_enabled", timeoutSeconds = 4)
                .firstOrNull()?.trim() != "1") return AdbActionResult.FAILED
        val validPort = if (customPort in 1024..65535) customPort else 5555
        val ok = if (enabled) {
            when (mode) {
                AdbMode.TLS -> {
                    val tcpPortProp = SuHelper.executeShellWithOutput("getprop service.adb.tcp.port", timeoutSeconds = 2)
                        .firstOrNull()?.trim()
                    val tcpPortActive = tcpPortProp != null && tcpPortProp != "-1" && tcpPortProp.toIntOrNull()?.let { it > 0 } == true
                    if (tcpPortActive) {
                        SuHelper.executeCommand(
                            "setprop service.adb.tcp.port -1 && setprop persist.adb.tcp.port \"\" && setprop ctl.restart adbd",
                            timeoutSeconds = 4
                        )
                        delay(800)
                    } else {
                        SuHelper.executeCommand(
                            "setprop service.adb.tcp.port -1 && setprop persist.adb.tcp.port \"\"",
                            timeoutSeconds = 2
                        )
                    }
                    SuHelper.executeCommand(
                        "settings put global adb_wifi_enabled 0 && sleep 0.2 && settings put global adb_wifi_enabled 1",
                        timeoutSeconds = 4
                    )
                }
                AdbMode.TCP -> {
                    SuHelper.executeCommand(
                        "settings put global adb_wifi_enabled 0 && setprop service.adb.tcp.port $validPort && setprop persist.adb.tcp.port \"\" && setprop ctl.restart adbd",
                        timeoutSeconds = 4
                    )
                }
            }
        } else {
            SuHelper.executeCommand(
                "settings put global adb_wifi_enabled 0 && setprop service.adb.tcp.port -1 && setprop persist.adb.tcp.port \"\" && setprop ctl.restart adbd",
                timeoutSeconds = 4
            )
        }
        if (!ok) return AdbActionResult.FAILED

        repeat(12) {
            currentCoroutineContext().ensureActive()
            val info = WirelessAdbInfo.read(context, preferredMode = mode)
            if (enabled) {
                if (info.listening == true && info.port != null) {
                    if (mode == AdbMode.TLS && (info.enabled == true || info.mode == AdbMode.TLS)) return AdbActionResult.READY
                    if (mode == AdbMode.TCP && info.port == validPort) return AdbActionResult.READY
                }
            } else {
                if (info.enabled == false && (info.listening != true || info.port == null)) return AdbActionResult.OFF
            }
            delay(500)
        }
        return AdbActionResult.FAILED
    }

    suspend fun setEnabled(
        context: Context,
        enabled: Boolean,
        mode: AdbMode = policy(context).mode,
        customPort: Int = policy(context).customPort
    ): AdbActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentPolicy = policy(context)
            val validPort = if (customPort in 1024..65535) customPort else 5555
            if (!enabled) {
                // Persist the cancellation before writing the system toggle. A queued boot job must not re-enable it.
                if (!save(context, currentPolicy.copy(enabled = false, result = AdbActionResult.INACTIVE)))
                    return@withLock AdbActionResult.FAILED
                WirelessAdbBootJob.cancel(context)
            } else {
                val wifi = wifi()
                if ("WIFI_QUERY_OK" !in wifi) return@withLock AdbActionResult.FAILED
                if (primarySsid(wifi) == null) return@withLock AdbActionResult.NO_NETWORK
                save(context, currentPolicy.copy(mode = mode, customPort = validPort))
            }
            switch(context, enabled, mode, validPort)
        }
    }

    suspend fun setBootEnabled(
        context: Context,
        enabled: Boolean,
        mode: AdbMode = policy(context).mode,
        customPort: Int = policy(context).customPort
    ): AdbActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentPolicy = policy(context)
            val validPort = if (customPort in 1024..65535) customPort else 5555
            if (!enabled) {
                if (!save(context, currentPolicy.copy(enabled = false, result = AdbActionResult.INACTIVE)))
                    return@withLock AdbActionResult.FAILED
                WirelessAdbBootJob.cancel(context)
                return@withLock AdbActionResult.SAVED
            }
            val wifi = wifi()
            if ("WIFI_QUERY_OK" !in wifi) return@withLock AdbActionResult.FAILED
            val ssid = primarySsid(wifi) ?: return@withLock AdbActionResult.NO_NETWORK
            val info = WirelessAdbInfo.read(context, preferredMode = mode)
            if (info.listening != true || info.port == null) return@withLock AdbActionResult.FAILED
            if (save(context, AdbBootPolicy(true, ssid, AdbActionResult.SAVED, mode, validPort))) AdbActionResult.SAVED else AdbActionResult.FAILED
        }
    }

    suspend fun restore(context: Context, deadline: Long): AdbActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val policy = policy(context)
            if (!policy.enabled) return@withLock AdbActionResult.INACTIVE
            val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
            val now = SystemClock.elapsedRealtime()
            val gate = restoreGate(policy, null, unlocked, now, deadline)
            val result = if (gate == AdbActionResult.EXPIRED || gate == AdbActionResult.WAIT_UNLOCK) gate else {
                val wifi = wifi()
                if ("WIFI_QUERY_OK" !in wifi) AdbActionResult.FAILED
                else restoreGate(policy, primarySsid(wifi), unlocked, SystemClock.elapsedRealtime(), deadline)
                    ?: switch(context, true, policy.mode, policy.customPort)
            }
            save(context, policy.copy(result = result))
            result
        }
    }
}

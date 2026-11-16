package com.multiroute.adb

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.util.AtomicFile
import android.util.Log
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
    private const val TAG = "MultiRoute-AdbBoot"
    private val mutex = Mutex()
    private val diskLock = Any()
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "wireless-adb-policy.json"))

    private fun syncBootScript(context: Context, policy: AdbBootPolicy) {
        try {
            val ssid = policy.ssid
            if (policy.enabled && !ssid.isNullOrBlank()) {
                val script = WirelessAdbBootScriptBuilder.buildBootScript(
                    ssid = ssid,
                    mode = policy.mode,
                    customPort = policy.customPort,
                    policyFilePath = file(context).baseFile.absolutePath
                )
                val logDir = WirelessAdbBootScriptBuilder.BOOT_LOG_PATH.substringBeforeLast('/', "/data/adb/multiroute")
                val cmd = "mkdir -p /data/adb/service.d '$logDir' && " +
                    "cat << 'MCROUTE_ADB_EOF' > ${WirelessAdbBootScriptBuilder.BOOT_SCRIPT_PATH}\n" +
                    script +
                    "MCROUTE_ADB_EOF\n" +
                    "chmod 755 ${WirelessAdbBootScriptBuilder.BOOT_SCRIPT_PATH}"
                if (!SuHelper.executeCommand(cmd, timeoutSeconds = 5)) {
                    Log.w(TAG, "Failed to deploy wireless ADB boot script")
                }
            } else {
                SuHelper.executeCommand(
                    "rm -f ${WirelessAdbBootScriptBuilder.BOOT_SCRIPT_PATH} ${WirelessAdbBootScriptBuilder.BOOT_STATUS_PATH}",
                    timeoutSeconds = 4
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to sync wireless ADB boot script: ${t.message}")
        }
    }

    private fun writeBootStatus(
        bootCount: Int?,
        uptimeSeconds: Long,
        source: String,
        mode: AdbMode,
        port: Int?,
        result: AdbActionResult,
        detail: String
    ) {
        runCatching {
            val content = WirelessAdbBootScriptBuilder.formatStatus(
                bootCount = bootCount,
                uptimeSeconds = uptimeSeconds,
                source = source,
                mode = mode,
                port = port,
                result = result,
                detail = detail
            )
            val statusDir = WirelessAdbBootScriptBuilder.BOOT_STATUS_PATH.substringBeforeLast('/', "/data/adb/multiroute")
            val cmd = "mkdir -p '$statusDir' && " +
                "cat << 'MCROUTE_ADB_STATUS_EOF' > ${WirelessAdbBootScriptBuilder.BOOT_STATUS_PATH}.tmp\n" +
                content +
                "MCROUTE_ADB_STATUS_EOF\n" +
                "mv ${WirelessAdbBootScriptBuilder.BOOT_STATUS_PATH}.tmp ${WirelessAdbBootScriptBuilder.BOOT_STATUS_PATH}"
            SuHelper.executeCommand(cmd, timeoutSeconds = 4)
        }.onFailure {
            Log.w(TAG, "Failed to write wireless ADB boot status: ${it.message}")
        }
    }

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

    fun updateConfig(context: Context, mode: AdbMode, customPort: Int): Boolean {
        val (saved, updated) = synchronized(diskLock) {
            val current = policy(context)
            val validPort = if (customPort in 1024..65535) customPort else 5555
            val next = current.copy(mode = mode, customPort = validPort)
            save(context, next) to next
        }
        if (saved && updated.enabled) {
            syncBootScript(context, updated)
        }
        return saved
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

    private data class SwitchOutcome(
        val result: AdbActionResult,
        val port: Int? = null,
        val detail: String = ""
    )

    private suspend fun switchWithDetail(
        context: Context,
        enabled: Boolean,
        mode: AdbMode,
        customPort: Int
    ): SwitchOutcome {
        currentCoroutineContext().ensureActive()
        if (enabled && SuHelper.executeShellWithOutput("settings get global development_settings_enabled", timeoutSeconds = 4)
                .firstOrNull()?.trim() != "1") return SwitchOutcome(AdbActionResult.FAILED, detail = "DEV_OFF")
        val validPort = if (customPort in 1024..65535) customPort else 5555
        var wasTrustedTlsBeforeTcpSwitch = false
        if (enabled) {
            val current = WirelessAdbInfo.read(context, preferredMode = mode)
            if (mode == AdbMode.TLS && current.enabled == true && current.listening == true &&
                current.tcpPort == null && current.port != null
            ) {
                val detail = if (current.systemTrustedNetwork == true) "ALREADY_ACTIVE_TRUSTED" else "ALREADY_ACTIVE"
                return SwitchOutcome(AdbActionResult.READY, port = current.port, detail = detail)
            }
            if (mode == AdbMode.TCP) {
                if (current.listening == true && current.tcpPort == validPort && current.tlsEnabled != true) {
                    val wifiFlag = SuHelper.executeShellWithOutput("settings get global adb_wifi_enabled", timeoutSeconds = 2)
                        .firstOrNull()?.trim()
                    if (wifiFlag == "0") {
                        return SwitchOutcome(AdbActionResult.READY, port = validPort, detail = "ALREADY_ACTIVE")
                    }
                }
                wasTrustedTlsBeforeTcpSwitch = current.systemTrustedNetwork == true && current.tlsPort != null
            }
        }
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
                        "settings put global adb_wifi_enabled 0 && sleep 0.2 && setprop persist.adb.tls_server.enable 0 && setprop service.adb.tcp.port $validPort && setprop persist.adb.tcp.port \"\" && setprop ctl.restart adbd",
                        timeoutSeconds = 4
                    )
                }
            }
        } else {
            SuHelper.executeCommand(
                "settings put global adb_wifi_enabled 0 && setprop persist.adb.tls_server.enable 0 && setprop service.adb.tcp.port -1 && setprop persist.adb.tcp.port \"\" && setprop ctl.restart adbd",
                timeoutSeconds = 4
            )
        }
        if (!ok) return SwitchOutcome(AdbActionResult.FAILED, detail = "SHELL_FAILED")

        repeat(12) {
            currentCoroutineContext().ensureActive()
            val info = WirelessAdbInfo.read(context, preferredMode = mode)
            if (enabled) {
                if (info.listening == true && info.port != null) {
                    if (mode == AdbMode.TLS && (info.enabled == true || info.mode == AdbMode.TLS)) {
                        return SwitchOutcome(AdbActionResult.READY, port = info.port, detail = "SWITCHED")
                    }
                    if (mode == AdbMode.TCP && info.port == validPort) {
                        val detail = if (wasTrustedTlsBeforeTcpSwitch) "SWITCHED_OVERRIDE" else "SWITCHED"
                        return SwitchOutcome(AdbActionResult.READY, port = info.port, detail = detail)
                    }
                }
            } else {
                if (info.enabled == false && (info.listening != true || info.port == null)) {
                    return SwitchOutcome(AdbActionResult.OFF, detail = "STOPPED")
                }
            }
            delay(500)
        }
        return SwitchOutcome(AdbActionResult.FAILED, detail = "TIMEOUT")
    }

    private suspend fun switch(context: Context, enabled: Boolean, mode: AdbMode, customPort: Int): AdbActionResult =
        switchWithDetail(context, enabled, mode, customPort).result

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
                val disabledPolicy = currentPolicy.copy(enabled = false, result = AdbActionResult.INACTIVE)
                if (!save(context, disabledPolicy))
                    return@withLock AdbActionResult.FAILED
                WirelessAdbBootJob.cancel(context)
                syncBootScript(context, disabledPolicy)
            } else {
                val wifi = wifi()
                if ("WIFI_QUERY_OK" !in wifi) return@withLock AdbActionResult.FAILED
                if (primarySsid(wifi) == null) return@withLock AdbActionResult.NO_NETWORK
                val updated = currentPolicy.copy(mode = mode, customPort = validPort)
                if (save(context, updated) && updated.enabled) {
                    syncBootScript(context, updated)
                }
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
                val disabledPolicy = currentPolicy.copy(enabled = false, result = AdbActionResult.INACTIVE)
                if (!save(context, disabledPolicy))
                    return@withLock AdbActionResult.FAILED
                WirelessAdbBootJob.cancel(context)
                syncBootScript(context, disabledPolicy)
                return@withLock AdbActionResult.SAVED
            }
            val wifi = wifi()
            if ("WIFI_QUERY_OK" !in wifi) return@withLock AdbActionResult.FAILED
            val ssid = primarySsid(wifi) ?: return@withLock AdbActionResult.NO_NETWORK
            val info = WirelessAdbInfo.read(context, preferredMode = mode)
            if (info.listening != true || info.port == null) return@withLock AdbActionResult.FAILED
            val savedPolicy = AdbBootPolicy(true, ssid, AdbActionResult.SAVED, mode, validPort)
            if (!save(context, savedPolicy)) return@withLock AdbActionResult.FAILED
            syncBootScript(context, savedPolicy)
            AdbActionResult.SAVED
        }
    }

    suspend fun restore(context: Context, deadline: Long): AdbActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val policy = policy(context)
            if (!policy.enabled) return@withLock AdbActionResult.INACTIVE
            syncBootScript(context, policy)

            val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
            val now = SystemClock.elapsedRealtime()
            val uptimeSeconds = now / 1000L

            val liveInfo = if (unlocked) WirelessAdbInfo.read(context, preferredMode = policy.mode) else null
            val existingStatus = liveInfo?.bootStatus
            val bootCount = liveInfo?.currentBootCount
            val alreadyRestoredThisBoot = existingStatus != null &&
                bootCount != null &&
                existingStatus.bootCount == bootCount &&
                existingStatus.result == AdbActionResult.READY &&
                existingStatus.mode == policy.mode &&
                (policy.mode != AdbMode.TCP || existingStatus.port == policy.customPort) &&
                liveInfo.enabled == true &&
                liveInfo.listening == true &&
                (policy.mode != AdbMode.TCP || liveInfo.tcpPort == policy.customPort)
            if (alreadyRestoredThisBoot) {
                save(context, policy.copy(result = AdbActionResult.READY))
                return@withLock AdbActionResult.READY
            }

            val gate = restoreGate(policy, null, unlocked, now, deadline)
            val outcome = if (gate == AdbActionResult.EXPIRED || gate == AdbActionResult.WAIT_UNLOCK) {
                SwitchOutcome(gate, detail = gate.name)
            } else {
                val wifi = wifi()
                if ("WIFI_QUERY_OK" !in wifi) {
                    SwitchOutcome(AdbActionResult.FAILED, detail = "WIFI_QUERY_FAILED")
                } else {
                    val netGate = restoreGate(policy, primarySsid(wifi), unlocked, SystemClock.elapsedRealtime(), deadline)
                    if (netGate != null) {
                        SwitchOutcome(netGate, detail = netGate.name)
                    } else {
                        switchWithDetail(context, true, policy.mode, policy.customPort)
                    }
                }
            }
            if (outcome.result != AdbActionResult.WAIT_UNLOCK) {
                writeBootStatus(
                    bootCount = bootCount,
                    uptimeSeconds = uptimeSeconds,
                    source = "BootJob",
                    mode = policy.mode,
                    port = outcome.port ?: if (policy.mode == AdbMode.TCP) policy.customPort else null,
                    result = outcome.result,
                    detail = outcome.detail
                )
            }
            save(context, policy.copy(result = outcome.result))
            outcome.result
        }
    }
}

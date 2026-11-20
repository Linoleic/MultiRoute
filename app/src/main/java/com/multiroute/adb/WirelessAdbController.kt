package com.multiroute.adb

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import android.util.AtomicFile
import android.util.Log
import com.multiroute.data.RouteConfigProvider
import com.multiroute.util.ModuleStateParser
import com.multiroute.util.SuHelper
import com.multiroute.util.WirelessAdbInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class AdbMode { TLS, TCP }
enum class AdbActionResult { READY, OFF, FAILED, NO_NETWORK, WAIT_NETWORK, WAIT_UNLOCK, EXPIRED, INACTIVE, SAVED }
internal enum class AdbTriggerDecision { ENABLE_TARGET, SUPPRESS_SYSTEM_TLS, WAIT_UNLOCK, WAIT_NETWORK, INACTIVE }

data class AdbBootPolicy(
    val restoreOnBoot: Boolean = false,
    val autoEnableOnWifi: Boolean = false,
    val takeOverSystemAutoStart: Boolean = false,
    val trustedSsids: List<String> = emptyList(),
    val result: AdbActionResult? = null,
    val mode: AdbMode = AdbMode.TLS,
    val customPort: Int = 5555
) {
    val hasAnyTrigger: Boolean get() = restoreOnBoot || autoEnableOnWifi || takeOverSystemAutoStart
}

object WirelessAdbController {
    private const val TAG = "MultiRoute-AdbAuto"
    private const val LEGACY_BOOT_SCRIPT_PATH = "/data/adb/service.d/02-multiroute-adb.sh"
    private const val LEGACY_BOOT_STATUS_PATH = "/data/adb/multiroute/adb_boot_status"
    private const val LEGACY_BOOT_LOG_PATH = "/data/adb/multiroute/last_adb_boot.log"

    private val mutex = Mutex()
    private val diskLock = Any()
    private val legacyCleaned = AtomicBoolean(false)

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "wireless-adb-policy.json"))

    internal fun sanitizeSsids(ssids: Iterable<String>): List<String> =
        ssids.map { it.trim() }
            .filter { it.isNotEmpty() && it != "<unknown ssid>" }
            .distinct()

    internal fun parsePolicyJson(raw: String): AdbBootPolicy {
        val data = JSONObject(raw)
        val mode = runCatching { AdbMode.valueOf(data.optString("mode")) }.getOrDefault(AdbMode.TLS)
        val customPort = data.optInt("customPort", 5555).let { if (it in 1024..65535) it else 5555 }
        val result = runCatching { AdbActionResult.valueOf(data.optString("result")) }.getOrNull()

        val hasNewSchema = data.has("restoreOnBoot") || data.has("autoEnableOnWifi") || data.has("trustedSsids")
        val restoreOnBoot = if (hasNewSchema) data.optBoolean("restoreOnBoot", false) else data.optBoolean("enabled", false)
        val autoEnableOnWifi = data.optBoolean("autoEnableOnWifi", false)
        val takeOver = data.optBoolean("takeOverSystemAutoStart", false)

        val ssids = buildList {
            val arr = data.optJSONArray("trustedSsids")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
            val legacySsid = data.optString("ssid")
            if (legacySsid.isNotEmpty()) add(legacySsid)
        }.let(::sanitizeSsids)

        return AdbBootPolicy(
            restoreOnBoot = restoreOnBoot,
            autoEnableOnWifi = autoEnableOnWifi,
            takeOverSystemAutoStart = takeOver,
            trustedSsids = ssids,
            result = result,
            mode = mode,
            customPort = customPort
        )
    }

    internal fun encodePolicyJson(policy: AdbBootPolicy): String = JSONObject()
        .put("restoreOnBoot", policy.restoreOnBoot)
        .put("autoEnableOnWifi", policy.autoEnableOnWifi)
        .put("takeOverSystemAutoStart", policy.takeOverSystemAutoStart)
        .put("trustedSsids", JSONArray(sanitizeSsids(policy.trustedSsids)))
        .put("result", policy.result?.name.orEmpty())
        .put("mode", policy.mode.name)
        .put("customPort", policy.customPort)
        .toString()

    fun policy(context: Context): AdbBootPolicy = synchronized(diskLock) {
        try {
            parsePolicyJson(file(context).readFully().toString(Charsets.UTF_8))
        } catch (_: Exception) {
            AdbBootPolicy()
        }
    }

    private fun save(context: Context, policy: AdbBootPolicy, syncSystemServer: Boolean = true): Boolean = synchronized(diskLock) {
        val target = file(context)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(encodePolicyJson(policy).toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            if (syncSystemServer) {
                if (!policy.restoreOnBoot) {
                    val status = WirelessAdbStatusStore.readStatus(context)
                    if (status?.bootRecovery != null) {
                        WirelessAdbStatusStore.writeStatus(context, status.copy(bootRecovery = null))
                    }
                }
                mirrorTriggersToPrefs(context, policy)
                publishTriggerSync(context, policy)
            }
            true
        } catch (_: Exception) {
            stream?.let { target.failWrite(it) }
            false
        }
    }

    private fun mirrorTriggersToPrefs(context: Context, policy: AdbBootPolicy) {
        runCatching {
            context.getSharedPreferences(RouteConfigProvider.PREF_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(RouteConfigProvider.KEY_ADB_RESTORE_ON_BOOT, policy.restoreOnBoot)
                .putBoolean(RouteConfigProvider.KEY_ADB_AUTO_ON_WIFI, policy.autoEnableOnWifi)
                .putBoolean(RouteConfigProvider.KEY_ADB_TAKE_OVER_SYSTEM, policy.takeOverSystemAutoStart)
                .putStringSet(RouteConfigProvider.KEY_ADB_TRUSTED_SSIDS, sanitizeSsids(policy.trustedSsids).toSet())
                .commit()
        }
    }

    fun publishTriggerSync(context: Context, policy: AdbBootPolicy = policy(context)) {
        runCatching {
            val intent = Intent(ModuleStateParser.ACTION_ADB_TRIGGER_CHANGED).apply {
                putExtra(ModuleStateParser.EXTRA_ADB_RESTORE_ON_BOOT, policy.restoreOnBoot)
                putExtra(ModuleStateParser.EXTRA_ADB_AUTO_ON_WIFI, policy.autoEnableOnWifi)
                putExtra(ModuleStateParser.EXTRA_ADB_TAKE_OVER_SYSTEM, policy.takeOverSystemAutoStart)
                putStringArrayListExtra(
                    ModuleStateParser.EXTRA_ADB_TRUSTED_SSIDS,
                    ArrayList(sanitizeSsids(policy.trustedSsids))
                )
            }
            context.sendBroadcast(intent)
        }
    }

    private fun cleanupLegacyRootArtifactsOnce() {
        if (!legacyCleaned.compareAndSet(false, true)) return
        runCatching {
            SuHelper.executeCommand(
                "rm -f $LEGACY_BOOT_SCRIPT_PATH $LEGACY_BOOT_STATUS_PATH $LEGACY_BOOT_LOG_PATH",
                timeoutSeconds = 4
            )
        }
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

    suspend fun currentPrimarySsid(): String? = withContext(Dispatchers.IO) {
        val lines = wifi()
        if ("WIFI_QUERY_OK" !in lines) null else primarySsid(lines)
    }

    fun readLogTail(context: Context, maxLines: Int = 30): List<String> =
        WirelessAdbStatusStore.readLogTail(context, maxLines)

    internal fun decideTriggerAction(
        policy: AdbBootPolicy,
        trigger: AdbTriggerSource,
        ssid: String?,
        unlocked: Boolean,
        bootRecoveryPending: Boolean = false
    ): AdbTriggerDecision {
        if (!policy.hasAnyTrigger) return AdbTriggerDecision.INACTIVE
        if (!unlocked) return AdbTriggerDecision.WAIT_UNLOCK

        val validSsid = ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val ssidMatched = validSsid != null && validSsid in policy.trustedSsids

        if (trigger.isBootTrigger) {
            if (policy.restoreOnBoot) {
                return if (ssidMatched) {
                    AdbTriggerDecision.ENABLE_TARGET
                } else if (policy.takeOverSystemAutoStart) {
                    AdbTriggerDecision.SUPPRESS_SYSTEM_TLS
                } else {
                    AdbTriggerDecision.WAIT_NETWORK
                }
            }
            return if (policy.takeOverSystemAutoStart) {
                if (policy.autoEnableOnWifi && ssidMatched) AdbTriggerDecision.ENABLE_TARGET
                else AdbTriggerDecision.SUPPRESS_SYSTEM_TLS
            } else {
                AdbTriggerDecision.INACTIVE
            }
        }

        if (trigger == AdbTriggerSource.WIFI) {
            if (policy.autoEnableOnWifi || (policy.restoreOnBoot && bootRecoveryPending)) {
                return if (ssidMatched) {
                    AdbTriggerDecision.ENABLE_TARGET
                } else if (policy.takeOverSystemAutoStart) {
                    AdbTriggerDecision.SUPPRESS_SYSTEM_TLS
                } else {
                    AdbTriggerDecision.WAIT_NETWORK
                }
            }
            if (policy.takeOverSystemAutoStart) {
                val keepOnMatchedBootSsid = policy.restoreOnBoot && ssidMatched
                return if (keepOnMatchedBootSsid) AdbTriggerDecision.INACTIVE else AdbTriggerDecision.SUPPRESS_SYSTEM_TLS
            }
        }

        return AdbTriggerDecision.INACTIVE
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
                .firstOrNull()?.trim() != "1"
        ) {
            return SwitchOutcome(AdbActionResult.FAILED, detail = "DEV_OFF")
        }
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

    private suspend fun suppressSystemTls(): SwitchOutcome {
        val currentFlags = SuHelper.executeShellWithOutput(
            "echo WIFI=$(settings get global adb_wifi_enabled); echo PERSIST=$(getprop persist.adb.tls_server.enable)",
            timeoutSeconds = 3
        )
        val wifiEnabled = currentFlags.firstOrNull { it.startsWith("WIFI=") }?.substringAfter('=') == "1"
        val persistEnabled = currentFlags.firstOrNull { it.startsWith("PERSIST=") }?.substringAfter('=') == "1"
        if (!wifiEnabled && !persistEnabled) {
            return SwitchOutcome(AdbActionResult.OFF, detail = "SYSTEM_TLS_ALREADY_OFF")
        }
        val ok = SuHelper.executeCommand(
            "settings put global adb_wifi_enabled 0 && setprop persist.adb.tls_server.enable 0",
            timeoutSeconds = 4
        )
        return if (ok) {
            SwitchOutcome(AdbActionResult.OFF, detail = "SYSTEM_TLS_SUPPRESSED")
        } else {
            SwitchOutcome(AdbActionResult.FAILED, detail = "SUPPRESS_FAILED")
        }
    }

    fun updateConfig(context: Context, mode: AdbMode, customPort: Int): Boolean {
        val updated = synchronized(diskLock) {
            val current = policy(context)
            val validPort = if (customPort in 1024..65535) customPort else 5555
            val next = current.copy(mode = mode, customPort = validPort)
            if (save(context, next)) next else null
        }
        cleanupLegacyRootArtifactsOnce()
        return updated != null
    }

    suspend fun setEnabled(
        context: Context,
        enabled: Boolean,
        mode: AdbMode = policy(context).mode,
        customPort: Int = policy(context).customPort
    ): AdbActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanupLegacyRootArtifactsOnce()
            val currentPolicy = policy(context)
            val validPort = if (customPort in 1024..65535) customPort else 5555
            if (!enabled) {
                val nextPolicy = currentPolicy.copy(
                    restoreOnBoot = false,
                    autoEnableOnWifi = false,
                    result = AdbActionResult.INACTIVE
                )
                if (!save(context, nextPolicy)) return@withLock AdbActionResult.FAILED
            } else {
                val wifiLines = wifi()
                if ("WIFI_QUERY_OK" !in wifiLines) return@withLock AdbActionResult.FAILED
                if (primarySsid(wifiLines) == null) return@withLock AdbActionResult.NO_NETWORK
                val updated = currentPolicy.copy(mode = mode, customPort = validPort)
                save(context, updated)
            }

            val outcome = switchWithDetail(context, enabled, mode, validPort)
            val info = WirelessAdbInfo.read(context, preferredMode = mode)
            val recovery = WirelessAdbStatusStore.readStatus(context)?.recoveryForBoot(info.currentBootCount)
            WirelessAdbStatusStore.writeStatus(
                context,
                AdbBootStatus(
                    bootCount = info.currentBootCount,
                    uptimeSeconds = SystemClock.elapsedRealtime() / 1000L,
                    source = "Manual",
                    trigger = AdbTriggerSource.MANUAL,
                    ssid = null,
                    mode = mode,
                    port = outcome.port ?: if (enabled && mode == AdbMode.TCP) validPort else null,
                    result = outcome.result,
                    detail = outcome.detail,
                    timestampMillis = System.currentTimeMillis(),
                    bootRecovery = when {
                        !enabled || !currentPolicy.restoreOnBoot -> null
                        recovery == AdbBootRecovery.PENDING && outcome.result == AdbActionResult.READY ->
                            AdbBootRecovery.COMPLETE
                        else -> recovery
                    }
                )
            )
            WirelessAdbStatusStore.appendLog(
                context,
                "manual enabled=$enabled mode=${mode.name} port=${outcome.port ?: validPort} -> ${outcome.result.name} (${outcome.detail})"
            )
            outcome.result
        }
    }

    suspend fun updateAutomation(
        context: Context,
        restoreOnBoot: Boolean? = null,
        autoEnableOnWifi: Boolean? = null,
        takeOverSystemAutoStart: Boolean? = null,
        mode: AdbMode? = null,
        customPort: Int? = null
    ): AdbActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanupLegacyRootArtifactsOnce()
            val current = policy(context)
            val nextBoot = restoreOnBoot ?: current.restoreOnBoot
            val nextWifi = autoEnableOnWifi ?: current.autoEnableOnWifi
            val nextTakeOver = takeOverSystemAutoStart ?: current.takeOverSystemAutoStart
            val nextMode = mode ?: current.mode
            val validPort = (customPort ?: current.customPort).let { if (it in 1024..65535) it else 5555 }

            var nextSsids = sanitizeSsids(current.trustedSsids)
            if ((nextBoot || nextWifi) && nextSsids.isEmpty()) {
                val wifiLines = wifi()
                if ("WIFI_QUERY_OK" !in wifiLines) return@withLock AdbActionResult.FAILED
                val detected = primarySsid(wifiLines) ?: return@withLock AdbActionResult.NO_NETWORK
                nextSsids = listOf(detected)
            }

            val nextPolicy = current.copy(
                restoreOnBoot = nextBoot,
                autoEnableOnWifi = nextWifi,
                takeOverSystemAutoStart = nextTakeOver,
                trustedSsids = nextSsids,
                mode = nextMode,
                customPort = validPort,
                result = if (nextBoot || nextWifi) AdbActionResult.SAVED else AdbActionResult.INACTIVE
            )
            if (!save(context, nextPolicy)) return@withLock AdbActionResult.FAILED

            if (nextTakeOver && !nextBoot && !nextWifi) {
                val suppressed = suppressSystemTls()
                WirelessAdbStatusStore.appendLog(
                    context,
                    "takeover enabled with auto-triggers off -> ${suppressed.result.name} (${suppressed.detail})"
                )
            } else {
                WirelessAdbStatusStore.appendLog(
                    context,
                    "policy saved boot=$nextBoot wifi=$nextWifi takeover=$nextTakeOver mode=${nextMode.name}:$validPort ssids=$nextSsids"
                )
            }
            AdbActionResult.SAVED
        }
    }

    suspend fun addTrustedSsid(context: Context, ssid: String): AdbActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = ssid.trim()
            if (clean.isEmpty() || clean == "<unknown ssid>") return@withLock AdbActionResult.NO_NETWORK
            val current = policy(context)
            val next = current.copy(trustedSsids = sanitizeSsids(current.trustedSsids + clean))
            if (!save(context, next)) return@withLock AdbActionResult.FAILED
            WirelessAdbStatusStore.appendLog(context, "added trusted SSID: $clean")
            AdbActionResult.SAVED
        }
    }

    suspend fun addCurrentWifiSsid(context: Context): AdbActionResult = withContext(Dispatchers.IO) {
        val lines = wifi()
        if ("WIFI_QUERY_OK" !in lines) return@withContext AdbActionResult.FAILED
        val ssid = primarySsid(lines) ?: return@withContext AdbActionResult.NO_NETWORK
        addTrustedSsid(context, ssid)
    }

    suspend fun removeTrustedSsid(context: Context, ssid: String): AdbActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = policy(context)
            val remaining = current.trustedSsids.filterNot { it == ssid }
            val next = if (remaining.isEmpty()) {
                current.copy(
                    trustedSsids = emptyList(),
                    restoreOnBoot = false,
                    autoEnableOnWifi = false,
                    result = AdbActionResult.INACTIVE
                )
            } else {
                current.copy(trustedSsids = remaining)
            }
            if (!save(context, next)) return@withLock AdbActionResult.FAILED
            WirelessAdbStatusStore.appendLog(context, "removed trusted SSID: $ssid (remaining=$remaining)")
            AdbActionResult.SAVED
        }
    }

    suspend fun handleTrigger(
        context: Context,
        trigger: AdbTriggerSource
    ): AdbActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanupLegacyRootArtifactsOnce()
            val policy = policy(context)
            if (!policy.hasAnyTrigger) return@withLock AdbActionResult.INACTIVE

            val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
            val uptimeSeconds = SystemClock.elapsedRealtime() / 1000L
            val sourceLabel = when (trigger) {
                AdbTriggerSource.BOOT -> "BootWatchdog"
                AdbTriggerSource.UNLOCK -> "UserUnlocked"
                AdbTriggerSource.WIFI -> "WifiCallback"
                AdbTriggerSource.MANUAL -> "Manual"
            }

            if (!unlocked) {
                WirelessAdbStatusStore.appendLog(context, "$sourceLabel skipped: locked")
                return@withLock AdbActionResult.WAIT_UNLOCK
            }

            val liveInfo = WirelessAdbInfo.read(context, preferredMode = policy.mode)
            val bootCount = liveInfo.currentBootCount
            val existingStatus = WirelessAdbStatusStore.readStatus(context)
            val bootRecovery = existingStatus?.recoveryForBoot(bootCount)
            val trackBootRecovery = policy.restoreOnBoot && bootRecovery != AdbBootRecovery.COMPLETE &&
                (trigger.isBootTrigger || bootRecovery == AdbBootRecovery.PENDING)

            val wifiLines = wifi()
            val wifiQuerySucceeded = "WIFI_QUERY_OK" in wifiLines
            if (!wifiQuerySucceeded && !trackBootRecovery) {
                WirelessAdbStatusStore.appendLog(context, "$sourceLabel failed: WIFI_QUERY_FAILED")
                return@withLock AdbActionResult.FAILED
            }
            val ssid = if (wifiQuerySucceeded) primarySsid(wifiLines) else null
            val decision = if (wifiQuerySucceeded) {
                decideTriggerAction(policy, trigger, ssid, unlocked, trackBootRecovery)
            } else {
                null
            }
            if (trigger.isBootTrigger &&
                bootCount != null &&
                existingStatus?.bootCount == bootCount &&
                (existingStatus.trigger?.isBootTrigger == true || bootRecovery == AdbBootRecovery.COMPLETE)
            ) {
                val bootAlreadySatisfied = when (decision) {
                    AdbTriggerDecision.ENABLE_TARGET -> existingStatus.result == AdbActionResult.READY &&
                        existingStatus.mode == policy.mode &&
                        (policy.mode != AdbMode.TCP || existingStatus.port == policy.customPort) &&
                        liveInfo.enabled == true &&
                        liveInfo.listening == true &&
                        (policy.mode != AdbMode.TCP || liveInfo.tcpPort == policy.customPort)
                    AdbTriggerDecision.SUPPRESS_SYSTEM_TLS -> existingStatus.result == AdbActionResult.OFF &&
                        liveInfo.tlsEnabled != true
                    else -> false
                }
                if (bootAlreadySatisfied) {
                    return@withLock existingStatus.result ?: AdbActionResult.READY
                }
            }
            if (bootRecovery == AdbBootRecovery.COMPLETE && !policy.autoEnableOnWifi &&
                (decision == AdbTriggerDecision.WAIT_NETWORK || decision == AdbTriggerDecision.ENABLE_TARGET)
            ) {
                return@withLock AdbActionResult.INACTIVE
            }

            val outcome = when (decision) {
                AdbTriggerDecision.INACTIVE -> SwitchOutcome(AdbActionResult.INACTIVE, detail = "INACTIVE")
                AdbTriggerDecision.WAIT_UNLOCK -> SwitchOutcome(AdbActionResult.WAIT_UNLOCK, detail = "WAIT_UNLOCK")
                AdbTriggerDecision.WAIT_NETWORK -> SwitchOutcome(
                    AdbActionResult.WAIT_NETWORK,
                    detail = if (ssid == null) "NO_WIFI" else "SSID_MISMATCH"
                )
                AdbTriggerDecision.ENABLE_TARGET -> switchWithDetail(context, true, policy.mode, policy.customPort)
                AdbTriggerDecision.SUPPRESS_SYSTEM_TLS -> suppressSystemTls()
                null -> SwitchOutcome(AdbActionResult.FAILED, detail = "WIFI_QUERY_FAILED")
            }

            if (outcome.result != AdbActionResult.INACTIVE) {
                WirelessAdbStatusStore.writeStatus(
                    context,
                    AdbBootStatus(
                        bootCount = bootCount,
                        uptimeSeconds = uptimeSeconds,
                        source = sourceLabel,
                        trigger = trigger,
                        ssid = ssid,
                        mode = policy.mode,
                        port = outcome.port ?: if (decision == AdbTriggerDecision.ENABLE_TARGET && policy.mode == AdbMode.TCP) policy.customPort else null,
                        result = outcome.result,
                        detail = outcome.detail,
                        timestampMillis = System.currentTimeMillis(),
                        bootRecovery = when {
                            !policy.restoreOnBoot -> null
                            !trackBootRecovery -> bootRecovery
                            outcome.result == AdbActionResult.READY -> AdbBootRecovery.COMPLETE
                            else -> AdbBootRecovery.PENDING
                        }
                    )
                )
                synchronized(diskLock) {
                    save(context, policy(context).copy(result = outcome.result), syncSystemServer = false)
                }
                WirelessAdbStatusStore.appendLog(
                    context,
                    "$sourceLabel ssid=${ssid ?: "-"} decision=${decision?.name ?: "-"} -> ${outcome.result.name} (${outcome.detail})"
                )
            }
            outcome.result
        }
    }
}

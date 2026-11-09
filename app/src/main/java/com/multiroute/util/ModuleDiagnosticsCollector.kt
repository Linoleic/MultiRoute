package com.multiroute.util

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.multiroute.BuildConfig
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.ScenarioStore
import com.multiroute.model.ScenarioResolution
import com.multiroute.model.ScenarioTrigger
import com.multiroute.wifi.DualWifiManager
import com.multiroute.wifi.DualWifiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticInfo(
    val isRootGranted: Boolean = false,
    val suVersion: String = "",
    val isModuleActive: Boolean = false,
    /** Precise module state; [isModuleActive] is kept as the coarse "loaded in system_server" flag. */
    val moduleStatus: ModuleStatus = ModuleStatus.NOT_ACTIVE,
    val moduleStatusDetail: String = "",
    val kernelRulesCount: Int = 0,
    val kernelRules: List<String> = emptyList(),
    val mobileDataAlwaysOn: Boolean = false,
    val mobileDataPreferredUids: String = "",
    val isKeepSlaveWifiScreenOff: Boolean = false,
    val activeTables: List<String> = emptyList(),
    val wifiSsids: Map<String, String> = emptyMap(),
    /** uid→channel as configured in the UI. */
    val configuredUidRules: Map<Int, String> = emptyMap(),
    /** uid→table as it actually exists in the kernel, so the two can be compared in the UI. */
    val kernelUidRules: Map<Int, String> = emptyMap(),
    /** Tail of the boot-recovery log written by the generated `service.d` script. */
    val bootRestoreLog: List<String> = emptyList()
)

/**
 * Evaluates Xposed/LSPosed module activation state and compiles live diagnostic snapshots
 * and exportable logs from kernel, framework settings, dual-Wi-Fi state, and `service.d` logs.
 */
internal class ModuleDiagnosticsCollector(
    private val shell: RootShellExecutor,
    private val routeSync: RouteSyncCoordinator
) {
    companion object {
        /** Pseudo PID used when a legacy flag proves the module ran but recorded no process. */
        private const val UNKNOWN_PID = -1
    }

    fun moduleStatusLabel(context: Context, status: ModuleStatus): String = when (status) {
        ModuleStatus.ACTIVE -> context.getString(R.string.module_status_active)
        ModuleStatus.PARTIAL -> context.getString(R.string.module_status_partial)
        ModuleStatus.OUTDATED -> context.getString(R.string.module_status_outdated)
        ModuleStatus.LEGACY_UNVERIFIED -> context.getString(R.string.module_status_legacy)
        ModuleStatus.STALE -> context.getString(R.string.module_status_stale)
        ModuleStatus.NOT_ACTIVE -> context.getString(R.string.module_status_not_active)
    }

    fun getModuleState(context: Context): ModuleState {
        val installedVersionCode = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)

        // 1) Primary: structured beacon, no root required.
        val globalRaw = runCatching {
            Settings.Global.getString(context.contentResolver, ModuleStateParser.STATE_KEY)
        }.getOrNull()
        var beacon = ModuleStateParser.parseBeacon(globalRaw)
        var legacyPid = ModuleStateParser.parseLegacyMarkerPid(globalRaw)

        // 2) System property channel: no root, no ContentResolver round trip.
        if (beacon == null && legacyPid == null) {
            val propRaw = readStateProperty()
            beacon = ModuleStateParser.parseBeacon(propRaw)
            legacyPid = ModuleStateParser.parseLegacyMarkerPid(propRaw)
        }

        // 3) Fallback: marker file. Only read when the cheap channels are empty.
        if (beacon == null && legacyPid == null) {
            val markerRaw = readMarkerRaw()
            beacon = ModuleStateParser.parseBeacon(markerRaw)
            legacyPid = ModuleStateParser.parseLegacyMarkerPid(markerRaw)
        }

        // 4) Last resort: the legacy flag proves something ran, but says nothing about the hooks.
        val legacyFlag = if (beacon == null && legacyPid == null) readLegacyActiveFlag() else false
        val legacyMarkerPid = legacyPid ?: if (legacyFlag) UNKNOWN_PID else null

        val pidToVerify = beacon?.systemServerPid ?: legacyPid
        val ownerVerified = pidToVerify?.let { verifyIsSystemServer(it) }

        return ModuleStateEvaluator.evaluate(
            ModuleStateEvaluator.Inputs(
                beacon = beacon,
                legacyMarkerPid = legacyMarkerPid,
                installedVersionCode = installedVersionCode,
                installedBuildId = BuildConfig.HOOK_BUILD_ID,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                ownerIsSystemServer = ownerVerified
            )
        )
    }

    /** Reads the compact property beacon without root (a plain `getprop`, ~10 ms). */
    private fun readStateProperty(): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", ModuleStateParser.STATE_PROPERTY))
        val line = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.errorStream.bufferedReader().use { it.readText() }
        p.waitFor()
        line.ifEmpty { null }
    }.getOrNull()

    /** Reads the marker file unprivileged first, then through root; null when unreadable. */
    private fun readMarkerRaw(): String? {
        runCatching {
            val f = File(ModuleStateParser.MARKER_PATH)
            if (f.canRead()) return f.readText().trim()
        }
        return shell.executeShellWithOutput("cat ${ModuleStateParser.MARKER_PATH}", timeoutSeconds = 2)
            .firstOrNull()?.trim()
    }

    /**
     * Verifies that [pid] really is system_server.
     * Returns null when it cannot be determined (for example without root).
     */
    private fun verifyIsSystemServer(pid: Int): Boolean? {
        if (pid <= 0) return null

        runCatching {
            val cmdline = File("/proc/$pid/cmdline").readText().replace('\u0000', ' ')
            return cmdline.contains("system_server")
        }

        val sentinel = ModuleStateParser.noProcessSentinel()
        val lines = shell.executeShellWithOutput(
            "if [ -d /proc/$pid ]; then cat /proc/$pid/cmdline; else echo $sentinel; fi",
            timeoutSeconds = 2
        )
        if (lines.isEmpty()) return null
        if (lines.any { it.contains(sentinel) }) return false
        return lines.any { it.contains("system_server") }
    }

    private fun readLegacyActiveFlag(): Boolean {
        val direct = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "sys.multiroute.active"))
            val line = p.inputStream.bufferedReader().use { it.readLine() }?.trim()
            p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor()
            line == "1"
        }.getOrDefault(false)
        if (direct) return true
        return shell.executeShellWithOutput("getprop sys.multiroute.active", timeoutSeconds = 2)
            .firstOrNull()?.trim() == "1"
    }

    suspend fun getDiagnosticInfo(context: Context): DiagnosticInfo = withContext(Dispatchers.IO) {
        var isRoot = false
        var suVer = ""
        val kernelRules = mutableListOf<String>()
        val activeTables = mutableListOf<String>()

        try {
            val lines = shell.executeShellWithOutput(
                "id; su -v; ip rule show pref 14500; ip -6 rule show pref 14500",
                timeoutSeconds = 4
            )
            if (lines.isNotEmpty() && lines[0].contains("uid=0")) {
                isRoot = true
            }
            if (lines.size > 1) {
                suVer = lines[1].trim()
            }
            for (i in 2 until lines.size) {
                val line = lines[i].trim()
                if (line.isNotEmpty() && line.contains("14500")) {
                    kernelRules.add(line)
                    val table = line.substringAfter("lookup ").substringBefore(";").trim()
                    if (table.isNotEmpty() && !activeTables.contains(table)) {
                        activeTables.add(table)
                    }
                }
            }
        } catch (_: Exception) {}

        val moduleState = getModuleState(context)
        val mobileDataAlwaysOn = runCatching {
            Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1
        }.getOrDefault(false)
        val mobileDataPreferredUids = runCatching {
            Settings.Secure.getString(context.contentResolver, "mobile_data_preferred_uids") ?: ""
        }.getOrDefault("")

        val wifiSsids = NetworkUtils.getConnectedWifiSsids()
        val keepSlaveWifi = RouteConfigProvider.isKeepSlaveWifiScreenOff(context)

        DiagnosticInfo(
            isRootGranted = isRoot,
            suVersion = suVer,
            isModuleActive = moduleState.isLoadedInSystemServer,
            moduleStatus = moduleState.status,
            moduleStatusDetail = moduleState.detailLabel(context),
            kernelRulesCount = kernelRules.size,
            kernelRules = kernelRules,
            mobileDataAlwaysOn = mobileDataAlwaysOn,
            mobileDataPreferredUids = mobileDataPreferredUids,
            isKeepSlaveWifiScreenOff = keepSlaveWifi,
            activeTables = activeTables,
            wifiSsids = wifiSsids,
            configuredUidRules = RouteConfigProvider.getUidRules(context),
            kernelUidRules = RouteRuleBuilder.parseKernelUidRules(kernelRules),
            bootRestoreLog = readBootRestoreLog()
        )
    }

    private fun readBootRestoreLog(maxLines: Int = 12): List<String> =
        try {
            // Absolute path on purpose: the su environment may resolve `tail` to busybox.
            shell.executeShellWithOutput("/system/bin/tail -n $maxLines ${RouteRuleBuilder.BOOT_LOG_PATH} 2>/dev/null", 4)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }

    fun scenarioReasonText(
        context: Context,
        resolution: ScenarioResolution
    ): String {
        if (resolution.activeId == null) return ""
        if (resolution.appliedManually) {
            return context.getString(R.string.scenario_reason_manual)
        }
        return when (val trigger = resolution.matchedTrigger) {
            is ScenarioTrigger.SsidMatch -> context.getString(
                R.string.scenario_reason_ssid,
                resolution.matchedSsid ?: trigger.ssids.joinToString(" / ")
            )
            is ScenarioTrigger.WifiLinkCount ->
                context.getString(R.string.scenario_reason_links2)
            is ScenarioTrigger.CellularOnly ->
                context.getString(R.string.scenario_reason_cellular)
            is ScenarioTrigger.Always ->
                context.getString(R.string.scenario_reason_always)
            else -> context.getString(R.string.scenario_reason_manual)
        }
    }

    suspend fun getDiagnosticLogs(context: Context): String = withContext(Dispatchers.IO) {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = timeFormat.format(Date())

        val sb = StringBuilder()
        sb.appendLine(context.getString(R.string.diag_title))
        sb.appendLine(context.getString(R.string.diag_time, now))
        sb.appendLine()

        // 1. 核心权限与系统环境
        val diag = getDiagnosticInfo(context)
        sb.appendLine(context.getString(R.string.diag_sec1))
        sb.appendLine(
            context.getString(
                R.string.diag_device_info,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT,
                Build.DISPLAY
            )
        )
        sb.appendLine(
            context.getString(
                R.string.diag_root_state,
                if (diag.isRootGranted) context.getString(R.string.diag_root_granted, diag.suVersion)
                else context.getString(R.string.diag_root_missing)
            )
        )
        sb.appendLine(context.getString(R.string.diag_module, moduleStatusLabel(context, diag.moduleStatus)))
        if (diag.moduleStatusDetail.isNotEmpty()) {
            sb.appendLine("  └ ${diag.moduleStatusDetail}")
        }
        sb.appendLine(
            context.getString(
                R.string.diag_cellular_always_on,
                if (diag.mobileDataAlwaysOn) context.getString(R.string.diag_on) else context.getString(R.string.diag_off)
            )
        )
        sb.appendLine(
            context.getString(
                R.string.diag_keepalive,
                if (diag.isKeepSlaveWifiScreenOff) context.getString(R.string.diag_keepalive_on)
                else context.getString(R.string.diag_keepalive_off)
            )
        )
        if (ScenarioStore.load(context).isNotEmpty()) {
            val resolution = routeSync.resolveScenarioProfile(context, RouteConfigProvider.getUidRules(context))
            sb.appendLine(
                context.getString(
                    R.string.diag_scenario,
                    resolution.activeName ?: context.getString(R.string.diag_scenario_none),
                    scenarioReasonText(context, resolution)
                )
            )
        }
        sb.appendLine(
            context.getString(
                R.string.diag_cellular_uids,
                diag.mobileDataPreferredUids.ifEmpty { context.getString(R.string.diag_empty) }
            )
        )
        sb.appendLine()

        // 2. 双 Wi-Fi 硬件与并发状态 (STA + STA)
        sb.appendLine(context.getString(R.string.diag_sec_dual_wifi))
        try {
            val wifiStatus = DualWifiManager.status.value
            val cap = if (wifiStatus.capability.detail.isNotEmpty()) {
                wifiStatus.capability
            } else {
                DualWifiManager.checkCapability(context)
            }
            sb.appendLine(
                context.getString(
                    R.string.diag_dual_wifi_hw,
                    context.getString(
                        if (cap.hardwareSupported) R.string.diag_dual_wifi_hw_supported
                        else R.string.diag_dual_wifi_hw_unsupported
                    )
                )
            )
            val chipset = if (cap.wifiChipsetRes != 0) context.getString(cap.wifiChipsetRes) else cap.wifiChipset.ifEmpty { DualWifiManager.detectWifiChipset(context) }
            sb.appendLine(context.getString(R.string.diag_dual_wifi_chip, chipset))
            sb.appendLine(
                context.getString(
                    R.string.diag_dual_wifi_overlay,
                    context.getString(
                        if (cap.configEnabled) R.string.diag_dual_wifi_overlay_on
                        else R.string.diag_dual_wifi_overlay_off
                    )
                )
            )
            sb.appendLine(
                context.getString(
                    R.string.diag_dual_wifi_mode,
                    context.getString(
                        if (cap.modeActive) R.string.diag_dual_wifi_mode_active
                        else R.string.diag_dual_wifi_mode_inactive
                    )
                )
            )
            sb.appendLine(
                context.getString(
                    R.string.diag_dual_wifi_scheme,
                    cap.configuredScheme.label(context),
                    cap.effectiveScheme.label(context)
                )
            )

            val notConnectedText = context.getString(R.string.diag_wifi_not_connected)
            val connectedFallbackText = context.getString(R.string.diag_wifi_connected)

            val pIface = wifiStatus.primaryIface ?: diag.wifiSsids.keys.firstOrNull() ?: "wlan0"
            val pSsid = wifiStatus.primarySsid ?: diag.wifiSsids[pIface] ?: notConnectedText
            val pFreqStr = if (wifiStatus.primaryFreq > 0) " · ${wifiStatus.primaryFreq} MHz" else ""
            val pIpStr = if (!wifiStatus.primaryIp.isNullOrEmpty()) " · ${wifiStatus.primaryIp}" else ""
            sb.appendLine(context.getString(R.string.diag_dual_wifi_primary, "$pIface -> $pSsid$pFreqStr$pIpStr"))

            val sIface = wifiStatus.secondaryIface ?: "wlan1"
            val sConnected = wifiStatus.secondaryConnected
            val sSsid = wifiStatus.secondarySsid ?: if (sConnected) connectedFallbackText else notConnectedText
            val sFreqStr = if (wifiStatus.secondaryFreq > 0) " · ${wifiStatus.secondaryFreq} MHz" else ""
            val sIpStr = if (!wifiStatus.secondaryIp.isNullOrEmpty()) " · ${wifiStatus.secondaryIp}" else ""
            sb.appendLine(context.getString(R.string.diag_dual_wifi_secondary, "$sIface -> $sSsid$sFreqStr$sIpStr"))

            val serviceRunning = DualWifiService.isRunning
            sb.appendLine(
                context.getString(
                    R.string.diag_dual_wifi_service,
                    context.getString(
                        if (serviceRunning) R.string.diag_dual_wifi_service_running
                        else R.string.diag_dual_wifi_service_stopped
                    )
                )
            )
        } catch (e: Exception) {
            sb.appendLine(context.getString(R.string.diag_dual_wifi_failed, e.message ?: ""))
        }
        sb.appendLine()

        // 3. 各通道 DNS 与私有 DNS 状态
        sb.appendLine(context.getString(R.string.diag_sec_dns))
        try {
            val pDnsMode = runCatching { Settings.Global.getString(context.contentResolver, "private_dns_mode") }.getOrNull().orEmpty()
            val pDnsSpec = runCatching { Settings.Global.getString(context.contentResolver, "private_dns_specifier") }.getOrNull().orEmpty()
            val pDnsDesc = when (pDnsMode.lowercase()) {
                "off" -> context.getString(R.string.diag_private_dns_off)
                "hostname" -> context.getString(R.string.diag_private_dns_hostname, pDnsSpec)
                "opportunistic" -> context.getString(R.string.diag_private_dns_opportunistic)
                else -> if (pDnsMode.isEmpty()) context.getString(R.string.diag_private_dns_auto) else pDnsMode
            }
            sb.appendLine(context.getString(R.string.diag_private_dns, pDnsDesc))
            sb.appendLine(
                context.getString(
                    R.string.diag_dns_follow_channel,
                    context.getString(
                        if (RouteConfigProvider.isDnsFollowsChannel(context)) R.string.diag_dns_follow_on
                        else R.string.diag_dns_follow_off
                    )
                )
            )

            val knownIfaces = (diag.activeTables + listOf("wlan0", "wlan1", "rmnet_data0", "ccmni0")).distinct()
            val resolvers = routeSync.getChannelResolvers(context, knownIfaces)
            if (resolvers.isEmpty()) {
                sb.appendLine(context.getString(R.string.diag_no_channel_dns))
            } else {
                resolvers.forEach { (iface, dnsList) ->
                    sb.appendLine(context.getString(R.string.diag_channel_dns, iface, dnsList.joinToString(", ")))
                }
            }
        } catch (e: Exception) {
            sb.appendLine(context.getString(R.string.diag_dns_failed, e.message ?: ""))
        }
        sb.appendLine()

        // 4. 规则生效对照：配置的 uid→通道 与内核里的 pref 14500 规则逐条比对
        sb.appendLine(context.getString(R.string.diag_sec2))
        if (diag.configuredUidRules.isEmpty()) {
            sb.appendLine(context.getString(R.string.diag_no_rules))
        } else {
            diag.configuredUidRules.entries.sortedBy { it.key }.forEach { (uid, channel) ->
                val actual = diag.kernelUidRules[uid]
                val verdict = when {
                    actual == null -> context.getString(R.string.diag_rule_not_effective)
                    actual == channel -> context.getString(R.string.diag_rule_effective)
                    else -> context.getString(R.string.diag_rule_effective_table, actual)
                }
                sb.appendLine("• uid $uid -> $channel: $verdict")
            }
        }
        sb.appendLine()

        // 5. 各网卡默认网关与策略路由表
        sb.appendLine(context.getString(R.string.diag_sec_routes))
        try {
            val routeLines = shell.executeShellWithOutput(
                "ip route show table all 2>/dev/null | grep -E 'default via|default dev'",
                timeoutSeconds = 3
            )
            if (routeLines.isEmpty()) {
                sb.appendLine(context.getString(R.string.diag_no_routes))
            } else {
                routeLines.forEach { sb.appendLine("• $it") }
            }
        } catch (e: Exception) {
            sb.appendLine(context.getString(R.string.diag_routes_failed, e.message ?: ""))
        }
        sb.appendLine()

        // 6. 内核策略路由规则 (pref 14500 & pref 14400)
        sb.appendLine(context.getString(R.string.diag_sec_kernel))
        if (diag.kernelRules.isEmpty()) {
            sb.appendLine(context.getString(R.string.diag_no_kernel_rules))
        } else {
            diag.kernelRules.forEach { rule -> sb.appendLine("• $rule") }
        }
        sb.appendLine()

        // 7. 当前活动的网络接口与 IP
        sb.appendLine(context.getString(R.string.diag_sec_ifaces))
        try {
            val lines = shell.executeShellWithOutput("ip -br addr; echo '---'; ip rule show | head -n 30", timeoutSeconds = 3)
            lines.forEach { sb.appendLine(it) }
        } catch (e: Exception) {
            sb.appendLine(context.getString(R.string.diag_ifaces_failed, e.message ?: ""))
        }
        sb.appendLine()

        // 8. 开机恢复日志（service.d 生成，锁屏状态下它是唯一的恢复路径）
        sb.appendLine(context.getString(R.string.diag_sec3, RouteRuleBuilder.BOOT_LOG_PATH))
        if (diag.bootRestoreLog.isEmpty()) {
            sb.appendLine(context.getString(R.string.diag_no_boot_log))
        } else {
            diag.bootRestoreLog.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()

        // 9. 最近 Logcat 相关日志
        sb.appendLine(context.getString(R.string.diag_sec_logcat))
        try {
            val lines = shell.executeShellWithOutput(
                "logcat -d -t 120 | grep -E 'MultiRoute|DualWifi|SlaveWifi|ConnectivityService|netd|wpa_supplicant' | tail -n 50",
                timeoutSeconds = 4
            )
            if (lines.isEmpty()) {
                sb.appendLine(context.getString(R.string.diag_no_logcat))
            } else {
                lines.forEach { sb.appendLine(it) }
            }
        } catch (_: Exception) {
            sb.appendLine(context.getString(R.string.diag_logcat_timeout))
        }

        sb.toString()
    }
}

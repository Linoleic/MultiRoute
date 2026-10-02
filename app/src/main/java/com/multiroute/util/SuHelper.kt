package com.multiroute.util

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.multiroute.data.RouteConfigProvider
import com.multiroute.model.CHANNEL_DEFAULT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticInfo(
    val isRootGranted: Boolean = false,
    val suVersion: String = "",
    val isModuleActive: Boolean = false,
    val kernelRulesCount: Int = 0,
    val kernelRules: List<String> = emptyList(),
    val mobileDataAlwaysOn: Boolean = false,
    val mobileDataPreferredUids: String = "",
    val isKeepSlaveWifiScreenOff: Boolean = false,
    val activeTables: List<String> = emptyList(),
    val wifiSsids: Map<String, String> = emptyMap()
)

object SuHelper {
    private const val TAG = "MultiRoute-SU"

    fun isKeepSlaveWifiScreenOff(context: Context): Boolean {
        return RouteConfigProvider.isKeepSlaveWifiScreenOff(context)
    }

    suspend fun setKeepSlaveWifiScreenOff(context: Context, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        RouteConfigProvider.setKeepSlaveWifiScreenOff(context, enabled)

        // 1. Transient system property for instantaneous system_server hook access
        val propVal = if (enabled) "1" else "0"
        executeCommand("setprop sys.multiroute.keep_slave_wifi $propVal")

        // 2. Xiaomi system setting: 0 = disable auto teardown, 1 = enable auto teardown
        val autoDisableVal = if (enabled) 0 else 1
        val direct = runCatching {
            Settings.System.putInt(context.contentResolver, "test_wifi_slave_auto_disable", autoDisableVal)
        }.getOrDefault(false)
        if (!direct) {
            executeCommand("settings put system test_wifi_slave_auto_disable $autoDisableVal")
        }
        true
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        val lines = executeShellWithOutput("id", timeoutSeconds = 3)
        lines.firstOrNull()?.contains("uid=0") == true
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun isMobileDataAlwaysOn(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    suspend fun enableMobileDataAlwaysOn(context: Context): Boolean = withContext(Dispatchers.IO) {
        val direct = runCatching {
            Settings.Global.putInt(context.contentResolver, "mobile_data_always_on", 1)
        }.getOrDefault(false)
        if (direct) return@withContext true
        executeCommand("settings put global mobile_data_always_on 1")
    }

    suspend fun disableMobileDataAlwaysOn(context: Context): Boolean = withContext(Dispatchers.IO) {
        val direct = runCatching {
            Settings.Global.putInt(context.contentResolver, "mobile_data_always_on", 0)
        }.getOrDefault(false)
        if (direct) return@withContext true
        executeCommand("settings put global mobile_data_always_on 0")
    }

    /**
     * Checks which network interfaces currently have active default routes in their kernel routing tables.
     * Prevents traffic blackholes when an interface is assigned but temporarily disconnected or has no route.
     */
    fun getInterfacesWithDefaultRoute(candidateIfaces: Collection<String>): Set<String> {
        val safeIfaces = candidateIfaces.filter { RouteRuleBuilder.isValidInterfaceName(it) }
        if (safeIfaces.isEmpty()) return emptySet()
        val cmd = safeIfaces.joinToString(" ") { iface ->
            "ip route show table $iface 2>/dev/null | grep -m1 '^default' && echo 'ONLINE_V4:$iface'; " +
            "ip -6 route show table $iface 2>/dev/null | grep -m1 '^default' && echo 'ONLINE_V6:$iface';"
        }
        val lines = executeShellWithOutput(cmd, timeoutSeconds = 3)
        val online = mutableSetOf<String>()
        for (line in lines) {
            if (line.startsWith("ONLINE_V4:") || line.startsWith("ONLINE_V6:")) {
                online.add(line.substringAfter(":").trim())
            }
        }
        return online
    }

    /**
     * Dynamically synchronizes routing rules for any interface (wlan0, wlan1, rmnet_data*, eth0, etc.)
     * to the kernel policy routing table and system settings.
     */
    suspend fun syncAllRouteRules(context: Context): Boolean = withContext(Dispatchers.IO) {
        val allRules = RouteConfigProvider.getAllRules(context)
        val pm = context.packageManager

        val channelToUidsMap = mutableMapOf<String, MutableList<Int>>()
        val cellularUids = mutableSetOf<Int>()

        for ((pkg, channelId) in allRules) {
            if (channelId == CHANNEL_DEFAULT) continue
            val uid = runCatching { pm.getPackageUid(pkg, 0) }.getOrNull() ?: continue
            if (uid < 10000) continue

            channelToUidsMap.getOrPut(channelId) { mutableListOf() }.add(uid)

            // If channel is a cellular interface, register in mobile_data_preferred_uids
            if (channelId.startsWith("rmnet") || channelId.startsWith("ccmni") || channelId.contains("mobile")) {
                cellularUids.add(uid)
            }
        }

        // 1. Sync Cellular UIDs via Android's native Settings.Secure
        val uidStr = cellularUids.joinToString(";")
        val direct = runCatching {
            Settings.Secure.putString(context.contentResolver, "mobile_data_preferred_uids", uidStr)
        }.getOrDefault(false)
        if (!direct) {
            executeCommand("settings put secure mobile_data_preferred_uids \"$uidStr\"")
        }

        // 2. Validate interface route tables: filter out channels that have no default route to prevent blackholing
        val candidateChannels = channelToUidsMap.keys
        val onlineChannels = getInterfacesWithDefaultRoute(candidateChannels)
        val activeChannelMap = channelToUidsMap.filterKeys { iface ->
            val isOnline = onlineChannels.contains(iface)
            if (!isOnline) {
                Log.w(TAG, "Interface $iface has no default route in kernel table; skipping to prevent traffic blackholing")
            }
            isOnline
        }

        // 3. Sync Linux kernel policy routing dynamically for active interfaces (dual-stack + LAN bypass)
        val script = RouteRuleBuilder.buildFullSyncScript(activeChannelMap)
        executeCommand(script)
    }

    @androidx.annotation.Keep
    @JvmStatic
    fun isModuleActiveInLSPosed(): Boolean {
        return false
    }

    /**
     * Dynamically detects whether the LSPosed MultiRoute module is actually activated in system_server.
     * Prevents false positives by strictly verifying the marker file with system_server process validation
     * and system properties, rather than relying on forgeable logs or in-process hook short-circuits.
     */
    fun checkModuleActivated(): Boolean {
        // 1. Root check for marker file written by system_server (/data/system/multiroute_active)
        val catLines = executeShellWithOutput("cat /data/system/multiroute_active", timeoutSeconds = 2)
        val text = catLines.firstOrNull()?.trim() ?: ""
        val pid = text.substringBefore(":").toIntOrNull()
        if (pid != null) {
            val cmdLines = executeShellWithOutput("cat /proc/$pid/cmdline", timeoutSeconds = 2)
            if (cmdLines.any { it.contains("system_server") }) {
                return true
            }
        }

        // 2. Direct marker file check (if permissions allow unprivileged read)
        val fileCheck = runCatching {
            val f = java.io.File("/data/system/multiroute_active")
            if (f.exists()) {
                val content = f.readText().trim()
                val fPid = content.substringBefore(":").toIntOrNull()
                if (fPid != null) {
                    val cmdline = java.io.File("/proc/$fPid/cmdline").readText()
                    cmdline.contains("system_server")
                } else false
            } else false
        }.getOrDefault(false)
        if (fileCheck) return true

        // 3. System property check (unprivileged + root)
        val prop = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "sys.multiroute.active"))
            val line = p.inputStream.bufferedReader().use { it.readLine() }?.trim()
            p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor()
            line == "1"
        }.getOrDefault(false)
        if (prop) return true

        val rootProp = executeShellWithOutput("getprop sys.multiroute.active", timeoutSeconds = 2)
        if (rootProp.firstOrNull()?.trim() == "1") return true

        return false
    }

    /**
     * Gathers rich live diagnostic status of the MultiRoute framework, Linux kernel, and Android settings.
     */
    suspend fun getDiagnosticInfo(context: Context): DiagnosticInfo = withContext(Dispatchers.IO) {
        var isRoot = false
        var suVer = ""
        val kernelRules = mutableListOf<String>()
        val activeTables = mutableListOf<String>()

        try {
            val lines = executeShellWithOutput("id; su -v; ip rule show pref 14500; ip -6 rule show pref 14500", timeoutSeconds = 4)
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

        val isModuleActive = checkModuleActivated()
        val mobileDataAlwaysOn = isMobileDataAlwaysOn(context)
        val mobileDataPreferredUids = runCatching {
            Settings.Secure.getString(context.contentResolver, "mobile_data_preferred_uids") ?: ""
        }.getOrDefault("")

        val wifiSsids = NetworkUtils.getConnectedWifiSsids()
        val keepSlaveWifi = isKeepSlaveWifiScreenOff(context)

        DiagnosticInfo(
            isRootGranted = isRoot,
            suVersion = suVer,
            isModuleActive = isModuleActive,
            kernelRulesCount = kernelRules.size,
            kernelRules = kernelRules,
            mobileDataAlwaysOn = mobileDataAlwaysOn,
            mobileDataPreferredUids = mobileDataPreferredUids,
            isKeepSlaveWifiScreenOff = keepSlaveWifi,
            activeTables = activeTables,
            wifiSsids = wifiSsids
        )
    }

    /**
     * Compiles a comprehensive diagnostic log snapshot for the user to view or copy.
     */
    suspend fun getDiagnosticLogs(context: Context): String = withContext(Dispatchers.IO) {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = timeFormat.format(Date())

        val sb = StringBuilder()
        sb.appendLine("=== MultiRoute 系统运行与诊断日志 ===")
        sb.appendLine("时间: $now")
        sb.appendLine()

        // 1. 基础系统与权限
        val diag = getDiagnosticInfo(context)
        sb.appendLine("[1. 核心权限与模块状态]")
        sb.appendLine("• Root 状态: ${if (diag.isRootGranted) "已授权 (${diag.suVersion})" else "未授权"}")
        sb.appendLine("• LSPosed 模块: ${if (diag.isModuleActive) "已激活 (system_server 正在运行)" else "未激活 (请在 LSPosed 管理器中启用模块)"}")
        sb.appendLine("• 蜂窝数据常活: ${if (diag.mobileDataAlwaysOn) "已开启 (1)" else "未开启 (0)"}")
        sb.appendLine("• 副 Wi-Fi 息屏防断联: ${if (diag.isKeepSlaveWifiScreenOff) "已开启 (保持常活)" else "未开启 (跟随系统休眠)"}")
        sb.appendLine("• 系统首选蜂窝 UIDs: ${diag.mobileDataPreferredUids.ifEmpty { "(空)" }}")
        sb.appendLine()

        // 2. Wi-Fi 连接状态
        sb.appendLine("[2. Wi-Fi SSID 实时映射]")
        if (diag.wifiSsids.isEmpty()) {
            sb.appendLine("• 未检测到已连接的 Wi-Fi SSID")
        } else {
            diag.wifiSsids.forEach { (iface, ssid) ->
                sb.appendLine("• $iface -> $ssid")
            }
        }
        sb.appendLine()

        // 3. 内核策略路由规则 (pref 14500)
        sb.appendLine("[3. Linux 内核策略路由 (pref 14500)]")
        if (diag.kernelRules.isEmpty()) {
            sb.appendLine("• 当前内核无 pref 14500 规则 (尚未分配应用分流)")
        } else {
            diag.kernelRules.forEach { rule ->
                sb.appendLine("• $rule")
            }
        }
        sb.appendLine()

        // 4. 当前活动的网络接口与 IP
        sb.appendLine("[4. 网络接口与路由表概要]")
        try {
            val lines = executeShellWithOutput("ip -br addr; echo '---'; ip rule show | head -n 25", timeoutSeconds = 3)
            lines.forEach { sb.appendLine(it) }
        } catch (e: Exception) {
            sb.appendLine("读取网络接口失败: ${e.message}")
        }
        sb.appendLine()

        // 5. 最近 Logcat 相关日志
        sb.appendLine("[5. Logcat 系统日志 (最近)]")
        try {
            val lines = executeShellWithOutput("logcat -d -t 60 | grep -E 'MultiRoute|ConnectivityService|netd' | tail -n 25", timeoutSeconds = 3)
            if (lines.isEmpty()) {
                sb.appendLine("• 暂无相关日志记录")
            } else {
                lines.forEach { sb.appendLine(it) }
            }
        } catch (_: Exception) {
            sb.appendLine("• Logcat 读取超时")
        }

        sb.toString()
    }

    /**
     * Executes a command as root with safety timeout and drained buffer to avoid deadlocks.
     */
    fun executeCommand(cmd: String, timeoutSeconds: Long = 5): Boolean {
        return try {
            val pb = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true)
            val process = pb.start()
            val drainThread = Thread {
                runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            }
            drainThread.start()
            val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.e(TAG, "Command timed out after ${timeoutSeconds}s: $cmd")
                false
            } else {
                drainThread.join(500)
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exec: $cmd", e)
            false
        }
    }

    /**
     * Executes a shell command as root and reads all output lines safely with timeout.
     */
    fun executeShellWithOutput(cmd: String, timeoutSeconds: Long = 5): List<String> {
        return try {
            val pb = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true)
            val process = pb.start()
            val lines = mutableListOf<String>()
            val readThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { seq ->
                        lines.addAll(seq.toList())
                    }
                }
            }
            readThread.start()
            val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.e(TAG, "Command with output timed out after ${timeoutSeconds}s: $cmd")
                emptyList()
            } else {
                readThread.join(500)
                lines
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exec with output: $cmd", e)
            emptyList()
        }
    }
}

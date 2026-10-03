package com.multiroute.util

/**
 * Parsing and evaluation of the LSPosed module state beacon.
 *
 * Why a beacon instead of the old marker file: a plain "module is loaded" marker cannot tell
 *
 *  * whether the hooks were actually installed (the marker was written *before* hooking), nor
 *  * whether system_server is still executing an **older module build** after an APK update
 *    (LSPosed cannot hot-reload a System Framework scoped module, so the app and the running hook
 *    code can silently diverge - that is precisely the trap this detects).
 *
 * The module (running inside system_server) therefore publishes a compact beacon and the app parses
 * it. The beacon is deliberately self-describing:
 *
 * ```
 * v=1;pid=3278;ver=5;bid=762e60d;boot=123456;ts=1791015204730;cs=3;slave=3;dual=1;stage=ready
 * ```
 *
 * `ver` / `bid` are the version code and source fingerprint **compiled into the loaded module code**
 * (not read back from the installed APK), which is what makes the version-skew detection sound.
 *
 * This file has no Android dependencies on purpose, so the decision logic stays unit-testable.
 */
object ModuleStateParser {

    /** Settings.Global key holding the beacon; readable by any app without permissions or root. */
    const val STATE_KEY = "multiroute_module_state"

    /**
     * System property holding a compact form of the beacon. Readable by any process without root,
     * permissions or a ContentResolver; the value must stay below the ~92 byte property limit, hence
     * the abbreviated keys and the boot time expressed in seconds.
     */
    const val STATE_PROPERTY = "sys.multiroute.state"

    /** Root-readable marker file kept for compatibility and for the no-settings fallback. */
    const val MARKER_PATH = "/data/system/multiroute_active"

    const val FORMAT_VERSION = 1

    private const val NO_PROC_SENTINEL = "__NO_PROC__"

    /** Parses the structured beacon; returns null when [raw] is absent or not a valid beacon. */
    fun parseBeacon(raw: String?): ModuleBeacon? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !text.contains('=')) return null

        val fields = mutableMapOf<String, String>()
        var bareFormatVersion: Int? = null
        for ((index, part) in text.split(';').withIndex()) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val idx = trimmed.indexOf('=')
            if (idx <= 0) {
                // Tolerate the compact leading token form ("v1;pid=..") in addition to "v=1".
                if (index == 0 && trimmed.length in 2..3 && trimmed[0] == 'v') {
                    bareFormatVersion = trimmed.substring(1).toIntOrNull()
                }
                continue
            }
            fields[trimmed.substring(0, idx)] = trimmed.substring(idx + 1)
        }

        val version = fields["v"]?.toIntOrNull() ?: bareFormatVersion ?: return null
        if (version <= 0 || version > FORMAT_VERSION) return null

        val pid = (fields["pid"] ?: fields["p"])?.toIntOrNull() ?: return null
        if (pid <= 0) return null

        return ModuleBeacon(
            formatVersion = version,
            systemServerPid = pid,
            moduleVersionCode = fields["ver"]?.toLongOrNull() ?: 0L,
            buildId = fields["bid"] ?: fields["b"] ?: "",
            // Full beacons carry epoch-independent milliseconds; the compact form carries seconds.
            bootElapsedMs = fields["boot"]?.toLongOrNull()
                ?: fields["bos"]?.toLongOrNull()?.times(1000L)
                ?: 0L,
            writtenAtMs = fields["ts"]?.toLongOrNull() ?: 0L,
            connectivityHooks = fields["cs"]?.toIntOrNull() ?: 0,
            slaveWifiHooks = (fields["slave"] ?: fields["s"])?.toIntOrNull() ?: 0,
            dualStaHooks = (fields["dual"] ?: fields["d"])?.toIntOrNull() ?: 0,
            stage = fields["stage"] ?: fields["st"] ?: "unknown"
        )
    }

    /**
     * Parses the legacy marker format `"<pid>:<timestamp>"` written by older module builds.
     * Returns the recorded PID, or null when the text is not a legacy marker.
     */
    fun parseLegacyMarkerPid(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.contains('=')) return null
        val pid = text.substringBefore(':').toIntOrNull() ?: return null
        return if (pid > 0) pid else null
    }

    /** Builds the beacon string; kept here so module and app share one definition of the format. */
    fun buildBeacon(
        pid: Int,
        moduleVersionCode: Long,
        buildId: String,
        bootElapsedMs: Long,
        writtenAtMs: Long,
        connectivityHooks: Int,
        slaveWifiHooks: Int,
        dualStaHooks: Int,
        stage: String
    ): String = "v=$FORMAT_VERSION" +
            ";pid=$pid" +
            ";ver=$moduleVersionCode" +
            ";bid=$buildId" +
            ";boot=$bootElapsedMs" +
            ";ts=$writtenAtMs" +
            ";cs=$connectivityHooks" +
            ";slave=$slaveWifiHooks" +
            ";dual=$dualStaHooks" +
            ";stage=$stage"

    internal fun noProcessSentinel(): String = NO_PROC_SENTINEL

    /**
     * Compact variant for the `sys.multiroute.*` property channel (value must stay under ~92 bytes).
     * Parsed by the same [parseBeacon] through the abbreviated key aliases.
     */
    fun buildCompactBeacon(
        pid: Int,
        moduleVersionCode: Long,
        buildId: String,
        bootElapsedMs: Long,
        connectivityHooks: Int,
        slaveWifiHooks: Int,
        dualStaHooks: Int,
        stage: String
    ): String = "v=$FORMAT_VERSION" +
            ";p=$pid" +
            ";ver=$moduleVersionCode" +
            ";b=$buildId" +
            ";bos=${bootElapsedMs / 1000L}" +
            ";cs=$connectivityHooks" +
            ";s=$slaveWifiHooks" +
            ";d=$dualStaHooks" +
            ";st=$stage"
}

/** Structured state published by the module running inside system_server. */
data class ModuleBeacon(
    val formatVersion: Int,
    val systemServerPid: Int,
    /** Version code compiled into the *loaded* module code (0 when unknown). */
    val moduleVersionCode: Long,
    /** Source fingerprint compiled into the *loaded* module code, e.g. `762e60d` or `762e60d-dirty`. */
    val buildId: String,
    /** `SystemClock.elapsedRealtime()` when the beacon was written (i.e. time since device boot). */
    val bootElapsedMs: Long,
    val writtenAtMs: Long,
    val connectivityHooks: Int,
    val slaveWifiHooks: Int,
    val dualStaHooks: Int,
    /** `loaded` while starting up, `hooks` after hook installation, `ready` for the final state. */
    val stage: String
) {
    val totalHooks: Int get() = connectivityHooks + slaveWifiHooks + dualStaHooks
}

enum class ModuleStatus {
    /** No beacon, marker or property at all: module not enabled / not injected. */
    NOT_ACTIVE,

    /** Loaded, but only a legacy marker is available: hook state cannot be verified. */
    LEGACY_UNVERIFIED,

    /** Loaded, hooks installed, loaded build matches the installed APK. */
    ACTIVE,

    /** Loaded, but the ConnectivityService hooks are not installed: app-visible state is untouched. */
    PARTIAL,

    /** system_server still runs an older module build: hook changes need a soft reboot. */
    OUTDATED,

    /** The recorded owner process is gone or is no longer system_server (stale record). */
    STALE
}

data class ModuleState(
    val status: ModuleStatus,
    val detail: String,
    val beacon: ModuleBeacon? = null
) {
    /** True whenever the module code is (or was) present inside system_server. */
    val isLoadedInSystemServer: Boolean
        get() = status != ModuleStatus.NOT_ACTIVE

    /** True only when hook installation was positively verified. */
    val isFullyOperational: Boolean
        get() = status == ModuleStatus.ACTIVE
}

/**
 * Pure decision logic: maps observable inputs to a precise [ModuleStatus].
 * Kept free of Android APIs so every branch is unit-testable.
 */
object ModuleStateEvaluator {

    /** A beacon written on a "later" boot than the current one is considered stale. */
    const val BOOT_SKEW_TOLERANCE_MS = 60_000L

    data class Inputs(
        val beacon: ModuleBeacon?,
        val legacyMarkerPid: Int?,
        val installedVersionCode: Long,
        /** Source fingerprint of the installed APK; empty when unknown. */
        val installedBuildId: String = "",
        val nowElapsedMs: Long,
        /** null = could not be determined (e.g. no root); true/false = verified. */
        val ownerIsSystemServer: Boolean?
    )

    fun evaluate(inputs: Inputs): ModuleState {
        val beacon = inputs.beacon

        if (beacon == null) {
            return when {
                inputs.legacyMarkerPid == null -> ModuleState(
                    ModuleStatus.NOT_ACTIVE,
                    "未检测到模块状态信标：模块未启用、未注入 system_server，或作用域未勾选"
                )

                inputs.ownerIsSystemServer == false -> ModuleState(
                    ModuleStatus.STALE,
                    "标记记录${pidText(inputs.legacyMarkerPid)}已不是 system_server（记录已过期）"
                )

                else -> ModuleState(
                    ModuleStatus.LEGACY_UNVERIFIED,
                    "模块已注入 system_server，但为旧版状态协议：只能证明代码已加载，无法校验 hook 是否安装"
                )
            }
        }

        if (inputs.ownerIsSystemServer == false) {
            return ModuleState(
                ModuleStatus.STALE,
                "信标记录的 PID ${beacon.systemServerPid} 已不是 system_server（记录已过期）",
                beacon
            )
        }

        if (beacon.bootElapsedMs > inputs.nowElapsedMs + BOOT_SKEW_TOLERANCE_MS) {
            return ModuleState(
                ModuleStatus.STALE,
                "信标来自更晚的开机周期（写入于开机 ${beacon.bootElapsedMs / 1000}s，当前 ${inputs.nowElapsedMs / 1000}s）",
                beacon
            )
        }

        // Only a positive mismatch on both sides is a real version skew (0/blank means "unknown").
        val versionSkew = inputs.installedVersionCode > 0 && beacon.moduleVersionCode > 0 &&
                beacon.moduleVersionCode != inputs.installedVersionCode
        val buildSkew = inputs.installedBuildId.isNotBlank() && beacon.buildId.isNotBlank() &&
                beacon.buildId != inputs.installedBuildId
        if (versionSkew || buildSkew) {
            return ModuleState(
                ModuleStatus.OUTDATED,
                "system_server 仍运行旧版本（已加载 v${beacon.moduleVersionCode}/${beacon.buildId.ifEmpty { "?" }}，" +
                        "当前已安装 v${inputs.installedVersionCode}/${inputs.installedBuildId.ifEmpty { "?" }}）：" +
                        "系统框架作用域无法热重载，需软重启后生效",
                beacon
            )
        }

        if (beacon.connectivityHooks <= 0) {
            return ModuleState(
                ModuleStatus.PARTIAL,
                "模块已加载但 ConnectivityService hook 未安装（cs=0）：应用可见的网络状态不会被改写",
                beacon
            )
        }

        return ModuleState(
            ModuleStatus.ACTIVE,
            "已激活：ConnectivityService hook ${beacon.connectivityHooks} 个，" +
                    "副 Wi-Fi 保活 hook ${beacon.slaveWifiHooks + beacon.dualStaHooks} 个",
            beacon
        )
    }

    /** Renders a PID for operator-facing messages; empty when the record carries no PID. */
    private fun pidText(pid: Int?): String = if (pid != null && pid > 0) "（PID $pid）" else ""
}

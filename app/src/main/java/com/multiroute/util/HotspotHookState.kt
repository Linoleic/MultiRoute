package com.multiroute.util

enum class HotspotHookState { LOADED, NOT_LOADED, UNKNOWN, VERSION_MISMATCH }

/** A beacon is evidence of a Hook only when it belongs to the currently running process. */
object HotspotHookStateParser {
    const val FILE_NAME = "multiroute_hotspot_status"

    fun evaluate(
        raw: String?,
        livePid: Int?,
        liveStartTicks: Long?,
        expectedBuild: String
    ): HotspotHookState {
        if (livePid == null || liveStartTicks == null) return HotspotHookState.UNKNOWN
        if (raw.isNullOrBlank()) return HotspotHookState.NOT_LOADED
        val fields = raw.trim().split(';').mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
        if (fields["v"] != "2" || fields["hook"] != "1" ||
            fields["pid"]?.toIntOrNull() != livePid || fields["start"]?.toLongOrNull() != liveStartTicks
        ) return HotspotHookState.NOT_LOADED
        return if (fields["build"] == expectedBuild) HotspotHookState.LOADED
        else HotspotHookState.VERSION_MISMATCH
    }

    /** /proc/PID/stat field 22, allowing spaces and parentheses in the process name. */
    fun processStartTicks(stat: String?): Long? = stat?.substringAfterLast(") ", "")
        ?.split(Regex("\\s+"))?.getOrNull(19)?.toLongOrNull()

    fun beacon(pid: Int, startTicks: Long, build: String, target: String, active: String): String =
        "v=2;hook=1;pid=$pid;start=$startTicks;build=$build;target=$target;active=$active"
}

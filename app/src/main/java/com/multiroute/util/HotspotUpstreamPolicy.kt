package com.multiroute.util

/** Network facts used for upstream selection; independent of Android and map iteration order. */
data class HotspotCandidate(
    val id: Long,
    val iface: String,
    val cellular: Boolean = false,
    val internet: Boolean = false,
    val dun: Boolean = false,
    val validated: Boolean = false,
    val hasAddress: Boolean = true,
    val hasDefaultRoute: Boolean = true,
    val subscriptions: Set<Int> = emptySet()
)

object HotspotUpstreamPolicy {
    fun normalize(target: String?): String = when (val value = target.orEmpty().trim().lowercase()) {
        "", "default" -> "default"
        "mobile", "cellular" -> "cellular"
        "primary_wifi" -> "wlan0"
        "slave_wifi" -> "wlan1"
        else -> value
    }

    fun needsCellular(target: String): Boolean = normalize(target).let {
        it == "cellular" || it.startsWith("rmnet") || it.startsWith("ccmni")
    }

    fun select(
        target: String,
        candidates: List<HotspotCandidate>,
        dunRequired: Boolean = false,
        cellularPermitted: Boolean = true,
        currentId: Long? = null,
        preferredSubscription: Int = -1
    ): HotspotCandidate? {
        val normalized = normalize(target)
        if (normalized == "default") return null
        return candidates.filter { candidate ->
            val nameMatches = normalized == "cellular" || candidate.iface.equals(normalized, true)
            val cellularTarget = needsCellular(normalized) || candidate.cellular
            nameMatches && candidate.iface.isNotBlank() && candidate.hasAddress && candidate.hasDefaultRoute &&
                (!cellularTarget || (cellularPermitted && candidate.cellular &&
                    if (dunRequired) candidate.dun else candidate.internet))
        }.sortedWith(
            compareByDescending<HotspotCandidate> { it.id == currentId }
                .thenByDescending { preferredSubscription >= 0 && preferredSubscription in it.subscriptions }
                .thenByDescending { it.validated }
                .thenBy { it.id }
        ).firstOrNull()
    }
}

/** Retains system intent separately from the temporary settings used to keep cellular requested. */
class HotspotRequestPolicy(autoUpstream: Boolean, dunRequired: Boolean, tryCell: Boolean) {
    var nativeAuto = autoUpstream
        private set
    var nativeDun = dunRequired
        private set
    var nativeTryCell = tryCell
        private set
    var observing = false
    var target = "default"

    fun onNativeConfig(auto: Boolean, dun: Boolean) {
        nativeAuto = auto
        nativeDun = dun
    }

    fun onNativeTryCell(tryCell: Boolean) { nativeTryCell = tryCell }

    fun effective(): Triple<Boolean, Boolean, Boolean> {
        val forceCell = observing && HotspotUpstreamPolicy.needsCellular(target)
        return Triple(if (forceCell) false else nativeAuto, nativeDun, if (forceCell) true else nativeTryCell)
    }
}

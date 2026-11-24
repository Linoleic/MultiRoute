package com.multiroute.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.multiroute.R
import com.multiroute.model.NetworkChannel
import com.multiroute.util.HotspotUpstreamPolicy
import com.multiroute.util.NetworkUtils
import com.multiroute.util.RouteRuleBuilder

internal fun NetworkChannel.isHotspotUpstream(): Boolean =
    !isVpn && !isHotspot && !cellularRestricted && !RouteRuleBuilder.isTunnelInterface(interfaceName)

internal data class HotspotUpstreamOption(
    val target: String,
    val channel: NetworkChannel? = null,
    val isDefault: Boolean = false,
    val isStandbyCellular: Boolean = false,
    val isOfflineSaved: Boolean = false
)

internal fun isHotspotTargetSelected(
    optionTarget: String,
    currentTarget: String,
    hasSpecificCellular: Boolean
): Boolean {
    val opt = HotspotUpstreamPolicy.normalize(optionTarget)
    val cur = HotspotUpstreamPolicy.normalize(currentTarget)
    if (opt == cur) return true
    return opt == "cellular" && !hasSpecificCellular && HotspotUpstreamPolicy.needsCellular(cur)
}

internal fun buildHotspotUpstreamOptions(
    channels: List<NetworkChannel>,
    currentTarget: String,
    supportsCellularData: Boolean = true,
    primaryConnected: Boolean = false,
    secondaryConnected: Boolean = false
): List<HotspotUpstreamOption> {
    val eligible = channels.filter { it.isHotspotUpstream() }.distinctBy { it.interfaceName }
    val cellularCount = eligible.count { it.transportType.equals("cellular", ignoreCase = true) }
    val hasSpecificCellular = cellularCount > 1

    val options = mutableListOf<HotspotUpstreamOption>()
    options += HotspotUpstreamOption(target = "default", isDefault = true)
    for (channel in eligible) {
        val isSingleCellular = channel.transportType.equals("cellular", ignoreCase = true) && cellularCount == 1
        options += HotspotUpstreamOption(
            target = if (isSingleCellular) "cellular" else channel.interfaceName,
            channel = channel
        )
    }
    if (cellularCount == 0 && supportsCellularData) {
        options += HotspotUpstreamOption(target = "cellular", isStandbyCellular = true)
    }
    if (primaryConnected && options.none { it.target.equals("wlan0", ignoreCase = true) }) {
        options += HotspotUpstreamOption(target = "wlan0")
    }
    if (secondaryConnected && options.none { it.target.equals("wlan1", ignoreCase = true) }) {
        options += HotspotUpstreamOption(target = "wlan1")
    }

    val normalized = HotspotUpstreamPolicy.normalize(currentTarget)
    if (normalized != "default" &&
        options.none { isHotspotTargetSelected(it.target, normalized, hasSpecificCellular) }
    ) {
        options += HotspotUpstreamOption(target = normalized, isOfflineSaved = true)
    }
    return options
}

@Composable
internal fun hotspotUpstreamLabel(target: String, channels: List<NetworkChannel>): String {
    val context = LocalContext.current
    val normalized = HotspotUpstreamPolicy.normalize(target)
    if (normalized == "default") return stringResource(R.string.dual_wifi_hotspot_upstream_default)
    val eligible = channels.filter { it.isHotspotUpstream() }
    if (normalized == "cellular") {
        val singleCellular = eligible.filter { it.transportType.equals("cellular", ignoreCase = true) }.singleOrNull()
        return if (singleCellular != null) NetworkUtils.channelLabel(context, singleCellular)
        else stringResource(R.string.ui_hotspot_upstream_default_cellular_short)
    }
    val matched = eligible.firstOrNull { it.interfaceName.equals(normalized, ignoreCase = true) }
    return when {
        matched != null -> NetworkUtils.channelLabel(context, matched)
        normalized == "wlan0" -> stringResource(R.string.wifi_i18n_primary_upstream)
        normalized == "wlan1" -> stringResource(R.string.wifi_i18n_secondary_upstream)
        HotspotUpstreamPolicy.needsCellular(normalized) ->
            stringResource(R.string.wifi_i18n_cellular_upstream, normalized)
        else -> normalized
    }
}

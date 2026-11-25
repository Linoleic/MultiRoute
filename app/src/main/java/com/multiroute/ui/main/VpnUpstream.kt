package com.multiroute.ui.main

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.multiroute.R
import com.multiroute.cellular.CellularTargets
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.RuleTarget
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.model.ScenarioObservation
import com.multiroute.util.NetworkUtils
import com.multiroute.util.RouteRuleBuilder
import com.multiroute.util.ScenarioEngine

internal fun NetworkChannel.isVpnUpstream(): Boolean =
    !isVpn && !isHotspot && !cellularRestricted && !RouteRuleBuilder.isTunnelInterface(interfaceName)

private fun vpnOwnerPackages(context: Context, uid: Int): List<String> =
    if (uid <= 0) emptyList() else context.packageManager.getPackagesForUid(uid).orEmpty().distinct()

private fun vpnOwnerRuleKey(packageName: String, uid: Int): String =
    RouteRuleBuilder.ruleKey(packageName, uid / 100_000)

internal fun resolveVpnOwnerTarget(context: Context, uid: Int): RuleTarget? {
    return resolveVpnOwnerTarget(uid, vpnOwnerPackages(context, uid))
}

private fun resolveVpnOwnerTarget(uid: Int, packages: List<String>): RuleTarget? {
    if (!RouteRuleBuilder.isValidUid(uid)) return null
    val pkg = packages.singleOrNull() ?: return null
    val key = vpnOwnerRuleKey(pkg, uid)
    return key.takeIf(RouteConfigProvider::isValidRuleKey)?.let { RuleTarget(it, uid) }
}

internal data class VpnUpstream(
    val ownerName: String?,
    val ownerTarget: RuleTarget?,
    val baseTarget: String,
    val effectiveTarget: String?,
    val scenarioName: String?,
    val configuredChannel: NetworkChannel?,
    val reportedChannel: NetworkChannel?,
    val reportedIface: String?
) {
    val canEdit: Boolean get() = ownerTarget != null
    val needsReconnect: Boolean
        get() = configuredChannel?.isVpnUpstream() == true &&
            reportedIface != null && reportedIface != configuredChannel.interfaceName
}

@Composable
internal fun rememberVpnUpstream(
    channel: NetworkChannel,
    channels: List<NetworkChannel>,
    scenario: ScenarioUiState
): VpnUpstream {
    val context = LocalContext.current
    val uid = channel.vpnOwnerUid
    val packages = remember(context, uid) { runCatching { vpnOwnerPackages(context, uid) }.getOrDefault(emptyList()) }
    val ownerTarget = remember(uid, packages) { resolveVpnOwnerTarget(uid, packages) }
    val ownerName = remember(context, uid, packages) {
        packages.singleOrNull()?.let { pkg ->
            val pm = context.packageManager
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            if (label == null || label == pkg) pkg else "$label ($pkg)"
        }
    }
    val prefs = remember(context) { context.getSharedPreferences(RouteConfigProvider.PREF_NAME, Context.MODE_PRIVATE) }
    val ownerKey = "uid_$uid"
    var baseTarget by remember(prefs, ownerKey) { mutableStateOf(prefs.getString(ownerKey, CHANNEL_DEFAULT) ?: CHANNEL_DEFAULT) }
    DisposableEffect(prefs, ownerKey) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            if (key == null || key == ownerKey) baseTarget = store.getString(ownerKey, CHANNEL_DEFAULT) ?: CHANNEL_DEFAULT
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val override = remember(channels, scenario, packages, uid) {
        val keys = packages.map { vpnOwnerRuleKey(it, uid) }.toSet()
        val observation = ScenarioObservation.fromChannels(channels)
        val selected = ScenarioEngine.selectProfiles(
            scenario.scenarioProfiles,
            observation,
            if (scenario.scenarioManualMode) ScenarioEngine.SelectionMode.MANUAL else ScenarioEngine.SelectionMode.AUTOMATIC,
            scenario.manualScenarioIds
        )
        selected.asReversed().firstNotNullOfOrNull { profile ->
            val raw = profile.overrides.entries.lastOrNull { it.key in keys }?.value
            val target = raw?.let { ScenarioEngine.resolveOverrideChannel(it, observation) }
            if (target == CHANNEL_DEFAULT || target != null &&
                (CellularTargets.isSim(target) || channels.any { target == it.id || target == it.interfaceName })
            ) {
                profile.name to target
            } else if (profile.bypassBaseRules) {
                profile.name to CHANNEL_DEFAULT
            } else null
        }
    }
    val effectiveTarget = override?.second ?: baseTarget.takeIf { uid > 0 }
    return VpnUpstream(
        ownerName = ownerName,
        ownerTarget = ownerTarget,
        baseTarget = baseTarget,
        effectiveTarget = effectiveTarget,
        scenarioName = override?.first,
        configuredChannel = channels.firstOrNull { it.id == effectiveTarget || it.interfaceName == effectiveTarget },
        reportedChannel = channels.firstOrNull { it.interfaceName == channel.underlyingIface },
        reportedIface = channel.underlyingIface
    )
}

@Composable
internal fun VpnUpstream.configuredLabel(): String {
    val context = LocalContext.current
    return when {
        effectiveTarget == null -> stringResource(R.string.ui_vpn_upstream_unavailable)
        effectiveTarget == CHANNEL_DEFAULT -> stringResource(R.string.vm_system_default)
        configuredChannel != null -> NetworkUtils.channelLabel(context, configuredChannel)
        else -> stringResource(R.string.ui_channel_offline_short, NetworkUtils.savedTargetLabel(context, effectiveTarget))
    }
}

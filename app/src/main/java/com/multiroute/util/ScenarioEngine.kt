package com.multiroute.util

import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.ScenarioObservation
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioResolution
import com.multiroute.model.ScenarioTrigger

/**
 * Picks the profile that applies and folds its overrides into the base assignments.
 *
 * Pure by design: no Android types, no preferences, no I/O - the caller supplies the profile list, the
 * current observation and (when the user pinned a profile by hand) its id. That keeps the interesting
 * rules - priority, first match, forced-default overrides, falling back to the base - unit-testable, which
 * is where this feature is most likely to go subtly wrong.
 */
object ScenarioEngine {

    /** Passed as [resolve]'s `manualId` when nothing is pinned. */
    const val MANUAL_NONE = ""

    /**
     * Prefix for an override that names a Wi-Fi network instead of an interface: `ssid:Hpkt` means "the
     * link that is connected to Hpkt right now". Resolved on every sync, so a plan keeps working when the
     * two Wi-Fi interfaces swap roles - which they do on device.
     */
    const val SYMBOLIC_SSID_PREFIX = "ssid:"

    fun symbolicSsid(ssid: String): String = SYMBOLIC_SSID_PREFIX + ssid

    fun isSymbolicChannel(value: String): Boolean = value.startsWith(SYMBOLIC_SSID_PREFIX)

    /** The SSID a symbolic channel names, or null for a plain interface name. */
    fun symbolicSsidOf(value: String): String? =
        if (isSymbolicChannel(value)) value.removePrefix(SYMBOLIC_SSID_PREFIX).takeIf { it.isNotEmpty() } else null

    /**
     * Turns an override value into the interface to use right now. A plain interface name is returned
     * unchanged; a symbolic one resolves through the links that are up, and null means "cannot be used now"
     * (the caller then leaves the base assignment alone rather than pointing at a link that is not there).
     */
    fun resolveOverrideChannel(value: String, observation: ScenarioObservation): String? {
        val ssid = symbolicSsidOf(value) ?: return value
        return observation.ssidToInterface.entries
            .firstOrNull { it.key.equals(ssid, ignoreCase = true) }
            ?.value
    }

    /** True when [trigger] applies to [observation]. */
    fun matches(trigger: ScenarioTrigger, observation: ScenarioObservation): Boolean = when (trigger) {
        is ScenarioTrigger.Always -> true
        is ScenarioTrigger.Manual -> false
        is ScenarioTrigger.CellularOnly -> observation.wifiLinkCount <= 0
        is ScenarioTrigger.SsidMatch -> observation.wifiSsids.any { connected ->
            trigger.ssids.any { it.equals(connected, ignoreCase = true) }
        }
        is ScenarioTrigger.WifiLinkCount -> {
            observation.wifiLinkCount >= trigger.min &&
                    (trigger.max == null || observation.wifiLinkCount <= trigger.max)
        }
    }

    /** The SSID that made an SSID trigger match, for the UI to show. */
    fun matchedSsid(trigger: ScenarioTrigger, observation: ScenarioObservation): String? {
        if (trigger !is ScenarioTrigger.SsidMatch) return null
        return observation.wifiSsids.firstOrNull { connected ->
            trigger.ssids.any { it.equals(connected, ignoreCase = true) }
        }
    }

    /**
     * Every profile that applies, lowest priority first.
     *
     * Plans layer rather than compete: with dual Wi-Fi up, a plan matching one link and a plan matching the
     * other both apply, each contributing its own overrides. A manual pin still short-circuits to that one
     * profile, because pinning means "only this".
     */
    fun selectProfiles(
        profiles: List<ScenarioProfile>,
        observation: ScenarioObservation,
        manualId: String? = null
    ): List<ScenarioProfile> {
        val pinned = manualId?.takeIf { it.isNotEmpty() && it != MANUAL_NONE }
        if (pinned != null) {
            // A pin that was deleted (or disabled) must not leave the device with no plan at all: fall
            // through to automatic selection instead.
            profiles.firstOrNull { it.id == pinned && it.enabled }?.let { return listOf(it) }
        }
        return profiles.filter { it.enabled && matches(it.trigger, observation) }
            .sortedBy { it.priority }
    }

    /** The profile that applies first, for the places that show a single plan. */
    fun selectProfile(
        profiles: List<ScenarioProfile>,
        observation: ScenarioObservation,
        manualId: String? = null
    ): Pair<ScenarioProfile?, ScenarioTrigger?> {
        val selected = selectProfiles(profiles, observation, manualId).firstOrNull()
        return selected to selected?.trigger
    }

    /**
     * Folds [overrides] (uid to channel) into [base]. A channel of [CHANNEL_DEFAULT] removes the uid, i.e.
     * the app follows the system default while the profile is active.
     */
    fun applyOverrides(
        base: Map<Int, String>,
        overrides: Map<Int, String>
    ): Pair<Map<Int, String>, Int> {
        if (overrides.isEmpty()) return base to 0
        val effective = base.toMutableMap()
        var forcedDefault = 0
        for ((uid, channelId) in overrides) {
            if (channelId == CHANNEL_DEFAULT || channelId.isEmpty()) {
                if (effective.remove(uid) != null) forcedDefault++
            } else {
                effective[uid] = channelId
            }
        }
        return effective to forcedDefault
    }

    /**
     * Full evaluation. [overridesByUid] is the active profile's overrides already resolved from rule keys
     * to UIDs, and [hasChannel] lets the caller drop overrides pointing at channels that do not exist any
     * more (a channel can disappear when a link is disconnected and reconfigured).
     */
    fun resolve(
        base: Map<Int, String>,
        profiles: List<ScenarioProfile>,
        observation: ScenarioObservation,
        manualId: String? = null,
        overridesByUid: (ScenarioProfile) -> Map<Int, String> = { emptyMap() },
        hasChannel: (String) -> Boolean = { true }
    ): ScenarioResolution {
        val selected = selectProfiles(profiles, observation, manualId)
        if (selected.isEmpty()) {
            return ScenarioResolution(effectiveRules = base)
        }

        // Fold every matching plan in priority order: a later plan can re-route an app an earlier one set,
        // and a later `default` can take one back out of routing.
        var effective = base
        var forcedDefault = 0
        var overridden = 0
        var unresolved = 0
        val overriddenBy = mutableMapOf<Int, String>()
        val lastPlanForUid = mutableMapOf<Int, String>()
        selected.forEach { profile ->
            val resolved = overridesByUid(profile).mapNotNull { (uid, raw) ->
                val channel = resolveOverrideChannel(raw, observation)
                when {
                    channel == null -> {
                        unresolved++
                        null
                    }
                    channel == CHANNEL_DEFAULT || hasChannel(channel) -> uid to channel
                    else -> null
                }
            }.toMap()
            // An app an earlier active plan already set: the later plan wins, and the loser is recorded so
            // the UI can say which plan was overridden instead of dropping it silently.
            resolved.keys.forEach { uid ->
                lastPlanForUid[uid]?.let { previous -> overriddenBy[uid] = previous }
                lastPlanForUid[uid] = profile.name
            }
            val (next, forced) = applyOverrides(effective, resolved)
            effective = next
            forcedDefault += forced
            overridden += resolved.count { it.value != CHANNEL_DEFAULT }
        }

        val first = selected.first()
        val trigger = first.trigger
        val pinned = !manualId.isNullOrEmpty() && manualId != MANUAL_NONE && manualId == first.id

        return ScenarioResolution(
            activeId = first.id,
            activeName = first.name,
            activeNames = selected.map { it.name },
            matchedTrigger = trigger,
            matchedSsid = matchedSsid(trigger, observation),
            appliedManually = pinned,
            effectiveRules = effective,
            overridden = overridden,
            forcedDefault = forcedDefault,
            overriddenBy = overriddenBy,
            unresolved = unresolved
        )
    }
}

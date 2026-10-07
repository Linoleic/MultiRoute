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
     * The profile that applies: a manual pin wins (as long as it is enabled), otherwise the enabled profile
     * with the lowest priority among those whose trigger matches. Disabled profiles never apply.
     */
    fun selectProfile(
        profiles: List<ScenarioProfile>,
        observation: ScenarioObservation,
        manualId: String? = null
    ): Pair<ScenarioProfile?, ScenarioTrigger?> {
        val pinned = manualId?.takeIf { it.isNotEmpty() && it != MANUAL_NONE }
        if (pinned != null) {
            profiles.firstOrNull { it.id == pinned && it.enabled }?.let { return it to it.trigger }
        }
        val chosen = profiles.filter { it.enabled && matches(it.trigger, observation) }
            .minByOrNull { it.priority }
        return chosen to chosen?.trigger
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
        val (profile, trigger) = selectProfile(profiles, observation, manualId)
        if (profile == null) {
            return ScenarioResolution(effectiveRules = base)
        }

        val resolved = overridesByUid(profile)
            .filterValues { it == CHANNEL_DEFAULT || hasChannel(it) }
        val (effective, forcedDefault) = applyOverrides(base, resolved)
        val pinned = !manualId.isNullOrEmpty() && manualId != MANUAL_NONE && manualId == profile.id

        return ScenarioResolution(
            activeId = profile.id,
            activeName = profile.name,
            matchedTrigger = trigger,
            matchedSsid = trigger?.let { matchedSsid(it, observation) },
            appliedManually = pinned,
            effectiveRules = effective,
            overridden = resolved.count { it.value != CHANNEL_DEFAULT },
            forcedDefault = forcedDefault
        )
    }
}

package com.multiroute.model

/**
 * A named overlay plus the condition under which it applies.
 *
 * The design mirrors how the feature is meant to be used: the per-app assignments in the routing screen
 * are the *default* plan, and a profile only overrides the apps it mentions. Nothing has to be duplicated
 * between profiles, and switching a profile never loses the base configuration.
 */
sealed class ScenarioTrigger {
    /** Applies whenever the profile is enabled (a standing override). */
    object Always : ScenarioTrigger()

    /** Applies when any connected Wi-Fi SSID is in [ssids]; matching ignores case. */
    data class SsidMatch(val ssids: List<String>) : ScenarioTrigger()

    /**
     * Applies when the number of connected Wi-Fi links is within [min]..[max] ([max] null = unbounded).
     * Counting links rather than naming interfaces is deliberate: which interface is `wlan0` and which is
     * `wlan1` swaps while the device runs (measured on device), so interface names are not a stable trigger.
     */
    data class WifiLinkCount(val min: Int = 0, val max: Int? = null) : ScenarioTrigger()

    /** Applies when there is no Wi-Fi link at all, i.e. cellular only. */
    object CellularOnly : ScenarioTrigger()

    /** Never matches on its own; the user applies it by hand. */
    object Manual : ScenarioTrigger()
}

/** One profile: what it is called, when it applies, and which apps it overrides. */
data class ScenarioProfile(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** Lower wins. Ties are broken by the order the profiles are listed in. */
    val priority: Int = 100,
    val trigger: ScenarioTrigger,
    /**
     * `ruleKey` (`pkg` or `pkg@<userId>`) to channel id. [com.multiroute.model.CHANNEL_DEFAULT] means
     * "put this app back on the system default in this profile", which is how a scenario can *remove* an
     * app from routing.
     */
    val overrides: Map<String, String> = emptyMap()
)

/** Everything a trigger is allowed to look at, so the engine stays a pure function. */
data class ScenarioObservation(
    val wifiSsids: List<String> = emptyList(),
    val wifiLinkCount: Int = 0,
    val hasCellular: Boolean = false,
    /**
     * SSID → interface name for the links that are up. Lets an override be written as "the Wi-Fi whose
     * SSID is X" instead of naming an interface: which interface is `wlan0` and which is `wlan1` swaps
     * while the device runs (measured on device), so naming the interface is the fragile option.
     */
    val ssidToInterface: Map<String, String> = emptyMap()
) {
    companion object {
        /** Builds the observation from the channel list the app already collects for the UI. */
        fun fromChannels(channels: List<NetworkChannel>): ScenarioObservation {
            val wifi = channels.filter { it.transportType == "WLAN" }
            return ScenarioObservation(
                wifiSsids = wifi.mapNotNull { it.ssid?.takeIf { ssid -> ssid.isNotBlank() } },
                wifiLinkCount = wifi.size,
                hasCellular = channels.any { it.transportType == "cellular" },
                ssidToInterface = wifi.mapNotNull { channel ->
                    channel.ssid?.takeIf { it.isNotBlank() }?.let { it to channel.interfaceName }
                }.toMap()
            )
        }
    }
}

/** The outcome of evaluating the profiles: which one won, why, and the rules that follow from it. */
data class ScenarioResolution(
    val activeId: String? = null,
    val activeName: String? = null,
    /**
     * Every plan that applied, lowest priority first. Several plans can be active at once - which is what
     * happens when two Wi-Fi links are up and each has a plan of its own - so this is the full list while
     * [activeId]/[activeName] name the first one, for the places that show a single plan.
     */
    val activeNames: List<String> = emptyList(),
    /** The trigger that matched, so the UI can explain itself in the user's language. */
    val matchedTrigger: ScenarioTrigger? = null,
    /** The SSID that caused the match, when it was an SSID trigger. */
    val matchedSsid: String? = null,
    /** True when the profile was applied by hand rather than by its trigger. */
    val appliedManually: Boolean = false,
    /** Base assignments with the profile's overrides folded in; this is what the kernel receives. */
    val effectiveRules: Map<Int, String> = emptyMap(),
    /** How many apps the profile re-routed, and how many it took out of routing. */
    val overridden: Int = 0,
    val forcedDefault: Int = 0,
    /**
     * UID → name of the plan whose value lost, when more than one active plan set the same app. The winner
     * applied; this is what makes the loser visible instead of silently dropped.
     */
    val overriddenBy: Map<Int, String> = emptyMap(),
    /** Apps whose plan value did not resolve (the named Wi-Fi is not up), so the base assignment stands. */
    val unresolved: Int = 0
)

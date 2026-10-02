package com.multiroute.util

/**
 * Pure helper object responsible for building Linux policy routing rules for IPv4 and IPv6,
 * local network (LAN) bypass rules, and input sanitization to prevent command injection.
 */
object RouteRuleBuilder {
    private val SAFE_IFACE_REGEX = Regex("^[a-zA-Z0-9_.]{1,15}$")
    const val DEFAULT_RULE_PREF = 14500
    const val LAN_BYPASS_PREF = 14400

    /**
     * Validates whether an interface name consists strictly of alphanumeric and safe chars.
     * Prevents shell command injection vulnerabilities.
     */
    fun isValidInterfaceName(iface: String): Boolean {
        return SAFE_IFACE_REGEX.matches(iface)
    }

    /**
     * Validates whether an Android UID is in valid user app range.
     */
    fun isValidUid(uid: Int): Boolean {
        return uid in 10000..Int.MAX_VALUE
    }

    /**
     * Builds cleanup commands for routing rules at specified preference levels.
     */
    fun buildCleanupCommands(prefs: List<Int> = listOf(DEFAULT_RULE_PREF, LAN_BYPASS_PREF)): List<String> {
        val commands = mutableListOf<String>()
        for (pref in prefs) {
            commands.add("while ip rule del pref $pref 2>/dev/null; do :; done;")
            commands.add("while ip -6 rule del pref $pref 2>/dev/null; do :; done;")
        }
        return commands
    }

    /**
     * Builds local network (LAN) bypass rules at [lanBypassPref] priority.
     * Ensures local RFC1918 subnets and IPv6 link-local / ULA subnets resolve
     * via local routing before per-UID policy routing triggers, avoiding
     * network unreachable errors for LAN resources (NAS, printers, gateways).
     */
    fun buildLanBypassCommands(lanBypassPref: Int = LAN_BYPASS_PREF): List<String> {
        return listOf(
            "ip rule add to 192.168.0.0/16 lookup main pref $lanBypassPref;",
            "ip rule add to 10.0.0.0/8 lookup main pref $lanBypassPref;",
            "ip rule add to 172.16.0.0/12 lookup main pref $lanBypassPref;",
            "ip rule add to 169.254.0.0/16 lookup main pref $lanBypassPref;",
            "ip -6 rule add to fc00::/7 lookup local pref $lanBypassPref;",
            "ip -6 rule add to fe80::/10 lookup local pref $lanBypassPref;"
        )
    }

    /**
     * Builds per-UID routing rules for both IPv4 and IPv6.
     * Rejects invalid interfaces and UIDs.
     */
    fun buildUidRoutingCommands(
        channelToUidsMap: Map<String, List<Int>>,
        pref: Int = DEFAULT_RULE_PREF
    ): List<String> {
        val commands = mutableListOf<String>()
        val sortedChannels = channelToUidsMap.keys.sorted()
        for (iface in sortedChannels) {
            if (!isValidInterfaceName(iface)) continue
            val uids = channelToUidsMap[iface]?.filter { isValidUid(it) }?.distinct()?.sorted() ?: continue
            for (uid in uids) {
                commands.add("ip rule add uidrange $uid-$uid lookup $iface pref $pref;")
                commands.add("ip -6 rule add uidrange $uid-$uid lookup $iface pref $pref;")
            }
        }
        return commands
    }

    /**
     * Generates a single script string to synchronize kernel policy routing rules.
     */
    fun buildFullSyncScript(
        channelToUidsMap: Map<String, List<Int>>,
        pref: Int = DEFAULT_RULE_PREF,
        lanBypassPref: Int = LAN_BYPASS_PREF
    ): String {
        val commands = mutableListOf<String>()
        commands.addAll(buildCleanupCommands(listOf(pref, lanBypassPref)))

        val uidCmds = buildUidRoutingCommands(channelToUidsMap, pref)
        if (uidCmds.isNotEmpty()) {
            commands.addAll(buildLanBypassCommands(lanBypassPref))
            commands.addAll(uidCmds)
        }

        return commands.joinToString(" ")
    }
}

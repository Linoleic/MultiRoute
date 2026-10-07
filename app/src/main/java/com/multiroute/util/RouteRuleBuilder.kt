package com.multiroute.util

/**
 * Pure helper object responsible for building Linux policy routing rules for IPv4 and IPv6,
 * local network (LAN) bypass rules, and input sanitization to prevent command injection.
 */
object RouteRuleBuilder {
    private val SAFE_IFACE_REGEX = Regex("^[a-zA-Z0-9_.]{1,15}$")
    private val SAFE_IPV4_PREFIX_REGEX = Regex("""^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)/(?:[0-9]|[1-2][0-9]|3[0-2])$""")
    private val SAFE_IPV6_PREFIX_REGEX = Regex("""^[0-9a-fA-F:]+/(?:[0-9]|[1-9][0-9]|1[0-2][0-8])$""")

    const val DEFAULT_RULE_PREF = 14500
    const val LAN_BYPASS_PREF = 14400
    const val BOOT_LOG_PATH = "/data/adb/multiroute/last_boot_sync.log"

    /**
     * uid→interface cache read by the hook when LSPosed's RemotePreferences cannot be read yet.
     *
     * Observed on device: while the module app has not been started in the current boot,
     * `getRemotePreferences()` returns an *empty* (not failing) map, so the ConnectivityService hooks
     * had no idea about the configured rules for the first ~90 seconds of every boot. The app writes
     * this file on every sync (via root) and the module also refreshes it whenever it manages to read
     * the preferences, so the hook can always fall back to a known-good rule map.
     */
    const val RULE_CACHE_PATH = "/data/system/multiroute_rules_cache"

    /**
     * Root-writable marker holding the number of stored scenario plans, or absent when there are none.
     * The app publishes it on every sync; the module (inside system_server, always awake) reads it to
     * decide whether a link change is worth waking the app for, since plans are the only thing whose
     * effect depends on the current links.
     */
    const val SCENARIO_PLAN_MARKER = "/data/system/multiroute_scenario_plans"

    /**
     * Absolute path to the platform `ip` binary.
     *
     * Mandatory: these commands are also deployed as a `service.d` script, and there the PATH puts
     * busybox first - busybox's `ip` applet only understands the built-in table names
     * (`main`/`local`/`default`) and rejects interface-named tables such as `wlan1`
     * (`ip: invalid argument 'wlan1' to 'table'`), which silently broke every per-UID rule and the
     * readiness probe on a real device.
     */
    const val IP_BIN = "/system/bin/ip"

    /**
     * Absolute path to the platform `iptables` binary, for the same reason as [IP_BIN]: the boot script
     * runs with busybox first in PATH, and only the platform binary is guaranteed to exist.
     */
    const val IPTABLES_BIN = "/system/bin/iptables"

    /** Dedicated NAT chain for the DNS redirect, so it can be flushed without touching anything else. */
    const val DNS_NAT_CHAIN = "MULTIROUTE_DNS"

    /**
     * Validates whether an interface name consists strictly of alphanumeric and safe chars.
     * Prevents shell command injection vulnerabilities.
     */
    fun isValidInterfaceName(iface: String): Boolean {
        return SAFE_IFACE_REGEX.matches(iface)
    }

    /**
     * Validates whether an IPv4 or IPv6 CIDR prefix is syntactically well-formed and safe.
     */
    fun isValidPrefix(prefix: String): Boolean {
        return SAFE_IPV4_PREFIX_REGEX.matches(prefix) || SAFE_IPV6_PREFIX_REGEX.matches(prefix)
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
            commands.add("while $IP_BIN rule del pref $pref 2>/dev/null; do :; done;")
            commands.add("while $IP_BIN -6 rule del pref $pref 2>/dev/null; do :; done;")
        }
        return commands
    }

    /**
     * Builds local network (LAN) bypass rules at [lanBypassPref] priority.
     * Generates direct subnet rules for connected interfaces (IPv4 and IPv6) to route
     * on-link traffic directly through the associated channel rather than misrouting across channels.
     * Also retains RFC1918 and link-local ranges as fallbacks.
     */
    fun buildLanBypassCommands(
        connectedPrefixes: Map<String, List<String>> = emptyMap(),
        lanBypassPref: Int = LAN_BYPASS_PREF
    ): List<String> {
        val commands = mutableListOf<String>()

        // 1. Channel-specific direct on-link subnet bypass rules (IPv4 & IPv6)
        val sortedIfaces = connectedPrefixes.keys.sorted()
        for (iface in sortedIfaces) {
            if (!isValidInterfaceName(iface)) continue
            val prefixes = connectedPrefixes[iface]?.distinct()?.sorted() ?: continue
            for (prefix in prefixes) {
                if (isValidPrefix(prefix)) {
                    if (prefix.contains(":")) {
                        commands.add("$IP_BIN -6 rule add to $prefix lookup $iface pref $lanBypassPref;")
                    } else {
                        commands.add("$IP_BIN rule add to $prefix lookup $iface pref $lanBypassPref;")
                    }
                }
            }
        }

        // 2. Global RFC1918 IPv4 private subnets via 'main' table
        commands.add("$IP_BIN rule add to 192.168.0.0/16 lookup main pref $lanBypassPref;")
        commands.add("$IP_BIN rule add to 10.0.0.0/8 lookup main pref $lanBypassPref;")
        commands.add("$IP_BIN rule add to 172.16.0.0/12 lookup main pref $lanBypassPref;")
        commands.add("$IP_BIN rule add to 169.254.0.0/16 lookup main pref $lanBypassPref;")

        // 3. IPv6 link-local bypass
        commands.add("$IP_BIN -6 rule add to fe80::/10 lookup local pref $lanBypassPref;")

        return commands
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
                commands.add("$IP_BIN rule add uidrange $uid-$uid lookup $iface pref $pref;")
                commands.add("$IP_BIN -6 rule add uidrange $uid-$uid lookup $iface pref $pref;")
            }
        }
        return commands
    }

    /**
     * Generates a single script string to synchronize kernel policy routing rules.
     */
    fun buildFullSyncScript(
        channelToUidsMap: Map<String, List<Int>>,
        connectedPrefixes: Map<String, List<String>> = emptyMap(),
        pref: Int = DEFAULT_RULE_PREF,
        lanBypassPref: Int = LAN_BYPASS_PREF,
        channelResolvers: Map<String, List<String>> = emptyMap()
    ): String {
        val commands = mutableListOf<String>()
        commands.addAll(buildCleanupCommands(listOf(pref, lanBypassPref)))

        val uidCmds = buildUidRoutingCommands(channelToUidsMap, pref)
        if (uidCmds.isNotEmpty()) {
            commands.addAll(buildLanBypassCommands(connectedPrefixes, lanBypassPref))
            commands.addAll(uidCmds)
        }
        // DNS redirection is applied together with the routing rules so both are atomic and both are
        // replayed by the boot script. An empty [channelResolvers] flushes the chain and drops the jump.
        commands.addAll(buildDnsRedirectCommands(resolveUidResolvers(channelToUidsMap, channelResolvers)))

        return commands.joinToString(" ")
    }

    /** Maps every assigned UID to the resolvers of the channel it was assigned to. */
    fun resolveUidResolvers(
        channelToUidsMap: Map<String, List<Int>>,
        channelResolvers: Map<String, List<String>>
    ): Map<Int, List<String>> {
        if (channelResolvers.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, List<String>>()
        for ((iface, uids) in channelToUidsMap) {
            // Only IPv4 resolvers for now: the redirect below is an iptables (not ip6tables) rule.
            val resolvers = channelResolvers[iface].orEmpty().filter { it.isNotBlank() && !it.contains(':') }
            if (resolvers.isEmpty()) continue
            for (uid in uids) result[uid] = resolvers
        }
        return result
    }

    /**
     * Builds the NAT rules that send an assigned app's DNS queries to its channel's own resolver.
     *
     * Android chooses the resolver from the *default* network, independently of where the kernel routes
     * the packets, so an assigned app would otherwise query an address that may only be reachable over a
     * different link (or keep querying the resolver of a VPN tunnel). DNAT rewrites the destination before
     * the reroute, and the reroute then follows the same per-UID rule as the app's other traffic.
     *
     * A dedicated chain is used so this can be flushed atomically and never touches NAT rules that belong
     * to the ROM or another module.
     */
    fun buildDnsRedirectCommands(uidResolvers: Map<Int, List<String>>): List<String> {
        // Every command ends with `;`: the whole sync script is joined with spaces, so a missing separator
        // makes the shell treat the next command as an argument of this one (that silently produced an
        // empty chain containing nothing but the flush).
        val commands = mutableListOf<String>()
        commands.add("$IPTABLES_BIN -t nat -N $DNS_NAT_CHAIN 2>/dev/null || true;")
        commands.add("$IPTABLES_BIN -t nat -F $DNS_NAT_CHAIN;")

        val usable = uidResolvers.filterKeys { isValidUid(it) }
            .mapValues { (_, resolvers) -> resolvers.firstOrNull { isValidPrefix("$it/32") } }
            .filterValues { it != null }

        if (usable.isEmpty()) {
            commands.add("$IPTABLES_BIN -t nat -D OUTPUT -j $DNS_NAT_CHAIN 2>/dev/null || true;")
            return commands
        }
        commands.add(
            "$IPTABLES_BIN -t nat -C OUTPUT -j $DNS_NAT_CHAIN 2>/dev/null || " +
                    "$IPTABLES_BIN -t nat -A OUTPUT -j $DNS_NAT_CHAIN;"
        )
        for ((uid, resolver) in usable.toSortedMap()) {
            for (proto in listOf("udp", "tcp")) {
                commands.add(
                    "$IPTABLES_BIN -t nat -A $DNS_NAT_CHAIN -m owner --uid-owner $uid " +
                            "-p $proto --dport 53 -j DNAT --to-destination $resolver:53;"
                )
            }
        }
        return commands
    }

    /**
     * Builds a self-healing boot-time recovery script for Magisk/KernelSU `service.d`.
     *
     * `service.d` runs during `late_start`, i.e. usually before Wi-Fi/cellular routing tables exist.
     * Two failure modes were observed on a real device and shape this design:
     *
     *  1. applying too early fails for every interface-dependent rule, and because applying starts with
     *     a cleanup pass it can also *delete* rules another component (the app) already installed;
     *  2. a long-running watch loop is unreliable here - the shell interpreter reads the script file
     *     incrementally, so when the app rewrites it (which it does on every sync) the running
     *     instance can die mid-flight.
     *
     * Therefore the generated script stays short-lived and only applies once **every** referenced
     * channel has a routing table, and otherwise logs and exits, leaving later network changes to the
     * app (which keeps a NetworkCallback and is woken by the module's boot broadcast).
     */
    fun buildBootRestoreScript(
        applyScript: String,
        interfaces: Collection<String>,
        logPath: String = BOOT_LOG_PATH,
        waitAttempts: Int = 36,
        waitIntervalSeconds: Int = 5
    ): String {
        val safeIfaces = interfaces.filter { isValidInterfaceName(it) }.distinct().sorted()
        val logDir = logPath.substringBeforeLast('/', "/data/adb/multiroute")

        // Without any channel there is nothing to restore, and an empty `for` list would be a
        // shell syntax error - emit a self-explanatory no-op script instead.
        if (safeIfaces.isEmpty()) {
            return """
                |#!/system/bin/sh
                |# MultiRoute boot-time policy routing recovery
                |# Generated automatically - do not edit by hand.
                |mkdir -p $logDir
                |echo "[${'$'}(date '+%Y-%m-%d %H:%M:%S')] no channels configured; nothing to restore" >> "$logPath"
                |exit 0
                """.trimMargin()
        }

        val body = applyScript.trim().ifEmpty { ":" }
        val indentedBody = body.lines().joinToString("\n") { "  $it" }

        val template = """
#!/system/bin/sh
# MultiRoute boot-time policy routing recovery
# Generated automatically - do not edit by hand.
mkdir -p @@LOG_DIR@@

# service.d scripts are launched with busybox in PATH, whose `ip` applet does not support
# `ip route show table <name>` - so the readiness probe would always report "not ready".
# Absolute paths to the platform tools are therefore mandatory here.
log() {
  echo "[@@(/system/bin/date '+%Y-%m-%d %H:%M:%S')] @@*" >> "@@LOG_PATH@@"
}

# A channel is only usable once netd created its routing table with a default route.
is_ready() {
  /system/bin/ip route show table "@@1" 2>/dev/null | /system/bin/grep -qm1 '^default' && return 0
  /system/bin/ip -6 route show table "@@1" 2>/dev/null | /system/bin/grep -qm1 '^default' && return 0
  return 1
}

# Readiness mask of all channels, e.g. "10" = first ready, second not ready yet.
ready_state() {
  _st=""
  for _f in @@IFACES@@; do
    if is_ready "@@_f"; then _st="@@_st""1"; else _st="@@_st""0"; fi
  done
  echo "@@_st"
}

apply_rules() {
@@BODY@@
}

# service.d runs before the interfaces are connected, so wait (bounded) for their routing tables.
# Applying while a table is missing would both fail and wipe rules the app may already have set.
_n=0
while [ "@@_n" -lt @@WAIT_ATTEMPTS@@ ]; do
  _state="@@(ready_state)"
  case "@@_state" in
    *0*) ;;
    *) break ;;
  esac
  _n=@@((_n + 1))
  sleep @@WAIT_INTERVAL@@
done

_state="@@(ready_state)"
case "@@_state" in
  *0*)
    log "channels not ready (mask @@_state) after @@_n attempt(s); skipping apply - the MultiRoute app will apply once networks are up"
    ;;
  *)
    log "all channels ready (mask @@_state); applying rules"
    apply_rules
    log "apply finished (rc=@@?)"
    ;;
esac
exit 0
"""

        return template
            .trimIndent()
            .replace("@@BODY@@", indentedBody)
            .replace("@@IFACES@@", safeIfaces.joinToString(" "))
            .replace("@@WAIT_ATTEMPTS@@", waitAttempts.toString())
            .replace("@@WAIT_INTERVAL@@", waitIntervalSeconds.toString())
            .replace("@@LOG_DIR@@", logDir)
            .replace("@@LOG_PATH@@", logPath)
            .replace("@@", "$")
    }

    /**
     * Parses the output of `cmd package list packages -U` (`package:<name> uid:<uid>` lines) into a
     * name→uid map, keeping only [wanted] packages (when non-empty) and plausible app UIDs.
     *
     * Kept pure so the parsing - the part that silently broke the previous dual-app implementation -
     * is covered by unit tests.
     */
    fun parsePackageUidOutput(lines: Collection<String>, wanted: Set<String> = emptySet()): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            val name = line.removePrefix("package:").substringBefore(' ').trim()
            if (name.isEmpty()) continue
            if (wanted.isNotEmpty() && !wanted.contains(name)) continue
            val uid = line.substringAfter("uid:", "").trim().toIntOrNull() ?: continue
            if (uid >= 10000) result[name] = uid
        }
        return result
    }

    /**
     * Builds a single shell snippet that lists every secondary user (OEM clone space / work profile)
     * with its packages and UIDs. Emits `USER <id>` blocks followed by `package:<name> uid:<uid>` lines,
     * consumed by [parseSecondaryUserListing].
     *
     * Only plain numeric ids are iterated, because `for x in $(...)` word-splits on IFS. The user *name*
     * is deliberately not collected: it can contain spaces/non-ASCII and the UI shows the numeric space
     * id instead ("微信 (999)"), which removed a whole class of shell/parsing fragility.
     */
    fun buildSecondaryUserListingQuery(): String =
        "for id in \$(cmd user list 2>/dev/null | /system/bin/sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do " +
                "[ \"\$id\" = \"0\" ] && continue; " +
                "echo \"USER \$id\"; " +
                "cmd package list packages -U --user \"\$id\" 2>/dev/null; " +
                "done"

    /** One secondary user (app-clone space / work profile) and the packages installed in it. */
    data class SecondaryUserInstalls(
        val userId: Int,
        val packageUids: Map<String, Int>
    )

    /** Parses [buildSecondaryUserListingQuery] output; malformed blocks are skipped. */
    fun parseSecondaryUserListing(lines: Collection<String>): List<SecondaryUserInstalls> {
        val result = mutableListOf<SecondaryUserInstalls>()
        var userId = -1
        var block = mutableListOf<String>()

        fun flush() {
            if (userId > 0) {
                val uids = parsePackageUidOutput(block)
                if (uids.isNotEmpty()) result.add(SecondaryUserInstalls(userId, uids))
            }
            block = mutableListOf()
        }

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("USER ") -> {
                    flush()
                    userId = line.removePrefix("USER ").trim().toIntOrNull() ?: -1
                }

                line.startsWith("package:") -> block.add(line)
            }
        }
        flush()
        return result
    }

    /**
     * Rule-store key for a package install: the plain package name for the primary user and
     * `"<pkg>@<userId>"` for OEM clone spaces / work profiles, so both can be configured separately.
     */
    fun ruleKey(packageName: String, userId: Int = 0): String =
        if (userId == 0) packageName else "$packageName@$userId"

    /** Inverse of [ruleKey]; malformed keys are treated as primary-user keys. */
    fun parseRuleKey(key: String): Pair<String, Int> {
        val at = key.lastIndexOf('@')
        if (at <= 0) return key to 0
        val userId = key.substring(at + 1).toIntOrNull() ?: return key to 0
        return key.substring(0, at) to userId
    }

    /** Parses the `;`-separated UID list Android uses for `mobile_data_preferred_uids`. */
    fun parseUidList(raw: String?): Set<Int> =
        raw?.split(';')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { isValidUid(it) }
            ?.toSet()
            .orEmpty()

    /** Serializes a UID list in the same form; drops invalid entries and keeps it sorted. */
    fun buildUidList(uids: Collection<Int>): String =
        uids.filter { isValidUid(it) }.distinct().sorted().joinToString(";")

    /**
     * Reconciles the assigned cellular UIDs with the platform's list.
     *
     * Returns the new platform list and the new bookkeeping of what this app contributed. Only UIDs
     * recorded in [ours] are ever removed, so entries owned by the system or another tool survive
     * untouched, and only UIDs that were genuinely missing are recorded as ours.
     */
    fun mergeCellularUids(
        platform: Set<Int>,
        assigned: Set<Int>,
        ours: Set<Int>
    ): Pair<Set<Int>, Set<Int>> {
        val toRemove = ours - assigned
        val toAdd = assigned.filterNot { platform.contains(it) }.toSet()
        return ((platform + toAdd) - toRemove) to ((ours + toAdd) - toRemove)
    }

    /**
     * Extracts the `uid -> table` mapping from `ip rule show pref 14500` output, whose lines look like
     * `14500: from all uidrange 10530-10530 lookup wlan1`. Used to compare what is configured with what
     * the kernel actually holds; lines with a range instead of a single UID are ignored.
     */
    fun parseKernelUidRules(lines: Collection<String>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("$DEFAULT_RULE_PREF:")) continue
            val range = line.substringAfter("uidrange ", "").substringBefore(' ').trim()
            val table = line.substringAfter("lookup ", "").substringBefore(' ').trim()
            if (range.isEmpty() || table.isEmpty()) continue
            val from = range.substringBefore('-').toIntOrNull() ?: continue
            val to = range.substringAfter('-', range).toIntOrNull() ?: continue
            if (from != to || !isValidUid(from)) continue
            result[from] = table
        }
        return result
    }

    /**
     * Serializes a uid→interface map for [RULE_CACHE_PATH]. Invalid entries are dropped and the output
     * is sorted so the file stays stable/diffable; one `"<uid> <iface>"` record per line.
     */
    fun buildRuleCacheText(uidToIface: Map<Int, String>): String =
        uidToIface.entries
            .filter { isValidUid(it.key) && isValidInterfaceName(it.value) }
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key} ${it.value}" }

    /** Parses [buildRuleCacheText] output; blank, comment and malformed lines are ignored. */
    fun parseRuleCache(text: String?): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (raw in text?.lines().orEmpty()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val uid = parts[0].toIntOrNull() ?: continue
            val iface = parts[1]
            if (isValidUid(uid) && isValidInterfaceName(iface)) result[uid] = iface
        }
        return result
    }
}

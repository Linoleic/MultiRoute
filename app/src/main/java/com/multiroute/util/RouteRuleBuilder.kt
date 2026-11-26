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
    const val DNS_MARK_PREF = 14450
    const val LAN_BYPASS_PREF = 14400
    const val BOOT_LOG_PATH = "/data/adb/multiroute/last_boot_sync.log"

    /**
     * Effective uid→interface snapshot, published atomically by the app after kernel synchronization.
     * Hooks prefer it over base preferences, including an empty snapshot. Only the app writes it.
     */
    const val RULE_CACHE_PATH = "/data/system/multiroute_rules_cache"

    /**
     * Root-writable marker holding the number of stored scenario plans, or absent when there are none.
     * The app publishes it on every sync; the module (inside system_server, always awake) reads it to
     * decide whether a link change is worth waking the app for, since plans are the only thing whose
     * effect depends on the current links.
     */
    const val SCENARIO_PLAN_MARKER = "/data/system/multiroute_scenario_plans"
    const val CONFIGURED_ROUTE_INTERFACES = "/data/system/multiroute_configured_route_interfaces"

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

    /** Dedicated mangle chain that stamps per-interface fwmark on redirected DNS packets before NAT. */
    const val DNS_MANGLE_CHAIN = "MULTIROUTE_DNS_MARK"

    /** Dedicated NAT POSTROUTING chain to masquerade marked DNS packets when rerouted across interfaces. */
    const val DNS_SNAT_CHAIN = "MULTIROUTE_DNS_SNAT"

    /** Bits 24..30 of fwmark, disjoint from Android netd (0x001fffff) and wakeupctrl (0x80000000). */
    const val DNS_FWMARK_MASK = "0x7f000000"

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
        return uid >= 10000 && uid % 100000 >= 10000
    }

    /**
     * Builds cleanup commands for routing rules at specified preference levels.
     */
    fun buildCleanupCommands(prefs: List<Int> = listOf(DEFAULT_RULE_PREF, LAN_BYPASS_PREF, DNS_MARK_PREF)): List<String> {
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
        connectedPrefixes: Map<String, List<String>>,
        lanBypassPref: Int,
        uid: Int
    ): List<String> {
        if (!isValidUid(uid)) return emptyList()
        val commands = mutableListOf<String>()
        val selector = "uidrange $uid-$uid"

        // 1. Channel-specific direct on-link subnet bypass rules (IPv4 & IPv6)
        val sortedIfaces = connectedPrefixes.keys.sorted()
        for (iface in sortedIfaces) {
            if (!isValidInterfaceName(iface)) continue
            val prefixes = connectedPrefixes[iface]?.distinct()?.sorted() ?: continue
            for (prefix in prefixes) {
                if (isValidPrefix(prefix)) {
                    if (prefix.contains(":")) {
                        commands.add("$IP_BIN -6 rule add $selector to $prefix lookup $iface pref $lanBypassPref;")
                    } else {
                        commands.add("$IP_BIN rule add $selector to $prefix lookup $iface pref $lanBypassPref;")
                    }
                }
            }
        }

        // Private-subnet fallbacks for this physically routed UID.
        commands.add("$IP_BIN rule add $selector to 192.168.0.0/16 lookup main pref $lanBypassPref;")
        commands.add("$IP_BIN rule add $selector to 10.0.0.0/8 lookup main pref $lanBypassPref;")
        commands.add("$IP_BIN rule add $selector to 172.16.0.0/12 lookup main pref $lanBypassPref;")
        commands.add("$IP_BIN rule add $selector to 169.254.0.0/16 lookup main pref $lanBypassPref;")

        // 3. IPv6 link-local bypass
        commands.add("$IP_BIN -6 rule add $selector to fe80::/10 lookup local pref $lanBypassPref;")

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
                if (iface.startsWith("rmnet") || iface.startsWith("ccmni") || iface == "cellular") {
                    // Keep the guard even while IPv6 works: a later route withdrawal must not let
                    // this UID fall through to another SIM. A successful lookup above wins first.
                    commands.add("$IP_BIN -6 rule add unreachable uidrange $uid-$uid pref $pref;")
                }
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
        channelResolvers: Map<String, List<String>> = emptyMap(),
        vpnInterfaces: Set<String> = emptySet(),
        dnsExcludedUids: Set<Int> = emptySet(),
        dnsMarkPref: Int = DNS_MARK_PREF
    ): String {
        val commands = mutableListOf<String>()
        commands.addAll(buildCleanupCommands(listOf(pref, lanBypassPref, dnsMarkPref)))

        val uidCmds = buildUidRoutingCommands(channelToUidsMap, pref)
        if (uidCmds.isNotEmpty()) {
            val physicalPrefixes = connectedPrefixes.filterKeys {
                it !in vpnInterfaces && !isTunnelInterface(it)
            }
            val physicalUids = channelToUidsMap.filterKeys {
                isValidInterfaceName(it) && it !in vpnInterfaces && !isTunnelInterface(it)
            }.values.flatten().distinct().sorted()
            for (uid in physicalUids) {
                commands.addAll(buildLanBypassCommands(physicalPrefixes, lanBypassPref, uid))
            }
            commands.addAll(uidCmds)
        }
        // DNS redirection is applied together with the routing rules so both are atomic and both are
        // replayed by the boot script. An empty [channelResolvers] flushes the chain and drops the jump.
        val uidResolvers = resolveUidResolvers(channelToUidsMap, channelResolvers, dnsExcludedUids)
        val uidInterfaces = channelToUidsMap.flatMap { (iface, uids) -> uids.map { it to iface } }.toMap()
        commands.addAll(buildDnsRedirectCommands(uidResolvers, uidInterfaces, dnsMarkPref))

        return commands.joinToString(" ")
    }

    fun isTunnelInterface(iface: String): Boolean =
        iface.startsWith("tun") || iface.startsWith("tap") ||
            iface.startsWith("wg") || iface.startsWith("ppp")

    internal fun buildRouteReadinessProbe(iface: String, splitVpn: Boolean): String {
        val routeArgs = if (splitVpn) "type unicast dev $iface" else ""
        val match = if (splitVpn) "." else "^default([[:space:]]|$)"
        return listOf("-4", "-6").joinToString(" || ") { family ->
            "/system/bin/ip $family route show table $iface $routeArgs 2>/dev/null | " +
                "/system/bin/grep -Eq '$match'"
        }
    }

    /** Maps every assigned UID to the resolvers of the channel it was assigned to. */
    fun resolveUidResolvers(
        channelToUidsMap: Map<String, List<Int>>,
        channelResolvers: Map<String, List<String>>,
        excludedUids: Set<Int> = emptySet()
    ): Map<Int, List<String>> {
        if (channelResolvers.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, List<String>>()
        for ((iface, uids) in channelToUidsMap) {
            // Only IPv4 resolvers for now: the redirect below is an iptables (not ip6tables) rule.
            val resolvers = channelResolvers[iface].orEmpty().filter { it.isNotBlank() && !it.contains(':') }
            if (resolvers.isEmpty()) continue
            for (uid in uids) {
                if (uid in excludedUids) continue
                result[uid] = resolvers
            }
        }
        return result
    }

    /**
     * Builds the NAT + mangle rules that send an assigned app's DNS queries to its channel's own resolver.
     *
     * Android chooses the resolver from the *default* network, independently of where the kernel routes
     * the packets, so an assigned app would otherwise query an address that may only be reachable over a
     * different link (or keep querying the resolver of a VPN tunnel). DNAT rewrites the destination,
     * which triggers `ip_route_me_harder()` with `uid=0` — so we also stamp an interface-specific fwmark
     * in `mangle OUTPUT` (and masquerade in `nat POSTROUTING` if the source address changed) so the
     * rerouted packet still hits its assigned channel's routing table.
     */
    fun buildDnsRedirectCommands(
        uidResolvers: Map<Int, List<String>>,
        uidInterfaces: Map<Int, String> = emptyMap(),
        dnsMarkPref: Int = DNS_MARK_PREF
    ): List<String> {
        // Every command ends with `;`: the whole sync script is joined with spaces, so a missing separator
        // makes the shell treat the next command as an argument of this one (that silently produced an
        // empty chain containing nothing but the flush).
        val commands = mutableListOf<String>()
        commands.add("$IPTABLES_BIN -t nat -N $DNS_NAT_CHAIN 2>/dev/null || true;")
        commands.add("$IPTABLES_BIN -t nat -F $DNS_NAT_CHAIN;")
        commands.add("$IPTABLES_BIN -t mangle -N $DNS_MANGLE_CHAIN 2>/dev/null || true;")
        commands.add("$IPTABLES_BIN -t mangle -F $DNS_MANGLE_CHAIN;")
        commands.add("$IPTABLES_BIN -t nat -N $DNS_SNAT_CHAIN 2>/dev/null || true;")
        commands.add("$IPTABLES_BIN -t nat -F $DNS_SNAT_CHAIN;")

        val usable = uidResolvers.filterKeys { isValidUid(it) }
            .mapValues { (_, resolvers) -> resolvers.firstOrNull { isValidPrefix("$it/32") } }
            .filterValues { it != null }

        if (usable.isEmpty()) {
            commands.add("$IPTABLES_BIN -t nat -D OUTPUT -j $DNS_NAT_CHAIN 2>/dev/null || true;")
            commands.add("$IPTABLES_BIN -t mangle -D OUTPUT -j $DNS_MANGLE_CHAIN 2>/dev/null || true;")
            commands.add("$IPTABLES_BIN -t nat -D POSTROUTING -j $DNS_SNAT_CHAIN 2>/dev/null || true;")
            return commands
        }
        commands.add(
            "$IPTABLES_BIN -t nat -C OUTPUT -j $DNS_NAT_CHAIN 2>/dev/null || " +
                    "$IPTABLES_BIN -t nat -A OUTPUT -j $DNS_NAT_CHAIN;"
        )

        val referencedIfaces = usable.keys
            .mapNotNull { uidInterfaces[it]?.takeIf(::isValidInterfaceName) }
            .distinct()
            .sorted()
            .take(127)
        val ifaceMarks = referencedIfaces.mapIndexed { idx, iface ->
            iface to String.format(java.util.Locale.US, "0x%08x", (idx + 1) shl 24)
        }.toMap()

        if (ifaceMarks.isNotEmpty()) {
            commands.add(
                "$IPTABLES_BIN -t mangle -C OUTPUT -j $DNS_MANGLE_CHAIN 2>/dev/null || " +
                        "$IPTABLES_BIN -t mangle -A OUTPUT -j $DNS_MANGLE_CHAIN;"
            )
            commands.add(
                "$IPTABLES_BIN -t nat -C POSTROUTING -j $DNS_SNAT_CHAIN 2>/dev/null || " +
                        "$IPTABLES_BIN -t nat -A POSTROUTING -j $DNS_SNAT_CHAIN;"
            )
            commands.add(
                "$IPTABLES_BIN -t nat -A $DNS_SNAT_CHAIN -m mark ! --mark 0x0/$DNS_FWMARK_MASK -j MASQUERADE;"
            )
            for ((iface, markHex) in ifaceMarks) {
                commands.add("$IP_BIN rule add fwmark $markHex/$DNS_FWMARK_MASK lookup $iface pref $dnsMarkPref;")
            }
        } else {
            commands.add("$IPTABLES_BIN -t mangle -D OUTPUT -j $DNS_MANGLE_CHAIN 2>/dev/null || true;")
            commands.add("$IPTABLES_BIN -t nat -D POSTROUTING -j $DNS_SNAT_CHAIN 2>/dev/null || true;")
        }

        for ((uid, resolver) in usable.toSortedMap()) {
            val markHex = uidInterfaces[uid]?.let { ifaceMarks[it] }
            for (proto in listOf("udp", "tcp")) {
                if (markHex != null) {
                    commands.add(
                        "$IPTABLES_BIN -t mangle -A $DNS_MANGLE_CHAIN -m owner --uid-owner $uid " +
                                "-p $proto --dport 53 -j MARK --set-xmark $markHex/$DNS_FWMARK_MASK;"
                    )
                }
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
        waitIntervalSeconds: Int = 5,
        vpnInterfaces: Set<String> = emptySet()
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
                |mkdir -p $logDir && chmod 700 $logDir
                |if [ "${'$'}(getprop sys.boot_completed 2>/dev/null)" = "1" ] && /system/bin/pm path android >/dev/null 2>&1; then
                |  if ! /system/bin/pm path io.github.linoleic.multiroute >/dev/null 2>&1; then
                |    rm -f /data/adb/service.d/00-multiroute-restore.sh /data/adb/service.d/01-multiroute-dualwifi.sh /data/adb/service.d/02-multiroute-adb.sh /data/system/multiroute_active /data/system/multiroute_rules_cache /data/system/multiroute_scenario_plans /data/system/multiroute_configured_route_interfaces "$logPath" /data/adb/multiroute/adb_boot_status /data/adb/multiroute/last_adb_boot.log 2>/dev/null
                |    exit 0
                |  fi
                |fi
                |echo "[${'$'}(date '+%Y-%m-%d %H:%M:%S')] no channels configured; nothing to restore" >> "$logPath" && chmod 600 "$logPath" 2>/dev/null
                |exit 0
                """.trimMargin()
        }

        val body = applyScript.trim().ifEmpty { ":" }
        val indentedBody = body.lines().joinToString("\n") { "  $it" }

        val template = """
#!/system/bin/sh
# MultiRoute boot-time policy routing recovery
# Generated automatically - do not edit by hand.
mkdir -p @@LOG_DIR@@ && chmod 700 @@LOG_DIR@@

# Remove leftover root artifacts on post-boot invocation if the package was uninstalled.
cleanup_if_uninstalled() {
  if [ "@@(getprop sys.boot_completed 2>/dev/null)" = "1" ] && /system/bin/pm path android >/dev/null 2>&1; then
    if ! /system/bin/pm path io.github.linoleic.multiroute >/dev/null 2>&1; then
      rm -f /data/adb/service.d/00-multiroute-restore.sh /data/adb/service.d/01-multiroute-dualwifi.sh /data/adb/service.d/02-multiroute-adb.sh /data/system/multiroute_active /data/system/multiroute_rules_cache /data/system/multiroute_scenario_plans /data/system/multiroute_configured_route_interfaces "@@LOG_PATH@@" /data/adb/multiroute/adb_boot_status /data/adb/multiroute/last_adb_boot.log 2>/dev/null
      exit 0
    fi
  fi
}

cleanup_if_uninstalled

# service.d scripts are launched with busybox in PATH, whose `ip` applet does not support
# `ip route show table <name>` - so the readiness probe would always report "not ready".
# Absolute paths to the platform tools are therefore mandatory here.
log() {
  echo "[@@(/system/bin/date '+%Y-%m-%d %H:%M:%S')] @@*" >> "@@LOG_PATH@@" && chmod 600 "@@LOG_PATH@@" 2>/dev/null
}

# VPN channels can have split routes instead of a default route.
is_ready() {
  case "@@1" in
@@READY_CASES@@
    *) return 1 ;;
  esac
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
  cleanup_if_uninstalled
  _state="@@(ready_state)"
  case "@@_state" in
    *0*) ;;
    *) break ;;
  esac
  _n=@@((_n + 1))
  sleep @@WAIT_INTERVAL@@
done

cleanup_if_uninstalled

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
            .replace("@@READY_CASES@@", safeIfaces.joinToString("\n") { iface ->
                "    $iface) ${buildRouteReadinessProbe(iface, iface in vpnInterfaces)} ;;"
            })
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
            if (isValidUid(uid)) result[name] = uid
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
        "users=\$(cmd user list 2>/dev/null) || exit \$?; " +
                "ids=\$(printf '%s\\n' \"\$users\" | /system/bin/sed -n 's/.*UserInfo{\\([0-9][0-9]*\\):.*/\\1/p') || exit \$?; " +
                "for id in \$ids; do " +
                "[ \"\$id\" = \"0\" ] && continue; " +
                "packages=\$(cmd package list packages -U --user \"\$id\" 2>/dev/null) || exit \$?; " +
                "echo \"USER \$id\"; " +
                "printf '%s\\n' \"\$packages\"; " +
                "done"

    const val SECONDARY_QUERY_SENTINEL = "MULTIROUTE_SECONDARY_OK"

    /** One secondary user (app-clone space / work profile) and the packages installed in it. */
    data class SecondaryUserInstalls(
        val userId: Int,
        val packageUids: Map<String, Int>
    )

    /** Result of querying secondary-user installs, distinguishing empty devices from failed/timed-out shells. */
    data class SecondaryUserScanResult(
        val installs: List<SecondaryUserInstalls>,
        val succeeded: Boolean
    )

    fun parseSecondaryUserListingWithSentinel(
        lines: Collection<String>,
        sentinel: String = SECONDARY_QUERY_SENTINEL
    ): SecondaryUserScanResult {
        val succeeded = lines.any { it.trim() == sentinel }
        if (!succeeded) return SecondaryUserScanResult(emptyList(), succeeded = false)
        val body = lines.filterNot { it.trim() == sentinel }
        return SecondaryUserScanResult(parseSecondaryUserListing(body), succeeded = true)
    }

    /** Parses [buildSecondaryUserListingQuery] output; malformed blocks are skipped. */
    fun parseSecondaryUserListing(lines: Collection<String>): List<SecondaryUserInstalls> {
        val result = mutableListOf<SecondaryUserInstalls>()
        var userId = -1
        var block = mutableListOf<String>()

        fun flush() {
            if (userId > 0) {
                val uids = parsePackageUidOutput(block).filterValues { it / 100000 == userId }
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
    fun parseKernelUidRules(lines: Collection<String>, includeUnreachable: Boolean = false): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("$DEFAULT_RULE_PREF:")) continue
            val range = line.substringAfter("uidrange ", "").substringBefore(' ').trim()
            val table = line.substringAfter("lookup ", "").substringBefore(' ').trim().ifEmpty {
                if (includeUnreachable && "unreachable" in line.split(' ')) "unreachable" else ""
            }
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

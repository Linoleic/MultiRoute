package com.multiroute.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import com.multiroute.cellular.CellularSubscriptions
import com.multiroute.cellular.CellularTargets
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.ScenarioStore
import com.multiroute.hook.CellularDefaultNetworkPolicy
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.ScenarioObservation
import com.multiroute.model.ScenarioResolution
import com.multiroute.model.ScenarioTrigger
import com.multiroute.wifi.DualWifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates kernel policy routing synchronization, scenario evaluation, LAN/DNS rules,
 * boot-restore `service.d` script generation, and cellular preference bookkeeping.
 */
internal class RouteSyncCoordinator(
    private val shell: RootShellExecutor
) {
    companion object {
        private const val TAG = "MultiRoute-SU"

        /**
         * Budget for one full rule-application script. It contains 20-30 `ip` invocations, so it must not
         * be judged with the 5s default used for single commands.
         */
        private const val RULE_SCRIPT_TIMEOUT_SECONDS = 30L
    }

    /**
     * Serializes [syncAllRouteRules]: applying rules is "delete all, then add the current set", so two
     * overlapping runs would interleave and can leave a partial rule set (seen on device right after a
     * soft reboot: one rule present, the second one only a minute later).
     */
    private val syncMutex = Mutex()

    suspend fun <T> withRouteConfigLock(block: suspend () -> T): T = syncMutex.withLock { block() }

    /**
     * Checks default routes for physical channels and unicast routes for identified VPNs.
     * Prevents traffic blackholes when an interface is assigned but temporarily disconnected or has no route.
     */
    fun getInterfacesWithDefaultRoute(
        candidateIfaces: Collection<String>,
        vpnInterfaces: Set<String> = emptySet()
    ): Set<String> {
        val safeIfaces = candidateIfaces.filter { RouteRuleBuilder.isValidInterfaceName(it) }
        if (safeIfaces.isEmpty()) return emptySet()
        val cmd = safeIfaces.joinToString(" ") { iface ->
            "if ${RouteRuleBuilder.buildRouteReadinessProbe(iface, iface in vpnInterfaces)}; " +
                "then echo 'ONLINE:$iface'; fi;"
        }
        val lines = shell.executeShellWithOutput(cmd, timeoutSeconds = 3)
        return lines.filter { it.startsWith("ONLINE:") }
            .map { it.substringAfter(":").trim() }.filter { it in safeIfaces }.toSet()
    }

    /**
     * Filters interfaces that currently have verified public Internet access.
     * Checks Android platform validation (NET_CAPABILITY_VALIDATED) first as a zero-overhead fast-path,
     * then uses lightweight HTTP 204 probing for unvalidated or secondary interfaces.
     */
    suspend fun filterInterfacesWithInternet(
        context: Context,
        candidateIfaces: Collection<String>
    ): Set<String> = withContext(Dispatchers.IO) {
        val safeIfaces = candidateIfaces.filter { RouteRuleBuilder.isValidInterfaceName(it) }.toSet()
        if (safeIfaces.isEmpty()) return@withContext emptySet()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext safeIfaces

        val ifaceToNetworkMap = mutableMapOf<String, Network>()
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network)
            val iface = lp?.interfaceName
            if (iface != null && safeIfaces.contains(iface)) {
                ifaceToNetworkMap[iface] = network
            }
        }

        val results = safeIfaces.map { iface ->
            async {
                val net = ifaceToNetworkMap[iface]
                if (net == null) {
                    iface to false
                } else {
                    val caps = cm.getNetworkCapabilities(net)
                    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        iface to true
                    } else {
                        iface to NetworkUtils.checkNetworkInternet(net)
                    }
                }
            }
        }.awaitAll().toMap()

        results.filterValues { it }.keys
    }

    /**
     * Extracts connected local subnet prefixes for given active interfaces.
     * Uses ConnectivityManager LinkProperties routes, with shell fallback.
     */
    fun getConnectedSubnetPrefixes(context: Context, ifaces: Collection<String>): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        val safeIfaces = ifaces.filter {
            RouteRuleBuilder.isValidInterfaceName(it) && !RouteRuleBuilder.isTunnelInterface(it)
        }.toSet()
        if (safeIfaces.isEmpty()) return emptyMap()

        // 1. Android ConnectivityManager LinkProperties
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                @Suppress("DEPRECATION")
                val networks = cm.allNetworks
                for (network in networks) {
                    val lp = cm.getLinkProperties(network) ?: continue
                    val iface = lp.interfaceName ?: continue
                    if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                        continue
                    }
                    if (safeIfaces.contains(iface)) {
                        for (route in lp.routes) {
                            if (!route.isDefaultRoute && !route.hasGateway()) {
                                val dest = route.destination.toString()
                                if (RouteRuleBuilder.isValidPrefix(dest)) {
                                    result.getOrPut(iface) { mutableListOf() }.add(dest)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Shell fallback for any interfaces missing from ConnectivityManager
        for (iface in safeIfaces) {
            if (result[iface].isNullOrEmpty()) {
                val lines = shell.executeShellWithOutput(
                    "ip route show dev $iface proto kernel 2>/dev/null; ip -6 route show dev $iface proto kernel 2>/dev/null",
                    timeoutSeconds = 2
                )
                for (line in lines) {
                    val prefix = line.substringBefore(" ").trim()
                    if (RouteRuleBuilder.isValidPrefix(prefix) && !prefix.startsWith("default")) {
                        result.getOrPut(iface) { mutableListOf() }.add(prefix)
                    }
                }
            }
        }

        return result
    }

    /**
     * DNS resolvers of the given interfaces, as the platform itself sees them (IPv4 only, because the
     * redirect rule that consumes them is an `iptables` rule). Used to send an assigned app's queries to
     * its own channel instead of whatever resolver the default network provides.
     */
    fun getChannelResolvers(context: Context, ifaces: Collection<String>): Map<String, List<String>> {
        val safeIfaces = ifaces.filter { RouteRuleBuilder.isValidInterfaceName(it) }.toSet()
        if (safeIfaces.isEmpty()) return emptyMap()

        // 1. ConnectivityManager, i.e. exactly what the platform resolves with.
        val result = mutableMapOf<String, List<String>>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                @Suppress("DEPRECATION")
                for (network in cm.allNetworks) {
                    val lp = cm.getLinkProperties(network) ?: continue
                    val iface = lp.interfaceName ?: continue
                    if (!safeIfaces.contains(iface)) continue
                    val resolvers = lp.dnsServers.mapNotNull { it.hostAddress }
                        .filter { it.isNotBlank() && !it.contains(':') }
                    if (resolvers.isNotEmpty()) result[iface] = resolvers
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ConnectivityManager resolver lookup failed: ${t.message}")
        }
        if (result.keys.containsAll(safeIfaces)) return result

        // 2. Shell fallback: `dumpsys connectivity` exposes the same LinkProperties, and unlike the Java
        // API it needs no app-visible network. Used only for the channels still missing, because the Java
        // path returned nothing for them on a real device even though the data was present.
        try {
            val raw = shell.executeShellWithOutput("/system/bin/dumpsys connectivity 2>/dev/null", timeoutSeconds = 6)
                .joinToString(" ")
            for (chunk in raw.split("InterfaceName: ").drop(1)) {
                val iface = chunk.substringBefore(' ').trim()
                if (!safeIfaces.contains(iface) || result.containsKey(iface)) continue
                val dns = Regex("""DnsAddresses: \[([^\]]*)\]""").find(chunk)?.groupValues?.get(1).orEmpty()
                val resolvers = dns.split(',', '/').map { it.trim() }
                    .filter { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) }
                if (resolvers.isNotEmpty()) result[iface] = resolvers
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Shell resolver fallback failed: ${t.message}")
        }
        return result
    }

    /**
     * Deploys a dynamic boot restore script to the KernelSU/Magisk `service.d` directory.
     */
    fun updateBootRestoreScript(
        applyScript: String,
        interfaces: Collection<String>,
        vpnInterfaces: Set<String> = emptySet()
    ): Boolean {
        return try {
            val scriptContent = RouteRuleBuilder.buildBootRestoreScript(
                applyScript, interfaces, vpnInterfaces = vpnInterfaces
            )

            val cmd = "mkdir -p /data/adb/service.d /data/adb/multiroute && " +
                "cat << 'MCROUTE_EOF' > /data/adb/service.d/00-multiroute-restore.sh\n" +
                "$scriptContent\n" +
                "MCROUTE_EOF\n" +
                "chmod 755 /data/adb/service.d/00-multiroute-restore.sh"

            val success = shell.executeCommand(cmd)
            if (!success) {
                Log.w(TAG, "Boot restore script deployment returned a non-zero exit code")
            }
            success
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to update boot restore script: ${t.message}")
            false
        }
    }

    /**
     * Enumerates OEM clone-space / work-profile installs so the UI can list and configure them
     * separately from the primary-user installs of the same packages.
     */
    fun listSecondaryUserInstalls(): List<RouteRuleBuilder.SecondaryUserInstalls> {
        val lines = shell.executeShellWithOutput(RouteRuleBuilder.buildSecondaryUserListingQuery(), timeoutSeconds = 6)
        if (lines.isEmpty()) {
            Log.w(TAG, "Secondary-user listing returned nothing; clone apps will not be listed")
            return emptyList()
        }
        return RouteRuleBuilder.parseSecondaryUserListing(lines)
    }

    fun resolveUidForRuleKey(
        context: Context,
        ruleKey: String,
        secondaryInstalls: List<RouteRuleBuilder.SecondaryUserInstalls>? = null
    ): Int {
        val (pkg, userId) = RouteRuleBuilder.parseRuleKey(ruleKey)
        if (pkg.isEmpty()) return -1
        return if (userId <= 0) {
            runCatching { context.packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
        } else {
            val installs = secondaryInstalls
                ?: runCatching { listSecondaryUserInstalls() }.getOrDefault(emptyList())
            installs.firstOrNull { it.userId == userId }?.packageUids?.get(pkg) ?: -1
        }
    }

    /**
     * Evaluates the scenario profiles against the current links and folds the winner into [base].
     */
    suspend fun resolveScenarioProfile(
        context: Context,
        base: Map<Int, String>,
        profiles: List<com.multiroute.model.ScenarioProfile> = ScenarioStore.load(context)
    ): ScenarioResolution {
        if (profiles.isEmpty()) {
            return ScenarioResolution(effectiveRules = base)
        }

        val channels = runCatching { NetworkUtils.getActiveChannels(context) }.getOrDefault(emptyList())
        var observation = ScenarioObservation.fromChannels(channels)

        val needsSsids = profiles.any {
            it.enabled && it.trigger is ScenarioTrigger.SsidMatch
        }
        if (needsSsids && observation.wifiSsids.isEmpty()) {
            val fromRoot = runCatching { NetworkUtils.getConnectedWifiSsids().values.toList() }
                .getOrDefault(emptyList())
            observation = observation.copy(wifiSsids = fromRoot.filter { it.isNotBlank() }.distinct())
        }

        val knownChannels = channels.flatMap { listOf(it.id, it.interfaceName) }.toSet()
        var secondaryInstalls: List<RouteRuleBuilder.SecondaryUserInstalls>? = null
        val resolution = ScenarioEngine.resolve(
            base = base,
            profiles = profiles,
            observation = observation,
            mode = if (ScenarioStore.isManualMode(context)) {
                ScenarioEngine.SelectionMode.MANUAL
            } else {
                ScenarioEngine.SelectionMode.AUTOMATIC
            },
            manualIds = ScenarioStore.getManualIds(context),
            overridesByUid = { profile ->
                profile.overrides.mapNotNull { (ruleKey, channelId) ->
                    val (pkg, userId) = RouteRuleBuilder.parseRuleKey(ruleKey)
                    val uid = if (userId <= 0) {
                        runCatching { context.packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
                    } else {
                        val installs = secondaryInstalls
                            ?: runCatching { listSecondaryUserInstalls() }.getOrDefault(emptyList()).also {
                                secondaryInstalls = it
                            }
                        installs.firstOrNull { it.userId == userId }?.packageUids?.get(pkg) ?: -1
                    }
                    if (uid > 0) uid to channelId else null
                }.toMap()
            },
            hasChannel = { CellularTargets.isSim(it) || knownChannels.isEmpty() || it in knownChannels }
        )

        if (resolution.activeId != null) {
            Log.i(
                TAG,
                "Scenario '${resolution.activeName}' active " +
                    "(${if (resolution.appliedManually) "pinned by hand" else "matched automatically"}): " +
                    "overrides=${resolution.overridden}, forcedDefault=${resolution.forcedDefault}, " +
                    "ssid=${resolution.matchedSsid ?: "-"}"
            )
        }
        return resolution
    }

    suspend fun syncAllRouteRules(context: Context): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock { syncAllRouteRulesInternal(context) }
    }

    private suspend fun syncAllRouteRulesInternal(context: Context): Boolean {
        // A reconnect may arrive within the display cache TTL. Resolve SIM targets from fresh topology.
        CellularSubscriptions.invalidate()
        val keepAliveValue = if (RouteConfigProvider.isKeepSlaveWifiScreenOff(context)) "1" else "0"
        shell.executeCommand("setprop persist.multiroute.keep_slave_wifi $keepAliveValue")

        val baseUidRules = RouteConfigProvider.getUidRules(context)
        val profiles = ScenarioStore.load(context)
        val scenarioTargets = profiles.filter { it.enabled }.flatMap { it.overrides.values }
        val configuredInterfaces = (baseUidRules.filterKeys(RouteRuleBuilder::isValidUid).values + scenarioTargets)
            .filter(RouteRuleBuilder::isValidInterfaceName).toSortedSet()
        val targetsPath = RouteRuleBuilder.CONFIGURED_ROUTE_INTERFACES
        if (!shell.executeCommand(
                "printf '%s\\n' '${configuredInterfaces.joinToString(" ")}' > $targetsPath.tmp && " +
                    "chmod 644 $targetsPath.tmp && mv -f $targetsPath.tmp $targetsPath"
            )
        ) Log.w(TAG, "Could not publish configured route interfaces")
        val scenario = resolveScenarioProfile(context, baseUidRules, profiles)
        val runtimeChannels = NetworkUtils.getActiveChannels(context)
        val vpnChannels = runtimeChannels.filter { it.isVpn }.associateBy { it.interfaceName }
        val simInterfaces = runtimeChannels.filter { CellularTargets.isSim(it.id) }
            .groupBy({ it.id }, { it.interfaceName })
        val uidRules = scenario.effectiveRules.mapNotNull { (uid, target) ->
            CellularTargets.resolve(target, simInterfaces)?.let { uid to it }
        }.toMap()

        Log.i(
            TAG,
            "Effective rules: ${uidRules.size} entry/entries [" +
                uidRules.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" } +
                "] scenario=${scenario.activeNames.ifEmpty { listOf("-") }.joinToString("+")}"
        )

        val scenarioPlanCount = profiles.size +
            baseUidRules.values.count { CellularTargets.isSim(it) }
        shell.executeCommand(
            if (scenarioPlanCount > 0) {
                "echo $scenarioPlanCount > ${RouteRuleBuilder.SCENARIO_PLAN_MARKER}"
            } else {
                "rm -f ${RouteRuleBuilder.SCENARIO_PLAN_MARKER}"
            }
        )

        val channelToUidsMap = mutableMapOf<String, MutableList<Int>>()
        val hotspotIface = runCatching { DualWifiManager.checkHotspotState(context).iface }.getOrNull()

        for ((uid, channelId) in uidRules) {
            if (channelId == CHANNEL_DEFAULT) continue
            if (!RouteRuleBuilder.isValidUid(uid)) continue
            if (!RouteRuleBuilder.isValidInterfaceName(channelId)) continue
            if ((hotspotIface != null && channelId == hotspotIface) || channelId.startsWith("swlan") || channelId.startsWith("ap")) continue

            // Prevent anti-loopback: VPN client apps must not route into VPN tunnels
            if (channelId in vpnChannels || RouteRuleBuilder.isTunnelInterface(channelId)) {
                val ownerUid = vpnChannels[channelId]?.vpnOwnerUid ?: -1
                if (ownerUid > 0 && ownerUid % 100000 == uid % 100000) continue
                val pkgName = runCatching { context.packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
                if (pkgName != null && (
                        pkgName.contains("clash", ignoreCase = true) ||
                            pkgName.contains("v2ray", ignoreCase = true) ||
                            pkgName.contains("wireguard", ignoreCase = true) ||
                            pkgName.contains("singbox", ignoreCase = true) ||
                            pkgName.contains("nekobox", ignoreCase = true) ||
                            pkgName.contains("shadowsocks", ignoreCase = true)
                        )
                ) {
                    Log.w(TAG, "Skipping UID $uid ($pkgName) on VPN channel $channelId to prevent routing loopback")
                    continue
                }
            }

            channelToUidsMap.getOrPut(channelId) { mutableListOf() }.add(uid)
        }

        // 2. Validate interface route tables
        val candidateChannels = channelToUidsMap.keys
        if (candidateChannels.isEmpty()) {
            val success = shell.executeCommand(
                RouteRuleBuilder.buildFullSyncScript(emptyMap()),
                timeoutSeconds = RULE_SCRIPT_TIMEOUT_SECONDS
            )
            if (!success || !updateRuleCache(context, emptyMap())) return false
            syncCellularPreferredUids(context, emptySet())
            val removed = shell.executeCommand("rm -f /data/adb/service.d/00-multiroute-restore.sh")
            notifyRouteRulesChanged(context)
            return removed
        }

        val autoFailover = RouteConfigProvider.isAutoFailoverEnabled(context)
        val probeInternet = RouteConfigProvider.isFailoverProbeInternet(context)
        val splitVpnInterfaces = vpnChannels.filterValues { it.vpnOwnerUid > 0 }.keys
        var routeReadyChannels = getInterfacesWithDefaultRoute(candidateChannels, splitVpnInterfaces)
        if ((candidateChannels intersect splitVpnInterfaces).any { it !in routeReadyChannels }) {
            // Network callbacks may precede installation of the VPN routing table by netd.
            delay(650L)
            routeReadyChannels = getInterfacesWithDefaultRoute(candidateChannels, splitVpnInterfaces)
        }

        val onlineChannels = if (autoFailover) {
            if (probeInternet && routeReadyChannels.isNotEmpty()) {
                val validated = filterInterfacesWithInternet(context, routeReadyChannels)
                Log.i(TAG, "Failover check: usableRoutes=$routeReadyChannels, internetValidated=$validated")
                validated
            } else {
                routeReadyChannels
            }
        } else {
            routeReadyChannels
        }

        if (onlineChannels.isEmpty() && candidateChannels.isNotEmpty()) {
            Log.w(
                TAG,
                "None of $candidateChannels is currently usable (offline or unvalidated); clearing their kernel rules " +
                    "(traffic falls back to the default network in that state anyway)"
            )
        }

        val activeChannelMap = channelToUidsMap.filterKeys { iface ->
            val isOnline = onlineChannels.contains(iface)
            if (!isOnline) {
                Log.w(TAG, "Interface $iface is not usable (offline or unvalidated); skipping to prevent traffic blackholing")
            }
            isOnline
        }

        // 3. Collect connected local subnet prefixes & resolvers for active channels
        val connectedPrefixes = getConnectedSubnetPrefixes(context, activeChannelMap.keys - vpnChannels.keys)
        val channelResolvers = if (RouteConfigProvider.isDnsFollowsChannel(context)) {
            getChannelResolvers(context, activeChannelMap.keys)
        } else {
            emptyMap()
        }
        Log.i(TAG, "DNS redirect: channelResolvers=$channelResolvers for ${activeChannelMap.keys}")

        // 4. Sync Linux kernel policy routing dynamically for active interfaces
        val script = RouteRuleBuilder.buildFullSyncScript(
            activeChannelMap,
            connectedPrefixes,
            channelResolvers = channelResolvers,
            vpnInterfaces = vpnChannels.keys
        )
        val success = shell.executeCommand(script, timeoutSeconds = RULE_SCRIPT_TIMEOUT_SECONDS)

        // 5. Deploy / update Magisk/KernelSU boot-time service.d restore script
        if (success) {
            val dynamicUids = scenario.effectiveRules.filterValues { CellularTargets.isSim(it) }.keys
            val bootChannels = activeChannelMap.ifEmpty { channelToUidsMap }.mapValues { (_, uids) ->
                uids.filterNot { it in dynamicUids }
            }.filterValues { it.isNotEmpty() }
            val bootScript = RouteRuleBuilder.buildFullSyncScript(
                bootChannels, connectedPrefixes, vpnInterfaces = vpnChannels.keys
            )
            val bootSaved = if (bootChannels.isEmpty()) shell.executeCommand("rm -f /data/adb/service.d/00-multiroute-restore.sh")
            else updateBootRestoreScript(bootScript, bootChannels.keys, splitVpnInterfaces)
            if (activeChannelMap.isEmpty()) {
                if (!updateRuleCache(context, emptyMap())) return false
                Log.w(TAG, "All configured channels are offline; kernel rules cleared, boot script updated with current targets")
            } else {
                if (!updateRuleCache(context, activeChannelMap, dynamicUids)) return false
            }
            val cellularUids = activeChannelMap.filterKeys {
                CellularDefaultNetworkPolicy.isCellular(it)
            }.values.flatten().toSet()
            syncCellularPreferredUids(context, cellularUids)
            notifyRouteRulesChanged(context)
            return bootSaved
        }

        return success
    }

    private fun syncMobileDataPreferredUids(context: Context, desired: Set<Int>) {
        val text = RouteRuleBuilder.buildUidList(desired)
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, "mobile_data_preferred_uids") ?: ""
        }.getOrDefault("")
        if (text == current) return
        val direct = runCatching {
            Settings.Secure.putString(context.contentResolver, "mobile_data_preferred_uids", text)
        }.getOrDefault(false)
        if (!direct) {
            shell.executeCommand("settings put secure mobile_data_preferred_uids \"$text\"")
        }
    }

    private fun notifyRouteRulesChanged(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ModuleStateParser.ACTION_ROUTES_CHANGED).setPackage("android"))
        }.onFailure { Log.w(TAG, "Could not notify system_server of route changes", it) }
    }

    /** Publish only successfully applied, online assignments; an empty file is authoritative. */
    private fun updateRuleCache(
        context: Context,
        channelToUidsMap: Map<String, List<Int>>,
        dynamicUids: Set<Int> = emptySet()
    ): Boolean {
        val routes = channelToUidsMap.flatMap { (iface, uids) -> uids.map { it to iface } }.toMap()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val text = "#boot:$boot\n#dynamic:${dynamicUids.sorted().joinToString(",")}\n" + RouteRuleBuilder.buildRuleCacheText(routes)
        val path = RouteRuleBuilder.RULE_CACHE_PATH
        // Root writes a sibling and renames it, so system_server cannot observe a partial snapshot.
        val command = "(cat > $path.tmp << 'MCROUTE_CACHE_EOF'\n" + text +
            "\nMCROUTE_CACHE_EOF\n) && chmod 644 $path.tmp && mv -f $path.tmp $path"
        return shell.executeCommand(command)
    }

    private fun syncCellularPreferredUids(context: Context, cellularUids: Set<Int>) {
        val oursMerged = RouteConfigProvider.getMergedCellularUids(context)
        val platformUids = RouteRuleBuilder.parseUidList(
            runCatching {
                Settings.Secure.getString(context.contentResolver, "mobile_data_preferred_uids")
            }.getOrDefault("")
        )
        val (desiredPlatformUids, ourContribution) =
            RouteRuleBuilder.mergeCellularUids(platformUids, cellularUids, oursMerged)
        syncMobileDataPreferredUids(context, desiredPlatformUids)
        RouteConfigProvider.setMergedCellularUids(context, ourContribution)
    }
}

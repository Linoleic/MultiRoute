package com.multiroute.util

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.multiroute.BuildConfig
import com.multiroute.data.RouteConfigProvider
import com.multiroute.model.CHANNEL_DEFAULT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticInfo(
    val isRootGranted: Boolean = false,
    val suVersion: String = "",
    val isModuleActive: Boolean = false,
    /** Precise module state; [isModuleActive] is kept as the coarse "loaded in system_server" flag. */
    val moduleStatus: ModuleStatus = ModuleStatus.NOT_ACTIVE,
    val moduleStatusDetail: String = "",
    val kernelRulesCount: Int = 0,
    val kernelRules: List<String> = emptyList(),
    val mobileDataAlwaysOn: Boolean = false,
    val mobileDataPreferredUids: String = "",
    val isKeepSlaveWifiScreenOff: Boolean = false,
    val activeTables: List<String> = emptyList(),
    val wifiSsids: Map<String, String> = emptyMap(),
    /** uid→channel as configured in the UI. */
    val configuredUidRules: Map<Int, String> = emptyMap(),
    /** uid→table as it actually exists in the kernel, so the two can be compared in the UI. */
    val kernelUidRules: Map<Int, String> = emptyMap(),
    /** Tail of the boot-recovery log written by the generated `service.d` script. */
    val bootRestoreLog: List<String> = emptyList()
)

object SuHelper {
    private const val TAG = "MultiRoute-SU"

    /**
     * Serializes [syncAllRouteRules]: applying rules is "delete all, then add the current set", so two
     * overlapping runs would interleave and can leave a partial rule set (seen on device right after a
     * soft reboot: one rule present, the second one only a minute later).
     */
    private val syncMutex = Mutex()

    /**
     * Budget for one full rule-application script. It contains 20-30 `ip` invocations, so it must not
     * be judged with the 5s default used for single commands.
     */
    private const val RULE_SCRIPT_TIMEOUT_SECONDS = 30L

    /** Pseudo PID used when a legacy flag proves the module ran but recorded no process. */
    private const val UNKNOWN_PID = -1

    fun isKeepSlaveWifiScreenOff(context: Context): Boolean {
        return RouteConfigProvider.isKeepSlaveWifiScreenOff(context)
    }

    suspend fun setKeepSlaveWifiScreenOff(context: Context, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        RouteConfigProvider.setKeepSlaveWifiScreenOff(context, enabled)

        // 1. Transient system property for instantaneous system_server hook access
        val propVal = if (enabled) "1" else "0"
        executeCommand("setprop sys.multiroute.keep_slave_wifi $propVal")

        // 1b. Persistent twin of the same flag. `sys.*` is wiped on reboot and RemotePreferences are
        // unreadable until this app has been started, so without this the keep-alive silently judged
        // itself "off" for the first ~90s of every boot (verified on device).
        executeCommand("setprop persist.multiroute.keep_slave_wifi $propVal")

        // 2. Xiaomi system setting: 0 = disable auto teardown, 1 = enable auto teardown
        val autoDisableVal = if (enabled) 0 else 1
        val direct = runCatching {
            Settings.System.putInt(context.contentResolver, "test_wifi_slave_auto_disable", autoDisableVal)
        }.getOrDefault(false)
        if (!direct) {
            executeCommand("settings put system test_wifi_slave_auto_disable $autoDisableVal")
        }

        // 3. Ask the running module to install (or stop installing) the Xiaomi dual-Wi-Fi hooks right away,
        // so enabling the switch takes effect without a reboot. It re-checks the persistent flag above, so
        // a stray broadcast cannot turn the hooks on by itself.
        runCatching {
            context.sendBroadcast(android.content.Intent(ModuleStateParser.ACTION_KEEPALIVE_CHANGED))
        }
        true
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        val lines = executeShellWithOutput("id", timeoutSeconds = 3)
        lines.firstOrNull()?.contains("uid=0") == true
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun isMobileDataAlwaysOn(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    suspend fun enableMobileDataAlwaysOn(context: Context): Boolean = withContext(Dispatchers.IO) {
        val direct = runCatching {
            Settings.Global.putInt(context.contentResolver, "mobile_data_always_on", 1)
        }.getOrDefault(false)
        if (direct) return@withContext true
        executeCommand("settings put global mobile_data_always_on 1")
    }

    suspend fun disableMobileDataAlwaysOn(context: Context): Boolean = withContext(Dispatchers.IO) {
        val direct = runCatching {
            Settings.Global.putInt(context.contentResolver, "mobile_data_always_on", 0)
        }.getOrDefault(false)
        if (direct) return@withContext true
        executeCommand("settings put global mobile_data_always_on 0")
    }

    /**
     * Checks which network interfaces currently have active default routes in their kernel routing tables.
     * Prevents traffic blackholes when an interface is assigned but temporarily disconnected or has no route.
     */
    fun getInterfacesWithDefaultRoute(candidateIfaces: Collection<String>): Set<String> {
        val safeIfaces = candidateIfaces.filter { RouteRuleBuilder.isValidInterfaceName(it) }
        if (safeIfaces.isEmpty()) return emptySet()
        val cmd = safeIfaces.joinToString(" ") { iface ->
            "ip route show table $iface 2>/dev/null | grep -m1 '^default' && echo 'ONLINE_V4:$iface'; " +
            "ip -6 route show table $iface 2>/dev/null | grep -m1 '^default' && echo 'ONLINE_V6:$iface';"
        }
        val lines = executeShellWithOutput(cmd, timeoutSeconds = 3)
        val online = mutableSetOf<String>()
        for (line in lines) {
            if (line.startsWith("ONLINE_V4:") || line.startsWith("ONLINE_V6:")) {
                online.add(line.substringAfter(":").trim())
            }
        }
        return online
    }

    /**
     * Extracts connected local subnet prefixes for given active interfaces.
     * Uses ConnectivityManager LinkProperties routes, with shell fallback.
     */
    fun getConnectedSubnetPrefixes(context: Context, ifaces: Collection<String>): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        val safeIfaces = ifaces.filter { RouteRuleBuilder.isValidInterfaceName(it) }.toSet()
        if (safeIfaces.isEmpty()) return emptyMap()

        // 1. Android ConnectivityManager LinkProperties
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null) {
                    @Suppress("DEPRECATION")
                    val networks = cm.allNetworks
                    for (network in networks) {
                        val lp = cm.getLinkProperties(network) ?: continue
                        val iface = lp.interfaceName ?: continue
                        if (safeIfaces.contains(iface)) {
                            for (route in lp.routes) {
                                if (!route.isDefaultRoute) {
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
                val lines = executeShellWithOutput(
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
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
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
            val raw = executeShellWithOutput("/system/bin/dumpsys connectivity 2>/dev/null", timeoutSeconds = 6)
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
     *
     * The script no longer replays a static snapshot: `service.d` runs during `late_start`, before
     * Wi-Fi/cellular routing tables exist, so it first waits for those tables to appear, applies the
     * rules, then keeps re-applying whenever a channel's readiness changes. That keeps recovery
     * working even when the OEM blocks background broadcasts, and leaves a trace in
     * [RouteRuleBuilder.BOOT_LOG_PATH].
     */
    fun updateBootRestoreScript(applyScript: String, interfaces: Collection<String>) {
        try {
            val scriptContent = RouteRuleBuilder.buildBootRestoreScript(applyScript, interfaces)

            val cmd = "mkdir -p /data/adb/service.d /data/adb/multiroute && " +
                    "cat << 'MCROUTE_EOF' > /data/adb/service.d/00-multiroute-restore.sh\n" +
                    "$scriptContent\n" +
                    "MCROUTE_EOF\n" +
                    "chmod 755 /data/adb/service.d/00-multiroute-restore.sh"

            if (!executeCommand(cmd)) {
                Log.w(TAG, "Boot restore script deployment returned a non-zero exit code")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to update boot restore script: ${t.message}")
        }
    }

    /**
     * Enumerates OEM clone-space / work-profile installs so the UI can list and configure them
     * separately from the primary-user installs of the same packages.
     */
    fun listSecondaryUserInstalls(): List<RouteRuleBuilder.SecondaryUserInstalls> {
        val lines = executeShellWithOutput(RouteRuleBuilder.buildSecondaryUserListingQuery(), timeoutSeconds = 6)
        if (lines.isEmpty()) {
            Log.w(TAG, "Secondary-user listing returned nothing; clone apps will not be listed")
            return emptyList()
        }
        return RouteRuleBuilder.parseSecondaryUserListing(lines)
    }

    /**
     * UID behind a rule key (`pkg` for the primary user, `pkg@<userId>` for a clone space or work profile).
     *
     * The primary user goes through PackageManager; the other users come from the root package listing,
     * which the caller can fetch once and pass in when it has several keys to resolve. Returns -1 when the
     * package is not installed for that user, so a configuration imported from another device simply does
     * not route apps that are missing here.
     */
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
     * Writes [content] to [path] with root rights.
     *
     * The content is written to the app's own cache first and then copied, so JSON that came from a file we
     * did not author never has to survive shell quoting.
     */
    fun writeTextAsRoot(context: Context, path: String, content: String): Boolean {
        if (path.isBlank()) return false
        return try {
            val staging = java.io.File(context.cacheDir, "config-transfer.json")
            staging.writeText(content)
            val dir = path.substringBeforeLast('/', "")
            val cmd = if (dir.isEmpty()) {
                "cp ${staging.absolutePath} $path && chmod 644 $path"
            } else {
                "sh -c 'mkdir -p $dir && cp ${staging.absolutePath} $path && chmod 644 $path'"
            }
            executeCommand(cmd)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write $path: ${t.message}")
            false
        }
    }

    /** Reads [path] with root rights, or null when it is missing or unreadable. */
    fun readTextAsRoot(path: String): String? {
        if (path.isBlank()) return null
        val lines = executeShellWithOutput("cat $path", timeoutSeconds = 8)
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    /**
     * Dynamically synchronizes routing rules for any interface (wlan0, wlan1, rmnet_data*, eth0, etc.)
     * to the kernel policy routing table and system settings.
     *
     * Applying rules means "delete every rule at our preferences, then add the current set", so two
     * overlapping syncs (module boot wake-up, network callback, UI action) can interleave and leave a
     * partial rule set - observed on device as one rule right after a soft reboot and the second one a
     * minute later. A process-wide mutex serializes them.
     */
    suspend fun syncAllRouteRules(context: Context): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock { syncAllRouteRulesInternal(context) }
    }

    /**
     * Evaluates the scenario profiles against the current links and folds the winner into [base].
     *
     * The common case costs nothing: with no profiles stored the base map is returned untouched and no
     * root command is run. SSIDs come from the channel list the platform already gives us; the root dump
     * is only consulted when an SSID trigger needs them and the platform redacted them.
     */
    private suspend fun resolveScenarioProfile(
        context: Context,
        base: Map<Int, String>
    ): com.multiroute.model.ScenarioResolution {
        val profiles = com.multiroute.data.ScenarioStore.load(context)
        if (profiles.isEmpty()) {
            return com.multiroute.model.ScenarioResolution(effectiveRules = base)
        }

        val channels = runCatching { NetworkUtils.getActiveChannels(context) }.getOrDefault(emptyList())
        var observation = com.multiroute.model.ScenarioObservation.fromChannels(channels)

        val needsSsids = profiles.any {
            it.enabled && it.trigger is com.multiroute.model.ScenarioTrigger.SsidMatch
        }
        if (needsSsids && observation.wifiSsids.isEmpty()) {
            val fromRoot = runCatching { NetworkUtils.getConnectedWifiSsids().values.toList() }
                .getOrDefault(emptyList())
            observation = observation.copy(wifiSsids = fromRoot.filter { it.isNotBlank() }.distinct())
        }

        val knownChannels = channels.map { it.interfaceName }.toSet()
        // Clone-space / work-profile overrides need the root package listing; fetch it once, only if a
        // profile that is actually applied contains one.
        var secondaryInstalls: List<RouteRuleBuilder.SecondaryUserInstalls>? = null
        val resolution = ScenarioEngine.resolve(
            base = base,
            profiles = profiles,
            observation = observation,
            manualId = com.multiroute.data.ScenarioStore.getManualId(context),
            overridesByUid = { profile ->
                profile.overrides.mapNotNull { (ruleKey, channelId) ->
                    val (pkg, userId) = RouteRuleBuilder.parseRuleKey(ruleKey)
                    val uid = if (userId <= 0) {
                        runCatching { context.packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
                    } else {
                        if (secondaryInstalls == null) {
                            secondaryInstalls = runCatching { listSecondaryUserInstalls() }
                                .getOrDefault(emptyList())
                        }
                        secondaryInstalls?.firstOrNull { it.userId == userId }?.packageUids?.get(pkg) ?: -1
                    }
                    if (uid > 0) uid to channelId else null
                }.toMap()
            },
            // When nothing is connected yet (for example while the device is still booting) an empty list
            // must not invalidate every override - the offline check below the sync decides what is usable.
            hasChannel = { knownChannels.isEmpty() || it in knownChannels }
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

    private suspend fun syncAllRouteRulesInternal(context: Context): Boolean {
        // Keep the persistent keep-alive flag aligned with the stored preference on every sync, so the
        // module can honour it from the very first moment of a boot (its other two sources - the
        // transient `sys.*` property and RemotePreferences - are unavailable at that point).
        val keepAliveValue = if (RouteConfigProvider.isKeepSlaveWifiScreenOff(context)) "1" else "0"
        executeCommand("setprop persist.multiroute.keep_slave_wifi $keepAliveValue")

        // `uid_<n>` is the contract the injected module reads, so routing is driven purely by UID.
        // That keeps primary installs, OEM clone spaces (Xiaomi XSpace = user 999) and work profiles
        // uniformly routable - and lets each of them be configured to a different channel.
        val baseUidRules = RouteConfigProvider.getUidRules(context)

        // Scenario profiles are overlays on those assignments: whichever profile matches the current links
        // (or the one the user pinned by hand) may re-route some apps, or take them out of routing
        // entirely, without touching the base configuration. Everything below only ever sees the result,
        // so boot recovery, the rule cache, offline skipping, the cellular bookkeeping and the DNS
        // redirect keep working exactly as before.
        val scenario = resolveScenarioProfile(context, baseUidRules)
        val uidRules = scenario.effectiveRules

        // Publish how many scenario plans exist. The module lives in system_server and is always awake,
        // so it is the component that can notice a link change while this app has been killed; it reads
        // this marker, wakes us with the restore broadcast, and the plan is re-evaluated here. Written on
        // every sync, so it also disappears again when the last plan is deleted.
        val scenarioPlanCount = com.multiroute.data.ScenarioStore.load(context).size
        executeCommand(
            if (scenarioPlanCount > 0) {
                "sh -c 'echo $scenarioPlanCount > ${RouteRuleBuilder.SCENARIO_PLAN_MARKER}'"
            } else {
                "sh -c 'rm -f ${RouteRuleBuilder.SCENARIO_PLAN_MARKER}'"
            }
        )

        val channelToUidsMap = mutableMapOf<String, MutableList<Int>>()
        val cellularUids = mutableSetOf<Int>()

        for ((uid, channelId) in uidRules) {
            if (channelId == CHANNEL_DEFAULT) continue
            if (!RouteRuleBuilder.isValidUid(uid)) continue
            if (!RouteRuleBuilder.isValidInterfaceName(channelId)) continue

            channelToUidsMap.getOrPut(channelId) { mutableListOf() }.add(uid)
            if (channelId.startsWith("rmnet") || channelId.startsWith("ccmni") || channelId.contains("mobile")) {
                cellularUids.add(uid)
            }
        }

        // 1. Keep the platform's own "mobile data preferred" list in step with the assignments: add the
        // UIDs that are missing and take back the ones this app added earlier, so unassigning an app no
        // longer leaves a permanent entry behind. Only UIDs recorded as ours are ever removed; entries
        // owned by the system or another tool stay untouched.
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

        // 2. Validate interface route tables
        val candidateChannels = channelToUidsMap.keys
        if (candidateChannels.isEmpty()) {
            // User explicitly cleared all routing rules: clean up kernel rules, boot script and cache,
            // and take this app's UIDs back out of the platform's preferred-mobile-data list.
            val ours = RouteConfigProvider.getMergedCellularUids(context)
            if (ours.isNotEmpty()) {
                val current = runCatching {
                    Settings.Secure.getString(context.contentResolver, "mobile_data_preferred_uids")
                }.getOrDefault("")
                syncMobileDataPreferredUids(context, RouteRuleBuilder.parseUidList(current) - ours)
                RouteConfigProvider.setMergedCellularUids(context, emptySet())
                Log.i(TAG, "Removed ${ours.size} UID(s) this app had added to mobile_data_preferred_uids")
            }
            val cleanupScript = RouteRuleBuilder.buildFullSyncScript(emptyMap())
            executeCommand(cleanupScript, timeoutSeconds = RULE_SCRIPT_TIMEOUT_SECONDS)
            executeCommand("rm -f /data/adb/service.d/00-multiroute-restore.sh")
            executeCommand("rm -f ${RouteRuleBuilder.RULE_CACHE_PATH}")
            return true
        }

        // A rule whose table has no default route does not blackhole traffic - the kernel falls through
        // to the next rule and the app silently keeps using the default network. Keeping such stale
        // rules around is therefore pure confusion (observed on device while a Wi-Fi link was
        // re-registering: rules pointed at table 1056 after its default route was gone), so the kernel
        // is always rewritten from the channels that are usable right now.
        val onlineChannels = getInterfacesWithDefaultRoute(candidateChannels)
        if (onlineChannels.isEmpty()) {
            Log.w(
                TAG,
                "None of $candidateChannels has a default route right now; clearing their kernel rules " +
                        "(traffic falls back to the default network in that state anyway)"
            )
        }

        val activeChannelMap = channelToUidsMap.filterKeys { iface ->
            val isOnline = onlineChannels.contains(iface)
            if (!isOnline) {
                Log.w(TAG, "Interface $iface has no default route in kernel table; skipping to prevent traffic blackholing")
            }
            isOnline
        }

        // 3. Collect connected local subnet prefixes for active channels (LAN bypass)
        val connectedPrefixes = getConnectedSubnetPrefixes(context, activeChannelMap.keys)

        // 3b. Resolvers of the active channels, for the DNS redirect. Android picks the resolver from the
        // default network, so without this an assigned app can query an address that is only reachable over
        // a different link (or keep querying a VPN tunnel). On by default; can be switched off.
        val channelResolvers = if (RouteConfigProvider.isDnsFollowsChannel(context)) {
            getChannelResolvers(context, activeChannelMap.keys)
        } else {
            emptyMap()
        }
        Log.i(TAG, "DNS redirect: channelResolvers=$channelResolvers for ${activeChannelMap.keys}")

        // 4. Sync Linux kernel policy routing dynamically for active interfaces (dual-stack + LAN bypass)
        val script = RouteRuleBuilder.buildFullSyncScript(
            activeChannelMap,
            connectedPrefixes,
            channelResolvers = channelResolvers
        )
        // The script spawns one `ip` process per rule (20-30 of them). 5s is enough on an idle device
        // but not on a busy one: right after a soft reboot the app reported "Command timed out after 5s"
        // and the sync returned false, leaving only part of the rules applied - which is exactly the
        // "half rule set" that was observed on device.
        val success = executeCommand(script, timeoutSeconds = RULE_SCRIPT_TIMEOUT_SECONDS)

        // 5. Deploy / update Magisk/KernelSU boot-time service.d restore script
        if (success) {
            if (activeChannelMap.isEmpty()) {
                // Every configured channel is unusable right now. The kernel rules were just cleared
                // (they would have been inert anyway), but the boot script is kept untouched: it is the
                // only place that still carries the user's intent and it re-applies everything on the
                // next boot. The hook cache is emptied so the app-visible state matches the kernel.
                updateRuleCache(emptyMap())
                Log.w(TAG, "All configured channels are offline; kernel rules cleared, boot script preserved")
            } else {
                // The boot script deliberately carries no DNS resolvers: they can change between boots
                // (different network), and a stale one would break resolution. It flushes the NAT chain
                // instead, and the app re-applies the redirect with fresh resolvers once it runs.
                val bootScript = RouteRuleBuilder.buildFullSyncScript(activeChannelMap, connectedPrefixes)
                updateBootRestoreScript(bootScript, activeChannelMap.keys)
                updateRuleCache(activeChannelMap)
            }
        }

        return success
    }

    /**
     * Writes the platform's `mobile_data_preferred_uids` setting when it differs from [desired].
     *
     * The direct Settings.Secure call needs WRITE_SECURE_SETTINGS, which a normal app only holds when it
     * has been granted explicitly, so the root shell is used as a fallback.
     */
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
            executeCommand("settings put secure mobile_data_preferred_uids \"$text\"")
        }
    }

    /**
     * Publishes the uid→interface map to [RouteRuleBuilder.RULE_CACHE_PATH].
     *
     * At the start of every boot, and until this app has been started, LSPosed's RemotePreferences
     * return an empty map, so the ConnectivityService hooks cannot see any rule. This cache gives them
     * a known-good fallback, and it is refreshed on every successful sync.
     */
    private fun updateRuleCache(channelToUidsMap: Map<String, List<Int>>) {
        val uidToIface = mutableMapOf<Int, String>()
        for ((iface, uids) in channelToUidsMap) {
            if (!RouteRuleBuilder.isValidInterfaceName(iface)) continue
            for (uid in uids) uidToIface[uid] = iface
        }
        val cacheText = RouteRuleBuilder.buildRuleCacheText(uidToIface)
        if (cacheText.isEmpty()) {
            executeCommand("rm -f ${RouteRuleBuilder.RULE_CACHE_PATH}")
            return
        }
        try {
            val cmd = "cat << 'MCROUTE_CACHE_EOF' > ${RouteRuleBuilder.RULE_CACHE_PATH}\n" +
                    "$cacheText\n" +
                    "MCROUTE_CACHE_EOF\n" +
                    "chmod 644 ${RouteRuleBuilder.RULE_CACHE_PATH}"
            if (!executeCommand(cmd)) {
                Log.w(TAG, "Rule cache update returned a non-zero exit code")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to update rule cache: ${t.message}")
        }
    }

    @androidx.annotation.Keep
    @JvmStatic
    fun isModuleActiveInLSPosed(): Boolean {
        return false
    }

    /**
     * Human-readable label for a [ModuleStatus], shared by the diagnostics log and the settings UI so
     * both stay consistent.
     *
     * Note: [isModuleActiveInLSPosed] above is only a legacy activation probe - it is hooked to return
     * true whenever the module is injected into the app's own process, so it must never be used as the
     * primary activation signal (it cannot see system_server or the hook state).
     */
    fun moduleStatusLabel(context: Context, status: ModuleStatus): String = when (status) {
        ModuleStatus.ACTIVE -> context.getString(com.multiroute.R.string.module_status_active)
        ModuleStatus.PARTIAL -> context.getString(com.multiroute.R.string.module_status_partial)
        ModuleStatus.OUTDATED -> context.getString(com.multiroute.R.string.module_status_outdated)
        ModuleStatus.LEGACY_UNVERIFIED -> context.getString(com.multiroute.R.string.module_status_legacy)
        ModuleStatus.STALE -> context.getString(com.multiroute.R.string.module_status_stale)
        ModuleStatus.NOT_ACTIVE -> context.getString(com.multiroute.R.string.module_status_not_active)
    }

    /**
     * Resolves the precise LSPosed module state, cheapest evidence first:
     *
     *  1. `Settings.Global[multiroute_module_state]` - the structured beacon the module publishes from
     *     system_server. Readable by an ordinary app, so the common case costs no `su` call at all.
     *  2. The root-readable marker file (same beacon on new builds, legacy `pid:ts` on old ones).
     *  3. The legacy `sys.multiroute.active` property flag.
     *
     * The beacon carries the version code *compiled into the loaded hook code*, so a mismatch with the
     * installed APK is reported as [ModuleStatus.OUTDATED] instead of a misleading "activated"; and it
     * carries the installed hook counts, so "loaded but hooks missing" is distinguishable from "ready".
     */
    fun getModuleState(context: Context): ModuleState {
        val installedVersionCode = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)

        // 1) Primary: structured beacon, no root required.
        val globalRaw = runCatching {
            Settings.Global.getString(context.contentResolver, ModuleStateParser.STATE_KEY)
        }.getOrNull()
        var beacon = ModuleStateParser.parseBeacon(globalRaw)
        var legacyPid = ModuleStateParser.parseLegacyMarkerPid(globalRaw)

        // 2) System property channel: no root, no ContentResolver round trip.
        if (beacon == null && legacyPid == null) {
            val propRaw = readStateProperty()
            beacon = ModuleStateParser.parseBeacon(propRaw)
            legacyPid = ModuleStateParser.parseLegacyMarkerPid(propRaw)
        }

        // 3) Fallback: marker file. Only read when the cheap channels are empty (older module build,
        //    or the module could not publish), so steady-state diagnostics stay su-free.
        if (beacon == null && legacyPid == null) {
            val markerRaw = readMarkerRaw()
            beacon = ModuleStateParser.parseBeacon(markerRaw)
            legacyPid = ModuleStateParser.parseLegacyMarkerPid(markerRaw)
        }

        // 4) Last resort: the legacy flag proves something ran, but says nothing about the hooks.
        val legacyFlag = if (beacon == null && legacyPid == null) readLegacyActiveFlag() else false
        val legacyMarkerPid = legacyPid ?: if (legacyFlag) UNKNOWN_PID else null

        val pidToVerify = beacon?.systemServerPid ?: legacyPid
        val ownerVerified = pidToVerify?.let { verifyIsSystemServer(it) }

        return ModuleStateEvaluator.evaluate(
            ModuleStateEvaluator.Inputs(
                beacon = beacon,
                legacyMarkerPid = legacyMarkerPid,
                installedVersionCode = installedVersionCode,
                installedBuildId = BuildConfig.HOOK_BUILD_ID,
                nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
                ownerIsSystemServer = ownerVerified
            )
        )
    }

    /** Reads the compact property beacon without root (a plain `getprop`, ~10 ms). */
    private fun readStateProperty(): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", ModuleStateParser.STATE_PROPERTY))
        val line = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.errorStream.bufferedReader().use { it.readText() }
        p.waitFor()
        line.ifEmpty { null }
    }.getOrNull()

    /** Reads the marker file unprivileged first, then through root; null when unreadable. */
    private fun readMarkerRaw(): String? {
        runCatching {
            val f = java.io.File(ModuleStateParser.MARKER_PATH)
            if (f.canRead()) return f.readText().trim()
        }
        return executeShellWithOutput("cat ${ModuleStateParser.MARKER_PATH}", timeoutSeconds = 2)
            .firstOrNull()?.trim()
    }

    /**
     * Verifies that [pid] really is system_server.
     * Returns null when it cannot be determined (for example without root), so an unknown result is
     * never turned into a false "stale" verdict.
     */
    private fun verifyIsSystemServer(pid: Int): Boolean? {
        if (pid <= 0) return null

        runCatching {
            val cmdline = java.io.File("/proc/$pid/cmdline").readText().replace('\u0000', ' ')
            return cmdline.contains("system_server")
        }

        val sentinel = ModuleStateParser.noProcessSentinel()
        val lines = executeShellWithOutput(
            "if [ -d /proc/$pid ]; then cat /proc/$pid/cmdline; else echo $sentinel; fi",
            timeoutSeconds = 2
        )
        if (lines.isEmpty()) return null
        if (lines.any { it.contains(sentinel) }) return false
        return lines.any { it.contains("system_server") }
    }

    /** Legacy `sys.multiroute.active` flag; proves the module ran, nothing more. */
    private fun readLegacyActiveFlag(): Boolean {
        val direct = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "sys.multiroute.active"))
            val line = p.inputStream.bufferedReader().use { it.readLine() }?.trim()
            p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor()
            line == "1"
        }.getOrDefault(false)
        if (direct) return true
        return executeShellWithOutput("getprop sys.multiroute.active", timeoutSeconds = 2)
            .firstOrNull()?.trim() == "1"
    }

    /**
     * Gathers rich live diagnostic status of the MultiRoute framework, Linux kernel, and Android settings.
     */
    suspend fun getDiagnosticInfo(context: Context): DiagnosticInfo = withContext(Dispatchers.IO) {
        var isRoot = false
        var suVer = ""
        val kernelRules = mutableListOf<String>()
        val activeTables = mutableListOf<String>()

        try {
            val lines = executeShellWithOutput("id; su -v; ip rule show pref 14500; ip -6 rule show pref 14500", timeoutSeconds = 4)
            if (lines.isNotEmpty() && lines[0].contains("uid=0")) {
                isRoot = true
            }
            if (lines.size > 1) {
                suVer = lines[1].trim()
            }
            for (i in 2 until lines.size) {
                val line = lines[i].trim()
                if (line.isNotEmpty() && line.contains("14500")) {
                    kernelRules.add(line)
                    val table = line.substringAfter("lookup ").substringBefore(";").trim()
                    if (table.isNotEmpty() && !activeTables.contains(table)) {
                        activeTables.add(table)
                    }
                }
            }
        } catch (_: Exception) {}

        val moduleState = getModuleState(context)
        val mobileDataAlwaysOn = isMobileDataAlwaysOn(context)
        val mobileDataPreferredUids = runCatching {
            Settings.Secure.getString(context.contentResolver, "mobile_data_preferred_uids") ?: ""
        }.getOrDefault("")

        val wifiSsids = NetworkUtils.getConnectedWifiSsids()
        val keepSlaveWifi = isKeepSlaveWifiScreenOff(context)

        DiagnosticInfo(
            isRootGranted = isRoot,
            suVersion = suVer,
            isModuleActive = moduleState.isLoadedInSystemServer,
            moduleStatus = moduleState.status,
            moduleStatusDetail = moduleState.detailLabel(context),
            kernelRulesCount = kernelRules.size,
            kernelRules = kernelRules,
            mobileDataAlwaysOn = mobileDataAlwaysOn,
            mobileDataPreferredUids = mobileDataPreferredUids,
            isKeepSlaveWifiScreenOff = keepSlaveWifi,
            activeTables = activeTables,
            wifiSsids = wifiSsids,
            configuredUidRules = RouteConfigProvider.getUidRules(context),
            kernelUidRules = RouteRuleBuilder.parseKernelUidRules(kernelRules),
            bootRestoreLog = readBootRestoreLog()
        )
    }

    /**
     * Tail of the boot-recovery log written by the generated `service.d` script. It is the only record of
     * what happened before the app was allowed to run (on a locked device it is the sole restore path),
     * and until now it could only be read over adb.
     */
    private fun readBootRestoreLog(maxLines: Int = 12): List<String> =
        try {
            // Absolute path on purpose: the su environment may resolve `tail` to busybox.
            executeShellWithOutput("/system/bin/tail -n $maxLines ${RouteRuleBuilder.BOOT_LOG_PATH} 2>/dev/null", 4)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }

    /**
     * Compiles a comprehensive diagnostic log snapshot for the user to view or copy.
     */
    /**
     * Human-readable reason for the active scenario plan, using the same strings the routing screen uses.
     * Shared with the diagnostic snapshot so a report says *why* a plan applied, not just that one did.
     */
    fun scenarioReasonText(
        context: Context,
        resolution: com.multiroute.model.ScenarioResolution
    ): String {
        if (resolution.activeId == null) return ""
        if (resolution.appliedManually) {
            return context.getString(com.multiroute.R.string.scenario_reason_manual)
        }
        return when (val trigger = resolution.matchedTrigger) {
            is com.multiroute.model.ScenarioTrigger.SsidMatch -> context.getString(
                com.multiroute.R.string.scenario_reason_ssid,
                resolution.matchedSsid ?: trigger.ssids.joinToString(" / ")
            )
            is com.multiroute.model.ScenarioTrigger.WifiLinkCount ->
                context.getString(com.multiroute.R.string.scenario_reason_links2)
            is com.multiroute.model.ScenarioTrigger.CellularOnly ->
                context.getString(com.multiroute.R.string.scenario_reason_cellular)
            is com.multiroute.model.ScenarioTrigger.Always ->
                context.getString(com.multiroute.R.string.scenario_reason_always)
            else -> context.getString(com.multiroute.R.string.scenario_reason_manual)
        }
    }

    suspend fun getDiagnosticLogs(context: Context): String = withContext(Dispatchers.IO) {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = timeFormat.format(Date())

        val sb = StringBuilder()
        sb.appendLine(context.getString(com.multiroute.R.string.diag_title))
        sb.appendLine(context.getString(com.multiroute.R.string.diag_time, now))
        sb.appendLine()

        // 1. 基础系统与权限
        val diag = getDiagnosticInfo(context)
        sb.appendLine(context.getString(com.multiroute.R.string.diag_sec1))
        sb.appendLine(context.getString(com.multiroute.R.string.diag_root_state, if (diag.isRootGranted) context.getString(com.multiroute.R.string.diag_root_granted, diag.suVersion) else context.getString(com.multiroute.R.string.diag_root_missing)))
        sb.appendLine(context.getString(com.multiroute.R.string.diag_module, moduleStatusLabel(context, diag.moduleStatus)))
        if (diag.moduleStatusDetail.isNotEmpty()) {
            sb.appendLine("  └ ${diag.moduleStatusDetail}")
        }
        sb.appendLine(context.getString(com.multiroute.R.string.diag_cellular_always_on, if (diag.mobileDataAlwaysOn) context.getString(com.multiroute.R.string.diag_on) else context.getString(com.multiroute.R.string.diag_off)))
        sb.appendLine(context.getString(com.multiroute.R.string.diag_keepalive, if (diag.isKeepSlaveWifiScreenOff) context.getString(com.multiroute.R.string.diag_keepalive_on) else context.getString(com.multiroute.R.string.diag_keepalive_off)))
        // Which scenario plan is in effect, and why - the two questions a report about "a wrong route"
        // always starts with. Only printed when plans exist, so the snapshot stays short for everyone else.
        if (com.multiroute.data.ScenarioStore.load(context).isNotEmpty()) {
            val resolution = resolveScenarioProfile(context, RouteConfigProvider.getUidRules(context))
            sb.appendLine(
                context.getString(
                    com.multiroute.R.string.diag_scenario,
                    resolution.activeName
                        ?: context.getString(com.multiroute.R.string.diag_scenario_none),
                    scenarioReasonText(context, resolution)
                )
            )
        }
        sb.appendLine(context.getString(com.multiroute.R.string.diag_cellular_uids, diag.mobileDataPreferredUids.ifEmpty { context.getString(com.multiroute.R.string.diag_empty) }))
        sb.appendLine()

        // 2. 规则生效对照：配置的 uid→通道 与内核里的 pref 14500 规则逐条比对
        sb.appendLine(context.getString(com.multiroute.R.string.diag_sec2))
        if (diag.configuredUidRules.isEmpty()) {
            sb.appendLine(context.getString(com.multiroute.R.string.diag_no_rules))
        } else {
            diag.configuredUidRules.entries.sortedBy { it.key }.forEach { (uid, channel) ->
                val actual = diag.kernelUidRules[uid]
                val verdict = when {
                    actual == null -> context.getString(com.multiroute.R.string.diag_rule_not_effective)
                    actual == channel -> context.getString(com.multiroute.R.string.diag_rule_effective)
                    else -> context.getString(com.multiroute.R.string.diag_rule_effective_table, actual)
                }
                sb.appendLine("• uid $uid -> $channel: $verdict")
            }
        }
        sb.appendLine()

        // 3. 开机恢复日志（service.d 生成，锁屏状态下它是唯一的恢复路径）
        sb.appendLine(context.getString(com.multiroute.R.string.diag_sec3, RouteRuleBuilder.BOOT_LOG_PATH))
        if (diag.bootRestoreLog.isEmpty()) {
            sb.appendLine(context.getString(com.multiroute.R.string.diag_no_boot_log))
        } else {
            diag.bootRestoreLog.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()

        // 2. Wi-Fi 连接状态
        sb.appendLine(context.getString(com.multiroute.R.string.diag_sec_wifi))
        if (diag.wifiSsids.isEmpty()) {
            sb.appendLine(context.getString(com.multiroute.R.string.diag_no_wifi))
        } else {
            diag.wifiSsids.forEach { (iface, ssid) ->
                sb.appendLine("• $iface -> $ssid")
            }
        }
        sb.appendLine()

        // 3. 内核策略路由规则 (pref 14500 & pref 14400)
        sb.appendLine(context.getString(com.multiroute.R.string.diag_sec_kernel))
        if (diag.kernelRules.isEmpty()) {
            sb.appendLine(context.getString(com.multiroute.R.string.diag_no_kernel_rules))
        } else {
            diag.kernelRules.forEach { rule ->
                sb.appendLine("• $rule")
            }
        }
        sb.appendLine()

        // 4. 当前活动的网络接口与 IP
        sb.appendLine(context.getString(com.multiroute.R.string.diag_sec_ifaces))
        try {
            val lines = executeShellWithOutput("ip -br addr; echo '---'; ip rule show | head -n 25", timeoutSeconds = 3)
            lines.forEach { sb.appendLine(it) }
        } catch (e: Exception) {
            sb.appendLine(context.getString(com.multiroute.R.string.diag_ifaces_failed, e.message ?: ""))
        }
        sb.appendLine()

        // 5. 最近 Logcat 相关日志
        sb.appendLine(context.getString(com.multiroute.R.string.diag_sec_logcat))
        try {
            val lines = executeShellWithOutput("logcat -d -t 60 | grep -E 'MultiRoute|ConnectivityService|netd' | tail -n 25", timeoutSeconds = 3)
            if (lines.isEmpty()) {
                sb.appendLine(context.getString(com.multiroute.R.string.diag_no_logcat))
            } else {
                lines.forEach { sb.appendLine(it) }
            }
        } catch (_: Exception) {
            sb.appendLine(context.getString(com.multiroute.R.string.diag_logcat_timeout))
        }

        sb.toString()
    }

    /**
     * Executes a command as root with safety timeout and drained buffer to avoid deadlocks.
     */
    fun executeCommand(cmd: String, timeoutSeconds: Long = 5): Boolean {
        return try {
            val pb = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true)
            val process = pb.start()
            val drainThread = Thread {
                runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            }
            drainThread.start()
            val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.e(TAG, "Command timed out after ${timeoutSeconds}s: $cmd")
                false
            } else {
                drainThread.join(500)
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exec: $cmd", e)
            false
        }
    }

    /**
     * Executes a shell command as root and reads all output lines safely with timeout.
     */
    fun executeShellWithOutput(cmd: String, timeoutSeconds: Long = 5): List<String> {
        return try {
            val pb = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true)
            val process = pb.start()
            val lines = mutableListOf<String>()
            val readThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { seq ->
                        lines.addAll(seq.toList())
                    }
                }
            }
            readThread.start()
            val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.e(TAG, "Command with output timed out after ${timeoutSeconds}s: $cmd")
                emptyList()
            } else {
                readThread.join(500)
                lines
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exec with output: $cmd", e)
            emptyList()
        }
    }
}

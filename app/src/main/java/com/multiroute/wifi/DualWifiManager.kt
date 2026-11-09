package com.multiroute.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import com.multiroute.util.HotspotUpstreamPolicy
import com.multiroute.util.NetworkUtils
import com.multiroute.util.SuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Global coordinator and public facade for Dual Wi-Fi (Multi-Internet / STA+STA Concurrency).
 *
 * Responsibilities:
 * - Maintains reactive UI state ([status])
 * - Manages secondary Wi-Fi connection life-cycle and concurrency serialization ([SecondaryConnectionCoordinator])
 * - Delegates hardware and ROM probing to [DeviceCapabilityDetector]
 * - Delegates band calculation to [DualWifiBandHelper]
 * - Delegates hotspot detection to [HotspotDetector]
 * - Delegates scan/credential parsing to [DualWifiScanParser]
 * - Dispatches platform-specific commands through [DualWifiDriver] implementations
 */
object DualWifiManager {
    private const val TAG = "MultiRoute-DualWifi"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val hotspotMutex = Mutex()
    private val secondaryConnections = SecondaryConnectionCoordinator()
    private val hotspotRequestId = AtomicLong()

    internal suspend fun <T> withHotspotPreferenceLock(block: suspend () -> T): T = hotspotMutex.withLock { block() }

    private val _status = MutableStateFlow(DualWifiStatus())
    val status: StateFlow<DualWifiStatus> = _status.asStateFlow()

    @Volatile
    private var isHotspotReceiverRegistered = false

    internal fun secondaryServiceGeneration(): Long? = secondaryConnections.monitoringTicket()?.generation

    internal fun isSecondaryServiceCurrent(generation: Long): Boolean =
        secondaryServiceGeneration() == generation

    // Forwarding delegates for device probing & compatibility
    fun detectRomType(
        manufacturer: String? = null,
        brand: String? = null,
        model: String? = null,
        androidRelease: String? = null,
        apiLevel: Int? = null,
        socModelOverride: String? = null,
        miuiPropOverride: String? = null,
        oplusPropOverride: String? = null,
        vivoPropOverride: String? = null
    ): DeviceRomInfo = DeviceCapabilityDetector.detectRomType(
        manufacturer, brand, model, androidRelease, apiLevel, socModelOverride, miuiPropOverride, oplusPropOverride, vivoPropOverride
    )

    fun getEffectiveScheme(configured: DualWifiScheme, rom: DeviceRomInfo): DualWifiScheme =
        DeviceCapabilityDetector.getEffectiveScheme(configured, rom)

    @StringRes
    fun detectWifiChipsetRes(lsmodOutput: String? = null): Int =
        DeviceCapabilityDetector.detectWifiChipsetRes(lsmodOutput)

    fun detectWifiChipset(context: Context, lsmodOutput: String? = null): String =
        DeviceCapabilityDetector.detectWifiChipset(context, lsmodOutput)

    // Forwarding delegates for band and collision calculation
    fun isSameBand(f1: Int, f2: Int): Boolean = DualWifiBandHelper.isSameBand(f1, f2)
    fun matchesConnected(
        candidateSsid: String,
        candidateBssid: String,
        candidateFreq: Int,
        connectedSsid: String?,
        connectedBssid: String?,
        connectedFreq: Int
    ): Boolean = DualWifiBandHelper.matchesConnected(candidateSsid, candidateBssid, candidateFreq, connectedSsid, connectedBssid, connectedFreq)

    fun isPrimaryCollision(
        candidateSsid: String,
        candidateBssid: String?,
        primarySsid: String?,
        primaryBssid: String?
    ): Boolean = DualWifiBandHelper.isPrimaryCollision(candidateSsid, candidateBssid, primarySsid, primaryBssid)

    fun isBandConflicted(itemFreq: Int, primaryFreq: Int): Boolean = DualWifiBandHelper.isBandConflicted(itemFreq, primaryFreq)
    fun isBandRecommended(itemFreq: Int, primaryFreq: Int): Boolean = DualWifiBandHelper.isBandRecommended(itemFreq, primaryFreq)
    @StringRes
    fun bandLabelRes(freq: Int): Int = DualWifiBandHelper.bandLabelRes(freq)
    fun formatBand(freq: Int, context: Context): String = DualWifiBandHelper.formatBand(freq, context)
    fun resolveSecurityType(capabilities: String?, passphrase: String?): String = DualWifiBandHelper.resolveSecurityType(capabilities, passphrase)
    fun resolvePrimarySecondaryIfaces(ifaces: List<String>, activeDefaultIface: String? = null): Pair<String, String> =
        DualWifiBandHelper.resolvePrimarySecondaryIfaces(ifaces, activeDefaultIface)

    // Forwarding delegates for hotspot detection
    fun parseHotspotFromIwDev(output: List<String>, context: Context? = null): HotspotInfo =
        HotspotDetector.parseHotspotFromIwDev(output, context)
    fun parseHotspotFromDumpsysWifi(lines: List<String>, context: Context? = null): HotspotInfo =
        HotspotDetector.parseHotspotFromDumpsysWifi(lines, context)
    fun isHotspotConflicted(candidateFreq: Int, hotspotFreq: Int): Boolean =
        HotspotDetector.isHotspotConflicted(candidateFreq, hotspotFreq)
    fun checkHotspotState(context: Context): HotspotInfo =
        HotspotDetector.checkHotspotState(context)

    // Forwarding delegates for scan & saved credential parsing
    suspend fun getSavedWifiPasswords(): Map<String, String> = DualWifiScanParser.getSavedWifiPasswords()
    suspend fun getNetworkIdForSsid(ssid: String): Int? = DualWifiScanParser.getNetworkIdForSsid(ssid)
    fun parseSavedNetworkListing(lines: List<String>): Map<String, Int> = DualWifiScanParser.parseSavedNetworkListing(lines)
    fun parseNetworkIdFromList(lines: List<String>, targetSsid: String): Int? = DualWifiScanParser.parseNetworkIdFromList(lines, targetSsid)
    fun parseSavedNetworkIds(lines: List<String>): Map<String, Int> = DualWifiScanParser.parseSavedNetworkIds(lines)

    fun parseScanResultLines(
        lines: List<String>,
        savedPasswords: Map<String, String> = emptyMap(),
        connectedDetails: Map<String, NetworkUtils.WifiConnectionDetails> = emptyMap(),
        primarySsid: String? = null,
        primaryBssid: String? = null,
        primaryFreq: Int = 0,
        secondarySsid: String? = null,
        secondaryBssid: String? = null,
        secondaryFreq: Int = 0,
        isSecConnected: Boolean = false,
        frameworkScanResults: List<android.net.wifi.ScanResult>? = null,
        savedNetworkSsids: Set<String> = savedPasswords.keys
    ): List<WifiScanItem> = DualWifiScanParser.parseScanResultLines(
        lines, savedPasswords, connectedDetails, primarySsid, primaryBssid, primaryFreq,
        secondarySsid, secondaryBssid, secondaryFreq, isSecConnected, frameworkScanResults, savedNetworkSsids
    )

    fun ensureHotspotReceiverRegistered(context: Context) {
        if (isHotspotReceiverRegistered) return
        val appContext = context.applicationContext
        try {
            val filter = android.content.IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    val apState = intent?.getIntExtra("wifi_state", 11) ?: 11
                    scope.launch {
                        refreshConnectionState(appContext)
                        // If hotspot was just disabled, trigger secondary Wi-Fi auto-reconnection
                        // after a short delay allowing kernel nl80211 to restore STA2 mode
                        if (apState == 11 && !_status.value.secondaryConnected &&
                            !RouteConfigProvider.isDualWifiDisabled(appContext) &&
                            RouteConfigProvider.isDualWifiAutoReconnectEnabled(appContext)
                        ) {
                            delay(1200)
                            refreshConnectionState(appContext)
                            if (!_status.value.secondaryConnected && !_status.value.isHotspotEnabled) {
                                autoReconnectSecondary(appContext)
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            isHotspotReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register WIFI_AP_STATE_CHANGED receiver: ${t.message}")
        }
    }

    /**
     * Checks whether the device hardware and framework support Dual Wi-Fi (STA + STA Concurrency).
     */
    suspend fun checkCapability(context: Context): DualWifiCapability = withContext(Dispatchers.IO) {
        val cap = DeviceCapabilityDetector.checkCapability(context)
        _status.update { it.copy(capability = cap) }
        refreshConnectionState(context)
        cap
    }

    /**
     * Activates Dual Wi-Fi according to the effective or target scheme via Root.
     */
    suspend fun activateDualWifi(context: Context, targetScheme: DualWifiScheme? = null): Boolean = withContext(Dispatchers.IO) {
        val confScheme = targetScheme ?: DualWifiScheme.fromId(RouteConfigProvider.getDualWifiScheme(context))
        val rom = detectRomType()
        val effective = getEffectiveScheme(confScheme, rom)
        val driver = DualWifiDriverFactory.getDriver(effective)

        val success = driver.activate(context)
        val capability = checkCapability(context)
        val activated = success && capability.isFullyReady
        if (activated) RouteConfigProvider.setDualWifiDisabled(context, false)
        activated
    }

    /** Turns off the global Multi-Internet mode and stops this app's secondary Wi-Fi lifecycle. */
    suspend fun deactivateDualWifi(context: Context): DualWifiDeactivationResult = withContext(Dispatchers.IO) {
        refreshConnectionState(context)
        val before = _status.value
        val hotspotTarget = HotspotUpstreamPolicy.normalize(before.hotspotUpstreamTarget)
        if (before.isHotspotEnabled &&
            (before.hotspotUpstreamActive.equals("wlan1", ignoreCase = true) || hotspotTarget == "wlan1")) {
            return@withContext DualWifiDeactivationResult.HOTSPOT_USES_SECONDARY
        }

        // Stop background retries first, but keep the saved SSID and password for a later re-enable.
        RouteConfigProvider.setDualWifiDisabled(context, true)
        val disconnectQueued = disconnectSecondaryWifi(
            context,
            clearSavedPreference = false,
            suppressReconnect = true
        )
        context.stopService(android.content.Intent(context, DualWifiService::class.java))

        val scheme = connectionScheme(context)
        val driver = DualWifiDriverFactory.getDriver(scheme)
        val cleanupDone = driver.cleanupDeactivation(context)

        val linkStopped = withTimeoutOrNull(8_000L) {
            while (true) {
                refreshConnectionState(context)
                val current = _status.value
                if (!current.secondaryConnected && !current.isConnecting) return@withTimeoutOrNull true
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false

        val modeCommand = SuHelper.executeCommand(
            "cmd wifi set-multi-internet-mode 0",
            timeoutSeconds = 8
        )

        val modeStopped = withTimeoutOrNull(5_000L) {
            while (true) {
                if (!checkCapability(context).modeActive) return@withTimeoutOrNull true
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false

        val keepAwakeRestored = if (modeStopped) {
            val previous = RouteConfigProvider.getKeepSlaveWifiScreenOffBeforeDualWifi(context)
            if (previous == null) true else {
                val restored = SuHelper.setKeepSlaveWifiScreenOff(context, previous)
                if (restored) RouteConfigProvider.clearKeepSlaveWifiScreenOffBeforeDualWifi(context)
                restored
            }
        } else false

        if (disconnectQueued && cleanupDone && modeCommand && linkStopped && modeStopped && keepAwakeRestored)
            DualWifiDeactivationResult.STOPPED else DualWifiDeactivationResult.FAILED
    }

    /**
     * Clears temporary network selection blocklist and stale requests in Wi-Fi framework.
     */
    suspend fun clearNetworkBlocklist(): Boolean = withContext(Dispatchers.IO) {
        val script = """
            cmd wifi clear-user-disabled-networks
            cmd wifi remove-all-requests
            cmd wifi allow-root-to-get-local-only-cmm enabled
            cmd wifi network-requests-set-user-approved android yes
        """.trimIndent()
        SuHelper.executeCommand(script, timeoutSeconds = 5)
    }

    /**
     * Scans nearby Wi-Fi networks and matches them against saved credentials.
     */
    suspend fun scanWifiNetworks(context: Context): List<WifiScanItem> = withContext(Dispatchers.IO) {
        _status.update { it.copy(isScanning = true) }
        try {
            SuHelper.executeCommand("cmd wifi start-scan 2>/dev/null", timeoutSeconds = 3)
            delay(1200)

            val lines = SuHelper.executeShellWithOutput("cmd wifi list-scan-results 2>/dev/null", timeoutSeconds = 5)
            val savedNetworks = DualWifiScanParser.getSavedNetworkIds()
            val savedPasswords = if (connectionScheme(context) == DualWifiScheme.XIAOMI_SLAVE) DualWifiScanParser.getSavedWifiPasswords() else emptyMap()
            val detailsMap = NetworkUtils.getConnectedWifiDetails()
            val primIface = _status.value.capability.primaryIface?.takeIf { it != "wlan1" } ?: "wlan0"
            val secIface = _status.value.capability.secondaryIface?.takeIf { it != primIface } ?: "wlan1"
            val primDetails = detailsMap[primIface]
            val secDetails = detailsMap[secIface]?.takeIf { it.iface != primIface }

            val primarySsid = _status.value.primarySsid ?: primDetails?.ssid
            val primaryBssid = _status.value.primaryBssid ?: primDetails?.bssid
            val primaryFreq = if (_status.value.primaryFreq > 0) _status.value.primaryFreq else (primDetails?.frequencyMhz ?: 0)

            val isSecConnected = _status.value.secondaryConnected &&
                !_status.value.secondarySsid.isNullOrEmpty() &&
                _status.value.secondarySsid != "<unknown ssid>"
            val secondarySsid = if (isSecConnected) _status.value.secondarySsid else null
            val secondaryBssid = if (isSecConnected) _status.value.secondaryBssid else null
            val secondaryFreq = if (isSecConnected) _status.value.secondaryFreq else 0

            val frameworkScanResults = runCatching {
                (context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.scanResults
            }.getOrNull()

            val items = DualWifiScanParser.parseScanResultLines(
                lines = lines,
                savedPasswords = savedPasswords,
                connectedDetails = detailsMap,
                primarySsid = primarySsid,
                primaryBssid = primaryBssid,
                primaryFreq = primaryFreq,
                secondarySsid = secondarySsid,
                secondaryBssid = secondaryBssid,
                secondaryFreq = secondaryFreq,
                isSecConnected = isSecConnected,
                frameworkScanResults = frameworkScanResults,
                savedNetworkSsids = savedNetworks.keys
            ).toMutableList()

            val sortedItems = DualWifiScanParser.sortScanResults(items, primaryFreq)

            _status.update { it.copy(isScanning = false, scanResults = sortedItems) }
            sortedItems
        } catch (t: Throwable) {
            Log.e(TAG, "Wi-Fi scan failed", t)
            _status.update { it.copy(isScanning = false, errorMessage = context.getString(R.string.wifi_i18n_scan_failed, t.message ?: "")) }
            emptyList()
        }
    }

    /**
     * Refreshes the currently connected primary and secondary Wi-Fi status.
     */
    suspend fun refreshConnectionState(context: Context) = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeDefault = cm?.activeNetwork
        val detailsMap = NetworkUtils.getConnectedWifiDetails()

        val primaryTargetIface = _status.value.capability.primaryIface?.takeIf { it != "wlan1" } ?: "wlan0"
        val secondaryTargetIface = _status.value.capability.secondaryIface?.takeIf { it != primaryTargetIface } ?: "wlan1"

        data class IfaceLink(val iface: String, val ip: String?, val isDefault: Boolean)
        val cmIfaces = mutableMapOf<String, IfaceLink>()
        if (cm != null) {
            for (network in cm.allNetworks) {
                val link = cm.getLinkProperties(network) ?: continue
                val iface = link.interfaceName ?: continue
                if (!iface.startsWith("wlan")) continue
                val ip = link.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
                cmIfaces[iface] = IfaceLink(iface, ip, network == activeDefault)
            }
        }

        // 1. Authoritative Primary Wi-Fi resolution (Targeting wlan0 / ROLE_CLIENT_PRIMARY)
        val primDetail = detailsMap[primaryTargetIface]
            ?: detailsMap.values.firstOrNull { it.isPrimary }
            ?: detailsMap.values.firstOrNull { it.iface == "wlan0" }
        val primCm = cmIfaces[primaryTargetIface]
            ?: cmIfaces.values.firstOrNull { it.isDefault }
            ?: cmIfaces.values.firstOrNull { it.iface == "wlan0" }

        val primIface = primDetail?.iface ?: primCm?.iface ?: primaryTargetIface
        val primSsid = primDetail?.ssid
        val primBssid = primDetail?.bssid
        val primFreq = primDetail?.frequencyMhz ?: 0
        val primRssi = primDetail?.rssi ?: 0
        var primIp = primCm?.ip ?: primDetail?.ip

        // 2. Authoritative Secondary Wi-Fi resolution (Targeting wlan1 / ROLE_CLIENT_SECONDARY)
        // Secondary Wi-Fi is strictly separated from primary interface (primIface)
        val secDetail = detailsMap[secondaryTargetIface]?.takeIf { it.iface != primIface }
            ?: detailsMap.values.firstOrNull { !it.isPrimary && it.iface != primIface && it.iface == "wlan1" }
        val secCm = cmIfaces[secondaryTargetIface]?.takeIf { it.iface != primIface }
            ?: cmIfaces.values.firstOrNull { it.iface != primIface && it.iface == "wlan1" }

        val secIface = secDetail?.iface ?: secCm?.iface ?: secondaryTargetIface
        val candidateSecSsid = secDetail?.ssid?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val candidateSecBssid = secDetail?.bssid
        val secFreq = secDetail?.frequencyMhz ?: 0
        val secRssi = secDetail?.rssi ?: 0
        var secIp = secCm?.ip ?: secDetail?.ip

        // Secondary is connected ONLY IF it has a distinct interface, a non-empty SSID, and does not collide with primary
        val secConnected = secDetail != null &&
            !candidateSecSsid.isNullOrEmpty() &&
            secIface != primIface &&
            (candidateSecSsid != primSsid || candidateSecBssid != primBssid || secIface != primIface)

        val secSsid = if (secConnected) candidateSecSsid else null
        val secBssid = if (secConnected) candidateSecBssid else null
        val secIpFinal = if (secConnected) secIp else null
        val secFreqFinal = if (secConnected) secFreq else 0
        val secRssiFinal = if (secConnected) secRssi else 0

        // Fallback for IP from kernel if not exposed by ConnectivityManager/WifiInfo
        if (primIp == null && !primSsid.isNullOrEmpty()) {
            primIp = SuHelper.executeShellWithOutput("ip -4 -br addr show dev $primIface 2>/dev/null", timeoutSeconds = 2)
                .firstOrNull()?.let { line ->
                    Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""").find(line)?.value
                }
        }
        val finalSecIpResolved = if (secIpFinal == null && secConnected) {
            SuHelper.executeShellWithOutput("ip -4 -br addr show dev $secIface 2>/dev/null", timeoutSeconds = 2)
                .firstOrNull()?.let { line ->
                    Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""").find(line)?.value
                }
        } else {
            secIpFinal
        }

        val hotspotInfo = checkHotspotState(context)

        hotspotMutex.withLock {
            val hotspotTarget = SuHelper.getHotspotUpstream()
            val hotspotActive = if (hotspotInfo.isEnabled) SuHelper.getHotspotActiveUpstream() else "none"
            val hookState = SuHelper.getHotspotHookStatus()
            _status.update {
                val reconciledScanResults = DualWifiScanParser.reconcileConnectedStates(
                    items = it.scanResults,
                    primarySsid = primSsid,
                    primaryBssid = primBssid,
                    primaryFreq = primFreq,
                    secondarySsid = secSsid,
                    secondaryBssid = secBssid,
                    secondaryFreq = secFreqFinal,
                    isSecondaryConnected = secConnected
                )
                it.copy(
                    scanResults = reconciledScanResults,
                    primaryIface = primIface,
                    primarySsid = primSsid,
                    primaryBssid = primBssid,
                    primaryIp = primIp,
                    primaryFreq = primFreq,
                    primaryRssi = primRssi,
                    secondaryIface = secIface,
                    secondarySsid = secSsid,
                    secondaryBssid = secBssid,
                    secondaryIp = finalSecIpResolved,
                    secondaryFreq = secFreqFinal,
                    secondaryRssi = secRssiFinal,
                    secondaryConnected = secConnected,
                    isHotspotEnabled = hotspotInfo.isEnabled,
                    hotspotIface = hotspotInfo.iface,
                    hotspotFreq = hotspotInfo.frequencyMhz,
                    hotspotBandRes = hotspotInfo.bandRes,
                    hotspotBand = hotspotInfo.bandLabel,
                    hotspotUpstreamTarget = hotspotTarget,
                    hotspotUpstreamActive = hotspotActive,
                    hotspotHookState = hookState
                )
            }
        }
    }

    /** Serializes root commands off the main thread; obsolete requests cannot publish stale state. */
    suspend fun setHotspotUpstream(target: String) {
        val requestId = hotspotRequestId.incrementAndGet()
        withContext(Dispatchers.IO) {
            hotspotMutex.withLock {
                if (requestId != hotspotRequestId.get()) return@withLock
                _status.update { it.copy(isHotspotSwitching = true, hotspotUpdateFailed = false) }
                try {
                    val success = SuHelper.setHotspotUpstream(target)
                    val actualTarget = SuHelper.getHotspotUpstream()
                    val active = SuHelper.getHotspotActiveUpstream()
                    val hookState = SuHelper.getHotspotHookStatus()
                    if (requestId == hotspotRequestId.get()) {
                        _status.update { it.copy(
                            hotspotUpstreamTarget = actualTarget,
                            hotspotUpstreamActive = active,
                            hotspotHookState = hookState,
                            hotspotUpdateFailed = !success
                        ) }
                    }
                } finally {
                    _status.update { it.copy(isHotspotSwitching = false) }
                }
            }
        }
    }

    /**
     * Asynchronously connects the secondary Wi-Fi using DualWifiManager.scope to prevent coroutine cancellation from UI dismissal.
     */
    fun connectSecondaryWifiAsync(
        context: Context,
        ssid: String,
        passphrase: String?,
        frequencyMhz: Int,
        bssid: String? = null,
        capabilities: String? = null
    ) {
        val ticket = secondaryConnections.connect()
        scope.launch {
            secondaryConnections.withConnection(ticket) {
                connectSecondaryWifi(context, ssid, passphrase, frequencyMhz, bssid, capabilities, ticket)
            }
        }
    }

    private fun connectionScheme(context: Context): DualWifiScheme {
        val rom = detectRomType()
        val configured = DualWifiScheme.fromId(RouteConfigProvider.getDualWifiScheme(context))
        return getEffectiveScheme(configured, rom)
    }

    internal suspend fun secondaryNetworkEntry(context: Context, item: WifiScanItem): SecondaryNetworkPolicy.Entry = withContext(Dispatchers.IO) {
        SecondaryNetworkPolicy.entry(connectionScheme(context) == DualWifiScheme.XIAOMI_SLAVE,
            item.isSecure, !item.savedPassword.isNullOrEmpty()) { getNetworkIdForSsid(item.ssid) }
    }

    /**
     * Connects the secondary Wi-Fi interface to the chosen access point via Root & Multi-Internet.
     */
    suspend fun connectSecondaryWifi(
        context: Context,
        ssid: String,
        passphrase: String?,
        frequencyMhz: Int,
        bssid: String? = null,
        capabilities: String? = null
    ): Boolean {
        val ticket = secondaryConnections.connect()
        return secondaryConnections.withConnection(ticket) {
            connectSecondaryWifi(context, ssid, passphrase, frequencyMhz, bssid, capabilities, ticket)
        }
    }

    private suspend fun connectSecondaryWifi(
        context: Context,
        ssid: String,
        passphrase: String?,
        frequencyMhz: Int,
        bssid: String?,
        capabilities: String?,
        ticket: SecondaryConnectionCoordinator.Ticket
    ): Boolean = withContext(Dispatchers.IO) {
      secondaryConnections.serialized {
        if (!secondaryConnections.isCurrent(ticket)) return@serialized false
        // Prevent collision: Never allow connecting primary AP as secondary Wi-Fi
        val primSsid = _status.value.primarySsid
        val primBssid = _status.value.primaryBssid
        if (isPrimaryCollision(ssid, bssid, primSsid, primBssid)) {
            secondaryConnections.publish(ticket) { _status.update {
                it.copy(
                    isConnecting = false,
                    connectingSsid = null,
                    connectingBssid = null,
                    errorMessage = context.getString(R.string.wifi_i18n_primary_connection_error, primSsid)
                )
            } }
            return@serialized false
        }

        // Prevent radio collision: Never allow connecting secondary Wi-Fi while personal hotspot is active
        if (_status.value.isHotspotEnabled || checkHotspotState(context).isEnabled) {
            val err = context.getString(R.string.dual_wifi_hotspot_error_blocked)
            secondaryConnections.publish(ticket) { _status.update {
                it.copy(
                    isConnecting = false,
                    connectingSsid = null,
                    connectingBssid = null,
                    errorMessage = err
                )
            } }
            return@serialized false
        }

        secondaryConnections.publish(ticket) {
            _status.update { it.copy(isConnecting = true, connectingSsid = ssid, connectingBssid = bssid, errorMessage = null) }
        }
        var connectionAccepted = false
        var driverExecuting = false
        val scheme = connectionScheme(context)
        val driver = DualWifiDriverFactory.getDriver(scheme)
        try {
            checkCurrentSecondaryIntent(ticket)
            val isXiaomiSlave = scheme == DualWifiScheme.XIAOMI_SLAVE
            check(SecondaryNetworkPolicy.canConnect(isXiaomiSlave) { getNetworkIdForSsid(ssid) }) {
                context.getString(R.string.dual_wifi_saved_network_required)
            }

            driver.prepareConnect(context)
            checkCurrentSecondaryIntent(ticket)

            driverExecuting = true
            driver.executeConnect(context, ssid, bssid, frequencyMhz, passphrase, capabilities) {
                checkCurrentSecondaryIntent(ticket)
            }

            // 4. Poll for secondary connection (AOSP multi-internet needs nl80211 wlan1 cold-start + DHCP)
            var connected = false
            var failureReason: String? = null
            val maxAttempts = driver.maxPollAttempts
            for (i in 0 until maxAttempts) {
                delay(1000)
                checkCurrentSecondaryIntent(ticket)
                refreshConnectionState(context)
                checkCurrentSecondaryIntent(ticket)
                if (_status.value.secondaryConnected && _status.value.secondarySsid == ssid &&
                    (bssid.isNullOrEmpty() || _status.value.secondaryBssid.equals(bssid, ignoreCase = true))) {
                    connected = true
                    break
                }

                // Check recent logcat for handshake failures
                if (i in 2..(maxAttempts - 2)) {
                    val logs = SuHelper.executeShellWithOutput(
                        "logcat -d -t 30 -s wpa_supplicant:I WifiClientModeImpl:D ClientModeImpl:D | grep -E 'WRONG_KEY|AUTH_FAILURE|Association rejection'",
                        timeoutSeconds = 2
                    )
                    for (line in logs) {
                        when {
                            line.contains("WRONG_KEY") || line.contains("AUTH_FAILURE_WRONG_PSWD") -> {
                                failureReason = context.getString(R.string.wifi_i18n_wrong_password)
                                break
                            }
                            line.contains("Association rejection") -> {
                                failureReason = context.getString(R.string.wifi_i18n_ap_rejected)
                                break
                            }
                        }
                    }
                    if (failureReason != null) break
                }
            }

            if (connected) {
                val secState = _status.value
                connectionAccepted = secondaryConnections.publish(ticket) {
                    RouteConfigProvider.setDualWifiDisabled(context, false)
                    if (ssid != secState.primarySsid) {
                        RouteConfigProvider.saveLastSecondaryWifi(
                            context,
                            ssid = ssid,
                            pass = if (isXiaomiSlave) passphrase else null,
                            freq = frequencyMhz,
                            bssid = bssid,
                            caps = capabilities
                        )
                    }
                    DualWifiService.start(context, secState.secondarySsid ?: ssid, secState.secondaryIface ?: "wlan1", ticket.generation)
                    scope.launch {
                        SuHelper.syncAllRouteRules(context)
                    }
                    _status.update { it.copy(isConnecting = false, connectingSsid = null, connectingBssid = null) }
                }
                connectionAccepted
            } else {
                val err = failureReason ?: context.getString(R.string.wifi_i18n_connect_timeout)
                secondaryConnections.publish(ticket) { _status.update {
                    it.copy(
                        isConnecting = false,
                        connectingSsid = null,
                        connectingBssid = null,
                        errorMessage = err
                    )
                } }
                false
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "connectSecondaryWifi coroutine cancelled")
                secondaryConnections.publish(ticket) {
                    _status.update { it.copy(isConnecting = false, connectingSsid = null, connectingBssid = null) }
                }
                throw t
            }
            Log.e(TAG, "Failed to connect secondary wifi", t)
            secondaryConnections.publish(ticket) {
                _status.update { it.copy(isConnecting = false, connectingSsid = null,
                            connectingBssid = null, errorMessage = context.getString(R.string.wifi_i18n_connect_failed, t.message ?: "") ) }
            }
            false
        } finally {
            if (!connectionAccepted && driverExecuting) {
                driver.release(context)
            }
        }
      }
    }

    private fun checkCurrentSecondaryIntent(ticket: SecondaryConnectionCoordinator.Ticket) {
        if (!secondaryConnections.isCurrent(ticket)) throw kotlinx.coroutines.CancellationException("Secondary connection superseded")
    }

    /**
     * Attempts to auto-reconnect the last configured secondary Wi-Fi if enabled and not currently connected.
     */
    suspend fun autoReconnectSecondary(context: Context): Boolean {
      if (RouteConfigProvider.isDualWifiDisabled(context)) return false
      val ticket = secondaryConnections.retry() ?: return false
      return secondaryConnections.withConnection(ticket) { withContext(Dispatchers.IO) {
        if (RouteConfigProvider.isDualWifiDisabled(context) ||
            !RouteConfigProvider.isDualWifiAutoReconnectEnabled(context)) {
            return@withContext false
        }
        refreshConnectionState(context)
        if (!secondaryConnections.isCurrent(ticket)) return@withContext false
        if (_status.value.secondaryConnected) return@withContext true

        // Yield radio resource to personal hotspot if enabled
        if (_status.value.isHotspotEnabled || checkHotspotState(context).isEnabled) {
            Log.i(TAG, "Secondary auto-reconnect skipped: Hotspot (SoftAP) is active, yielding radio chain")
            return@withContext false
        }

        val lastWifi = RouteConfigProvider.getLastSecondaryWifi(context)
        val ssid = lastWifi["ssid"] as? String ?: return@withContext false
        val pass = (lastWifi["pass"] as? String)?.takeIf { it.isNotEmpty() }
        val freq = lastWifi["freq"] as? Int ?: 0
        val bssid = lastWifi["bssid"] as? String
        val caps = lastWifi["caps"] as? String

        val currentPrimarySsid = _status.value.primarySsid
        val currentPrimaryBssid = _status.value.primaryBssid
        if (isPrimaryCollision(ssid, bssid, currentPrimarySsid, currentPrimaryBssid)) {
            Log.w(TAG, "Prevented secondary auto-reconnect: configured secondary '$ssid' matches active primary '$currentPrimarySsid'")
            secondaryConnections.publish(ticket) {
                RouteConfigProvider.clearLastSecondaryWifi(context)
            }
            return@withContext false
        }

        Log.i(TAG, "Auto reconnecting secondary Wi-Fi: $ssid")
        if (!secondaryConnections.isCurrent(ticket)) return@withContext false
        connectSecondaryWifi(context, ssid, pass, freq, bssid, caps, ticket)
      } }
    }

    /**
     * Disconnects the secondary Wi-Fi network.
     */
    fun disconnectSecondaryWifi(
        context: Context,
        clearSavedPreference: Boolean = true,
        expectedGeneration: Long? = null,
        suppressReconnect: Boolean = clearSavedPreference
    ): Boolean {
        // Invalidate an in-flight attempt before waiting for any blocking root operation.
        val ticket = if (expectedGeneration == null) secondaryConnections.disconnect(suppressReconnect = suppressReconnect)
            else secondaryConnections.disconnectIfCurrent(SecondaryConnectionCoordinator.Ticket(expectedGeneration)) ?: return false
        scope.launch {
          secondaryConnections.serialized {
            // A later explicit connect may have overtaken this queued disconnect.
            if (!secondaryConnections.isCurrent(ticket)) return@serialized
            try {
                if (clearSavedPreference) RouteConfigProvider.clearLastSecondaryWifi(context)
                DualWifiService.stop(context)
                val scheme = connectionScheme(context)
                val driver = DualWifiDriverFactory.getDriver(scheme)
                driver.release(context)
                delay(500)
                refreshConnectionState(context)
                secondaryConnections.publish(ticket) {
                    _status.update { it.copy(isConnecting = false, connectingSsid = null, connectingBssid = null) }
                }
                SuHelper.syncAllRouteRules(context)
            } finally {
                secondaryConnections.disconnected(ticket)
            }
          }
        }
        return true
    }
}

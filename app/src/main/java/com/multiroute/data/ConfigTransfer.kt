package com.multiroute.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.TestServerConfig
import com.multiroute.util.NetworkUtils
import com.multiroute.util.RouteRuleBuilder
import com.multiroute.util.ScenarioEngine
import com.multiroute.util.SuHelper
import com.multiroute.wifi.DualWifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Portable user settings. Passwords, UID caches and runtime system state are never serialized. */
object ConfigTransfer {
    const val FORMAT = "multiroute-config"
    const val VERSION = 3
    const val EXPORT_DIR = "/sdcard/Download"
    private const val HOTSPOT_KEY = "multiroute_hotspot_upstream"

    enum class Section(val key: String) {
        ASSIGNMENTS("assignments"), SCENARIOS("scenarios"), ROUTING("settings"),
        DUAL_WIFI("dualWifi"), HOTSPOT("hotspot"), APPEARANCE("appearance"), TEST_SERVER("testServer")
    }
    data class Document(
        val sections: Set<Section>, val assignments: Map<String, String> = emptyMap(),
        val profiles: List<ScenarioProfile> = emptyList(), val mode: String? = null,
        val manualIds: Set<String> = emptySet(),
        val rulesSettings: Map<Section, Map<String, Any>> = emptyMap(),
        val testServer: Map<String, Any> = emptyMap(), val hotspot: String? = null,
        val wifiManufacturer: String? = null, val wifiNetwork: Map<String, Any>? = null,
        val ignored: Int = 0
    )
    data class Preview(
        val document: Document, val targets: Map<String, Int>, val missingApps: List<String>,
        val unavailableChannels: List<String>, val incompatibleScheme: Boolean
    )
    data class ImportResult(
        val assignments: Int, val scenarios: Int, val skipped: Int, val settings: Int,
        val backupPath: String, val unavailableChannels: List<String>
    )
    class ImportFailedException(cause: Throwable? = null) : Exception("Configuration import failed", cause)

    fun timestamp(epochMillis: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(epochMillis))
    fun exportFileName(epochMillis: Long): String = "MultiRoute-config-${timestamp(epochMillis)}.json"
    fun exportPath(epochMillis: Long): String = "$EXPORT_DIR/${exportFileName(epochMillis)}"
    fun normalizeAssignments(raw: Map<String, String>): Map<String, String> = raw.filter { (key, channel) ->
        RouteConfigProvider.isValidRuleKey(key) && channel != CHANNEL_DEFAULT && RouteConfigProvider.isValidChannelId(channel)
    }
    private val networkBooleans = mapOf(
        "dnsFollowsChannel" to RouteConfigProvider.KEY_DNS_FOLLOWS_CHANNEL,
        "keepSlaveWifiScreenOff" to RouteConfigProvider.KEY_KEEP_SLAVE_WIFI_SCREEN_OFF,
        "autoFailoverEnabled" to RouteConfigProvider.KEY_AUTO_FAILOVER_ENABLED,
        "failoverProbeInternet" to RouteConfigProvider.KEY_FAILOVER_PROBE_INTERNET
    )
    private val schemes = setOf("auto", "aosp_overlay", "xiaomi_slave", "root_direct")

    suspend fun exportJson(context: Context, now: Long): String = SuHelper.withRouteConfigLock {
        DualWifiManager.withHotspotPreferenceLock { buildJson(context, now) }
    }

    fun buildJson(context: Context, now: Long): String {
        val root = JSONObject().put("format", FORMAT).put("version", VERSION).put("exportedAt", now)
        root.put("assignments", JSONObject(normalizeAssignments(RouteConfigProvider.getAllRules(context))))
        val profiles = ScenarioStore.load(context)
        root.put("scenarios", JSONObject()
            .put("profiles", ScenarioStore.encodeProfiles(profiles))
            .put("mode", if (ScenarioStore.isManualMode(context)) "manual" else "auto")
            .put("manualIds", JSONArray(ScenarioStore.getManualIds(context).filter { id -> profiles.any { it.id == id } }.sorted())))
        root.put("settings", JSONObject()
            .put("dnsFollowsChannel", RouteConfigProvider.isDnsFollowsChannel(context))
            .put("keepSlaveWifiScreenOff", RouteConfigProvider.isKeepSlaveWifiScreenOff(context))
            .put("autoFailoverEnabled", RouteConfigProvider.isAutoFailoverEnabled(context))
            .put("failoverHoldOffSeconds", RouteConfigProvider.getFailoverHoldOffSeconds(context))
            .put("failoverProbeInternet", RouteConfigProvider.isFailoverProbeInternet(context)))
        root.put("appearance", JSONObject()
            .put("themeMode", RouteConfigProvider.getThemeMode(context))
            .put("dynamicColor", RouteConfigProvider.isDynamicColor(context))
            .put("language", RouteConfigProvider.getLanguage(context)))
        val last = RouteConfigProvider.getLastSecondaryWifi(context)
        val network = if (last["ssid"] is String) JSONObject()
            .put("ssid", last["ssid"]).put("frequencyMhz", last["freq"] ?: 0)
            .put("bssid", last["bssid"] ?: "").put("capabilities", last["caps"] ?: "") else null
        root.put("dualWifi", JSONObject()
            .put("autoReconnect", RouteConfigProvider.isDualWifiAutoReconnectEnabled(context))
            .put("scheme", RouteConfigProvider.getDualWifiScheme(context).takeIf { it in schemes } ?: "auto")
            .put("manufacturer", Build.MANUFACTURER).put("lastNetwork", network ?: JSONObject.NULL))
        root.put("hotspot", JSONObject().put("upstream",
            Settings.Global.getString(context.contentResolver, HOTSPOT_KEY) ?: "default"))
        val server = TestServerManager.getConfig(context)
        val safeUrl = server.customUrl.takeIf { it.isEmpty() || isSafeServerUrl(it) }.orEmpty()
        root.put("testServer", JSONObject()
            .put("presetId", if (server.isCustom && safeUrl.isEmpty()) TestServerConfig.DEFAULT_PRESET_ID else server.selectedPresetId)
            .put("customUrl", safeUrl))
        return root.toString(2)
    }

    /** Validate the complete document before resolving packages or modifying preferences. */
    fun parse(json: String): Document? = try {
        val root = JSONObject(json)
        require(root.get("format") == FORMAT)
        val version = root.integer("version"); require(version in 1..VERSION)
        val sections = mutableSetOf<Section>()
        val ruleSettings = mutableMapOf<Section, Map<String, Any>>()
        var assignments = emptyMap<String, String>()
        var profiles = emptyList<ScenarioProfile>()
        var mode: String? = null
        var manualIds = emptySet<String>()
        var ignored = 0
        if (root.has("assignments")) {
            val raw = root.obj("assignments").strings()
            assignments = normalizeAssignments(raw); ignored += raw.size - assignments.size
            sections += Section.ASSIGNMENTS
        }
        if (root.has("scenarios")) {
            val array = (if (version == 1) root.get("scenarios") else root.obj("scenarios").get("profiles")) as? JSONArray
                ?: error("profiles must be an array")
            validateProfiles(array); profiles = ScenarioStore.parseProfiles(array)
            require(profiles.size == array.length() && profiles.map { it.id }.distinct().size == profiles.size)
            if (version >= 2) {
                val s = root.obj("scenarios")
                mode = s.string("mode").also { require(it in setOf("auto", "manual")) }
                manualIds = s.stringArray("manualIds").toSet()
                require(manualIds.all { id -> profiles.any { it.id == id } })
            }
            sections += Section.SCENARIOS
        }
        if (root.has("settings")) {
            val s = root.obj("settings"); val values = mutableMapOf<String, Any>()
            networkBooleans.forEach { (jsonKey, prefKey) -> if (s.has(jsonKey)) values[prefKey] = s.boolean(jsonKey) }
            if (s.has("failoverHoldOffSeconds")) values[RouteConfigProvider.KEY_FAILOVER_HOLD_OFF_SECONDS] =
                s.integer("failoverHoldOffSeconds").also { require(it in 1..15) }
            ruleSettings[Section.ROUTING] = values; sections += Section.ROUTING
            if (version == 1 && s.has("manualScenarioId")) {
                require(Section.SCENARIOS in sections)
                val id = s.string("manualScenarioId")
                manualIds = setOf(id).filter { it.isNotEmpty() && profiles.any { p -> p.id == it } }.toSet()
                mode = if (manualIds.isEmpty()) "auto" else "manual"
            }
        }
        if (version == 1 && Section.SCENARIOS in sections && mode == null) mode = "auto"
        var manufacturer: String? = null
        var wifiNetwork: Map<String, Any>? = null
        var hotspot: String? = null
        var server = emptyMap<String, Any>()
        if (version >= 2) {
            if (root.has("appearance")) {
                val a = root.obj("appearance")
                ruleSettings[Section.APPEARANCE] = mapOf(
                    RouteConfigProvider.KEY_THEME_MODE to a.string("themeMode").also { require(it in setOf("system", "light", "dark")) },
                    RouteConfigProvider.KEY_DYNAMIC_COLOR to a.boolean("dynamicColor"),
                    RouteConfigProvider.KEY_LANGUAGE to a.string("language").also { require(it in setOf("", "zh-CN", "en")) })
                sections += Section.APPEARANCE
            }
            if (root.has("dualWifi")) {
                val w = root.obj("dualWifi")
                ruleSettings[Section.DUAL_WIFI] = mapOf(
                    RouteConfigProvider.KEY_DUAL_WIFI_AUTO_RECONNECT to w.boolean("autoReconnect"),
                    RouteConfigProvider.KEY_DUAL_WIFI_SCHEME to w.string("scheme").also { require(it in schemes) })
                manufacturer = w.string("manufacturer"); require(w.has("lastNetwork"))
                if (!w.isNull("lastNetwork")) {
                    val n = w.obj("lastNetwork")
                    wifiNetwork = mapOf(
                        RouteConfigProvider.KEY_DUAL_WIFI_LAST_SSID to n.string("ssid").also { require(it.isNotBlank() && it.length <= 32) },
                        RouteConfigProvider.KEY_DUAL_WIFI_LAST_FREQ to n.integer("frequencyMhz").also { require(it in 0..7125) },
                        RouteConfigProvider.KEY_DUAL_WIFI_LAST_BSSID to n.string("bssid").also { require(it.isEmpty() || Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}").matches(it)) },
                        RouteConfigProvider.KEY_DUAL_WIFI_LAST_CAPS to n.string("capabilities"))
                }
                sections += Section.DUAL_WIFI
            }
            if (root.has("hotspot")) {
                hotspot = root.obj("hotspot").string("upstream")
                require(hotspot == "default" || hotspot == "cellular" || RouteRuleBuilder.isValidInterfaceName(hotspot))
                sections += Section.HOTSPOT
            }
            if (root.has("testServer")) {
                val s = root.obj("testServer"); val preset = s.string("presetId")
                require(preset == TestServerConfig.PRESET_CUSTOM || TestServerConfig.PRESETS.any { it.id == preset })
                val url = s.string("customUrl")
                require((url.isEmpty() && preset != TestServerConfig.PRESET_CUSTOM) || isSafeServerUrl(url))
                server = mapOf(TestServerManager.KEY_PRESET_ID to preset, TestServerManager.KEY_CUSTOM_URL to url)
                sections += Section.TEST_SERVER
            }
        }
        require(sections.isNotEmpty())
        Document(sections, assignments, profiles, mode, manualIds, ruleSettings, server, hotspot, manufacturer, wifiNetwork, ignored)
    } catch (_: Exception) { null }

    suspend fun preview(context: Context, json: String): Preview? {
        val doc = parse(json) ?: return null
        val keys = doc.assignments.keys + doc.profiles.flatMap { it.overrides.keys }
        val secondary = if (keys.any { '@' in it }) SuHelper.listSecondaryUserInstalls() else emptyList()
        val targets = keys.associateWith { SuHelper.resolveUidForRuleKey(context, it, secondary) }
        val active = NetworkUtils.getActiveChannels(context)
        val channels = active.flatMap { listOf(it.id, it.interfaceName) }.toSet()
        val wanted = doc.assignments.values + doc.profiles.flatMap { it.overrides.values } + listOfNotNull(doc.hotspot)
        return Preview(doc, targets, targets.filterValues { it < 10000 }.keys.sorted(),
            wanted.filter { target ->
                val ssid = ScenarioEngine.symbolicSsidOf(target)
                if (ssid != null) active.none { it.ssid.equals(ssid, ignoreCase = true) }
                else target !in setOf("default", "cellular") && target !in channels
            }.distinct().sorted(),
            !isSchemeCompatible(doc.rulesSettings[Section.DUAL_WIFI]?.get(RouteConfigProvider.KEY_DUAL_WIFI_SCHEME) as? String ?: "auto",
                doc.wifiManufacturer, Build.MANUFACTURER))
    }

    internal fun isSchemeCompatible(scheme: String, source: String?, destination: String): Boolean =
        scheme == "auto" || (source.equals(destination, ignoreCase = true) &&
            (scheme != "xiaomi_slave" || destination.lowercase() in setOf("xiaomi", "redmi", "poco")))

    /** Missing sections and concurrent unrelated keys are preserved, including during rollback. */
    suspend fun apply(context: Context, json: String, now: Long, selected: Set<Section>? = null): ImportResult? =
        SuHelper.withRouteConfigLock {
          DualWifiManager.withHotspotPreferenceLock {
            val preview = preview(context, json) ?: return@withHotspotPreferenceLock null
            val doc = preview.document
            val sections = selected?.intersect(doc.sections) ?: doc.sections
            if (sections.isEmpty()) return@withHotspotPreferenceLock null
            val rules = context.getSharedPreferences(RouteConfigProvider.PREF_NAME, Context.MODE_PRIVATE)
            val scenarios = context.getSharedPreferences(ScenarioStore.PREFS, Context.MODE_PRIVATE)
            val server = context.getSharedPreferences(TestServerManager.PREF_NAME, Context.MODE_PRIVATE)
            val snapshots = listOf(rules, scenarios, server).associateWith { HashMap(it.all) }
            val next = snapshots.mapValues { HashMap(it.value) }
            var skipped = if (Section.ASSIGNMENTS in sections) doc.ignored else 0
            var count = 0
            if (Section.ASSIGNMENTS in sections) {
                val values = next.getValue(rules)
                values.keys.filter(RouteConfigProvider::isStoredRuleKey).forEach(values::remove)
                doc.assignments.forEach { (key, channel) ->
                    val uid = preview.targets[key] ?: -1
                    if (uid >= 10000) { values[key] = channel; values["uid_$uid"] = channel; count++ } else skipped++
                }
            }
            if (Section.SCENARIOS in sections) {
                val values = next.getValue(scenarios)
                values["profiles"] = ScenarioStore.encodeProfiles(doc.profiles).toString()
                values["mode"] = doc.mode ?: "auto"; values["manual_ids"] = doc.manualIds; values.remove("manual_id")
            }
            var settings = 0
            doc.rulesSettings.filterKeys { it in sections }.forEach { (_, values) -> next.getValue(rules).putAll(values); settings += values.size }
            if (Section.DUAL_WIFI in sections) {
                val values = next.getValue(rules)
                if (preview.incompatibleScheme) { values[RouteConfigProvider.KEY_DUAL_WIFI_SCHEME] = "auto"; skipped++ }
                val oldSsid = values[RouteConfigProvider.KEY_DUAL_WIFI_LAST_SSID]
                val newSsid = doc.wifiNetwork?.get(RouteConfigProvider.KEY_DUAL_WIFI_LAST_SSID)
                if (oldSsid != newSsid || newSsid == null) values.remove(RouteConfigProvider.KEY_DUAL_WIFI_LAST_PASS)
                listOf(RouteConfigProvider.KEY_DUAL_WIFI_LAST_SSID, RouteConfigProvider.KEY_DUAL_WIFI_LAST_FREQ,
                    RouteConfigProvider.KEY_DUAL_WIFI_LAST_BSSID, RouteConfigProvider.KEY_DUAL_WIFI_LAST_CAPS).forEach(values::remove)
                doc.wifiNetwork?.let(values::putAll)
            }
            if (Section.TEST_SERVER in sections) next.getValue(server).putAll(doc.testServer)
            // Backup failure prevents replacement. Unique names avoid same-second overwrites.
            val backup = try {
                File.createTempFile("config-backup-${timestamp(now)}-", ".json", context.filesDir).also { it.writeText(buildJson(context, now)) }
            } catch (e: Exception) { throw ImportFailedException(e) }
            val oldRaw = Settings.Global.getString(context.contentResolver, HOTSPOT_KEY)
            val oldTarget = oldRaw ?: "default"
            val changesHotspot = Section.HOTSPOT in sections && doc.hotspot != oldTarget
            val oldPersist = if (changesHotspot) SuHelper.executeShellWithOutput("getprop persist.multiroute.hotspot_upstream").firstOrNull().orEmpty() else ""
            val transaction = PreferenceTransaction(next.mapValues { (sp, values) ->
                PreferenceTransaction.changesBetween(snapshots.getValue(sp), values)
            })
            var hotspotAttempted = false
            try {
                transaction.apply({ it.all }, ::writePreferences)
                if (changesHotspot) {
                    hotspotAttempted = true; check(SuHelper.setHotspotUpstream(doc.hotspot!!))
                }
            } catch (e: Exception) {
                val restored = transaction.rollback(::writePreferences)
                val hotspotRestored = !hotspotAttempted || restoreHotspot(oldRaw, oldPersist)
                if (!restored || !hotspotRestored) android.util.Log.e("MultiRoute-Config", "Configuration rollback incomplete")
                throw ImportFailedException(e)
            }
            val importedTargets = (if (Section.ASSIGNMENTS in sections) doc.assignments.values else emptyList()) +
                (if (Section.SCENARIOS in sections) doc.profiles.flatMap { it.overrides.values } else emptyList()) +
                (if (Section.HOTSPOT in sections) listOfNotNull(doc.hotspot) else emptyList())
            ImportResult(count, if (Section.SCENARIOS in sections) doc.profiles.size else 0, skipped, settings,
                backup.absolutePath, preview.unavailableChannels.filter { it in importedTargets })
          }
        }

    private fun restoreHotspot(raw: String?, persistent: String): Boolean {
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        val setting = if (raw == null) "settings delete global $HOTSPOT_KEY" else "settings put global $HOTSPOT_KEY ${quote(raw)}"
        return SuHelper.executeCommand("$setting && setprop persist.multiroute.hotspot_upstream ${quote(persistent)} && am broadcast -a com.multiroute.ACTION_UPDATE_HOTSPOT_UPSTREAM")
    }

    private fun writePreferences(sp: SharedPreferences, values: Map<String, *>): Boolean {
        val editor = sp.edit()
        values.forEach { (key, value) -> when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported preference type")
        } }
        return editor.commit()
    }
    private fun validateProfiles(array: JSONArray) {
        for (i in 0 until array.length()) {
            val p = array.get(i) as? JSONObject ?: error("Invalid scenario")
            require(p.string("id").isNotBlank()); p.string("name")
            if (p.has("enabled")) p.boolean("enabled")
            if (p.has("priority")) p.integer("priority")
            val trigger = p.obj("trigger")
            when (trigger.string("kind")) {
                "ssid" -> require(trigger.stringArray("ssids").all { it.isNotBlank() && it.length <= 32 })
                "wifi_links" -> { val min = trigger.integer("min"); require(min >= 0); if (trigger.has("max")) require(trigger.integer("max") >= min) }
                "always", "manual", "cellular_only" -> Unit
                else -> error("Unknown scenario trigger")
            }
            p.obj("overrides").strings().forEach { (key, channel) ->
                require(RouteConfigProvider.isValidRuleKey(key))
                require(channel == "default" || RouteConfigProvider.isValidChannelId(channel) ||
                    ScenarioEngine.symbolicSsidOf(channel)?.let { it.isNotBlank() && it.length <= 32 } == true)
            }
        }
    }
    private fun isSafeServerUrl(value: String): Boolean = runCatching {
        val uri = URI(value); uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
    private fun JSONObject.obj(key: String) = get(key) as? JSONObject ?: error("$key must be an object")
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("$key must be a string")
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("$key must be a boolean")
    private fun JSONObject.integer(key: String): Int {
        val n = get(key); require(n is Int || n is Long)
        return (n as Number).toLong().also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    }
    private fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith { string(it) }
    private fun JSONObject.stringArray(key: String): List<String> {
        val array = get(key) as? JSONArray ?: error("$key must be an array")
        return (0 until array.length()).map { array.get(it) as? String ?: error("$key must contain strings") }
    }
}

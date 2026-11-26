package com.multiroute.data

import android.content.Context
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.model.ScenarioVpnCondition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scenario profiles, stored as one JSON string.
 *
 * Kept apart from [RouteConfigProvider] on purpose: the base per-app assignments stay exactly where they
 * were, so this feature needs no migration and switching profiles can never lose them. One JSON blob also
 * means one atomic write, and it is the natural unit for a later export/import.
 */
object ScenarioStore {

    internal const val PREFS = "multiroute_scenarios"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_MANUAL = "manual_id"
    private const val KEY_MODE = "mode"
    private const val KEY_MANUAL_IDS = "manual_ids"
    private const val KEY_ROUTING_PAUSED = "routing_paused"

    const val MODE_AUTOMATIC = "auto"
    const val MODE_MANUAL = "manual"
    const val MODE_DISABLED = "disabled"

    /** Trigger kinds, written to JSON so the format stays readable and forward-compatible. */
    private const val KIND_ALWAYS = "always"
    private const val KIND_MANUAL = "manual"
    private const val KIND_SSID = "ssid"
    private const val KIND_LINK_COUNT = "wifi_links"
    private const val KIND_CELLULAR_ONLY = "cellular_only"
    private const val VPN_CONNECTED = "connected"
    private const val VPN_DISCONNECTED = "disconnected"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<ScenarioProfile> {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            parseProfiles(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Profiles as a JSON array. Shared with the configuration export so a backup and the live store always
     * agree on the shape - an export that used its own writer would be a second format to keep in step.
     */
    fun encodeProfiles(profiles: List<ScenarioProfile>): JSONArray {
        val array = JSONArray()
        profiles.forEach { array.put(encodeProfile(it)) }
        return array
    }

    /** Parses [encodeProfiles] output; a malformed entry is skipped instead of failing the whole list. */
    fun parseProfiles(array: JSONArray): List<ScenarioProfile> =
        (0 until array.length()).mapNotNull { index ->
            runCatching { parseProfile(array.getJSONObject(index)) }.getOrNull()
        }

    fun save(context: Context, profiles: List<ScenarioProfile>) {
        prefs(context).edit().putString(KEY_PROFILES, encodeProfiles(profiles).toString()).apply()
    }

    /** Replaces the profile with the same id, or appends it. */
    fun upsert(context: Context, profile: ScenarioProfile) {
        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) current[index] = profile else current.add(profile)
        save(context, current)
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
        if (id in getManualIds(context)) {
            setManualIds(context, getManualIds(context) - id)
        }
    }

    fun getMode(context: Context): com.multiroute.util.ScenarioEngine.SelectionMode {
        val sp = prefs(context)
        if (sp.contains(KEY_MODE)) {
            return when (sp.getString(KEY_MODE, MODE_AUTOMATIC)) {
                MODE_MANUAL -> com.multiroute.util.ScenarioEngine.SelectionMode.MANUAL
                MODE_DISABLED -> com.multiroute.util.ScenarioEngine.SelectionMode.DISABLED
                else -> com.multiroute.util.ScenarioEngine.SelectionMode.AUTOMATIC
            }
        }
        return if (getManualIds(context).isNotEmpty()) {
            com.multiroute.util.ScenarioEngine.SelectionMode.MANUAL
        } else {
            com.multiroute.util.ScenarioEngine.SelectionMode.AUTOMATIC
        }
    }

    fun setMode(context: Context, mode: com.multiroute.util.ScenarioEngine.SelectionMode) {
        val raw = when (mode) {
            com.multiroute.util.ScenarioEngine.SelectionMode.MANUAL -> MODE_MANUAL
            com.multiroute.util.ScenarioEngine.SelectionMode.DISABLED -> MODE_DISABLED
            com.multiroute.util.ScenarioEngine.SelectionMode.AUTOMATIC -> MODE_AUTOMATIC
        }
        prefs(context).edit().putString(KEY_MODE, raw).apply()
    }

    fun isRoutingPaused(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ROUTING_PAUSED, false)

    fun setRoutingPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_ROUTING_PAUSED, paused).apply()
    }

    /**
     * True when the conditions are ignored and plans are switched by hand only. Stored explicitly; a
     * database written by an older build has no mode, and its single pinned plan meant the same thing.
     */
    fun isManualMode(context: Context): Boolean =
        getMode(context) == com.multiroute.util.ScenarioEngine.SelectionMode.MANUAL

    fun setManualMode(context: Context, manual: Boolean) {
        setMode(
            context,
            if (manual) com.multiroute.util.ScenarioEngine.SelectionMode.MANUAL
            else com.multiroute.util.ScenarioEngine.SelectionMode.AUTOMATIC
        )
    }

    /** Plans switched on by hand. Several can be on at once, and they layer by priority like the rest. */
    fun getManualIds(context: Context): Set<String> {
        val sp = prefs(context)
        sp.getStringSet(KEY_MANUAL_IDS, null)?.let { return it }
        // Legacy: one pinned plan.
        return (sp.getString(KEY_MANUAL, "") ?: "").takeIf { it.isNotEmpty() }?.let { setOf(it) }
            ?: emptySet()
    }

    fun setManualIds(context: Context, ids: Collection<String>) {
        prefs(context).edit().putStringSet(KEY_MANUAL_IDS, ids.toSet()).apply()
    }

    /** Switches one plan on or off; returns true when it is now on. */
    fun toggleManualId(context: Context, id: String): Boolean {
        val current = getManualIds(context).toMutableSet()
        val enabled = if (current.contains(id)) {
            current.remove(id)
            false
        } else {
            current.add(id)
            true
        }
        setManualIds(context, current)
        return enabled
    }

    /** Id of the profile the user pinned by hand, or empty when selection is automatic. */
    fun getManualId(context: Context): String = getManualIds(context).firstOrNull() ?: ""

    fun setManualId(context: Context, id: String?) {
        if (id.isNullOrEmpty()) {
            setManualIds(context, emptySet())
        } else {
            setManualIds(context, setOf(id))
        }
    }

    private fun encodeProfile(profile: ScenarioProfile): JSONObject {
        val trigger = JSONObject()
        when (val t = profile.trigger) {
            is ScenarioTrigger.Always -> trigger.put("kind", KIND_ALWAYS)
            is ScenarioTrigger.Manual -> trigger.put("kind", KIND_MANUAL)
            is ScenarioTrigger.CellularOnly -> trigger.put("kind", KIND_CELLULAR_ONLY)
            is ScenarioTrigger.SsidMatch -> {
                trigger.put("kind", KIND_SSID)
                trigger.put("ssids", JSONArray(t.ssids))
            }
            is ScenarioTrigger.WifiLinkCount -> {
                trigger.put("kind", KIND_LINK_COUNT)
                trigger.put("min", t.min)
                t.max?.let { trigger.put("max", it) }
            }
        }
        val overrides = JSONObject()
        profile.overrides.forEach { (key, channel) -> overrides.put(key, channel) }
        return JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("enabled", profile.enabled)
            put("priority", profile.priority)
            put("trigger", trigger)
            when (profile.vpnCondition) {
                ScenarioVpnCondition.CONNECTED -> put("vpnCondition", VPN_CONNECTED)
                ScenarioVpnCondition.DISCONNECTED -> put("vpnCondition", VPN_DISCONNECTED)
                ScenarioVpnCondition.ANY -> Unit
            }
            if (profile.bypassBaseRules) put("bypassBaseRules", true)
            put("overrides", overrides)
        }
    }

    private fun parseProfile(json: JSONObject): ScenarioProfile {
        val t = json.optJSONObject("trigger") ?: JSONObject()
        val trigger = when (t.optString("kind")) {
            KIND_ALWAYS -> ScenarioTrigger.Always
            KIND_SSID -> ScenarioTrigger.SsidMatch(
                (0 until (t.optJSONArray("ssids")?.length() ?: 0))
                    .mapNotNull { t.optJSONArray("ssids")?.optString(it)?.takeIf { s -> s.isNotBlank() } }
            )
            KIND_LINK_COUNT -> ScenarioTrigger.WifiLinkCount(
                min = t.optInt("min", 0),
                max = if (t.has("max")) t.optInt("max") else null
            )
            KIND_CELLULAR_ONLY -> ScenarioTrigger.CellularOnly
            else -> ScenarioTrigger.Manual
        }
        val overridesJson = json.optJSONObject("overrides") ?: JSONObject()
        val overrides = mutableMapOf<String, String>()
        overridesJson.keys().forEach { key ->
            val value = overridesJson.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) overrides[key] = value
        }
        val vpnCondition = when (json.optString("vpnCondition")) {
            VPN_CONNECTED -> ScenarioVpnCondition.CONNECTED
            VPN_DISCONNECTED -> ScenarioVpnCondition.DISCONNECTED
            else -> ScenarioVpnCondition.ANY
        }
        return ScenarioProfile(
            id = json.optString("id").ifBlank { return missingId() },
            name = json.optString("name"),
            enabled = json.optBoolean("enabled", true),
            priority = json.optInt("priority", 100),
            trigger = trigger,
            vpnCondition = vpnCondition,
            bypassBaseRules = json.optBoolean("bypassBaseRules", false),
            overrides = overrides
        )
    }

    /** A profile without an id cannot be addressed by the UI, so it is treated as unparseable. */
    private fun missingId(): Nothing = throw IllegalArgumentException("profile without id")
}

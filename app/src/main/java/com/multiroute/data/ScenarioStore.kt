package com.multiroute.data

import android.content.Context
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
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

    private const val PREFS = "multiroute_scenarios"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_MANUAL = "manual_id"

    /** Trigger kinds, written to JSON so the format stays readable and forward-compatible. */
    private const val KIND_ALWAYS = "always"
    private const val KIND_MANUAL = "manual"
    private const val KIND_SSID = "ssid"
    private const val KIND_LINK_COUNT = "wifi_links"
    private const val KIND_CELLULAR_ONLY = "cellular_only"

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
        if (getManualId(context) == id) setManualId(context, null)
    }

    /** Id of the profile the user pinned by hand, or empty when selection is automatic. */
    fun getManualId(context: Context): String = prefs(context).getString(KEY_MANUAL, "") ?: ""

    fun setManualId(context: Context, id: String?) {
        if (id.isNullOrEmpty()) prefs(context).edit().remove(KEY_MANUAL).apply()
        else prefs(context).edit().putString(KEY_MANUAL, id).apply()
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
        return ScenarioProfile(
            id = json.optString("id").ifBlank { return missingId() },
            name = json.optString("name"),
            enabled = json.optBoolean("enabled", true),
            priority = json.optInt("priority", 100),
            trigger = trigger,
            overrides = overrides
        )
    }

    /** A profile without an id cannot be addressed by the UI, so it is treated as unparseable. */
    private fun missingId(): Nothing = throw IllegalArgumentException("profile without id")
}

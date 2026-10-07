package com.multiroute.data

import android.content.Context
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.util.RouteRuleBuilder
import com.multiroute.util.SuHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Whole-configuration export and import: one JSON document holding the base per-app assignments, every
 * scenario plan and the settings that change routing behaviour.
 *
 * Import **replaces** the configuration rather than merging - that is what restoring a backup means - but it
 * writes the current configuration to a timestamped file first and reports what it ignored, so importing the
 * wrong file cannot quietly destroy a working setup. Assignments keep their rule keys, so a clone-space
 * install travels with the primary one; entries pointing at apps or channels that do not exist on this
 * device are counted as skipped instead of being applied.
 */
object ConfigTransfer {

    const val FORMAT = "multiroute-config"
    const val VERSION = 1

    /** Where the root-shell export writes, so the file is reachable from any file manager. */
    const val EXPORT_DIR = "/sdcard/Download"

    data class ImportResult(
        val assignments: Int,
        val scenarios: Int,
        val skipped: Int,
        val settings: Int,
        /** Timestamped copy of what was replaced, or null when it could not be written. */
        val backupPath: String?
    )

    /** `20261007-193045`, so several exports on one day do not overwrite each other. */
    fun timestamp(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        return fmt.format(java.util.Date(epochMillis))
    }

    fun exportFileName(epochMillis: Long): String = "MultiRoute-config-${timestamp(epochMillis)}.json"

    fun exportPath(epochMillis: Long): String = "$EXPORT_DIR/${exportFileName(epochMillis)}"

    /**
     * Drops assignment entries this build cannot route: an invalid rule key, an empty channel or a channel
     * that is not a plausible interface name. Pure, so the rules are unit-tested instead of trusted.
     */
    fun normalizeAssignments(raw: Map<String, String>): Map<String, String> =
        raw.filter { (key, channel) ->
            RouteConfigProvider.isValidRuleKey(key) &&
                    channel.isNotEmpty() &&
                    channel != CHANNEL_DEFAULT &&
                    RouteRuleBuilder.isValidInterfaceName(channel)
        }

    /** Serializes the live configuration. */
    fun buildJson(context: Context, now: Long): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", now)

        val assignments = JSONObject()
        RouteConfigProvider.getAllRules(context).forEach { (key, channel) -> assignments.put(key, channel) }
        root.put("assignments", assignments)

        root.put("scenarios", ScenarioStore.encodeProfiles(ScenarioStore.load(context)))

        val settings = JSONObject()
        settings.put("dnsFollowsChannel", RouteConfigProvider.isDnsFollowsChannel(context))
        settings.put("keepSlaveWifiScreenOff", RouteConfigProvider.isKeepSlaveWifiScreenOff(context))
        settings.put("manualScenarioId", ScenarioStore.getManualId(context))
        root.put("settings", settings)

        return root.toString(2)
    }

    /**
     * Validates and applies [json]. Returns null when the document is not a MultiRoute configuration (or
     * comes from a newer format version); otherwise the counts plus the backup path.
     */
    fun apply(context: Context, json: String, now: Long): ImportResult? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        if (root.optString("format") != FORMAT) return null
        val version = root.optInt("version", 0)
        if (version !in 1..VERSION) return null

        val rawAssignments = mutableMapOf<String, String>()
        root.optJSONObject("assignments")?.let { obj ->
            obj.keys().forEach { key -> rawAssignments[key] = obj.optString(key) }
        }
        val assignments = normalizeAssignments(rawAssignments)
        val skipped = rawAssignments.size - assignments.size

        val scenarios = ScenarioStore.parseProfiles(root.optJSONArray("scenarios") ?: JSONArray())
        val settings = root.optJSONObject("settings")

        // Back up first: the next few lines are destructive, and a backup is what makes a wrong file
        // recoverable rather than fatal.
        val backupFile = java.io.File(context.filesDir, "config-backup-${timestamp(now)}.json")
        val backupPath = try {
            backupFile.writeText(buildJson(context, now))
            backupFile.absolutePath
        } catch (_: Exception) {
            null
        }

        RouteConfigProvider.clearAllRules(context)

        // One write per channel instead of one per app: the store writes both the rule key and the UID key.
        var secondaryInstalls: List<RouteRuleBuilder.SecondaryUserInstalls>? = null
        var applied = 0
        assignments.entries.groupBy({ it.value }, { it.key }).forEach { (channel, keys) ->
            if (keys.any { it.substringAfter('@', "").isNotEmpty() } && secondaryInstalls == null) {
                secondaryInstalls = runCatching { SuHelper.listSecondaryUserInstalls() }.getOrDefault(emptyList())
            }
            val targets = keys.map { key ->
                RuleTarget(key, SuHelper.resolveUidForRuleKey(context, key, secondaryInstalls))
            }
            RouteConfigProvider.setTargetChannels(context, targets, channel)
            applied += targets.size
        }

        ScenarioStore.save(context, scenarios)

        var appliedSettings = 0
        settings?.let { s ->
            if (s.has("dnsFollowsChannel")) {
                RouteConfigProvider.setDnsFollowsChannel(context, s.optBoolean("dnsFollowsChannel"))
                appliedSettings++
            }
            if (s.has("keepSlaveWifiScreenOff")) {
                RouteConfigProvider.setKeepSlaveWifiScreenOff(context, s.optBoolean("keepSlaveWifiScreenOff"))
                appliedSettings++
            }
            if (s.has("manualScenarioId")) {
                ScenarioStore.setManualId(context, s.optString("manualScenarioId"))
                appliedSettings++
            }
        }

        return ImportResult(
            assignments = applied,
            scenarios = scenarios.size,
            skipped = skipped,
            settings = appliedSettings,
            backupPath = backupPath
        )
    }
}

package com.multiroute.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Test

/**
 * The pure part of the configuration export/import: what counts as a routable assignment, and the names the
 * exported files get. The JSON plumbing itself is exercised on device.
 */
class ConfigTransferTest {

    @Test
    fun testNormalizeAssignmentsKeepsOnlyRoutableEntries() {
        val raw = mapOf(
            "com.example.app" to "wlan1",
            "com.example.clone@999" to "wlan0",
            "" to "wlan1",                                  // no rule key
            "com.example.nochannel" to "",                  // no channel
            "com.example.forcedefault" to "default",        // not an assignment
            "not a package!" to "wlan1",                    // invalid package
            "com.example.oddchannel" to "this-name-is-too-long" // not a plausible interface
        )
        val normalized = ConfigTransfer.normalizeAssignments(raw)
        assertEquals(setOf("com.example.app", "com.example.clone@999"), normalized.keys)
        assertEquals("wlan1", normalized["com.example.app"])
        assertEquals("wlan0", normalized["com.example.clone@999"])
    }

    @Test
    fun testNormalizeAssignmentsOnEmptyInput() {
        assertTrue(ConfigTransfer.normalizeAssignments(emptyMap()).isEmpty())
    }

    @Test
    fun testExportFileNameAndPath() {
        val name = ConfigTransfer.exportFileName(1_700_000_000_000L)
        assertTrue("unexpected name: $name", Regex("^MultiRoute-config-\\d{8}-\\d{6}\\.json$").matches(name))
        assertEquals("${ConfigTransfer.EXPORT_DIR}/$name", ConfigTransfer.exportPath(1_700_000_000_000L))
        assertTrue(ConfigTransfer.EXPORT_DIR.startsWith("/sdcard/"))
    }

    @Test
    fun testTimestampShape() {
        assertTrue(Regex("^\\d{8}-\\d{6}$").matches(ConfigTransfer.timestamp(1_700_000_000_000L)))
    }

    private fun document(version: Int = 2) = JSONObject().put("format", ConfigTransfer.FORMAT).put("version", version)
    private fun profile(id: String) = JSONObject().put("id", id).put("name", id)
        .put("trigger", JSONObject().put("kind", "manual")).put("overrides", JSONObject())

    @Test
    fun settingsAndUidCachesAreNeverAssignments() {
        val raw = mapOf("dual_wifi_last_pass" to "wlan0", "dual_wifi_last_ssid" to "wlan1",
            "dual_wifi_scheme" to "auto", "uid_10267" to "wlan0", "theme_mode" to "dark", "com.example.app" to "wlan0")
        assertEquals(mapOf("com.example.app" to "wlan0"), ConfigTransfer.normalizeAssignments(raw))
        assertFalse(RouteConfigProvider.isValidRuleKey("com..app"))
        assertFalse(RouteConfigProvider.isValidRuleKey("com.app@1000"))
        assertTrue(RouteConfigProvider.isValidRuleKey("com.app@999"))
    }

    @Test
    fun v2RetainsAutomaticModeAndAllManualSelections() {
        val plans = org.json.JSONArray().put(profile("a")).put(profile("b"))
        val root = document().put("scenarios", JSONObject().put("profiles", plans).put("mode", "auto")
            .put("manualIds", org.json.JSONArray(listOf("a", "b"))))
        val parsed = ConfigTransfer.parse(root.toString())!!
        assertEquals("auto", parsed.mode)
        assertEquals(setOf("a", "b"), parsed.manualIds)
        assertEquals(setOf(ConfigTransfer.Section.SCENARIOS), parsed.sections)
    }

    @Test
    fun v1SinglePinMigratesToManualMode() {
        val root = document(1).put("assignments", JSONObject())
            .put("scenarios", org.json.JSONArray().put(profile("a")))
            .put("settings", JSONObject().put("manualScenarioId", "a"))
        val parsed = ConfigTransfer.parse(root.toString())!!
        assertEquals("manual", parsed.mode)
        assertEquals(setOf("a"), parsed.manualIds)
        assertFalse(ConfigTransfer.Section.APPEARANCE in parsed.sections)
        assertFalse(ConfigTransfer.Section.DUAL_WIFI in parsed.sections)
    }

    @Test
    fun v1EmptyOrDeletedPinMigratesToAutomatic() {
        for (id in listOf("", "deleted")) {
            val root = document(1).put("scenarios", org.json.JSONArray())
                .put("settings", JSONObject().put("manualScenarioId", id))
            val parsed = ConfigTransfer.parse(root.toString())!!
            assertEquals("auto", parsed.mode)
            assertTrue(parsed.manualIds.isEmpty())
        }
    }

    @Test
    fun invalidTypesAndFutureVersionsRejectWholeDocument() {
        val bad = listOf(
            document().put("assignments", "wrong"),
            document().put("assignments", JSONObject().put("com.app", 123)),
            document().put("settings", JSONObject().put("autoFailoverEnabled", "false")),
            document().put("settings", JSONObject().put("failoverHoldOffSeconds", 0)),
            document().put("settings", JSONObject().put("failoverHoldOffSeconds", 1.5)),
            document(999).put("assignments", JSONObject()), document(),
            document().put("appearance", JSONObject().put("themeMode", "dark"))
        )
        bad.forEach { assertNull(it.toString(), ConfigTransfer.parse(it.toString())) }
    }

    @Test
    fun invalidScenarioDoesNotBecomeAnEmptyReplacement() {
        val duplicate = org.json.JSONArray().put(profile("a")).put(profile("a"))
        val unknownTrigger = org.json.JSONArray().put(profile("a").put("trigger", JSONObject().put("kind", "new-kind")))
        for (plans in listOf(duplicate, unknownTrigger)) {
            val root = document().put("scenarios", JSONObject().put("profiles", plans)
                .put("mode", "auto").put("manualIds", org.json.JSONArray()))
            assertNull(ConfigTransfer.parse(root.toString()))
        }
    }

    @Test
    fun missingSelectionIsRejectedInsteadOfSilentlyLosingIt() {
        val root = document().put("scenarios", JSONObject().put("profiles", org.json.JSONArray())
            .put("mode", "manual").put("manualIds", org.json.JSONArray(listOf("missing"))))
        assertNull(ConfigTransfer.parse(root.toString()))
    }

    @Test
    fun legacySettingsAreIgnoredEvenWhenTheirValuesLookLikeInterfaces() {
        val parsed = ConfigTransfer.parse(document(1).put("assignments", JSONObject()
            .put("dual_wifi_last_pass", "wlan0").put("com.app", "wlan1")).toString())!!
        assertEquals(mapOf("com.app" to "wlan1"), parsed.assignments)
        assertEquals(1, parsed.ignored)
    }

    @Test
    fun partialAppearanceHasNoImplicitRoutingSections() {
        val parsed = ConfigTransfer.parse(document().put("appearance", JSONObject()
            .put("themeMode", "dark").put("dynamicColor", false).put("language", "en")).toString())!!
        assertEquals(setOf(ConfigTransfer.Section.APPEARANCE), parsed.sections)
        assertEquals("dark", parsed.rulesSettings.getValue(ConfigTransfer.Section.APPEARANCE)[RouteConfigProvider.KEY_THEME_MODE])
    }

    @Test
    fun unsafeHotspotTargetsAndUrlCredentialsAreRejected() {
        assertNull(ConfigTransfer.parse(document().put("hotspot", JSONObject().put("upstream", "wlan0;id")).toString()))
        assertNull(ConfigTransfer.parse(document().put("testServer", JSONObject()
            .put("presetId", "custom").put("customUrl", "https://user:secret@example.com/ip")).toString()))
    }

    @Test
    fun deviceSpecificSchemeCannotCrossManufacturers() {
        assertTrue(ConfigTransfer.isSchemeCompatible("auto", "Xiaomi", "Google"))
        assertFalse(ConfigTransfer.isSchemeCompatible("xiaomi_slave", "Xiaomi", "Google"))
        assertFalse(ConfigTransfer.isSchemeCompatible("xiaomi_slave", "Google", "Google"))
        assertTrue(ConfigTransfer.isSchemeCompatible("xiaomi_slave", "Xiaomi", "Xiaomi"))
    }
}

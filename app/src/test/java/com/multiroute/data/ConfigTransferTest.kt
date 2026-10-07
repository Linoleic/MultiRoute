package com.multiroute.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}

package com.multiroute.data

import org.junit.Assert.*
import org.junit.Test

class PreferenceTransactionTest {
    private fun write(store: MutableMap<String, Any?>, values: Map<String, Any?>): Boolean {
        values.forEach { (key, value) -> if (value == null) store.remove(key) else store[key] = value }
        return true
    }

    @Test fun appearanceImportPreservesRequestRecordedAfterSnapshot() {
        val snapshot = mapOf("theme" to "system", "ssid" to "AP")
        val patch = PreferenceTransaction.changesBetween(snapshot, snapshot + ("theme" to "dark"))
        val current = snapshot.toMutableMap<String, Any?>().apply { put("shell_request", "AP_2g_internet") }
        PreferenceTransaction(mapOf("rules" to patch)).apply({ current }) { _, values -> write(current, values) }
        assertEquals("dark", current["theme"])
        assertEquals("AP_2g_internet", current["shell_request"])
        assertEquals("AP", current["ssid"])
    }

    @Test fun removalAndAdditionOnlyAffectImportedKeys() {
        val snapshot = mapOf("old.app" to "wlan0", "theme" to "dark")
        val next = mapOf("new.app" to "wlan1", "theme" to "dark")
        val current = snapshot.toMutableMap<String, Any?>().apply { put("runtime", "new") }
        val transaction = PreferenceTransaction(mapOf("rules" to PreferenceTransaction.changesBetween(snapshot, next)))
        transaction.apply({ current }) { _, values -> write(current, values) }
        assertFalse(current.containsKey("old.app"))
        assertEquals("wlan1", current["new.app"])
        assertEquals("new", current["runtime"])
    }

    @Test fun failedSecondCommitRollsBackAttemptedKeysAndPreservesNewRuntimeValues() {
        val stores = mapOf("rules" to mutableMapOf<String, Any?>("theme" to "system"),
            "scenarios" to mutableMapOf<String, Any?>("mode" to "auto"))
        val transaction = PreferenceTransaction(linkedMapOf("rules" to mapOf("theme" to "dark"),
            "scenarios" to mapOf("mode" to "manual", "new_key" to "value")))
        try {
            transaction.apply({ stores.getValue(it) }) { name, values ->
                write(stores.getValue(name), values)
                name != "scenarios"
            }
            fail("Expected failed commit")
        } catch (_: IllegalStateException) { }
        stores.getValue("rules")["shell_request"] = "latest"
        assertTrue(transaction.rollback { name, values -> write(stores.getValue(name), values) })
        assertEquals(mapOf("theme" to "system", "shell_request" to "latest"), stores.getValue("rules"))
        assertEquals(mapOf("mode" to "auto"), stores.getValue("scenarios"))
    }

    @Test fun rollbackUsesValuesAtCommitInsteadOfAnOlderPreview() {
        val patch = PreferenceTransaction.changesBetween(mapOf("theme" to "system"), mapOf("theme" to "light"))
        val current = mutableMapOf<String, Any?>("theme" to "dark")
        val transaction = PreferenceTransaction(mapOf("rules" to patch))
        transaction.apply({ current }) { _, values -> write(current, values) }
        assertTrue(transaction.rollback { _, values -> write(current, values) })
        assertEquals("dark", current["theme"])
    }

    @Test fun rollbackContinuesAfterAnIndividualStoreFails() {
        val current = mutableMapOf<String, Any?>("key" to "old")
        val transaction = PreferenceTransaction(linkedMapOf("first" to mapOf("key" to "a"), "second" to mapOf("key" to "b")))
        transaction.apply({ current }) { _, values -> write(current, values) }
        val visited = mutableListOf<String>()
        assertFalse(transaction.rollback { name, _ ->
            visited += name
            if (name == "second") error("write failure")
            true
        })
        assertEquals(listOf("second", "first"), visited)
    }
}

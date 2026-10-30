package com.multiroute.cellular

import com.multiroute.data.ConfigTransfer
import com.multiroute.data.RouteConfigProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CellularTargetsTest {
    private val sim = "sim_" + "a".repeat(64)
    @Test fun acceptsOpaqueHexIccidsAndRejectsUnavailableOrMaskedIdentity() {
        assertEquals("89ABCDEF012345678901", CellularInventory.normalizedIccid("89abcdef012345678901F"))
        assertNull(CellularInventory.normalizedIccid("89xxxxxxxxxxxxxxxxxx"))
        assertNull(CellularInventory.normalizedIccid("00000000000000000000"))
        assertNull(CellularInventory.normalizedIccid(null))
    }
    @Test fun resolvesIdentityAgainAfterInterfaceChangesAndNeverGuessesAnotherSim() {
        assertEquals("rmnet_data4", CellularTargets.resolve(sim, mapOf(sim to listOf("rmnet_data4"))))
        assertEquals("rmnet_data7", CellularTargets.resolve(sim, mapOf(sim to listOf("rmnet_data7"))))
        assertNull(CellularTargets.resolve(sim, emptyMap()))
        assertNull(CellularTargets.resolve(sim, mapOf(sim to listOf("rmnet_data4", "rmnet_data7"))))
        assertEquals("wlan1", CellularTargets.resolve("wlan1", emptyMap()))
    }
    @Test fun configurationRetainsDeviceScopedTargetsWithoutChangingLegacyInterfaceRules() {
        assertTrue(RouteConfigProvider.isValidChannelId(sim))
        assertFalse(RouteConfigProvider.isValidChannelId("sim_" + "a".repeat(63)))
        val rules = mapOf("com.example.app" to sim, "com.example.old" to "rmnet_data4")
        assertEquals(rules, ConfigTransfer.normalizeAssignments(rules))
        val json = JSONObject().put("format", ConfigTransfer.FORMAT).put("version", 3)
            .put("assignments", JSONObject(rules)).toString()
        assertEquals(rules, ConfigTransfer.parse(json)!!.assignments)
    }
}

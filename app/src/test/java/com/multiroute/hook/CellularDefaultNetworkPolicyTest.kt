package com.multiroute.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class CellularDefaultNetworkPolicyTest {
    @Test fun keepsTwoSimAssignmentsSeparateAndLeavesUnassignedUidsAlone() {
        val groups = CellularDefaultNetworkPolicy.groups(setOf(10596, 10698, 10700, 10701), mapOf(
            10596 to "rmnet_data2", 10698 to "rmnet_data4", 10700 to "wlan0",
            10702 to "rmnet_data2", 10701 to "default"))
        assertEquals(mapOf("rmnet_data2" to setOf(10596), "rmnet_data4" to setOf(10698)), groups)
    }

    @Test fun switchingInterfaceMovesTheSameUidAndClearingRemovesIt() {
        val uids = setOf(10596)
        assertEquals(mapOf("rmnet_data4" to uids),
            CellularDefaultNetworkPolicy.groups(uids, mapOf(10596 to "rmnet_data4")))
        assertEquals(emptyMap<String, Set<Int>>(), CellularDefaultNetworkPolicy.groups(uids, emptyMap()))
    }

    @Test fun rejectsSystemUidsAndInvalidInterfaceNames() {
        assertEquals(emptyMap<String, Set<Int>>(), CellularDefaultNetworkPolicy.groups(
            setOf(1000, 10596), mapOf(1000 to "rmnet_data2", 10596 to "rmnet_data2;reboot")))
    }
}

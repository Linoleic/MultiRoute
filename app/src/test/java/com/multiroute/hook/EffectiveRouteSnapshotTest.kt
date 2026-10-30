package com.multiroute.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveRouteSnapshotTest {
    @Test fun scenarioAndOnlineSnapshotWinsOverNonEmptyBaseAssignments() {
        val base = mapOf(10596 to "rmnet_data2", 10698 to "rmnet_data2")
        assertEquals(mapOf(10596 to "rmnet_data4"),
            EffectiveRouteSnapshot.resolve("10596 rmnet_data4\n", base))
        assertEquals(emptyMap<Int, String>(), EffectiveRouteSnapshot.resolve("\n", base))
    }

    @Test fun baseIsOnlyUsedBeforeTheFirstSnapshotExists() {
        assertEquals(mapOf(10596 to "rmnet_data2"), EffectiveRouteSnapshot.resolve(null,
            mapOf(10596 to "rmnet_data2", 1000 to "rmnet_data2", 10698 to "bad;cmd")))
    }

    @Test fun scenarioCanIntroduceCellularSelectionWithoutChangingTheBase() {
        val base = mapOf(10596 to "wlan0")
        val effective = EffectiveRouteSnapshot.resolve("10596 rmnet_data4", base)
        assertEquals(mapOf("rmnet_data4" to setOf(10596)),
            CellularDefaultNetworkPolicy.groups(setOf(10596), effective))
        assertEquals(mapOf(10596 to "wlan0"), base)
    }
}

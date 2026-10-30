package com.multiroute.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveRouteSnapshotTest {
    @Test fun legacyInterfaceAssignmentsStillWorkAtBootWhileSimBindingsAwaitDiscovery() {
        val snapshot = "#boot:12\n#dynamic:10596\n10596 rmnet_data4\n10698 wlan1\n"
        assertEquals(mapOf(10698 to "wlan1"), EffectiveRouteSnapshot.resolve(snapshot, emptyMap(), 13))
        assertEquals(mapOf(10596 to "rmnet_data4", 10698 to "wlan1"), EffectiveRouteSnapshot.resolve(snapshot, emptyMap(), 12))
        assertEquals(setOf(10596), EffectiveRouteSnapshot.suspendedSimUids(snapshot, 13))
        assertTrue(EffectiveRouteSnapshot.suspendedSimUids(snapshot, 12).isEmpty())
    }
    @Test fun previousBootSnapshotCannotPinAReusedInterface() {
        val snapshot = "#boot:12\n10596 rmnet_data4\n"
        val base = mapOf(10596 to "rmnet_data3")
        assertEquals(mapOf(10596 to "rmnet_data4"), EffectiveRouteSnapshot.resolve(snapshot, base, 12))
        assertTrue(EffectiveRouteSnapshot.resolve(snapshot, base, 13).isEmpty())
        assertTrue(EffectiveRouteSnapshot.resolve(snapshot, base).isEmpty())
    }
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

package com.multiroute.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class CellularNetworkSelectionTest {
    private data class Agent(val netId: Int, val subId: Int, val iface: String)

    @Test fun identicalSubscriptionSelectorsStillKeepDifferentBearersSeparate() {
        val networks = listOf(Agent(101, 2, "rmnet_data2"), Agent(102, 2, "rmnet_data4"))
        assertEquals(listOf(networks[1]), CellularNetworkSelection.candidates(102, networks) { it.netId })
    }

    @Test fun reusedInterfaceAndSubscriptionDoNotResurrectADeadNetwork() {
        val networks = listOf(Agent(103, 2, "rmnet_data4"))
        assertEquals(emptyList<Agent>(), CellularNetworkSelection.candidates(102, networks) { it.netId })
        assertEquals(networks, CellularNetworkSelection.candidates(103, networks) { it.netId })
    }
}

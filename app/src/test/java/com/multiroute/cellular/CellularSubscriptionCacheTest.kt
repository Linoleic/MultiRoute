package com.multiroute.cellular

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellularSubscriptionCacheTest {
    private val sim = CellularSubscription(
        subId = 2, slot = 1, carrier = "Carrier", key = "sim_" + "a".repeat(64),
        isDefault = false, networks = mapOf("102" to "rmnet_data3")
    )

    @Test fun transientFailureKeepsOnlyMatchingLiveNetworkIdentity() {
        val cache = CellularSubscriptionCache { 0L }
        cache.read(useLastConfirmed = false) { listOf(sim) }
        cache.invalidate()

        val fallback = cache.read(useLastConfirmed = true) { null }
        assertEquals(sim, fallback.subscriptionForNetwork("102", "rmnet_data3"))
        assertNull(fallback.subscriptionForNetwork("103", "rmnet_data3"))
        assertNull(fallback.subscriptionForNetwork("102", "rmnet_data4"))
        assertEquals(emptyList<CellularSubscription>(), cache.read(useLastConfirmed = false) {
            error("The failed attempt is still within the cache TTL")
        })
    }

    @Test fun successfulEmptyInventoryClearsLastConfirmedNetworks() {
        val cache = CellularSubscriptionCache { 0L }
        cache.read(useLastConfirmed = true) { listOf(sim) }
        cache.invalidate()
        assertEquals(emptyList<CellularSubscription>(), cache.read(useLastConfirmed = true) { emptyList() })
        cache.invalidate()
        assertEquals(emptyList<CellularSubscription>(), cache.read(useLastConfirmed = true) { null })
    }

    @Test fun retryWindowStartsWhenTheReadFinishesAndRecoversNewNetworkIdentity() {
        var time = 0L
        val cache = CellularSubscriptionCache { time }
        cache.read(useLastConfirmed = true) { listOf(sim) }
        cache.invalidate()
        cache.read(useLastConfirmed = true) {
            time = 8_000L
            null
        }
        time = 10_000L
        assertEquals(listOf(sim), cache.read(useLastConfirmed = true) { error("Retried too early") })

        time = 11_000L
        val reconnected = sim.copy(networks = mapOf("103" to "rmnet_data4"))
        val refreshed = cache.read(useLastConfirmed = true) { listOf(reconnected) }
        assertNull(refreshed.subscriptionForNetwork("102", "rmnet_data3"))
        assertEquals(reconnected, refreshed.subscriptionForNetwork("103", "rmnet_data4"))
    }
}

package com.multiroute.wifi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SecondaryConnectionCoordinatorTest {
    @Test fun disconnectWhileConnectIsBlockedPreventsLateSuccessAndRetry() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val entered = CompletableDeferred<Unit>()
        val completeConnection = CompletableDeferred<Unit>()
        var requestPresent = false
        var savedSsid: String? = null
        var serviceRunning = false
        val ticket = coordinator.connect()
        val connect = launch {
            coordinator.serialized {
                requestPresent = true
                entered.complete(Unit)
                completeConnection.await()
                val accepted = coordinator.publish(ticket) { savedSsid = "AP"; serviceRunning = true }
                if (!accepted) requestPresent = false
            }
        }
        entered.await()
        val stop = coordinator.disconnect()
        assertNull(coordinator.retry())
        val disconnect = launch {
            coordinator.serialized {
                if (coordinator.isCurrent(stop)) {
                    savedSsid = null; serviceRunning = false; requestPresent = false
                    coordinator.disconnected(stop)
                }
            }
        }
        completeConnection.complete(Unit)
        joinAll(connect, disconnect)
        assertNull(savedSsid)
        assertFalse(serviceRunning)
        assertFalse(requestPresent)
        assertNull(coordinator.retry())
    }

    @Test fun delayedAutoRetryCannotOverrideDisconnectOrNewUserChoice() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val delayedRetry = coordinator.retry()!!
        val stop = coordinator.disconnect()
        coordinator.disconnected(stop)
        assertFalse(coordinator.publish(delayedRetry) { fail("Stale retry published") })
        assertNull(coordinator.retry())
        val selected = coordinator.connect()
        assertFalse(coordinator.publish(delayedRetry) { fail("Retry replaced new choice") })
        assertTrue(coordinator.publish(selected) { })
        assertEquals(selected, coordinator.retry())
    }

    @Test fun queuedDisconnectCannotTearDownANewerConnection() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val stopped = coordinator.disconnect()
        val selected = coordinator.connect()
        var serviceRunning = false
        coordinator.serialized { coordinator.publish(selected) { serviceRunning = true } }
        coordinator.serialized {
            if (coordinator.isCurrent(stopped)) serviceRunning = false
            coordinator.disconnected(stopped)
        }
        assertTrue(serviceRunning)
        assertEquals(selected, coordinator.retry())
    }

    @Test fun temporaryRadioYieldOnlyAllowsRetryAfterCleanup() {
        val coordinator = SecondaryConnectionCoordinator()
        val previous = coordinator.connect()
        val yield = coordinator.disconnect(suppressReconnect = false)
        assertFalse(coordinator.isCurrent(previous))
        assertNull(coordinator.retry())
        coordinator.disconnected(yield)
        assertEquals(yield, coordinator.retry())
    }
}

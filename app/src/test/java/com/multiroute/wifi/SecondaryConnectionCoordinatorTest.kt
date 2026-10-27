package com.multiroute.wifi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
        coordinator.withConnection(selected) { assertTrue(coordinator.publish(selected) { }) }
        assertEquals(selected, coordinator.retry())
    }

    @Test fun queuedDisconnectCannotTearDownANewerConnection() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val stopped = coordinator.disconnect()
        val selected = coordinator.connect()
        var serviceRunning = false
        coordinator.withConnection(selected) {
            coordinator.serialized { coordinator.publish(selected) { serviceRunning = true } }
        }
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

    @Test fun monitorAndHotspotRetriesShareOneReservationUntilPreflightCompletes() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val monitor = coordinator.retry()!!
        assertNull(coordinator.retry())
        coordinator.withConnection(monitor) {
            assertNull(coordinator.retry())
        }
        assertNotNull(coordinator.retry())
    }

    @Test fun userConnectionPreventsBackgroundRetryFromQueueingBehindIt() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val selected = coordinator.connect()
        assertNull(coordinator.retry())
        coordinator.withConnection(selected) {
            assertNull(coordinator.retry())
        }
        assertNotNull(coordinator.retry())
    }

    @Test fun cancellationWhileWaitingForRootLockReleasesRetryReservation() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val locked = CompletableDeferred<Unit>()
        val holder = launch {
            coordinator.serialized { locked.complete(Unit); awaitCancellation() }
        }
        locked.await()
        val retry = coordinator.retry()!!
        val entered = CompletableDeferred<Unit>()
        val queued = launch {
            coordinator.withConnection(retry) {
                entered.complete(Unit)
                coordinator.serialized { fail("Cancelled waiter acquired the root lock") }
            }
        }
        entered.await()
        queued.cancelAndJoin()
        assertNotNull(coordinator.retry())
        holder.cancelAndJoin()
    }

    @Test fun failedPreflightReleasesReservation() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val retry = coordinator.retry()!!
        try {
            coordinator.withConnection(retry) { error("Network lookup failed") }
            fail("Expected preflight failure")
        } catch (_: IllegalStateException) { }
        assertNotNull(coordinator.retry())
    }

    @Test fun staleConnectionCompletionCannotReleaseNewConnectionReservation() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val old = coordinator.retry()!!
        lateinit var selected: SecondaryConnectionCoordinator.Ticket
        coordinator.withConnection(old) { selected = coordinator.connect() }
        assertNull(coordinator.retry())
        coordinator.withConnection(selected) { }
        assertNotNull(coordinator.retry())
    }

    @Test fun delayedServiceStartAndNotificationCannotReviveOrDisconnectNewChoice() = runTest {
        val coordinator = SecondaryConnectionCoordinator()
        val old = coordinator.connect()
        coordinator.withConnection(old) { }
        assertEquals(old, coordinator.monitoringTicket())
        val stop = coordinator.disconnect()
        assertNull(coordinator.monitoringTicket())
        coordinator.disconnected(stop)
        assertNull(coordinator.monitoringTicket())
        val selected = coordinator.connect()
        assertNotEquals(old, coordinator.monitoringTicket())
        assertNull(coordinator.disconnectIfCurrent(old))
        assertEquals(selected, coordinator.monitoringTicket())
        assertNotNull(coordinator.disconnectIfCurrent(selected))
        assertNull(coordinator.monitoringTicket())
    }
}

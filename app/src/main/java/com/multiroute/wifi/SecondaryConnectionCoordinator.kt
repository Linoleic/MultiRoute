package com.multiroute.wifi

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** User intents are registered before dispatch; retries never create a newer user intent. */
internal class SecondaryConnectionCoordinator {
    data class Ticket(val generation: Long)

    private val intents = Any()
    private val operations = Mutex()
    private var generation = 0L
    private var retryAllowed = true
    private var disconnectPending = false
    private var connectionPending = false

    fun connect(): Ticket = synchronized(intents) {
        generation++
        retryAllowed = true
        disconnectPending = false
        connectionPending = true
        Ticket(generation)
    }

    fun retry(): Ticket? = synchronized(intents) {
        if (retryAllowed && !disconnectPending && !connectionPending) {
            connectionPending = true
            Ticket(generation)
        } else null
    }

    private fun connectionFinished(ticket: Ticket) = synchronized(intents) {
        if (ticket.generation == generation) connectionPending = false
    }

    /** Own the reservation across preflight, queueing and cancellation, not only root execution. */
    suspend fun <T> withConnection(ticket: Ticket, action: suspend () -> T): T = try {
        action()
    } finally {
        connectionFinished(ticket)
    }

    fun monitoringTicket(): Ticket? = synchronized(intents) {
        if (retryAllowed && !disconnectPending) Ticket(generation) else null
    }

    /** Notification actions from an older connection must not stop a newer user choice. */
    fun disconnectIfCurrent(ticket: Ticket): Ticket? = synchronized(intents) {
        if (ticket != monitoringTicket()) null else disconnect()
    }

    fun disconnect(suppressReconnect: Boolean = true): Ticket = synchronized(intents) {
        generation++
        retryAllowed = !suppressReconnect
        disconnectPending = true
        connectionPending = false
        Ticket(generation)
    }

    fun isCurrent(ticket: Ticket): Boolean = synchronized(intents) { ticket.generation == generation }

    /** Only short, non-suspending publications belong here; never run root commands under this lock. */
    fun publish(ticket: Ticket, action: () -> Unit): Boolean = synchronized(intents) {
        if (ticket.generation != generation) false else { action(); true }
    }

    fun disconnected(ticket: Ticket) = synchronized(intents) {
        if (ticket.generation == generation) disconnectPending = false
    }

    suspend fun <T> serialized(action: suspend () -> T): T = operations.withLock { action() }
}

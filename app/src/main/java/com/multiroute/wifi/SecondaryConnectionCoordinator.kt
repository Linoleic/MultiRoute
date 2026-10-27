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

    fun connect(): Ticket = synchronized(intents) {
        generation++
        retryAllowed = true
        disconnectPending = false
        Ticket(generation)
    }

    fun retry(): Ticket? = synchronized(intents) {
        if (retryAllowed && !disconnectPending) Ticket(generation) else null
    }

    fun disconnect(suppressReconnect: Boolean = true): Ticket = synchronized(intents) {
        generation++
        retryAllowed = !suppressReconnect
        disconnectPending = true
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

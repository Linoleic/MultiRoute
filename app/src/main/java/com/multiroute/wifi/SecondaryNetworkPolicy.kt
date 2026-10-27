package com.multiroute.wifi

/** Multi-Internet selects saved configurations; the Xiaomi service can provision a new one. */
internal object SecondaryNetworkPolicy {
    enum class Entry { CONNECT, PASSWORD, SYSTEM_SETTINGS }

    suspend fun canConnect(usesXiaomiService: Boolean, savedNetworkId: suspend () -> Int?): Boolean =
        usesXiaomiService || savedNetworkId()?.let { it >= 0 } == true

    suspend fun entry(
        usesXiaomiService: Boolean,
        isSecure: Boolean,
        hasPassword: Boolean,
        savedNetworkId: suspend () -> Int?
    ): Entry {
        if (savedNetworkId()?.let { it >= 0 } == true) return Entry.CONNECT
        if (!usesXiaomiService) return Entry.SYSTEM_SETTINGS
        return if (isSecure && !hasPassword) Entry.PASSWORD else Entry.CONNECT
    }
}

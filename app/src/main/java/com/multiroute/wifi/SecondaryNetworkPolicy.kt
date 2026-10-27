package com.multiroute.wifi

/** Multi-Internet selects saved configurations; the Xiaomi service can provision a new one. */
internal object SecondaryNetworkPolicy {
    suspend fun canConnect(usesXiaomiService: Boolean, savedNetworkId: suspend () -> Int?): Boolean =
        usesXiaomiService || savedNetworkId()?.let { it >= 0 } == true
}

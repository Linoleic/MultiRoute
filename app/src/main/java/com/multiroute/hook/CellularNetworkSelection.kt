package com.multiroute.hook

/** Restricts only module-owned requests. The platform still checks capabilities and network scores. */
internal object CellularNetworkSelection {
    fun <T, K> candidates(target: K, networks: Collection<T>, identity: (T) -> K?): List<T> =
        networks.filter { identity(it) == target }
}

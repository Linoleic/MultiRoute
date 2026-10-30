package com.multiroute.cellular

/** Device-scoped, opaque SIM identity. Runtime subscription IDs and interface names are not identities. */
object CellularTargets {
    fun isSim(value: String): Boolean = Regex("^sim_[0-9a-f]{64}$").matches(value)

    fun resolve(value: String, interfaces: Map<String, List<String>>): String? =
        if (isSim(value)) interfaces[value]?.distinct()?.singleOrNull() else value
}

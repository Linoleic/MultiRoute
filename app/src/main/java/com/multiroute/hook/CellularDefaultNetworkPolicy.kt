package com.multiroute.hook

import com.multiroute.util.RouteRuleBuilder

/** Only explicitly assigned cellular UIDs participate; unrelated system preferences stay intact. */
internal object CellularDefaultNetworkPolicy {
    fun isCellular(iface: String): Boolean =
        iface.startsWith("rmnet") || iface.startsWith("ccmni") || iface == "cellular"

    fun groups(platformUids: Set<Int>, routes: Map<Int, String>): Map<String, Set<Int>> =
        routes.entries.filter { (uid, iface) ->
            uid in platformUids && RouteRuleBuilder.isValidUid(uid) &&
                RouteRuleBuilder.isValidInterfaceName(iface) && isCellular(iface)
        }.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }
}

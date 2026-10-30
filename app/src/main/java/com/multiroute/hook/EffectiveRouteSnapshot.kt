package com.multiroute.hook

import com.multiroute.util.RouteRuleBuilder

/** A published empty snapshot means no effective routes; only absence permits legacy preferences. */
internal object EffectiveRouteSnapshot {
    fun resolve(published: String?, preferences: Map<Int, String>): Map<Int, String> =
        if (published != null) RouteRuleBuilder.parseRuleCache(published)
        else preferences.filter { (uid, iface) ->
            RouteRuleBuilder.isValidUid(uid) && RouteRuleBuilder.isValidInterfaceName(iface)
        }
}

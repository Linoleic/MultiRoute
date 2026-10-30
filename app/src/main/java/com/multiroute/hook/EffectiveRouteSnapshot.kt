package com.multiroute.hook

import com.multiroute.util.RouteRuleBuilder

/** A published empty snapshot means no effective routes; only absence permits legacy preferences. */
internal object EffectiveRouteSnapshot {
    fun suspendedSimUids(published: String?, bootCount: Int): Set<Int> {
        if (published == null || !published.startsWith("#boot:") ||
            (bootCount >= 0 && published.lineSequence().first().removePrefix("#boot:").toIntOrNull() == bootCount)) return emptySet()
        return published.lineSequence().firstOrNull { it.startsWith("#dynamic:") }
            ?.removePrefix("#dynamic:")?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.filter(RouteRuleBuilder::isValidUid)?.toSet() ?: emptySet()
    }
    fun resolve(published: String?, preferences: Map<Int, String>, bootCount: Int = -1): Map<Int, String> =
        if (published != null && published.startsWith("#boot:") &&
            (bootCount < 0 || published.lineSequence().first().removePrefix("#boot:").toIntOrNull() != bootCount)) {
            val dynamic = published.lineSequence().firstOrNull { it.startsWith("#dynamic:") }
            if (dynamic == null) emptyMap() else {
                val ids = dynamic.removePrefix("#dynamic:").split(',').mapNotNull { it.toIntOrNull() }.toSet()
                RouteRuleBuilder.parseRuleCache(published).filterKeys { it !in ids }
            }
        }
        else if (published != null) RouteRuleBuilder.parseRuleCache(published)
        else preferences.filter { (uid, iface) ->
            RouteRuleBuilder.isValidUid(uid) && RouteRuleBuilder.isValidInterfaceName(iface)
        }
}

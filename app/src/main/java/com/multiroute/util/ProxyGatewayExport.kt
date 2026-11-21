package com.multiroute.util

import org.json.JSONArray
import org.json.JSONObject

internal enum class ProxyGatewayFormat(val label: String, val fileName: String, val mimeType: String) {
    SING_BOX("sing-box", "MultiRoute-sing-box-routes.json", "application/json"),
    MIHOMO("Mihomo", "MultiRoute-mihomo-rules.yaml", "application/yaml")
}

/** Builds an opt-in rule fragment for outbounds already defined in the proxy. */
internal object ProxyGatewayExport {
    data class Fragment(
        val content: String,
        val uidCount: Int,
        val outboundCount: Int,
        val directInterfaces: Set<String>
    )
    class InvalidMihomoOutbound(val tag: String) : IllegalArgumentException()

    fun build(
        publishedRules: Map<Int, String>,
        installedRules: Map<Int, String>,
        physicalOutbounds: Map<String, String>,
        format: ProxyGatewayFormat = ProxyGatewayFormat.SING_BOX,
        defaultRouteInterfaces: Map<Int, String> = emptyMap()
    ): Fragment? {
        val physical = publishedRules.filter { (uid, iface) -> installedRules[uid] == iface }
        val viaDefault = defaultRouteInterfaces.filterKeys { it !in publishedRules && it !in installedRules }
        val byOutbound = sortedMapOf<String, MutableList<Int>>()
        val directInterfaces = mutableSetOf<String>()
        for ((uid, iface) in physical + viaDefault) {
            if (!RouteRuleBuilder.isValidUid(uid)) continue
            val outbound = physicalOutbounds[iface] ?: continue
            byOutbound.getOrPut(outbound) { mutableListOf() }.add(uid)
            if (uid in physical) directInterfaces.add(iface)
        }
        if (byOutbound.isEmpty()) return null

        val content = when (format) {
            ProxyGatewayFormat.SING_BOX -> singBoxRules(byOutbound)
            ProxyGatewayFormat.MIHOMO -> mihomoRules(byOutbound)
        }
        return Fragment(content, byOutbound.values.sumOf { it.size }, byOutbound.size, directInterfaces)
    }

    private fun singBoxRules(byOutbound: Map<String, List<Int>>): String {
        val rules = JSONArray()
        byOutbound.forEach { (outbound, uids) ->
            rules.put(JSONObject()
                .put("user_id", JSONArray(uids.sorted()))
                .put("action", "route")
                .put("outbound", outbound))
        }
        return JSONObject().put("route", JSONObject().put("rules", rules)).toString(2)
    }

    private fun mihomoRules(byOutbound: Map<String, List<Int>>): String = buildString {
        appendLine("rules:")
        byOutbound.forEach { (outbound, uids) ->
            // Mihomo splits rule fields on commas and trims the outbound field.
            if (outbound.isBlank() || outbound != outbound.trim() || outbound.any { it == ',' || it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
                throw InvalidMihomoOutbound(outbound)
            }
            for (uid in uids.sorted()) {
                append("  - ")
                appendLine(JSONObject.quote("UID,$uid,$outbound").replace("\\/", "/"))
            }
        }
    }
}

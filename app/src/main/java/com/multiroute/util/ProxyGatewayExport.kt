package com.multiroute.util

import org.json.JSONArray
import org.json.JSONObject

internal enum class ProxyGatewayFormat(val label: String, val fileName: String, val mimeType: String) {
    SING_BOX("sing-box", "MultiRoute-sing-box-routes.json", "application/json"),
    MIHOMO("Mihomo", "MultiRoute-mihomo-rules.yaml", "application/yaml"),
    MIHOMO_ANDROID_VPN("Mihomo Android VPN", "MultiRoute-android-vpn-rules.yaml", "application/yaml")
}

/** Builds an opt-in rule fragment for outbounds already defined in the proxy. */
internal object ProxyGatewayExport {
    data class PlannedRule(
        val uid: Int,
        val interfaceName: String,
        val outbound: String,
        val isPhysicalAssignment: Boolean
    )

    data class Fragment(
        val content: String,
        val uidCount: Int,
        val outboundCount: Int,
        val directInterfaces: Set<String>,
        /** Exact rows represented by [content], in the same deterministic order. */
        val rules: List<PlannedRule> = emptyList()
    )
    class InvalidMihomoOutbound(val tag: String) : IllegalArgumentException()

    fun build(
        publishedRules: Map<Int, String>,
        installedRules: Map<Int, String>,
        physicalOutbounds: Map<String, String>,
        format: ProxyGatewayFormat = ProxyGatewayFormat.SING_BOX,
        defaultRouteInterfaces: Map<Int, String> = emptyMap()
    ): Fragment? {
        val physical = if (format == ProxyGatewayFormat.MIHOMO_ANDROID_VPN) emptyMap()
        else publishedRules.filter { (uid, iface) -> installedRules[uid] == iface }
        val viaDefault = defaultRouteInterfaces.filterKeys { it !in publishedRules && it !in installedRules }
        val byOutbound = sortedMapOf<String, MutableList<Int>>()
        val directInterfaces = mutableSetOf<String>()
        val planned = mutableListOf<PlannedRule>()
        for ((uid, iface) in physical + viaDefault) {
            if (!RouteRuleBuilder.isValidUid(uid)) continue
            val outbound = physicalOutbounds[iface] ?: continue
            byOutbound.getOrPut(outbound) { mutableListOf() }.add(uid)
            val isPhysical = uid in physical
            if (isPhysical) directInterfaces.add(iface)
            planned += PlannedRule(uid, iface, outbound, isPhysical)
        }
        if (byOutbound.isEmpty()) return null

        val content = when (format) {
            ProxyGatewayFormat.SING_BOX -> singBoxRules(byOutbound)
            ProxyGatewayFormat.MIHOMO -> mihomoRules(byOutbound)
            ProxyGatewayFormat.MIHOMO_ANDROID_VPN -> androidVpnRules(byOutbound, planned)
        }
        return Fragment(
            content,
            byOutbound.values.sumOf { it.size },
            byOutbound.size,
            directInterfaces,
            planned.sortedWith(compareBy<PlannedRule> { it.outbound }.thenBy { it.uid })
        )
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

    private fun androidVpnRules(byOutbound: Map<String, List<Int>>, planned: List<PlannedRule>): String = buildString {
        val rules = mihomoRules(byOutbound)
        appendLine("# Requires a compatible Android VPN client.")
        appendLine("# Set interface-name on each existing outbound before adding these rules:")
        for ((outbound, entries) in planned.groupBy { it.outbound }.toSortedMap()) {
            val iface = entries.map { it.interfaceName }.distinct().singleOrNull() ?: throw InvalidMihomoOutbound(outbound)
            require(Regex("""[a-zA-Z0-9_.-]{1,15}""").matches(iface))
            appendLine("# ${JSONObject.quote(outbound)} -> interface-name: android-vpn:$iface")
        }
        append(rules)
    }

    fun buildOutboundReference(fragment: Fragment, format: ProxyGatewayFormat): String? {
        val pairs = fragment.rules
            .map { it.outbound to it.interfaceName }
            .distinct()
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        if (pairs.isEmpty()) return null

        return when (format) {
            ProxyGatewayFormat.SING_BOX -> {
                val outbounds = JSONArray()
                for ((outbound, iface) in pairs) {
                    outbounds.put(JSONObject().put("tag", outbound).put("bind_interface", iface))
                }
                JSONObject().put("outbounds", outbounds).toString(2)
            }
            ProxyGatewayFormat.MIHOMO, ProxyGatewayFormat.MIHOMO_ANDROID_VPN -> buildString {
                appendLine(
                    if (format == ProxyGatewayFormat.MIHOMO_ANDROID_VPN) {
                        "# Set interface-name on each matching outbound in your Android VPN config or override:"
                    } else {
                        "# Set interface-name on each matching outbound in your proxy config or override:"
                    }
                )
                appendLine("proxies:")
                for ((outbound, iface) in pairs) {
                    val quoted = JSONObject.quote(outbound).replace("\\/", "/")
                    val boundIface = if (format == ProxyGatewayFormat.MIHOMO_ANDROID_VPN) {
                        "android-vpn:$iface"
                    } else iface
                    appendLine("  - name: $quoted")
                    appendLine("    interface-name: $boundIface")
                }
            }
        }
    }
}

package com.multiroute.util

import android.content.Context
import android.content.pm.PackageManager
import com.multiroute.model.NetworkChannel

internal object AndroidVpnGateway {
    private const val VERSION_KEY = "io.github.linoleic.multiroute.vpn_gateway_version"
    private val agentPrefix = Regex("""^\s*NetworkAgentInfo\{network\{\d+\}""")
    private val transport = Regex("""\bTransports:\s+(?:[A-Z0-9_]+\|)*VPN\b""")
    private val interfaceName = Regex("""\bInterfaceName:\s*([\w.-]+)""")
    private val owner = Regex("""\bOwnerUid:\s*(\d+)\b""")
    private val uidRanges = Regex("""\bUids:\s*<\{([0-9,\s-]*)\}>""")

    /** Clients advertise the protocol in their manifest. A package name alone cannot establish support. */
    fun findSupportedVpn(context: Context, channels: List<NetworkChannel>): NetworkChannel? {
        val pm = context.packageManager
        return channels.filter { channel ->
            channel.isVpn && RouteRuleBuilder.isValidUid(channel.vpnOwnerUid) && runCatching {
                val pkg = pm.getPackagesForUid(channel.vpnOwnerUid)?.singleOrNull()
                pkg != null && pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                    .metaData?.getInt(VERSION_KEY) == 1
            }.getOrDefault(false)
        }.singleOrNull()
    }

    /** null means the VPN capture could not be confirmed, even if a VPN is active. */
    fun capturedUids(lines: List<String>, iface: String, ownerUid: Int): List<IntRange>? {
        val agent = lines.singleOrNull { line ->
            agentPrefix.containsMatchIn(line) && transport.containsMatchIn(line) &&
                interfaceName.find(line)?.groupValues?.get(1) == iface &&
                owner.find(line)?.groupValues?.get(1)?.toIntOrNull() == ownerUid
        } ?: return null
        val ranges = uidRanges.find(agent)?.groupValues?.get(1) ?: return null
        if (ranges.isBlank()) return emptyList()
        return ranges.split(',').map { range ->
            val value = range.trim().split('-', limit = 2)
            val first = value[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val last = if (value.size == 1) first else value[1].toIntOrNull() ?: return null
            if (last < first) return null
            first..last
        }
    }
}

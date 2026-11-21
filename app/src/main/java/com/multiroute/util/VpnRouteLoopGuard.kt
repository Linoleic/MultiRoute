package com.multiroute.util

/** VPN client self-routing policy shared by channel selection and route application. */
internal object VpnRouteLoopGuard {
    private val knownVpnClientKeywords = listOf(
        "clash", "v2ray", "wireguard", "singbox", "nekobox", "shadowsocks"
    )

    /**
     * Returns true when routing [candidateUid] through a VPN would route the VPN client into itself.
     * Exact owner UID equality is authoritative. Cross-user appId equality is only a safety signal when
     * package identity or the conservative known-client naming fallback also identifies the client.
     */
    fun shouldSkip(
        isVpn: Boolean,
        ownerUid: Int,
        candidateUid: Int,
        candidatePackageNames: Iterable<String>,
        vpnOwnerPackageNames: Iterable<String> = emptyList()
    ): Boolean {
        if (!isVpn) return false
        if (ownerUid > 0 && candidateUid == ownerUid) return true

        val candidatePackages = candidatePackageNames.toList()
        val ownerPackages = vpnOwnerPackageNames.toSet()
        val sameVpnPackage = candidatePackages.any { it in ownerPackages }
        val knownClient = candidatePackages.any { packageName ->
            knownVpnClientKeywords.any { keyword -> packageName.contains(keyword, ignoreCase = true) }
        }
        val sameAppId = ownerUid > 0 && candidateUid > 0 &&
            ownerUid % 100000 == candidateUid % 100000
        return knownClient || (sameAppId && sameVpnPackage)
    }
}

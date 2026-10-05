package com.multiroute.model

import android.graphics.drawable.Drawable

const val CHANNEL_DEFAULT = "default"

data class NetworkChannel(
    val id: String,                // Unique ID, matching interfaceName (e.g. "wlan0", "wlan1", "rmnet_data3", "eth0")
    val interfaceName: String,     // Kernel interface name (e.g. "wlan0")
    val transportType: String,
    val shortName: String,         // Short label: "wlan0" or "wlan0 (SSID)"
    val ssid: String? = null,      // Wi-Fi network SSID (e.g. "H3C_CA202C", "Hpkt")
    val ipAddress: String?,        // Primary IPv4/IPv6 address
    val allIpAddresses: List<String> = emptyList(), // All assigned IPv4 / IPv6 addresses
    val gateway: String? = null,   // Default gateway / route address
    val dnsServers: List<String> = emptyList(),     // DNS server IP addresses
    val domains: String? = null,   // Search domain
    val mtu: Int = 0,              // Interface MTU
    val downlinkBps: Int = 0,      // Downlink bandwidth in Kbps
    val uplinkBps: Int = 0,        // Uplink bandwidth in Kbps
    val isMetered: Boolean = false,// Whether network is metered
    val isDefault: Boolean = false,
    val isValidated: Boolean = true
)

data class AppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val uid: Int = -1,
    val targetChannelId: String,   // CHANNEL_DEFAULT or NetworkChannel.id
    val isSystemApp: Boolean,
    /** 0 = primary user; other values are OEM clone spaces / work profiles (Xiaomi XSpace = 999). */
    val userId: Int = 0
) {
    /**
     * Stable identity in the rule store. A clone-space install of the same package gets its own key
     * (`pkg@999`), so the clone and the primary install can be routed to different channels.
     */
    val ruleKey: String get() = com.multiroute.util.RouteRuleBuilder.ruleKey(packageName, userId)
}

package com.multiroute.util

/** A local observation, never proof that a workstation can reach an endpoint. */
data class WirelessAdbInfo(
    val enabled: Boolean?,
    val port: Int?,
    val listening: Boolean?,
    val addresses: List<Pair<String, String>>
) {
    companion object {
        fun read(): WirelessAdbInfo = parse(SuHelper.executeShellWithOutput("""
            echo ENABLED=${'$'}(settings get global adb_wifi_enabled)
            echo PORT=${'$'}(getprop service.adb.tls.port)
            sockets=${'$'}(ss -lnt 2>/dev/null)
            if [ ${'$'}? -eq 0 ]; then
              printf '%s\n' "${'$'}sockets"
              echo SOCKETS_OK
            fi
            ip -o -4 addr show up scope global 2>/dev/null
        """.trimIndent(), timeoutSeconds = 4))

        internal fun parse(lines: List<String>): WirelessAdbInfo {
            val enabled = when (lines.firstOrNull { it.startsWith("ENABLED=") }?.substringAfter('=')) {
                "1" -> true
                "0" -> false
                else -> null
            }
            val port = lines.firstOrNull { it.startsWith("PORT=") }?.substringAfter('=')
                ?.toIntOrNull()?.takeIf { it in 1..65535 }
            val listening = if ("SOCKETS_OK" in lines && port != null) {
                lines.any { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    fields.size >= 5 && fields[0] == "LISTEN" &&
                        fields[3].substringAfterLast(':').toIntOrNull() == port
                }
            } else null
            val addressPattern = Regex("^\\d+:\\s+(\\S+)\\s+inet\\s+([0-9.]+)/\\d+")
            val addresses = lines.mapNotNull { line ->
                val match = addressPattern.find(line.trim()) ?: return@mapNotNull null
                val iface = match.groupValues[1].substringBefore('@')
                if (!Regex("(?:wlan|eth|ap|swlan)\\d+").matches(iface)) return@mapNotNull null
                val ip = match.groupValues[2]
                if (ip.split('.').size != 4 || ip.split('.').any { it.toIntOrNull() !in 0..255 })
                    return@mapNotNull null
                iface to ip
            }.distinct()
            return WirelessAdbInfo(enabled, port, listening, addresses)
        }
    }
}

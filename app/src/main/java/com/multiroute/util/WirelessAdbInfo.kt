package com.multiroute.util

import com.multiroute.adb.AdbMode
import com.multiroute.adb.WirelessAdbController

/** A local observation, never proof that a workstation can reach an endpoint. */
data class WirelessAdbInfo(
    val enabled: Boolean?,
    val port: Int?,
    val listening: Boolean?,
    val addresses: List<Pair<String, String>>,
    val tlsEnabled: Boolean? = null,
    val mode: AdbMode? = null,
    val tcpPort: Int? = null,
    val tlsPort: Int? = null
) {
    companion object {
        fun read(context: android.content.Context? = null, preferredMode: AdbMode? = null): WirelessAdbInfo {
            val modeToUse = preferredMode ?: context?.let {
                runCatching { WirelessAdbController.policy(it).mode }.getOrNull()
            }
            val lines = SuHelper.executeShellWithOutput("""
            echo ENABLED=${'$'}(settings get global adb_wifi_enabled)
            echo TLS=${'$'}(getprop persist.adb.tls_server.enable)
            echo TLS_PORT=${'$'}(getprop service.adb.tls.port)
            echo TCP_PORT=${'$'}(getprop service.adb.tcp.port)
            echo PERSIST_TCP_PORT=${'$'}(getprop persist.adb.tcp.port)
            echo PORT=${'$'}(getprop service.adb.tls.port)
            sockets=${'$'}(ss -lnt 2>/dev/null)
            if [ ${'$'}? -eq 0 ]; then
              printf '%s\n' "${'$'}sockets"
              echo SOCKETS_OK
            fi
            ip -o -4 addr show up scope global 2>/dev/null
        """.trimIndent(), timeoutSeconds = 4)
            val propertyTlsPort = lines.firstOrNull { it.startsWith("TLS_PORT=") }?.substringAfter('=')
                ?.toIntOrNull()?.takeIf { it in 1..65535 }
            val propertyPort = propertyTlsPort ?: lines.firstOrNull { it.startsWith("PORT=") }?.substringAfter('=')
                ?.toIntOrNull()?.takeIf { it in 1..65535 }
            if (context != null && "ENABLED=1" in lines && (propertyPort == null || android.os.Build.VERSION.SDK_INT >= 37)) {
                val apk = context.applicationInfo.sourceDir
                if (!apk.contains('\'')) {
                    val framework = SuHelper.executeShellWithOutput(
                        "/system/bin/env CLASSPATH='$apk' /system/bin/app_process /system/bin com.multiroute.adb.AdbPortReader",
                        timeoutSeconds = 4)
                    val port = framework.firstOrNull { it.startsWith("PORT=") }?.substringAfter('=')
                        ?.toIntOrNull()?.takeIf { it in 1..65535 }
                    if (port != null) {
                        return parse(
                            listOf("TLS_PORT=$port", "PORT=$port") + lines.filterNot { it.startsWith("PORT=") || it.startsWith("TLS_PORT=") },
                            modeToUse
                        )
                    }
                }
            }
            return parse(lines, modeToUse)
        }

        internal fun parse(lines: List<String>, preferredMode: AdbMode? = null): WirelessAdbInfo {
            val enabledVal = lines.firstOrNull { it.startsWith("ENABLED=") }?.substringAfter('=')
            val tlsSwitch = when (enabledVal) {
                "1" -> true
                "0" -> false
                else -> null
            }
            val tlsPort = (lines.firstOrNull { it.startsWith("TLS_PORT=") }
                ?: lines.firstOrNull { it.startsWith("PORT=") })?.substringAfter('=')
                ?.toIntOrNull()?.takeIf { it in 1..65535 }

            val tcpProp = lines.firstOrNull { it.startsWith("TCP_PORT=") }?.substringAfter('=')
            val persistTcpProp = lines.firstOrNull { it.startsWith("PERSIST_TCP_PORT=") }?.substringAfter('=')
            val tcpPort = when {
                tcpProp == "-1" -> null
                !tcpProp.isNullOrBlank() -> tcpProp.toIntOrNull()?.takeIf { it in 1..65535 }
                else -> persistTcpProp?.toIntOrNull()?.takeIf { it in 1..65535 }
            }

            val listeningPorts = if ("SOCKETS_OK" in lines) {
                lines.mapNotNull { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size >= 5 && fields[0] == "LISTEN") {
                        fields[3].substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }
                    } else null
                }.toSet()
            } else null

            val tlsListening = if (listeningPorts != null && tlsPort != null) tlsPort in listeningPorts else null
            val tcpListening = if (listeningPorts != null && tcpPort != null) tcpPort in listeningPorts else null

            val mode = when {
                preferredMode == AdbMode.TCP && (tcpListening == true || tcpPort != null) -> AdbMode.TCP
                preferredMode == AdbMode.TLS && (tlsListening == true || tlsSwitch == true) -> AdbMode.TLS
                tcpListening == true && tlsListening != true -> AdbMode.TCP
                tlsListening == true && tcpListening != true -> AdbMode.TLS
                tcpListening == true && tlsListening == true -> preferredMode ?: AdbMode.TCP
                preferredMode != null -> preferredMode
                tlsSwitch == true -> AdbMode.TLS
                tcpPort != null -> AdbMode.TCP
                else -> null
            }

            val (effectivePort, effectiveListening, effectiveEnabled) = when (mode) {
                AdbMode.TCP -> {
                    val listening = tcpListening
                    val enabled = if (listening == true) true else (tcpPort != null && tcpProp != "-1")
                    Triple(tcpPort, listening, enabled)
                }
                AdbMode.TLS -> {
                    Triple(tlsPort, tlsListening, tlsSwitch)
                }
                null -> {
                    val anyListening = if (listeningPorts != null) (tlsListening == true || tcpListening == true) else null
                    val isEnabled = when {
                        tlsSwitch == true -> true
                        tcpListening == true -> true
                        tcpPort != null && tcpProp != "-1" -> true
                        tlsSwitch == false && tcpProp == "-1" -> false
                        tlsSwitch == false && tcpPort == null -> false
                        else -> null
                    }
                    Triple(tlsPort ?: tcpPort, anyListening, isEnabled)
                }
            }

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

            val tls = when (lines.firstOrNull { it.startsWith("TLS=") }?.substringAfter('=')) {
                "1" -> true
                "0" -> false
                else -> null
            }

            return WirelessAdbInfo(
                enabled = effectiveEnabled,
                port = effectivePort,
                listening = effectiveListening,
                addresses = addresses,
                tlsEnabled = tls,
                mode = mode,
                tcpPort = tcpPort,
                tlsPort = tlsPort
            )
        }
    }
}

package com.multiroute.util

/** A privacy-preserving snapshot of generic kernel traffic-hook evidence. */
data class TransparentProxyDiagnostics(
    val interfaces: List<TcInterfaceEvidence>,
    val cgroupHooks: CgroupHookEvidence,
    val establishedSockets: SocketEvidence
) {
    /** Advises only when another link carries the known sing-box local TC marker. */
    fun mayBypassOn(interfaceName: String): Boolean =
        cgroupHooks.singBoxHookCount == 0 &&
            interfaces.any {
                it.interfaceName == interfaceName && it.checked && !it.unattributedBpfPresent && !it.singBoxLocalTcPresent
            } && interfaces.any { it.singBoxLocalTcPresent }
}

data class TcInterfaceEvidence(
    val interfaceName: String,
    /** True only if the egress and ingress filter checks both completed successfully. */
    val checked: Boolean,
    val unattributedBpfPresent: Boolean,
    /** The sing-box-specific local TC marker; this is only a bypass advisory clue. */
    val singBoxLocalTcPresent: Boolean,
    val platformBpfPresent: Boolean = false
)

data class CgroupHookEvidence(
    val status: Status,
    val unattributedHookCount: Int = 0,
    val platformHookCount: Int = 0,
    val singBoxHookCount: Int = 0,
    /** A program file descriptor does not prove that the program is attached. */
    val singBoxSocketProgramHeld: Boolean = false
) {
    enum class Status { AVAILABLE, UNAVAILABLE, ERROR }
}

data class SocketEvidence(val status: Status, val establishedCount: Int, val perInterface: Map<String, Int> = emptyMap()) {
    enum class Status { OBSERVED, SKIPPED_NO_KNOWN_PROCESS, UNAVAILABLE, ERROR }
}

/**
 * Read-only generic Linux traffic diagnostics. It deliberately does not inspect application
 * configuration or NetProxy files/commands, and returned values contain no addresses or PIDs.
 */
internal class TransparentProxyDiagnosticsCollector(private val shell: RootShellExecutor) {
    fun collect(activeInterfaces: List<String> = emptyList()): TransparentProxyDiagnostics {
        val interfaces = (activeInterfaces.asSequence() + sequence { yieldAll(discoverPhysicalUpInterfaces()) })
            .filter(::isSafeInterfaceName).distinct().take(MAX_INTERFACES).toList()

        val tcEvidence = interfaces.map(::inspectInterface)
        val processEvidence = inspectSingBoxProcess()
        val cgroups = inspectCgroupHooks().copy(singBoxSocketProgramHeld = SING_BOX_CGROUP_PROGRAM in processEvidence)
        val sockets = if (SING_BOX_RUNNING in processEvidence || tcEvidence.any { it.singBoxLocalTcPresent } || cgroups.singBoxHookCount > 0) {
            inspectEstablishedSockets(interfaces.toSet())
        } else SocketEvidence(SocketEvidence.Status.SKIPPED_NO_KNOWN_PROCESS, 0)
        return TransparentProxyDiagnostics(tcEvidence, cgroups, sockets)
    }

    private fun discoverPhysicalUpInterfaces(): List<String> {
        // Reading sysfs metadata only; requiring /device excludes loopback and software tunnels.
        return shell.executeShellWithOutput(
            "for p in /sys/class/net/*; do [ -e \"\$p/device\" ] || continue; " +
                "[ \"\$(cat \"\$p/operstate\" 2>/dev/null)\" = up ] || continue; basename \"\$p\"; done",
            timeoutSeconds = 3
        ).take(MAX_INTERFACES).filter(::isSafeInterfaceName)
    }

    private fun inspectInterface(name: String): TcInterfaceEvidence {
        var checked = true
        var unattributedBpf = false
        var singBoxLocal = false
        var platformBpf = false
        for (direction in listOf("ingress", "egress")) {
            val marker = "__MR_TC_${direction.uppercase()}_"
            val lines = shell.executeShellWithOutput(
                "if tc filter show dev '$name' $direction 2>/dev/null; then echo ${marker}OK; " +
                    "else echo ${marker}ERROR; fi",
                timeoutSeconds = 2
            )
            val parsed = TransparentProxyDiagnosticsParser.parseTc(lines, marker, egress = direction == "egress")
            checked = checked && parsed.checked
            unattributedBpf = unattributedBpf || parsed.unattributedBpfPresent
            singBoxLocal = singBoxLocal || parsed.singBoxLocalTcPresent
            platformBpf = platformBpf || parsed.platformBpfPresent
        }
        return TcInterfaceEvidence(name, checked, unattributedBpf, singBoxLocal, platformBpf)
    }

    private fun inspectCgroupHooks(): CgroupHookEvidence {
        val marker = "__MR_BPFTool_"
        // A non-effective tree can miss legacy cgroup attachments on Android.
        val lines = shell.executeShellWithOutput(
            "if ! command -v bpftool >/dev/null 2>&1; then echo ${marker}UNAVAILABLE; " +
                "elif bpftool cgroup show /sys/fs/cgroup effective 2>/dev/null; then echo ${marker}AVAILABLE; " +
                "else echo ${marker}ERROR; fi",
            timeoutSeconds = 3
        )
        return TransparentProxyDiagnosticsParser.parseCgroup(lines, marker)
    }

    private fun inspectSingBoxProcess(): List<String> = shell.executeShellWithOutput(
        "for p in \$(pidof sing-box 2>/dev/null | cut -d ' ' -f 1-4); do " +
            "echo $SING_BOX_RUNNING; " +
            // Linux BPF_PROG_TYPE_CGROUP_SOCK_ADDR (18). Some Android kernels expose only the
            // program FD for legacy attaches, so never infer a live hook from this fallback.
            "if grep -qE '^prog_type:[[:space:]]*18$' /proc/\"\$p\"/fdinfo/* 2>/dev/null; then " +
            "echo $SING_BOX_CGROUP_PROGRAM; break; fi; done",
        timeoutSeconds = 2
    )

    private fun inspectEstablishedSockets(interfaces: Set<String>): SocketEvidence {
        // `ss` output is parsed in memory; only a count is returned. No endpoint/process text escapes.
        val marker = "__MR_SS_"
        val lines = shell.executeShellWithOutput(
            "if ! command -v ss >/dev/null 2>&1; then echo ${marker}UNAVAILABLE; " +
                "elif ss -H -n -t -u -p 2>/dev/null; then echo ${marker}OK; fi",
            timeoutSeconds = 2
        )
        return TransparentProxyDiagnosticsParser.parseSockets(lines, marker, interfaces)
    }

    private fun isSafeInterfaceName(name: String): Boolean = interfaceNamePattern.matches(name)

    companion object {
        private const val MAX_INTERFACES = 12
        private const val SING_BOX_RUNNING = "__MR_SB_RUNNING"
        private const val SING_BOX_CGROUP_PROGRAM = "__MR_SB_CGROUP_PROGRAM"
        private val interfaceNamePattern = Regex("[A-Za-z0-9_.-]{1,15}")
    }
}

/** Small pure parsers kept independent from Android and shell execution for fixture testing. */
internal object TransparentProxyDiagnosticsParser {
    private val fields = Regex("\\s+")
    private val boundInterface = Regex("""%([A-Za-z0-9_.-]+)\]?:[0-9]+$""")
    private val singBoxProcess = Regex("""\("sing-box",pid=\d+[,)]""")
    private val bpfAction = Regex("""^action\s+(?:order\s+\d+:\s+)?bpf(?:\s|$)""")
    private val platformTcPrograms = setOf(
        "prog_netd_schedcls_egress_accecn_eth",
        "prog_netd_schedcls_egress_accecn_rawip",
        "prog_netd_schedcls_tether_downstream6_ether",
        "prog_netd_schedcls_tether_downstream6_rawip",
        "prog_netd_schedcls_tether_upstream6_ether",
        "prog_netd_schedcls_tether_upstream6_rawip",
        "prog_netd_schedcls_tether_downstream4_ether",
        "prog_netd_schedcls_tether_downstream4_rawip",
        "prog_netd_schedcls_tether_upstream4_ether",
        "prog_netd_schedcls_tether_upstream4_rawip"
    )
    private val legacyCgroupHookNames = mapOf(
        "inet_ingress" to "ingress", "inet_egress" to "egress",
        "inet4_connect" to "connect4", "inet6_connect" to "connect6",
        "udp4_sendmsg" to "sendmsg4", "udp6_sendmsg" to "sendmsg6",
        "udp4_recvmsg" to "recvmsg4", "udp6_recvmsg" to "recvmsg6"
    )
    // Kernel names can be truncated to 15 characters. Match the observed name and hook together.
    private val platformCgroupPrograms = mapOf(
        "ingress" to setOf("ingress_stats_6", "cgroupskb_ingre"),
        "egress" to setOf("egress_stats_6_", "cgroupskb_egres"),
        "connect4" to setOf("connect4_inet4_"), "connect6" to setOf("connect6_inet6_"),
        "sendmsg4" to setOf("sendmsg4_udp4_s"), "sendmsg6" to setOf("sendmsg6_udp6_s"),
        "recvmsg4" to setOf("recvmsg4_udp4_r"), "recvmsg6" to setOf("recvmsg6_udp6_r")
    )
    private val singBoxCgroupPrograms = mapOf(
        "connect4" to "sb_ebpf_conn4", "connect6" to "sb_ebpf_conn6",
        "sendmsg4" to "sb_ebpf_udp4", "sendmsg6" to "sb_ebpf_udp6",
        "recvmsg4" to "sb_ebpf_urcv4", "recvmsg6" to "sb_ebpf_urcv6"
    )
    internal data class TcResult(
        val checked: Boolean, val unattributedBpfPresent: Boolean, val singBoxLocalTcPresent: Boolean,
        val platformBpfPresent: Boolean = false
    )

    fun parseTc(lines: List<String>, markerPrefix: String, egress: Boolean): TcResult {
        val marker = lines.firstOrNull { it == "${markerPrefix}OK" || it == "${markerPrefix}ERROR" }
        val checked = marker == "${markerPrefix}OK"
        if (!checked) return TcResult(false, false, false)
        var unattributedBpf = false
        var singBoxLocal = false
        var platformBpf = false
        for (line in lines) {
            val text = line.trim().lowercase()
            val columns = text.split(fields)
            val bpfFilter = columns.firstOrNull() == "filter" && "bpf" in columns && "handle" in columns
            if (!bpfFilter && !bpfAction.containsMatchIn(text)) continue
            // tc's BPF filter name is the field after the handle; [*fsobj] is its object suffix.
            val name = if (bpfFilter) columns.getOrNull(columns.indexOf("handle") + 2) else null
            when {
                egress && name == "sb_tc_local" -> singBoxLocal = true
                name?.removeSuffix(":[*fsobj]") in platformTcPrograms -> platformBpf = true
                else -> unattributedBpf = true
            }
        }
        return TcResult(true, unattributedBpf, singBoxLocal, platformBpf)
    }

    fun parseCgroup(lines: List<String>, markerPrefix: String): CgroupHookEvidence {
        val status = when {
            lines.any { it == "${markerPrefix}UNAVAILABLE" } -> CgroupHookEvidence.Status.UNAVAILABLE
            lines.any { it == "${markerPrefix}AVAILABLE" } -> CgroupHookEvidence.Status.AVAILABLE
            else -> CgroupHookEvidence.Status.ERROR
        }
        if (status != CgroupHookEvidence.Status.AVAILABLE) return CgroupHookEvidence(status)

        var unattributed = 0
        var platform = 0
        var singBox = 0
        for (line in lines) {
            val columns = line.trim().split(fields)
            if (columns.firstOrNull()?.toIntOrNull() == null) continue
            val rawHook = columns.getOrNull(1)?.removePrefix("cgroup_") ?: continue
            val hook = legacyCgroupHookNames[rawHook] ?: rawHook
            val knownPlatformNames = platformCgroupPrograms[hook] ?: continue
            val program = columns.drop(2).lastOrNull()
            when {
                program in knownPlatformNames -> platform++
                program != null && program == singBoxCgroupPrograms[hook] -> singBox++
                else -> unattributed++
            }
        }
        return CgroupHookEvidence(status, unattributed, platform, singBox)
    }

    fun parseSockets(lines: List<String>, markerPrefix: String, interfaces: Set<String>): SocketEvidence {
        if ("${markerPrefix}UNAVAILABLE" in lines) return SocketEvidence(SocketEvidence.Status.UNAVAILABLE, 0)
        if ("${markerPrefix}OK" !in lines) return SocketEvidence(SocketEvidence.Status.ERROR, 0)
        val counts = mutableMapOf<String, Int>()
        var established = 0
        for (line in lines) {
            if (!singBoxProcess.containsMatchIn(line)) continue
            val columns = line.trim().split(fields, limit = 7)
            val offset = when (columns.firstOrNull()) {
                "tcp", "udp" -> 1
                else -> 0
            }
            if (columns.getOrNull(offset) != "ESTAB") continue
            val local = columns.getOrNull(offset + 3) ?: continue
            val peer = columns.getOrNull(offset + 4) ?: continue
            if (local.isLoopbackEndpoint() || peer.isLoopbackEndpoint()) continue
            established++
            val iface = boundInterface.find(local)?.groupValues?.get(1)
            if (iface != null && iface in interfaces) counts[iface] = (counts[iface] ?: 0) + 1
        }
        return SocketEvidence(SocketEvidence.Status.OBSERVED, established, counts.toSortedMap())
    }

    private fun String.isLoopbackEndpoint(): Boolean = startsWith("127.") || startsWith("[::1]") || startsWith("[::ffff:127.")
}

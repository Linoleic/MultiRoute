package com.multiroute.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransparentProxyDiagnosticsTest {
    @Test
    fun recognizesThePixelSystemTcFilterAndKeepsLookalikesUnattributed() {
        val platformRow = "filter protocol all pref 6 bpf chain 0 handle 0x1 prog_netd_schedcls_egress_accecn_eth:[*fsobj] direct-action id 20"
        val marker = "__MR_TC_EGRESS_"
        assertEquals(
            TransparentProxyDiagnosticsParser.TcResult(true, false, false, platformBpfPresent = true),
            TransparentProxyDiagnosticsParser.parseTc(listOf(platformRow, "${marker}OK"), marker, egress = true)
        )
        assertEquals(
            TransparentProxyDiagnosticsParser.TcResult(true, true, false),
            TransparentProxyDiagnosticsParser.parseTc(
                listOf(platformRow.replace("eth:[", "eth_other:["), "${marker}OK"), marker, egress = true
            )
        )
    }

    @Test
    fun preservesGenericBpfAlongsideSingBoxMarkerAndIgnoresFilterHeaders() {
        val generic = TransparentProxyDiagnosticsParser.parseTc(
            listOf(
                "filter protocol all pref 1 bpf chain 0",
                "filter protocol ip pref 2 flower chain 0 handle 0x1",
                "    action order 1: bpf action name other_proxy id 99",
                "__MR_TC_INGRESS_OK"
            ),
            "__MR_TC_INGRESS_", egress = false
        )
        assertTrue(generic.checked)
        assertTrue(generic.unattributedBpfPresent)
        assertFalse(generic.singBoxLocalTcPresent)

        val singBoxRow = "filter protocol all pref 1 bpf chain 0 handle 0x5344 sb_tc_local direct-action not_in_hw id 104 tag abc jited"
        val unknownRow = "filter protocol all pref 2 bpf chain 0 handle 0x1 sb_tc_local_extra direct-action id 99"
        assertEquals(
            TransparentProxyDiagnosticsParser.TcResult(true, true, true),
            TransparentProxyDiagnosticsParser.parseTc(
                listOf(singBoxRow, unknownRow, "__MR_TC_EGRESS_OK"), "__MR_TC_EGRESS_", egress = true
            )
        )
        assertEquals(
            TransparentProxyDiagnosticsParser.TcResult(true, true, false),
            TransparentProxyDiagnosticsParser.parseTc(listOf(singBoxRow, "__MR_TC_INGRESS_OK"), "__MR_TC_INGRESS_", egress = false)
        )
    }

    @Test
    fun successfulEmptyTcIsCheckedButErrorIsNot() {
        assertEquals(
            TransparentProxyDiagnosticsParser.TcResult(true, false, false),
            TransparentProxyDiagnosticsParser.parseTc(
                listOf("filter protocol all pref 1 bpf chain 0", "__MR_TC_EGRESS_OK"), "__MR_TC_EGRESS_", egress = true
            )
        )
        assertEquals(
            TransparentProxyDiagnosticsParser.TcResult(false, false, false),
            TransparentProxyDiagnosticsParser.parseTc(listOf("__MR_TC_EGRESS_ERROR"), "__MR_TC_EGRESS_", egress = true)
        )
    }

    @Test
    fun cgroupDistinguishesSystemHooksKnownProxyHooksAndUnknownPrograms() {
        val rows = listOf(
            "CgroupPath",
            "ID       AttachType        AttachFlags     Name",
            "/sys/fs/cgroup",
            "    100  connect4           multi           custom_proxy",
            "    101  cgroup_udp6_sendmsg multi                         ",
            "    102  cgroup_inet_ingress multi           ingress_stats_6",
            "    103  egress            multi           cgroupskb_egres",
            "    104  device            multi           sb_unrelated",
            "    105  cgroup_inet4_connect             connect4_inet4_",
            "    106  connect6           multi           sb_ebpf_conn6",
            "    107  cgroup_udp4_sendmsg              sb_ebpf_udp4",
            "    108  cgroup_udp6_recvmsg              sb_ebpf_conn4",
            "    109  recvmsg4           multi           recvmsg4_udp4_r",
            "__MR_BPFTool_AVAILABLE"
        )
        assertEquals(
            CgroupHookEvidence(CgroupHookEvidence.Status.AVAILABLE, unattributedHookCount = 3, platformHookCount = 4, singBoxHookCount = 2),
            TransparentProxyDiagnosticsParser.parseCgroup(rows, "__MR_BPFTool_")
        )
    }

    @Test
    fun cgroupDistinguishesMissingToolFailedReadAndEmptyResult() {
        assertEquals(
            CgroupHookEvidence(CgroupHookEvidence.Status.UNAVAILABLE),
            TransparentProxyDiagnosticsParser.parseCgroup(listOf("__MR_BPFTool_UNAVAILABLE"), "__MR_BPFTool_")
        )
        assertEquals(
            CgroupHookEvidence(CgroupHookEvidence.Status.AVAILABLE),
            TransparentProxyDiagnosticsParser.parseCgroup(listOf("__MR_BPFTool_AVAILABLE", "cgroup /:"), "__MR_BPFTool_")
        )
        assertEquals(
            CgroupHookEvidence(CgroupHookEvidence.Status.ERROR),
            TransparentProxyDiagnosticsParser.parseCgroup(listOf("100 connect4 proxy", "__MR_BPFTool_ERROR"), "__MR_BPFTool_")
        )
    }

    @Test
    fun countsConnectedTcpAndUdpWithoutTreatingLocalListenersAsUpstreams() {
        val rows = listOf(
            """tcp ESTAB 0 0 192.0.2.5%wlan0:34000 198.51.100.9:443 users:(("sing-box",pid=42,fd=5))""",
            """udp ESTAB 0 0 [2001:db8::1%wlan1]:34000 [2001:db8::5]:443 users:(("sing-box",pid=42,fd=6))""",
            """tcp ESTAB 0 0 192.0.2.5:34001 198.51.100.9:443 users:(("sing-box",pid=42,fd=7))""",
            """tcp ESTAB 0 0 192.0.2.5%wlan1:5555 198.51.100.3:38222 users:(("adbd",pid=43,fd=5))""",
            """udp UNCONN 0 0 192.0.2.5%wlan1:9999 0.0.0.0:* users:(("sing-box",pid=42,fd=8))""",
            """tcp ESTAB 0 0 127.0.0.1:9999 127.0.0.1:55102 users:(("sing-box",pid=42,fd=9))"""
        )
        assertEquals(
            SocketEvidence(SocketEvidence.Status.ERROR, 0),
            TransparentProxyDiagnosticsParser.parseSockets(rows, "__MR_SS_", setOf("wlan0", "wlan1"))
        )
        assertEquals(
            SocketEvidence(SocketEvidence.Status.UNAVAILABLE, 0),
            TransparentProxyDiagnosticsParser.parseSockets(listOf("__MR_SS_UNAVAILABLE"), "__MR_SS_", emptySet())
        )
        assertEquals(
            SocketEvidence(SocketEvidence.Status.OBSERVED, 3, mapOf("wlan0" to 1, "wlan1" to 1)),
            TransparentProxyDiagnosticsParser.parseSockets(
                rows + "__MR_SS_OK", "__MR_SS_", setOf("wlan0", "wlan1")
            )
        )
    }

    @Test
    fun bypassAdvisoryRequiresKnownMarkerOnAnotherCheckedLink() {
        val snapshot = TransparentProxyDiagnostics(
            interfaces = listOf(
                TcInterfaceEvidence("wlan0", checked = true, unattributedBpfPresent = false, singBoxLocalTcPresent = true),
                TcInterfaceEvidence("wlan1", checked = true, unattributedBpfPresent = false, singBoxLocalTcPresent = false, platformBpfPresent = true),
                TcInterfaceEvidence("rmnet0", checked = false, unattributedBpfPresent = false, singBoxLocalTcPresent = false),
                TcInterfaceEvidence("tun0", checked = true, unattributedBpfPresent = true, singBoxLocalTcPresent = false)
            ),
            cgroupHooks = CgroupHookEvidence(CgroupHookEvidence.Status.AVAILABLE, platformHookCount = 1),
            establishedSockets = SocketEvidence(SocketEvidence.Status.SKIPPED_NO_KNOWN_PROCESS, 0)
        )
        assertTrue(snapshot.mayBypassOn("wlan1"))
        assertFalse(snapshot.mayBypassOn("wlan0"))
        assertFalse(snapshot.mayBypassOn("rmnet0"))
        assertFalse(snapshot.mayBypassOn("tun0"))
        assertFalse(snapshot.copy(interfaces = snapshot.interfaces.filterNot { it.singBoxLocalTcPresent }).mayBypassOn("wlan1"))
        assertTrue(snapshot.copy(cgroupHooks = CgroupHookEvidence(CgroupHookEvidence.Status.UNAVAILABLE, singBoxSocketProgramHeld = true)).mayBypassOn("wlan1"))
        assertFalse(snapshot.copy(cgroupHooks = CgroupHookEvidence(CgroupHookEvidence.Status.AVAILABLE, singBoxHookCount = 1)).mayBypassOn("wlan1"))
    }
}

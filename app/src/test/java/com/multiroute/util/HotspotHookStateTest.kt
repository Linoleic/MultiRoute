package com.multiroute.util

import org.junit.Assert.*
import org.junit.Test

class HotspotHookStateTest {
    private val beacon = HotspotHookStateParser.beacon(100, 1234, "build-a", "cellular", "rmnet_data2")

    @Test fun requiresBeaconFromTheCurrentProcessAndBuild() {
        assertEquals(HotspotHookState.LOADED, HotspotHookStateParser.evaluate(beacon, 100, 1234, "build-a"))
        assertEquals(HotspotHookState.NOT_LOADED, HotspotHookStateParser.evaluate(beacon, 101, 1234, "build-a"))
        assertEquals(HotspotHookState.NOT_LOADED, HotspotHookStateParser.evaluate(beacon, 100, 9999, "build-a"))
        assertEquals(HotspotHookState.VERSION_MISMATCH, HotspotHookStateParser.evaluate(beacon, 100, 1234, "build-b"))
    }

    @Test fun dumpsysAndOldProcessBeaconsAreNotLoadEvidence() {
        listOf(null, "", "v=1;source=dumpsys;target=cellular;active=wlan0",
            "v=1;pid=100;ts=1234;target=hooked;active=none",
            beacon.replace("hook=1", "hook=0"), beacon.replace("start=1234", "start=invalid")
        ).forEach { assertEquals(HotspotHookState.NOT_LOADED, HotspotHookStateParser.evaluate(it, 100, 1234, "build-a")) }
    }

    @Test fun unknownProcessIdentityIsNotAClaimThatHookIsAbsent() {
        assertEquals(HotspotHookState.UNKNOWN, HotspotHookStateParser.evaluate(beacon, null, 1234, "build-a"))
        assertEquals(HotspotHookState.UNKNOWN, HotspotHookStateParser.evaluate(beacon, 100, null, "build-a"))
    }

    @Test fun statParserHandlesParenthesesAndSpacesInComm() {
        val fields = (3..52).map { if (it == 22) "1234" else "0" }.joinToString(" ")
        assertEquals(1234L, HotspotHookStateParser.processStartTicks("100 (network (stack) name) $fields"))
        assertNull(HotspotHookStateParser.processStartTicks("permission denied"))
        assertNull(HotspotHookStateParser.processStartTicks("100 (short) S 1 2"))
    }
}

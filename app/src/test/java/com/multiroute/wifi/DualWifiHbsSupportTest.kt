package com.multiroute.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualWifiHbsSupportTest {
    @Test
    fun resolvesQcomIniSubdirAndRejectsUnsafeNames() {
        assertEquals("peach_v2", DeviceCapabilityDetector.resolveQcomWifiIniSubdir("qca_cld3_peach_v2"))
        assertEquals("kiwi_v2", DeviceCapabilityDetector.resolveQcomWifiIniSubdir("kiwi_v2\n"))
        assertNull(DeviceCapabilityDetector.resolveQcomWifiIniSubdir(""))
        assertNull(DeviceCapabilityDetector.resolveQcomWifiIniSubdir("../bad"))
    }

    @Test
    fun parsesDual5gHbsSupportFromQcomIniAndDumpsysFallback() {
        val xiaomi15Ini = listOf(
            "# No HBS support",
            "enable_sbs=0"
        )
        val xiaomiPadIni = listOf("enable_sbs=1")
        val lastWinsIni = listOf("enable_sbs=1", "# enable_sbs=0", "enable_sbs = 0")

        assertEquals(false, DeviceCapabilityDetector.parseDual5gHbsSupport(xiaomi15Ini))
        assertEquals(true, DeviceCapabilityDetector.parseDual5gHbsSupport(xiaomiPadIni))
        assertEquals(false, DeviceCapabilityDetector.parseDual5gHbsSupport(lastWinsIni))
        assertEquals(
            true,
            DeviceCapabilityDetector.parseDual5gHbsSupport(
                emptyList(),
                listOf("Resource Name: config_wifiAllowMultiInternetConnectDual5GFrequency, value: true")
            )
        )
        assertNull(DeviceCapabilityDetector.parseDual5gHbsSupport(emptyList()))
    }

    @Test
    fun flagsLowAndHigh5gAsConflictedWhenHbsIsUnsupported() {
        // 5280 MHz (low 5G) + 5785 MHz (high 5G)
        assertFalse(DualWifiBandHelper.isBandConflicted(5785, 5280, allowDual5gHbs = true))
        assertTrue(DualWifiBandHelper.isBandRecommended(5785, 5280, allowDual5gHbs = true))
        assertFalse(DualWifiBandHelper.isDual5gUnsupportedConflict(5785, 5280, allowDual5gHbs = true))

        assertTrue(DualWifiBandHelper.isBandConflicted(5785, 5280, allowDual5gHbs = false))
        assertFalse(DualWifiBandHelper.isBandRecommended(5785, 5280, allowDual5gHbs = false))
        assertTrue(DualWifiBandHelper.isDual5gUnsupportedConflict(5785, 5280, allowDual5gHbs = false))

        // 5280 MHz (5G) + 2462 MHz (2.4G) stays recommended regardless of HBS
        assertFalse(DualWifiBandHelper.isBandConflicted(2462, 5280, allowDual5gHbs = false))
        assertTrue(DualWifiBandHelper.isBandRecommended(2462, 5280, allowDual5gHbs = false))
        assertFalse(DualWifiBandHelper.isDual5gUnsupportedConflict(2462, 5280, allowDual5gHbs = false))
    }
}

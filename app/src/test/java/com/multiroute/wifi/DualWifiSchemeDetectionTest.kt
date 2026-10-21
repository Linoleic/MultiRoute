package com.multiroute.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests simulating various real-world device parameters to test
 * ROM detection, Wi-Fi chipset probing, and auto-scheme resolution.
 */
class DualWifiSchemeDetectionTest {

    @Test
    fun testXiaomiPad6SProHyperOS() {
        // Xiaomi Pad 6S Pro 12.4: Snapdragon 8 Gen 2, FastConnect 7800, HyperOS 1.0
        val rom = DualWifiManager.detectRomType(
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            model = "24018RPACC",
            androidRelease = "14",
            apiLevel = 34,
            socModelOverride = "SM8550",
            miuiPropOverride = "V816.0.4.0.UNXCNXM"
        )
        val chip = DualWifiManager.detectWifiChipset(
            lsmodOutput = "wlan 13000000 0 - Live 0xffffff9010000000"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.XIAOMI_HYPEROS, rom.romType)
        assertEquals(DualWifiScheme.XIAOMI_SLAVE, scheme)
        assertTrue(chip.contains("Qualcomm FastConnect"))
    }

    @Test
    fun testXiaomi14ProHyperOS2() {
        // Xiaomi 14 Pro: Snapdragon 8 Gen 3, HyperOS 2.0
        val rom = DualWifiManager.detectRomType(
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            model = "23116PN5BC",
            androidRelease = "15",
            apiLevel = 35,
            socModelOverride = "SM8650",
            miuiPropOverride = "V816.0.24.0.UNBCNXM"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.XIAOMI_HYPEROS, rom.romType)
        assertEquals(DualWifiScheme.XIAOMI_SLAVE, scheme)
    }

    @Test
    fun testRedmiK70Pro() {
        // Redmi K70 Pro: Brand Redmi, HyperOS
        val rom = DualWifiManager.detectRomType(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "23117RK66C",
            androidRelease = "14",
            apiLevel = 34,
            socModelOverride = "SM8650",
            miuiPropOverride = "V816.0.12.0.UNMCNXM"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.XIAOMI_HYPEROS, rom.romType)
        assertEquals(DualWifiScheme.XIAOMI_SLAVE, scheme)
    }

    @Test
    fun testPocoF6Pro() {
        // POCO F6 Pro: Brand POCO
        val rom = DualWifiManager.detectRomType(
            manufacturer = "Xiaomi",
            brand = "POCO",
            model = "23113RKC6G",
            androidRelease = "14",
            apiLevel = 34,
            socModelOverride = "SM8550",
            miuiPropOverride = "V816.0.6.0.UNKMIXM"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.XIAOMI_HYPEROS, rom.romType)
        assertEquals(DualWifiScheme.XIAOMI_SLAVE, scheme)
    }

    @Test
    fun testGooglePixel7Pro() {
        // Google Pixel 7 Pro: Tensor G2 (GS201), Broadcom BCM4389 DBS
        val rom = DualWifiManager.detectRomType(
            manufacturer = "Google",
            brand = "google",
            model = "Pixel 7 Pro",
            androidRelease = "16",
            apiLevel = 36,
            socModelOverride = "GS201"
        )
        val chip = DualWifiManager.detectWifiChipset(
            lsmodOutput = "bcmdhd4389 4235264 0 - Live 0xffffff9008000000"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.AOSP_PIXEL, rom.romType)
        assertEquals(DualWifiScheme.AOSP_OVERLAY, scheme)
        assertEquals("Broadcom BCM4389 (DBS 双射频)", chip)
    }

    @Test
    fun testGooglePixel8Pro() {
        // Google Pixel 8 Pro: Tensor G3 (zuma), Broadcom Wi-Fi
        val rom = DualWifiManager.detectRomType(
            manufacturer = "Google",
            brand = "google",
            model = "Pixel 8 Pro",
            androidRelease = "15",
            apiLevel = 35,
            socModelOverride = "zuma"
        )
        val chip = DualWifiManager.detectWifiChipset(
            lsmodOutput = "bcmdhd 5100000 0 - Live 0xffffff9008000000"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.AOSP_PIXEL, rom.romType)
        assertEquals(DualWifiScheme.AOSP_OVERLAY, scheme)
        assertEquals("Broadcom Wi-Fi (DBS 双射频)", chip)
    }

    @Test
    fun testOnePlus12ColorOS() {
        // OnePlus 12: Snapdragon 8 Gen 3, ColorOS 14, FastConnect 7800
        val rom = DualWifiManager.detectRomType(
            manufacturer = "OnePlus",
            brand = "OnePlus",
            model = "PJD110",
            androidRelease = "14",
            apiLevel = 34,
            socModelOverride = "SM8650",
            oplusPropOverride = "V14.0.0"
        )
        val chip = DualWifiManager.detectWifiChipset(
            lsmodOutput = "qca_cld3_wlan 8900000 0 - Live"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.COLOROS_OPPO, rom.romType)
        assertEquals(DualWifiScheme.AOSP_OVERLAY, scheme)
        assertTrue(chip.contains("Qualcomm FastConnect"))
    }

    @Test
    fun testVivoX100OriginOS() {
        // vivo X100: Dimensity 9300, MediaTek Filogic, OriginOS 4
        val rom = DualWifiManager.detectRomType(
            manufacturer = "vivo",
            brand = "vivo",
            model = "V2309A",
            androidRelease = "14",
            apiLevel = 34,
            socModelOverride = "MT6989",
            vivoPropOverride = "OriginOS 4"
        )
        val chip = DualWifiManager.detectWifiChipset(
            lsmodOutput = "wmt_drv 120000 0 - Live"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.ORIGINOS_VIVO, rom.romType)
        assertEquals(DualWifiScheme.AOSP_OVERLAY, scheme)
        assertTrue(chip.contains("MediaTek Filogic"))
    }

    @Test
    fun testSamsungGalaxyS24Ultra() {
        // Samsung Galaxy S24 Ultra: One UI 6.1
        val rom = DualWifiManager.detectRomType(
            manufacturer = "samsung",
            brand = "samsung",
            model = "SM-S9280",
            androidRelease = "14",
            apiLevel = 34,
            socModelOverride = "SM8650"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.SAMSUNG_ONEUI, rom.romType)
        assertEquals(DualWifiScheme.ROOT_DIRECT, scheme)
    }

    @Test
    fun testGenericAospAndCustomRom() {
        // Sony Xperia / Nothing Phone / LineageOS / Generic AOSP
        val rom = DualWifiManager.detectRomType(
            manufacturer = "Sony",
            brand = "Sony",
            model = "XQ-EC54",
            androidRelease = "14",
            apiLevel = 34,
            socModelOverride = "SM8650"
        )
        val scheme = DualWifiManager.getEffectiveScheme(DualWifiScheme.AUTO, rom)

        assertEquals(RomType.GENERIC_OEM, rom.romType)
        assertEquals(DualWifiScheme.AOSP_OVERLAY, scheme)
    }

    @Test
    fun testManualSchemeSelectionOverridesAuto() {
        // If user explicitly configured AOSP_OVERLAY on Xiaomi or Samsung
        val xiaomiRom = DualWifiManager.detectRomType(
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            model = "24018RPACC",
            androidRelease = "14",
            apiLevel = 34,
            miuiPropOverride = "V816.0.4.0.UNXCNXM"
        )
        val effective = DualWifiManager.getEffectiveScheme(DualWifiScheme.AOSP_OVERLAY, xiaomiRom)
        assertEquals(DualWifiScheme.AOSP_OVERLAY, effective)

        // If user explicitly configured ROOT_DIRECT on Pixel
        val pixelRom = DualWifiManager.detectRomType(
            manufacturer = "Google",
            brand = "google",
            model = "Pixel 7 Pro",
            androidRelease = "16",
            apiLevel = 36
        )
        val effectivePixel = DualWifiManager.getEffectiveScheme(DualWifiScheme.ROOT_DIRECT, pixelRom)
        assertEquals(DualWifiScheme.ROOT_DIRECT, effectivePixel)
    }
    @Test
    fun testSameSsidDifferentBandsDisambiguation() {
        val sameSsid = "Office-5G"
        val primaryBssid = "40:31:3c:04:90:2e"
        val primaryFreq = 5260 // 5GHz Low

        // Candidate 1: Same SSID, 5GHz Low (Primary)
        val isPrimaryMatch = DualWifiManager.matchesConnected(
            candidateSsid = sameSsid,
            candidateBssid = primaryBssid,
            candidateFreq = 5260,
            connectedSsid = sameSsid,
            connectedBssid = primaryBssid,
            connectedFreq = primaryFreq
        )
        assertTrue("5GHz AP matching primary should be identified as primary", isPrimaryMatch)

        // Candidate 2: Same SSID, 2.4GHz band (different BSSID and frequency)
        val candidate24Bssid = "40:31:3c:04:90:2d"
        val is24GPrimaryMatch = DualWifiManager.matchesConnected(
            candidateSsid = sameSsid,
            candidateBssid = candidate24Bssid,
            candidateFreq = 2437,
            connectedSsid = sameSsid,
            connectedBssid = primaryBssid,
            connectedFreq = primaryFreq
        )
        assertFalse("2.4GHz AP with same SSID must NOT be identified as primary", is24GPrimaryMatch)

        // Candidate 3: Same SSID, 5GHz High band (e.g. 5745 MHz)
        val candidate5GHighBssid = "40:31:3c:04:90:2f"
        val is5GHighPrimaryMatch = DualWifiManager.matchesConnected(
            candidateSsid = sameSsid,
            candidateBssid = candidate5GHighBssid,
            candidateFreq = 5745,
            connectedSsid = sameSsid,
            connectedBssid = primaryBssid,
            connectedFreq = primaryFreq
        )
        assertFalse("5GHz High AP with same SSID but different band/BSSID must NOT be identified as primary", is5GHighPrimaryMatch)
    }

    @Test
    fun testSameSsidDualConnectedStateIsolation() {
        val sameSsid = "DualHome"
        val primaryBssid = "aa:bb:cc:dd:ee:01"
        val primaryFreq = 5240 // 5G Low
        val secondaryBssid = "aa:bb:cc:dd:ee:02"
        val secondaryFreq = 2412 // 2.4G

        // 5G candidate
        val cand5G_Primary = DualWifiManager.matchesConnected(
            candidateSsid = sameSsid,
            candidateBssid = primaryBssid,
            candidateFreq = 5240,
            connectedSsid = sameSsid,
            connectedBssid = primaryBssid,
            connectedFreq = primaryFreq
        )
        val cand5G_Secondary = DualWifiManager.matchesConnected(
            candidateSsid = sameSsid,
            candidateBssid = primaryBssid,
            candidateFreq = 5240,
            connectedSsid = sameSsid,
            connectedBssid = secondaryBssid,
            connectedFreq = secondaryFreq
        )
        assertTrue(cand5G_Primary)
        assertFalse(cand5G_Secondary)

        // 2.4G candidate
        val cand24G_Primary = DualWifiManager.matchesConnected(
            candidateSsid = sameSsid,
            candidateBssid = secondaryBssid,
            candidateFreq = 2412,
            connectedSsid = sameSsid,
            connectedBssid = primaryBssid,
            connectedFreq = primaryFreq
        )
        val cand24G_Secondary = DualWifiManager.matchesConnected(
            candidateSsid = sameSsid,
            candidateBssid = secondaryBssid,
            candidateFreq = 2412,
            connectedSsid = sameSsid,
            connectedBssid = secondaryBssid,
            connectedFreq = secondaryFreq
        )
        assertFalse(cand24G_Primary)
        assertTrue(cand24G_Secondary)
    }

    @Test
    fun testPrimaryCollisionDetection() {
        // 1. Same SSID matching primary Wi-Fi is detected as collision
        val sameSsidCollision = DualWifiManager.isPrimaryCollision(
            candidateSsid = "Wrt-F5G",
            candidateBssid = "40:31:3c:04:90:2e",
            primarySsid = "Wrt-F5G",
            primaryBssid = "40:31:3c:04:90:2e"
        )
        assertTrue(sameSsidCollision)

        // 2. Case-insensitive SSID match is detected as collision
        val caseInsensitiveCollision = DualWifiManager.isPrimaryCollision(
            candidateSsid = "wrt-f5g",
            candidateBssid = null,
            primarySsid = "Wrt-F5G",
            primaryBssid = "40:31:3c:04:90:2e"
        )
        assertTrue(caseInsensitiveCollision)

        // 3. Different SSID with same BSSID is detected as collision
        val sameBssidCollision = DualWifiManager.isPrimaryCollision(
            candidateSsid = "Wrt-F5G-Guest",
            candidateBssid = "40:31:3C:04:90:2E",
            primarySsid = "Wrt-F5G",
            primaryBssid = "40:31:3c:04:90:2e"
        )
        assertTrue(sameBssidCollision)

        // 4. Different secondary SSID and different BSSID is not a collision
        val safeSecondary = DualWifiManager.isPrimaryCollision(
            candidateSsid = "Hpkt",
            candidateBssid = "7e:b5:66:c3:f7:fd",
            primarySsid = "Wrt-F5G",
            primaryBssid = "40:31:3c:04:90:2e"
        )
        assertFalse(safeSecondary)

        // 5. When primary Wi-Fi is disconnected (null or empty), no collision
        val noPrimary = DualWifiManager.isPrimaryCollision(
            candidateSsid = "Hpkt",
            candidateBssid = "7e:b5:66:c3:f7:fd",
            primarySsid = null,
            primaryBssid = null
        )
        assertFalse(noPrimary)

        // 6. Masked BSSID (02:00:00:00:00:00) does not falsely trigger collision on different SSID
        val maskedBssid = DualWifiManager.isPrimaryCollision(
            candidateSsid = "Hpkt",
            candidateBssid = "02:00:00:00:00:00",
            primarySsid = "Wrt-F5G",
            primaryBssid = "02:00:00:00:00:00"
        )
        assertFalse(maskedBssid)
    }

    @Test
    fun testParseHotspotFromIwDev() {
        // Case 1: Active SoftAP on wlan2 (2.4GHz channel 6, 2437 MHz)
        val iwOutputWlan2 = listOf(
            "phy#0",
            "\tInterface wlan0",
            "\t\tifindex 31",
            "\t\twdev 0x1",
            "\t\taddr 48:74:12:34:56:78",
            "\t\ttype managed",
            "\t\tchannel 36 (5180 MHz), width: 80 MHz, center1: 5210 MHz",
            "\tInterface wlan2",
            "\t\tifindex 33",
            "\t\twdev 0x3",
            "\t\taddr 4a:74:12:34:56:7a",
            "\t\ttype AP",
            "\t\tchannel 6 (2437 MHz), width: 20 MHz, center1: 2437 MHz"
        )
        val hotspot1 = DualWifiManager.parseHotspotFromIwDev(iwOutputWlan2)
        assertTrue(hotspot1.isEnabled)
        assertEquals("wlan2", hotspot1.iface)
        assertEquals(2437, hotspot1.frequencyMhz)
        assertEquals("2.4 GHz", hotspot1.bandLabel)

        // Case 2: Active SoftAP on ap0 (5GHz channel 149, 5745 MHz)
        val iwOutputAp0 = listOf(
            "phy#0",
            "\tInterface wlan0",
            "\t\ttype managed",
            "\tInterface ap0",
            "\t\ttype AP",
            "\t\tchannel 149 (5745 MHz), width: 40 MHz"
        )
        val hotspot2 = DualWifiManager.parseHotspotFromIwDev(iwOutputAp0)
        assertTrue(hotspot2.isEnabled)
        assertEquals("ap0", hotspot2.iface)
        assertEquals(5745, hotspot2.frequencyMhz)
        assertEquals("5 GHz (高频)", hotspot2.bandLabel)

        // Case 3: No active SoftAP (only managed clients)
        val iwOutputNoAp = listOf(
            "phy#0",
            "\tInterface wlan0",
            "\t\ttype managed",
            "\tInterface wlan1",
            "\t\ttype managed"
        )
        val hotspot3 = DualWifiManager.parseHotspotFromIwDev(iwOutputNoAp)
        assertFalse(hotspot3.isEnabled)

        // Case 4: SoftAP interface present without channel/frequency yet
        val iwOutputApNoFreq = listOf(
            "phy#0",
            "\tInterface wlan2",
            "\t\ttype AP"
        )
        val hotspot4 = DualWifiManager.parseHotspotFromIwDev(iwOutputApNoFreq)
        assertTrue(hotspot4.isEnabled)
        assertEquals("wlan2", hotspot4.iface)
        assertEquals(0, hotspot4.frequencyMhz)
        assertEquals("已开启", hotspot4.bandLabel)
    }

    @Test
    fun testIsHotspotConflicted() {
        // Hotspot on 2.4GHz (2412 MHz)
        assertTrue(DualWifiManager.isHotspotConflicted(candidateFreq = 2437, hotspotFreq = 2412))
        assertFalse(DualWifiManager.isHotspotConflicted(candidateFreq = 5180, hotspotFreq = 2412))
        assertFalse(DualWifiManager.isHotspotConflicted(candidateFreq = 5745, hotspotFreq = 2412))

        // Hotspot on Low 5GHz (5180 MHz)
        assertTrue(DualWifiManager.isHotspotConflicted(candidateFreq = 5220, hotspotFreq = 5180))
        assertFalse(DualWifiManager.isHotspotConflicted(candidateFreq = 5745, hotspotFreq = 5180))
        assertFalse(DualWifiManager.isHotspotConflicted(candidateFreq = 2412, hotspotFreq = 5180))

        // Hotspot on High 5GHz (5745 MHz)
        assertTrue(DualWifiManager.isHotspotConflicted(candidateFreq = 5785, hotspotFreq = 5745))
        assertFalse(DualWifiManager.isHotspotConflicted(candidateFreq = 5220, hotspotFreq = 5745))

        // Invalid frequencies
        assertFalse(DualWifiManager.isHotspotConflicted(candidateFreq = 2412, hotspotFreq = 0))
        assertFalse(DualWifiManager.isHotspotConflicted(candidateFreq = 0, hotspotFreq = 2412))
    }

    @Test
    fun testParseHotspotFromDumpsysWifi() {
        // Case 1: Standard AOSP Pixel dumpsys wifi output with SoftApInfo
        val linesPixel = listOf(
            "mCurrentSoftApInfoMap {ap0=SoftApInfo{band=5GHz, frequency=5260, bandwidth=4, iface=ap0}}",
            "event_type=0,channel_frequency=5260,num_connected_clients=0"
        )
        val info1 = DualWifiManager.parseHotspotFromDumpsysWifi(linesPixel)
        assertTrue(info1.isEnabled)
        assertEquals("ap0", info1.iface)
        assertEquals(5260, info1.frequencyMhz)
        assertEquals("5 GHz (低频)", info1.bandLabel)

        // Case 2: 2.4GHz tethered event in dumpsys
        val lines24G = listOf(
            "mSoftApTetheredEvents:",
            "event_type=0,channel_frequency=2437,num_connected_clients=1"
        )
        val info2 = DualWifiManager.parseHotspotFromDumpsysWifi(lines24G)
        assertTrue(info2.isEnabled)
        assertEquals(2437, info2.frequencyMhz)
        assertEquals("2.4 GHz", info2.bandLabel)

        // Case 3: Empty or irrelevant lines
        val linesEmpty = listOf(
            "mWifiLogProto.SoftApManagerReturnCodeCounts:",
            "mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal=0"
        )
        val info3 = DualWifiManager.parseHotspotFromDumpsysWifi(linesEmpty)
        assertFalse(info3.isEnabled)
    }
}

package com.multiroute.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the LSPosed module state beacon and its evaluation logic.
 *
 * These cover the cases that the old boolean marker check could not express, above all
 * "system_server still runs an older module build after an APK update" (needs a soft reboot)
 * and "module loaded but hooks were never installed".
 */
class ModuleStateTest {

    private val testBuildId = "762e60d"

    private fun beacon(
        pid: Int = 3278,
        version: Long = 6L,
        buildId: String = testBuildId,
        boot: Long = 60_000L,
        ts: Long = 1_791_000_000_000L,
        cs: Int = 3,
        slave: Int = 3,
        dual: Int = 1,
        stage: String = "ready"
    ) = ModuleStateParser.buildBeacon(pid, version, buildId, boot, ts, cs, slave, dual, stage)

    private fun evaluate(
        rawBeacon: String?,
        installedVersion: Long = 6L,
        installedBuildId: String = testBuildId,
        legacyPid: Int? = null,
        ownerIsSystemServer: Boolean? = true,
        nowElapsedMs: Long = 3_600_000L
    ): ModuleState = ModuleStateEvaluator.evaluate(
        ModuleStateEvaluator.Inputs(
            beacon = ModuleStateParser.parseBeacon(rawBeacon),
            legacyMarkerPid = legacyPid,
            installedVersionCode = installedVersion,
            installedBuildId = installedBuildId,
            nowElapsedMs = nowElapsedMs,
            ownerIsSystemServer = ownerIsSystemServer
        )
    )

    @Test
    fun testCompactBeaconFitsPropertyLimitAndParses() {
        val compact = ModuleStateParser.buildCompactBeacon(
            pid = 27678,
            moduleVersionCode = 5L,
            buildId = "762e60d-dirty",
            bootElapsedMs = 62_582_000L,
            connectivityHooks = 3,
            slaveWifiHooks = 3,
            dualStaHooks = 1,
            stage = "ready"
        )

        // Android caps system property values at ~92 bytes.
        assertTrue("compact beacon is ${compact.length} bytes", compact.length <= 92)

        val parsed = ModuleStateParser.parseBeacon(compact)
        assertNotNull(parsed)
        assertEquals(27678, parsed!!.systemServerPid)
        assertEquals(5L, parsed.moduleVersionCode)
        assertEquals("762e60d-dirty", parsed.buildId)
        assertEquals(62_582_000L, parsed.bootElapsedMs) // carried as seconds, exposed as ms
        assertEquals(3, parsed.connectivityHooks)
        assertEquals(3, parsed.slaveWifiHooks)
        assertEquals(1, parsed.dualStaHooks)
        assertEquals("ready", parsed.stage)
    }

    @Test
    fun testCompactBeaconEvaluatesLikeTheFullOne() {
        val compact = ModuleStateParser.buildCompactBeacon(
            pid = 27678,
            moduleVersionCode = 5L,
            buildId = testBuildId,
            bootElapsedMs = 60_000L,
            connectivityHooks = 3,
            slaveWifiHooks = 3,
            dualStaHooks = 1,
            stage = "ready"
        )

        assertEquals(ModuleStatus.ACTIVE, evaluate(rawBeacon = compact, installedVersion = 5L).status)
    }

    @Test
    fun testBeaconRoundTrip() {
        val parsed = ModuleStateParser.parseBeacon(beacon())

        assertNotNull(parsed)
        assertEquals(3278, parsed!!.systemServerPid)
        assertEquals(6L, parsed.moduleVersionCode)
        assertEquals(testBuildId, parsed.buildId)
        assertEquals(60_000L, parsed.bootElapsedMs)
        assertEquals(1_791_000_000_000L, parsed.writtenAtMs)
        assertEquals(3, parsed.connectivityHooks)
        assertEquals(3, parsed.slaveWifiHooks)
        assertEquals(1, parsed.dualStaHooks)
        assertEquals("ready", parsed.stage)
        assertEquals(7, parsed.totalHooks)
    }

    @Test
    fun testBeaconAcceptsCompactLeadingFormatToken() {
        // Older/alternative writers may emit "v1;pid=.." instead of "v=1;pid=..".
        val parsed = ModuleStateParser.parseBeacon(beacon().replace("v=1;", "v1;"))

        assertNotNull(parsed)
        assertEquals(1, parsed!!.formatVersion)
        assertEquals(3278, parsed.systemServerPid)
    }

    @Test
    fun testBeaconParsingRejectsGarbage() {
        assertNull(ModuleStateParser.parseBeacon(null))
        assertNull(ModuleStateParser.parseBeacon(""))
        assertNull(ModuleStateParser.parseBeacon("3278:1791015204730"))     // legacy marker format
        assertNull(ModuleStateParser.parseBeacon("v1;ver=6;boot=1;ts=1"))   // no pid
        assertNull(ModuleStateParser.parseBeacon("v9;pid=3278;ver=6"))      // unknown future format
        assertNull(ModuleStateParser.parseBeacon("v1;pid=0;ver=6"))         // invalid pid
    }

    @Test
    fun testLegacyMarkerParsing() {
        assertEquals(3278, ModuleStateParser.parseLegacyMarkerPid("3278:1791015204730"))
        assertEquals(3278, ModuleStateParser.parseLegacyMarkerPid(" 3278:1 "))
        assertNull(ModuleStateParser.parseLegacyMarkerPid(beacon()))        // new format is not a legacy marker
        assertNull(ModuleStateParser.parseLegacyMarkerPid("abc:1"))
        assertNull(ModuleStateParser.parseLegacyMarkerPid(""))
        assertNull(ModuleStateParser.parseLegacyMarkerPid(null))
    }

    @Test
    fun testNotActiveWhenNoEvidence() {
        val state = evaluate(rawBeacon = null)

        assertEquals(ModuleStatus.NOT_ACTIVE, state.status)
        assertFalse(state.isLoadedInSystemServer)
        assertFalse(state.isFullyOperational)
    }

    @Test
    fun testLegacyMarkerIsLoadedButUnverifiable() {
        val state = evaluate(rawBeacon = null, legacyPid = 3278, ownerIsSystemServer = true)

        assertEquals(ModuleStatus.LEGACY_UNVERIFIED, state.status)
        assertTrue(state.isLoadedInSystemServer)
        assertFalse(state.isFullyOperational)
        assertEquals(com.multiroute.R.string.module_detail_legacy, state.detailRes)
    }

    @Test
    fun testStaleWhenRecordedOwnerIsNotSystemServer() {
        val fromBeacon = evaluate(rawBeacon = beacon(), ownerIsSystemServer = false)
        assertEquals(ModuleStatus.STALE, fromBeacon.status)
        assertEquals(com.multiroute.R.string.module_detail_beacon_stale, fromBeacon.detailRes)

        val fromMarker = evaluate(rawBeacon = null, legacyPid = 4242, ownerIsSystemServer = false)
        assertEquals(ModuleStatus.STALE, fromMarker.status)
        assertEquals(com.multiroute.R.string.module_detail_marker_stale, fromMarker.detailRes)
    }

    @Test
    fun testOutdatedWhenLoadedBuildDiffersFromInstalledApk() {
        // system_server loaded v5 while the installed APK is already v6: hot reload silently failed.
        val state = evaluate(rawBeacon = beacon(version = 5L), installedVersion = 6L)

        assertEquals(ModuleStatus.OUTDATED, state.status)
        assertTrue(state.isLoadedInSystemServer)
        assertFalse(state.isFullyOperational)
        assertEquals(com.multiroute.R.string.module_detail_outdated, state.detailRes)
        assertEquals(4, state.detailArgs.size)
    }

    @Test
    fun testPartialWhenConnectivityHooksAreMissing() {
        val state = evaluate(rawBeacon = beacon(cs = 0))

        assertEquals(ModuleStatus.PARTIAL, state.status)
        assertTrue(state.isLoadedInSystemServer)
        assertFalse(state.isFullyOperational)
    }

    @Test
    fun testActiveWhenHooksInstalledAndVersionMatches() {
        val state = evaluate(rawBeacon = beacon())

        assertEquals(ModuleStatus.ACTIVE, state.status)
        assertTrue(state.isLoadedInSystemServer)
        assertTrue(state.isFullyOperational)
        assertEquals(com.multiroute.R.string.module_detail_active, state.detailRes)
        assertEquals(listOf(3, 4), state.detailArgs)
    }

    @Test
    fun testUnknownOwnerDoesNotProduceFalseStaleVerdict() {
        // Without root the owner cannot be verified; that must not degrade to STALE.
        val state = evaluate(rawBeacon = beacon(), ownerIsSystemServer = null)
        assertEquals(ModuleStatus.ACTIVE, state.status)

        val legacy = evaluate(rawBeacon = null, legacyPid = 3278, ownerIsSystemServer = null)
        assertEquals(ModuleStatus.LEGACY_UNVERIFIED, legacy.status)
    }

    @Test
    fun testBeaconFromLaterBootIsStale() {
        // Settings.Global survives reboots: a beacon written after a longer uptime than the current
        // one cannot belong to the running system_server.
        val state = evaluate(rawBeacon = beacon(boot = 10_000_000L), nowElapsedMs = 1_000L)

        assertEquals(ModuleStatus.STALE, state.status)
        assertEquals(com.multiroute.R.string.module_detail_late_boot, state.detailRes)
    }

    @Test
    fun testBuildIdSkewIsOutdatedEvenWithSameVersionCode() {
        // The development trap: same versionCode (commit count unchanged for local edits) but the
        // loaded hook code is an older/different build than the installed APK.
        val state = evaluate(rawBeacon = beacon(buildId = "oldsha-dirty"), installedBuildId = "newsha")

        assertEquals(ModuleStatus.OUTDATED, state.status)
        assertEquals(4, state.detailArgs.size)
    }

    @Test
    fun testBlankBuildIdOnEitherSideIsIgnored() {
        assertEquals(
            ModuleStatus.ACTIVE,
            evaluate(rawBeacon = beacon(buildId = ""), installedBuildId = testBuildId).status
        )
        assertEquals(
            ModuleStatus.ACTIVE,
            evaluate(rawBeacon = beacon(buildId = testBuildId), installedBuildId = "").status
        )
    }

    @Test
    fun testVersionUnknownOnEitherSideIsNotTreatedAsSkew() {
        // installedVersionCode == 0 means "could not be read" - do not report a false OUTDATED.
        val bothUnknown = evaluate(rawBeacon = beacon(version = 0L), installedVersion = 0L)
        assertEquals(ModuleStatus.ACTIVE, bothUnknown.status)

        // A module build that cannot report its own version must not be flagged as outdated either.
        val moduleVersionUnknown = evaluate(rawBeacon = beacon(version = 0L), installedVersion = 6L)
        assertEquals(ModuleStatus.ACTIVE, moduleVersionUnknown.status)
    }
}

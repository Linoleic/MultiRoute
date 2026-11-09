package com.multiroute.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTrafficMonitorTest {

    @Test
    fun `parse handles standard proc net dev lines and tight colons`() {
        val sampleLines = listOf(
            "Inter-|   Receive                                                |  Transmit",
            " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
            "    lo: 256206014  205486    0    0    0     0          0         0 256206014  205486    0    0    0     0       0          0",
            " wlan0: 3058845302 3340593    0    0    0     0          0         0 414285519 1280419    0    0    0     0       0          0",
            "rmnet_data0:98765432100 123456 0 0 0 0 0 0 12345678900 654321 0 0 0 0 0 0",
            " wlan1:       0       0    0    0    0     0          0         0        0       0    0    0    0     0       0          0",
            "sh: cat: /proc/net/dev: Permission denied",
            "bad: 10 20 30",
            "neg: -1 0 0 0 0 0 0 0 100 0 0 0 0 0 0 0"
        )

        val parsed = ProcNetDevParser.parse(sampleLines)
        assertEquals(4, parsed.size)
        assertEquals(InterfaceByteCounters(256206014L, 256206014L), parsed["lo"])
        assertEquals(InterfaceByteCounters(3058845302L, 414285519L), parsed["wlan0"])
        assertEquals(InterfaceByteCounters(98765432100L, 12345678900L), parsed["rmnet_data0"])
        assertEquals(InterfaceByteCounters(0L, 0L), parsed["wlan1"])
    }

    @Test
    fun `formatter produces compact locale-stable rates and totals`() {
        assertEquals("0 B/s", TrafficRateFormatter.formatSpeed(-50L))
        assertEquals("512 B/s", TrafficRateFormatter.formatSpeed(512L))
        assertEquals("1.5 KB/s", TrafficRateFormatter.formatSpeed(1536L))
        assertEquals("150 KB/s", TrafficRateFormatter.formatSpeed(150L * 1024L))
        assertEquals("4.8 MB/s", TrafficRateFormatter.formatSpeed((4.8 * 1024 * 1024).toLong()))
        assertEquals("120 MB/s", TrafficRateFormatter.formatSpeed(120L * 1024L * 1024L))
        assertEquals("1.25 GB/s", TrafficRateFormatter.formatSpeed((1.25 * 1024 * 1024 * 1024).toLong()))

        assertEquals("500 B", TrafficRateFormatter.formatTotalBytes(500L))
        assertEquals("12.0 KB", TrafficRateFormatter.formatTotalBytes(12L * 1024L))
        assertEquals("32.5 MB", TrafficRateFormatter.formatTotalBytes((32.5 * 1024 * 1024).toLong()))
        assertEquals("2.50 GB", TrafficRateFormatter.formatTotalBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `tracker measures baseline then scaled instantaneous speeds and 30-sample window`() {
        var now = 1_000L
        var rxWlan0 = 1_000_000L
        var txWlan0 = 500_000L

        val tracker = ChannelTrafficTracker(
            maxHistoryPoints = 4,
            nowMs = { now },
            readProcNetDev = {
                procLines("wlan0" to (rxWlan0 to txWlan0), "lo" to (100L to 100L))
            }
        )

        // Tick 0: baseline only (lo ignored because activeInterfaces specifies wlan0)
        val t0 = tracker.sample(listOf("wlan0"))
        assertEquals(setOf("wlan0"), t0.keys)
        val s0 = t0.getValue("wlan0")
        assertFalse(s0.hasSpeedSample)
        assertEquals(1_000_000L, s0.totalRxBytes)
        assertEquals(0L, s0.rxBytesPerSec)
        assertTrue(s0.history.isEmpty())

        // Tick 1: +500ms, +50_000 RX bytes => 100_000 B/s
        now += 500L
        rxWlan0 += 50_000L
        txWlan0 += 5_000L
        val t1 = tracker.sample(listOf("wlan0")).getValue("wlan0")
        assertTrue(t1.hasSpeedSample)
        assertEquals(100_000L, t1.rxBytesPerSec)
        assertEquals(10_000L, t1.txBytesPerSec)
        assertEquals(100_000L, t1.peakRxBytesPerSec)
        assertEquals(listOf(TrafficPoint(100_000L, 10_000L)), t1.history)

        // Push 4 more steady samples at 1s (+10_000 B/s) -> initial 100_000 peak must roll off
        repeat(4) {
            now += 1_000L
            rxWlan0 += 10_000L
            txWlan0 += 2_000L
            tracker.sample(listOf("wlan0"))
        }
        now += 1_000L
        rxWlan0 += 20_000L
        txWlan0 += 4_000L
        val latest = tracker.sample(listOf("wlan0")).getValue("wlan0")
        assertEquals(4, latest.history.size)
        assertEquals(20_000L, latest.peakRxBytesPerSec)
        assertEquals(4_000L, latest.peakTxBytesPerSec)
    }

    @Test
    fun `tracker clamps negative deltas when interface resets or reconnects`() {
        var now = 1_000L
        var rx = 5_000_000L
        var tx = 1_000_000L

        val tracker = ChannelTrafficTracker(
            nowMs = { now },
            readProcNetDev = { procLines("wlan1" to (rx to tx)) }
        )

        tracker.sample(listOf("wlan1"))
        now += 1_000L
        rx = 1_500L // Interface recreated; counters wrapped back down
        tx = 500L

        val resetTick = tracker.sample(listOf("wlan1")).getValue("wlan1")
        assertTrue(resetTick.hasSpeedSample)
        assertEquals(0L, resetTick.rxBytesPerSec)
        assertEquals(0L, resetTick.txBytesPerSec)
        assertEquals(1_500L, resetTick.totalRxBytes)

        // Next tick computes accurately from the new baseline
        now += 1_000L
        rx += 8_000L
        tx += 2_000L
        val nextTick = tracker.sample(listOf("wlan1")).getValue("wlan1")
        assertEquals(8_000L, nextTick.rxBytesPerSec)
        assertEquals(2_000L, nextTick.txBytesPerSec)
    }

    @Test
    fun `resetBaseline prevents multi-second gap spikes while clearing only stale history`() {
        var now = 1_000L
        var rx = 1_000L

        val tracker = ChannelTrafficTracker(
            staleHistoryGapMs = 15_000L,
            nowMs = { now },
            readProcNetDev = { procLines("wlan0" to (rx to 0L)) }
        )

        tracker.sample(listOf("wlan0"))
        now += 1_000L
        rx += 2_000L
        val tick1 = tracker.sample(listOf("wlan0")).getValue("wlan0")
        assertEquals(1, tick1.history.size)

        // Brief tab switch (3s <= 15s) + large download while away: rebase without 3s smear, preserve history
        now += 3_000L
        rx += 999_000L
        tracker.resetBaseline()
        val resumeShort = tracker.sample(listOf("wlan0")).getValue("wlan0")
        assertFalse(resumeShort.hasSpeedSample)
        assertEquals(1, resumeShort.history.size)

        now += 1_000L
        rx += 5_000L
        val afterResume = tracker.sample(listOf("wlan0")).getValue("wlan0")
        assertEquals(5_000L, afterResume.rxBytesPerSec)
        assertEquals(2, afterResume.history.size)

        // Long background pause (> 15s): clear stale history too
        now += 20_000L
        rx += 100_000L
        tracker.resetBaseline()
        val resumeStale = tracker.sample(listOf("wlan0")).getValue("wlan0")
        assertFalse(resumeStale.hasSpeedSample)
        assertTrue(resumeStale.history.isEmpty())
    }

    @Test
    fun `tracker backs off repeated proc failures and rebases cleanly upon root recovery`() {
        var now = 1_000L
        var rootWorks = true
        var procReads = 0
        var procRx = 10_000_000L

        val tracker = ChannelTrafficTracker(
            procRetryCooldownMs = 15_000L,
            nowMs = { now },
            readProcNetDev = {
                procReads++
                if (rootWorks) procLines("wlan0" to (procRx to 0L)) else emptyList()
            }
        )

        // Establish an active baseline before root temporarily fails
        assertTrue(tracker.sample(listOf("wlan0")).containsKey("wlan0"))
        assertEquals(1, procReads)

        // Two failed /proc reads -> returns empty map & engages cooldown + clears baseline
        rootWorks = false
        now += 1_000L
        assertTrue(tracker.sample(listOf("wlan0")).isEmpty())
        now += 1_000L
        assertTrue(tracker.sample(listOf("wlan0")).isEmpty())
        assertEquals(3, procReads)

        // During cooldown window, proc is NOT retried on every 1s poll
        now += 1_000L
        assertTrue(tracker.sample(listOf("wlan0")).isEmpty())
        now += 5_000L
        assertTrue(tracker.sample(listOf("wlan0")).isEmpty())
        assertEquals(3, procReads)

        // Root recovers and clearProcCooldown() fires: next tick rebases safely without smearing gap
        rootWorks = true
        procRx += 500_000L
        tracker.clearProcCooldown()
        now += 1_000L
        val recovered0 = tracker.sample(listOf("wlan0")).getValue("wlan0")
        assertEquals(4, procReads)
        assertFalse(recovered0.hasSpeedSample)
        assertEquals(10_500_000L, recovered0.totalRxBytes)

        now += 1_000L
        procRx += 12_000L
        val recovered1 = tracker.sample(listOf("wlan0")).getValue("wlan0")
        assertTrue(recovered1.hasSpeedSample)
        assertEquals(12_000L, recovered1.rxBytesPerSec)

        // Also verifies natural cooldown expiry retries /proc automatically
        rootWorks = false
        now += 1_000L
        tracker.sample(listOf("wlan0"))
        now += 1_000L
        tracker.sample(listOf("wlan0"))
        val callsAtCooldown = procReads
        now += 14_000L
        assertTrue(tracker.sample(listOf("wlan0")).isEmpty())
        assertEquals(callsAtCooldown, procReads)

        rootWorks = true
        procRx += 20_000L
        now += 2_000L // Past 15s cooldown
        val expired0 = tracker.sample(listOf("wlan0")).getValue("wlan0")
        assertEquals(callsAtCooldown + 1, procReads)
        assertFalse(expired0.hasSpeedSample)
    }

    @Test
    fun `tracker prunes dropped interfaces and ignores non-positive elapsedMs`() {
        var now = 1_000L
        var active = listOf("wlan0", "wlan1")
        var wlan0Rx = 100L
        var wlan1Rx = 200L

        val tracker = ChannelTrafficTracker(
            nowMs = { now },
            readProcNetDev = {
                procLines("wlan0" to (wlan0Rx to 0L), "wlan1" to (wlan1Rx to 0L))
            }
        )

        tracker.sample(active)
        // Duplicate timestamp (elapsedMs == 0): must not crash or record bogus speed
        val sameTs = tracker.sample(active).getValue("wlan0")
        assertFalse(sameTs.hasSpeedSample)

        now += 1_000L
        wlan0Rx += 1_000L
        active = listOf("wlan0")
        val pruned = tracker.sample(active)
        assertEquals(setOf("wlan0"), pruned.keys)
    }

    private fun procLines(vararg entries: Pair<String, Pair<Long, Long>>): List<String> = buildList {
        add("Inter-|   Receive | Transmit")
        add(" face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed")
        for ((iface, counts) in entries) {
            val (rx, tx) = counts
            add(" $iface: $rx 10 0 0 0 0 0 0 $tx 10 0 0 0 0 0 0")
        }
    }
}

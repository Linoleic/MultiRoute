package com.multiroute.util

import android.os.SystemClock
import java.util.Locale
import kotlin.math.roundToLong

data class InterfaceByteCounters(
    val rxBytes: Long,
    val txBytes: Long
)

data class TrafficPoint(
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long
)

data class ChannelTrafficStats(
    val interfaceName: String,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    val peakRxBytesPerSec: Long = 0L,
    val peakTxBytesPerSec: Long = 0L,
    val history: List<TrafficPoint> = emptyList(),
    val hasSpeedSample: Boolean = false
) {
    val peakCombinedBytesPerSec: Long
        get() = maxOf(peakRxBytesPerSec, peakTxBytesPerSec)
}

/**
 * Parses `/proc/net/dev` snapshots emitted by Linux kernels (Android 11–16).
 */
object ProcNetDevParser {
    private val WHITESPACE = Regex("\\s+")

    fun parse(lines: List<String>): Map<String, InterfaceByteCounters> {
        if (lines.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, InterfaceByteCounters>()
        for (rawLine in lines) {
            val colonIdx = rawLine.indexOf(':')
            if (colonIdx <= 0) continue

            val iface = rawLine.substring(0, colonIdx).trim()
            if (iface.isEmpty() || iface.any { it.isWhitespace() }) continue

            val payload = rawLine.substring(colonIdx + 1).trim()
            if (payload.isEmpty()) continue

            val tokens = payload.split(WHITESPACE)
            // Receive columns [0..7], Transmit columns [8..15]:
            // 0 = rx_bytes, 8 = tx_bytes
            if (tokens.size < 9) continue
            val rx = tokens[0].toLongOrNull() ?: continue
            val tx = tokens[8].toLongOrNull() ?: continue
            if (rx < 0L || tx < 0L) continue

            result[iface] = InterfaceByteCounters(rxBytes = rx, txBytes = tx)
        }
        return result
    }
}

/**
 * Locale-stable compact byte & throughput formatter for tabular display.
 */
object TrafficRateFormatter {
    private const val KIB = 1024L
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * 1024L * 1024L

    fun formatSpeed(bytesPerSec: Long): String {
        val safe = bytesPerSec.coerceAtLeast(0L)
        return when {
            safe < KIB -> "$safe B/s"
            safe < 100L * KIB -> String.format(Locale.US, "%.1f KB/s", safe / KIB.toDouble())
            safe < MIB -> String.format(Locale.US, "%.0f KB/s", safe / KIB.toDouble())
            safe < 100L * MIB -> String.format(Locale.US, "%.1f MB/s", safe / MIB.toDouble())
            safe < GIB -> String.format(Locale.US, "%.0f MB/s", safe / MIB.toDouble())
            else -> String.format(Locale.US, "%.2f GB/s", safe / GIB.toDouble())
        }
    }

    fun formatTotalBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < KIB -> "$safe B"
            safe < MIB -> String.format(Locale.US, "%.1f KB", safe / KIB.toDouble())
            safe < GIB -> String.format(Locale.US, "%.1f MB", safe / MIB.toDouble())
            else -> String.format(Locale.US, "%.2f GB", safe / GIB.toDouble())
        }
    }
}

/**
 * Computes instantaneous RX/TX rates and rolling 30-sample throughput histories.
 *
 * Reads `/proc/net/dev` atomically via [SuHelper] (backed by the pooled persistent `su` shell in
 * [RootShellExecutor]), providing uniform sub-second counter accuracy from Android 11 through 16
 * without system service cache jitter. Engages a failure cooldown when root is unavailable so
 * denied superuser sessions are not retried on every 1 Hz tick.
 */
class ChannelTrafficTracker(
    private val maxHistoryPoints: Int = DEFAULT_HISTORY_POINTS,
    private val procRetryCooldownMs: Long = DEFAULT_PROC_COOLDOWN_MS,
    private val staleHistoryGapMs: Long = DEFAULT_STALE_HISTORY_GAP_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val readProcNetDev: () -> List<String> = {
        SuHelper.executeShellWithOutput("cat /proc/net/dev", timeoutSeconds = 2)
    }
) {
    companion object {
        const val DEFAULT_HISTORY_POINTS = 30
        const val DEFAULT_PROC_COOLDOWN_MS = 15_000L
        const val DEFAULT_STALE_HISTORY_GAP_MS = 15_000L
    }

    private data class PreviousReading(
        val counters: InterfaceByteCounters,
        val timestampMs: Long
    )

    private val previousReadings = HashMap<String, PreviousReading>()
    private val histories = HashMap<String, ArrayDeque<TrafficPoint>>()
    private var lastSampleAtMs: Long = Long.MIN_VALUE
    private var procCooldownUntilMs: Long = Long.MIN_VALUE
    private var consecutiveProcFailures: Int = 0

    @Synchronized
    fun resetBaseline() {
        previousReadings.clear()
        val now = nowMs()
        if (lastSampleAtMs != Long.MIN_VALUE && now - lastSampleAtMs > staleHistoryGapMs) {
            histories.clear()
        }
    }

    @Synchronized
    fun clearProcCooldown() {
        procCooldownUntilMs = Long.MIN_VALUE
        consecutiveProcFailures = 0
    }

    @Synchronized
    fun sample(activeInterfaces: Collection<String>): Map<String, ChannelTrafficStats> {
        val now = nowMs()
        if (lastSampleAtMs != Long.MIN_VALUE && now - lastSampleAtMs > staleHistoryGapMs) {
            previousReadings.clear()
            histories.clear()
        }

        val counters = readProcCounters(now)
        if (counters.isEmpty()) {
            return emptyMap()
        }
        lastSampleAtMs = now

        val targetIfaces: Set<String> =
            if (activeInterfaces.isNotEmpty()) activeInterfaces.toSet() else counters.keys

        // Drop disappeared interfaces so memory stays bounded to currently live links.
        previousReadings.keys.retainAll(targetIfaces)
        histories.keys.retainAll(targetIfaces)

        val result = LinkedHashMap<String, ChannelTrafficStats>(targetIfaces.size)
        for (iface in targetIfaces) {
            val current = counters[iface] ?: continue
            val prev = previousReadings[iface]
            val queue = histories.getOrPut(iface) { ArrayDeque(maxHistoryPoints) }

            var hasSpeed = false
            var rxSpeed = 0L
            var txSpeed = 0L

            if (prev != null) {
                val elapsedMs = now - prev.timestampMs
                if (elapsedMs > 0L) {
                    val rxDelta = if (current.rxBytes >= prev.counters.rxBytes) {
                        current.rxBytes - prev.counters.rxBytes
                    } else {
                        0L
                    }
                    val txDelta = if (current.txBytes >= prev.counters.txBytes) {
                        current.txBytes - prev.counters.txBytes
                    } else {
                        0L
                    }
                    rxSpeed = ((rxDelta.toDouble() * 1000.0) / elapsedMs.toDouble()).roundToLong()
                    txSpeed = ((txDelta.toDouble() * 1000.0) / elapsedMs.toDouble()).roundToLong()
                    hasSpeed = true

                    if (queue.size >= maxHistoryPoints) {
                        queue.removeFirst()
                    }
                    queue.addLast(TrafficPoint(rxBytesPerSec = rxSpeed, txBytesPerSec = txSpeed))
                }
            }

            previousReadings[iface] = PreviousReading(counters = current, timestampMs = now)

            val historySnapshot = queue.toList()
            val peakRx = historySnapshot.maxOfOrNull { it.rxBytesPerSec } ?: rxSpeed
            val peakTx = historySnapshot.maxOfOrNull { it.txBytesPerSec } ?: txSpeed

            result[iface] = ChannelTrafficStats(
                interfaceName = iface,
                rxBytesPerSec = rxSpeed,
                txBytesPerSec = txSpeed,
                totalRxBytes = current.rxBytes,
                totalTxBytes = current.txBytes,
                peakRxBytesPerSec = peakRx,
                peakTxBytesPerSec = peakTx,
                history = historySnapshot,
                hasSpeedSample = hasSpeed
            )
        }

        return result
    }

    private fun readProcCounters(now: Long): Map<String, InterfaceByteCounters> {
        if (now < procCooldownUntilMs) {
            return emptyMap()
        }
        val procParsed = ProcNetDevParser.parse(readProcNetDev())
        if (procParsed.isNotEmpty()) {
            consecutiveProcFailures = 0
            procCooldownUntilMs = Long.MIN_VALUE
            return procParsed
        }
        consecutiveProcFailures++
        if (consecutiveProcFailures >= 2) {
            procCooldownUntilMs = now + procRetryCooldownMs
            previousReadings.clear()
        }
        return emptyMap()
    }
}

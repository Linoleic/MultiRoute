package com.multiroute.cellular

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.multiroute.util.SuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class CellularSubscription(val subId: Int, val slot: Int, val carrier: String, val key: String?, val isDefault: Boolean, val networks: Map<String, String>)

internal fun List<CellularSubscription>.subscriptionForNetwork(netId: String, iface: String): CellularSubscription? =
    singleOrNull { it.networks[netId] == iface }

/** A failed read has no authority to replace the last complete inventory. */
internal class CellularSubscriptionCache(private val now: () -> Long) {
    private var readAt: Long? = null
    private var latest: List<CellularSubscription>? = null
    private var lastConfirmed = emptyList<CellularSubscription>()

    fun invalidate() {
        readAt = null
    }

    fun read(useLastConfirmed: Boolean, load: () -> List<CellularSubscription>?): List<CellularSubscription> {
        if (readAt?.let { now() - it < 3_000L } != true) {
            latest = load()
            readAt = now()
            latest?.let { lastConfirmed = it }
        }
        return latest ?: if (useLastConfirmed) lastConfirmed else emptyList()
    }
}

object CellularSubscriptions {
    private val mutex = Mutex()
    private val cache = CellularSubscriptionCache(SystemClock::elapsedRealtime)

    @Volatile
    var lastError: String? = null
        private set

    internal fun summarizeInventoryError(output: List<String>, error: Throwable): String {
        val raw = output
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .let { lines ->
                lines.firstOrNull { line ->
                    listOf("Exception", "Error", "SecurityException", "Caused by", "failed")
                        .any { marker -> line.contains(marker, ignoreCase = true) }
                } ?: lines.firstOrNull()
            }
            ?: "${error.javaClass.simpleName}: ${error.message ?: "no output"}"
        val redacted = raw
            .replace(Regex("89[0-9A-Fa-f]{12,}"), "<redacted>")
            .replace(Regex("\\b\\d{10,}\\b"), "<redacted>")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (redacted.length > 180) redacted.take(177) + "..." else redacted
    }

    suspend fun invalidate() = mutex.withLock { cache.invalidate() }

    /** Only callers matching live network IDs and interfaces should opt in to the fallback. */
    suspend fun read(context: Context, useLastConfirmed: Boolean = false): List<CellularSubscription> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.read(useLastConfirmed) { readInventory(context) }
            }
        }

    private fun readInventory(context: Context): List<CellularSubscription>? {
        val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
        val output = SuHelper.executeShellWithOutput(
            "/system/bin/env CLASSPATH='$apk' /system/bin/app_process /system/bin com.multiroute.cellular.CellularInventory",
            timeoutSeconds = 8
        )
        return runCatching {
            val line = output.first { it.startsWith("MULTIROUTE_SIM=") }.removePrefix("MULTIROUTE_SIM=")
            val array = JSONArray(line)
            val list = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val networks = item.getJSONArray("networks")
                CellularSubscription(
                    subId = item.getInt("subId"),
                    slot = item.getInt("slot"),
                    carrier = item.optString("carrier"),
                    key = item.optString("key").takeIf(CellularTargets::isSim),
                    isDefault = item.optBoolean("default"),
                    networks = (0 until networks.length()).associate { networkIndex ->
                        val network = networks.getJSONObject(networkIndex)
                        network.getString("netId") to network.getString("iface")
                    }
                )
            }
            lastError = null
            list
        }.onFailure {
            val detail = summarizeInventoryError(output, it)
            lastError = detail
            Log.w("MultiRoute-Cellular", "SIM inventory unavailable ($detail); retaining last confirmed network identities", it)
        }.getOrNull()
    }
}

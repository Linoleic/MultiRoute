package com.multiroute.cellular

import android.content.Context
import android.os.SystemClock
import com.multiroute.util.SuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class CellularSubscription(val subId: Int, val slot: Int, val carrier: String, val key: String?, val isDefault: Boolean, val networks: Map<String, String>)

object CellularSubscriptions {
    private val mutex = Mutex()
    private var stamp = -10_000L
    private var cached = emptyList<CellularSubscription>()

    suspend fun invalidate() { mutex.withLock { stamp = -10_000L } }

    suspend fun read(context: Context): List<CellularSubscription> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (now - stamp < 3000) return@withLock cached
            val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
            val output = SuHelper.executeShellWithOutput(
                "/system/bin/env CLASSPATH='$apk' /system/bin/app_process /system/bin com.multiroute.cellular.CellularInventory", timeoutSeconds = 8)
            cached = runCatching {
                val line = output.first { it.startsWith("MULTIROUTE_SIM=") }.removePrefix("MULTIROUTE_SIM=")
                val array = JSONArray(line)
                (0 until array.length()).map { array.getJSONObject(it) }.map {
                    CellularSubscription(it.getInt("subId"), it.getInt("slot"), it.optString("carrier"),
                        it.optString("key").takeIf(CellularTargets::isSim), it.optBoolean("default"),
                        it.getJSONArray("networks").let { array -> (0 until array.length()).associate { index ->
                            val net = array.getJSONObject(index); net.getString("netId") to net.getString("iface")
                        } })
                }
            }.getOrDefault(emptyList())
            stamp = now
            cached
        }
    }
}

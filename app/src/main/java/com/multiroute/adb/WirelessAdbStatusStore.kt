package com.multiroute.adb

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AdbTriggerSource {
    BOOT, UNLOCK, WIFI, MANUAL;

    internal val isBootTrigger: Boolean get() = this == BOOT || this == UNLOCK
}

enum class AdbBootRecovery { PENDING, COMPLETE }

/** Telemetry persisted for the latest wireless ADB recovery/trigger attempt in app no-backup storage. */
data class AdbBootStatus(
    val bootCount: Int?,
    val uptimeSeconds: Long?,
    val source: String?,
    val trigger: AdbTriggerSource?,
    val ssid: String?,
    val mode: AdbMode?,
    val port: Int?,
    val result: AdbActionResult?,
    val detail: String?,
    val timestampMillis: Long = 0L,
    val bootRecovery: AdbBootRecovery? = null
) {
    internal fun recoveryForBoot(currentBootCount: Int?): AdbBootRecovery? =
        bootRecovery.takeIf { currentBootCount != null && currentBootCount == bootCount }
}

/**
 * Stores wireless ADB recovery status and bounded diagnostic logs inside [Context.getNoBackupFilesDir]
 * so uninstalling the app leaves zero root/external log or state artifacts behind.
 */
internal object WirelessAdbStatusStore {
    private const val STATUS_FILE_NAME = "wireless-adb-status.json"
    private const val LOG_FILE_NAME = "wireless-adb.log"
    private const val MAX_LOG_LINES = 60
    private val diskLock = Any()

    fun statusFile(context: Context): File = File(context.noBackupFilesDir, STATUS_FILE_NAME)

    fun logFile(context: Context): File = File(context.noBackupFilesDir, LOG_FILE_NAME)

    fun readStatus(context: Context): AdbBootStatus? = synchronized(diskLock) {
        val file = statusFile(context)
        if (!file.isFile) return null
        return runCatching { parseStatus(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrNull()
    }

    fun writeStatus(context: Context, status: AdbBootStatus): Boolean = synchronized(diskLock) {
        val target = AtomicFile(statusFile(context))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(encodeStatus(status).toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let { target.failWrite(it) }
            false
        }
    }

    fun appendLog(context: Context, message: String, nowMillis: Long = System.currentTimeMillis()) = synchronized(diskLock) {
        runCatching {
            val file = logFile(context)
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(nowMillis))
            val existing = if (file.isFile) file.readLines(Charsets.UTF_8) else emptyList()
            val updated = (existing + "[$stamp] $message").takeLast(MAX_LOG_LINES)
            val target = AtomicFile(file)
            val stream = target.startWrite()
            try {
                stream.write((updated.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                target.finishWrite(stream)
            } catch (t: Throwable) {
                target.failWrite(stream)
                throw t
            }
        }
    }

    fun readLogTail(context: Context, maxLines: Int = 30): List<String> = synchronized(diskLock) {
        val file = logFile(context)
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines(Charsets.UTF_8).takeLast(maxLines) }.getOrDefault(emptyList())
    }

    internal fun encodeStatus(status: AdbBootStatus): String = JSONObject().apply {
        status.bootCount?.let { put("bootCount", it) }
        status.uptimeSeconds?.let { put("uptimeSec", it) }
        status.source?.let { put("source", it) }
        status.trigger?.let { put("trigger", it.name) }
        status.ssid?.let { put("ssid", it) }
        status.mode?.let { put("mode", it.name) }
        status.port?.let { put("port", it) }
        status.result?.let { put("result", it.name) }
        status.detail?.let { put("detail", it) }
        if (status.timestampMillis > 0L) put("timestamp", status.timestampMillis)
        put("bootRecovery", status.bootRecovery?.name ?: JSONObject.NULL)
    }.toString()

    internal fun parseStatus(raw: String): AdbBootStatus? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val json = JSONObject(trimmed)
            val trigger = runCatching { AdbTriggerSource.valueOf(json.optString("trigger")) }.getOrNull()
            val result = runCatching { AdbActionResult.valueOf(json.optString("result")) }.getOrNull()
            val recovery = if (json.has("bootRecovery")) {
                runCatching { AdbBootRecovery.valueOf(json.optString("bootRecovery")) }.getOrNull()
            } else if (trigger?.isBootTrigger == true && result == AdbActionResult.WAIT_NETWORK) {
                AdbBootRecovery.PENDING
            } else {
                null
            }
            AdbBootStatus(
                bootCount = json.optInt("bootCount", -1).takeIf { it >= 0 },
                uptimeSeconds = if (json.has("uptimeSec")) json.optLong("uptimeSec") else null,
                source = json.optString("source").takeIf { it.isNotEmpty() },
                trigger = trigger,
                ssid = json.optString("ssid").takeIf { it.isNotEmpty() },
                mode = json.optString("mode").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { AdbMode.valueOf(it) }.getOrNull() },
                port = if (json.has("port")) json.optInt("port").takeIf { it in 1..65535 } else null,
                result = result,
                detail = json.optString("detail").takeIf { it.isNotEmpty() },
                timestampMillis = json.optLong("timestamp", 0L),
                bootRecovery = recovery
            )
        }.getOrNull()
    }
}

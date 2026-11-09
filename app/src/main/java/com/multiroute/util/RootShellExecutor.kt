package com.multiroute.util

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.EOFException
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class ShellExecResult(
    val exitCode: Int,
    val output: List<String>,
    val timedOut: Boolean = false,
    val completedCleanly: Boolean = false
)

/**
 * Pooled persistent root (`su`) shell execution engine.
 *
 * Instead of forking a fresh `su` child process on every query, commands are framed in isolated
 * subshells inside long-lived interactive root shell sessions (separate pools for standard `su`
 * and `su -M` global mount namespace). Timed-out or broken sessions are destroyed immediately and
 * replaced on demand.
 */
internal class RootShellExecutor(
    private val processStarter: (List<String>) -> Process = { args ->
        ProcessBuilder(args).redirectErrorStream(true).start()
    },
    private val logError: (String, Throwable?) -> Unit = { msg, t ->
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
    },
    private val maxIdleStandardSessions: Int = 2,
    private val maxIdleGlobalNsSessions: Int = 1
) {
    companion object {
        private const val TAG = "MultiRoute-SU"
        private const val ASYNC_WRITE_THRESHOLD_CHARS = 16_384

        private val ioExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "MultiRoute-SuWrite").apply { isDaemon = true }
        }

        val default: RootShellExecutor by lazy { RootShellExecutor() }
    }

    private val poolLock = Any()
    private val standardPool = ArrayDeque<PersistentSession>()
    private val globalNsPool = ArrayDeque<PersistentSession>()
    private val seqCounter = AtomicInteger(0)
    private val random = SecureRandom()

    fun executeCommand(cmd: String, timeoutSeconds: Long = 5, sensitive: Boolean = false): Boolean {
        val result = runCommand(
            cmd = cmd,
            timeoutSeconds = timeoutSeconds,
            globalMountNamespace = false,
            sensitive = sensitive
        )
        return result.completedCleanly && !result.timedOut && result.exitCode == 0
    }

    fun executeShellWithOutput(
        cmd: String,
        timeoutSeconds: Long = 5,
        globalMountNamespace: Boolean = false
    ): List<String> {
        val result = runCommand(
            cmd = cmd,
            timeoutSeconds = timeoutSeconds,
            globalMountNamespace = globalMountNamespace,
            sensitive = false
        )
        return if (result.completedCleanly && !result.timedOut) result.output else emptyList()
    }

    fun writeTextAsRoot(context: Context, path: String, content: String): Boolean {
        if (path.isBlank()) return false
        var staging: File? = null
        return try {
            val file = File.createTempFile("config-transfer-", ".json", context.cacheDir)
            staging = file
            file.writeText(content)
            val dir = path.substringBeforeLast('/', "")
            val quotedStaging = shellQuote(file.absolutePath)
            val quotedPath = shellQuote(path)
            val cmd = if (dir.isEmpty()) {
                "cp $quotedStaging $quotedPath && chmod 644 $quotedPath"
            } else {
                val quotedDir = shellQuote(dir)
                "mkdir -p $quotedDir && cp $quotedStaging $quotedPath && chmod 644 $quotedPath"
            }
            executeCommand(cmd)
        } catch (t: Throwable) {
            logError("Failed to write $path: ${t.message}", t)
            false
        } finally {
            staging?.delete()
        }
    }

    fun readTextAsRoot(path: String): String? {
        if (path.isBlank()) return null
        val result = runCommand(
            cmd = "cat ${shellQuote(path)} 2>/dev/null",
            timeoutSeconds = 8,
            globalMountNamespace = false,
            sensitive = false
        )
        if (!result.completedCleanly || result.timedOut || result.exitCode != 0 || result.output.isEmpty()) {
            return null
        }
        return result.output.joinToString("\n")
    }

    /** Closes all currently idle sessions (useful for cleanup and testing). */
    fun closeAllIdle() {
        val drained = mutableListOf<PersistentSession>()
        synchronized(poolLock) {
            drained.addAll(standardPool)
            standardPool.clear()
            drained.addAll(globalNsPool)
            globalNsPool.clear()
        }
        drained.forEach { it.close() }
    }

    internal fun runCommand(
        cmd: String,
        timeoutSeconds: Long = 5,
        globalMountNamespace: Boolean = false,
        sensitive: Boolean = false
    ): ShellExecResult {
        if (cmd.isBlank()) {
            return ShellExecResult(exitCode = 0, output = emptyList(), completedCleanly = true)
        }
        val effectiveTimeout = timeoutSeconds.coerceAtLeast(1L)
        val label = if (sensitive) "[credentials omitted]" else cmd

        var session = borrowSession(globalMountNamespace)
        val borrowedFromPool = session != null
        if (session == null) {
            session = openSession(globalMountNamespace, label)
                ?: return ShellExecResult(exitCode = -1, output = emptyList())
        }

        var result = session.execute(cmd, effectiveTimeout)
        if (!result.completedCleanly && !result.timedOut && borrowedFromPool) {
            // Stale idle session might have been killed externally (e.g. root daemon restart); retry once cleanly.
            session.close()
            session = openSession(globalMountNamespace, label)
                ?: return ShellExecResult(exitCode = -1, output = emptyList())
            result = session.execute(cmd, effectiveTimeout)
        }

        if (result.timedOut) {
            logError("Command timed out after ${effectiveTimeout}s: $label", null)
            session.close()
        } else if (result.completedCleanly && session.isAlive()) {
            returnSession(session, globalMountNamespace)
        } else {
            session.close()
        }

        return result
    }

    private fun openSession(globalMountNamespace: Boolean, label: String): PersistentSession? {
        return try {
            val args = if (globalMountNamespace) listOf("su", "-M") else listOf("su")
            val process = processStarter(args)
            val nonce = Integer.toHexString(random.nextInt())
            PersistentSession(
                process = process,
                nonce = nonce,
                nextSeq = { seqCounter.incrementAndGet() }
            )
        } catch (e: Exception) {
            logError("Failed to start su session for: $label", e)
            null
        }
    }

    private fun borrowSession(globalMountNamespace: Boolean): PersistentSession? {
        val pool = if (globalMountNamespace) globalNsPool else standardPool
        val stale = mutableListOf<PersistentSession>()
        val candidate: PersistentSession? = synchronized(poolLock) {
            var found: PersistentSession? = null
            while (pool.isNotEmpty()) {
                val next = pool.removeFirst()
                if (next.isAlive()) {
                    found = next
                    break
                } else {
                    stale.add(next)
                }
            }
            found
        }
        stale.forEach { it.close() }
        return candidate
    }

    private fun returnSession(session: PersistentSession, globalMountNamespace: Boolean) {
        val maxIdle = if (globalMountNamespace) maxIdleGlobalNsSessions else maxIdleStandardSessions
        val pool = if (globalMountNamespace) globalNsPool else standardPool
        val accepted = synchronized(poolLock) {
            if (session.isAlive() && pool.size < maxIdle) {
                pool.addFirst(session)
                true
            } else {
                false
            }
        }
        if (!accepted) {
            session.close()
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private class PendingRequest(
        val beginMarker: String,
        val endMarker: String
    ) {
        val lines = mutableListOf<String>()
        val future = CompletableFuture<ShellExecResult>()
        var started = false
    }

    private class PersistentSession(
        private val process: Process,
        private val nonce: String,
        private val nextSeq: () -> Int
    ) {
        private val reader = BufferedReader(InputStreamReader(process.inputStream))
        private val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        private val closed = AtomicBoolean(false)

        @Volatile
        private var activeRequest: PendingRequest? = null

        private val readerThread = Thread {
            try {
                while (!closed.get()) {
                    val line = reader.readLine() ?: break
                    val req = activeRequest ?: continue
                    if (req.future.isDone) continue
                    if (!req.started) {
                        if (line.contains(req.beginMarker)) {
                            req.started = true
                        }
                        continue
                    }
                    val idx = line.indexOf(req.endMarker)
                    if (idx >= 0) {
                        if (idx > 0) {
                            req.lines.add(line.substring(0, idx))
                        }
                        val rc = line.substring(idx + req.endMarker.length).trim().toIntOrNull() ?: -1
                        req.future.complete(
                            ShellExecResult(
                                exitCode = rc,
                                output = req.lines.toList(),
                                timedOut = false,
                                completedCleanly = true
                            )
                        )
                    } else {
                        req.lines.add(line)
                    }
                }
                activeRequest?.future?.completeExceptionally(EOFException("su shell terminated before sentinel"))
            } catch (t: Throwable) {
                activeRequest?.future?.completeExceptionally(t)
            } finally {
                closed.set(true)
            }
        }.apply {
            isDaemon = true
            name = "MultiRoute-SuReader-$nonce"
            start()
        }

        fun isAlive(): Boolean = !closed.get() && process.isAlive

        fun execute(cmd: String, timeoutSeconds: Long): ShellExecResult {
            if (!isAlive()) {
                return ShellExecResult(exitCode = -1, output = emptyList())
            }
            val seq = nextSeq()
            val beginMarker = "__MR_BEG_${seq}_${nonce}__"
            val endMarker = "__MR_END_${seq}_${nonce}__:"
            val request = PendingRequest(beginMarker = beginMarker, endMarker = endMarker)
            activeRequest = request

            val payload = buildString {
                append("printf '%s\\n' '")
                append(beginMarker)
                append("'\n(\n")
                append(cmd)
                if (!cmd.endsWith('\n')) append('\n')
                append(") </dev/null\n")
                append("__mr_rc=\$?\n")
                append("printf '")
                append(endMarker)
                append("%d\\n' \"\$__mr_rc\"\n")
            }

            return try {
                if (payload.length > ASYNC_WRITE_THRESHOLD_CHARS) {
                    ioExecutor.execute {
                        try {
                            writer.write(payload)
                            writer.flush()
                        } catch (t: Throwable) {
                            request.future.completeExceptionally(t)
                        }
                    }
                } else {
                    writer.write(payload)
                    writer.flush()
                }
                request.future.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                ShellExecResult(exitCode = -1, output = emptyList(), timedOut = true, completedCleanly = false)
            } catch (_: Throwable) {
                ShellExecResult(exitCode = -1, output = emptyList(), timedOut = false, completedCleanly = false)
            } finally {
                activeRequest = null
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            activeRequest?.future?.cancel(true)
            runCatching { process.destroyForcibly() }
            runCatching { process.outputStream.close() }
            runCatching { readerThread.interrupt() }
        }
    }
}

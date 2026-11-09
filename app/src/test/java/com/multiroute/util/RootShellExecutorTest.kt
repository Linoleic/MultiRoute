package com.multiroute.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RootShellExecutorTest {

    private data class ScriptReply(
        val lines: List<String> = emptyList(),
        val exitCode: Int = 0,
        val startupNoise: List<String> = emptyList(),
        val noTrailingNewline: Boolean = false,
        val hangUntilDestroyed: Boolean = false,
        val crashBeforeSentinel: Boolean = false
    )

    private class InMemoryPipe {
        private val lock = ReentrantLock()
        private val dataOrClose = lock.newCondition()
        private val buffer = ArrayDeque<Byte>()
        private var closed = false

        val inputStream: InputStream = object : InputStream() {
            override fun read(): Int {
                val single = ByteArray(1)
                val n = read(single, 0, 1)
                return if (n == -1) -1 else single[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                lock.withLock {
                    while (buffer.isEmpty() && !closed) {
                        try {
                            dataOrClose.await()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return -1
                        }
                    }
                    if (buffer.isEmpty() && closed) return -1
                    var count = 0
                    while (count < len && buffer.isNotEmpty()) {
                        b[off + count] = buffer.removeFirst()
                        count++
                    }
                    return count
                }
            }

            override fun close() {
                this@InMemoryPipe.close()
            }
        }

        val outputStream: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                lock.withLock {
                    if (closed) throw IOException("Pipe closed")
                    for (i in 0 until len) {
                        buffer.addLast(b[off + i])
                    }
                    dataOrClose.signalAll()
                }
            }

            override fun close() {
                this@InMemoryPipe.close()
            }
        }

        fun close() {
            lock.withLock {
                closed = true
                dataOrClose.signalAll()
            }
        }
    }

    private class FakeShellProcess(
        private val handler: (commandBody: String) -> ScriptReply
    ) : Process() {
        private val stdinPipe = InMemoryPipe()
        private val stdoutPipe = InMemoryPipe()
        private val stderrPipe = InMemoryPipe().apply { close() }
        private val alive = AtomicBoolean(true)
        private val destroyed = AtomicBoolean(false)
        private val exitCode = AtomicInteger(0)
        private val worker: Thread

        val wasDestroyed: Boolean get() = destroyed.get()

        init {
            worker = Thread {
                val reader = stdinPipe.inputStream.bufferedReader(StandardCharsets.UTF_8)
                try {
                    while (alive.get()) {
                        val line = reader.readLine() ?: break
                        // Protocol frame starts with: printf '%s\n' '<beginMarker>'
                        if (!line.startsWith("printf '%s\\n' '")) continue
                        val beginMarker = line.removePrefix("printf '%s\\n' '").removeSuffix("'")
                        val openParen = reader.readLine() ?: break
                        if (openParen != "(") continue

                        val cmdLines = mutableListOf<String>()
                        while (true) {
                            val bodyLine = reader.readLine() ?: return@Thread
                            if (bodyLine == ") </dev/null") break
                            cmdLines.add(bodyLine)
                        }
                        reader.readLine() ?: break // __mr_rc=$?
                        val printfEnd = reader.readLine() ?: break
                        val endMarker = printfEnd
                            .removePrefix("printf '")
                            .substringBefore("%d\\n'")

                        val reply = handler(cmdLines.joinToString("\n"))
                        if (reply.hangUntilDestroyed) {
                            while (alive.get()) {
                                try {
                                    Thread.sleep(50)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                            break
                        }

                        val out = buildString {
                            reply.startupNoise.forEach { append(it).append('\n') }
                            append(beginMarker).append('\n')
                            if (reply.crashBeforeSentinel) {
                                reply.lines.forEach { append(it).append('\n') }
                            } else {
                                reply.lines.forEachIndexed { idx, l ->
                                    append(l)
                                    if (!(reply.noTrailingNewline && idx == reply.lines.lastIndex)) {
                                        append('\n')
                                    }
                                }
                                append(endMarker).append(reply.exitCode).append('\n')
                            }
                        }
                        stdoutPipe.outputStream.write(out.toByteArray(StandardCharsets.UTF_8))
                        stdoutPipe.outputStream.flush()

                        if (reply.crashBeforeSentinel) {
                            alive.set(false)
                            exitCode.set(137)
                            stdoutPipe.close()
                            break
                        }
                    }
                } catch (_: Throwable) {
                    // Closed during teardown
                } finally {
                    alive.set(false)
                    stdoutPipe.close()
                }
            }.apply {
                isDaemon = true
                name = "FakeShellProcess-Worker"
                start()
            }
        }

        override fun getOutputStream(): OutputStream = stdinPipe.outputStream
        override fun getInputStream(): InputStream = stdoutPipe.inputStream
        override fun getErrorStream(): InputStream = stderrPipe.inputStream

        override fun isAlive(): Boolean = alive.get()

        override fun waitFor(): Int {
            worker.join()
            return exitCode.get()
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            worker.join(unit.toMillis(timeout))
            return !isAlive
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("Process still alive")
            return exitCode.get()
        }

        override fun destroy() {
            destroyed.set(true)
            alive.set(false)
            stdinPipe.close()
            stdoutPipe.close()
            worker.interrupt()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    @Test
    fun reusesPersistentSessionAcrossMultipleCommands() {
        val spawnedArgs = Collections.synchronizedList(mutableListOf<List<String>>())
        val executor = RootShellExecutor(
            processStarter = { args ->
                spawnedArgs.add(args)
                FakeShellProcess { cmd ->
                    when (cmd) {
                        "id" -> ScriptReply(lines = listOf("uid=0(root) gid=0(root)"), exitCode = 0)
                        "echo one" -> ScriptReply(lines = listOf("one"), exitCode = 0)
                        "true" -> ScriptReply(exitCode = 0)
                        else -> ScriptReply(lines = listOf("ok:$cmd"), exitCode = 0)
                    }
                }
            },
            logError = { _, _ -> }
        )

        try {
            assertTrue(executor.executeCommand("true"))
            assertEquals(listOf("uid=0(root) gid=0(root)"), executor.executeShellWithOutput("id"))
            assertEquals(listOf("one"), executor.executeShellWithOutput("echo one"))
            assertTrue(executor.executeCommand("setprop persist.multiroute.test 1"))

            assertEquals(1, spawnedArgs.size)
            assertEquals(listOf("su"), spawnedArgs[0])
        } finally {
            executor.closeAllIdle()
        }
    }

    @Test
    fun filtersStartupNoiseAndHandlesOutputWithoutTrailingNewline() {
        val executor = RootShellExecutor(
            processStarter = {
                FakeShellProcess { _ ->
                    ScriptReply(
                        startupNoise = listOf("WARNING: noisy mkshrc banner"),
                        lines = listOf("line-1", "no-newline-tail"),
                        exitCode = 0,
                        noTrailingNewline = true
                    )
                }
            },
            logError = { _, _ -> }
        )

        try {
            val output = executor.executeShellWithOutput("printf 'no-newline-tail'")
            assertEquals(listOf("line-1", "no-newline-tail"), output)
        } finally {
            executor.closeAllIdle()
        }
    }

    @Test
    fun preservesOutputOnNonZeroExitWhileExecuteCommandReturnsFalse() {
        val executor = RootShellExecutor(
            processStarter = {
                FakeShellProcess { cmd ->
                    if (cmd.contains("exit 5")) {
                        ScriptReply(lines = listOf("partial line"), exitCode = 5)
                    } else {
                        ScriptReply(lines = listOf("recovered"), exitCode = 0)
                    }
                }
            },
            logError = { _, _ -> }
        )

        try {
            assertFalse(executor.executeCommand("echo fail; exit 5"))
            assertEquals(listOf("partial line"), executor.executeShellWithOutput("echo warn; exit 5"))
            assertEquals(listOf("recovered"), executor.executeShellWithOutput("echo after"))
        } finally {
            executor.closeAllIdle()
        }
    }

    @Test
    fun blankCommandShortCircuitsWithoutSpawningSession() {
        val spawns = AtomicInteger(0)
        val executor = RootShellExecutor(
            processStarter = {
                spawns.incrementAndGet()
                FakeShellProcess { ScriptReply() }
            },
            logError = { _, _ -> }
        )

        assertTrue(executor.executeCommand("   \n  "))
        assertEquals(emptyList<String>(), executor.executeShellWithOutput(""))
        assertEquals(0, spawns.get())
    }

    @Test
    fun timeoutDestroysHungSessionAndRecoversOnNextCommand() {
        val processes = Collections.synchronizedList(mutableListOf<FakeShellProcess>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val executor = RootShellExecutor(
            processStarter = {
                FakeShellProcess { cmd ->
                    if (cmd == "hang") ScriptReply(hangUntilDestroyed = true)
                    else ScriptReply(lines = listOf("ok:$cmd"), exitCode = 0)
                }.also { processes.add(it) }
            },
            logError = { msg, _ -> errors.add(msg) }
        )

        try {
            assertTrue(executor.executeCommand("warm"))
            assertFalse(executor.executeCommand("hang", timeoutSeconds = 1, sensitive = true))
            assertEquals(1, processes.size)
            assertTrue(processes[0].wasDestroyed)
            assertTrue(errors.any { it.contains("[credentials omitted]") })

            assertEquals(listOf("ok:after"), executor.executeShellWithOutput("after"))
            assertEquals(2, processes.size)
        } finally {
            executor.closeAllIdle()
        }
    }

    @Test
    fun staleBorrowedSessionCrashingMidCommandRetriesOnceOnFreshSession() {
        val spawns = AtomicInteger(0)
        val executor = RootShellExecutor(
            processStarter = {
                val id = spawns.incrementAndGet()
                FakeShellProcess { cmd ->
                    if (id == 1 && cmd == "probe") {
                        ScriptReply(lines = listOf("stale"), crashBeforeSentinel = true)
                    } else {
                        ScriptReply(lines = listOf("session-$id:$cmd"), exitCode = 0)
                    }
                }
            },
            logError = { _, _ -> }
        )

        try {
            assertEquals(listOf("session-1:init"), executor.executeShellWithOutput("init"))
            assertEquals(listOf("session-2:probe"), executor.executeShellWithOutput("probe"))
            assertEquals(2, spawns.get())
        } finally {
            executor.closeAllIdle()
        }
    }

    @Test
    fun globalMountNamespaceUsesDedicatedSuMPool() {
        val spawnedArgs = Collections.synchronizedList(mutableListOf<List<String>>())
        val executor = RootShellExecutor(
            processStarter = { args ->
                spawnedArgs.add(args)
                val tag = if ("-M" in args) "global-ns" else "app-ns"
                FakeShellProcess { ScriptReply(lines = listOf(tag), exitCode = 0) }
            },
            logError = { _, _ -> }
        )

        try {
            assertEquals(listOf("app-ns"), executor.executeShellWithOutput("ns1", globalMountNamespace = false))
            assertEquals(listOf("app-ns"), executor.executeShellWithOutput("ns2", globalMountNamespace = false))
            assertEquals(listOf("global-ns"), executor.executeShellWithOutput("g1", globalMountNamespace = true))
            assertEquals(listOf("global-ns"), executor.executeShellWithOutput("g2", globalMountNamespace = true))

            assertEquals(listOf(listOf("su"), listOf("su", "-M")), spawnedArgs)
        } finally {
            executor.closeAllIdle()
        }
    }

    @Test
    fun enforcesMaxIdleCapacityUnderConcurrentLoad() {
        val processes = Collections.synchronizedList(mutableListOf<FakeShellProcess>())
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        val executor = RootShellExecutor(
            processStarter = {
                FakeShellProcess { cmd ->
                    if (cmd.startsWith("concurrent")) {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    ScriptReply(lines = listOf("done:$cmd"), exitCode = 0)
                }.also { processes.add(it) }
            },
            logError = { _, _ -> },
            maxIdleStandardSessions = 2
        )

        val pool = Executors.newFixedThreadPool(3)
        try {
            val futures = (1..3).map { idx ->
                pool.submit(Callable {
                    executor.executeShellWithOutput("concurrent-$idx", timeoutSeconds = 5)
                })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            futures.forEach { f -> assertTrue(f.get(5, TimeUnit.SECONDS).isNotEmpty()) }

            assertEquals(3, processes.size)
            assertEquals(1, processes.count { it.wasDestroyed })
            assertEquals(2, processes.count { it.isAlive })
        } finally {
            pool.shutdownNow()
            executor.closeAllIdle()
        }
    }

    @Test
    fun asyncWritesLargeScriptsCleanly() {
        var receivedLength = 0
        val executor = RootShellExecutor(
            processStarter = {
                FakeShellProcess { cmd ->
                    receivedLength = cmd.length
                    ScriptReply(lines = listOf("applied:${cmd.lines().size}"), exitCode = 0)
                }
            },
            logError = { _, _ -> }
        )

        try {
            val largeScript = (1..800).joinToString("\n") { "ip rule del pref 14500 2>/dev/null # padding-$it-abcdef" }
            assertTrue(largeScript.length > 16_384)
            val lines = executor.executeShellWithOutput(largeScript, timeoutSeconds = 5)
            assertEquals(listOf("applied:800"), lines)
            assertEquals(largeScript.length, receivedLength)
        } finally {
            executor.closeAllIdle()
        }
    }
}

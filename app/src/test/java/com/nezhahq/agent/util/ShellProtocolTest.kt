package com.nezhahq.agent.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProtocolTest {
    @Test
    fun parsesOutputMarkerAndExitCode() {
        val marker = marker("parse")
        val result = ShellProtocolReader().read(
            ByteArrayInputStream(frame("hello\n", marker, 23)),
            marker
        )

        assertEquals("hello\n", result.output)
        assertEquals(23, result.exitCode)
        assertFalse(result.truncated)
    }

    @Test
    fun recognizesMarkerAfterOutputWithoutTrailingNewline() {
        val marker = marker("no_newline")
        val result = ShellProtocolReader().read(
            ByteArrayInputStream(frame("last-byte", marker, 0)),
            marker
        )

        assertEquals("last-byte", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun recognizesMarkerSplitAcrossEverySmallReadBuffer() {
        val marker = marker("split_marker")
        val output = "prefix ${marker.prefix.dropLast(3)}X suffix\n"
        val result = ShellProtocolReader(readBufferBytes = 3).read(
            ByteArrayInputStream(frame(output, marker, 7)),
            marker
        )

        assertEquals(output, result.output)
        assertEquals(7, result.exitCode)
    }

    @Test
    fun drainsPastFourMiBWhileRetainingOnlyTheLimit() {
        val marker = marker("large_output")
        val outputBytes = MAX_SHELL_OUTPUT_BYTES + 32 * 1024
        val completion = "${marker.prefix}0${marker.suffix}".toByteArray(StandardCharsets.US_ASCII)
        val payload = ByteArray(outputBytes + completion.size) { 'x'.code.toByte() }
        completion.copyInto(payload, outputBytes)
        val input = ByteArrayInputStream(payload)

        val result = ShellProtocolReader().read(input, marker)

        assertEquals(MAX_SHELL_OUTPUT_BYTES, result.output.length)
        assertTrue(result.output.all { it == 'x' })
        assertTrue(result.truncated)
        assertEquals(0, input.available())
    }

    @Test
    fun rejectsInvalidExitCodeAndEofWithoutMarker() {
        val marker = marker("bad_protocol")
        val invalidExit = "data${marker.prefix}999${marker.suffix}"

        assertThrows(ShellProtocolException::class.java) {
            ShellProtocolReader().read(
                ByteArrayInputStream(invalidExit.toByteArray(StandardCharsets.UTF_8)),
                marker
            )
        }
        assertThrows(ShellProtocolException::class.java) {
            ShellProtocolReader().read(
                ByteArrayInputStream("data only".toByteArray(StandardCharsets.UTF_8)),
                marker
            )
        }
    }

    @Test
    fun openingSessionRedirectsStderrImmediately() {
        val process = FakeProcess(ByteArrayInputStream(ByteArray(0)))

        val session = ShellSession.openRedirected(process, "test")

        assertEquals("exec 2>&1\n", process.writtenUtf8())
        session.destroy()
        assertTrue(process.destroyed)
    }

    @Test
    fun successfulCommandsReuseOneDaemonReaderExecutor() {
        val firstMarker = marker("first_normal")
        val secondMarker = marker("second_normal")
        val input = frame("one", firstMarker, 0) + frame("two", secondMarker, 0)
        val process = FakeProcess(ByteArrayInputStream(input))
        val markers = ArrayDeque(listOf(firstMarker, secondMarker))
        val executorStarts = AtomicInteger()
        var readerThread: Thread? = null
        val shell = PersistentShell(
            sessionFactory = { ShellSession(process, "shared") },
            markerFactory = { markers.removeFirst() },
            protocolReader = ShellProtocolReader(readBufferBytes = 1),
            executorFactory = {
                executorStarts.incrementAndGet()
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "test-shared-reader").apply {
                        isDaemon = true
                        readerThread = this
                    }
                }
            }
        )

        try {
            assertEquals("one", shell.execute("first", timeoutMs = 1_000))
            assertEquals("two", shell.execute("second", timeoutMs = 1_000))
            assertEquals(1, executorStarts.get())
            assertTrue(readerThread?.isDaemon == true)
        } finally {
            shell.shutdown()
        }
    }

    @Test
    fun timeoutDestroysSessionAndRebuildsWithoutOldReaderPollution() {
        val firstMarker = marker("timed_out")
        val secondMarker = marker("replacement")
        val markers = ArrayDeque(listOf(firstMarker, secondMarker))
        val stubbornInput = StubbornInputStream()
        val firstProcess = FakeProcess(stubbornInput)
        val secondProcess = FakeProcess(
            ByteArrayInputStream(frame("fresh", secondMarker, 0))
        )
        val sessions = ArrayDeque(
            listOf(
                ShellSession(firstProcess, "first"),
                ShellSession(secondProcess, "second")
            )
        )
        val sessionStarts = AtomicInteger()
        val executorStarts = AtomicInteger()
        val shell = PersistentShell(
            sessionFactory = {
                sessionStarts.incrementAndGet()
                sessions.removeFirst()
            },
            markerFactory = { markers.removeFirst() },
            executorFactory = {
                val number = executorStarts.incrementAndGet()
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "test-shell-reader-$number").apply { isDaemon = true }
                }
            },
            readerStopWaitMs = 10
        )

        try {
            assertEquals("", shell.execute("slow-command", timeoutMs = 20))
            assertTrue(firstProcess.destroyed)

            assertEquals("fresh", shell.execute("next-command", timeoutMs = 1_000))
            assertEquals(2, sessionStarts.get())
            assertEquals(2, executorStarts.get())
            assertFalse(secondProcess.destroyed)
        } finally {
            stubbornInput.release()
            shell.shutdown()
        }
    }

    @Test
    fun shutdownInterruptsLongCommandAndAllowsLaterRebuild() {
        val blockedMarker = marker("shutdown_blocked")
        val rebuiltMarker = marker("shutdown_rebuilt")
        val markers = ArrayDeque(listOf(blockedMarker, rebuiltMarker))
        val blockedInput = StubbornInputStream()
        val blockedProcess = FakeProcess(blockedInput)
        val rebuiltProcess = FakeProcess(
            ByteArrayInputStream(frame("rebuilt", rebuiltMarker, 0))
        )
        val sessions = ArrayDeque(
            listOf(
                ShellSession(blockedProcess, "blocked"),
                ShellSession(rebuiltProcess, "rebuilt")
            )
        )
        val shell = PersistentShell(
            sessionFactory = { sessions.removeFirst() },
            markerFactory = { markers.removeFirst() }
        )
        val caller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-long-shell-call").apply { isDaemon = true }
        }

        try {
            val command = caller.submit<String> {
                shell.execute("blocked-command", timeoutMs = 120_000)
            }
            assertTrue(blockedInput.awaitReadStarted())

            val startedNs = System.nanoTime()
            shell.shutdown()
            val shutdownMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)

            assertTrue("shutdown took ${shutdownMs}ms", shutdownMs < 1_000)
            assertEquals("", command.get(1, TimeUnit.SECONDS))
            assertTrue(blockedProcess.destroyed)
            assertEquals(1, blockedProcess.destroyCalls.get())
            assertEquals("rebuilt", shell.execute("after-shutdown", timeoutMs = 1_000))
        } finally {
            blockedInput.release()
            shell.shutdown()
            caller.shutdownNow()
        }
    }

    @Test
    fun shutdownRacingNormalCompletionDoesNotCancelNextCommand() {
        val racingMarker = marker("racing_normal")
        val nextMarker = marker("after_race")
        val racingInput = GatedInputStream(frame("finished", racingMarker, 0))
        val racingProcess = FakeProcess(racingInput)
        val nextProcess = FakeProcess(ByteArrayInputStream(frame("next", nextMarker, 0)))
        val sessions = ArrayDeque(
            listOf(
                ShellSession(racingProcess, "racing"),
                ShellSession(nextProcess, "next")
            )
        )
        val markers = ArrayDeque(listOf(racingMarker, nextMarker))
        val shell = PersistentShell(
            sessionFactory = { sessions.removeFirst() },
            markerFactory = { markers.removeFirst() },
            readerStopWaitMs = 10
        )
        val callers = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "test-shutdown-race").apply { isDaemon = true }
        }

        try {
            val first = callers.submit<String> { shell.execute("racing", timeoutMs = 120_000) }
            assertTrue(racingInput.awaitReadStarted())
            val startRace = CountDownLatch(1)
            val shutdown = callers.submit {
                startRace.await()
                shell.shutdown()
            }
            startRace.countDown()
            racingInput.release()

            shutdown.get(1, TimeUnit.SECONDS)
            assertTrue(first.get(1, TimeUnit.SECONDS) in setOf("", "finished"))
            assertEquals("next", shell.execute("next-command", timeoutMs = 1_000))
            assertFalse(nextProcess.destroyed)
        } finally {
            racingInput.release()
            shell.shutdown()
            callers.shutdownNow()
        }
    }

    private fun marker(label: String): ShellMarker {
        return ShellMarker(("0123456789abcdef_" + label).padEnd(24, '_'))
    }

    private fun frame(output: String, marker: ShellMarker, exitCode: Int): ByteArray {
        return (output + marker.prefix + exitCode + marker.suffix)
            .toByteArray(StandardCharsets.UTF_8)
    }

    private class StubbornInputStream : InputStream() {
        private val released = CountDownLatch(1)
        private val readStarted = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            while (true) {
                try {
                    released.await()
                    return -1
                } catch (_: InterruptedException) {
                    // Deliberately ignore cancellation to exercise executor replacement.
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()

        override fun close() = Unit

        fun release() {
            released.countDown()
        }

        fun awaitReadStarted(): Boolean = readStarted.await(1, TimeUnit.SECONDS)
    }

    private class GatedInputStream(payload: ByteArray) : InputStream() {
        private val released = CountDownLatch(1)
        private val readStarted = CountDownLatch(1)
        private val delegate = ByteArrayInputStream(payload)

        override fun read(): Int {
            awaitRelease()
            return delegate.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            awaitRelease()
            return delegate.read(buffer, offset, length)
        }

        override fun close() = Unit

        fun release() {
            released.countDown()
        }

        fun awaitReadStarted(): Boolean = readStarted.await(1, TimeUnit.SECONDS)

        private fun awaitRelease() {
            readStarted.countDown()
            while (true) {
                try {
                    released.await()
                    return
                } catch (_: InterruptedException) {
                    // The race deliberately allows completion after cancellation.
                }
            }
        }
    }

    private class FakeProcess(private val stdout: InputStream) : Process() {
        private val stdin = ByteArrayOutputStream()
        private val stderr = ByteArrayInputStream(ByteArray(0))
        @Volatile private var alive = true
        @Volatile var destroyed = false
            private set
        val destroyCalls = AtomicInteger()

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 0
        }

        override fun destroy() {
            destroyCalls.incrementAndGet()
            destroyed = true
            alive = false
        }

        fun writtenUtf8(): String = stdin.toString(StandardCharsets.UTF_8.name())
    }
}

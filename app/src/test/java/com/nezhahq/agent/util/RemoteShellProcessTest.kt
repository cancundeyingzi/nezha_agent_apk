package com.nezhahq.agent.util

import com.nezhahq.agent.executor.ProcessCompatibility
import com.nezhahq.agent.executor.ProcessWaitResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RemoteShellProcessTest {
    @Test fun runningRemoteNeverRequestsExitCodeAndSessionIsReused() {
        val marker = ShellMarker("remote_test_marker")
        val payload = ("ok${marker.prefix}0${marker.suffix}").repeat(3)
        val remote = FakeRemote(payload)
        var starts = 0
        val shell = PersistentShell(
            sessionFactory = {
                starts++
                ShellSession(RemoteShellProcess(remote) { remote.running }, "shizuku")
            },
            markerFactory = { marker },
            protocolReader = ShellProtocolReader(readBufferBytes = 1)
        )
        try {
            repeat(3) {
                assertEquals("ok", shell.execute("cat /proc/mounts"))
                assertTrue(shell.isAlive())
            }
            assertEquals(1, starts)
            assertEquals(0, remote.exitCalls)
            assertFalse(remote.destroyed)
        } finally {
            shell.shutdown()
        }
        assertTrue(remote.destroyed)
    }

    @Test fun waitingPollsAliveUntilExitAndPreservesExitCode() {
        val remote = FakeRemote()
        val process = RemoteShellProcess(remote) { remote.running }
        var now = 0L
        val compatibility = ProcessCompatibility(
            sdkInt = 23,
            destroyForcibly = { error("unexpected termination") },
            nanoTime = { now },
            sleep = { millis ->
                now += TimeUnit.MILLISECONDS.toNanos(millis)
                remote.running = false
            }
        )
        assertEquals(ProcessWaitResult.Exited(7), compatibility.waitFor(process, 100))
        assertEquals(1, remote.exitCalls)
    }

    @Test fun runningRemoteStillTimesOut() {
        val remote = FakeRemote()
        var now = 0L
        val compatibility = ProcessCompatibility(
            sdkInt = 23,
            destroyForcibly = { error("unexpected termination") },
            nanoTime = { now },
            sleep = { now += TimeUnit.MILLISECONDS.toNanos(it) }
        )
        assertEquals(ProcessWaitResult.TimedOut,
            compatibility.waitFor(RemoteShellProcess(remote) { remote.running }, 20))
        assertEquals(0, remote.exitCalls)
    }

    @Test fun transportFailureIsReportedAndSessionDestroyed() {
        val marker = ShellMarker("disconnect_test_marker")
        val remote = FakeRemote("ok${marker.prefix}0${marker.suffix}")
        val failure = IllegalStateException("Binder disconnected")
        var disconnected = false
        val errors = mutableListOf<Exception>()
        val shell = PersistentShell(
            sessionFactory = {
                ShellSession(RemoteShellProcess(remote) {
                    if (disconnected) throw failure
                    remote.running
                }, "shizuku")
            },
            markerFactory = { marker },
            onCommandFailure = { _, exception -> errors += exception }
        )
        try {
            assertEquals("ok", shell.execute("first"))
            disconnected = true
            assertEquals("", shell.execute("second"))
            assertTrue(remote.destroyed)
            assertSame(failure, errors.single())
            assertFalse(shell.isAlive())
        } finally {
            shell.shutdown()
        }
    }

    @Test fun exitedRemoteIsReplacedOnNextCommand() {
        val marker = ShellMarker("restart_test_marker")
        val first = FakeRemote("one${marker.prefix}0${marker.suffix}")
        val second = FakeRemote("two${marker.prefix}0${marker.suffix}")
        val remotes = ArrayDeque(listOf(first, second))
        val shell = PersistentShell(
            sessionFactory = {
                val remote = remotes.removeFirst()
                ShellSession(RemoteShellProcess(remote) { remote.running }, "shizuku")
            },
            markerFactory = { marker }
        )
        try {
            assertEquals("one", shell.execute("first"))
            first.running = false
            assertFalse(shell.isAlive())
            assertEquals("two", shell.execute("second"))
            assertTrue(first.destroyed)
            assertFalse(second.destroyed)
        } finally {
            shell.shutdown()
        }
    }

    private class FakeRemote(payload: String = "") : Process() {
        var running = true
        var destroyed = false
        var exitCalls = 0
        private val input = ByteArrayInputStream(payload.toByteArray())
        private val output = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getOutputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = error("Unexpected blocking wait")
        override fun exitValue(): Int {
            exitCalls++
            // Deliberately unlike the local JVM exception, as with a Binder transport.
            if (running) throw RuntimeException("process hasn't exited")
            return 7
        }
        override fun destroy() { destroyed = true; running = false }
    }
}

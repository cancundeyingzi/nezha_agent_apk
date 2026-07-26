package com.nezhahq.agent.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeShutdownGateTest {
    @Test
    fun submitDoesNotBlockTheCaller() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = RuntimeShutdownGate(scope) { }
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)

        val job = gate.submit {
            entered.countDown()
            release.await()
        }

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFalse(job.isCompleted)
        release.countDown()
        runBlocking { job.join() }
        scope.cancel()
    }

    @Test
    fun shutdownsRunOneAtATimeInSubmissionOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = RuntimeShutdownGate(scope) { }
        val events = mutableListOf<String>()
        val overlapped = AtomicBoolean(false)
        val running = AtomicBoolean(false)
        val releaseFirst = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)

        gate.submit {
            if (!running.compareAndSet(false, true)) overlapped.set(true)
            synchronized(events) { events += "first" }
            firstEntered.countDown()
            releaseFirst.await()
            running.set(false)
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val second = gate.submit {
            if (!running.compareAndSet(false, true)) overlapped.set(true)
            synchronized(events) { events += "second" }
            running.set(false)
        }
        releaseFirst.countDown()
        runBlocking { second.join() }

        assertEquals(listOf("first", "second"), events)
        assertFalse(overlapped.get())
        scope.cancel()
    }

    @Test
    fun awaitIdleReturnsOnlyAfterTheLastShutdownFinished() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = RuntimeShutdownGate(scope) { }
        val finished = AtomicBoolean(false)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)

        gate.submit {
            entered.countDown()
            release.await()
            finished.set(true)
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        release.countDown()

        runBlocking { gate.awaitIdle() }

        assertTrue(finished.get())
        scope.cancel()
    }

    @Test
    fun awaitIdleReturnsImmediatelyWhenNothingWasSubmitted() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        runBlocking { RuntimeShutdownGate(scope) { }.awaitIdle() }

        scope.cancel()
    }

    /**
     * Runtime teardown reports combined failures, so a throwing shutdown must be contained: it may
     * neither reach the uncaught handler nor stop the queued shutdown behind it.
     */
    @Test
    fun aFailingShutdownIsReportedAndStillReleasesTheNextOne() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failures = mutableListOf<Throwable>()
        val gate = RuntimeShutdownGate(scope) { synchronized(failures) { failures += it } }
        val secondRan = AtomicBoolean(false)

        val first = gate.submit { throw IllegalStateException("teardown failed") }
        val second = gate.submit { secondRan.set(true) }
        runBlocking { second.join() }

        assertTrue(first.isCompleted)
        assertFalse(first.isCancelled)
        assertTrue(secondRan.get())
        assertEquals(1, synchronized(failures) { failures.size })
        assertEquals("teardown failed", synchronized(failures) { failures.single().message })
        scope.cancel()
    }

    @Test
    fun awaitIdleReturnsAfterAFailingShutdownInsteadOfPropagatingIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = RuntimeShutdownGate(scope) { }

        gate.submit { throw IllegalStateException("teardown failed") }
        runBlocking { withTimeout(2_000) { gate.awaitIdle() } }

        scope.cancel()
    }
}

package com.nezhahq.agent.simulator

import com.nezhahq.agent.core.model.SimulatedDeviceConfig

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SimulatedDeviceLoopTest {
    @Test
    fun successesAreCountedOnlyAfterReporterCompletes() = runBlocking {
        var calls = 0
        var successes = 0
        var failures = 0
        val reporter = SimulatedDeviceReporter { _, _ ->
            calls += 1
            if (calls == 2) error("state receipt missing")
        }
        val loop = SimulatedDeviceLoop(
            reporter = reporter,
            nowMs = { 0L },
            delayNext = {}
        )

        loop.run(
            config = validConfig(),
            shouldContinue = { calls < 3 },
            onSuccess = { successes += 1 },
            onFailure = { failures += 1 }
        )

        assertEquals(3, calls)
        assertEquals(2, successes)
        assertEquals(1, failures)
    }

    @Test
    fun slowReportsDoNotCreateCatchUpDelays() = runBlocking {
        var calls = 0
        var now = 0L
        val delays = mutableListOf<Long>()
        val reporter = SimulatedDeviceReporter { _, _ ->
            calls += 1
            now += 1_500L
        }
        val loop = SimulatedDeviceLoop(
            reporter = reporter,
            intervalMs = 1_000L,
            nowMs = { now },
            delayNext = { delays += it }
        )

        loop.run(
            config = validConfig(),
            shouldContinue = { calls < 2 },
            onSuccess = {},
            onFailure = {}
        )

        assertEquals(2, calls)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun configuredWorkersRunReportsConcurrently() = runBlocking {
        val entered = AtomicInteger(0)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val releaseReports = CompletableDeferred<Unit>()
        val reporter = SimulatedDeviceReporter { _, _ ->
            val currentActive = active.incrementAndGet()
            maxActive.updateAndGet { old -> maxOf(old, currentActive) }
            if (entered.incrementAndGet() == 2) {
                releaseReports.complete(Unit)
            }
            releaseReports.await()
            active.decrementAndGet()
        }
        val loop = SimulatedDeviceLoop(
            reporter = reporter,
            nowMs = { 0L },
            delayNext = {}
        )

        loop.run(
            config = validConfig(threadCount = 2),
            shouldContinue = { entered.get() < 2 },
            onSuccess = {},
            onFailure = {}
        )

        assertEquals(2, entered.get())
        assertEquals(2, maxActive.get())
    }

    @Test
    fun reporterFailureDoesNotStopOtherReports() = runBlocking {
        val calls = AtomicInteger(0)
        val successes = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val keepRunning = AtomicBoolean(true)
        val reporter = SimulatedDeviceReporter { _, _ ->
            if (calls.incrementAndGet() == 1) {
                error("state receipt missing")
            }
        }
        val loop = SimulatedDeviceLoop(
            reporter = reporter,
            nowMs = { 0L },
            delayNext = {}
        )

        loop.run(
            config = validConfig(threadCount = 2),
            shouldContinue = { keepRunning.get() && calls.get() < 2 },
            onSuccess = {
                successes.incrementAndGet()
                keepRunning.set(false)
            },
            onFailure = {
                failures.incrementAndGet()
            }
        )

        assertEquals(1, failures.get())
        assertTrue(successes.get() >= 1)
        assertTrue(calls.get() >= 2)
    }

    @Test
    fun failedDeviceKeepsItsUuidUntilTheFullReportSucceeds() = runBlocking {
        val devices = listOf(
            RandomDeviceFactory.create(),
            RandomDeviceFactory.create()
        )
        var generatedDevices = 0
        var calls = 0
        val reportedUuids = mutableListOf<String>()
        val reporter = SimulatedDeviceReporter { _, device ->
            calls += 1
            reportedUuids += device.uuid
            if (calls <= 2) error("report incomplete")
        }
        val loop = SimulatedDeviceLoop(
            reporter = reporter,
            deviceFactory = { devices[generatedDevices++] },
            nowMs = { 0L },
            delayNext = {}
        )

        loop.run(
            config = validConfig(),
            shouldContinue = { calls < 4 },
            onSuccess = {},
            onFailure = {}
        )

        assertEquals(
            listOf(
                devices[0].uuid,
                devices[0].uuid,
                devices[0].uuid,
                devices[1].uuid
            ),
            reportedUuids
        )
        assertEquals(2, generatedDevices)
    }

    @Test
    fun invalidConfigIsRejectedBeforeStart() {
        assertEquals(
            "请先填写模拟器服务端 IP 或域名",
            SimulatedDeviceConfig.validationError("", "8008", "secret")
        )
        assertEquals(
            "模拟器端口号无效，请填写 1-65535 之间的数字",
            SimulatedDeviceConfig.validationError("example.com", "70000", "secret")
        )
        assertEquals(
            "请先填写模拟器客户端密钥 (Secret)",
            SimulatedDeviceConfig.validationError("example.com", "8008", "")
        )
        val threadCountError =
            "模拟器并发线程数无效，请填写 1-${SimulatedDeviceConfig.MAX_THREAD_COUNT} 之间的数字"
        assertEquals(
            threadCountError,
            SimulatedDeviceConfig.validationError("example.com", "8008", "secret", "")
        )
        assertEquals(
            threadCountError,
            SimulatedDeviceConfig.validationError("example.com", "8008", "secret", "fast")
        )
        assertEquals(
            threadCountError,
            SimulatedDeviceConfig.validationError("example.com", "8008", "secret", "0")
        )
        assertEquals(
            threadCountError,
            SimulatedDeviceConfig.validationError("example.com", "8008", "secret", "51")
        )
        assertNull(
            SimulatedDeviceConfig.validationError("example.com", "8008", "secret", "1")
        )
        assertNull(
            SimulatedDeviceConfig.validationError("example.com", "8008", "secret", "50")
        )
    }

    private fun validConfig(threadCount: Int = 1): SimulatedDeviceConfig =
        SimulatedDeviceConfig(
            server = "example.com",
            port = 8008,
            secret = "secret",
            useTls = true,
            threadCount = threadCount
        )
}

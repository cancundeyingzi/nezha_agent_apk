package com.nezhahq.agent.service

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.RemoteCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeControllerTest {
    @Test
    fun oldRuntimeStopsBeforeReplacementIsCreatedAndStarted() = runBlocking {
        val events = mutableListOf<String>()
        var nextId = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                val id = ++nextId
                events += "create-$id"
                RecordingRuntime(
                    onStart = { events += "start-$id" },
                    onStop = { events += "stop-$id" }
                )
            }
        )

        controller.reload(config("first"))
        controller.reload(config("second"))

        assertEquals(
            listOf("create-1", "start-1", "stop-1", "create-2", "start-2"),
            events
        )
    }

    @Test
    fun failedReplacementIsClosedAndControllerRemainsStopped() = runBlocking {
        var stops = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime(
                    onStart = { error("start failed") },
                    onStop = { stops++ }
                )
            }
        )

        val failure = runCatching { controller.reload(config("failed")) }.exceptionOrNull()

        assertEquals("start failed", failure?.message)
        assertEquals(1, stops)
        assertFalse(controller.isRunning)
    }

    @Test
    fun repeatedStopIsIdempotentIncludingFinalCleanup() = runBlocking {
        var runtimeStops = 0
        var finalCleanups = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime(onStop = { runtimeStops++ })
            },
            finalCleanup = { finalCleanups++ }
        )
        controller.reload(config("running"))

        controller.stop()
        controller.stop()

        assertEquals(1, runtimeStops)
        assertEquals(1, finalCleanups)
    }

    @Test
    fun capabilityUpdateTargetsOnlyCurrentRuntime() = runBlocking {
        val runtimes = mutableListOf<RecordingRuntime>()
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime().also(runtimes::add)
            }
        )
        val first = RemoteCapabilities(shellEnabled = true)
        val second = RemoteCapabilities(natEnabled = true)

        controller.reload(config("one"))
        controller.updateCapabilities(first)
        controller.reload(config("two"))
        controller.updateCapabilities(second)

        assertEquals(listOf(first), runtimes[0].capabilityUpdates)
        assertEquals(listOf(second), runtimes[1].capabilityUpdates)
        assertTrue(controller.isRunning)
    }

    /**
     * Teardown is terminal: nothing would retry it after the service is gone, so withholding the
     * privileged shell because the runtime failed would leak it for the rest of the process.
     */
    @Test
    fun stopReleasesProcessResourcesEvenWhenTheRuntimeFailsToStop() = runBlocking {
        var finalCleanups = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime(onStop = { error("teardown failed") })
            },
            finalCleanup = { finalCleanups++ }
        )
        controller.reload(config("running"))

        val failure = runCatching { controller.stop() }.exceptionOrNull()

        assertEquals("teardown failed", failure?.message)
        assertEquals(1, finalCleanups)
        assertFalse(controller.isRunning)
    }

    @Test
    fun stopReportsRuntimeAndCleanupFailuresTogether() = runBlocking {
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime(onStop = { error("teardown failed") })
            },
            finalCleanup = { error("cleanup failed") }
        )
        controller.reload(config("running"))

        val failure = runCatching { controller.stop() }.exceptionOrNull()

        assertEquals("teardown failed", failure?.message)
        assertEquals(
            listOf("cleanup failed"),
            failure?.suppressed?.map(Throwable::message)
        )
    }

    /**
     * Keep-alive must survive a botched cleanup: the replacement still starts, so the agent stays
     * connected instead of the service stopping itself over a best-effort teardown.
     */
    @Test
    fun reloadStillStartsTheReplacementWhenTheOldRuntimeFailsToStop() = runBlocking {
        val started = mutableListOf<String>()
        val teardownFailures = mutableListOf<Throwable>()
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory { snapshot ->
                RecordingRuntime(
                    onStart = { started += snapshot.server },
                    onStop = { error("teardown failed") }
                )
            },
            onTeardownFailure = { teardownFailures += it }
        )
        controller.reload(config("first"))

        controller.reload(config("second"))

        assertEquals(listOf("first", "second"), started)
        assertEquals(listOf("teardown failed"), teardownFailures.map(Throwable::message))
        assertTrue(controller.isRunning)
    }

    /**
     * Collectors query the privileged shell as soon as the runtime starts, so authorization has to
     * be applied before it exists — and it must follow the snapshot being started, not the previous
     * one, or a reload that turns root mode off would leave it granted.
     */
    @Test
    fun privilegedAccessIsAppliedForEachSnapshotBeforeItsRuntimeStarts() = runBlocking {
        val events = mutableListOf<String>()
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory { snapshot ->
                RecordingRuntime(onStart = { events += "start-rootMode=${snapshot.rootMode}" })
            },
            applyPrivilegedAccess = { enabled -> events += "authorize=$enabled" }
        )

        controller.reload(config("first").copy(rootMode = true))
        controller.reload(config("second").copy(rootMode = false))

        assertEquals(
            listOf(
                "authorize=true",
                "start-rootMode=true",
                "authorize=false",
                "start-rootMode=false"
            ),
            events
        )
    }

    @Test
    fun stopReleasesTheProcessShellThatReloadAuthorized() = runBlocking {
        val events = mutableListOf<String>()
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory { RecordingRuntime() },
            applyPrivilegedAccess = { enabled -> events += "authorize=$enabled" },
            finalCleanup = { events += "release-shell" }
        )
        controller.reload(config("running").copy(rootMode = true))

        controller.stop()

        assertEquals(listOf("authorize=true", "release-shell"), events)
    }

    private class RecordingRuntime(
        private val onStart: suspend () -> Unit = {},
        private val onStop: suspend () -> Unit = {}
    ) : AgentRuntimeHandle {
        val capabilityUpdates = mutableListOf<RemoteCapabilities>()

        override suspend fun start() = onStart()

        override suspend fun stop() = onStop()

        override suspend fun updateCapabilities(capabilities: RemoteCapabilities) {
            capabilityUpdates += capabilities
        }
    }
}

internal fun config(server: String) = AgentConfig(
    server = server,
    port = 5555,
    secret = "secret",
    uuid = "uuid",
    useTls = true,
    rootMode = false
)

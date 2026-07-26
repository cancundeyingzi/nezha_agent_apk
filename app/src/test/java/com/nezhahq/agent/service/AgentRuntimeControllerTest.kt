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

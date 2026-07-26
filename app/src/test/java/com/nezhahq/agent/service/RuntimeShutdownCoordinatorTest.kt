package com.nezhahq.agent.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeShutdownCoordinatorTest {
    @Test
    fun workAndAllResourcesCloseOnceInOrder() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = RuntimeShutdownCoordinator(
            cancelAndJoinWork = { events += "cancel-and-join-tasks-and-streams" },
            closeNetwork = { events += "unregister-network" },
            closeKeepAlive = { events += "close-keepalive" },
            closeGrpc = { events += "close-channel" }
        )

        coordinator.close()
        coordinator.close()

        assertEquals(
            listOf(
                "cancel-and-join-tasks-and-streams",
                "unregister-network",
                "close-keepalive",
                "close-channel"
            ),
            events
        )
    }

    @Test
    fun everyPhaseRunsEvenAfterAnEarlierOneFails() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = RuntimeShutdownCoordinator(
            cancelAndJoinWork = { error("work teardown failed") },
            closeNetwork = { events += "unregister-network" },
            closeKeepAlive = { events += "close-keepalive" },
            closeGrpc = { events += "close-channel" }
        )

        val failure = runCatching { coordinator.close() }.exceptionOrNull()

        assertEquals("work teardown failed", failure?.message)
        assertEquals(
            listOf("unregister-network", "close-keepalive", "close-channel"),
            events
        )
    }

    /**
     * The retry only reaches the coordinator because [AgentRuntime.stop] stays non-terminal after a
     * failure; a runtime wedged in STOPPING would short-circuit every later attempt.
     */
    @Test
    fun retryResumesAtTheFailedPhaseWithoutRepeatingSucceededOnes() = runBlocking {
        val events = mutableListOf<String>()
        var keepAliveFailuresLeft = 1
        val coordinator = RuntimeShutdownCoordinator(
            cancelAndJoinWork = { events += "cancel-and-join-tasks-and-streams" },
            closeNetwork = { events += "unregister-network" },
            closeKeepAlive = {
                events += "close-keepalive"
                if (keepAliveFailuresLeft-- > 0) error("keep-alive teardown failed")
            },
            closeGrpc = { events += "close-channel" }
        )

        val failure = runCatching { coordinator.close() }.exceptionOrNull()
        coordinator.close()

        assertEquals("keep-alive teardown failed", failure?.message)
        assertEquals(
            listOf(
                "cancel-and-join-tasks-and-streams",
                "unregister-network",
                "close-keepalive",
                "close-channel",
                "close-keepalive"
            ),
            events
        )
    }
}

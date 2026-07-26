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
}

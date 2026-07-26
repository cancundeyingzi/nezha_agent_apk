package com.nezhahq.agent.service

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single ordered teardown gate for a runtime's work and owned resources.
 */
internal class RuntimeShutdownCoordinator(
    private val cancelAndJoinWork: suspend () -> Unit,
    private val closeNetwork: () -> Unit,
    private val closeKeepAlive: suspend () -> Unit,
    private val closeGrpc: () -> Unit
) {
    private val closed = AtomicBoolean()

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelAndJoinWork()
        closeNetwork()
        closeKeepAlive()
        closeGrpc()
    }
}

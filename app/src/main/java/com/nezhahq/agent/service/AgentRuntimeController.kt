package com.nezhahq.agent.service

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.RemoteCapabilities
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface AgentRuntimeHandle {
    suspend fun start()
    suspend fun stop()
    suspend fun updateCapabilities(capabilities: RemoteCapabilities)
}

internal fun interface AgentRuntimeFactory {
    fun create(config: AgentConfig): AgentRuntimeHandle
}

/**
 * Serializes runtime replacement. The old runtime is fully stopped before a replacement exists.
 */
internal class AgentRuntimeController(
    private val factory: AgentRuntimeFactory,
    private val finalCleanup: () -> Unit = {}
) {
    private val transitionMutex = Mutex()
    private var current: AgentRuntimeHandle? = null
    private var finalCleanupComplete = false

    val isRunning: Boolean
        get() = synchronized(this) { current != null }

    suspend fun reload(config: AgentConfig) = transitionMutex.withLock {
        val old = synchronized(this) {
            current.also { current = null }
        }
        old?.stop()

        val replacement = factory.create(config)
        try {
            replacement.start()
            synchronized(this) {
                current = replacement
                finalCleanupComplete = false
            }
        } catch (failure: Throwable) {
            runCatching { replacement.stop() }
            throw failure
        }
    }

    suspend fun updateCapabilities(capabilities: RemoteCapabilities) =
        transitionMutex.withLock {
            synchronized(this) { current }?.updateCapabilities(capabilities)
        }

    suspend fun stop() = transitionMutex.withLock {
        val old = synchronized(this) {
            current.also { current = null }
        }
        old?.stop()
        val shouldClean = synchronized(this) {
            if (finalCleanupComplete) false else {
                finalCleanupComplete = true
                true
            }
        }
        if (shouldClean) finalCleanup()
    }
}

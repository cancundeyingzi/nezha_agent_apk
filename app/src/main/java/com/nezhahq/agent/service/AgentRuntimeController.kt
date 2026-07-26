package com.nezhahq.agent.service

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.RemoteCapabilities
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 *
 * Every method runs to completion in the caller's context. Moving teardown off the main thread is
 * [RuntimeShutdownGate]'s job, so this class never owns a background scope of its own.
 */
internal class AgentRuntimeController(
    private val factory: AgentRuntimeFactory,
    private val finalCleanup: () -> Unit = {},
    private val onTeardownFailure: (Throwable) -> Unit = {}
) {
    private val transitionMutex = Mutex()
    private var current: AgentRuntimeHandle? = null
    private var finalCleanupComplete = false

    val isRunning: Boolean
        get() = synchronized(this) { current != null }

    /**
     * Replaces the runtime with one built from [config].
     *
     * A teardown failure of the old runtime is reported but never aborts the replacement: its
     * ownership is already released, and dropping the connection over a best-effort cleanup would
     * take the agent — and its keep-alive resources — offline for no gain.
     */
    suspend fun reload(config: AgentConfig) {
        transitionMutex.withLock {
            stopCurrent()?.let(onTeardownFailure)

            // A service-destruction cancellation arriving during teardown must win before creation.
            currentCoroutineContext().ensureActive()

            val replacement = factory.create(config)
            startOrDiscard(replacement)
            synchronized(this) {
                current = replacement
                finalCleanupComplete = false
            }
        }
    }

    suspend fun updateCapabilities(capabilities: RemoteCapabilities) {
        transitionMutex.withLock {
            synchronized(this) { current }?.updateCapabilities(capabilities)
        }
    }

    /**
     * Terminal teardown. Process-owned resources are released even when the runtime failed to stop.
     *
     * Nothing drives a retry once the service is gone, so withholding the privileged shell for a
     * cleanup that will never run would leak it for the remaining lifetime of the process.
     */
    suspend fun stop() {
        transitionMutex.withLock {
            val failures = listOfNotNull(stopCurrent(), releaseProcessResources())
            val primary = failures.firstOrNull() ?: return@withLock
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }

    /**
     * Stops the current runtime and gives up ownership of it either way.
     *
     * A runtime that failed to tear down is not reusable, so keeping it as [current] would only
     * make [isRunning] report a connection that no longer runs.
     */
    private suspend fun stopCurrent(): Throwable? {
        val old = synchronized(this) { current.also { current = null } } ?: return null
        return withContext(NonCancellable) { runCatching { old.stop() }.exceptionOrNull() }
    }

    private suspend fun startOrDiscard(replacement: AgentRuntimeHandle) {
        try {
            replacement.start()
        } catch (failure: Throwable) {
            val cleanupFailure = withContext(NonCancellable) {
                runCatching { replacement.stop() }.exceptionOrNull()
            }
            if (cleanupFailure != null && cleanupFailure !== failure) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private suspend fun releaseProcessResources(): Throwable? {
        val shouldRelease = synchronized(this) {
            if (finalCleanupComplete) {
                false
            } else {
                finalCleanupComplete = true
                true
            }
        }
        if (!shouldRelease) return null
        return withContext(NonCancellable) { runCatching { finalCleanup() }.exceptionOrNull() }
    }
}

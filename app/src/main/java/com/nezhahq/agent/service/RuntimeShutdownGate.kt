package com.nezhahq.agent.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-level gate that runs service shutdowns in the background, one at a time.
 *
 * `Service.onDestroy` runs on the main thread and must not wait for a shutdown: cancelling a
 * runtime joins stream sessions whose blocking socket reads do not observe coroutine cancellation.
 * Shutdown also releases process-owned resources such as the privileged shell, so the next runtime
 * may only start once the previous shutdown has finished — that is what [awaitIdle] guarantees.
 */
internal class RuntimeShutdownGate(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onFailure: (Throwable) -> Unit
) {
    private val lock = Any()
    private var pending: Job? = null

    /**
     * Queues [shutdown] after any shutdown that has not finished yet.
     *
     * A failing shutdown is reported and then treated as finished: it must neither crash the
     * process nor stall the queue, because the next service instance waits on this job.
     */
    fun submit(shutdown: suspend () -> Unit): Job = synchronized(lock) {
        val previous = pending
        val job = scope.launch {
            previous?.join()
            try {
                shutdown()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                onFailure(failure)
            }
        }
        pending = job
        job
    }

    /** Suspends until every submitted shutdown has finished, successfully or not. */
    suspend fun awaitIdle() {
        synchronized(lock) { pending }?.join()
    }
}

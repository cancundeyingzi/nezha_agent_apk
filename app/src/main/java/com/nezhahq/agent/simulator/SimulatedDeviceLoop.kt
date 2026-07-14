package com.nezhahq.agent.simulator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SimulatedDeviceLoop(
    private val reporter: SimulatedDeviceReporter,
    private val intervalMs: Long = 1_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayNext: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun run(
        config: SimulatedDeviceConfig,
        shouldContinue: () -> Boolean,
        onSuccess: suspend () -> Unit,
        onFailure: suspend (Throwable) -> Unit
    ) {
        val threadCount = config.threadCount
            .coerceIn(1, SimulatedDeviceConfig.MAX_THREAD_COUNT)
        val staggerMs = if (threadCount > 1) {
            (intervalMs / threadCount).coerceAtLeast(1L)
        } else {
            0L
        }

        coroutineScope {
            repeat(threadCount) { workerIndex ->
                launch {
                    if (staggerMs > 0L && workerIndex > 0) {
                        delayNext(staggerMs * workerIndex)
                    }
                    runWorker(config, shouldContinue, onSuccess, onFailure)
                }
            }
        }
    }

    private suspend fun runWorker(
        config: SimulatedDeviceConfig,
        shouldContinue: () -> Boolean,
        onSuccess: suspend () -> Unit,
        onFailure: suspend (Throwable) -> Unit
    ) {
        while (shouldContinue()) {
            val startedAt = nowMs()
            try {
                reporter.reportOne(config)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onFailure(t)
            }

            val remaining = intervalMs - (nowMs() - startedAt)
            if (shouldContinue() && remaining > 0L) {
                delayNext(remaining)
            }
        }
    }
}

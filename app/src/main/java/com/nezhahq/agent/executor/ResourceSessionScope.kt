package com.nezhahq.agent.executor

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

/**
 * Runs a structured session and closes its blocking resources before waiting for child jobs.
 *
 * This ordering is important for jobs blocked in Process or Socket reads: cancellation alone does
 * not interrupt ordinary Java blocking I/O, while closing the owning resource does.
 */
internal suspend fun resourceSessionScope(
    closeResource: () -> Unit,
    block: suspend CoroutineScope.() -> Unit
) {
    val closed = AtomicBoolean()
    fun closeOnce() {
        if (closed.compareAndSet(false, true)) closeResource()
    }

    try {
        coroutineScope {
            try {
                block()
            } finally {
                closeOnce()
            }
        }
    } finally {
        closeOnce()
    }
}

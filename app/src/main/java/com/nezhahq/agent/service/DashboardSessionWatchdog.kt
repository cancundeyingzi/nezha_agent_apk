package com.nezhahq.agent.service

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout

class DashboardSessionTimeoutException(
    message: String,
    cause: Throwable
) : RuntimeException(message, cause)

object DashboardSessionWatchdog {
    const val HANDSHAKE_TIMEOUT_MS = 5_000L
    const val STATE_RECEIPT_TIMEOUT_MS = 10_000L
    const val TASK_IDLE_TIMEOUT_MS = 30_000L
    const val RECONNECT_BACKOFF_MS = 5_000L
    const val STATE_REPORT_INTERVAL_MS = 2_000L

    suspend fun <T> callWithin(
        timeoutMs: Long,
        operationName: String,
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw DashboardSessionTimeoutException(
                "$operationName timed out after ${timeoutMs}ms",
                e
            )
        }
    }

    suspend fun <T> receiveWithin(
        channel: ReceiveChannel<T>,
        timeoutMs: Long,
        streamName: String
    ): T = callWithin(timeoutMs, streamName) { channel.receive() }
}

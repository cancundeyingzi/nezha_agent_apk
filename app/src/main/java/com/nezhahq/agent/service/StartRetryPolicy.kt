package com.nezhahq.agent.service

/**
 * Backoff schedule for retrying a runtime start that failed for a possibly transient reason.
 *
 * Retrying never gives up: a monitoring agent that stops trying is offline for good, and the
 * failures this covers — a rejected network callback, a channel that could not be built — usually
 * clear on their own. The cap keeps a permanently broken device from retrying in a tight loop.
 */
internal class StartRetryPolicy(
    private val initialDelayMillis: Long = INITIAL_DELAY_MILLIS,
    private val maxDelayMillis: Long = MAX_DELAY_MILLIS
) {
    private val lock = Any()
    private var currentDelayMillis = initialDelayMillis

    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive." }
        require(maxDelayMillis >= initialDelayMillis) {
            "maxDelayMillis must not be below initialDelayMillis."
        }
    }

    /** Returns how long to wait before the next attempt, then doubles it up to the cap. */
    fun nextDelayMillis(): Long = synchronized(lock) {
        currentDelayMillis.also {
            currentDelayMillis = (it * 2).coerceAtMost(maxDelayMillis)
        }
    }

    /** Returns to the initial delay after a successful start or an explicit user request. */
    fun reset() = synchronized(lock) {
        currentDelayMillis = initialDelayMillis
    }

    private companion object {
        const val INITIAL_DELAY_MILLIS = 5_000L
        const val MAX_DELAY_MILLIS = 60_000L
    }
}

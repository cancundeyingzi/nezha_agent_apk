package com.nezhahq.agent.collector

/** Adds two non-negative counters, returning null rather than wrapping on overflow. */
internal fun addWithoutOverflow(left: Long, right: Long): Long? =
    if (left < 0L || right < 0L || right > Long.MAX_VALUE - left) null else left + right

/** Adds two non-negative counters and saturates if their sum cannot fit in a [Long]. */
internal fun addSaturating(left: Long, right: Long): Long =
    addWithoutOverflow(left, right) ?: Long.MAX_VALUE

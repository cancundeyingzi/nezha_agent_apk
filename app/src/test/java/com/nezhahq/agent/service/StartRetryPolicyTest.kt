package com.nezhahq.agent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StartRetryPolicyTest {
    @Test
    fun delaysDoubleUntilTheyReachTheCapAndStayThere() {
        val policy = StartRetryPolicy(initialDelayMillis = 5_000L, maxDelayMillis = 60_000L)

        val delays = List(7) { policy.nextDelayMillis() }

        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L),
            delays
        )
    }

    @Test
    fun resetReturnsToTheInitialDelay() {
        val policy = StartRetryPolicy(initialDelayMillis = 5_000L, maxDelayMillis = 60_000L)
        repeat(4) { policy.nextDelayMillis() }

        policy.reset()

        assertEquals(5_000L, policy.nextDelayMillis())
        assertEquals(10_000L, policy.nextDelayMillis())
    }

    @Test
    fun aCapBelowTheInitialDelayIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StartRetryPolicy(initialDelayMillis = 5_000L, maxDelayMillis = 1_000L)
        }
    }

    @Test
    fun aNonPositiveInitialDelayIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StartRetryPolicy(initialDelayMillis = 0L, maxDelayMillis = 60_000L)
        }
    }
}

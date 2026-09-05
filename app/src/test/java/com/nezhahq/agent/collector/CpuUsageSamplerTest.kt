package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuUsageSamplerTest {
    private val sampler = CpuUsageSampler()

    @Test
    fun `first valid sample only establishes baseline`() {
        assertEquals(0.0, sampler.sample("cpu 100 0 100 800 0 0 0 0"), 0.0)
    }

    @Test
    fun `second sample calculates interval usage`() {
        sampler.sample("cpu 100 0 100 800 0 0 0 0")

        val usage = sampler.sample("cpu 150 0 150 850 0 0 0 0")

        assertEquals(66.666, usage, 0.001)
    }

    @Test
    fun `counter rollback resets baseline`() {
        sampler.sample("cpu 100 20 100 800 0 0 0 0")

        assertEquals(0.0, sampler.sample("cpu 200 10 200 900 0 0 0 0"), 0.0)

        val usage = sampler.sample("cpu 250 20 250 980 0 0 0 0")
        assertEquals(57.894, usage, 0.001)
    }

    @Test
    fun `brief invalid sample keeps the previous usage and baseline`() {
        sampler.sample("cpu 100 0 100 800 0 0 0 0")
        assertEquals(66.666, sampler.sample("cpu 150 0 150 850 0 0 0 0"), 0.001)

        assertEquals(66.666, sampler.sample("not cpu data"), 0.001)
        assertEquals(66.666, sampler.sample("cpu 200 0 200 900 0 0 0 0"), 0.001)
    }

    @Test
    fun `persistent read failure eventually clears stale usage`() {
        sampler.sample("cpu 100 0 100 800 0 0 0 0")
        sampler.sample("cpu 150 0 150 850 0 0 0 0")

        assertEquals(66.666, sampler.sample(null), 0.001)
        assertEquals(66.666, sampler.sample(null), 0.001)
        assertEquals(0.0, sampler.sample(null), 0.0)
        assertEquals(0.0, sampler.sample("cpu 200 0 200 900 0 0 0 0"), 0.0)
    }
}

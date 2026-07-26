package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSpeedSamplerTest {
    private val sampler = NetworkSpeedSampler()

    @Test
    fun `first sample only establishes baseline`() {
        val speed = sampler.sample(primary(1_000L, 2_000L), nowMs = 1_000L)

        assertEquals(0L, speed.rxBytesPerSecond)
        assertEquals(0L, speed.txBytesPerSecond)
    }

    @Test
    fun `second sample divides the delta by the elapsed interval`() {
        sampler.sample(primary(1_000L, 2_000L), nowMs = 1_000L)

        val speed = sampler.sample(primary(4_000L, 5_000L), nowMs = 3_000L)

        assertEquals(1_500L, speed.rxBytesPerSecond)
        assertEquals(1_500L, speed.txBytesPerSecond)
    }

    /**
     * A counter that goes backwards inside one source — kernel counter wrap, or the source itself
     * being swapped for one with a smaller base. Subtracting yields a negative speed, which must
     * never reach the dashboard.
     */
    @Test
    fun `a counter going backwards reports zero instead of a negative speed`() {
        sampler.sample(primary(10_000L, 20_000L), nowMs = 1_000L)

        val speed = sampler.sample(primary(500L, 700L), nowMs = 3_000L)

        assertEquals(0L, speed.rxBytesPerSecond)
        assertEquals(0L, speed.txBytesPerSecond)
    }

    /**
     * The other half of the problem, and the one a "did it shrink?" check cannot see:
     * `/proc/net/dev` aggregates every interface, so switching to it from the device-scoped
     * TrafficStats counters is a jump *upwards*. Without comparing the source, this tick would
     * report a multi-gigabyte-per-second spike.
     */
    @Test
    fun `switching to a larger counter source reports zero instead of a huge spike`() {
        sampler.sample(primary(10_000L, 20_000L), nowMs = 1_000L)

        val speed = sampler.sample(procNetDev(9_000_000_000L, 8_000_000_000L), nowMs = 3_000L)

        assertEquals(0L, speed.rxBytesPerSecond)
        assertEquals(0L, speed.txBytesPerSecond)
    }

    /** Once both ticks come from the same source again, normal deltas resume. */
    @Test
    fun `sampling recovers on the tick after a source switch`() {
        sampler.sample(primary(10_000L, 20_000L), nowMs = 1_000L)
        sampler.sample(procNetDev(9_000_000_000L, 8_000_000_000L), nowMs = 3_000L)

        val speed = sampler.sample(procNetDev(9_000_002_000L, 8_000_004_000L), nowMs = 5_000L)

        assertEquals(1_000L, speed.rxBytesPerSecond)
        assertEquals(2_000L, speed.txBytesPerSecond)
    }

    /** The rolled-back value has to become the new baseline, or every later tick stays negative. */
    @Test
    fun `sampling recovers on the tick after a rollback`() {
        sampler.sample(primary(10_000L, 20_000L), nowMs = 1_000L)
        sampler.sample(primary(500L, 700L), nowMs = 3_000L)

        val speed = sampler.sample(primary(1_500L, 1_700L), nowMs = 5_000L)

        assertEquals(500L, speed.rxBytesPerSecond)
        assertEquals(500L, speed.txBytesPerSecond)
    }

    /** One direction going backwards invalidates the whole tick: both counters share a source. */
    @Test
    fun `a rollback in either direction discards the tick`() {
        sampler.sample(primary(1_000L, 5_000L), nowMs = 1_000L)

        val speed = sampler.sample(primary(2_000L, 4_000L), nowMs = 3_000L)

        assertEquals(0L, speed.rxBytesPerSecond)
        assertEquals(0L, speed.txBytesPerSecond)
    }

    @Test
    fun `a non advancing clock reports zero rather than dividing by zero`() {
        sampler.sample(primary(1_000L, 2_000L), nowMs = 1_000L)

        val speed = sampler.sample(primary(4_000L, 5_000L), nowMs = 1_000L)

        assertEquals(0L, speed.rxBytesPerSecond)
        assertEquals(0L, speed.txBytesPerSecond)
    }

    private fun primary(rx: Long, tx: Long) =
        TrafficReading(TrafficSnapshot(rx, tx), TrafficSource.PRIMARY)

    private fun procNetDev(rx: Long, tx: Long) =
        TrafficReading(TrafficSnapshot(rx, tx), TrafficSource.PROC_NET_DEV)
}

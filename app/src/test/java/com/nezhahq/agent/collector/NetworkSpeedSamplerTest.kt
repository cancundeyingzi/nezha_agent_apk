package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkSpeedSamplerTest {
    @Test
    fun `normal samples calculate bytes per second`() {
        val sampler = NetworkSpeedSampler()
        sampler.sample(reading(1_000L, 2_000L), 1_000L)

        val sample = sampler.sample(reading(4_000L, 8_000L), 3_000L)

        assertEquals(1_500L, sample.rxBytesPerSecond)
        assertEquals(3_000L, sample.txBytesPerSecond)
    }

    @Test
    fun `counter rollback cannot become an unsigned dashboard spike`() {
        val sampler = NetworkSpeedSampler()
        sampler.sample(reading(10_000L, 20_000L), 1_000L)

        val sample = sampler.sample(reading(500L, 700L), 3_000L)

        assertEquals(0L, sample.rxBytesPerSecond)
        assertEquals(0L, sample.txBytesPerSecond)
    }

    @Test
    fun `counter source switch establishes a new baseline`() {
        val sampler = NetworkSpeedSampler()
        sampler.sample(reading(1_000L, 2_000L), 1_000L)

        val switched = sampler.sample(
            reading(9_000_000_000L, 8_000_000_000L, TrafficSource.TOTAL_TRAFFIC_STATS),
            3_000L
        )
        val recovered = sampler.sample(
            reading(9_000_002_000L, 8_000_004_000L, TrafficSource.TOTAL_TRAFFIC_STATS),
            5_000L
        )

        assertEquals(0L, switched.rxBytesPerSecond)
        assertEquals(0L, switched.txBytesPerSecond)
        assertEquals(TrafficSnapshot(1_000L, 2_000L), switched.snapshot)
        assertEquals(1_000L, recovered.rxBytesPerSecond)
        assertEquals(2_000L, recovered.txBytesPerSecond)
        assertEquals(TrafficSnapshot(3_000L, 6_000L), recovered.snapshot)
    }

    @Test
    fun `rate calculation saturates instead of overflowing`() {
        val sampler = NetworkSpeedSampler()
        sampler.sample(reading(0L, 0L), 1_000L)

        val sample = sampler.sample(reading(Long.MAX_VALUE, Long.MAX_VALUE), 1_001L)

        assertEquals(Long.MAX_VALUE, sample.rxBytesPerSecond)
        assertEquals(Long.MAX_VALUE, sample.txBytesPerSecond)
    }

    @Test
    fun `brief missing samples reuse the last rate but stale speed expires`() {
        val sampler = NetworkSpeedSampler()
        sampler.sample(reading(1_000L, 2_000L), 1_000L)
        sampler.sample(reading(3_000L, 6_000L), 3_000L)

        assertEquals(1_000L, sampler.sample(null, 5_000L).rxBytesPerSecond)
        assertEquals(1_000L, sampler.sample(null, 7_000L).rxBytesPerSecond)
        assertEquals(0L, sampler.sample(null, 9_000L).rxBytesPerSecond)
    }

    @Test
    fun `selector ignores one transient source failure`() {
        val selector = TrafficSourceSelector(toleratedFailures = 1)
        var privilegedAvailable = true
        val candidates = listOf(
            TrafficSource.PRIVILEGED_PROC_NET_DEV,
            TrafficSource.TOTAL_TRAFFIC_STATS
        )
        val reader: (TrafficSource) -> TrafficSnapshot? = { source ->
            when (source) {
                TrafficSource.PRIVILEGED_PROC_NET_DEV ->
                    if (privilegedAvailable) TrafficSnapshot(10L, 20L) else null
                else -> TrafficSnapshot(100L, 200L)
            }
        }

        assertEquals(TrafficSource.PRIVILEGED_PROC_NET_DEV, selector.read(candidates, reader)?.source)
        privilegedAvailable = false
        assertNull(selector.read(candidates, reader))
        assertEquals(TrafficSource.TOTAL_TRAFFIC_STATS, selector.read(candidates, reader)?.source)
    }

    @Test
    fun `selector returns to the preferred source after it recovers`() {
        val selector = TrafficSourceSelector(toleratedFailures = 0)
        var privilegedAvailable = false
        val candidates = listOf(
            TrafficSource.PRIVILEGED_PROC_NET_DEV,
            TrafficSource.TOTAL_TRAFFIC_STATS
        )
        val reader: (TrafficSource) -> TrafficSnapshot? = { source ->
            if (source == TrafficSource.PRIVILEGED_PROC_NET_DEV && !privilegedAvailable) null
            else TrafficSnapshot(10L, 20L)
        }

        assertEquals(TrafficSource.TOTAL_TRAFFIC_STATS, selector.read(candidates, reader)?.source)
        privilegedAvailable = true
        assertEquals(TrafficSource.PRIVILEGED_PROC_NET_DEV, selector.read(candidates, reader)?.source)
    }

    private fun reading(
        rx: Long,
        tx: Long,
        source: TrafficSource = TrafficSource.PRIVILEGED_PROC_NET_DEV
    ) = TrafficReading(TrafficSnapshot(rx, tx), source)
}

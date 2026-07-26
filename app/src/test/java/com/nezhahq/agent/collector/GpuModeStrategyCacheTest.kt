package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuModeStrategyCacheTest {

    @Test
    fun `normal unavailable does not prevent privileged probe`() {
        val cache = GpuModeStrategyCache()
        var privilegedProbes = 0

        val normal = cache.collect(
            isPrivileged = false,
            readCached = { error("normal strategy should not already be cached") },
            probe = {
                GpuProbeResult(GpuCollectionStrategy.UNAVAILABLE, emptyList())
            }
        )
        val privileged = cache.collect(
            isPrivileged = true,
            readCached = { error("privileged strategy should not already be cached") },
            probe = {
                privilegedProbes++
                GpuProbeResult(GpuCollectionStrategy.SHELL_FS, listOf(42.0))
            }
        )

        assertEquals(emptyList<Double>(), normal)
        assertEquals(listOf(42.0), privileged)
        assertEquals(1, privilegedProbes)
    }

    @Test
    fun `collector cache instances do not share strategy`() {
        val first = GpuModeStrategyCache()
        val second = GpuModeStrategyCache()
        var secondProbes = 0

        first.collect(
            isPrivileged = true,
            readCached = { error("first strategy should not already be cached") },
            probe = {
                GpuProbeResult(GpuCollectionStrategy.UNAVAILABLE, emptyList())
            }
        )
        val secondResult = second.collect(
            isPrivileged = true,
            readCached = { error("second strategy should not already be cached") },
            probe = {
                secondProbes++
                GpuProbeResult(GpuCollectionStrategy.DUMPSYS, listOf(73.0))
            }
        )

        assertEquals(listOf(73.0), secondResult)
        assertEquals(1, secondProbes)
    }

    @Test
    fun `switching mode uses each modes cached strategy`() {
        val cache = GpuModeStrategyCache()
        var normalProbes = 0
        var privilegedProbes = 0

        cache.collect(
            isPrivileged = false,
            readCached = { error("normal strategy should not already be cached") },
            probe = {
                normalProbes++
                GpuProbeResult(GpuCollectionStrategy.DIRECT, listOf(10.0))
            }
        )
        cache.collect(
            isPrivileged = true,
            readCached = { error("privileged strategy should not already be cached") },
            probe = {
                privilegedProbes++
                GpuProbeResult(GpuCollectionStrategy.DUMPSYS, listOf(20.0))
            }
        )

        val normalAgain = cache.collect(
            isPrivileged = false,
            readCached = { strategy ->
                assertEquals(GpuCollectionStrategy.DIRECT, strategy)
                listOf(11.0)
            },
            probe = { error("normal mode should use its cache") }
        )
        val privilegedAgain = cache.collect(
            isPrivileged = true,
            readCached = { strategy ->
                assertEquals(GpuCollectionStrategy.DUMPSYS, strategy)
                listOf(21.0)
            },
            probe = { error("privileged mode should use its cache") }
        )

        assertEquals(listOf(11.0), normalAgain)
        assertEquals(listOf(21.0), privilegedAgain)
        assertEquals(1, normalProbes)
        assertEquals(1, privilegedProbes)
    }
}

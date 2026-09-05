package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegedMetricsSnapshotTest {
    @Test
    fun `battery section does not contaminate network counters`() {
        val output = """
            __NEZHA_CPU__
            cpu 1 0 1 8
            __NEZHA_LOAD__
            0 0 0
            __NEZHA_PROCESS__
            1
            __NEZHA_TCP__
            0
            __NEZHA_UDP__
            0
            __NEZHA_NET__
            wlan0: 1000 0 0 0 0 0 0 0 2000 0 0 0 0 0 0 0
            __NEZHA_BATTERY__
            POWER_SUPPLY_TYPE=Battery
            POWER_SUPPLY_POWER_NOW=8000000
        """.trimIndent()
        val snapshot = PrivilegedMetricsSnapshotParser.parse(output)!!
        assertEquals(TrafficSnapshot(1000L, 2000L), snapshot.traffic)
        assertEquals("POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_POWER_NOW=8000000", snapshot.batteryUevent)
        assertNull(PrivilegedMetricsSnapshotParser.parse(output.substringBefore("POWER_SUPPLY_TYPE"))!!.batteryUevent)
    }

    @Test
    fun `complete framed output parses as one coherent snapshot`() {
        val output = """
            ignored shell preamble
            __NEZHA_CPU__
            cpu 100 0 50 850 0 0 0 0
            __NEZHA_LOAD__
            0.34 0.28 0.22 1/345 12345
            __NEZHA_PROCESS__
            321
            __NEZHA_TCP__
            12
            __NEZHA_UDP__
            0
            __NEZHA_NET__
            lo: 1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0
            wlan0: 1000 0 0 0 0 0 0 0 2000 0 0 0 0 0 0 0
        """.trimIndent()

        val snapshot = PrivilegedMetricsSnapshotParser.parse(output)!!

        assertEquals("cpu 100 0 50 850 0 0 0 0", snapshot.cpuLine)
        assertEquals(Triple(0.34, 0.28, 0.22), snapshot.loadAverage)
        assertEquals(321L, snapshot.processCount)
        assertEquals(Pair(12L, 0L), snapshot.connectionCounts)
        assertEquals(TrafficSnapshot(1000L, 2000L), snapshot.traffic)
    }

    @Test
    fun `missing frame marker rejects partial shell output`() {
        assertNull(PrivilegedMetricsSnapshotParser.parse("__NEZHA_CPU__\ncpu 1 0 1 8"))
    }

    @Test
    fun `invalid fields stay unavailable instead of becoming synthetic zeroes`() {
        val output = """
            __NEZHA_CPU__
            permission denied
            __NEZHA_LOAD__
            NaN 0 0
            __NEZHA_PROCESS__
            0
            __NEZHA_TCP__
            failed
            __NEZHA_UDP__
            0
            __NEZHA_NET__
            permission denied
        """.trimIndent()

        val snapshot = PrivilegedMetricsSnapshotParser.parse(output)!!

        assertNull(snapshot.cpuLine)
        assertNull(snapshot.loadAverage)
        assertNull(snapshot.processCount)
        assertNull(snapshot.connectionCounts)
        assertNull(snapshot.traffic)
    }

    @Test
    fun `stable metric tolerates brief loss then uses fallback`() {
        val metric = StableMetric<Long>(toleratedFailures = 1)

        assertEquals(50L, metric.resolve(50L) { 1L })
        assertEquals(50L, metric.resolve(null) { 1L })
        assertEquals(1L, metric.resolve(null) { 1L })
    }
}

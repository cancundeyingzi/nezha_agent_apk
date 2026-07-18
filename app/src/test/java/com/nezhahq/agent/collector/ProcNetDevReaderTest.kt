package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcNetDevReaderTest {
    @Test
    fun `parse aggregates physical interfaces`() {
        val content = """
            Inter-|   Receive                                                |  Transmit
             face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed
              lo: 10 1 0 0 0 0 0 0 10 1 0 0 0 0 0 0
           wlan0: 100 1 0 0 0 0 0 0 200 2 0 0 0 0 0 0
            rmnet_data0:	300 3 0 0 0 0 0 0 400 4 0 0 0 0 0 0
        """.trimIndent()

        assertEquals(TrafficSnapshot(rxBytes = 400L, txBytes = 600L), ProcNetDevReader.parse(content))
    }

    @Test
    fun `parse excludes loopback and tun interfaces`() {
        val content = """
              lo: 10 0 0 0 0 0 0 0 20 0 0 0 0 0 0 0
            tun0: 30 0 0 0 0 0 0 0 40 0 0 0 0 0 0 0
            tun99: 50 0 0 0 0 0 0 0 60 0 0 0 0 0 0 0
             eth0: 70 0 0 0 0 0 0 0 80 0 0 0 0 0 0 0
        """.trimIndent()

        assertEquals(TrafficSnapshot(rxBytes = 70L, txBytes = 80L), ProcNetDevReader.parse(content))
    }

    @Test
    fun `parse skips malformed and negative counters`() {
        val content = """
            missing separator
             eth0: 1 2 3
             eth1: invalid 0 0 0 0 0 0 0 10 0 0 0 0 0 0 0
             eth2: -1 0 0 0 0 0 0 0 20 0 0 0 0 0 0 0
             eth3: 9223372036854775808 0 0 0 0 0 0 0 30 0 0 0 0 0 0 0
             eth4: 40 0 0 0 0 0 0 0 50 0 0 0 0 0 0 0
        """.trimIndent()

        assertEquals(TrafficSnapshot(rxBytes = 40L, txBytes = 50L), ProcNetDevReader.parse(content))
        assertNull(ProcNetDevReader.parse("eth0: invalid"))
    }

    @Test
    fun `parse rejects aggregate overflow`() {
        val content = """
            eth0: ${Long.MAX_VALUE} 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0
            eth1: 1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0
        """.trimIndent()

        assertNull(ProcNetDevReader.parse(content))
    }

    @Test
    fun `parse retains legitimate one-way snapshots`() {
        val receiveOnly = "eth0: 42 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
        val transmitOnly = "eth0: 0 0 0 0 0 0 0 0 24 0 0 0 0 0 0 0"

        assertEquals(TrafficSnapshot(rxBytes = 42L, txBytes = 0L), ProcNetDevReader.parse(receiveOnly))
        assertEquals(TrafficSnapshot(rxBytes = 0L, txBytes = 24L), ProcNetDevReader.parse(transmitOnly))
    }

    @Test
    fun `selection replaces receive-only primary with one fallback snapshot`() {
        val fallback = TrafficSnapshot(rxBytes = 12L, txBytes = 34L)

        val selected = selectTrafficSnapshot(TrafficSnapshot(rxBytes = 56L, txBytes = 0L)) {
            fallback
        }

        assertEquals(fallback, selected)
    }

    @Test
    fun `selection replaces transmit-only primary with one fallback snapshot`() {
        val fallback = TrafficSnapshot(rxBytes = 12L, txBytes = 34L)

        val selected = selectTrafficSnapshot(TrafficSnapshot(rxBytes = 0L, txBytes = 56L)) {
            fallback
        }

        assertEquals(fallback, selected)
    }

    @Test
    fun `selection preserves receive-only primary when fallback is empty`() {
        val primary = TrafficSnapshot(rxBytes = 56L, txBytes = 0L)

        val selected = selectTrafficSnapshot(primary) {
            TrafficSnapshot(rxBytes = 0L, txBytes = 0L)
        }

        assertEquals(primary, selected)
    }

    @Test
    fun `selection preserves transmit-only primary when fallback is empty`() {
        val primary = TrafficSnapshot(rxBytes = 0L, txBytes = 56L)

        val selected = selectTrafficSnapshot(primary) {
            TrafficSnapshot(rxBytes = 0L, txBytes = 0L)
        }

        assertEquals(primary, selected)
    }
}

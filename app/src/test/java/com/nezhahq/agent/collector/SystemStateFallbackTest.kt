package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the privileged-source fallbacks in `SystemStateCollector`.
 *
 * They all exist because `RootShell.execute` returns an empty string instead of throwing when the
 * shell session cannot be established. Fallbacks written inside `catch` were therefore unreachable
 * and the dashboard silently showed zeroes, so each decision is now an explicit emptiness check —
 * these tests pin that down.
 */
class SystemStateFallbackTest {

    // ── ss 连接数 ────────────────────────────────────────────────────────────

    @Test
    fun `an unavailable shell asks for the proc fallback`() {
        assertNull(parseSsConnectionCounts(tcpRaw = "", udpRaw = ""))
        assertNull(parseSsConnectionCounts(tcpRaw = null, udpRaw = null))
    }

    /** A missing `ss` binary still leaves `wc -l` printing "0" at the end of the pipeline. */
    @Test
    fun `two zero counts ask for the proc fallback`() {
        assertNull(parseSsConnectionCounts(tcpRaw = "0", udpRaw = "0"))
    }

    @Test
    fun `unparsable output ask for the proc fallback`() {
        assertNull(parseSsConnectionCounts(tcpRaw = "ss: not found", udpRaw = "ss: not found"))
    }

    /** `ss` proving itself on one direction means the other direction's zero is a real count. */
    @Test
    fun `a working ss keeps a genuine zero for the other direction`() {
        assertEquals(Pair(12L, 0L), parseSsConnectionCounts(tcpRaw = "12", udpRaw = "0"))
        assertEquals(Pair(0L, 7L), parseSsConnectionCounts(tcpRaw = "garbage", udpRaw = "7"))
    }

    @Test
    fun `counts are trimmed and never negative`() {
        assertEquals(Pair(31L, 4L), parseSsConnectionCounts(tcpRaw = "  31 ", udpRaw = "4"))
        assertEquals(Pair(9L, 0L), parseSsConnectionCounts(tcpRaw = "9", udpRaw = "-3"))
    }

    // ── /proc/loadavg ───────────────────────────────────────────────────────

    @Test
    fun `a blank privileged read falls through to the direct read`() {
        var directReads = 0

        val loadAverage = firstParsableLoadAverage(
            { "" },
            {
                directReads++
                "0.34 0.28 0.22 1/345 12345"
            }
        )

        assertEquals(Triple(0.34, 0.28, 0.22), loadAverage)
        assertEquals(1, directReads)
    }

    @Test
    fun `a usable privileged read never pays for the direct read`() {
        var directReads = 0

        val loadAverage = firstParsableLoadAverage(
            { "1.50 1.25 1.00 2/512 6789" },
            {
                directReads++
                "0.34 0.28 0.22 1/345 12345"
            }
        )

        assertEquals(Triple(1.50, 1.25, 1.00), loadAverage)
        assertEquals(0, directReads)
    }

    @Test
    fun `every source failing yields no load average at all`() {
        assertNull(firstParsableLoadAverage({ null }, { "" }))
    }

    @Test
    fun `truncated or non numeric lines do not parse`() {
        assertNull(parseLoadAvgLine("0.34 0.28"))
        assertNull(parseLoadAvgLine("cat: /proc/loadavg: Permission denied"))
        assertNull(parseLoadAvgLine("   "))
        assertNull(parseLoadAvgLine(null))
    }

    /** The trailing running/total-process and last-pid fields are not load values. */
    @Test
    fun `only the first three fields of loadavg are read`() {
        assertEquals(Triple(0.34, 0.28, 0.22), parseLoadAvgLine("0.34 0.28 0.22 1/345 12345"))
    }
}

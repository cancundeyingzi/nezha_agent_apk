package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoIpCollectorTest {

    @Test
    fun parseTraceBodyReadsIpAndCountryCode() {
        val trace = GeoIpCollector.parseTraceBody(
            """
            fl=114f114
            h=blog.cloudflare.com
            ip=1.2.3.4
            loc=US
            uag=Mozilla/5.0
            """.trimIndent()
        )

        assertEquals("1.2.3.4", trace.ip)
        assertEquals("US", trace.countryCode)
    }

    @Test
    fun parseTraceBodyReadsIpv6() {
        val trace = GeoIpCollector.parseTraceBody(
            """
            ip=2001:db8::1
            loc=JP
            """.trimIndent()
        )

        assertEquals("2001:db8::1", trace.ip)
        assertEquals("JP", trace.countryCode)
    }

    @Test
    fun parseTraceBodyAcceptsPlainIpResponse() {
        val trace = GeoIpCollector.parseTraceBody("8.8.8.8\n")

        assertEquals("8.8.8.8", trace.ip)
        assertNull(trace.countryCode)
    }

    @Test
    fun parseTraceBodyReturnsNullIpWhenMissing() {
        val trace = GeoIpCollector.parseTraceBody(
            """
            fl=114f114
            loc=CN
            """.trimIndent()
        )

        assertNull(trace.ip)
        assertEquals("CN", trace.countryCode)
    }

    @Test
    fun validateIpForFamilyAcceptsOnlyRequestedFamily() {
        assertEquals(
            "1.2.3.4",
            GeoIpCollector.validateIpForFamily("1.2.3.4", GeoIpCollector.IpFamily.V4)
        )
        assertNull(
            GeoIpCollector.validateIpForFamily("2001:db8::1", GeoIpCollector.IpFamily.V4)
        )

        assertEquals(
            "2001:db8:0:0:0:0:0:1",
            GeoIpCollector.validateIpForFamily("2001:db8::1", GeoIpCollector.IpFamily.V6)
        )
        assertNull(
            GeoIpCollector.validateIpForFamily("1.2.3.4", GeoIpCollector.IpFamily.V6)
        )
    }

    @Test
    fun validateIpForFamilyRejectsInvalidAddresses() {
        assertNull(
            GeoIpCollector.validateIpForFamily("999.2.3.4", GeoIpCollector.IpFamily.V4)
        )
        assertNull(
            GeoIpCollector.validateIpForFamily("not-an-ip", GeoIpCollector.IpFamily.V6)
        )
    }
}

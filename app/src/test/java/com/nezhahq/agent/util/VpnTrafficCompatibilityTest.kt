package com.nezhahq.agent.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTrafficCompatibilityTest {
    @Test
    fun `sdk 30 supports requested compatibility mode`() {
        assertTrue(VpnTrafficCompatibility.isSupported(sdkInt = 30))
        assertTrue(VpnTrafficCompatibility.normalize(enabled = true, sdkInt = 30))
    }

    @Test
    fun `sdk 31 rejects requested compatibility mode`() {
        assertFalse(VpnTrafficCompatibility.isSupported(sdkInt = 31))
        assertFalse(VpnTrafficCompatibility.normalize(enabled = true, sdkInt = 31))
    }

    @Test
    fun `disabled setting remains disabled on supported sdk`() {
        assertFalse(VpnTrafficCompatibility.normalize(enabled = false, sdkInt = 30))
    }
}

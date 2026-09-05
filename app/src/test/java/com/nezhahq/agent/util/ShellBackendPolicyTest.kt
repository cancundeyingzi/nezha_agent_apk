package com.nezhahq.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellBackendPolicyTest {
    @Test fun authorizedShizukuIsSelectedWithoutSuFallback() {
        val policy = ShellBackendPolicy()
        assertEquals(ShellBackend.SHIZUKU, policy.select(true, true, true))
        assertNull(policy.select(false, false, true))
        assertNull(policy.select(false, true, true))
        assertEquals(ShellBackend.SHIZUKU, policy.select(true, true, true))
    }

    @Test fun pendingShizukuPermissionDoesNotRequestRoot() {
        val policy = ShellBackendPolicy()
        assertNull(policy.select(false, true, true))
        assertNull(policy.select(false, false, true))
        assertEquals(ShellBackend.SHIZUKU, policy.select(true, true, true))
    }

    @Test fun noBackendDoesNotLaunchAnything() {
        assertNull(ShellBackendPolicy().select(false, false, false))
    }

    @Test fun rejectedRootIsNotRetriedUntilModeIsReset() {
        val policy = ShellBackendPolicy()
        assertEquals(ShellBackend.SU, policy.select(false, false, true))
        policy.rejectSu()
        assertNull(policy.select(false, false, true))
        policy.reset()
        assertEquals(ShellBackend.SU, policy.select(false, false, true))
    }

    @Test fun existingRootSelectionDoesNotSwitchWhenShizukuStarts() {
        val policy = ShellBackendPolicy()
        assertEquals(ShellBackend.SU, policy.select(false, false, true))
        assertEquals(ShellBackend.SU, policy.select(true, true, true))
        policy.reset()
        assertEquals(ShellBackend.SHIZUKU, policy.select(true, true, true))
    }
}

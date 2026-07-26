package com.nezhahq.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellPolicyTest {
    @Test
    fun deniedAccessRevokesResourcesOncePerAllowedTransition() {
        var allowed = true
        var revocations = 0
        val gate = PrivilegedAccessGate(
            isAllowed = { allowed },
            onRevoked = { revocations += 1 }
        )

        assertTrue(gate.authorize())
        allowed = false

        assertFalse(gate.authorize())
        assertFalse(gate.authorize())
        assertEquals(1, revocations)
    }

    @Test
    fun explicitRevokeAlwaysClosesResources() {
        var revocations = 0
        val gate = PrivilegedAccessGate(
            isAllowed = { true },
            onRevoked = { revocations += 1 }
        )

        gate.revoke()

        assertEquals(1, revocations)
    }
}

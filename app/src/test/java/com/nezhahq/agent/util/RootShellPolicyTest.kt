package com.nezhahq.agent.util

import com.nezhahq.agent.core.security.PrivilegedAccessController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellPolicyTest {
    @Test
    fun disableRevokesResourcesOncePerEnabledTransition() {
        var revocations = 0
        val controller = PrivilegedAccessController(
            cleanup = { revocations += 1 }
        )

        assertFalse(controller.isEnabled())
        controller.enable()
        assertTrue(controller.isEnabled())

        controller.disableAndRevoke()
        controller.disableAndRevoke()

        assertFalse(controller.isEnabled())
        assertEquals(1, revocations)

        controller.enable()
        controller.disableAndRevoke()
        assertEquals(2, revocations)
    }

    @Test
    fun disableFailsClosedBeforeSynchronousCleanup() {
        lateinit var controller: PrivilegedAccessController
        var cleanupCompleted = false
        controller = PrivilegedAccessController(
            cleanup = {
                assertFalse(controller.isEnabled())
                cleanupCompleted = true
            }
        )
        controller.enable()

        controller.disableAndRevoke()

        assertTrue(cleanupCompleted)
    }
}

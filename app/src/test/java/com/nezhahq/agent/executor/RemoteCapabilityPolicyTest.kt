package com.nezhahq.agent.executor

import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteCapabilityPolicyTest {
    @Test
    fun taskTypesMapToExpectedCapabilities() {
        assertEquals(
            RemoteCapability.SHELL,
            RemoteCapabilityPolicy.requiredCapability(TaskTypes.COMMAND)
        )
        assertEquals(
            RemoteCapability.SHELL,
            RemoteCapabilityPolicy.requiredCapability(TaskTypes.TERMINAL)
        )
        assertEquals(
            RemoteCapability.FILE_MANAGER,
            RemoteCapabilityPolicy.requiredCapability(TaskTypes.FILE_MANAGER)
        )
        assertEquals(
            RemoteCapability.NAT,
            RemoteCapabilityPolicy.requiredCapability(TaskTypes.NAT)
        )
        assertNull(RemoteCapabilityPolicy.requiredCapability(TaskTypes.HTTP_GET))
    }

    @Test
    fun capabilitiesAreDeniedByDefaultAndGrantedIndependently() {
        val defaults = RemoteCapabilities()
        assertNotNull(RemoteCapabilityPolicy.denialReason(TaskTypes.COMMAND, defaults))
        assertNotNull(RemoteCapabilityPolicy.denialReason(TaskTypes.FILE_MANAGER, defaults))
        assertNotNull(RemoteCapabilityPolicy.denialReason(TaskTypes.NAT, defaults))

        val fileManagerOnly = RemoteCapabilities(fileManagerEnabled = true)
        assertNull(
            RemoteCapabilityPolicy.denialReason(TaskTypes.FILE_MANAGER, fileManagerOnly)
        )
        assertNotNull(RemoteCapabilityPolicy.denialReason(TaskTypes.COMMAND, fileManagerOnly))
        assertNotNull(RemoteCapabilityPolicy.denialReason(TaskTypes.NAT, fileManagerOnly))
    }
}

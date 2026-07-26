package com.nezhahq.agent.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentServiceRunningStateTest {
    @Test
    fun reloadGuardAllowsOnlyLiveServiceInstance() {
        AgentServiceRunningState.onDestroyed()
        assertFalse(AgentServiceRunningState.canRequestReload())

        AgentServiceRunningState.onCreated()
        assertTrue(AgentServiceRunningState.canRequestReload())

        AgentServiceRunningState.onDestroyed()
        assertFalse(AgentServiceRunningState.canRequestReload())
    }
}

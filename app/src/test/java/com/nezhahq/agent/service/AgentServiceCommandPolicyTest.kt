package com.nezhahq.agent.service

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentServiceCommandPolicyTest {
    @Test
    fun explicitAndRepeatedStartCommandsReload() {
        val first = AgentServiceCommandPolicy.decide(AgentService.ACTION_START_OR_RELOAD)
        val repeated = AgentServiceCommandPolicy.decide(AgentService.ACTION_START_OR_RELOAD)

        assertEquals(AgentServiceCommand.START_OR_RELOAD, first.command)
        assertEquals(AgentServiceCommand.START_OR_RELOAD, repeated.command)
        assertEquals(Service.START_STICKY, first.startResult)
        assertEquals(Service.START_STICKY, repeated.startResult)
    }

    @Test
    fun nullIntentActionRecoversLatestPersistedConfig() {
        val decision = AgentServiceCommandPolicy.decide(null)

        assertEquals(AgentServiceCommand.RECOVER_LATEST, decision.command)
        assertEquals(Service.START_STICKY, decision.startResult)
    }

    @Test
    fun unknownActionDoesNotStartBusinessWork() {
        val decision = AgentServiceCommandPolicy.decide("unexpected")

        assertEquals(AgentServiceCommand.IGNORE, decision.command)
        assertEquals(Service.START_STICKY, decision.startResult)
    }
}

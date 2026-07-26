package com.nezhahq.agent.service

import com.nezhahq.agent.core.task.TaskTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSessionRegistryTest {

    @Test
    fun `duplicate StreamID is rejected until its lease is released`() {
        val registry = registry()
        val first = registry.tryAcquire(TaskTypes.TERMINAL, "same")
        assertTrue(first is StreamSessionAdmission.Accepted)

        val duplicate = registry.tryAcquire(TaskTypes.NAT, "same")
        assertTrue(duplicate is StreamSessionAdmission.Rejected)

        (first as StreamSessionAdmission.Accepted).lease.close()
        val retried = registry.tryAcquire(TaskTypes.NAT, "same")
        assertTrue(retried is StreamSessionAdmission.Accepted)
    }

    @Test
    fun `per-type limit is released exactly once`() {
        val registry = registry()
        val accepted = registry.tryAcquire(TaskTypes.TERMINAL, "terminal-1")
            as StreamSessionAdmission.Accepted

        assertTrue(
            registry.tryAcquire(TaskTypes.TERMINAL, "terminal-2")
                is StreamSessionAdmission.Rejected
        )
        assertEquals(1, registry.activeCount(TaskTypes.TERMINAL))

        accepted.lease.close()
        accepted.lease.close()

        assertEquals(0, registry.activeCount())
        assertTrue(
            registry.tryAcquire(TaskTypes.TERMINAL, "terminal-2")
                is StreamSessionAdmission.Accepted
        )
    }

    @Test
    fun `total limit applies across task types`() {
        val registry = registry()
        assertTrue(
            registry.tryAcquire(TaskTypes.TERMINAL, "terminal")
                is StreamSessionAdmission.Accepted
        )
        assertTrue(
            registry.tryAcquire(TaskTypes.FILE_MANAGER, "files")
                is StreamSessionAdmission.Accepted
        )

        val rejected = registry.tryAcquire(TaskTypes.NAT, "nat")
        assertTrue(rejected is StreamSessionAdmission.Rejected)
        assertTrue(
            (rejected as StreamSessionAdmission.Rejected).reason.contains("2 active")
        )
    }

    private fun registry(): StreamSessionRegistry =
        StreamSessionRegistry(
            maxTotal = 2,
            maxByTaskType = mapOf(
                TaskTypes.TERMINAL to 1,
                TaskTypes.NAT to 2,
                TaskTypes.FILE_MANAGER to 1
            )
        )
}

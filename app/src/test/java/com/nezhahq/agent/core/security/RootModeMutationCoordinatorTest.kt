package com.nezhahq.agent.core.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootModeMutationCoordinatorTest {
    @Test
    fun concurrentEnableAndDisableCannotDivergePersistedAndRuntimeState() {
        val persisted = AtomicBoolean(false)
        val runtime = AtomicBoolean(false)
        val enableApplyStarted = CountDownLatch(1)
        val releaseEnableApply = CountDownLatch(1)
        val disableAttemptStarted = CountDownLatch(1)
        val disablePersisted = CountDownLatch(1)
        val coordinator = RootModeMutationCoordinator { enabled ->
            if (enabled) {
                enableApplyStarted.countDown()
                assertTrue(releaseEnableApply.await(1, TimeUnit.SECONDS))
            }
            runtime.set(enabled)
        }

        val enableThread = Thread {
            coordinator.persistAndApply(enabled = true) {
                persisted.set(true)
                true
            }
        }
        val disableThread = Thread {
            disableAttemptStarted.countDown()
            coordinator.persistAndApply(enabled = false) {
                persisted.set(false)
                disablePersisted.countDown()
                true
            }
        }

        enableThread.start()
        assertTrue(enableApplyStarted.await(1, TimeUnit.SECONDS))
        disableThread.start()
        assertTrue(disableAttemptStarted.await(1, TimeUnit.SECONDS))

        // Disable persistence cannot overtake the blocked runtime enable application.
        assertFalse(disablePersisted.await(100, TimeUnit.MILLISECONDS))
        releaseEnableApply.countDown()
        assertTrue(disablePersisted.await(1, TimeUnit.SECONDS))
        enableThread.join(1_000)
        disableThread.join(1_000)

        assertFalse(enableThread.isAlive)
        assertFalse(disableThread.isAlive)
        assertFalse(persisted.get())
        assertFalse(runtime.get())
    }

    @Test
    fun persistenceFailureDisablesRuntimeAuthorization() {
        val runtime = AtomicBoolean(true)
        val coordinator = RootModeMutationCoordinator(runtime::set)

        assertFalse(coordinator.persistAndApply(enabled = true) { false })
        assertFalse(runtime.get())
    }
}

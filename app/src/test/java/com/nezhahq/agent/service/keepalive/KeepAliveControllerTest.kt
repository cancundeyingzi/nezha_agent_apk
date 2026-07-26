package com.nezhahq.agent.service.keepalive

import com.nezhahq.agent.core.model.KeepAliveSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class KeepAliveControllerTest {
    @Test
    fun reconfigureAndCloseAreRepeatableAndSettingsTransitionIndependently() = runBlocking {
        val audio = RecordingResource()
        val overlay = RecordingResource()
        val wakeLock = RecordingResource()
        val vpn = RecordingResource()
        val controller = KeepAliveController(audio, overlay, wakeLock, vpn)
        val initial = KeepAliveSettings(audio = true, overlay = false, vpn = false)

        controller.reconfigure(initial)
        controller.reconfigure(initial)

        assertEquals(1, audio.enableCount)
        assertEquals(0, overlay.enableCount)
        assertEquals(1, wakeLock.enableCount)
        assertEquals(0, vpn.enableCount)

        controller.reconfigure(KeepAliveSettings(audio = false, overlay = true, vpn = true))

        assertEquals(1, audio.disableCount)
        assertEquals(1, overlay.enableCount)
        assertEquals(1, vpn.enableCount)
        assertTrue(wakeLock.enabled)

        controller.close()
        controller.close()

        assertEquals(1, audio.closeCount)
        assertEquals(1, overlay.closeCount)
        assertEquals(1, wakeLock.closeCount)
        assertEquals(1, vpn.closeCount)

        controller.reconfigure(initial)

        assertTrue(audio.enabled)
        assertFalse(overlay.enabled)
        assertTrue(wakeLock.enabled)
        assertFalse(vpn.enabled)
    }

    @Test
    fun audioRapidRestartNeverOverlapsAndReleasesEachOutput() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val activeCount = AtomicInteger()
        val maxActiveCount = AtomicInteger()
        val created = LinkedBlockingQueue<BlockingAudioOutput>()
        val audio = AudioKeepAlive(scope, dispatcher) {
            BlockingAudioOutput(activeCount, maxActiveCount).also(created::add)
        }

        try {
            audio.setEnabled(true)
            val first = created.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(first.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.setEnabled(false)
            assertTrue(first.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.setEnabled(true)
            val second = created.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(second.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.close()

            assertTrue(second.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, maxActiveCount.get())
            assertEquals(0, activeCount.get())
        } finally {
            audio.close()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun audioReleasesAfterStartupFailureAndCanStartAgain() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val created = LinkedBlockingQueue<FailingAudioOutput>()
        val audio = AudioKeepAlive(scope, dispatcher) {
            FailingAudioOutput().also(created::add)
        }

        try {
            audio.setEnabled(true)
            val first = created.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(first.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.setEnabled(true)
            val second = created.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(second.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, first.releaseCount.get())
            assertEquals(1, second.releaseCount.get())
        } finally {
            audio.close()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun audioReleasesAfterWriteFailure() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val output = WriteFailingAudioOutput()
        val audio = AudioKeepAlive(scope, dispatcher) { output }

        try {
            audio.setEnabled(true)

            assertTrue(output.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, output.releaseCount.get())
        } finally {
            audio.close()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun overlayFailuresAlwaysClearOwnedState() = runBlocking {
        val addFailingHost = FakeOverlayHost(failAdd = true)
        val addFailingOverlay = OverlayKeepAlive(addFailingHost)

        addFailingOverlay.setEnabled(true)
        addFailingOverlay.setEnabled(true)

        assertEquals(2, addFailingHost.addCount)

        val removeFailingHost = FakeOverlayHost(failRemove = true)
        val removeFailingOverlay = OverlayKeepAlive(removeFailingHost)
        removeFailingOverlay.setEnabled(true)
        removeFailingOverlay.close()
        removeFailingOverlay.setEnabled(true)

        assertEquals(2, removeFailingHost.addCount)
        assertEquals(1, removeFailingHost.removeCount)
    }

    @Test
    fun wakeLockUsesBoundedRenewableLeaseWithoutDuplicateSchedulers() = runBlocking {
        val lease = FakeWakeLockLease()
        val scheduler = FakeRenewalScheduler()
        val wakeLock = WakeLockKeepAlive(lease, scheduler)

        wakeLock.setEnabled(true)
        wakeLock.setEnabled(true)

        assertEquals(listOf(WakeLockKeepAlive.LEASE_MILLIS), lease.acquireTimeouts)
        assertEquals(WakeLockKeepAlive.RENEWAL_MILLIS, scheduler.initialDelayMillis)
        assertEquals(WakeLockKeepAlive.RENEWAL_MILLIS, scheduler.intervalMillis)
        assertEquals(1, scheduler.scheduleCount)

        scheduler.fire()
        assertEquals(
            listOf(WakeLockKeepAlive.LEASE_MILLIS, WakeLockKeepAlive.LEASE_MILLIS),
            lease.acquireTimeouts
        )

        wakeLock.close()
        wakeLock.close()
        scheduler.fire()

        assertEquals(1, scheduler.cancelCount)
        assertEquals(1, lease.releaseCount)
        assertEquals(2, lease.acquireTimeouts.size)

        wakeLock.setEnabled(true)
        assertEquals(2, scheduler.scheduleCount)
        assertEquals(3, lease.acquireTimeouts.size)
    }

    @Test
    fun vpnTransitionsAreIdempotentAndCloseStopsExistingService() = runBlocking {
        val host = FakeVpnHost()
        val vpn = PlaceholderVpnKeepAlive(host)

        vpn.setEnabled(true)
        vpn.setEnabled(true)
        vpn.setEnabled(false)
        vpn.setEnabled(false)
        vpn.close()
        vpn.close()

        assertEquals(1, host.startCount)
        assertEquals(2, host.stopCount)

        vpn.setEnabled(true)
        assertEquals(2, host.startCount)
    }

    private class RecordingResource : KeepAliveResource {
        var enabled = false
        var closed = false
        var enableCount = 0
        var disableCount = 0
        var closeCount = 0

        override suspend fun setEnabled(enabled: Boolean) {
            closed = false
            if (this.enabled == enabled) return
            this.enabled = enabled
            if (enabled) enableCount++ else disableCount++
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            enabled = false
            closeCount++
        }
    }

    private class BlockingAudioOutput(
        private val activeCount: AtomicInteger,
        private val maxActiveCount: AtomicInteger
    ) : AudioOutput {
        override val bufferSizeSamples = 8
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        private val writeGate = CountDownLatch(1)

        override fun start() {
            val active = activeCount.incrementAndGet()
            maxActiveCount.updateAndGet { current -> maxOf(current, active) }
            started.countDown()
        }

        override fun write(samples: ShortArray): Int {
            writeGate.await()
            return samples.size
        }

        override fun interrupt() {
            writeGate.countDown()
        }

        override fun release() {
            activeCount.decrementAndGet()
            released.countDown()
        }
    }

    private class FailingAudioOutput : AudioOutput {
        override val bufferSizeSamples = 8
        val released = CountDownLatch(1)
        val releaseCount = AtomicInteger()

        override fun start() = throw IllegalStateException("start failed")

        override fun write(samples: ShortArray): Int = samples.size

        override fun interrupt() = Unit

        override fun release() {
            releaseCount.incrementAndGet()
            released.countDown()
        }
    }

    private class WriteFailingAudioOutput : AudioOutput {
        override val bufferSizeSamples = 8
        val released = CountDownLatch(1)
        val releaseCount = AtomicInteger()

        override fun start() = Unit

        override fun write(samples: ShortArray): Int = -1

        override fun interrupt() = Unit

        override fun release() {
            releaseCount.incrementAndGet()
            released.countDown()
        }
    }

    private class FakeOverlayHost(
        private val failAdd: Boolean = false,
        private val failRemove: Boolean = false
    ) : OverlayHost {
        var addCount = 0
        var removeCount = 0

        override fun hasPermission(): Boolean = true

        override fun add(): OverlayHandle {
            addCount++
            if (failAdd) throw IllegalStateException("add failed")
            return OverlayHandle {
                removeCount++
                if (failRemove) throw IllegalStateException("remove failed")
            }
        }
    }

    private class FakeWakeLockLease : WakeLockLease {
        val acquireTimeouts = mutableListOf<Long>()
        var releaseCount = 0

        override fun acquire(timeoutMillis: Long) {
            acquireTimeouts += timeoutMillis
        }

        override fun release() {
            releaseCount++
        }
    }

    private class FakeRenewalScheduler : RenewalScheduler {
        var initialDelayMillis = 0L
        var intervalMillis = 0L
        var scheduleCount = 0
        var cancelCount = 0
        private var action: (() -> Unit)? = null

        override fun schedule(
            initialDelayMillis: Long,
            intervalMillis: Long,
            action: () -> Unit
        ): RenewalHandle {
            this.initialDelayMillis = initialDelayMillis
            this.intervalMillis = intervalMillis
            this.action = action
            scheduleCount++
            return RenewalHandle { cancelCount++ }
        }

        fun fire() {
            action?.invoke()
        }
    }

    private class FakeVpnHost : PlaceholderVpnHost {
        var startCount = 0
        var stopCount = 0

        override fun startIfPermitted() {
            startCount++
        }

        override fun stop() {
            stopCount++
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L
    }
}

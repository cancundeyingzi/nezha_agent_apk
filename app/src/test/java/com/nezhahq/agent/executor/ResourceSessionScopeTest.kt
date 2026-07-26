package com.nezhahq.agent.executor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceSessionScopeTest {

    @Test
    fun `cancellation closes resource before waiting for blocked child`() = runBlocking {
        val resource = BlockingResource()
        val session = launch {
            resourceSessionScope(resource::close) {
                launch(Dispatchers.IO) {
                    resource.readUntilClosed()
                }
                awaitCancellation()
            }
        }

        yield()
        assertTrue(resource.readStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        session.cancel()
        withTimeout(TEST_TIMEOUT_MS) { session.join() }

        assertTrue(resource.closed.await(0, TimeUnit.MILLISECONDS))
        assertEquals(1, resource.closeCount.get())
    }

    private class BlockingResource {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger()

        fun readUntilClosed() {
            readStarted.countDown()
            check(closed.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "Resource was not closed while the child was blocked."
            }
        }

        fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}

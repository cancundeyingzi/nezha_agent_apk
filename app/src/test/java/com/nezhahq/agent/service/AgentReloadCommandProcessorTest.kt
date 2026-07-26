package com.nezhahq.agent.service

import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReloadCommandProcessorTest {
    @Test
    fun burstBeforeProcessingLoadsAndAppliesOnlyLatestSnapshot() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        scope.launch { gate.await() }
        val repository = FakeConfigRepository(config("old"))
        val applied = mutableListOf<String>()
        val controller = AgentRuntimeController(
            AgentRuntimeFactory { snapshot ->
                RecordingRuntime(onStart = {
                    applied += snapshot.server
                    started.countDown()
                })
            }
        )
        val processor = processor(scope, dispatcher, repository, controller)

        processor.request()
        repository.result = Result.success(config("latest"))
        processor.request()
        processor.request()
        gate.countDown()

        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("latest"), applied)
        assertEquals(1, repository.loads.get())
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun requestDuringReloadRunsAfterwardWithLatestStorageAndNeverOverlaps() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val applied = mutableListOf<String>()
        val repository = FakeConfigRepository(config("one"))
        val controller = AgentRuntimeController(
            AgentRuntimeFactory { snapshot ->
                RecordingRuntime(
                    onStart = {
                        val count = active.incrementAndGet()
                        maxActive.updateAndGet { previous -> maxOf(previous, count) }
                        applied += snapshot.server
                        if (snapshot.server == "one") {
                            firstStarted.countDown()
                            releaseFirst.await()
                        } else {
                            secondStarted.countDown()
                        }
                    },
                    onStop = { active.decrementAndGet() }
                )
            }
        )
        val processor = processor(scope, dispatcher, repository, controller)

        processor.request()
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        repository.result = Result.success(config("two"))
        processor.request()
        releaseFirst.countDown()

        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("one", "two"), applied)
        assertEquals(1, maxActive.get())
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun storageFailureLeavesExistingRuntimeUntouched() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val started = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var stops = 0
        val repository = FakeConfigRepository(config("valid"))
        val controller = AgentRuntimeController(
            AgentRuntimeFactory {
                RecordingRuntime(
                    onStart = { started.countDown() },
                    onStop = { stops++ }
                )
            }
        )
        val processor = processor(
            scope,
            dispatcher,
            repository,
            controller,
            onFailureWithRuntime = { failed.countDown() }
        )
        processor.request()
        assertTrue(started.await(2, TimeUnit.SECONDS))

        repository.result = Result.failure(IllegalStateException("storage unavailable"))
        processor.request()

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertTrue(controller.isRunning)
        assertEquals(0, stops)
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun firstStorageFailureSignalsStopPath() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val failed = CountDownLatch(1)
        val repository = FakeConfigRepository(config("unused")).apply {
            result = Result.failure(IllegalArgumentException("invalid"))
        }
        val controller = AgentRuntimeController(
            AgentRuntimeFactory { RecordingRuntime() }
        )
        val processor = processor(
            scope,
            dispatcher,
            repository,
            controller,
            onFailureWithoutRuntime = { failed.countDown() }
        )

        processor.request()

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertFalse(controller.isRunning)
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    private fun processor(
        scope: CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        repository: ConfigRepository,
        controller: AgentRuntimeController,
        onFailureWithoutRuntime: (Throwable) -> Unit = {},
        onFailureWithRuntime: (Throwable) -> Unit = {}
    ) = AgentReloadCommandProcessor(
        scope = scope,
        repository = repository,
        controller = controller,
        ioDispatcher = dispatcher,
        onFailureWithoutRuntime = onFailureWithoutRuntime,
        onFailureWithRuntime = onFailureWithRuntime
    )

    private class RecordingRuntime(
        private val onStart: suspend () -> Unit = {},
        private val onStop: suspend () -> Unit = {}
    ) : AgentRuntimeHandle {
        override suspend fun start() = onStart()
        override suspend fun stop() = onStop()
        override suspend fun updateCapabilities(capabilities: RemoteCapabilities) = Unit
    }

    private class FakeConfigRepository(initial: AgentConfig) : ConfigRepository {
        @Volatile
        var result: Result<AgentConfig> = Result.success(initial)
        val loads = AtomicInteger()

        override fun loadAgentConfig(): Result<AgentConfig> {
            loads.incrementAndGet()
            return result
        }

        override fun saveConnection(
            server: String,
            port: Int,
            secret: String,
            uuid: String,
            useTls: Boolean
        ): Result<Unit> = unsupported()

        override fun saveToolSettings(settings: KeepAliveSettings): Result<Unit> = unsupported()
        override fun saveRootMode(enabled: Boolean): Result<Unit> = unsupported()
        override fun saveRemoteCapability(
            capability: RemoteCapability,
            enabled: Boolean
        ): Result<Unit> = unsupported()

        private fun unsupported(): Result<Unit> =
            Result.failure(UnsupportedOperationException())
    }
}

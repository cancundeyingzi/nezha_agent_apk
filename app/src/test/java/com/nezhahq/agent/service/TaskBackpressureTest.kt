package com.nezhahq.agent.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import proto.Nezha.Task
import proto.Nezha.TaskResult

class TaskBackpressureTest {

    @Test
    fun `full result channel backpressures the caller without losing rejection`() = runBlocking {
        val shortTasks = Channel<Task>(1)
        val results = Channel<TaskResult>(1)
        shortTasks.send(task(id = 1))
        results.send(TaskResult.getDefaultInstance())

        var admission: ShortTaskAdmission? = null
        val enqueueJob = launch {
            admission = enqueueShortTaskWithBackpressure(
                task(id = 2),
                shortTasks,
                results
            )
        }

        yield()
        assertFalse(enqueueJob.isCompleted)

        results.receive()
        withTimeout(TEST_TIMEOUT_MS) { enqueueJob.join() }

        assertEquals(ShortTaskAdmission.REJECTED_QUEUE_FULL, admission)
        val rejection = results.receive()
        assertEquals(2L, rejection.id)
        assertFalse(rejection.successful)
        assertTrue(rejection.data.contains("queue is full"))

        shortTasks.close()
        results.close()
        Unit
    }

    @Test
    fun `available short task queue accepts without producing a result`() = runBlocking {
        val shortTasks = Channel<Task>(1)
        val results = Channel<TaskResult>(1)
        val submitted = task(id = 7)

        val admission = enqueueShortTaskWithBackpressure(
            submitted,
            shortTasks,
            results
        )

        assertEquals(ShortTaskAdmission.ENQUEUED, admission)
        assertEquals(submitted, shortTasks.receive())
        assertTrue(results.tryReceive().isFailure)

        shortTasks.close()
        results.close()
        Unit
    }

    private fun task(id: Long): Task =
        Task.newBuilder()
            .setId(id)
            .setType(1L)
            .build()

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}

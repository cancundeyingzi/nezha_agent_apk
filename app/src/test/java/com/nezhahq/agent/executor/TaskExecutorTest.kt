package com.nezhahq.agent.executor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import proto.Nezha.Task

class TaskExecutorTest {
    @Test
    fun keepaliveReturnsSuccessfulEmptyResult() = runBlocking {
        val task = Task.newBuilder()
            .setId(7L)
            .setType(TaskTypes.KEEPALIVE)
            .build()

        val result = TaskExecutor.executeTask(task, isCommandEnabled = false)

        assertEquals(7L, result.id)
        assertEquals(TaskTypes.KEEPALIVE, result.type)
        assertTrue(result.successful)
        assertEquals("", result.data)
    }

    @Test
    fun unsupportedMcpTypesReturnExplicitFailure() = runBlocking {
        val task = Task.newBuilder()
            .setId(16L)
            .setType(TaskTypes.FS_LIST)
            .build()

        val result = TaskExecutor.executeTask(task, isCommandEnabled = false)

        assertEquals(TaskTypes.FS_LIST, result.type)
        assertFalse(result.successful)
        assertEquals(TaskTypes.unsupportedMessage(TaskTypes.FS_LIST), result.data)
    }

    @Test
    fun streamTaskConstantsPreserveExistingRouting() {
        assertTrue(TaskTypes.TERMINAL in TaskTypes.STREAM_TASKS)
        assertTrue(TaskTypes.NAT in TaskTypes.STREAM_TASKS)
        assertTrue(TaskTypes.FILE_MANAGER in TaskTypes.STREAM_TASKS)
        assertFalse(TaskTypes.KEEPALIVE in TaskTypes.STREAM_TASKS)
    }
}

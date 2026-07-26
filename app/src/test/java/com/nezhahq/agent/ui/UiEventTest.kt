package com.nezhahq.agent.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [UiEvents], the channel the view model actually emits through.
 *
 * The previous version of this test built its own `Channel` and asserted that kotlinx buffers —
 * true regardless of anything in this codebase, and blind to the view model switching to a
 * rendezvous or conflated channel. Driving the production type is what makes these assertions able
 * to fail.
 */
class UiEventTest {

    @Test
    fun messagesEmittedBeforeAnyCollectorArrivesAreStillDelivered() = runBlocking {
        val events = UiEvents()

        events.send("配置已解析完成")
        events.sendLong("配置存储不可用")

        assertEquals(
            listOf(
                UiEvent.Message("配置已解析完成"),
                UiEvent.LongMessage("配置存储不可用")
            ),
            events.flow.take(2).toList()
        )
    }

    /**
     * Emitting returns even when the buffer is full and nothing is collecting.
     *
     * Reporting how an operation ended must never be able to stall the operation itself, so a full
     * buffer drops the message instead of suspending the producer. Everything already queued is
     * still delivered.
     */
    @Test
    fun emittingNeverBlocksTheCallerEvenWhenTheBufferIsFull() = runBlocking {
        val events = UiEvents(capacity = 1)

        events.send("first")
        events.send("second")

        assertEquals(listOf(UiEvent.Message("first")), events.flow.take(1).toList())
    }

    @Test
    fun theTwoKindsAreDistinguishableSoThePresenterCanPickADuration() {
        val short: UiEvent = UiEvent.Message("saved")
        val long: UiEvent = UiEvent.LongMessage("storage unavailable, reset it")

        assertEquals("saved", (short as UiEvent.Message).text)
        assertEquals("storage unavailable, reset it", (long as UiEvent.LongMessage).text)
    }
}

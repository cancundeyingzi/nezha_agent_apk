package com.nezhahq.agent.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the reason the view model emits events instead of calling Toast: a buffered channel keeps
 * a message that was produced while nothing was collecting, so work started off-screen still
 * reports its outcome once the UI returns.
 */
class UiEventTest {
    @Test
    fun messagesEmittedBeforeAnyCollectorArrivesAreStillDelivered() = runBlocking {
        val events = Channel<UiEvent>(Channel.BUFFERED)

        events.trySend(UiEvent.Message("配置已解析完成"))
        events.trySend(UiEvent.LongMessage("配置存储不可用"))
        events.close()

        assertEquals(
            listOf(
                UiEvent.Message("配置已解析完成"),
                UiEvent.LongMessage("配置存储不可用")
            ),
            events.receiveAsFlow().toList()
        )
    }

    @Test
    fun theTwoKindsAreDistinguishableSoThePresenterCanPickADuration() {
        val short: UiEvent = UiEvent.Message("saved")
        val long: UiEvent = UiEvent.LongMessage("storage unavailable, reset it")

        assertEquals("saved", (short as UiEvent.Message).text)
        assertEquals("storage unavailable, reset it", (long as UiEvent.LongMessage).text)
    }
}

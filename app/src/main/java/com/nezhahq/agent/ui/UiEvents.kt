package com.nezhahq.agent.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The view model's outbound one-off messages.
 *
 * Buffered on purpose. Work started from a screen keeps running after that screen goes away — a
 * configuration write, a reset, an instant sample — and the user still needs to learn how it ended.
 * A rendezvous channel would drop those messages because nothing is collecting at the moment they
 * are produced, and a conflated one would keep only the last. Neither is acceptable when the
 * message being dropped is a storage failure.
 *
 * Emitting never suspends and never fails the caller: reporting an outcome must not be able to
 * break the operation that produced it.
 */
class UiEvents(capacity: Int = Channel.BUFFERED) {
    private val channel = Channel<UiEvent>(capacity)

    /** Collected by the presenter; see [UiEvent]. */
    val flow: Flow<UiEvent> = channel.receiveAsFlow()

    fun send(text: String) {
        channel.trySend(UiEvent.Message(text))
    }

    fun sendLong(text: String) {
        channel.trySend(UiEvent.LongMessage(text))
    }
}

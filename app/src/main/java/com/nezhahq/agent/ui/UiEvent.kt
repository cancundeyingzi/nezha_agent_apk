package com.nezhahq.agent.ui

/**
 * A one-off message from the view model to whatever is presenting it.
 *
 * The view model used to call `Toast.makeText` directly, which tied it to an Android runtime and
 * contradicted its own contract that the UI only observes state. Emitting events instead keeps that
 * decision — toast, snackbar, or nothing at all — with the composable, and lets the view model be
 * tested on a plain JVM.
 */
sealed interface UiEvent {
    /** Short-lived confirmation or rejection, e.g. "配置已解析完成". */
    data class Message(val text: String) : UiEvent

    /** Needs the user to read it, e.g. a storage failure explaining what to do next. */
    data class LongMessage(val text: String) : UiEvent
}

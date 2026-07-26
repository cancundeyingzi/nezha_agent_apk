package com.nezhahq.agent.core.model

/**
 * A connection configuration as the user is editing it, which may still be incomplete.
 *
 * [AgentConfig] is the validated counterpart the runtime consumes; it rejects blank fields, so it
 * cannot represent a half-filled form. Keeping the two apart is what lets the UI and the service
 * read through the same repository instead of the UI reaching for storage directly.
 */
data class ConnectionDraft(
    val server: String,
    val port: Int,
    val secret: String,
    val uuid: String,
    val useTls: Boolean,
    val rootMode: Boolean
)

/** Persisted auto-start choice together with whether the first-run prompt was already answered. */
data class AutoStartState(
    val enabled: Boolean,
    val promptShown: Boolean
)

/** Simulator settings as stored; the UI edits them as text and validates before saving. */
data class SimulatorDraft(
    val server: String,
    val port: Int,
    val secret: String,
    val useTls: Boolean,
    val threadCount: Int
)

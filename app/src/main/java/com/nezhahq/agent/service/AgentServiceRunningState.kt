package com.nezhahq.agent.service

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local service marker. False negatives are safe; it never probes deprecated OS APIs.
 */
internal object AgentServiceRunningState {
    private val running = AtomicBoolean()

    fun onCreated() {
        running.set(true)
    }

    fun onDestroyed() {
        running.set(false)
    }

    fun canRequestReload(): Boolean = running.get()
}

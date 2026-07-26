package com.nezhahq.agent.core.security

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local authorization boundary for privileged operations.
 *
 * Disabling fails closed before cleanup starts. Cleanup is invoked synchronously and exactly once
 * for each enabled-to-disabled transition.
 */
class PrivilegedAccessController(
    private val cleanup: () -> Unit
) {
    private val enabled = AtomicBoolean(false)

    fun enable() {
        enabled.set(true)
    }

    fun disableAndRevoke() {
        if (enabled.getAndSet(false)) {
            cleanup()
        }
    }

    fun isEnabled(): Boolean = enabled.get()
}

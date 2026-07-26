package com.nezhahq.agent.core.security

/**
 * Serializes persisted root-mode changes with their process-local authorization update.
 *
 * The lock is intentionally owned by one process-wide instance. Callers must not create a
 * coordinator per repository because independently ordered writes can re-enable stale state.
 */
internal class RootModeMutationCoordinator(
    private val applyAuthorization: (Boolean) -> Unit
) {
    private val lock = Any()

    fun persistAndApply(
        enabled: Boolean,
        persist: () -> Boolean
    ): Boolean = synchronized(lock) {
        val persisted = try {
            persist()
        } catch (error: Throwable) {
            applyFailClosed()
            throw error
        }

        if (persisted) {
            applyAuthorizationFailClosed(enabled)
        } else {
            applyFailClosed()
        }
        persisted
    }

    fun loadAndApply(load: () -> Boolean): Boolean = synchronized(lock) {
        val enabled = try {
            load()
        } catch (error: Throwable) {
            applyFailClosed()
            throw error
        }
        applyAuthorizationFailClosed(enabled)
        enabled
    }

    private fun applyAuthorizationFailClosed(enabled: Boolean) {
        try {
            applyAuthorization(enabled)
        } catch (error: Throwable) {
            if (enabled) {
                runCatching { applyAuthorization(false) }
            }
            throw error
        }
    }

    private fun applyFailClosed() {
        applyAuthorization(false)
    }
}

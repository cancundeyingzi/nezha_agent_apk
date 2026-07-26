package com.nezhahq.agent.executor

/**
 * Central authorization policy for remote task capabilities.
 *
 * The existing remote-command setting intentionally covers both silent commands and interactive
 * terminal shells. Other task capabilities keep their current behavior.
 */
internal object TaskAuthorizationPolicy {
    private val remoteShellTasks = setOf(
        TaskTypes.COMMAND,
        TaskTypes.TERMINAL
    )

    fun denialReason(taskType: Long, remoteShellEnabled: Boolean): String? {
        if (remoteShellEnabled || taskType !in remoteShellTasks) return null
        return "Remote shell execution is disabled on Android Agent."
    }
}

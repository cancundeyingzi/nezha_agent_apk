package com.nezhahq.agent.executor

import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability

/** Pure mapping from wire task types to locally granted remote capabilities. */
internal object RemoteCapabilityPolicy {
    fun requiredCapability(taskType: Long): RemoteCapability? = when (taskType) {
        TaskTypes.COMMAND, TaskTypes.TERMINAL -> RemoteCapability.SHELL
        TaskTypes.FILE_MANAGER -> RemoteCapability.FILE_MANAGER
        TaskTypes.NAT -> RemoteCapability.NAT
        else -> null
    }

    fun denialReason(
        taskType: Long,
        capabilities: RemoteCapabilities
    ): String? {
        val required = requiredCapability(taskType) ?: return null
        if (capabilities.isEnabled(required)) return null
        return when (required) {
            RemoteCapability.SHELL ->
                "Remote shell execution is disabled on Android Agent."
            RemoteCapability.FILE_MANAGER ->
                "Remote file manager is disabled on Android Agent."
            RemoteCapability.NAT ->
                "Remote NAT is disabled on Android Agent."
        }
    }
}

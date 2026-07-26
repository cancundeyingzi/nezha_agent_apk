package com.nezhahq.agent.core.config

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapability

interface ConfigRepository {
    fun loadAgentConfig(): Result<AgentConfig>

    fun saveConnection(
        server: String,
        port: Int,
        secret: String,
        uuid: String,
        useTls: Boolean
    ): Result<Unit>

    fun saveToolSettings(settings: KeepAliveSettings): Result<Unit>

    fun saveRootMode(enabled: Boolean): Result<Unit>

    fun saveRemoteCapability(
        capability: RemoteCapability,
        enabled: Boolean
    ): Result<Unit>
}

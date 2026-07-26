package com.nezhahq.agent

import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.AutoStartState
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatorDraft

/**
 * Defaults for every [ConfigRepository] member so a test overrides only what it exercises.
 *
 * Writes fail by default: a test that unexpectedly persists something should notice.
 */
open class StubConfigRepository : ConfigRepository {
    override fun storageStatus(): StorageStatus = StorageStatus.READY

    override fun resetStorage(): Boolean = true

    override fun loadAgentConfig(): Result<AgentConfig> = unsupported()

    override fun hasCompleteConnection(): Boolean = false

    override fun loadConnectionDraft(): ConnectionDraft = ConnectionDraft(
        server = "",
        port = 5555,
        secret = "",
        uuid = "",
        useTls = true,
        rootMode = false
    )

    override fun loadToolSettings(): KeepAliveSettings = KeepAliveSettings()

    override fun loadRemoteCapabilities(): RemoteCapabilities = RemoteCapabilities()

    override fun loadAutoStartState(): AutoStartState =
        AutoStartState(enabled = false, promptShown = false)

    override fun loadSimulatorDraft(): SimulatorDraft = SimulatorDraft(
        server = "",
        port = 5555,
        secret = "",
        useTls = true,
        threadCount = 5
    )

    override fun saveConnection(draft: ConnectionDraft): Result<Unit> = unsupported()

    override fun saveToolSettings(settings: KeepAliveSettings): Result<Unit> = unsupported()

    override fun saveRemoteCapability(
        capability: RemoteCapability,
        enabled: Boolean
    ): Result<Unit> = unsupported()

    override fun saveAutoStart(enabled: Boolean): Result<Unit> = unsupported()

    override fun saveAutoStartPromptResult(enabled: Boolean): Result<Unit> = unsupported()

    override fun saveSimulator(draft: SimulatorDraft): Result<Unit> = unsupported()

    private fun <T> unsupported(): Result<T> = Result.failure(UnsupportedOperationException())
}

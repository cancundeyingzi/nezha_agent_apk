package com.nezhahq.agent.util

import android.content.Context
import android.os.Build
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.AutoStartState
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatorDraft
import com.nezhahq.agent.core.platform.VpnTrafficCompatibility
import java.io.IOException

/**
 * [ConfigRepository] backed by [ConfigStore].
 *
 * Every read opens the store first, so a store that failed to initialize reports its defaults
 * rather than throwing at each call site.
 */
class SharedPreferencesConfigRepository(context: Context) : ConfigRepository {
    private val context = context.applicationContext

    override fun storageStatus(): StorageStatus = ConfigStore.initialize(context)

    override fun resetStorage(): Boolean = ConfigStore.resetConfigurationStorage(context)

    override fun loadAgentConfig(): Result<AgentConfig> = runCatching {
        check(storageStatus().isUsable) { "Configuration storage is unavailable." }
        val draft = loadConnectionDraft()
        AgentConfig(
            server = draft.server,
            port = draft.port,
            secret = draft.secret,
            uuid = draft.uuid,
            useTls = draft.useTls,
            rootMode = draft.rootMode,
            keepAlive = loadToolSettings(),
            remoteCapabilities = loadRemoteCapabilities()
        )
    }

    override fun hasCompleteConnection(): Boolean = ConfigStore.hasValidConfig(context)

    override fun loadConnectionDraft(): ConnectionDraft = ConnectionDraft(
        server = ConfigStore.getServer(context),
        port = ConfigStore.getPort(context),
        secret = ConfigStore.getSecret(context),
        uuid = ConfigStore.getUuid(context),
        useTls = ConfigStore.getUseTls(context),
        rootMode = ConfigStore.getRootMode(context)
    )

    /** The VPN fallback is normalized on read so an unsupported device never reports it enabled. */
    override fun loadToolSettings(): KeepAliveSettings = KeepAliveSettings(
        audio = ConfigStore.getEnableKeepAliveAudio(context),
        overlay = ConfigStore.getEnableFloatWindow(context),
        vpn = VpnTrafficCompatibility.normalize(
            enabled = ConfigStore.getEnableVpnTraffic(context),
            sdkInt = Build.VERSION.SDK_INT
        )
    )

    override fun loadRemoteCapabilities(): RemoteCapabilities = RemoteCapabilities(
        shellEnabled = ConfigStore.getEnableRemoteCommand(context),
        fileManagerEnabled = ConfigStore.getEnableRemoteFileManager(context),
        natEnabled = ConfigStore.getEnableRemoteNat(context)
    )

    override fun loadAutoStartState(): AutoStartState = AutoStartState(
        enabled = ConfigStore.getEnableAutoStart(context),
        promptShown = ConfigStore.getHasShownAutoStartPrompt(context)
    )

    override fun loadSimulatorDraft(): SimulatorDraft = SimulatorDraft(
        server = ConfigStore.getSimulatorServer(context),
        port = ConfigStore.getSimulatorPort(context),
        secret = ConfigStore.getSimulatorSecret(context),
        useTls = ConfigStore.getSimulatorUseTls(context),
        threadCount = ConfigStore.getSimulatorThreadCount(context)
    )

    override fun saveConnection(draft: ConnectionDraft): Result<Unit> = runCatching {
        requirePersisted(
            ConfigStore.saveConfig(
                context = context,
                server = draft.server,
                port = draft.port,
                secret = draft.secret,
                useTLS = draft.useTls,
                uuid = draft.uuid,
                rootMode = draft.rootMode
            ),
            "connection configuration"
        )
    }

    override fun saveToolSettings(settings: KeepAliveSettings): Result<Unit> = runCatching {
        requirePersisted(
            ConfigStore.saveToolSettings(
                context,
                settings.audio,
                settings.overlay,
                settings.vpn
            ),
            "tool settings"
        )
    }

    override fun saveRemoteCapability(
        capability: RemoteCapability,
        enabled: Boolean
    ): Result<Unit> = runCatching {
        val persisted = when (capability) {
            RemoteCapability.SHELL -> ConfigStore.setEnableRemoteCommand(context, enabled)
            RemoteCapability.FILE_MANAGER ->
                ConfigStore.setEnableRemoteFileManager(context, enabled)
            RemoteCapability.NAT -> ConfigStore.setEnableRemoteNat(context, enabled)
        }
        requirePersisted(persisted, "${capability.name.lowercase()} capability")
    }

    override fun saveAutoStart(enabled: Boolean): Result<Unit> = runCatching {
        requirePersisted(ConfigStore.setEnableAutoStart(context, enabled), "auto start")
    }

    override fun saveAutoStartPromptResult(enabled: Boolean): Result<Unit> = runCatching {
        requirePersisted(
            ConfigStore.saveAutoStartPromptResult(context, enabled),
            "auto start prompt result"
        )
    }

    override fun saveSimulator(draft: SimulatorDraft): Result<Unit> = runCatching {
        requirePersisted(
            ConfigStore.saveSimulatorConfig(
                context = context,
                server = draft.server,
                port = draft.port,
                secret = draft.secret,
                useTls = draft.useTls,
                threadCount = draft.threadCount
            ),
            "simulator configuration"
        )
    }

    private fun requirePersisted(persisted: Boolean, description: String) {
        if (!persisted) {
            throw IOException("Unable to persist $description.")
        }
    }
}

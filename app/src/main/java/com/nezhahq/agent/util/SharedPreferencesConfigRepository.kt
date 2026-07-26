package com.nezhahq.agent.util

import android.content.Context
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import java.io.IOException

class SharedPreferencesConfigRepository(context: Context) : ConfigRepository {
    private val context = context.applicationContext

    override fun loadAgentConfig(): Result<AgentConfig> = runCatching {
        check(ConfigStore.initialize(context) != StorageStatus.UNAVAILABLE) {
            "Configuration storage is unavailable."
        }
        AgentConfig(
            server = ConfigStore.getServer(context),
            port = ConfigStore.getPort(context),
            secret = ConfigStore.getSecret(context),
            uuid = ConfigStore.getUuid(context),
            useTls = ConfigStore.getUseTls(context),
            rootMode = ConfigStore.getRootMode(context),
            keepAlive = KeepAliveSettings(
                audio = ConfigStore.getEnableKeepAliveAudio(context),
                overlay = ConfigStore.getEnableFloatWindow(context),
                vpn = ConfigStore.getEnableVpnTraffic(context)
            ),
            remoteCapabilities = RemoteCapabilities(
                shellEnabled = ConfigStore.getEnableRemoteCommand(context),
                fileManagerEnabled = ConfigStore.getEnableRemoteFileManager(context),
                natEnabled = ConfigStore.getEnableRemoteNat(context)
            )
        )
    }

    override fun saveConnection(
        server: String,
        port: Int,
        secret: String,
        uuid: String,
        useTls: Boolean
    ): Result<Unit> = runCatching {
        validateConnection(server, port, secret, uuid, useTls)
        requirePersisted(
            ConfigStore.saveConnectionConfig(context, server, port, secret, uuid, useTls),
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

    override fun saveRootMode(enabled: Boolean): Result<Unit> = runCatching {
        requirePersisted(ConfigStore.setRootMode(context, enabled), "root mode")
        RootShell.configureAuthorization(enabled)
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

    private fun validateConnection(
        server: String,
        port: Int,
        secret: String,
        uuid: String,
        useTls: Boolean
    ) {
        AgentConfig(
            server = server,
            port = port,
            secret = secret,
            uuid = uuid,
            useTls = useTls,
            rootMode = false
        )
    }

    private fun requirePersisted(persisted: Boolean, description: String) {
        if (!persisted) {
            throw IOException("Unable to persist $description.")
        }
    }
}

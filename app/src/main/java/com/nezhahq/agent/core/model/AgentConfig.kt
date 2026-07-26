package com.nezhahq.agent.core.model

data class AgentConfig(
    val server: String,
    val port: Int,
    val secret: String,
    val uuid: String,
    val useTls: Boolean,
    val rootMode: Boolean,
    val keepAlive: KeepAliveSettings = KeepAliveSettings(),
    val remoteCapabilities: RemoteCapabilities = RemoteCapabilities()
) {
    init {
        require(server.isNotBlank()) { "Server must not be blank." }
        require(port in 1..65535) { "Port must be between 1 and 65535." }
        require(secret.isNotBlank()) { "Secret must not be blank." }
        require(uuid.isNotBlank()) { "UUID must not be blank." }
    }
}

data class KeepAliveSettings(
    val audio: Boolean = false,
    val overlay: Boolean = false,
    val vpn: Boolean = false
)

data class RemoteCapabilities(
    val shellEnabled: Boolean = false,
    val fileManagerEnabled: Boolean = false,
    val natEnabled: Boolean = false
) {
    fun isEnabled(capability: RemoteCapability): Boolean = when (capability) {
        RemoteCapability.SHELL -> shellEnabled
        RemoteCapability.FILE_MANAGER -> fileManagerEnabled
        RemoteCapability.NAT -> natEnabled
    }
}

enum class RemoteCapability {
    SHELL,
    FILE_MANAGER,
    NAT
}

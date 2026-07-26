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

    companion object {
        /**
         * Validates what the user typed, returning a message to show or null when it is usable.
         *
         * The UI cannot construct an [AgentConfig] to find out whether a half-filled form is valid,
         * so both sides share this instead of each keeping its own rules. UUID is absent on purpose:
         * a blank one is filled in for the user rather than rejected.
         */
        fun validationError(server: String, portText: String, secret: String): String? {
            val port = portText.trim().toIntOrNull()
            return when {
                server.isBlank() -> "请先填写服务端 IP 或域名"
                port == null || port !in 1..65535 -> "端口号无效，请填写 1-65535 之间的数字"
                secret.isBlank() -> "请先填写客户端密钥 (Secret)"
                else -> null
            }
        }
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

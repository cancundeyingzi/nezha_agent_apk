package com.nezhahq.agent.core.model

data class SimulatedDeviceConfig(
    val server: String,
    val port: Int,
    val secret: String,
    val useTls: Boolean,
    val threadCount: Int = DEFAULT_THREAD_COUNT
) {
    companion object {
        const val DEFAULT_THREAD_COUNT = 5
        const val MAX_THREAD_COUNT = 50

        fun validationError(
            server: String,
            portText: String,
            secret: String,
            threadCountText: String = DEFAULT_THREAD_COUNT.toString()
        ): String? {
            val port = portText.trim().toIntOrNull()
            val threadCount = threadCountText.trim().toIntOrNull()
            return when {
                server.isBlank() -> "请先填写模拟器服务端 IP 或域名"
                port == null || port !in 1..65535 ->
                    "模拟器端口号无效，请填写 1-65535 之间的数字"
                secret.isBlank() -> "请先填写模拟器客户端密钥 (Secret)"
                threadCount == null || threadCount !in 1..MAX_THREAD_COUNT ->
                    "模拟器并发线程数无效，请填写 1-$MAX_THREAD_COUNT 之间的数字"
                else -> null
            }
        }
    }
}

package com.nezhahq.agent.grpc

import android.content.Context
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * gRPC 连接状态枚举，用于驱动 UI 实时反馈。
 *
 * 状态转换流程：
 * IDLE → CONNECTING → CONNECTED ⇄ RECONNECTING
 *                   ↘ AUTH_FAILED
 */
enum class GrpcConnectionState {
    /** 初始/已停止状态 */
    IDLE,
    /** 正在建立 gRPC 连接 */
    CONNECTING,
    /** 双向流已建立，数据正常上报中 */
    CONNECTED,
    /** 连接断开后正在自动重连 */
    RECONNECTING,
    /** 认证失败（密钥或 UUID 不匹配） */
    AUTH_FAILED
}

object GrpcManager {

    private var channel: ManagedChannel? = null
    var stub: NezhaServiceCoroutineStub? = null
        private set

    // ── gRPC 连接状态 StateFlow，供 ViewModel 收集并驱动 UI 变更 ──
    private val _connectionState = MutableStateFlow(GrpcConnectionState.IDLE)
    val connectionState: StateFlow<GrpcConnectionState> = _connectionState.asStateFlow()

    /** 更新连接状态（由 AgentService 在关键节点调用）。 */
    fun updateState(state: GrpcConnectionState) {
        _connectionState.value = state
    }

    fun initialize(context: Context) {
        val server = ConfigStore.getServer(context)
        val port = ConfigStore.getPort(context)
        val secret = ConfigStore.getSecret(context)
        val uuid = ConfigStore.getUuid(context)
        val useTls = ConfigStore.getUseTls(context)

        if (server.isEmpty() || secret.isEmpty() || uuid.isEmpty()) return

        shutdown() // Close previous if any

        var builder = OkHttpChannelBuilder.forAddress(server, port)
        if (useTls) {
            builder.useTransportSecurity()
            // Optional: If you need to trust all self-signed certs (not recommended for prod, but often needed for self-hosted dash)
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            builder.usePlaintext()
        }

        Logger.i("Connecting to $server:$port via ChannelBuilder...")
        channel = builder
            .keepAliveTime(10, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .intercept(AuthInterceptor(secret, uuid))
            .build()

        stub = NezhaServiceCoroutineStub(channel!!)
    }

    fun shutdown() {
        Logger.i("Closing Grpc connection stub.")
        channel?.shutdownNow()
        channel = null
        stub = null
        _connectionState.value = GrpcConnectionState.IDLE
    }
}

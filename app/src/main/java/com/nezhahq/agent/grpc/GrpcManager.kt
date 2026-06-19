package com.nezhahq.agent.grpc

import android.content.Context
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * gRPC 连接状态，用于驱动 UI 和前台服务通知。
 * 传输模式由配置显式决定；TLS 失败不会自动切换到明文。
 */
enum class GrpcConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_FAILED,
    PLAINTEXT_CONNECTING,
    PLAINTEXT_CONNECTED,
    PLAINTEXT_RECONNECTING;

    val isPlaintext: Boolean
        get() = this == PLAINTEXT_CONNECTING
                || this == PLAINTEXT_CONNECTED
                || this == PLAINTEXT_RECONNECTING
}

enum class GrpcTransportMode {
    TLS,
    PLAINTEXT
}

/**
 * gRPC 连接管理器。
 *
 * 安全不变量：只有用户配置 use_tls=false 时才允许明文连接。
 * TLS 握手或证书失败必须继续按 TLS 重试，禁止携带鉴权 metadata 自动降级。
 */
object GrpcManager {
    private val lifecycleLock = Any()

    @Volatile
    private var channel: ManagedChannel? = null

    @Volatile
    var stub: NezhaServiceCoroutineStub? = null
        private set

    @Volatile
    private var currentTransportMode = GrpcTransportMode.TLS

    private val _connectionState = MutableStateFlow(GrpcConnectionState.IDLE)
    val connectionState: StateFlow<GrpcConnectionState> = _connectionState.asStateFlow()

    fun updateState(state: GrpcConnectionState) {
        _connectionState.value = state
    }

    fun resolveTransportMode(useTls: Boolean): GrpcTransportMode =
        if (useTls) GrpcTransportMode.TLS else GrpcTransportMode.PLAINTEXT

    fun currentTransportMode(): GrpcTransportMode = currentTransportMode

    fun isPlaintextModeActive(): Boolean = currentTransportMode == GrpcTransportMode.PLAINTEXT

    fun recordConnectionSuccess() {
        if (currentTransportMode == GrpcTransportMode.PLAINTEXT) {
            Logger.i("Grpc: 明文模式连接成功")
        }
    }

    fun initialize(context: Context) {
        val server = ConfigStore.getServer(context)
        val port = ConfigStore.getPort(context)
        val secret = ConfigStore.getSecret(context)
        val uuid = ConfigStore.getUuid(context)
        val transportMode = resolveTransportMode(ConfigStore.getUseTls(context))

        synchronized(lifecycleLock) {
            if (server.isEmpty() || secret.isEmpty() || uuid.isEmpty()) {
                Logger.e("Grpc: 配置不完整，跳过通道初始化")
                shutdownLocked(preserveConnectionState = false)
                return
            }

            currentTransportMode = transportMode
            shutdownLocked(preserveConnectionState = true)

            val builder = OkHttpChannelBuilder.forAddress(server, port)
            when (transportMode) {
                GrpcTransportMode.TLS -> {
                    if (!configureTls(builder)) {
                        stub = null
                        return
                    }
                    Logger.i("Grpc: 使用 TLS 加密连接 $server:$port")
                }
                GrpcTransportMode.PLAINTEXT -> {
                    builder.usePlaintext()
                    Logger.i("Grpc: 使用显式明文连接 $server:$port")
                }
            }

            val newChannel = builder
                .keepAliveTime(10, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .intercept(AuthInterceptor(secret, uuid))
                .build()

            channel = newChannel
            stub = NezhaServiceCoroutineStub(newChannel)
        }
    }

    private fun configureTls(builder: OkHttpChannelBuilder): Boolean {
        return try {
            builder.useTransportSecurity()
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory)
            true
        } catch (e: Exception) {
            Logger.e("Grpc: TLS 初始化失败，将保持 TLS 模式重试，禁止自动明文降级", e)
            false
        }
    }

    fun shutdown(preserveConnectionState: Boolean = false) {
        synchronized(lifecycleLock) {
            shutdownLocked(preserveConnectionState)
        }
    }

    private fun shutdownLocked(preserveConnectionState: Boolean = false) {
        Logger.i("Grpc: Closing connection stub.")
        channel?.shutdownNow()
        channel = null
        stub = null
        if (!preserveConnectionState) {
            _connectionState.value = GrpcConnectionState.IDLE
        }
    }
}

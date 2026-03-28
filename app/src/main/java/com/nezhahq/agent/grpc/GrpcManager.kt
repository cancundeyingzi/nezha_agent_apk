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
 *                   ↘ TLS_FALLBACK_CONNECTING → TLS_FALLBACK_CONNECTED ⇄ TLS_FALLBACK_RECONNECTING
 *
 * ## 状态正交设计
 * 传输安全模式（TLS/明文）和连接阶段（连接中/已连接/重连中）是两个独立维度。
 * TLS 降级模式下使用 TLS_FALLBACK_CONNECTING / TLS_FALLBACK_CONNECTED /
 * TLS_FALLBACK_RECONNECTING 反映实际连接阶段，让 UI 能区分降级后的连接状况。
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
    AUTH_FAILED,
    /** TLS 降级为明文 + 正在连接 */
    TLS_FALLBACK_CONNECTING,
    /** TLS 降级为明文 + 已连接 */
    TLS_FALLBACK_CONNECTED,
    /** TLS 降级为明文 + 正在重连 */
    TLS_FALLBACK_RECONNECTING;

    /** 是否处于 TLS 降级模式（任一降级子状态） */
    val isTlsFallback: Boolean
        get() = this == TLS_FALLBACK_CONNECTING
                || this == TLS_FALLBACK_CONNECTED
                || this == TLS_FALLBACK_RECONNECTING
}

/**
 * gRPC 连接管理器（单例）。
 *
 * ## TLS 自动降级机制
 * 默认始终使用 TLS 加密连接。当 TLS 连接连续失败达到 [MAX_TLS_FAILURES] 次后，
 * 自动降级为明文（plaintext）连接以保证可用性。每次 Service 重启或
 * 调用 [resetTlsFallback] 时重置计数器，重新尝试 TLS。
 *
 * ## 防闪退保护
 * TLS 初始化过程中的 SSL Context 创建、证书信任管理器等环节
 * 均有完整的异常捕获，任何异常仅记录日志并降级为明文，不会导致应用闪退。
 */
object GrpcManager {

    /** TLS 最大连续失败次数，超过后降级为明文 */
    private const val MAX_TLS_FAILURES = 3

    private var channel: ManagedChannel? = null
    var stub: NezhaServiceCoroutineStub? = null
        private set

    // ── TLS 降级状态管理 ──────────────────────────────────────────────────
    /** TLS 连续失败计数（线程安全：仅在 AgentService 单协程循环中访问） */
    @Volatile
    private var tlsFailCount = 0

    /** 当前是否已降级为明文传输 */
    @Volatile
    private var tlsFallbackActive = false

    // ── gRPC 连接状态 StateFlow，供 ViewModel 收集并驱动 UI 变更 ──
    private val _connectionState = MutableStateFlow(GrpcConnectionState.IDLE)
    val connectionState: StateFlow<GrpcConnectionState> = _connectionState.asStateFlow()

    /** 更新连接状态（由 AgentService 在关键节点调用）。 */
    fun updateState(state: GrpcConnectionState) {
        _connectionState.value = state
    }

    private fun activateTlsFallback() {
        if (!tlsFallbackActive) {
            Logger.i("GrpcManager: ⚠️ TLS 已降级为明文连接")
        }
        tlsFallbackActive = true
        // 激活降级时设置为降级连接中状态（后续由 AgentService 更新为实际阶段）
        updateState(GrpcConnectionState.TLS_FALLBACK_CONNECTING)
    }

    /**
     * 记录一次 TLS 连接失败。
     *
     * @return true 表示已达到降级阈值，下次 initialize 将使用明文
     */
    fun recordTlsFailure(): Boolean {
        tlsFailCount++
        Logger.i("GrpcManager: TLS 连接失败计数 $tlsFailCount/$MAX_TLS_FAILURES")
        if (tlsFailCount >= MAX_TLS_FAILURES) {
            Logger.i("GrpcManager: ⚠️ TLS 连续失败 $MAX_TLS_FAILURES 次，将降级为明文连接")
            activateTlsFallback()
            return true
        }
        return false
    }

    /**
     * 记录一次连接成功，重置 TLS 失败计数。
     */
    fun recordConnectionSuccess() {
        if (tlsFailCount > 0) {
            Logger.i("GrpcManager: 连接成功，重置 TLS 失败计数（之前 $tlsFailCount 次）")
        }
        tlsFailCount = 0
        // 注意：不重置 tlsFallbackActive，若当前是明文连接且成功，保持明文直到 Service 重启
        if (tlsFallbackActive) {
            updateState(GrpcConnectionState.TLS_FALLBACK_CONNECTED)
        }
    }

    /**
     * 重置 TLS 降级状态（通常在 Service 重启时调用）。
     * 重置后下次 initialize 将重新尝试 TLS 连接。
     */
    fun resetTlsFallback() {
        tlsFailCount = 0
        tlsFallbackActive = false
        Logger.i("GrpcManager: TLS 降级状态已重置，下次将重新尝试 TLS 连接")
    }

    /** 查询当前是否处于 TLS 降级（明文）模式 */
    fun isTlsFallbackActive(): Boolean = tlsFallbackActive

    /**
     * 初始化 gRPC 通道和 Stub。
     *
     * 根据 TLS 降级状态自动选择加密或明文传输：
     * - 默认/正常状态：使用 TLS 加密（信任所有证书，兼容自签名部署）
     * - 降级状态（tlsFallbackActive = true）：使用明文传输
     *
     * TLS 初始化异常（SSLContext 创建失败等）会计入失败计数，
     * 累计达到 [MAX_TLS_FAILURES] 次后才降级为明文；
     * 未达阈值时直接 return（stub 保持 null），不会抛出异常，
     * 由 AgentService 重试循环中的 stub==null 检查触发下次重试。
     */
    fun initialize(context: Context) {
        val server = ConfigStore.getServer(context)
        val port = ConfigStore.getPort(context)
        val secret = ConfigStore.getSecret(context)
        val uuid = ConfigStore.getUuid(context)

        if (server.isEmpty() || secret.isEmpty() || uuid.isEmpty()) return

        shutdown(preserveConnectionState = tlsFallbackActive) // 关闭之前的连接（不重置降级状态）

        val builder = OkHttpChannelBuilder.forAddress(server, port)

        // ── 根据降级状态决定传输安全策略 ──────────────────────────────────
        if (!tlsFallbackActive) {
            // 尝试 TLS 加密连接
            try {
                builder.useTransportSecurity()
                // 信任所有证书（兼容自签名 Dashboard 部署，非生产推荐）
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory)
                Logger.i("GrpcManager: 使用 TLS 加密连接 $server:$port")
            } catch (e: Exception) {
                // TLS 本地初始化失败（如 SSLContext 创建异常）
                // [P2 修复] 走统一的失败计数逻辑，不直接降级
                Logger.e("GrpcManager: TLS 初始化异常（计入失败计数）", e)
                val shouldFallback = recordTlsFailure()

                if (shouldFallback) {
                    // 已达到 MAX_TLS_FAILURES 阈值，降级为明文
                    // 需要重新创建 builder，因为之前已调用 useTransportSecurity
                    val fallbackBuilder = OkHttpChannelBuilder.forAddress(server, port)
                    fallbackBuilder.usePlaintext()
                    Logger.i("GrpcManager: 降级使用明文连接 $server:$port")
                    channel = fallbackBuilder
                        .keepAliveTime(10, TimeUnit.SECONDS)
                        .keepAliveTimeout(5, TimeUnit.SECONDS)
                        .keepAliveWithoutCalls(true)
                        .intercept(AuthInterceptor(secret, uuid))
                        .build()
                    stub = NezhaServiceCoroutineStub(channel!!)
                    return
                }

                // 未达到降级阈值：stub 保持 null，不构建通道。
                // AgentService 重试循环中的 stub==null 检查会在 5 秒后
                // 自然触发重新 initialize()，无需抛出异常。
                return
            }
        } else {
            // 已降级为明文
            builder.usePlaintext()
            Logger.i("GrpcManager: 当前处于 TLS 降级模式，使用明文连接 $server:$port")
        }

        channel = builder
            .keepAliveTime(10, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .intercept(AuthInterceptor(secret, uuid))
            .build()

        stub = NezhaServiceCoroutineStub(channel!!)
    }

    /**
     * 关闭 gRPC 连接。
     * 注意：仅关闭通道，不重置 TLS 降级状态（降级状态由 [resetTlsFallback] 管理）。
     */
    fun shutdown(preserveConnectionState: Boolean = false) {
        Logger.i("Closing Grpc connection stub.")
        channel?.shutdownNow()
        channel = null
        stub = null
        if (!preserveConnectionState) {
            _connectionState.value = GrpcConnectionState.IDLE
        }
    }
}

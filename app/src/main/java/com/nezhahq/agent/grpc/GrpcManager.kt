package com.nezhahq.agent.grpc

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.util.Logger
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub

/**
 * gRPC connection state shared with the UI. Channel ownership belongs to [GrpcConnection].
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
        get() = this == PLAINTEXT_CONNECTING ||
            this == PLAINTEXT_CONNECTED ||
            this == PLAINTEXT_RECONNECTING
}

enum class GrpcTransportMode {
    TLS,
    PLAINTEXT
}

/**
 * The connection state the service publishes and the UI observes.
 *
 * Held by the application container rather than a global object, so the dependency is visible at
 * both ends and a test can observe a holder it owns. It carries no channel, stub, or Context.
 */
class ConnectionStateHolder {
    private val _connectionState = MutableStateFlow(GrpcConnectionState.IDLE)
    val connectionState: StateFlow<GrpcConnectionState> = _connectionState.asStateFlow()

    fun updateState(state: GrpcConnectionState) {
        _connectionState.value = state
    }
}

object GrpcManager {
    fun resolveTransportMode(useTls: Boolean): GrpcTransportMode =
        if (useTls) GrpcTransportMode.TLS else GrpcTransportMode.PLAINTEXT
}

internal fun interface GrpcManagedChannelFactory {
    fun create(config: AgentConfig, transportMode: GrpcTransportMode): ManagedChannel
}

internal interface GrpcConnection : AutoCloseable {
    val stub: NezhaServiceCoroutineStub?
    val transportMode: GrpcTransportMode

    fun connect()
    fun disconnect(preserveConnectionState: Boolean = false)
}

/**
 * One runtime's connection. Reconnect replaces its channel only after the old channel is closed.
 */
internal class ManagedGrpcConnection(
    private val config: AgentConfig,
    private val stateSink: (GrpcConnectionState) -> Unit = {},
    private val channelFactory: GrpcManagedChannelFactory =
        GrpcManagedChannelFactory { snapshot, mode ->
            GrpcChannelFactory.create(
                snapshot.server,
                snapshot.port,
                snapshot.secret,
                snapshot.uuid,
                mode
            )
        }
) : GrpcConnection {
    private val lock = Any()
    private var channel: ManagedChannel? = null
    private var closed = false

    @Volatile
    override var stub: NezhaServiceCoroutineStub? = null
        private set

    override val transportMode: GrpcTransportMode =
        GrpcManager.resolveTransportMode(config.useTls)

    override fun connect() = synchronized(lock) {
        check(!closed) { "A closed gRPC connection cannot be reused." }
        disconnectLocked(preserveConnectionState = true)
        when (transportMode) {
            GrpcTransportMode.TLS ->
                Logger.i("Grpc: 使用 TLS 加密连接 ${config.server}:${config.port}")
            GrpcTransportMode.PLAINTEXT ->
                Logger.i("Grpc: 使用显式明文连接 ${config.server}:${config.port}")
        }
        val newChannel = try {
            channelFactory.create(config, transportMode)
        } catch (exception: Exception) {
            if (transportMode == GrpcTransportMode.TLS) {
                Logger.e("Grpc: TLS 初始化失败，将保持 TLS 模式重试，禁止自动明文降级", exception)
            } else {
                Logger.e("Grpc: 明文通道初始化失败", exception)
            }
            throw exception
        }
        channel = newChannel
        stub = NezhaServiceCoroutineStub(newChannel)
    }

    override fun disconnect(preserveConnectionState: Boolean) = synchronized(lock) {
        disconnectLocked(preserveConnectionState)
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        disconnectLocked(preserveConnectionState = false)
    }

    private fun disconnectLocked(preserveConnectionState: Boolean) {
        channel?.shutdownNow()
        channel = null
        stub = null
        if (!preserveConnectionState) stateSink(GrpcConnectionState.IDLE)
    }
}

package com.nezhahq.agent.grpc

import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.concurrent.TimeUnit

object GrpcChannelFactory {
    fun create(
        server: String,
        port: Int,
        secret: String,
        uuid: String,
        transportMode: GrpcTransportMode
    ): ManagedChannel {
        val builder = OkHttpChannelBuilder.forAddress(server, port)
        when (transportMode) {
            GrpcTransportMode.TLS -> configureTls(builder)
            GrpcTransportMode.PLAINTEXT -> builder.usePlaintext()
        }

        return builder
            .keepAliveTime(10, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .intercept(AuthInterceptor(secret, uuid))
            .build()
    }

    private fun configureTls(builder: OkHttpChannelBuilder) {
        // 使用平台默认信任库和 gRPC OkHttp 的默认主机名校验。
        // 不要在此安装自定义的 trust-all TrustManager，否则 TLS 只能提供加密，
        // 无法验证服务端身份，连接会暴露给中间人攻击。
        builder.useTransportSecurity()
    }
}

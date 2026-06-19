package com.nezhahq.agent.grpc

import io.grpc.Metadata

/**
 * Dashboard 鉴权协议兼容层：同时写入 hyphen 和 underscore 两套 key。
 *
 * 历史原因：0.x 版本使用 client_secret/client_uuid，1.0+ 改为
 * client-secret/client-uuid。TODO(2026-Q3): 当 Dashboard 最低支持版本
 * >= 1.0 时，移除 LEGACY_* 写入。
 */
object AuthMetadataWriter {
    private val CLIENT_SECRET_KEY: Metadata.Key<String> =
        Metadata.Key.of("client-secret", Metadata.ASCII_STRING_MARSHALLER)
    private val CLIENT_UUID_KEY: Metadata.Key<String> =
        Metadata.Key.of("client-uuid", Metadata.ASCII_STRING_MARSHALLER)
    private val LEGACY_CLIENT_SECRET_KEY: Metadata.Key<String> =
        Metadata.Key.of("client_secret", Metadata.ASCII_STRING_MARSHALLER)
    private val LEGACY_CLIENT_UUID_KEY: Metadata.Key<String> =
        Metadata.Key.of("client_uuid", Metadata.ASCII_STRING_MARSHALLER)

    fun write(headers: Metadata, secret: String, uuid: String) {
        headers.put(CLIENT_SECRET_KEY, secret)
        headers.put(CLIENT_UUID_KEY, uuid)
        headers.put(LEGACY_CLIENT_SECRET_KEY, secret)
        headers.put(LEGACY_CLIENT_UUID_KEY, uuid)
    }
}

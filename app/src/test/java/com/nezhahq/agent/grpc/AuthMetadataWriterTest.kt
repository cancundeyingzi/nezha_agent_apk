package com.nezhahq.agent.grpc

import io.grpc.Metadata
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthMetadataWriterTest {
    @Test
    fun writeAddsHyphenAndLegacyUnderscoreKeys() {
        val headers = Metadata()

        AuthMetadataWriter.write(headers, secret = "secret-value", uuid = "uuid-value")

        assertEquals("secret-value", headers.getAscii("client-secret"))
        assertEquals("uuid-value", headers.getAscii("client-uuid"))
        assertEquals("secret-value", headers.getAscii("client_secret"))
        assertEquals("uuid-value", headers.getAscii("client_uuid"))
    }

    private fun Metadata.getAscii(name: String): String? =
        get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER))
}

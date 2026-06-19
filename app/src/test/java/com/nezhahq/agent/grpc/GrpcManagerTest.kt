package com.nezhahq.agent.grpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrpcManagerTest {
    @Test
    fun resolveTransportModeUsesExplicitTlsConfig() {
        assertEquals(GrpcTransportMode.TLS, GrpcManager.resolveTransportMode(useTls = true))
        assertEquals(GrpcTransportMode.PLAINTEXT, GrpcManager.resolveTransportMode(useTls = false))
    }

    @Test
    fun plaintextStatesAreNamedExplicitly() {
        assertTrue(GrpcConnectionState.PLAINTEXT_CONNECTED.isPlaintext)
        assertFalse(GrpcConnectionState.CONNECTED.isPlaintext)
    }
}

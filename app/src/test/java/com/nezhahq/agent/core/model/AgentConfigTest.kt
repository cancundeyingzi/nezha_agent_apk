package com.nezhahq.agent.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigTest {
    @Test
    fun validConfigurationIsAccepted() {
        val config = validConfig()

        assertTrue(config.useTls)
        assertFalse(config.remoteCapabilities.shellEnabled)
        assertFalse(config.remoteCapabilities.fileManagerEnabled)
        assertFalse(config.remoteCapabilities.natEnabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankServerIsRejected() {
        validConfig(server = " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun portBelowRangeIsRejected() {
        validConfig(port = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun portAboveRangeIsRejected() {
        validConfig(port = 65536)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankSecretIsRejected() {
        validConfig(secret = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankUuidIsRejected() {
        validConfig(uuid = "\t")
    }

    private fun validConfig(
        server: String = "agent.example.com",
        port: Int = 5555,
        secret: String = "secret",
        uuid: String = "uuid"
    ): AgentConfig = AgentConfig(
        server = server,
        port = port,
        secret = secret,
        uuid = uuid,
        useTls = true,
        rootMode = false
    )
}

package com.nezhahq.agent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * These messages are shown to the user verbatim, so they are asserted literally: the UI and the
 * service share this function, and changing a string here changes what the user reads.
 */
class AgentConfigValidationTest {
    @Test
    fun aCompleteConnectionPasses() {
        assertNull(AgentConfig.validationError(server = "panel.example.com", portText = "5555", secret = "s"))
    }

    @Test
    fun aBlankServerIsReported() {
        assertEquals(
            "请先填写服务端 IP 或域名",
            AgentConfig.validationError(server = "   ", portText = "5555", secret = "s")
        )
    }

    @Test
    fun aPortOutsideTheValidRangeIsReported() {
        val expected = "端口号无效，请填写 1-65535 之间的数字"
        assertEquals(expected, AgentConfig.validationError("host", "0", "s"))
        assertEquals(expected, AgentConfig.validationError("host", "65536", "s"))
        assertEquals(expected, AgentConfig.validationError("host", "-1", "s"))
        assertEquals(expected, AgentConfig.validationError("host", "abc", "s"))
        assertEquals(expected, AgentConfig.validationError("host", "", "s"))
    }

    @Test
    fun aBlankSecretIsReported() {
        assertEquals(
            "请先填写客户端密钥 (Secret)",
            AgentConfig.validationError(server = "host", portText = "5555", secret = "")
        )
    }

    @Test
    fun surroundingWhitespaceInThePortIsAccepted() {
        assertNull(AgentConfig.validationError(server = "host", portText = " 5555 ", secret = "s"))
    }

    /**
     * Passing validation is not the same as being buildable.
     *
     * [AgentConfig.validationError] takes no UUID at all, because a blank one is filled in for the
     * user rather than reported back. The constructor still rejects a blank UUID, so a caller that
     * forwards a validated form straight through without generating one gets an exception instead
     * of a message it can show. Asserting only that validation passes — which the previous version
     * of this test did, with a call identical to [aCompleteConnectionPasses] — cannot catch that.
     */
    @Test
    fun aFormThatPassesValidationStillNeedsAUuidBeforeItCanBuildAConfig() {
        assertNull(AgentConfig.validationError(server = "host", portText = "5555", secret = "s"))

        assertThrows(IllegalArgumentException::class.java) {
            AgentConfig(
                server = "host",
                port = 5555,
                secret = "s",
                uuid = "",
                useTls = true,
                rootMode = false
            )
        }
    }

    @Test
    fun everyValidatedFieldIsAcceptedByTheRuntimeConfig() {
        val config = AgentConfig(
            server = "host",
            port = 5555,
            secret = "s",
            uuid = "u",
            useTls = true,
            rootMode = false
        )

        assertNull(
            AgentConfig.validationError(config.server, config.port.toString(), config.secret)
        )
    }
}

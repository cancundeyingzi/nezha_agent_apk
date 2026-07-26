package com.nezhahq.agent

import com.nezhahq.agent.util.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SilentLoggerRuleTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun loggingInsideATestReachesTheRuleRatherThanTheAndroidLog() {
        Logger.i("info line")
        Logger.e("error line", IllegalStateException("cause"))

        assertEquals(listOf("info line", "error line"), silentLogger.messages)
    }

    /**
     * The point of the rule: without it this call throws "not mocked" now that
     * `unitTests.isReturnDefaultValues` is off.
     */
    @Test
    fun theInstalledSinkIsNotTheAndroidOne() {
        assertNotNull(Logger.platformSink)
        assertTrue(Logger.platformSink.javaClass.name.contains("SilentLoggerRule"))
    }
}

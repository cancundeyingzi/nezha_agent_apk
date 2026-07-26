package com.nezhahq.agent

import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.PlatformLogSink
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Points [Logger]'s platform write at a recorder instead of `android.util.Log` for one test.
 *
 * Unit tests run without the Android framework, so `android.util.Log` throws "not mocked" — add
 * this rule to any test whose code under test logs. It is worth knowing why the failure is hard to
 * recognise without it: the exception surfaces far from its cause, and a broad `catch` upstream can
 * turn it into a plausible-looking result. `TaskExecutor` did exactly that, reporting the mocking
 * error to the Dashboard as the task's output.
 *
 * Recorded lines are exposed so a test may assert on them, but the usual reason to add the rule is
 * simply to let logging happen.
 */
class SilentLoggerRule : TestRule {
    private val recorded = mutableListOf<String>()

    /** Messages logged during the test, in order. */
    val messages: List<String>
        get() = synchronized(recorded) { recorded.toList() }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val previous = Logger.platformSink
                Logger.platformSink = RecordingSink()
                try {
                    base.evaluate()
                } finally {
                    Logger.platformSink = previous
                }
            }
        }

    private inner class RecordingSink : PlatformLogSink {
        override fun info(message: String) = record(message)

        override fun error(message: String, throwable: Throwable?) = record(message)
    }

    private fun record(message: String) {
        synchronized(recorded) { recorded += message }
    }
}

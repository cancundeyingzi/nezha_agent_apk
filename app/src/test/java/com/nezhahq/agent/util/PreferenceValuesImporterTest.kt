package com.nezhahq.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceValuesImporterTest {
    @Test
    fun importsEverySupportedTypeInOneCommit() {
        val operations = FakeImportOperations()
        val values = linkedMapOf(
            "string" to "value",
            "strings" to setOf("a", "b"),
            "int" to 7,
            "long" to 8L,
            "float" to 1.5f,
            "boolean" to true
        )

        assertTrue(PreferenceValuesImporter(operations).importMissing(values))

        assertEquals(
            listOf(
                "put:string",
                "put:strings",
                "put:int",
                "put:long",
                "put:float",
                "put:boolean",
                "commit"
            ),
            operations.events
        )
        assertEquals("value", operations.targetValues["string"])
        assertEquals(setOf("a", "b"), operations.targetValues["strings"])
    }

    @Test
    fun existingPlaintextValueWinsWhileMissingLegacyKeysImport() {
        val operations = FakeImportOperations(
            initialTargetValues = linkedMapOf("server" to "new.example")
        )

        assertTrue(
            PreferenceValuesImporter(operations).importMissing(
                linkedMapOf(
                    "server" to "legacy.example",
                    "secret" to "legacy-secret"
                )
            )
        )

        assertEquals("new.example", operations.targetValues["server"])
        assertEquals("legacy-secret", operations.targetValues["secret"])
        assertEquals(listOf("put:secret", "commit"), operations.events)
    }

    @Test
    fun targetCommitFailureDoesNotPublishPendingValues() {
        val operations = FakeImportOperations(commitSucceeds = false)

        assertFalse(
            PreferenceValuesImporter(operations).importMissing(
                linkedMapOf("secret" to "legacy-secret")
            )
        )

        assertTrue(operations.targetValues.isEmpty())
        assertEquals(listOf("put:secret", "commit"), operations.events)
    }

    @Test
    fun unsupportedSetTypeIsRejectedWithoutCommit() {
        val operations = FakeImportOperations()

        assertFalse(
            PreferenceValuesImporter(operations).importMissing(
                linkedMapOf("invalid" to setOf(1, 2))
            )
        )

        assertTrue(operations.events.isEmpty())
        assertTrue(operations.targetValues.isEmpty())
    }

    private class FakeImportOperations(
        initialTargetValues: LinkedHashMap<String, Any?> = linkedMapOf(),
        private val commitSucceeds: Boolean = true
    ) : PreferenceImportOperations {
        val targetValues = LinkedHashMap(initialTargetValues)
        val events = mutableListOf<String>()

        override fun targetContains(key: String): Boolean = targetValues.containsKey(key)

        override fun targetEditor(): PreferenceValueEditor = object : PreferenceValueEditor {
            private val pendingValues = linkedMapOf<String, Any?>()

            override fun putString(key: String, value: String) = put(key, value)
            override fun putStringSet(key: String, value: Set<String>) = put(key, value)
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)

            override fun commit(): Boolean {
                events += "commit"
                if (commitSucceeds) targetValues.putAll(pendingValues)
                return commitSucceeds
            }

            private fun put(key: String, value: Any?) {
                events += "put:$key"
                pendingValues[key] = value
            }
        }
    }
}

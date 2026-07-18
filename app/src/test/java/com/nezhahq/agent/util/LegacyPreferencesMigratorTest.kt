package com.nezhahq.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPreferencesMigratorTest {
    @Test
    fun migratesEverySupportedTypeInOneCommitThenRemovesLegacyStorage() {
        val operations = FakeLegacyOperations(
            linkedMapOf(
                "string" to "value",
                "strings" to setOf("a", "b"),
                "int" to 7,
                "long" to 8L,
                "float" to 1.5f,
                "boolean" to true
            )
        )

        assertTrue(LegacyPreferencesMigrator(operations).migrate())

        assertEquals(
            listOf(
                "put:string",
                "put:strings",
                "put:int",
                "put:long",
                "put:float",
                "put:boolean",
                "commit",
                "clear",
                "delete"
            ),
            operations.events
        )
        assertEquals("value", operations.encryptedValues["string"])
        assertEquals(setOf("a", "b"), operations.encryptedValues["strings"])
        assertTrue(operations.legacyValues.isEmpty())
        assertFalse(operations.legacyExists)
    }

    @Test
    fun encryptedCommitFailureDoesNotClearOrDeleteLegacyValues() {
        val operations = FakeLegacyOperations(
            initialValues = linkedMapOf("secret" to "plaintext"),
            encryptedCommitSucceeds = false
        )

        assertFalse(LegacyPreferencesMigrator(operations).migrate())

        assertEquals(listOf("put:secret", "commit"), operations.events)
        assertEquals("plaintext", operations.legacyValues["secret"])
        assertTrue(operations.legacyExists)
    }

    @Test
    fun legacyClearFailureDoesNotDeleteLegacyStorage() {
        val operations = FakeLegacyOperations(
            initialValues = linkedMapOf("secret" to "plaintext"),
            legacyClearSucceeds = false
        )

        assertFalse(LegacyPreferencesMigrator(operations).migrate())

        assertEquals(listOf("put:secret", "commit", "clear"), operations.events)
        assertEquals("plaintext", operations.legacyValues["secret"])
        assertTrue(operations.legacyExists)
    }

    @Test
    fun absentLegacyFileIsNotReadOrCreated() {
        val operations = FakeLegacyOperations(linkedMapOf()).apply { legacyExists = false }

        assertTrue(LegacyPreferencesMigrator(operations).migrate())

        assertTrue(operations.events.isEmpty())
    }

    @Test
    fun existingEncryptedValueWinsWhileMissingLegacyKeysStillMigrate() {
        val operations = FakeLegacyOperations(
            initialValues = linkedMapOf(
                "server" to "legacy.example",
                "secret" to "legacy-secret"
            ),
            initialEncryptedValues = linkedMapOf("server" to "new.example")
        )

        assertTrue(LegacyPreferencesMigrator(operations).migrate())

        assertEquals("new.example", operations.encryptedValues["server"])
        assertEquals("legacy-secret", operations.encryptedValues["secret"])
        assertEquals(listOf("put:secret", "commit", "clear", "delete"), operations.events)
        assertTrue(operations.legacyValues.isEmpty())
        assertFalse(operations.legacyExists)
    }

    private class FakeLegacyOperations(
        initialValues: LinkedHashMap<String, Any?>,
        private val encryptedCommitSucceeds: Boolean = true,
        private val legacyClearSucceeds: Boolean = true,
        initialEncryptedValues: LinkedHashMap<String, Any?> = linkedMapOf()
    ) : LegacyPreferenceOperations {
        val legacyValues = LinkedHashMap(initialValues)
        val encryptedValues = LinkedHashMap(initialEncryptedValues)
        val events = mutableListOf<String>()
        var legacyExists = true

        override fun legacyStorageExists(): Boolean = legacyExists

        override fun readLegacyValues(): Map<String, Any?> = LinkedHashMap(legacyValues)

        override fun encryptedContains(key: String): Boolean = encryptedValues.containsKey(key)

        override fun encryptedEditor(): PreferenceValueEditor = object : PreferenceValueEditor {
            private val pendingValues = linkedMapOf<String, Any?>()

            override fun putString(key: String, value: String) = put(key, value)
            override fun putStringSet(key: String, value: Set<String>) = put(key, value)
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)

            override fun commit(): Boolean {
                events += "commit"
                if (encryptedCommitSucceeds) encryptedValues.putAll(pendingValues)
                return encryptedCommitSucceeds
            }

            private fun put(key: String, value: Any?) {
                events += "put:$key"
                pendingValues[key] = value
            }
        }

        override fun clearLegacyValues(): Boolean {
            events += "clear"
            if (legacyClearSucceeds) legacyValues.clear()
            return legacyClearSucceeds
        }

        override fun deleteLegacyStorage(): Boolean {
            events += "delete"
            legacyExists = false
            return true
        }
    }
}

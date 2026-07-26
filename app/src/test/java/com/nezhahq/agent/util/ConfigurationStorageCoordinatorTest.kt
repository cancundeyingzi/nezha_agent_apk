package com.nezhahq.agent.util

import com.nezhahq.agent.core.config.StorageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigurationStorageCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun completedMigrationOpensPlainStorageOnceAndIsIdempotent() {
        val operations = FakeConfigurationStorageOperations(migrationComplete = true)
        val coordinator = ConfigurationStorageCoordinator(operations)

        assertEquals(StorageStatus.READY, coordinator.initialize())
        assertEquals(StorageStatus.READY, coordinator.initialize())
        assertEquals(1, operations.openCalls)
        assertEquals(0, operations.importCalls)
        assertEquals("stored", coordinator.read("default") { "stored" })
    }

    @Test
    fun readableLegacyImportUsesPlainStorageWithoutRecoveryWarning() {
        val operations = FakeConfigurationStorageOperations(
            importResult = LegacyImportResult.COMPLETED
        )
        val coordinator = ConfigurationStorageCoordinator(operations)

        assertEquals(StorageStatus.READY, coordinator.initialize())
        assertEquals(1, operations.openCalls)
        assertEquals(1, operations.importCalls)
        assertEquals(0, operations.resetCalls)
    }

    @Test
    fun unreadableLegacyStoreNeverDeletesOrResetsUsablePlainValues() {
        val operations = FakeConfigurationStorageOperations(
            importResult = LegacyImportResult.UNREADABLE
        ).apply {
            plainStorage.values["server"] = "fallback.example"
        }
        val coordinator = ConfigurationStorageCoordinator(operations)

        assertEquals(StorageStatus.LEGACY_UNREADABLE, coordinator.initialize())
        assertEquals(
            "fallback.example",
            coordinator.read("") { it.values["server"] as String }
        )
        assertEquals(0, operations.resetCalls)
    }

    @Test
    fun successfulPlainWriteEndsUnreadableLegacyRecoveryState() {
        val coordinator = ConfigurationStorageCoordinator(
            FakeConfigurationStorageOperations(importResult = LegacyImportResult.UNREADABLE)
        )
        assertEquals(StorageStatus.LEGACY_UNREADABLE, coordinator.initialize())

        assertTrue(coordinator.write {
            it.values["secret"] = "new-secret"
            true
        })

        assertEquals(StorageStatus.READY, coordinator.currentStatus())
        assertEquals("new-secret", coordinator.read("") { it.values["secret"] as String })
    }

    @Test
    fun plainStorageOpenFailureIsUnavailableAndAccessFailsClosed() {
        val operations = FakeConfigurationStorageOperations(openThrows = true)
        val coordinator = ConfigurationStorageCoordinator(operations)
        var readerCalled = false
        var writerCalled = false

        assertEquals(StorageStatus.UNAVAILABLE, coordinator.initialize())
        assertEquals("safe", coordinator.read("safe") {
            readerCalled = true
            "unsafe"
        })
        assertFalse(coordinator.write {
            writerCalled = true
            true
        })
        assertFalse(readerCalled)
        assertFalse(writerCalled)
        assertEquals(1, operations.openCalls)
    }

    @Test
    fun failedLegacyImportDoesNotExposePartiallyInitializedStorage() {
        val operations = FakeConfigurationStorageOperations(
            importResult = LegacyImportResult.FAILED
        )
        val coordinator = ConfigurationStorageCoordinator(operations)
        var readerCalled = false

        assertEquals(StorageStatus.UNAVAILABLE, coordinator.initialize())
        assertEquals(5555, coordinator.read(5555) {
            readerCalled = true
            1
        })
        assertFalse(readerCalled)
        assertFalse(coordinator.write { true })
    }

    @Test
    fun failedWriteMakesSubsequentReadsUnavailable() {
        val coordinator = ConfigurationStorageCoordinator(
            FakeConfigurationStorageOperations(migrationComplete = true)
        )
        assertEquals(StorageStatus.READY, coordinator.initialize())

        assertFalse(coordinator.write { false })
        assertEquals(StorageStatus.UNAVAILABLE, coordinator.currentStatus())
        assertEquals("safe", coordinator.read("safe") { "unsafe" })
    }

    @Test
    fun resetRebuildsAndVerifiesEmptyPlainStorage() {
        val operations = FakeConfigurationStorageOperations(migrationComplete = true).apply {
            plainStorage.values["server"] = "old.example"
        }
        val coordinator = ConfigurationStorageCoordinator(operations)
        assertEquals(StorageStatus.READY, coordinator.initialize())

        assertTrue(coordinator.reset())

        assertEquals(1, operations.resetCalls)
        assertEquals(StorageStatus.READY, coordinator.currentStatus())
        assertTrue(coordinator.read(false) { it.values.isEmpty() })
    }

    @Test
    fun resetVerificationFailureStaysUnavailable() {
        val operations = FakeConfigurationStorageOperations(resetStorageIsValid = false)
        val coordinator = ConfigurationStorageCoordinator(operations)

        assertFalse(coordinator.reset())

        assertEquals(1, operations.resetCalls)
        assertEquals(StorageStatus.UNAVAILABLE, coordinator.currentStatus())
    }

    @Test
    fun sharedPreferencesFilesDeletesXmlAndBackup() {
        val dataDirectory = temporaryFolder.newFolder("data")
        val preferencesDirectory = File(dataDirectory, "shared_prefs").apply { mkdirs() }
        File(preferencesDirectory, "config.xml").writeText("xml")
        File(preferencesDirectory, "config.xml.bak").writeText("backup")
        val files = SharedPreferencesFiles(dataDirectory, "config")

        assertTrue(files.exists())
        assertTrue(files.delete())
        assertFalse(files.exists())
    }

    @Test
    fun api23DeletionRetriesClearAfterRemovingCorruptFiles() {
        val operations = FakeDeletionOperations(
            clearResults = listOf(false, true),
            supportsPlatformDelete = false
        )

        assertTrue(PreferencesDeletionCoordinator(operations).delete())

        assertEquals(listOf("clear", "files", "clear", "files"), operations.events)
        assertEquals(0, operations.platformDeleteCalls)
    }

    @Test
    fun api23DeletionFailsWhenSecondClearFails() {
        val operations = FakeDeletionOperations(
            clearResults = listOf(false, false),
            supportsPlatformDelete = false
        )

        assertFalse(PreferencesDeletionCoordinator(operations).delete())

        assertEquals(listOf("clear", "files", "clear"), operations.events)
    }

    @Test
    fun api24PlatformDeleteExceptionRemainsFailure() {
        val operations = FakeDeletionOperations(
            clearResults = emptyList(),
            supportsPlatformDelete = true,
            platformDeleteThrows = true
        )

        assertFalse(PreferencesDeletionCoordinator(operations).delete())

        assertEquals(listOf("platform", "files"), operations.events)
    }

    private data class FakeStorage(val values: MutableMap<String, Any?> = linkedMapOf())

    private class FakeConfigurationStorageOperations(
        private val migrationComplete: Boolean = false,
        private val importResult: LegacyImportResult = LegacyImportResult.COMPLETED,
        private val openThrows: Boolean = false,
        private val resetStorageIsValid: Boolean = true
    ) : ConfigurationStorageOperations<FakeStorage> {
        val plainStorage = FakeStorage()
        var openCalls = 0
        var importCalls = 0
        var resetCalls = 0

        override fun openPlainStorage(): FakeStorage {
            openCalls += 1
            if (openThrows) error("open failed")
            return plainStorage
        }

        override fun isMigrationComplete(storage: FakeStorage): Boolean = migrationComplete

        override fun importLegacyEncryptedStorage(storage: FakeStorage): LegacyImportResult {
            importCalls += 1
            return importResult
        }

        override fun resetPlainStorage(): FakeStorage {
            resetCalls += 1
            return FakeStorage()
        }

        override fun isResetStorage(storage: FakeStorage): Boolean = resetStorageIsValid
    }

    private class FakeDeletionOperations(
        clearResults: List<Boolean>,
        override val supportsPlatformDelete: Boolean,
        private val platformDeleteThrows: Boolean = false
    ) : PreferencesDeletionOperations {
        private val pendingClearResults = ArrayDeque(clearResults)
        val events = mutableListOf<String>()
        var platformDeleteCalls = 0

        override fun clearCacheSynchronously(): Boolean {
            events += "clear"
            return pendingClearResults.removeFirst()
        }

        override fun deleteWithPlatform(): Boolean {
            events += "platform"
            platformDeleteCalls += 1
            if (platformDeleteThrows) error("platform delete failed")
            return true
        }

        override fun deleteFiles(): Boolean {
            events += "files"
            return true
        }
    }
}

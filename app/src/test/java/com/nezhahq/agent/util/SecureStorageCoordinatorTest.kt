package com.nezhahq.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecureStorageCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun firstCreationSuccessIsReadyAndIdempotent() {
        val operations = FakeSecureStorageOperations()
        val coordinator = SecureStorageCoordinator(operations)

        assertEquals(StorageStatus.READY, coordinator.initialize())
        assertEquals(StorageStatus.READY, coordinator.initialize())
        assertEquals(1, operations.createCalls)
        assertEquals(1, operations.migrationCalls)
        assertEquals("stored", coordinator.read("default") { "stored" })
    }

    @Test
    fun firstCreationFailureCleansAndRetriesOnceAsRecovered() {
        val operations = FakeSecureStorageOperations(createFailuresRemaining = 1)
        val coordinator = SecureStorageCoordinator(operations)

        assertEquals(StorageStatus.RECOVERED, coordinator.initialize())
        assertEquals(2, operations.createCalls)
        assertEquals(1, operations.clearEncryptedCalls)
        assertEquals(1, operations.clearMasterKeyCalls)
        assertEquals(0, operations.clearFallbackCalls)
    }

    @Test
    fun repeatedCreationFailureIsUnavailableAndAccessFailsClosed() {
        val operations = FakeSecureStorageOperations(createFailuresRemaining = 2)
        val coordinator = SecureStorageCoordinator(operations)
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
        assertEquals(StorageStatus.UNAVAILABLE, coordinator.initialize())
        assertEquals(2, operations.createCalls)
    }

    @Test
    fun failedAutomaticCleanupDoesNotRetryOrClaimRecovery() {
        val failedCleanups = listOf(
            FakeSecureStorageOperations(
                createFailuresRemaining = 1,
                clearEncryptedSucceeds = false
            ),
            FakeSecureStorageOperations(
                createFailuresRemaining = 1,
                clearMasterKeySucceeds = false
            )
        )
        failedCleanups.forEach { operations ->
            val coordinator = SecureStorageCoordinator(operations)
            assertEquals(StorageStatus.UNAVAILABLE, coordinator.initialize())
            assertEquals(1, operations.createCalls)
            assertEquals(1, operations.clearEncryptedCalls)
            assertEquals(1, operations.clearMasterKeyCalls)
        }
    }

    @Test
    fun migrationFailureDoesNotExposeCandidateStorage() {
        val operations = FakeSecureStorageOperations(migrationSucceeds = false)
        val coordinator = SecureStorageCoordinator(operations)
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
        val coordinator = SecureStorageCoordinator(FakeSecureStorageOperations())
        assertEquals(StorageStatus.READY, coordinator.initialize())

        assertFalse(coordinator.write { false })
        assertEquals(StorageStatus.UNAVAILABLE, coordinator.currentStatus())
        assertEquals("safe", coordinator.read("safe") { "unsafe" })
    }

    @Test
    fun resetClearsAllStorageClassesAndRebuildsEmptyStorage() {
        val operations = FakeSecureStorageOperations().apply {
            createdValues += "server" to "old.example"
        }
        val coordinator = SecureStorageCoordinator(operations)
        assertEquals(StorageStatus.READY, coordinator.initialize())

        operations.createdValues.clear()
        assertTrue(coordinator.reset())

        assertEquals(1, operations.clearEncryptedCalls)
        assertEquals(1, operations.clearFallbackCalls)
        assertEquals(1, operations.clearMasterKeyCalls)
        assertEquals(2, operations.createCalls)
        assertEquals(StorageStatus.READY, coordinator.currentStatus())
        assertTrue(coordinator.read(false) { it.values.isEmpty() })
    }

    @Test
    fun resetRunsEveryCleanupAndStaysUnavailableWhenOneFails() {
        val operations = FakeSecureStorageOperations(clearEncryptedSucceeds = false)
        val coordinator = SecureStorageCoordinator(operations)

        assertFalse(coordinator.reset())

        assertEquals(1, operations.clearEncryptedCalls)
        assertEquals(1, operations.clearFallbackCalls)
        assertEquals(1, operations.clearMasterKeyCalls)
        assertEquals(0, operations.createCalls)
        assertEquals(StorageStatus.UNAVAILABLE, coordinator.currentStatus())
    }

    @Test
    fun sharedPreferencesFilesDeletesXmlAndBackup() {
        val dataDirectory = temporaryFolder.newFolder("data")
        val preferencesDirectory = File(dataDirectory, "shared_prefs").apply { mkdirs() }
        File(preferencesDirectory, "secure.xml").writeText("xml")
        File(preferencesDirectory, "secure.xml.bak").writeText("backup")
        val files = SharedPreferencesFiles(dataDirectory, "secure")

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
        assertEquals(0, operations.platformDeleteCalls)
    }

    @Test
    fun api23MissingFilesDoNotHideCacheClearFailure() {
        val operations = FakeDeletionOperations(
            clearResults = listOf(false, false),
            supportsPlatformDelete = false,
            filesInitiallyExist = false
        )

        assertFalse(PreferencesDeletionCoordinator(operations).delete())

        assertEquals(listOf("clear", "files", "clear"), operations.events)
        assertEquals(0, operations.platformDeleteCalls)
    }

    @Test
    fun api24PlatformFalseCanSucceedAfterExplicitFileRetry() {
        val operations = FakeDeletionOperations(
            clearResults = emptyList(),
            supportsPlatformDelete = true,
            platformDeleteResult = false
        )

        assertTrue(PreferencesDeletionCoordinator(operations).delete())

        assertEquals(listOf("platform", "files"), operations.events)
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

    private class FakeDeletionOperations(
        clearResults: List<Boolean>,
        override val supportsPlatformDelete: Boolean,
        filesInitiallyExist: Boolean = true,
        private val platformDeleteResult: Boolean = true,
        private val platformDeleteThrows: Boolean = false
    ) : PreferencesDeletionOperations {
        private val pendingClearResults = ArrayDeque(clearResults)
        private var filesExist = filesInitiallyExist
        val events = mutableListOf<String>()
        var platformDeleteCalls = 0

        override fun clearCacheSynchronously(): Boolean {
            events += "clear"
            val succeeded = pendingClearResults.removeFirst()
            if (succeeded) filesExist = true // commit rebuilds an empty preferences file.
            return succeeded
        }

        override fun deleteWithPlatform(): Boolean {
            events += "platform"
            platformDeleteCalls += 1
            if (platformDeleteThrows) error("platform delete failed")
            if (platformDeleteResult) filesExist = false
            return platformDeleteResult
        }

        override fun deleteFiles(): Boolean {
            events += "files"
            if (filesExist) filesExist = false
            return !filesExist
        }
    }

    private class FakeSecureStorageOperations(
        private var createFailuresRemaining: Int = 0,
        private val migrationSucceeds: Boolean = true,
        private val clearEncryptedSucceeds: Boolean = true,
        private val clearMasterKeySucceeds: Boolean = true
    ) : SecureStorageOperations<FakeStorage> {
        var createCalls = 0
        var clearEncryptedCalls = 0
        var clearFallbackCalls = 0
        var clearMasterKeyCalls = 0
        var migrationCalls = 0
        val createdValues = linkedMapOf<String, Any?>()

        override fun createEncryptedStorage(): FakeStorage {
            createCalls += 1
            if (createFailuresRemaining > 0) {
                createFailuresRemaining -= 1
                error("creation failed")
            }
            return FakeStorage(createdValues.toMutableMap())
        }

        override fun clearEncryptedStorage(): Boolean {
            clearEncryptedCalls += 1
            createdValues.clear()
            return clearEncryptedSucceeds
        }

        override fun clearLegacyFallback(): Boolean {
            clearFallbackCalls += 1
            return true
        }

        override fun clearMasterKey(): Boolean {
            clearMasterKeyCalls += 1
            return clearMasterKeySucceeds
        }

        override fun migrateLegacyFallback(storage: FakeStorage): Boolean {
            migrationCalls += 1
            return migrationSucceeds
        }

        override fun isStorageEmpty(storage: FakeStorage): Boolean = storage.values.isEmpty()
    }
}

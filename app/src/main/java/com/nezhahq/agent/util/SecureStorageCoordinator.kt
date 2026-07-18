package com.nezhahq.agent.util

import java.io.File

/** Current availability of the encrypted configuration store. */
enum class StorageStatus {
    READY,
    RECOVERED,
    UNAVAILABLE
}

/**
 * Platform operations used by [SecureStorageCoordinator].
 *
 * Keeping the lifecycle policy here, rather than in Android framework calls, makes the exact
 * recovery/reset behavior deterministic and unit-testable.
 */
internal interface SecureStorageOperations<T> {
    fun createEncryptedStorage(): T
    fun clearEncryptedStorage(): Boolean
    fun clearLegacyFallback(): Boolean
    fun clearMasterKey(): Boolean
    fun migrateLegacyFallback(storage: T): Boolean
    fun isStorageEmpty(storage: T): Boolean
}

/** Thread-safe, single-attempt lifecycle and fail-closed access policy for secure storage. */
internal class SecureStorageCoordinator<T>(
    private val operations: SecureStorageOperations<T>
) {
    private val lock = Any()
    private var status: StorageStatus? = null
    private var storage: T? = null

    fun initialize(): StorageStatus = synchronized(lock) {
        status?.let { return@synchronized it }

        val initialStorage = createStorageOrNull()
        if (initialStorage != null) {
            return@synchronized finishInitialization(initialStorage, StorageStatus.READY)
        }

        // A failed first creation gets one, and only one, automatic repair attempt.
        val encryptedCleared = safely { operations.clearEncryptedStorage() }
        val masterKeyCleared = safely { operations.clearMasterKey() }
        if (!encryptedCleared || !masterKeyCleared) {
            return@synchronized becomeUnavailable()
        }

        val recoveredStorage = createStorageOrNull()
            ?: return@synchronized becomeUnavailable()
        finishInitialization(recoveredStorage, StorageStatus.RECOVERED)
    }

    fun currentStatus(): StorageStatus = synchronized(lock) {
        status ?: StorageStatus.UNAVAILABLE
    }

    fun <R> read(defaultValue: R, reader: (T) -> R): R = synchronized(lock) {
        val activeStorage = storage ?: return@synchronized defaultValue
        try {
            reader(activeStorage)
        } catch (_: Exception) {
            becomeUnavailable()
            defaultValue
        }
    }

    fun write(writer: (T) -> Boolean): Boolean = synchronized(lock) {
        val activeStorage = storage ?: return@synchronized false
        val persisted = try {
            writer(activeStorage)
        } catch (_: Exception) {
            false
        }
        if (!persisted) becomeUnavailable()
        persisted
    }

    /**
     * Removes every historical storage source and creates a new, verified-empty encrypted store.
     * Every cleanup operation runs even if another one fails, so a later user retry has the best
     * chance of succeeding.
     */
    fun reset(): Boolean = synchronized(lock) {
        becomeUnavailable()

        val encryptedCleared = safely { operations.clearEncryptedStorage() }
        val fallbackCleared = safely { operations.clearLegacyFallback() }
        val masterKeyCleared = safely { operations.clearMasterKey() }
        if (!encryptedCleared || !fallbackCleared || !masterKeyCleared) {
            return@synchronized false
        }

        val newStorage = createStorageOrNull() ?: return@synchronized false
        val isEmpty = safely { operations.isStorageEmpty(newStorage) }
        if (!isEmpty) return@synchronized false

        storage = newStorage
        status = StorageStatus.READY
        true
    }

    private fun finishInitialization(candidate: T, successStatus: StorageStatus): StorageStatus {
        if (!safely { operations.migrateLegacyFallback(candidate) }) {
            return becomeUnavailable()
        }
        storage = candidate
        status = successStatus
        return successStatus
    }

    private fun createStorageOrNull(): T? = try {
        operations.createEncryptedStorage()
    } catch (_: Exception) {
        null
    }

    private fun becomeUnavailable(): StorageStatus {
        storage = null
        status = StorageStatus.UNAVAILABLE
        return StorageStatus.UNAVAILABLE
    }

    private inline fun safely(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: Exception) {
        false
    }
}

internal interface PreferenceValueEditor {
    fun putString(key: String, value: String)
    fun putStringSet(key: String, value: Set<String>)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putFloat(key: String, value: Float)
    fun putBoolean(key: String, value: Boolean)
    fun commit(): Boolean
}

internal interface LegacyPreferenceOperations {
    fun legacyStorageExists(): Boolean
    fun readLegacyValues(): Map<String, Any?>
    fun encryptedContains(key: String): Boolean
    fun encryptedEditor(): PreferenceValueEditor
    fun clearLegacyValues(): Boolean
    fun deleteLegacyStorage(): Boolean
}

/** Copies legacy values in one encrypted commit, then removes the plaintext source. */
internal class LegacyPreferencesMigrator(
    private val operations: LegacyPreferenceOperations
) {
    fun migrate(): Boolean {
        return try {
            if (!operations.legacyStorageExists()) return true

            val values = operations.readLegacyValues()
            val editor = operations.encryptedEditor()
            for ((key, value) in values) {
                if (operations.encryptedContains(key)) continue
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> {
                        if (!value.all { it is String }) return false
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, (value as Set<String>).toSet())
                    }
                    else -> return false
                }
            }

            if (!editor.commit()) return false
            if (!operations.clearLegacyValues()) return false
            operations.deleteLegacyStorage()
        } catch (_: Exception) {
            false
        }
    }
}

/** Files Android uses for one SharedPreferences name. */
internal class SharedPreferencesFiles(dataDirectory: File, preferencesName: String) {
    private val preferencesDirectory = File(dataDirectory, "shared_prefs")
    private val xmlFile = File(preferencesDirectory, "$preferencesName.xml")
    private val backupFile = File(preferencesDirectory, "$preferencesName.xml.bak")

    fun exists(): Boolean = xmlFile.exists() || backupFile.exists()

    fun delete(): Boolean {
        val xmlDeleted = !xmlFile.exists() || xmlFile.delete()
        val backupDeleted = !backupFile.exists() || backupFile.delete()
        return xmlDeleted && backupDeleted && !exists()
    }
}

internal interface PreferencesDeletionOperations {
    val supportsPlatformDelete: Boolean

    fun clearCacheSynchronously(): Boolean
    fun deleteWithPlatform(): Boolean
    fun deleteFiles(): Boolean
}

/**
 * Deletes one SharedPreferences store while proving both its in-memory cache and disk files were
 * cleared. API 23 needs a second clear after removing a file that prevented the first commit.
 */
internal class PreferencesDeletionCoordinator(
    private val operations: PreferencesDeletionOperations
) {
    fun delete(): Boolean {
        if (operations.supportsPlatformDelete) {
            val platformCallCompleted = try {
                operations.deleteWithPlatform()
                true
            } catch (_: Exception) {
                false
            }
            val filesDeleted = safely { operations.deleteFiles() }
            return platformCallCompleted && filesDeleted
        }

        if (safely { operations.clearCacheSynchronously() }) {
            return safely { operations.deleteFiles() }
        }

        // Remove a corrupt file, rebuild/clear the cached preferences, then remove the new empty
        // file created by the successful synchronous clear.
        if (!safely { operations.deleteFiles() }) return false
        if (!safely { operations.clearCacheSynchronously() }) return false
        return safely { operations.deleteFiles() }
    }

    private inline fun safely(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: Exception) {
        false
    }
}

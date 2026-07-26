package com.nezhahq.agent.util

import com.nezhahq.agent.core.config.StorageStatus
import java.io.File

internal enum class LegacyImportResult {
    COMPLETED,
    UNREADABLE,
    FAILED
}

/** Platform operations used by [ConfigurationStorageCoordinator]. */
internal interface ConfigurationStorageOperations<T> {
    fun openPlainStorage(): T
    fun isMigrationComplete(storage: T): Boolean
    fun importLegacyEncryptedStorage(storage: T): LegacyImportResult
    fun resetPlainStorage(): T
    fun isResetStorage(storage: T): Boolean
}

/**
 * Thread-safe lifecycle for the traditional app-private configuration store.
 *
 * The old encrypted preferences are treated only as a non-destructive migration source. If the
 * Android Keystore cannot decrypt them, the live plaintext store is still exposed and the source
 * is left untouched. This avoids the old recovery cycle that deleted ciphertext before it could
 * know whether any configuration had survived elsewhere.
 */
internal class ConfigurationStorageCoordinator<T>(
    private val operations: ConfigurationStorageOperations<T>
) {
    private val lock = Any()
    private var status: StorageStatus? = null
    private var storage: T? = null

    fun initialize(): StorageStatus = synchronized(lock) {
        status?.let { return@synchronized it }

        val plainStorage = try {
            operations.openPlainStorage()
        } catch (_: Exception) {
            return@synchronized becomeUnavailable()
        }

        val migrationComplete = try {
            operations.isMigrationComplete(plainStorage)
        } catch (_: Exception) {
            return@synchronized becomeUnavailable()
        }

        if (migrationComplete) {
            return@synchronized activate(plainStorage, StorageStatus.READY)
        }

        when (try {
            operations.importLegacyEncryptedStorage(plainStorage)
        } catch (_: Exception) {
            LegacyImportResult.FAILED
        }) {
            LegacyImportResult.COMPLETED -> activate(plainStorage, StorageStatus.READY)
            LegacyImportResult.UNREADABLE ->
                activate(plainStorage, StorageStatus.LEGACY_UNREADABLE)
            LegacyImportResult.FAILED -> becomeUnavailable()
        }
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
        if (persisted) {
            // Every live write also records the migration marker, so an unreadable legacy source
            // is never retried after the user has saved authoritative plaintext configuration.
            status = StorageStatus.READY
        } else {
            becomeUnavailable()
        }
        persisted
    }

    /** Deletes live configuration and recreates a verified-empty plaintext store. */
    fun reset(): Boolean = synchronized(lock) {
        becomeUnavailable()

        val newStorage = try {
            operations.resetPlainStorage()
        } catch (_: Exception) {
            return@synchronized false
        }
        val resetVerified = try {
            operations.isResetStorage(newStorage)
        } catch (_: Exception) {
            false
        }
        if (!resetVerified) return@synchronized false

        activate(newStorage, StorageStatus.READY)
        true
    }

    private fun activate(candidate: T, newStatus: StorageStatus): StorageStatus {
        storage = candidate
        status = newStatus
        return newStatus
    }

    private fun becomeUnavailable(): StorageStatus {
        storage = null
        status = StorageStatus.UNAVAILABLE
        return StorageStatus.UNAVAILABLE
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

internal interface PreferenceImportOperations {
    fun targetContains(key: String): Boolean
    fun targetEditor(): PreferenceValueEditor
}

/** Copies supported values without overwriting values already saved in the plaintext target. */
internal class PreferenceValuesImporter(
    private val operations: PreferenceImportOperations
) {
    fun importMissing(values: Map<String, Any?>): Boolean {
        return try {
            val editor = operations.targetEditor()
            for ((key, value) in values) {
                if (operations.targetContains(key)) continue
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
            editor.commit()
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

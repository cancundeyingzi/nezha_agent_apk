package com.nezhahq.agent.core.config

/** Current availability of the app-private, plaintext configuration store. */
enum class StorageStatus {
    READY,

    /**
     * Plaintext storage is usable, but the one-time import from the old encrypted store could not
     * be read. Existing plaintext fallback values remain available and no legacy data is deleted.
     */
    LEGACY_UNREADABLE,
    UNAVAILABLE;

    val isUsable: Boolean
        get() = this != UNAVAILABLE
}

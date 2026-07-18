package com.nezhahq.agent.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nezhahq.agent.simulator.SimulatedDeviceConfig
import java.io.File
import java.security.KeyStore

/** Encrypted, fail-closed configuration storage. */
object ConfigStore {
    private const val PREFS_FILE = "nezha_secure_prefs"
    private const val LEGACY_FALLBACK_FILE = "${PREFS_FILE}_fallback"
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    private val runtimeLock = Any()

    @Volatile
    private var runtime: Runtime? = null

    /**
     * Initializes encrypted storage once per process. A failed first creation is repaired and
     * retried once; no plaintext store is ever returned as live configuration storage.
     */
    fun initialize(context: Context): StorageStatus = runtimeFor(context).coordinator.initialize()

    /**
     * Deletes encrypted preferences, the historical plaintext fallback, and the AndroidKeyStore
     * master key before creating a verified-empty encrypted store.
     */
    fun resetSecureStorage(context: Context): Boolean = runtimeFor(context).coordinator.reset()

    fun saveConfig(
        context: Context,
        server: String,
        port: Int,
        secret: String,
        useTLS: Boolean = true,
        uuid: String = "",
        rootMode: Boolean = false,
        enableKeepAliveAudio: Boolean = false
    ): Boolean = commit(context) { editor ->
        editor.putString("server", server)
        editor.putInt("port", port)
        editor.putString("secret", secret)
        editor.putBoolean("use_tls", useTLS)
        editor.putString("uuid", uuid)
        editor.putBoolean("root_mode", rootMode)
        editor.putBoolean("enable_keep_alive_audio", enableKeepAliveAudio)
    }

    fun getServer(context: Context): String = read(context, "") {
        it.getString("server", "") ?: ""
    }

    fun getPort(context: Context): Int = read(context, 5555) { it.getInt("port", 5555) }

    fun getSecret(context: Context): String = read(context, "") {
        it.getString("secret", "") ?: ""
    }

    fun getUuid(context: Context): String = read(context, "") {
        it.getString("uuid", "") ?: ""
    }

    fun getUseTls(context: Context): Boolean = read(context, true) {
        it.getBoolean("use_tls", true)
    }

    fun getRootMode(context: Context): Boolean = read(context, false) {
        it.getBoolean("root_mode", false)
    }

    fun getEnableKeepAliveAudio(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_keep_alive_audio", false)
    }

    fun getEnableFloatWindow(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_float_window", false)
    }

    fun getEnableVpnTraffic(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_vpn_traffic", false)
    }

    fun getEnableAutoStart(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_auto_start", false)
    }

    fun getHasShownAutoStartPrompt(context: Context): Boolean = read(context, false) {
        it.getBoolean("has_shown_auto_start_prompt", false)
    }

    fun getEnableRemoteCommand(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_remote_command", false)
    }

    fun hasSimulatorConfig(context: Context): Boolean = read(context, false) {
        it.contains("simulator_server")
    }

    fun getSimulatorServer(context: Context): String = read(context, "") { prefs ->
        if (prefs.contains("simulator_server")) {
            prefs.getString("simulator_server", "") ?: ""
        } else {
            prefs.getString("server", "") ?: ""
        }
    }

    fun getSimulatorPort(context: Context): Int = read(context, 5555) { prefs ->
        if (prefs.contains("simulator_port")) {
            prefs.getInt("simulator_port", 5555)
        } else {
            prefs.getInt("port", 5555)
        }
    }

    fun getSimulatorSecret(context: Context): String = read(context, "") { prefs ->
        if (prefs.contains("simulator_secret")) {
            prefs.getString("simulator_secret", "") ?: ""
        } else {
            prefs.getString("secret", "") ?: ""
        }
    }

    fun getSimulatorUseTls(context: Context): Boolean = read(context, true) { prefs ->
        if (prefs.contains("simulator_use_tls")) {
            prefs.getBoolean("simulator_use_tls", true)
        } else {
            prefs.getBoolean("use_tls", true)
        }
    }

    fun getSimulatorThreadCount(context: Context): Int =
        read(context, SimulatedDeviceConfig.DEFAULT_THREAD_COUNT) { prefs ->
            prefs.getInt("simulator_thread_count", SimulatedDeviceConfig.DEFAULT_THREAD_COUNT)
                .coerceIn(1, SimulatedDeviceConfig.MAX_THREAD_COUNT)
        }

    fun setEnableAutoStart(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_auto_start", enable)
    }

    fun setHasShownAutoStartPrompt(context: Context, shown: Boolean): Boolean = commit(context) {
        it.putBoolean("has_shown_auto_start_prompt", shown)
    }

    fun saveSimulatorConfig(
        context: Context,
        server: String,
        port: Int,
        secret: String,
        useTls: Boolean,
        threadCount: Int
    ): Boolean = commit(context) { editor ->
        editor.putString("simulator_server", server)
        editor.putInt("simulator_port", port)
        editor.putString("simulator_secret", secret)
        editor.putBoolean("simulator_use_tls", useTls)
        editor.putInt(
            "simulator_thread_count",
            threadCount.coerceIn(1, SimulatedDeviceConfig.MAX_THREAD_COUNT)
        )
    }

    fun setEnableFloatWindow(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_float_window", enable)
    }

    fun setEnableKeepAliveAudio(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_keep_alive_audio", enable)
    }

    fun setEnableVpnTraffic(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_vpn_traffic", enable)
    }

    fun setEnableRemoteCommand(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_remote_command", enable)
    }

    fun saveToolSettings(
        context: Context,
        enableKeepAliveAudio: Boolean,
        enableFloatWindow: Boolean,
        enableVpnTraffic: Boolean
    ): Boolean = commit(context) { editor ->
        editor.putBoolean("enable_keep_alive_audio", enableKeepAliveAudio)
        editor.putBoolean("enable_float_window", enableFloatWindow)
        editor.putBoolean("enable_vpn_traffic", enableVpnTraffic)
    }

    fun saveAutoStartPromptResult(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_auto_start", enable)
        it.putBoolean("has_shown_auto_start_prompt", true)
    }

    fun hasValidConfig(context: Context): Boolean = read(context, false) { prefs ->
        !prefs.getString("server", "").isNullOrEmpty() &&
            !prefs.getString("secret", "").isNullOrEmpty() &&
            !prefs.getString("uuid", "").isNullOrEmpty()
    }

    private fun runtimeFor(context: Context): Runtime {
        runtime?.let { return it }
        return synchronized(runtimeLock) {
            runtime ?: Runtime(context.applicationContext).also { runtime = it }
        }
    }

    private inline fun <T> read(
        context: Context,
        defaultValue: T,
        crossinline reader: (SharedPreferences) -> T
    ): T {
        val currentRuntime = runtimeFor(context)
        currentRuntime.coordinator.initialize()
        return currentRuntime.coordinator.read(defaultValue) { reader(it) }
    }

    /** SharedPreferences.commit is intentional: callers must know whether persistence succeeded. */
    @SuppressLint("ApplySharedPref")
    private inline fun commit(
        context: Context,
        crossinline changes: (SharedPreferences.Editor) -> Unit
    ): Boolean {
        val currentRuntime = runtimeFor(context)
        if (currentRuntime.coordinator.initialize() == StorageStatus.UNAVAILABLE) return false
        return currentRuntime.coordinator.write { prefs ->
            val editor = prefs.edit()
            changes(editor)
            editor.commit()
        }
    }

    private class Runtime(context: Context) {
        val coordinator = SecureStorageCoordinator(AndroidSecureStorageOperations(context))
    }

    private class AndroidSecureStorageOperations(
        private val context: Context
    ) : SecureStorageOperations<SharedPreferences> {
        override fun createEncryptedStorage(): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        override fun clearEncryptedStorage(): Boolean =
            deletePreferencesCompat(context, PREFS_FILE)

        override fun clearLegacyFallback(): Boolean =
            deletePreferencesCompat(context, LEGACY_FALLBACK_FILE)

        override fun clearMasterKey(): Boolean = try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
            !keyStore.containsAlias(MASTER_KEY_ALIAS)
        } catch (_: Exception) {
            false
        }

        override fun migrateLegacyFallback(storage: SharedPreferences): Boolean {
            return LegacyPreferencesMigrator(
                AndroidLegacyPreferenceOperations(context, storage)
            ).migrate()
        }

        override fun isStorageEmpty(storage: SharedPreferences): Boolean = storage.all.isEmpty()
    }

    private class AndroidLegacyPreferenceOperations(
        private val context: Context,
        private val encryptedPreferences: SharedPreferences
    ) : LegacyPreferenceOperations {
        private val legacyFiles = preferencesFiles(context, LEGACY_FALLBACK_FILE)
        private val legacyPreferences by lazy {
            context.getSharedPreferences(LEGACY_FALLBACK_FILE, Context.MODE_PRIVATE)
        }

        override fun legacyStorageExists(): Boolean = legacyFiles.exists()

        override fun readLegacyValues(): Map<String, Any?> = legacyPreferences.all

        override fun encryptedContains(key: String): Boolean =
            encryptedPreferences.contains(key)

        override fun encryptedEditor(): PreferenceValueEditor =
            SharedPreferencesValueEditor(encryptedPreferences.edit())

        @SuppressLint("ApplySharedPref")
        override fun clearLegacyValues(): Boolean = legacyPreferences.edit().clear().commit()

        override fun deleteLegacyStorage(): Boolean =
            deletePreferencesCompat(context, LEGACY_FALLBACK_FILE)
    }

    private class SharedPreferencesValueEditor(
        private val editor: SharedPreferences.Editor
    ) : PreferenceValueEditor {
        override fun putString(key: String, value: String) {
            editor.putString(key, value)
        }

        override fun putStringSet(key: String, value: Set<String>) {
            editor.putStringSet(key, value)
        }

        override fun putInt(key: String, value: Int) {
            editor.putInt(key, value)
        }

        override fun putLong(key: String, value: Long) {
            editor.putLong(key, value)
        }

        override fun putFloat(key: String, value: Float) {
            editor.putFloat(key, value)
        }

        override fun putBoolean(key: String, value: Boolean) {
            editor.putBoolean(key, value)
        }

        @SuppressLint("ApplySharedPref")
        override fun commit(): Boolean = editor.commit()
    }

    private fun deletePreferencesCompat(context: Context, name: String): Boolean {
        return PreferencesDeletionCoordinator(
            AndroidPreferencesDeletionOperations(context, name)
        ).delete()
    }

    private class AndroidPreferencesDeletionOperations(
        private val context: Context,
        private val name: String
    ) : PreferencesDeletionOperations {
        private val files = preferencesFiles(context, name)

        override val supportsPlatformDelete: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

        @SuppressLint("ApplySharedPref")
        override fun clearCacheSynchronously(): Boolean {
            return context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }

        override fun deleteWithPlatform(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(name)
            } else {
                false
            }
        }

        override fun deleteFiles(): Boolean = files.delete()
    }

    private fun preferencesFiles(context: Context, name: String): SharedPreferencesFiles {
        return SharedPreferencesFiles(File(context.applicationInfo.dataDir), name)
    }
}

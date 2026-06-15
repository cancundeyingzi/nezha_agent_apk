package com.nezhahq.agent.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object ConfigStore {

    private const val PREFS_FILE = "nezha_secure_prefs"

    @Volatile
    private var prefsInstance: SharedPreferences? = null

    /**
     * 获取加密的 SharedPreferences 实例。
     * 采用双重检查锁（Double-Checked Locking）单例模式，避免由于反复初始化
     * EncryptedSharedPreferences 和读写 KeyStore 产生严重的性能开销和主线程卡顿。
     * [Security Audit] 使用了 applicationContext 防内存溢出，同时利用 AES256_GCM 保证数据存储安全。
     *
     * [Robustness] 如果 EncryptedSharedPreferences 初始化失败（KeyStore 损坏），自动降级到普通 SharedPreferences。
     */
    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        android.util.Log.i("LiquidGlass", "→ ConfigStore.getEncryptedPrefs 被调用")
        return prefsInstance ?: synchronized(this) {
            prefsInstance ?: run {
                android.util.Log.i("LiquidGlass", "  prefsInstance 为 null，需要初始化")
                try {
                    android.util.Log.i("LiquidGlass", "  → 开始创建 MasterKey")
                    val masterKey = MasterKey.Builder(context.applicationContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    android.util.Log.i("LiquidGlass", "  ✓ MasterKey 创建成功")

                    android.util.Log.i("LiquidGlass", "  → 开始创建 EncryptedSharedPreferences")
                    val prefs = EncryptedSharedPreferences.create(
                        context.applicationContext,
                        PREFS_FILE,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    android.util.Log.i("LiquidGlass", "  ✓ EncryptedSharedPreferences 创建成功")
                    prefsInstance = prefs
                    prefs
                } catch (e: Exception) {
                    android.util.Log.e("LiquidGlass", "  ✗ EncryptedSharedPreferences 初始化失败", e)
                    android.util.Log.e("LiquidGlass", "  异常类型: ${e.javaClass.name}")
                    android.util.Log.e("LiquidGlass", "  异常消息: ${e.message}")

                    // 尝试删除损坏的加密文件和 MasterKey，然后重试一次
                    android.util.Log.w("LiquidGlass", "  → 尝试删除损坏的加密文件并重试")
                    try {
                        // 删除损坏的 SharedPreferences 文件
                        context.applicationContext.deleteSharedPreferences(PREFS_FILE)

                        // 尝试删除损坏的 MasterKey（如果可能）
                        try {
                            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                            keyStore.load(null)
                            val masterKeyAlias = "_androidx_security_master_key_"
                            if (keyStore.containsAlias(masterKeyAlias)) {
                                keyStore.deleteEntry(masterKeyAlias)
                                android.util.Log.i("LiquidGlass", "  ✓ 已删除损坏的 MasterKey")
                            }
                        } catch (keyEx: Exception) {
                            android.util.Log.w("LiquidGlass", "  删除 MasterKey 时出错（可忽略）", keyEx)
                        }

                        android.util.Log.i("LiquidGlass", "  → 重新创建 EncryptedSharedPreferences")
                        val masterKey = MasterKey.Builder(context.applicationContext)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build()

                        val prefs = EncryptedSharedPreferences.create(
                            context.applicationContext,
                            PREFS_FILE,
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                        android.util.Log.i("LiquidGlass", "  ✓ 重试成功！EncryptedSharedPreferences 已创建")
                        prefsInstance = prefs
                        prefs
                    } catch (retryEx: Exception) {
                        android.util.Log.e("LiquidGlass", "  ✗ 重试仍然失败", retryEx)
                        android.util.Log.w("LiquidGlass", "  → 降级到普通 SharedPreferences（无加密）")

                        // 降级到普通 SharedPreferences
                        val fallbackPrefs = context.applicationContext.getSharedPreferences(
                            "${PREFS_FILE}_fallback",
                            Context.MODE_PRIVATE
                        )
                        android.util.Log.i("LiquidGlass", "  ✓ 已降级到普通 SharedPreferences")
                        prefsInstance = fallbackPrefs
                        fallbackPrefs
                    }
                }
            }
        }
    }

    /**
     * 保存连接配置和基础工具设置。
     *
     * 注意：enable_vpn_traffic 由 [setEnableVpnTraffic] 独立管理，
     * 不在此方法中写入，防止全量覆写导致设置被重置。
     */
    fun saveConfig(context: Context, server: String, port: Int, secret: String, useTLS: Boolean = true, uuid: String = "", rootMode: Boolean = false, enableKeepAliveAudio: Boolean = false) {
        getEncryptedPrefs(context).edit().apply {
            putString("server", server)
            putInt("port", port)
            putString("secret", secret)
            putBoolean("use_tls", useTLS)
            putString("uuid", uuid)
            putBoolean("root_mode", rootMode)
            putBoolean("enable_keep_alive_audio", enableKeepAliveAudio)
            // enable_float_window 由 setEnableFloatWindow() 独立管理
            // enable_vpn_traffic 由 setEnableVpnTraffic() 独立管理
            apply()
        }
    }

    fun getServer(context: Context): String = getEncryptedPrefs(context).getString("server", "") ?: ""
    fun getPort(context: Context): Int = getEncryptedPrefs(context).getInt("port", 5555)
    fun getSecret(context: Context): String = getEncryptedPrefs(context).getString("secret", "") ?: ""
    fun getUuid(context: Context): String = getEncryptedPrefs(context).getString("uuid", "") ?: ""
    fun getUseTls(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("use_tls", true)
    fun getRootMode(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("root_mode", false)
    fun getEnableKeepAliveAudio(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("enable_keep_alive_audio", false)
    fun getEnableFloatWindow(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("enable_float_window", false)
    fun getEnableVpnTraffic(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("enable_vpn_traffic", false)
    fun getEnableAutoStart(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("enable_auto_start", false)
    fun getHasShownAutoStartPrompt(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("has_shown_auto_start_prompt", false)

    fun setEnableAutoStart(context: Context, enable: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean("enable_auto_start", enable).apply()
    }

    fun setHasShownAutoStartPrompt(context: Context, shown: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean("has_shown_auto_start_prompt", shown).apply()
    }

    /** 悬浮窗开关 — 独立保存，不受 saveConfig 全量覆写影响 */
    fun setEnableFloatWindow(context: Context, enable: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean("enable_float_window", enable).apply()
    }

    /** VPN 流量计量开关 — 独立保存，不受 saveConfig 全量覆写影响 */
    fun setEnableVpnTraffic(context: Context, enable: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean("enable_vpn_traffic", enable).apply()
    }

    // ── [安全修复] 远程命令执行独立开关 ──────────────────────────────────────
    // 将命令执行权限从 rootMode 中解耦，防止用户启用 Root/Shizuku
    // （用于采集器/终端/文件管理器提权）时静默授予面板远程命令执行能力。
    // 默认 false，需用户在 UI 中显式开启并确认安全警告。

    /** 获取远程命令执行开关状态（默认关闭） */
    fun getEnableRemoteCommand(context: Context): Boolean =
        getEncryptedPrefs(context).getBoolean("enable_remote_command", false)

    /** 远程命令执行开关 — 独立保存，不受 saveConfig 全量覆写影响 */
    fun setEnableRemoteCommand(context: Context, enable: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean("enable_remote_command", enable).apply()
    }

    fun hasValidConfig(context: Context): Boolean {
        return getServer(context).isNotEmpty() && getSecret(context).isNotEmpty() && getUuid(context).isNotEmpty()
    }
}

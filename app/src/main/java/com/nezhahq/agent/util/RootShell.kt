package com.nezhahq.agent.util

import android.content.pm.PackageManager
import java.security.SecureRandom
import rikka.shizuku.Shizuku

/**
 * Thread-safe persistent privileged shell. Commands are serialized through one su/Shizuku
 * process and bounded by a per-command timeout; any framing or IO failure resets the session.
 */
object RootShell {
    private const val RETRY_COOLDOWN_MS = 30_000L
    private const val MARKER_RANDOM_BYTES = 24
    private val markerRandom = SecureRandom()

    private val persistentShell = PersistentShell(
        sessionFactory = ::startSession,
        markerFactory = { ShellMarker(newMarkerToken()) },
        retryCooldownMs = RETRY_COOLDOWN_MS,
        onCommandFailure = { command, exception ->
            if (command == "<start>") {
                Logger.e("RootShell: 建立 Shell 会话失败", exception)
            } else {
                Logger.e("RootShell: 执行命令失败 [$command]，正在重置会话", exception)
            }
        }
    )

    /** Executes [command], returning at most 4 MiB of merged stdout/stderr. */
    fun execute(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): String {
        return persistentShell.execute(command, timeoutMs)
    }

    /** Executes [command] and returns its first non-blank output line. */
    fun executeFirstLine(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): String? {
        return persistentShell.executeFirstLine(command, timeoutMs)
    }

    /** Closes the process and reader executor. A later call to [execute] can start them again. */
    fun shutdown() {
        persistentShell.shutdown()
    }

    fun isAlive(): Boolean = persistentShell.isAlive()

    fun getSessionType(): String? = persistentShell.sessionType()

    /** Tries su first, then an authorized Shizuku shell. */
    private fun startSession(): ShellSession? {
        var temporarySuProcess: Process? = null
        try {
            temporarySuProcess = Runtime.getRuntime().exec("su")
            Thread.sleep(200)
            if (isProcessAlive(temporarySuProcess)) {
                val session = ShellSession.openRedirected(temporarySuProcess, "su")
                temporarySuProcess = null
                Logger.i("RootShell: 持久化 su 会话已建立（Root 模式）。")
                return session
            }
            Logger.i("RootShell: su 进程已退出（可能权限被拒绝），尝试 Shizuku 回退...")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.i("RootShell: su 启动被中断，取消本次高权限 Shell 建立。")
            return null
        } catch (exception: Exception) {
            Logger.i("RootShell: su 命令执行失败（${exception.message}），尝试 Shizuku 回退...")
        } finally {
            temporarySuProcess?.let(ShellSession::destroyProcess)
        }

        try {
            if (!isShizukuAvailable()) {
                Logger.i("RootShell: Shizuku 不可用（未运行或未授权），高权限 Shell 功能不可用。")
                return null
            }

            @Suppress("DEPRECATION")
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh"), null, null) as Process
            return try {
                ShellSession.openRedirected(process, "shizuku").also {
                    Logger.i(
                        "RootShell: 持久化 Shizuku Shell 会话已建立" +
                            "（ADB 模式，UID=${Shizuku.getUid()}）。"
                    )
                }
            } catch (exception: Exception) {
                ShellSession.destroyProcess(process)
                throw exception
            }
        } catch (exception: Exception) {
            Logger.e("RootShell: Shizuku Shell 启动失败，高权限 Shell 功能不可用", exception)
            return null
        }
    }

    private fun isProcessAlive(process: Process): Boolean {
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        } catch (_: IllegalStateException) {
            true
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (exception: Exception) {
            Logger.e("RootShell: 检测 Shizuku 状态时异常", exception)
            false
        }
    }

    private fun newMarkerToken(): String {
        val randomBytes = ByteArray(MARKER_RANDOM_BYTES)
        markerRandom.nextBytes(randomBytes)
        val hex = CharArray(randomBytes.size * 2)
        val alphabet = "0123456789abcdef"
        for (index in randomBytes.indices) {
            val value = randomBytes[index].toInt() and 0xff
            hex[index * 2] = alphabet[value ushr 4]
            hex[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(hex)
    }
}

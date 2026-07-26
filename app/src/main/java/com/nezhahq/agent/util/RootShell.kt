package com.nezhahq.agent.util

import android.content.pm.PackageManager
import com.nezhahq.agent.core.security.PrivilegedAccessController
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

/**
 * Process-wide privileged-shell boundary.
 *
 * Every su/Shizuku entry point checks the process-local authorization controller. Callers may
 * still choose whether a privileged strategy is useful, but they cannot bypass the authorization
 * decision by invoking a lower-level process API.
 */
object RootShell {
    private const val RETRY_COOLDOWN_MS = 30_000L
    private const val MARKER_RANDOM_BYTES = 24
    private val markerRandom = SecureRandom()

    private val managedProcessLock = Any()
    private val managedProcesses = mutableSetOf<ManagedProcess>()

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

    private val accessController = PrivilegedAccessController(
        cleanup = ::closePrivilegedResources
    )

    /** Applies the persisted process-level root authorization state. */
    fun configureAuthorization(enabled: Boolean) {
        if (enabled) {
            accessController.enable()
        } else {
            accessController.disableAndRevoke()
        }
    }

    /** Returns whether privileged operations are currently authorized for this process. */
    fun isAuthorized(): Boolean = accessController.isEnabled()

    /** Executes [command], returning at most 4 MiB of merged stdout/stderr. */
    fun execute(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): String {
        if (!accessController.isEnabled()) return ""
        return persistentShell.execute(command, timeoutMs)
    }

    /** Executes [command] and returns its first non-blank output line. */
    fun executeFirstLine(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): String? {
        return execute(command, timeoutMs).lineSequence().firstOrNull { it.isNotBlank() }
    }

    /**
     * Starts one independently managed interactive privileged shell.
     *
     * The returned process is tracked so [shutdown] or a later authorization denial can revoke it.
     */
    internal fun startInteractiveShell(workingDirectory: File): ManagedProcess? {
        return startManagedShell(workingDirectory)
    }

    /**
     * Opens stdout for one privileged command without buffering the complete result in memory.
     * Closing the stream also destroys and unregisters its backing process.
     */
    internal fun openCommandInputStream(command: String): InputStream? {
        val managed = startManagedShell(workingDirectory = null) ?: return null
        return try {
            managed.process.outputStream.apply {
                write("exec $command\n".toByteArray(Charsets.UTF_8))
                flush()
            }
            ManagedProcessInputStream(managed)
        } catch (exception: Exception) {
            managed.close()
            Logger.e("RootShell: 启动流式高权限命令失败", exception)
            null
        }
    }

    /** Closes the process and reader executor. A later call to [execute] can start them again. */
    fun shutdown() {
        closePrivilegedResources()
    }

    fun isAlive(): Boolean = accessController.isEnabled() && persistentShell.isAlive()

    fun getSessionType(): String? {
        if (!accessController.isEnabled()) return null
        return persistentShell.sessionType()
    }

    /** Tries su first, then an authorized Shizuku shell. */
    private fun startSession(): ShellSession? {
        if (!accessController.isEnabled()) return null
        val launched = startShellProcess(workingDirectory = null) ?: return null
        if (!accessController.isEnabled()) {
            ShellSession.destroyProcess(launched.process)
            return null
        }
        return try {
            ShellSession.openRedirected(launched.process, launched.type)
        } catch (exception: Exception) {
            ShellSession.destroyProcess(launched.process)
            throw exception
        }
    }

    private fun startManagedShell(workingDirectory: File?): ManagedProcess? {
        if (!accessController.isEnabled()) return null
        val launched = startShellProcess(workingDirectory) ?: return null
        if (!accessController.isEnabled()) {
            ShellSession.destroyProcess(launched.process)
            return null
        }

        return try {
            // Shizuku does not expose ProcessBuilder.redirectErrorStream; configure the shell once.
            launched.process.outputStream.apply {
                write("exec 2>&1\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val managed = ManagedProcess(launched.process, launched.type)
            synchronized(managedProcessLock) {
                managedProcesses += managed
            }
            if (!accessController.isEnabled()) {
                managed.close()
                null
            } else {
                managed
            }
        } catch (exception: Exception) {
            ShellSession.destroyProcess(launched.process)
            Logger.e("RootShell: 初始化独立高权限 Shell 失败", exception)
            null
        }
    }

    private fun startShellProcess(workingDirectory: File?): LaunchedShell? {
        if (!accessController.isEnabled()) return null
        var temporarySuProcess: Process? = null
        try {
            temporarySuProcess = ProcessBuilder("su")
                .redirectErrorStream(true)
                .apply {
                    if (workingDirectory != null) directory(workingDirectory)
                }
                .start()
            Thread.sleep(200)
            if (isProcessAlive(temporarySuProcess)) {
                val launched = LaunchedShell(temporarySuProcess, "su")
                temporarySuProcess = null
                Logger.i("RootShell: su Shell 已建立（Root 模式）。")
                return launched
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
            val process = method.invoke(
                null,
                arrayOf("sh"),
                null,
                workingDirectory?.absolutePath
            ) as Process
            Logger.i("RootShell: Shizuku Shell 已建立（ADB 模式，UID=${Shizuku.getUid()}）。")
            return LaunchedShell(process, "shizuku")
        } catch (exception: Exception) {
            Logger.e("RootShell: Shizuku Shell 启动失败，高权限 Shell 功能不可用", exception)
            return null
        }
    }

    private fun closePrivilegedResources() {
        persistentShell.shutdown()
        val processes = synchronized(managedProcessLock) {
            managedProcesses.toList().also { managedProcesses.clear() }
        }
        processes.forEach(ManagedProcess::forceClose)
    }

    private fun unregister(managedProcess: ManagedProcess) {
        synchronized(managedProcessLock) {
            managedProcesses -= managedProcess
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

    private data class LaunchedShell(
        val process: Process,
        val type: String
    )

    internal class ManagedProcess internal constructor(
        val process: Process,
        val type: String
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            unregister(this)
            ShellSession.destroyProcess(process)
        }

        internal fun forceClose() {
            if (!closed.compareAndSet(false, true)) return
            ShellSession.destroyProcess(process)
        }
    }

    private class ManagedProcessInputStream(
        private val managedProcess: ManagedProcess
    ) : InputStream() {
        private val delegate = managedProcess.process.inputStream

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray): Int = delegate.read(buffer)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun available(): Int = delegate.available()

        override fun close() {
            managedProcess.close()
        }
    }
}

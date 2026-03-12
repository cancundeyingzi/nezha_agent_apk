package com.nezhahq.agent.util

import com.nezhahq.agent.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 持久化 Root Shell 会话管理器（单例）。
 *
 * ## 解决的核心问题
 * 传统的 `Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd"))` 每次调用
 * 都会 fork 一个新进程，在探针每 2 秒采集一次数据的场景下，这会造成：
 *  - 严重的内存抖动与 CPU 突增（探针自身把 CPU 吃满）
 *  - 急剧的电量损耗
 *
 * ## 解决方案
 * 维护一个长生命周期的 `su` 进程（shell 会话），所有 root 命令通过
 * stdin 写入，输出从 stdout 持续读取，避免重复 fork 的巨大开销。
 *
 * ## 通信协议
 * 利用 Shell 的顺序执行特性：在每条命令后追加 `echo __SENTINEL__`，
 * 当读取到哨兵行时，认为本次命令输出已完整接收。
 *
 * ## 线程安全
 * 所有公开方法通过 [ReentrantLock] 保护，可安全地在多个协程中调用。
 *
 * ## 生命周期
 * 应在 Service.onDestroy() 中调用 [shutdown] 以释放 su 进程资源。
 */
object RootShell {

    /** 哨兵字符串，用于标识单次命令输出的结束边界。 */
    private const val SENTINEL = "__NEZHA_CMD_DONE_7F3A__"

    private val lock = ReentrantLock()

    @Volatile private var process: Process? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var reader: BufferedReader? = null

    // ──────────────────────────────────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 通过持久 shell 执行一条命令，返回其完整标准输出。
     *
     * 内部自动检测 su 进程是否存活，若已死亡则重新建立会话。
     *
     * @param command 要执行的 shell 命令（无需 `su -c` 前缀）
     * @return 命令的完整标准输出字符串，失败时返回空字符串
     */
    fun execute(command: String): String {
        return lock.withLock {
            try {
                ensureAlive()
                val w = writer ?: return@withLock ""
                val r = reader ?: return@withLock ""

                // 写命令 + 哨兵，flush 确保数据立即送入 su 进程
                w.println(command)
                w.println("echo $SENTINEL")
                w.flush()

                // 读取输出直到遇到哨兵行
                val sb = StringBuilder()
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    if (line == SENTINEL) break
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }
                sb.toString()
            } catch (e: Exception) {
                Logger.e("RootShell: 执行命令失败 [$command]，正在重置会话", e)
                // 进程可能已崩溃，销毁以便下次调用时重建
                destroyInternal()
                ""
            }
        }
    }

    /**
     * 通过持久 shell 执行命令，只返回第一个非空行。
     *
     * 适用于只需要单行结果的命令（如 `head -n 1 /proc/stat`、`wc -l`）。
     *
     * @param command 要执行的 shell 命令
     * @return 输出的第一个非空行，失败时返回 null
     */
    fun executeFirstLine(command: String): String? {
        return execute(command).lineSequence().firstOrNull { it.isNotBlank() }
    }

    /**
     * 关闭持久 su 会话，释放所有相关资源。
     *
     * **必须**在 Service.onDestroy() 中调用，以防止 su 进程泄漏。
     */
    fun shutdown() {
        lock.withLock { destroyInternal() }
    }

    /**
     * 返回当前 su 会话是否处于活跃状态（用于调试/日志）。
     */
    fun isAlive(): Boolean {
        return lock.withLock {
            try {
                val p = process ?: return@withLock false
                p.exitValue() // 若进程仍在运行，此调用抛出 IllegalThreadStateException
                false         // 如果没抛出，说明进程已退出
            } catch (e: IllegalThreadStateException) {
                true          // 进程仍在运行
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有实现
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 检测 su 进程是否存活，若未启动或已退出则重新建立。
     * **必须在持有 [lock] 时调用。**
     */
    private fun ensureAlive() {
        val alive = try {
            val p = process ?: run { startInternal(); return }
            p.exitValue()  // 正常完成 = 已退出
            false
        } catch (e: IllegalThreadStateException) {
            true           // 抛出 IllegalThreadStateException = 进程仍在运行
        }

        if (!alive) {
            Logger.i("RootShell: su 会话已退出，正在重新建立...")
            destroyInternal()
            startInternal()
        }
    }

    /**
     * 启动新的 su 进程并初始化 IO 流读写器。
     * **必须在持有 [lock] 时调用。**
     */
    private fun startInternal() {
        try {
            val p = Runtime.getRuntime().exec("su")
            process = p
            // autoFlush=true：每次 println 后自动 flush，避免命令滞留在缓冲区
            writer = PrintWriter(p.outputStream.bufferedWriter(), true)
            reader = BufferedReader(InputStreamReader(p.inputStream))
            Logger.i("RootShell: 持久化 su 会话已建立（PID 位于 su 进程内）。")
        } catch (e: Exception) {
            Logger.e("RootShell: 无法启动 su 进程，Root 功能将不可用", e)
            process = null; writer = null; reader = null
        }
    }

    /**
     * 销毁当前 su 进程及相关 IO 流，忽略所有关闭时的异常。
     * **必须在持有 [lock] 时调用。**
     */
    private fun destroyInternal() {
        // 优雅退出：先发送 exit 命令，再强制销毁
        try { writer?.println("exit"); writer?.flush() } catch (_: Exception) {}
        try { writer?.close() }  catch (_: Exception) {}
        try { reader?.close() }  catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null; writer = null; reader = null
    }
}

package com.nezhahq.agent.util

import android.content.pm.PackageManager
import com.nezhahq.agent.util.Logger
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 持久化高权限 Shell 会话管理器（单例）。
 *
 * ## 解决的核心问题
 * 传统的 `Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd"))` 每次调用
 * 都会 fork 一个新进程，在探针每 2 秒采集一次数据的场景下，这会造成：
 *  - 严重的内存抖动与 CPU 突增（探针自身把 CPU 吃满）
 *  - 急剧的电量损耗
 *
 * ## 解决方案
 * 维护一个长生命周期的高权限 Shell 进程（会话），所有命令通过
 * stdin 写入，输出从 stdout 持续读取，避免重复 fork 的巨大开销。
 *
 * ## Shell 会话建立策略（三级回退）
 * 1. **Root 模式**：首先尝试 `su` 启动 Root Shell
 * 2. **Shizuku 模式**：`su` 失败后，检测 Shizuku 是否活跃且已获授权，
 *    若满足则使用 `Shizuku.newProcess(arrayOf("sh"), null, null)`
 *    启动 ADB 级别的 Shell 会话
 * 3. **均失败**：打印日志警告，Shell 功能不可用
 *
 * ## 通信协议
 * 利用 Shell 的顺序执行特性：在每条命令后追加 `echo __SENTINEL__`，
 * 当读取到哨兵行时，认为本次命令输出已完整接收。
 *
 * ## 线程安全
 * 所有公开方法通过 [ReentrantLock] 保护，可安全地在多个协程中调用。
 *
 * ## 生命周期
 * 应在 Service.onDestroy() 中调用 [shutdown] 以释放 Shell 进程资源。
 */
object RootShell {

    /** 哨兵字符串，用于标识单次命令输出的结束边界。 */
    private const val SENTINEL = "__NEZHA_CMD_DONE_7F3A__"

    private val lock = ReentrantLock()

    @Volatile private var process: Process? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var reader: BufferedReader? = null

    /**
     * 当前会话类型标识，用于日志区分。
     * - "su"       : Root Shell
     * - "shizuku"  : Shizuku ADB Shell
     * - null       : 未建立会话
     */
    @Volatile private var sessionType: String? = null

    /**
     * 上次 startInternal 尝试失败的时间戳（毫秒），用于节流重试。
     * 当 su 和 Shizuku 都不可用时，避免每 2 秒的采集循环中反复探测，
     * 改为每 30 秒最多尝试一次。
     */
    @Volatile private var lastStartFailedMs: Long = 0L

    /** 重试冷却时间：30 秒。 */
    private const val RETRY_COOLDOWN_MS = 30_000L

    // ──────────────────────────────────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 通过持久 shell 执行一条命令，返回其完整标准输出。
     *
     * 内部自动检测 Shell 进程是否存活，若已死亡则重新建立会话。
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

                // 写命令 + 哨兵，flush 确保数据立即送入 Shell 进程
                w.println(command)
                w.println("echo $SENTINEL")
                w.flush()

                // 读取输出直到遇到哨兵行。
                // 注意：由于部分 sysfs 文件内容结尾没有换行符（各家设备 ROM 实现存在差异），
                // 此时 w.println("echo $SENTINEL") 写入的哨兵会直接粘连在最后一行数据后面，
                // 如 "0-7__NEZHA_CMD_DONE_7F3A__"，导致 line == SENTINEL 永远不成立并死锁！
                // 必须改用 endsWith 匹配，并剔除残余的哨兵后缀还原真实数据。
                val sb = StringBuilder()
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    if (line!!.endsWith(SENTINEL)) {
                        val realData = line!!.removeSuffix(SENTINEL)
                        if (realData.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(realData)
                        }
                        break
                    }
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
     * 关闭持久 Shell 会话，释放所有相关资源。
     *
     * **必须**在 Service.onDestroy() 中调用，以防止 Shell 进程泄漏。
     */
    fun shutdown() {
        lock.withLock { destroyInternal() }
    }

    /**
     * 返回当前 Shell 会话是否处于活跃状态（用于调试/日志）。
     */
    fun isAlive(): Boolean {
        return lock.withLock {
            try {
                val p = process ?: return@withLock false
                p.exitValue() // 若进程仍在运行，此调用可能抛出 IllegalThreadStateException 或 IllegalStateException
                false         // 如果没抛出，说明进程已退出
            } catch (e: Exception) {
                true          // 进程仍在运行
            }
        }
    }

    /**
     * 返回当前 Shell 会话的类型（"su" / "shizuku" / null）。
     * 用于 UI 层或日志展示当前使用的提权方式。
     */
    fun getSessionType(): String? {
        return sessionType
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有实现
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 检测 Shell 进程是否存活，若未启动或已退出则重新建立。
     * **必须在持有 [lock] 时调用。**
     */
    private fun ensureAlive() {
        val alive = try {
            val p = process ?: run {
                // 节流：如果上次尝试建立会话失败（su+Shizuku 都不可用），
                // 冷却期内不再重复尝试，避免每 2 秒采集循环中的无意义探测
                val now = System.currentTimeMillis()
                if (lastStartFailedMs > 0L && (now - lastStartFailedMs) < RETRY_COOLDOWN_MS) {
                    return  // 冷却期内，直接跳过，execute() 会返回空字符串
                }
                startInternal()
                return
            }
            p.exitValue()  // 正常完成 = 已退出
            false
        } catch (e: Exception) {
            true           // 抛出 Exception (如 IllegalStateException) = 进程仍在运行
        }

        if (!alive) {
            Logger.i("RootShell: Shell 会话已退出，正在重新建立...")
            destroyInternal()
            startInternal()
        }
    }

    /**
     * 启动新的高权限 Shell 进程并初始化 IO 流读写器。
     *
     * ## 三级回退策略
     * 1. 首先尝试通过 `Runtime.exec("su")` 启动 Root Shell
     * 2. 若 su 失败（非 Root 设备），检测 Shizuku 服务是否存活且已授权：
     *    - Shizuku 可用 → 使用 `Shizuku.newProcess(arrayOf("sh"), null, null)`
     *      启动 ADB 级别的 Shell（UID 2000）
     * 3. 若 Shizuku 也不可用 → 打日志警告，会话不建立
     *
     * **必须在持有 [lock] 时调用。**
     */
    private fun startInternal() {
        // ── 第一步：尝试 su ──────────────────────────────────────────────
        try {
            val p = Runtime.getRuntime().exec("su")
            // 立即验证 su 进程是否真的存活（某些设备 exec("su") 不抛异常但进程秒退）
            Thread.sleep(200) // 短暂等待让 su 进程有时间初始化或者退出
            try {
                p.exitValue()
                // 能拿到 exitValue 说明 su 已退出（被拒绝或不存在），需要继续尝试 Shizuku
                Logger.i("RootShell: su 进程已退出（可能权限被拒绝），尝试 Shizuku 回退...")
            } catch (e: IllegalThreadStateException) {
                // 抛出 IllegalThreadStateException 说明进程正在运行 → su 成功！
                process = p
                writer = PrintWriter(p.outputStream.bufferedWriter(), true)
                reader = BufferedReader(InputStreamReader(p.inputStream))
                sessionType = "su"
                lastStartFailedMs = 0L  // 成功建立会话，重置失败时间戳
                Logger.i("RootShell: 持久化 su 会话已建立（Root 模式）。")
                return
            }
        } catch (e: Exception) {
            Logger.i("RootShell: su 命令执行失败（${e.message}），尝试 Shizuku 回退...")
        }

        // ── 第二步：尝试 Shizuku ─────────────────────────────────────────
        try {
            if (isShizukuAvailable()) {
                // Shizuku.newProcess 在 Shizuku 13.1.5 中仍然可用，
                // 该方法通过 Shizuku 服务以 ADB Shell (UID 2000) 身份启动 sh 进程。
                // 注意：官方计划在未来版本中废弃 newProcess，届时需迁移至 UserService。
                @Suppress("DEPRECATION")
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val p = method.invoke(null, arrayOf("sh"), null, null) as Process
                process = p
                writer = PrintWriter(p.outputStream.bufferedWriter(), true)
                reader = BufferedReader(InputStreamReader(p.inputStream))
                sessionType = "shizuku"
                lastStartFailedMs = 0L  // 成功建立会话，重置失败时间戳
                Logger.i("RootShell: 持久化 Shizuku Shell 会话已建立（ADB 模式，UID=${Shizuku.getUid()}）。")
                return
            } else {
                Logger.i("RootShell: Shizuku 不可用（未运行或未授权），高权限 Shell 功能不可用。")
            }
        } catch (e: Exception) {
            Logger.e("RootShell: Shizuku Shell 启动失败，高权限 Shell 功能不可用", e)
        }

        // ── 第三步：均失败 ──────────────────────────────────────────────
        process = null; writer = null; reader = null; sessionType = null
        lastStartFailedMs = System.currentTimeMillis()  // 记录失败时间戳，触发冷却期
    }

    /**
     * 检测 Shizuku 服务是否活跃且本应用已获得使用权限。
     *
     * ## 检查逻辑
     * 1. `Shizuku.pingBinder()` — 测试 Shizuku 服务是否存活
     * 2. `Shizuku.checkSelfPermission()` — 检查本应用是否已被用户授权
     * 3. `!Shizuku.isPreV11()` — 确保 Shizuku 版本 >= v11（旧版 API 不支持）
     *
     * @return true 如果 Shizuku 可用且已授权
     */
    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            // Shizuku 未安装或 ContentProvider 不可达时可能抛出异常
            Logger.e("RootShell: 检测 Shizuku 状态时异常", e)
            false
        }
    }

    /**
     * 销毁当前 Shell 进程及相关 IO 流，忽略所有关闭时的异常。
     * **必须在持有 [lock] 时调用。**
     */
    private fun destroyInternal() {
        // 优雅退出：先发送 exit 命令，再强制销毁
        try { writer?.println("exit"); writer?.flush() } catch (_: Exception) {}
        try { writer?.close() }  catch (_: Exception) {}
        try { reader?.close() }  catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null; writer = null; reader = null; sessionType = null
    }
}

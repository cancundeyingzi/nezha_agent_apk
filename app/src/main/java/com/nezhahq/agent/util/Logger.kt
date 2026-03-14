package com.nezhahq.agent.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局日志管理器（单例），为 UI 层提供日志流，同时写入 Android Log。
 *
 * ## 去重/节流机制
 * 同一消息在 [DEDUP_WINDOW_MS] 毫秒内重复出现时，不再追加新条目，
 * 而是在原条目后面更新 "×N" 重复计数。此机制可有效防止高频采集循环中
 * 重复错误日志填满缓冲区（如每 2 秒一次的 Root 权限失败），
 * 保护真正重要的运行信息不被冲掉。
 *
 * ## 线程安全
 * 所有状态更新通过 [synchronized] 保护，可在任意线程安全调用。
 *
 * ## 缓冲区策略
 * 最多保留 [MAX_LOG_SIZE] 条日志。超出时移除最早的条目（FIFO）。
 */
object Logger {

    /** 日志缓冲区最大容量。 */
    private const val MAX_LOG_SIZE = 200

    /**
     * 去重窗口时间（毫秒）。
     * 同一条消息在此时间窗口内重复出现时，仅更新计数而不追加新条目。
     * 设置为 30 秒：覆盖 SystemStateCollector 的 2 秒采集周期 × 15 次，
     * 足以抑制绝大多数高频重复日志。
     */
    private const val DEDUP_WINDOW_MS = 30_000L

    // ── StateFlow：供 Compose UI 层以 List<String> 形式观察 ──

    private val _logs = MutableStateFlow<List<String>>(listOf("System Logger Initialized."))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // ── 内部可变列表（真正的数据源） ──

    /** 实际日志存储列表（通过 synchronized 保护并发访问）。 */
    private val logList = mutableListOf("System Logger Initialized.")

    // ── 去重状态 ──

    /**
     * 上一条日志的原始消息文本（不含时间戳和重复计数）。
     * 用于与新消息比对以判断是否属于重复日志。
     */
    private var lastRawMessage: String = ""

    /** 当前重复消息的连续出现次数（首次出现时为 1）。 */
    private var lastRepeatCount: Int = 0

    /** 上一条日志的写入时间戳（System.currentTimeMillis()），用于判断去重窗口。 */
    private var lastLogTimeMs: Long = 0L

    // ── 时间格式化 ──

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ══════════════════════════════════════════════════════════════════════════
    // 公开 API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 记录 INFO 级别日志。
     *
     * @param message 日志消息文本
     */
    fun i(message: String) {
        // SimpleDateFormat 非线程安全，需在 synchronized 中格式化时间
        val formattedLog = synchronized(this) {
            val time = dateFormat.format(Date())
            "[$time] $message"
        }
        addLogWithDedup(formattedLog, message)
        android.util.Log.i("NezhaAgent", message)
    }

    /**
     * 记录 ERROR 级别日志。
     *
     * @param message 日志消息文本
     * @param throwable 可选的异常对象（其 message 会附加到日志末尾）
     */
    fun e(message: String, throwable: Throwable? = null) {
        val err = throwable?.let { " - ${it.message ?: it.javaClass.simpleName}" } ?: ""
        // SimpleDateFormat 非线程安全，需在 synchronized 中格式化时间
        val formattedLog = synchronized(this) {
            val time = dateFormat.format(Date())
            "[$time] ERROR: $message$err"
        }
        // 去重仅基于主消息，忽略异常详情（同一个方法产生的异常消息通常相同）
        addLogWithDedup(formattedLog, message)
        android.util.Log.e("NezhaAgent", message, throwable)
    }

    /**
     * 获取所有日志的完整拼接字符串（用于复制到剪贴板等一次性场景）。
     *
     * 注意：此方法会创建一个新的大字符串，不应在高频渲染路径中调用。
     */
    fun getLogString(): String {
        synchronized(this) {
            return logList.joinToString("\n")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 内部实现
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 带去重/节流逻辑的日志追加。
     *
     * ## 去重规则
     * 如果新消息与上一条消息的 [rawMessage] 完全相同，且在 [DEDUP_WINDOW_MS] 窗口内：
     * - **不追加新条目**，而是更新最后一条日志的显示文本，附加 " ×N" 重复计数
     * - 重复计数持续累加，直到窗口超时或出现不同消息
     *
     * ## 窗口超时
     * 当同一消息超过 [DEDUP_WINDOW_MS] 仍在重复时，视为"新一轮重复"，
     * 追加一条新条目并重置计数。这避免了某条日志永远不出现在列表底部的问题。
     *
     * @param formattedLog 带时间戳的完整日志字符串（展示用）
     * @param rawMessage 不含时间戳的原始消息（用于去重比对）
     */
    private fun addLogWithDedup(formattedLog: String, rawMessage: String) {
        val now = System.currentTimeMillis()

        synchronized(this) {
            // 判断是否属于重复消息（消息相同且在去重窗口内）
            val isDuplicate = rawMessage == lastRawMessage
                    && (now - lastLogTimeMs) < DEDUP_WINDOW_MS

            if (isDuplicate && logList.isNotEmpty()) {
                // ── 重复消息：更新最后一条的重复计数 ──
                lastRepeatCount++
                lastLogTimeMs = now
                // 替换最后一条日志为带有重复计数的版本
                val lastIndex = logList.lastIndex
                logList[lastIndex] = "$formattedLog ×$lastRepeatCount"
            } else {
                // ── 新消息或窗口已过期：追加新条目 ──
                lastRawMessage = rawMessage
                lastRepeatCount = 1
                lastLogTimeMs = now
                logList.add(formattedLog)
                // 超出缓冲区则移除最旧的条目
                if (logList.size > MAX_LOG_SIZE) {
                    logList.removeAt(0)
                }
            }

            // 发布新的不可变快照到 StateFlow（触发 Compose 重组）
            _logs.value = logList.toList()
        }
    }
}

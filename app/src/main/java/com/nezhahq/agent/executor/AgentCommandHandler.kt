package com.nezhahq.agent.executor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.nezhahq.agent.util.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 虚拟指令处理器（Virtual Command Handler）。
 *
 * ## 设计思路
 * 对从 Dashboard 终端传入的 `@agent <subcommand>` 指令进行路由分发，
 * 将结果以格式化文本返回到终端输出流。
 *
 * ## 安全设计
 * - 短信读取前先检查 `READ_SMS` 运行时权限，未授权则返回友好提示
 * - 所有数据仅在内存中处理，**绝不写入磁盘或 Logcat**
 * - 查询结果通过 ContentResolver 获取后直接格式化，不做任何持久化
 *
 * ## 扩展性
 * 新增指令只需在 [execute] 的 `when` 分支中添加即可，
 * 后续可扩展 `@agent battery`、`@agent clipboard` 等指令。
 *
 * @param context Android 上下文，用于 ContentResolver 和权限检查
 */
class AgentCommandHandler(private val context: Context) {

    /**
     * 执行虚拟指令并返回格式化的终端输出文本。
     *
     * @param subcommand `@agent` 后的子指令（已 trim），例如 "sms"、"help"
     * @return 终端输出文本，包含换行符
     */
    fun execute(subcommand: String): String {
        return when (subcommand.lowercase(Locale.ROOT)) {
            "" , "help" -> executeHelp()
            "sms" -> executeSms()
            else -> "❌ 未知指令: @agent $subcommand\r\n输入 @agent help 查看可用指令列表。\r\n"
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 指令实现
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 显示帮助信息。
     */
    private fun executeHelp(): String {
        return buildString {
            append("\r\n")
            append("╔══════════════════════════════════════╗\r\n")
            append("║     @agent 虚拟指令系统 v1.0        ║\r\n")
            append("╠══════════════════════════════════════╣\r\n")
            append("║  @agent help   显示此帮助信息       ║\r\n")
            append("║  @agent sms    查看最近 5 条短信    ║\r\n")
            append("╚══════════════════════════════════════╝\r\n")
            append("\r\n")
            append("提示: 所有其他输入将作为标准 Shell 命令执行。\r\n")
            append("\r\n")
        }
    }

    /**
     * 读取最近 5 条短信。
     *
     * ## 安全流程
     * 1. 检查 READ_SMS 权限 → 未授权则返回提示
     * 2. 通过 ContentResolver 查询 content://sms/inbox
     * 3. 结果仅在 StringBuilder 中组装，不落盘
     * 4. 限制最多 5 条，防止大量数据占用终端
     */
    private fun executeSms(): String {
        // ── 权限检查 ──
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return buildString {
                append("\r\n")
                append("⚠️ 短信权限未授予\r\n")
                append("请在设备的「设置 → 应用 → 哪吒探针 → 权限」中\r\n")
                append("手动开启「短信」权限后重试。\r\n")
                append("\r\n")
            }
        }

        // ── 查询短信 ──
        return try {
            val smsUri: Uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,   // 发件人号码
                Telephony.Sms.BODY,      // 短信内容
                Telephony.Sms.DATE       // 接收时间
            )
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val cursor: Cursor? = context.contentResolver.query(
                smsUri, projection, null, null, sortOrder
            )

            if (cursor == null) {
                return "❌ 无法查询短信数据库\r\n"
            }

            cursor.use { c ->
                if (c.count == 0) {
                    return "📭 收件箱为空，没有找到短信。\r\n"
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val addressIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)

                buildString {
                    append("\r\n")
                    append("📱 最近 5 条短信 (收件箱)\r\n")
                    append("═══════════════════════════════════════\r\n")

                    var count = 0
                    while (c.moveToNext() && count < 5) {
                        count++
                        val address = c.getString(addressIdx) ?: "未知号码"
                        val body = c.getString(bodyIdx) ?: ""
                        val dateMs = c.getLong(dateIdx)
                        val dateStr = dateFormat.format(Date(dateMs))

                        // 截断超长短信内容，防止刷屏
                        val truncatedBody = if (body.length > 100) {
                            body.substring(0, 100) + "..."
                        } else {
                            body
                        }

                        append("[$count] $address\r\n")
                        append("    时间: $dateStr\r\n")
                        append("    内容: $truncatedBody\r\n")
                        append("───────────────────────────────────────\r\n")
                    }
                    append("共显示 $count 条短信\r\n")
                    append("\r\n")
                }
            }
        } catch (e: Exception) {
            Logger.e("AgentCommandHandler: 短信查询失败", e)
            "❌ 短信查询失败: ${e.message}\r\n"
        }
    }
}

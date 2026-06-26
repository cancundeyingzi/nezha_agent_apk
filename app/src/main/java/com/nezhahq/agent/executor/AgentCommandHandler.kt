package com.nezhahq.agent.executor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.nezhahq.agent.service.KeepAliveAccessibilityService
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 虚拟指令处理器（Virtual Command Handler）。
 *
 * ## 设计思路
 * 对从 Dashboard 终端传入的 `@agent <subcommand>` 指令进行路由分发，
 * 将结果以格式化文本返回到终端输出流。
 *
 * ## 安全设计
 * - 短信读取前先检查 `READ_SMS` 运行时权限，未授权则返回友好提示
 * - 短信查询结果仅在内存中处理，**绝不写入磁盘或 Logcat**
 * - 截图指令只写入用户指定路径，默认 `/sdcard`，并优先使用已授权的无障碍服务
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
    suspend fun execute(subcommand: String): String {
        val normalized = subcommand.trim()
        val commandToken = normalized.substringBefore(' ', normalized)
        val command = commandToken.lowercase(Locale.ROOT)
        val args = normalized.drop(commandToken.length).trim()

        return when (command) {
            "" , "help" -> executeHelp()
            "sms" -> executeSms()
            "screenshot", "screencap", "screen", "截图", "截屏" -> executeScreenshot(args)
            else -> "❌ 未知指令: @agent $normalized\r\n输入 @agent help 查看可用指令列表。\r\n"
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
            append("╔═════════════════════════════════════╗\r\n")
            append("║     @agent 虚拟指令系统 v1.0        ║\r\n")
            append("╠═════════════════════════════════════╣\r\n")
            append("║  @agent help   显示此帮助信息       ║\r\n")
            append("║  @agent sms    查看最近 5 条短信    ║\r\n")
            append("║  @agent screenshot [路径] 保存截图  ║\r\n")
            append("╚═════════════════════════════════════╝\r\n")
            append("\r\n")
            append("截图默认保存到 /sdcard，例如 /sdcard/nezha_screenshot_20260626_120000.png\r\n")
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

    /**
     * 保存当前屏幕截图，默认落盘到 /sdcard。
     *
     * 优先使用 Android 11+ 无障碍截图 API；当无障碍不可用时，如果用户已开启
     * Root/Shizuku 高权限模式，则回退到系统 screencap 命令。
     */
    private suspend fun executeScreenshot(pathArg: String): String {
        val targetPath = normalizeScreenshotPath(pathArg)

        when (val result = KeepAliveAccessibilityService.saveScreenshot(targetPath)) {
            is KeepAliveAccessibilityService.ScreenshotSaveResult.Success -> {
                Logger.i("AgentCommandHandler: 无障碍截图已保存: ${result.path}")
                return formatScreenshotSuccess(result.path, result.bytes, "无障碍服务")
            }
            is KeepAliveAccessibilityService.ScreenshotSaveResult.Failure -> {
                Logger.i("AgentCommandHandler: 无障碍截图不可用，准备尝试高权限兜底: ${result.reason}")
                val rootMode = ConfigStore.getRootMode(context)
                if (!rootMode) {
                    return buildString {
                        append("\r\n")
                        append("❌ 截图保存失败\r\n")
                        append("目标路径: $targetPath\r\n")
                        append("原因: ${result.reason}\r\n")
                        append("\r\n")
                        append("请确认无障碍服务已启用、所有文件访问可写，或开启 Root/Shizuku 高权限模式后重试。\r\n")
                        append("\r\n")
                    }
                }

                return when (val shellResult = captureScreenshotViaShell(targetPath)) {
                    is ShellScreenshotResult.Success -> {
                        Logger.i("AgentCommandHandler: screencap 截图已保存: ${shellResult.path}")
                        formatScreenshotSuccess(shellResult.path, shellResult.bytes, "Root/Shizuku screencap")
                    }
                    is ShellScreenshotResult.Failure -> buildString {
                        append("\r\n")
                        append("❌ 截图保存失败\r\n")
                        append("目标路径: $targetPath\r\n")
                        append("无障碍: ${result.reason}\r\n")
                        append("高权限: ${shellResult.reason}\r\n")
                        append("\r\n")
                    }
                }
            }
        }
    }

    private suspend fun captureScreenshotViaShell(targetPath: String): ShellScreenshotResult =
        withContext(Dispatchers.IO) {
            val parent = File(targetPath).parent?.takeIf { it.isNotBlank() } ?: defaultScreenshotDir()
            val escapedParent = shellEscape(parent)
            val escapedTarget = shellEscape(targetPath)
            val command = """
                mkdir -p $escapedParent 2>&1
                screencap -p $escapedTarget 2>&1
                status=${'$'}?
                if [ "${'$'}status" -eq 0 ] && [ -s $escapedTarget ]; then
                    chmod 666 $escapedTarget 2>/dev/null
                    bytes=${'$'}(wc -c < $escapedTarget 2>/dev/null | tr -d ' ')
                    echo "$SHELL_SUCCESS_MARKER${'$'}bytes"
                else
                    echo "$SHELL_FAILURE_MARKER${'$'}status"
                fi
            """.trimIndent()

            val output = RootShell.execute(command)
            if (output.isBlank()) {
                return@withContext ShellScreenshotResult.Failure("Root/Shizuku shell 不可用或无输出")
            }

            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val successLine = lines.lastOrNull { it.startsWith(SHELL_SUCCESS_MARKER) }
            if (successLine != null) {
                val bytes = successLine.removePrefix(SHELL_SUCCESS_MARKER).toLongOrNull() ?: 0L
                if (bytes > 0L) {
                    return@withContext ShellScreenshotResult.Success(targetPath, bytes)
                }
                return@withContext ShellScreenshotResult.Failure("screencap 已返回成功，但文件为空")
            }

            val failureLine = lines.lastOrNull { it.startsWith(SHELL_FAILURE_MARKER) }
            val exitCode = failureLine?.removePrefix(SHELL_FAILURE_MARKER)?.ifBlank { "未知" } ?: "未知"
            val detail = lines
                .filterNot {
                    it.startsWith(SHELL_SUCCESS_MARKER) || it.startsWith(SHELL_FAILURE_MARKER)
                }
                .joinToString("\n")
                .ifBlank { "无详细错误输出" }

            ShellScreenshotResult.Failure("screencap 退出码 $exitCode: $detail")
        }

    private fun normalizeScreenshotPath(pathArg: String): String {
        val rawPath = pathArg.stripWrappingQuotes()
        val generatedName = "nezha_screenshot_${screenshotDateFormat.format(Date())}.png"
        val defaultDir = defaultScreenshotDir()
        if (rawPath.isBlank()) return "$defaultDir/$generatedName"

        val normalized = rawPath.replace('\\', '/')
        if (normalized.endsWith("/")) return normalized + generatedName

        return if (File(normalized).isAbsolute) {
            normalized
        } else {
            "$defaultDir/$normalized"
        }
    }

    private fun defaultScreenshotDir(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    private fun formatScreenshotSuccess(path: String, bytes: Long, method: String): String {
        return buildString {
            append("\r\n")
            append("✅ 截图已保存\r\n")
            append("路径: $path\r\n")
            append("大小: $bytes bytes\r\n")
            append("方式: $method\r\n")
            append("\r\n")
        }
    }

    private fun String.stripWrappingQuotes(): String {
        val value = trim()
        if (value.length < 2) return value
        val first = value.first()
        val last = value.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            value.substring(1, value.length - 1).trim()
        } else {
            value
        }
    }

    private fun shellEscape(input: String): String {
        return "'" + input.replace("'", "'\\''") + "'"
    }

    private sealed class ShellScreenshotResult {
        data class Success(val path: String, val bytes: Long) : ShellScreenshotResult()
        data class Failure(val reason: String) : ShellScreenshotResult()
    }

    private companion object {
        const val SHELL_SUCCESS_MARKER = "__NEZHA_SCREENSHOT_OK__:"
        const val SHELL_FAILURE_MARKER = "__NEZHA_SCREENSHOT_FAIL__:"

        val screenshotDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}

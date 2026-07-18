package com.nezhahq.agent

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nezhahq.agent.collector.SystemStateCollector
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.grpc.GrpcManager
import com.nezhahq.agent.service.AgentService
import com.nezhahq.agent.simulator.GrpcSimulatedDeviceReporter
import com.nezhahq.agent.simulator.SimulatedDeviceConfig
import com.nezhahq.agent.simulator.SimulatedDeviceLoop
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.StorageStatus
import com.nezhahq.agent.util.VpnTrafficCompatibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 主界面 ViewModel（MVVM 架构核心）。
 *
 * ## 设计目标
 * 将 MainActivity 中原先 800+ 行的业务逻辑全部剥离到此 ViewModel：
 * - 配置字段的读写与持久化
 * - Shizuku 权限检测与请求
 * - gRPC 连接状态收集
 * - 即时测试采集逻辑
 * - 服务启停控制
 *
 * ## 旋转安全
 * ViewModel 绑定到 Activity 的 ViewModelStore，配置变更（屏幕旋转）时状态不丢失。
 *
 * ## 线程安全
 * 所有 IO/CPU 操作在 viewModelScope 中通过协程调度执行，UI 层仅观察 State。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    var storageStatus by mutableStateOf(ConfigStore.initialize(application))
        private set

    val isSecureStorageAvailable: Boolean
        get() = storageStatus != StorageStatus.UNAVAILABLE

    var isResettingSecureStorage by mutableStateOf(false)
        private set

    var storageErrorMessage by mutableStateOf(
        if (storageStatus == StorageStatus.UNAVAILABLE) SECURE_STORAGE_UNAVAILABLE_MESSAGE else null
    )
        private set

    init {
        android.util.Log.i("LiquidGlass", "====== MainViewModel 开始初始化 ======")
        android.util.Log.i("LiquidGlass", "  application = $application")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 配置字段状态（Compose State，驱动 UI 重组）
    // ══════════════════════════════════════════════════════════════════════════

    /** 服务端 IP 或域名 */
    var server by mutableStateOf(run {
        android.util.Log.i("LiquidGlass", "  → 读取 server 配置")
        val value = ConfigStore.getServer(application)
        android.util.Log.i("LiquidGlass", "  ✓ server = $value")
        value
    })
    /** gRPC 端口 */
    var port by mutableStateOf(run {
        android.util.Log.i("LiquidGlass", "  → 读取 port 配置")
        val value = ConfigStore.getPort(application).toString()
        android.util.Log.i("LiquidGlass", "  ✓ port = $value")
        value
    })
    /** 客户端密钥 */
    var secret by mutableStateOf(run {
        android.util.Log.i("LiquidGlass", "  → 读取 secret 配置")
        val value = ConfigStore.getSecret(application)
        android.util.Log.i("LiquidGlass", "  ✓ secret = ${if (value.isEmpty()) "(empty)" else "***"}")
        value
    })
    /** 客户端 UUID */
    var uuid by mutableStateOf(run {
        android.util.Log.i("LiquidGlass", "  → 读取 uuid 配置")
        val value = ConfigStore.getUuid(application)
        android.util.Log.i("LiquidGlass", "  ✓ uuid = ${redactUuidForLog(value)}")
        value
    })
    /** gRPC 传输层安全开关；默认开启，只有用户显式关闭时才允许明文。 */
    var useTls by mutableStateOf(ConfigStore.getUseTls(application))
    /** Root/Shizuku 高权限模式 */
    var rootMode by mutableStateOf(ConfigStore.getRootMode(application))
    /** 智能解析输入框内容 */
    var clipboardInput by mutableStateOf("")

    // ── 工具页设置 ──
    /** 后台音频保活 */
    var enableKeepAliveAudio by mutableStateOf(ConfigStore.getEnableKeepAliveAudio(application))
        private set
    /** 像素级透明悬浮窗 */
    var enableFloatWindow by mutableStateOf(ConfigStore.getEnableFloatWindow(application))
        private set
    /** 开机自启动 */
    var enableAutoStart by mutableStateOf(ConfigStore.getEnableAutoStart(application))
        private set
    /** VPN 流量兼容模式（部分无 Root/Shizuku 且 Android < 12 ROM 的兜底方案） */
    var enableVpnTraffic by mutableStateOf(
        VpnTrafficCompatibility.normalize(
            enabled = ConfigStore.getEnableVpnTraffic(application),
            sdkInt = Build.VERSION.SDK_INT
        )
    )
        private set

    val isVpnTrafficCompatibilityAvailable: Boolean
        get() = VpnTrafficCompatibility.isSupported(Build.VERSION.SDK_INT)
    /** [安全修复] 远程命令执行独立开关（与 Root/Shizuku 模式解耦） */
    var enableRemoteCommand by mutableStateOf(ConfigStore.getEnableRemoteCommand(application))
        private set

    var isConfigWriteInProgress by mutableStateOf(false)
        private set

    // ── 娱乐模拟设备上报 ──
    var simulatorServer by mutableStateOf(ConfigStore.getSimulatorServer(application))
    var simulatorPort by mutableStateOf(ConfigStore.getSimulatorPort(application).toString())
    var simulatorSecret by mutableStateOf(ConfigStore.getSimulatorSecret(application))
    var simulatorUseTls by mutableStateOf(ConfigStore.getSimulatorUseTls(application))
    var simulatorThreadCount by mutableStateOf(ConfigStore.getSimulatorThreadCount(application).toString())

    init {
        // A decrypt/read failure also closes the store; reflect that after loading every field.
        storageStatus = ConfigStore.initialize(application)
        if (!isSecureStorageAvailable) {
            storageErrorMessage = SECURE_STORAGE_UNAVAILABLE_MESSAGE
        }
    }

    var simulatorRunning by mutableStateOf(false)
        private set
    var simulatorSuccessCount by mutableStateOf(0)
        private set
    var simulatorFailureCount by mutableStateOf(0)
        private set
    var simulatorActiveThreadCount by mutableStateOf(0)
        private set
    var simulatorLastStatus by mutableStateOf("未启动")
        private set

    private var simulatorJob: Job? = null
    private val simulatorLoop = SimulatedDeviceLoop(GrpcSimulatedDeviceReporter())

    /** 首次启动自启动授权弹窗 */
    var showAutoStartPrompt by mutableStateOf(false)
        private set


    // ══════════════════════════════════════════════════════════════════════════
    // Shizuku 权限状态
    // ══════════════════════════════════════════════════════════════════════════

    /** Shizuku 状态文本，驱动 UI 显示 */
    var shizukuStatusText by mutableStateOf("")

    // ══════════════════════════════════════════════════════════════════════════
    // gRPC 连接状态（来自 GrpcManager 的 StateFlow）
    // ══════════════════════════════════════════════════════════════════════════

    /** gRPC 连接状态 StateFlow，供 UI 层 collectAsState */
    val grpcConnectionState: StateFlow<GrpcConnectionState> = GrpcManager.connectionState

    // ══════════════════════════════════════════════════════════════════════════
    // 即时测试采集
    // ══════════════════════════════════════════════════════════════════════════

    /** 即时测试结果文本（null = 隐藏弹窗） */
    var instantTestResult by mutableStateOf<String?>(null)
        private set

    /** 即时测试是否正在执行 */
    var isTestRunning by mutableStateOf(false)
        private set

    // ══════════════════════════════════════════════════════════════════════════
    // 业务方法
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 智能解析面板安装脚本中的配置信息。
     *
     * 支持两种格式：
     * 1. 传统 flag 模式：`-s host:port -p secret --tls`
     * 2. 环境变量模式：`NZ_SERVER=host:port NZ_CLIENT_SECRET=xxx NZ_UUID=yyy NZ_TLS=true`
     */
    fun parseClipboardConfig() {
        if (isConfigWriteInProgress) return
        val input = clipboardInput

        // Legacy flag based params
        val sMatch = Regex("-s\\s+([^:\\s]+):(\\d+)").find(input)
        if (sMatch != null) {
            server = sMatch.groupValues[1]
            port = sMatch.groupValues[2]
        } else {
            val sMatch2 = Regex("-s\\s+([^\\s:]+)").find(input)
            if (sMatch2 != null) server = sMatch2.groupValues[1]
        }

        val pMatch = Regex("-p\\s+([^\\s]+)").find(input)
        if (pMatch != null) secret = pMatch.groupValues[1]

        if (Regex("(^|\\s)--tls(\\s|$)").containsMatchIn(input)) {
            useTls = true
        }
        if (Regex("(^|\\s)--(no-tls|disable-tls)(\\s|$)").containsMatchIn(input)) {
            useTls = false
        }

        // New Dashboard Script logic (Environment variable based mapping)
        val envServerMatch = Regex("NZ_SERVER=([^:\\s]+):(\\d+)").find(input)
        if (envServerMatch != null) {
            server = envServerMatch.groupValues[1]
            port = envServerMatch.groupValues[2]
        }
        val envSecretMatch = Regex("NZ_CLIENT_SECRET=([^\\s]+)").find(input)
        if (envSecretMatch != null) secret = envSecretMatch.groupValues[1]

        val envUuidMatch = Regex("NZ_UUID=([^\\s]+)").find(input)
        if (envUuidMatch != null) {
            val parsedUuid = envUuidMatch.groupValues[1].replace(Regex("^['\"]|['\"]$"), "")
            if (parsedUuid.isNotBlank() && parsedUuid != "\\") {
                uuid = parsedUuid
            } else {
                uuid = java.util.UUID.randomUUID().toString()
            }
        } else if (uuid.isBlank() || uuid == "''" || uuid == "\"\"") {
            uuid = java.util.UUID.randomUUID().toString()
        }

        val envTlsMatch = Regex("NZ_TLS=([^\\s]+)").find(input)
        if (envTlsMatch != null) {
            parseBooleanLike(envTlsMatch.groupValues[1])?.let { useTls = it }
        }

        Toast.makeText(getApplication(), "配置已解析完成", Toast.LENGTH_SHORT).show()
    }

    fun setKeepAliveAudio(enabled: Boolean) {
        persistToolSettings(
            enableKeepAliveAudio = enabled,
            enableFloatWindow = enableFloatWindow,
            enableVpnTraffic = enableVpnTraffic,
            action = "保存后台音频设置",
            failureMessage = "后台音频设置保存失败"
        )
    }

    fun setFloatWindow(enabled: Boolean) {
        persistToolSettings(
            enableKeepAliveAudio = enableKeepAliveAudio,
            enableFloatWindow = enabled,
            enableVpnTraffic = enableVpnTraffic,
            action = "保存悬浮窗设置",
            failureMessage = "悬浮窗设置保存失败"
        )
    }

    fun setVpnTraffic(enabled: Boolean) {
        persistToolSettings(
            enableKeepAliveAudio = enableKeepAliveAudio,
            enableFloatWindow = enableFloatWindow,
            enableVpnTraffic = enabled,
            action = "保存 VPN 流量设置",
            failureMessage = "VPN 流量设置保存失败"
        )
    }

    /**
     * 保存配置并启动探针服务。
     *
     * 内部处理：
     * 1. UUID 清洗与持久化
     * 2. 电池优化白名单检测
     * 3. 前台服务启动
     *
     * @param notificationPermGranted 通知权限是否已授予（Android 13+ 专用）
     * @param requestNotificationPerm 请求通知权限的回调（若需要）
     */
    fun startAgent(
        notificationPermGranted: Boolean,
        requestNotificationPerm: (() -> Unit) -> Unit
    ) {
        val ctx = getApplication<Application>()
        if (!requireSecureStorage("启动探针")) return
        val p = port.toIntOrNull()

        // [修复问题6] 启动前配置校验，防止配置不完整时启动服务
        // 原实现不校验配置，导致 GrpcManager.stub 为 null 后服务在循环中反复报错
        if (server.isBlank()) {
            Toast.makeText(ctx, "请先填写服务端 IP 或域名", Toast.LENGTH_SHORT).show()
            return
        }
        if (p == null || p <= 0 || p > 65535) {
            Toast.makeText(ctx, "端口号无效，请填写 1-65535 之间的数字", Toast.LENGTH_SHORT).show()
            return
        }
        if (secret.isBlank()) {
            Toast.makeText(ctx, "请先填写客户端密钥 (Secret)", Toast.LENGTH_SHORT).show()
            return
        }

        val cleanedUuid = uuid.trim().replace(Regex("^['\"]|['\"]$"), "")
        val uuidToSave = cleanedUuid.takeUnless { it.isBlank() || it == "\\" }
            ?: java.util.UUID.randomUUID().toString()
        val serverToSave = server
        val secretToSave = secret
        val useTlsToSave = useTls
        val rootModeToSave = rootMode

        // 封装实际启动服务的 lambda
        val doLaunchService: () -> Unit = {
            // 电池优化白名单检测
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val packageName = ctx.packageName
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val batteryIntent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply { data = Uri.parse("package:$packageName") }
                    try {
                        batteryIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        ctx.startActivity(batteryIntent)
                        Toast.makeText(
                            ctx,
                            "请在弹出的系统对话框中选择 '允许' 以保证后台保活",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        try {
                            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            fallback.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            ctx.startActivity(fallback)
                        } catch (_: Exception) { /* 兜底：忽略，不阻塞探针启动 */ }
                    }
                }
            }

            // 启动前台服务
            val intent = Intent(ctx, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            Toast.makeText(ctx, "后台探针服务已启动", Toast.LENGTH_SHORT).show()
            
            // 启动成功后，检查是否需要显示自启动授权弹窗
            checkAndShowAutoStartPrompt()
        }

        persistOnIo(
            action = "保存连接配置并启动探针",
            failureMessage = "连接配置保存失败，探针未启动",
            persistence = {
                val currentAudioSetting = ConfigStore.getEnableKeepAliveAudio(ctx)
                ConfigStore.saveConfig(
                    ctx,
                    serverToSave,
                    p,
                    secretToSave,
                    useTlsToSave,
                    uuidToSave,
                    rootModeToSave,
                    currentAudioSetting
                )
            },
            onSuccess = {
                uuid = uuidToSave
                // Android 13+ 通知权限时序控制 only begins after durable persistence.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !notificationPermGranted
                ) {
                    requestNotificationPerm(doLaunchService)
                } else {
                    doLaunchService()
                }
            }
        )
    }

    /**
     * 停止探针服务。
     */
    fun stopAgent() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, AgentService::class.java)
        ctx.stopService(intent)
        Toast.makeText(ctx, "后台探针服务已停止", Toast.LENGTH_SHORT).show()
    }

    fun startSimulator() {
        if (simulatorRunning) return

        val ctx = getApplication<Application>()
        if (!requireSecureStorage("启动模拟器")) return
        val trimmedServer = simulatorServer.trim()
        val trimmedSecret = simulatorSecret.trim()
        val validationError = SimulatedDeviceConfig.validationError(
            server = trimmedServer,
            portText = simulatorPort,
            secret = trimmedSecret,
            threadCountText = simulatorThreadCount
        )
        if (validationError != null) {
            Toast.makeText(ctx, validationError, Toast.LENGTH_SHORT).show()
            return
        }

        val parsedPort = simulatorPort.trim().toInt()
        val parsedThreadCount = simulatorThreadCount.trim().toInt()
        val simulatorTlsToSave = simulatorUseTls
        val config = SimulatedDeviceConfig(
            server = trimmedServer,
            port = parsedPort,
            secret = trimmedSecret,
            useTls = simulatorTlsToSave,
            threadCount = parsedThreadCount
        )

        persistOnIo(
            action = "保存模拟器配置并启动模拟器",
            failureMessage = "模拟器配置保存失败，模拟器未启动",
            persistence = {
                ConfigStore.saveSimulatorConfig(
                    ctx,
                    server = trimmedServer,
                    port = parsedPort,
                    secret = trimmedSecret,
                    useTls = simulatorTlsToSave,
                    threadCount = parsedThreadCount
                )
            },
            onSuccess = {
                simulatorServer = trimmedServer
                simulatorPort = parsedPort.toString()
                simulatorSecret = trimmedSecret
                simulatorThreadCount = parsedThreadCount.toString()
                simulatorRunning = true
                simulatorSuccessCount = 0
                simulatorFailureCount = 0
                simulatorActiveThreadCount = parsedThreadCount
                simulatorLastStatus =
                    "模拟器已开启，$parsedThreadCount 个并发线程正在上报随机设备..."
                simulatorJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        simulatorLoop.run(
                            config = config,
                            shouldContinue = { isActive },
                            onSuccess = {
                                withContext(Dispatchers.Main) {
                                    simulatorSuccessCount += 1
                                    simulatorLastStatus =
                                        "上次成功：第 $simulatorSuccessCount 台设备已收到状态回执"
                                }
                            },
                            onFailure = { throwable ->
                                withContext(Dispatchers.Main) {
                                    simulatorFailureCount += 1
                                    simulatorLastStatus =
                                        "上次失败：${throwable.toSimulatorMessage()}"
                                }
                            }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (throwable: Throwable) {
                        withContext(Dispatchers.Main) {
                            simulatorRunning = false
                            simulatorLastStatus =
                                "模拟器异常停止：${throwable.toSimulatorMessage()}"
                        }
                    }
                }
                Toast.makeText(ctx, "娱乐模拟设备已开启", Toast.LENGTH_SHORT).show()
            }
        )
    }

    fun stopSimulator() {
        if (!simulatorRunning && simulatorJob == null) {
            Toast.makeText(getApplication(), "娱乐模拟设备未开启", Toast.LENGTH_SHORT).show()
            return
        }
        simulatorJob?.cancel()
        simulatorJob = null
        simulatorRunning = false
        simulatorLastStatus =
            "模拟器已停止，本次会话成功 $simulatorSuccessCount 台，失败 $simulatorFailureCount 台"
        Toast.makeText(getApplication(), "娱乐模拟设备已关闭", Toast.LENGTH_SHORT).show()
    }

    fun onUseTlsChanged(enabled: Boolean) {
        if (isConfigWriteInProgress) return
        useTls = enabled
    }

    /**
     * 检查是否需要显示首次开机自启动授权弹窗
     */
    private fun checkAndShowAutoStartPrompt() {
        val ctx = getApplication<Application>()
        if (!isSecureStorageAvailable) return
        val hasShownAutoStartPrompt = ConfigStore.getHasShownAutoStartPrompt(ctx)
        storageStatus = ConfigStore.initialize(ctx)
        if (!isSecureStorageAvailable) {
            storageErrorMessage = SECURE_STORAGE_UNAVAILABLE_MESSAGE
            showAutoStartPrompt = false
            return
        }
        if (!hasShownAutoStartPrompt) {
            showAutoStartPrompt = true
        }
    }

    /**
     * 处理自启动授权弹窗结果
     */
    fun onAutoStartPromptResult(accepted: Boolean) {
        persistOnIo(
            action = "保存开机自启动设置",
            failureMessage = "开机自启动设置保存失败",
            persistence = { ConfigStore.saveAutoStartPromptResult(getApplication(), accepted) },
            onSuccess = {
                enableAutoStart = accepted
                showAutoStartPrompt = false
            },
            onFailure = { showAutoStartPrompt = false }
        )
    }

    /**
     * 工具页手动切换自启动开关
     */
    fun toggleAutoStart(enabled: Boolean, onSaved: () -> Unit = {}) {
        persistOnIo(
            action = "保存开机自启动设置",
            failureMessage = "开机自启动设置保存失败",
            persistence = { ConfigStore.setEnableAutoStart(getApplication(), enabled) },
            onSuccess = {
                enableAutoStart = enabled
                onSaved()
            }
        )
    }

    /**
     * 切换远程命令执行开关。
     *
     * [安全修复] 此开关与 Root/Shizuku 模式完全独立，
     * 防止用户启用 Root 提权功能时静默授予面板远程命令执行权限。
     */
    fun toggleRemoteCommand(enabled: Boolean) {
        persistOnIo(
            action = "保存远程命令设置",
            failureMessage = "远程命令设置保存失败",
            persistence = { ConfigStore.setEnableRemoteCommand(getApplication(), enabled) },
            onSuccess = { enableRemoteCommand = enabled }
        )
    }

    /** Clears all credential storage on an IO thread and refreshes the UI with safe defaults. */
    fun resetSecureStorage() {
        if (isResettingSecureStorage) return
        val ctx = getApplication<Application>()
        isResettingSecureStorage = true
        simulatorJob?.cancel()
        simulatorJob = null
        simulatorRunning = false
        ctx.stopService(Intent(ctx, AgentService::class.java))
        viewModelScope.launch {
            val resetSucceeded = withContext(Dispatchers.IO) {
                ConfigStore.resetSecureStorage(ctx)
            }
            isResettingSecureStorage = false
            storageStatus = ConfigStore.initialize(ctx)
            if (!resetSucceeded || !isSecureStorageAvailable) {
                storageErrorMessage = "安全配置重置失败，加密存储仍不可用，请稍后重试"
                Toast.makeText(ctx, storageErrorMessage, Toast.LENGTH_LONG).show()
                return@launch
            }
            refreshConfigurationFromSecureStorage()
            storageStatus = ConfigStore.initialize(ctx)
            if (!isSecureStorageAvailable) {
                storageErrorMessage = "安全配置重置后读取失败，加密存储仍不可用，请稍后重试"
                Toast.makeText(ctx, storageErrorMessage, Toast.LENGTH_LONG).show()
                return@launch
            }
            storageErrorMessage = null
            Toast.makeText(ctx, "安全配置已重置，请重新填写连接信息", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 处理 Root/Shizuku 模式切换。

     *
     * 开启时自动检测 Shizuku 可用性并按需请求权限。
     */
    fun onRootModeChanged(enabled: Boolean, shizukuRequestCode: Int) {
        if (isConfigWriteInProgress) return
        if (enabled) {
            rootMode = true
            tryRequestShizukuPermission(shizukuRequestCode)
        } else {
            rootMode = false
            shizukuStatusText = ""
        }
    }

    /**
     * Shizuku 权限回调处理（由 Activity 桥接调用）。
     */
    fun onShizukuPermissionResult(granted: Boolean) {
        if (granted) {
            shizukuStatusText = "✅ Shizuku 已授权"
            Toast.makeText(getApplication(), "Shizuku 权限已授予，ADB Shell 模式可用", Toast.LENGTH_SHORT).show()
        } else {
            shizukuStatusText = "❌ Shizuku 授权被拒绝"
            rootMode = false
            Toast.makeText(getApplication(), "Shizuku 权限被拒绝，已关闭高权限模式", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 执行即时测试采集。
     *
     * 在后台协程中调用 SystemStateCollector，将结果格式化后
     * 通过 `instantTestResult` 驱动 AlertDialog 展示。
     */
    fun runInstantTest() {
        if (isTestRunning) return
        isTestRunning = true
        // 先显示"正在采样"提示
        instantTestResult = "⏳ 正在采样网速中..."
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val ctx = getApplication<Application>()
                    val collector = SystemStateCollector(ctx)

                    // ── 第 1 次采样：建立差值基准 ──────────────────────────────
                    // 网络速度和 CPU 使用率均基于差值法（两次采样的变化量 / 时间差），
                    // 首次调用时无历史基准，差值必然为 0，因此需先"预热"一次。
                    collector.getState()

                    // ── 等待采样间隔 ──────────────────────────────────────────
                    // 1.5 秒足以让 /proc/stat 和网络流量产生可测量的变化量，
                    // 同时对用户体验的等待时间也在可接受范围内。
                    delay(1500L)

                    // ── 第 2 次采样：获取真实差值数据 ──────────────────────────
                    val state = collector.getState()
                    val hostInfo = com.nezhahq.agent.collector.SystemInfoCollector.getHostInfo(ctx, "test")
                    // 从 CPU 显示名称中提取真实核心数
                    // CPU 名称格式为 "{SoC名称} {核心数} {Physical/Virtual} Core"
                    val cpuDisplayName = hostInfo.cpuList.firstOrNull() ?: "N/A"
                    val actualCoreCount = Regex("(\\d+)\\s+(?:Physical|Virtual)\\s+Core")
                        .find(cpuDisplayName)?.groupValues?.get(1) ?: "N/A"
                    buildString {
                        appendLine("═══ 采集结果预览 ═══")
                        appendLine("▸ CPU 使用率: ${"%.1f".format(state.cpu)}%")
                        appendLine("▸ CPU 核心数: $actualCoreCount")
                        appendLine("▸ CPU 名称: $cpuDisplayName")
                        appendLine("▸ Load: ${"%.2f".format(state.load1)} / ${"%.2f".format(state.load5)} / ${"%.2f".format(state.load15)}")
                        appendLine("▸ 内存已用: ${state.memUsed / 1024 / 1024} MB")
                        appendLine("▸ Swap 已用: ${state.swapUsed / 1024 / 1024} MB")
                        appendLine("▸ 磁盘已用: ${state.diskUsed / 1024 / 1024} MB")
                        appendLine("▸ 网络速度: ↓${state.netInSpeed / 1024} KB/s  ↑${state.netOutSpeed / 1024} KB/s")
                        appendLine("▸ TCP 连接: ${state.tcpConnCount}")
                        appendLine("▸ UDP 连接: ${state.udpConnCount}")
                        appendLine("▸ 进程数: ${state.processCount}")
                        appendLine("▸ 温度: ${state.temperaturesList.firstOrNull()?.let { "${it.name} ${it.temperature}°C" } ?: "N/A"}")
                        // GPU 信息
                        val gpuName = com.nezhahq.agent.collector.GpuCollector.getGpuNames().firstOrNull()
                        appendLine("▸ GPU: ${gpuName ?: "N/A"}")
                        val gpuUsages = state.gpuList
                        appendLine("▸ GPU 使用率: ${
                            if (gpuUsages.isNotEmpty()) "${"%.1f".format(gpuUsages.first())}%"
                            else "N/A（需 Root/Shizuku）"
                        }")
                        appendLine()
                        appendLine("═══ 权限状态 ═══")
                        appendLine("▸ Shell 会话: ${RootShell.getSessionType() ?: "无（普通模式）"}")
                        appendLine("▸ Shell 存活: ${if (RootShell.isAlive()) "✅" else "❌"}")
                    }
                }
                instantTestResult = result
            } catch (e: Exception) {
                instantTestResult = "❌ 采集失败: ${e.message}"
            } finally {
                isTestRunning = false
            }
        }
    }

    /**
     * 关闭即时测试结果弹窗。
     */
    fun dismissTestResult() {
        instantTestResult = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 私有方法
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 尝试请求 Shizuku 权限。
     *
     * ## 逻辑流程
     * 1. 检查 Shizuku 服务是否存活
     * 2. 检查 Shizuku 版本
     * 3. 检查并申请权限
     */
    private fun tryRequestShizukuPermission(requestCode: Int) {
        val ctx = getApplication<Application>()
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatusText = "⚠️ Shizuku 未运行（可使用 Root 则忽略此提示）"
                Toast.makeText(
                    ctx,
                    "Shizuku 未运行。如设备已 Root 可忽略此提示；\n否则请先安装并启动 Shizuku 应用。",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            if (Shizuku.isPreV11()) {
                shizukuStatusText = "⚠️ Shizuku 版本过低，不受支持"
                Toast.makeText(ctx, "当前 Shizuku 版本过低，请升级到 v11 以上", Toast.LENGTH_LONG).show()
                return
            }

            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    shizukuStatusText = "✅ Shizuku 已授权（UID=${Shizuku.getUid()}）"
                }
                Shizuku.shouldShowRequestPermissionRationale() -> {
                    shizukuStatusText = "❌ Shizuku 授权被永久拒绝，请在 Shizuku 应用中手动授权"
                    Toast.makeText(
                        ctx,
                        "Shizuku 权限已被永久拒绝，请打开 Shizuku 应用，在「授权管理」中手动为本应用授权。",
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> {
                    shizukuStatusText = "⏳ 等待 Shizuku 授权..."
                    Shizuku.requestPermission(requestCode)
                }
            }
        } catch (e: Exception) {
            shizukuStatusText = "⚠️ Shizuku 检测异常"
            Toast.makeText(
                ctx,
                "Shizuku 检测失败：${e.message}\n如设备已 Root 可忽略此提示。",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun parseBooleanLike(rawValue: String): Boolean? {
        val normalized = rawValue
            .trim()
            .trim('\'', '"')
            .trimEnd(';')
            .lowercase()
        return when (normalized) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    private fun requireSecureStorage(action: String): Boolean {
        storageStatus = ConfigStore.initialize(getApplication())
        if (isSecureStorageAvailable) return true
        storageErrorMessage = SECURE_STORAGE_UNAVAILABLE_MESSAGE
        Toast.makeText(
            getApplication(),
            "$action 已拒绝：$SECURE_STORAGE_UNAVAILABLE_MESSAGE",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun persistOnIo(
        action: String,
        failureMessage: String,
        persistence: () -> Boolean,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {}
    ) {
        if (isConfigWriteInProgress) {
            onFailure()
            Toast.makeText(getApplication(), "安全配置正在保存，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (!requireSecureStorage(action)) {
            onFailure()
            return
        }

        isConfigWriteInProgress = true
        viewModelScope.launch {
            val persisted = withContext(Dispatchers.IO) {
                try {
                    persistence()
                } catch (_: Exception) {
                    false
                }
            }
            isConfigWriteInProgress = false
            if (persisted) {
                onSuccess()
            } else {
                onFailure()
                reportStorageWriteFailure(failureMessage)
            }
        }
    }

    private fun persistToolSettings(
        enableKeepAliveAudio: Boolean,
        enableFloatWindow: Boolean,
        enableVpnTraffic: Boolean,
        action: String,
        failureMessage: String
    ) {
        val normalizedVpnSetting = VpnTrafficCompatibility.normalize(
            enabled = enableVpnTraffic,
            sdkInt = Build.VERSION.SDK_INT
        )
        if (!isVpnTrafficCompatibilityAvailable) {
            this.enableVpnTraffic = false
        }

        persistOnIo(
            action = action,
            failureMessage = failureMessage,
            persistence = {
                ConfigStore.saveToolSettings(
                    context = getApplication(),
                    enableKeepAliveAudio = enableKeepAliveAudio,
                    enableFloatWindow = enableFloatWindow,
                    enableVpnTraffic = normalizedVpnSetting
                )
            },
            onSuccess = {
                this.enableKeepAliveAudio = enableKeepAliveAudio
                this.enableFloatWindow = enableFloatWindow
                this.enableVpnTraffic = normalizedVpnSetting
                showToolSettingSaved()
            }
        )
    }

    private fun showToolSettingSaved() {
        Toast.makeText(
            getApplication(),
            "配置已保存，请在主页停止并重新启动探针以生效",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun reportStorageWriteFailure(message: String) {
        storageStatus = ConfigStore.initialize(getApplication())
        storageErrorMessage = "$message；安全配置存储不可用，请重置安全配置"
        Toast.makeText(getApplication(), storageErrorMessage, Toast.LENGTH_LONG).show()
    }

    private fun refreshConfigurationFromSecureStorage() {
        val ctx = getApplication<Application>()
        server = ConfigStore.getServer(ctx)
        port = ConfigStore.getPort(ctx).toString()
        secret = ConfigStore.getSecret(ctx)
        uuid = ConfigStore.getUuid(ctx)
        useTls = ConfigStore.getUseTls(ctx)
        rootMode = ConfigStore.getRootMode(ctx)
        enableKeepAliveAudio = ConfigStore.getEnableKeepAliveAudio(ctx)
        enableFloatWindow = ConfigStore.getEnableFloatWindow(ctx)
        enableAutoStart = ConfigStore.getEnableAutoStart(ctx)
        enableVpnTraffic = VpnTrafficCompatibility.normalize(
            enabled = ConfigStore.getEnableVpnTraffic(ctx),
            sdkInt = Build.VERSION.SDK_INT
        )
        enableRemoteCommand = ConfigStore.getEnableRemoteCommand(ctx)
        simulatorServer = ConfigStore.getSimulatorServer(ctx)
        simulatorPort = ConfigStore.getSimulatorPort(ctx).toString()
        simulatorSecret = ConfigStore.getSimulatorSecret(ctx)
        simulatorUseTls = ConfigStore.getSimulatorUseTls(ctx)
        simulatorThreadCount = ConfigStore.getSimulatorThreadCount(ctx).toString()
        simulatorSuccessCount = 0
        simulatorFailureCount = 0
        simulatorActiveThreadCount = 0
        simulatorLastStatus = "未启动"
        showAutoStartPrompt = false
    }

    override fun onCleared() {
        simulatorJob?.cancel()
        simulatorJob = null
        super.onCleared()
    }

}

private const val SECURE_STORAGE_UNAVAILABLE_MESSAGE =
    "安全配置存储不可用，凭据不会写入；请重置安全配置"

internal fun redactUuidForLog(value: String): String {
    if (value.isBlank()) return "(empty)"
    return if (value.length <= 8) "***" else "${value.take(4)}...${value.takeLast(4)}"
}

private fun Throwable.toSimulatorMessage(): String {
    val message = generateSequence(this) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: this::class.java.simpleName
    return message.replace('\n', ' ').take(120)
}

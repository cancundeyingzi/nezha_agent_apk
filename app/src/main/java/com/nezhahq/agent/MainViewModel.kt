package com.nezhahq.agent

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nezhahq.agent.collector.GpuCollector
import com.nezhahq.agent.collector.SystemInfoCollector
import com.nezhahq.agent.collector.SystemStateCollector
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.core.model.SimulatorDraft
import com.nezhahq.agent.core.platform.VpnTrafficCompatibility
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.service.AgentService
import com.nezhahq.agent.simulator.GrpcSimulatedDeviceReporter
import com.nezhahq.agent.simulator.SimulatedDeviceLoop
import com.nezhahq.agent.ui.ClipboardConfigParser
import com.nezhahq.agent.ui.ParsedUuid
import com.nezhahq.agent.ui.UiEvent
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.util.RootShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
class MainViewModel(
    application: Application,
    private val repository: ConfigRepository
) : AndroidViewModel(application) {

    // Read once here: opening the store is what reports its status, so every field below is loaded
    // from the same snapshot rather than re-opening it per property.
    private val initialConnection = repository.loadConnectionDraft()
    private val initialTools = repository.loadToolSettings()
    private val initialCapabilities = repository.loadRemoteCapabilities()
    private val initialAutoStart = repository.loadAutoStartState()
    private val initialSimulator = repository.loadSimulatorDraft()

    var storageStatus by mutableStateOf(repository.storageStatus())
        private set

    val isConfigStorageAvailable: Boolean
        get() = storageStatus.isUsable

    var isResettingConfigStorage by mutableStateOf(false)
        private set

    var storageErrorMessage by mutableStateOf(
        if (storageStatus.isUsable) null else CONFIG_STORAGE_UNAVAILABLE_MESSAGE
    )
        private set

    // ══════════════════════════════════════════════════════════════════════════
    // 配置字段状态（Compose State，驱动 UI 重组）
    // ══════════════════════════════════════════════════════════════════════════

    /** 服务端 IP 或域名 */
    var server by mutableStateOf(initialConnection.server)
    /** gRPC 端口 */
    var port by mutableStateOf(initialConnection.port.toString())
    /** 客户端密钥 */
    var secret by mutableStateOf(initialConnection.secret)
    /** 客户端 UUID */
    var uuid by mutableStateOf(initialConnection.uuid)
    /** gRPC 传输层安全开关；默认开启，只有用户显式关闭时才允许明文。 */
    var useTls by mutableStateOf(initialConnection.useTls)
    /** Root/Shizuku 高权限模式 */
    var rootMode by mutableStateOf(initialConnection.rootMode)
    // ── 工具页设置 ──
    /** 后台音频保活 */
    var enableKeepAliveAudio by mutableStateOf(initialTools.audio)
        private set
    /** 像素级透明悬浮窗 */
    var enableFloatWindow by mutableStateOf(initialTools.overlay)
        private set
    /** 开机自启动 */
    var enableAutoStart by mutableStateOf(initialAutoStart.enabled)
        private set
    /** VPN 流量兼容模式（部分无 Root/Shizuku 且 Android < 12 ROM 的兜底方案） */
    var enableVpnTraffic by mutableStateOf(initialTools.vpn)
        private set

    val isVpnTrafficCompatibilityAvailable: Boolean
        get() = VpnTrafficCompatibility.isSupported(Build.VERSION.SDK_INT)
    /** 远程 Shell 开关：同时控制 TaskType 4 命令与新建交互终端。 */
    var enableRemoteCommand by mutableStateOf(initialCapabilities.shellEnabled)
        private set
    /** 远程文件管理开关：控制 TaskType 11 的浏览、下载与上传。 */
    var enableRemoteFileManager by mutableStateOf(initialCapabilities.fileManagerEnabled)
        private set
    /** 内网穿透开关：控制 TaskType 9 的 TCP 转发。 */
    var enableRemoteNat by mutableStateOf(initialCapabilities.natEnabled)
        private set

    var isConfigWriteInProgress by mutableStateOf(false)
        private set

    // ── 娱乐模拟设备上报 ──
    var simulatorServer by mutableStateOf(initialSimulator.server)
    var simulatorPort by mutableStateOf(initialSimulator.port.toString())
    var simulatorSecret by mutableStateOf(initialSimulator.secret)
    var simulatorUseTls by mutableStateOf(initialSimulator.useTls)
    var simulatorThreadCount by mutableStateOf(initialSimulator.threadCount.toString())

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
    val grpcConnectionState: StateFlow<GrpcConnectionState> =
        application.appContainer.connectionState.connectionState

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
    // 一次性 UI 事件
    // ══════════════════════════════════════════════════════════════════════════

    private val uiEvents = UiEvents()

    /** Transient messages for the presenter to show; see [UiEvent]. */
    val events: Flow<UiEvent> = uiEvents.flow

    /**
     * Whether configuration may be edited right now.
     *
     * Collapses the storage-usable and no-write-in-flight pair that the screens previously
     * recombined at every control, where forgetting one half silently enabled a dead toggle.
     */
    val canEditConfig: Boolean
        get() = isConfigStorageAvailable && !isConfigWriteInProgress

    private fun notify(text: String) = uiEvents.send(text)

    private fun notifyLong(text: String) = uiEvents.sendLong(text)

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
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val input = clipboard
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()

        if (input.isBlank()) {
            notify("剪贴板中没有可解析的安装脚本")
            return
        }

        val parsed = ClipboardConfigParser.parse(input)
        parsed.server?.let { server = it }
        parsed.port?.let { port = it }
        parsed.secret?.let { secret = it }
        parsed.useTls?.let { useTls = it }
        uuid = resolveUuid(parsed.uuid)

        notify("配置已解析完成")
    }

    /**
     * Settles on the UUID to use after a paste.
     *
     * A script that named one wins. A script that declared an empty one is asking for a fresh UUID.
     * A script that said nothing leaves the configured UUID alone — unless there is nothing usable
     * there either, in which case one is generated so the user never has to supply it by hand.
     */
    private fun resolveUuid(parsed: ParsedUuid): String = when (parsed) {
        is ParsedUuid.Found -> parsed.value
        ParsedUuid.Placeholder -> newUuid()
        ParsedUuid.Absent ->
            if (uuid.isBlank() || uuid == "''" || uuid == "\"\"") newUuid() else uuid
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
        if (!requireConfigStorage("启动探针")) return

        // Same rules the service validates with, so a saved configuration can always be started.
        AgentConfig.validationError(server = server, portText = port, secret = secret)?.let {
            notify(it)
            return
        }

        val cleanedUuid = uuid.trim().replace(Regex("^['\"]|['\"]$"), "")
        val uuidToSave = cleanedUuid.takeUnless { it.isBlank() || it == "\\" } ?: newUuid()
        val draftToSave = ConnectionDraft(
            server = server,
            port = port.trim().toInt(),
            secret = secret,
            uuid = uuidToSave,
            useTls = useTls,
            rootMode = rootMode
        )

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
                        notifyLong("请在弹出的系统对话框中选择 '允许' 以保证后台保活")
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
            AgentService.startOrReload(ctx)
            notify("后台探针服务已启动")
            
            // 启动成功后，检查是否需要显示自启动授权弹窗
            checkAndShowAutoStartPrompt()
        }

        persistOnIo(
            action = "保存连接配置并启动探针",
            failureMessage = "连接配置保存失败，探针未启动",
            persistence = { repository.saveConnection(draftToSave) },
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
        notify("后台探针服务已停止")
    }

    fun startSimulator() {
        if (simulatorRunning) return

        if (!requireConfigStorage("启动模拟器")) return
        val trimmedServer = simulatorServer.trim()
        val trimmedSecret = simulatorSecret.trim()
        val validationError = SimulatedDeviceConfig.validationError(
            server = trimmedServer,
            portText = simulatorPort,
            secret = trimmedSecret,
            threadCountText = simulatorThreadCount
        )
        if (validationError != null) {
            notify(validationError)
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
                repository.saveSimulator(
                    SimulatorDraft(
                        server = trimmedServer,
                        port = parsedPort,
                        secret = trimmedSecret,
                        useTls = simulatorTlsToSave,
                        threadCount = parsedThreadCount
                    )
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
                notify("娱乐模拟设备已开启")
            }
        )
    }

    fun stopSimulator() {
        if (!simulatorRunning && simulatorJob == null) {
            notify("娱乐模拟设备未开启")
            return
        }
        simulatorJob?.cancel()
        simulatorJob = null
        simulatorRunning = false
        simulatorActiveThreadCount = 0
        simulatorLastStatus =
            "模拟器已停止，本次会话成功 $simulatorSuccessCount 台，失败 $simulatorFailureCount 台"
        notify("娱乐模拟设备已关闭")
    }

    fun onUseTlsChanged(enabled: Boolean) {
        if (isConfigWriteInProgress) return
        useTls = enabled
    }

    /**
     * 检查是否需要显示首次开机自启动授权弹窗
     */
    private fun checkAndShowAutoStartPrompt() {
        if (!isConfigStorageAvailable) return
        val hasShownAutoStartPrompt = repository.loadAutoStartState().promptShown
        storageStatus = repository.storageStatus()
        if (!isConfigStorageAvailable) {
            reportStorageProblem(CONFIG_STORAGE_UNAVAILABLE_MESSAGE)
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
            persistence = { repository.saveAutoStartPromptResult(accepted) },
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
            persistence = { repository.saveAutoStart(enabled) },
            onSuccess = {
                enableAutoStart = enabled
                onSaved()
            }
        )
    }

    /**
     * 切换远程 Shell 执行开关。
     *
     * 此开关与 Root/Shizuku 模式完全独立，同时控制静默命令和交互终端；
     * Root 模式只决定已授权 Shell 能否提权。
     *
     * 持久化成功后立即通知运行中的服务重读授权，兑现界面上「关闭后新请求立即生效」的承诺。
     */
    fun toggleRemoteCommand(enabled: Boolean) {
        persistOnIo(
            action = "保存远程命令设置",
            failureMessage = "远程命令设置保存失败",
            persistence = { repository.saveRemoteCapability(RemoteCapability.SHELL, enabled) },
            onSuccess = {
                enableRemoteCommand = enabled
                AgentService.requestCapabilityRefreshIfRunning(getApplication())
            }
        )
    }

    /**
     * 切换远程文件管理开关（TaskType 11）。
     *
     * 本应用持有「所有文件访问」权限，开启后面板可读写设备上几乎全部文件。
     */
    fun toggleRemoteFileManager(enabled: Boolean) {
        persistOnIo(
            action = "保存文件管理设置",
            failureMessage = "文件管理设置保存失败",
            persistence = { repository.saveRemoteCapability(RemoteCapability.FILE_MANAGER, enabled) },
            onSuccess = {
                enableRemoteFileManager = enabled
                AgentService.requestCapabilityRefreshIfRunning(getApplication())
            }
        )
    }

    /** 切换内网穿透开关（TaskType 9）。 */
    fun toggleRemoteNat(enabled: Boolean) {
        persistOnIo(
            action = "保存内网穿透设置",
            failureMessage = "内网穿透设置保存失败",
            persistence = { repository.saveRemoteCapability(RemoteCapability.NAT, enabled) },
            onSuccess = {
                enableRemoteNat = enabled
                AgentService.requestCapabilityRefreshIfRunning(getApplication())
            }
        )
    }

    /** Clears all configuration on an IO thread and refreshes the UI with defaults. */
    fun resetConfigurationStorage() {
        if (isResettingConfigStorage) return
        val ctx = getApplication<Application>()
        isResettingConfigStorage = true
        simulatorJob?.cancel()
        simulatorJob = null
        simulatorRunning = false
        ctx.stopService(Intent(ctx, AgentService::class.java))
        viewModelScope.launch {
            val resetSucceeded = withContext(Dispatchers.IO) {
                repository.resetStorage()
            }
            isResettingConfigStorage = false
            storageStatus = repository.storageStatus()
            if (!resetSucceeded || !isConfigStorageAvailable) {
                reportStorageProblem("配置存储重置失败，请稍后重试")
                return@launch
            }
            refreshConfigurationFromStorage()
            storageStatus = repository.storageStatus()
            if (!isConfigStorageAvailable) {
                reportStorageProblem("配置存储重置后读取失败，请稍后重试")
                return@launch
            }
            storageErrorMessage = null
            notifyLong("配置已重置，请重新填写连接信息")
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
            notify("Shizuku 权限已授予，ADB Shell 模式可用")
        } else {
            shizukuStatusText = "❌ Shizuku 授权被拒绝"
            rootMode = false
            notify("Shizuku 权限被拒绝，已关闭高权限模式")
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
                val result = withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val gpuCollector = GpuCollector()
                    val collector = SystemStateCollector(ctx, gpuCollector, Dispatchers.IO)
                    val isPrivileged = RootShell.isAuthorized()

                    // ── 第 1 次采样：建立差值基准 ──────────────────────────────
                    // 网络速度和 CPU 使用率均基于差值法（两次采样的变化量 / 时间差），
                    // 首次调用时无历史基准，差值必然为 0，因此需先"预热"一次。
                    collector.getState(isPrivileged)

                    // ── 等待采样间隔 ──────────────────────────────────────────
                    // 1.5 秒足以让 /proc/stat 和网络流量产生可测量的变化量，
                    // 同时对用户体验的等待时间也在可接受范围内。
                    delay(1500L)

                    // ── 第 2 次采样：获取真实差值数据 ──────────────────────────
                    val state = collector.getState(isPrivileged)
                    val hostInfo = SystemInfoCollector.getHostInfo(
                        context = ctx,
                        appVersion = "test",
                        gpuCollector = gpuCollector,
                        isRootMode = isPrivileged
                    )
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
                        val gpuName = gpuCollector.getGpuNames().firstOrNull()
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
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatusText = "⚠️ Shizuku 未运行（可使用 Root 则忽略此提示）"
                notifyLong("Shizuku 未运行。如设备已 Root 可忽略此提示；\n否则请先安装并启动 Shizuku 应用。")
                return
            }

            if (Shizuku.isPreV11()) {
                shizukuStatusText = "⚠️ Shizuku 版本过低，不受支持"
                notifyLong("当前 Shizuku 版本过低，请升级到 v11 以上")
                return
            }

            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    shizukuStatusText = "✅ Shizuku 已授权（UID=${Shizuku.getUid()}）"
                }
                Shizuku.shouldShowRequestPermissionRationale() -> {
                    shizukuStatusText = "❌ Shizuku 授权被永久拒绝，请在 Shizuku 应用中手动授权"
                    notifyLong("Shizuku 权限已被永久拒绝，请打开 Shizuku 应用，在「授权管理」中手动为本应用授权。")
                }
                else -> {
                    shizukuStatusText = "⏳ 等待 Shizuku 授权..."
                    Shizuku.requestPermission(requestCode)
                }
            }
        } catch (e: Exception) {
            shizukuStatusText = "⚠️ Shizuku 检测异常"
            notifyLong("Shizuku 检测失败：${e.message}\n如设备已 Root 可忽略此提示。")
        }
    }

    private fun requireConfigStorage(action: String): Boolean {
        storageStatus = repository.storageStatus()
        if (isConfigStorageAvailable) return true
        reportStorageProblem("$action 已拒绝：$CONFIG_STORAGE_UNAVAILABLE_MESSAGE")
        return false
    }

    private fun persistOnIo(
        action: String,
        failureMessage: String,
        persistence: () -> Result<Unit>,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {}
    ) {
        if (isConfigWriteInProgress) {
            onFailure()
            notify("配置正在保存，请稍候")
            return
        }
        if (!requireConfigStorage(action)) {
            onFailure()
            return
        }

        isConfigWriteInProgress = true
        viewModelScope.launch {
            val persisted = withContext(Dispatchers.IO) {
                persistence().isSuccess
            }
            isConfigWriteInProgress = false
            if (persisted) {
                storageStatus = repository.storageStatus()
                storageErrorMessage = null
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
                repository.saveToolSettings(
                    KeepAliveSettings(
                        audio = enableKeepAliveAudio,
                        overlay = enableFloatWindow,
                        vpn = normalizedVpnSetting
                    )
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
        notifyLong("配置已保存，请在主页停止并重新启动探针以生效")
    }

    private fun reportStorageWriteFailure(message: String) {
        storageStatus = repository.storageStatus()
        reportStorageProblem("$message；配置存储不可用，请重置配置存储")
    }

    /**
     * The only way [storageErrorMessage] becomes non-null.
     *
     * Setting the field and telling the user were separate steps before, so the banner could show a
     * problem the user was never notified about, or outlive the condition that caused it.
     */
    private fun reportStorageProblem(message: String) {
        storageErrorMessage = message
        notifyLong(message)
    }

    private fun refreshConfigurationFromStorage() {
        val connection = repository.loadConnectionDraft()
        server = connection.server
        port = connection.port.toString()
        secret = connection.secret
        uuid = connection.uuid
        useTls = connection.useTls
        rootMode = connection.rootMode

        val tools = repository.loadToolSettings()
        enableKeepAliveAudio = tools.audio
        enableFloatWindow = tools.overlay
        enableVpnTraffic = tools.vpn
        enableAutoStart = repository.loadAutoStartState().enabled

        val capabilities = repository.loadRemoteCapabilities()
        enableRemoteCommand = capabilities.shellEnabled
        enableRemoteFileManager = capabilities.fileManagerEnabled
        enableRemoteNat = capabilities.natEnabled

        val simulator = repository.loadSimulatorDraft()
        simulatorServer = simulator.server
        simulatorPort = simulator.port.toString()
        simulatorSecret = simulator.secret
        simulatorUseTls = simulator.useTls
        simulatorThreadCount = simulator.threadCount.toString()
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

    companion object {
        /** Supplies the storage-backed repository; the UI never constructs storage itself. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                )
                MainViewModel(application, application.appContainer.configRepository)
            }
        }
    }
}

private const val CONFIG_STORAGE_UNAVAILABLE_MESSAGE =
    "配置存储不可用，连接信息不会写入；请重置配置存储"

private fun newUuid(): String = java.util.UUID.randomUUID().toString()

private fun Throwable.toSimulatorMessage(): String {
    val message = generateSequence(this) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: this::class.java.simpleName
    return message.replace('\n', ' ').take(120)
}

package com.nezhahq.agent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.nezhahq.agent.collector.GeoIpCollector
import com.nezhahq.agent.collector.SystemInfoCollector
import com.nezhahq.agent.collector.SystemStateCollector
import com.nezhahq.agent.executor.FileManager
import com.nezhahq.agent.executor.NatManager
import com.nezhahq.agent.executor.TaskExecutor
import com.nezhahq.agent.executor.TerminalManager
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.grpc.GrpcManager
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.FloatWindowManager
import com.nezhahq.agent.util.KeepAliveAudioPlayer
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.*
import proto.Nezha.Task
import proto.Nezha.TaskResult
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

class AgentService : Service() {

    private val job = SupervisorJob()
    // ── 全局协程异常兜底处理器 ─────────────────────────────────────────────
    // 防止任何未被 try-catch 捕获的协程异常（如 gRPC TLS 握手失败）
    // 传播到线程级别的 UncaughtExceptionHandler 导致应用闪退（FATAL EXCEPTION）。
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Logger.e("AgentService: 协程未捕获异常（已兜底，不闪退）", throwable)
    }
    private val scope = CoroutineScope(Dispatchers.IO + job + exceptionHandler)
    
    private var wakeLock: PowerManager.WakeLock? = null
    private val stateCollector by lazy { SystemStateCollector(this) }
    private val audioPlayer = KeepAliveAudioPlayer()

    // ── [修复问题5] 保存 NetworkCallback 引用，以便在 onDestroy 中注销 ──────
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null

    // ── 通知渠道 ID ──────────────────────────────────────────────────────────
    private val notificationChannelId = "nezha_agent_service"

    private companion object {
        private const val SHORT_TASK_WORKER_COUNT = 8
        private const val SHORT_TASK_QUEUE_CAPACITY = 64
        private val TLS_FAILURE_MARKERS = listOf(
            "ssl",
            "tls",
            "handshake",
            "certificate",
            "trust anchor",
            "peer unverified",
            "hostname",
            "not an ssl/tls record",
            "unsupported or unrecognized ssl message",
            "unable to find valid certification path"
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (ConfigStore.getEnableKeepAliveAudio(this)) {
            Logger.i("AgentService: 启用无声音频保活机制")
            audioPlayer.start()
        }
        if (ConfigStore.getEnableFloatWindow(this)) {
            Logger.i("AgentService: 启用悬浮窗保活机制")
            FloatWindowManager.show(this)
        }
        // ── 前台服务 startForeground 类型适配 ─────────────────────────────────
        // Android Q (10, API 29)+ 要求在调用时传入与 Manifest 声明一致的 serviceType。
        // Android 14 (API 34)+ 对 dataSync 的审查更严格（需真实数据同步活动），
        // 改用 FOREGROUND_SERVICE_TYPE_SPECIAL_USE 对长期系统监控进程保活效果更佳，
        // 且 Manifest 中已声明对应权限 FOREGROUND_SERVICE_SPECIAL_USE 与用途说明。
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                @Suppress("InlinedApi")
                startForeground(
                    1001,
                    createNotification("正在连接..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    1001,
                    createNotification("正在连接..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> startForeground(1001, createNotification("正在连接..."))
        }
        acquireWakeLock()
        
        Logger.i("Service started, configuring Grpc...")
        // 重置 TLS 降级状态：每次 Service 启动时重新尝试 TLS 连接
        GrpcManager.resetTlsFallback()
        GrpcManager.initialize(this)

        // ── 清理上次可能因 App Crash 遗留的临时上传文件 ───────────────────
        // FileManager 上传时使用 cacheDir/nezha_upload_{time}.tmp 作为中转，
        // 正常流程会在完成/异常时删除，但若 App 被系统强杀则会残留。
        scope.launch(Dispatchers.IO) {
            try {
                cacheDir.listFiles { file ->
                    file.name.startsWith("nezha_upload_") && file.name.endsWith(".tmp")
                }?.forEach { staleFile ->
                    Logger.i("AgentService: 清理残留临时文件: ${staleFile.name}")
                    staleFile.delete()
                }
            } catch (_: Exception) {}
        }
        
        Logger.i("Initializing network listeners and daemon coroutines...")
        setupNetworkListener()
        startWorkLoop()

        // ── VPN 流量计量服务 ──────────────────────────────────────────────────
        val vpnEnabled = ConfigStore.getEnableVpnTraffic(this)
        Logger.i("AgentService: VPN 流量计量配置 = $vpnEnabled")
        if (vpnEnabled) {
            // [修复问题7] 添加 Android 版本检查，VPN 流量计量仅适用于 Android 12 以下
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Logger.i("AgentService: Android 12+ 设备不需要 VPN 流量计量（系统已有精确统计 API），跳过启动")
            } else {
                try {
                    val prepareIntent = VpnService.prepare(this)
                    if (prepareIntent == null) {
                        // VPN 已被用户授权，直接启动计量服务
                        val vpnIntent = Intent(this, TrafficVpnService::class.java)
                        startService(vpnIntent)
                        Logger.i("AgentService: VPN 流量计量服务已启动")
                    } else {
                        Logger.i("AgentService: VPN 流量计量已启用但 VPN 权限未授权（需在工具页重新开启开关以触发授权），跳过启动")
                    }
                } catch (e: Exception) {
                    Logger.e("AgentService: VPN 流量计量服务启动异常", e)
                }
            }
        }
    }

    private fun setupNetworkListener() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        // [修复问题5] 保存 NetworkCallback 引用，防止泄漏
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network changed, update geoIP
                Logger.i("Network dynamically available, polling full GeoIP metadata...")
                scope.launch {
                    try {
                        val geoIp = GeoIpCollector.fetchGeoIP()
                        if (geoIp != null) GrpcManager.stub?.reportGeoIP(geoIp)
                    } catch (e: Exception) {
                        // gRPC 调用可能因 TLS 握手失败抛出异常，
                        // 此处捕获防止未处理异常导致闪退
                        Logger.e("GeoIP 上报失败（将在下次连接成功后重试）", e)
                    }
                }
            }
        }
        networkCallback = callback
        connectivityManager?.registerNetworkCallback(request, callback)
    }

    private fun startWorkLoop() {
        scope.launch {
            while (isActive) {
                try {
                    showConnectingStatus()
                    Logger.i("Preparing to handshake and send reports to Dashboard...")

                    // [修复问题6] 配置校验：stub 为空时等待重试而非反复抛异常
                    val stub = GrpcManager.stub
                    if (stub == null) {
                        Logger.e("AgentService: GrpcManager.stub 未初始化（配置可能不完整），5秒后重试...")
                        updateNotification("配置不完整，等待重试")
                        delay(5000)
                        GrpcManager.initialize(this@AgentService)
                        continue
                    }
                    
                    // 1. Report Host Info
                    Logger.i("Sending Static Host Information (ReportSystemInfo2)...")
                    val hostInfo = SystemInfoCollector.getHostInfo(this@AgentService, "1.0-android")
                    stub.reportSystemInfo2(hostInfo)
                    
                    // 2. Report Geo IP
                    Logger.i("Sending GeoIP Information...")
                    val geoIp = GeoIpCollector.fetchGeoIP()
                    if (geoIp != null) stub.reportGeoIP(geoIp)
                    
                    // 3. Bidirectional streams (Status & Tasks)
                    Logger.i("Handshake success. Opening Bidirectional streams for SystemState and Tasks...")
                    showConnectedStatus()
                    // 连接握手成功，重置 TLS 失败计数
                    GrpcManager.recordConnectionSuccess()
                    coroutineScope {
                        launch {
                            val stateFlow = flow {
                                while (currentCoroutineContext().isActive) {
                                    emit(withContext(Dispatchers.Default) {
                                        stateCollector.getState()
                                    })
                                    delay(2000) // Report state every 2 seconds
                                }
                            }
                            stub.reportSystemState(stateFlow).collect { _ ->
                                // Optional logic when dashboard acks state stream chunk (ignored typically)
                            }
                        }
                        
                        launch {
                            handleTaskStream(stub)
                        }
                    }
                } catch (e: Exception) {
                    val isAuthError = isAuthenticationFailure(e)
                    if (isAuthError) {
                        // 认证失败不计入 TLS 失败计数（问题在密钥/UUID，非 TLS）
                        GrpcManager.updateState(GrpcConnectionState.AUTH_FAILED)
                        updateNotification("认证失败，请检查密钥和 UUID")
                        Logger.e("Agent loop: 认证失败，请检查密钥和 UUID 配置", e)
                    } else {
                        if (!GrpcManager.isTlsFallbackActive() && isGenuineTlsFailure(e)) {
                            val shouldFallback = GrpcManager.recordTlsFailure()
                            if (shouldFallback) {
                                Logger.i("Agent loop: TLS 连续失败已达阈值，下次重连将使用明文传输")
                            }
                        }
                        showReconnectStatus()
                        Logger.e("Agent loop terminated/failed", e)
                    }
                    delay(5000) // Reconnect backoff
                    Logger.i("Re-initializing GrpcManager to attempt recovery...")
                    GrpcManager.initialize(this@AgentService)
                }
            }
        }
    }

    private suspend fun handleTaskStream(stub: NezhaServiceCoroutineStub) = coroutineScope {
        val resultChannel = Channel<TaskResult>(SHORT_TASK_QUEUE_CAPACITY)
        // 短任务使用固定 worker + 有界队列。
        // 队列满时直接拒绝新短任务，避免阻塞 gRPC 入站流并拖住后续 8/9/11 会话任务。
        val shortTaskQueue = Channel<Task>(SHORT_TASK_QUEUE_CAPACITY)

        val workerJobs = List(SHORT_TASK_WORKER_COUNT) {
            launch {
                for (task in shortTaskQueue) {
                    executeShortTask(task, resultChannel)
                }
            }
        }

        try {
            stub.requestTask(resultChannel.receiveAsFlow()).collect { task ->
                when (task.type) {
                    8L, 9L, 11L -> launchStreamTask(stub, task)
                    else -> enqueueShortTask(task, shortTaskQueue, resultChannel)
                }
            }
        } finally {
            workerJobs.forEach { it.cancel() }
            shortTaskQueue.close()
        }
    }

    private fun CoroutineScope.launchStreamTask(
        stub: NezhaServiceCoroutineStub,
        task: Task
    ) {
        val isRootMode = ConfigStore.getRootMode(this@AgentService)
        when (task.type) {
            8L -> launch {
                try {
                    val json = org.json.JSONObject(task.data)
                    val streamId = json.getString("StreamID")
                    // 注意：终端会话（TaskType 8）始终允许建立连接。
                    // enableRemoteCommand 开关仅控制 TaskType 4 的静默 sh -c 命令执行，
                    // 终端是交互式的，安全级别由 rootMode 独立控制（是否能 su/Shizuku 提权）。
                    Logger.i("收到终端任务 (TaskID=${task.id}, StreamID=$streamId)")
                    val terminal = TerminalManager(
                        this@AgentService, stub, streamId, isRootMode
                    )
                    terminal.run()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e("终端任务执行失败", e)
                    }
                }
            }
            9L -> launch {
                try {
                    val json = org.json.JSONObject(task.data)
                    val streamId = json.getString("StreamID")
                    val natHost = json.getString("Host")
                    Logger.i("收到 NAT 内网穿透任务 (TaskID=${task.id}, StreamID=$streamId, Host=$natHost)")
                    val natManager = NatManager(stub, streamId, natHost)
                    natManager.run()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e("NAT 内网穿透任务执行失败", e)
                    }
                }
            }
            11L -> launch {
                try {
                    val json = org.json.JSONObject(task.data)
                    val streamId = json.getString("StreamID")
                    Logger.i("收到文件管理器任务 (TaskID=${task.id}, StreamID=$streamId)")
                    val fileManager = FileManager(this@AgentService, stub, streamId)
                    fileManager.run()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e("文件管理器任务执行失败", e)
                    }
                }
            }
        }
    }

    /**
     * 将短任务入队到 Worker 队列。
     *
     * ## 背压策略
     * - shortTaskQueue 满 → 构造失败结果并尝试非阻塞发送至 resultChannel
     * - resultChannel 也满 → 在独立协程中挂起 send()，
     *   既不阻塞 collect 闭包（保证 8/9/11 流式任务不被拖住），
     *   又保证面板最终收到该任务的完成状态
     */
    private fun CoroutineScope.enqueueShortTask(
        task: Task,
        shortTaskQueue: SendChannel<Task>,
        resultChannel: SendChannel<TaskResult>
    ) {
        if (shortTaskQueue.trySend(task).isSuccess) {
            return
        }

        Logger.e("AgentService: 短任务队列已满，拒绝任务 TaskID=${task.id}, Type=${task.type}")
        val droppedResult = TaskResult.newBuilder()
            .setId(task.id)
            .setType(task.type)
            .setSuccessful(false)
            .setData("Task dropped: local short-task queue is full.")
            .build()
        if (!resultChannel.trySend(droppedResult).isSuccess) {
            // resultChannel 也满：在独立协程中挂起发送，不阻塞 collect 闭包
            Logger.e("AgentService: 结果通道暂满，异步等待上报被拒任务 TaskID=${task.id}")
            launch {
                resultChannel.send(droppedResult)
            }
        }
    }

    private suspend fun executeShortTask(
        task: Task,
        resultChannel: SendChannel<TaskResult>
    ) {
        val isCommandEnabled = ConfigStore.getEnableRemoteCommand(this@AgentService)
        val result = TaskExecutor.executeTask(task, isCommandEnabled = isCommandEnabled)
        resultChannel.send(result)
    }

    // ── [P3 修复] 连接状态辅助方法 ──────────────────────────────────────────
    // 降级模式下使用细分状态（TLS_FALLBACK_CONNECTING 等），
    // 而非统一的 TLS_FALLBACK，让 UI 能区分降级后的实际连接阶段。

    private fun showConnectingStatus() {
        if (GrpcManager.isTlsFallbackActive()) {
            GrpcManager.updateState(GrpcConnectionState.TLS_FALLBACK_CONNECTING)
            updateNotification("TLS 失败，已降级明文，正在连接...")
        } else {
            GrpcManager.updateState(GrpcConnectionState.CONNECTING)
            updateNotification("正在连接...")
        }
    }

    private fun showConnectedStatus() {
        if (GrpcManager.isTlsFallbackActive()) {
            GrpcManager.updateState(GrpcConnectionState.TLS_FALLBACK_CONNECTED)
            updateNotification("已连接到面板（明文传输）")
        } else {
            GrpcManager.updateState(GrpcConnectionState.CONNECTED)
            updateNotification("已连接到面板")
        }
    }

    private fun showReconnectStatus() {
        if (GrpcManager.isTlsFallbackActive()) {
            GrpcManager.updateState(GrpcConnectionState.TLS_FALLBACK_RECONNECTING)
            updateNotification("TLS 失败，已降级明文，正在重连...")
        } else {
            GrpcManager.updateState(GrpcConnectionState.RECONNECTING)
            updateNotification("连接断开，正在重连...")
        }
    }

    private fun isAuthenticationFailure(throwable: Throwable): Boolean {
        return throwable.causeSequence().any { cause ->
            when (cause) {
                is StatusException -> cause.status.code == Status.Code.UNAUTHENTICATED
                is StatusRuntimeException -> cause.status.code == Status.Code.UNAUTHENTICATED
                else -> cause.message?.contains("UNAUTHENTICATED", ignoreCase = true) == true
            }
        }
    }

    private fun isGenuineTlsFailure(throwable: Throwable): Boolean {
        return throwable.causeSequence().any { cause ->
            cause is SSLException ||
                cause is CertificateException ||
                cause.message?.let(::containsTlsFailureMarker) == true
        }
    }

    private fun containsTlsFailureMarker(message: String): Boolean {
        val normalized = message.lowercase()
        return TLS_FAILURE_MARKERS.any { marker -> marker in normalized }
    }

    private fun Throwable.causeSequence(): Sequence<Throwable> = generateSequence(this) { it.cause }

    // ── [修复问题4] WakeLock 无超时，由 onDestroy 显式释放 ──────────────────
    // 原实现使用 24 小时超时，长期运行的 agent 会在 24 小时后失去保活条件
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NezhaAgent::BgWakeLock")
        wakeLock?.acquire() // 无超时，由 onDestroy 释放
    }

    /**
     * 创建前台服务通知。
     *
     * [修复问题6] 通知文案根据实际连接状态动态设置，
     * 不再硬编码为 "Connected to dashboard"。
     *
     * @param statusText 当前连接状态描述
     */
    private fun createNotification(statusText: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId, "Nezha Agent Status", 
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("Nezha Agent Running")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    /**
     * 动态更新前台通知内容。
     *
     * [修复问题6] 根据 gRPC 实际连接状态更新通知，
     * 让用户能通过通知栏了解真实连接情况。
     */
    private fun updateNotification(statusText: String) {
        try {
            val notification = createNotification(statusText)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(1001, notification)
        } catch (e: Exception) {
            // 通知更新失败不应影响核心业务
            Logger.e("AgentService: 通知更新失败", e)
        }
    }

    override fun onDestroy() {
        Logger.i("Service is being destroyed globally by system or user intent.")
        super.onDestroy()
        audioPlayer.stop()
        FloatWindowManager.hide(this)
        job.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }

        // [修复问题5] 注销 NetworkCallback，防止泄漏和重复回调
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Logger.e("AgentService: 注销 NetworkCallback 异常", e)
            }
        }
        networkCallback = null
        connectivityManager = null

        GrpcManager.shutdown()
        // 清理 GPU 采集器缓存，确保服务重启时重新探测 sysfs 路径
        com.nezhahq.agent.collector.GpuCollector.resetCache()
        // 关闭持久化 Root Shell 会话，释放后台 su 进程资源，防止进程泄漏
        RootShell.shutdown()
        Logger.i("RootShell persistent session closed.")
        // 停止 VPN 流量计量服务（若正在运行）
        try {
            stopService(Intent(this, TrafficVpnService::class.java))
        } catch (_: Exception) {}
    }
}

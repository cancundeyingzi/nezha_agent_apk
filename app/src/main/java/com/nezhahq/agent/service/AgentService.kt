package com.nezhahq.agent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.executor.FileManager
import com.nezhahq.agent.executor.NatManager
import com.nezhahq.agent.executor.RemoteCapabilityPolicy
import com.nezhahq.agent.executor.TaskTypes
import com.nezhahq.agent.executor.TaskExecutor
import com.nezhahq.agent.executor.TerminalManager
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.grpc.GrpcManager
import com.nezhahq.agent.grpc.GrpcTransportMode
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.FloatWindowManager
import com.nezhahq.agent.util.KeepAliveAudioPlayer
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.StorageStatus
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
import java.util.concurrent.atomic.AtomicBoolean
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
        private const val TASK_INPUT_BUFFER_CAPACITY = 16
        private const val TASK_RESULT_BUFFER_CAPACITY = 8
        private const val MAX_STREAM_SESSIONS = 36
        private const val MAX_TERMINAL_SESSIONS = 2
        private const val MAX_NAT_SESSIONS = 32
        private const val MAX_FILE_MANAGER_SESSIONS = 2
        private const val MAX_STREAM_ID_BYTES = 256
        private const val MAX_NAT_HOST_BYTES = 1024
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
        // Enter foreground before configuration IO. This notification-only step does not start
        // agent business work.
        startAgentForeground("正在读取连接配置...")
        val storageStatus = ConfigStore.initialize(this)
        if (storageStatus == StorageStatus.UNAVAILABLE) {
            val reason = "配置存储不可用，探针服务已停止"
            updateNotification(reason)
            Logger.e("AgentService: $reason；未启动音频、悬浮窗、网络、gRPC 或 VPN")
            stopSelf()
            return
        }

        val audioEnabled = ConfigStore.getEnableKeepAliveAudio(this)
        val floatWindowEnabled = ConfigStore.getEnableFloatWindow(this)
        val vpnEnabled = ConfigStore.getEnableVpnTraffic(this)
        if (ConfigStore.initialize(this) == StorageStatus.UNAVAILABLE) {
            val reason = "读取连接配置失败，探针服务已停止"
            updateNotification(reason)
            Logger.e("AgentService: $reason；未启动音频、悬浮窗、网络、gRPC 或 VPN")
            stopSelf()
            return
        }
        updateNotification("正在连接...")

        Logger.i("Service started, configuring Grpc...")
        GrpcManager.initialize(this)
        if (ConfigStore.initialize(this) == StorageStatus.UNAVAILABLE) {
            Logger.e("AgentService: gRPC 初始化期间配置存储失效，停止服务且不启动保活或网络任务")
            GrpcManager.shutdown()
            updateNotification("连接配置读取失败，服务已停止")
            stopSelf()
            return
        }

        if (audioEnabled) {
            Logger.i("AgentService: 启用无声音频保活机制")
            audioPlayer.start()
        }
        if (floatWindowEnabled) {
            Logger.i("AgentService: 启用悬浮窗保活机制")
            FloatWindowManager.show(this)
        }
        acquireWakeLock()

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

        // ── VPN 流量兼容服务 ──────────────────────────────────────────────────
        Logger.i("AgentService: VPN 流量兼容配置 = $vpnEnabled")
        if (vpnEnabled) {
            // 占位 VPN 兼容行为仅保留在 Android 12 以下。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Logger.i("AgentService: VPN 流量兼容模式仅适用于 Android 12 以下，跳过启动")
            } else {
                try {
                    val prepareIntent = VpnService.prepare(this)
                    if (prepareIntent == null) {
                        // VPN 已被用户授权，直接启动兼容服务
                        val vpnIntent = Intent(this, TrafficVpnService::class.java)
                        startService(vpnIntent)
                        Logger.i("AgentService: VPN 流量兼容服务已启动")
                    } else {
                        Logger.i("AgentService: VPN 流量兼容已启用但权限未授权（需在工具页重新开启开关以触发授权），跳过启动")
                    }
                } catch (e: Exception) {
                    Logger.e("AgentService: VPN 流量兼容服务启动异常", e)
                }
            }
        }
    }

    private fun startAgentForeground(statusText: String) {
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                @Suppress("InlinedApi")
                startForeground(
                    1001,
                    createNotification(statusText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    1001,
                    createNotification(statusText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> startForeground(1001, createNotification(statusText))
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
                        val stub = GrpcManager.stub
                        if (geoIp != null && stub != null) {
                            DashboardSessionWatchdog.callWithin(
                                DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
                                "NetworkCallback ReportGeoIP"
                            ) {
                                stub.reportGeoIP(geoIp)
                            }
                        }
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
                        val hasValidConfig = ConfigStore.hasValidConfig(this@AgentService)
                        if (hasValidConfig) {
                            Logger.e("Grpc: 连接通道未初始化，5秒后按当前传输模式重试")
                            updateNotification("连接初始化失败，等待重试")
                        } else {
                            Logger.e("Grpc: 配置不完整，5秒后重试")
                            updateNotification("配置不完整，等待重试")
                        }
                        delay(DashboardSessionWatchdog.RECONNECT_BACKOFF_MS)
                        GrpcManager.initialize(this@AgentService)
                        continue
                    }

                    reportInitialDashboardInfo(stub)

                    // 3. Bidirectional streams (Status & Tasks). 只有收到面板回执后才显示已连接。
                    Logger.i("Handshake success. Opening Bidirectional streams for SystemState and Tasks...")
                    val connectionMarked = AtomicBoolean(false)
                    coroutineScope {
                        launch {
                            handleSystemStateStream(stub, connectionMarked)
                        }
                        
                        launch {
                            handleTaskStream(stub)
                        }
                    }
                } catch (e: CancellationException) {
                    Logger.i("Agent loop cancelled, propagating...")
                    throw e
                } catch (e: Exception) {
                    handleAgentLoopFailure(e)
                }
            }
        }
    }

    private suspend fun reportInitialDashboardInfo(stub: NezhaServiceCoroutineStub) {
        Logger.i("Sending Static Host Information (ReportSystemInfo2)...")
        val hostInfo = SystemInfoCollector.getHostInfo(this@AgentService, getAppVersionName())
        DashboardSessionWatchdog.callWithin(
            DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
            "ReportSystemInfo2"
        ) {
            stub.reportSystemInfo2(hostInfo)
        }

        Logger.i("Sending GeoIP Information...")
        val geoIp = GeoIpCollector.fetchGeoIP()
        if (geoIp != null) {
            DashboardSessionWatchdog.callWithin(
                DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
                "ReportGeoIP"
            ) {
                stub.reportGeoIP(geoIp)
            }
        }
    }

    private suspend fun handleSystemStateStream(
        stub: NezhaServiceCoroutineStub,
        connectionMarked: AtomicBoolean
    ) = coroutineScope {
        val stateFlow = flow {
            while (currentCoroutineContext().isActive) {
                emit(withContext(Dispatchers.Default) {
                    stateCollector.getState()
                })
                delay(DashboardSessionWatchdog.STATE_REPORT_INTERVAL_MS)
            }
        }
        val receiptChannel = stub.reportSystemState(stateFlow).produceIn(this)
        try {
            while (isActive) {
                DashboardSessionWatchdog.receiveWithin(
                    receiptChannel,
                    DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS,
                    "ReportSystemState receipt"
                )
                showConnectedStatusIfNeeded(connectionMarked)
            }
        } finally {
            receiptChannel.cancel()
        }
    }

    private suspend fun handleTaskStream(stub: NezhaServiceCoroutineStub) = coroutineScope {
        val resultChannel = Channel<TaskResult>(TASK_RESULT_BUFFER_CAPACITY)
        val shortTaskQueue = Channel<Task>(SHORT_TASK_QUEUE_CAPACITY)
        val streamSessions = createStreamSessionRegistry()

        val workerJobs = List(SHORT_TASK_WORKER_COUNT) {
            launch {
                for (task in shortTaskQueue) {
                    executeShortTask(task, resultChannel)
                }
            }
        }

        try {
            val taskChannel = stub.requestTask(resultChannel.receiveAsFlow())
                .buffer(TASK_INPUT_BUFFER_CAPACITY)
                .produceIn(this)
            try {
                while (isActive) {
                    val task = DashboardSessionWatchdog.receiveWithin(
                        taskChannel,
                        DashboardSessionWatchdog.TASK_IDLE_TIMEOUT_MS,
                        "RequestTask stream"
                    )
                    routeIncomingTask(
                        stub,
                        task,
                        shortTaskQueue,
                        resultChannel,
                        streamSessions
                    )
                }
            } finally {
                taskChannel.cancel()
            }
        } finally {
            workerJobs.forEach { it.cancel() }
            shortTaskQueue.close()
            resultChannel.close()
        }
    }

    private fun createStreamSessionRegistry(): StreamSessionRegistry =
        StreamSessionRegistry(
            maxTotal = MAX_STREAM_SESSIONS,
            maxByTaskType = mapOf(
                TaskTypes.TERMINAL to MAX_TERMINAL_SESSIONS,
                TaskTypes.NAT to MAX_NAT_SESSIONS,
                TaskTypes.FILE_MANAGER to MAX_FILE_MANAGER_SESSIONS
            )
        )

    private suspend fun CoroutineScope.routeIncomingTask(
        stub: NezhaServiceCoroutineStub,
        task: Task,
        shortTaskQueue: SendChannel<Task>,
        resultChannel: SendChannel<TaskResult>,
        streamSessions: StreamSessionRegistry
    ) {
        val capabilities = loadRemoteCapabilities()
        val denialReason = RemoteCapabilityPolicy.denialReason(
            taskType = task.type,
            capabilities = capabilities
        )
        if (denialReason != null) {
            Logger.i(
                "AgentService: 已拒绝未授权的远程 Shell 任务 " +
                    "(TaskID=${task.id}, Type=${task.type})"
            )
            reportTaskFailure(task, denialReason, resultChannel)
            return
        }

        if (task.type in TaskTypes.STREAM_TASKS) {
            launchStreamTask(stub, task, resultChannel, streamSessions)
        } else {
            val admission = enqueueShortTaskWithBackpressure(
                task,
                shortTaskQueue,
                resultChannel
            )
            if (admission == ShortTaskAdmission.REJECTED_QUEUE_FULL) {
                Logger.e(
                    "AgentService: 短任务队列已满，已拒绝并背压上报 " +
                        "TaskID=${task.id}, Type=${task.type}"
                )
            }
        }
    }

    private suspend fun CoroutineScope.launchStreamTask(
        stub: NezhaServiceCoroutineStub,
        task: Task,
        resultChannel: SendChannel<TaskResult>,
        streamSessions: StreamSessionRegistry
    ) {
        val request = try {
            parseStreamTask(task)
        } catch (e: Exception) {
            val message = "Invalid stream task: ${e.message ?: "unknown error"}"
            Logger.e(
                "AgentService: 流式任务参数无效 TaskID=${task.id}, Type=${task.type}",
                e
            )
            reportTaskFailure(task, message, resultChannel)
            return
        }

        val lease = when (
            val admission = streamSessions.tryAcquire(task.type, request.streamId)
        ) {
            is StreamSessionAdmission.Accepted -> admission.lease
            is StreamSessionAdmission.Rejected -> {
                Logger.e(
                    "AgentService: 已拒绝流式任务 TaskID=${task.id}, " +
                        "Type=${task.type}: ${admission.reason}"
                )
                reportTaskFailure(task, admission.reason, resultChannel)
                return
            }
        }

        val sessionJob = when (request) {
            is StreamTaskRequest.Terminal -> launchTerminalSession(stub, request)
            is StreamTaskRequest.Nat -> launchNatSession(stub, request)
            is StreamTaskRequest.FileManager -> launchFileManagerSession(stub, request)
        }
        sessionJob.invokeOnCompletion {
            lease.close()
        }
    }

    private fun parseStreamTask(task: Task): StreamTaskRequest {
        val json = org.json.JSONObject(task.data)
        val streamId = json.getString("StreamID")
        require(streamId.isNotBlank()) { "StreamID must not be blank." }
        require(streamId.toByteArray(Charsets.UTF_8).size <= MAX_STREAM_ID_BYTES) {
            "StreamID exceeds the $MAX_STREAM_ID_BYTES-byte limit."
        }

        return when (task.type) {
            TaskTypes.TERMINAL -> StreamTaskRequest.Terminal(task, streamId)
            TaskTypes.NAT -> {
                val host = json.getString("Host")
                require(host.isNotBlank()) { "NAT Host must not be blank." }
                require(host.toByteArray(Charsets.UTF_8).size <= MAX_NAT_HOST_BYTES) {
                    "NAT Host exceeds the $MAX_NAT_HOST_BYTES-byte limit."
                }
                StreamTaskRequest.Nat(task, streamId, host)
            }
            TaskTypes.FILE_MANAGER -> StreamTaskRequest.FileManager(task, streamId)
            else -> error("Task type ${task.type} is not a stream task.")
        }
    }

    private fun CoroutineScope.launchTerminalSession(
        stub: NezhaServiceCoroutineStub,
        request: StreamTaskRequest.Terminal
    ): Job = launch {
        runStreamTask("终端", request.task) {
            Logger.i(
                "收到终端任务 (TaskID=${request.task.id}, StreamID=${request.streamId})"
            )
            TerminalManager(this@AgentService, stub, request.streamId).run()
        }
    }

    private fun CoroutineScope.launchNatSession(
        stub: NezhaServiceCoroutineStub,
        request: StreamTaskRequest.Nat
    ): Job = launch {
        runStreamTask("NAT 内网穿透", request.task) {
            Logger.i(
                "收到 NAT 内网穿透任务 " +
                    "(TaskID=${request.task.id}, StreamID=${request.streamId}, Host=${request.host})"
            )
            NatManager(stub, request.streamId, request.host).run()
        }
    }

    private fun CoroutineScope.launchFileManagerSession(
        stub: NezhaServiceCoroutineStub,
        request: StreamTaskRequest.FileManager
    ): Job = launch {
        runStreamTask("文件管理器", request.task) {
            Logger.i(
                "收到文件管理器任务 " +
                    "(TaskID=${request.task.id}, StreamID=${request.streamId})"
            )
            FileManager(this@AgentService, stub, request.streamId).run()
        }
    }

    private suspend fun runStreamTask(
        taskName: String,
        task: Task,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(
                "$taskName 任务执行失败 (TaskID=${task.id}, Type=${task.type})",
                e
            )
        }
    }

    private suspend fun reportTaskFailure(
        task: Task,
        message: String,
        resultChannel: SendChannel<TaskResult>
    ) {
        resultChannel.send(buildFailedTaskResult(task, message))
    }

    private sealed interface StreamTaskRequest {
        val task: Task
        val streamId: String

        data class Terminal(
            override val task: Task,
            override val streamId: String
        ) : StreamTaskRequest

        data class Nat(
            override val task: Task,
            override val streamId: String,
            val host: String
        ) : StreamTaskRequest

        data class FileManager(
            override val task: Task,
            override val streamId: String
        ) : StreamTaskRequest
    }

    private suspend fun executeShortTask(
        task: Task,
        resultChannel: SendChannel<TaskResult>
    ) {
        // Recheck immediately before execution so disabling the setting also rejects queued commands.
        val capabilities = loadRemoteCapabilities()
        val result = TaskExecutor.executeTask(
            task,
            capabilities = capabilities
        )
        resultChannel.send(result)
    }

    private fun loadRemoteCapabilities(): RemoteCapabilities = RemoteCapabilities(
        shellEnabled = ConfigStore.getEnableRemoteCommand(this),
        fileManagerEnabled = ConfigStore.getEnableRemoteFileManager(this),
        natEnabled = ConfigStore.getEnableRemoteNat(this)
    )

    private fun showConnectingStatus() {
        if (GrpcManager.isPlaintextModeActive()) {
            GrpcManager.updateState(GrpcConnectionState.PLAINTEXT_CONNECTING)
            updateNotification("明文模式，正在连接...")
        } else {
            GrpcManager.updateState(GrpcConnectionState.CONNECTING)
            updateNotification("正在连接...")
        }
    }

    private fun showConnectedStatus() {
        if (GrpcManager.isPlaintextModeActive()) {
            GrpcManager.updateState(GrpcConnectionState.PLAINTEXT_CONNECTED)
            updateNotification("已连接到面板（明文传输）")
        } else {
            GrpcManager.updateState(GrpcConnectionState.CONNECTED)
            updateNotification("已连接到面板")
        }
    }

    private fun showConnectedStatusIfNeeded(connectionMarked: AtomicBoolean) {
        if (connectionMarked.compareAndSet(false, true)) {
            showConnectedStatus()
            GrpcManager.recordConnectionSuccess()
        }
    }

    private fun showReconnectStatus() {
        if (GrpcManager.isPlaintextModeActive()) {
            GrpcManager.updateState(GrpcConnectionState.PLAINTEXT_RECONNECTING)
            updateNotification("明文模式，正在重连...")
        } else {
            GrpcManager.updateState(GrpcConnectionState.RECONNECTING)
            updateNotification("连接断开，正在重连...")
        }
    }

    private suspend fun handleAgentLoopFailure(e: Exception) {
        val isAuthError = isAuthenticationFailure(e)
        if (isAuthError) {
            // 认证失败不计入 TLS 失败计数（问题在密钥/UUID，非 TLS）
            GrpcManager.updateState(GrpcConnectionState.AUTH_FAILED)
            updateNotification("认证失败，请检查密钥和 UUID")
            Logger.e("Agent loop: 认证失败，请检查密钥和 UUID 配置", e)
        } else {
            if (GrpcManager.currentTransportMode() == GrpcTransportMode.TLS
                && isGenuineTlsFailure(e)
            ) {
                Logger.e("Grpc: TLS 连接失败，将继续按 TLS 重试，不自动切换明文", e)
            }
            showReconnectStatus()
            Logger.e("Agent loop terminated/failed; closing channel before reconnect", e)
        }
        GrpcManager.shutdown(preserveConnectionState = true)
        delay(DashboardSessionWatchdog.RECONNECT_BACKOFF_MS)
        Logger.i("Re-initializing GrpcManager to attempt recovery...")
        GrpcManager.initialize(this@AgentService)
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

    private fun getAppVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            Logger.e("AgentService: 读取应用版本号失败", e)
            "unknown"
        }
    }

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
        // 停止 VPN 流量兼容服务（若正在运行）
        try {
            stopService(Intent(this, TrafficVpnService::class.java))
        } catch (_: Exception) {}
    }
}

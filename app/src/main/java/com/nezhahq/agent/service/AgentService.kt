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
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.KeepAliveAudioPlayer
import com.nezhahq.agent.util.FloatWindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import proto.Nezha.Receipt
import proto.Nezha.TaskResult

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
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    1001,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> startForeground(1001, createNotification())
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

        // ── VPN 流量计量服务：在无 Root/Shizuku 且 Android < 12 时的兜底方案 ──
        // 仅当用户在工具页手动开启且 VPN 权限已预授权时才启动
        val vpnEnabled = ConfigStore.getEnableVpnTraffic(this)
        Logger.i("AgentService: VPN 流量计量配置 = $vpnEnabled")
        if (vpnEnabled) {
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

    private fun setupNetworkListener() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
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
        })
    }

    private fun startWorkLoop() {
        scope.launch {
            while (isActive) {
                try {
                    GrpcManager.updateState(GrpcConnectionState.CONNECTING)
                    Logger.i("Preparing to handshake and send reports to Dashboard...")
                    val stub = GrpcManager.stub ?: throw Exception("Stub not initialized")
                    
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
                    GrpcManager.updateState(GrpcConnectionState.CONNECTED)
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
                            stub.reportSystemState(stateFlow).collect { receipt ->
                                // Optional logic when dashboard acks state stream chunk (ignored typically)
                            }
                        }
                        
                        launch {
                            val resultChannel = kotlinx.coroutines.channels.Channel<TaskResult>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                            stub.requestTask(resultChannel.receiveAsFlow()).collect { task ->
                                launch {
                                    when (task.type) {
                                        8L -> {
                                            // ── TaskTypeTerminalGRPC ──
                                            // Dashboard 请求打开终端，解析 StreamID 并启动 IOStream
                                            try {
                                                val json = org.json.JSONObject(task.data)
                                                val streamId = json.getString("StreamID")
                                                Logger.i("收到终端任务 (TaskID=${task.id}, StreamID=$streamId)")
                                                val terminal = TerminalManager(
                                                    this@AgentService, stub, streamId
                                                )
                                                terminal.run()
                                            } catch (e: Exception) {
                                                if (e !is CancellationException) {
                                                    Logger.e("终端任务执行失败", e)
                                                }
                                            }
                                        }
                                        9L -> {
                                            // ── TaskTypeNAT（内网穿透/反向代理）──
                                            // Dashboard 请求建立 NAT 通道，解析 StreamID 和 Host，
                                            // 通过 IOStream 双向流将远端请求转发到本地目标服务
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
                                        11L -> {
                                            // ── TaskTypeFM（文件管理器）──
                                            // Dashboard 请求打开文件管理器，解析 StreamID 并启动 IOStream
                                            // 支持浏览目录 / 下载文件 / 上传文件
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
                                        else -> {
                                            // 其他任务类型：HTTP/ICMP/TCP/Command 等
                                            val result = TaskExecutor.executeTask(task, isCommandEnabled = false)
                                            resultChannel.send(result)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 检测认证失败（gRPC UNAUTHENTICATED 状态码）
                    val isAuthError = e.message?.contains("UNAUTHENTICATED", ignoreCase = true) == true
                    if (isAuthError) {
                        // 认证失败不计入 TLS 失败计数（问题在密钥/UUID，非 TLS）
                        GrpcManager.updateState(GrpcConnectionState.AUTH_FAILED)
                        Logger.e("Agent loop: 认证失败，请检查密钥和 UUID 配置", e)
                    } else {
                        // ── TLS 自动降级逻辑 ──────────────────────────────────
                        // 如果当前使用 TLS 且连接失败，记录失败次数
                        // 达到阈值后自动降级为明文连接
                        if (!GrpcManager.isTlsFallbackActive()) {
                            val shouldFallback = GrpcManager.recordTlsFailure()
                            if (shouldFallback) {
                                Logger.i("Agent loop: TLS 连续失败已达阈值，下次重连将使用明文传输")
                            }
                        }
                        GrpcManager.updateState(GrpcConnectionState.RECONNECTING)
                        Logger.e("Agent loop terminated/failed", e)
                    }
                    delay(5000) // Reconnect backoff
                    Logger.i("Re-initializing GrpcManager to attempt recovery...")
                    GrpcManager.initialize(this@AgentService)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NezhaAgent::BgWakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // Support for long-running
    }

    private fun createNotification(): Notification {
        val channelId = "nezha_agent_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Nezha Agent Status", 
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nezha Agent Running")
            .setContentText("Connected to dashboard")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        Logger.i("Service is being destroyed globally by system or user intent.")
        super.onDestroy()
        audioPlayer.stop()
        FloatWindowManager.hide(this)
        job.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
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

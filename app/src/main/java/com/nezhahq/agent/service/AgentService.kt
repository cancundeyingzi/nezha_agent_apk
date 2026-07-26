package com.nezhahq.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nezhahq.agent.grpc.GrpcManager
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.SharedPreferencesConfigRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Android lifecycle, foreground notification, and reload-command adapter.
 *
 * Business work and resource ownership live in an [AgentRuntime] instance.
 */
class AgentService : Service() {
    private val commandJob = SupervisorJob()
    private val commandScope = CoroutineScope(
        Dispatchers.IO +
            commandJob +
            CoroutineExceptionHandler { _, throwable ->
                Logger.e("AgentService: reload worker failed", throwable)
            }
    )

    private lateinit var runtimeController: AgentRuntimeController
    private lateinit var commandProcessor: AgentReloadCommandProcessor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentServiceRunningState.onCreated()
        startAgentForeground("正在读取连接配置...")

        runtimeController = AgentRuntimeController(
            factory = AgentRuntimeFactory { config ->
                AgentRuntime(
                    context = applicationContext,
                    config = config,
                    statusSink = { state, message ->
                        GrpcManager.updateState(state)
                        updateNotification(message)
                    }
                )
            },
            finalCleanup = {
                RootShell.shutdown()
                Logger.i("AgentService: process-owned RootShell resources closed.")
            }
        )
        commandProcessor = AgentReloadCommandProcessor(
            scope = commandScope,
            repository = SharedPreferencesConfigRepository(applicationContext),
            controller = runtimeController,
            ioDispatcher = Dispatchers.IO,
            onFailureWithoutRuntime = { failure ->
                Logger.e("AgentService: configuration/runtime start failed; stopping service", failure)
                updateNotification("连接配置无效或不可用，服务已停止")
                stopSelf()
            },
            onFailureWithRuntime = { failure ->
                Logger.e("AgentService: reload rejected; existing runtime remains active", failure)
                updateNotification("重载失败，继续使用当前连接")
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val decision = AgentServiceCommandPolicy.decide(intent?.action)
        when (decision.command) {
            AgentServiceCommand.START_OR_RELOAD,
            AgentServiceCommand.RECOVER_LATEST -> commandProcessor.request()
            AgentServiceCommand.IGNORE ->
                Logger.i("AgentService: ignored unknown action ${intent?.action}")
        }
        return decision.startResult
    }

    override fun onDestroy() {
        AgentServiceRunningState.onDestroyed()
        try {
            runBlocking(Dispatchers.IO) {
                commandProcessor.close()
                runtimeController.stop()
            }
        } finally {
            commandScope.cancel()
            super.onDestroy()
        }
    }

    private fun startAgentForeground(statusText: String) {
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                @Suppress("InlinedApi")
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(statusText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(statusText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> startForeground(NOTIFICATION_ID, createNotification(statusText))
        }
    }

    private fun createNotification(statusText: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Nezha Agent Status",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Nezha Agent Running")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun updateNotification(statusText: String) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, createNotification(statusText))
        } catch (exception: Exception) {
            Logger.e("AgentService: notification update failed", exception)
        }
    }

    companion object {
        const val ACTION_START_OR_RELOAD =
            "com.nezhahq.agent.action.START_OR_RELOAD"

        private const val NOTIFICATION_CHANNEL_ID = "nezha_agent_service"
        private const val NOTIFICATION_ID = 1001
        fun startOrReload(context: Context) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_START_OR_RELOAD)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Requests a persisted-config reload only when this process owns a live service instance.
         */
        fun requestReloadIfRunning(context: Context): Boolean {
            if (!AgentServiceRunningState.canRequestReload()) return false
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_START_OR_RELOAD)
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        internal fun isRunningInProcess(): Boolean =
            AgentServiceRunningState.canRequestReload()
    }
}

package com.nezhahq.agent.service.keepalive

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.nezhahq.agent.service.TrafficVpnService
import com.nezhahq.agent.util.Logger

internal interface PlaceholderVpnHost {
    fun startIfPermitted()
    fun stop()
}

internal class PlaceholderVpnKeepAlive(
    private val host: PlaceholderVpnHost
) : KeepAliveResource {
    private var state = State.UNCONFIGURED

    override suspend fun setEnabled(enabled: Boolean) = synchronized(this) {
        val next = if (enabled) State.ENABLED else State.DISABLED
        if (state == next) return@synchronized
        state = next
        if (enabled) start() else stop()
    }

    override suspend fun close() = synchronized(this) {
        if (state == State.CLOSED) return@synchronized
        state = State.CLOSED
        stop()
    }

    private fun start() {
        runCatching { host.startIfPermitted() }
            .onFailure { Logger.e("$TAG: VPN 流量兼容服务启动异常", it) }
    }

    private fun stop() {
        runCatching { host.stop() }
            .onFailure { Logger.e("$TAG: VPN 流量兼容服务停止异常", it) }
    }

    private enum class State {
        UNCONFIGURED,
        ENABLED,
        DISABLED,
        CLOSED
    }

    private companion object {
        const val TAG = "PlaceholderVpnKeepAlive"
    }
}

internal class AndroidPlaceholderVpnHost(
    private val context: Context
) : PlaceholderVpnHost {
    override fun startIfPermitted() {
        Logger.i("AgentService: VPN 流量兼容配置 = true")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Logger.i("AgentService: VPN 流量兼容模式仅适用于 Android 12 以下，跳过启动")
            return
        }
        if (TrafficVpnService.isRunning()) return

        if (VpnService.prepare(context) == null) {
            context.startService(Intent(context, TrafficVpnService::class.java))
            Logger.i("AgentService: VPN 流量兼容服务已启动")
        } else {
            Logger.i("AgentService: VPN 流量兼容已启用但权限未授权，跳过启动")
        }
    }

    override fun stop() {
        context.stopService(Intent(context, TrafficVpnService::class.java))
    }
}

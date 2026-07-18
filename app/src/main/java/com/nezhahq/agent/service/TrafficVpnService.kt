package com.nezhahq.agent.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.nezhahq.agent.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 11 及以下的占位 VPN 兼容服务。
 *
 * 部分 ROM 只有在 VPN 会话存在时才允许应用读取系统网络计数。该服务只建立一条
 * 不承载真实流量的 `/32` 路由，不配置 DNS、不读取 TUN 数据，也不代理数据包。
 */
class TrafficVpnService : VpnService() {
    companion object {
        private const val TAG = "TrafficVPN"
        /** RFC 5737 TEST-NET-1 address, reserved for documentation and not real traffic. */
        private const val PLACEHOLDER_ADDRESS = "192.0.2.1"
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Logger.i("$TAG: Android 12+ 不启用占位 VPN")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running.get()) return START_STICKY

        vpnInterface = establishVpn()
        if (vpnInterface == null) {
            Logger.e("$TAG: 占位 VPN 接口建立失败")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        running.set(true)
        Logger.i("$TAG: 占位 VPN 已启动（不代理数据包）")
        return START_STICKY
    }

    override fun onRevoke() {
        Logger.i("$TAG: VPN 权限已撤销")
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        Logger.i("$TAG: 占位 VPN 已停止")
        super.onDestroy()
    }

    private fun establishVpn(): ParcelFileDescriptor? = try {
        Builder()
            .setSession("哪吒流量兼容模式")
            .addAddress(PLACEHOLDER_ADDRESS, 32)
            .addRoute(PLACEHOLDER_ADDRESS, 32)
            .allowFamily(OsConstants.AF_INET6)
            .setMtu(1500)
            .setBlocking(false)
            .apply {
                runCatching { addDisallowedApplication(packageName) }
                    .onFailure { Logger.e("$TAG: 排除自身应用失败", it) }
            }
            .establish()
    } catch (e: Exception) {
        Logger.e("$TAG: 建立占位 VPN 接口异常", e)
        null
    }
}

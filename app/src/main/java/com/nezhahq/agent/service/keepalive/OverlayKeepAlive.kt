package com.nezhahq.agent.service.keepalive

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.nezhahq.agent.util.Logger
import kotlin.random.Random

internal fun interface OverlayHandle {
    fun remove()
}

internal interface OverlayHost {
    fun hasPermission(): Boolean
    fun add(): OverlayHandle
}

internal class OverlayKeepAlive(
    private val host: OverlayHost
) : KeepAliveResource {
    private var handle: OverlayHandle? = null

    override suspend fun setEnabled(enabled: Boolean) = synchronized(this) {
        if (enabled) start() else remove()
    }

    override suspend fun close() = synchronized(this) { remove() }

    private fun start() {
        if (handle != null) return
        if (!host.hasPermission()) {
            Logger.i("$TAG: 没有悬浮窗权限，跳过悬浮窗保活")
            return
        }

        var added = false
        try {
            handle = host.add()
            added = true
            Logger.i("$TAG: 悬浮窗已添加")
        } catch (e: Exception) {
            Logger.e("$TAG: 添加悬浮窗失败", e)
        } finally {
            if (!added) handle = null
        }
    }

    private fun remove() {
        val current = handle ?: return
        try {
            current.remove()
            Logger.i("$TAG: 悬浮窗已移除")
        } catch (e: Exception) {
            Logger.e("$TAG: 移除悬浮窗失败", e)
        } finally {
            handle = null
        }
    }

    private companion object {
        const val TAG = "OverlayKeepAlive"
    }
}

internal class AndroidOverlayHost(
    private val context: Context
) : OverlayHost {
    override fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    override fun add(): OverlayHandle {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = FrameLayout(context).apply { setBackgroundColor(0x00000000) }
        val randomX = Random.nextInt(10, 100)
        val randomY = Random.nextInt(10, 100)
        val params = WindowManager.LayoutParams(
            1,
            1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = randomX
            y = randomY
        }
        windowManager.addView(view, params)
        Logger.i("OverlayKeepAlive: 悬浮窗位置 ($randomX, $randomY)")
        return WindowOverlayHandle(windowManager, view)
    }
}

private class WindowOverlayHandle(
    private val windowManager: WindowManager,
    private val view: View
) : OverlayHandle {
    override fun remove() = windowManager.removeView(view)
}

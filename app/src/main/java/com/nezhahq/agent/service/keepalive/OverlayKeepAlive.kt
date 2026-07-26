package com.nezhahq.agent.service.keepalive

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.nezhahq.agent.util.Logger
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface OverlayHandle {
    /** Returns true only when the view is confirmed detached. Must run on the main thread. */
    fun remove(): Boolean

    /**
     * Schedules [remove] on the main thread and returns immediately.
     *
     * Used when the caller is being cancelled and can no longer wait for the main thread. See
     * [OverlayKeepAlive.close].
     */
    fun removeWhenMainThreadIsFree()
}

internal interface OverlayHost {
    fun hasPermission(): Boolean
    fun add(): OverlayHandle
}

/**
 * Owns the keep-alive overlay window.
 *
 * [WindowManager] is main-thread only, and this resource is enabled from the main thread but torn
 * down from a background cleanup scope. Confining every host call to [mainDispatcher] here keeps
 * that asymmetry from reaching the window API, where a wrong-thread removal would leak the window.
 */
internal class OverlayKeepAlive(
    private val host: OverlayHost,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : KeepAliveResource {
    private val lifecycleMutex = Mutex()

    /** Volatile because [close]'s cancellation path reads it from outside [lifecycleMutex]. */
    @Volatile
    private var handle: OverlayHandle? = null

    override suspend fun setEnabled(enabled: Boolean) = lifecycleMutex.withLock {
        if (enabled) start() else remove()
    }

    /**
     * Removes the window, handing the work to the main looper if this coroutine is cut short.
     *
     * Removal has to happen on the main thread, and teardown runs under a deadline. When the main
     * thread is busy, that deadline used to cancel the removal mid-suspend and drop the handle with
     * it, stranding a `TYPE_APPLICATION_OVERLAY` window that nothing could reach again — one more
     * per reload. Posting it instead means a cancelled teardown still ends with the window gone.
     */
    override suspend fun close() {
        try {
            lifecycleMutex.withLock { remove() }
        } catch (cancellation: CancellationException) {
            // Outside the lock on purpose: cancellation can also strike while waiting for it, and
            // an abandoned window has to be handed off from either point. Racing a concurrent
            // removal is harmless — removing a detached view reports success.
            handle?.removeWhenMainThreadIsFree()
            handle = null
            throw cancellation
        }
    }

    private suspend fun start() {
        if (handle != null) return
        if (!host.hasPermission()) {
            Logger.i("$TAG: 没有悬浮窗权限，跳过悬浮窗保活")
            return
        }

        handle = try {
            withContext(mainDispatcher) { host.add() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Logger.e("$TAG: 添加悬浮窗失败", e)
            return
        }
        Logger.i("$TAG: 悬浮窗已添加")
    }

    private suspend fun remove() {
        val current = handle ?: return
        val removed = try {
            withContext(mainDispatcher) { current.remove() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Logger.e("$TAG: 移除悬浮窗失败", e)
            return
        }
        if (removed) {
            handle = null
            Logger.i("$TAG: 悬浮窗已移除")
        } else {
            Logger.e("$TAG: 悬浮窗移除状态不确定，将保留所有权并重试")
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
    override fun remove(): Boolean {
        try {
            windowManager.removeView(view)
            if (!view.isAttachedToWindow) return true
        } catch (firstFailure: Exception) {
            if (!view.isAttachedToWindow) return true
            Logger.e("OverlayKeepAlive: 常规移除失败，尝试立即移除", firstFailure)
        }

        return try {
            windowManager.removeViewImmediate(view)
            true
        } catch (fallbackFailure: Exception) {
            Logger.e("OverlayKeepAlive: 立即移除失败", fallbackFailure)
            !view.isAttachedToWindow
        }
    }

    /**
     * Queues the removal on the main looper without waiting for it.
     *
     * The window belongs to the process, not to the coroutine that asked for it to go away, so the
     * last-resort teardown must survive that coroutine being cancelled. Nothing observes the
     * result: by the time this is used, the caller has already run out of time to react.
     */
    override fun removeWhenMainThreadIsFree() {
        Handler(Looper.getMainLooper()).post {
            if (remove()) Logger.i("OverlayKeepAlive: 悬浮窗已在主线程空闲后移除")
        }
    }
}

package com.nezhahq.agent.service.keepalive

import android.content.Context
import android.os.PowerManager
import com.nezhahq.agent.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface WakeLockLease {
    fun acquire(timeoutMillis: Long)
    fun release()
}

internal fun interface RenewalHandle {
    fun cancel()
}

internal interface RenewalScheduler {
    fun schedule(initialDelayMillis: Long, intervalMillis: Long, action: () -> Unit): RenewalHandle
}

internal class WakeLockKeepAlive(
    private val lease: WakeLockLease,
    private val scheduler: RenewalScheduler
) : KeepAliveResource {
    private var active = false
    private var generation = 0L
    private var renewal: RenewalHandle? = null

    override suspend fun setEnabled(enabled: Boolean) = synchronized(this) {
        if (enabled) start() else stop()
    }

    override suspend fun close() = synchronized(this) { stop() }

    private fun start() {
        if (active) return
        val token = ++generation
        try {
            lease.acquire(LEASE_MILLIS)
            active = true
            renewal = scheduler.schedule(RENEWAL_MILLIS, RENEWAL_MILLIS) {
                renew(token)
            }
        } catch (e: Exception) {
            active = false
            renewal = null
            runCatching { lease.release() }
            Logger.e("$TAG: 获取或调度 WakeLock 失败", e)
        }
    }

    @Synchronized
    private fun renew(token: Long) {
        if (!active || generation != token) return
        runCatching { lease.acquire(LEASE_MILLIS) }
            .onFailure { Logger.e("$TAG: 续租 WakeLock 失败", it) }
    }

    private fun stop() {
        if (!active && renewal == null) return
        active = false
        generation++
        renewal?.cancel()
        renewal = null
        runCatching { lease.release() }
            .onFailure { Logger.e("$TAG: 释放 WakeLock 失败", it) }
    }

    internal companion object {
        const val LEASE_MILLIS = 10 * 60 * 1_000L
        const val RENEWAL_MILLIS = 5 * 60 * 1_000L
        private const val TAG = "WakeLockKeepAlive"
    }
}

internal class AndroidWakeLockLease(context: Context) : WakeLockLease {
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NezhaAgent::BgWakeLock")
        .apply { setReferenceCounted(false) }

    override fun acquire(timeoutMillis: Long) {
        wakeLock.acquire(timeoutMillis)
    }

    override fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }
}

internal class CoroutineRenewalScheduler(
    private val scope: CoroutineScope
) : RenewalScheduler {
    override fun schedule(
        initialDelayMillis: Long,
        intervalMillis: Long,
        action: () -> Unit
    ): RenewalHandle {
        val job = scope.launch {
            delay(initialDelayMillis)
            while (isActive) {
                action()
                delay(intervalMillis)
            }
        }
        return JobRenewalHandle(job)
    }
}

private class JobRenewalHandle(
    private val job: Job
) : RenewalHandle {
    override fun cancel() = job.cancel()
}

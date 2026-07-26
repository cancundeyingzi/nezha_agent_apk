package com.nezhahq.agent.service.keepalive

import android.content.Context
import com.nezhahq.agent.core.model.KeepAliveSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface KeepAliveResource {
    suspend fun setEnabled(enabled: Boolean)
    suspend fun close()
}

internal class KeepAliveController(
    private val audio: KeepAliveResource,
    private val overlay: KeepAliveResource,
    private val wakeLock: KeepAliveResource,
    private val vpn: KeepAliveResource
) {
    private val lifecycleMutex = Mutex()

    suspend fun reconfigure(settings: KeepAliveSettings) = lifecycleMutex.withLock {
        configureAudio(settings.audio)
        configureOverlay(settings.overlay)
        configureWakeLock()
        configureVpn(settings.vpn)
    }

    suspend fun close() = lifecycleMutex.withLock {
        closeVpn()
        closeWakeLock()
        closeOverlay()
        closeAudio()
    }

    private suspend fun configureAudio(enabled: Boolean) = audio.setEnabled(enabled)

    private suspend fun configureOverlay(enabled: Boolean) = overlay.setEnabled(enabled)

    private suspend fun configureWakeLock() = wakeLock.setEnabled(true)

    private suspend fun configureVpn(enabled: Boolean) = vpn.setEnabled(enabled)

    private suspend fun closeAudio() = audio.close()

    private suspend fun closeOverlay() = overlay.close()

    private suspend fun closeWakeLock() = wakeLock.close()

    private suspend fun closeVpn() = vpn.close()

    companion object {
        fun create(context: Context, scope: CoroutineScope): KeepAliveController {
            val appContext = context.applicationContext
            return KeepAliveController(
                audio = AudioKeepAlive(scope),
                overlay = OverlayKeepAlive(AndroidOverlayHost(appContext)),
                wakeLock = WakeLockKeepAlive(
                    AndroidWakeLockLease(appContext),
                    CoroutineRenewalScheduler(scope)
                ),
                vpn = PlaceholderVpnKeepAlive(AndroidPlaceholderVpnHost(appContext))
            )
        }
    }
}

package com.nezhahq.agent

import android.app.Application
import kotlin.concurrent.thread

class NezhaAgentApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Off the main thread: opening storage is what migrates the legacy encrypted store, and
        // building its master key is an Android Keystore operation that takes seconds on some
        // devices — long enough to make cold start an ANR.
        //
        // Nothing has to wait for it. Privileged access is denied until the stored grant is
        // applied, so a collector that samples during the gap reports unprivileged values for one
        // cycle instead of acting on a grant that was never confirmed.
        thread(name = "nezha-config-init", isDaemon = true) {
            container.applyPersistedRootAuthorization()
        }
    }
}

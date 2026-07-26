package com.nezhahq.agent

import android.app.Application
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.StorageStatus

class NezhaAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val storageStatus = ConfigStore.initialize(this)
        val rootModeEnabled =
            storageStatus != StorageStatus.UNAVAILABLE && ConfigStore.getRootMode(this)
        RootShell.configureAuthorization(rootModeEnabled)
    }
}

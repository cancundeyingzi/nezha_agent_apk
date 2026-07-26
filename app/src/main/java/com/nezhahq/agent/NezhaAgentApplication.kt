package com.nezhahq.agent

import android.app.Application
import com.nezhahq.agent.util.ConfigStore

class NezhaAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val storageStatus = ConfigStore.initialize(this)
        ConfigStore.synchronizeRootAuthorization(this, storageStatus)
    }
}

package com.nezhahq.agent

import android.app.Application
import com.nezhahq.agent.util.RootShell

class NezhaAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RootShell.initialize(this)
    }
}

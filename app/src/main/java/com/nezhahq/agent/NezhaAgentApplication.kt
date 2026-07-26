package com.nezhahq.agent

import android.app.Application

class NezhaAgentApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Opening storage is what reports its status, and the container's repository does that on
        // first read; applying the persisted grant here keeps privileged queries correct before any
        // service starts.
        container.applyPersistedRootAuthorization()
    }
}

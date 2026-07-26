package com.nezhahq.agent

import android.content.Context
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.grpc.ConnectionStateHolder
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.SharedPreferencesConfigRepository

/**
 * The application's object graph, assembled once in [NezhaAgentApplication].
 *
 * Hand-written on purpose: the graph is three objects wired into three entry points (service,
 * view model, boot receiver), well below the point where an annotation processor earns its build
 * cost. Its providers map one-to-one onto `@Provides` should that change.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val configRepository: ConfigRepository = SharedPreferencesConfigRepository(appContext)

    /** Written by the running service, observed by the UI. */
    val connectionState = ConnectionStateHolder()

    /**
     * Applies the persisted root-mode grant to this process at startup.
     *
     * Routed through the storage layer so it takes the same ordering boundary as a root-mode
     * write; otherwise a startup read could race a concurrent toggle and re-enable stale state.
     */
    fun applyPersistedRootAuthorization() {
        ConfigStore.synchronizeRootAuthorization(appContext)
    }
}

/** Reaches the container from any component holding a [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as NezhaAgentApplication).container

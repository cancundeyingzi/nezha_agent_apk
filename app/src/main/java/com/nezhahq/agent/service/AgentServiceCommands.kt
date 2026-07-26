package com.nezhahq.agent.service

import android.app.Service
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.model.AgentConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AgentServiceCommand {
    START_OR_RELOAD,
    RECOVER_LATEST,
    IGNORE
}

internal data class AgentServiceCommandDecision(
    val command: AgentServiceCommand,
    val startResult: Int
)

internal object AgentServiceCommandPolicy {
    fun decide(action: String?): AgentServiceCommandDecision =
        AgentServiceCommandDecision(
            command = fromAction(action),
            startResult = Service.START_STICKY
        )

    fun fromAction(action: String?): AgentServiceCommand = when (action) {
        null -> AgentServiceCommand.RECOVER_LATEST
        AgentService.ACTION_START_OR_RELOAD -> AgentServiceCommand.START_OR_RELOAD
        else -> AgentServiceCommand.IGNORE
    }
}

internal sealed interface ConfigLoadOutcome {
    data class Ready(val config: AgentConfig) : ConfigLoadOutcome
    data class Failed(val error: Throwable) : ConfigLoadOutcome
}

/**
 * Conflates pending requests and loads configuration only when a serial transition is ready.
 */
internal class AgentReloadCommandProcessor(
    scope: CoroutineScope,
    private val repository: ConfigRepository,
    private val controller: AgentRuntimeController,
    private val ioDispatcher: CoroutineDispatcher,
    private val onFailureWithoutRuntime: (Throwable) -> Unit,
    private val onFailureWithRuntime: (Throwable) -> Unit
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val closed = AtomicBoolean()
    private val worker: Job = scope.launch {
        for (ignored in requests) {
            while (requests.tryReceive().isSuccess) {
                // Drop duplicate requests already pending before loading the latest snapshot.
            }
            val outcome = withContext(ioDispatcher) {
                repository.loadAgentConfig().fold(
                    onSuccess = { ConfigLoadOutcome.Ready(it) },
                    onFailure = { ConfigLoadOutcome.Failed(it) }
                )
            }
            when (outcome) {
                is ConfigLoadOutcome.Ready -> {
                    try {
                        controller.reload(outcome.config)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        if (controller.isRunning) {
                            onFailureWithRuntime(failure)
                        } else {
                            onFailureWithoutRuntime(failure)
                        }
                    }
                }
                is ConfigLoadOutcome.Failed -> {
                    if (controller.isRunning) {
                        onFailureWithRuntime(outcome.error)
                    } else {
                        onFailureWithoutRuntime(outcome.error)
                    }
                }
            }
        }
    }

    fun request() {
        if (!closed.get()) requests.trySend(Unit)
    }

    suspend fun close() {
        if (closed.compareAndSet(false, true)) requests.close()
        worker.cancel()
        worker.join()
    }
}

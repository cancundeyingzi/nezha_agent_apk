package com.nezhahq.agent.service

import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface StreamSessionAdmission {
    data class Accepted(val lease: StreamSessionLease) : StreamSessionAdmission
    data class Rejected(val reason: String) : StreamSessionAdmission
}

/**
 * Atomically enforces stream-session uniqueness and concurrency limits.
 *
 * A lease must be closed when its session job completes. Closing a lease is idempotent.
 */
internal class StreamSessionRegistry(
    private val maxTotal: Int,
    maxByTaskType: Map<Long, Int>
) {
    private val lock = Any()
    private val limits = maxByTaskType.toMap()
    private val activeTaskTypeByStreamId = mutableMapOf<String, Long>()
    private val activeCountByTaskType = mutableMapOf<Long, Int>()

    init {
        require(maxTotal > 0) { "maxTotal must be positive." }
        require(limits.isNotEmpty()) { "At least one stream task limit is required." }
        require(limits.values.all { it > 0 }) { "All stream task limits must be positive." }
    }

    fun tryAcquire(taskType: Long, streamId: String): StreamSessionAdmission =
        synchronized(lock) {
            val typeLimit = limits[taskType]
                ?: return@synchronized StreamSessionAdmission.Rejected(
                    "Unsupported stream task type $taskType."
                )

            if (activeTaskTypeByStreamId.containsKey(streamId)) {
                return@synchronized StreamSessionAdmission.Rejected(
                    "Stream session is already active for StreamID=$streamId."
                )
            }
            if (activeTaskTypeByStreamId.size >= maxTotal) {
                return@synchronized StreamSessionAdmission.Rejected(
                    "The device has reached the limit of $maxTotal active stream sessions."
                )
            }

            val activeForType = activeCountByTaskType[taskType] ?: 0
            if (activeForType >= typeLimit) {
                return@synchronized StreamSessionAdmission.Rejected(
                    "The device has reached the limit of $typeLimit active sessions " +
                        "for task type $taskType."
                )
            }

            activeTaskTypeByStreamId[streamId] = taskType
            activeCountByTaskType[taskType] = activeForType + 1
            StreamSessionAdmission.Accepted(
                StreamSessionLease {
                    release(taskType, streamId)
                }
            )
        }

    internal fun activeCount(): Int = synchronized(lock) {
        activeTaskTypeByStreamId.size
    }

    internal fun activeCount(taskType: Long): Int = synchronized(lock) {
        activeCountByTaskType[taskType] ?: 0
    }

    private fun release(taskType: Long, streamId: String) {
        synchronized(lock) {
            if (activeTaskTypeByStreamId.remove(streamId) != taskType) return

            val remaining = (activeCountByTaskType[taskType] ?: 1) - 1
            if (remaining == 0) {
                activeCountByTaskType.remove(taskType)
            } else {
                activeCountByTaskType[taskType] = remaining
            }
        }
    }
}

internal class StreamSessionLease(
    private val release: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

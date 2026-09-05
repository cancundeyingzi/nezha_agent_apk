package com.nezhahq.agent.collector

internal enum class TrafficSource {
    PRIVILEGED_PROC_NET_DEV,
    INTERFACE_TRAFFIC_STATS,
    NETWORK_STATS,
    TOTAL_TRAFFIC_STATS,
    DIRECT_PROC_NET_DEV
}

internal data class TrafficReading(
    val snapshot: TrafficSnapshot,
    val source: TrafficSource
)

internal data class NetworkSample(
    val snapshot: TrafficSnapshot,
    val rxBytesPerSecond: Long,
    val txBytesPerSecond: Long
)

/** Keeps a working counter source selected and tolerates brief read failures before switching. */
internal class TrafficSourceSelector(
    private val toleratedFailures: Int = DEFAULT_TOLERATED_FAILURES
) {
    private var selectedSource: TrafficSource? = null
    private var consecutiveFailures = 0

    init {
        require(toleratedFailures >= 0)
    }

    fun read(
        candidates: List<TrafficSource>,
        reader: (TrafficSource) -> TrafficSnapshot?
    ): TrafficReading? {
        val selected = selectedSource?.takeIf { it in candidates }
        var checkedPreferred: TrafficSource? = null
        if (selected != null) {
            val preferred = candidates.firstOrNull()
            if (preferred != null && preferred != selected) {
                checkedPreferred = preferred
                reader(preferred)?.let { snapshot ->
                    selectedSource = preferred
                    consecutiveFailures = 0
                    return TrafficReading(snapshot, preferred)
                }
            }
            reader(selected)?.let { snapshot ->
                consecutiveFailures = 0
                return TrafficReading(snapshot, selected)
            }
            if (consecutiveFailures++ < toleratedFailures) return null
            selectedSource = null
            consecutiveFailures = 0
        } else if (selectedSource != null) {
            selectedSource = null
            consecutiveFailures = 0
        }

        for (source in candidates) {
            if (source == selected || source == checkedPreferred) continue
            val snapshot = reader(source) ?: continue
            selectedSource = source
            consecutiveFailures = 0
            return TrafficReading(snapshot, source)
        }
        return null
    }

    private companion object {
        const val DEFAULT_TOLERATED_FAILURES = 2
    }
}

/**
 * Calculates rates only inside one monotonic counter domain and keeps reported transfer totals
 * continuous when the underlying source must change.
 */
internal class NetworkSpeedSampler {
    private var baseline: TimedTrafficReading? = null
    private var reportedSnapshot = TrafficSnapshot(0L, 0L)
    private var lastSample = ZERO_SAMPLE
    private var consecutiveMissingSamples = 0

    fun sample(current: TrafficReading?, nowMs: Long): NetworkSample {
        val previous = baseline
        if (current == null) {
            if (consecutiveMissingSamples++ >= MAX_TOLERATED_MISSING_SAMPLES) {
                lastSample = reportedSnapshot.toSample()
                consecutiveMissingSamples = 0
            }
            return lastSample
        }

        consecutiveMissingSamples = 0
        baseline = TimedTrafficReading(current, nowMs)
        if (previous == null) {
            reportedSnapshot = current.snapshot
            return reportedSnapshot.toSample().also { lastSample = it }
        }
        if (current.source != previous.reading.source) {
            return reportedSnapshot.toSample().also { lastSample = it }
        }

        val elapsedMs = nowMs - previous.atMs
        val rxDelta = current.snapshot.rxBytes - previous.reading.snapshot.rxBytes
        val txDelta = current.snapshot.txBytes - previous.reading.snapshot.txBytes
        if (rxDelta < 0L || txDelta < 0L) {
            return reportedSnapshot.toSample().also { lastSample = it }
        }

        reportedSnapshot = TrafficSnapshot(
            addSaturating(reportedSnapshot.rxBytes, rxDelta),
            addSaturating(reportedSnapshot.txBytes, txDelta)
        )
        if (elapsedMs <= 0L) return reportedSnapshot.toSample().also { lastSample = it }

        return NetworkSample(
            snapshot = reportedSnapshot,
            rxBytesPerSecond = ratePerSecond(rxDelta, elapsedMs),
            txBytesPerSecond = ratePerSecond(txDelta, elapsedMs)
        ).also { lastSample = it }
    }

    private fun TrafficSnapshot.toSample(): NetworkSample = NetworkSample(this, 0L, 0L)

    private fun ratePerSecond(delta: Long, elapsedMs: Long): Long {
        if (delta == 0L) return 0L
        val whole = delta / elapsedMs
        if (whole > Long.MAX_VALUE / MILLIS_PER_SECOND) return Long.MAX_VALUE

        val scaledWhole = whole * MILLIS_PER_SECOND
        val remainder = delta % elapsedMs
        val scaledRemainder = if (remainder <= Long.MAX_VALUE / MILLIS_PER_SECOND) {
            remainder * MILLIS_PER_SECOND / elapsedMs
        } else {
            (remainder.toDouble() * MILLIS_PER_SECOND / elapsedMs)
                .coerceAtMost(Long.MAX_VALUE.toDouble())
                .toLong()
        }
        return addSaturating(scaledWhole, scaledRemainder)
    }

    private data class TimedTrafficReading(
        val reading: TrafficReading,
        val atMs: Long
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_TOLERATED_MISSING_SAMPLES = 2
        val ZERO_SAMPLE = NetworkSample(TrafficSnapshot(0L, 0L), 0L, 0L)
    }
}

package com.nezhahq.agent.collector

/** Stateful `/proc/stat` sampler. A valid baseline is required before usage is reported. */
internal class CpuUsageSampler {
    private var baseline: CpuCounters? = null

    fun sample(line: String?): Double {
        val current = parse(line)
        if (current == null) {
            baseline = null
            return 0.0
        }

        val previous = baseline
        baseline = current
        if (previous == null || current.values.indices.any { current.values[it] < previous.values[it] }) {
            return 0.0
        }

        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L || idleDelta < 0L || idleDelta > totalDelta) return 0.0

        return ((totalDelta - idleDelta).toDouble() * 100.0 / totalDelta.toDouble())
            .coerceIn(0.0, 100.0)
    }

    private fun parse(line: String?): CpuCounters? {
        if (line.isNullOrBlank()) return null
        val fields = line.trim().split(WHITESPACE)
        if (fields.size < REQUIRED_FIELD_COUNT + 1 || fields[0] != "cpu") return null

        val values = LongArray(COUNTER_COUNT)
        for (index in values.indices) {
            val value = fields.getOrNull(index + 1)?.toLongOrNull()
                ?: if (index >= REQUIRED_FIELD_COUNT) 0L else return null
            if (value < 0L) return null
            values[index] = value
        }

        val total = values.fold(0L) { sum, value -> addWithoutOverflow(sum, value) ?: return null }
        val idle = addWithoutOverflow(values[IDLE_INDEX], values[IOWAIT_INDEX]) ?: return null
        return CpuCounters(values, total, idle)
    }

    private fun addWithoutOverflow(left: Long, right: Long): Long? =
        if (right > Long.MAX_VALUE - left) null else left + right

    private data class CpuCounters(
        val values: LongArray,
        val total: Long,
        val idle: Long
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val REQUIRED_FIELD_COUNT = 4
        const val COUNTER_COUNT = 8
        const val IDLE_INDEX = 3
        const val IOWAIT_INDEX = 4
    }
}

package com.nezhahq.agent.collector

internal const val PRIVILEGED_METRICS_COMMAND =
    "printf '__NEZHA_CPU__\\n'; head -n 1 /proc/stat 2>/dev/null; " +
        "printf '__NEZHA_LOAD__\\n'; cat /proc/loadavg 2>/dev/null; " +
        "printf '__NEZHA_PROCESS__\\n'; ps -A 2>/dev/null | tail -n +2 | wc -l; " +
        "printf '__NEZHA_TCP__\\n'; " +
        "if nezha_tcp=\$(ss -tn 2>/dev/null); then " +
        "printf '%s\\n' \"\$nezha_tcp\" | tail -n +2 | wc -l; fi; " +
        "printf '__NEZHA_UDP__\\n'; " +
        "if nezha_udp=\$(ss -un 2>/dev/null); then " +
        "printf '%s\\n' \"\$nezha_udp\" | tail -n +2 | wc -l; fi; " +
        "unset nezha_tcp nezha_udp; " +
        "printf '__NEZHA_NET__\\n'; cat /proc/net/dev 2>/dev/null; " +
        "printf '__NEZHA_BATTERY__\\n'; cat /sys/class/power_supply/battery/uevent 2>/dev/null; :"

internal data class PrivilegedMetricsSnapshot(
    val cpuLine: String?,
    val loadAverage: Triple<Double, Double, Double>?,
    val processCount: Long?,
    val connectionCounts: Pair<Long, Long>?,
    val traffic: TrafficSnapshot?,
    val batteryUevent: String? = null
)

/** Parses the single framed shell response used for one privileged metrics tick. */
internal object PrivilegedMetricsSnapshotParser {
    private val markers = listOf(
        "__NEZHA_CPU__",
        "__NEZHA_LOAD__",
        "__NEZHA_PROCESS__",
        "__NEZHA_TCP__",
        "__NEZHA_UDP__",
        "__NEZHA_NET__"
    )

    fun parse(output: String): PrivilegedMetricsSnapshot? {
        if (output.isBlank()) return null
        val sections = extractSections(output) ?: return null
        val tcp = parseNonNegativeLong(sections[3])
        val udp = parseNonNegativeLong(sections[4])

        return PrivilegedMetricsSnapshot(
            cpuLine = sections[0].lineSequence().firstOrNull { it.trimStart().startsWith("cpu ") },
            loadAverage = parseLoadAverage(sections[1]),
            processCount = parseNonNegativeLong(sections[2])?.takeIf { it > 0L },
            connectionCounts = if (tcp != null && udp != null) Pair(tcp, udp) else null,
            traffic = ProcNetDevReader.parse(sections[5].substringBefore("__NEZHA_BATTERY__")),
            batteryUevent = sections[5].substringAfter("__NEZHA_BATTERY__", "").trim().takeIf { it.isNotEmpty() }
        )
    }

    private fun extractSections(output: String): List<String>? {
        val positions = IntArray(markers.size)
        var searchFrom = 0
        for (index in markers.indices) {
            val position = output.indexOf(markers[index], searchFrom)
            if (position < 0) return null
            positions[index] = position
            searchFrom = position + markers[index].length
        }

        return markers.indices.map { index ->
            val start = positions[index] + markers[index].length
            val end = positions.getOrNull(index + 1) ?: output.length
            output.substring(start, end).trim()
        }
    }

    private fun parseNonNegativeLong(section: String): Long? =
        section.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
}

internal fun parseLoadAverage(line: String?): Triple<Double, Double, Double>? {
    val fields = line?.trim()?.split(LOAD_SEPARATOR) ?: return null
    if (fields.size < 3) return null
    val load1 = fields[0].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    val load5 = fields[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    val load15 = fields[2].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    if (load1 < 0.0 || load5 < 0.0 || load15 < 0.0) return null
    return Triple(load1, load5, load15)
}

/** Reuses the last privileged value across brief failures, then invokes the honest fallback. */
internal class StableMetric<T>(
    private val toleratedFailures: Int = DEFAULT_TOLERATED_FAILURES
) {
    private var lastValue: T? = null
    private var consecutiveFailures = 0

    init {
        require(toleratedFailures >= 0)
    }

    fun resolve(candidate: T?, fallback: () -> T): T {
        if (candidate != null) {
            lastValue = candidate
            consecutiveFailures = 0
            return candidate
        }

        val previous = lastValue
        if (previous != null && consecutiveFailures++ < toleratedFailures) return previous
        lastValue = null
        consecutiveFailures = 0
        return fallback()
    }

    private companion object {
        const val DEFAULT_TOLERATED_FAILURES = 2
    }
}

private val LOAD_SEPARATOR = Regex("\\s+")

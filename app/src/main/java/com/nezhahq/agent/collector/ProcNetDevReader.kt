package com.nezhahq.agent.collector

import java.io.File

internal data class TrafficSnapshot(
    val rxBytes: Long,
    val txBytes: Long
)

/** Keeps both counters in one source domain when a primary snapshot is incomplete. */
internal fun selectTrafficSnapshot(
    primary: TrafficSnapshot,
    fallback: () -> TrafficSnapshot?
): TrafficSnapshot {
    if (primary.rxBytes > 0L && primary.txBytes > 0L) return primary

    val candidate = fallback() ?: return primary
    val suppliesMissingDirection =
        (primary.rxBytes <= 0L && candidate.rxBytes > 0L) ||
            (primary.txBytes <= 0L && candidate.txBytes > 0L)
    return if (suppliesMissingDirection) candidate else primary
}

/** Reads and aggregates physical network counters exposed by `/proc/net/dev`. */
internal object ProcNetDevReader {
    private val fieldSeparator = Regex("\\s+")
    private val sourceFile = File("/proc/net/dev")

    fun read(): TrafficSnapshot? = runCatching { parse(sourceFile.readText()) }.getOrNull()

    internal fun parse(content: String): TrafficSnapshot? {
        var totalRx = 0L
        var totalTx = 0L
        var interfaceCount = 0

        for (line in content.lineSequence()) {
            val snapshot = parseLine(line) ?: continue
            totalRx = addWithoutOverflow(totalRx, snapshot.rxBytes) ?: return null
            totalTx = addWithoutOverflow(totalTx, snapshot.txBytes) ?: return null
            interfaceCount++
        }

        return if (interfaceCount > 0) TrafficSnapshot(totalRx, totalTx) else null
    }

    private fun parseLine(line: String): TrafficSnapshot? {
        val separatorIndex = line.indexOf(':')
        if (separatorIndex <= 0) return null

        val interfaceName = line.substring(0, separatorIndex).trim()
        if (interfaceName.isEmpty() || shouldIgnore(interfaceName)) return null

        val fields = line.substring(separatorIndex + 1)
            .trim()
            .split(fieldSeparator)
        if (fields.size < 9) return null

        val rxBytes = fields[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val txBytes = fields[8].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        return TrafficSnapshot(rxBytes, txBytes)
    }

    private fun shouldIgnore(interfaceName: String): Boolean =
        interfaceName == "lo" || interfaceName.startsWith("tun")

    private fun addWithoutOverflow(left: Long, right: Long): Long? =
        if (right > Long.MAX_VALUE - left) null else left + right
}

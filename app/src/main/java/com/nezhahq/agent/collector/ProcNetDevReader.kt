package com.nezhahq.agent.collector

import java.io.File

internal data class TrafficSnapshot(
    val rxBytes: Long,
    val txBytes: Long
)

/**
 * 累计计数的来源域。
 *
 * 两个域的基数完全不同（TrafficStats 是设备口径，/proc/net/dev 是全部接口的累计值，
 * 通常大得多），所以跨域的两次读数相减没有物理意义——差值可能是巨大的正数，也可能是负数。
 * [NetworkSpeedSampler] 靠这个标识判断基线是否还能用。
 */
internal enum class TrafficSource { PRIMARY, PROC_NET_DEV }

/** 一次流量读数，连同它来自哪个计数域。 */
internal data class TrafficReading(
    val snapshot: TrafficSnapshot,
    val source: TrafficSource
)

/** Keeps both counters in one source domain when a primary snapshot is incomplete. */
internal fun selectTrafficSnapshot(
    primary: TrafficSnapshot,
    fallback: () -> TrafficSnapshot?
): TrafficReading {
    if (primary.rxBytes > 0L && primary.txBytes > 0L) {
        return TrafficReading(primary, TrafficSource.PRIMARY)
    }

    val candidate = fallback() ?: return TrafficReading(primary, TrafficSource.PRIMARY)
    val suppliesMissingDirection =
        (primary.rxBytes <= 0L && candidate.rxBytes > 0L) ||
            (primary.txBytes <= 0L && candidate.txBytes > 0L)
    return if (suppliesMissingDirection) {
        TrafficReading(candidate, TrafficSource.PROC_NET_DEV)
    } else {
        TrafficReading(primary, TrafficSource.PRIMARY)
    }
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
}

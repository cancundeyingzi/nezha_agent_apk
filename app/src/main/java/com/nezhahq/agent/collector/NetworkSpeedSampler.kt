package com.nezhahq.agent.collector

/** 一拍网速采样结果，单位 B/s。 */
internal data class NetworkSpeed(
    val rxBytesPerSecond: Long,
    val txBytesPerSecond: Long
)

/**
 * 由两次累计流量计数算出瞬时网速的有状态采样器，需要先建立基线才会给出非零速度。
 *
 * ## 为什么基线会失效
 * 累计计数器在**同一个来源域内**理应单调递增，但这里的计数源本身会在两拍之间被换掉：
 * SystemStateCollector 读流量时有一整条降级链，TrafficStats 某个方向返回 0 就会让
 * [selectTrafficSnapshot] 切到 /proc/net/dev。
 *
 * 换域的差值有两个方向，必须都挡住：
 * - **向下**：新域基数更小，或内核计数器自身回绕 —— 相减得负数，面板画出负速度。
 * - **向上**：/proc/net/dev 是全部接口的累计值，通常远大于 TrafficStats 的设备口径 ——
 *   相减得到一个巨大的正数，面板上凭空出现一拍几 GB/s 的尖峰。只看"是否变小"抓不到这一类，
 *   所以这里比较的是 [TrafficSource] 而不只是数值大小。
 *
 * 处理方式与 [CpuUsageSampler] 对 /proc/stat 计数回退的处理一致：视为基线失效，
 * 本拍上报 0 并把新值作为新基线，下一拍即可恢复正常。
 */
internal class NetworkSpeedSampler {
    private var baseline: TrafficReading? = null
    private var baselineAtMs = 0L

    /**
     * @param current 本拍读到的累计收发字节数，连同它的来源域
     * @param nowMs   单调时钟读数（调用方传入，避免本类依赖 Android API 而无法单测）
     */
    fun sample(current: TrafficReading, nowMs: Long): NetworkSpeed {
        val previous = baseline
        val elapsedMs = nowMs - baselineAtMs
        // 无论本拍是否算得出速度，都要把基线推进到当前值：计数源切换后若不推进，
        // 之后每一拍都会拿新计数域的值去减旧计数域的值，错误将永远持续下去。
        baseline = current
        baselineAtMs = nowMs

        if (previous == null || elapsedMs <= 0L) return ZERO_SPEED

        val sourceChanged = current.source != previous.source
        val rolledBack = current.snapshot.rxBytes < previous.snapshot.rxBytes ||
            current.snapshot.txBytes < previous.snapshot.txBytes
        if (sourceChanged || rolledBack) return ZERO_SPEED

        return NetworkSpeed(
            rxBytesPerSecond =
                (current.snapshot.rxBytes - previous.snapshot.rxBytes) * MILLIS_PER_SECOND / elapsedMs,
            txBytesPerSecond =
                (current.snapshot.txBytes - previous.snapshot.txBytes) * MILLIS_PER_SECOND / elapsedMs
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        val ZERO_SPEED = NetworkSpeed(0L, 0L)
    }
}

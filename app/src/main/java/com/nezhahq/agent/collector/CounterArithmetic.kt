package com.nezhahq.agent.collector

/**
 * 累计计数器求和时的溢出保护。
 *
 * CPU 时间片、网卡累计字节数、分区容量都是单调递增的非负量，累加时唯一可能的溢出方向是正向。
 * 采集器里原本散落着三份同语义实现（CpuUsageSampler、ProcNetDevReader、DiskCollector），
 * 其中任意一份被改动都会与另外两份产生行为分歧，因此收敛到这里。
 *
 * 保留两个变体而不是强行统一，是因为调用方对"溢出"的处置本就不同：
 * - [addWithoutOverflow] 返回 null，让调用方把整份样本判为不可信并整体丢弃。差值法算错一次
 *   会直接体现为面板曲线上的一个尖峰，宁可这一拍不报。
 * - [addSaturating] 饱和到 [Long.MAX_VALUE]，用于必须给出一个数值的汇总场景（磁盘总量）。
 *
 * 两者都假定入参非负——这是累计计数器的固有性质，负值应由调用方在解析阶段拦掉。
 */
internal fun addWithoutOverflow(left: Long, right: Long): Long? =
    if (right > Long.MAX_VALUE - left) null else left + right

/** 溢出时饱和到 [Long.MAX_VALUE] 的加法，其余语义同 [addWithoutOverflow]。 */
internal fun addSaturating(left: Long, right: Long): Long =
    addWithoutOverflow(left, right) ?: Long.MAX_VALUE

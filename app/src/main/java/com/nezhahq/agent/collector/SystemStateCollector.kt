package com.nezhahq.agent.collector

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import proto.Nezha.State
import proto.Nezha.State_SensorTemperature
import java.io.File

/**
 * 系统运行时状态采集器（动态数据，每次上报前调用一次）。
 *
 * ## 采集内容
 * CPU 占用率、内存使用量、Swap（ZRAM）使用量、磁盘使用量、
 * 网络速度/累计流量、TCP/UDP 连接数、进程数、电池温度。
 *
 * ## 模式说明
 *
 * ### 普通模式（isRootMode = false）
 *  - CPU：优先读取 /proc/stat（差值法，精确 0-100%）。
 *    若遭 Android 9+ SELinux 拒绝（EACCES），**直接返回 0.0** 而非使用 top。
 *    原因：top 命令第一帧输出的是系统启动以来的平均值，非当前瞬时值；
 *    且各家 OEM ROM 格式差异极大，解析准确率堪忧，不如诚实返回 0。
 *  - 连接数：通过字节级换行符计数读取 /proc/net/tcp(6) / /proc/net/udp(6)，
 *    性能远优于按行 readLine() + String 对象创建。
 *  - 进程数：枚举 /proc 下的数字子目录（每 PID 一个）。
 *
 * ### Root/Shizuku 模式（isRootMode = true）
 *  - CPU：通过 [RootShell]（持久 su 会话）执行 `head -n 1 /proc/stat`，
 *    绕过 SELinux 限制，使用差值法精确计算。
 *  - 连接数：通过 [RootShell] 执行 `ss` 命令，获取全系统连接数。
 *  - 进程数：通过 [RootShell] 执行 `ps -A | wc -l` 获取全量进程数。
 *
 * ## 性能优化
 *  - Regex 均以文件级常量形式预编译，避免每拍重新编译。
 *  - /proc/meminfo 解析改用纯字符串操作，避免临时 Regex 对象和 GC。
 *  - /proc/net/ 等连接数统计改为字节缓冲区计换行符，无 String 对象分配。
 *  - Root 模式下的短命令通过 [RootShell] 单例持久会话执行，
 *    彻底消除每 2 秒 fork 新 su 进程的性能灾难；只有慢命令（GPU 的 dumpsys 兜底）
 *    才用独立进程，以免长时间占住那把全进程共用的会话锁。
 */
class SystemStateCollector(
    private val context: Context,
    private val gpuCollector: GpuCollector,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // ──────────────────────────────────────────────────────────────────────────
    // 伴生对象：与实例状态无关的常量
    // ──────────────────────────────────────────────────────────────────────────
    companion object {
        /** /proc/net/tcp 等文件的字节读取缓冲区大小（8 KiB）。 */
        private const val NET_BUF_SIZE = 8192

        /** 所有负载数据源都不可用时上报的安全默认值。 */
        private val DEFAULT_LOAD_AVERAGE = Triple(0.0, 0.0, 0.0)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 状态变量：差值法采样器（各自持有自己的基线）
    // ──────────────────────────────────────────────────────────────────────────
    private val networkSpeedSampler = NetworkSpeedSampler()

    private val cpuUsageSampler = CpuUsageSampler()

    // ──────────────────────────────────────────────────────────────────────────
    // 日志去重标志：对于已知的不可恢复限制，只打印一次警告
    // ──────────────────────────────────────────────────────────────────────────

    /** /proc/loadavg 读取失败的警告是否已打印（SELinux 拒绝属于永久性限制，无需反复提示） */
    private var loadAvgWarningLogged = false

    // ──────────────────────────────────────────────────────────────────────────
    // 公开采集入口
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun getState(isRootMode: Boolean): State = withContext(ioDispatcher) {
        collectState(isRootMode)
    }

    // suspend：GPU 的 dumpsys 兜底走独立进程（[RootShell.executeIsolated]），是挂起调用。
    private suspend fun collectState(isRootMode: Boolean): State {
        // ── 1. RAM ─────────────────────────────────────────────────────────────
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val memUsed = memInfo.totalMem - memInfo.availMem

        // ── 2. Swap 使用量（/proc/meminfo，普通权限即可）──────────────────────
        val swapUsed = readSwapUsedBytes()

        // ── 3. 磁盘使用量（/data 基准 + 附加存储扫描）────────────────────────
        val diskInfo = DiskCollector.getDiskInfo(isRootMode)
        val diskUsed = diskInfo.usedBytes

        // ── 4. 网络速度与流量 ──────────────────────────────────────────────────
        // 首拍没有基线、计数源在两拍之间被换掉、内核计数器回绕，都由采样器判为基线失效并报 0，
        // 详见 [NetworkSpeedSampler]。
        val traffic = readNetworkTrafficBytes(isRootMode)
        val speed = networkSpeedSampler.sample(traffic, SystemClock.elapsedRealtime())

        // ── 5. 温度传感器（电池温度作为系统回退值）───────────────────────────
        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tempCelsius = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            ?.toDouble()?.div(10.0) ?: 0.0
        val sensorTemp = State_SensorTemperature.newBuilder()
            .setName("Battery")
            .setTemperature(tempCelsius)
            .build()

        // ── 6. CPU + 进程数 + 连接数 ───────────────────────────────────────────
        val cpuUsage     = readCpuUsagePercent(isRootMode)
        var processCount = 0L
        var tcpConnCount = 0L
        var udpConnCount = 0L

        try {
            processCount = readProcessCount(isRootMode)
            val (tcp, udp) = readConnectionCounts(isRootMode)
            tcpConnCount = tcp
            udpConnCount = udp
        } catch (e: Exception) {
            Logger.e("StateCollector: 采集进程/连接数时异常", e)
        }

        // ── 7. 系统负载（1 / 5 / 15 分钟平均值）────────────────────────────────
        val loadAvg = readLoadAverage(isRootMode)

        // ── 8. GPU 使用率（Root/Shizuku 模式可用）────────────────────────────
        val gpuUsages = gpuCollector.getGpuUsages(isRootMode)

        return State.newBuilder()
            .setCpu(cpuUsage)
            .setMemUsed(memUsed)
            .setSwapUsed(swapUsed)
            .setDiskUsed(diskUsed)
            .setNetInTransfer(traffic.snapshot.rxBytes)
            .setNetOutTransfer(traffic.snapshot.txBytes)
            .setNetInSpeed(speed.rxBytesPerSecond)
            .setNetOutSpeed(speed.txBytesPerSecond)
            .setUptime(SystemClock.elapsedRealtime() / 1000)
            .setLoad1(loadAvg.first)
            .setLoad5(loadAvg.second)
            .setLoad15(loadAvg.third)
            .setTcpConnCount(tcpConnCount)
            .setUdpConnCount(udpConnCount)
            .setProcessCount(processCount)
            .addTemperatures(sensorTemp)
            .addAllGpu(gpuUsages)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 网络流量采集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取全系统的收发流量。
     * - Root/Shizuku 模式：通过 `cat /proc/net/dev` 解析全网卡流量，规避 Android 11+ 对 TrafficStats 的限制。
     * - 普通模式（Android 12+）：遍历 NetworkInterface 并调用 TrafficStats.getRxBytes(iface.name)。
     * - 普通模式（Android 6+ 降级）：尝试使用 NetworkStatsManager 查询设备总计。
     * - 普通模式（最低兜底）：使用 TrafficStats.getTotalRxBytes()，若被系统拦截或不支持则回退为 0。
     * - 所有普通策略均返回 0 时，直接尝试读取 `/proc/net/dev`。
     */
    private fun readNetworkTrafficBytes(isRootMode: Boolean): TrafficReading {
        var rx = -1L
        var tx = -1L

        if (isRootMode) {
            try {
                ProcNetDevReader.parse(RootShell.execute("cat /proc/net/dev"))?.let { snapshot ->
                    rx = snapshot.rxBytes
                    tx = snapshot.txBytes
                }
            } catch (e: Exception) {
                Logger.e("StateCollector: Root 模式读取 /proc/net/dev 失败", e)
            }
        }

        // 降级策略 1：使用 Android 12 (API 31+) 提供的按网卡获取流量的方法
        if ((rx < 0L || tx < 0L) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                if (interfaces != null) {
                    var tempRx = 0L
                    var tempTx = 0L
                    var hasData = false
                    for (iface in interfaces) {
                        if (!iface.isLoopback) {
                            val r = TrafficStats.getRxBytes(iface.name)
                            val t = TrafficStats.getTxBytes(iface.name)
                            if (r != TrafficStats.UNSUPPORTED.toLong() && r >= 0) {
                                tempRx += r
                                hasData = true
                            }
                            if (t != TrafficStats.UNSUPPORTED.toLong() && t >= 0) {
                                tempTx += t
                                hasData = true
                            }
                        }
                    }
                    if (hasData) {
                        rx = tempRx
                        tx = tempTx
                    }
                }
            } catch (e: Exception) {
                Logger.e("StateCollector: API 31+ TrafficStats 按网卡获取失败", e)
            }
        }

        // 降级策略 2：使用 Android 6 (API 23+) 的 NetworkStatsManager 获取设备级流量
        if ((rx < 0L || tx < 0L) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? android.app.usage.NetworkStatsManager
                if (nsm != null) {
                    var tempRx = 0L
                    var tempTx = 0L
                    var hasData = false

                    val queryStats = { transportType: Int ->
                        try {
                            val bucket = nsm.querySummaryForDevice(transportType, null, 0, System.currentTimeMillis())
                            if (bucket != null) {
                                tempRx += bucket.rxBytes
                                tempTx += bucket.txBytes
                                if (bucket.rxBytes > 0 || bucket.txBytes > 0) hasData = true
                            }
                        } catch (e: Exception) {
                            // 忽略缺乏权限 (SecurityException) 或服务不可用等异常
                        }
                    }

                    // querySummaryForDevice takes the legacy ConnectivityManager TYPE_* constants;
                    // the NetworkCapabilities transports that replaced them are not accepted by
                    // this overload, so the deprecated values are the only ones that work here.
                    @Suppress("DEPRECATION")
                    queryStats(android.net.ConnectivityManager.TYPE_WIFI)
                    @Suppress("DEPRECATION")
                    queryStats(android.net.ConnectivityManager.TYPE_MOBILE)
                    @Suppress("DEPRECATION")
                    queryStats(android.net.ConnectivityManager.TYPE_ETHERNET)

                    if (hasData) {
                        rx = tempRx
                        tx = tempTx
                    }
                }
            } catch (e: Exception) {
                Logger.e("StateCollector: NetworkStatsManager 获取失败", e)
            }
        }

        // 降级策略 3：回退到最基础的 TrafficStats 总计（API 8+）
        if (rx < 0L || tx < 0L) {
            val tsRx = TrafficStats.getTotalRxBytes()
            val tsTx = TrafficStats.getTotalTxBytes()
            rx = if (tsRx >= 0) tsRx else 0L
            tx = if (tsTx >= 0) tsTx else 0L
        }

        // 任一方向缺失时整体切换到同一个 /proc 快照，避免混合不同计数域。
        return selectTrafficSnapshot(
            primary = TrafficSnapshot(rxBytes = rx, txBytes = tx),
            fallback = ProcNetDevReader::read
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CPU 使用率采集（两层策略）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取 CPU 使用率，范围 [0.0, 100.0]。
     *
     * ### Root 模式
     * 通过 [RootShell] 持久 su 会话执行 `head -n 1 /proc/stat`，绕过 SELinux，
     * 再通过差值法（本次 - 上次）计算精确使用率。
     *
     * ### 普通模式（Android ≤ 8）
     * 直接读取 `/proc/stat` 并差值法计算。
     *
     * ### 普通模式（Android 9+ SELinux 拒绝）
     * 直接返回 **0.0**。
     * 放弃 `top` 降级方案的原因：
     * 1. `top -n 1` 输出的是系统自启动以来的 **平均值**，非当前瞬时值。
     * 2. 各 OEM ROM 对 top 输出格式改动较大，解析准确率堪忧。
     * 3. 若需准确数据，应提示用户启用 Root 模式。
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return [0.0, 100.0] 内的 CPU 使用率
     */
    private fun readCpuUsagePercent(isRootMode: Boolean): Double {
        val line = try {
            if (isRootMode) {
                // Root 模式：使用持久 su 会话读取（不创建新进程！）
                RootShell.executeFirstLine("head -n 1 /proc/stat")
            } else {
                File("/proc/stat").bufferedReader().use { it.readLine() }
            }
        } catch (e: Exception) {
            // Android 9+ SELinux 策略收紧导致 EACCES，属预期行为
            // 诚实返回 0.0，不使用 top（第一帧陷阱 + OEM 格式混乱）
            null
        }
        return parseProcStatLine(line)
    }

    /**
     * 解析 `/proc/stat` 第一行（"cpu" 综合行），通过差值法计算使用率。
     *
     * 行格式：`cpu  <user> <nice> <system> <idle> <iowait> <irq> <softirq> ...`
     *
     * 第一行是**所有核心**的累加，因此差值法的结果天然为 0-100%，
     * 无需额外除以核心数。
     *
     * @param line /proc/stat 的第一行，null 返回 0.0
     * @return [0.0, 100.0] 的 CPU 使用率，首次调用返回 0.0（无历史基准）
     */
    private fun parseProcStatLine(line: String?): Double {
        return cpuUsageSampler.sample(line)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 系统负载采集（/proc/loadavg）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取系统负载平均值（1 分钟 / 5 分钟 / 15 分钟）。
     *
     * /proc/loadavg 格式示例：`0.34 0.28 0.22 1/345 12345`
     * 前三个字段分别为 1/5/15 分钟的 CPU 队列平均长度。
     *
     * ### 权限策略
     * - **Root/Shizuku 模式**：先通过 [RootShell] 执行 `cat /proc/loadavg`，
     *   绕过 Android 9+ 的 SELinux 限制；拿不到可解析结果时再退回直读文件。
     * - **普通模式**：直接读取 `/proc/loadavg`。
     *   Android 7~8 的内核通常允许读取此文件；
     *   Android 9+ 部分 OEM ROM 可能通过 SELinux 策略拒绝读取，
     *   此时返回 (0.0, 0.0, 0.0) 作为安全默认值。
     *
     * 回退由 [firstParsableLoadAverage] 按"解析结果是否为空"判定，而不是靠 catch，
     * 原因见该函数注释。
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return Triple(load1, load5, load15)，所有数据源都失败返回 (0.0, 0.0, 0.0)
     */
    private fun readLoadAverage(isRootMode: Boolean): Triple<Double, Double, Double> {
        return firstParsableLoadAverage(
            { if (isRootMode) readLoadAvgRoot() else null },
            ::readLoadAvgDirect
        ) ?: DEFAULT_LOAD_AVERAGE
    }

    /**
     * Root/Shizuku 模式下通过持久 Shell 读取 /proc/loadavg 首行。
     *
     * @return Shell 输出的首行；会话不可用时 [RootShell] 返回空串，异常时返回 null，
     *         两者都会被上层判为"不可解析"从而触发直读回退
     */
    private fun readLoadAvgRoot(): String? {
        return try {
            RootShell.executeFirstLine("cat /proc/loadavg")
        } catch (e: Exception) {
            Logger.e("StateCollector: Root 模式读取 /proc/loadavg 异常，回退到直接读取", e)
            null
        }
    }

    /**
     * 直接读取 /proc/loadavg 文件内容（普通模式和 Root 模式回退时使用）。
     *
     * @return 文件第一行内容，读取失败返回 null
     */
    private fun readLoadAvgDirect(): String? {
        return try {
            val result = File("/proc/loadavg").bufferedReader().use { it.readLine() }
            // 如果曾经失败后又变为可读（例如切换了模式），重置标志允许未来再次打印
            if (result != null) loadAvgWarningLogged = false
            result
        } catch (e: Exception) {
            // Android 9+ SELinux 拒绝属已知的不可恢复限制，只在首次失败时打印一次警告
            if (!loadAvgWarningLogged) {
                Logger.i("StateCollector: 无法直接读取 /proc/loadavg（SELinux 限制），Load 数据不可用")
                loadAvgWarningLogged = true
            }
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Swap 使用量（/proc/meminfo，纯字符串解析）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 从 /proc/meminfo 读取 Swap 使用量（Bytes）。
     *
     * Android 设备的 ZRAM 虚拟 Swap 会完整反映在 /proc/meminfo 中。
     * 此接口无需任何特殊权限。
     *
     * ### 性能说明
     * 不使用 `Regex("\\d+").find(line)` 提取数字，而是通过纯字符串
     * 操作直接截取，避免每次调用的临时 Regex 对象分配和 GC 压力。
     */
    private fun readSwapUsedBytes(): Long {
        var swapTotal = 0L
        var swapFree  = 0L
        var foundTotal = false
        var foundFree  = false
        try {
            File("/proc/meminfo").bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    when {
                        !foundTotal && l.startsWith("SwapTotal:") -> {
                            swapTotal = parseKbFromMeminfoLine(l)
                            foundTotal = true
                        }
                        !foundFree && l.startsWith("SwapFree:") -> {
                            swapFree = parseKbFromMeminfoLine(l)
                            foundFree = true
                        }
                    }
                    // 两个值都读到后提前退出，避免读取整个文件
                    if (foundTotal && foundFree) break
                }
            }
        } catch (e: Exception) {
            Logger.e("StateCollector: 读取 /proc/meminfo Swap 失败", e)
        }
        // /proc/meminfo 单位为 kB，转换为 Bytes
        return (swapTotal - swapFree).coerceAtLeast(0L) * 1024L
    }

    /**
     * 从 /proc/meminfo 的一行中提取以 kB 为单位的数值（Long）。
     *
     * 格式示例：`SwapTotal:   2097148 kB`
     * 纯字符串实现，无临时 Regex 对象，无额外内存分配。
     *
     * @param line meminfo 中的一行，含冒号分隔的 key: value
     * @return 数值（kB），解析失败返回 0
     */
    private fun parseKbFromMeminfoLine(line: String): Long {
        // 找到冒号后的内容，例如 "   2097148 kB"
        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return 0L
        val rest = line.substring(colonIdx + 1).trimStart()
        // rest 类似 "2097148 kB"：找到第一个非数字字符截断
        var end = 0
        while (end < rest.length && rest[end].isDigit()) end++
        return if (end > 0) rest.substring(0, end).toLongOrNull() ?: 0L else 0L
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 进程数采集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 获取当前进程总数。
     *
     * - **Root 模式**：通过 [RootShell] 执行 `ps -A | wc -l`（全量，含系统进程）。
     * - **普通模式**：枚举 `/proc` 下的数字子目录（每 PID 一目录，无需权限）。
     */
    private fun readProcessCount(isRootMode: Boolean): Long {
        return if (isRootMode) {
            val output = RootShell.executeFirstLine("ps -A 2>/dev/null | wc -l")
            val total = output?.trim()?.toLongOrNull()
            // ps -A 输出包含标题行，减 1 得到实际进程数。
            // 若输出不可解析，或计算后为非正数（如 toybox 不支持 -A 时返回 0），回退到 /proc 枚举法。
            if (total != null) {
                val count = (total - 1L).coerceAtLeast(0L)
                if (count > 0L) {
                    return count
                }
            }
            readProcessCountFromProc()
        } else {
            readProcessCountFromProc()
        }
    }

    /**
     * 枚举 `/proc` 目录下的纯数字子目录统计进程数。
     * 每个进程在 /proc 下均有一个以其 PID 命名的目录，此法无需任何权限。
     */
    private fun readProcessCountFromProc(): Long {
        return try {
            File("/proc").listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
                ?.size?.toLong() ?: 0L
        } catch (e: Exception) {
            Logger.e("StateCollector: 枚举 /proc 目录统计进程数失败", e)
            0L
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TCP/UDP 连接数采集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 获取 TCP 和 UDP 连接数，返回 Pair(tcpCount, udpCount)。
     *
     * - **Root 模式**：通过 [RootShell] 执行 `ss` 命令，获取全系统连接数。
     * - **普通模式**：字节级扫描 /proc/net/tcp(6) 和 /proc/net/udp(6)，
     *   统计换行符数量（减去标题行），性能远优于按行 readLine() + String 分配。
     */
    private fun readConnectionCounts(isRootMode: Boolean): Pair<Long, Long> {
        return if (isRootMode) {
            readConnectionCountsRoot()
        } else {
            readConnectionCountsFromProc()
        }
    }

    /**
     * Root 模式：通过持久 [RootShell] 执行 `ss` 统计全系统 TCP/UDP 连接数。
     *
     * 每次调用共使用 **2 次** shell 写入（不创建新进程），极低开销。
     * `ss` 不可用时由 [parseSsConnectionCounts] 判定并回退到 /proc/net 字节统计法；
     * 这里的 catch 只是兜底，真正生效的是显式判空，原因见该函数注释。
     */
    private fun readConnectionCountsRoot(): Pair<Long, Long> {
        val counts = try {
            // ss -tn: TCP 连接，不解析主机名；tail -n +2 跳过标题行
            parseSsConnectionCounts(
                tcpRaw = RootShell.executeFirstLine("ss -tn 2>/dev/null | tail -n +2 | wc -l"),
                udpRaw = RootShell.executeFirstLine("ss -un 2>/dev/null | tail -n +2 | wc -l")
            )
        } catch (e: Exception) {
            Logger.e("StateCollector: Root 模式 ss 异常，回退到 /proc/net", e)
            null
        }
        return counts ?: readConnectionCountsFromProc()
    }

    /**
     * 普通模式：通过字节缓冲区扫描 /proc/net/tcp(6) 和 udp(6) 统计条目数。
     *
     * ### 性能优化说明
     * 传统 `bufferedReader().readLine()` 每行都会创建一个 String 对象，
     * 在有数百条连接的场景（高并发服务端）会造成大量短生命周期对象和 GC 停顿。
     *
     * 改为直接以字节流扫描，统计 `\n` 出现次数（每行一个换行符），
     * 完全避免 String 对象分配，CPU 和内存占用降低一个数量级。
     *
     * 标题行也包含换行符，最终结果减 1 以排除。
     */
    private fun readConnectionCountsFromProc(): Pair<Long, Long> {
        val tcpCount = countNewlinesInFiles(
            File("/proc/net/tcp"),
            File("/proc/net/tcp6")
        )
        val udpCount = countNewlinesInFiles(
            File("/proc/net/udp"),
            File("/proc/net/udp6")
        )
        return Pair(tcpCount, udpCount)
    }

    /**
     * 统计一组文件中换行符 `\n` 的总出现次数（减 1 排除每文件标题行）。
     *
     * 使用 [NET_BUF_SIZE] 字节的复用缓冲区，单次最多读取 8 KiB，
     * 同一缓冲区在循环中重复使用，不进行额外内存分配。
     *
     * @param files 要扫描的文件列表（不存在或无读权限的文件会被跳过）
     * @return 所有文件的有效条目总数（已减去标题行）
     */
    private fun countNewlinesInFiles(vararg files: File): Long {
        val buf = ByteArray(NET_BUF_SIZE)
        var total = 0L

        for (file in files) {
            if (!file.exists() || !file.canRead()) continue
            var newlines = 0L
            try {
                file.inputStream().use { stream ->
                    var bytesRead: Int
                    while (stream.read(buf).also { bytesRead = it } > 0) {
                        // 逐字节扫描换行符，无 String 对象分配
                        for (i in 0 until bytesRead) {
                            if (buf[i] == '\n'.code.toByte()) newlines++
                        }
                    }
                }
                // 每个文件首行为列标题（也含换行符），减 1 得实际条目数
                total += (newlines - 1L).coerceAtLeast(0L)
            } catch (e: Exception) {
                Logger.e("StateCollector: 字节扫描 ${file.name} 失败", e)
            }
        }
        return total
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 提权数据源的回退判定（纯函数，脱离 Android 运行时可单测）
//
// 这一节存在的共同理由：RootShell.execute 在会话建不起来、命令超时或 root 授权被撤销时
// **返回空串而不抛异常**。历史上这些回退都写在 catch 里，于是永远不可达，面板长期显示
// 0 连接 / 0.00 负载而没有任何告警。所有降级都必须按"结果是否可解析"显式判定。
// ══════════════════════════════════════════════════════════════════════════════

/** 分割 /proc/loadavg 各字段的空白正则，预编译后复用，避免每拍重新编译。 */
private val LOAD_AVERAGE_SEPARATOR = Regex("\\s+")

/**
 * 依次尝试各数据源，返回第一个能解析出 1/5/15 分钟负载的结果，全部失败返回 null。
 *
 * 数据源传的是惰性 lambda 而不是已读好的字符串：前一个数据源成功时，后面的就不该再白付
 * 一次 /proc 文件 IO。
 */
internal fun firstParsableLoadAverage(
    vararg sources: () -> String?
): Triple<Double, Double, Double>? {
    for (source in sources) {
        parseLoadAvgLine(source())?.let { return it }
    }
    return null
}

/**
 * 解析 /proc/loadavg 的一行内容，提取前三个浮点数。
 *
 * 行格式：`0.34 0.28 0.22 1/345 12345`
 *
 * @param line /proc/loadavg 的第一行；null 与空串（Shell 不可用时的返回值）都视为无数据
 * @return Triple(load1, load5, load15)，格式不匹配返回 null
 */
internal fun parseLoadAvgLine(line: String?): Triple<Double, Double, Double>? {
    if (line.isNullOrBlank()) return null
    val parts = line.trim().split(LOAD_AVERAGE_SEPARATOR)
    if (parts.size < 3) return null
    val load1  = parts[0].toDoubleOrNull() ?: return null
    val load5  = parts[1].toDoubleOrNull() ?: return null
    val load15 = parts[2].toDoubleOrNull() ?: return null
    return Triple(load1, load5, load15)
}

/**
 * 解析 `ss ... | wc -l` 两路输出，判定 root 侧连接数统计是否可用。
 *
 * 判空必须显式做，而且不能只看"是否为空串"：设备上没有 `ss` 二进制时，管道末端的 `wc -l`
 * 照样会输出 "0"，既不抛异常也不是空串。旧代码的 `?: 0L` 因此把它当成"真的没有连接"。
 *
 * 判定规则与本文件的 readProcessCount 一致——两路都拿不到正数才算不可用。只要任意一路有
 * 正数，就说明 `ss` 工作正常，另一路的 0 是真实值（例如设备上确实没有 UDP 套接字），
 * 应当照原样上报而不是回退。
 *
 * @return Pair(tcp, udp)；返回 null 表示这条路走不通，调用方应回退到 /proc/net 统计
 */
internal fun parseSsConnectionCounts(tcpRaw: String?, udpRaw: String?): Pair<Long, Long>? {
    val tcp = parseConnectionCount(tcpRaw)
    val udp = parseConnectionCount(udpRaw)
    if (tcp <= 0L && udp <= 0L) return null
    return Pair(tcp, udp)
}

/** 把 `wc -l` 的输出解析为非负计数，不可解析或为负一律按 0 处理。 */
private fun parseConnectionCount(raw: String?): Long =
    raw?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

//go:build android

// Android 专用 CPU 监控实现
// 直接读取 /proc/stat 和 /proc/cpuinfo，绕过 gopsutil 库
// 原因：gopsutil 在 Android 上由于 SELinux 策略和 /proc 访问限制可能无法正常工作

package cpu

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
)

// 上一次采样的 CPU 时间数据，用于计算增量百分比
var (
	lastTotal uint64 // 上次采样的 CPU 总时间
	lastIdle  uint64 // 上次采样的 CPU 空闲时间
	cpuMu     sync.Mutex
)

// GetHost 从 /proc/cpuinfo 读取 CPU 型号信息
func GetHost(ctx context.Context) ([]string, error) {
	f, err := os.Open("/proc/cpuinfo")
	if err != nil {
		return nil, fmt.Errorf("读取 /proc/cpuinfo 失败: %w", err)
	}
	defer f.Close()

	var cpuType string
	if t, ok := ctx.Value(CPUHostKey).(string); ok {
		cpuType = t
	}

	cpuModelCount := make(map[string]int)
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		// Android 设备的 /proc/cpuinfo 可能使用 "model name" 或 "Hardware" 字段
		if strings.HasPrefix(line, "model name") || strings.HasPrefix(line, "Hardware") {
			parts := strings.SplitN(line, ":", 2)
			if len(parts) == 2 {
				model := strings.TrimSpace(parts[1])
				if model != "" {
					cpuModelCount[model]++
				}
			}
		}
	}

	// 某些 ARM Android 设备的 /proc/cpuinfo 没有 "model name"
	// 尝试从 "Processor" 字段获取
	if len(cpuModelCount) == 0 {
		f.Seek(0, 0)
		scanner = bufio.NewScanner(f)
		for scanner.Scan() {
			line := scanner.Text()
			if strings.HasPrefix(line, "Processor") || strings.HasPrefix(line, "processor") {
				parts := strings.SplitN(line, ":", 2)
				if len(parts) == 2 {
					val := strings.TrimSpace(parts[1])
					// "processor" 字段可能只是编号（如 "0", "1"）
					if _, err := strconv.Atoi(val); err != nil && val != "" {
						cpuModelCount[val]++
					}
				}
			}
		}
	}

	// 最终 fallback：使用通用描述
	if len(cpuModelCount) == 0 {
		// 计算 processor 数量
		f.Seek(0, 0)
		scanner = bufio.NewScanner(f)
		count := 0
		for scanner.Scan() {
			if strings.HasPrefix(scanner.Text(), "processor") {
				count++
			}
		}
		if count == 0 {
			count = 1
		}
		cpuModelCount["ARM Processor"] = count
	}

	ch := make([]string, 0, len(cpuModelCount))
	for model, count := range cpuModelCount {
		ch = append(ch, fmt.Sprintf("%s %d %s Core", model, count, cpuType))
	}

	return ch, nil
}

// GetState 通过读取 /proc/stat 计算 CPU 使用率百分比
// 使用增量计算法：比较两次采样之间的 CPU 时间变化
func GetState(_ context.Context) ([]float64, error) {
	cpuMu.Lock()
	defer cpuMu.Unlock()

	total, idle, err := readCPUStat()
	if err != nil {
		return nil, err
	}

	// 计算增量
	totalDelta := total - lastTotal
	idleDelta := idle - lastIdle

	// 更新缓存
	lastTotal = total
	lastIdle = idle

	// 首次调用时 totalDelta 可能为 0（无上次数据），返回 0%
	if totalDelta == 0 {
		return []float64{0}, nil
	}

	// CPU 使用率 = (总时间增量 - 空闲时间增量) / 总时间增量 * 100
	cpuPercent := float64(totalDelta-idleDelta) / float64(totalDelta) * 100.0
	if cpuPercent < 0 {
		cpuPercent = 0
	}
	if cpuPercent > 100 {
		cpuPercent = 100
	}

	return []float64{cpuPercent}, nil
}

// readCPUStat 解析 /proc/stat 第一行（cpu 聚合行）
// 格式: cpu  user nice system idle iowait irq softirq steal guest guest_nice
func readCPUStat() (total, idle uint64, err error) {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return 0, 0, fmt.Errorf("读取 /proc/stat 失败: %w", err)
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	if scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "cpu ") {
			fields := strings.Fields(line)
			if len(fields) < 5 {
				return 0, 0, fmt.Errorf("/proc/stat 格式异常: 字段不足")
			}

			// 解析各时间字段:  user, nice, system, idle, iowait, irq, softirq, steal...
			for i := 1; i < len(fields); i++ {
				v, parseErr := strconv.ParseUint(fields[i], 10, 64)
				if parseErr != nil {
					continue
				}
				total += v
			}

			// idle = 第4个字段 (idle)
			idleVal, _ := strconv.ParseUint(fields[4], 10, 64)
			idle = idleVal

			// 加上 iowait（第5个字段），也属于空闲时间
			if len(fields) > 5 {
				iowait, _ := strconv.ParseUint(fields[5], 10, 64)
				idle += iowait
			}
		}
	}

	if total == 0 {
		return 0, 0, fmt.Errorf("无法解析 /proc/stat")
	}

	return total, idle, nil
}

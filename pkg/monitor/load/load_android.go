//go:build android

// Android 专用系统负载监控实现
// 直接读取 /proc/loadavg，绕过 gopsutil 库

package load

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"

	psLoad "github.com/shirou/gopsutil/v4/load"
)

// GetState 从 /proc/loadavg 读取系统负载平均值
// /proc/loadavg 格式: "0.50 0.35 0.25 1/200 12345"
// 分别为: 1分钟 5分钟 15分钟 运行中进程/总进程 最近PID
func GetState(_ context.Context) (*psLoad.AvgStat, error) {
	data, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return &psLoad.AvgStat{}, fmt.Errorf("读取 /proc/loadavg 失败: %w", err)
	}

	fields := strings.Fields(strings.TrimSpace(string(data)))
	if len(fields) < 3 {
		return &psLoad.AvgStat{}, fmt.Errorf("/proc/loadavg 格式异常")
	}

	load1, _ := strconv.ParseFloat(fields[0], 64)
	load5, _ := strconv.ParseFloat(fields[1], 64)
	load15, _ := strconv.ParseFloat(fields[2], 64)

	return &psLoad.AvgStat{
		Load1:  load1,
		Load5:  load5,
		Load15: load15,
	}, nil
}

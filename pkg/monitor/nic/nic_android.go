//go:build android

// Android 专用网络接口监控实现
// 直接读取 /proc/net/dev 获取网卡流量数据，绕过 gopsutil 库
// 原因：gopsutil 在部分 Android 设备上无法正确读取网络接口计数器

package nic

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"
)

// GetState 从 /proc/net/dev 读取网络接口的收发字节数
// /proc/net/dev 格式：
//
//	Inter-|   Receive                              |  Transmit
//	 face |bytes packets errs drop ...             |bytes packets errs drop ...
//	 wlan0: 12345  100    0    0  ...               67890  200    0    0  ...
func GetState(ctx context.Context) ([]uint64, error) {
	f, err := os.Open("/proc/net/dev")
	if err != nil {
		return nil, fmt.Errorf("读取 /proc/net/dev 失败: %w", err)
	}
	defer f.Close()

	allowList, _ := ctx.Value(NICKey).(map[string]bool)

	var netInTransfer, netOutTransfer uint64
	scanner := bufio.NewScanner(f)

	// 跳过前两行表头
	lineNum := 0
	for scanner.Scan() {
		lineNum++
		if lineNum <= 2 {
			continue
		}

		line := scanner.Text()

		// 解析接口名称：格式为 "  iface_name: ..."
		colonIdx := strings.Index(line, ":")
		if colonIdx < 0 {
			continue
		}

		ifaceName := strings.TrimSpace(line[:colonIdx])
		if ifaceName == "" {
			continue
		}

		// 过滤：应用排除列表（lo、docker、tun 等虚拟接口）
		if DefaultMatcher.Contains([]byte(ifaceName)) && !allowList[ifaceName] {
			continue
		}
		// 如果设置了白名单，仅允许白名单中的接口
		if len(allowList) > 0 && !allowList[ifaceName] {
			continue
		}

		// 解析数据字段
		dataStr := strings.TrimSpace(line[colonIdx+1:])
		fields := strings.Fields(dataStr)

		// /proc/net/dev 每行至少16个字段：
		// Receive:  bytes packets errs drop fifo frame compressed multicast
		// Transmit: bytes packets errs drop fifo colls carrier compressed
		if len(fields) < 10 {
			continue
		}

		// 接收字节数 = 第1个字段 (index 0)
		bytesRecv, err := strconv.ParseUint(fields[0], 10, 64)
		if err != nil {
			continue
		}

		// 发送字节数 = 第9个字段 (index 8)
		bytesSent, err := strconv.ParseUint(fields[8], 10, 64)
		if err != nil {
			continue
		}

		netInTransfer += bytesRecv
		netOutTransfer += bytesSent
	}

	return []uint64{netInTransfer, netOutTransfer}, nil
}

//go:build !android

package nic

import (
	"context"

	"github.com/shirou/gopsutil/v4/net"
)

func GetState(ctx context.Context) ([]uint64, error) {
	var netInTransfer, netOutTransfer uint64
	nc, err := net.IOCountersWithContext(ctx, true)
	if err != nil {
		return nil, err
	}

	allowList, _ := ctx.Value(NICKey).(map[string]bool)

	for _, v := range nc {
		if DefaultMatcher.Contains([]byte(v.Name)) && !allowList[v.Name] {
			continue
		}
		if len(allowList) > 0 && !allowList[v.Name] {
			continue
		}
		netInTransfer += v.BytesRecv
		netOutTransfer += v.BytesSent
	}

	return []uint64{netInTransfer, netOutTransfer}, nil
}

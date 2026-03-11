package nic

import "github.com/cloudflare/ahocorasick"

// NICKeyType 是 NIC 上下文键的类型
type NICKeyType string

// NICKey 用于在 context 中传递 NIC 白名单配置
const NICKey NICKeyType = "nic"

// 需要排除的虚拟/隧道网卡名称子串列表
var excludeNetInterfaces = []string{
	"lo", "tun", "docker", "veth", "br-", "vmbr", "vnet", "kube", "Meta", "tailscale", "fw", "tap",
}

// DefaultMatcher Aho-Corasick 多模式匹配器，用于高效过滤网卡名称
var DefaultMatcher = ahocorasick.NewStringMatcher(excludeNetInterfaces)

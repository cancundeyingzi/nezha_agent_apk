package cpu

// CPUHostType 是 CPU 上下文键的类型
type CPUHostType string

// CPUHostKey 用于在 context 中传递 CPU 类型信息（Physical/Virtual）
const CPUHostKey CPUHostType = "cpu"

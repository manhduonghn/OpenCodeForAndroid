//go:build linux && arm64

package main

// memfdCreateSyscall is __NR_memfd_create from the Linux arm64 syscall table.
// The standard syscall package does not export this generated constant.
const memfdCreateSyscall = 279

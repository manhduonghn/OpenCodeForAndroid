//go:build linux && amd64

package main

// memfdCreateSyscall is __NR_memfd_create from the Linux x86_64 syscall table.
// The standard syscall package does not export this generated constant.
const memfdCreateSyscall = 319

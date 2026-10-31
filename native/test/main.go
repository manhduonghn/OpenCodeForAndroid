// Command fixture is the static Go program of the native tests. It is built
// without cgo for linux/arm64 and started through qemu-aarch64; the commands
// exercise the paths and the blocked syscalls that oc-gosys handles.
package main

import (
	"fmt"
	"os"
	"runtime"
	"syscall"
	"unsafe"
)

func fail(format string, args ...any) {
	fmt.Printf(format+"\n", args...)
	os.Exit(1)
}

func readFile(path string) {
	f, err := os.Open(path)
	if err != nil {
		fail("open: %v", err)
	}
	defer f.Close()
	buf := make([]byte, 64)
	n, err := f.Read(buf)
	if err != nil {
		fail("read: %v", err)
	}
	fmt.Printf("content: %s\n", string(buf[:n]))
}

func main() {
	if len(os.Args) < 2 {
		fail("usage: fixture open PATH | getcwd DIR | setuid | clone3 | getgroups | blocked")
	}
	switch os.Args[1] {
	case "open":
		if len(os.Args) != 3 {
			fail("usage: fixture open PATH")
		}
		readFile(os.Args[2])
	case "getcwd":
		if err := os.Chdir(os.Args[2]); err != nil {
			fail("chdir: %v", err)
		}
		dir, err := os.Getwd()
		if err != nil {
			fail("getcwd: %v", err)
		}
		fmt.Printf("cwd: %s\n", dir)
	case "setuid":
		_, _, errno := syscall.Syscall(146, 12345, 0, 0) // setuid
		if errno != 0 {
			fail("setuid: %v", errno)
		}
		fmt.Println("setuid: ok")
	case "clone3":
		_, _, errno := syscall.Syscall(435, 0, 0, 0) // clone3
		if errno != syscall.ENOSYS {
			fail("clone3: %v", errno)
		}
		fmt.Println("clone3: enosys")
	case "getgroups":
		var list [8]int32
		n, _, errno := syscall.Syscall(158, uintptr(len(list)), uintptr(unsafe.Pointer(&list[0])), 0) // getgroups
		if errno != 0 {
			fail("getgroups: %v", errno)
		}
		if n != 1 || list[0] != 0 {
			fail("getgroups: %d groups, first %d", n, list[0])
		}
		fmt.Println("getgroups: one empty group")
	case "blocked":
		// These syscalls are missing from Android's seccomp allowlist. The
		// patched program must see ENOSYS and use its fallback instead of
		// being killed by the SIGSYS trap.
		path := []byte("/tmp/oc-blocked\x00")
		p := uintptr(unsafe.Pointer(&path[0]))
		check := func(name string, nr, a0, a1, a2, a3 uintptr) {
			_, _, errno := syscall.Syscall6(nr, a0, a1, a2, a3, 0, 0)
			if errno != syscall.ENOSYS {
				fail("%s: %v, want ENOSYS", name, errno)
			}
		}
		check("faccessat2", 439, ^uintptr(0), p, 0, 0x200)
		check("openat2", 437, ^uintptr(0), p, 0, 24)
		check("name_to_handle_at", 264, ^uintptr(0), p, 0, 0)
		check("chroot", 51, p, 0, 0, 0)
		runtime.KeepAlive(path)
		fmt.Println("blocked: enosys")
	default:
		fail("unknown command %q", os.Args[1])
	}
}

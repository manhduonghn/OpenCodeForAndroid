package main

import (
	"errors"
	"fmt"
	"hash/fnv"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

// The sandbox of this app. The values are also what the app exports as
// OC_PREFIX / OC_USER_HOME; the constants are only a fallback for a shell that
// starts oc-gopatch without the environment of the login profile.
const (
	defaultPrefix   = "/data/data/com.opencode.android/files/usr"
	defaultUserHome = "/data/data/com.opencode.android/files/home"

	// The roots that a program built for Termux still contains. A shebang or a
	// path argument can name them even though they do not exist on the device.
	termuxPrefix = "/data/data/com.termux/files/usr"
	termuxHome   = "/data/data/com.termux/files/home"
)

func ocPrefix() string {
	if v := os.Getenv("OC_PREFIX"); v != "" {
		return strings.TrimRight(v, "/")
	}
	return defaultPrefix
}

func ocUserHome() string {
	if v := os.Getenv("OC_USER_HOME"); v != "" {
		return strings.TrimRight(v, "/")
	}
	return defaultUserHome
}

func runEnv() []string {
	env := os.Environ()
	env = setIfEmpty(env, "OC_PREFIX", ocPrefix())
	env = setIfEmpty(env, "OC_USER_HOME", ocUserHome())
	return env
}

func setIfEmpty(env []string, name, value string) []string {
	prefix := name + "="
	for i, entry := range env {
		if strings.HasPrefix(entry, prefix) {
			if entry == prefix {
				env[i] = prefix + value
			}
			return env
		}
	}
	return append(env, prefix+value)
}

// realExeEnv sets OC_REAL_EXE, replacing a value inherited from a routed
// parent. The preload uses it for /proc/self/exe because the kernel reports the
// loader there.
func realExeEnv(env []string, path string) []string {
	out := make([]string, 0, len(env)+1)
	for _, entry := range env {
		if strings.HasPrefix(entry, "OC_REAL_EXE=") {
			continue
		}
		out = append(out, entry)
	}
	return append(out, "OC_REAL_EXE="+path)
}

// withoutRealExe drops the override before an exec that does not go through the
// loader: the kernel then reports the real executable.
func withoutRealExe(env []string) []string {
	out := make([]string, 0, len(env))
	for _, entry := range env {
		if strings.HasPrefix(entry, "OC_REAL_EXE=") {
			continue
		}
		out = append(out, entry)
	}
	return out
}

func resolveProgram(name string) (string, error) {
	if strings.Contains(name, "/") {
		if filepath.IsAbs(name) {
			return name, nil
		}
		abs, err := filepath.Abs(name)
		if err != nil {
			return "", err
		}
		return abs, nil
	}
	return lookPath(name)
}

// resolveInputPath maps a path spelled with a Termux root to the private prefix
// when the mapped file exists. The kernel never sees the Termux roots in this
// sandbox, and unlike a patched Go program or a glibc program under the preload,
// the helper has no path translation of its own.
func resolveInputPath(name string) string {
	if mapped, ok := mapRoot(name, termuxPrefix, ocPrefix()); ok {
		return mapped
	}
	if mapped, ok := mapRoot(name, termuxHome, ocUserHome()); ok {
		return mapped
	}
	return name
}

func mapRoot(name, from, to string) (string, bool) {
	if name == from {
		return to, true
	}
	if !strings.HasPrefix(name, from+"/") {
		return name, false
	}
	mapped := to + strings.TrimPrefix(name, from)
	if !fileExists(mapped) {
		return name, false
	}
	return mapped, true
}

// lookPath is exec.LookPath without the faccessat2 probe. Android's seccomp
// allowlist does not contain faccessat2 and the filter traps it with SIGSYS;
// the android port of Go skips the syscall for that reason, but this helper is
// built for linux, where Go probes it on every PATH lookup. os.Stat only needs
// newfstatat, which the filter allows.
func lookPath(name string) (string, error) {
	for _, dir := range filepath.SplitList(os.Getenv("PATH")) {
		if dir == "" {
			dir = "."
		}
		candidate := filepath.Join(dir, name)
		if st, err := os.Stat(candidate); err == nil && st.Mode().IsRegular() && st.Mode().Perm()&0o111 != 0 {
			return candidate, nil
		}
	}
	return "", fmt.Errorf("%s: executable file not found in $PATH", name)
}

// patchedCopy returns a cached, patched copy of the program [src], a Go
// program or a static C program. The name includes the size and modification
// time, so a replaced download is patched again and an unchanged one is reused.
// The stub bytes are part of the key as well: an app update ships a new
// oc-gosys blob, and copies patched with the old one must not be reused.
func patchedCopy(src string) (string, error) {
	st, err := os.Stat(src)
	if err != nil {
		return "", err
	}
	h := fnv.New64a()
	fmt.Fprintf(h, "%s\x00%d\x00%d\x00", src, st.Size(), st.ModTime().UnixNano())
	h.Write(gosysBlob)
	dir := filepath.Join(ocUserHome(), ".opencode", "patched")
	dst := filepath.Join(dir, fmt.Sprintf("%s-%016x", filepath.Base(src), h.Sum64()))
	if fileExists(dst) {
		return dst, nil
	}
	if err := patchFile(src, dst); err != nil {
		return "", err
	}
	return dst, nil
}

// startScript runs a script through its interpreter. Android has no /bin/sh or
// /usr/bin/env, so the interpreter is resolved inside the private prefix when
// the shebang names an absolute path that does not exist on the device.
func startScript(path string, args []string, env []string, detail string) error {
	fields := strings.Fields(detail)
	if len(fields) == 0 {
		return fmt.Errorf("%s: empty shebang", path)
	}
	interp := fields[0]
	extra := fields[1:]
	if filepath.Base(interp) == "env" {
		options := true
		for len(extra) > 0 && options {
			option := extra[0]
			if !strings.HasPrefix(option, "-") {
				if strings.Contains(option, "=") {
					// env NAME=VALUE PROGRAM ...: the assignment does not change
					// which program runs.
					extra = extra[1:]
					continue
				}
				break
			}
			extra = extra[1:]
			switch option {
			case "--":
				options = false
			case "-S", "--split-string":
				// The rest of the line is the command line of the program.
				options = false
			case "-u", "--unset", "-C", "--chdir":
				// These options take the next word with them.
				if len(extra) > 0 {
					extra = extra[1:]
				}
			}
		}
		if len(extra) == 0 {
			return fmt.Errorf("%s: the shebang names env without a program", path)
		}
		interp = extra[0]
		extra = extra[1:]
	}
	if !filepath.IsAbs(interp) {
		if resolved, err := lookPath(interp); err == nil {
			interp = resolved
		} else if fallback := filepath.Join(ocPrefix(), "bin", interp); fileExists(fallback) {
			interp = fallback
		}
	} else if !fileExists(interp) {
		// The script was written for a Termux install or names a path that
		// only exists inside the private prefix (/bin/sh is /system/bin/sh on
		// Android). Resolve those spellings before giving up.
		switch {
		case interp == "/bin/sh" || interp == "/usr/bin/sh":
			// Android has no /bin; the system shell is the closest match.
			if fileExists("/system/bin/sh") {
				interp = "/system/bin/sh"
			}
		case strings.HasPrefix(interp, termuxPrefix):
			interp = ocPrefix() + strings.TrimPrefix(interp, termuxPrefix)
		case strings.HasPrefix(interp, termuxHome):
			interp = ocUserHome() + strings.TrimPrefix(interp, termuxHome)
		default:
			relative := strings.TrimPrefix(interp, "/")
			for _, root := range []string{ocPrefix(), ocUserHome()} {
				if candidate := filepath.Join(root, relative); fileExists(candidate) {
					interp = candidate
					break
				}
			}
		}
	}
	argv := append([]string{interp}, extra...)
	argv = append(argv, path)
	if len(args) > 1 {
		argv = append(argv, args[1:]...)
	}
	// A glibc interpreter needs the loader and the preload just like a direct
	// glibc program; only bionic/static interpreters can be exec'd as they are.
	if kind, _, err := classifyFile(interp); err == nil && kind == "glibc" {
		return startGlibc(interp, argv, env)
	}
	return syscall.Exec(interp, argv, withoutRealExe(env))
}

// startGlibc starts a dynamic program through the glibc loader of the prefix
// with liboc-pathmap.so preloaded, exactly like the app starts its own tools.
func startGlibc(path string, args []string, env []string) error {
	loader := findLoader()
	if loader == "" {
		// Nothing named glibc is installed: let the kernel produce its own
		// error instead of hiding the real problem.
		return syscall.Exec(path, args, withoutRealExe(env))
	}
	prefix := ocPrefix()
	argv := []string{loader}
	preloaded := false
	if preload := filepath.Join(prefix, "lib", "liboc-pathmap.so"); fileExists(preload) {
		argv = append(argv, "--preload", preload)
		preloaded = true
	}
	// --argv0 (glibc 2.33) preserves the name the caller asked for. Without it
	// the loader uses the program path, which is close enough for an older
	// loader that only understands the rest of the options.
	if len(args) > 0 && args[0] != path && loaderSupportsArgv0(loader) {
		argv = append(argv, "--argv0", args[0])
	}
	argv = append(argv,
		"--library-path", prefix+"/lib:"+prefix+"/glibc/lib",
		path,
	)
	if len(args) > 1 {
		argv = append(argv, args[1:]...)
	}
	// OC_REAL_EXE is only meaningful with the preload: without it, the variable
	// would leak into children that never translate /proc/self/exe.
	if !preloaded {
		return syscall.Exec(loader, argv, withoutRealExe(env))
	}
	return syscall.Exec(loader, argv, realExeEnv(env, path))
}

// loaderSupportsArgv0 reports whether the loader understands --argv0. The
// option name is present in the option table of every loader that implements
// it, so a byte scan avoids running the loader just to test a flag.
func loaderSupportsArgv0(loader string) bool {
	data, err := os.ReadFile(loader)
	if err != nil {
		return false
	}
	return strings.Contains(string(data), "--argv0")
}

func findLoader() string {
	dir := filepath.Join(ocPrefix(), "glibc", "lib")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasPrefix(name, "ld-linux") || !strings.Contains(name, ".so.") {
			continue
		}
		path := filepath.Join(dir, name)
		if st, err := os.Stat(path); err == nil && st.Mode()&0o111 != 0 {
			return path
		}
	}
	return ""
}

func runCommand(args []string) error {
	if len(args) == 0 {
		return errors.New("run: missing command")
	}
	path, err := resolveProgram(args[0])
	if err != nil {
		return err
	}
	path = resolveInputPath(path)
	env := runEnv()

	kind, detail, err := classifyFile(path)
	if err != nil {
		return err
	}
	if debugEnabled() {
		strategy := "pass"
		switch kind {
		case "script":
			strategy = "script"
		case "glibc":
			strategy = "glibc"
		case "go":
			strategy = "go"
		case "static":
			strategy = "static"
		}
		extra := ""
		if detail != "" {
			extra = " (" + detail + ")"
		}
		fmt.Fprintf(os.Stderr, "oc-gopatch: %s strategy=%s%s\n", path, strategy, extra)
	}
	switch kind {
	case "script":
		return startScript(path, args, env, detail)
	case "glibc":
		return startGlibc(path, args, env)
	case "go":
		patched, err := patchedCopy(path)
		if err != nil {
			// The program still runs with the paths it was built with; it is
			// better than refusing to start it.
			fmt.Fprintf(os.Stderr, "oc-gopatch: %s: %v; starting it unpatched\n", path, err)
			return syscall.Exec(path, args, env)
		}
		return syscall.Exec(patched, args, env)
	case "static":
		// A static C program is patched at its "svc #0" sites. Many static
		// programs (Rust, tiny tools) do not need the policy, and a program
		// whose patch fails must still run with the paths it was built with.
		if !staticPatchEnabled() {
			return syscall.Exec(path, args, env)
		}
		patched, err := patchedCopy(path)
		if err != nil {
			if debugEnabled() {
				fmt.Fprintf(os.Stderr, "oc-gopatch: %s: %v; starting it unpatched\n", path, err)
			}
			return syscall.Exec(path, args, env)
		}
		return syscall.Exec(patched, args, env)
	default:
		return syscall.Exec(path, args, env)
	}
}

// staticPatchEnabled reports whether the "svc #0" patcher of a static C program
// is allowed. OC_STATIC_PATCH=0 restores the plain exec for programs that the
// user knows are happier unpatched.
func staticPatchEnabled() bool {
	value := os.Getenv("OC_STATIC_PATCH")
	return value != "0"
}

// debugEnabled reports whether OC_DEBUG asked for the strategy line. It is off by
// default, so the normal run writes nothing extra to stderr.
func debugEnabled() bool {
	value := os.Getenv("OC_DEBUG")
	return value != "" && value != "0"
}

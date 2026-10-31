package main

import (
	"errors"
	"fmt"
	"hash/fnv"
	"os"
	"os/exec"
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
	return exec.LookPath(name)
}

// patchedCopy returns a cached, patched copy of the Go program [src]. The name
// includes the size and modification time, so a replaced download is patched
// again and an unchanged one is reused.
func patchedCopy(src string) (string, error) {
	st, err := os.Stat(src)
	if err != nil {
		return "", err
	}
	h := fnv.New64a()
	fmt.Fprintf(h, "%s\x00%d\x00%d", src, st.Size(), st.ModTime().UnixNano())
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
		for len(extra) > 0 && strings.HasPrefix(extra[0], "-") {
			option := extra[0]
			extra = extra[1:]
			if option == "-S" || strings.Contains(option, "S") {
				break
			}
		}
		if len(extra) == 0 {
			return fmt.Errorf("%s: the shebang names env without a program", path)
		}
		interp = extra[0]
		extra = extra[1:]
	}
	if !filepath.IsAbs(interp) {
		if resolved, err := exec.LookPath(interp); err == nil {
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
	return syscall.Exec(interp, argv, env)
}

// startGlibc starts a dynamic program through the glibc loader of the prefix
// with liboc-pathmap.so preloaded, exactly like the app starts its own tools.
func startGlibc(path string, args []string, env []string) error {
	loader := findLoader()
	if loader == "" {
		// Nothing named glibc is installed: let the kernel produce its own
		// error instead of hiding the real problem.
		return syscall.Exec(path, args, env)
	}
	prefix := ocPrefix()
	argv := []string{loader}
	if preload := filepath.Join(prefix, "lib", "liboc-pathmap.so"); fileExists(preload) {
		argv = append(argv, "--preload", preload)
	}
	// Without --argv0 (glibc 2.33) the loader passes [path] as argv[0]; the
	// basename is the same, and an older loader still understands the rest.
	argv = append(argv,
		"--library-path", prefix+"/lib:"+prefix+"/glibc/lib",
		path,
	)
	if len(args) > 1 {
		argv = append(argv, args[1:]...)
	}
	return syscall.Exec(loader, argv, env)
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
	env := runEnv()

	kind, detail, err := classifyFile(path)
	if err != nil {
		return err
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
	default:
		return syscall.Exec(path, args, env)
	}
}

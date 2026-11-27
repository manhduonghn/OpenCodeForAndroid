package main

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestStripBionicPreload(t *testing.T) {
	env := []string{
		"PATH=/bin",
		"LD_PRELOAD=/data/app/.../liboc_exec.so",
		"LD_LIBRARY_PATH=/data/data/com.opencode.android/files/usr/lib",
		"MY_LD_PRELOAD=keep",
		"LD_PRELOADX=keep",
	}
	want := []string{"PATH=/bin", "MY_LD_PRELOAD=keep", "LD_PRELOADX=keep"}
	if got := stripBionicPreload(env); !reflect.DeepEqual(got, want) {
		t.Fatalf("stripBionicPreload = %q, want %q", got, want)
	}
}

func TestFindLoaderPrefersAnExecutableApkCopy(t *testing.T) {
	prefix := t.TempDir()
	t.Setenv("OC_PREFIX", prefix)
	prefixLoader := filepath.Join(prefix, "glibc", "lib", "ld-linux-aarch64.so.1")
	if err := os.MkdirAll(filepath.Dir(prefixLoader), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(prefixLoader, []byte("loader"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("OC_GLIBC_LOADER", "")

	if got := findLoader(); got != prefixLoader {
		t.Fatalf("findLoader without an APK copy = %q, want %q", got, prefixLoader)
	}

	apkLoader := filepath.Join(t.TempDir(), "liboc_ld.so")
	if err := os.WriteFile(apkLoader, []byte("loader"), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("OC_GLIBC_LOADER", apkLoader)
	if got := findLoader(); got != prefixLoader {
		t.Fatalf("findLoader with a non-executable APK copy = %q, want %q", got, prefixLoader)
	}

	if err := os.Chmod(apkLoader, 0o755); err != nil {
		t.Fatal(err)
	}
	if got := findLoader(); got != apkLoader {
		t.Fatalf("findLoader with an executable APK copy = %q, want %q", got, apkLoader)
	}
}

func TestSetIfEmpty(t *testing.T) {
	env := []string{"A=", "B=old"}
	env = setIfEmpty(env, "A", "default")
	env = setIfEmpty(env, "B", "default")
	env = setIfEmpty(env, "C", "default")
	want := []string{"A=default", "B=old", "C=default"}
	if !reflect.DeepEqual(env, want) {
		t.Fatalf("setIfEmpty = %q, want %q", env, want)
	}
}

func TestLoaderSupportsArgv0(t *testing.T) {
	loader := filepath.Join(t.TempDir(), "ld-linux")
	if err := os.WriteFile(loader, []byte("--argv0"), 0o755); err != nil {
		t.Fatal(err)
	}
	if !loaderSupportsArgv0(loader) {
		t.Fatal("a loader containing --argv0 should report support")
	}
	if err := os.WriteFile(loader, []byte("ld-linux"), 0o755); err != nil {
		t.Fatal(err)
	}
	if loaderSupportsArgv0(loader) {
		t.Fatal("a loader without --argv0 should not report support")
	}
}

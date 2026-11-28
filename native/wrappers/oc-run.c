/*
 * liboc_ocrun.so -- `oc-run` entry point and generic exec wrapper for targetSdk 36.
 *
 * The old entry point was a script in $PREFIX/bin, which Android 10+ refuses to
 * execute (app_data_file). This bionic PIE lives in nativeLibraryDir instead and
 * opens the static Go patcher, also shipped as an APK native library. The
 * symlink $PREFIX/bin/oc-run points here, so existing callers do not change.
 *
 * The generated shell environment also puts a directory of symlinks to this PIE
 * at the front of PATH, one per executable in $PREFIX/bin. When invoked under any
 * name other than `oc-run`, the basename of argv[0] selects the real program in
 * $PREFIX/bin. That gives Go programs (which issue raw execve syscalls that an
 * LD_PRELOAD shim cannot see, e.g. `gh` running `git`) a route through the patcher
 * too, so every command in the prefix is started with the right linker/loader.
 *
 * The patcher is a static ELF and does not load LD_PRELOAD. The wrapper exports
 * the bionic shim in its environment, so every dynamic child the patcher starts
 * can still run programs from the app prefix through /system/bin/linker64.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define DEFAULT_PREFIX "/data/data/com.opencode.android/files/usr"
#define DEFAULT_HOME "/data/data/com.opencode.android/files/home"
#define SYSTEM_LINKER "/system/bin/linker64"
#define GOPATCH "liboc_gopatch.so"
#define SHIM "liboc_exec.so"
#define LOADER "liboc_ld.so"

static void set_default(const char *name, const char *value) {
    if (getenv(name) == NULL || getenv(name)[0] == '\0') setenv(name, value, 1);
}

static const char *base_name(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

/* Avoid snprintf here: gcc -Wformat-truncation cannot prove the fixed suffix fits. */
static int sibling(char *out, size_t size, const char *dir, const char *name) {
    size_t dir_len = strlen(dir);
    size_t name_len = strlen(name);
    if (dir_len + 1 + name_len + 1 > size) return -1;
    memcpy(out, dir, dir_len);
    out[dir_len] = '/';
    memcpy(out + dir_len + 1, name, name_len + 1);
    return 0;
}

int main(int argc, char **argv) {
    char self[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (length <= 0) {
        fprintf(stderr, "oc-run: cannot find the APK native library directory\n");
        return 127;
    }
    self[length] = '\0';

    char *slash = strrchr(self, '/');
    if (slash == NULL) return 127;
    *slash = '\0';

    char patcher[PATH_MAX];
    char shim[PATH_MAX];
    char loader[PATH_MAX];
    if (sibling(patcher, sizeof(patcher), self, GOPATCH) != 0 ||
        sibling(shim, sizeof(shim), self, SHIM) != 0 ||
        sibling(loader, sizeof(loader), self, LOADER) != 0) {
        fprintf(stderr, "oc-run: native library path is too long\n");
        return 127;
    }

    const char *prefix = getenv("OC_PREFIX");
    if (prefix == NULL || prefix[0] == '\0') prefix = DEFAULT_PREFIX;
    char glibc_dir[PATH_MAX];
    set_default("OC_PREFIX", prefix);
    set_default("OC_USER_HOME", DEFAULT_HOME);
    set_default("OC_EXEC_SHIM", shim);
    if (sibling(glibc_dir, sizeof(glibc_dir), prefix, "glibc/lib") == 0) {
        set_default("OC_GLIBC_LIB_DIR", glibc_dir);
    } else {
        set_default("OC_GLIBC_LIB_DIR", DEFAULT_PREFIX "/glibc/lib");
    }
    /* Always prefer the APK loader: the one in the prefix is app_data_file. */
    if (access(loader, F_OK) == 0) setenv("OC_GLIBC_LOADER", loader, 1);
    if (access(shim, F_OK) == 0) setenv("LD_PRELOAD", shim, 1);

    /*
     * A symlink in the generated wrapper directory invokes us under the name of
     * the real program. Resolve it inside $PREFIX/bin instead of using PATH: the
     * wrapper directory is first in PATH, so a PATH lookup would find this PIE
     * again. `oc-run` itself keeps the old "oc-run COMMAND" behaviour.
     */
    const char *called = base_name(argv[0]);
    int wrapper_mode = strcmp(called, "oc-run") != 0 && strcmp(called, "liboc_ocrun.so") != 0;
    char bin_dir[PATH_MAX];
    char command_path[PATH_MAX];
    const char *command = NULL;
    int first_arg = 1;
    if (wrapper_mode) {
        if (sibling(bin_dir, sizeof(bin_dir), prefix, "bin") != 0 ||
            sibling(command_path, sizeof(command_path), bin_dir, called) != 0) {
            fprintf(stderr, "oc-run: command path is too long\n");
            return 127;
        }
        if (access(command_path, F_OK) != 0) {
            fprintf(stderr, "oc-run: %s is not installed in the prefix\n", called);
            return 127;
        }
        command = command_path;
    } else {
        if (argc < 2) {
            fprintf(stderr, "oc-run: no command given\n");
            return 127;
        }
        command = argv[1];
        first_arg = 2;
    }

    if (access(patcher, X_OK) == 0) {
        size_t need = (size_t)argc + 5;
        char **next = calloc(need, sizeof(char *));
        if (next == NULL) return 125;
        size_t k = 0;
        next[k++] = patcher;
        next[k++] = (char *)"run";
        next[k++] = (char *)"--";
        next[k++] = (char *)command;
        for (int i = first_arg; i < argc; i++) next[k++] = argv[i];
        next[k] = NULL;
        execv(patcher, next);
        /* A failed patcher run is not fatal: the command may still work. */
        free(next);
    }

    /* No patcher: prefer the system linker, which can map app-data files. */
    size_t need = (size_t)argc + 4;
    char **next = calloc(need, sizeof(char *));
    if (next == NULL) return 125;
    size_t k = 0;
    next[k++] = (char *)SYSTEM_LINKER;
    next[k++] = (char *)command;
    for (int i = first_arg; i < argc; i++) next[k++] = argv[i];
    next[k] = NULL;
    execv(SYSTEM_LINKER, next);
    free(next);

    /* Host tests (and devices without the linker) still need a normal exec. */
    if (wrapper_mode) {
        /* PATH would find this wrapper again, so call the resolved prefix file directly. */
        size_t count = (size_t)argc + 2;
        char **fallback = calloc(count, sizeof(char *));
        if (fallback == NULL) return 125;
        fallback[0] = (char *)command;
        for (int i = 1; i < argc; i++) fallback[i] = argv[i];
        execv(command, fallback);
        free(fallback);
    } else {
        execvp(argv[1], argv + 1);
    }
    fprintf(stderr, "oc-run: cannot start %s\n", command);
    return 127;
}

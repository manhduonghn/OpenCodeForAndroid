/*
 * liboc_ocrun.so -- `oc-run` entry point for targetSdk 36.
 *
 * The old entry point was a script in $PREFIX/bin, which Android 10+ refuses to
 * execute (app_data_file). This bionic PIE lives in nativeLibraryDir instead and
 * opens the static Go patcher, also shipped as an APK native library. The
 * symlink $PREFIX/bin/oc-run points here, so existing callers do not change.
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
#define GOPATCH "liboc_gopatch.so"
#define SHIM "liboc_exec.so"
#define LOADER "liboc_ld.so"

static void set_default(const char *name, const char *value) {
    if (getenv(name) == NULL || getenv(name)[0] == '\0') setenv(name, value, 1);
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

    set_default("OC_PREFIX", DEFAULT_PREFIX);
    set_default("OC_USER_HOME", DEFAULT_HOME);
    set_default("OC_EXEC_SHIM", shim);
    set_default("OC_GLIBC_LIB_DIR", DEFAULT_PREFIX "/glibc/lib");
    /* Always prefer the APK loader: the one in the prefix is app_data_file. */
    if (access(loader, F_OK) == 0) setenv("OC_GLIBC_LOADER", loader, 1);
    if (access(shim, F_OK) == 0) setenv("LD_PRELOAD", shim, 1);

    if (access(patcher, X_OK) == 0 && argc > 1) {
        size_t need = (size_t)argc + 4;
        char **next = calloc(need, sizeof(char *));
        if (next == NULL) return 125;
        next[0] = patcher;
        next[1] = (char *)"run";
        next[2] = (char *)"--";
        for (int i = 1; i < argc; i++) next[i + 2] = argv[i];
        next[argc + 2] = NULL;
        execv(patcher, next);
        /* A failed patcher run is not fatal: the command may still work. */
        free(next);
    }

    /* No patcher: let the shim route the command (if the caller has one). */
    if (argc > 1) execvp(argv[1], argv + 1);
    fprintf(stderr, "oc-run: no command given\n");
    return 127;
}

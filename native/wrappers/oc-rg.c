/*
 * liboc_rg.so -- ripgrep cache entry for targetSdk 36.
 *
 * OpenCode (a glibc process) execs its configured ripgrep binary directly. A
 * script or binary under $PREFIX is app_data_file and cannot be executed with
 * targetSdk >= 29. The cache entry is therefore a symlink to this APK library.
 * It starts the prefix ripgrep through Android's system linker, which may mmap
 * the app-data ELF even though it may not execve() it.
 */
#define _GNU_SOURCE
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define DEFAULT_PREFIX "/data/data/com.opencode.android/files/usr"
#define SYSTEM_LINKER "/system/bin/linker64"
#define SHIM "liboc_exec.so"
#define LOADER "liboc_ld.so"

/* Avoid snprintf here: gcc -Wformat-truncation cannot prove the fixed suffix fits. */
static int join_path(char *out, size_t size, const char *dir, const char *suffix) {
    size_t dir_len = strlen(dir);
    size_t suffix_len = strlen(suffix);
    if (dir_len + 1 + suffix_len + 1 > size) return -1;
    memcpy(out, dir, dir_len);
    out[dir_len] = '/';
    memcpy(out + dir_len + 1, suffix, suffix_len + 1);
    return 0;
}

int main(int argc, char **argv) {
    const char *prefix = getenv("PREFIX");
    if (prefix == NULL || prefix[0] == '\0') prefix = getenv("OC_PREFIX");
    if (prefix == NULL || prefix[0] == '\0') prefix = DEFAULT_PREFIX;

    char self[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (length <= 0) return 127;
    self[length] = '\0';
    char *slash = strrchr(self, '/');
    if (slash == NULL) return 127;
    *slash = '\0';

    char shim[PATH_MAX];
    char loader[PATH_MAX];
    if (join_path(shim, sizeof(shim), self, SHIM) != 0 ||
        join_path(loader, sizeof(loader), self, LOADER) != 0) return 127;
    if (access(shim, F_OK) == 0) {
        setenv("LD_PRELOAD", shim, 1);
        setenv("OC_EXEC_SHIM", shim, 1);
    }
    if (access(loader, F_OK) == 0) setenv("OC_GLIBC_LOADER", loader, 1);

    char binary[PATH_MAX];
    if (join_path(binary, sizeof(binary), prefix, "bin/rg") != 0) return 127;
    if (access(binary, F_OK) != 0) {
        fprintf(stderr, "oc-rg: missing %s\n", binary);
        return 127;
    }

    char **next = calloc((size_t)argc + 3, sizeof(char *));
    if (next == NULL) return 125;
    next[0] = (char *)SYSTEM_LINKER;
    next[1] = binary;
    for (int i = 1; i < argc; i++) next[i + 1] = argv[i];
    next[argc + 1] = NULL;
    execv(SYSTEM_LINKER, next);

    /* Fallback for a static ripgrep build, which the linker cannot load. */
    char **fallback = calloc((size_t)argc + 1, sizeof(char *));
    if (fallback == NULL) return 125;
    fallback[0] = argc > 0 && argv[0] != NULL ? argv[0] : (char *)"rg";
    for (int i = 1; i < argc; i++) fallback[i] = argv[i];
    execv(binary, fallback);
    perror("oc-rg");
    free(fallback);
    free(next);
    return 127;
}

/*
 * liboc-pathmap.so - translate the Termux roots and install the SIGSYS policy for
 * glibc programs that are started by OpenCode for Android.
 *
 * The packages of this app are relocated by rewriting the Termux prefix in their
 * files, but a program that the user downloads later still contains
 * /data/data/com.termux/files/usr and /data/data/com.termux/files/home. This
 * preload library maps both roots to the private prefix of the app at every file
 * system entry point.
 *
 * The library is deliberately allocation free: the two roots are copied once in
 * the constructor from OC_PREFIX / OC_USER_HOME and a thread local guard blocks
 * recursion. A path that does not start with one of the Termux roots is passed to
 * the real function with its original pointer, so the hot path is one strncmp.
 *
 * The same library installs the SIGSYS policy that Android needs for glibc: an app
 * cannot change its uid, and the seccomp filter of the platform kills the process
 * on these syscalls. The handler emulates them instead: set*id and setgroups
 * succeed, getgroups reports one empty group, clone3 and rseq return ENOSYS, and
 * every other blocked syscall returns ENOSYS so glibc retries through its older
 * path. OC_DEBUG=1 makes the handler report every swallowed syscall on stderr.
 *
 * A program that starts another glibc program keeps the preload: the exec and
 * spawn wrappers add LD_PRELOAD and the two OC_ roots to the child environment
 * (only when the target is a glibc ELF, never for bionic or static programs).
 *
 * Build: see build.sh in this directory. The version script exports only the
 * hooked symbols, everything else (including the constructor) stays hidden.
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <ftw.h>
#include <glob.h>
#include <limits.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/vfs.h>
#include <sys/xattr.h>
#include <ucontext.h>
#include <unistd.h>

extern char **environ;

#if defined(__linux__) && defined(__has_include)
#if __has_include(<linux/openat2.h>)
#include <linux/openat2.h>
#define OC_HAVE_OPENAT2 1
#endif
#endif

#define EXPORT __attribute__((visibility("default")))

#define TERMUX_PREFIX "/data/data/com.termux/files/usr"
#define TERMUX_HOME "/data/data/com.termux/files/home"
#define TERMUX_PREFIX_LEN (sizeof(TERMUX_PREFIX) - 1)
#define TERMUX_HOME_LEN (sizeof(TERMUX_HOME) - 1)

/* The map is read-only after the constructor, no locking is needed. */
static char g_prefix[PATH_MAX];
static size_t g_prefix_len;
static char g_home[PATH_MAX];
static size_t g_home_len;
static int g_enabled;

/* Set while map_path() copies, so a hook that runs inside the copy cannot recurse. */
static __thread int g_busy;

/* The path this object was loaded from, so exec can keep it in the child. */
static char g_self[PATH_MAX];

/* OC_DEBUG=1 makes the SIGSYS handler report what it swallowed. */
static int g_debug;

static void install_sigsys_policy(void);

static void copy_env(char *out, size_t size, const char *name) {
    const char *value = getenv(name);
    if (value == NULL || value[0] == '\0') return;
    size_t len = strlen(value);
    while (len > 1 && value[len - 1] == '/') len--;
    if (len == 0 || len >= size) return;
    memcpy(out, value, len);
    out[len] = '\0';
}

__attribute__((constructor)) static void pathmap_init(void) {
    copy_env(g_prefix, sizeof(g_prefix), "OC_PREFIX");
    copy_env(g_home, sizeof(g_home), "OC_USER_HOME");
    g_prefix_len = strlen(g_prefix);
    g_home_len = strlen(g_home);
    /* Mapping a Termux root onto itself would loop; refuse that root. */
    if (g_prefix_len == 0 || strcmp(g_prefix, TERMUX_PREFIX) == 0) {
        g_prefix_len = 0;
    }
    if (g_home_len == 0 || strcmp(g_home, TERMUX_HOME) == 0) {
        g_home_len = 0;
    }
    g_enabled = g_prefix_len > 0 || g_home_len > 0;
    {
        const char *debug = getenv("OC_DEBUG");
        g_debug = debug != NULL && debug[0] != '\0' && strcmp(debug, "0") != 0;
    }
    {
        /* dli_fname is the path the loader opened, which is what a child needs in
         * LD_PRELOAD. The app and oc-gopatch pass an absolute path. */
        Dl_info info;
        if (dladdr((void *)pathmap_init, &info) != 0 && info.dli_fname != NULL) {
            size_t len = strlen(info.dli_fname);
            if (len > 0 && len < sizeof(g_self)) memcpy(g_self, info.dli_fname, len + 1);
        }
    }
    install_sigsys_policy();
}

/*
 * A marker for tests and for `oc-run --debug`: a process that sees this symbol
 * has the preload, even when it inherited a cleaned environment.
 */
EXPORT int oc_pathmap_active(void) { return 1; }

/*
 * Translate [path] into [buffer] when it starts with one of the two Termux roots.
 * Returns the original pointer when no translation applies, so callers never copy.
 * A relative path, a NULL path and a path that does not fit are passed through
 * unchanged: the kernel then answers as it would for a path that long.
 */
static const char *map_path(const char *path, char *buffer, size_t size) {
    if (path == NULL || !g_enabled || g_busy || buffer == NULL || size == 0) return path;
    if (path[0] != '/') return path;

    const char *target = NULL;
    size_t old_len = 0;
    if (g_prefix_len > 0 && strncmp(path, TERMUX_PREFIX, TERMUX_PREFIX_LEN) == 0 &&
        (path[TERMUX_PREFIX_LEN] == '\0' || path[TERMUX_PREFIX_LEN] == '/')) {
        target = g_prefix;
        old_len = TERMUX_PREFIX_LEN;
    } else if (g_home_len > 0 && strncmp(path, TERMUX_HOME, TERMUX_HOME_LEN) == 0 &&
               (path[TERMUX_HOME_LEN] == '\0' || path[TERMUX_HOME_LEN] == '/')) {
        target = g_home;
        old_len = TERMUX_HOME_LEN;
    }
    if (target == NULL) return path;

    const char *suffix = path + old_len;
    size_t suffix_len = strlen(suffix);
    size_t target_len = strnlen(target, size);
    if (target_len + suffix_len + 1 > size) return path;

    g_busy = 1;
    memcpy(buffer, target, target_len);
    memcpy(buffer + target_len, suffix, suffix_len + 1);
    g_busy = 0;
    return buffer;
}

/*
 * The inverse of map_path: a path that the kernel reports with the private prefix
 * of the app is shown to the program as its Termux spelling. The replacement is
 * shorter, so it is done in place. [n] is the buffer length, [*out] the new length.
 */
static void unmap_buffer(char *buffer, size_t *n) {
    if (!g_enabled || buffer == NULL || *n == 0) return;
    const char *old = NULL;
    const char *replacement = NULL;
    size_t old_len = 0;
    size_t new_len = 0;
    if (g_prefix_len > 0 && *n >= g_prefix_len &&
        memcmp(buffer, g_prefix, g_prefix_len) == 0 &&
        (*n == g_prefix_len || buffer[g_prefix_len] == '/')) {
        old = g_prefix;
        old_len = g_prefix_len;
        replacement = TERMUX_PREFIX;
        new_len = TERMUX_PREFIX_LEN;
    } else if (g_home_len > 0 && *n >= g_home_len &&
               memcmp(buffer, g_home, g_home_len) == 0 &&
               (*n == g_home_len || buffer[g_home_len] == '/')) {
        old = g_home;
        old_len = g_home_len;
        replacement = TERMUX_HOME;
        new_len = TERMUX_HOME_LEN;
    }
    (void)old;
    if (replacement == NULL || new_len > old_len || *n - old_len > SIZE_MAX - new_len) return;
    memmove(buffer + new_len, buffer + old_len, *n - old_len);
    memcpy(buffer, replacement, new_len);
    *n = *n - old_len + new_len;
}

/* ------------------------------------------------ exec preload propagation */

/*
 * A child started by a glibc program must keep liboc-pathmap.so in LD_PRELOAD:
 * without it, the child loses both the path translation and the SIGSYS handler,
 * and a modern glibc that probes faccessat2 dies on the seccomp trap. The
 * preload is added only when the target is really a glibc ELF, because loading a
 * glibc shared object into a bionic or static program aborts it.
 */

static int read_exact(int fd, void *buffer, size_t size, off_t offset) {
    size_t done = 0;
    while (done < size) {
        ssize_t n = pread(fd, (char *)buffer + done, size - done, offset + (off_t)done);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) break;
        done += (size_t)n;
    }
    return (int)done;
}

/* True when [fd] is a 64-bit little-endian ELF whose PT_INTERP is the glibc loader. */
static int elf_is_glibc(int fd) {
    unsigned char header[64];
    if (read_exact(fd, header, sizeof(header), 0) < (int)sizeof(header)) return 0;
    if (memcmp(header, ELFMAG, SELFMAG) != 0) return 0;
    if (header[EI_CLASS] != ELFCLASS64 || header[EI_DATA] != ELFDATA2LSB) return 0;
    uint64_t phoff;
    uint16_t phentsize;
    uint16_t phnum;
    memcpy(&phoff, header + 32, sizeof(phoff));
    memcpy(&phentsize, header + 54, sizeof(phentsize));
    memcpy(&phnum, header + 56, sizeof(phnum));
    if (phentsize != 56 || phnum == 0 || phnum > 128) return 0;
    for (uint16_t i = 0; i < phnum; i++) {
        unsigned char ph[56];
        off_t at = (off_t)(phoff + (uint64_t)i * 56);
        if (read_exact(fd, ph, sizeof(ph), at) < (int)sizeof(ph)) return 0;
        uint32_t type;
        uint64_t offset;
        uint64_t filesz;
        memcpy(&type, ph + 0, sizeof(type));
        memcpy(&offset, ph + 8, sizeof(offset));
        memcpy(&filesz, ph + 32, sizeof(filesz));
        if (type != PT_INTERP) continue;
        if (filesz == 0 || filesz >= 512) return 0;
        char interp[512];
        if (read_exact(fd, interp, (size_t)filesz, (off_t)offset) < (int)filesz) return 0;
        interp[filesz] = '\0';
        return strstr(interp, "ld-linux") != NULL || strstr(interp, "ld.so") != NULL;
    }
    return 0;
}

/* A script is judged by its interpreter: `#!/bin/sh` is bionic, `#!/usr/bin/env
 * python3` is glibc when python3 is a glibc program in the private prefix. */
static int file_is_glibc(const char *path) {
    if (path == NULL || path[0] == '\0') return 0;
    int fd = openat(AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    int glibc = elf_is_glibc(fd);
    if (!glibc) {
        char line[256];
        int n = read_exact(fd, line, sizeof(line) - 1, 0);
        if (n >= 2 && line[0] == '#' && line[1] == '!') {
            line[n] = '\0';
            char *interp = line + 2;
            while (*interp == ' ' || *interp == '\t') interp++;
            char *end = interp;
            while (*end != '\0' && *end != '\n' && *end != '\r' && *end != ' ' &&
                   *end != '\t') {
                end++;
            }
            *end = '\0';
            if (interp[0] != '\0' && strcmp(interp, path) != 0) {
                char mapped[PATH_MAX];
                glibc = file_is_glibc(map_path(interp, mapped, sizeof(mapped)));
            }
        }
    }
    close(fd);
    return glibc;
}

/* Resolve a bare command name through PATH for the preload decision only. */
static int executable_file(const char *path) {
    struct stat st;
    return access(path, X_OK) == 0 && stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

static const char *find_program(const char *name, char *buffer, size_t size) {
    if (name == NULL || name[0] == '\0' || strchr(name, '/') != NULL) return name;
    const char *path = getenv("PATH");
    if (path == NULL) return name;
    size_t name_len = strlen(name);
    while (*path != '\0') {
        const char *end = strchr(path, ':');
        size_t len = end != NULL ? (size_t)(end - path) : strlen(path);
        if (len == 0) {
            if (name_len + 3 <= size) {
                buffer[0] = '.';
                buffer[1] = '/';
                memcpy(buffer + 2, name, name_len + 1);
                if (executable_file(buffer)) return buffer;
            }
        } else if (len + name_len + 2 <= size) {
            memcpy(buffer, path, len);
            buffer[len] = '/';
            memcpy(buffer + len + 1, name, name_len + 1);
            if (executable_file(buffer)) return buffer;
        }
        if (end == NULL) break;
        path = end + 1;
    }
    return name;
}

static char *join_env(const char *name, const char *value) {
    size_t name_len = strlen(name);
    size_t value_len = strlen(value);
    char *out = malloc(name_len + value_len + 2);
    if (out == NULL) return NULL;
    memcpy(out, name, name_len);
    out[name_len] = '=';
    memcpy(out + name_len + 1, value, value_len + 1);
    return out;
}

static char *merge_preload(const char *value) {
    size_t len = strlen("LD_PRELOAD=") + strlen(g_self) + 1 + strlen(value) + 1;
    char *out = malloc(len);
    if (out == NULL) return NULL;
    if (value[0] != '\0') {
        snprintf(out, len, "LD_PRELOAD=%s %s", g_self, value);
    } else {
        snprintf(out, len, "LD_PRELOAD=%s", g_self);
    }
    return out;
}

static int entry_is_original(const char *entry, char *const source[], size_t count) {
    for (size_t i = 0; i < count; i++) {
        if (source[i] == entry) return 1;
    }
    return 0;
}

static void free_preload_env(char **env, char *const source[], size_t count) {
    if (env == NULL) return;
    for (size_t i = 0; env[i] != NULL; i++) {
        if (!entry_is_original(env[i], source, count)) free(env[i]);
    }
    free(env);
}

/*
 * Build a copy of [source] (or a fresh environment when it is NULL) that always
 * has LD_PRELOAD, OC_PREFIX and OC_USER_HOME. Returns NULL when the target is not
 * glibc or the allocation failed; the caller then uses the original environment.
 */
static char **preload_env(const char *target, char *const source[], size_t count) {
    if (!g_enabled || g_self[0] == '\0') return NULL;
    if (!file_is_glibc(target)) return NULL;
    char **out = calloc(count + 4, sizeof(char *));
    if (out == NULL) return NULL;
    size_t used = 0;
    int have_preload = 0;
    int have_prefix = 0;
    int have_home = 0;
    for (size_t i = 0; i < count; i++) {
        const char *entry = source[i];
        if (strncmp(entry, "LD_PRELOAD=", 11) == 0) {
            if (strstr(entry + 11, g_self) != NULL) {
                out[used++] = source[i];
            } else {
                out[used] = merge_preload(entry + 11);
                if (out[used] == NULL) goto fail;
                used++;
            }
            have_preload = 1;
        } else if (strncmp(entry, "OC_PREFIX=", 10) == 0) {
            out[used++] = source[i];
            have_prefix = 1;
        } else if (strncmp(entry, "OC_USER_HOME=", 13) == 0) {
            out[used++] = source[i];
            have_home = 1;
        } else {
            out[used++] = source[i];
        }
    }
    if (!have_preload) {
        out[used] = join_env("LD_PRELOAD", g_self);
        if (out[used] == NULL) goto fail;
        used++;
    }
    if (!have_prefix && g_prefix_len > 0) {
        out[used] = join_env("OC_PREFIX", g_prefix);
        if (out[used] == NULL) goto fail;
        used++;
    }
    if (!have_home && g_home_len > 0) {
        out[used] = join_env("OC_USER_HOME", g_home);
        if (out[used] == NULL) goto fail;
        used++;
    }
    out[used] = NULL;
    return out;
fail:
    free_preload_env(out, source, count);
    return NULL;
}

/* ---------------------------------------------------------------- dlsym helpers */

#define RESOLVE(name, type)                                                   \
    static type real_##name;                                                  \
    if (real_##name == NULL) {                                                \
        *(void **)(&real_##name) = dlsym(RTLD_NEXT, #name);                   \
        if (real_##name == NULL) {                                            \
            errno = ENOSYS;                                                   \
            return -1;                                                        \
        }                                                                     \
    }

#define RESOLVE_PTR(name, type)                                               \
    static type real_##name;                                                  \
    if (real_##name == NULL) {                                                \
        *(void **)(&real_##name) = dlsym(RTLD_NEXT, #name);                   \
        if (real_##name == NULL) {                                            \
            errno = ENOSYS;                                                   \
            return NULL;                                                      \
        }                                                                     \
    }

/* ------------------------------------------------------------------- open/openat */

EXPORT int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }
    typedef int (*fn_t)(const char *, int, ...);
    RESOLVE(open, fn_t)
    char buffer[PATH_MAX];
    return real_open(map_path(path, buffer, sizeof(buffer)), flags, mode);
}

EXPORT int open64(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }
    typedef int (*fn_t)(const char *, int, ...);
    RESOLVE(open64, fn_t)
    char buffer[PATH_MAX];
    return real_open64(map_path(path, buffer, sizeof(buffer)), flags, mode);
}

EXPORT int openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }
    typedef int (*fn_t)(int, const char *, int, ...);
    RESOLVE(openat, fn_t)
    char buffer[PATH_MAX];
    return real_openat(dirfd, map_path(path, buffer, sizeof(buffer)), flags, mode);
}

EXPORT int openat64(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }
    typedef int (*fn_t)(int, const char *, int, ...);
    RESOLVE(openat64, fn_t)
    char buffer[PATH_MAX];
    return real_openat64(dirfd, map_path(path, buffer, sizeof(buffer)), flags, mode);
}

#ifdef OC_HAVE_OPENAT2
EXPORT int openat2(int dirfd, const char *path, const struct open_how *how, size_t size) {
    typedef int (*fn_t)(int, const char *, const struct open_how *, size_t);
    RESOLVE(openat2, fn_t)
    char buffer[PATH_MAX];
    return real_openat2(dirfd, map_path(path, buffer, sizeof(buffer)), how, size);
}
#endif

/*
 * The fortified entry points. A program built with _FORTIFY_SOURCE calls these
 * instead of open/openat, and some of them are reached without the PLT, so they
 * need the same translation.
 */
EXPORT int __open_2(const char *path, int flags) {
    typedef int (*fn_t)(const char *, int);
    RESOLVE(__open_2, fn_t)
    char buffer[PATH_MAX];
    return real___open_2(map_path(path, buffer, sizeof(buffer)), flags);
}

EXPORT int __open64_2(const char *path, int flags) {
    typedef int (*fn_t)(const char *, int);
    RESOLVE(__open64_2, fn_t)
    char buffer[PATH_MAX];
    return real___open64_2(map_path(path, buffer, sizeof(buffer)), flags);
}

EXPORT int __openat_2(int dirfd, const char *path, int flags) {
    typedef int (*fn_t)(int, const char *, int);
    RESOLVE(__openat_2, fn_t)
    char buffer[PATH_MAX];
    return real___openat_2(dirfd, map_path(path, buffer, sizeof(buffer)), flags);
}

EXPORT int __openat64_2(int dirfd, const char *path, int flags) {
    typedef int (*fn_t)(int, const char *, int);
    RESOLVE(__openat64_2, fn_t)
    char buffer[PATH_MAX];
    return real___openat64_2(dirfd, map_path(path, buffer, sizeof(buffer)), flags);
}

EXPORT int creat(const char *path, mode_t mode) {
    typedef int (*fn_t)(const char *, mode_t);
    RESOLVE(creat, fn_t)
    char buffer[PATH_MAX];
    return real_creat(map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int creat64(const char *path, mode_t mode) {
    typedef int (*fn_t)(const char *, mode_t);
    RESOLVE(creat64, fn_t)
    char buffer[PATH_MAX];
    return real_creat64(map_path(path, buffer, sizeof(buffer)), mode);
}

/* ---------------------------------------------------------------------- stdio */

EXPORT FILE *fopen(const char *path, const char *mode) {
    typedef FILE *(*fn_t)(const char *, const char *);
    RESOLVE_PTR(fopen, fn_t)
    char buffer[PATH_MAX];
    return real_fopen(map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT FILE *fopen64(const char *path, const char *mode) {
    typedef FILE *(*fn_t)(const char *, const char *);
    RESOLVE_PTR(fopen64, fn_t)
    char buffer[PATH_MAX];
    return real_fopen64(map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT FILE *freopen(const char *path, const char *mode, FILE *stream) {
    typedef FILE *(*fn_t)(const char *, const char *, FILE *);
    RESOLVE_PTR(freopen, fn_t)
    char buffer[PATH_MAX];
    return real_freopen(map_path(path, buffer, sizeof(buffer)), mode, stream);
}

EXPORT FILE *freopen64(const char *path, const char *mode, FILE *stream) {
    typedef FILE *(*fn_t)(const char *, const char *, FILE *);
    RESOLVE_PTR(freopen64, fn_t)
    char buffer[PATH_MAX];
    return real_freopen64(map_path(path, buffer, sizeof(buffer)), mode, stream);
}

EXPORT DIR *opendir(const char *path) {
    typedef DIR *(*fn_t)(const char *);
    RESOLVE_PTR(opendir, fn_t)
    char buffer[PATH_MAX];
    return real_opendir(map_path(path, buffer, sizeof(buffer)));
}

/* --------------------------------------------------------------------- stat */

EXPORT int stat(const char *path, struct stat *buf) {
    typedef int (*fn_t)(const char *, struct stat *);
    RESOLVE(stat, fn_t)
    char buffer[PATH_MAX];
    return real_stat(map_path(path, buffer, sizeof(buffer)), buf);
}

EXPORT int lstat(const char *path, struct stat *buf) {
    typedef int (*fn_t)(const char *, struct stat *);
    RESOLVE(lstat, fn_t)
    char buffer[PATH_MAX];
    return real_lstat(map_path(path, buffer, sizeof(buffer)), buf);
}

EXPORT int stat64(const char *path, struct stat64 *buf) {
    typedef int (*fn_t)(const char *, struct stat64 *);
    RESOLVE(stat64, fn_t)
    char buffer[PATH_MAX];
    return real_stat64(map_path(path, buffer, sizeof(buffer)), buf);
}

EXPORT int lstat64(const char *path, struct stat64 *buf) {
    typedef int (*fn_t)(const char *, struct stat64 *);
    RESOLVE(lstat64, fn_t)
    char buffer[PATH_MAX];
    return real_lstat64(map_path(path, buffer, sizeof(buffer)), buf);
}

EXPORT int fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    typedef int (*fn_t)(int, const char *, struct stat *, int);
    RESOLVE(fstatat, fn_t)
    char buffer[PATH_MAX];
    return real_fstatat(dirfd, map_path(path, buffer, sizeof(buffer)), buf, flags);
}

EXPORT int fstatat64(int dirfd, const char *path, struct stat64 *buf, int flags) {
    typedef int (*fn_t)(int, const char *, struct stat64 *, int);
    RESOLVE(fstatat64, fn_t)
    char buffer[PATH_MAX];
    return real_fstatat64(dirfd, map_path(path, buffer, sizeof(buffer)), buf, flags);
}

EXPORT int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *buf) {
    typedef int (*fn_t)(int, const char *, int, unsigned int, struct statx *);
    RESOLVE(statx, fn_t)
    char buffer[PATH_MAX];
    return real_statx(dirfd, map_path(path, buffer, sizeof(buffer)), flags, mask, buf);
}

EXPORT int statfs(const char *path, struct statfs *buf) {
    typedef int (*fn_t)(const char *, struct statfs *);
    RESOLVE(statfs, fn_t)
    char buffer[PATH_MAX];
    return real_statfs(map_path(path, buffer, sizeof(buffer)), buf);
}

EXPORT int statfs64(const char *path, struct statfs64 *buf) {
    typedef int (*fn_t)(const char *, struct statfs64 *);
    RESOLVE(statfs64, fn_t)
    char buffer[PATH_MAX];
    return real_statfs64(map_path(path, buffer, sizeof(buffer)), buf);
}

EXPORT int statvfs(const char *path, struct statvfs *buf) {
    typedef int (*fn_t)(const char *, struct statvfs *);
    RESOLVE(statvfs, fn_t)
    char buffer[PATH_MAX];
    return real_statvfs(map_path(path, buffer, sizeof(buffer)), buf);
}

EXPORT int statvfs64(const char *path, struct statvfs64 *buf) {
    typedef int (*fn_t)(const char *, struct statvfs64 *);
    RESOLVE(statvfs64, fn_t)
    char buffer[PATH_MAX];
    return real_statvfs64(map_path(path, buffer, sizeof(buffer)), buf);
}

/* -------------------------------------------------------------- timestamps */

EXPORT int utimensat(int dirfd, const char *path, const struct timespec times[2], int flags) {
    typedef int (*fn_t)(int, const char *, const struct timespec[2], int);
    RESOLVE(utimensat, fn_t)
    char buffer[PATH_MAX];
    return real_utimensat(dirfd, map_path(path, buffer, sizeof(buffer)), times, flags);
}

EXPORT int utimes(const char *path, const struct timeval times[2]) {
    typedef int (*fn_t)(const char *, const struct timeval[2]);
    RESOLVE(utimes, fn_t)
    char buffer[PATH_MAX];
    return real_utimes(map_path(path, buffer, sizeof(buffer)), times);
}

EXPORT int lutimes(const char *path, const struct timeval times[2]) {
    typedef int (*fn_t)(const char *, const struct timeval[2]);
    RESOLVE(lutimes, fn_t)
    char buffer[PATH_MAX];
    return real_lutimes(map_path(path, buffer, sizeof(buffer)), times);
}

EXPORT int futimesat(int dirfd, const char *path, const struct timeval times[2]) {
    typedef int (*fn_t)(int, const char *, const struct timeval[2]);
    RESOLVE(futimesat, fn_t)
    char buffer[PATH_MAX];
    return real_futimesat(dirfd, map_path(path, buffer, sizeof(buffer)), times);
}

/* ------------------------------------------------------------------- xattrs */

EXPORT ssize_t getxattr(const char *path, const char *name, void *value, size_t size) {
    typedef ssize_t (*fn_t)(const char *, const char *, void *, size_t);
    RESOLVE(getxattr, fn_t)
    char buffer[PATH_MAX];
    return real_getxattr(map_path(path, buffer, sizeof(buffer)), name, value, size);
}

EXPORT ssize_t lgetxattr(const char *path, const char *name, void *value, size_t size) {
    typedef ssize_t (*fn_t)(const char *, const char *, void *, size_t);
    RESOLVE(lgetxattr, fn_t)
    char buffer[PATH_MAX];
    return real_lgetxattr(map_path(path, buffer, sizeof(buffer)), name, value, size);
}

EXPORT int setxattr(const char *path, const char *name, const void *value, size_t size,
                    int flags) {
    typedef int (*fn_t)(const char *, const char *, const void *, size_t, int);
    RESOLVE(setxattr, fn_t)
    char buffer[PATH_MAX];
    return real_setxattr(map_path(path, buffer, sizeof(buffer)), name, value, size, flags);
}

EXPORT int lsetxattr(const char *path, const char *name, const void *value, size_t size,
                     int flags) {
    typedef int (*fn_t)(const char *, const char *, const void *, size_t, int);
    RESOLVE(lsetxattr, fn_t)
    char buffer[PATH_MAX];
    return real_lsetxattr(map_path(path, buffer, sizeof(buffer)), name, value, size, flags);
}

EXPORT ssize_t listxattr(const char *path, char *list, size_t size) {
    typedef ssize_t (*fn_t)(const char *, char *, size_t);
    RESOLVE(listxattr, fn_t)
    char buffer[PATH_MAX];
    return real_listxattr(map_path(path, buffer, sizeof(buffer)), list, size);
}

EXPORT ssize_t llistxattr(const char *path, char *list, size_t size) {
    typedef ssize_t (*fn_t)(const char *, char *, size_t);
    RESOLVE(llistxattr, fn_t)
    char buffer[PATH_MAX];
    return real_llistxattr(map_path(path, buffer, sizeof(buffer)), list, size);
}

EXPORT int removexattr(const char *path, const char *name) {
    typedef int (*fn_t)(const char *, const char *);
    RESOLVE(removexattr, fn_t)
    char buffer[PATH_MAX];
    return real_removexattr(map_path(path, buffer, sizeof(buffer)), name);
}

EXPORT int lremovexattr(const char *path, const char *name) {
    typedef int (*fn_t)(const char *, const char *);
    RESOLVE(lremovexattr, fn_t)
    char buffer[PATH_MAX];
    return real_lremovexattr(map_path(path, buffer, sizeof(buffer)), name);
}

/* --------------------------------------------------------------------- access */

EXPORT int access(const char *path, int mode) {
    typedef int (*fn_t)(const char *, int);
    RESOLVE(access, fn_t)
    char buffer[PATH_MAX];
    return real_access(map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int eaccess(const char *path, int mode) {
    typedef int (*fn_t)(const char *, int);
    RESOLVE(eaccess, fn_t)
    char buffer[PATH_MAX];
    return real_eaccess(map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int faccessat(int dirfd, const char *path, int mode, int flags) {
    typedef int (*fn_t)(int, const char *, int, int);
    RESOLVE(faccessat, fn_t)
    char buffer[PATH_MAX];
    return real_faccessat(dirfd, map_path(path, buffer, sizeof(buffer)), mode, flags);
}

/* ------------------------------------------------------------------ file ops */

EXPORT int mkdir(const char *path, mode_t mode) {
    typedef int (*fn_t)(const char *, mode_t);
    RESOLVE(mkdir, fn_t)
    char buffer[PATH_MAX];
    return real_mkdir(map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int mkdirat(int dirfd, const char *path, mode_t mode) {
    typedef int (*fn_t)(int, const char *, mode_t);
    RESOLVE(mkdirat, fn_t)
    char buffer[PATH_MAX];
    return real_mkdirat(dirfd, map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int mknod(const char *path, mode_t mode, dev_t dev) {
    typedef int (*fn_t)(const char *, mode_t, dev_t);
    RESOLVE(mknod, fn_t)
    char buffer[PATH_MAX];
    return real_mknod(map_path(path, buffer, sizeof(buffer)), mode, dev);
}

EXPORT int mknodat(int dirfd, const char *path, mode_t mode, dev_t dev) {
    typedef int (*fn_t)(int, const char *, mode_t, dev_t);
    RESOLVE(mknodat, fn_t)
    char buffer[PATH_MAX];
    return real_mknodat(dirfd, map_path(path, buffer, sizeof(buffer)), mode, dev);
}

EXPORT int mkfifo(const char *path, mode_t mode) {
    typedef int (*fn_t)(const char *, mode_t);
    RESOLVE(mkfifo, fn_t)
    char buffer[PATH_MAX];
    return real_mkfifo(map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int mkfifoat(int dirfd, const char *path, mode_t mode) {
    typedef int (*fn_t)(int, const char *, mode_t);
    RESOLVE(mkfifoat, fn_t)
    char buffer[PATH_MAX];
    return real_mkfifoat(dirfd, map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int rmdir(const char *path) {
    typedef int (*fn_t)(const char *);
    RESOLVE(rmdir, fn_t)
    char buffer[PATH_MAX];
    return real_rmdir(map_path(path, buffer, sizeof(buffer)));
}

EXPORT int unlink(const char *path) {
    typedef int (*fn_t)(const char *);
    RESOLVE(unlink, fn_t)
    char buffer[PATH_MAX];
    return real_unlink(map_path(path, buffer, sizeof(buffer)));
}

EXPORT int unlinkat(int dirfd, const char *path, int flags) {
    typedef int (*fn_t)(int, const char *, int);
    RESOLVE(unlinkat, fn_t)
    char buffer[PATH_MAX];
    return real_unlinkat(dirfd, map_path(path, buffer, sizeof(buffer)), flags);
}

EXPORT int rename(const char *old_path, const char *new_path) {
    typedef int (*fn_t)(const char *, const char *);
    RESOLVE(rename, fn_t)
    char old_buffer[PATH_MAX];
    char new_buffer[PATH_MAX];
    return real_rename(
        map_path(old_path, old_buffer, sizeof(old_buffer)),
        map_path(new_path, new_buffer, sizeof(new_buffer)));
}

EXPORT int renameat(int old_dirfd, const char *old_path, int new_dirfd, const char *new_path) {
    typedef int (*fn_t)(int, const char *, int, const char *);
    static fn_t real_renameat;
    if (real_renameat == NULL) {
        *(void **)(&real_renameat) = dlsym(RTLD_NEXT, "renameat");
        if (real_renameat == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }
    char old_buffer[PATH_MAX];
    char new_buffer[PATH_MAX];
    return real_renameat(
        old_dirfd, map_path(old_path, old_buffer, sizeof(old_buffer)),
        new_dirfd, map_path(new_path, new_buffer, sizeof(new_buffer)));
}

EXPORT int renameat2(int old_dirfd, const char *old_path, int new_dirfd, const char *new_path,
                     unsigned int flags) {
    typedef int (*fn_t)(int, const char *, int, const char *, unsigned int);
    RESOLVE(renameat2, fn_t)
    char old_buffer[PATH_MAX];
    char new_buffer[PATH_MAX];
    return real_renameat2(
        old_dirfd, map_path(old_path, old_buffer, sizeof(old_buffer)),
        new_dirfd, map_path(new_path, new_buffer, sizeof(new_buffer)), flags);
}

EXPORT int chdir(const char *path) {
    typedef int (*fn_t)(const char *);
    RESOLVE(chdir, fn_t)
    char buffer[PATH_MAX];
    return real_chdir(map_path(path, buffer, sizeof(buffer)));
}

EXPORT int chmod(const char *path, mode_t mode) {
    typedef int (*fn_t)(const char *, mode_t);
    RESOLVE(chmod, fn_t)
    char buffer[PATH_MAX];
    return real_chmod(map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int fchmodat(int dirfd, const char *path, mode_t mode, int flags) {
    typedef int (*fn_t)(int, const char *, mode_t, int);
    RESOLVE(fchmodat, fn_t)
    char buffer[PATH_MAX];
    return real_fchmodat(dirfd, map_path(path, buffer, sizeof(buffer)), mode, flags);
}

EXPORT int chown(const char *path, uid_t owner, gid_t group) {
    typedef int (*fn_t)(const char *, uid_t, gid_t);
    RESOLVE(chown, fn_t)
    char buffer[PATH_MAX];
    return real_chown(map_path(path, buffer, sizeof(buffer)), owner, group);
}

EXPORT int lchown(const char *path, uid_t owner, gid_t group) {
    typedef int (*fn_t)(const char *, uid_t, gid_t);
    RESOLVE(lchown, fn_t)
    char buffer[PATH_MAX];
    return real_lchown(map_path(path, buffer, sizeof(buffer)), owner, group);
}

EXPORT int fchownat(int dirfd, const char *path, uid_t owner, gid_t group, int flags) {
    typedef int (*fn_t)(int, const char *, uid_t, gid_t, int);
    RESOLVE(fchownat, fn_t)
    char buffer[PATH_MAX];
    return real_fchownat(dirfd, map_path(path, buffer, sizeof(buffer)), owner, group, flags);
}

EXPORT int truncate(const char *path, off_t length) {
    typedef int (*fn_t)(const char *, off_t);
    RESOLVE(truncate, fn_t)
    char buffer[PATH_MAX];
    return real_truncate(map_path(path, buffer, sizeof(buffer)), length);
}

EXPORT int truncate64(const char *path, off64_t length) {
    typedef int (*fn_t)(const char *, off64_t);
    RESOLVE(truncate64, fn_t)
    char buffer[PATH_MAX];
    return real_truncate64(map_path(path, buffer, sizeof(buffer)), length);
}

EXPORT int link(const char *old_path, const char *new_path) {
    typedef int (*fn_t)(const char *, const char *);
    RESOLVE(link, fn_t)
    char old_buffer[PATH_MAX];
    char new_buffer[PATH_MAX];
    return real_link(
        map_path(old_path, old_buffer, sizeof(old_buffer)),
        map_path(new_path, new_buffer, sizeof(new_buffer)));
}

EXPORT int linkat(int old_dirfd, const char *old_path, int new_dirfd, const char *new_path,
                  int flags) {
    typedef int (*fn_t)(int, const char *, int, const char *, int);
    RESOLVE(linkat, fn_t)
    char old_buffer[PATH_MAX];
    char new_buffer[PATH_MAX];
    return real_linkat(
        old_dirfd, map_path(old_path, old_buffer, sizeof(old_buffer)),
        new_dirfd, map_path(new_path, new_buffer, sizeof(new_buffer)), flags);
}

EXPORT int symlink(const char *target, const char *link_path) {
    typedef int (*fn_t)(const char *, const char *);
    RESOLVE(symlink, fn_t)
    char target_buffer[PATH_MAX];
    char link_buffer[PATH_MAX];
    return real_symlink(
        map_path(target, target_buffer, sizeof(target_buffer)),
        map_path(link_path, link_buffer, sizeof(link_buffer)));
}

EXPORT int symlinkat(const char *target, int dirfd, const char *link_path) {
    typedef int (*fn_t)(const char *, int, const char *);
    RESOLVE(symlinkat, fn_t)
    char target_buffer[PATH_MAX];
    char link_buffer[PATH_MAX];
    return real_symlinkat(
        map_path(target, target_buffer, sizeof(target_buffer)),
        dirfd, map_path(link_path, link_buffer, sizeof(link_buffer)));
}

EXPORT ssize_t readlink(const char *path, char *buf, size_t size) {
    typedef ssize_t (*fn_t)(const char *, char *, size_t);
    RESOLVE(readlink, fn_t)
    char buffer[PATH_MAX];
    ssize_t n = real_readlink(map_path(path, buffer, sizeof(buffer)), buf, size);
    if (n > 0) {
        size_t length = (size_t)n;
        unmap_buffer(buf, &length);
        n = (ssize_t)length;
    }
    return n;
}

EXPORT ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    typedef ssize_t (*fn_t)(int, const char *, char *, size_t);
    RESOLVE(readlinkat, fn_t)
    char buffer[PATH_MAX];
    ssize_t n = real_readlinkat(dirfd, map_path(path, buffer, sizeof(buffer)), buf, size);
    if (n > 0) {
        size_t length = (size_t)n;
        unmap_buffer(buf, &length);
        n = (ssize_t)length;
    }
    return n;
}

EXPORT char *realpath(const char *path, char *resolved) {
    typedef char *(*fn_t)(const char *, char *);
    RESOLVE_PTR(realpath, fn_t)
    char buffer[PATH_MAX];
    char *result = real_realpath(map_path(path, buffer, sizeof(buffer)), resolved);
    if (result != NULL) {
        size_t length = strlen(result);
        unmap_buffer(result, &length);
        result[length] = '\0';
    }
    return result;
}

EXPORT char *getcwd(char *buf, size_t size) {
    typedef char *(*fn_t)(char *, size_t);
    RESOLVE_PTR(getcwd, fn_t)
    char *result = real_getcwd(buf, size);
    if (result != NULL) {
        size_t length = strlen(result);
        unmap_buffer(result, &length);
        result[length] = '\0';
    }
    return result;
}

EXPORT int inotify_add_watch(int fd, const char *path, uint32_t mask) {
    typedef int (*fn_t)(int, const char *, uint32_t);
    RESOLVE(inotify_add_watch, fn_t)
    char buffer[PATH_MAX];
    return real_inotify_add_watch(fd, map_path(path, buffer, sizeof(buffer)), mask);
}

/* --------------------------------------------------------------- directory */

EXPORT int scandir(const char *path, struct dirent ***namelist,
                   int (*selector)(const struct dirent *),
                   int (*compare)(const struct dirent **, const struct dirent **)) {
    typedef int (*fn_t)(const char *, struct dirent ***, int (*)(const struct dirent *),
                        int (*)(const struct dirent **, const struct dirent **));
    RESOLVE(scandir, fn_t)
    char buffer[PATH_MAX];
    return real_scandir(map_path(path, buffer, sizeof(buffer)), namelist, selector, compare);
}

EXPORT int scandir64(const char *path, struct dirent64 ***namelist,
                     int (*selector)(const struct dirent64 *),
                     int (*compare)(const struct dirent64 **, const struct dirent64 **)) {
    typedef int (*fn_t)(const char *, struct dirent64 ***, int (*)(const struct dirent64 *),
                        int (*)(const struct dirent64 **, const struct dirent64 **));
    RESOLVE(scandir64, fn_t)
    char buffer[PATH_MAX];
    return real_scandir64(map_path(path, buffer, sizeof(buffer)), namelist, selector, compare);
}

EXPORT int scandirat(int dirfd, const char *path, struct dirent ***namelist,
                     int (*selector)(const struct dirent *),
                     int (*compare)(const struct dirent **, const struct dirent **)) {
    typedef int (*fn_t)(int, const char *, struct dirent ***, int (*)(const struct dirent *),
                        int (*)(const struct dirent **, const struct dirent **));
    RESOLVE(scandirat, fn_t)
    char buffer[PATH_MAX];
    return real_scandirat(dirfd, map_path(path, buffer, sizeof(buffer)), namelist, selector,
                          compare);
}

EXPORT int scandirat64(int dirfd, const char *path, struct dirent64 ***namelist,
                       int (*selector)(const struct dirent64 *),
                       int (*compare)(const struct dirent64 **, const struct dirent64 **)) {
    typedef int (*fn_t)(int, const char *, struct dirent64 ***, int (*)(const struct dirent64 *),
                        int (*)(const struct dirent64 **, const struct dirent64 **));
    RESOLVE(scandirat64, fn_t)
    char buffer[PATH_MAX];
    return real_scandirat64(dirfd, map_path(path, buffer, sizeof(buffer)), namelist, selector,
                            compare);
}

/* glob returns the matched paths, so they are unmapped before the caller sees them. */
static void unmap_path_list(char **list, size_t count) {
    if (list == NULL) return;
    for (size_t i = 0; i < count; i++) {
        if (list[i] == NULL) continue;
        size_t length = strlen(list[i]);
        unmap_buffer(list[i], &length);
        list[i][length] = '\0';
    }
}

EXPORT int glob(const char *pattern, int flags, int (*errfunc)(const char *, int),
                glob_t *pglob) {
    typedef int (*fn_t)(const char *, int, int (*)(const char *, int), glob_t *);
    RESOLVE(glob, fn_t)
    char buffer[PATH_MAX];
    int rc = real_glob(map_path(pattern, buffer, sizeof(buffer)), flags, errfunc, pglob);
    if (rc == 0 && pglob != NULL) unmap_path_list(pglob->gl_pathv, pglob->gl_pathc);
    return rc;
}

EXPORT int glob64(const char *pattern, int flags, int (*errfunc)(const char *, int),
                  glob64_t *pglob) {
    typedef int (*fn_t)(const char *, int, int (*)(const char *, int), glob64_t *);
    RESOLVE(glob64, fn_t)
    char buffer[PATH_MAX];
    int rc = real_glob64(map_path(pattern, buffer, sizeof(buffer)), flags, errfunc, pglob);
    if (rc == 0 && pglob != NULL) unmap_path_list(pglob->gl_pathv, pglob->gl_pathc);
    return rc;
}

EXPORT int ftw(const char *path, __ftw_func_t func, int descriptors) {
    typedef int (*fn_t)(const char *, __ftw_func_t, int);
    RESOLVE(ftw, fn_t)
    char buffer[PATH_MAX];
    return real_ftw(map_path(path, buffer, sizeof(buffer)), func, descriptors);
}

EXPORT int nftw(const char *path, __nftw_func_t func, int descriptors, int flags) {
    typedef int (*fn_t)(const char *, __nftw_func_t, int, int);
    RESOLVE(nftw, fn_t)
    char buffer[PATH_MAX];
    return real_nftw(map_path(path, buffer, sizeof(buffer)), func, descriptors, flags);
}

EXPORT void *dlopen(const char *file, int flags) {
    typedef void *(*fn_t)(const char *, int);
    RESOLVE_PTR(dlopen, fn_t)
    char buffer[PATH_MAX];
    return real_dlopen(map_path(file, buffer, sizeof(buffer)), flags);
}

/* ---------------------------------------------------------------------- exec */

/*
 * A glibc program is started through its loader by `oc-run`, and this library is
 * preloaded. A program that execs a child would lose the preload unless it is put
 * back here, so the exec and spawn wrappers extend the environment of a glibc
 * target (never of a bionic or static one). The path itself is still translated
 * in place: the interpreter of a relocated package points into this app.
 */
#define EXEC_ARG_MAX 1024

static char **exec_environment(const char *target, char *const envp[], size_t *count) {
    char *const *source = envp;
    size_t n = 0;
    if (source != NULL) {
        while (source[n] != NULL) n++;
    }
    *count = n;
    return preload_env(target, source, n);
}

EXPORT int execve(const char *path, char *const argv[], char *const envp[]) {
    typedef int (*fn_t)(const char *, char *const[], char *const[]);
    RESOLVE(execve, fn_t)
    char buffer[PATH_MAX];
    const char *mapped = map_path(path, buffer, sizeof(buffer));
    size_t count = 0;
    char **env = exec_environment(mapped, envp, &count);
    int rc = real_execve(mapped, argv, env != NULL ? (char *const *)env : envp);
    free_preload_env(env, envp, count);
    return rc;
}

EXPORT int execv(const char *path, char *const argv[]) {
    typedef int (*fn_t)(const char *, char *const[], char *const[]);
    RESOLVE(execve, fn_t)
    char buffer[PATH_MAX];
    const char *mapped = map_path(path, buffer, sizeof(buffer));
    size_t count = 0;
    char **env = exec_environment(mapped, environ, &count);
    int rc = real_execve(mapped, argv, env != NULL ? (char *const *)env : environ);
    free_preload_env(env, environ, count);
    return rc;
}

EXPORT int execvp(const char *file, char *const argv[]) {
    typedef int (*fn_t)(const char *, char *const[], char *const[]);
    RESOLVE(execvpe, fn_t)
    char buffer[PATH_MAX];
    char found[PATH_MAX];
    const char *mapped = map_path(file, buffer, sizeof(buffer));
    const char *target = find_program(mapped, found, sizeof(found));
    size_t count = 0;
    char **env = exec_environment(target, environ, &count);
    int rc = real_execvpe(target, argv, env != NULL ? (char *const *)env : environ);
    free_preload_env(env, environ, count);
    return rc;
}

EXPORT int execvpe(const char *file, char *const argv[], char *const envp[]) {
    typedef int (*fn_t)(const char *, char *const[], char *const[]);
    RESOLVE(execvpe, fn_t)
    char buffer[PATH_MAX];
    char found[PATH_MAX];
    const char *mapped = map_path(file, buffer, sizeof(buffer));
    const char *target = find_program(mapped, found, sizeof(found));
    size_t count = 0;
    char **env = exec_environment(target, envp, &count);
    int rc = real_execvpe(target, argv, env != NULL ? (char *const *)env : envp);
    free_preload_env(env, envp, count);
    return rc;
}

EXPORT int execveat(int dirfd, const char *path, char *const argv[], char *const envp[],
                    int flags) {
    typedef int (*fn_t)(int, const char *, char *const[], char *const[], int);
    RESOLVE(execveat, fn_t)
    char buffer[PATH_MAX];
    const char *mapped = map_path(path, buffer, sizeof(buffer));
    char probe[64];
    const char *target = mapped;
    if (mapped != NULL && mapped[0] == '\0' && (flags & AT_EMPTY_PATH)) {
        /* The executable is a descriptor; /proc/self/fd follows it for detection. */
        snprintf(probe, sizeof(probe), "/proc/self/fd/%d", dirfd);
        target = probe;
    }
    size_t count = 0;
    char **env = exec_environment(target, envp, &count);
    int rc = real_execveat(dirfd, mapped, argv, env != NULL ? (char *const *)env : envp, flags);
    free_preload_env(env, envp, count);
    return rc;
}

EXPORT int fexecve(int fd, char *const argv[], char *const envp[]) {
    typedef int (*fn_t)(int, char *const[], char *const[]);
    RESOLVE(fexecve, fn_t)
    char probe[64];
    snprintf(probe, sizeof(probe), "/proc/self/fd/%d", fd);
    size_t count = 0;
    char **env = exec_environment(probe, envp, &count);
    int rc = real_fexecve(fd, argv, env != NULL ? (char *const *)env : envp);
    free_preload_env(env, envp, count);
    return rc;
}

/*
 * The `exec*l` family is variadic and cannot be forwarded; the arguments are
 * collected here and handed to the non-variadic entry points above.
 */
static int collect_args(char **argv, size_t capacity, const char *first, va_list *args) {
    size_t used = 0;
    argv[used++] = (char *)first;
    while (used < capacity) {
        char *value = va_arg(*args, char *);
        argv[used++] = value;
        if (value == NULL) return 0;
    }
    errno = E2BIG;
    return -1;
}

EXPORT int execl(const char *path, const char *arg, ...) {
    char *argv[EXEC_ARG_MAX];
    va_list args;
    va_start(args, arg);
    int rc = collect_args(argv, EXEC_ARG_MAX, arg, &args);
    va_end(args);
    if (rc != 0) return rc;
    return execv(path, argv);
}

EXPORT int execlp(const char *file, const char *arg, ...) {
    char *argv[EXEC_ARG_MAX];
    va_list args;
    va_start(args, arg);
    int rc = collect_args(argv, EXEC_ARG_MAX, arg, &args);
    va_end(args);
    if (rc != 0) return rc;
    return execvp(file, argv);
}

EXPORT int execle(const char *path, const char *arg, ...) {
    char *argv[EXEC_ARG_MAX];
    va_list args;
    va_start(args, arg);
    int rc = collect_args(argv, EXEC_ARG_MAX, arg, &args);
    if (rc != 0) {
        va_end(args);
        return rc;
    }
    char *const *envp = va_arg(args, char *const *);
    va_end(args);
    return execve(path, argv, envp);
}

EXPORT int posix_spawn(pid_t *pid, const char *path,
                       const posix_spawn_file_actions_t *actions,
                       const posix_spawnattr_t *attr, char *const argv[],
                       char *const envp[]) {
    typedef int (*fn_t)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
    RESOLVE(posix_spawn, fn_t)
    char buffer[PATH_MAX];
    const char *mapped = map_path(path, buffer, sizeof(buffer));
    size_t count = 0;
    char **env = exec_environment(mapped, envp, &count);
    int rc = real_posix_spawn(pid, mapped, actions, attr, argv,
                              env != NULL ? (char *const *)env : envp);
    free_preload_env(env, envp, count);
    return rc;
}

EXPORT int posix_spawnp(pid_t *pid, const char *file,
                        const posix_spawn_file_actions_t *actions,
                        const posix_spawnattr_t *attr, char *const argv[],
                        char *const envp[]) {
    typedef int (*fn_t)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
    RESOLVE(posix_spawnp, fn_t)
    char buffer[PATH_MAX];
    char found[PATH_MAX];
    const char *mapped = map_path(file, buffer, sizeof(buffer));
    const char *target = find_program(mapped, found, sizeof(found));
    size_t count = 0;
    char **env = exec_environment(target, envp, &count);
    int rc = real_posix_spawnp(pid, target, actions, attr, argv,
                               env != NULL ? (char *const *)env : envp);
    free_preload_env(env, envp, count);
    return rc;
}

/* The file actions carry paths too, and they are applied in the child after the
 * preload decision, so they need their own translation. */
EXPORT int posix_spawn_file_actions_addopen(posix_spawn_file_actions_t *actions, int fd,
                                            const char *path, int oflag, mode_t mode) {
    typedef int (*fn_t)(posix_spawn_file_actions_t *, int, const char *, int, mode_t);
    RESOLVE(posix_spawn_file_actions_addopen, fn_t)
    char buffer[PATH_MAX];
    return real_posix_spawn_file_actions_addopen(
        actions, fd, map_path(path, buffer, sizeof(buffer)), oflag, mode);
}

EXPORT int posix_spawn_file_actions_addchdir_np(posix_spawn_file_actions_t *actions,
                                                const char *path) {
    typedef int (*fn_t)(posix_spawn_file_actions_t *, const char *);
    RESOLVE(posix_spawn_file_actions_addchdir_np, fn_t)
    char buffer[PATH_MAX];
    return real_posix_spawn_file_actions_addchdir_np(
        actions, map_path(path, buffer, sizeof(buffer)));
}

/* --------------------------------------------------------------- SIGSYS policy */

#if defined(__aarch64__)
#define SYSCALL_NR(uc) ((uc)->uc_mcontext.regs[8])
#define SYSCALL_ARG(uc, i) ((uc)->uc_mcontext.regs[i])
#define SET_SYSCALL_RESULT(uc, value) ((uc)->uc_mcontext.regs[0] = (unsigned long)(value))
#define ADVANCE_PC(uc) ((uc)->uc_mcontext.pc += 4)

#define NR_SET_ROBUST_LIST 99
#define NR_SETGID 144
#define NR_SETREGID 143
#define NR_SETREUID 145
#define NR_SETUID 146
#define NR_SETRESUID 147
#define NR_SETRESGID 149
#define NR_SETFSUID 151
#define NR_SETFSGID 152
#define NR_SETGROUPS 159
#define NR_GETGROUPS 158
#define NR_RSEQ 293
#define NR_CLONE3 435
#elif defined(__x86_64__)
#include <sys/ucontext.h>
#define SYSCALL_NR(uc) ((uc)->uc_mcontext.gregs[REG_RAX])
/* The handler only looks at the first two arguments, and the x86_64 argument
 * registers are not contiguous in gregs (rdi, rsi, rdx, r10, r8, r9). */
#define SYSCALL_ARG(uc, i) \
    ((i) == 0 ? (uc)->uc_mcontext.gregs[REG_RDI] : (uc)->uc_mcontext.gregs[REG_RSI])
#define SET_SYSCALL_RESULT(uc, value) \
    ((uc)->uc_mcontext.gregs[REG_RAX] = (greg_t)(value))
#define ADVANCE_PC(uc) ((uc)->uc_mcontext.gregs[REG_RIP] += 2)

#define NR_SET_ROBUST_LIST 273
#define NR_SETGID 106
#define NR_SETREGID 114
#define NR_SETREUID 113
#define NR_SETUID 105
#define NR_SETRESUID 117
#define NR_SETRESGID 119
#define NR_SETFSUID 122
#define NR_SETFSGID 123
#define NR_SETGROUPS 116
#define NR_GETGROUPS 115
#define NR_RSEQ 334
#define NR_CLONE3 435
#else
#error "liboc-pathmap supports aarch64 and x86_64 only"
#endif

static int is_fake_success(long nr) {
    switch (nr) {
        case NR_SET_ROBUST_LIST:
        case NR_SETGID:
        case NR_SETREGID:
        case NR_SETREUID:
        case NR_SETUID:
        case NR_SETRESUID:
        case NR_SETRESGID:
        case NR_SETFSUID:
        case NR_SETFSGID:
        case NR_SETGROUPS:
            return 1;
        default:
            return 0;
    }
}

/* Async-signal-safe long formatting and one write(2): the handler may not call
 * printf, malloc or anything that could take a lock. */
static size_t format_long(char *out, long value) {
    char digits[24];
    size_t digits_len = 0;
    unsigned long v = value < 0 ? (unsigned long)(-(value + 1)) + 1 : (unsigned long)value;
    do {
        digits[digits_len++] = (char)('0' + (int)(v % 10));
        v /= 10;
    } while (v != 0);
    size_t n = 0;
    if (value < 0) out[n++] = '-';
    while (digits_len > 0) out[n++] = digits[--digits_len];
    return n;
}

static void debug_blocked_syscall(long nr, int emulated) {
    if (!g_debug) return;
    char buffer[96];
    size_t n = 0;
    const char *head = "oc-pathmap: SIGSYS ";
    while (*head != '\0') buffer[n++] = *head++;
    n += format_long(buffer + n, nr);
    const char *tail = emulated ? " emulated\n" : " -> ENOSYS\n";
    while (*tail != '\0') buffer[n++] = *tail++;
    ssize_t written = write(STDERR_FILENO, buffer, n);
    (void)written;
}

static void sigsys_handler(int signal, siginfo_t *info, void *context) {
    (void)signal;
    (void)info;
    ucontext_t *uc = (ucontext_t *)context;
    long nr = (long)SYSCALL_NR(uc);
    if (nr == NR_GETGROUPS) {
        /* getgroups(0, NULL) asks for the count; otherwise write one empty group. */
        unsigned long size = (unsigned long)SYSCALL_ARG(uc, 0);
        unsigned long list = (unsigned long)SYSCALL_ARG(uc, 1);
        if (size > 0 && list != 0) {
            *(unsigned int *)list = 0;
        }
        SET_SYSCALL_RESULT(uc, 1);
        debug_blocked_syscall(nr, 1);
    } else if (is_fake_success(nr)) {
        SET_SYSCALL_RESULT(uc, 0);
        debug_blocked_syscall(nr, 1);
    } else {
        SET_SYSCALL_RESULT(uc, (unsigned long)-ENOSYS);
        debug_blocked_syscall(nr, 0);
    }
    ADVANCE_PC(uc);
}

static void install_sigsys_policy(void) {
    static int installed;
    if (installed) return;
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = sigsys_handler;
    action.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGSYS, &action, NULL) == 0) {
        installed = 1;
    }
}

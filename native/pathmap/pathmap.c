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
 * path.
 *
 * Build: see build.sh in this directory. The version script exports only the
 * hooked symbols, everything else (including the constructor) stays hidden.
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
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
#include <sys/types.h>
#include <ucontext.h>
#include <unistd.h>

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
    install_sigsys_policy();
}

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
EXPORT int openat2(int dirfd, const char *path, struct open_how *how, size_t size) {
    typedef int (*fn_t)(int, const char *, struct open_how *, size_t);
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

/* ---------------------------------------------------------------------- exec */

/*
 * The interpreter of a glibc ELF is absolute, so mapping the path only makes the
 * file run when its PT_INTERP already points into this app (all packages that the
 * installer relocated). The `oc-run` launcher is what starts a binary whose
 * interpreter still points into the Termux app.
 */
EXPORT int execve(const char *path, char *const argv[], char *const envp[]) {
    typedef int (*fn_t)(const char *, char *const[], char *const[]);
    RESOLVE(execve, fn_t)
    char buffer[PATH_MAX];
    return real_execve(map_path(path, buffer, sizeof(buffer)), argv, envp);
}

EXPORT int execv(const char *path, char *const argv[]) {
    typedef int (*fn_t)(const char *, char *const[]);
    RESOLVE(execv, fn_t)
    char buffer[PATH_MAX];
    return real_execv(map_path(path, buffer, sizeof(buffer)), argv);
}

EXPORT int execvp(const char *file, char *const argv[]) {
    typedef int (*fn_t)(const char *, char *const[]);
    RESOLVE(execvp, fn_t)
    char buffer[PATH_MAX];
    return real_execvp(map_path(file, buffer, sizeof(buffer)), argv);
}

EXPORT int execvpe(const char *file, char *const argv[], char *const envp[]) {
    typedef int (*fn_t)(const char *, char *const[], char *const[]);
    RESOLVE(execvpe, fn_t)
    char buffer[PATH_MAX];
    return real_execvpe(map_path(file, buffer, sizeof(buffer)), argv, envp);
}

EXPORT int posix_spawn(pid_t *pid, const char *path,
                       const posix_spawn_file_actions_t *actions,
                       const posix_spawnattr_t *attr, char *const argv[],
                       char *const envp[]) {
    typedef int (*fn_t)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
    RESOLVE(posix_spawn, fn_t)
    char buffer[PATH_MAX];
    return real_posix_spawn(pid, map_path(path, buffer, sizeof(buffer)), actions, attr,
                            argv, envp);
}

EXPORT int posix_spawnp(pid_t *pid, const char *file,
                        const posix_spawn_file_actions_t *actions,
                        const posix_spawnattr_t *attr, char *const argv[],
                        char *const envp[]) {
    typedef int (*fn_t)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
    RESOLVE(posix_spawnp, fn_t)
    char buffer[PATH_MAX];
    return real_posix_spawnp(pid, map_path(file, buffer, sizeof(buffer)), actions, attr,
                             argv, envp);
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
    } else if (is_fake_success(nr)) {
        SET_SYSCALL_RESULT(uc, 0);
    } else {
        SET_SYSCALL_RESULT(uc, (unsigned long)-ENOSYS);
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

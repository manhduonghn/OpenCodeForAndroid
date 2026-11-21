/*
 * liboc-pathmap.so - translate the Termux and FHS roots, install the SIGSYS
 * policy, and route the execution of glibc programs for OpenCode for Android.
 *
 * The packages of this app are relocated by rewriting the Termux prefix in their
 * files, but a program that the user downloads later still contains
 * /data/data/com.termux/files/usr, /data/data/com.termux/files/home or a
 * distribution path such as /usr/lib or /tmp. This preload library maps all of
 * them to the private prefix of the app at every file system entry point.
 *
 * The Termux roots are copied once in the constructor from OC_PREFIX /
 * OC_USER_HOME. The FHS table (/usr, /etc, /tmp, /var, /root, /dev/shm, /bin) is
 * checked only after the two Termux roots and can be disabled with OC_FHS=0.
 * /bin/sh is deliberately left alone so the scripts of Android keep the bionic
 * shell; system() and popen() use the glibc shell in $PREFIX/bin instead when
 * one is installed. A thread local guard blocks recursion, and a path that does
 * not match any root is passed to the real function with its original pointer.
 *
 * A glibc program whose PT_INTERP does not exist is rewritten into
 * `ld.so --preload liboc-pathmap.so --library-path ... PROGRAM`. A script is
 * exec'd through the interpreter named by its shebang, resolving Termux and FHS
 * spellings (and `#!/usr/bin/env`). OC_EXEC_ROUTE=0 turns that off. This lets a
 * binary downloaded from a release page run without `oc-run`.
 *
 * A program started through ld.so sees the loader as /proc/self/exe. When it is
 * running under ld.so, readlink/open/stat/realpath of /proc/self/exe report the
 * real target, read from OC_REAL_EXE (set by the exec routing) or from
 * /proc/self/cmdline; exec of /proc/self/exe re-runs that target, not the
 * loader. OC_REAL_EXE from the environment wins for the first
 * process, which is how `oc-run` and the app start their own tools.
 *
 * Inotify watches on a path that does not exist yet create the directory first,
 * because Vite, Metro and chokidar otherwise silently watch nothing.
 *
 * Unix socket names are mapped too: bind/connect/sendto/sendmsg translate an
 * absolute AF_UNIX path into a local sockaddr_un copy, and a mapped name that no
 * longer fits in sun_path is replaced by a deterministic FNV-1a hash under
 * $PREFIX/tmp. getsockname/getpeername report the original spelling, and close
 * removes a hashed listener name.
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
 * (only when the target is a glibc ELF). A routed child already loaded this
 * library through `ld.so --preload`, so its LD_PRELOAD stays clean, and a bionic
 * or static child never inherits either the preload or OC_REAL_EXE: loading a
 * glibc object into it aborts, and the stale executable would be reported as
 * /proc/self/exe. `/bin/sh` is left unmapped so scripts and system() keep the
 * bionic shell when no glibc shell is installed; with one, system()/popen()
 * start it through posix_spawn() and keep the path translation.
 *
 * Build: see build.sh in this directory. Every hooked symbol carries EXPORT and
 * the build uses -fvisibility=hidden, so the constructor and the helpers stay
 * hidden.
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
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <ucontext.h>
#include <unistd.h>
#include <utime.h>

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

/* Descriptor kept open on the semaphore directory for the short ELF alias. */
#define OC_SEMAPHORE_FD 9

/*
 * The relocated libandroid-posix-semaphore.so builds names below
 * /proc/self/fd/9/ (padded to 40 bytes). The descriptor is inherited across execve because this
 * helper never sets FD_CLOEXEC. Use a raw syscall here so the call does not go
 * through the open()/openat() hooks defined below while they are being set up.
 */
static void keep_semaphore_fd(void) {
    const char *dir = getenv("OC_SEMAPHORE_DIR");
    if (dir == NULL || dir[0] == '\0') return;
    int fd = (int)syscall(SYS_openat, AT_FDCWD, dir, O_RDONLY | O_DIRECTORY);
    if (fd < 0) {
        syscall(SYS_mkdirat, AT_FDCWD, dir, 0700);
        fd = (int)syscall(SYS_openat, AT_FDCWD, dir, O_RDONLY | O_DIRECTORY);
    }
    if (fd < 0) return;
    if (fd != OC_SEMAPHORE_FD) {
        if (syscall(SYS_dup3, fd, OC_SEMAPHORE_FD, 0) < 0) {
            syscall(SYS_close, fd);
            return;
        }
        syscall(SYS_close, fd);
    }
}

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

/*
 * OC_FHS=0 turns off the mapping of the FHS roots (/usr, /etc, /tmp, /var,
 * /root, /dev/shm, /bin) onto the private prefix. The Termux roots are always
 * mapped; this switch only exists so a program that a full FHS confuses can be
 * started without shipping a new APK.
 */
static int g_fhs = 1;

/* OC_EXEC_ROUTE=0 turns off the routing of a glibc program through the loader. */
static int g_exec_route = 1;

/*
 * OC_LOW_PORTS=0 turns off the +8000 translation of a bind() below 1024 and the
 * loopback retry in connect(). OC_SYSTEM=0 keeps the real system()/popen(),
 * which run the bionic shell of Android.
 */
static int g_low_ports = 1;
static int g_system = 1;

/* OC_REAL_EXE overrides /proc/self/exe, for a program started through ld.so. */
static char g_real_exe[PATH_MAX];

/* 0 = not detected yet, 1 = /proc/self/exe must be faked, -1 = leave it alone. */
static int g_exe_state;

/* OC_DEBUG=1 makes the SIGSYS handler report what it swallowed. */
static int g_debug;

static void install_sigsys_policy(void);
static void create_tree_raw(const char *path);
static void popen_lock_init(void);

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
        const char *fhs = getenv("OC_FHS");
        g_fhs = !(fhs != NULL && strcmp(fhs, "0") == 0);
        const char *route = getenv("OC_EXEC_ROUTE");
        g_exec_route = !(route != NULL && strcmp(route, "0") == 0);
        const char *ports = getenv("OC_LOW_PORTS");
        g_low_ports = !(ports != NULL && strcmp(ports, "0") == 0);
        const char *system_route = getenv("OC_SYSTEM");
        g_system = !(system_route != NULL && strcmp(system_route, "0") == 0);
    }
    copy_env(g_real_exe, sizeof(g_real_exe), "OC_REAL_EXE");
    {
        /* dli_fname is the path the loader opened, which is what a child needs in
         * LD_PRELOAD. The app and oc-gopatch pass an absolute path. */
        Dl_info info;
        if (dladdr((void *)pathmap_init, &info) != 0 && info.dli_fname != NULL) {
            size_t len = strlen(info.dli_fname);
            if (len > 0 && len < sizeof(g_self)) memcpy(g_self, info.dli_fname, len + 1);
        }
    }
    /* A fork while another thread is inside popen/pclose must not leave the
     * spinlock held in the child. The constructor registers the child handler
     * before any thread can use the table; popen() calls it again for a dlopen
     * of this library after a fork. */
    popen_lock_init();
    install_sigsys_policy();
    keep_semaphore_fd();
}

/*
 * A marker for tests and for `oc-run --debug`: a process that sees this symbol
 * has the preload, even when it inherited a cleaned environment.
 */
EXPORT int oc_pathmap_active(void) { return 1; }

/*
 * Translate [path] into [buffer] when it starts with one of the two Termux roots
 * or one of the FHS roots. Returns the original pointer when no translation
 * applies, so callers never copy. A relative path, a NULL path and a path that
 * does not fit are passed through unchanged: the kernel then answers as it would
 * for a path that long.
 */
static const char *map_fhs(const char *path, char *buffer, size_t size);

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
    if (target == NULL) {
        if (!g_fhs) return path;
        return map_fhs(path, buffer, size);
    }

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
 * The FHS roots that a program written for a normal Linux distribution uses.
 * The prefix of this app already has the same shape, so mapping them is a
 * prefix swap. They are checked only after the two Termux roots, so a program
 * that spells the Termux paths keeps the same answer as before.
 *
 * /bin/sh is deliberately NOT mapped: scripts and tools of Android have to keep
 * resolving to the bionic shell of the platform. system()/popen() bypass this
 * table and start the glibc shell in $PREFIX/bin directly (see glibc_shell).
 */
struct fhs_rule {
    const char *from;
    size_t from_len;
    int to_home;
    const char *suffix;
};

static const struct fhs_rule g_fhs_rules[] = {
    {"/dev/shm", 8, 0, "/tmp/shm"},
    {"/usr", 4, 0, ""},
    {"/etc", 4, 0, "/etc"},
    {"/tmp", 4, 0, "/tmp"},
    {"/var", 4, 0, "/var"},
    {"/root", 5, 1, ""},
    {"/bin", 4, 0, "/bin"},
};

static const char *map_fhs(const char *path, char *buffer, size_t size) {
    if (!g_fhs) return path;
    for (size_t i = 0; i < sizeof(g_fhs_rules) / sizeof(g_fhs_rules[0]); i++) {
        const struct fhs_rule *rule = &g_fhs_rules[i];
        const char *root = rule->to_home ? g_home : g_prefix;
        size_t root_len = rule->to_home ? g_home_len : g_prefix_len;
        if (root_len == 0) continue;
        if (strncmp(path, rule->from, rule->from_len) != 0) continue;
        if (path[rule->from_len] != '\0' && path[rule->from_len] != '/') continue;
        // Keep the bionic shell of Android for callers that use /bin/sh
        // directly (and for system() when no glibc shell is installed).
        if (strcmp(rule->from, "/bin") == 0 && strcmp(path, "/bin/sh") == 0) continue;

        size_t suffix_len = strlen(rule->suffix);
        size_t rest_len = strlen(path + rule->from_len);
        if (root_len + suffix_len + rest_len + 1 > size) continue;

        g_busy = 1;
        memcpy(buffer, root, root_len);
        if (suffix_len > 0) memcpy(buffer + root_len, rule->suffix, suffix_len);
        memcpy(buffer + root_len + suffix_len, path + rule->from_len, rest_len + 1);
        g_busy = 0;
        return buffer;
    }
    return path;
}

static void unmap_buffer(char *buffer, size_t *n) {
    if (!g_enabled || buffer == NULL || *n == 0) return;
    const char *replacement = NULL;
    size_t old_len = 0;
    size_t new_len = 0;
    if (g_prefix_len > 0 && *n >= g_prefix_len &&
        memcmp(buffer, g_prefix, g_prefix_len) == 0 &&
        (*n == g_prefix_len || buffer[g_prefix_len] == '/')) {
        old_len = g_prefix_len;
        replacement = TERMUX_PREFIX;
        new_len = TERMUX_PREFIX_LEN;
    } else if (g_home_len > 0 && *n >= g_home_len &&
               memcmp(buffer, g_home, g_home_len) == 0 &&
               (*n == g_home_len || buffer[g_home_len] == '/')) {
        old_len = g_home_len;
        replacement = TERMUX_HOME;
        new_len = TERMUX_HOME_LEN;
    }
    if (replacement == NULL || new_len > old_len || *n - old_len > SIZE_MAX - new_len) return;
    memmove(buffer + new_len, buffer + old_len, *n - old_len);
    memcpy(buffer, replacement, new_len);
    *n = *n - old_len + new_len;
}

/* ------------------------------------------------------------- Unix sockets */

/*
 * A Unix socket path lives inside struct sockaddr_un and is limited to 108
 * bytes, so it needs its own translation: map_path() is applied to the name,
 * and a mapped name that no longer fits is replaced by a deterministic hash
 * under $PREFIX/tmp. A server and a client that spell the same original path
 * compute the same hash and meet at the same socket.
 *
 * The caller's sockaddr is never modified: the translated address is a local
 * copy. Abstract names (sun_path[0] == '\0') and relative names are left alone.
 * The table remembers the original spelling for getsockname/getpeername, and
 * the hashed path of a bound socket so close() can remove it. An inode check
 * keeps an old listener from removing a path that a newer listener has already
 * bound under the same hash.
 */

#define UNIX_SOCKET_PATH_MAX (sizeof(((struct sockaddr_un *)0)->sun_path))
#define UNIX_SOCKET_SLOTS 64

struct unix_target {
    struct sockaddr_un addr;
    socklen_t len;
    char original[UNIX_SOCKET_PATH_MAX];
    size_t original_len;
    char physical[UNIX_SOCKET_PATH_MAX];
    size_t physical_len;
    int hashed;
};

struct socket_record {
    int fd_plus_one; /* 0 = free */
    int owner_pid;
    int owns_hash;
    int has_id;
    dev_t hash_dev;
    ino_t hash_ino;
    unsigned char local_len;
    unsigned char peer_len;
    char local[UNIX_SOCKET_PATH_MAX];
    char peer[UNIX_SOCKET_PATH_MAX];
    char hashed[UNIX_SOCKET_PATH_MAX];
};

static struct socket_record g_socket_records[UNIX_SOCKET_SLOTS];
static int g_socket_record_count;
static int g_socket_lock;

static void socket_lock(void) {
    while (__atomic_exchange_n(&g_socket_lock, 1, __ATOMIC_ACQUIRE)) {
        while (__atomic_load_n(&g_socket_lock, __ATOMIC_RELAXED)) {
        }
    }
}

static void socket_unlock(void) { __atomic_store_n(&g_socket_lock, 0, __ATOMIC_RELEASE); }

static int socket_stat_raw(const char *path, struct stat *st) {
    return syscall(SYS_newfstatat, AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW) == 0;
}

static uint64_t socket_hash(const char *text) {
    uint64_t hash = 14695981039346656037ULL;
    for (const unsigned char *p = (const unsigned char *)text; *p != '\0'; p++) {
        hash ^= *p;
        hash *= 1099511628211ULL;
    }
    return hash;
}

static void socket_hash_hex(uint64_t value, char *out) {
    static const char digits[] = "0123456789abcdef";
    for (int i = 15; i >= 0; i--) {
        out[i] = digits[value & 0xf];
        value >>= 4;
    }
}

/* The deterministic short name for a mapped path that is too long for sun_path. */
static int socket_hashed_path(const char *mapped, char *out, size_t size) {
    if (mapped == NULL || g_prefix_len == 0) return 0;
    static const char suffix[] = "/tmp/.oc_sock_";
    const size_t suffix_len = sizeof(suffix) - 1;
    size_t total = g_prefix_len + suffix_len + 16;
    if (total >= size) return 0;
    memcpy(out, g_prefix, g_prefix_len);
    memcpy(out + g_prefix_len, suffix, suffix_len);
    socket_hash_hex(socket_hash(mapped), out + g_prefix_len + suffix_len);
    out[total] = '\0';
    return 1;
}

/* $PREFIX/tmp is the directory of the hashed names; create it for a listener. */
static void socket_ensure_dir(void) {
    if (g_prefix_len == 0 || g_prefix_len + 5 > PATH_MAX) return;
    char dir[PATH_MAX];
    memcpy(dir, g_prefix, g_prefix_len);
    memcpy(dir + g_prefix_len, "/tmp", 5);
    syscall(SYS_mkdirat, AT_FDCWD, dir, 0700);
}

static int translate_unix(const struct sockaddr *addr, socklen_t addrlen,
                          struct unix_target *target) {
    if (addr == NULL || target == NULL || addrlen < (socklen_t)sizeof(sa_family_t)) return 0;
    if (addr->sa_family != AF_UNIX) return 0;
    const size_t base = offsetof(struct sockaddr_un, sun_path);
    if (addrlen <= base) return 0;
    const struct sockaddr_un *sun = (const struct sockaddr_un *)addr;
    size_t available = (size_t)addrlen - base;
    if (available > UNIX_SOCKET_PATH_MAX) available = UNIX_SOCKET_PATH_MAX;
    if (available == 0 || sun->sun_path[0] == '\0' || sun->sun_path[0] != '/') return 0;
    size_t length = strnlen(sun->sun_path, available);
    if (length == 0 || length == available) return 0;

    memcpy(target->original, sun->sun_path, length + 1);
    target->original_len = length;

    char mapped[PATH_MAX];
    const char *physical = map_path(target->original, mapped, sizeof(mapped));
    size_t physical_len = strlen(physical);
    if (physical_len < UNIX_SOCKET_PATH_MAX) {
        memcpy(target->physical, physical, physical_len + 1);
        target->hashed = 0;
    } else {
        if (!socket_hashed_path(physical, target->physical, sizeof(target->physical))) return 0;
        physical_len = strlen(target->physical);
        target->hashed = 1;
    }
    target->physical_len = physical_len;

    memset(&target->addr, 0, sizeof(target->addr));
    target->addr.sun_family = AF_UNIX;
    memcpy(target->addr.sun_path, target->physical, physical_len + 1);
    target->len = (socklen_t)(base + physical_len + 1);
    return 1;
}

static struct socket_record *socket_record_get(int fd, int create) {
    if (fd < 0) return NULL;
    for (size_t i = 0; i < UNIX_SOCKET_SLOTS; i++) {
        struct socket_record *record = &g_socket_records[i];
        if (record->fd_plus_one == fd + 1) return record;
    }
    if (!create) return NULL;
    for (size_t i = 0; i < UNIX_SOCKET_SLOTS; i++) {
        struct socket_record *record = &g_socket_records[i];
        if (record->fd_plus_one != 0) continue;
        memset(record, 0, sizeof(*record));
        record->fd_plus_one = fd + 1;
        record->owner_pid = (int)syscall(SYS_getpid);
        __atomic_add_fetch(&g_socket_record_count, 1, __ATOMIC_RELAXED);
        return record;
    }
    return NULL;
}

static void socket_record_release(struct socket_record *record) {
    record->fd_plus_one = 0;
    __atomic_sub_fetch(&g_socket_record_count, 1, __ATOMIC_RELAXED);
}

static void socket_record_maybe_release(struct socket_record *record) {
    if (record->local_len == 0 && record->peer_len == 0 && !record->owns_hash) {
        socket_record_release(record);
    }
}

static void socket_record_local(int fd, const struct unix_target *target,
                                const struct stat *st) {
    socket_lock();
    struct socket_record *record = socket_record_get(fd, 1);
    if (record != NULL) {
        record->owner_pid = (int)syscall(SYS_getpid);
        record->owns_hash = target->hashed;
        record->local_len = (unsigned char)target->original_len;
        memcpy(record->local, target->original, target->original_len + 1);
        record->peer_len = 0;
        record->peer[0] = '\0';
        record->has_id = 0;
        if (target->hashed) {
            size_t length = strlen(target->physical);
            memcpy(record->hashed, target->physical, length + 1);
            if (st != NULL && S_ISSOCK(st->st_mode)) {
                record->has_id = 1;
                record->hash_dev = st->st_dev;
                record->hash_ino = st->st_ino;
            }
        } else {
            record->hashed[0] = '\0';
        }
    }
    socket_unlock();
}

static void socket_record_peer(int fd, const struct unix_target *target) {
    socket_lock();
    struct socket_record *record = socket_record_get(fd, 1);
    if (record != NULL) {
        record->peer_len = (unsigned char)target->original_len;
        memcpy(record->peer, target->original, target->original_len + 1);
    }
    socket_unlock();
}

static void socket_record_clear_peer(int fd) {
    if (__atomic_load_n(&g_socket_record_count, __ATOMIC_RELAXED) == 0) return;
    socket_lock();
    struct socket_record *record = socket_record_get(fd, 0);
    if (record != NULL) {
        record->peer_len = 0;
        record->peer[0] = '\0';
        socket_record_maybe_release(record);
    }
    socket_unlock();
}

static int socket_record_lookup(int fd, int peer, char *out, size_t *out_len) {
    if (__atomic_load_n(&g_socket_record_count, __ATOMIC_RELAXED) == 0) return 0;
    int found = 0;
    socket_lock();
    struct socket_record *record = socket_record_get(fd, 0);
    if (record != NULL) {
        unsigned char length = peer ? record->peer_len : record->local_len;
        if (length > 0) {
            memcpy(out, peer ? record->peer : record->local, (size_t)length + 1);
            *out_len = length;
            found = 1;
        }
    }
    socket_unlock();
    return found;
}

static void socket_record_copy_local(int source, int destination) {
    if (__atomic_load_n(&g_socket_record_count, __ATOMIC_RELAXED) == 0) return;
    socket_lock();
    struct socket_record *from = socket_record_get(source, 0);
    if (from != NULL) {
        struct socket_record *to = socket_record_get(destination, 1);
        if (to != NULL) {
            to->owner_pid = (int)syscall(SYS_getpid);
            to->owns_hash = 0;
            to->has_id = 0;
            to->hashed[0] = '\0';
            to->local_len = from->local_len;
            if (from->local_len > 0) {
                memcpy(to->local, from->local, (size_t)from->local_len + 1);
            } else {
                to->local[0] = '\0';
            }
            to->peer_len = 0;
            to->peer[0] = '\0';
        }
    }
    socket_unlock();
}

static void socket_unlink_if_owned(const char *path, int has_id, dev_t device, ino_t inode) {
    struct stat st;
    if (!socket_stat_raw(path, &st) || !S_ISSOCK(st.st_mode)) return;
    if (has_id && (st.st_dev != device || st.st_ino != inode)) return;
    syscall(SYS_unlinkat, AT_FDCWD, path, 0);
}

/* Another live record shares the same hashed name: a dup() kept the socket
 * alive, so closing one descriptor must not remove the name. */
static int socket_record_hashed_shared_locked(const struct socket_record *self) {
    for (size_t i = 0; i < UNIX_SOCKET_SLOTS; i++) {
        const struct socket_record *record = &g_socket_records[i];
        if (record == self || record->fd_plus_one == 0) continue;
        if (record->owns_hash && strcmp(record->hashed, self->hashed) == 0) return 1;
    }
    return 0;
}

static void socket_record_close(int fd, int closed) {
    if (__atomic_load_n(&g_socket_record_count, __ATOMIC_RELAXED) == 0) return;
    char path[UNIX_SOCKET_PATH_MAX];
    path[0] = '\0';
    int unlink_path = 0;
    int has_id = 0;
    dev_t device = 0;
    ino_t inode = 0;
    socket_lock();
    struct socket_record *record = socket_record_get(fd, 0);
    if (record != NULL) {
        if (closed && record->owns_hash && record->hashed[0] != '\0' &&
            record->owner_pid == (int)syscall(SYS_getpid) &&
            !socket_record_hashed_shared_locked(record)) {
            size_t length = strlen(record->hashed);
            memcpy(path, record->hashed, length + 1);
            unlink_path = 1;
            has_id = record->has_id;
            device = record->hash_dev;
            inode = record->hash_ino;
        }
        socket_record_release(record);
    }
    socket_unlock();
    if (unlink_path) socket_unlink_if_owned(path, has_id, device, inode);
}

/* dup()/dup2()/dup3() keep the same open file description, so the original
 * name and peer must follow the new descriptor. */
static void socket_record_dup(int source, int destination) {
    if (__atomic_load_n(&g_socket_record_count, __ATOMIC_RELAXED) == 0) return;
    socket_lock();
    struct socket_record *from = socket_record_get(source, 0);
    if (from != NULL) {
        struct socket_record *to = socket_record_get(destination, 0);
        if (to == NULL) to = socket_record_get(destination, 1);
        if (to != NULL) {
            to->owner_pid = (int)syscall(SYS_getpid);
            to->owns_hash = from->owns_hash;
            to->has_id = from->has_id;
            to->hash_dev = from->hash_dev;
            to->hash_ino = from->hash_ino;
            to->local_len = from->local_len;
            memcpy(to->local, from->local, (size_t)from->local_len + 1);
            to->peer_len = from->peer_len;
            memcpy(to->peer, from->peer, (size_t)from->peer_len + 1);
            memcpy(to->hashed, from->hashed, sizeof(to->hashed));
        }
    }
    socket_unlock();
}

/* close_range() drops every descriptor in the window at once; collect the
 * tracked ones under the lock and then close them one by one. */
static void socket_record_close_range(unsigned int first, unsigned int last) {
    if (__atomic_load_n(&g_socket_record_count, __ATOMIC_RELAXED) == 0) return;
    int fds[UNIX_SOCKET_SLOTS];
    size_t count = 0;
    socket_lock();
    for (size_t i = 0; i < UNIX_SOCKET_SLOTS; i++) {
        const struct socket_record *record = &g_socket_records[i];
        if (record->fd_plus_one == 0) continue;
        unsigned int fd = (unsigned int)(record->fd_plus_one - 1);
        if (fd >= first && fd <= last) fds[count++] = (int)fd;
    }
    socket_unlock();
    for (size_t i = 0; i < count; i++) socket_record_close(fds[i], 1);
}

/* Remove the short name of a long socket path; used by unlink/unlinkat/remove. */
static int socket_unlink_hashed(const char *mapped) {
    if (mapped == NULL || mapped[0] != '/' || g_prefix_len == 0) return 0;
    if (strlen(mapped) < UNIX_SOCKET_PATH_MAX) return 0;
    char path[UNIX_SOCKET_PATH_MAX];
    if (!socket_hashed_path(mapped, path, sizeof(path))) return 0;
    struct stat st;
    if (!socket_stat_raw(path, &st) || !S_ISSOCK(st.st_mode)) return 0;
    return syscall(SYS_unlinkat, AT_FDCWD, path, 0) == 0;
}

static void restore_socket_name(int fd, struct sockaddr *addr, socklen_t *addrlen,
                                socklen_t capacity, int peer) {
    if (addr == NULL || addrlen == NULL) return;
    if (capacity < (socklen_t)sizeof(sa_family_t)) return;
    size_t limit = (size_t)*addrlen;
    if (limit > (size_t)capacity) limit = (size_t)capacity;
    const size_t base = offsetof(struct sockaddr_un, sun_path);
    if (limit <= base) return;
    if (addr->sa_family != AF_UNIX) return;
    struct sockaddr_un *sun = (struct sockaddr_un *)addr;
    size_t available = limit - base;
    if (available > UNIX_SOCKET_PATH_MAX) available = UNIX_SOCKET_PATH_MAX;
    if (available == 0 || sun->sun_path[0] == '\0') return;
    size_t length = strnlen(sun->sun_path, available);
    if (length == 0 || length == available) return;

    char original[UNIX_SOCKET_PATH_MAX];
    size_t original_len = 0;
    if (socket_record_lookup(fd, peer, original, &original_len)) {
        size_t needed = base + original_len + 1;
        if (needed <= (size_t)capacity && original_len < UNIX_SOCKET_PATH_MAX) {
            memcpy(sun->sun_path, original, original_len + 1);
            *addrlen = (socklen_t)needed;
            return;
        }
    }

    char temp[PATH_MAX];
    memcpy(temp, sun->sun_path, length + 1);
    size_t temp_len = length;
    unmap_buffer(temp, &temp_len);
    temp[temp_len] = '\0';
    if (temp_len < UNIX_SOCKET_PATH_MAX && base + temp_len + 1 <= (size_t)capacity) {
        memcpy(sun->sun_path, temp, temp_len + 1);
        *addrlen = (socklen_t)(base + temp_len + 1);
    }
}

static void accept_done(int listener, int newfd, struct sockaddr *addr, socklen_t *addrlen,
                        socklen_t capacity) {
    if (newfd < 0) return;
    socket_record_copy_local(listener, newfd);
    if (addr != NULL && addrlen != NULL) {
        restore_socket_name(newfd, addr, addrlen, capacity, 1);
    }
}

/* ------------------------------------------------ /proc/self/exe virtualization */

/*
 * A program started through ld.so reports the loader as /proc/self/exe. Every
 * runtime asks for the path of itself (Node's process.execPath, Python's
 * sys.executable, Rust's current_exe, Go's os.Executable), and getting
 * ld-linux back makes it spawn the wrong program. The real target is recovered
 * from OC_REAL_EXE, or from /proc/self/cmdline when the loader was invoked by
 * hand. The override is only active when the process really runs under a glibc
 * loader; a directly started binary already has a correct /proc/self/exe.
 */

static int open_raw(const char *path, int flags) {
    return (int)syscall(SYS_openat, AT_FDCWD, path, flags | O_CLOEXEC, 0);
}

static void close_raw(int fd) { syscall(SYS_close, fd); }

static int stat_raw(const char *path, struct stat *st) {
    return syscall(SYS_newfstatat, AT_FDCWD, path, st, 0) == 0;
}

static int regular_file_raw(const char *path) {
    struct stat st;
    return stat_raw(path, &st) && S_ISREG(st.st_mode);
}

static ssize_t read_cmdline_raw(char *buf, size_t size) {
    int fd = open_raw("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = syscall(SYS_read, fd, buf, size - 1);
    close_raw(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    return n;
}

/*
 * The loader is normally `ld-linux-<arch>.so.1` or the arch-neutral `ld.so`,
 * but /proc/self/exe resolves symlinks: the path can be the real `ld-2.xx.so`
 * behind both names. Treat all three spellings as the loader.
 */
static int name_is_loader(const char *base) {
    if (base == NULL) return 0;
    if (strstr(base, "ld-linux") != NULL) return 1;
    if (strcmp(base, "ld.so") == 0) return 1;
    return strncmp(base, "ld-", 3) == 0 && strstr(base, ".so") != NULL;
}

static int loader_argv0(const char *line) {
    const char *base = strrchr(line, '/');
    base = base != NULL ? base + 1 : line;
    return name_is_loader(base);
}

static int option_takes_value(const char *arg) {
    static const char *const names[] = {
        "--preload",         "--library-path",         "--argv0",
        "--audit",           "--inhibit-rpath",        "--glibc-hwcaps-prepend",
        "--glibc-hwcaps-mask",
    };
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        size_t len = strlen(names[i]);
        if (strncmp(arg, names[i], len) == 0 && (arg[len] == '\0' || arg[len] == '=')) {
            return arg[len] != '=';
        }
    }
    return 0;
}

/* The kernel's executable for this process, without going through our hook. */
static int executable_is_loader(void) {
    char link[PATH_MAX];
    ssize_t n = syscall(SYS_readlinkat, AT_FDCWD, "/proc/self/exe", link, sizeof(link) - 1);
    if (n <= 0) return 0;
    link[n] = '\0';
    return loader_argv0(link);
}

/*
 * Pick the target of `loader OPTION... TARGET ARGS`. The string area of
 * /proc/self/cmdline normally still starts with the loader, but a loader is
 * allowed to rewrite it in place, so the first argument is only skipped when it
 * really names the loader; otherwise it is the program itself.
 */
static int cmdline_target(char *line, size_t length, char *out, size_t size) {
    const char *end = line + length;
    int skip_next = 0;
    int first = 1;
    for (char *arg = line; arg < end; arg += strlen(arg) + 1) {
        if (first) {
            first = 0;
            if (loader_argv0(arg)) continue;
        }
        if (skip_next) {
            skip_next = 0;
            continue;
        }
        if (arg[0] == '-') {
            if (option_takes_value(arg)) skip_next = 1;
            continue;
        }
        size_t len = strlen(arg);
        if (len + 1 > size) return 0;
        memcpy(out, arg, len + 1);
        return regular_file_raw(out);
    }
    return 0;
}

static void detect_real_exe(void) {
    if (g_exe_state != 0) return;
    g_exe_state = -1;

    if (!executable_is_loader()) return;

    char line[4096];
    ssize_t length = read_cmdline_raw(line, sizeof(line));
    if (length < 0) return;

    if (g_real_exe[0] == '/' && regular_file_raw(g_real_exe)) {
        g_exe_state = 1;
        return;
    }
    char target[PATH_MAX];
    if (!cmdline_target(line, (size_t)length, target, sizeof(target))) return;
    memcpy(g_real_exe, target, strlen(target) + 1);
    g_exe_state = 1;
}

/* True for /proc/self/exe, /proc/thread-self/exe and /proc/<our-pid>/exe. */
static int is_self_exe_path(const char *path) {
    if (path == NULL) return 0;
    if (strcmp(path, "/proc/self/exe") == 0) return 1;
    if (strcmp(path, "/proc/thread-self/exe") == 0) return 1;
    if (strncmp(path, "/proc/", 6) != 0) return 0;
    const char *digits = path + 6;
    if (*digits < '0' || *digits > '9') return 0;
    long pid = 0;
    while (*digits >= '0' && *digits <= '9') {
        pid = pid * 10 + (*digits - '0');
        if (pid > 0x7fffffffL) return 0;
        digits++;
    }
    if (strcmp(digits, "/exe") != 0) return 0;
    return pid == (long)getpid();
}

/* The replacement for a path that names the current executable, or NULL. */
static const char *exe_override(const char *path) {
    if (!is_self_exe_path(path)) return NULL;
    detect_real_exe();
    return g_exe_state == 1 ? g_real_exe : NULL;
}

/* Write the fake executable into a readlink buffer, with readlink semantics. */
static ssize_t copy_exe_link(const char *target, char *buf, size_t size) {
    if (size == 0) {
        errno = EINVAL;
        return -1;
    }
    size_t len = strlen(target);
    if (len > size) len = size;
    memcpy(buf, target, len);
    return (ssize_t)len;
}

/* ---------------------------------------------------------------- exec routing */

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

/* The parts of the ELF header that the exec routing needs. */
struct elf_info {
    uint16_t machine;
    int has_interp;
    char interp[256];
    char runpath[512]; /* DT_RUNPATH, or DT_RPATH when there is no DT_RUNPATH */
};

static int is_glibc_interp(const char *interp) {
    return strstr(interp, "ld-linux") != NULL || strstr(interp, "ld.so") != NULL;
}

/*
 * Read the header, the program headers and DT_RUNPATH of a 64-bit
 * little-endian ELF. The dynamic string table is not read in full: only the
 * bytes of the runpath string are fetched, through a virtual-address to file
 * offset conversion from the PT_LOAD segment that contains it.
 */
static int probe_elf(int fd, struct elf_info *info) {
    memset(info, 0, sizeof(*info));
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
    memcpy(&info->machine, header + 18, sizeof(info->machine));
    if (phentsize != 56 || phnum == 0 || phnum > 128) return 0;

    struct load_segment {
        uint64_t vaddr;
        uint64_t offset;
        uint64_t filesz;
    } loads[8];
    int load_count = 0;
    uint64_t dyn_offset = 0;
    uint64_t dyn_size = 0;

    for (uint16_t i = 0; i < phnum; i++) {
        unsigned char ph[56];
        off_t at = (off_t)(phoff + (uint64_t)i * 56);
        if (read_exact(fd, ph, sizeof(ph), at) < (int)sizeof(ph)) return 0;
        uint32_t type;
        uint64_t offset;
        uint64_t vaddr;
        uint64_t filesz;
        memcpy(&type, ph + 0, sizeof(type));
        memcpy(&offset, ph + 8, sizeof(offset));
        memcpy(&vaddr, ph + 16, sizeof(vaddr));
        memcpy(&filesz, ph + 32, sizeof(filesz));
        if (type == PT_LOAD) {
            if (load_count < 8) loads[load_count++] = (struct load_segment){vaddr, offset, filesz};
        } else if (type == PT_INTERP) {
            size_t len = filesz < sizeof(info->interp) - 1 ? (size_t)filesz
                                                          : sizeof(info->interp) - 1;
            if (len > 0 && read_exact(fd, info->interp, len, (off_t)offset) == (int)len) {
                info->interp[len] = '\0';
                info->has_interp = 1;
            }
        } else if (type == PT_DYNAMIC) {
            dyn_offset = offset;
            dyn_size = filesz;
        }
    }

    if (dyn_size >= 16) {
        uint64_t strtab = 0;
        uint64_t rpath = 0;
        uint64_t runpath = 0;
        int have_rpath = 0;
        int have_runpath = 0;
        size_t entries = (size_t)(dyn_size / 16);
        if (entries > 512) entries = 512;
        for (size_t i = 0; i < entries; i++) {
            uint64_t entry[2];
            if (read_exact(fd, entry, sizeof(entry), (off_t)(dyn_offset + i * 16)) !=
                (int)sizeof(entry)) {
                break;
            }
            long tag = (long)entry[0];
            if (tag == DT_NULL) break;
            if (tag == DT_STRTAB) {
                strtab = entry[1];
            } else if (tag == DT_RUNPATH) {
                runpath = entry[1];
                have_runpath = 1;
            } else if (tag == DT_RPATH) {
                rpath = entry[1];
                have_rpath = 1;
            }
        }
        uint64_t chosen = have_runpath ? runpath : (have_rpath ? rpath : 0);
        if (strtab != 0 && chosen != 0) {
            for (int i = 0; i < load_count; i++) {
                if (strtab < loads[i].vaddr || strtab >= loads[i].vaddr + loads[i].filesz) {
                    continue;
                }
                off_t position = (off_t)(loads[i].offset + (strtab - loads[i].vaddr) + chosen);
                int n = read_exact(fd, info->runpath, sizeof(info->runpath) - 1, position);
                if (n > 0) {
                    info->runpath[n] = '\0';
                } else {
                    info->runpath[0] = '\0';
                }
                break;
            }
        }
    }
    return 1;
}

/* True when [fd] is a 64-bit little-endian ELF whose PT_INTERP is the glibc loader. */
static int elf_is_glibc(int fd) {
    struct elf_info info;
    if (!probe_elf(fd, &info)) return 0;
    return info.has_interp && is_glibc_interp(info.interp);
}

/* A script is judged by its interpreter: `#!/bin/sh` is bionic, `#!/usr/bin/env
 * python3` is glibc when python3 is a glibc program in the private prefix. */
static int file_is_glibc_depth(const char *path, int depth) {
    if (path == NULL || path[0] == '\0' || depth > 4) return 0;
    int fd = open_raw(path, O_RDONLY);
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
                glibc = file_is_glibc_depth(map_path(interp, mapped, sizeof(mapped)), depth + 1);
            }
        }
    }
    close(fd);
    return glibc;
}

static int file_is_glibc(const char *path) { return file_is_glibc_depth(path, 0); }

/*
 * The dynamic loader is a glibc ELF without PT_INTERP, so file_is_glibc() cannot
 * recognise it. It must still count as glibc: a program that starts `ld.so`
 * explicitly with LD_PRELOAD expects that preload to survive.
 */
static int is_loader_program(const char *path) {
    if (path == NULL) return 0;
    const char *base = strrchr(path, '/');
    base = base != NULL ? base + 1 : path;
    return name_is_loader(base);
}

/* Resolve a bare command name through PATH for the preload decision only. */
static int executable_file(const char *path) {
    struct stat st;
    return access(path, X_OK) == 0 && stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

/*
 * preload_env() runs in the child of fork() when a shell starts a program: the
 * shell forks, then execve() builds the environment of the child. Another thread
 * of that process can hold the malloc lock at the moment of fork, so an
 * allocation in the child can hang forever. The environment is therefore built
 * in a per-thread arena of static TLS with a bump allocator and memcpy only. A
 * huge environment restarts in an anonymous mmap region (also lock-free); the
 * common case of a few dozen variables never leaves the arena.
 *
 * The initial-exec model is correct here: this library is preloaded at startup
 * (ld.so --preload or LD_PRELOAD), never dlopen'd into a running process, so
 * glibc reserves the block for every thread when it creates it. A thread that
 * exists before a fork has already touched its arena and the child inherits it.
 */
#define ENV_ARENA_SIZE 16384
#define ENV_MMAP_INITIAL (64u * 1024u)
#define ENV_MMAP_MAX (16u * 1024u * 1024u)
#define ENV_REGION_MAGIC UINT64_C(0x4f43454e564d4150)

static __thread union {
    max_align_t align;
    char bytes[ENV_ARENA_SIZE];
} g_env_arena __attribute__((tls_model("initial-exec")));

/* The size is stored in the mapping itself so free_preload_env() needs no
 * side table (and no global pointer shared by threads). */
union env_region_header {
    max_align_t align;
    struct {
        uint64_t magic;
        size_t size;
    } fields;
};

struct env_pool {
    char *base;
    size_t size;
    size_t used;
    void *region; /* NULL for the static TLS arena */
    size_t region_size;
};

static void env_pool_arena(struct env_pool *pool) {
    pool->base = g_env_arena.bytes;
    pool->size = sizeof(g_env_arena.bytes);
    pool->used = 0;
    pool->region = NULL;
    pool->region_size = 0;
}

static int env_pool_mmap(struct env_pool *pool, size_t size) {
    pool->base = NULL;
    pool->size = 0;
    pool->used = 0;
    pool->region = NULL;
    pool->region_size = 0;
    if (size == 0 || size > SIZE_MAX - sizeof(union env_region_header)) return 0;
    size_t total = size + sizeof(union env_region_header);
    void *region = mmap(NULL, total, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (region == MAP_FAILED) return 0;
    union env_region_header *header = region;
    header->fields.magic = ENV_REGION_MAGIC;
    header->fields.size = total;
    pool->region = region;
    pool->region_size = total;
    pool->base = (char *)region + sizeof(*header);
    pool->size = size;
    return 1;
}

static void env_pool_release(struct env_pool *pool) {
    if (pool->region != NULL) munmap(pool->region, pool->region_size);
    pool->base = NULL;
    pool->size = 0;
    pool->used = 0;
    pool->region = NULL;
    pool->region_size = 0;
}

static void *env_pool_alloc(struct env_pool *pool, size_t size) {
    if (pool->base == NULL) return NULL;
    const size_t align = _Alignof(max_align_t);
    if (size > SIZE_MAX - (align - 1)) return NULL;
    size = (size + align - 1) & ~(align - 1);
    if (size > pool->size - pool->used) return NULL;
    void *out = pool->base + pool->used;
    pool->used += size;
    return out;
}

static char *join_env(struct env_pool *pool, const char *name, const char *value) {
    size_t name_len = strlen(name);
    size_t value_len = strlen(value);
    char *out = env_pool_alloc(pool, name_len + value_len + 2);
    if (out == NULL) return NULL;
    memcpy(out, name, name_len);
    out[name_len] = '=';
    memcpy(out + name_len + 1, value, value_len + 1);
    return out;
}

static char *merge_preload(struct env_pool *pool, const char *value) {
    size_t self_len = strlen(g_self);
    size_t value_len = strlen(value);
    size_t len = 11 + self_len + (value_len > 0 ? 1 + value_len : 0) + 1;
    char *out = env_pool_alloc(pool, len);
    if (out == NULL) return NULL;
    memcpy(out, "LD_PRELOAD=", 11);
    char *cursor = out + 11;
    memcpy(cursor, g_self, self_len);
    cursor += self_len;
    if (value_len > 0) {
        *cursor++ = ' ';
        memcpy(cursor, value, value_len);
        cursor += value_len;
    }
    *cursor = '\0';
    return out;
}

/* LD_PRELOAD separates entries with spaces or colons; compare whole entries. */
static int token_is_self(const char *begin, const char *end) {
    size_t length = (size_t)(end - begin);
    return length == strlen(g_self) && memcmp(begin, g_self, length) == 0;
}

static int preload_mentions_self(const char *value) {
    const char *cursor = value;
    while (*cursor != '\0') {
        while (*cursor == ' ' || *cursor == '\t' || *cursor == ':') cursor++;
        const char *end = cursor;
        while (*end != '\0' && *end != ' ' && *end != '\t' && *end != ':') end++;
        if (end == cursor) break;
        if (token_is_self(cursor, end)) return 1;
        cursor = end;
    }
    return 0;
}

/* True when at least one entry other than this library survives the filter. */
static int preload_has_other(const char *value) {
    const char *cursor = value;
    while (*cursor != '\0') {
        while (*cursor == ' ' || *cursor == '\t' || *cursor == ':') cursor++;
        const char *end = cursor;
        while (*end != '\0' && *end != ' ' && *end != '\t' && *end != ':') end++;
        if (end == cursor) break;
        if (!token_is_self(cursor, end)) return 1;
        cursor = end;
    }
    return 0;
}

/* Copy [value] without the entry for this library; only called when one remains. */
static char *filter_preload(struct env_pool *pool, const char *value) {
    size_t length = strlen(value);
    char *out = env_pool_alloc(pool, 11 + length + 1);
    if (out == NULL) return NULL;
    memcpy(out, "LD_PRELOAD=", 11);
    size_t used = 11;
    const char *cursor = value;
    while (*cursor != '\0') {
        while (*cursor == ' ' || *cursor == '\t' || *cursor == ':') cursor++;
        const char *end = cursor;
        while (*end != '\0' && *end != ' ' && *end != '\t' && *end != ':') end++;
        if (end == cursor) break;
        if (!token_is_self(cursor, end)) {
            if (used > 11) out[used++] = ' ';
            memcpy(out + used, cursor, (size_t)(end - cursor));
            used += (size_t)(end - cursor);
        }
        cursor = end;
    }
    out[used] = '\0';
    return out;
}

static int entry_is_original(const char *entry, char *const source[], size_t count) {
    for (size_t i = 0; i < count; i++) {
        if (source[i] == entry) return 1;
    }
    return 0;
}

static int env_is_arena(char **env) {
    uintptr_t value = (uintptr_t)env;
    uintptr_t base = (uintptr_t)g_env_arena.bytes;
    return value >= base && value < base + sizeof(g_env_arena.bytes);
}

static void free_preload_env(char **env, char *const source[], size_t count) {
    if (env == NULL || env_is_arena(env)) return;
    union env_region_header *header =
        (union env_region_header *)((char *)env - sizeof(union env_region_header));
    if (header->fields.magic != ENV_REGION_MAGIC) {
        /* Kept for a caller that never went through env_pool_mmap(). */
        for (size_t i = 0; env[i] != NULL; i++) {
            if (!entry_is_original(env[i], source, count)) free(env[i]);
        }
        free(env);
        return;
    }
    munmap(header, header->fields.size);
}

/*
 * Build a copy of [source] (or a fresh environment when it is NULL) for a child.
 *
 * A glibc child gets LD_PRELOAD unless [use_preload] is false: a routed child
 * already loaded this library through ld.so --preload, and inheriting the entry
 * would leak it into any bionic grandchild. Every glibc child also gets
 * OC_PREFIX, OC_USER_HOME and, when [fake_exe] is set, the executable that
 * /proc/self/exe must report.
 *
 * A bionic, static or script child never receives this glibc preload: bionic
 * would ignore or abort on it, and OC_REAL_EXE would be stale. Such a child
 * gets a cleaned copy only when the source contains one of our variables;
 * otherwise NULL tells the caller to pass the original environment.
 *
 * [failed] separates "nothing to change" from an allocation failure, so the
 * caller can retry on the heap after the arena overflows.
 */
static char **preload_env_build(const char *target, char *const source[], size_t count,
                                int use_preload, int fake_exe, struct env_pool *pool,
                                int *failed) {
    *failed = 0;
    int glibc = file_is_glibc(target) || is_loader_program(target);
    char **out = env_pool_alloc(pool, (count + 5) * sizeof(char *));
    if (out == NULL) {
        *failed = 1;
        return NULL;
    }
    out[0] = NULL;
    size_t used = 0;
    int have_preload = 0;
    int have_prefix = 0;
    int have_home = 0;
    int changed = 0;
    for (size_t i = 0; i < count; i++) {
        const char *entry = source[i];
        if (strncmp(entry, "LD_PRELOAD=", 11) == 0) {
            if (glibc && use_preload) {
                if (preload_mentions_self(entry + 11)) {
                    out[used++] = source[i];
                } else {
                    out[used] = merge_preload(pool, entry + 11);
                    if (out[used] == NULL) goto fail;
                    used++;
                    changed = 1;
                }
                have_preload = 1;
            } else if (preload_mentions_self(entry + 11)) {
                /* Redundant for a routed glibc child, fatal for a bionic one. */
                if (preload_has_other(entry + 11)) {
                    char *filtered = filter_preload(pool, entry + 11);
                    if (filtered == NULL) goto fail;
                    out[used++] = filtered;
                }
                changed = 1;
            } else {
                out[used++] = source[i];
            }
        } else if (strncmp(entry, "OC_PREFIX=", 10) == 0) {
            out[used++] = source[i];
            have_prefix = 1;
        } else if (strncmp(entry, "OC_USER_HOME=", 13) == 0) {
            out[used++] = source[i];
            have_home = 1;
        } else if (strncmp(entry, "OC_REAL_EXE=", 12) == 0) {
            /* Always replace: the value of the parent is never the child. */
            changed = 1;
        } else {
            out[used++] = source[i];
        }
        out[used] = NULL;
    }
    if (!glibc) {
        out[used] = NULL;
        if (!changed) return NULL;
        return out;
    }
    if (use_preload && !have_preload) {
        out[used] = join_env(pool, "LD_PRELOAD", g_self);
        if (out[used] == NULL) goto fail;
        used++;
    }
    if (!have_prefix && g_prefix_len > 0) {
        out[used] = join_env(pool, "OC_PREFIX", g_prefix);
        if (out[used] == NULL) goto fail;
        used++;
    }
    if (!have_home && g_home_len > 0) {
        out[used] = join_env(pool, "OC_USER_HOME", g_home);
        if (out[used] == NULL) goto fail;
        used++;
    }
    /*
     * A routed child sees ld.so as /proc/self/exe. Only a routed child needs the
     * override, and the value is the target of this exec, never the value that
     * the current process inherited.
     */
    if (fake_exe && target != NULL && target[0] != '\0') {
        out[used] = join_env(pool, "OC_REAL_EXE", target);
        if (out[used] == NULL) goto fail;
        used++;
    }
    out[used] = NULL;
    return out;
fail:
    *failed = 1;
    return NULL;
}

static char **preload_env(const char *target, char *const source[], size_t count,
                          int use_preload, int fake_exe) {
    if (!g_enabled || g_self[0] == '\0') return NULL;
    struct env_pool pool;
    /* The common environment fits the TLS arena; only a huge one grows. */
    env_pool_arena(&pool);
    int failed = 0;
    char **out = preload_env_build(target, source, count, use_preload, fake_exe,
                                   &pool, &failed);
    if (!failed) return out;
    /*
     * Grow an anonymous mapping instead of falling back to malloc: a fork()
     * child may still be running after another thread took the malloc lock, so
     * even the rare large environment must be built without that lock.
     */
    for (size_t size = ENV_MMAP_INITIAL; size <= ENV_MMAP_MAX; size *= 2) {
        if (!env_pool_mmap(&pool, size)) return NULL;
        failed = 0;
        out = preload_env_build(target, source, count, use_preload, fake_exe,
                                &pool, &failed);
        if (!failed) {
            if (out == NULL) env_pool_release(&pool);
            return out;
        }
        env_pool_release(&pool);
    }
    return NULL;
}

/* ---------------------------------------------------------------- routing core */

/*
 * The routing engine rewrites one exec into either
 *   ld.so --preload ... --library-path ... --argv0 ... PROGRAM ARGS...
 * or, for a script, into a direct exec of the resolved interpreter. Every string
 * is copied because the caller may free its argv as soon as exec returns.
 */

struct text {
    char *data;
    size_t len;
    size_t cap;
};

static int text_init(struct text *text) {
    text->cap = 256;
    text->data = malloc(text->cap);
    if (text->data == NULL) return -1;
    text->data[0] = '\0';
    text->len = 0;
    return 0;
}

static void text_free(struct text *text) {
    free(text->data);
    text->data = NULL;
    text->len = 0;
    text->cap = 0;
}

static int text_reserve(struct text *text, size_t extra) {
    if (text->len + extra + 1 <= text->cap) return 0;
    size_t cap = text->cap == 0 ? 256 : text->cap;
    while (cap < text->len + extra + 1) {
        if (cap > SIZE_MAX / 2) return -1;
        cap *= 2;
    }
    char *grown = realloc(text->data, cap);
    if (grown == NULL) return -1;
    text->data = grown;
    text->cap = cap;
    return 0;
}

static int text_append(struct text *text, const char *value) {
    size_t len = strlen(value);
    if (text_reserve(text, len) != 0) return -1;
    memcpy(text->data + text->len, value, len + 1);
    text->len += len;
    return 0;
}

static int text_append_char(struct text *text, char value) {
    if (text_reserve(text, 1) != 0) return -1;
    text->data[text->len++] = value;
    text->data[text->len] = '\0';
    return 0;
}

static int text_append_entry(struct text *text, const char *entry) {
    if (entry == NULL || entry[0] == '\0') return 0;
    if (text->len > 0 && text_append_char(text, ':') != 0) return -1;
    return text_append(text, entry);
}

/* One rewritten exec. Empty until route_build() succeeds. */
struct route {
    char *exec_path;    /* the path handed to the real exec/spawn */
    char *loader;       /* ld.so when the route goes through the loader */
    char *program;      /* the ELF that will really run */
    char *library_path; /* the --library-path value */
    char **argv;
    size_t argc;
    size_t capacity;
};

static void route_init(struct route *route) { memset(route, 0, sizeof(*route)); }

static void route_clear(struct route *route) {
    if (route->argv != NULL) {
        for (size_t i = 0; i < route->argc; i++) free(route->argv[i]);
        free(route->argv);
    }
    free(route->exec_path);
    free(route->loader);
    free(route->program);
    free(route->library_path);
    memset(route, 0, sizeof(*route));
}

static int route_push(struct route *route, const char *value) {
    if (route->argc + 2 > route->capacity) {
        size_t capacity = route->capacity == 0 ? 16 : route->capacity * 2;
        while (capacity < route->argc + 2) {
            if (capacity > SIZE_MAX / 2) return -1;
            capacity *= 2;
        }
        char **grown = realloc(route->argv, capacity * sizeof(char *));
        if (grown == NULL) return -1;
        route->argv = grown;
        route->capacity = capacity;
    }
    char *copy = strdup(value);
    if (copy == NULL) return -1;
    route->argv[route->argc++] = copy;
    route->argv[route->argc] = NULL;
    return 0;
}

static size_t array_count(char *const list[]) {
    size_t count = 0;
    if (list == NULL) return 0;
    while (list[count] != NULL) count++;
    return count;
}

static const char *env_value(char *const env[], const char *name) {
    if (env == NULL) return NULL;
    size_t len = strlen(name);
    for (size_t i = 0; env[i] != NULL; i++) {
        if (strncmp(env[i], name, len) == 0 && env[i][len] == '=') return env[i] + len + 1;
    }
    return NULL;
}

static int copy_path(const char *path, char *out, size_t size) {
    size_t len = strlen(path);
    if (len + 1 > size) return 0;
    memcpy(out, path, len + 1);
    return 1;
}

static int join_path(char *out, size_t size, const char *dir, const char *name) {
    size_t dir_len = strlen(dir);
    size_t name_len = strlen(name);
    int slash = name_len == 0 || name[0] != '/';
    if (dir_len + (size_t)slash + name_len + 1 > size) return -1;
    memcpy(out, dir, dir_len);
    size_t used = dir_len;
    if (slash) out[used++] = '/';
    memcpy(out + used, name, name_len + 1);
    return 0;
}

/*
 * The route probe and the loader need an absolute path; the kernel alone would
 * resolve a relative one. getcwd is issued as a raw syscall so the unmapping
 * hook cannot rewrite the answer.
 */
static int make_absolute(const char *path, char *out, size_t size) {
    if (path[0] == '/') return copy_path(path, out, size) ? 0 : -1;
    char cwd[PATH_MAX];
    if (syscall(SYS_getcwd, cwd, sizeof(cwd)) <= 0) return -1;
    while (path[0] == '.' && path[1] == '/') path += 2;
    return join_path(out, size, cwd, path);
}

static int executable_raw(const char *path) {
    struct stat st;
    if (!stat_raw(path, &st)) return 0;
    return S_ISREG(st.st_mode) && (st.st_mode & 0111) != 0;
}

static void directory_of(const char *path, char *out, size_t size) {
    const char *slash = strrchr(path, '/');
    if (slash == NULL) {
        if (size > 1) {
            out[0] = '.';
            out[1] = '\0';
        }
        return;
    }
    size_t len = slash == path ? 1 : (size_t)(slash - path);
    if (len + 1 > size) len = size > 0 ? size - 1 : 0;
    memcpy(out, path, len);
    out[len] = '\0';
}

/* -------------------------------------------------------------- loader lookup */

static const char *machine_name(uint16_t machine) {
    switch (machine) {
        case EM_AARCH64: return "aarch64";
        case EM_X86_64: return "x86_64";
        default: return "unknown";
    }
}

static const char *loader_soname(uint16_t machine) {
    switch (machine) {
        case EM_AARCH64: return "ld-linux-aarch64.so.1";
        case EM_X86_64: return "ld-linux-x86-64.so.2";
        default: return NULL;
    }
}

static int loader_machine_matches(const char *path, uint16_t machine) {
    int fd = open_raw(path, O_RDONLY);
    if (fd < 0) return 0;
    struct elf_info info;
    int ok = probe_elf(fd, &info) && info.machine == machine;
    close_raw(fd);
    return ok;
}

static int loader_candidate(const char *dir, const char *name, uint16_t machine,
                            char *out, size_t size) {
    char path[PATH_MAX];
    if (join_path(path, sizeof(path), dir, name) != 0) return 0;
    if (!executable_raw(path)) return 0;
    if (!loader_machine_matches(path, machine)) return 0;
    return copy_path(path, out, size);
}

static int scan_loader_dir(const char *dir, uint16_t machine, char *out, size_t size) {
    DIR *handle = opendir(dir);
    if (handle == NULL) return 0;
    int found = 0;
    struct dirent *entry;
    while ((entry = readdir(handle)) != NULL) {
        if (strncmp(entry->d_name, "ld-linux", 8) != 0) continue;
        if (strstr(entry->d_name, ".so.") == NULL) continue;
        if (loader_candidate(dir, entry->d_name, machine, out, size)) {
            found = 1;
            break;
        }
    }
    closedir(handle);
    return found;
}

/*
 * Pick the loader of the prefix for the ELF machine. The exact soname wins, then
 * the arch-neutral ld.so symlink, then any ld-linux found in the directory (a
 * distribution may rename it, but the e_machine check keeps a foreign loader
 * from being picked).
 */
static int find_loader(uint16_t machine, char *out, size_t size) {
    const char *soname = loader_soname(machine);
    if (soname == NULL || g_prefix_len == 0) return 0;
    static const char *const dirs[] = {"glibc/lib", "lib"};
    for (size_t i = 0; i < sizeof(dirs) / sizeof(dirs[0]); i++) {
        char dir[PATH_MAX];
        if (join_path(dir, sizeof(dir), g_prefix, dirs[i]) != 0) continue;
        if (loader_candidate(dir, soname, machine, out, size)) return 1;
    }
    for (size_t i = 0; i < sizeof(dirs) / sizeof(dirs[0]); i++) {
        char dir[PATH_MAX];
        if (join_path(dir, sizeof(dir), g_prefix, dirs[i]) != 0) continue;
        if (loader_candidate(dir, "ld.so", machine, out, size)) return 1;
    }
    for (size_t i = 0; i < sizeof(dirs) / sizeof(dirs[0]); i++) {
        char dir[PATH_MAX];
        if (join_path(dir, sizeof(dir), g_prefix, dirs[i]) != 0) continue;
        if (scan_loader_dir(dir, machine, out, size)) return 1;
    }
    return 0;
}

/* Scans the loader file for the literal option; glibc 2.33 added --argv0. */
static int file_contains(const char *path, const char *needle) {
    int fd = open_raw(path, O_RDONLY);
    if (fd < 0) return 0;
    size_t needle_len = strlen(needle);
    char buffer[8192 + 64];
    size_t carry = 0;
    int found = 0;
    for (;;) {
        ssize_t n = syscall(SYS_read, fd, buffer + carry, sizeof(buffer) - carry);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        size_t total = carry + (size_t)n;
        if (total >= needle_len && memmem(buffer, total, needle, needle_len) != NULL) {
            found = 1;
            break;
        }
        carry = needle_len > 1 ? needle_len - 1 : 0;
        if (carry > total) carry = total;
        memmove(buffer, buffer + total - carry, carry);
    }
    close_raw(fd);
    return found;
}

static char g_argv0_loader[PATH_MAX];
static int g_argv0_support;

static int loader_supports_argv0(const char *loader) {
    if (strcmp(loader, g_argv0_loader) == 0) return g_argv0_support;
    int support = file_contains(loader, "--argv0");
    copy_path(loader, g_argv0_loader, sizeof(g_argv0_loader));
    g_argv0_support = support;
    return support;
}

/* ------------------------------------------------------------ library search */

/*
 * The loader does not expand $ORIGIN in --library-path (it only does that for
 * DT_RUNPATH), so the expansion happens here. $LIB is approximated by "lib";
 * nothing in the target list needs the multiarch tuple.
 */
static void append_expanded_entry(struct text *text, const char *entry, const char *origin,
                                  const char *platform) {
    char expanded[PATH_MAX];
    size_t used = 0;
    size_t len = strlen(entry);
    for (size_t i = 0; i < len; i++) {
        if (entry[i] == '$') {
            const char *name = NULL;
            size_t name_len = 0;
            size_t skip = 0;
            if (entry[i + 1] == '{') {
                const char *close = strchr(entry + i + 2, '}');
                if (close != NULL) {
                    name = entry + i + 2;
                    name_len = (size_t)(close - name);
                    skip = name_len + 2;
                }
            } else {
                name = entry + i + 1;
                while (name[name_len] != '\0' && name[name_len] != '/' &&
                       name[name_len] != ':') {
                    name_len++;
                }
                skip = name_len;
            }
            const char *value = NULL;
            if (name != NULL) {
                if (name_len == 6 && strncmp(name, "ORIGIN", 6) == 0) {
                    value = origin;
                } else if (name_len == 3 && strncmp(name, "LIB", 3) == 0) {
                    value = "lib";
                } else if (name_len == 8 && strncmp(name, "PLATFORM", 8) == 0) {
                    value = platform;
                }
            }
            if (value != NULL) {
                size_t value_len = strlen(value);
                if (used + value_len + 1 > sizeof(expanded)) return;
                memcpy(expanded + used, value, value_len);
                used += value_len;
                i += skip;
                continue;
            }
        }
        if (used + 2 > sizeof(expanded)) return;
        expanded[used++] = entry[i];
    }
    expanded[used] = '\0';
    char mapped[PATH_MAX];
    const char *target = map_path(expanded, mapped, sizeof(mapped));
    (void)text_append_entry(text, target);
}

static void append_path_list(struct text *text, const char *list, const char *origin,
                             const char *platform) {
    if (list == NULL) return;
    while (*list != '\0') {
        const char *end = strchr(list, ':');
        size_t len = end != NULL ? (size_t)(end - list) : strlen(list);
        if (len > 0 && len < PATH_MAX) {
            char entry[PATH_MAX];
            memcpy(entry, list, len);
            entry[len] = '\0';
            append_expanded_entry(text, entry, origin, platform);
        }
        if (end == NULL) break;
        list = end + 1;
    }
}

/*
 * Runpath first, so a downloaded binary finds the libraries next to itself
 * before the prefix, then the two prefix directories, then the inherited
 * LD_LIBRARY_PATH. An empty result is allowed: the loader keeps its default.
 */
static void build_library_path(const struct elf_info *info, const char *program,
                               char *const envp[], char *out, size_t size) {
    out[0] = '\0';
    struct text text;
    if (text_init(&text) != 0) return;
    char origin[PATH_MAX];
    directory_of(program, origin, sizeof(origin));
    const char *platform = machine_name(info->machine);
    append_path_list(&text, info->runpath, origin, platform);
    if (g_prefix_len > 0) {
        char entry[PATH_MAX];
        if (join_path(entry, sizeof(entry), g_prefix, "lib") == 0) {
            (void)text_append_entry(&text, entry);
        }
        if (join_path(entry, sizeof(entry), g_prefix, "glibc/lib") == 0) {
            (void)text_append_entry(&text, entry);
        }
    }
    append_path_list(&text, env_value(envp, "LD_LIBRARY_PATH"), origin, platform);
    if (text.len > 0 && text.len + 1 <= size) memcpy(out, text.data, text.len + 1);
    text_free(&text);
}

/* ------------------------------------------------------------- script routing */

#define ROUTE_DEPTH_MAX 4
#define ROUTE_WORDS_MAX 64

static int route_build(const char *physical, char *const argv[], char *const envp[],
                       struct route *route, int depth);
static int route_redirect(const char *program, char *const argv[], struct route *route);

/*
 * Resolve a command name through PATH (from [envp]) and return its physical
 * path. A name with a slash is only mapped, never searched.
 */
static int find_program_path(const char *name, const char *path, char *out, size_t size) {
    if (name == NULL || name[0] == '\0') return 0;
    if (strchr(name, '/') != NULL) {
        char mapped[PATH_MAX];
        const char *target = map_path(name, mapped, sizeof(mapped));
        if (executable_file(target)) return copy_path(target, out, size);
        if (target != name && executable_file(name)) return copy_path(name, out, size);
        return 0;
    }
    if (path == NULL) path = "/bin:/usr/bin";
    size_t name_len = strlen(name);
    while (*path != '\0') {
        const char *end = strchr(path, ':');
        size_t len = end != NULL ? (size_t)(end - path) : strlen(path);
        char candidate[PATH_MAX];
        int ready = 0;
        if (len == 0) {
            if (name_len + 3 <= sizeof(candidate)) {
                candidate[0] = '.';
                candidate[1] = '/';
                memcpy(candidate + 2, name, name_len + 1);
                ready = 1;
            }
        } else if (len + name_len + 2 <= sizeof(candidate)) {
            memcpy(candidate, path, len);
            candidate[len] = '/';
            memcpy(candidate + len + 1, name, name_len + 1);
            ready = 1;
        }
        if (ready) {
            /* The mapped spelling is what the program means inside the prefix;
             * the original is kept for a path that really exists (host tests,
             * a bionic tool that already lives at /system/bin). */
            char mapped[PATH_MAX];
            const char *target = map_path(candidate, mapped, sizeof(mapped));
            if (executable_file(target)) return copy_path(target, out, size);
            if (target != candidate && executable_file(candidate)) {
                return copy_path(candidate, out, size);
            }
        }
        if (end == NULL) break;
        path = end + 1;
    }
    return 0;
}

/*
 * The interpreter of a script is looked up in the prefix. An original path wins
 * when it exists (that is what the kernel would use), then its mapped spelling,
 * then the same basename through PATH. /bin/sh is special: the prefix spelling
 * is deliberately not used, and the bionic shell of Android takes over.
 */
static int resolve_interp(const char *interp, char *const envp[], char *out, size_t size) {
    if (interp[0] == '/') {
        if (regular_file_raw(interp)) return copy_path(interp, out, size);
        char mapped[PATH_MAX];
        const char *target = map_path(interp, mapped, sizeof(mapped));
        if (target != interp && regular_file_raw(target)) return copy_path(target, out, size);
        if (strcmp(interp, "/bin/sh") == 0) {
            if (regular_file_raw("/system/bin/sh")) return copy_path("/system/bin/sh", out, size);
        }
        const char *base = strrchr(interp, '/');
        base = base != NULL ? base + 1 : interp;
        return find_program_path(base, env_value(envp, "PATH"), out, size);
    }
    return find_program_path(interp, env_value(envp, "PATH"), out, size);
}

static int is_env_program(const char *interp) {
    const char *base = strrchr(interp, '/');
    base = base != NULL ? base + 1 : interp;
    return strcmp(base, "env") == 0;
}

/*
 * `#!/usr/bin/env python3 -u` cannot work through the bionic env of the prefix,
 * which does not route its child. The reference implementation of this library
 * skips env and execs the program itself. The kernel hands the rest of the
 * shebang line to env as one argument, so -S/--split-string is honoured here
 * instead: its tokens become the program and its arguments. Assignments and the
 * rare env option that changes the environment are dropped.
 */
static int parse_env_command(const char *line, char *const envp[], char *program,
                             size_t program_size, char *args, size_t args_size) {
    char storage[PATH_MAX];
    size_t len = strlen(line);
    if (len >= sizeof(storage)) return 0;
    memcpy(storage, line, len + 1);
    char collected[PATH_MAX];
    collected[0] = '\0';
    const char *found = NULL;
    char *save = NULL;
    int options = 1;
    for (char *token = strtok_r(storage, " \t", &save); token != NULL;
         token = strtok_r(NULL, " \t", &save)) {
        if (options && token[0] == '-' && token[1] != '\0') {
            if (strcmp(token, "--") == 0) {
                options = 0;
                continue;
            }
            /*
             * -S/--split-string splits the rest of the line into a command
             * line: the next token is the program and the tokens after it are
             * its arguments, even when they start with '-'.
             */
            if (strcmp(token, "-S") == 0 || strcmp(token, "--split-string") == 0) {
                options = 0;
                continue;
            }
            if (strcmp(token, "-u") == 0 || strcmp(token, "--unset") == 0 ||
                strcmp(token, "-C") == 0 || strcmp(token, "--chdir") == 0) {
                (void)strtok_r(NULL, " \t", &save);
                continue;
            }
            /* -i, -0, -v and --opt=value need no separate value. */
            continue;
        }
        if (options && strchr(token, '=') != NULL) continue;
        if (found == NULL) {
            /* Everything after the program is an argument of the program,
             * even when it starts with '-'. */
            found = token;
            options = 0;
            continue;
        }
        size_t used = strlen(collected);
        size_t word_len = strlen(token);
        if (used + 1 + word_len + 1 > sizeof(collected)) return 0;
        if (used > 0) collected[used++] = ' ';
        memcpy(collected + used, token, word_len + 1);
    }
    if (found == NULL) return 0;
    char resolved[PATH_MAX];
    if (!resolve_interp(found, envp, resolved, sizeof(resolved))) return 0;
    if (!copy_path(resolved, program, program_size)) return 0;
    return copy_path(collected, args, args_size);
}

static int parse_shebang(const char *line, char *interp, size_t interp_size,
                         char *arg, size_t arg_size) {
    if (line[0] != '#' || line[1] != '!') return 0;
    const char *p = line + 2;
    while (*p == ' ' || *p == '\t') p++;
    size_t used = 0;
    while (*p != '\0' && *p != ' ' && *p != '\t' && *p != '\r' && *p != '\n') {
        if (used + 1 >= interp_size) return 0;
        interp[used++] = *p++;
    }
    interp[used] = '\0';
    if (used == 0) return 0;
    while (*p == ' ' || *p == '\t') p++;
    size_t len = strlen(p);
    while (len > 0 && (p[len - 1] == ' ' || p[len - 1] == '\t' || p[len - 1] == '\r')) len--;
    if (len + 1 > arg_size) return 0;
    memcpy(arg, p, len);
    arg[len] = '\0';
    return 1;
}

/*
 * Build an argv that runs the interpreter over the script, then recurse so the
 * interpreter itself can be routed through the loader when needed. When the
 * recursion finds nothing to rewrite (a bionic or static interpreter), the
 * interpreter is still exec'd directly: relying on the kernel would fail when
 * the shebang of the script names a path that only exists in the prefix.
 */
static int route_script(const char *script, const char *line, char *const argv[],
                        char *const envp[], struct route *route, int depth) {
    char interp[PATH_MAX];
    char extra[PATH_MAX];
    if (!parse_shebang(line, interp, sizeof(interp), extra, sizeof(extra))) return 0;

    char program[PATH_MAX];
    char initial[PATH_MAX];
    initial[0] = '\0';
    if (is_env_program(interp)) {
        if (!parse_env_command(extra, envp, program, sizeof(program), initial,
                               sizeof(initial))) {
            return 0;
        }
    } else {
        if (!resolve_interp(interp, envp, program, sizeof(program))) return 0;
        memcpy(initial, extra, strlen(extra) + 1);
    }

    char storage[PATH_MAX];
    memcpy(storage, initial, strlen(initial) + 1);
    char *words[ROUTE_WORDS_MAX];
    size_t word_count = 0;
    char *save = NULL;
    for (char *word = strtok_r(storage, " \t", &save); word != NULL;
         word = strtok_r(NULL, " \t", &save)) {
        if (word_count == ROUTE_WORDS_MAX) return 0;
        words[word_count++] = word;
    }

    size_t original = array_count(argv);
    size_t count = 1 + word_count + 1 + (original > 0 ? original - 1 : 0);
    char **temp = malloc((count + 1) * sizeof(char *));
    if (temp == NULL) return 0;
    size_t used = 0;
    temp[used++] = program;
    for (size_t i = 0; i < word_count; i++) temp[used++] = words[i];
    temp[used++] = (char *)script;
    for (size_t i = 1; i < original; i++) temp[used++] = argv[i];
    temp[used] = NULL;

    int routed = route_build(program, temp, envp, route, depth + 1);
    if (routed != 1) routed = route_redirect(program, temp, route);
    free(temp);
    return routed;
}

/* ------------------------------------------------------------ route assembly */

/*
 * ld.so OPTION... PROGRAM [ARG...]. --argv0 is added only when the loader
 * mentions the option, so an older glibc still receives the minimum syntax it
 * understands; without it the kernel-visible argv[0] is the program path.
 */
static int route_assemble(const char *loader, const char *program, char *const argv[],
                          const char *library_path, struct route *route) {
    route->loader = strdup(loader);
    route->program = strdup(program);
    route->exec_path = strdup(loader);
    if (route->loader == NULL || route->program == NULL || route->exec_path == NULL) {
        return -1;
    }
    if (library_path != NULL && library_path[0] != '\0') {
        route->library_path = strdup(library_path);
        if (route->library_path == NULL) return -1;
    }
    if (route_push(route, loader) != 0) return -1;
    if (g_self[0] != '\0') {
        if (route_push(route, "--preload") != 0) return -1;
        if (route_push(route, g_self) != 0) return -1;
    }
    if (route->library_path != NULL) {
        if (route_push(route, "--library-path") != 0) return -1;
        if (route_push(route, route->library_path) != 0) return -1;
    }
    if (argv != NULL && argv[0] != NULL && argv[0][0] != '\0' &&
        loader_supports_argv0(loader)) {
        if (route_push(route, "--argv0") != 0) return -1;
        if (route_push(route, argv[0]) != 0) return -1;
    }
    if (route_push(route, program) != 0) return -1;
    if (argv != NULL) {
        for (size_t i = 1; argv[i] != NULL; i++) {
            if (route_push(route, argv[i]) != 0) return -1;
        }
    }
    return 1;
}

/* Exec the interpreter itself; its own argv already starts with its path. */
static int route_redirect(const char *program, char *const argv[], struct route *route) {
    route->exec_path = strdup(program);
    route->program = strdup(program);
    if (route->exec_path == NULL || route->program == NULL) {
        route_clear(route);
        return 0;
    }
    for (size_t i = 0; argv[i] != NULL; i++) {
        if (route_push(route, argv[i]) != 0) {
            route_clear(route);
            return 0;
        }
    }
    return 1;
}

/*
 * Decide what a path really is. Returns 1 when [route] holds a rewritten exec,
 * 0 when the caller should pass the original path and argv to the kernel.
 *
 * An ELF is routed only when its PT_INTERP names a glibc loader that does not
 * exist: a package relocated by the app already points at the loader of the
 * prefix, and a bionic or static program must never see the glibc loader.
 */
static int route_build(const char *physical, char *const argv[], char *const envp[],
                       struct route *route, int depth) {
    route_init(route);
    if (depth > ROUTE_DEPTH_MAX || physical == NULL || physical[0] == '\0') return 0;
    const char *base = strrchr(physical, '/');
    base = base != NULL ? base + 1 : physical;
    if (name_is_loader(base)) return 0;

    int fd = open_raw(physical, O_RDONLY);
    if (fd < 0) return 0;
    struct elf_info info;
    int elf = probe_elf(fd, &info);
    char line[256] = {0};
    int shebang = 0;
    if (!elf) {
        int n = read_exact(fd, line, sizeof(line) - 1, 0);
        if (n >= 2) {
            line[n] = '\0';
            char *newline = memchr(line, '\n', (size_t)n);
            if (newline != NULL) *newline = '\0';
            shebang = line[0] == '#' && line[1] == '!';
        }
    }
    close_raw(fd);

    if (elf) {
        if (!info.has_interp || !is_glibc_interp(info.interp)) return 0;
        if (regular_file_raw(info.interp)) return 0;
        char loader[PATH_MAX];
        if (!find_loader(info.machine, loader, sizeof(loader))) return 0;
        char library_path[PATH_MAX * 2];
        build_library_path(&info, physical, envp, library_path, sizeof(library_path));
        if (route_assemble(loader, physical, argv, library_path, route) != 1) {
            route_clear(route);
            return 0;
        }
        return 1;
    }
    if (!shebang) return 0;
    return route_script(physical, line, argv, envp, route, depth);
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

/* --------------------------------------------------------------- low ports */

/*
 * Android does not allow the app to bind a port below 1024. A tutorial server
 * (`python -m http.server 80`, nginx, `node server.js`) then fails with EACCES,
 * which looks like a broken program. bind() transparently moves such a port by
 * +8000, and connect() to loopback retries the moved port when the original
 * refuses the connection. The move is reported once on stderr and in
 * $PREFIX/run/moved-ports/<original>, so the app can tell the user which port
 * is really listening. OC_LOW_PORTS=0 turns both rules off.
 */
#define LOW_PORT_OFFSET 8000

static int low_port_translate(const struct sockaddr *addr, socklen_t addrlen,
                              struct sockaddr_storage *out, socklen_t *outlen,
                              uint16_t *original, int loopback_only) {
    if (!g_low_ports || addr == NULL || out == NULL || outlen == NULL || original == NULL) {
        return 0;
    }
    if (addrlen < (socklen_t)sizeof(sa_family_t)) return 0;
    uint16_t port;
    if (addr->sa_family == AF_INET) {
        if (addrlen < (socklen_t)sizeof(struct sockaddr_in)) return 0;
        const struct sockaddr_in *in = (const struct sockaddr_in *)addr;
        port = ntohs(in->sin_port);
        if (port == 0 || port >= 1024) return 0;
        if (loopback_only && (ntohl(in->sin_addr.s_addr) >> 24) != 127) return 0;
        if ((size_t)addrlen > sizeof(*out)) return 0;
        memcpy(out, addr, (size_t)addrlen);
        ((struct sockaddr_in *)out)->sin_port = htons((uint16_t)(port + LOW_PORT_OFFSET));
    } else if (addr->sa_family == AF_INET6) {
        if (addrlen < (socklen_t)sizeof(struct sockaddr_in6)) return 0;
        const struct sockaddr_in6 *in6 = (const struct sockaddr_in6 *)addr;
        port = ntohs(in6->sin6_port);
        if (port == 0 || port >= 1024) return 0;
        if (loopback_only && !IN6_IS_ADDR_LOOPBACK(&in6->sin6_addr)) return 0;
        if ((size_t)addrlen > sizeof(*out)) return 0;
        memcpy(out, addr, (size_t)addrlen);
        ((struct sockaddr_in6 *)out)->sin6_port = htons((uint16_t)(port + LOW_PORT_OFFSET));
    } else {
        return 0;
    }
    *outlen = addrlen;
    *original = port;
    return 1;
}

/* Warn once per original port; the marker file is refreshed on every bind. */
static unsigned char g_low_port_warned[1024];

static void moved_port_note(uint16_t original, uint16_t moved) {
    if (original < sizeof(g_low_port_warned) &&
        __atomic_exchange_n(&g_low_port_warned[original], 1, __ATOMIC_RELAXED) == 0) {
        char line[96];
        int n = snprintf(line, sizeof(line), "oc-pathmap: port %u moved to %u\n",
                         (unsigned)original, (unsigned)moved);
        if (n > 0) {
            size_t len = (size_t)n < sizeof(line) ? (size_t)n : sizeof(line) - 1;
            (void)syscall(SYS_write, STDERR_FILENO, line, len);
        }
    }
    int n;
    if (g_prefix_len == 0) return;
    char dir[PATH_MAX];
    n = snprintf(dir, sizeof(dir), "%s/run/moved-ports", g_prefix);
    if (n <= 0 || (size_t)n >= sizeof(dir)) return;
    create_tree_raw(dir);
    char path[PATH_MAX];
    n = snprintf(path, sizeof(path), "%s/%u", dir, (unsigned)original);
    if (n <= 0 || (size_t)n >= sizeof(path)) return;
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path,
                          O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;
    char text[32];
    n = snprintf(text, sizeof(text), "%u\n", (unsigned)moved);
    if (n > 0) (void)syscall(SYS_write, fd, text, (size_t)n);
    close_raw(fd);
}

/* --------------------------------------------------------- Unix socket hooks */

EXPORT int bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    typedef int (*fn_t)(int, const struct sockaddr *, socklen_t);
    RESOLVE(bind, fn_t)
    struct unix_target target;
    if (translate_unix(addr, addrlen, &target)) {
        if (target.hashed) {
            int saved = errno;
            socket_ensure_dir();
            errno = saved;
        }
        int rc = real_bind(sockfd, (const struct sockaddr *)&target.addr, target.len);
        if (rc == 0) {
            struct stat st;
            const struct stat *id = NULL;
            if (target.hashed && socket_stat_raw(target.physical, &st)) id = &st;
            socket_record_local(sockfd, &target, id);
        }
        return rc;
    }
    struct sockaddr_storage moved;
    socklen_t moved_len = 0;
    uint16_t original = 0;
    if (low_port_translate(addr, addrlen, &moved, &moved_len, &original, 0)) {
        int rc = real_bind(sockfd, (const struct sockaddr *)&moved, moved_len);
        if (rc == 0) {
            moved_port_note(original, (uint16_t)(original + LOW_PORT_OFFSET));
        }
        return rc;
    }
    return real_bind(sockfd, addr, addrlen);
}

EXPORT int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    typedef int (*fn_t)(int, const struct sockaddr *, socklen_t);
    RESOLVE(connect, fn_t)
    socket_record_clear_peer(sockfd);
    struct unix_target target;
    if (translate_unix(addr, addrlen, &target)) {
        int rc = real_connect(sockfd, (const struct sockaddr *)&target.addr, target.len);
        if (rc == 0) {
            socket_record_peer(sockfd, &target);
        } else {
            int saved = errno;
            if (saved == EINPROGRESS || saved == EALREADY || saved == EISCONN) {
                socket_record_peer(sockfd, &target);
            }
            errno = saved;
        }
        return rc;
    }
    int rc = real_connect(sockfd, addr, addrlen);
    if (rc != 0 && errno == ECONNREFUSED) {
        int saved = errno;
        struct sockaddr_storage moved;
        socklen_t moved_len = 0;
        uint16_t original = 0;
        if (low_port_translate(addr, addrlen, &moved, &moved_len, &original, 1)) {
            int retried = real_connect(sockfd, (const struct sockaddr *)&moved, moved_len);
            if (retried == 0) return 0;
        }
        errno = saved;
    }
    return rc;
}

EXPORT ssize_t sendto(int sockfd, const void *buf, size_t len, int flags,
                      const struct sockaddr *dest_addr, socklen_t addrlen) {
    typedef ssize_t (*fn_t)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
    RESOLVE(sendto, fn_t)
    struct unix_target target;
    if (translate_unix(dest_addr, addrlen, &target)) {
        return real_sendto(sockfd, buf, len, flags, (const struct sockaddr *)&target.addr,
                           target.len);
    }
    return real_sendto(sockfd, buf, len, flags, dest_addr, addrlen);
}

EXPORT ssize_t sendmsg(int sockfd, const struct msghdr *msg, int flags) {
    typedef ssize_t (*fn_t)(int, const struct msghdr *, int);
    RESOLVE(sendmsg, fn_t)
    struct unix_target target;
    if (msg != NULL &&
        translate_unix((const struct sockaddr *)msg->msg_name, msg->msg_namelen, &target)) {
        struct msghdr copy = *msg;
        copy.msg_name = &target.addr;
        copy.msg_namelen = target.len;
        return real_sendmsg(sockfd, &copy, flags);
    }
    return real_sendmsg(sockfd, msg, flags);
}

EXPORT int getsockname(int sockfd, struct sockaddr *addr, socklen_t *addrlen) {
    typedef int (*fn_t)(int, struct sockaddr *, socklen_t *);
    RESOLVE(getsockname, fn_t)
    socklen_t capacity = addrlen != NULL ? *addrlen : 0;
    int rc = real_getsockname(sockfd, addr, addrlen);
    if (rc == 0) restore_socket_name(sockfd, addr, addrlen, capacity, 0);
    return rc;
}

EXPORT int getpeername(int sockfd, struct sockaddr *addr, socklen_t *addrlen) {
    typedef int (*fn_t)(int, struct sockaddr *, socklen_t *);
    RESOLVE(getpeername, fn_t)
    socklen_t capacity = addrlen != NULL ? *addrlen : 0;
    int rc = real_getpeername(sockfd, addr, addrlen);
    if (rc == 0) restore_socket_name(sockfd, addr, addrlen, capacity, 1);
    return rc;
}

EXPORT int accept(int sockfd, struct sockaddr *addr, socklen_t *addrlen) {
    typedef int (*fn_t)(int, struct sockaddr *, socklen_t *);
    RESOLVE(accept, fn_t)
    socklen_t capacity = addrlen != NULL ? *addrlen : 0;
    int fd = real_accept(sockfd, addr, addrlen);
    accept_done(sockfd, fd, addr, addrlen, capacity);
    return fd;
}

EXPORT int accept4(int sockfd, struct sockaddr *addr, socklen_t *addrlen, int flags) {
    typedef int (*fn_t)(int, struct sockaddr *, socklen_t *, int);
    RESOLVE(accept4, fn_t)
    socklen_t capacity = addrlen != NULL ? *addrlen : 0;
    int fd = real_accept4(sockfd, addr, addrlen, flags);
    accept_done(sockfd, fd, addr, addrlen, capacity);
    return fd;
}

EXPORT int close(int fd) {
    typedef int (*fn_t)(int);
    RESOLVE(close, fn_t)
    int rc = real_close(fd);
    int saved = errno;
    socket_record_close(fd, rc == 0);
    errno = saved;
    return rc;
}

/*
 * A descriptor that refers to a bound Unix socket can be moved with dup2() or
 * dup3() (servers that daemonize, or that replace stdio). The socket record is
 * copied so getsockname()/getpeername() keep returning the original spelling,
 * and the target of a dup2()/dup3() releases whatever it referred to before.
 */
EXPORT int dup(int oldfd) {
    typedef int (*fn_t)(int);
    RESOLVE(dup, fn_t)
    int rc = real_dup(oldfd);
    if (rc >= 0) socket_record_dup(oldfd, rc);
    return rc;
}

EXPORT int dup2(int oldfd, int newfd) {
    typedef int (*fn_t)(int, int);
    RESOLVE(dup2, fn_t)
    int rc = real_dup2(oldfd, newfd);
    if (rc >= 0 && oldfd != newfd) {
        socket_record_close(newfd, 1);
        socket_record_dup(oldfd, newfd);
    }
    return rc;
}

EXPORT int dup3(int oldfd, int newfd, int flags) {
    typedef int (*fn_t)(int, int, int);
    RESOLVE(dup3, fn_t)
    int rc = real_dup3(oldfd, newfd, flags);
    if (rc >= 0 && oldfd != newfd) {
        socket_record_close(newfd, 1);
        socket_record_dup(oldfd, newfd);
    }
    return rc;
}

#ifndef CLOSE_RANGE_CLOEXEC
#define CLOSE_RANGE_CLOEXEC (1U << 2)
#endif

EXPORT int close_range(unsigned int first, unsigned int last, int flags) {
    typedef int (*fn_t)(unsigned int, unsigned int, int);
    RESOLVE(close_range, fn_t)
    int rc = real_close_range(first, last, flags);
    int saved = errno;
    /* CLOSE_RANGE_CLOEXEC only sets FD_CLOEXEC; the descriptors stay open. */
    if (rc == 0 && (flags & CLOSE_RANGE_CLOEXEC) == 0) {
        socket_record_close_range(first, last);
    }
    errno = saved;
    return rc;
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
    const char *exe = exe_override(path);
    return real_open(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), flags, mode);
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
    const char *exe = exe_override(path);
    return real_open64(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), flags, mode);
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
    const char *exe = exe_override(path);
    return real_openat(dirfd, exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), flags,
                       mode);
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
    const char *exe = exe_override(path);
    return real_openat64(dirfd, exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), flags,
                         mode);
}

#ifdef OC_HAVE_OPENAT2
EXPORT int openat2(int dirfd, const char *path, const struct open_how *how, size_t size) {
    typedef int (*fn_t)(int, const char *, const struct open_how *, size_t);
    RESOLVE(openat2, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real_openat2(dirfd, exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), how,
                        size);
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
    const char *exe = exe_override(path);
    return real___open_2(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), flags);
}

EXPORT int __open64_2(const char *path, int flags) {
    typedef int (*fn_t)(const char *, int);
    RESOLVE(__open64_2, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real___open64_2(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), flags);
}

EXPORT int __openat_2(int dirfd, const char *path, int flags) {
    typedef int (*fn_t)(int, const char *, int);
    RESOLVE(__openat_2, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real___openat_2(dirfd, exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)),
                           flags);
}

EXPORT int __openat64_2(int dirfd, const char *path, int flags) {
    typedef int (*fn_t)(int, const char *, int);
    RESOLVE(__openat64_2, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real___openat64_2(dirfd, exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)),
                             flags);
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
    const char *exe = exe_override(path);
    return real_fopen(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT FILE *fopen64(const char *path, const char *mode) {
    typedef FILE *(*fn_t)(const char *, const char *);
    RESOLVE_PTR(fopen64, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real_fopen64(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT FILE *freopen(const char *path, const char *mode, FILE *stream) {
    typedef FILE *(*fn_t)(const char *, const char *, FILE *);
    RESOLVE_PTR(freopen, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real_freopen(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), mode, stream);
}

EXPORT FILE *freopen64(const char *path, const char *mode, FILE *stream) {
    typedef FILE *(*fn_t)(const char *, const char *, FILE *);
    RESOLVE_PTR(freopen64, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real_freopen64(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), mode, stream);
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
    const char *exe = exe_override(path);
    return real_stat(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), buf);
}

/* lstat must keep reporting /proc/self/exe as a link: the override is a target. */
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
    const char *exe = exe_override(path);
    return real_stat64(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), buf);
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
    const char *exe = (flags & AT_SYMLINK_NOFOLLOW) ? NULL : exe_override(path);
    return real_fstatat(dirfd, exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), buf,
                        flags);
}

EXPORT int fstatat64(int dirfd, const char *path, struct stat64 *buf, int flags) {
    typedef int (*fn_t)(int, const char *, struct stat64 *, int);
    RESOLVE(fstatat64, fn_t)
    char buffer[PATH_MAX];
    const char *exe = (flags & AT_SYMLINK_NOFOLLOW) ? NULL : exe_override(path);
    return real_fstatat64(dirfd, exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), buf,
                          flags);
}

EXPORT int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *buf) {
    typedef int (*fn_t)(int, const char *, int, unsigned int, struct statx *);
    RESOLVE(statx, fn_t)
    char buffer[PATH_MAX];
    const char *exe = (flags & AT_SYMLINK_NOFOLLOW) ? NULL : exe_override(path);
    return real_statx(dirfd, exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), flags,
                      mask, buf);
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

EXPORT int utime(const char *path, const struct utimbuf *times) {
    typedef int (*fn_t)(const char *, const struct utimbuf *);
    RESOLVE(utime, fn_t)
    char buffer[PATH_MAX];
    return real_utime(map_path(path, buffer, sizeof(buffer)), times);
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
    const char *exe = exe_override(path);
    return real_access(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int eaccess(const char *path, int mode) {
    typedef int (*fn_t)(const char *, int);
    RESOLVE(eaccess, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real_eaccess(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int euidaccess(const char *path, int mode) {
    typedef int (*fn_t)(const char *, int);
    RESOLVE(euidaccess, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real_euidaccess(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), mode);
}

EXPORT int faccessat(int dirfd, const char *path, int mode, int flags) {
    typedef int (*fn_t)(int, const char *, int, int);
    RESOLVE(faccessat, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    return real_faccessat(dirfd, exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), mode,
                          flags);
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
    const char *mapped = map_path(path, buffer, sizeof(buffer));
    int hashed = socket_unlink_hashed(mapped);
    int rc = real_unlink(mapped);
    if (rc != 0 && hashed) return 0;
    return rc;
}

EXPORT int unlinkat(int dirfd, const char *path, int flags) {
    typedef int (*fn_t)(int, const char *, int);
    RESOLVE(unlinkat, fn_t)
    char buffer[PATH_MAX];
    const char *mapped = map_path(path, buffer, sizeof(buffer));
    int hashed = (flags & AT_REMOVEDIR) ? 0 : socket_unlink_hashed(mapped);
    int rc = real_unlinkat(dirfd, mapped, flags);
    if (rc != 0 && hashed) return 0;
    return rc;
}

/* remove() is unlink() for files and rmdir() for directories. */
EXPORT int remove(const char *path) {
    typedef int (*fn_t)(const char *);
    RESOLVE(remove, fn_t)
    char buffer[PATH_MAX];
    const char *mapped = map_path(path, buffer, sizeof(buffer));
    int hashed = socket_unlink_hashed(mapped);
    int rc = real_remove(mapped);
    if (rc != 0 && hashed) return 0;
    return rc;
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
    const char *exe = exe_override(path);
    if (exe != NULL) return copy_exe_link(exe, buf, size);
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
    const char *exe = exe_override(path);
    if (exe != NULL) return copy_exe_link(exe, buf, size);
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
    const char *exe = exe_override(path);
    char *result =
        real_realpath(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)), resolved);
    /* The executable override is already the spelling the caller must see. */
    if (result != NULL && exe == NULL) {
        size_t length = strlen(result);
        unmap_buffer(result, &length);
        result[length] = '\0';
    }
    return result;
}

/* The glibc variant allocates with malloc; the result is unmapped in place. */
EXPORT char *canonicalize_file_name(const char *path) {
    typedef char *(*fn_t)(const char *);
    RESOLVE_PTR(canonicalize_file_name, fn_t)
    char buffer[PATH_MAX];
    const char *exe = exe_override(path);
    char *result =
        real_canonicalize_file_name(exe != NULL ? exe : map_path(path, buffer, sizeof(buffer)));
    if (result != NULL && exe == NULL) {
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

/*
 * Watchers are commonly installed on a cache directory that the program only
 * creates later (Vite, Metro, chokidar). Creating the tree first turns a silent
 * ENOENT into a working watcher; the real function then reports the usual
 * errors for a path that cannot be created.
 */
static void create_tree_raw(const char *path) {
    char temp[PATH_MAX];
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(temp)) return;
    memcpy(temp, path, len + 1);
    for (char *p = temp + 1; *p != '\0'; p++) {
        if (*p != '/') continue;
        *p = '\0';
        syscall(SYS_mkdirat, AT_FDCWD, temp, 0700);
        *p = '/';
    }
    syscall(SYS_mkdirat, AT_FDCWD, temp, 0700);
}

EXPORT int inotify_add_watch(int fd, const char *path, uint32_t mask) {
    typedef int (*fn_t)(int, const char *, uint32_t);
    RESOLVE(inotify_add_watch, fn_t)
    char buffer[PATH_MAX];
    const char *mapped = map_path(path, buffer, sizeof(buffer));
    if (mapped != path && !stat_raw(mapped, &(struct stat){0})) create_tree_raw(mapped);
    return real_inotify_add_watch(fd, mapped, mask);
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

/* The 64-bit variants have their own symbols even where the types match. */
EXPORT int ftw64(const char *path, __ftw64_func_t func, int descriptors) {
    typedef int (*fn_t)(const char *, __ftw64_func_t, int);
    RESOLVE(ftw64, fn_t)
    char buffer[PATH_MAX];
    return real_ftw64(map_path(path, buffer, sizeof(buffer)), func, descriptors);
}

EXPORT int nftw64(const char *path, __nftw64_func_t func, int descriptors, int flags) {
    typedef int (*fn_t)(const char *, __nftw64_func_t, int, int);
    RESOLVE(nftw64, fn_t)
    char buffer[PATH_MAX];
    return real_nftw64(map_path(path, buffer, sizeof(buffer)), func, descriptors, flags);
}

EXPORT void *dlopen(const char *file, int flags) {
    typedef void *(*fn_t)(const char *, int);
    RESOLVE_PTR(dlopen, fn_t)
    char buffer[PATH_MAX];
    return real_dlopen(map_path(file, buffer, sizeof(buffer)), flags);
}

EXPORT void *dlmopen(Lmid_t nsid, const char *file, int mode) {
    typedef void *(*fn_t)(Lmid_t, const char *, int);
    RESOLVE_PTR(dlmopen, fn_t)
    char buffer[PATH_MAX];
    return real_dlmopen(nsid, map_path(file, buffer, sizeof(buffer)), mode);
}

/* ---------------------------------------------------------------------- exec */

/*
 * A process started through ld.so keeps the library in LD_PRELOAD, so a glibc
 * child keeps both the path translation and the SIGSYS policy. A bionic or
 * static child never gets the preload: loading a glibc object into it aborts.
 *
 * A downloaded glibc binary whose PT_INTERP does not exist is rewritten into
 * `ld.so --preload ... PROGRAM`, and a script whose interpreter lives in the
 * prefix is exec'd through that interpreter. The route is chosen before the
 * real call because the kernel cannot translate a path or a shebang itself.
 */
#define EXEC_ARG_MAX 1024

struct exec_plan {
    const char *path;      /* NULL means "pass the original path" */
    char **argv;           /* NULL means "pass the original argv" */
    char **env;            /* NULL means "pass the original environment" */
    char *const *env_source;
    size_t env_source_count;
    char mapped[PATH_MAX]; /* backing store for path when nothing is routed */
    struct route route;
};

static void plan_init(struct exec_plan *plan) { memset(plan, 0, sizeof(*plan)); }

static void plan_clear(struct exec_plan *plan) {
    if (plan->env != NULL) free_preload_env(plan->env, plan->env_source, plan->env_source_count);
    route_clear(&plan->route);
    memset(plan, 0, sizeof(*plan));
}

static void build_exec_plan(const char *path, char *const argv[], char *const envp[],
                            const char *search_path, struct exec_plan *plan) {
    plan_init(plan);
    /* A NULL envp means an empty environment, not the one of the caller. */
    char *const *base_env = envp;
    if (path == NULL || path[0] == '\0') return;

    char found[PATH_MAX];
    const char *target = path;
    if (search_path != NULL) {
        if (!find_program_path(path, search_path, found, sizeof(found))) return;
        target = found;
    }
    /*
     * Under the loader, "/proc/self/exe" means the program, not ld.so. Exec the
     * real executable so the route and the preload apply again; the loader
     * would otherwise start the program without this library.
     */
    if (is_self_exe_path(target)) {
        const char *exe = exe_override(target);
        if (exe != NULL) target = exe;
    }

    char buffer[PATH_MAX];
    const char *mapped = map_path(target, buffer, sizeof(buffer));
    if (make_absolute(mapped, plan->mapped, sizeof(plan->mapped)) != 0) return;
    plan->path = plan->mapped;
    plan->env_source = base_env;
    plan->env_source_count = array_count(base_env);

    if (g_exec_route && g_prefix_len > 0 && g_self[0] != '\0' &&
        route_build(plan->mapped, argv, base_env, &plan->route, 0) == 1) {
        plan->path = plan->route.exec_path;
        plan->argv = plan->route.argv;
        /*
         * A loader route already loaded this library with --preload, so the
         * child's LD_PRELOAD stays clean. An interpreter redirect execs a
         * program directly and needs the preload the usual way.
         */
        int via_loader = plan->route.loader != NULL;
        plan->env = preload_env(plan->route.program, base_env, plan->env_source_count,
                                via_loader ? 0 : 1, via_loader ? 1 : 0);
        return;
    }
    plan->env = preload_env(plan->mapped, base_env, plan->env_source_count, 1, 0);
}

EXPORT int execve(const char *path, char *const argv[], char *const envp[]) {
    typedef int (*fn_t)(const char *, char *const[], char *const[]);
    RESOLVE(execve, fn_t)
    char *const *base_env = envp;
    struct exec_plan plan;
    build_exec_plan(path, argv, envp, NULL, &plan);
    int rc = real_execve(plan.path != NULL ? plan.path : path,
                         plan.argv != NULL ? plan.argv : argv,
                         plan.env != NULL ? (char *const *)plan.env : base_env);
    plan_clear(&plan);
    return rc;
}

EXPORT int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

EXPORT int execvp(const char *file, char *const argv[]) {
    return execvpe(file, argv, environ);
}

EXPORT int execvpe(const char *file, char *const argv[], char *const envp[]) {
    typedef int (*fn_t)(const char *, char *const[], char *const[]);
    RESOLVE(execvpe, fn_t)
    char *const *base_env = envp;
    struct exec_plan plan;
    /* glibc's execvpe searches the PATH of the caller, not of the child envp. */
    build_exec_plan(file, argv, base_env, env_value(environ, "PATH"), &plan);
    int rc = real_execvpe(plan.path != NULL ? plan.path : file,
                          plan.argv != NULL ? plan.argv : argv,
                          plan.env != NULL ? (char *const *)plan.env : base_env);
    plan_clear(&plan);
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
    if ((mapped == NULL || mapped[0] == '\0') && (flags & AT_EMPTY_PATH)) {
        /* The executable is a descriptor; /proc/self/fd follows it for detection. */
        snprintf(probe, sizeof(probe), "/proc/self/fd/%d", dirfd);
        target = probe;
    }
    char *const *base_env = envp;
    size_t count = array_count(base_env);
    char **env = preload_env(target, base_env, count, 1, 0);
    int rc = real_execveat(dirfd, mapped, argv,
                           env != NULL ? (char *const *)env : base_env, flags);
    free_preload_env(env, base_env, count);
    return rc;
}

EXPORT int fexecve(int fd, char *const argv[], char *const envp[]) {
    typedef int (*fn_t)(int, char *const[], char *const[]);
    RESOLVE(fexecve, fn_t)
    char probe[64];
    snprintf(probe, sizeof(probe), "/proc/self/fd/%d", fd);
    char *const *base_env = envp;
    size_t count = array_count(base_env);
    char **env = preload_env(probe, base_env, count, 1, 0);
    int rc = real_fexecve(fd, argv, env != NULL ? (char *const *)env : base_env);
    free_preload_env(env, base_env, count);
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
    char *const *base_env = envp;
    struct exec_plan plan;
    build_exec_plan(path, argv, envp, NULL, &plan);
    int rc = real_posix_spawn(pid, plan.path != NULL ? plan.path : path, actions, attr,
                              plan.argv != NULL ? plan.argv : argv,
                              plan.env != NULL ? (char *const *)plan.env : base_env);
    plan_clear(&plan);
    return rc;
}

EXPORT int posix_spawnp(pid_t *pid, const char *file,
                        const posix_spawn_file_actions_t *actions,
                        const posix_spawnattr_t *attr, char *const argv[],
                        char *const envp[]) {
    typedef int (*fn_t)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
    RESOLVE(posix_spawnp, fn_t)
    char *const *base_env = envp;
    struct exec_plan plan;
    /* posix_spawnp searches PATH of the caller, not of the child environment. */
    build_exec_plan(file, argv, envp, env_value(environ, "PATH"), &plan);
    int rc = real_posix_spawnp(pid, plan.path != NULL ? plan.path : file, actions, attr,
                               plan.argv != NULL ? plan.argv : argv,
                               plan.env != NULL ? (char *const *)plan.env : base_env);
    plan_clear(&plan);
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

/* ------------------------------------------------------- system() and popen() */

/*
 * system() and popen() need a shell for `-c COMMAND`. /bin/sh is deliberately
 * left as the bionic shell of Android, so a child started through it is not
 * glibc and never sees the path translation or the preload. When the prefix has
 * a glibc shell, these wrappers build the child themselves with posix_spawn():
 * the shell and every command it starts keep LD_PRELOAD and the two OC_* roots.
 * The bionic shell is still the fallback when no glibc shell exists, and
 * OC_SYSTEM=0 keeps the old behaviour. system() keeps the signal contract of
 * POSIX: SIGCHLD is blocked, SIGINT/SIGQUIT are ignored in the parent and reset
 * to default in the child.
 */
static int glibc_shell(char *out, size_t size) {
    if (g_prefix_len == 0) return 0;
    static const char *const names[] = {"bin/sh", "bin/bash"};
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        char path[PATH_MAX];
        if (join_path(path, sizeof(path), g_prefix, names[i]) != 0) continue;
        if (!executable_file(path)) continue;
        if (!file_is_glibc(path)) continue;
        if (!copy_path(path, out, size)) continue;
        return 1;
    }
    return 0;
}

static int spawn_command(const char *shell, const char *command,
                         const posix_spawn_file_actions_t *actions,
                         const posix_spawnattr_t *attr, pid_t *pid) {
    char *argv[] = {(char *)"sh", (char *)"-c", (char *)command, NULL};
    return posix_spawn(pid, shell, actions, attr, argv, environ);
}

EXPORT int system(const char *command) {
    typedef int (*fn_t)(const char *);
    static fn_t real_system;
    if (real_system == NULL) {
        *(void **)(&real_system) = dlsym(RTLD_NEXT, "system");
        if (real_system == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }
    char shell[PATH_MAX];
    if (!g_system || !glibc_shell(shell, sizeof(shell))) return real_system(command);
    if (command == NULL) return 1;

    struct sigaction ignore;
    struct sigaction old_int;
    struct sigaction old_quit;
    memset(&ignore, 0, sizeof(ignore));
    ignore.sa_handler = SIG_IGN;
    sigemptyset(&ignore.sa_mask);
    if (sigaction(SIGINT, &ignore, &old_int) != 0) return -1;
    if (sigaction(SIGQUIT, &ignore, &old_quit) != 0) {
        (void)sigaction(SIGINT, &old_int, NULL);
        return -1;
    }

    sigset_t block;
    sigset_t old_mask;
    sigemptyset(&block);
    sigaddset(&block, SIGCHLD);
    if (sigprocmask(SIG_BLOCK, &block, &old_mask) != 0) {
        (void)sigaction(SIGINT, &old_int, NULL);
        (void)sigaction(SIGQUIT, &old_quit, NULL);
        return -1;
    }

    posix_spawnattr_t attr;
    int attr_ready = 0;
    int rc = posix_spawnattr_init(&attr);
    if (rc == 0) {
        attr_ready = 1;
        sigset_t defaults;
        sigemptyset(&defaults);
        sigaddset(&defaults, SIGINT);
        sigaddset(&defaults, SIGQUIT);
        sigset_t child_mask = old_mask;
        sigdelset(&child_mask, SIGCHLD);
        rc = posix_spawnattr_setsigdefault(&attr, &defaults);
        if (rc == 0) rc = posix_spawnattr_setsigmask(&attr, &child_mask);
        if (rc == 0) {
            rc = posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSIGDEF |
                                                    POSIX_SPAWN_SETSIGMASK);
        }
    }

    pid_t pid = -1;
    if (rc == 0) rc = spawn_command(shell, command, NULL, &attr, &pid);
    if (attr_ready) (void)posix_spawnattr_destroy(&attr);

    int status = -1;
    int wait_error = 0;
    if (rc == 0) {
        for (;;) {
            pid_t waited = waitpid(pid, &status, 0);
            if (waited == pid) break;
            if (waited < 0 && errno == EINTR) continue;
            wait_error = errno;
            status = -1;
            break;
        }
    }

    (void)sigaction(SIGINT, &old_int, NULL);
    (void)sigaction(SIGQUIT, &old_quit, NULL);
    (void)sigprocmask(SIG_SETMASK, &old_mask, NULL);

    if (rc != 0) {
        /* The shell disappeared between the check and the spawn. */
        if (rc == ENOENT) return real_system(command);
        errno = rc;
        return -1;
    }
    if (wait_error != 0) errno = wait_error;
    return status;
}

#define POPEN_SLOTS 32

struct popen_record {
    FILE *stream;
    pid_t pid;
};

static struct popen_record g_popen_records[POPEN_SLOTS];
static int g_popen_lock;

/*
 * The record table is protected by a spinlock, not a pthread mutex, because it
 * is also touched from a child before it execs. fork() only clones the calling
 * thread: if another thread owned g_popen_lock at that moment, the child would
 * spin forever in the next popen()/pclose(). The atfork child handler drops the
 * lock. The records themselves are inherited unchanged; the child may add its
 * own. add() publishes the stream pointer last, so a fork in the middle of an
 * insert leaves an empty slot (or a pid without a stream), never a stream whose
 * pid is missing.
 */
static void popen_atfork_child(void) {
    __atomic_store_n(&g_popen_lock, 0, __ATOMIC_RELEASE);
}

static int g_popen_atfork_registered;

static void popen_lock_init(void) {
    /* Exchange before registering: a failure is not worth retrying on every
     * popen(), and the lock starts at zero for a process that forks before it. */
    if (__atomic_exchange_n(&g_popen_atfork_registered, 1, __ATOMIC_ACQ_REL) != 0) return;
    (void)pthread_atfork(NULL, NULL, popen_atfork_child);
}

static void popen_lock(void) {
    while (__atomic_exchange_n(&g_popen_lock, 1, __ATOMIC_ACQUIRE)) {
        while (__atomic_load_n(&g_popen_lock, __ATOMIC_RELAXED)) {
        }
    }
}

static void popen_unlock(void) { __atomic_store_n(&g_popen_lock, 0, __ATOMIC_RELEASE); }

static int popen_record_add(FILE *stream, pid_t pid) {
    popen_lock_init();
    popen_lock();
    for (size_t i = 0; i < POPEN_SLOTS; i++) {
        if (g_popen_records[i].stream == NULL) {
            /* Publish the stream last; see the atfork note above. */
            g_popen_records[i].pid = pid;
            __atomic_store_n(&g_popen_records[i].stream, stream, __ATOMIC_RELEASE);
            popen_unlock();
            return 1;
        }
    }
    popen_unlock();
    return 0;
}

static pid_t popen_record_take(FILE *stream) {
    pid_t pid = 0;
    popen_lock_init();
    popen_lock();
    for (size_t i = 0; i < POPEN_SLOTS; i++) {
        if (g_popen_records[i].stream == stream) {
            pid = g_popen_records[i].pid;
            g_popen_records[i].stream = NULL;
            g_popen_records[i].pid = 0;
            break;
        }
    }
    popen_unlock();
    return pid;
}

EXPORT FILE *popen(const char *command, const char *type) {
    typedef FILE *(*fn_t)(const char *, const char *);
    static fn_t real_popen;
    if (real_popen == NULL) {
        *(void **)(&real_popen) = dlsym(RTLD_NEXT, "popen");
        if (real_popen == NULL) {
            errno = ENOSYS;
            return NULL;
        }
    }
    if (command == NULL || type == NULL) {
        errno = EINVAL;
        return NULL;
    }
    int reading;
    if (type[0] == 'r') {
        reading = 1;
    } else if (type[0] == 'w') {
        reading = 0;
    } else {
        errno = EINVAL;
        return NULL;
    }
    int close_on_exec = 0;
    for (const char *flag = type + 1; *flag != '\0'; flag++) {
        if (*flag == 'e') {
            close_on_exec = 1;
        } else {
            errno = EINVAL;
            return NULL;
        }
    }
    char shell[PATH_MAX];
    if (!g_system || !glibc_shell(shell, sizeof(shell))) return real_popen(command, type);

    int fds[2];
    if (pipe(fds) != 0) return NULL;
    for (size_t i = 0; i < 2; i++) {
        int flags = fcntl(fds[i], F_GETFD);
        if (flags >= 0) (void)fcntl(fds[i], F_SETFD, flags | FD_CLOEXEC);
    }

    posix_spawn_file_actions_t actions;
    int rc = posix_spawn_file_actions_init(&actions);
    if (rc != 0) {
        close(fds[0]);
        close(fds[1]);
        errno = rc;
        return NULL;
    }
    int child_fd = reading ? fds[1] : fds[0];
    int target_fd = reading ? STDOUT_FILENO : STDIN_FILENO;
    if (child_fd == target_fd) {
        /* dup2(fd, fd) does not clear FD_CLOEXEC; a closed stdio slot can
         * make pipe() return the target number itself. */
        int flags = fcntl(child_fd, F_GETFD);
        if (flags >= 0) (void)fcntl(child_fd, F_SETFD, flags & ~FD_CLOEXEC);
    }
    rc = posix_spawn_file_actions_adddup2(&actions, child_fd, target_fd);
    pid_t pid = -1;
    if (rc == 0) rc = spawn_command(shell, command, &actions, NULL, &pid);
    (void)posix_spawn_file_actions_destroy(&actions);
    if (rc != 0) {
        close(fds[0]);
        close(fds[1]);
        errno = rc;
        return NULL;
    }

    int parent_fd = reading ? fds[0] : fds[1];
    close(reading ? fds[1] : fds[0]);
    if (!close_on_exec) {
        int flags = fcntl(parent_fd, F_GETFD);
        if (flags >= 0) (void)fcntl(parent_fd, F_SETFD, flags & ~FD_CLOEXEC);
    }
    FILE *stream = fdopen(parent_fd, reading ? "r" : "w");
    if (stream == NULL) {
        int saved = errno;
        close(parent_fd);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {
        }
        errno = saved;
        return NULL;
    }
    if (!popen_record_add(stream, pid)) {
        (void)fclose(stream);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {
        }
        errno = EMFILE;
        return NULL;
    }
    return stream;
}

EXPORT int pclose(FILE *stream) {
    typedef int (*fn_t)(FILE *);
    static fn_t real_pclose;
    if (real_pclose == NULL) {
        *(void **)(&real_pclose) = dlsym(RTLD_NEXT, "pclose");
        if (real_pclose == NULL) {
            errno = ENOSYS;
            return -1;
        }
    }
    pid_t pid = popen_record_take(stream);
    if (pid == 0) return real_pclose(stream);

    int closed = fclose(stream);
    int saved = closed == 0 ? 0 : errno;
    int status = 0;
    for (;;) {
        pid_t waited = waitpid(pid, &status, 0);
        if (waited == pid) break;
        if (waited < 0 && errno == EINTR) continue;
        return -1;
    }
    if (closed != 0) {
        errno = saved;
        return -1;
    }
    return status;
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

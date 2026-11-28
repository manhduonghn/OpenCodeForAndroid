/*
 * liboc_exec.so -- W^X exec shim for OpenCode for Android (targetSdk 36).
 *
 * Android 10+ denies execve() of files labelled app_data_file for apps that
 * target API 29 or newer. The app still has to run programs it downloaded into
 * its own prefix. Like termux-exec, this library is injected with LD_PRELOAD
 * into every bionic process of the runtime and rewrites exec calls:
 *
 *   - bionic ELF / shebang script in the app prefix:
 *       execve("/system/bin/linker64", [linker, binary, args...], env)
 *     The kernel only ever sees the system linker being executed; the linker
 *     mmaps the app file, which is allowed (execute, but not execute_no_trans).
 *
 *   - glibc ELF (the OpenCode CLI and friends):
 *       execve(<APK native lib dir>/liboc_ld.so,
 *              [ld.so, --library-path ..., --preload liboc-pathmap.so, binary, args...])
 *     Android's linker cannot load glibc's ld-linux (no PT_PHDR), so the loader
 *     itself is shipped inside the APK: nativeLibraryDir files are labelled
 *     apk_data_file and remain executable.
 *
 * The bionic shim must never be loaded into a glibc process. For the glibc
 * branch LD_PRELOAD and LD_LIBRARY_PATH are removed from the child environment;
 * liboc-pathmap.so is passed with the loader's own --preload option instead.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdbool.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

extern char **environ;

#if UINTPTR_MAX != 0xffffffffffffffff
#error "oc-exec supports only 64-bit Android"
#endif

#define SYSTEM_LINKER "/system/bin/linker64"
#define ELF_NATIVE_CLASS ELFCLASS64

#ifdef __aarch64__
#define EM_NATIVE EM_AARCH64
#elif defined(__x86_64__)
#define EM_NATIVE EM_X86_64
#else
#error "oc-exec supports only arm64 and x86_64"
#endif

#define DEFAULT_PREFIX "/data/data/com.opencode.android/files/usr"
#define DEFAULT_HOME "/data/data/com.opencode.android/files/home"
#define INTERP_MAX 512

static const char *env_default(const char *name, const char *fallback) {
    const char *v = getenv(name);
    return (v != NULL && v[0] != '\0') ? v : fallback;
}

static bool starts_with(const char *s, const char *prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

static bool is_file(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

static int count_ptrs(char *const *a) {
    int n = 0;
    while (a != NULL && a[n] != NULL) n++;
    return n;
}

/* Every heap object of a route lives in one list, so a failed exec leaks nothing. */
struct allocation {
    struct allocation *next;
    void *ptr;
};

static void keep(struct allocation **list, void *ptr) {
    if (ptr == NULL) return;
    struct allocation *item = malloc(sizeof(*item));
    if (item == NULL) { free(ptr); return; }
    item->ptr = ptr;
    item->next = *list;
    *list = item;
}

static void release(struct allocation **list) {
    while (*list != NULL) {
        struct allocation *next = (*list)->next;
        free((*list)->ptr);
        free(*list);
        *list = next;
    }
}

/* Address inside this .so; dladdr gives its apk_data_file path even when the
 * caller cleared OC_EXEC_SHIM or LD_PRELOAD before reaching an exec call. */
static void shim_address_marker(void) {}

static const char *shim_path(void) {
    static char path[PATH_MAX];
    if (path[0] != '\0') return path;
    Dl_info info;
    if (dladdr((void *)shim_address_marker, &info) != 0 && info.dli_fname != NULL &&
        info.dli_fname[0] == '/' && is_file(info.dli_fname)) {
        snprintf(path, sizeof(path), "%s", info.dli_fname);
    }
    if (path[0] == '\0') {
        /* bionic may report only a library name; LD_PRELOAD needs a real path. */
        const char *fallback = getenv("OC_EXEC_SHIM");
        if (fallback != NULL && fallback[0] == '/' && is_file(fallback)) {
            snprintf(path, sizeof(path), "%s", fallback);
        }
    }
    return path[0] != '\0' ? path : NULL;
}

/* The W^X restriction only applies to the writable app data tree. */
static bool needs_linker_trampoline(const char *path) {
    if (path == NULL || path[0] != '/') return false;
    if (starts_with(path, "/system/") || starts_with(path, "/apex/") ||
        starts_with(path, "/vendor/") || starts_with(path, "/proc/")) return false;

    const char *prefix = env_default("OC_PREFIX", env_default("PREFIX", DEFAULT_PREFIX));
    const char *home = env_default("OC_USER_HOME", env_default("HOME", DEFAULT_HOME));
    if (starts_with(path, prefix) || starts_with(path, home)) return true;
    if (starts_with(path, "/data/data/") || starts_with(path, "/data/user/")) {
        /* Files extracted by the system under /data/app/.../lib are apk_data_file. */
        return !strstr(path, "/data/app/");
    }
    return false;
}

/* Android's linker cannot load ld-linux: it has no PT_PHDR. */
static bool is_glibc_loader(const char *path) {
    if (path == NULL) return false;
    const char *apk_loader = getenv("OC_GLIBC_LOADER");
    if (apk_loader != NULL && strcmp(path, apk_loader) == 0) return true;
    const char *name = strrchr(path, '/');
    name = (name == NULL) ? path : name + 1;
    return starts_with(name, "ld-linux") || starts_with(name, "ld.so") ||
        strcmp(name, "liboc_ld.so") == 0 || strstr(path, "/glibc/lib/") != NULL;
}

enum target_kind {
    TARGET_PLAIN = 0,
    TARGET_BIONIC,   /* route through /system/bin/linker64 */
    TARGET_GLIBC,    /* route through the APK's ld-linux copy */
    TARGET_SCRIPT,
};

struct target_info {
    enum target_kind kind;
    char interpreter[INTERP_MAX];
    char interp_arg[INTERP_MAX];
};

static void inspect_elf(int fd, struct target_info *info) {
    Elf64_Ehdr eh;
    if (pread(fd, &eh, sizeof(eh), 0) != (ssize_t)sizeof(eh)) return;
    if (memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0 || eh.e_ident[EI_CLASS] != ELF_NATIVE_CLASS ||
        eh.e_machine != EM_NATIVE || eh.e_phentsize != sizeof(Elf64_Phdr) || eh.e_phnum == 0) return;

    Elf64_Phdr *phdr = calloc(eh.e_phnum, sizeof(Elf64_Phdr));
    if (phdr == NULL) return;
    if (pread(fd, phdr, (size_t)eh.e_phnum * sizeof(Elf64_Phdr), eh.e_phoff) <= 0) {
        free(phdr);
        return;
    }
    for (size_t i = 0; i < eh.e_phnum; i++) {
        if (phdr[i].p_type != PT_INTERP) continue;
        size_t len = phdr[i].p_filesz;
        if (len > 0 && len < sizeof(info->interpreter) &&
            pread(fd, info->interpreter, len, phdr[i].p_offset) == (ssize_t)len) {
            info->interpreter[len] = '\0';
            info->kind = is_glibc_loader(info->interpreter) ? TARGET_GLIBC : TARGET_BIONIC;
        }
        break;
    }
    /* ET_DYN without PT_INTERP can still be loaded by the system linker. */
    if (info->kind == TARGET_PLAIN && eh.e_type == ET_DYN) info->kind = TARGET_BIONIC;
    free(phdr);
}

static void inspect_header(const char *path, struct target_info *info) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    char head[1024];
    ssize_t got = read(fd, head, sizeof(head));
    if (got >= (ssize_t)sizeof(Elf64_Ehdr) && memcmp(head, ELFMAG, SELFMAG) == 0) {
        inspect_elf(fd, info);
    } else if (got > 2 && head[0] == '#' && head[1] == '!') {
        ssize_t end = 0;
        while (end < got && head[end] != '\n') end++;
        if (end > 2 && end < (ssize_t)sizeof(info->interpreter)) {
            char line[INTERP_MAX];
            memcpy(line, head + 2, (size_t)(end - 2));
            line[end - 2] = '\0';
            char *arg = strpbrk(line, " \t");
            if (arg != NULL) {
                *arg++ = '\0';
                while (*arg == ' ' || *arg == '\t') arg++;
                snprintf(info->interp_arg, sizeof(info->interp_arg), "%s", arg);
            }
            const char *interp = line;
            while (*interp == ' ' || *interp == '\t') interp++;
            /* Bionic has no /bin or /usr/bin; map those to the app prefix. */
            if (starts_with(interp, "/bin/") || starts_with(interp, "/usr/bin/")) {
                const char *prefix = env_default("OC_PREFIX", env_default("PREFIX", DEFAULT_PREFIX));
                snprintf(info->interpreter, sizeof(info->interpreter), "%s/bin/%s", prefix,
                         starts_with(interp, "/usr/bin/") ? interp + 9 : interp + 5);
            } else {
                snprintf(info->interpreter, sizeof(info->interpreter), "%s", interp);
            }
            info->kind = is_glibc_loader(info->interpreter) ? TARGET_GLIBC : TARGET_SCRIPT;
        }
    }
    close(fd);
}

struct route {
    const char *executable;
    char **argv;
    char **envp;
    struct allocation *allocs;
    bool ok;
};

/*
 * Copy envp. glibc children drop LD_PRELOAD/LD_LIBRARY_PATH (the path-map
 * preload is passed to ld.so with --preload). Every other child gets this
 * shim re-inserted, so even a wrapper that cleared LD_PRELOAD keeps the W^X
 * routing in the next process.
 */
static char **prepare_env(struct allocation **allocs, char *const *envp,
                          const char *real_exe, bool strip_loader_env, bool ensure_shim) {
    int n = count_ptrs(envp);
    char **out = calloc((size_t)n + 4, sizeof(char *));
    keep(allocs, out);
    if (out == NULL) return NULL;

    const char *shim = ensure_shim ? shim_path() : NULL;
    int k = 0;
    for (int i = 0; i < n; i++) {
        if (strip_loader_env && (starts_with(envp[i], "LD_PRELOAD=") ||
                                 starts_with(envp[i], "LD_LIBRARY_PATH="))) continue;
        if (ensure_shim && shim != NULL && starts_with(envp[i], "LD_PRELOAD=")) continue;
        if (starts_with(envp[i], "OC_REAL_EXE=")) continue;
        out[k++] = envp[i];
    }
    if (shim != NULL) {
        size_t len = strlen("LD_PRELOAD=") + strlen(shim) + 1;
        char *entry = malloc(len);
        if (entry == NULL) return NULL;
        snprintf(entry, len, "LD_PRELOAD=%s", shim);
        keep(allocs, entry);
        out[k++] = entry;
    }
    if (real_exe != NULL) {
        size_t len = strlen("OC_REAL_EXE=") + strlen(real_exe) + 1;
        char *entry = malloc(len);
        if (entry == NULL) return NULL;
        snprintf(entry, len, "OC_REAL_EXE=%s", real_exe);
        keep(allocs, entry);
        out[k++] = entry;
    }
    out[k] = NULL;
    return out;
}

/* Plain exec of a system/APK file: clear the bionic preload only for glibc. */
static struct route plain_route(const char *path, char *const argv[], char *const envp[], bool glibc) {
    struct route r = {path, (char **)argv, (char **)envp, NULL, true};
    if (glibc) {
        r.envp = prepare_env(&r.allocs, envp, NULL, true, false);
        if (r.envp == NULL) r.ok = false;
    } else {
        r.envp = prepare_env(&r.allocs, envp, NULL, false, true);
        if (r.envp == NULL) r.ok = false;
    }
    return r;
}

/*
 * Build the trampoline command. `depth` guards against a rewritten interpreter
 * that is itself a script with a shebang. `extra_arg` is the shebang argument
 * of a script, placed after the interpreter program.
 */
static struct route prepare_route(const char *path, char *const argv[], char *const envp[],
                                  int depth, const char *extra_arg) {
    struct route r = {path, (char **)argv, (char **)envp, NULL, false};
    if (path == NULL || argv == NULL || envp == NULL || depth > 2) {
        errno = EFAULT;
        return r;
    }
    if (getenv("OC_EXEC_DISABLE") != NULL) {
        r.ok = true;
        return r;
    }

    char resolved[PATH_MAX];
    const char *target = realpath(path, resolved) != NULL ? resolved : path;

    /* The glibc loader lives in nativeLibraryDir, outside the app-data tree. */
    if (is_glibc_loader(path) || is_glibc_loader(target)) {
        return plain_route(path, argv, envp, true);
    }

    if (!needs_linker_trampoline(path) && !needs_linker_trampoline(target)) {
        return plain_route(path, argv, envp, false);
    }

    struct target_info info = {0};
    inspect_header(target, &info);

    if (info.kind == TARGET_PLAIN) {
        /* Static ELF ET_EXEC cannot be loaded by Android's linker; run it as-is. */
        return plain_route(path, argv, envp, false);
    }

    if (info.kind == TARGET_SCRIPT) {
        /* interpreter [shebang-arg] script [args...]. The interpreter becomes
         * argv[0] here so that the inner route keeps the script as its first
         * real argument (the linker trampoline rewrites argv[0] again). */
        int argc = count_ptrs(argv);
        char **next = calloc((size_t)argc + 2, sizeof(char *));
        keep(&r.allocs, next);
        if (next == NULL) { errno = ENOMEM; return r; }
        /* The kernel passes the script's path to its interpreter; argv[0] is only
         * the command name ("opencode") when the caller resolved it via PATH. */
        char *script = strdup(path);
        keep(&r.allocs, script);
        if (script == NULL) { errno = ENOMEM; return r; }
        int k = 0;
        next[k++] = (char *)info.interpreter;
        next[k++] = script;
        for (int i = 1; i < argc; i++) next[k++] = argv[i];
        next[k] = NULL;

        struct route inner = prepare_route(info.interpreter, next, envp, depth + 1,
                                           info.interp_arg[0] != '\0' ? info.interp_arg : NULL);
        /* Keep the script's allocations alive until the exec returns. */
        struct allocation *tail = r.allocs;
        while (tail != NULL && tail->next != NULL) tail = tail->next;
        if (tail != NULL) tail->next = inner.allocs;
        else r.allocs = inner.allocs;
        inner.allocs = r.allocs;
        return inner;
    }

    const char *prefix = env_default("OC_PREFIX", env_default("PREFIX", DEFAULT_PREFIX));
    bool glibc = (info.kind == TARGET_GLIBC || is_glibc_loader(target));
    int argc = count_ptrs(argv);
    char **next = calloc((size_t)argc + 8, sizeof(char *));
    keep(&r.allocs, next);
    if (next == NULL) { errno = ENOMEM; return r; }
    int k = 0;
    if (glibc) {
        const char *loader = getenv("OC_GLIBC_LOADER");
        if (loader == NULL || !is_file(loader)) { errno = ENOENT; return r; }
        const char *glibc_dir = env_default("OC_GLIBC_LIB_DIR", NULL);
        char *libpath = NULL;
        if (glibc_dir == NULL) {
            if (asprintf(&libpath, "%s/glibc/lib", prefix) < 0) { errno = ENOMEM; return r; }
        } else {
            libpath = strdup(glibc_dir);
        }
        keep(&r.allocs, libpath);
        if (libpath == NULL) { errno = ENOMEM; return r; }
        char *pathmap = NULL;
        if (asprintf(&pathmap, "%s/lib/liboc-pathmap.so", prefix) < 0) { errno = ENOMEM; return r; }
        keep(&r.allocs, pathmap);

        next[k++] = (char *)loader;
        next[k++] = "--library-path";
        next[k++] = libpath;
        if (is_file(pathmap)) {
            next[k++] = "--preload";
            next[k++] = pathmap;
        }
        next[k++] = (char *)path;
        if (extra_arg != NULL) next[k++] = (char *)extra_arg;
        for (int i = 1; i < argc; i++) next[k++] = argv[i];
        next[k] = NULL;
        /* A bionic .so would abort the glibc loader; pathmap goes via --preload. */
        r.envp = prepare_env(&r.allocs, envp, path, true, false);
        if (r.envp == NULL) return r;
        r.executable = loader;
    } else {
        /* execve() runs linker64, so argv[0] is the linker's own name and the
         * program path must be argv[1]; the linker then uses it as argv[0] of
         * the program. Verified on device with `exec -a` (a one-entry argv
         * makes the linker parse the first real argument as the program). */
        next[k++] = (char *)SYSTEM_LINKER;
        next[k++] = (char *)path;
        if (extra_arg != NULL) next[k++] = (char *)extra_arg;
        for (int i = 1; i < argc; i++) next[k++] = argv[i];
        next[k] = NULL;
        r.envp = prepare_env(&r.allocs, envp, path, false, true);
        if (r.envp == NULL) return r;
        r.executable = SYSTEM_LINKER;
    }
    r.argv = next;
    r.ok = true;
    return r;
}

__attribute__((visibility("default")))
int execve(const char *path, char *const argv[], char *const envp[]) {
    struct route r = prepare_route(path, argv, envp, 0, NULL);
    if (!r.ok) { release(&r.allocs); return -1; }
    int ret = (int)syscall(SYS_execve, r.executable, r.argv, r.envp);
    int saved = errno;
    release(&r.allocs);
    errno = saved;
    return ret;
}

/* Bionic's exec* family ends up in execve() on recent Android, but keep the
 * variants explicit for older releases and for callers that use libc wrappers. */
__attribute__((visibility("default")))
int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

__attribute__((visibility("default")))
int execvp(const char *path, char *const argv[]) { return execvpe(path, argv, environ); }

__attribute__((visibility("default")))
int execvpe(const char *name, char *const argv[], char *const envp[]) {
    if (name[0] == '\0') { errno = ENOENT; return -1; }
    if (strchr(name, '/') != NULL) return execve(name, argv, envp);

    const char *path = getenv("PATH");
    if (path == NULL) path = "/system/bin";
    char *copy = strdup(path);
    if (copy == NULL) { errno = ENOMEM; return -1; }
    char *save = NULL;
    bool saw_eacces = false;
    for (char *dir = strtok_r(copy, ":", &save); dir != NULL; dir = strtok_r(NULL, ":", &save)) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", dir[0] == '\0' ? "." : dir, name);
        execve(candidate, argv, envp);
        switch (errno) {
        case EACCES: saw_eacces = true; break;
        case EISDIR: case ELOOP: case ENAMETOOLONG: case ENOENT: case ENOTDIR: break;
        case ENOEXEC: {
            char *script_argv[count_ptrs(argv) + 3];
            int k = 0;
            script_argv[k++] = argv[0];
            script_argv[k++] = candidate;
            for (int i = 1; argv[i] != NULL; i++) script_argv[k++] = argv[i];
            script_argv[k] = NULL;
            char shell[PATH_MAX];
            snprintf(shell, sizeof(shell), "%s/bin/sh",
                     env_default("OC_PREFIX", env_default("PREFIX", DEFAULT_PREFIX)));
            execve(shell, script_argv, envp);
            free(copy);
            if (saw_eacces) errno = EACCES;
            return -1;
        }
        default: free(copy); if (saw_eacces) errno = EACCES; return -1;
        }
    }
    free(copy);
    if (saw_eacces) errno = EACCES;
    return -1;
}

static int exec_variants(const char *path, const char *arg0, va_list ap, bool use_env, bool search_path) {
    size_t n = 1;
    va_list counter;
    va_copy(counter, ap);
    while (va_arg(counter, char *) != NULL) n++;
    va_end(counter);
    char **argv = calloc(n + 1, sizeof(char *));
    if (argv == NULL) { errno = ENOMEM; return -1; }
    argv[0] = (char *)arg0;
    for (size_t i = 1; i < n; i++) argv[i] = va_arg(ap, char *);
    char **envp = environ;
    if (use_env) {
        (void)va_arg(ap, char *); /* the NULL that terminates the varargs */
        envp = va_arg(ap, char **);
    }
    int ret = search_path ? execvp(path, argv) : execve(path, argv, envp);
    int saved = errno;
    free(argv);
    errno = saved;
    return ret;
}

__attribute__((visibility("default")))
int execl(const char *path, const char *arg0, ...) {
    va_list ap; va_start(ap, arg0);
    int ret = exec_variants(path, arg0, ap, false, false);
    va_end(ap); return ret;
}
__attribute__((visibility("default")))
int execle(const char *path, const char *arg0, ...) {
    va_list ap; va_start(ap, arg0);
    int ret = exec_variants(path, arg0, ap, true, false);
    va_end(ap); return ret;
}
__attribute__((visibility("default")))
int execlp(const char *path, const char *arg0, ...) {
    va_list ap; va_start(ap, arg0);
    int ret = exec_variants(path, arg0, ap, false, true);
    va_end(ap); return ret;
}

__attribute__((visibility("default")))
int fexecve(int fd, char *const argv[], char *const envp[]) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    execve(path, argv, envp);
    if (errno == ENOENT) errno = EBADF;
    return -1;
}

__attribute__((visibility("default")))
int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    static int (*real_spawn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                             const posix_spawnattr_t *, char *const[], char *const[]);
    if (real_spawn == NULL) {
        real_spawn = dlsym(RTLD_NEXT, "posix_spawn");
        if (real_spawn == NULL) { errno = ENOSYS; return -1; }
    }
    struct route r = prepare_route(path, argv, envp, 0, NULL);
    if (!r.ok) { release(&r.allocs); return -1; }
    int ret = real_spawn(pid, r.executable, actions, attr, r.argv, r.envp);
    int saved = errno;
    release(&r.allocs);
    errno = saved;
    return ret;
}

__attribute__((visibility("default")))
int posix_spawnp(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (strchr(path, '/') == NULL) {
        const char *search = getenv("PATH");
        if (search == NULL) search = "/system/bin";
        char *copy = strdup(search);
        if (copy == NULL) { errno = ENOMEM; return -1; }
        char *save = NULL;
        char *found = NULL;
        for (char *dir = strtok_r(copy, ":", &save); dir != NULL; dir = strtok_r(NULL, ":", &save)) {
            char candidate[PATH_MAX];
            snprintf(candidate, sizeof(candidate), "%s/%s", dir[0] == '\0' ? "." : dir, path);
            if (access(candidate, X_OK) == 0) { found = strdup(candidate); break; }
        }
        free(copy);
        if (found == NULL) { errno = ENOENT; return -1; }
        int ret = posix_spawn(pid, found, actions, attr, argv, envp);
        int saved = errno;
        free(found);
        errno = saved;
        return ret;
    }
    return posix_spawn(pid, path, actions, attr, argv, envp);
}

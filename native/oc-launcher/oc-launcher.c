/*
 * Fixed launcher for the OpenCode CLI inside this app's sandbox.
 *
 * This is a bionic (Android) executable extracted from the APK so it can
 * always be started directly. It runs the downloaded OpenCode binary through
 * the glibc loader of the prefix with liboc-pathmap.so preloaded, which gives
 * the process a working /proc/self/exe, path translation and exec routing even
 * when the downloaded binary was not patched (updates and downgrades).
 *
 * The real binary is the symlink $HOME/.opencode/bin/opencode, which points at
 * the currently installed build in $HOME/.opencode/installed/.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define DEFAULT_PREFIX "/data/data/com.opencode.android/files/usr"
#define DEFAULT_HOME "/data/data/com.opencode.android/files/home"

/* Descriptor kept open on the semaphore directory for the short ELF alias. */
#define OC_SEMAPHORE_FD 9

extern char **environ;

/*
 * The relocated libandroid-posix-semaphore.so appends semaphore names to
 * /proc/self/fd/9/sem. Keep a descriptor on the real directory so the alias
 * resolves in the glibc process and in everything it execs. No FD_CLOEXEC is
 * set, so the descriptor survives execve().
 */
static void keep_semaphore_fd(void) {
    const char *dir = getenv("OC_SEMAPHORE_DIR");
    if (dir == NULL || dir[0] == '\0') return;
    int fd = open(dir, O_RDONLY | O_DIRECTORY);
    if (fd < 0) {
        mkdir(dir, 0700);
        fd = open(dir, O_RDONLY | O_DIRECTORY);
    }
    if (fd < 0) return;
    if (fd != OC_SEMAPHORE_FD) {
        if (dup2(fd, OC_SEMAPHORE_FD) < 0) {
            close(fd);
            return;
        }
        close(fd);
    }
}

static void die(const char *fmt, ...) {
    va_list ap;
    fprintf(stderr, "opencode: ");
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    exit(127);
}

static const char *getenv_default(const char *name, const char *fallback) {
    const char *v = getenv(name);
    return (v && v[0]) ? v : fallback;
}

static int is_exec_file(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode) && access(path, X_OK) == 0;
}

static int is_reg_file(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

static char *find_loader(const char *prefix) {
    char dirpath[PATH_MAX];
    snprintf(dirpath, sizeof(dirpath), "%s/glibc/lib", prefix);
    DIR *dir = opendir(dirpath);
    if (!dir) return NULL;
    struct dirent *ent;
    char *found = NULL;
    while ((ent = readdir(dir)) != NULL) {
        const char *name = ent->d_name;
        if (strncmp(name, "ld-linux", 8) != 0) continue;
        if (!strstr(name, ".so.")) continue;
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/%s", dirpath, name);
        if (is_exec_file(path)) {
            found = strdup(path);
            break;
        }
    }
    closedir(dir);
    return found;
}

static int loader_supports_argv0(const char *loader) {
    int fd = open(loader, O_RDONLY);
    if (fd < 0) return 0;
    char buf[4096];
    /* "--argv0" appears early in the option table of loaders that implement it. */
    char tail[7] = {0};
    ssize_t n;
    int found = 0;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        char window[sizeof(buf) + 7];
        memcpy(window, tail, 7);
        memcpy(window + 7, buf, (size_t)n);
        size_t wlen = 7 + (size_t)n;
        for (size_t i = 0; i + 7 <= wlen; i++) {
            if (memcmp(window + i, "--argv0", 7) == 0) { found = 1; break; }
        }
        if (found) break;
        memcpy(tail, window + wlen - 7, 7);
    }
    close(fd);
    return found;
}

static char *join2(const char *a, const char *b) {
    size_t la = strlen(a), lb = strlen(b);
    char *s = malloc(la + lb + 1);
    if (!s) return NULL;
    memcpy(s, a, la);
    memcpy(s + la, b, lb + 1);
    return s;
}

int main(int argc, char **argv) {
    (void)argc;
    const char *prefix = getenv_default("PREFIX", getenv_default("OC_PREFIX", DEFAULT_PREFIX));
    const char *home = getenv_default("HOME", getenv_default("OC_USER_HOME", DEFAULT_HOME));

    char link_path[PATH_MAX];
    snprintf(link_path, sizeof(link_path), "%s/.opencode/bin/opencode", home);

    char real_bin[PATH_MAX];
    if (!realpath(link_path, real_bin)) {
        if (is_reg_file(link_path)) {
            snprintf(real_bin, sizeof(real_bin), "%s", link_path);
        } else {
            die("OpenCode is not installed (%s is missing). Open the app to install it.", link_path);
        }
    }
    if (!is_reg_file(real_bin)) {
        die("The OpenCode binary was not found (%s). Open the app to install it.", real_bin);
    }

    char *loader = find_loader(prefix);
    if (!loader) {
        die("The glibc loader was not found in %s/glibc/lib. Open the app to repair the runtime.", prefix);
    }

    char pathmap[PATH_MAX];
    snprintf(pathmap, sizeof(pathmap), "%s/lib/liboc-pathmap.so", prefix);
    int have_pathmap = is_reg_file(pathmap);

    char libpath[PATH_MAX];
    snprintf(libpath, sizeof(libpath), "%s/lib:%s/glibc/lib", prefix, prefix);

    int want_argv0 = loader_supports_argv0(loader);
    const char *arg0 = (argv && argv[0]) ? argv[0] : "opencode";

    int extra = 0;
    if (have_pathmap) extra += 2; /* --preload path */
    if (want_argv0) extra += 2;   /* --argv0 arg0 */
    extra += 2;                   /* --library-path lp */
    extra += 1;                   /* real_bin */

    int orig_rest = 0;
    if (argv) { for (int i = 1; argv[i]; i++) orig_rest++; }

    int newc = 1 + extra + orig_rest;
    char **newv = calloc((size_t)newc + 1, sizeof(char *));
    if (!newv) die("out of memory");

    int k = 0;
    newv[k++] = loader;
    if (have_pathmap) { newv[k++] = "--preload"; newv[k++] = pathmap; }
    if (want_argv0) { newv[k++] = "--argv0"; newv[k++] = (char *)arg0; }
    newv[k++] = "--library-path";
    newv[k++] = libpath;
    newv[k++] = real_bin;
    if (argv) { for (int i = 1; argv[i]; i++) newv[k++] = argv[i]; }
    newv[k] = NULL;

    int envc = 0;
    while (environ && environ[envc]) envc++;
    char **newe = calloc((size_t)envc + 4, sizeof(char *));
    if (!newe) die("out of memory");
    int j = 0, has_prefix = 0, has_home = 0;
    char *real_exe_entry = join2("OC_REAL_EXE=", real_bin);
    for (int i = 0; i < envc; i++) {
        if (strncmp(environ[i], "OC_REAL_EXE=", 12) == 0) continue;
        if (strncmp(environ[i], "OC_PREFIX=", 10) == 0) has_prefix = 1;
        if (strncmp(environ[i], "OC_USER_HOME=", 13) == 0) has_home = 1;
        newe[j++] = environ[i];
    }
    newe[j++] = real_exe_entry;
    if (!has_prefix) newe[j++] = join2("OC_PREFIX=", prefix);
    if (!has_home) newe[j++] = join2("OC_USER_HOME=", home);
    newe[j] = NULL;

    keep_semaphore_fd();
    execve(loader, newv, newe);
    fprintf(stderr, "opencode: could not start %s: %s\n", loader, strerror(errno));
    return 127;
}

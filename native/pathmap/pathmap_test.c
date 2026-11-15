/*
 * Tests for liboc-pathmap.so. The test is a normal glibc program; the library is
 * preloaded by run-test.sh. OC_PREFIX and OC_USER_HOME point at a temporary tree,
 * so the test can create the real files that the Termux paths must resolve to.
 *
 * The SIGSYS policy is not exercised here: raising SIGSYS by hand has no syscall
 * context. It is covered by the Go fixtures, which make the blocked calls through
 * the patched Syscall6.
 *
 * The exec test runs the test binary again with a clean environment. That is the
 * case where the preload has to be put back by the execve hook; the child asserts
 * that both the marker and the path translation are present.
 *
 * The routing tests start two fixtures that the kernel alone cannot start:
 * a glibc program whose PT_INTERP does not exist (run-test.sh links it that way)
 * and a script whose shebang names /usr/bin/... . The loader fixture checks the
 * /proc/self/exe virtualization when OC_REAL_EXE is absent, and the plain
 * fixture checks the FHS mapping and the cleaned environment of a routed child.
 */

#define _GNU_SOURCE

#include <assert.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <glob.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>
#include <utime.h>

#define TERMUX_PREFIX "/data/data/com.termux/files/usr"
#define TERMUX_HOME "/data/data/com.termux/files/home"

static char g_root[PATH_MAX];
static char g_prefix[PATH_MAX];
static char g_home[PATH_MAX];

static void join(char *out, size_t size, const char *a, const char *b) {
    int n = snprintf(out, size, "%s%s", a, b);
    assert(n > 0 && (size_t)n < size);
}

static void mkdirs(const char *path) {
    char temp[PATH_MAX];
    snprintf(temp, sizeof(temp), "%s", path);
    for (char *p = temp + 1; *p; p++) {
        if (*p != '/') continue;
        *p = '\0';
        mkdir(temp, 0700);
        *p = '/';
    }
    assert(mkdir(temp, 0700) == 0 || errno == EEXIST);
}

static void write_file(const char *path, const char *text) {
    FILE *f = fopen(path, "w");
    assert(f != NULL);
    assert(fputs(text, f) >= 0);
    assert(fclose(f) == 0);
}

static void read_file(const char *path, char *out, size_t size) {
    FILE *f = fopen(path, "r");
    assert(f != NULL);
    size_t n = fread(out, 1, size - 1, f);
    out[n] = '\0';
    fclose(f);
}

/* Create the parent directories of [path] and write a text file there. */
static void write_under(const char *root, const char *relative, const char *text) {
    char path[PATH_MAX];
    char dir[PATH_MAX];
    join(path, sizeof(path), root, relative);
    snprintf(dir, sizeof(dir), "%s", path);
    char *slash = strrchr(dir, '/');
    assert(slash != NULL);
    *slash = '\0';
    mkdirs(dir);
    write_file(path, text);
}

static int env_flag(const char *name) {
    const char *value = getenv(name);
    return value != NULL && value[0] != '\0';
}

static void wait_child(pid_t pid) {
    int status = 0;
    assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status));
    assert(WEXITSTATUS(status) == 0);
}

static int g_walked;

static int walk_cb(const char *path, const struct stat *st, int flag, struct FTW *info) {
    (void)path;
    (void)st;
    (void)flag;
    (void)info;
    g_walked++;
    return 0;
}

static int walk_cb64(const char *path, const struct stat64 *st, int flag, struct FTW *info) {
    (void)path;
    (void)st;
    (void)flag;
    (void)info;
    g_walked++;
    return 0;
}

static int ftw_cb64(const char *path, const struct stat64 *st, int flag) {
    (void)path;
    (void)st;
    (void)flag;
    g_walked++;
    return 0;
}

/* /usr, /etc, /tmp, /var, /root, /dev/shm and /bin are rewritten to the prefix
 * or the home. /bin/sh is the one exception: system() keeps the platform shell. */
static void test_fhs_mapping(void) {
    struct stat st;

    write_under(g_prefix, "/share/pathmap-usr", "usr");
    assert(stat("/usr/share/pathmap-usr", &st) == 0);
    assert(S_ISREG(st.st_mode) && st.st_size == (off_t)strlen("usr"));

    write_under(g_prefix, "/bin/fhs-tool", "bin");
    assert(stat("/usr/bin/fhs-tool", &st) == 0);
    assert(S_ISREG(st.st_mode) && st.st_size == (off_t)strlen("bin"));
    assert(stat("/bin/fhs-tool", &st) == 0);
    assert(S_ISREG(st.st_mode) && st.st_size == (off_t)strlen("bin"));
    assert(access("/usr/bin/fhs-tool", R_OK) == 0);

    write_under(g_prefix, "/etc/pathmap.conf", "etc");
    assert(stat("/etc/pathmap.conf", &st) == 0 && st.st_size == (off_t)strlen("etc"));

    write_under(g_prefix, "/tmp/pathmap-tmp", "tmp");
    assert(stat("/tmp/pathmap-tmp", &st) == 0 && st.st_size == (off_t)strlen("tmp"));

    write_under(g_prefix, "/var/lib/pathmap-var", "var");
    assert(stat("/var/lib/pathmap-var", &st) == 0 && st.st_size == (off_t)strlen("var"));

    write_under(g_home, "/pathmap-root", "root");
    assert(stat("/root/pathmap-root", &st) == 0 && st.st_size == (off_t)strlen("root"));

    write_under(g_prefix, "/tmp/shm/pathmap-shm", "shm");
    assert(stat("/dev/shm/pathmap-shm", &st) == 0 && st.st_size == (off_t)strlen("shm"));

    char prefix_shell[PATH_MAX];
    join(prefix_shell, sizeof(prefix_shell), g_prefix, "/bin/sh");
    write_file(prefix_shell, "prefix-shell");
    struct stat prefix_st;
    assert(stat(prefix_shell, &prefix_st) == 0);
    /* If the platform shell exists it must not be the file we just wrote. */
    if (stat("/bin/sh", &st) == 0) {
        assert(st.st_dev != prefix_st.st_dev || st.st_ino != prefix_st.st_ino);
    }
}

static void test_inotify_autocreate(void) {
    char physical[PATH_MAX];
    join(physical, sizeof(physical), g_prefix, "/tmp/shm/watch/deep");
    struct stat st;
    assert(stat(physical, &st) == -1 && errno == ENOENT);

    int fd = inotify_init1(IN_CLOEXEC);
    assert(fd >= 0);
    /* The directory does not exist; the hook creates it before adding a watch.
     * IN_ISDIR is an event bit and the kernel rejects it in a watch mask. */
    int wd = inotify_add_watch(fd, "/tmp/shm/watch/deep", IN_CREATE);
    assert(wd >= 0);
    assert(stat(physical, &st) == 0 && S_ISDIR(st.st_mode));
    assert(inotify_rm_watch(fd, wd) == 0);
    assert(close(fd) == 0);
}

/* A glibc program whose PT_INTERP is missing is started through ld.so. */
static void test_exec_route(void) {
    if (env_flag("OC_TEST_NO_EXEC")) return;
    const char *program = getenv("OC_TEST_ROUTE_PROGRAM");
    const char *loader = getenv("OC_TEST_LOADER");
    if (program == NULL || loader == NULL) return;

    /* Proof that the routed child did not lose the FHS mapping. */
    write_under(g_prefix, "/bin/oc-route-marker", "route");

    char expect[PATH_MAX + 32];
    snprintf(expect, sizeof(expect), "OC_TEST_EXPECT_EXE=%s", program);
    char argv0_env[PATH_MAX + 32];
    const char *wanted_argv0 = getenv("OC_TEST_EXPECT_ARGV0");
    char *envp[5];
    size_t count = 0;
    envp[count++] = (char *)"OC_TEST_ROUTE_CHILD=1";
    envp[count++] = expect;
    if (wanted_argv0 != NULL) {
        snprintf(argv0_env, sizeof(argv0_env), "OC_TEST_EXPECT_ARGV0=%s", wanted_argv0);
        envp[count++] = argv0_env;
    }
    envp[count] = NULL;

    fflush(NULL);
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        char *const args[] = {(char *)"oc-route-fixture", (char *)"arg1", NULL};
        execve(program, args, envp);
        _exit(127);
    }
    wait_child(pid);
}

/* A loader started with LD_PRELOAD (no OC_REAL_EXE) recovers the target from
 * /proc/self/cmdline for /proc/self/exe. */
static void test_loader_exe(void) {
    if (env_flag("OC_TEST_NO_EXEC")) return;
    const char *program = getenv("OC_TEST_ROUTE_PROGRAM");
    const char *loader = getenv("OC_TEST_LOADER");
    const char *preload = getenv("OC_TEST_PRELOAD");
    if (program == NULL || loader == NULL || preload == NULL) return;

    char expect[PATH_MAX + 32];
    char preload_entry[PATH_MAX + 32];
    char prefix_entry[PATH_MAX + 32];
    char home_entry[PATH_MAX + 32];
    snprintf(expect, sizeof(expect), "OC_TEST_EXPECT_EXE=%s", program);
    snprintf(preload_entry, sizeof(preload_entry), "LD_PRELOAD=%s", preload);
    snprintf(prefix_entry, sizeof(prefix_entry), "OC_PREFIX=%s", g_prefix);
    snprintf(home_entry, sizeof(home_entry), "OC_USER_HOME=%s", g_home);
    char *const envp[] = {
        (char *)"OC_TEST_LOADER_CHILD=1", expect, preload_entry, prefix_entry, home_entry, NULL,
    };

    fflush(NULL);
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        char *const args[] = {(char *)loader, (char *)program, (char *)"arg1", NULL};
        execve(loader, args, envp);
        _exit(127);
    }
    wait_child(pid);
}

/* A script whose shebang names /usr/bin/... must run the prefix interpreter. */
static void test_script_route(void) {
    if (env_flag("OC_TEST_NO_EXEC")) return;
    const char *self = getenv("OC_TEST_SELF");
    if (self == NULL) return;

    char interp[PATH_MAX];
    join(interp, sizeof(interp), g_prefix, "/bin/oc-interp");
    char script[PATH_MAX];
    join(script, sizeof(script), g_prefix, "/bin/oc-route-script");
    write_under(g_prefix, "/bin/oc-route-script", "#!/usr/bin/oc-interp\ninterp-ran\n");
    assert(chmod(script, 0755) == 0);
    struct stat st;
    if (stat(interp, &st) == 0) assert(unlink(interp) == 0);
    assert(symlink(self, interp) == 0);

    char expect[PATH_MAX + 32];
    snprintf(expect, sizeof(expect), "OC_TEST_EXPECT_SCRIPT=%s", script);
    char *const envp[] = {(char *)"OC_TEST_INTERP=1", expect, NULL};

    fflush(NULL);
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        char *const args[] = {(char *)script, (char *)"arg1", NULL};
        execve(script, args, envp);
        _exit(127);
    }
    wait_child(pid);
}

static void test_exec_clean_env(void) {
    /* qemu-user turns /proc/self/exe into the qemu binary, so this case is
     * only meaningful when the test runs natively. */
    if (getenv("OC_TEST_NO_EXEC") != NULL) return;
    char physical[PATH_MAX];
    join(physical, sizeof(physical), g_prefix, "/child-check");
    write_file(physical, "child");

    fflush(NULL);
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        char *const argv[] = {(char *)"/proc/self/exe", (char *)"child", NULL};
        /* No LD_PRELOAD and no OC_* here: the execve hook has to add them. */
        char *const envp[] = {(char *)"OC_TEST_CHILD=1", NULL};
        execve(argv[0], argv, envp);
        _exit(127);
    }
    int status = 0;
    assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status));
    assert(WEXITSTATUS(status) == 0);
}

int main(int argc, char **argv) {
    if (env_flag("OC_TEST_INTERP")) {
        /* Started as the interpreter of a script whose shebang names
         * /usr/bin/oc-interp, which only exists as a link in the prefix. */
        assert(argc >= 2);
        assert(dlsym(RTLD_DEFAULT, "oc_pathmap_active") != NULL);
        /* This exec went straight to the interpreter, so the hook put the
         * preload back, and no routed process left OC_REAL_EXE behind. */
        assert(getenv("LD_PRELOAD") != NULL);
        assert(getenv("OC_REAL_EXE") == NULL);
        const char *script = getenv("OC_TEST_EXPECT_SCRIPT");
        const char *prefix = getenv("OC_PREFIX");
        assert(script != NULL && prefix != NULL);
        assert(strcmp(argv[1], script) == 0);
        char expected_interp[PATH_MAX];
        snprintf(expected_interp, sizeof(expected_interp), "%s/bin/oc-interp", prefix);
        assert(strcmp(argv[0], expected_interp) == 0);
        char line[64];
        FILE *file = fopen(script, "r");
        assert(file != NULL);
        assert(fgets(line, sizeof(line), file) != NULL);
        assert(strcmp(line, "#!/usr/bin/oc-interp\n") == 0);
        assert(fgets(line, sizeof(line), file) != NULL);
        assert(strcmp(line, "interp-ran\n") == 0);
        fclose(file);
        assert(access(TERMUX_PREFIX "/bin/fhs-tool", R_OK) == 0);
        puts("interp: ok");
        return 0;
    }
    if (getenv("OC_TEST_CHILD") != NULL) {
        /* A process started with a clean environment still has the preload. */
        assert(dlsym(RTLD_DEFAULT, "oc_pathmap_active") != NULL);
        assert(getenv("OC_PREFIX") != NULL);
        assert(getenv("OC_USER_HOME") != NULL);
        struct stat st;
        assert(stat(TERMUX_PREFIX "/child-check", &st) == 0);
        assert(S_ISREG(st.st_mode));
        puts("child: ok");
        return 0;
    }

    const char *root = getenv("OC_TEST_ROOT");
    const char *prefix = getenv("OC_PREFIX");
    const char *home = getenv("OC_USER_HOME");
    assert(root != NULL && prefix != NULL && home != NULL);
    snprintf(g_root, sizeof(g_root), "%s", root);
    snprintf(g_prefix, sizeof(g_prefix), "%s", prefix);
    snprintf(g_home, sizeof(g_home), "%s", home);

    mkdirs(g_prefix);
    mkdirs(g_home);

    char physical[PATH_MAX];
    join(physical, sizeof(physical), g_prefix, "/hello");
    write_file(physical, "prefix");

    join(physical, sizeof(physical), g_home, "/hello");
    write_file(physical, "home");

    /* stat / access / fopen go through the map. */
    struct stat st;
    assert(stat(TERMUX_PREFIX "/hello", &st) == 0);
    assert(S_ISREG(st.st_mode));
    assert(access(TERMUX_PREFIX "/hello", R_OK) == 0);
    assert(euidaccess(TERMUX_PREFIX "/hello", R_OK) == 0);
    char text[16];
    read_file(TERMUX_PREFIX "/hello", text, sizeof(text));
    assert(strcmp(text, "prefix") == 0);

    assert(stat(TERMUX_HOME "/hello", &st) == 0);
    join(physical, sizeof(physical), g_home, "/hello");
    assert(st.st_size == (off_t)strlen("home"));

    /* statx is a separate syscall and does not go through stat(). */
    struct statx stx;
    memset(&stx, 0, sizeof(stx));
    assert(statx(AT_FDCWD, TERMUX_PREFIX "/hello", 0, STATX_TYPE | STATX_SIZE, &stx) == 0);
    assert((stx.stx_mask & STATX_TYPE) != 0 && S_ISREG(stx.stx_mode));
    assert((stx.stx_mask & STATX_SIZE) != 0 && stx.stx_size == strlen("prefix"));

    /* The distribution roots and the inotify directory creation. */
    test_fhs_mapping();
    test_inotify_autocreate();

    /* statfs / statvfs take a path too. */
    struct statfs sfs;
    struct statvfs svfs;
    assert(statfs(TERMUX_PREFIX "/hello", &sfs) == 0 && sfs.f_bsize > 0);
    assert(statvfs(TERMUX_PREFIX "/hello", &svfs) == 0 && svfs.f_bsize > 0);

    /* Timestamps. */
    struct timespec times[2] = {{0, 0}, {123456789, 0}};
    assert(utimensat(AT_FDCWD, TERMUX_PREFIX "/hello", times, 0) == 0);
    assert(stat(TERMUX_PREFIX "/hello", &st) == 0);
    assert(st.st_mtim.tv_sec == 123456789);
    struct timeval tv[2] = {{1000, 0}, {2000, 0}};
    assert(utimes(TERMUX_PREFIX "/hello", tv) == 0);
    assert(stat(TERMUX_PREFIX "/hello", &st) == 0);
    assert(st.st_mtime == 2000);
    struct utimbuf ub = {3000, 4000};
    assert(utime(TERMUX_PREFIX "/hello", &ub) == 0);
    assert(stat(TERMUX_PREFIX "/hello", &st) == 0);
    assert(st.st_atime == 3000 && st.st_mtime == 4000);

    /* Extended attributes: any answer other than ENOENT proves the path was
     * translated; the file system of CI may not support them at all. */
    char value[32];
    ssize_t got = getxattr(TERMUX_PREFIX "/hello", "user.pathmap", value, sizeof(value));
    if (got < 0) assert(errno != ENOENT);
    got = listxattr(TERMUX_PREFIX "/hello", value, sizeof(value));
    if (got < 0) assert(errno != ENOENT);
    int set = setxattr(TERMUX_PREFIX "/hello", "user.pathmap", "1", 1, 0);
    if (set == 0) {
        assert(removexattr(TERMUX_PREFIX "/hello", "user.pathmap") == 0);
    } else {
        assert(errno != ENOENT);
    }

    /* A path outside the two roots is passed through unchanged. */
    assert(stat("/proc/self/exe", &st) == 0);
    assert(stat(TERMUX_PREFIX "/does-not-exist", &st) == -1 && errno == ENOENT);

    /* Creating, renaming and removing files. */
    assert(mkdir(TERMUX_PREFIX "/dir", 0700) == 0);
    join(physical, sizeof(physical), g_prefix, "/dir");
    assert(stat(physical, &st) == 0 && S_ISDIR(st.st_mode));

    join(physical, sizeof(physical), g_prefix, "/dir/item");
    write_file(physical, "item");

    /* scandir and glob return entries; glob must also unmap its result. */
    struct dirent **list = NULL;
    int entries = scandir(TERMUX_PREFIX "/dir", &list, NULL, alphasort);
    assert(entries >= 3);
    int found = 0;
    for (int i = 0; i < entries; i++) {
        if (strcmp(list[i]->d_name, "item") == 0) found = 1;
        free(list[i]);
    }
    free(list);
    assert(found);

    glob_t matches;
    memset(&matches, 0, sizeof(matches));
    assert(glob(TERMUX_PREFIX "/dir/*", 0, NULL, &matches) == 0);
    assert(matches.gl_pathc == 1);
    assert(strcmp(matches.gl_pathv[0], TERMUX_PREFIX "/dir/item") == 0);
    globfree(&matches);

    g_walked = 0;
    assert(nftw(TERMUX_PREFIX "/dir", walk_cb, 8, 0) == 0);
    assert(g_walked >= 1);

    /* The 64-bit variants are separate symbols and need the same map. */
    g_walked = 0;
    assert(nftw64(TERMUX_PREFIX "/dir", walk_cb64, 8, 0) == 0);
    assert(g_walked >= 1);
    g_walked = 0;
    assert(ftw64(TERMUX_PREFIX "/dir", ftw_cb64, 8) == 0);
    assert(g_walked >= 1);

    /* mkfifo / mknod reach the real call with the translated path. */
    assert(mkfifo(TERMUX_PREFIX "/fifo", 0600) == 0);
    assert(stat(TERMUX_PREFIX "/fifo", &st) == 0 && S_ISFIFO(st.st_mode));
    assert(unlink(TERMUX_PREFIX "/fifo") == 0);
    assert(mknod(TERMUX_PREFIX "/noded", S_IFREG | 0600, 0) == 0);
    assert(stat(TERMUX_PREFIX "/noded", &st) == 0 && S_ISREG(st.st_mode));
    assert(unlink(TERMUX_PREFIX "/noded") == 0);

    /* dlopen maps an absolute Termux path; run-test.sh puts a plugin there. */
    void *plugin = dlopen(TERMUX_PREFIX "/lib/libpathmap-test-plugin.so", RTLD_NOW);
    assert(plugin != NULL);
    int (*plugin_fn)(void) = (int (*)(void))dlsym(plugin, "oc_pathmap_test_plugin");
    assert(plugin_fn != NULL && plugin_fn() == 42);
    assert(dlclose(plugin) == 0);

    /* dlmopen is a separate symbol: it takes a namespace before the path. */
    void *namespaced =
        dlmopen(LM_ID_NEWLM, TERMUX_PREFIX "/lib/libpathmap-test-plugin.so", RTLD_NOW);
    assert(namespaced != NULL);
    int (*namespaced_fn)(void) = (int (*)(void))dlsym(namespaced, "oc_pathmap_test_plugin");
    assert(namespaced_fn != NULL && namespaced_fn() == 42);
    assert(dlclose(namespaced) == 0);

    /* canonicalize_file_name returns a malloc'd path with the Termux spelling. */
    char *canonical = canonicalize_file_name(TERMUX_PREFIX "/hello");
    assert(canonical != NULL);
    assert(strcmp(canonical, TERMUX_PREFIX "/hello") == 0);
    free(canonical);

    /* remove() has to pick unlink or rmdir on the translated path. */
    join(physical, sizeof(physical), g_prefix, "/remove-me");
    write_file(physical, "x");
    assert(remove(TERMUX_PREFIX "/remove-me") == 0);
    assert(stat(physical, &st) == -1 && errno == ENOENT);

    assert(rename(TERMUX_PREFIX "/hello", TERMUX_PREFIX "/hello2") == 0);
    join(physical, sizeof(physical), g_prefix, "/hello2");
    assert(stat(physical, &st) == 0);
    assert(unlink(TERMUX_PREFIX "/hello2") == 0);
    assert(unlink(TERMUX_PREFIX "/dir/item") == 0);
    assert(rmdir(TERMUX_PREFIX "/dir") == 0);

    /* getcwd shows the Termux spelling, readlink too. */
    assert(chdir(TERMUX_PREFIX) == 0);
    char cwd[PATH_MAX];
    assert(getcwd(cwd, sizeof(cwd)) != NULL);
    assert(strcmp(cwd, TERMUX_PREFIX) == 0);

    join(physical, sizeof(physical), g_prefix, "/link");
    char target[PATH_MAX];
    join(target, sizeof(target), g_prefix, "/target");
    assert(symlink(target, physical) == 0);
    char link[PATH_MAX];
    ssize_t n = readlink(TERMUX_PREFIX "/link", link, sizeof(link) - 1);
    assert(n > 0);
    link[n] = '\0';
    char expected[PATH_MAX];
    join(expected, sizeof(expected), TERMUX_PREFIX, "/target");
    assert(strcmp(link, expected) == 0);
    assert(unlink(TERMUX_PREFIX "/link") == 0);

    assert(chdir(g_root) == 0);

    test_exec_route();
    test_loader_exe();
    test_script_route();
    test_exec_clean_env();

    puts("pathmap: ok");
    return 0;
}

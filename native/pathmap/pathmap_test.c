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
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

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

static int g_walked;

static int walk_cb(const char *path, const struct stat *st, int flag, struct FTW *info) {
    (void)path;
    (void)st;
    (void)flag;
    (void)info;
    g_walked++;
    return 0;
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

int main(void) {
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

    test_exec_clean_env();

    puts("pathmap: ok");
    return 0;
}

/*
 * Tests for liboc-pathmap.so. The test is a normal glibc program; the library is
 * preloaded by run-test.sh. OC_PREFIX and OC_USER_HOME point at a temporary tree,
 * so the test can create the real files that the Termux paths must resolve to.
 *
 * The SIGSYS policy is not exercised here: raising SIGSYS by hand has no syscall
 * context. It is covered by the Go fixtures, which make the blocked calls through
 * the patched Syscall6.
 */

#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
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

int main(void) {
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

    /* A path outside the two roots is passed through unchanged. */
    assert(stat("/proc/self/exe", &st) == 0);
    assert(stat(TERMUX_PREFIX "/does-not-exist", &st) == -1 && errno == ENOENT);

    /* Creating, renaming and removing files. */
    assert(mkdir(TERMUX_PREFIX "/dir", 0700) == 0);
    join(physical, sizeof(physical), g_prefix, "/dir");
    assert(stat(physical, &st) == 0 && S_ISDIR(st.st_mode));
    assert(rename(TERMUX_PREFIX "/hello", TERMUX_PREFIX "/hello2") == 0);
    join(physical, sizeof(physical), g_prefix, "/hello2");
    assert(stat(physical, &st) == 0);
    assert(unlink(TERMUX_PREFIX "/hello2") == 0);
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
    puts("pathmap: ok");
    return 0;
}

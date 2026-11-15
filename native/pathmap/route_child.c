/*
 * Fixture for the exec routing of liboc-pathmap.so. run-test.sh links it with a
 * PT_INTERP that does not exist and a DT_RUNPATH of $ORIGIN/lib, so the kernel
 * alone cannot start it: only the execve hook can rewrite it into
 * `ld.so --preload ... --library-path ... PROGRAM`.
 *
 * Two parents start this program:
 *   - the pathmap test, through execve() with a clean environment (route mode);
 *   - the same test, through ld.so by hand with LD_PRELOAD (loader mode).
 * Both cases must report a correct /proc/self/exe and keep the FHS mapping.
 */

#define _GNU_SOURCE

#include <assert.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* From liboc-route-test.so, found through $ORIGIN/lib. */
int oc_route_test(void);

static const char *required_env(const char *name) {
    const char *value = getenv(name);
    assert(value != NULL && value[0] != '\0');
    return value;
}

int main(int argc, char **argv) {
    assert(argc >= 2);
    assert(strcmp(argv[1], "arg1") == 0);
    assert(oc_route_test() == 7);
    assert(dlsym(RTLD_DEFAULT, "oc_pathmap_active") != NULL);

    const char *expected = required_env("OC_TEST_EXPECT_EXE");
    char link[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", link, sizeof(link) - 1);
    assert(n > 0);
    link[n] = '\0';
    assert(strcmp(link, expected) == 0);

    if (getenv("OC_TEST_ROUTE_CHILD") != NULL) {
        /* The route put the library on the loader command line, so it is not in
         * the environment, and the target of this exec is the fake exe. */
        assert(getenv("LD_PRELOAD") == NULL);
        assert(strcmp(required_env("OC_REAL_EXE"), expected) == 0);
        const char *wanted_argv0 = getenv("OC_TEST_EXPECT_ARGV0");
        if (wanted_argv0 != NULL) {
            assert(strcmp(argv[0], wanted_argv0) == 0);
        } else {
            assert(strcmp(argv[0], expected) == 0);
        }
        struct stat st;
        assert(stat("/usr/bin/oc-route-marker", &st) == 0);
        assert(S_ISREG(st.st_mode));

        /* /proc/self/exe still means the program under the loader: re-exec it
         * and check that the second generation keeps the route and the map. */
        if (getenv("OC_TEST_REEXEC") == NULL) {
            char expect_entry[PATH_MAX + 32];
            snprintf(expect_entry, sizeof(expect_entry), "OC_TEST_EXPECT_EXE=%s", expected);
            char argv0_entry[PATH_MAX + 32];
            char *envp[5];
            size_t count = 0;
            envp[count++] = (char *)"OC_TEST_ROUTE_CHILD=1";
            envp[count++] = (char *)"OC_TEST_REEXEC=1";
            envp[count++] = expect_entry;
            if (wanted_argv0 != NULL) {
                snprintf(argv0_entry, sizeof(argv0_entry), "OC_TEST_EXPECT_ARGV0=%s",
                         wanted_argv0);
                envp[count++] = argv0_entry;
            }
            envp[count] = NULL;
            char *const args[] = {argv[0], (char *)"arg1", NULL};
            execve("/proc/self/exe", args, envp);
            _exit(127);
        }
    } else if (getenv("OC_TEST_LOADER_CHILD") != NULL) {
        /* Started by hand through ld.so: no OC_REAL_EXE, the target comes from
         * /proc/self/cmdline, and the inherited preload stays. */
        assert(getenv("OC_REAL_EXE") == NULL);
        assert(getenv("LD_PRELOAD") != NULL);
        assert(strcmp(argv[0], expected) == 0);
    }

    puts("route child: ok");
    return 0;
}

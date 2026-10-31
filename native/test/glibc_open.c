/*
 * The glibc half of the native tests: a normal dynamically linked program that
 * opens a Termux path. oc-gopatch run starts it through the glibc loader with
 * liboc-pathmap.so preloaded, so the path must resolve inside OC_PREFIX.
 */

#include <errno.h>
#include <stdio.h>
#include <string.h>

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "/data/data/com.termux/files/usr/hello";
    FILE *f = fopen(path, "r");
    if (f == NULL) {
        fprintf(stderr, "open %s: %s\n", path, strerror(errno));
        return 1;
    }
    char buf[64];
    if (fgets(buf, sizeof(buf), f) == NULL) {
        fprintf(stderr, "read %s: %s\n", path, strerror(errno));
        fclose(f);
        return 1;
    }
    fclose(f);
    printf("content: %s", buf);
    return 0;
}

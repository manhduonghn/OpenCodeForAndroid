/*
 * oc-gosys - the small freestanding runtime that is added to a static Go program.
 *
 * Go does not use libc, so LD_PRELOAD cannot help it. Every system call of a Go
 * program goes through internal/runtime/syscall.Syscall6, which loads the number
 * and the six arguments and executes "svc #0". This file is linked as a flat,
 * position-independent binary and copied into a code cave of the program. The
 * instruction at the call site becomes a branch to the thunk below, the thunk
 * calls oc_stub_entry() and jumps back to the instruction after the original
 * "svc #0".
 *
 * The stub does two things:
 *   - it translates /data/data/com.termux/files/usr and .../home to the private
 *     prefix of the app, like liboc-pathmap.so does for glibc programs;
 *   - it answers the syscalls that Android's seccomp filter rejects with the same
 *     policy as the SIGSYS handler: set*id/setgroups/set_robust_list succeed,
 *     getgroups reports one empty group, clone3 and rseq return ENOSYS.
 *
 * It uses no libc, no relocations, no writable static data and no stack frame
 * larger than the thunk + a few registers. The scratch area for rewritten paths
 * is zero-filled memory that the patcher appends to the data segment of the Go
 * program; its address is stored in the header as an offset from the header.
 */

#include <stddef.h>
#include <stdint.h>

#define OC_PREFIX "/data/data/com.opencode.android/files/usr"
#define OC_USER_HOME "/data/data/com.opencode.android/files/home"
#define TERMUX_PREFIX "/data/data/com.termux/files/usr"
#define TERMUX_HOME "/data/data/com.termux/files/home"

#define OC_PREFIX_LEN (sizeof(OC_PREFIX) - 1)
#define OC_HOME_LEN (sizeof(OC_USER_HOME) - 1)
#define TERMUX_PREFIX_LEN (sizeof(TERMUX_PREFIX) - 1)
#define TERMUX_HOME_LEN (sizeof(TERMUX_HOME) - 1)

#define OC_GOSYS_VERSION 1u
#define OC_SLOTS 16u
#define OC_SLOT_SIZE 4096u
#define OC_PATH_CAP 2048u
#define OC_SCRATCH_SIZE (64u + OC_SLOTS * OC_SLOT_SIZE)

struct oc_gosys_hdr {
    char magic[8];        /* "OCGOSYS1" */
    uint32_t version;
    uint32_t reserved;
    int64_t scratch_rel;  /* scratch area, relative to this header */
    uint64_t scratch_size;
};

/*
 * The patcher fills scratch_rel. The object is volatile so the compiler cannot
 * fold the zero initializer into every use; it lives at offset 0 of the blob.
 */
__attribute__((section(".hdr"), used))
const volatile struct oc_gosys_hdr g_hdr = {
    .magic = "OCGOSYS1",
    .version = OC_GOSYS_VERSION,
    .reserved = 0,
    .scratch_rel = 0,
    .scratch_size = OC_SCRATCH_SIZE,
};

/* AArch64 Linux syscall numbers. */
enum {
    NR_GETCWD = 17,
    NR_INOTIFY_ADD_WATCH = 27,
    NR_MKNODAT = 33,
    NR_MKDIRAT = 34,
    NR_UNLINKAT = 35,
    NR_SYMLINKAT = 36,
    NR_LINKAT = 37,
    NR_RENAMEAT = 38,
    NR_STATFS = 43,
    NR_TRUNCATE = 45,
    NR_FACCESSAT = 48,
    NR_CHDIR = 49,
    NR_CHROOT = 51,
    NR_FCHMODAT = 53,
    NR_FCHOWNAT = 54,
    NR_OPENAT = 56,
    NR_READLINKAT = 78,
    NR_NEWFSTATAT = 79,
    NR_UTIMENSAT = 88,
    NR_EXECVE = 221,
    NR_NAME_TO_HANDLE_AT = 264,
    NR_RENAMEAT2 = 276,
    NR_EXECVEAT = 281,
    NR_STATX = 291,
    NR_OPENAT2 = 437,
    NR_FACCESSAT2 = 439,
    NR_SET_ROBUST_LIST = 99,
    NR_SETGID = 144,
    NR_SETREGID = 143,
    NR_SETREUID = 145,
    NR_SETUID = 146,
    NR_SETRESUID = 147,
    NR_SETRESGID = 149,
    NR_SETFSUID = 151,
    NR_SETFSGID = 152,
    NR_SETGROUPS = 159,
    NR_GETGROUPS = 158,
    NR_RSEQ = 293,
    NR_CLONE3 = 435,
};

/*
 * The two words that Linux returns for a system call. Keeping both matters:
 * Go's Syscall6 publishes x0 and x1 as r1 and r2, and a few callers look at r2.
 * A struct of two 64-bit fields is returned in x0/x1 by the AArch64 C ABI.
 */
struct oc_result {
    int64_t r0;
    int64_t r1;
};

static inline struct oc_result sc6(int64_t nr, int64_t a0, int64_t a1, int64_t a2, int64_t a3,
                                   int64_t a4, int64_t a5) {
    register int64_t x8 __asm__("x8") = nr;
    register int64_t x0 __asm__("x0") = a0;
    register int64_t x1 __asm__("x1") = a1;
    register int64_t x2 __asm__("x2") = a2;
    register int64_t x3 __asm__("x3") = a3;
    register int64_t x4 __asm__("x4") = a4;
    register int64_t x5 __asm__("x5") = a5;
    __asm__ volatile("svc #0"
                     : "+r"(x0), "+r"(x1)
                     : "r"(x8), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                     : "memory", "cc");
    struct oc_result result = { x0, x1 };
    return result;
}

static inline char *scratch_base(void) {
    const volatile struct oc_gosys_hdr *h = &g_hdr;
    return (char *)h + h->scratch_rel;
}

static inline uint32_t *slot_state(void) {
    return (uint32_t *)scratch_base();
}

static inline char *slot_path(int slot, int which) {
    return scratch_base() + 64 + (uint64_t)slot * OC_SLOT_SIZE + (uint64_t)which * OC_PATH_CAP;
}

/* Take a slot without sleeping; every slot is a pair of path buffers. */
static int slot_take(void) {
    uint32_t *state = slot_state();
    for (uint32_t i = 0; i < OC_SLOTS; i++) {
        uint32_t expected = 0;
        if (__atomic_compare_exchange_n(&state[i], &expected, 1u, 0,
                                        __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) {
            return (int)i;
        }
    }
    /* All slots are in a syscall. Wait a little, then run without a mapping
     * instead of touching a buffer that another goroutine owns. */
    for (uint32_t spin = 0; spin < (1u << 18); spin++) {
        for (uint32_t i = 0; i < OC_SLOTS; i++) {
            uint32_t expected = 0;
            if (__atomic_compare_exchange_n(&state[i], &expected, 1u, 0,
                                            __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) {
                return (int)i;
            }
        }
        __asm__ volatile("yield" ::: "memory");
    }
    return -1;
}

static void slot_give(int slot) {
    if (slot >= 0) __atomic_store_n(&slot_state()[slot], 0u, __ATOMIC_RELEASE);
}

static int prefix_match(const char *p, const char *lit, uint64_t n) {
    for (uint64_t i = 0; i < n; i++) {
        if (p[i] != lit[i]) return 0;
    }
    return p[n] == '/' || p[n] == '\0';
}

static uint64_t str_len(const char *s) {
    uint64_t n = 0;
    while (s[n]) n++;
    return n;
}

static void bytes_copy(char *d, const char *s, uint64_t n) {
    for (uint64_t i = 0; i < n; i++) d[i] = s[i];
}

/*
 * Translate one absolute path into [buf]. Returns [path] when no translation is
 * needed, so a caller can pass the original pointer to the kernel.
 */
static const char *map_path(const char *path, char *buf) {
    if (path == NULL || buf == NULL || path[0] != '/') return path;

    const char *replacement;
    uint64_t old_len;
    if (prefix_match(path, TERMUX_PREFIX, TERMUX_PREFIX_LEN)) {
        replacement = OC_PREFIX;
        old_len = TERMUX_PREFIX_LEN;
    } else if (prefix_match(path, TERMUX_HOME, TERMUX_HOME_LEN)) {
        replacement = OC_USER_HOME;
        old_len = TERMUX_HOME_LEN;
    } else {
        return path;
    }

    uint64_t new_len = old_len == TERMUX_PREFIX_LEN ? OC_PREFIX_LEN : OC_HOME_LEN;
    uint64_t suffix_len = str_len(path + old_len);
    if (new_len + suffix_len + 1 > OC_PATH_CAP) return path;
    bytes_copy(buf, replacement, new_len);
    bytes_copy(buf + new_len, path + old_len, suffix_len + 1);
    return buf;
}

/* Show a path reported by the kernel with the spelling the program expects. */
static void unmap_buffer(char *buf, int64_t *length) {
    if (buf == NULL || length == NULL || *length <= 0) return;
    uint64_t n = (uint64_t)*length;
    const char *replacement;
    uint64_t old_len;
    uint64_t new_len;
    if (n >= OC_PREFIX_LEN && prefix_match(buf, OC_PREFIX, OC_PREFIX_LEN)) {
        replacement = TERMUX_PREFIX;
        old_len = OC_PREFIX_LEN;
        new_len = TERMUX_PREFIX_LEN;
    } else if (n >= OC_HOME_LEN && prefix_match(buf, OC_USER_HOME, OC_HOME_LEN)) {
        replacement = TERMUX_HOME;
        old_len = OC_HOME_LEN;
        new_len = TERMUX_HOME_LEN;
    } else {
        return;
    }
    if (new_len > old_len) return;
    for (uint64_t i = 0; i + old_len < n; i++) buf[new_len + i] = buf[old_len + i];
    bytes_copy(buf, replacement, new_len);
    *length = (int64_t)(n - old_len + new_len);
}

/* The calls that Android seccomp blocks. Returns 1 when [nr] was handled. */
static int policy(int64_t nr, int64_t a0, int64_t a1, struct oc_result *result) {
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
            result->r0 = 0;
            result->r1 = 0;
            return 1;
        case NR_GETGROUPS:
            if (a0 > 0 && a1 != 0) *(uint32_t *)a1 = 0;
            result->r0 = 1;
            result->r1 = 0;
            return 1;
        case NR_CLONE3:
        case NR_RSEQ:
            result->r0 = -38; /* -ENOSYS */
            result->r1 = 0;
            return 1;
        default:
            return 0;
    }
}

/*
 * Entry point. The thunk copies x8 (the syscall number) to x6, the seventh
 * integer argument register of the AArch64 C ABI, and leaves the six syscall
 * arguments in x0-x5.
 */
__attribute__((section(".text.oc_stub_entry"), used, noinline))
struct oc_result oc_stub_entry(int64_t a0, int64_t a1, int64_t a2, int64_t a3, int64_t a4,
                               int64_t a5, int64_t nr) {
    struct oc_result result;
    if (policy(nr, a0, a1, &result)) return result;

    switch (nr) {
        case NR_GETCWD: {
            result = sc6(nr, a0, a1, 0, 0, 0, 0);
            if (result.r0 > 0 && a0 != 0) {
                int64_t length = str_len((char *)a0) + 1;
                unmap_buffer((char *)a0, &length);
                result.r0 = length;
            }
            return result;
        }
        case NR_READLINKAT: {
            int slot = slot_take();
            const char *path = slot < 0 ? (const char *)a1 : map_path((const char *)a1, slot_path(slot, 0));
            result = sc6(nr, a0, (int64_t)path, a2, a3, 0, 0);
            if (result.r0 > 0 && a2 != 0) {
                int64_t length = result.r0;
                unmap_buffer((char *)a2, &length);
                result.r0 = length;
            }
            slot_give(slot);
            return result;
        }
        case NR_OPENAT:
        case NR_OPENAT2:
        case NR_NEWFSTATAT:
        case NR_FACCESSAT:
        case NR_FACCESSAT2:
        case NR_STATX:
        case NR_MKDIRAT:
        case NR_UNLINKAT:
        case NR_FCHMODAT:
        case NR_FCHOWNAT:
        case NR_UTIMENSAT:
        case NR_MKNODAT:
        case NR_NAME_TO_HANDLE_AT:
        case NR_EXECVEAT: {
            int slot = slot_take();
            const char *path = slot < 0 ? (const char *)a1 : map_path((const char *)a1, slot_path(slot, 0));
            result = sc6(nr, a0, (int64_t)path, a2, a3, a4, a5);
            slot_give(slot);
            return result;
        }
        case NR_CHDIR:
        case NR_CHROOT:
        case NR_TRUNCATE:
        case NR_STATFS:
        case NR_EXECVE: {
            int slot = slot_take();
            const char *path = slot < 0 ? (const char *)a0 : map_path((const char *)a0, slot_path(slot, 0));
            result = sc6(nr, (int64_t)path, a1, a2, a3, a4, a5);
            slot_give(slot);
            return result;
        }
        case NR_INOTIFY_ADD_WATCH: {
            int slot = slot_take();
            const char *path = slot < 0 ? (const char *)a1 : map_path((const char *)a1, slot_path(slot, 0));
            result = sc6(nr, a0, (int64_t)path, a2, a3, a4, a5);
            slot_give(slot);
            return result;
        }
        case NR_SYMLINKAT: {
            int slot = slot_take();
            const char *target = slot < 0 ? (const char *)a0 : map_path((const char *)a0, slot_path(slot, 0));
            const char *link_path = slot < 0 ? (const char *)a2 : map_path((const char *)a2, slot_path(slot, 1));
            result = sc6(nr, (int64_t)target, a1, (int64_t)link_path, a3, a4, a5);
            slot_give(slot);
            return result;
        }
        case NR_LINKAT:
        case NR_RENAMEAT:
        case NR_RENAMEAT2: {
            int slot = slot_take();
            const char *old_path = slot < 0 ? (const char *)a1 : map_path((const char *)a1, slot_path(slot, 0));
            const char *new_path = slot < 0 ? (const char *)a3 : map_path((const char *)a3, slot_path(slot, 1));
            result = sc6(nr, a0, (int64_t)old_path, a2, (int64_t)new_path, a4, a5);
            slot_give(slot);
            return result;
        }
        default:
            return sc6(nr, a0, a1, a2, a3, a4, a5);
    }
}

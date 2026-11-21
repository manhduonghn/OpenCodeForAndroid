/*
 * Pure C, app-built replacement for the CLI's embedded `opencode-pty` helper.
 *
 * It speaks the wire protocol used by OpenCode's web terminal (protocol 9):
 * a 4-byte big-endian JSON frame length, a `{"token":...,"request":...}`
 * envelope, JSON responses and tag-0 binary output frames. The daemon is a
 * bionic executable, so no glibc loader, path-map preload or per-version asset
 * carving is needed on Android.
 */
#define _GNU_SOURCE
#include "pty_core.h"
#include "screen.h"
#include "util.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/file.h>
#include <unistd.h>

#define PROTOCOL_VERSION 9
#define MAX_FRAME_BYTES (8u * 1024u * 1024u)
#define REPLAY_LIMIT (2u * 1024u * 1024u)
#define READ_CHUNK 65536
#define MAX_FRAME_DATA (MAX_FRAME_BYTES - 32)
#define TAG_OUTPUT 0
#define HANDOFF_TIMEOUT_MS 120000
#define MAX_METADATA_BYTES (1024u * 1024u)
#define DRAIN_AFTER_EXIT_MS 1000

/* Descriptor kept open on the semaphore directory for the short ELF alias. */
#define OC_SEMAPHORE_FD 9

/*
 * Every child spawned by this daemon (both PTY and pipe processes) inherits an
 * open descriptor on the real semaphore directory, so the relocated library's
 * /proc/self/fd/9/ alias works regardless of the install path length.
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

/* Protocol 9 has two process modes. The PTY mode serves the web terminal;
 * pipe mode is what the `shell` tool uses. */
typedef enum { MODE_PTY = 0, MODE_PIPE = 1 } ProcessMode;

typedef struct {
    int fd;
    Buf in;
    Buf out;
    int subscribed;
    uint64_t owner_generation;
    int closing;
    uint64_t terminal_id;
    char attachment[128];
    int role;
} Client;

typedef struct {
    uint64_t id;
    Pty pty;
    Screen screen;
    unsigned char *replay;
    size_t replay_len;
    size_t replay_cap;
    uint64_t head;
    uint64_t tail;
    uint64_t started_at;
    char *cwd;
    char *group_id;
    char *title;
    char *user_title;
    char *osc_title;
    char *controller;
    uint64_t controller_generation;
    int eof;
    int exit_sent;
    int fd_closed;

    ProcessMode mode;
    /* Pipe-only state. `pty.master` holds the pipe read end for pipes. */
    int output_fd;
    char *output_path;
    char *metadata;
    uint64_t output_lines;
    char *output_error;
    uint64_t drain_deadline;
    Client **waiters;
    size_t waiter_count;
} Terminal;

static char g_instance[33];
static char g_token[33];
static char g_socket_path[256];
static dev_t g_socket_dev = 0;
static ino_t g_socket_ino = 0;
static char g_runtime_dir[PATH_MAX];
static int g_listener = -1;
static int g_lock_fd = -1;
static unsigned long long g_generation = 0;
static uint64_t g_next_id = 1;
static volatile sig_atomic_t g_stop = 0;

/* Server ownership: the daemon dies when its owner disappears without a handoff. */
static Client *g_owner = NULL;
static uint64_t g_owner_generation = 0;
static char g_handoff_ticket[33];
static uint64_t g_handoff_expires = 0;
static uint64_t g_handoff_deadline = 0;
static uint64_t g_start_deadline = 0;
static int g_handoff_waiting = 0;

static Terminal **g_terminals = NULL;
static size_t g_terminal_count = 0;
static Client **g_clients = NULL;
static size_t g_client_count = 0;

static void on_signal(int sig) {
    (void)sig;
    g_stop = 1;
}

static uint64_t ptime_ms(void);

static void *xmalloc(size_t n) {
    void *p = malloc(n ? n : 1);
    if (!p) abort();
    return p;
}

static void *xcalloc(size_t n, size_t size) {
    void *p = calloc(n ? n : 1, size ? size : 1);
    if (!p) abort();
    return p;
}

static char *xstrdup(const char *s) {
    char *p = strdup(s ? s : "");
    if (!p) abort();
    return p;
}

static void random_hex(char out[33]) {
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    unsigned char bytes[16];
    if (fd < 0 || read(fd, bytes, sizeof(bytes)) != (ssize_t)sizeof(bytes)) {
        unsigned int seed = (unsigned int)getpid() ^ (unsigned int)ptime_ms();
        for (size_t i = 0; i < sizeof(bytes); i++) {
            seed = seed * 1103515245u + 12345u;
            bytes[i] = (unsigned char)(seed >> 16);
        }
    }
    if (fd >= 0) close(fd);
    static const char digits[] = "0123456789abcdef";
    for (size_t i = 0; i < 16; i++) {
        out[i * 2] = digits[bytes[i] >> 4];
        out[i * 2 + 1] = digits[bytes[i] & 15];
    }
    out[32] = 0;
}

static uint64_t ptime_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

/* Ownership deadlines must not jump when the device clock is adjusted. */
static uint64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static int mkdir_p(const char *path) {
    char copy[PATH_MAX];
    if (strlen(path) >= sizeof(copy)) return -1;
    strcpy(copy, path);
    for (char *p = copy + 1; *p; p++) {
        if (*p != '/') continue;
        *p = 0;
        if (mkdir(copy, 0700) && errno != EEXIST) return -1;
        *p = '/';
    }
    return mkdir(copy, 0700) == 0 || errno == EEXIST ? 0 : -1;
}

static int runtime_file_path(const char *name, char *out, size_t size) {
    int n = snprintf(out, size, "%s/%s", g_runtime_dir, name);
    return n >= 0 && (size_t)n < size ? 0 : -1;
}

static int write_file(const char *path, const char *data, mode_t mode) {
    size_t len = strlen(data);
    char *tmp = xmalloc(strlen(path) + 40);
    snprintf(tmp, strlen(path) + 40, "%s.tmp.%d", path, (int)getpid());
    int fd = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, mode);
    if (fd < 0) {
        unlink(tmp);
        fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    }
    if (fd < 0) {
        free(tmp);
        return -1;
    }
    size_t done = 0;
    while (done < len) {
        ssize_t n = write(fd, data + done, len - done);
        if (n <= 0 && errno != EINTR) {
            close(fd);
            unlink(tmp);
            free(tmp);
            return -1;
        }
        if (n > 0) done += (size_t)n;
    }
    fsync(fd);
    close(fd);
    if (rename(tmp, path)) {
        unlink(tmp);
        free(tmp);
        return -1;
    }
    free(tmp);
    return 0;
}

static char *read_file(const char *path, size_t *len) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    Buf b = {0};
    unsigned char chunk[4096];
    for (;;) {
        ssize_t n = read(fd, chunk, sizeof(chunk));
        if (n > 0) {
            if (buf_append(&b, chunk, (size_t)n)) {
                buf_free(&b);
                close(fd);
                return NULL;
            }
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
    close(fd);
    if (len) *len = b.len;
    return (char *)b.data;
}

static int socket_root_has_parent(const char *root) {
    const char *p = root;
    while (*p) {
        while (*p == '/') p++;
        const char *start = p;
        while (*p && *p != '/') p++;
        size_t n = (size_t)(p - start);
        if (n == 2 && start[0] == '.' && start[1] == '.') return 1;
    }
    return 0;
}

static int compute_socket_path(const char *runtime_dir) {
    const char *env = getenv("OPENCODE_PTY_SOCKET_DIR");
    if (!env || !*env) env = "/tmp";
    if (env[0] != '/' || socket_root_has_parent(env)) {
        fputs("opencode-pty: OPENCODE_PTY_SOCKET_DIR must be absolute without '..'\n", stderr);
        return -1;
    }
    char root[PATH_MAX];
    if (snprintf(root, sizeof(root), "%s/opencode-pty-%u", env, (unsigned)geteuid()) >=
        (int)sizeof(root)) {
        fputs("opencode-pty: socket directory path is too long\n", stderr);
        return -1;
    }
    if (mkdir_p(root)) {
        perror("opencode-pty: socket directory");
        return -1;
    }
    struct stat st;
    if (lstat(root, &st) || !S_ISDIR(st.st_mode) || st.st_uid != geteuid()) {
        fputs("opencode-pty: invalid PTY socket directory\n", stderr);
        return -1;
    }
    chmod(root, 0700);

    char canonical[PATH_MAX];
    if (!realpath(runtime_dir, canonical)) {
        perror("opencode-pty: runtime directory");
        return -1;
    }
    Sha256 sha;
    sha256_init(&sha);
    sha256_update(&sha, canonical, strlen(canonical));
    unsigned char digest[32];
    sha256_final(&sha, digest);
    char name[33];
    static const char digits[] = "0123456789abcdef";
    for (size_t i = 0; i < 16; i++) {
        name[i * 2] = digits[digest[i] >> 4];
        name[i * 2 + 1] = digits[digest[i] & 15];
    }
    name[32] = 0;
    if (snprintf(g_socket_path, sizeof(g_socket_path), "%s/%s.sock", root, name) >=
        (int)sizeof(g_socket_path)) {
        fputs("opencode-pty: socket path is too long\n", stderr);
        return -1;
    }
    if (strlen(g_socket_path) >= sizeof(struct sockaddr_un) - offsetof(struct sockaddr_un, sun_path)) {
        fputs("opencode-pty: socket path is too long\n", stderr);
        return -1;
    }
    return 0;
}

static int write_registration(void) {
    Buf b = {0};
    buf_printf(&b,
               "{\n  \"instance_id\": \"%s\",\n  \"pid\": %d,\n  \"protocol\": %d,\n"
               "  \"socket\": ",
               g_instance, (int)getpid(), PROTOCOL_VERSION);
    buf_json_string(&b, g_socket_path, strlen(g_socket_path));
    buf_printf(&b, ",\n  \"token\": \"%s\",\n  \"features\": []\n}\n", g_token);
    char path[PATH_MAX];
    int result = runtime_file_path("service.json", path, sizeof(path)) ||
                 write_file(path, (const char *)b.data, 0600);
    buf_free(&b);
    return result;
}

static int registration_matches(void) {
    char path[PATH_MAX];
    if (runtime_file_path("service.json", path, sizeof(path))) return 0;
    size_t len = 0;
    char *text = read_file(path, &len);
    if (!text) return 0;
    Json *registration = json_parse(text, len, NULL);
    const char *instance = json_string(registration, "instance_id", "");
    int found = !strcmp(instance, g_instance);
    json_free(registration);
    free(text);
    return found;
}

static void cleanup_runtime(void) {
    int ours = registration_matches();
    struct stat now;
    if (ours && g_socket_ino && lstat(g_socket_path, &now) == 0 &&
        now.st_dev == g_socket_dev && now.st_ino == g_socket_ino) {
        unlink(g_socket_path);
    }
    char path[PATH_MAX];
    if (ours && !runtime_file_path("service.json", path, sizeof(path))) unlink(path);
    if (ours && !runtime_file_path("service.lock", path, sizeof(path))) unlink(path);
    if (g_lock_fd >= 0) close(g_lock_fd);
    if (ours) rmdir(g_runtime_dir);
}

static Terminal *terminal_find(uint64_t id) {
    for (size_t i = 0; i < g_terminal_count; i++) {
        if (g_terminals[i]->id == id) return g_terminals[i];
    }
    return NULL;
}

static void terminal_add(Terminal *t) {
    Terminal **next = realloc(g_terminals, (g_terminal_count + 1) * sizeof(Terminal *));
    if (!next) abort();
    g_terminals = next;
    g_terminals[g_terminal_count++] = t;
}

static void terminal_free(Terminal *t) {
    pty_close(&t->pty);
    if (t->mode == MODE_PIPE && t->output_fd >= 0) close(t->output_fd);
    free(t->output_path);
    free(t->metadata);
    free(t->output_error);
    free(t->waiters);
    screen_free(&t->screen);
    free(t->replay);
    free(t->cwd);
    free(t->group_id);
    free(t->title);
    free(t->user_title);
    free(t->osc_title);
    free(t->controller);
    free(t);
}

static int terminal_remove(uint64_t id) {
    for (size_t i = 0; i < g_terminal_count; i++) {
        if (g_terminals[i]->id != id) continue;
        Terminal *removed = g_terminals[i];
        memmove(g_terminals + i, g_terminals + i + 1,
                (g_terminal_count - i - 1) * sizeof(Terminal *));
        g_terminal_count--;
        terminal_free(removed);
        return 1;
    }
    return 0;
}

static Client *client_add(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    flags = fcntl(fd, F_GETFD, 0);
    if (flags >= 0) fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
    Client *c = xcalloc(1, sizeof(Client));
    c->fd = fd;
    Client **next = realloc(g_clients, (g_client_count + 1) * sizeof(Client *));
    if (!next) abort();
    g_clients = next;
    g_clients[g_client_count++] = c;
    return c;
}

static void client_free(Client *c) {
    close(c->fd);
    buf_free(&c->in);
    buf_free(&c->out);
    free(c);
}

static void client_remove(size_t index) {
    Client *c = g_clients[index];
    if (c == g_owner && c->owner_generation == g_owner_generation) {
        g_owner = NULL;
        /* A prepared handoff may still be claimed by the replacement server. */
        if (g_handoff_ticket[0]) g_handoff_waiting = 1;
        else g_stop = 1;
    }
    /* A client may be blocked in `wait`; drop it so no stale pointer is signalled later. */
    for (size_t i = 0; i < g_terminal_count; i++) {
        Terminal *t = g_terminals[i];
        for (size_t j = 0; j < t->waiter_count;) {
            if (t->waiters[j] == c) {
                memmove(t->waiters + j, t->waiters + j + 1,
                        (t->waiter_count - j - 1) * sizeof(Client *));
                t->waiter_count--;
            } else {
                j++;
            }
        }
    }
    client_free(c);
    memmove(g_clients + index, g_clients + index + 1, (g_client_count - index - 1) * sizeof(Client *));
    g_client_count--;
}

/* Mirrors Ownership::tick: an expired ticket on a live owner is simply dropped. */
static void ownership_tick(uint64_t now) {
    if (!g_owner && !g_handoff_waiting && now > g_start_deadline) {
        g_stop = 1;
        return;
    }
    if (g_handoff_ticket[0] && now > g_handoff_deadline) {
        g_handoff_ticket[0] = 0;
        g_handoff_expires = 0;
        g_handoff_deadline = 0;
        if (g_handoff_waiting) {
            g_handoff_waiting = 0;
            g_stop = 1;
        }
    }
}

static int client_flush(Client *c) {
    while (c->out.len) {
        ssize_t n = write(c->fd, c->out.data, c->out.len);
        if (n > 0) {
            memmove(c->out.data, c->out.data + n, c->out.len - (size_t)n);
            c->out.len -= (size_t)n;
            continue;
        }
        if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
        return -1;
    }
    return 0;
}

static void client_frame(Client *c, const Buf *payload) {
    if (!payload || !payload->len || c->closing) return;
    if (c->out.len + payload->len + 4 > 16u * 1024u * 1024u) {
        c->closing = 1;
        return;
    }
    unsigned char prefix[4] = {
        (unsigned char)(payload->len >> 24), (unsigned char)(payload->len >> 16),
        (unsigned char)(payload->len >> 8), (unsigned char)payload->len
    };
    if (buf_append(&c->out, prefix, 4) || buf_append(&c->out, payload->data, payload->len)) c->closing = 1;
}

static const char *term_title(Terminal *t) {
    if (t->user_title && *t->user_title) return t->user_title;
    if (t->osc_title && *t->osc_title) return t->osc_title;
    return t->title ? t->title : "";
}

static const char *basename_of(const char *path) {
    if (!path) return "";
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

static const char *term_foreground(Terminal *t) {
    return t->pty.exited ? NULL : basename_of(t->pty.argv ? t->pty.argv[0] : "");
}

static const char *signal_name(int sig) {
    switch (sig) {
    case SIGHUP: return "SIGHUP";
    case SIGINT: return "SIGINT";
    case SIGQUIT: return "SIGQUIT";
    case SIGILL: return "SIGILL";
    case SIGTRAP: return "SIGTRAP";
    case SIGABRT: return "SIGABRT";
    case SIGBUS: return "SIGBUS";
    case SIGFPE: return "SIGFPE";
    case SIGKILL: return "SIGKILL";
    case SIGUSR1: return "SIGUSR1";
    case SIGSEGV: return "SIGSEGV";
    case SIGUSR2: return "SIGUSR2";
    case SIGPIPE: return "SIGPIPE";
    case SIGALRM: return "SIGALRM";
    case SIGTERM: return "SIGTERM";
#ifdef SIGSTKFLT
    case SIGSTKFLT: return "SIGSTKFLT";
#endif
#ifdef SIGCHLD
    case SIGCHLD: return "SIGCHLD";
#endif
#ifdef SIGCONT
    case SIGCONT: return "SIGCONT";
#endif
#ifdef SIGSTOP
    case SIGSTOP: return "SIGSTOP";
#endif
#ifdef SIGTSTP
    case SIGTSTP: return "SIGTSTP";
#endif
#ifdef SIGTTIN
    case SIGTTIN: return "SIGTTIN";
#endif
#ifdef SIGTTOU
    case SIGTTOU: return "SIGTTOU";
#endif
    default: return "UNKNOWN";
    }
}

static void replay_append(Terminal *t, const unsigned char *data, size_t len,
                          uint64_t *start, uint64_t *end) {
    *start = t->tail;
    *end = t->tail;
    if (!len) return;
    size_t drop = 0;
    if (t->replay_len + len > REPLAY_LIMIT) {
        drop = t->replay_len + len - REPLAY_LIMIT;
        if (drop > t->replay_len) drop = t->replay_len;
        memmove(t->replay, t->replay + drop, t->replay_len - drop);
        t->replay_len -= drop;
        t->head += drop;
    }
    if (t->replay_cap < t->replay_len + len) {
        size_t cap = t->replay_cap ? t->replay_cap : 65536;
        while (cap < t->replay_len + len) cap *= 2;
        unsigned char *next = realloc(t->replay, cap);
        if (!next) abort();
        t->replay = next;
        t->replay_cap = cap;
    }
    memcpy(t->replay + t->replay_len, data, len);
    t->replay_len += len;
    *start = t->tail;
    t->tail += len;
    *end = t->tail;
}

static int replay_slice(Terminal *t, uint64_t offset, uint64_t *available, uint64_t *end,
                        int *truncated, const unsigned char **data, size_t *len) {
    if (offset > t->tail) return -1;
    *available = offset < t->head ? t->head : offset;
    *end = t->tail;
    *truncated = offset < t->head;
    size_t skip = (size_t)(*available - t->head);
    *data = t->replay ? t->replay + skip : NULL;
    *len = t->replay_len > skip ? t->replay_len - skip : 0;
    return 0;
}

static void write_process_info(Buf *b, Terminal *t) {
    buf_printf(b, "{\"id\":%llu,\"mode\":\"%s\",\"pid\":",
               (unsigned long long)t->id, t->mode == MODE_PIPE ? "pipe" : "pty");
    if (t->pty.pid > 0) buf_printf(b, "%d", (int)t->pty.pid);
    else buf_puts(b, "null");
    buf_puts(b, ",\"command\":[");
    if (t->pty.argv) {
        for (size_t i = 0; i < t->pty.argc; i++) {
            if (i) buf_putc(b, ',');
            buf_json_string(b, t->pty.argv[i], strlen(t->pty.argv[i]));
        }
    }
    buf_puts(b, "],\"cwd\":");
    buf_json_string(b, t->cwd ? t->cwd : "/", strlen(t->cwd ? t->cwd : "/"));
    buf_puts(b, ",\"group_id\":");
    buf_json_string(b, t->group_id ? t->group_id : "", strlen(t->group_id ? t->group_id : ""));
    if (t->exit_sent) {
        buf_puts(b, ",\"lifecycle\":{\"status\":\"exited\",\"exit_code\":");
        if (t->pty.signaled) buf_puts(b, "null");
        else buf_printf(b, "%d", t->pty.exit_code);
        if (t->pty.signaled) {
            buf_puts(b, ",\"signal\":");
            buf_json_string(b, signal_name(t->pty.signal_number), strlen(signal_name(t->pty.signal_number)));
        }
        buf_putc(b, '}');
    } else {
        buf_puts(b, ",\"lifecycle\":{\"status\":\"running\"}");
    }
    buf_printf(b, ",\"output_head\":%llu,\"output_tail\":%llu,\"started_at\":%llu,\"exited_at\":",
               (unsigned long long)t->head, (unsigned long long)t->tail,
               (unsigned long long)t->started_at);
    if (t->exit_sent && t->pty.exited_at) buf_printf(b, "%llu", (unsigned long long)t->pty.exited_at);
    else buf_puts(b, "null");

    if (t->mode == MODE_PIPE) {
        /* Protocol 9: a pipe process has a `pipe` object and no `pty` object. */
        const char *path = t->output_path ? t->output_path : "";
        buf_puts(b, ",\"pipe\":{\"output_path\":");
        buf_json_string(b, path, strlen(path));
        buf_printf(b, ",\"output_lines\":%llu,\"metadata\":", (unsigned long long)t->output_lines);
        buf_puts(b, t->metadata ? t->metadata : "{}");
        if (t->output_error) {
            buf_puts(b, ",\"output_error\":");
            buf_json_string(b, t->output_error, strlen(t->output_error));
        }
        buf_putc(b, '}');
    } else {
        buf_puts(b, ",\"pty\":{\"title\":");
        const char *title = term_title(t);
        buf_json_string(b, title, strlen(title));
        buf_puts(b, ",\"user_title\":");
        if (t->user_title) buf_json_string(b, t->user_title, strlen(t->user_title));
        else buf_puts(b, "null");
        buf_puts(b, ",\"foreground_process\":");
        const char *foreground = term_foreground(t);
        if (foreground) buf_json_string(b, foreground, strlen(foreground));
        else buf_puts(b, "null");
        buf_printf(b, ",\"cols\":%d,\"rows\":%d}", t->screen.cols, t->screen.rows);
    }
    buf_putc(b, '}');
}

static void response(Client *c, const Buf *payload) {
    client_frame(c, payload);
}

static void resp_error(Client *c, const char *message) {
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"error\",\"message\":");
    buf_json_string(&b, message, strlen(message));
    buf_putc(&b, '}');
    response(c, &b);
    buf_free(&b);
}

static void resp_ok(Client *c) {
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"ok\"}");
    response(c, &b);
    buf_free(&b);
}

static void send_pong(Client *c) {
    Buf b = {0};
    buf_printf(&b, "{\"type\":\"pong\",\"instance_id\":\"%s\",\"pid\":%d,\"protocol\":%d,\"features\":[]}",
               g_instance, (int)getpid(), PROTOCOL_VERSION);
    response(c, &b);
    buf_free(&b);
}

static void send_created(Client *c, Terminal *t) {
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"created\",\"process\":");
    write_process_info(&b, t);
    buf_putc(&b, '}');
    response(c, &b);
    buf_free(&b);
}

static void send_process(Client *c, Terminal *t) {
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"process\",\"process\":");
    write_process_info(&b, t);
    buf_putc(&b, '}');
    response(c, &b);
    buf_free(&b);
}

static void send_processes(Client *c, ProcessMode mode) {
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"processes\",\"processes\":[");
    int first = 1;
    for (size_t i = 0; i < g_terminal_count; i++) {
        Terminal *t = g_terminals[i];
        if (t->mode != mode) continue;
        if (!first) buf_putc(&b, ',');
        first = 0;
        write_process_info(&b, t);
    }
    buf_puts(&b, "]}");
    response(c, &b);
    buf_free(&b);
}

static void build_checkpoint(Terminal *t, Buf *out) {
    /*
     * Rebuild the visible screen instead of replaying the whole output history: checkpoints must
     * stay small even after a long session, and ghostty-web only needs the final screen state.
     */
    buf_puts(out, "\x1b[H\x1b[2J\x1b[0m");
    for (int y = 0; y < t->screen.rows; y++) {
        Buf row = {0};
        screen_row(&t->screen, y, &row);
        if (row.len) {
            buf_printf(out, "\x1b[%d;1H\x1b[2K", y + 1);
            buf_append(out, row.data, row.len);
        }
        buf_free(&row);
    }
    buf_printf(out, "\x1b[%d;%dH", t->screen.y + 1, t->screen.x + 1);
}

static void send_snapshot(Client *c, Terminal *t) {
    Buf checkpoint = {0};
    build_checkpoint(t, &checkpoint);
    size_t encoded_len = 0;
    char *encoded = b64_encode(checkpoint.data, checkpoint.len, &encoded_len);
    Buf text = {0};
    screen_text(&t->screen, &text);
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"snapshot\",\"process\":");
    write_process_info(&b, t);
    buf_puts(&b, ",\"text\":");
    buf_json_string(&b, (const char *)text.data, text.len);
    buf_puts(&b, ",\"checkpoint_base64\":");
    buf_json_string(&b, encoded, encoded_len);
    buf_printf(&b, ",\"cursor_x\":%d,\"cursor_y\":%d}", t->screen.x, t->screen.y);
    response(c, &b);
    buf_free(&b);
    buf_free(&text);
    buf_free(&checkpoint);
    free(encoded);
}

static void send_rows(Client *c, Terminal *t, int wanted) {
    Buf lines = {0};
    screen_rows_json(&t->screen, wanted, &lines);
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"rows\",\"process\":");
    write_process_info(&b, t);
    buf_puts(&b, ",\"lines\":");
    buf_append(&b, lines.data, lines.len);
    buf_printf(&b, ",\"cursor_x\":%d,\"cursor_y\":%d}", t->screen.x, t->screen.y);
    response(c, &b);
    buf_free(&b);
    buf_free(&lines);
}

static void send_replay(Client *c, Terminal *t, uint64_t offset) {
    uint64_t available = 0, end = 0;
    int truncated = 0;
    const unsigned char *data = NULL;
    size_t len = 0;
    if (replay_slice(t, offset, &available, &end, &truncated, &data, &len)) {
        Buf message = {0};
        buf_printf(&message, "requested offset %llu is beyond tail %llu",
                   (unsigned long long)offset, (unsigned long long)t->tail);
        resp_error(c, (const char *)message.data);
        buf_free(&message);
        return;
    }
    size_t encoded_len = 0;
    char *encoded = b64_encode(data, len, &encoded_len);
    Buf b = {0};
    buf_printf(&b, "{\"type\":\"replay\",\"requested_offset\":%llu,\"available_offset\":%llu,"
                   "\"end_offset\":%llu,\"truncated\":%s,\"data_base64\":",
               (unsigned long long)offset, (unsigned long long)available, (unsigned long long)end,
               truncated ? "true" : "false");
    buf_json_string(&b, encoded, encoded_len);
    buf_putc(&b, '}');
    response(c, &b);
    buf_free(&b);
    free(encoded);
}

static void send_attached(Client *c, Terminal *t, uint64_t offset) {
    uint64_t available = 0, end = 0;
    int truncated = 0;
    const unsigned char *data = NULL;
    size_t len = 0;
    replay_slice(t, offset, &available, &end, &truncated, &data, &len);
    size_t encoded_len = 0;
    char *encoded = b64_encode(data, len, &encoded_len);
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"attached\",\"process\":");
    write_process_info(&b, t);
    buf_printf(&b, ",\"role\":\"%s\",\"generation\":%llu,\"requested_offset\":%llu,"
                   "\"available_offset\":%llu,\"end_offset\":%llu,\"truncated\":%s,\"replay_base64\":",
               c->role == 1 ? "controller" : "observer",
               (unsigned long long)t->controller_generation, (unsigned long long)offset,
               (unsigned long long)available, (unsigned long long)end,
               truncated ? "true" : "false");
    buf_json_string(&b, encoded, encoded_len);
    buf_putc(&b, '}');
    response(c, &b);
    buf_free(&b);
    free(encoded);
}

static void send_output(Client *c, uint64_t start, uint64_t end, const unsigned char *data, size_t len) {
    if (len > MAX_FRAME_DATA || c->closing) return;
    Buf payload = {0};
    buf_putc(&payload, TAG_OUTPUT);
    unsigned char be[8];
    for (int i = 7; i >= 0; i--) {
        be[i] = (unsigned char)(start >> (8 * (7 - i)));
    }
    buf_append(&payload, be, 8);
    for (int i = 7; i >= 0; i--) {
        be[i] = (unsigned char)(end >> (8 * (7 - i)));
    }
    buf_append(&payload, be, 8);
    buf_append(&payload, data, len);
    client_frame(c, &payload);
    buf_free(&payload);
}

static void broadcast_output(Terminal *t, uint64_t start, uint64_t end,
                             const unsigned char *data, size_t len) {
    for (size_t i = 0; i < g_client_count; i++) {
        Client *c = g_clients[i];
        if (c->subscribed && c->terminal_id == t->id) send_output(c, start, end, data, len);
    }
}

static void broadcast(Client *skip, uint64_t terminal_id, const Buf *payload) {
    for (size_t i = 0; i < g_client_count; i++) {
        Client *c = g_clients[i];
        if (c == skip || !c->subscribed || c->terminal_id != terminal_id) continue;
        response(c, payload);
    }
}

static void broadcast_resized(Terminal *t, int cols, int rows) {
    Buf checkpoint = {0};
    build_checkpoint(t, &checkpoint);
    size_t encoded_len = 0;
    char *encoded = b64_encode(checkpoint.data, checkpoint.len, &encoded_len);
    Buf b = {0};
    buf_printf(&b, "{\"type\":\"resized\",\"cols\":%d,\"rows\":%d,\"generation\":%llu,\"checkpoint_base64\":",
               cols, rows, (unsigned long long)t->controller_generation);
    buf_json_string(&b, encoded, encoded_len);
    buf_putc(&b, '}');
    broadcast(NULL, t->id, &b);
    buf_free(&b);
    free(encoded);
    buf_free(&checkpoint);
}

static void broadcast_controller(Terminal *t) {
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"controller_changed\",\"attachment_id\":");
    if (t->controller) buf_json_string(&b, t->controller, strlen(t->controller));
    else buf_puts(&b, "null");
    buf_printf(&b, ",\"generation\":%llu}", (unsigned long long)t->controller_generation);
    broadcast(NULL, t->id, &b);
    buf_free(&b);
}

static void broadcast_title(Terminal *t, const char *title) {
    Buf b = {0};
    buf_puts(&b, "{\"type\":\"title_changed\",\"title\":");
    buf_json_string(&b, title, strlen(title));
    buf_putc(&b, '}');
    broadcast(NULL, t->id, &b);
    buf_free(&b);
}

static void build_exited(Terminal *t, Buf *b) {
    buf_puts(b, "{\"type\":\"exited\",\"exit_code\":");
    if (t->pty.signaled) buf_puts(b, "null");
    else buf_printf(b, "%d", t->pty.exit_code);
    if (t->pty.signaled) {
        buf_puts(b, ",\"signal\":");
        buf_json_string(b, signal_name(t->pty.signal_number), strlen(signal_name(t->pty.signal_number)));
    }
    if (t->mode == MODE_PIPE) {
        buf_printf(b, ",\"final_offset\":%llu,\"output_lines\":%llu,\"exited_at\":%llu",
                   (unsigned long long)t->tail, (unsigned long long)t->output_lines,
                   (unsigned long long)t->pty.exited_at);
        if (t->output_error) {
            buf_puts(b, ",\"output_error\":");
            buf_json_string(b, t->output_error, strlen(t->output_error));
        }
        buf_putc(b, '}');
    } else {
        buf_printf(b, ",\"final_offset\":%llu,\"exited_at\":%llu}",
                   (unsigned long long)t->tail, (unsigned long long)t->pty.exited_at);
    }
}

static void send_exited(Client *c, Terminal *t) {
    Buf b = {0};
    build_exited(t, &b);
    response(c, &b);
    buf_free(&b);
}

static void broadcast_exited(Terminal *t) {
    Buf b = {0};
    build_exited(t, &b);
    broadcast(NULL, t->id, &b);
    buf_free(&b);
    /* `wait` clients are not subscribers: they get exactly one Exited response
     * on the connection that issued the request. */
    for (size_t i = 0; i < t->waiter_count; i++) {
        Client *c = t->waiters[i];
        if ((c->subscribed && c->terminal_id == t->id) || c->closing) continue;
        send_exited(c, t);
    }
    free(t->waiters);
    t->waiters = NULL;
    t->waiter_count = 0;
}

static void scan_title(Terminal *t, const unsigned char *data, size_t len) {
    for (size_t i = 0; i + 2 < len; i++) {
        if (data[i] != 0x1b || data[i + 1] != ']') continue;
        size_t j = i + 2;
        if (j < len && (data[j] == '0' || data[j] == '1' || data[j] == '2')) j++;
        if (j >= len || data[j] != ';') continue;
        j++;
        size_t start = j;
        while (j < len && data[j] != 0x07 && data[j] != 0x1b) j++;
        if (j == start) continue;
        char *title = xmalloc(j - start + 1);
        memcpy(title, data + start, j - start);
        title[j - start] = 0;
        if (!t->osc_title || strcmp(t->osc_title, title)) {
            free(t->osc_title);
            t->osc_title = title;
            broadcast_title(t, term_title(t));
        } else {
            free(title);
        }
        i = j;
    }
}

static int resolve_program(const char *program, char **out) {
    if (strchr(program, '/')) {
        if (access(program, X_OK)) return -1;
        *out = xstrdup(program);
        return 0;
    }
    const char *path = getenv("PATH");
    if (!path) path = "/system/bin:/data/data/com.opencode.android/files/usr/bin";
    size_t len = strlen(path);
    char *copy = xmalloc(len + 1);
    memcpy(copy, path, len + 1);
    char *save = NULL;
    for (char *part = strtok_r(copy, ":", &save); part; part = strtok_r(NULL, ":", &save)) {
        size_t n = strlen(part) + strlen(program) + 2;
        char *candidate = xmalloc(n);
        snprintf(candidate, n, "%s/%s", *part ? part : "/", program);
        if (!access(candidate, X_OK)) {
            free(copy);
            *out = candidate;
            return 0;
        }
        free(candidate);
    }
    free(copy);
    return -1;
}

static int make_argv(const char *program, Json *args, char ***out, size_t *count) {
    size_t n = json_array_count(args);
    char **argv = xcalloc(n + 2, sizeof(char *));
    argv[0] = xstrdup(program);
    for (size_t i = 0; i < n; i++) {
        Json *item = json_array_item(args, i);
        if (!item || item->type != JSON_STRING) {
            for (size_t j = 0; j <= n; j++) free(argv[j]);
            free(argv);
            return -1;
        }
        argv[i + 1] = xstrdup(item->u.string.data);
    }
    *out = argv;
    *count = n + 1;
    return 0;
}

static void free_vector(char **vector, size_t count);

static int env_has_key(Json *env, const char *key) {
    if (!env || env->type != JSON_OBJECT) return 0;
    for (size_t i = 0; i < env->u.object.count; i++) {
        if (!strcmp(env->u.object.keys[i], key)) return 1;
    }
    return 0;
}

static void envp_set(char **envp, size_t *written, const char *key, const char *value) {
    size_t len = strlen(key) + strlen(value) + 2;
    envp[*written] = xmalloc(len);
    snprintf(envp[*written], len, "%s=%s", key, value);
    (*written)++;
}

static int make_envp(Json *env, char ***out, size_t *out_count) {
    size_t n = env && env->type == JSON_OBJECT ? env->u.object.count : 0;
    /* Room for the caller map, the terminal overrides and the Android runtime variables below. */
    char **envp = xcalloc(n + 14, sizeof(char *));
    size_t written = 0;
    for (size_t i = 0; i < n; i++) {
        Json *value = &env->u.object.values[i];
        if (value->type != JSON_STRING) {
            free_vector(envp, written);
            return -1;
        }
        const char *key = env->u.object.keys[i];
        /* The daemon overrides these two after the caller environment. */
        if (!strcmp(key, "TERM") || !strcmp(key, "COLORTERM")) continue;
        envp_set(envp, &written, key, value->u.string.data);
    }
    /*
     * The web client sends an empty env map, but the Android prefix shell needs the runtime paths
     * of the server that spawned this daemon. Without LD_LIBRARY_PATH its first exec dies with
     * `libandroid-support.so not found` before the login profile can set anything up. Inherit only
     * these safe variables, and never override a value the caller supplied explicitly.
     */
    static const char *const inherited[] = {
        "LD_LIBRARY_PATH", "BASH_ENV", "HOME", "PATH", "TMPDIR",
        "LANG", "TZDIR", "SHELL", "PREFIX", "OC_PREFIX", "OC_USER_HOME",
        NULL
    };
    for (size_t i = 0; inherited[i]; i++) {
        if (env_has_key(env, inherited[i])) continue;
        const char *value = getenv(inherited[i]);
        if (value && *value) envp_set(envp, &written, inherited[i], value);
    }
    envp_set(envp, &written, "TERM", "xterm-256color");
    envp_set(envp, &written, "COLORTERM", "truecolor");
    char **fit = realloc(envp, (written + 1) * sizeof(char *));
    *out = fit ? fit : envp;
    *out_count = written;
    return 0;
}

static void free_vector(char **vector, size_t count) {
    if (!vector) return;
    for (size_t i = 0; i < count; i++) free(vector[i]);
    free(vector);
}

/* The PTY master is non-blocking so the output pump cannot stall the daemon. */
static int pty_write_all(Pty *pty, const unsigned char *bytes, size_t len) {
    size_t done = 0;
    while (done < len) {
        ssize_t n = write(pty->master, bytes + done, len - done);
        if (n > 0) {
            done += (size_t)n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd pfd;
            pfd.fd = pty->master;
            pfd.events = POLLOUT;
            pfd.revents = 0;
            int ready = poll(&pfd, 1, 1000);
            if (ready > 0 && (pfd.revents & POLLOUT) != 0) continue;
            if (ready < 0 && errno == EINTR) continue;
            return -1;
        }
        return -1;
    }
    return 0;
}

static uint64_t request_id(Json *request) {
    return (uint64_t)json_number(request, "id", 0);
}

static int terminal_allows_controller(Terminal *t, const char *attachment) {
    if (!t->controller) return 1;
    return attachment && !strcmp(attachment, t->controller);
}

/*
 * `subscribe` keeps one long-lived connection, but `control` and `input` arrive on
 * one-shot connections. Authorization therefore has to look up the attachment id
 * across all live clients, not on the connection that sent the current request.
 */
static int terminal_has_subscriber(Terminal *t, const char *attachment) {
    if (!attachment || !*attachment) return 0;
    for (size_t i = 0; i < g_client_count; i++) {
        Client *c = g_clients[i];
        if (c->subscribed && c->terminal_id == t->id &&
            !strcmp(c->attachment, attachment)) {
            return 1;
        }
    }
    return 0;
}

/* Returns 1 when the active controller (and generation) actually changed. */
static int terminal_set_controller(Terminal *t, const char *attachment) {
    for (size_t i = 0; i < g_client_count; i++) {
        Client *c = g_clients[i];
        if (!c->subscribed || c->terminal_id != t->id) continue;
        if (c->role == 1 && t->controller &&
            !strcmp(c->attachment, t->controller)) {
            c->role = 0;
        }
        if (!strcmp(c->attachment, attachment)) c->role = 1;
    }
    if (t->controller && !strcmp(t->controller, attachment)) return 0;
    free(t->controller);
    t->controller = xstrdup(attachment);
    t->controller_generation++;
    return 1;
}

/* Serialize opaque pipe metadata once, then store/emit the same bytes. */
static char *json_dup(const Json *value) {
    Buf b = {0};
    if (json_write(&b, value) || b.len > MAX_METADATA_BYTES) {
        buf_free(&b);
        return NULL;
    }
    char *out = xmalloc(b.len + 1);
    memcpy(out, b.data ? (const char *)b.data : "", b.len);
    out[b.len] = 0;
    buf_free(&b);
    return out;
}

static void handle_request(Client *c, Json *request) {
    if (!request || request->type != JSON_OBJECT) {
        resp_error(c, "invalid request");
        return;
    }
    const char *op = json_string(request, "op", "");
    if (!*op) {
        resp_error(c, "invalid request: missing field `op`");
        return;
    }
    if (c->owner_generation) {
        if (c->owner_generation != g_owner_generation) {
            resp_error(c, "owner connection has been superseded");
            return;
        }
        if (strcmp(op, "prepare_handoff") && strcmp(op, "shutdown")) {
            resp_error(c, "owner connection only accepts prepare_handoff or shutdown");
            return;
        }
    }
    if (!strcmp(op, "ping")) {
        send_pong(c);
        return;
    }
    if (!strcmp(op, "shutdown")) {
        resp_ok(c);
        client_flush(c);
        g_stop = 1;
        return;
    }
    if (!strcmp(op, "own")) {
        const char *instance = json_string(request, "instance_id", "");
        const char *ticket = json_string(request, "ticket", NULL);
        uint64_t now = monotonic_ms();
        ownership_tick(now);
        int allowed = 0;
        if (g_stop) {
            resp_error(c, "daemon ownership deadline expired or daemon is stopping");
        } else if (strcmp(instance, g_instance)) {
            resp_error(c, "daemon instance_id mismatch");
        } else if (ticket) {
            if (!g_handoff_ticket[0] || now > g_handoff_deadline ||
                strcmp(ticket, g_handoff_ticket)) {
                resp_error(c, "invalid handoff ticket");
            } else {
                allowed = 1;
            }
        } else if (g_owner) {
            resp_error(c, "daemon already has a live owner");
        } else if (g_handoff_ticket[0]) {
            resp_error(c, "invalid handoff ticket");
        } else {
            allowed = 1;
        }
        if (!allowed) return;
        g_handoff_ticket[0] = 0;
        g_handoff_expires = 0;
        g_handoff_deadline = 0;
        g_handoff_waiting = 0;
        g_owner = c;
        c->owner_generation = g_owner_generation = ++g_generation;
        {
            Buf b = {0};
            buf_puts(&b, "{\"type\":\"owned\"}");
            response(c, &b);
            buf_free(&b);
        }
        return;
    }
    if (!strcmp(op, "prepare_handoff")) {
        ownership_tick(monotonic_ms());
        if (c != g_owner || g_stop) {
            resp_error(c, "handoff requires the owner connection");
            return;
        }
        if (!g_handoff_ticket[0]) {
            random_hex(g_handoff_ticket);
            g_handoff_deadline = monotonic_ms() + HANDOFF_TIMEOUT_MS;
            g_handoff_expires = ptime_ms() + HANDOFF_TIMEOUT_MS;
        }
        Buf b = {0};
        buf_printf(&b, "{\"type\":\"handoff\",\"ticket\":\"%s\",\"expires_at\":%llu}",
                   g_handoff_ticket, (unsigned long long)g_handoff_expires);
        response(c, &b);
        buf_free(&b);
        return;
    }
    if (!strcmp(op, "list")) {
        const char *mode = json_string(request, "mode", "pty");
        if (!strcmp(mode, "pipe")) {
            send_processes(c, MODE_PIPE);
        } else if (!strcmp(mode, "pty")) {
            send_processes(c, MODE_PTY);
        } else {
            resp_error(c, "invalid process mode");
        }
        return;
    }
    if (!strcmp(op, "get") || !strcmp(op, "write") || !strcmp(op, "resize") ||
        !strcmp(op, "control") || !strcmp(op, "input") || !strcmp(op, "snapshot") ||
        !strcmp(op, "read_rows") || !strcmp(op, "rename") || !strcmp(op, "replay") ||
        !strcmp(op, "subscribe") || !strcmp(op, "terminate")) {
        Terminal *t = terminal_find(request_id(request));
        if (!t) {
            resp_error(c, "unknown terminal");
            return;
        }
        /* A pipe has no screen and no terminal master. Output replay and
         * subscription still work on pipes; only terminal ops are rejected. */
        if (t->mode != MODE_PTY &&
            (!strcmp(op, "write") || !strcmp(op, "resize") || !strcmp(op, "control") ||
             !strcmp(op, "input") || !strcmp(op, "snapshot") || !strcmp(op, "read_rows") ||
             !strcmp(op, "rename"))) {
            resp_error(c, "operation requires a pty process");
            return;
        }
        if (!strcmp(op, "get")) {
            send_process(c, t);
        } else if (!strcmp(op, "snapshot")) {
            send_snapshot(c, t);
        } else if (!strcmp(op, "read_rows")) {
            Json *rows = json_get(request, "rows");
            int wanted = 0;
            if (rows && rows->type == JSON_NUMBER) {
                wanted = (int)rows->u.number;
                if (wanted <= 0 || wanted > 65535) {
                    resp_error(c, "rows must be between 1 and 65535");
                    return;
                }
            } else if (rows && rows->type != JSON_NULL) {
                resp_error(c, "invalid rows");
                return;
            }
            send_rows(c, t, wanted);
        } else if (!strcmp(op, "replay")) {
            send_replay(c, t, (uint64_t)json_number(request, "offset", 0));
        } else if (!strcmp(op, "rename")) {
            Json *title = json_get(request, "title");
            if (title && title->type != JSON_STRING && title->type != JSON_NULL) {
                resp_error(c, "invalid title");
                return;
            }
            const char *new_title = title && title->type == JSON_STRING && *title->u.string.data
                                        ? title->u.string.data : NULL;
            int changed = (t->user_title ? 1 : 0) != (new_title ? 1 : 0) ||
                          (new_title && strcmp(t->user_title, new_title));
            if (changed) {
                free(t->user_title);
                t->user_title = new_title ? xstrdup(new_title) : NULL;
            }
            resp_ok(c);
            if (changed) broadcast_title(t, term_title(t));
        } else if (!strcmp(op, "terminate")) {
            uint64_t id = t->id;
            resp_ok(c);
            terminal_remove(id);
        } else if (!strcmp(op, "subscribe")) {
            const char *attachment = json_string(request, "attachment_id", "");
            if (!*attachment) {
                resp_error(c, "attachment_id is required");
                return;
            }
            if (strlen(attachment) >= sizeof(c->attachment)) {
                resp_error(c, "attachment_id is too long");
                return;
            }
            uint64_t offset = (uint64_t)json_number(request, "offset", 0);
            uint64_t available = 0, end = 0;
            int truncated = 0;
            const unsigned char *data = NULL;
            size_t len = 0;
            if (replay_slice(t, offset, &available, &end, &truncated, &data, &len)) {
                resp_error(c, "requested offset is beyond tail");
                return;
            }
            int role = !strcmp(json_string(request, "role", "controller"), "controller") ? 1 : 0;
            int takeover = json_bool(request, "takeover", 0);
            int controller_changed = 0;
            if (role) {
                if (t->controller && strcmp(t->controller, attachment) && !takeover) {
                    Buf message = {0};
                    buf_printf(&message, "terminal already has controller %s", t->controller);
                    resp_error(c, (const char *)message.data);
                    buf_free(&message);
                    return;
                }
                controller_changed = terminal_set_controller(t, attachment);
            }
            c->subscribed = 1;
            c->terminal_id = t->id;
            snprintf(c->attachment, sizeof(c->attachment), "%s", attachment);
            c->role = role;
            send_attached(c, t, offset);
            /* A fresh subscriber to an already-exited terminal still needs the final event. */
            if (t->exit_sent) send_exited(c, t);
            if (controller_changed) broadcast_controller(t);
        } else if (!strcmp(op, "write")) {
            const char *attachment = json_string(request, "attachment_id", NULL);
            if (!terminal_allows_controller(t, attachment)) {
                resp_error(c, "terminal input requires the active controller attachment");
                return;
            }
            Json *data = json_get(request, "data_base64");
            if (!data || data->type != JSON_STRING || t->pty.exited || t->pty.master < 0) {
                resp_error(c, "terminal is not writable");
                return;
            }
            if (data->u.string.len > 1024u * 1024u) {
                resp_error(c, "terminal input exceeds 1 MiB limit");
                return;
            }
            unsigned char *bytes = NULL;
            size_t len = 0;
            if (b64_decode(data->u.string.data, data->u.string.len, &bytes, &len) || !len) {
                resp_error(c, "invalid input base64");
                return;
            }
            int written = pty_write_all(&t->pty, bytes, len);
            free(bytes);
            if (written) {
                resp_error(c, "terminal input failed");
                return;
            }
            resp_ok(c);
        } else if (!strcmp(op, "resize") || !strcmp(op, "control")) {
            const char *attachment = json_string(request, "attachment_id", NULL);
            if (!strcmp(op, "control")) {
                if (!attachment || !terminal_has_subscriber(t, attachment)) {
                    resp_error(c, "attachment is not subscribed to this terminal");
                    return;
                }
                int changed = terminal_set_controller(t, attachment);
                if (changed) broadcast_controller(t);
            } else if (!terminal_allows_controller(t, attachment)) {
                resp_error(c, "terminal input requires the active controller attachment");
                return;
            }
            int cols = (int)json_number(request, "cols", 0);
            int rows = (int)json_number(request, "rows", 0);
            if (cols <= 0 || rows <= 0 || cols > 65535 || rows > 65535) {
                resp_error(c, "terminal dimensions must be positive");
                return;
            }
            int changed = t->screen.cols != cols || t->screen.rows != rows;
            if (pty_resize(&t->pty, cols, rows)) {
                resp_error(c, "resize failed");
                return;
            }
            screen_resize(&t->screen, cols, rows);
            resp_ok(c);
            if (changed) broadcast_resized(t, cols, rows);
        } else if (!strcmp(op, "input")) {
            const char *attachment = json_string(request, "attachment_id", "");
            if (!terminal_has_subscriber(t, attachment)) {
                resp_error(c, "attachment is not subscribed to this terminal");
                return;
            }
            if (t->pty.exited || t->pty.master < 0) {
                resp_error(c, "terminal is not writable");
                return;
            }
            int controller_changed = terminal_set_controller(t, attachment);
            if (controller_changed) broadcast_controller(t);
            int cols = (int)json_number(request, "cols", 0);
            int rows = (int)json_number(request, "rows", 0);
            if (cols <= 0 || rows <= 0 || cols > 65535 || rows > 65535) {
                resp_error(c, "terminal dimensions must be positive");
                return;
            }
            Json *data = json_get(request, "data_base64");
            if (!data || data->type != JSON_STRING) {
                resp_error(c, "invalid input base64");
                return;
            }
            if (data->u.string.len > 1024u * 1024u) {
                resp_error(c, "terminal input exceeds 1 MiB limit");
                return;
            }
            unsigned char *bytes = NULL;
            size_t len = 0;
            if (b64_decode(data->u.string.data, data->u.string.len, &bytes, &len)) {
                resp_error(c, "invalid input base64");
                return;
            }
            int changed = t->screen.cols != cols || t->screen.rows != rows;
            if (pty_resize(&t->pty, cols, rows)) {
                free(bytes);
                resp_error(c, "resize failed");
                return;
            }
            screen_resize(&t->screen, cols, rows);
            /* The client must adopt the new size before child output is rendered. */
            if (changed) broadcast_resized(t, cols, rows);
            int written = pty_write_all(&t->pty, bytes, len);
            free(bytes);
            if (written) resp_error(c, "terminal input failed");
            else resp_ok(c);
        }
        return;
    }
    if (!strcmp(op, "create_pty")) {
        const char *program = json_string(request, "program", "");
        if (!*program) {
            resp_error(c, "program cannot be empty");
            return;
        }
        int cols = (int)json_number(request, "cols", 80);
        int rows = (int)json_number(request, "rows", 24);
        if (cols <= 0 || rows <= 0 || cols > 65535 || rows > 65535) {
            resp_error(c, "terminal dimensions must be positive");
            return;
        }
        const char *cwd = json_string(request, "cwd", "");
        if (!*cwd || cwd[0] != '/') {
            resp_error(c, "cwd must be absolute");
            return;
        }
        struct stat st;
        if (stat(cwd, &st) || !S_ISDIR(st.st_mode)) {
            Buf message = {0};
            buf_printf(&message, "cwd is not a directory: %s", cwd);
            resp_error(c, (const char *)message.data);
            buf_free(&message);
            return;
        }
        char *resolved = NULL;
        if (resolve_program(program, &resolved)) {
            Buf message = {0};
            buf_printf(&message, "program not found: %s", program);
            resp_error(c, (const char *)message.data);
            buf_free(&message);
            return;
        }
        char **argv = NULL;
        size_t argc = 0;
        if (make_argv(program, json_get(request, "args"), &argv, &argc)) {
            free(resolved);
            resp_error(c, "args must be strings");
            return;
        }
        Json *env = json_get(request, "env");
        size_t env_count = 0;
        char **envp = NULL;
        if (make_envp(env, &envp, &env_count)) {
            free_vector(argv, argc);
            free(resolved);
            resp_error(c, "env values must be strings");
            return;
        }
        uint64_t started = ptime_ms();
        Terminal *t = xcalloc(1, sizeof(Terminal));
        t->id = g_next_id++;
        t->started_at = started;
        t->cwd = xstrdup(cwd);
        t->group_id = xstrdup(json_string(request, "group_id", ""));
        t->title = xstrdup(json_string(request, "title", ""));
        screen_init(&t->screen, cols, rows);
        if (pty_spawn(resolved, argv, envp, cwd, cols, rows, &t->pty)) {
            resp_error(c, "failed to start terminal");
            screen_free(&t->screen);
            free(t->cwd);
            free(t->group_id);
            free(t->title);
            free(t);
        } else {
            terminal_add(t);
            send_created(c, t);
        }
        free_vector(envp, env_count);
        free_vector(argv, argc);
        free(resolved);
        return;
    }
    if (!strcmp(op, "create_pipe")) {
        const char *program = json_string(request, "program", "");
        const char *cwd = json_string(request, "cwd", "");
        const char *output_path = json_string(request, "output_path", "");
        Json *env = json_get(request, "env");
        Json *meta = json_get(request, "metadata");
        if (!*program || !*cwd || cwd[0] != '/' || !*output_path || output_path[0] != '/') {
            resp_error(c, "program, cwd and output_path are required; paths must be absolute");
            return;
        }
        if (!env || env->type != JSON_OBJECT || !meta || meta->type != JSON_OBJECT) {
            resp_error(c, "env and metadata must be objects");
            return;
        }
        struct stat st;
        if (stat(cwd, &st) || !S_ISDIR(st.st_mode)) {
            Buf message = {0};
            buf_printf(&message, "cwd is not a directory: %s", cwd);
            resp_error(c, (const char *)message.data);
            buf_free(&message);
            return;
        }
        char *metadata = json_dup(meta);
        if (!metadata) {
            resp_error(c, "metadata must be an object of at most 1 MiB");
            return;
        }
        char *resolved = NULL;
        if (resolve_program(program, &resolved)) {
            Buf message = {0};
            buf_printf(&message, "program not found: %s", program);
            resp_error(c, (const char *)message.data);
            buf_free(&message);
            free(metadata);
            return;
        }
        char **argv = NULL;
        size_t argc = 0;
        if (make_argv(program, json_get(request, "args"), &argv, &argc)) {
            free(resolved);
            free(metadata);
            resp_error(c, "args must be strings");
            return;
        }
        char **envp = NULL;
        size_t env_count = 0;
        if (make_envp(env, &envp, &env_count)) {
            free_vector(argv, argc);
            free(resolved);
            free(metadata);
            resp_error(c, "env values must be strings");
            return;
        }
        uint64_t started = ptime_ms();
        Terminal *t = xcalloc(1, sizeof(Terminal));
        t->mode = MODE_PIPE;
        t->id = g_next_id++;
        t->started_at = started;
        t->cwd = xstrdup(cwd);
        t->group_id = xstrdup(json_string(request, "group_id", ""));
        t->output_path = xstrdup(output_path);
        t->metadata = metadata;
        t->output_fd = -1;
        /* Screen is unused for pipes, but keeping it valid makes generic ops safe. */
        screen_init(&t->screen, 80, 24);
        int sink = -1;
        if (pipe_spawn(resolved, argv, envp, cwd, output_path, &sink, &t->pty)) {
            if (sink >= 0) close(sink);
            resp_error(c, "failed to start pipe");
            screen_free(&t->screen);
            free(t->cwd);
            free(t->group_id);
            free(t->output_path);
            free(t->metadata);
            free(t);
        } else {
            t->output_fd = sink;
            terminal_add(t);
            send_created(c, t);
        }
        free_vector(envp, env_count);
        free_vector(argv, argc);
        free(resolved);
        return;
    }
    if (!strcmp(op, "wait")) {
        Terminal *t = terminal_find(request_id(request));
        if (!t) {
            resp_error(c, "process not found");
            return;
        }
        if (t->mode != MODE_PIPE) {
            resp_error(c, "wait is only supported for pipes");
            return;
        }
        if (t->exit_sent) {
            send_exited(c, t);
            return;
        }
        for (size_t i = 0; i < t->waiter_count; i++) {
            if (t->waiters[i] == c) return; /* already waiting on this connection */
        }
        Client **next = realloc(t->waiters, (t->waiter_count + 1) * sizeof(Client *));
        if (!next) {
            resp_error(c, "out of memory");
            return;
        }
        t->waiters = next;
        t->waiters[t->waiter_count++] = c;
        /* No response now: `broadcast_exited` streams exactly one Exited reply. */
        return;
    }
    if (!strcmp(op, "signal")) {
        Terminal *t = terminal_find(request_id(request));
        if (!t) {
            resp_error(c, "process not found");
            return;
        }
        if (t->mode != MODE_PIPE) {
            resp_error(c, "signal is only supported for pipes");
            return;
        }
        const char *name = json_string(request, "signal", "");
        int sig = 0;
        if (!strcmp(name, "SIGTERM")) sig = SIGTERM;
        else if (!strcmp(name, "SIGINT")) sig = SIGINT;
        else if (!strcmp(name, "SIGKILL")) sig = SIGKILL;
        if (!sig) {
            resp_error(c, "unsupported signal");
            return;
        }
        pty_signal(&t->pty, sig);
        resp_ok(c);
        return;
    }
    if (!strcmp(op, "set_metadata")) {
        Terminal *t = terminal_find(request_id(request));
        if (!t) {
            resp_error(c, "process not found");
            return;
        }
        if (t->mode != MODE_PIPE) {
            resp_error(c, "set_metadata is only supported for pipes");
            return;
        }
        Json *meta = json_get(request, "metadata");
        if (!meta || meta->type != JSON_OBJECT) {
            resp_error(c, "metadata must be an object");
            return;
        }
        char *value = json_dup(meta);
        if (!value) {
            resp_error(c, "metadata exceeds 1 MiB limit");
            return;
        }
        free(t->metadata);
        t->metadata = value;
        resp_ok(c);
        return;
    }
    Buf message = {0};
    buf_printf(&message, "unsupported request: %s", op);
    resp_error(c, (const char *)message.data);
    buf_free(&message);
}

static void client_requests(Client *c) {
    while (c->in.len >= 4) {
        uint32_t len = ((uint32_t)c->in.data[0] << 24) | ((uint32_t)c->in.data[1] << 16) |
                       ((uint32_t)c->in.data[2] << 8) | c->in.data[3];
        if (len == 0 || len > MAX_FRAME_BYTES) {
            resp_error(c, "invalid frame length");
            c->closing = 1;
            return;
        }
        if (c->in.len < len + 4) return;
        Json *envelope = json_parse((const char *)c->in.data + 4, len, NULL);
        memmove(c->in.data, c->in.data + 4 + len, c->in.len - 4 - len);
        c->in.len -= 4 + len;
        if (!envelope) {
            resp_error(c, "invalid request envelope");
            continue;
        }
        const char *token = json_string(envelope, "token", "");
        if (strcmp(token, g_token)) {
            resp_error(c, "authentication failed");
            json_free(envelope);
            continue;
        }
        Json *request = json_get(envelope, "request");
        if (request) {
            Json copy = *request;
            handle_request(c, &copy);
        } else {
            resp_error(c, "invalid request: missing field `request`");
        }
        json_free(envelope);
    }
}

static void pump_pty(Terminal *t) {
    if (t->pty.master < 0) return;
    for (;;) {
        unsigned char chunk[READ_CHUNK];
        ssize_t n = read(t->pty.master, chunk, sizeof(chunk));
        if (n > 0) {
            uint64_t start = 0, end = 0;
            replay_append(t, chunk, (size_t)n, &start, &end);
            screen_feed(&t->screen, chunk, (size_t)n);
            scan_title(t, chunk, (size_t)n);
            broadcast_output(t, start, end, chunk, (size_t)n);
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
        t->eof = 1;
        break;
    }
    /* Publish only after both the child is reaped and the master has reached EOF, so no
     * trailing output is lost when the browser switches to EXITED on this event. */
    int state = pty_wait(&t->pty);
    if (state > 0 && t->eof && !t->exit_sent) {
        t->exit_sent = 1;
        if (t->pty.exited_at < t->started_at) t->pty.exited_at = t->started_at;
        broadcast_exited(t);
        /* Stop polling the dead master but keep argv/cwd for later snapshots. */
        if (!t->fd_closed && t->pty.master >= 0) {
            close(t->pty.master);
            t->pty.master = -1;
        }
        t->fd_closed = 1;
    }
}

/*
 * Pipe output is persisted to output_path first, then replayed/broadcast with
 * the same offset semantics as a terminal. A file write failure is reported in
 * PipeInfo/Exited but never stalls the reader.
 */
static void pump_pipe(Terminal *t) {
    if (!t || t->mode != MODE_PIPE) return;
    if (t->pty.master >= 0 && !t->eof) {
        for (;;) {
            unsigned char chunk[READ_CHUNK];
            ssize_t n = read(t->pty.master, chunk, sizeof(chunk));
            if (n > 0) {
                if (t->output_fd >= 0 && !t->output_error) {
                    size_t off = 0;
                    while (off < (size_t)n) {
                        ssize_t w = write(t->output_fd, chunk + off, (size_t)n - off);
                        if (w > 0) {
                            for (ssize_t k = 0; k < w; k++) {
                                if (chunk[off + (size_t)k] == '\n') t->output_lines++;
                            }
                            off += (size_t)w;
                            continue;
                        }
                        if (w < 0 && errno == EINTR) continue;
                        t->output_error = xstrdup(strerror(errno ? errno : EIO));
                        break;
                    }
                }
                uint64_t start = 0, end = 0;
                replay_append(t, chunk, (size_t)n, &start, &end);
                broadcast_output(t, start, end, chunk, (size_t)n);
                continue;
            }
            if (n < 0 && errno == EINTR) continue;
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
            t->eof = 1;
            break;
        }
    }

    int state = pty_wait(&t->pty);
    if (state < 0) return;
    if (state > 0 && !t->drain_deadline) {
        /* Upstream drains 1s in case a grandchild still holds the write end. */
        t->drain_deadline = monotonic_ms() + DRAIN_AFTER_EXIT_MS;
    }
    if (!t->exit_sent && state > 0 &&
        (t->eof || (t->drain_deadline && monotonic_ms() >= t->drain_deadline))) {
        t->exit_sent = 1;
        if (t->pty.exited_at < t->started_at) t->pty.exited_at = t->started_at;
        broadcast_exited(t);
        if (!t->fd_closed && t->pty.master >= 0) {
            close(t->pty.master);
            t->pty.master = -1;
        }
        t->fd_closed = 1;
    }
}

static void pump_process(Terminal *t) {
    if (!t) return;
    if (t->mode == MODE_PIPE) pump_pipe(t);
    else pump_pty(t);
}

static void close_finished_clients(void) {
    for (size_t i = 0; i < g_client_count;) {
        Client *c = g_clients[i];
        if (c->closing && c->out.len == 0) client_remove(i);
        else i++;
    }
}

enum { SLOT_LISTENER, SLOT_CLIENT, SLOT_PTY };

static void event_loop(void) {
    while (!g_stop) {
        ownership_tick(monotonic_ms());
        if (g_stop) break;
        size_t max_fds = 1 + g_client_count + g_terminal_count;
        struct pollfd *fds = xcalloc(max_fds, sizeof(struct pollfd));
        unsigned char *kinds = xcalloc(max_fds, 1);
        size_t *indexes = xmalloc(max_fds * sizeof(size_t));
        size_t nfds = 0;
        if (g_listener >= 0) {
            fds[nfds].fd = g_listener;
            fds[nfds].events = POLLIN;
            kinds[nfds] = SLOT_LISTENER;
            nfds++;
        }
        for (size_t i = 0; i < g_client_count; i++) {
            fds[nfds].fd = g_clients[i]->fd;
            fds[nfds].events = POLLIN;
            if (g_clients[i]->out.len) fds[nfds].events |= POLLOUT;
            kinds[nfds] = SLOT_CLIENT;
            indexes[nfds] = i;
            nfds++;
        }
        for (size_t i = 0; i < g_terminal_count; i++) {
            fds[nfds].fd = g_terminals[i]->pty.master;
            fds[nfds].events = POLLIN;
            kinds[nfds] = SLOT_PTY;
            indexes[nfds] = i;
            nfds++;
        }
        int ready = poll(fds, nfds, 50);
        if (ready < 0 && errno != EINTR) {
            free(fds);
            free(kinds);
            free(indexes);
            break;
        }
        if (ready > 0) {
            for (size_t i = 0; i < nfds; i++) {
                if (kinds[i] == SLOT_LISTENER) {
                    if (fds[i].revents & POLLIN) {
                        int fd = accept(g_listener, NULL, NULL);
                        if (fd >= 0) client_add(fd);
                    }
                    continue;
                }
                if (kinds[i] == SLOT_CLIENT) {
                    size_t slot = indexes[i];
                    if (slot >= g_client_count || fds[i].fd != g_clients[slot]->fd) continue;
                    Client *c = g_clients[slot];
                    if (fds[i].revents & POLLOUT) client_flush(c);
                    if (fds[i].revents & POLLIN) {
                        unsigned char chunk[65536];
                        ssize_t n = read(c->fd, chunk, sizeof(chunk));
                        if (n > 0) {
                            if (buf_append(&c->in, chunk, (size_t)n)) c->closing = 1;
                            else client_requests(c);
                        } else if (n == 0 || (n < 0 && errno != EAGAIN && errno != EINTR)) {
                            c->closing = 1;
                        }
                    }
                    if (fds[i].revents & (POLLHUP | POLLERR | POLLNVAL)) c->closing = 1;
                    continue;
                }
                if (kinds[i] == SLOT_PTY) {
                    size_t slot = indexes[i];
                    if (slot < g_terminal_count && fds[i].fd == g_terminals[slot]->pty.master &&
                        (fds[i].revents & (POLLIN | POLLHUP))) {
                        pump_process(g_terminals[slot]);
                    }
                }
            }
        }
        for (size_t i = 0; i < g_terminal_count; i++) pump_process(g_terminals[i]);
        close_finished_clients();
        free(fds);
        free(kinds);
        free(indexes);
    }
}

static int valid_name(const char *name) {
    if (!*name || !strcmp(name, ".") || !strcmp(name, "..") || strchr(name, '/')) return 0;
    for (const unsigned char *p = (const unsigned char *)name; *p; p++) {
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_' || *p == '-')) return 0;
    }
    return 1;
}

static int run_daemon(int argc, char **argv) {
    const char *root = NULL;
    const char *name = NULL;
    for (int i = 2; i < argc; i++) {
        if (!strcmp(argv[i], "--runtime-dir") && i + 1 < argc) root = argv[++i];
        else if (!strcmp(argv[i], "--name") && i + 1 < argc) name = argv[++i];
    }
    if (!root || !name || !valid_name(name) || root[0] != '/') {
        fputs("usage: opencode-pty daemon --runtime-dir DIR --name NAME\n", stderr);
        return 2;
    }
    if (snprintf(g_runtime_dir, sizeof(g_runtime_dir), "%s/%s", root, name) >=
        (int)sizeof(g_runtime_dir)) {
        fputs("opencode-pty: runtime directory path is too long\n", stderr);
        return 2;
    }
    if (mkdir_p(g_runtime_dir)) {
        perror("opencode-pty: runtime directory");
        return 1;
    }
    chmod(g_runtime_dir, 0700);
    char lock_path[PATH_MAX];
    if (runtime_file_path("service.lock", lock_path, sizeof(lock_path))) {
        fputs("opencode-pty: lock path is too long\n", stderr);
        return 2;
    }
    g_lock_fd = open(lock_path, O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    if (g_lock_fd < 0 || flock(g_lock_fd, LOCK_EX | LOCK_NB)) {
        fputs("opencode-pty: another daemon owns this runtime directory\n", stderr);
        return 1;
    }
    if (compute_socket_path(g_runtime_dir)) return 1;
    unlink(g_socket_path);
    g_listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (g_listener < 0) {
        perror("opencode-pty: socket");
        return 1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strcpy(addr.sun_path, g_socket_path);
    if (bind(g_listener, (struct sockaddr *)&addr, sizeof(addr))) {
        perror("opencode-pty: bind");
        return 1;
    }
    chmod(g_socket_path, 0600);
    struct stat socket_stat;
    if (lstat(g_socket_path, &socket_stat) == 0) {
        g_socket_dev = socket_stat.st_dev;
        g_socket_ino = socket_stat.st_ino;
    }
    if (listen(g_listener, 64)) {
        perror("opencode-pty: listen");
        return 1;
    }
    int flags = fcntl(g_listener, F_GETFL, 0);
    if (flags >= 0) fcntl(g_listener, F_SETFL, flags | O_NONBLOCK);
    random_hex(g_instance);
    random_hex(g_token);
    g_start_deadline = monotonic_ms() + 5000;
    if (write_registration()) {
        perror("opencode-pty: registration");
        return 1;
    }
    event_loop();
    /* The shutdown reply must reach the client before the socket is removed. */
    for (size_t i = 0; i < g_client_count; i++) client_flush(g_clients[i]);
    for (size_t i = 0; i < g_terminal_count; i++) pty_close(&g_terminals[i]->pty);
    close(g_listener);
    g_listener = -1;
    cleanup_runtime();
    return 0;
}

int main(int argc, char **argv) {
    keep_semaphore_fd();
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    if (argc >= 2 && (!strcmp(argv[1], "--version") || !strcmp(argv[1], "-V"))) {
        puts("opencode-pty 1.0.0 (android-c, protocol 9)");
        return 0;
    }
    if (argc >= 2 && !strcmp(argv[1], "daemon")) return run_daemon(argc, argv);
    fputs("usage: opencode-pty daemon --runtime-dir DIR --name NAME\n", stderr);
    return 2;
}

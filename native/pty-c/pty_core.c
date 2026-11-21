#define _GNU_SOURCE
#include "pty_core.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

extern char **environ;

uint64_t pty_now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts)) return 0;
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

int pty_spawn(const char *program, char *const argv[], char *const envp[], const char *cwd,
              int cols, int rows, Pty *out) {
    memset(out, 0, sizeof(*out));
    out->master = -1;
    out->pid = -1;
    out->cols = cols;
    out->rows = rows;

    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) return -1;
    if (grantpt(master) || unlockpt(master)) {
        close(master);
        return -1;
    }
    char *name = ptsname(master);
    if (!name) {
        close(master);
        return -1;
    }
    int slave = open(name, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) {
        close(master);
        return -1;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    ioctl(slave, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        close(slave);
        close(master);
        return -1;
    }
    if (pid == 0) {
        close(master);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        if (chdir(cwd)) _exit(127);
        execve(program, argv, envp ? envp : environ);
        _exit(127);
    }
    close(slave);
    int flags = fcntl(master, F_GETFL, 0);
    if (flags >= 0) fcntl(master, F_SETFL, flags | O_NONBLOCK);
    out->master = master;
    out->pid = pid;
    out->argc = 0;
    while (argv[out->argc]) out->argc++;
    out->argv = calloc(out->argc + 1, sizeof(char *));
    if (!out->argv) {
        pty_close(out);
        return -1;
    }
    for (size_t i = 0; i < out->argc; i++) {
        out->argv[i] = strdup(argv[i]);
        if (!out->argv[i]) {
            pty_close(out);
            return -1;
        }
    }
    return 0;
}

/*
 * Pipe processes are used by the `shell` tool: no terminal, stdin closed,
 * stdout and stderr merged into a single pipe. The daemon owns the output file
 * and writes every byte it reads from the pipe there before broadcasting it.
 * This mirrors the upstream process daemon's platform pipe spawner.
 */
int pipe_spawn(const char *program, char *const argv[], char *const envp[], const char *cwd,
               const char *output_path, int *out_sink, Pty *out) {
    memset(out, 0, sizeof(*out));
    out->master = -1;
    out->pid = -1;
    if (!output_path || !*output_path || output_path[0] != '/' || !out_sink) return -1;

    int fds[2];
    if (pipe2(fds, O_CLOEXEC)) return -1;
    /* O_NOFOLLOW: never write through a symlink planted at the final component. */
    int sink = open(output_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (sink < 0) {
        close(fds[0]);
        close(fds[1]);
        return -1;
    }
    *out_sink = sink;

    pid_t pid = fork();
    if (pid < 0) {
        close(sink);
        close(fds[0]);
        close(fds[1]);
        *out_sink = -1;
        return -1;
    }
    if (pid == 0) {
        close(sink);
        close(fds[0]);
        setpgid(0, 0);
        int devnull = open("/dev/null", O_RDONLY | O_CLOEXEC);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            if (devnull > STDERR_FILENO) close(devnull);
        }
        dup2(fds[1], STDOUT_FILENO);
        dup2(fds[1], STDERR_FILENO);
        if (fds[1] > STDERR_FILENO) close(fds[1]);
        /* Do not inherit handlers or a signal mask from the daemon. */
        const int reset[] = { SIGHUP, SIGINT, SIGQUIT, SIGTERM, SIGALRM, SIGCHLD };
        for (size_t i = 0; i < sizeof(reset) / sizeof(reset[0]); i++) signal(reset[i], SIG_DFL);
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        if (chdir(cwd)) _exit(127);
        execve(program, argv, envp ? envp : environ);
        _exit(127);
    }

    close(fds[1]);
    int flags = fcntl(fds[0], F_GETFL, 0);
    if (flags >= 0) fcntl(fds[0], F_SETFL, flags | O_NONBLOCK);
    out->master = fds[0];
    out->pid = pid;
    out->argc = 0;
    while (argv[out->argc]) out->argc++;
    out->argv = calloc(out->argc + 1, sizeof(char *));
    if (!out->argv) {
        close(sink);
        *out_sink = -1;
        pty_close(out);
        return -1;
    }
    for (size_t i = 0; i < out->argc; i++) {
        out->argv[i] = strdup(argv[i]);
        if (!out->argv[i]) {
            close(sink);
            *out_sink = -1;
            pty_close(out);
            return -1;
        }
    }
    return 0;
}

int pty_resize(Pty *pty, int cols, int rows) {
    if (!pty || pty->master < 0) return -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    if (ioctl(pty->master, TIOCSWINSZ, &ws)) return -1;
    pty->cols = cols;
    pty->rows = rows;
    return 0;
}

int pty_wait(Pty *pty) {
    if (!pty || pty->exited || pty->pid <= 0) return pty ? pty->exited : 1;
    int status = 0;
    pid_t done = waitpid(pty->pid, &status, WNOHANG);
    if (done != pty->pid) return done < 0 && errno != ECHILD ? -1 : 0;
    pty->signaled = 0;
    pty->signal_number = 0;
    if (WIFEXITED(status)) pty->exit_code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) {
        pty->signaled = 1;
        pty->signal_number = WTERMSIG(status);
        pty->exit_code = 128 + pty->signal_number;
    } else pty->exit_code = 0;
    pty->exited = 1;
    pty->exited_at = pty_now_ms();
    return 1;
}

void pty_signal(Pty *pty, int sig) {
    if (!pty || pty->pid <= 0 || pty->exited) return;
    if (kill(-pty->pid, sig) && errno == ESRCH) kill(pty->pid, sig);
}

void pty_close(Pty *pty) {
    if (!pty) return;
    if (pty->pid > 0 && !pty->exited) {
        pty_signal(pty, SIGHUP);
        pty_signal(pty, SIGTERM);
        pty_signal(pty, SIGKILL);
        int status = 0;
        while (waitpid(pty->pid, &status, 0) < 0 && errno == EINTR) {
        }
        pty->exited = 1;
        pty->exited_at = pty_now_ms();
        if (WIFSIGNALED(status)) {
            pty->signaled = 1;
            pty->signal_number = WTERMSIG(status);
            pty->exit_code = 128 + pty->signal_number;
        }
    }
    if (pty->master >= 0) close(pty->master);
    pty->master = -1;
    if (pty->argv) {
        for (size_t i = 0; i < pty->argc; i++) free(pty->argv[i]);
        free(pty->argv);
        pty->argv = NULL;
    }
}

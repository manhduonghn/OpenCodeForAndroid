/* Pure C PTY lifecycle used by the app-built opencode-pty helper. */
#ifndef OPENCODE_PTY_CORE_H
#define OPENCODE_PTY_CORE_H

#include <stdint.h>
#include <sys/types.h>

typedef struct {
    int master;
    pid_t pid;
    int cols;
    int rows;
    int exited;
    int exit_code;
    int signaled;
    int signal_number;
    uint64_t exited_at;
    char **argv;
    size_t argc;
} Pty;

int pty_spawn(const char *program, char *const argv[], char *const envp[], const char *cwd,
              int cols, int rows, Pty *out);
/*
 * Non-terminal process: stdin is /dev/null, stdout and stderr share one pipe,
 * and the caller writes those bytes to `output_path`. The read end is stored in
 * `out->master` so the daemon's existing poll/pump loop can consume it.
 */
int pipe_spawn(const char *program, char *const argv[], char *const envp[], const char *cwd,
               const char *output_path, int *out_sink, Pty *out);
int pty_resize(Pty *pty, int cols, int rows);
int pty_wait(Pty *pty);
void pty_signal(Pty *pty, int sig);
void pty_close(Pty *pty);
uint64_t pty_now_ms(void);

#endif

/*
 * Static C fixture of the native tests. It is built with
 * "aarch64-linux-gnu-gcc -static" and started through qemu-aarch64; the commands
 * mirror native/test/main.go so the same assertions cover both patchers.
 *
 * The interesting part is that a static program makes its own system calls:
 * there is no PT_INTERP and LD_PRELOAD cannot reach it. native/gopatch rewrites
 * every "svc #0" of the program, so after "oc-gopatch patch" the paths below
 * resolve inside the private prefix and the blocked calls return ENOSYS.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static void fail(const char *what)
{
	printf("%s: %s\n", what, strerror(errno));
	exit(1);
}

static void cmd_open(const char *path)
{
	int fd = open(path, O_RDONLY);
	if (fd < 0)
		fail("open");
	char buf[65];
	ssize_t n = read(fd, buf, sizeof buf - 1);
	if (n < 0)
		fail("read");
	buf[n] = '\0';
	printf("content: %s\n", buf);
	close(fd);
}

static void cmd_getcwd(const char *dir)
{
	if (chdir(dir) != 0)
		fail("chdir");
	char cwd[512];
	if (getcwd(cwd, sizeof cwd) == NULL)
		fail("getcwd");
	printf("cwd: %s\n", cwd);
}

static void cmd_setuid(void)
{
	/* Android's seccomp filter rejects setuid; the stub reports success. */
	if (setuid(12345) != 0)
		fail("setuid");
	printf("setuid: ok\n");
}

static void cmd_clone3(void)
{
	long r = syscall(435, 0, 0, 0); /* clone3 */
	if (r != -1 || errno != ENOSYS)
		fail("clone3");
	printf("clone3: enosys\n");
}

static void cmd_getgroups(void)
{
	gid_t list[8];
	int n = getgroups(8, list);
	if (n < 0)
		fail("getgroups");
	if (n != 1 || list[0] != 0) {
		printf("getgroups: %d groups, first %d\n", n, (int)list[0]);
		exit(1);
	}
	printf("getgroups: one empty group\n");
}

static void cmd_blocked(void)
{
	static const struct {
		const char *name;
		long nr;
	} calls[] = {
		{"faccessat2", 439},
		{"openat2", 437},
		{"name_to_handle_at", 264},
		{"chroot", 51},
	};
	for (size_t i = 0; i < sizeof calls / sizeof calls[0]; i++) {
		/* These are missing from Android's seccomp allowlist. The patched
		 * program must see ENOSYS and use its fallback instead of being
		 * killed by the SIGSYS trap. */
		long r = syscall(calls[i].nr, -100, "/tmp/oc-blocked", 0, 0, 0, 0);
		if (r != -1 || errno != ENOSYS) {
			printf("%s: not enosys\n", calls[i].name);
			exit(1);
		}
	}
	printf("blocked: enosys\n");
}

static void cmd_fork(void)
{
	/* fork() goes through clone (220), which the patcher leaves alone. */
	pid_t p = fork();
	if (p < 0)
		fail("fork");
	if (p == 0)
		_exit(7);
	int st = 0;
	if (waitpid(p, &st, 0) != p)
		fail("waitpid");
	if (!WIFEXITED(st) || WEXITSTATUS(st) != 7) {
		printf("fork: bad status %d\n", st);
		exit(1);
	}
	printf("fork: ok\n");
}

int main(int argc, char **argv)
{
	if (argc < 2) {
		printf("usage: fixture open PATH | getcwd DIR | setuid | clone3 | getgroups | blocked | fork\n");
		return 1;
	}
	if (!strcmp(argv[1], "open") && argc == 3)
		cmd_open(argv[2]);
	else if (!strcmp(argv[1], "getcwd") && argc == 3)
		cmd_getcwd(argv[2]);
	else if (!strcmp(argv[1], "setuid"))
		cmd_setuid();
	else if (!strcmp(argv[1], "clone3"))
		cmd_clone3();
	else if (!strcmp(argv[1], "getgroups"))
		cmd_getgroups();
	else if (!strcmp(argv[1], "blocked"))
		cmd_blocked();
	else if (!strcmp(argv[1], "fork"))
		cmd_fork();
	else {
		printf("unknown command %s\n", argv[1]);
		return 1;
	}
	return 0;
}

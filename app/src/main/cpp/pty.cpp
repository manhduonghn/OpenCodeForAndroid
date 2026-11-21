// Pseudo-terminal bridge for the interactive terminal.
//
// The JVM side asks for a master/slave pair, a child in its own session with
// the slave as controlling terminal, and the shell exec'ed on it. Everything
// that allocates happens before fork(); the child only uses async-signal-safe
// calls until execve(). Reads and writes of the master descriptor happen on the
// Java side through a ParcelFileDescriptor.

#include <jni.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <string>
#include <vector>

namespace {

/** Owns a file descriptor and closes it on every exit path. */
class Descriptor {
public:
    Descriptor() = default;
    explicit Descriptor(int value) : value_(value) {}
    ~Descriptor() { close(); }

    Descriptor(const Descriptor&) = delete;
    Descriptor& operator=(const Descriptor&) = delete;

    int get() const { return value_; }
    bool valid() const { return value_ >= 0; }

    int release() {
        const int value = value_;
        value_ = -1;
        return value;
    }

    void close() {
        if (value_ >= 0) {
            ::close(value_);
            value_ = -1;
        }
    }

private:
    int value_ = -1;
};

void throwIOException(JNIEnv *env, const char *action, int error) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", action, strerror(error));
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message);
}

std::string utf8(JNIEnv *env, jstring value) {
    const char *characters = env->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) return {};
    std::string copy(characters);
    env->ReleaseStringUTFChars(value, characters);
    return copy;
}

/** Copies a Java string array into stable C strings and a NULL terminated argv. */
std::vector<char *> argumentVector(JNIEnv *env, jobjectArray array, std::vector<std::string> &storage) {
    const jsize count = array == nullptr ? 0 : env->GetArrayLength(array);
    storage.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; index++) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, index));
        if (item == nullptr) continue;
        storage.push_back(utf8(env, item));
        env->DeleteLocalRef(item);
    }
    std::vector<char *> pointers;
    pointers.reserve(storage.size() + 1);
    for (auto &value : storage) pointers.push_back(value.data());
    pointers.push_back(nullptr);
    return pointers;
}

/**
 * Closes every descriptor above stderr. close_range is one syscall; when the
 * kernel or the headers do not provide it, /proc/self/fd lists what is open.
 */
void closeInheritedDescriptors() {
#ifdef __NR_close_range
    if (syscall(__NR_close_range, 3, ~0U, 0) == 0) return;
#endif
    DIR *directory = opendir("/proc/self/fd");
    if (directory == nullptr) {
        const long maximum = sysconf(_SC_OPEN_MAX);
        for (int fd = 3; fd < maximum; fd++) ::close(fd);
        return;
    }
    const int listing = dirfd(directory);
    while (struct dirent *entry = readdir(directory)) {
        const int fd = atoi(entry->d_name);
        if (fd > STDERR_FILENO && fd != listing) ::close(fd);
    }
    closedir(directory);
}

/**
 * The relocated libandroid-posix-semaphore.so builds semaphore names below
 * /proc/self/fd/9/sem.. Open descriptor 9 on the real directory after
 * closeInheritedDescriptors(), so the shell and every program it starts can
 * use the short alias regardless of the install path length. No O_CLOEXEC is
 * used: the descriptor must survive execve(). The directory comes from the
 * environment passed to execve, not from the app process environment.
 */
void keepSemaphoreDescriptor(char *const *environment) {
    const char prefix[] = "OC_SEMAPHORE_DIR=";
    const char *directory = nullptr;
    for (char *const *item = environment; item != nullptr && *item != nullptr; ++item) {
        if (strncmp(*item, prefix, sizeof(prefix) - 1) == 0) {
            directory = *item + sizeof(prefix) - 1;
            break;
        }
    }
    if (directory == nullptr || *directory == '\0') return;

    int descriptor = ::open(directory, O_RDONLY | O_DIRECTORY);
    if (descriptor < 0) {
        ::mkdir(directory, 0700);
        descriptor = ::open(directory, O_RDONLY | O_DIRECTORY);
    }
    if (descriptor < 0) return;
    if (descriptor != 9) {
        if (dup2(descriptor, 9) < 0) {
            ::close(descriptor);
            return;
        }
        ::close(descriptor);
    }
}

void resetSignalState() {
    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, nullptr);

    struct sigaction action {};
    action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    for (int signalNumber = 1; signalNumber < NSIG; signalNumber++) {
        sigaction(signalNumber, &action, nullptr);
    }
}

/** Runs in the child after fork; must not allocate or touch the JVM. */
[[noreturn]] void runChild(
    const char *slavePath,
    const char *workingDirectory,
    const char *executable,
    char *const *arguments,
    char *const *environment) {
    resetSignalState();
    setsid();

    Descriptor slave(::open(slavePath, O_RDWR | O_NOCTTY));
    if (!slave.valid()) _exit(126);
    ioctl(slave.get(), TIOCSCTTY, 0);

    for (int target = 0; target <= 2; target++) {
        if (dup2(slave.get(), target) < 0) _exit(126);
    }
    if (slave.get() > STDERR_FILENO) slave.close();
    closeInheritedDescriptors();
    keepSemaphoreDescriptor(environment);

    if (chdir(workingDirectory) != 0) chdir("/");
    execve(executable, arguments, environment);

    const char failure[] = "pty: cannot execute the shell\r\n";
    ssize_t ignored = write(STDERR_FILENO, failure, sizeof(failure) - 1);
    (void) ignored;
    _exit(127);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_opencode_android_terminal_Pty_nativeStart(
    JNIEnv *env, jclass, jstring jpath, jobjectArray jargv, jobjectArray jenvp,
    jstring jcwd, jint rows, jint cols, jintArray jpid) {
    const std::string path = utf8(env, jpath);
    const std::string workingDirectory = utf8(env, jcwd);
    std::vector<std::string> argumentStorage;
    std::vector<std::string> environmentStorage;
    std::vector<char *> arguments = argumentVector(env, jargv, argumentStorage);
    std::vector<char *> environment = argumentVector(env, jenvp, environmentStorage);

    Descriptor master(::open("/dev/ptmx", O_RDWR | O_CLOEXEC));
    if (!master.valid()) {
        throwIOException(env, "open /dev/ptmx", errno);
        return -1;
    }

    char slavePath[64];
    if (grantpt(master.get()) != 0 || unlockpt(master.get()) != 0 ||
        ptsname_r(master.get(), slavePath, sizeof(slavePath)) != 0) {
        throwIOException(env, "prepare pseudo-terminal", errno);
        return -1;
    }

    // UTF-8 input so bash and readline map multi-byte characters correctly.
    // Software flow control is off so Ctrl+S and Ctrl+Q reach the program.
    struct termios settings {};
    if (tcgetattr(master.get(), &settings) == 0) {
#ifdef IUTF8
        settings.c_iflag |= IUTF8;
#endif
        settings.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(master.get(), TCSANOW, &settings);
    }

    struct winsize size {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(cols);
    ioctl(master.get(), TIOCSWINSZ, &size);

    const pid_t pid = fork();
    if (pid < 0) {
        throwIOException(env, "fork", errno);
        return -1;
    }
    if (pid == 0) {
        runChild(
            slavePath,
            workingDirectory.c_str(),
            path.c_str(),
            arguments.data(),
            environment.data());
    }

    const jint child = pid;
    env->SetIntArrayRegion(jpid, 0, 1, &child);
    return master.release();
}

extern "C" JNIEXPORT void JNICALL
Java_com_opencode_android_terminal_Pty_nativeResize(
    JNIEnv *, jclass, jint fd, jint rows, jint cols, jint widthPixels, jint heightPixels) {
    struct winsize size {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(cols);
    size.ws_xpixel = static_cast<unsigned short>(widthPixels);
    size.ws_ypixel = static_cast<unsigned short>(heightPixels);
    ioctl(fd, TIOCSWINSZ, &size);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opencode_android_terminal_Pty_nativeWaitFor(JNIEnv *, jclass, jint pid) {
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opencode_android_terminal_Pty_nativeForegroundGroup(JNIEnv *, jclass, jint fd) {
    return tcgetpgrp(fd);
}

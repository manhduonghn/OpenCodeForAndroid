package com.opencode.android.terminal

import com.opencode.android.OpenCodeBootstrap
import com.opencode.android.TermuxSemaphorePath
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The executable, arguments and environment of one interactive shell. */
internal class TerminalLaunch(
    val shellName: String,
    val executable: String,
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: File,
)

/**
 * Builds the command line of the interactive shell inside the app's prefix.
 *
 * Bash is the login shell of the packaged runtime, so it is used whenever the
 * prefix has one. Its managed rc file sources the package environment (PATH,
 * TMPDIR, LD_LIBRARY_PATH, certificates, the JDK) and then the user's
 * `~/.bashrc`; without it an interactive shell would miss everything the rest
 * of the app installs. Android's `sh` is only the fallback when the runtime is
 * still missing, and its rc file explains that instead of letting `pkg` fail
 * with a bare "command not found".
 */
internal object TerminalLauncher {
    private const val SYSTEM_LINKER = "/system/bin/linker64"

    /** Managed rc file of bash, replaced when its content changes. */
    private val BASH_RC = File(OpenCodeBootstrap.PREFIX, "etc/opencode-terminal.bashrc")

    /** Managed rc file of the fallback shell, read through `$ENV` by Android's `sh`. */
    private val FALLBACK_RC = File(OpenCodeBootstrap.PREFIX, "etc/opencode-terminal.sh")

    fun launch(rows: Int, cols: Int): TerminalLaunch {
        val home = File(OpenCodeBootstrap.HOME).apply { mkdirs() }
        val tmp = File(OpenCodeBootstrap.PREFIX, "tmp").apply { mkdirs() }
        val bash = File(OpenCodeBootstrap.PREFIX, "bin/bash")
        if (bash.isFile && bash.canExecute()) {
            // targetSdk 36 denies execve() of app data files. The app process is forked from
            // zygote, so a late LD_PRELOAD would not affect it: start the system linker
            // directly and let it mmap bash. The shim is loaded into bash from that env and
            // takes over every later exec (pkg, git, scripts, glibc programs).
            return TerminalLaunch(
                shellName = "bash",
                executable = SYSTEM_LINKER,
                argv = listOf(
                    SYSTEM_LINKER,
                    bash.absolutePath,
                    "--rcfile",
                    install(BASH_RC, bashRc()).absolutePath,
                    "-i",
                ),
                env = bashEnvironment(home, tmp, bash),
                cwd = home,
            )
        }

        // The runtime download failed or was never run. Android's shell is still
        // useful for looking at the device, but it must not inherit the prefix
        // libraries. It can still carry the bionic shim (a file in nativeLibraryDir)
        // so a later `pkg` install works without restarting the terminal. Its rc
        // file explains why `pkg` is missing; the notice offers a runtime retry.
        return TerminalLaunch(
            shellName = "sh",
            executable = "/system/bin/sh",
            argv = listOf("sh", "-i"),
            env = fallbackEnvironment(home, tmp),
            cwd = home,
        )
    }

    private fun bashEnvironment(home: File, tmp: File, bash: File): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            put("HOME", home.absolutePath)
            put("PREFIX", OpenCodeBootstrap.PREFIX)
            put("OC_PREFIX", OpenCodeBootstrap.PREFIX)
            put("OC_USER_HOME", home.absolutePath)
            put("PATH", "${OpenCodeBootstrap.PREFIX}/bin:/system/bin:/system/xbin:/vendor/bin")
            put("SHELL", bash.absolutePath)
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")
            put("TMPDIR", tmp.absolutePath)
            put("OC_SEMAPHORE_DIR", TermuxSemaphorePath.directory(File(OpenCodeBootstrap.PREFIX)).absolutePath)
            put("DEBIAN_FRONTEND", "noninteractive")
            put("OPENCODE_TERMINAL", "1")
            // Bash resolves its own libraries before the rc file runs; the rc
            // file repeats these for every child process.
            File(OpenCodeBootstrap.PREFIX, "lib").takeIf { it.isDirectory }?.let {
                put("LD_LIBRARY_PATH", it.absolutePath)
            }
            // Termux's ncurses searches the prefix terminfo database first.
            File(OpenCodeBootstrap.PREFIX, "share/terminfo").takeIf { it.isDirectory }?.let {
                put("TERMINFO", it.absolutePath)
            }
            putExecShim()
        }

    private fun fallbackEnvironment(home: File, tmp: File): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            put("HOME", home.absolutePath)
            put("PATH", "/system/bin:/system/xbin:/vendor/bin")
            put("SHELL", "/system/bin/sh")
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")
            put("TMPDIR", tmp.absolutePath)
            put("OC_SEMAPHORE_DIR", TermuxSemaphorePath.directory(File(OpenCodeBootstrap.PREFIX)).absolutePath)
            put("PS1", "\\$ ")
            put("ENV", install(FALLBACK_RC, fallbackRc()).absolutePath)
            putExecShim()
        }

    /** Environment entries that load the W^X shim in the first bionic process of the terminal. */
    private fun MutableMap<String, String>.putExecShim() {
        OpenCodeBootstrap.execShim()?.let {
            put("LD_PRELOAD", it)
            put("OC_EXEC_SHIM", it)
        }
        OpenCodeBootstrap.nativeGlibcLoader()?.let { put("OC_GLIBC_LOADER", it) }
        put("OC_GLIBC_LIB_DIR", OpenCodeBootstrap.GLIBC_LIB_DIR)
        put("OC_PREFIX", OpenCodeBootstrap.PREFIX)
        put("OC_USER_HOME", OpenCodeBootstrap.HOME)
    }

    private fun bashRc(): String = buildString {
        appendLine("# Managed by OpenCode for Android for the native terminal.")
        appendLine("# Replaced on every launch; put your own settings in ~/.bashrc.")
        appendLine("if [ -r \"\$PREFIX/etc/profile\" ]; then")
        appendLine("  . \"\$PREFIX/etc/profile\"")
        appendLine("else")
        appendLine("  [ -r \"\$PREFIX/etc/opencode-env.sh\" ] && . \"\$PREFIX/etc/opencode-env.sh\"")
        appendLine("  [ -r \"\$HOME/.bashrc\" ] && . \"\$HOME/.bashrc\"")
        appendLine("fi")
        // A user profile or ~/.bashrc may replace PATH; the packaged tools must
        // stay reachable either way.
        appendLine("case \":\${PATH:-}:\" in")
        appendLine("  *\":\$PREFIX/bin:\"*) ;;")
        appendLine("  *) PATH=\"\$PREFIX/bin\${PATH:+:\$PATH}\"; export PATH ;;")
        appendLine("esac")
        appendLine("HISTFILE=\"\$HOME/.bash_history\"")
        appendLine("HISTSIZE=5000")
        appendLine("HISTCONTROL=ignoredups")
        appendLine("shopt -s checkwinsize histappend 2>/dev/null")
        appendLine("PS1='\\[\\e[1;36m\\]\\w\\[\\e[0m\\] \\[\\e[1;32m\\]\\$\\[\\e[0m\\] '")
        appendLine("__oc_osc7() { printf '\\033]7;file://localhost%s\\033\\\\' \"\$PWD\"; }")
        appendLine("PROMPT_COMMAND=\"__oc_osc7\${PROMPT_COMMAND:+;\$PROMPT_COMMAND}\"")
        appendLine("alias ls='ls --color=auto' grep='grep --color=auto' 2>/dev/null")
        // Call the bridge by absolute path: a missing bin/pkg symlink (an
        // interrupted update) must not turn into "command not found", and the
        // check on every call picks up a repair without restarting the shell.
        appendLine("pkg() {")
        appendLine("  if [ -x \"\$PREFIX/libexec/oc-bridge\" ]; then")
        appendLine("    LD_LIBRARY_PATH= command \"\$PREFIX/libexec/oc-bridge\" \"\$@\"")
        appendLine("  else")
        appendLine("    printf '%s\\n' 'pkg: Linux runtime chưa sẵn sàng. Đóng rồi mở lại terminal để cài đặt.' >&2")
        appendLine("    return 127")
        appendLine("  fi")
        appendLine("}")
    }

    private fun fallbackRc(): String = buildString {
        appendLine("# Managed by OpenCode for Android for the fallback shell.")
        appendLine("# Replaced on every launch; there is no Linux runtime behind it.")
        appendLine("pkg() {")
        appendLine("  printf '%s\\n' 'pkg: Linux runtime chưa được cài. Nhấn \"Thử lại\" trong terminal để tải lại.' >&2")
        appendLine("  return 127")
        appendLine("}")
        appendLine("opencode() {")
        appendLine("  printf '%s\\n' 'opencode: Linux runtime chưa được cài. Nhấn \"Thử lại\" trong terminal để tải lại.' >&2")
        appendLine("  return 127")
        appendLine("}")
    }

    /**
     * Writes [content] to [target] only when it differs. The file is written to
     * a sibling and renamed into place, so a reader (or an interrupted launch)
     * never sees a partially written rc file.
     */
    private fun install(target: File, content: String): File {
        val directory = target.parentFile ?: return target
        directory.mkdirs()
        val unchanged = target.isFile && runCatching { target.readText() }.getOrNull() == content
        if (unchanged) return target

        val temporary = File.createTempFile("${target.name}.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return target
    }
}

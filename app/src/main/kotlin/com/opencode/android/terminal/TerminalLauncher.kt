package com.opencode.android.terminal

import com.opencode.android.OpenCodeBootstrap
import com.opencode.android.RuntimeEnv
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
        File(OpenCodeBootstrap.PREFIX, "tmp").mkdirs()
        // One contract for every layer. This is the same map that spawned the server and
        // rendered etc/opencode-env.sh: PATH, HOME, TMPDIR, LD_LIBRARY_PATH, OC_SEMAPHORE_DIR,
        // TERMINFO, the shim and every optional resolved path come from RuntimeContract, not
        // from a second hand-maintained list in the terminal.
        val contract = RuntimeEnv.build(OpenCodeBootstrap.runtimeInputs())
        val bash = File(OpenCodeBootstrap.PREFIX, "bin/bash")
        if (bash.isFile && bash.canExecute()) {
            // targetSdk 36 denies execve() of app data files. The app process is forked from
            // zygote, so a late LD_PRELOAD would not affect it: start the system linker
            // directly and let it mmap bash. The shim is loaded into bash from that env and
            // takes over every later exec (pkg, git, scripts, glibc programs). LD_LIBRARY_PATH
            // is already set there, so bash can resolve libandroid-support.so on its first exec.
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
                env = contract.shellEnvironment(terminal = true),
                cwd = home,
            )
        }

        // The runtime download failed or was never run. Android's shell is still useful for
        // looking at the device, but it must not use the prefix binaries. Only the paths that
        // are Android-specific are overridden here; every other value still comes from the
        // contract, so a later `pkg` install or a terminal restart sees the same runtime.
        val fallbackEnvironment = contract.shellEnvironment(terminal = true).toMutableMap().apply {
            put("PATH", "/system/bin:/system/xbin:/vendor/bin")
            put("SHELL", "/system/bin/sh")
            put("PS1", "\\$ ")
            put("ENV", install(FALLBACK_RC, fallbackRc()).absolutePath)
        }
        return TerminalLaunch(
            shellName = "sh",
            executable = "/system/bin/sh",
            argv = listOf("sh", "-i"),
            env = fallbackEnvironment,
            cwd = home,
        )
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

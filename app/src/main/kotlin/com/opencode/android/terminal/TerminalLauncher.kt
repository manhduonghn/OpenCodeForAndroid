// Adapted from AndroidHarness (https://github.com/Sanuu7/AndroidHarness), Copyright (c) 2026 Sanuu7,
// MIT License. See ANDROIDHARNESS_LICENSE.txt in app/src/main/cpp.

package com.opencode.android.terminal

import com.opencode.android.OpenCodeBootstrap
import java.io.File

/** The executable, arguments and environment of one interactive shell. */
internal class TerminalLaunch(
    val shellName: String,
    val executable: String,
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: File,
)

/**
 * Starts the interactive shell inside the app's own prefix.
 *
 * Bash is the only login shell of the runtime, so it is used whenever the
 * prefix has one. The managed rc file sources the package environment (PATH,
 * TMPDIR, LD_LIBRARY_PATH, certificates, the JDK…) and then the user's
 * `~/.bashrc`; without it an interactive shell would miss everything that the
 * rest of the app sets up. When the prefix is still missing the app downloads
 * the Linux runtime before it starts a shell (see [com.opencode.android.TerminalScreen]);
 * Android's own shell is only the last resort when that download failed.
 */
internal object TerminalLauncher {
    /** Managed rc file, rewritten when the app changes it. */
    private val RC_FILE = File(OpenCodeBootstrap.PREFIX, "etc/opencode-terminal.bashrc")

    /** Managed rc file of the fallback shell, read through `$ENV` by Android's `sh`. */
    private val FALLBACK_RC_FILE = File(OpenCodeBootstrap.PREFIX, "etc/opencode-terminal.sh")

    fun launch(rows: Int, cols: Int): TerminalLaunch {
        val home = File(OpenCodeBootstrap.HOME).apply { mkdirs() }
        val tmp = File(OpenCodeBootstrap.PREFIX, "tmp").apply { mkdirs() }
        val bash = File(OpenCodeBootstrap.PREFIX, "bin/bash")
        if (bash.isFile && bash.canExecute()) {
            val rc = writeRcFile()
            val env = LinkedHashMap<String, String>().apply {
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
                put("DEBIAN_FRONTEND", "noninteractive")
                put("OPENCODE_TERMINAL", "1")
                // Bash resolves its own libraries before the rc file runs. The
                // package environment repeats this for every child process.
                File(OpenCodeBootstrap.PREFIX, "lib")
                    .takeIf { it.isDirectory }
                    ?.let { put("LD_LIBRARY_PATH", it.absolutePath) }
                // Termux's ncurses searches its own terminfo build prefix first.
                File(OpenCodeBootstrap.PREFIX, "share/terminfo")
                    .takeIf { it.isDirectory }
                    ?.let { put("TERMINFO", it.absolutePath) }
            }
            return TerminalLaunch(
                shellName = "bash",
                executable = bash.absolutePath,
                argv = listOf(bash.absolutePath, "--rcfile", rc.absolutePath, "-i"),
                env = env,
                cwd = home,
            )
        }

        // No runtime yet (the download failed): Android's shell is still useful
        // for poking at the device. Do not hand it the prefix libraries or the
        // bionic preload. Its rc file explains why `pkg` is missing instead of
        // leaving the user with a bare "command not found".
        val env = LinkedHashMap<String, String>().apply {
            put("HOME", home.absolutePath)
            put("PATH", "/system/bin:/system/xbin:/vendor/bin")
            put("SHELL", "/system/bin/sh")
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")
            put("TMPDIR", tmp.absolutePath)
            put("PS1", "\\$ ")
            put("ENV", writeFallbackRcFile().absolutePath)
        }
        return TerminalLaunch(
            shellName = "sh",
            executable = "/system/bin/sh",
            argv = listOf("sh", "-i"),
            env = env,
            cwd = home,
        )
    }

    private fun writeRcFile(): File {
        val text = buildString {
            appendLine("# Managed by OpenCode for Android for the native terminal.")
            appendLine("# Replaced on every launch; put your own settings in ~/.bashrc.")
            appendLine("if [ -r \"\$PREFIX/etc/profile\" ]; then")
            appendLine("  . \"\$PREFIX/etc/profile\"")
            appendLine("else")
            appendLine("  [ -r \"\$PREFIX/etc/opencode-env.sh\" ] && . \"\$PREFIX/etc/opencode-env.sh\"")
            appendLine("  [ -r \"\$HOME/.bashrc\" ] && . \"\$HOME/.bashrc\"")
            appendLine("fi")
            // A user profile or ~/.bashrc may replace PATH; `pkg` and the packaged
            // tools must stay reachable.
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
            // Call the bridge by its absolute path, so a missing `bin/pkg` symlink
            // (an interrupted app update) cannot turn into "command not found",
            // and re-check it on every call so a repair by the app is picked up
            // without restarting the shell.
            appendLine("pkg() {")
            appendLine("  if [ -x \"\$PREFIX/libexec/oc-bridge\" ]; then")
            appendLine("    LD_LIBRARY_PATH= LD_PRELOAD= command \"\$PREFIX/libexec/oc-bridge\" \"\$@\"")
            appendLine("  else")
            appendLine("    printf '%s\\n' 'pkg: Linux runtime chưa sẵn sàng. Đóng rồi mở lại terminal để cài đặt.' >&2")
            appendLine("    return 127")
            appendLine("  fi")
            appendLine("}")
        }
        RC_FILE.parentFile?.mkdirs()
        if (!RC_FILE.isFile || RC_FILE.readText() != text) RC_FILE.writeText(text)
        return RC_FILE
    }

    /**
     * The rc file of the fallback shell. Android's `sh` reads `$ENV` when it is
     * interactive, so `pkg` can explain that the command needs the Linux runtime
     * instead of answering "not found". `pkg install` cannot work in this shell;
     * the terminal notice offers the retry that downloads the runtime.
     */
    private fun writeFallbackRcFile(): File {
        val text = buildString {
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
        FALLBACK_RC_FILE.parentFile?.mkdirs()
        if (!FALLBACK_RC_FILE.isFile || FALLBACK_RC_FILE.readText() != text) FALLBACK_RC_FILE.writeText(text)
        return FALLBACK_RC_FILE
    }
}

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
        // bionic preload.
        val env = LinkedHashMap<String, String>().apply {
            put("HOME", home.absolutePath)
            put("PATH", "/system/bin:/system/xbin:/vendor/bin")
            put("SHELL", "/system/bin/sh")
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")
            put("TMPDIR", tmp.absolutePath)
            put("PS1", "\\$ ")
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
            appendLine("HISTFILE=\"\$HOME/.bash_history\"")
            appendLine("HISTSIZE=5000")
            appendLine("HISTCONTROL=ignoredups")
            appendLine("shopt -s checkwinsize histappend 2>/dev/null")
            appendLine("PS1='\\[\\e[1;36m\\]\\w\\[\\e[0m\\] \\[\\e[1;32m\\]\\$\\[\\e[0m\\] '")
            appendLine("__oc_osc7() { printf '\\033]7;file://localhost%s\\033\\\\' \"\$PWD\"; }")
            appendLine("PROMPT_COMMAND=\"__oc_osc7\${PROMPT_COMMAND:+;\$PROMPT_COMMAND}\"")
            appendLine("alias ls='ls --color=auto' grep='grep --color=auto' 2>/dev/null")
        }
        RC_FILE.parentFile?.mkdirs()
        if (!RC_FILE.isFile || RC_FILE.readText() != text) RC_FILE.writeText(text)
        return RC_FILE
    }
}

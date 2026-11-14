// Adapted from AndroidHarness (https://github.com/Sanuu7/AndroidHarness), Copyright (c) 2026 Sanuu7,
// MIT License. See ANDROIDHARNESS_LICENSE.txt in app/src/main/cpp.

package com.opencode.android.terminal

/**
 * The one interactive shell of the app.
 *
 * The session is owned here rather than by [TerminalScreen], so hiding the panel,
 * rotating the device or sending the app to the background keeps the shell and
 * everything running in it alive. The screen only attaches to [session] when it
 * is visible.
 */
object TerminalSessions {

    private var current: TerminalSession? = null

    /** Grid size of the last view that showed the terminal, reused by a restart. */
    @Volatile
    var lastRows: Int = 24
        private set
    @Volatile
    var lastCols: Int = 80
        private set
    @Volatile
    var lastWidthPx: Int = 0
        private set
    @Volatile
    var lastHeightPx: Int = 0
        private set

    /** False in a build without the native PTY library. */
    val supported: Boolean get() = Pty.available

    val session: TerminalSession?
        @Synchronized get() = current

    /**
     * Start a shell, or resize and return the one that is already running.
     *
     * The caller runs this off the UI thread: writing the managed rc file touches
     * storage. Throws when the native library is missing or the PTY cannot be
     * opened; the screen turns the message into a retry notice.
     */
    @Synchronized
    fun start(rows: Int, cols: Int, widthPx: Int, heightPx: Int): TerminalSession {
        rememberGrid(rows, cols, widthPx, heightPx)
        current?.takeIf { it.running }?.let { running ->
            running.resize(rows, cols, widthPx, heightPx)
            return running
        }
        current = null
        val launch = TerminalLauncher.launch(rows, cols)
        val pty = Pty.start(
            executable = launch.executable,
            argv = launch.argv,
            env = launch.env,
            cwd = launch.cwd,
            rows = rows,
            cols = cols,
        )
        if (widthPx > 0 && heightPx > 0) pty.resize(rows, cols, widthPx, heightPx)
        return TerminalSession(launch.shellName, pty, rows, cols) { exited ->
            // A finished session stays available so the screen can show the last
            // output and the exit code; [restart] replaces it explicitly.
            if (current === exited) current = exited
        }.also { current = it }
    }

    fun rememberGrid(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        if (rows <= 0 || cols <= 0) return
        lastRows = rows
        lastCols = cols
        lastWidthPx = widthPx
        lastHeightPx = heightPx
    }

    /** Hangs up the shell and forgets it. */
    @Synchronized
    fun close() {
        val old = current ?: return
        current = null
        old.terminate()
    }

    /** Restart with the last known grid, so the new shell starts at the right size. */
    @Synchronized
    fun restart(): TerminalSession {
        val old = current
        current = null
        old?.terminate()
        return start(lastRows, lastCols, lastWidthPx, lastHeightPx)
    }

    /** Stop the shell when the app process is going away for good. */
    @Synchronized
    fun shutdown() {
        val old = current ?: return
        current = null
        old.terminate()
    }
}

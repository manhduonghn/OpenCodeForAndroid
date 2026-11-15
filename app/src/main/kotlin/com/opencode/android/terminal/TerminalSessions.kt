package com.opencode.android.terminal

/**
 * The single interactive shell of the app.
 *
 * The session is owned here instead of by a screen, so closing the terminal
 * panel, rotating the device or sending the app to the background leaves the
 * shell, its jobs and its scrollback intact. A screen attaches to [session]
 * only while it is visible.
 *
 * All lifecycle operations are serialised: starting, resizing, restarting and
 * closing cannot interleave and leave two PTYs or a half-closed session behind.
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
     * Starts a shell, or resizes and returns the one that is already running.
     *
     * Call this off the UI thread: writing the managed rc file touches storage
     * and opening the PTY forks a process. Throws when the native library is
     * missing or the PTY cannot be opened; the screen turns that into a retry
     * notice.
     */
    @Synchronized
    fun start(rows: Int, cols: Int, widthPx: Int, heightPx: Int): TerminalSession {
        rememberGrid(rows, cols, widthPx, heightPx)
        current?.takeIf { it.running }?.let { running ->
            running.resize(rows, cols, widthPx, heightPx)
            return running
        }

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
        val created = TerminalSession(launch.shellName, pty, rows, cols) { exited ->
            // A finished session stays available so the screen can show its last
            // output and exit code. Only clear it if it is still the current one;
            // restart() may already have installed a replacement.
            synchronized(this) {
                if (current === exited && !exited.running) current = exited
            }
        }
        current = created
        return created
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

    /** Restarts with the last known grid, so the new shell starts at the right size. */
    @Synchronized
    fun restart(): TerminalSession {
        val old = current
        current = null
        old?.terminate()
        return start(lastRows, lastCols, lastWidthPx, lastHeightPx)
    }

    /** Stops the shell when the app process is going away for good. */
    @Synchronized
    fun shutdown() {
        val old = current ?: return
        current = null
        old.terminate()
    }
}

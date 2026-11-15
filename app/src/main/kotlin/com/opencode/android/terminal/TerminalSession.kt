package com.opencode.android.terminal

import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A program running on a pseudo-terminal and the screen it draws.
 *
 * Output is read on a daemon thread and parsed on the emulator's lock; the
 * renderer takes the same lock, so it never sees a half-updated cursor or row.
 * Listeners are called without that lock held and a throwing listener is
 * isolated, because a UI callback must not be able to stop the reader.
 *
 * The session belongs to [TerminalSessions], not to a view. Hiding the panel or
 * backgrounding the app therefore leaves the shell and every process it started
 * alive.
 */
class TerminalSession internal constructor(
    val shellName: String,
    private val pty: Pty,
    rows: Int,
    cols: Int,
    private val onExit: (TerminalSession) -> Unit,
) {
    val emulator = TerminalEmulator(rows, cols, maxHistory = MAX_HISTORY)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val exitReported = AtomicBoolean(false)
    private val terminationStarted = AtomicBoolean(false)

    /** Exit code once the shell has ended, or null while it runs. */
    @Volatile
    var exitCode: Int? = null
        private set

    @Volatile
    var title: String = ""
        private set

    /** Path from the last OSC 7 report, when the shell sends one. */
    @Volatile
    var workingDirectory: String? = null
        private set

    @Volatile
    var onBell: (() -> Unit)? = null

    val running: Boolean get() = exitCode == null
    val pid: Int get() = pty.pid

    init {
        emulator.onReply = pty::write
        emulator.onBell = {
            val bell = onBell
            if (bell != null) runCatching(bell)
        }
        startReader()
        startWaiter()
    }

    private fun startReader() {
        thread(name = "opencode-terminal-read-${pty.pid}", isDaemon = true) {
            val buffer = ByteArray(READ_BUFFER)
            try {
                while (true) {
                    val count = pty.input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    synchronized(emulator) {
                        emulator.append(buffer, 0, count)
                        title = emulator.title
                        workingDirectory = emulator.workingDirectory
                    }
                    notifyListeners()
                }
            } catch (_: IOException) {
                // The slave side is closed once the shell and its jobs are gone.
            } finally {
                pty.close()
            }
        }
    }

    private fun startWaiter() {
        thread(name = "opencode-terminal-wait-${pty.pid}", isDaemon = true) {
            val code = pty.waitFor()
            // Jobs the shell left behind still hold the terminal open; HUP them
            // so the reader reaches end-of-file instead of blocking for ever.
            pty.signal(OsConstants.SIGHUP)
            exitCode = code
            notifyListeners()
            if (exitReported.compareAndSet(false, true)) onExit(this)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            // A listener runs UI code. One broken callback must not stop the
            // reader thread or hide output from the other listeners.
            runCatching(listener)
        }
    }

    fun write(bytes: ByteArray) {
        if (running) pty.write(bytes)
    }

    fun writeText(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun paste(text: String) {
        val bracketed = synchronized(emulator) { emulator.bracketedPaste }
        write(KeyEncoder.paste(text, bracketed))
    }

    fun resize(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        val changed = synchronized(emulator) {
            val beforeRows = emulator.rows
            val beforeCols = emulator.cols
            emulator.resize(rows, cols)
            beforeRows != emulator.rows || beforeCols != emulator.cols
        }
        if (!changed) return
        if (running) pty.resize(rows, cols, widthPx, heightPx)
        notifyListeners()
    }

    fun transcript(): String = synchronized(emulator) { emulator.transcriptText() }

    /**
     * Hangs up the shell and kills it if it ignored the first signal. The kill
     * is delayed because a shell writing its history needs a moment to exit.
     */
    fun terminate() {
        if (!terminationStarted.compareAndSet(false, true)) return
        pty.signal(OsConstants.SIGHUP)
        thread(name = "opencode-terminal-kill-${pty.pid}", isDaemon = true) {
            repeat(KILL_POLLS) {
                if (!running) return@thread
                Thread.sleep(KILL_POLL_MS)
            }
            if (running) pty.signal(OsConstants.SIGKILL)
        }
    }

    private companion object {
        const val MAX_HISTORY = 3_000
        const val READ_BUFFER = 16 * 1024
        const val KILL_POLLS = 15
        const val KILL_POLL_MS = 100L
    }
}

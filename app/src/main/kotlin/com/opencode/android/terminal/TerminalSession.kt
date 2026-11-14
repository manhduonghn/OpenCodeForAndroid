// Adapted from AndroidHarness (https://github.com/Sanuu7/AndroidHarness), Copyright (c) 2026 Sanuu7,
// MIT License. See ANDROIDHARNESS_LICENSE.txt in app/src/main/cpp.

package com.opencode.android.terminal

import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A running program on a pseudo-terminal plus the screen it draws. Output is
 * parsed on a reader thread; the UI reads [emulator] while holding its lock.
 *
 * The session lives in [TerminalSessions] rather than in a screen, so closing
 * the terminal panel or sending the app to the background keeps the shell
 * running.
 */
class TerminalSession internal constructor(
    val shellName: String,
    private val pty: Pty,
    rows: Int,
    cols: Int,
    private val onExit: (TerminalSession) -> Unit,
) {
    val emulator = TerminalEmulator(rows, cols, maxHistory = 3_000)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

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
        emulator.onReply = { pty.write(it) }
        emulator.onBell = { onBell?.invoke() }
        thread(name = "opencode-terminal-read-${pty.pid}", isDaemon = true) {
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = pty.input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(emulator) {
                        emulator.append(buffer, 0, n)
                        title = emulator.title
                        workingDirectory = emulator.workingDirectory
                    }
                    notifyListeners()
                }
            } catch (_: IOException) {
                // EIO once every holder of the slave side is gone.
            }
            pty.close()
        }
        thread(name = "opencode-terminal-wait-${pty.pid}", isDaemon = true) {
            val code = pty.waitFor()
            // Jobs the shell left behind still hold the terminal open; hang them up.
            pty.signal(OsConstants.SIGHUP)
            exitCode = code
            notifyListeners()
            onExit(this)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() = listeners.forEach { it() }

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
            val before = emulator.rows to emulator.cols
            emulator.resize(rows, cols)
            before != (emulator.rows to emulator.cols)
        }
        if (changed && running) pty.resize(rows, cols, widthPx, heightPx)
        if (changed) notifyListeners()
    }

    fun transcript(): String = synchronized(emulator) { emulator.transcriptText() }

    /** Hangs up the shell, then kills it if it is still there shortly after. */
    fun terminate() {
        if (!running) return
        pty.signal(OsConstants.SIGHUP)
        thread(name = "opencode-terminal-kill-${pty.pid}", isDaemon = true) {
            Thread.sleep(1_500)
            if (running) pty.signal(OsConstants.SIGKILL)
        }
    }
}

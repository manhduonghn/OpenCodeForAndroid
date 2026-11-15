package com.opencode.android.terminal

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A child process attached to a pseudo-terminal.
 *
 * The child is a session leader and the PTY slave is its controlling terminal,
 * so the line discipline implements Ctrl+C, Ctrl+Z and SIGWINCH for the
 * foreground job. The parent owns only the master descriptor: [input] reads
 * what the program writes and [write] sends keystrokes back.
 */
class Pty private constructor(
    val pid: Int,
    private val masterFd: Int,
    private val descriptor: ParcelFileDescriptor,
) {
    val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val writeLock = Any()
    private val closedFlag = AtomicBoolean(false)

    val closed: Boolean get() = closedFlag.get()

    fun write(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        synchronized(writeLock) {
            try {
                output.write(bytes)
            } catch (_: IOException) {
                // The child is gone. The reader reaches EOF and reports the exit.
            }
        }
    }

    fun resize(rows: Int, cols: Int, widthPx: Int = 0, heightPx: Int = 0) {
        if (!closed) nativeResize(masterFd, rows.coerceAtLeast(1), cols.coerceAtLeast(1), widthPx, heightPx)
    }

    /** Blocks until the child exits; returns its code, or 128 + the signal number. */
    fun waitFor(): Int = nativeWaitFor(pid)

    /** Process group of the foreground job, or -1 when the child is gone. */
    fun foregroundGroup(): Int = if (closed) -1 else nativeForegroundGroup(masterFd)

    /** Signals the child's whole session, which owns every job it started. */
    fun signal(signal: Int) {
        runCatching { android.system.Os.kill(-pid, signal) }
        runCatching { android.system.Os.kill(pid, signal) }
    }

    /** Closes the master side. The reader then sees end-of-file. */
    fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        runCatching { descriptor.close() }
    }

    companion object {
        /** False in a build made without the NDK: the terminal reports no PTY support. */
        val available: Boolean by lazy {
            runCatching { System.loadLibrary("opencode_pty") }.isSuccess
        }

        fun start(
            executable: String,
            argv: List<String>,
            env: Map<String, String>,
            cwd: File,
            rows: Int,
            cols: Int,
        ): Pty {
            check(available) { "This build has no terminal support library." }
            val pid = IntArray(1)
            val environment = env.map { "${it.key}=${it.value}" }.toTypedArray()
            val master = nativeStart(
                executable,
                argv.toTypedArray(),
                environment,
                cwd.absolutePath,
                rows.coerceAtLeast(1),
                cols.coerceAtLeast(1),
                pid,
            )
            return Pty(pid[0], master, ParcelFileDescriptor.adoptFd(master))
        }

        @JvmStatic
        private external fun nativeStart(
            path: String,
            argv: Array<String>,
            envp: Array<String>,
            cwd: String,
            rows: Int,
            cols: Int,
            pid: IntArray,
        ): Int

        @JvmStatic
        private external fun nativeResize(fd: Int, rows: Int, cols: Int, widthPx: Int, heightPx: Int)

        @JvmStatic
        private external fun nativeWaitFor(pid: Int): Int

        @JvmStatic
        private external fun nativeForegroundGroup(fd: Int): Int
    }
}

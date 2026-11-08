package com.opencode.android

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * `libandroid-posix-semaphore.so` builds the names of the POSIX semaphores below a directory that
 * was compiled in for the Termux app: `/data/data/com.termux/files/usr/tmp/sem.`. That directory
 * does not exist in this sandbox, so the first `sem_open` of Python's `multiprocessing` (locks,
 * queues, `Pool`, `ProcessPoolExecutor`) fails with `FileNotFoundError`.
 *
 * The path is rewritten in place with [ElfStrings] to `/data/data/<package>/tmp/sem.`; both are 40
 * bytes for the release application id, so no offset in the binary moves. The directory itself is
 * created by [ensureDirectory], because `sem_open` only creates the files inside it.
 *
 * This object is pure JVM code: it is used by the package extractor and by the repair pass, and it
 * is covered by the unit tests of the app.
 */
internal object TermuxSemaphorePath {

    /** The library inside the prefix. */
    const val LIBRARY = "lib/libandroid-posix-semaphore.so"

    /** Prefix that the Termux build of the library compiled in. */
    const val TERMUX_TMP = "/data/data/com.termux/files/usr/tmp/sem."

    /** The last part of the prefix, stripped to get the sandbox root (`/data/data/<package>`). */
    private const val FILES_USR = "/files/usr"

    fun appliesTo(relPath: String): Boolean = relPath == LIBRARY

    /**
     * The directory that must exist before `sem_open` runs: `/data/data/<package>/tmp`. The
     * library appends the semaphore name to the compiled-in string, so the directory is the
     * parent of that string, not `$PREFIX/tmp` (which would not fit in the 40 bytes).
     */
    fun directory(prefix: File): File = File(appRoot(prefix), "tmp")

    /** The string that replaces [TERMUX_TMP], `/data/data/<package>/tmp/sem.`. */
    fun replacement(prefix: File): String = File(directory(prefix), "sem.").absolutePath

    /**
     * Create [directory] when it is missing.
     *
     * @return true when the directory had to be created, false when it already existed or could
     *     not be created.
     */
    fun ensureDirectory(prefix: File): Boolean {
        val dir = directory(prefix)
        if (dir.isDirectory) return false
        return dir.mkdirs()
    }

    /**
     * Rewrite [TERMUX_TMP] inside [file] to [replacement].
     *
     * @return the number of rewritten strings, 0 when the file does not contain the Termux path
     *     (already patched, or not the library).
     */
    fun patch(file: File, prefix: File): Int {
        if (!file.isFile) return 0
        return try {
            val data = file.readBytes()
            val old = TERMUX_TMP.toByteArray(StandardCharsets.UTF_8)
            val new = replacement(prefix).toByteArray(StandardCharsets.UTF_8)
            val count = ElfStrings.replace(data, old, new)
            if (count > 0) file.writeBytes(data)
            count
        } catch (e: Exception) {
            // A library that cannot be read or written is left for the next repair pass.
            0
        }
    }

    private fun appRoot(prefix: File): File {
        val path = prefix.absolutePath.trimEnd('/')
        return if (path.endsWith(FILES_USR)) File(path.removeSuffix(FILES_USR)) else prefix
    }
}

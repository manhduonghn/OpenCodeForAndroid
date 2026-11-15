package com.opencode.android

import java.io.File

/**
 * Markers that `liboc-pathmap.so` writes when it moves a privileged port.
 *
 * Android refuses to bind a port below 1024 with `EACCES`, which looks like a
 * broken tutorial server. The preload library binds `port + 8000` instead and
 * records the real port in `$PREFIX/run/moved-ports/<original>`. The file holds
 * the new port as decimal text. This object only parses and removes those files,
 * so the UI layer stays independent of the native side.
 */
object MovedPorts {

    private const val DIRECTORY = "run/moved-ports"
    private const val MIN_PORT = 1
    private const val MAX_LOW_PORT = 1023
    private const val MAX_PORT = 65535

    /** A bind of [original] that the library turned into [moved]. */
    data class MovedPort(val original: Int, val moved: Int)

    /** Directory of the markers below the Termux-like prefix of the app. */
    fun directory(prefix: String): File = File(prefix, DIRECTORY)

    /**
     * Valid markers in [directory], ordered by the original port. Missing
     * directories, non-numeric names, directories and truncated files are
     * ignored: a partially written marker is retried on the next scan.
     */
    fun read(directory: File): List<MovedPort> {
        val entries = directory.listFiles() ?: return emptyList()
        val result = ArrayList<MovedPort>()
        for (entry in entries) {
            if (!entry.isFile) continue
            val original = entry.name.toIntOrNull() ?: continue
            if (original !in MIN_PORT..MAX_LOW_PORT) continue
            val moved = runCatching { entry.readText().trim().toIntOrNull() }.getOrNull() ?: continue
            if (moved !in MIN_PORT..MAX_PORT || moved == original) continue
            result.add(MovedPort(original, moved))
        }
        return result.sortedBy { it.original }
    }

    /**
     * Remove the marker of [port] after it was reported. A marker that changed
     * in the meantime (a second bind of the same original port) is kept so the
     * next scan can report the newer destination.
     *
     * @return true when the marker is gone or was already removed.
     */
    fun discard(directory: File, port: MovedPort): Boolean {
        val file = File(directory, port.original.toString())
        val current = runCatching { file.readText().trim().toIntOrNull() }.getOrNull()
        if (current != null && current != port.moved) return false
        if (!file.exists()) return true
        return file.delete()
    }
}

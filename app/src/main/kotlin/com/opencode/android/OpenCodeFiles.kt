package com.opencode.android

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** File helpers that the installer, the services and the state files share. */
object OpenCodeFiles {

    /**
     * Write [content] through a sibling temporary file and rename it over [file], so a reader
     * never sees a half written file.
     *
     * [mode] sets the permissions of the temporary file before the rename and of the final file
     * afterwards; pass a negative value to keep the default permissions. The temporary file is
     * removed when the write or the rename fails.
     */
    @Throws(IOException::class)
    fun writeTextAtomically(file: File, content: String, mode: Int = -1) {
        val directory = file.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the directory $directory")
        }
        val temporary = File(directory, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            if (mode >= 0) Os.chmod(temporary.absolutePath, mode)
            if (!temporary.renameTo(file)) {
                // rename(2) replaces the destination atomically; the fallback keeps FAT-style
                // storages working, where rename refuses to overwrite an existing file.
                if (!file.delete() || !temporary.renameTo(file)) {
                    throw IOException("Could not replace $file")
                }
            }
            if (mode >= 0) Os.chmod(file.absolutePath, mode)
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }
}

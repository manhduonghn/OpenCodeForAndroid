package com.opencode.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale

/**
 * Shared storage, SD cards and the downloaded files of the app.
 *
 * On Android 10 and lower the legacy read/write permission covers every shared volume. On
 * Android 11+ the root of an SD card needs the "All files access" special permission, while the
 * legacy permissions still cover the primary storage because this app targets API 28.
 */
object OpenCodeStorage {

    fun hasLegacyPermission(context: Context): Boolean {
        val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun hasStoragePermission(context: Context): Boolean =
        hasAllFilesAccess() || hasLegacyPermission(context)

    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    fun isPrimaryRoot(root: File): Boolean {
        val primary = primaryRoot()
        if (root.absolutePath == primary.absolutePath) return true
        return try {
            root.canonicalPath == primary.canonicalPath
        } catch (e: Exception) {
            false
        }
    }

    /** Root directories of the mounted volumes, primary storage first. */
    fun volumeRoots(context: Context): List<File> {
        val roots = ArrayList<File>()
        val dirs = context.getExternalFilesDirs(null) ?: return roots
        for (dir in dirs) {
            if (dir == null) continue
            val root = volumeRootOf(dir) ?: continue
            if (roots.none { it.absolutePath == root.absolutePath }) roots.add(root)
        }
        if (roots.isEmpty()) roots.add(primaryRoot())
        return roots
    }

    private fun volumeRootOf(appDir: File): File? {
        val path = appDir.absolutePath
        val index = path.indexOf("/Android/data/")
        if (index <= 0) return null
        return File(path.substring(0, index))
    }

    /** The `~/storage` directory that OpenCode sees, next to the app HOME. */
    fun storageHome(): File = File(OpenCodeBootstrap.HOME, "storage")

    /**
     * Create `~/storage/shared` and `~/storage/sdcardN` links, exactly like `termux-setup-storage`
     * does. Returns true when the shared storage link exists afterwards.
     */
    fun ensureLinks(context: Context): Boolean {
        val base = storageHome()
        if (!base.isDirectory && !base.mkdirs()) return false
        val volumes = volumeRoots(context)
        if (volumes.isEmpty()) return false

        var sharedOk = false
        var secondary = 0
        for (volume in volumes) {
            if (isPrimaryRoot(volume)) {
                sharedOk = link(File(base, "shared"), volume)
            } else {
                secondary++
                link(File(base, "sdcard$secondary"), volume)
            }
        }
        if (!sharedOk) {
            sharedOk = link(File(base, "shared"), primaryRoot())
        }
        return sharedOk
    }

    private fun link(link: File, target: File): Boolean {
        return try {
            val path = link.toPath()
            if (Files.isSymbolicLink(path)) {
                val current = Files.readSymbolicLink(path).toString()
                if (current == target.absolutePath) return true
                Files.delete(path)
            } else if (link.exists()) {
                if (!link.delete()) return false
            }
            Os.symlink(target.absolutePath, link.absolutePath)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (value >= 100) {
            String.format(Locale.US, "%.0f %s", value, units[index])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }

    fun freeBytes(path: File): Long = try {
        StatFs(path.absolutePath).availableBytes
    } catch (e: Exception) {
        -1L
    }

    /** Size of a file or directory, capped so a huge tree cannot freeze the UI. */
    fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(file)
        var visited = 0
        while (stack.isNotEmpty() && visited < 20000) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                visited++
                val symbolic = try {
                    Files.isSymbolicLink(child.toPath())
                } catch (e: Exception) {
                    false
                }
                when {
                    symbolic -> Unit
                    child.isDirectory -> stack.add(child)
                    child.isFile -> total += child.length()
                }
            }
        }
        return total
    }

    /** Top level entries of the app HOME, without hidden files and the storage links. */
    fun homeEntries(): List<File> {
        val home = File(OpenCodeBootstrap.HOME)
        val entries = home.listFiles() ?: return emptyList()
        return entries
            .filter { !it.name.startsWith(".") && it.name != "storage" }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
    }

    /** Delete a file or a directory only when it is inside the app HOME and not HOME itself. */
    fun deleteSafely(file: File): Boolean {
        val home = try {
            File(OpenCodeBootstrap.HOME).canonicalFile
        } catch (e: Exception) {
            return false
        }
        val target = try {
            file.canonicalFile
        } catch (e: Exception) {
            return false
        }
        if (target.absolutePath == home.absolutePath) return false
        if (!target.absolutePath.startsWith(home.absolutePath + File.separator)) return false
        return try {
            target.deleteRecursively()
        } catch (e: Exception) {
            false
        }
    }
}

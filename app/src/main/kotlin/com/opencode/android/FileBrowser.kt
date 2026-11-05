package com.opencode.android

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

/**
 * One row of the file browser. Directories keep [size] at -1: recursively measuring every folder
 * of a volume when a list is opened is slow and can walk into mount points.
 */
data class FileEntry(
    val file: File,
    val kind: Kind,
    val size: Long,
    val lastModified: Long,
    val isSymlink: Boolean,
) {
    val isDirectory: Boolean
        get() = kind == Kind.DIRECTORY || kind == Kind.REPO

    enum class Kind { DIRECTORY, REPO, FILE }
}

/**
 * Filesystem backend of the Files screen.
 *
 * The app targets API 28, so the legacy storage permissions cover the shared volume. Everything
 * else is plain `java.nio.file`: symbolic links are never followed by a recursive operation and
 * deletes remove the link itself, not the directory it points at.
 */
object FileBrowser {

    enum class SortKey { NAME, SIZE, MODIFIED, TYPE }

    data class Sort(val key: SortKey = SortKey.NAME, val ascending: Boolean = true)

    enum class Conflict { REPLACE, KEEP_BOTH, SKIP }

    /** Child entries of [directory], or null when Android does not let the app read the folder. */
    fun entries(directory: File, showHidden: Boolean, sort: Sort): List<FileEntry>? {
        val children = try {
            directory.listFiles()
        } catch (e: SecurityException) {
            null
        } ?: return null
        return children.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map(::entry)
            .sortedWith(comparator(sort))
            .toList()
    }

    fun entry(file: File): FileEntry {
        val path = file.toPath()
        val symlink = try {
            Files.isSymbolicLink(path)
        } catch (e: Exception) {
            false
        }
        val directory = try {
            Files.isDirectory(path)
        } catch (e: Exception) {
            file.isDirectory
        }
        val modified = try {
            Files.getLastModifiedTime(path).toMillis()
        } catch (e: Exception) {
            file.lastModified()
        }
        val size = when {
            directory -> -1L
            else -> try {
                Files.size(path)
            } catch (e: Exception) {
                file.length()
            }
        }
        val kind = when {
            directory && File(file, ".git").isDirectory -> FileEntry.Kind.REPO
            directory -> FileEntry.Kind.DIRECTORY
            else -> FileEntry.Kind.FILE
        }
        return FileEntry(file, kind, size, modified, symlink)
    }

    /**
     * Directories always come first; the chosen key only orders the rows inside each group. The
     * name is the final tie breaker so the list does not jump between two equal timestamps.
     */
    fun comparator(sort: Sort): Comparator<FileEntry> {
        val byName = compareBy<FileEntry> { it.file.name.lowercase(Locale.US) }
        val key: Comparator<FileEntry> = when (sort.key) {
            SortKey.NAME -> byName
            SortKey.SIZE -> compareBy { it.size }
            SortKey.MODIFIED -> compareBy { it.lastModified }
            SortKey.TYPE -> compareBy<FileEntry> {
                if (it.isDirectory) "" else it.file.extension.lowercase(Locale.US)
            }.then(byName)
        }
        val ordered = if (sort.ascending) key else key.reversed()
        val directoriesFirst = Comparator<FileEntry> { first, second ->
            when {
                first.isDirectory == second.isDirectory -> 0
                first.isDirectory -> -1
                else -> 1
            }
        }
        return directoriesFirst.then(ordered).then(byName)
    }

    /** True for a path that exists, including a dangling symbolic link. */
    fun exists(file: File): Boolean = try {
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (e: Exception) {
        file.exists()
    }

    fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (e: Exception) {
        file.absoluteFile
    }

    /** True when [child] is [parent] or lives below it, after resolving symbolic links. */
    fun isSameOrInside(child: File, parent: File): Boolean {
        val childPath = canonical(child).absolutePath
        val parentPath = canonical(parent).absolutePath
        if (childPath == parentPath) return true
        val prefix = if (parentPath.endsWith(File.separator)) parentPath else parentPath + File.separator
        return childPath.startsWith(prefix)
    }

    fun sameParent(first: File, second: File): Boolean =
        canonical(first.parentFile ?: first).absolutePath ==
            canonical(second.parentFile ?: second).absolutePath

    /**
     * Pick the target for a copy or move. [Conflict.REPLACE] keeps the original name (the caller
     * deletes the old target first), [Conflict.KEEP_BOTH] finds a free "name (1).ext" and
     * [Conflict.SKIP] returns null. Without a conflict the original name is always used.
     */
    fun destination(source: File, directory: File, conflict: Conflict): File? {
        val direct = File(directory, source.name)
        if (!exists(direct)) return direct
        return when (conflict) {
            Conflict.REPLACE -> direct
            Conflict.KEEP_BOTH -> uniqueName(directory, source.name, source.isDirectory)
            Conflict.SKIP -> null
        }
    }

    /** "photo.jpg" -> "photo (1).jpg", while ".config" -> ".config (1)". */
    fun uniqueName(directory: File, name: String, isDirectory: Boolean): File {
        val direct = File(directory, name)
        if (!exists(direct)) return direct
        val dot = if (isDirectory) -1 else name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!exists(candidate)) return candidate
            index++
        }
    }

    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\u0000')

    fun createDirectory(file: File): Boolean = try {
        Files.createDirectory(file.toPath())
        true
    } catch (e: Exception) {
        false
    }

    fun createFile(file: File): Boolean = try {
        Files.createFile(file.toPath())
        true
    } catch (e: Exception) {
        false
    }

    fun rename(file: File, newName: String): File? {
        if (!isValidName(newName)) return null
        if (newName == file.name) return file
        val target = File(file.parentFile, newName)
        if (exists(target)) return null
        return try {
            Files.move(file.toPath(), target.toPath())
            target
        } catch (e: Exception) {
            null
        }
    }

    /** Copy [source] to [target]. [replace] deletes an existing target as a whole. */
    fun copy(source: File, target: File, replace: Boolean): Boolean {
        if (!exists(source)) return false
        if (isSameOrInside(target, source)) return false
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        return try {
            if (Files.isSymbolicLink(sourcePath)) {
                if (exists(target)) {
                    if (!replace || !delete(target)) return false
                }
                target.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) return false
                }
                Files.createSymbolicLink(targetPath, Files.readSymbolicLink(sourcePath))
                return true
            }
            val directory = Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)
            if (exists(target)) {
                if (!replace || !delete(target)) return false
            }
            if (directory) {
                target.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) return false
                }
                Files.createDirectories(targetPath)
                val children = source.listFiles()
                if (children == null ||
                    !children.all { child -> copy(child, File(target, child.name), false) }
                ) {
                    // Do not leave a half-copied tree behind when one child fails.
                    delete(target)
                    false
                } else {
                    true
                }
            } else {
                target.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) return false
                }
                try {
                    try {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES)
                    } catch (e: Exception) {
                        // Some FUSE mounts reject attribute copying. Remove the partial target
                        // before retrying, otherwise the second copy sees a file in the way.
                        try {
                            Files.deleteIfExists(targetPath)
                        } catch (ignored: Exception) {
                            // The retry below reports the real failure.
                        }
                        Files.copy(sourcePath, targetPath)
                    }
                    true
                } catch (e: Exception) {
                    try {
                        Files.deleteIfExists(targetPath)
                    } catch (ignored: Exception) {
                        // Nothing else to clean up.
                    }
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Rename where possible, then fall back to copy + delete across filesystems. */
    fun move(source: File, target: File, replace: Boolean): Boolean {
        if (!exists(source)) return false
        if (isSameOrInside(target, source)) return false
        return try {
            if (exists(target)) {
                if (!replace || !delete(target)) return false
            }
            val moved = try {
                Files.move(source.toPath(), target.toPath())
                true
            } catch (e: Exception) {
                false
            }
            if (moved) return true
            copy(source, target, false) && delete(source)
        } catch (e: Exception) {
            false
        }
    }

    /** Delete a file, a symbolic link or a whole tree without following symbolic links. */
    fun delete(file: File): Boolean {
        if (file.parentFile == null) return false
        val path = file.toPath()
        return try {
            if (Files.isSymbolicLink(path) ||
                !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                Files.deleteIfExists(path)
            } else {
                var success = true
                Files.walkFileTree(
                    path,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            try {
                                Files.deleteIfExists(file)
                            } catch (e: Exception) {
                                success = false
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exc: java.io.IOException,
                        ): FileVisitResult {
                            success = false
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(
                            dir: Path,
                            exc: java.io.IOException?,
                        ): FileVisitResult {
                            if (exc != null) success = false
                            try {
                                Files.deleteIfExists(dir)
                            } catch (e: Exception) {
                                success = false
                            }
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                success
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Recursive size with a hard entry cap, so a huge tree cannot stall the dialog forever. */
    fun sizeOf(file: File): Long {
        if (!exists(file)) return 0L
        var total = 0L
        var visited = 0
        try {
            Files.walkFileTree(
                file.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (++visited > MAX_SIZE_ENTRIES) return FileVisitResult.TERMINATE
                        total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: java.io.IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE

                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (++visited > MAX_SIZE_ENTRIES) return FileVisitResult.TERMINATE
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (e: Exception) {
            return total
        }
        return total
    }

    fun permissions(file: File): String? = try {
        PosixFilePermissions.toString(
            Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS),
        )
    } catch (e: Exception) {
        null
    }

    fun symlinkTarget(file: File): String? = try {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.readSymbolicLink(file.toPath()).toString()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private const val MAX_SIZE_ENTRIES = 50_000
}

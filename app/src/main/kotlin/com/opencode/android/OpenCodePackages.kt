package com.opencode.android

import android.os.Build
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

/**
 * Installs Termux packages into the app-private prefix without apt: the package lists are
 * downloaded from the Termux repositories, the .deb archives are downloaded and verified, and the
 * data archives are extracted directly into the prefix. The absolute paths of the Termux prefix
 * are rewritten while extracting, so the package does not need the Termux app and does not need
 * the com.termux package name.
 */
object OpenCodePackages {

    private const val TAG = "OpenCodePackages"

    /** Prefix that is baked into the Termux packages. */
    private const val TERMUX_USR = "data/data/com.termux/files/usr"
    private const val TERMUX_USR_ABS = "/$TERMUX_USR/"
    private const val TERMUX_SHEBANG = "#!$TERMUX_USR_ABS"

    /** Android bridge wrappers of termux-tools, they only shadow the real /system binaries. */
    private val SYSTEM_WRAPPERS = setOf(
        "am", "bmgr", "bu", "cmd", "content", "device_config", "dpm", "dumpsys",
        "getprop", "ime", "input", "log", "logcat", "media", "mount", "notify",
        "pm", "settings", "setprop", "sm", "svc", "uiautomator", "umount",
        "wm", "start", "stop",
    )

    /**
     * Dependencies that the app does not need. 'termux-tools' is a collection of wrappers around
     * the Android commands of the Termux app (am, pm, …) and pulls the Termux app itself
     * (termux-am, termux-am-socket, termux-core) plus a dozen command line packages. The bash
     * binary works without it.
     */
    private val SKIPPED_DEPENDENCIES = setOf(
        "termux-tools", "termux-am", "termux-am-socket", "termux-core", "termux-exec",
    )

    private val MAIN_MIRRORS = arrayOf(
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
        "https://grimler.se/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
    )

    private val GLIBC_MIRRORS = arrayOf(
        "https://packages-cf.termux.dev/apt/termux-glibc",
        "https://packages.termux.dev/apt/termux-glibc",
    )

    private const val INDEX_PATH = "/dists/stable/main/binary-"
    private const val GLIBC_INDEX_PATH = "/dists/glibc/stable/binary-"

    /** Progress callbacks, they are invoked on the thread that runs the installation. */
    interface Progress {
        fun onStatus(message: String)
        fun onPercent(percent: Int, detail: String)
    }

    /** A single package of the repository index. */
    private class Pkg {
        var name: String = ""
        var version: String = ""
        var filename: String = ""
        var sha256: String = ""
        var description: String = ""
        var repo: String = ""
        val depends: MutableList<String> = ArrayList()
    }

    /** The Debian architecture of the device, or null when OpenCode has no build for it. */
    fun arch(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return when {
            abi.startsWith("arm64") -> "aarch64"
            abi.startsWith("x86_64") -> "x86_64"
            else -> null
        }
    }

    /**
     * Install the given packages and all of their dependencies into the prefix. Already installed
     * packages are skipped, the installation is idempotent.
     */
    @Throws(Exception::class)
    fun install(prefix: File, wanted: List<String>, progress: Progress) {
        val arch = arch()
            ?: throw IOException("The CPU architecture \"${firstAbi()}\" is not supported.")

        val marker = File(prefix, ".packages-installed")
        val installed = readInstalled(marker).toMutableList()
        if (installed.containsAll(wanted) && File(prefix, "bin/bash").isFile) {
            progress.onStatus("The Linux toolchain is already installed.")
            progress.onPercent(100, "")
            ensureShellLink(prefix)
            return
        }

        // A prefix without a shell is broken, even when the marker says the packages were
        // installed (an earlier run may have been interrupted while extracting). Left over files
        // of the apt based bootstrap are not relocatable either, start from a clean directory.
        if (!File(prefix, "bin/bash").isFile && prefix.exists()) {
            progress.onStatus("Removing the files of the previous installation...")
            deleteTree(prefix)
            installed.clear()
        }
        if (!prefix.isDirectory && !prefix.mkdirs()) throw IOException("Could not create $prefix")

        progress.onStatus("Fetching the package lists...")
        val index = LinkedHashMap<String, Pkg>()

        var mainMirror: String? = null
        for (mirror in MAIN_MIRRORS) {
            try {
                parseIndex(fetch(mirror + INDEX_PATH + arch + "/Packages"), mirror, index)
                mainMirror = mirror
                break
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch the package list from $mirror", e)
            }
        }
        if (mainMirror == null) {
            throw IOException("Could not download the package list of the Termux repository.")
        }

        for (mirror in GLIBC_MIRRORS) {
            try {
                val glibc = LinkedHashMap<String, Pkg>()
                parseIndex(fetch(mirror + GLIBC_INDEX_PATH + arch + "/Packages"), mirror, glibc)
                for ((name, pkg) in glibc) index.putIfAbsent(name, pkg)
                break
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch the glibc package list from $mirror", e)
            }
        }

        val closure = dependencyClosure(index, wanted, installed)
        if (closure.isEmpty() && !File(prefix, "bin/bash").isFile) {
            throw IOException("The package list does not contain the required packages.")
        }

        val cache = File(prefix.parentFile, "deb-cache")
        if (!cache.isDirectory && !cache.mkdirs()) throw IOException("Could not create $cache")

        var done = 0
        for (pkg in closure) {
            done++
            val percent = done * 100 / closure.size
            progress.onStatus("Downloading ${pkg.name} ${pkg.version}...")
            progress.onPercent(percent, pkg.name)

            val deb = File(cache, packageFileName(pkg))
            if (!deb.isFile || deb.length() == 0L) {
                downloadVerified("${pkg.repo}/${pkg.filename}", deb, pkg.sha256)
            }
            progress.onStatus("Extracting ${pkg.name} ${pkg.version}...")
            extractDeb(deb, prefix)
            deb.delete()
        }

        appendInstalled(marker, closure, installed)
        ensureShellLink(prefix)
        progress.onPercent(100, "")
    }

    /**
     * Search the main Termux repository. The result is one line per package:
     * `name version - description`, sorted by name.
     */
    @Throws(Exception::class)
    fun search(query: String): List<String> {
        val arch = arch()
            ?: throw IOException("The CPU architecture \"${firstAbi()}\" is not supported.")

        val index = LinkedHashMap<String, Pkg>()
        var loaded = false
        for (mirror in MAIN_MIRRORS) {
            try {
                parseIndex(fetch(mirror + INDEX_PATH + arch + "/Packages"), mirror, index)
                loaded = true
                break
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch the package list from $mirror", e)
            }
        }
        if (!loaded) throw IOException("Could not download the package list of the Termux repository.")

        val needle = query.trim().lowercase(Locale.US)
        return index.values
            .filter { pkg ->
                needle.isEmpty() ||
                    pkg.name.lowercase(Locale.US).contains(needle) ||
                    pkg.description.lowercase(Locale.US).contains(needle)
            }
            .map { pkg ->
                buildString {
                    append(pkg.name).append(' ').append(pkg.version)
                    if (pkg.description.isNotEmpty()) append(" - ").append(pkg.description)
                }
            }
            .sorted()
    }

    /* ------------------------------------------------------------------ package index */

    private fun parseIndex(text: String, repo: String, out: MutableMap<String, Pkg>) {
        var current: Pkg? = null
        var lastKey = ""
        for (rawLine in text.split("\n")) {
            if (rawLine.isBlank()) {
                current = null
                lastKey = ""
                continue
            }
            if (rawLine.startsWith(" ") || rawLine.startsWith("\t")) {
                val pkg = current ?: continue
                when (lastKey) {
                    "Depends", "Pre-Depends" -> pkg.depends.addAll(parseDepends(rawLine.trim()))
                    "Description" -> pkg.description = "${pkg.description} ${rawLine.trim()}".trim()
                }
                continue
            }
            val colon = rawLine.indexOf(':')
            if (colon <= 0) continue
            val key = rawLine.substring(0, colon).trim()
            val value = rawLine.substring(colon + 1).trim()
            if (key == "Package") {
                val pkg = Pkg().apply {
                    name = value
                    this.repo = repo
                }
                current = pkg
                out[value] = pkg
                lastKey = key
                continue
            }
            val pkg = current ?: continue
            lastKey = key
            when (key) {
                "Version" -> pkg.version = value
                "Filename" -> pkg.filename = value
                "SHA256" -> pkg.sha256 = value
                "Depends", "Pre-Depends" -> pkg.depends.addAll(parseDepends(value))
                "Description" -> pkg.description = value
            }
        }
    }

    private fun parseDepends(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",").mapNotNull { part ->
            var name = part.trim()
            name.indexOf('|').let { if (it >= 0) name = name.substring(0, it).trim() }
            name.indexOf('(').let { if (it >= 0) name = name.substring(0, it).trim() }
            name.indexOf(':').let { if (it >= 0) name = name.substring(0, it).trim() }
            name.ifEmpty { null }
        }
    }

    private fun dependencyClosure(
        index: Map<String, Pkg>,
        wanted: List<String>,
        installed: List<String>,
    ): List<Pkg> {
        val out = LinkedHashMap<String, Pkg>()
        val queue = ArrayDeque(wanted)
        val seen = HashSet(installed)
        val missing = LinkedHashSet<String>()
        while (!queue.isEmpty()) {
            val name = queue.poll() ?: break
            if (name in SKIPPED_DEPENDENCIES) continue
            if (!seen.add(name)) continue
            val pkg = index[name]
            if (pkg == null) {
                missing.add(name)
                continue
            }
            out[name] = pkg
            queue.addAll(pkg.depends)
        }

        // A missing dependency is not fatal (some are optional), but a requested package has to
        // exist, otherwise the runtime is incomplete without a useful error message.
        for (name in wanted) {
            if (name in missing) {
                throw IllegalStateException(
                    "The repository does not provide the package '$name' for this architecture.",
                )
            }
        }
        if (missing.isNotEmpty()) Log.w(TAG, "The package list has no entry for: $missing")
        return out.values.toList()
    }

    /* ------------------------------------------------------------------ download */

    private fun firstAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private fun packageFileName(pkg: Pkg): String {
        val slash = pkg.filename.lastIndexOf('/')
        return if (slash >= 0) pkg.filename.substring(slash + 1) else pkg.filename
    }

    @Throws(IOException::class)
    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "OpenCodeForAndroid")
        try {
            val code = connection.responseCode
            if (code != 200) throw IOException("HTTP $code for $url")
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                copy(input, out)
                return out.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun downloadVerified(url: String, dest: File, sha256: String) {
        val temp = File(dest.absolutePath + ".part")
        val digest = newSha256()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "OpenCodeForAndroid")
        try {
            val code = connection.responseCode
            if (code != 200) throw IOException("HTTP $code for $url")
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    out.flush()
                }
            }
        } finally {
            connection.disconnect()
        }

        val actual = hex(digest.digest())
        if (sha256.isNotEmpty() && !actual.equals(sha256, ignoreCase = true)) {
            temp.delete()
            throw IOException("Checksum mismatch for ${dest.name}")
        }
        if (!temp.renameTo(dest)) {
            if (!dest.delete() || !temp.renameTo(dest)) {
                temp.delete()
                throw IOException("Could not write $dest")
            }
        }
    }

    private fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
    }

    /* ------------------------------------------------------------------ extraction */

    @Throws(Exception::class)
    private fun extractDeb(deb: File, prefix: File) {
        ArArchiveInputStream(BufferedInputStream(FileInputStream(deb))).use { ar ->
            while (true) {
                val entry = ar.nextEntry ?: throw IOException("No data archive in ${deb.name}")
                if (!entry.name.startsWith("data.tar")) continue
                val compressed = decompressor(entry.name, ar)
                TarArchiveInputStream(compressed).use { tar ->
                    while (true) {
                        val tarEntry = tar.nextEntry ?: break
                        extractEntry(tar, tarEntry, prefix)
                    }
                }
                return
            }
        }
    }

    @Throws(Exception::class)
    private fun decompressor(name: String, input: InputStream): InputStream {
        val factory = CompressorStreamFactory()
        val lower = name.lowercase(Locale.US)
        return when {
            lower.endsWith(".xz") || lower.endsWith(".lzma") ->
                factory.createCompressorInputStream(CompressorStreamFactory.XZ, input)

            lower.endsWith(".gz") -> factory.createCompressorInputStream(CompressorStreamFactory.GZIP, input)
            lower.endsWith(".bz2") -> factory.createCompressorInputStream(CompressorStreamFactory.BZIP2, input)
            lower.endsWith(".zst") -> factory.createCompressorInputStream(CompressorStreamFactory.ZSTANDARD, input)
            else -> input
        }
    }

    @Throws(Exception::class)
    private fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, prefix: File) {
        var rel = entry.name
        if (rel.startsWith("./")) rel = rel.substring(2)
        if (rel.startsWith("/")) rel = rel.substring(1)
        if (rel.startsWith(TERMUX_USR)) {
            rel = rel.substring(TERMUX_USR.length)
            if (rel.startsWith("/")) rel = rel.substring(1)
        }
        if (rel.isEmpty() || rel.startsWith("data/data")) return
        if (rel.contains("..")) throw IOException("Refusing traversal path: ${entry.name}")

        val target = File(prefix, rel)
        if (entry.isDirectory) {
            target.mkdirs()
            return
        }
        target.parentFile?.mkdirs()

        if (entry.isSymbolicLink) {
            val rewritten = rewriteLink(entry.linkName, rel)
            target.delete()
            try {
                Os.symlink(rewritten, target.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Could not create the symlink $target", e)
            }
            return
        }

        if (entry.isLink) {
            var link = entry.linkName
            if (link.startsWith("./")) link = link.substring(2)
            if (link.startsWith(TERMUX_USR)) link = link.substring(TERMUX_USR.length + 1)
            copy(File(prefix, link), target)
            chmod(target, entry.mode)
            return
        }

        val head = ByteArray(256)
        val headLength = readUpTo(tar, head, head.size)
        val shebangLine = firstLine(head, headLength)
        val name = rel.substring(rel.lastIndexOf('/') + 1)
        val binEntry = rel.startsWith("bin/") && !rel.contains("/applets/")

        if (binEntry && shebangLine.startsWith(TERMUX_SHEBANG + "bin/sh") && name in SYSTEM_WRAPPERS) {
            // Termux bridge wrapper, drop it and keep the real Android command.
            drain(tar)
            return
        }

        val rewritten = rewriteShebang(shebangLine, prefix.absolutePath)
        FileOutputStream(target).use { out ->
            if (rewritten != null) {
                val newline = indexOfNewline(head, headLength)
                if (newline >= 0) {
                    out.write(rewritten.toByteArray(StandardCharsets.UTF_8))
                    out.write(head, newline, headLength - newline)
                } else {
                    out.write(rewritten.toByteArray(StandardCharsets.UTF_8))
                }
            } else {
                out.write(head, 0, headLength)
            }
            copy(tar, out)
            out.flush()
        }
        chmod(target, entry.mode)
    }

    private fun rewriteLink(target: String?, linkPath: String): String? {
        if (target == null || !target.startsWith(TERMUX_USR_ABS)) return target
        val inside = target.substring(TERMUX_USR_ABS.length)

        var depth = 0
        var path = linkPath
        while (true) {
            val slash = path.indexOf('/')
            if (slash < 0) break
            depth++
            path = path.substring(slash + 1)
        }
        return "../".repeat(depth) + inside
    }

    private fun rewriteShebang(firstLine: String?, prefix: String): String? {
        if (firstLine == null || !firstLine.startsWith(TERMUX_SHEBANG)) return null
        return "#!$prefix/" + firstLine.substring(TERMUX_SHEBANG.length)
    }

    private fun firstLine(head: ByteArray, length: Int): String {
        if (length <= 0) return ""
        val end = indexOfNewline(head, length)
        return String(head, 0, if (end >= 0) end else length, StandardCharsets.UTF_8).trim()
    }

    private fun indexOfNewline(bytes: ByteArray, length: Int): Int {
        for (i in 0 until length) if (bytes[i] == '\n'.code.toByte()) return i
        return -1
    }

    @Throws(IOException::class)
    private fun readUpTo(input: InputStream, buffer: ByteArray, max: Int): Int {
        var total = 0
        while (total < max) {
            val read = input.read(buffer, total, max - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    @Throws(IOException::class)
    private fun drain(input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (input.read(buffer) != -1) {
            // Drain the entry.
        }
    }

    private fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode and 0x1FF)
        } catch (e: Exception) {
            Log.w(TAG, "Could not chmod $file", e)
        }
    }

    @Throws(IOException::class)
    private fun copy(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
    }

    @Throws(IOException::class)
    private fun copy(source: File, target: File) {
        if (!source.isFile) return
        FileInputStream(source).use { input -> FileOutputStream(target).use { out -> copy(input, out) } }
    }

    /* ------------------------------------------------------------------ markers */

    private fun readInstalled(marker: File): List<String> {
        if (!marker.isFile) return emptyList()
        return try {
            marker.readText(Charsets.UTF_8)
                .split("\n")
                .map { line -> line.substringBefore(' ').trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $marker", e)
            emptyList()
        }
    }

    private fun appendInstalled(marker: File, packages: List<Pkg>, already: List<String>) {
        val sb = StringBuilder()
        for (name in already) sb.append(name).append('\n')
        for (pkg in packages) sb.append(pkg.name).append(' ').append(pkg.version).append('\n')
        try {
            FileOutputStream(marker).use {
                it.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                it.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not write $marker", e)
        }
    }

    /** Delete a directory tree without following symlinks (they may point outside the prefix). */
    @Throws(IOException::class)
    private fun deleteTree(file: File) {
        Files.walkFileTree(
            file.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(path)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** Some packages ship a /bin/sh link, make sure the prefix always has one. */
    private fun ensureShellLink(prefix: File) {
        val bash = File(prefix, "bin/bash")
        val sh = File(prefix, "bin/sh")
        if (!bash.isFile || sh.exists()) return
        try {
            Os.symlink("bash", sh.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create $sh", e)
        }
    }
}

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
import java.util.Locale

/**
 * Installs Termux packages into the app-private prefix without apt: the package lists are
 * downloaded from the Termux repositories, the .deb archives are downloaded and verified, and the
 * data archives are extracted directly into the prefix. The absolute paths of the Termux prefix
 * are rewritten while extracting, so the package does not need the Termux app and does not need
 * the com.termux package name.
 *
 * The extraction rules follow the AndroidHarness project:
 *
 *  - absolute symlinks into `/data/data/com.termux/files/usr` become relative links, so the tree
 *    stays relocatable and never points at another app;
 *  - scripts with a Termux shebang (`bin/pip`, `lib/node_modules/npm/bin/npm-cli.js`, the git
 *    helpers, …) get the shebang rewritten into this prefix, which also makes the coreutils
 *    `bin/env` work for `#!/…/bin/env node` launchers;
 *  - the Android bridge wrappers of termux-tools (`pm`, `cmd`, `am`, …) are dropped, they only
 *    shadow the real /system commands and die with exit 126;
 *  - git's ELF carries `SHELL_PATH=/data/data/com.termux/files/usr/bin/sh`, it is patched in
 *    place to `/system/bin/sh`, otherwise hooks, aliases and the credential helper of
 *    `gh auth setup-git` cannot spawn a shell.
 */
object OpenCodePackages {

    private const val TAG = "OpenCodePackages"

    /** Prefix that is baked into the Termux packages. */
    private const val TERMUX_USR = "data/data/com.termux/files/usr"
    private const val TERMUX_USR_ABS = "/$TERMUX_USR/"
    private const val TERMUX_USR_ROOT = "/$TERMUX_USR"

    /** Marker of any shebang that points at the Termux app, even outside files/usr. */
    private const val TERMUX_SHEBANG_MARK = "#!/data/data/com.termux/"

    /** Written after the one-time deep repair of the node package tree. */
    private const val DEEP_REPAIR_MARKER = ".packages-repaired-v2"

    /** Android bridge wrappers of termux-tools, they only shadow the real /system binaries. */
    private val SYSTEM_WRAPPERS = setOf(
        "am", "bmgr", "bu", "cmd", "content", "device_config", "dpm", "dumpsys",
        "getprop", "ime", "input", "log", "logcat", "media", "mount", "notify",
        "pm", "settings", "setprop", "sm", "svc", "uiautomator", "umount",
        "wm", "start", "stop",
    )

    /**
     * Bridge commands that cannot work here at all. `cmd` is also shipped as an ELF and
     * `termux-am`/`termux-am-socket` need the socket service of the Termux app.
     */
    private val UNSUPPORTED_BRIDGES = setOf("cmd", "termux-am", "termux-am-socket")

    /**
     * Dependencies that the app does not need. 'termux-tools' is a collection of wrappers around
     * the Android commands of the Termux app (am, pm, …) and pulls the Termux app itself
     * (termux-am, termux-am-socket, termux-core) plus a dozen command line packages. The bash
     * binary works without it.
     */
    private val SKIPPED_DEPENDENCIES = setOf(
        "termux-tools", "termux-am", "termux-am-socket", "termux-core", "termux-exec",
    )

    /**
     * Package name → files proving that the package is actually usable. A marker entry whose
     * files vanished (an interrupted extraction, a wiped prefix) must not make the installer
     * believe the package is installed: that was the silent no-op bug of the old installer.
     */
    private val BINARY_PROOF: Map<String, List<String>> = mapOf(
        "bash" to listOf("bin/bash"),
        "coreutils" to listOf("bin/coreutils", "bin/env"),
        "busybox" to listOf("bin/busybox"),
        "ca-certificates" to listOf("etc/tls/cert.pem"),
        "patchelf" to listOf("bin/patchelf"),
        "git" to listOf("bin/git"),
        "gh" to listOf("bin/gh"),
        "openssl" to listOf("bin/openssl"),
        "python" to listOf("bin/python3", "bin/python"),
        "python-pip" to listOf("bin/pip", "bin/pip3"),
        "nodejs" to listOf("bin/node"),
        "npm" to listOf("bin/npm"),
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

        /** One entry per dependency; an entry holds the alternatives of `a | b`. */
        val depends: MutableList<List<String>> = ArrayList()
        val provides: MutableList<String> = ArrayList()
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
     * packages are skipped, the installation is idempotent. Packages that the marker lists but
     * whose files are gone are installed again instead of being trusted.
     */
    @Throws(Exception::class)
    fun install(prefix: File, wanted: List<String>, progress: Progress) {
        val arch = arch()
            ?: throw IOException("The CPU architecture \"${firstAbi()}\" is not supported.")

        val marker = File(prefix, ".packages-installed")
        var recorded = readInstalled(marker)
        var broken = brokenPackages(prefix)
        if (recorded.containsAll(wanted) && broken.isEmpty() && File(prefix, "bin/bash").isFile) {
            progress.onStatus("The Linux toolchain is already installed.")
            progress.onPercent(100, "")
            ensureEnvironmentLinks(prefix)
            return
        }

        // A prefix without a shell is broken. When there is no marker either, the files are
        // leftovers of the old apt based bootstrap, they are not relocatable: start clean.
        // With a marker the tree is kept (a full wipe would throw away every package the user
        // installed) and the recorded packages are extracted again, see below.
        val shellMissing = !File(prefix, "bin/bash").isFile
        if (shellMissing && !marker.isFile && prefix.exists()) {
            progress.onStatus("Removing the files of the previous installation...")
            deleteTree(prefix)
            recorded = emptyList()
            broken = emptyList()
        }
        if (shellMissing) {
            // Without a shell the tree cannot be trusted. The marker lists what should be there,
            // so every recorded package is installed again (with its dependencies) instead of
            // only bash: reinstalling bash alone would leave its missing libraries behind.
            broken = recorded
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

        val providers = buildProviders(index)
        // The shell is the base of everything the app spawns; when it is gone, install it again
        // even when the caller only asked for an add-on (git, gh, npm, …).
        val effectiveWanted = LinkedHashSet(wanted)
        if (shellMissing) effectiveWanted.add("bash")
        effectiveWanted.addAll(broken)
        val required = LinkedHashSet(wanted)
        if (shellMissing) required.add("bash")
        val already = recorded.filter { it !in broken }
        val closure = dependencyClosure(
            index,
            providers,
            effectiveWanted.toList(),
            already,
            required.toList(),
        )
        if (closure.isEmpty() && shellMissing) {
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
                downloadWithMirrors(pkg, deb)
            }
            progress.onStatus("Extracting ${pkg.name} ${pkg.version}...")
            try {
                extractDeb(deb, prefix)
            } catch (e: Exception) {
                // A cached archive may be truncated by an earlier crash, fetch it again once.
                deb.delete()
                downloadWithMirrors(pkg, deb)
                extractDeb(deb, prefix)
            }
            deb.delete()
        }

        // The marker is rewritten, not appended: entries of broken packages must disappear so the
        // next run does not trust them again.
        writeInstalled(marker, already, closure)
        ensureEnvironmentLinks(prefix)
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

    /**
     * Repair a prefix that was installed by an older build of the app: dangling Termux symlinks,
     * dead Termux shebangs and the git ELF shell path. Cheap enough to run on every start of the
     * app, it returns a summary or null when nothing needed fixing.
     */
    fun repair(prefix: File): String? {
        if (!File(prefix, ".packages-installed").isFile) return null
        var relinked = 0
        var removed = 0
        var rewritten = 0
        var shellPatched = 0

        fun repairShebangs(dir: File, isBin: Boolean) {
            if (!dir.isDirectory) return
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                if (isBin && name in UNSUPPORTED_BRIDGES) {
                    if (file.delete()) removed++
                    continue
                }
                val firstLine = firstLineOf(file) ?: continue
                if (!firstLine.startsWith(TERMUX_SHEBANG_MARK)) continue
                val repaired = TermuxShebangs.rewrittenFirstLine(firstLine, prefix.absolutePath)
                val wrapper = isBin && TermuxShebangs.isWrapperScript(name, firstLine)
                if (repaired != null && !wrapper) {
                    if (rewriteFirstLine(file, repaired)) rewritten++
                } else if (isBin && file.delete()) {
                    removed++
                }
            }
        }

        for (dir in listOf(File(prefix, "bin"), File(prefix, "bin/applets"))) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                val link = readLink(file) ?: continue
                val target = TermuxLinkRewrite.relativeTarget(link, file.toRelativeString(prefix))
                    ?: continue
                if (relink(file, target)) relinked++
            }
        }

        // Cheap scan: bin/ (the wrappers, the applets and the python/pip scripts) and the git
        // helpers.
        repairShebangs(File(prefix, "bin"), true)
        repairShebangs(File(prefix, "libexec/git-core"), false)

        // A prefix installed by an older build has node packages whose `#!/…/bin/env node`
        // shebangs were never rewritten (npm, npx, node-gyp, …). Walk that tree once: the new
        // extractor rewrites every shebang, so a second pass is not needed.
        val deepMarker = File(prefix, DEEP_REPAIR_MARKER)
        if (!deepMarker.isFile) {
            val modules = File(prefix, "lib/node_modules")
            if (modules.isDirectory) {
                try {
                    Files.walkFileTree(
                        modules.toPath(),
                        object : SimpleFileVisitor<Path>() {
                            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                                val file = path.toFile()
                                val firstLine = firstLineOf(file) ?: return FileVisitResult.CONTINUE
                                if (firstLine.startsWith(TERMUX_SHEBANG_MARK)) {
                                    val repaired =
                                        TermuxShebangs.rewrittenFirstLine(firstLine, prefix.absolutePath)
                                    if (repaired != null && rewriteFirstLine(file, repaired)) rewritten++
                                }
                                return FileVisitResult.CONTINUE
                            }
                        },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not repair the shebangs below $modules", e)
                }
            }
            try {
                deepMarker.writeText("2\n")
            } catch (e: IOException) {
                Log.w(TAG, "Could not write $deepMarker", e)
            }
        }

        for (dir in listOf(File(prefix, "bin"), File(prefix, "libexec/git-core"))) {
            if (!dir.isDirectory) continue
            val gitOnly = dir.name == "bin"
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (gitOnly && !file.name.startsWith("git")) return@forEach
                if (TermuxShellPath.patch(file) > 0) shellPatched++
            }
        }

        ensureEnvironmentLinks(prefix)

        if (relinked == 0 && removed == 0 && rewritten == 0 && shellPatched == 0) return null
        return buildString {
            if (relinked > 0) append("relinked ").append(relinked).append(" Termux symlink(s)")
            if (removed > 0) {
                if (isNotEmpty()) append("; ")
                append("removed ").append(removed).append(" dead Termux shim(s)")
            }
            if (rewritten > 0) {
                if (isNotEmpty()) append("; ")
                append("rewrote ").append(rewritten).append(" Termux shebang(s)")
            }
            if (shellPatched > 0) {
                if (isNotEmpty()) append("; ")
                append("patched ").append(shellPatched).append(" git binary(ies)")
            }
        }
    }

    /** Packages whose marker entry exists but whose proof files are gone. */
    fun brokenPackages(prefix: File): List<String> {
        val recorded = readInstalled(File(prefix, ".packages-installed"))
        if (recorded.isEmpty()) return emptyList()
        return recorded.filter { name ->
            if (name == "glibc") return@filter !hasGlibcLoader(prefix)
            // A truncated extraction leaves a cert.pem that makes every HTTPS request fail with
            // "error adding trust anchors"; the real bundle is ~200 KB.
            if (name == "ca-certificates") {
                val cert = File(prefix, "etc/tls/cert.pem")
                return@filter !cert.isFile || cert.length() < 1024
            }
            val proof = BINARY_PROOF[name] ?: return@filter false
            proof.none { File(prefix, it).exists() }
        }
    }

    private fun hasGlibcLoader(prefix: File): Boolean =
        File(prefix, "glibc/lib").listFiles()?.any { it.name.startsWith("ld-linux") } == true

    /** The packages recorded in the installation marker. */
    fun installed(prefix: File): List<String> =
        readInstalled(File(prefix, ".packages-installed"))

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
                    "Provides" -> pkg.provides.addAll(parseProvides(rawLine.trim()))
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
                "Provides" -> pkg.provides.addAll(parseProvides(value))
                "Description" -> pkg.description = value
            }
        }
    }

    /**
     * Parse a dependency list into alternatives: `nodejs | nodejs-lts` becomes
     * `["nodejs", "nodejs-lts"]`. The resolver picks the first alternative that exists, so a
     * package that is not built for this architecture does not make the installation fail.
     */
    private fun parseDepends(value: String?): List<List<String>> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",").mapNotNull { part ->
            val alternatives = part.split("|").mapNotNull { alternative -> cleanName(alternative) }
                .distinct()
            alternatives.ifEmpty { null }
        }
    }

    private fun parseProvides(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",").mapNotNull { part -> cleanName(part) }
    }

    private fun cleanName(value: String): String? {
        var name = value.trim()
        name = name.substringBefore('(').trim()
        name = name.substringBefore(':').trim()
        return name.ifEmpty { null }
    }

    /**
     * Map virtual package names (`python3`, `awk`, …) to the package that provides them. A real
     * package always wins over a provider.
     */
    private fun buildProviders(index: Map<String, Pkg>): Map<String, Pkg> {
        val providers = LinkedHashMap<String, Pkg>()
        for (pkg in index.values) {
            for (provided in pkg.provides) {
                if (provided in index) continue
                providers.putIfAbsent(provided, pkg)
            }
        }
        return providers
    }

    private fun dependencyClosure(
        index: Map<String, Pkg>,
        providers: Map<String, Pkg>,
        wanted: List<String>,
        installed: List<String>,
        required: List<String>,
    ): List<Pkg> {
        val out = LinkedHashMap<String, Pkg>()
        val seen = HashSet(installed)
        val missing = LinkedHashSet<String>()

        fun lookup(name: String): Pkg? = index[name] ?: providers[name]

        // Depth-first, dependencies before the package that needs them: an add-on extracted
        // after its dependencies can safely overwrite a shared file.
        fun visit(name: String) {
            if (name in SKIPPED_DEPENDENCIES) return
            if (!seen.add(name)) return
            val pkg = lookup(name)
            if (pkg == null) {
                missing.add(name)
                return
            }
            // A provider has its own name in the marker, remember it under both names. When the
            // provider is installed already, the virtual name is satisfied and nothing is added.
            if (pkg.name != name && pkg.name in seen) return
            seen.add(pkg.name)
            for (alternatives in pkg.depends) {
                val chosen = alternatives.firstOrNull { it in SKIPPED_DEPENDENCIES || lookup(it) != null }
                    ?: alternatives.firstOrNull()
                if (chosen != null) visit(chosen)
            }
            out.putIfAbsent(pkg.name, pkg)
        }

        wanted.forEach { visit(it) }

        // A missing dependency is not fatal (some are optional), but a requested package has to
        // exist, otherwise the runtime is incomplete without a useful error message.
        for (name in required) {
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

    /**
     * Download a package from the mirror of its index first, then from every other mirror. A
     * mirror that serves an outdated index or blocks the pool would otherwise break an install
     * that another mirror can serve.
     */
    @Throws(IOException::class)
    private fun downloadWithMirrors(pkg: Pkg, dest: File) {
        val filename = pkg.filename
        // The index is a network response, keep the download inside the repository pool.
        val base = filename.substringAfterLast('/')
        if (filename.startsWith('/') || filename.contains('\\') ||
            filename.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            !base.endsWith(".deb")
        ) {
            throw IOException("Unsafe package filename in the index: $filename")
        }

        val mirrors = LinkedHashSet<String>()
        if (pkg.repo.isNotEmpty()) mirrors.add(pkg.repo)
        mirrors.addAll(MAIN_MIRRORS)
        mirrors.addAll(GLIBC_MIRRORS)

        var lastError: IOException? = null
        for (mirror in mirrors) {
            val url = "$mirror/$filename"
            try {
                downloadVerified(url, dest, pkg.sha256)
                return
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "Could not download $url: ${e.message}")
                dest.delete()
                File(dest.absolutePath + ".part").delete()
            }
        }
        throw lastError ?: IOException("Could not download ${pkg.name}")
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
            val original = entry.linkName
            val rewritten = TermuxLinkRewrite.relativeTarget(original, rel) ?: original
            deletePath(target)
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
        val rewritten = TermuxShebangs.rewrittenFirstLine(shebangLine, prefix.absolutePath)
        val deadTermuxScript = shebangLine.startsWith(TERMUX_SHEBANG_MARK) && rewritten == null

        if (binEntry && (
                name in UNSUPPORTED_BRIDGES ||
                    TermuxShebangs.isWrapperScript(name, shebangLine) ||
                    deadTermuxScript
                )
        ) {
            // Termux bridge wrapper, drop it and keep the real Android command.
            drain(tar)
            return
        }

        deletePath(target)
        FileOutputStream(target).use { out ->
            val newline = if (rewritten != null) indexOfNewline(head, headLength) else -1
            if (rewritten != null && newline >= 0) {
                out.write(rewritten.toByteArray(StandardCharsets.UTF_8))
                out.write(head, newline, headLength - newline)
            } else {
                out.write(head, 0, headLength)
            }
            copy(tar, out)
            out.flush()
        }
        chmod(target, entry.mode)

        // git's ELFs embed the Termux shell path, no environment variable can redirect it.
        if (TermuxShellPath.appliesTo(rel)) TermuxShellPath.patch(target)
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

    /* ------------------------------------------------------------------ links and shebangs */

    /**
     * Termux packages ship absolute symlinks into their own build prefix. They dangle inside this
     * app, so the target is turned into a relative path from the link's directory.
     */
    private object TermuxLinkRewrite {
        fun relativeTarget(linkName: String, linkPath: String): String? {
            val inside = when {
                linkName.startsWith(TERMUX_USR_ABS) -> linkName.substring(TERMUX_USR_ABS.length)
                linkName == TERMUX_USR_ROOT -> ""
                else -> return null
            }
            val depth = linkPath.trim('/').split('/').dropLast(1).count { it.isNotEmpty() }
            return "../".repeat(depth) + inside
        }
    }

    /** Shebang repair for the scripts that ship with the packages. */
    private object TermuxShebangs {
        fun isWrapperScript(name: String, firstLine: String): Boolean =
            name in SYSTEM_WRAPPERS && firstLine.startsWith("#!${TERMUX_USR_ABS}bin/sh")

        /**
         * Rewrite `#!/data/data/com.termux/files/usr/...` into this prefix. The `bin/env`
         * launchers (`#!/…/bin/env node`) are covered too: coreutils installs bin/env.
         */
        fun rewrittenFirstLine(firstLine: String, prefix: String): String? {
            if (!firstLine.startsWith("#!$TERMUX_USR_ABS")) return null
            val line = "#!$prefix/" + firstLine.substring("#!$TERMUX_USR_ABS".length)
            return if (line.contains("/data/data/com.termux")) null else line
        }
    }

    /**
     * Termux's git embeds its SHELL_PATH in the ELF: git runs hooks, aliases and `!` credential
     * helpers through that literal, which does not exist outside the Termux app. The string is
     * patched in place, only inside ELF files, through the shared [ElfStrings] rewriter: the
     * shorter replacement is followed by NULs up to the end of the string that held it, which is
     * dead data, and nothing outside that string is touched (the strings that follow it in .rodata,
     * "cannot get tree for HEAD", … survive).
     */
    private object TermuxShellPath {
        private const val TERMUX_SHELL = "/data/data/com.termux/files/usr/bin/sh"
        private const val NEUTRAL_SHELL = "/system/bin/sh"
        private val NEEDLE = TERMUX_SHELL.toByteArray(StandardCharsets.UTF_8)
        private val REPLACEMENT = NEUTRAL_SHELL.toByteArray(StandardCharsets.UTF_8)

        fun appliesTo(relPath: String): Boolean =
            relPath.startsWith("bin/git") || relPath.startsWith("libexec/git-core/")

        fun patch(file: File): Int = try {
            val bytes = file.readBytes()
            if (bytes.size < 4 || bytes[0] != 0x7f.toByte() ||
                bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() ||
                bytes[3] != 'F'.code.toByte()
            ) {
                0
            } else {
                val count = ElfStrings.replace(bytes, NEEDLE, REPLACEMENT)
                if (count > 0) file.writeBytes(bytes)
                count
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not patch the shell path of $file", e)
            0
        }
    }

    private fun readLink(file: File): String? = try {
        Os.readlink(file.absolutePath)
    } catch (e: Exception) {
        null
    }

    private fun relink(file: File, target: String): Boolean = try {
        Os.remove(file.absolutePath)
        Os.symlink(target, file.absolutePath)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not repair the symlink $file", e)
        false
    }

    private fun firstLineOf(file: File): String? = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(256)
            val length = readUpTo(input, head, head.size)
            val elf = length >= 4 && head[0] == 0x7f.toByte() &&
                head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() &&
                head[3] == 'F'.code.toByte()
            val newline = if (elf) -1 else indexOfNewline(head, length)
            if (newline > 0) String(head, 0, newline, StandardCharsets.UTF_8).trim() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun rewriteFirstLine(file: File, firstLine: String): Boolean = try {
        val text = file.readText(StandardCharsets.UTF_8)
        val newline = text.indexOf('\n')
        val rest = if (newline >= 0) text.substring(newline) else ""
        file.writeText(firstLine + rest, StandardCharsets.UTF_8)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not rewrite the shebang of $file", e)
        false
    }

    /** Delete a file or symlink, including a dangling symlink that File.exists() misses. */
    private fun deletePath(file: File) {
        try {
            Os.remove(file.absolutePath)
        } catch (e: Exception) {
            // Does not exist.
        }
    }

    /* ------------------------------------------------------------------ markers */

    private fun readInstalled(marker: File): List<String> {
        if (!marker.isFile) return emptyList()
        return try {
            marker.readText(Charsets.UTF_8)
                .split("\n")
                .map { line -> line.substringBefore(' ').trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $marker", e)
            emptyList()
        }
    }

    private fun writeInstalled(marker: File, already: List<String>, packages: List<Pkg>) {
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

    /**
     * Some packages ship a /bin/sh link, make sure the prefix always has one. The env link is
     * what `#!/…/bin/env node` launchers of npm need: coreutils provides it, and a prefix
     * installed before coreutils joined the toolchain gets the toybox env as a fallback.
     */
    private fun ensureEnvironmentLinks(prefix: File) {
        val bin = File(prefix, "bin")
        if (!bin.isDirectory) return

        val bash = File(bin, "bash")
        val sh = File(bin, "sh")
        if (bash.isFile && !lexists(sh)) {
            try {
                Os.symlink("bash", sh.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Could not create $sh", e)
            }
        }

        val env = File(bin, "env")
        if (!lexists(env)) {
            val target = if (File(bin, "coreutils").isFile) "coreutils" else "/system/bin/env"
            try {
                Os.symlink(target, env.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Could not create $env", e)
            }
        }

        configureJdk(prefix)
    }

    /**
     * The `openjdk-*` packages install their tools into `lib/jvm/java-<version>-openjdk/bin` and
     * rely on `update-alternatives` in a Debian maintainer script to link them into `bin/`. This
     * prefix has no dpkg, so the maintainer scripts never run: create the links here, otherwise
     * `java` and `javac` are not on PATH even though the JDK is installed. The environment
     * snippet in [OpenCodeBootstrap] points `JAVA_HOME` and the loader path at the newest JDK.
     */
    private fun configureJdk(prefix: File) {
        val jdk = newestJdk(prefix) ?: return
        val tools = File(jdk, "bin").listFiles() ?: return
        val bin = File(prefix, "bin")
        if (!bin.isDirectory) return
        val jdkDir = jdk.relativeTo(prefix).invariantSeparatorsPath

        for (tool in tools) {
            if (!tool.isFile || !tool.canExecute()) continue
            val link = File(bin, tool.name)
            if (link.exists()) continue
            // A Debian alternatives link left by another package may dangle; replace it.
            if (lexists(link)) deletePath(link)
            try {
                Os.symlink("../$jdkDir/bin/${tool.name}", link.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Could not create the JDK link $link", e)
            }
        }
    }

    /** Newest `lib/jvm/java-<version>-openjdk` directory, or null when no JDK is installed. */
    private fun newestJdk(prefix: File): File? =
        File(prefix, "lib/jvm").listFiles()
            ?.filter { it.isDirectory && isJdkName(it.name) }
            ?.maxByOrNull { jdkVersion(it.name) }

    private fun isJdkName(name: String): Boolean =
        name.startsWith("java-") && name.endsWith("-openjdk")

    private fun jdkVersion(name: String): Int =
        name.removePrefix("java-").substringBefore('-').toIntOrNull() ?: 0

    private fun lexists(file: File): Boolean = try {
        Os.lstat(file.absolutePath)
        true
    } catch (e: Exception) {
        false
    }
}

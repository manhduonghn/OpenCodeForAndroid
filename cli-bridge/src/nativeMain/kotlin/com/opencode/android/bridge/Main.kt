@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.opencode.android.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.X_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.getuid
import platform.posix.mkdir
import platform.posix.setenv
import platform.posix.system
import platform.posix.umask
import platform.posix.unsetenv
import platform.posix.unlink
import platform.posix.usleep
import platform.posix.write
import kotlin.system.exitProcess

/*
 * oc-bridge - the command line entry point of the app, written in Kotlin/Native.
 *
 * The app has no shell launchers any more. `pkg` and `oc-install` are symlinks to this one
 * executable, which talks to the app through the same ordered broadcast that the old shell
 * script used. The package service still owns the download, verification and extraction; this
 * program only parses the command, starts the request and follows the status file.
 */

private const val APP = "com.opencode.android"
private const val RECEIVER = "$APP/.OpenCodePkgReceiver"
private const val REQUEST_ACTION = "$APP.pkg.REQUEST"
private const val FORCE_UPDATE_ACTION = "$APP.opencode.FORCE_UPDATE"

private const val PREFIX_FALLBACK = "/data/data/com.opencode.android/files/usr"
private const val HOME_FALLBACK = "/data/data/com.opencode.android/files/home"

private const val RESULT_ACCEPTED = 1
private const val POLL_INTERVAL_US = 200_000
private const val START_TIMEOUT_TICKS = 150
private const val IDLE_TIMEOUT_TICKS = 9000 // 9000 * 200 ms = 30 minutes

private const val USAGE = """Usage: pkg <command> [arguments]

Commands:
  install <package...>   Install Termux packages and their dependencies
  bundle <group...>      Install a curated group: dev, python, node, java, net, all
  search <text>          Search the Termux package repository
  list-installed         List the packages of this prefix
  update, upgrade        Refresh the index (every install uses the live index)
  help                   Show this help

Examples:
  pkg install git gh
  pkg install openjdk
  pkg bundle dev
  pkg search editor
  pkg list-installed

The packages are extracted into the app-private prefix by the app itself, so apt,
dpkg and the Termux app are not needed. 'openjdk' is the same package as
'openjdk-17', 'bundle all' installs every group (it is large and not the whole
repository)."""

private const val BUNDLE_DEV =
    "clang make cmake ninja pkg-config autoconf automake libtool patchelf git gh " +
        "ripgrep fd jq yq tree file less nano vim bat diffutils findutils sed gawk " +
        "grep tar xz-utils zstd 7zip unzip zip curl wget openssh rsync tmux htop " +
        "procps lsof ncurses-utils bash-completion sqlite"
private const val BUNDLE_PYTHON = "python python-pip python-cryptography python-numpy python-pillow"
private const val BUNDLE_NODE = "nodejs npm yarn pnpm"
private const val BUNDLE_JAVA = "openjdk-17 ca-certificates-java"
private const val BUNDLE_NET = "curl wget openssh rsync nmap dnsutils whois net-tools socat"

private class CliError(message: String, val code: Int = 1) : Exception(message)

private fun prefix(): String = env("PREFIX") ?: PREFIX_FALLBACK
private fun home(): String = env("HOME") ?: HOME_FALLBACK
private fun stateDir(): String = "${home()}/.opencode"
private fun packagesMarker(): String = "${prefix()}/.packages-installed"

private fun env(name: String): String? =
    getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

private fun userId(): String = (getuid() / 100000u).toString()

private fun sessionName(): String = "pkg-${getpid()}"

private fun statusPath(session: String): String = "${stateDir()}/$session.status"
private fun resultPath(session: String): String = "${stateDir()}/$session.out"

private fun fail(message: String): Nothing = throw CliError("pkg: $message")

/**
 * Write UTF-8 to a file descriptor.
 *
 * `println`/`print` cannot be used here: on Android the Kotlin/Native runtime compiles with
 * `Kotlin_printToAndroidLogcat = 1` and sends them to logcat instead of stdout, where the terminal
 * would never see them. Writing the bytes directly also avoids the C string conversion, so
 * messages with quotes or non-ASCII text are passed through unchanged.
 */
private fun emit(fd: Int, text: String) {
    val bytes = text.encodeToByteArray()
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
            if (written <= 0) return
            offset += written.toInt()
        }
    }
}

/** Print [text] to stdout without a trailing newline. */
private fun out(text: String) = emit(1, text)

/** Print a line to stdout. */
private fun line(text: String = "") = emit(1, text + "\n")

/** Print a line to stderr. */
private fun err(message: String) = emit(2, message + "\n")

private fun readTextFile(path: String): String? {
    val file = fopen(path, "r") ?: return null
    return try {
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            val text = StringBuilder()
            while (fgets(buffer, 4096, file) != null) {
                text.append(buffer.toKString())
            }
            text.toString()
        }
    } finally {
        fclose(file)
    }
}

private fun deleteFile(path: String) {
    unlink(path)
}

private fun executable(path: String): Boolean = access(path, X_OK) == 0
private fun exists(path: String): Boolean = access(path, F_OK) == 0

private fun ensureStateDir() {
    mkdir(stateDir(), 448u) // 0700, ignored when it already exists
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

private fun compact(value: String): String = value.replace('\r', ' ').replace('\n', ' ').trim()

private fun field(text: String?, key: String): String {
    if (text == null) return ""
    for (line in text.split('\n')) {
        if (line.startsWith("$key=")) return line.substring(key.length + 1).trimEnd('\r')
    }
    return ""
}

/**
 * Send the ordered broadcast to [OpenCodePkgReceiver]. `am start-service` cannot be used: the
 * shell command sends `com.android.shell` as its calling package and Android rejects the request
 * when the real caller is this app ("Can't start from invalid source").
 */
private fun startService(action: String, session: String, packages: String?, query: String?) {
    ensureStateDir()
    val status = statusPath(session)
    val result = resultPath(session)
    deleteFile(status)
    deleteFile(result)

    val outputFile = "${stateDir()}/.am-${getpid()}.out"
    deleteFile(outputFile)
    try {
        val command = buildString {
            append("/system/bin/am broadcast --user ").append(shellQuote(userId()))
            append(" -n ").append(shellQuote(RECEIVER))
            append(" -a ").append(shellQuote(REQUEST_ACTION))
            append(" --es ").append(shellQuote("action")).append(' ').append(shellQuote(action))
            append(" --es ").append(shellQuote("session")).append(' ').append(shellQuote(session))
            if (packages != null) {
                append(" --es ").append(shellQuote("packages")).append(' ')
                    .append(shellQuote(packages))
            }
            if (query != null) {
                append(" --es ").append(shellQuote("query")).append(' ').append(shellQuote(query))
            }
            append(" > ").append(shellQuote(outputFile)).append(" 2>&1")
        }

        val exit = system(command)
        val output = readTextFile(outputFile).orEmpty()
        // Match the whole line, not just "result=1": "result=10" must not look like an accept.
        val accepted = output.contains("Broadcast completed: result=$RESULT_ACCEPTED")
        when {
            accepted -> if (exit != 0) {
                fail("the activity manager failed: ${compact(output)}")
            }
            output.contains("Broadcast completed") ->
                fail("the app did not accept the request: ${compact(output)}")
            output.contains("Error") || output.contains("Exception") ||
                output.contains("SecurityException") ->
                fail("the app refused the request: ${compact(output)}")
            else -> fail("the app did not answer: ${compact(output)}")
        }
    } finally {
        deleteFile(outputFile)
    }
}

/** Follow the status file until the service reports done or error. */
private fun waitForService(session: String) {
    val status = statusPath(session)

    var waited = 0
    while (!exists(status)) {
        usleep(POLL_INTERVAL_US.toUInt())
        waited++
        if (waited >= START_TIMEOUT_TICKS) {
            fail("the app did not answer. Bring OpenCode to the foreground and try again.")
        }
    }

    var lastMessage = ""
    var idle = 0
    while (true) {
        val text = readTextFile(status)
        val state = field(text, "state")
        val message = field(text, "message")
        val percent = field(text, "percent").toIntOrNull() ?: 0

        if (message.isNotEmpty() && message != lastMessage) {
            if (percent == 0) line(message) else line("[$percent%] $message")
            lastMessage = message
            idle = 0
        } else {
            idle++
        }

        when (state) {
            "done" -> return
            "error" -> fail(message.ifEmpty { "the package operation failed." })
        }

        if (idle >= IDLE_TIMEOUT_TICKS) {
            fail("no progress for 30 minutes, giving up. Check the notification of the app.")
        }
        usleep(POLL_INTERVAL_US.toUInt())
    }
}

private fun aliasFor(name: String): String = when (name) {
    "openjdk", "java", "javac", "jdk" -> "openjdk-17"
    "node" -> "nodejs"
    "pip", "pip3" -> "python-pip"
    else -> name
}

private fun validName(name: String): Boolean =
    name.isNotEmpty() && name.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' ||
            it == '+' || it == '.' || it == '_' || it == '-'
    }

private fun bundleFor(group: String): String? = when (group) {
    "dev" -> BUNDLE_DEV
    "python" -> BUNDLE_PYTHON
    "node" -> BUNDLE_NODE
    "java" -> BUNDLE_JAVA
    "net" -> BUNDLE_NET
    "all" -> "$BUNDLE_DEV $BUNDLE_PYTHON $BUNDLE_NODE $BUNDLE_JAVA $BUNDLE_NET"
    else -> null
}

private fun install(arguments: List<String>) {
    val packages = LinkedHashSet<String>()
    for (argument in arguments) {
        when (argument) {
            "-y", "--yes", "-f", "--fix-broken" -> continue
        }
        if (argument.startsWith("-")) fail("install: unsupported option '$argument'")

        val name = aliasFor(argument)
        if (!validName(name)) fail("install: '$argument' is not a valid package name")
        packages.add(name)
        // The JDK ships a trust store that is empty for real CAs; ca-certificates-java adds
        // lib/security/jssecacerts, which Java prefers.
        if (name.startsWith("openjdk-")) packages.add("ca-certificates-java")
    }

    if (packages.isEmpty()) fail("install: no package name given. Try: pkg install git gh")
    if (!executable("${prefix()}/bin/bash")) {
        fail("the Linux runtime is not installed yet. Open the app first.")
    }

    val list = packages.joinToString(" ")
    line("Installing $list ...")
    val session = sessionName()
    try {
        startService("install", session, list, null)
        waitForService(session)
    } finally {
        deleteFile(statusPath(session))
        deleteFile(resultPath(session))
    }

    line()
    line("Installed in ${prefix()}.")
    line("For the new commands in this shell run: . \"${prefix()}/etc/opencode-env.sh\"")
}

private fun bundle(arguments: List<String>) {
    if (arguments.isEmpty()) fail("bundle: no group given. Try: pkg bundle dev")

    val packages = ArrayList<String>()
    for (group in arguments) {
        val list = bundleFor(group)
            ?: fail("bundle: unknown group '$group'. Try: dev, python, node, java, net, all")
        packages.addAll(list.split(' ').filter { it.isNotEmpty() })
    }
    install(packages)
}

private fun search(arguments: List<String>) {
    if (arguments.isEmpty()) fail("search: no search text given. Try: pkg search git")
    val query = arguments.joinToString(" ")
    line("Searching for '$query' ...")

    val session = sessionName()
    try {
        startService("search", session, null, query)
        waitForService(session)

        line()
        val result = readTextFile(resultPath(session)).orEmpty()
        if (result.isNotEmpty()) {
            out(result)
            if (!result.endsWith("\n")) line()
        } else {
            line("No package matched '$query'.")
        }
    } finally {
        deleteFile(statusPath(session))
        deleteFile(resultPath(session))
    }
}

private fun listInstalled() {
    val marker = packagesMarker()
    if (!exists(marker)) {
        line("No package is installed yet.")
        return
    }
    val text = readTextFile(marker).orEmpty()
    val names = text.split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
    if (names.isEmpty()) {
        line("No package is installed yet.")
        return
    }
    for (name in names) line(name)
}

private fun update() {
    line("Every installation downloads the live package index, there is nothing to refresh.")
    line("Run 'pkg install <package>' to install or upgrade a package.")
}

private fun runPkg(arguments: List<String>) {
    val command = arguments.firstOrNull() ?: "help"
    val rest = if (arguments.isEmpty()) emptyList() else arguments.drop(1)

    when (command) {
        "install", "add" -> install(rest)
        "bundle", "toolbox" -> bundle(rest)
        "search", "find" -> search(rest)
        "list-installed", "list", "installed" -> listInstalled()
        "update", "upgrade" -> update()
        "remove", "uninstall", "delete" -> fail(
            "'$command' is not supported: the app installs packages without dpkg, so there " +
                "is no package database to remove from. Clear the app data to start over.",
        )
        "files", "show" -> fail(
            "'$command' is not supported: the packages are extracted directly into " +
                "${prefix()}, without a dpkg database.",
        )
        "help", "-h", "--help", "" -> line(USAGE)
        else -> throw CliError("pkg: unknown command '$command'\n\n$USAGE", 2)
    }
}

private fun runInstall() {
    line("oc-install: asking the OpenCode app to install/update the runtime (watch the notification)")
    val outputFile = "${stateDir()}/.am-install-${getpid()}.out"
    ensureStateDir()
    deleteFile(outputFile)
    try {
        val command = buildString {
            append("/system/bin/am start -a android.intent.action.VIEW")
            append(" -n ").append(shellQuote("$APP/.OpenCodeWebActivity"))
            append(" --ez ").append(shellQuote(FORCE_UPDATE_ACTION)).append(" true")
            append(" > ").append(shellQuote(outputFile)).append(" 2>&1")
        }
        val exit = system(command)
        val output = readTextFile(outputFile).orEmpty()
        if (exit != 0 || output.contains("Error") || output.contains("Exception")) {
            err("oc-install: the app could not be started: ${compact(output)}")
            // exitProcess does not unwind, remove the captured 'am' output first.
            deleteFile(outputFile)
            // system() returns the raw wait status, not the exit code; never pass it to
            // exitProcess, where 256 would be truncated to a successful 0.
            exitProcess(1)
        }
    } finally {
        deleteFile(outputFile)
    }
}

/**
 * The `/system/bin/am` script runs `app_process` without an absolute path, so `/system/bin` has to
 * be in PATH. The library path of the Termux prefix and a bionic preload must not leak into the
 * Android commands that the bridge starts.
 */
private fun prepareEnvironment() {
    unsetenv("LD_PRELOAD")
    unsetenv("LD_LIBRARY_PATH")
    val path = getenv("PATH")?.toKString().orEmpty()
    if (path.split(':').none { it == "/system/bin" }) {
        setenv("PATH", if (path.isEmpty()) "/system/bin" else "/system/bin:$path", 1)
    }
}

/** argv[0] is not in [args], read it back from /proc so pkg and oc-install are distinguishable. */
private fun invokedName(): String {
    val line = readTextFile("/proc/self/cmdline").orEmpty()
    val command = line.substringBefore('\u0000').trim()
    return command.substringAfterLast('/')
}

private fun route(args: Array<String>): Pair<String, List<String>> {
    val invoked = invokedName()
    if (invoked == "pkg" || invoked == "oc-install") return invoked to args.toList()

    val first = args.firstOrNull()
    if (first == "pkg" || first == "oc-install") return first to args.drop(1)
    return "pkg" to args.toList()
}

fun main(args: Array<String>) {
    umask(63u) // 0077, the status files belong to this app only
    prepareEnvironment()
    val (command, rest) = route(args)
    try {
        when (command) {
            "oc-install" -> runInstall()
            else -> runPkg(rest)
        }
    } catch (e: CliError) {
        err(e.message ?: "pkg: the command failed")
        exitProcess(e.code)
    } catch (t: Throwable) {
        err("pkg: ${t.message ?: t::class.simpleName}")
        exitProcess(1)
    }
}

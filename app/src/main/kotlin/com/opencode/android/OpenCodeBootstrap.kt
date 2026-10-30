package com.opencode.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

/**
 * Bootstraps the official OpenCode CLI natively inside Android and keeps it runnable.
 *
 * The official OpenCode CLI is only published as a glibc ELF binary, while Android only ships
 * bionic. The binary therefore gets the glibc loader as its ELF interpreter and runs directly, the
 * way a normal Linux program does:
 *
 * ```
 * download the official npm tarball @opencode/cli-linux-<arch>
 *   -> unpack package/bin/opencode in Kotlin (no tar binary needed)
 *   -> relocate the glibc runtime and set the binary's PT_INTERP to its loader
 *   -> start the binary directly: /proc/self/exe is the binary, so OpenCode can spawn its own
 *      background `serve` process (that was broken with an explicit loader)
 *   -> fall back to "$PREFIX/glibc/lib/ld-linux-*.so.* --library-path" if the patch fails
 * ```
 *
 * Everything is done from this single object so that the installation keeps its progress,
 * survives the app being killed mid download and is able to repair itself afterwards. No root,
 * no proot and no chroot is needed.
 */
object OpenCodeBootstrap {

    private const val LOG_TAG = "OpenCodeBootstrap"

    /* ------------------------------------------------------------------ public API */

    /** Intent action asking the app to (re)install the OpenCode runtime. */
    const val ACTION_FORCE_UPDATE = "com.opencode.android.opencode.FORCE_UPDATE"

    /** The command the user types inside the terminal to start the OpenCode TUI. */
    const val OPENCODE_COMMAND = "opencode"

    /**
     * A listener that is notified every time the state of the installation changes. The callback
     * is always invoked on the UI thread.
     */
    fun interface InstallListener {
        fun onOpenCodeInstallStateChanged(state: OpenCodeInstallState?)
    }

    /* ------------------------------------------------------------------ paths */

    /** App-private Termux-like prefix, eg. `/data/data/com.opencode.android/files/usr`. */
    const val PREFIX = "/data/data/com.opencode.android/files/usr"

    /**
     * Root of the sandbox (`context.getApplicationInfo().dataDir`). It has exactly the same length
     * as the prefix that is baked into the Termux packages
     * (`/data/data/com.termux/files/usr`), which makes it possible to relocate compiled-in paths
     * in place: every byte is replaced one to one, so no offset moves.
     */
    const val DATA_DIR = "/data/data/com.opencode.android"

    /** Alias of [PREFIX]/glibc, target of the relocated loader search path. */
    const val GLIBC_ALIAS_DIR = "$DATA_DIR/glibc"

    /** Alias of [PREFIX]/etc, target of the relocated login profile of bash. */
    const val ETC_ALIAS_DIR = "$DATA_DIR/etc"

    /** App-private Termux-like home, eg. `/data/data/com.opencode.android/files/home`. */
    const val HOME = "/data/data/com.opencode.android/files/home"

    /** State directory of the installer. */
    const val OC_HOME = "$HOME/.opencode"

    /** Directory holding the installed OpenCode builds. */
    const val INSTALL_DIR = "$OC_HOME/installed"

    /** Directory holding the `opencode` symlink. */
    const val LINK_DIR = "$OC_HOME/bin"

    /** Symlink pointing to the currently installed binary. */
    const val LINK_PATH = "$LINK_DIR/opencode"

    /** Contains the version that is currently installed. */
    const val READY_MARKER_PATH = "$OC_HOME/.native-ready"

    /**
     * Fingerprint ("<size> <mtime>") of the installed binary whose ELF interpreter was pointed at
     * the glibc loader. Present only when the binary really starts directly.
     */
    const val NATIVE_INTERP_MARKER_PATH = "$OC_HOME/.native-interp"

    /** Remembers a failed attempt, so a 240 MB copy is not retried on every app start. */
    private const val NATIVE_REPAIR_MARKER_PATH = "$OC_HOME/.native-repair-tried"

    /** Directory used while downloading and patching. */
    const val TMP_DIR = "$OC_HOME/tmp"

    /** Directory holding the glibc loader shipped by `glibc-runner`. */
    const val GLIBC_LIB_DIR = "$PREFIX/glibc/lib"

    /** Symlink to the currently installed OpenCode binary, `opencode`. */
    const val OPENCODE_BIN_PATH = "$PREFIX/bin/opencode"

    /** Symlink to [BRIDGE_PATH], `oc-install`. */
    const val OC_INSTALL_PATH = "$PREFIX/bin/oc-install"

    /** Symlink to [BRIDGE_PATH], `pkg`. */
    const val PKG_PATH = "$PREFIX/bin/pkg"

    /** Kotlin/Native executable behind `pkg` and `oc-install`. */
    const val BRIDGE_PATH = "$PREFIX/libexec/oc-bridge"

    /** Records which app build extracted the current bridge. */
    const val BRIDGE_MARKER_PATH = "$OC_HOME/.cli-bridge"

    /** Environment snippet that the shell startup files of the runtime source. */
    const val ENV_SCRIPT_PATH = "$PREFIX/etc/opencode-env.sh"

    /** HTTP Basic user name the `opencode serve` of the 2.x line expects. */
    const val SERVER_USER = "opencode"

    /** Password of the server, exported by the environment and used by the WebView. */
    const val SERVER_PASSWORD_PATH = "$OC_HOME/server-password"

    /** Installer script of older versions, removed on sight. */
    const val LEGACY_SETUP_SCRIPT_PATH = "$OC_HOME/setup.sh"

    /** DNS configuration of Termux. */
    const val RESOLV_CONF_PATH = "$PREFIX/etc/resolv.conf"

    /**
     * Configuration directory of the glibc runtime. libc reads `resolv.conf`, `hosts` and
     * `nsswitch.conf` from here (via the `<dataDir>/glibc` alias), which is where the resolver
     * configuration has to be written.
     */
    const val GLIBC_ETC_DIR = "$PREFIX/glibc/etc"

    /** Records for which glibc build the shared objects were relocated to this sandbox. */
    private const val GLIBC_RELOCATED_PATH = "$OC_HOME/.glibc-relocated"

    /**
     * Upper bound of a glibc object that is relocated. libstdc++ (22 MB), libasan (11 MB) and the
     * sanitizer runtimes carry only compiler paths, while everything that the resolver and the
     * runtime read at run time stays well below this limit.
     */
    private const val MAX_RELOCATED_FILE_SIZE = 8L * 1024 * 1024

    /* ------------------------------------------------------------------ constants */

    /** Bumped when the bridge binary has to be extracted again, even for the same app build. */
    private const val BRIDGE_VERSION = 1

    /** Bumped when the native interpreter repair has to be retried for an installed binary. */
    private const val NATIVE_REPAIR_VERSION = 11

    /** First line of the profile this app writes, a profile without it is left alone. */
    private const val PROFILE_MARKER =
        "# Managed by the OpenCode for Android app. opencode-profile-version: 2"

    /** Markers of the block that hooks the package environment into etc/bash.bashrc. */
    private const val BASHRC_BLOCK_BEGIN = "# >>> OpenCode for Android package environment >>>"
    private const val BASHRC_BLOCK_END = "# <<< OpenCode for Android package environment <<<"

    private const val USER_AGENT = "OpenCodeForAndroid"

    private const val HTTP_CONNECT_TIMEOUT_MS = 20_000L
    private const val HTTP_READ_TIMEOUT_MS = 60_000L
    private const val VERIFY_TIMEOUT_MS = 90_000L
    private const val PATCH_TIMEOUT_MS = 300_000L
    private const val EXTRACT_TIMEOUT_MS = 180_000L
    private const val PROCESS_POLL_INTERVAL_MS = 100L

    /** Maximum number of characters of a single command output kept in memory. */
    private const val MAX_OUTPUT_CHARS = 256 * 1024

    /** Maximum length of the detail part of a notification. */
    private const val MAX_DETAIL_CHARS = 900

    /** Prefix that the Termux packages are built for, it has the same length as [DATA_DIR]. */
    private const val PREFIX_TERMUX = "/data/data/com.termux/files/usr"

    /** Home directory that is baked into the Termux profile scripts. */
    private const val HOME_TERMUX = "/data/data/com.termux/files/home"

    /** Mirrors of the main Termux repository, tried in order. */
    private val MAIN_MIRRORS = arrayOf(
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
        "https://grimler.se/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
    )

    /**
     * Termux packages that are extracted into the app-private prefix before OpenCode is
     * downloaded: a shell, the core utilities (they provide `bin/env`, which the `#!/…/bin/env`
     * launchers of npm and pip need), the certificate store, the glibc runtime (the loader that
     * runs OpenCode) and patchelf (it rewrites the RUNPATH of the binaries).
     */
    private val CORE_PACKAGES = listOf("bash", "coreutils", "ca-certificates", "patchelf", "glibc")

    /** Endpoint telling which OpenCode version is the latest one. */
    private const val VERSION_API_URL = "https://opencode.ai/update/api/latest/cli/npm"

    /** npm registry used as fallback for the version lookup and for the download. */
    private const val NPM_REGISTRY_URL = "https://registry.npmjs.org"

    /** The npm packages that carry the platform build, the 2.x line renamed the scope. */
    private val PLATFORM_PACKAGE_PREFIXES = arrayOf(
        "@opencode/cli-linux-", "@opencode-ai/cli-linux-",
    )

    private val VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"")
    private val VERSION_OUTPUT_PATTERN = Pattern.compile("(?s).*\\d+\\.\\d+.*")

    /** Notification channel used for the progress and the result of an installation. */
    const val NOTIFICATION_CHANNEL_ID = "opencode_notification_channel"

    /** Name of the notification channel. */
    const val NOTIFICATION_CHANNEL_NAME = "OpenCode"

    /** Id of the notification used for the progress and the result of an installation. */
    const val NOTIFICATION_ID = 1338

    private val sLock = Any()

    /** The installer that is currently running, null when nothing is being installed. */
    private var sInstaller: Installer? = null

    /** True while the silent maintenance of the runtime runs in the background. */
    private var sMaintenanceRunning = false

    /** The listeners that want to know about the progress of the installation. */
    private val sListeners = CopyOnWriteArrayList<InstallListener>()

    /** The callbacks that are waiting for the installation to finish. */
    private val sInstallWaiters = ArrayList<() -> Unit>()

    /** The state of the installation, it is mirrored on the disk. */
    private var sState = OpenCodeInstallState()

    /** Name of the shared preferences file of the app. */
    private const val PREFERENCES_NAME = "opencode"

    /** Key of the server password in the preferences. */
    private const val PREF_SERVER_PASSWORD = "server_password"

    /** Cached server password, see [getServerPassword]. */
    private var sServerPassword: String? = null

    /** The handler of the UI thread, used to notify the listeners. */
    private val sMainHandler = Handler(Looper.getMainLooper())

    /* ------------------------------------------------------------------ public API */

    /**
     * Make sure that the OpenCode runtime is complete and call [onReady] afterwards.
     *
     * If something is missing, the installer is started and [onReady] is called once the
     * installation finished (successfully or not). The callback is always invoked on the UI
     * thread. When an installation is already running, the request is queued and the progress is
     * shown by the listeners.
     */
    @JvmOverloads
    fun setupIfNeeded(context: Context?, onReady: (() -> Unit)? = null) {
        if (context == null) return

        // Nothing has to be downloaded. Refresh the CLI entry points in the background without
        // showing any progress UI, otherwise the installation dialog would flash on every cold
        // start even though OpenCode is already installed.
        if (!isInstallRunning()) {
            val problems = runtimeProblems()
            if (problems.isEmpty()) {
                clearFinishedState()
                maintainInBackground(context)
                runOnUiThread(context, onReady)
                return
            }
            // An app update adds the Kotlin/Native bridge and replaces the old shell launchers with
            // symlinks. That only needs the cheap maintenance pass, not a 100 MB reinstall.
            if (problems.all { isEntryPointProblem(it) }) {
                Log.i(LOG_TAG, "Only the CLI entry points need to be refreshed: $problems")
                clearFinishedState()
                maintainInBackground(context)
                runOnUiThread(context, onReady)
                return
            }
        }

        val installer: Installer
        synchronized(sLock) {
            val running = sInstaller
            if (running != null) {
                Log.i(LOG_TAG, "An installation is already running, queueing the request.")
                if (onReady != null) sInstallWaiters.add(onReady)
                return
            }
            installer = Installer(context, false)
            sInstaller = installer
            if (onReady != null) sInstallWaiters.add(onReady)
        }

        installer.start()
    }

    /**
     * Force a (re)installation of the OpenCode runtime. The user is asked through the
     * notification, the installation itself runs in the background.
     */
    fun requestUpdate(context: Context?) {
        if (context == null) return

        if (!isPrefixReady()) {
            Log.w(LOG_TAG, "The Linux toolchain prefix is not ready yet, cannot update OpenCode.")
            return
        }

        val installer: Installer
        synchronized(sLock) {
            if (sInstaller != null) {
                Log.i(LOG_TAG, "An installation is already running, ignoring the update request.")
                return
            }
            installer = Installer(context, true)
            sInstaller = installer
        }

        installer.start()
    }

    /**
     * Cheap check run whenever the app comes to the foreground: if the runtime is broken it is
     * repaired, otherwise nothing happens.
     */
    fun checkRuntime(context: Context?) {
        if (context == null) return
        if (!isPrefixReady()) return

        val problems = runtimeProblems()
        if (problems.isEmpty()) {
            // Everything is fine, but the entry points may belong to an older build of the app, so
            // refresh them in the background.
            clearFinishedState()
            maintainInBackground(context)
        } else {
            Log.i(LOG_TAG, "The OpenCode runtime is incomplete: ${problems.joinToString(", ")}")
            setupIfNeeded(context, null)
        }
    }

    /**
     * Drop the state of an installation that finished (successfully or not) when the runtime is
     * complete, so that a stale result is not shown again on the next start.
     */
    private fun clearFinishedState() {
        synchronized(sLock) {
            if (sInstaller != null) return
            sState = OpenCodeInstallState()
        }
        OpenCodeInstallState.clear()
    }

    /**
     * Refresh the CLI entry points in the background. No progress is published and no dialog is
     * shown, so this is safe to call on every start of the app.
     */
    private fun maintainInBackground(context: Context) {
        synchronized(sLock) {
            // Never touch the runtime while an installation is replacing it.
            if (sInstaller != null || sMaintenanceRunning) return
            sMaintenanceRunning = true
        }

        Thread({
            try {
                Installer(context, false).apply {
                    logOnly = true
                    doMaintenance()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to maintain the OpenCode runtime", e)
            } finally {
                synchronized(sLock) { sMaintenanceRunning = false }
            }
        }, "opencode-maintenance").start()
    }

    /**
     * Read the state of the last installation from the disk. It has to be called once when the
     * process of the app starts, so that the screen of the app shows the real progress even when
     * Android killed the app while it was downloading something.
     */
    fun restoreState() {
        synchronized(sLock) {
            // An installation that is still running has a state that is up to date.
            if (sInstaller != null) return

            val saved = OpenCodeInstallState.load()
            // A succeeded or failed installation is stale when the runtime is complete, the app
            // must not show the result of an earlier run again on every start.
            if (!saved.running && (saved.done || saved.failed) && runtimeProblems().isEmpty()) {
                OpenCodeInstallState.clear()
                return
            }

            if (saved.running) {
                // The process was killed, the installation is not running anymore.
                saved.running = false
                saved.detail = "The app was closed, the installation was interrupted."
                saved.addStep("The previous installation was interrupted, it is resumed automatically.")
            }

            sState = saved
        }
    }

    /** The current state of the installation, never null. */
    fun getInstallState(): OpenCodeInstallState = synchronized(sLock) { copyOf(sState) }

    /**
     * Write the resolver configuration that the glibc runtime of this prefix reads.
     *
     * glibc cannot use the resolver of Android (bionic hands DNS over to netd); it reads its
     * nameservers from `$PREFIX/glibc/etc/resolv.conf`. The servers of the active network are
     * used, because they change with Wi-Fi, mobile data and VPN, so the file is rewritten every
     * time the server starts. Public resolvers are only a last resort for a network that does not
     * report its own servers.
     */
    fun refreshResolvConf(context: Context?) {
        val servers = ArrayList<String>()
        if (context != null) {
            try {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network: Network? = manager?.activeNetwork
                val properties = if (network == null) null else manager.getLinkProperties(network)
                properties?.dnsServers?.forEach { address: InetAddress ->
                    if (address.isAnyLocalAddress || address.isLinkLocalAddress) return@forEach
                    val host = address.hostAddress
                    if (host.isNullOrEmpty() || host in servers) return@forEach
                    servers.add(host)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not read the DNS servers of the active network", e)
            }
        }
        if (servers.isEmpty()) {
            // The device does not report its resolvers (rare, but some ROMs do that).
            servers.add("1.1.1.1")
            servers.add("8.8.8.8")
        }

        val content = buildString {
            servers.take(4).forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:2\n")
        }

        // resolv.conf of the prefix and the one the glibc libraries read. In the Termux packages
        // the second path is a symlink to the first one, writing both also repairs a missing link.
        for (path in listOf(RESOLV_CONF_PATH, "$GLIBC_ETC_DIR/resolv.conf")) {
            val file = File(path)
            if (file.parentFile?.isDirectory != true) continue
            try {
                writeTextFile(file, content)
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Could not write $path", e)
            }
        }
        Log.i(LOG_TAG, "Resolver configuration: $servers")
    }

    /**
     * The password of the local OpenCode server.
     *
     * The 2.x server requires HTTP Basic authentication and otherwise prints a random password on
     * every start. The app generates one on the first use and keeps it, so the WebView and the
     * terminal always know it.
     */
    fun getServerPassword(context: Context?): String = synchronized(sLock) {
        sServerPassword?.let { return it }

        var password: String? = null
        if (context != null) {
            try {
                password = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_SERVER_PASSWORD, null)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not read the server password", e)
            }
        }

        if (password == null || password.length < 16) {
            val random = ByteArray(24)
            SecureRandom().nextBytes(random)
            password = buildString(random.size * 2) {
                for (b in random) append(String.format(java.util.Locale.US, "%02x", b.toInt() and 0xFF))
            }
            if (context != null) {
                try {
                    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .edit().putString(PREF_SERVER_PASSWORD, password).apply()
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Could not store the server password", e)
                }
            }
        }

        val result = password ?: ""
        sServerPassword = result
        result
    }

    /** True while an installation is running. */
    fun isInstallRunning(): Boolean = synchronized(sLock) { sInstaller != null }

    /** Register a listener that is notified about every change of the state. */
    fun addInstallListener(listener: InstallListener?) {
        if (listener != null) sListeners.add(listener)
    }

    /** Unregister a listener. */
    fun removeInstallListener(listener: InstallListener?) {
        if (listener != null) sListeners.remove(listener)
    }

    /**
     * The list of things that are missing for OpenCode to run. An empty list means that the
     * runtime is complete.
     */
    fun runtimeProblems(): List<String> {
        val problems = ArrayList<String>()

        if (!isPrefixReady()) problems.add("the Linux toolchain ($PREFIX/bin/bash)")
        if (!File("$PREFIX/bin/sh").exists()) problems.add("the shell link ($PREFIX/bin/sh)")
        if (!File(READY_MARKER_PATH).isFile) problems.add("the installed version marker ($READY_MARKER_PATH)")
        if (!File(OPENCODE_BIN_PATH).exists()) problems.add("the opencode symlink ($OPENCODE_BIN_PATH)")
        if (!isExecutable(File(BRIDGE_PATH))) problems.add("the CLI bridge ($BRIDGE_PATH)")
        if (!File(OC_INSTALL_PATH).exists()) problems.add("the oc-install symlink ($OC_INSTALL_PATH)")
        if (!File(PKG_PATH).exists()) problems.add("the pkg symlink ($PKG_PATH)")
        if (!File(LINK_PATH).exists()) problems.add("the opencode symlink ($LINK_PATH)")
        if (findGlibcLoader() == null) problems.add("the glibc loader ($GLIBC_LIB_DIR/ld-linux-*.so.*)")
        if (!isExecutable(File("$PREFIX/bin/patchelf"))) problems.add("the patchelf tool ($PREFIX/bin/patchelf)")

        return problems
    }

    /** True for a problem that the maintenance pass repairs without downloading anything. */
    private fun isEntryPointProblem(problem: String): Boolean =
        problem == "the opencode symlink ($OPENCODE_BIN_PATH)" ||
            problem == "the CLI bridge ($BRIDGE_PATH)" ||
            problem == "the oc-install symlink ($OC_INSTALL_PATH)" ||
            problem == "the pkg symlink ($PKG_PATH)"

    /** True if the OpenCode runtime is complete and can be started. */
    fun isRuntimeComplete(): Boolean = runtimeProblems().isEmpty()

    /** The version of OpenCode that is currently installed, or an empty string. */
    fun getInstalledVersion(): String = readTrimmed(File(READY_MARKER_PATH))

    /** True if the app-private Linux toolchain prefix is usable. */
    fun isPrefixReady(): Boolean {
        val bash = File("$PREFIX/bin/bash")
        return bash.isFile && bash.canExecute()
    }

    /**
     * Write the environment of the installed Termux packages and hook it into the shell startup
     * files of the prefix. The package service calls this after every installation: the Termux
     * binaries link against `$PREFIX/lib` and the git build has its helper programs below the old
     * Termux prefix. Idempotent, safe to call at any time.
     */
    fun ensurePackageEnvironment() {
        writePackageEnvironment()
    }

    /**
     * Write `etc/opencode-env.sh` and hook it into the shell startup files. The profile is only
     * replaced when this app wrote it or when it still points at the Termux app; a profile edited
     * by the user is left untouched.
     */
    private fun writePackageEnvironment() {
        val etc = File(PREFIX, "etc")
        if (!etc.isDirectory && !etc.mkdirs()) {
            Log.w(LOG_TAG, "Could not create $etc")
            return
        }
        writeTextFileQuietly(File(ENV_SCRIPT_PATH), buildPackageEnvironment())
        writeGitConfig()
        ensureLoginProfile()
        hookBashrc()
    }

    /**
     * Write `etc/gitconfig` with the CA bundle of this prefix. Termux's libcurl was built with
     * `/data/data/com.termux/files/usr/etc/tls/cert.pem` as its default CA file, a path that does
     * not exist inside this app, and OpenSSL ignores `SSL_CERT_FILE` when curl passes that
     * explicit file. `http.sslCAInfo` makes git hand our bundle to libcurl instead, for the app's
     * clone as well as for a clone from the terminal.
     */
    private fun writeGitConfig() {
        val cert = File(PREFIX, "etc/tls/cert.pem")
        if (!cert.isFile) return
        val text = buildString {
            append("# Managed by the OpenCode for Android app.\n")
            append("[http]\n")
            append("\tsslCAInfo = ").append(cert.absolutePath).append('\n')
        }
        writeTextFileQuietly(File(PREFIX, "etc/gitconfig"), text)
    }

    /**
     * The Termux packages link against `$PREFIX/lib` with an absolute RUNPATH that points at the
     * com.termux prefix. The git build additionally has its helper programs and its template
     * directory compiled in below the old prefix. Neither is readable from this app, so both are
     * re-rooted here. The file is sourced by the login profile and by etc/bash.bashrc.
     */
    private fun buildPackageEnvironment(): String = buildString {
        append("# Managed by the OpenCode for Android app (package environment v2).\n")
        append("# Sourced by etc/profile and etc/bash.bashrc of the runtime.\n")
        append("PREFIX=\"\${PREFIX:-").append(PREFIX).append("}\"\n")
        append("export PREFIX\n")
        append("case \":\${PATH:-}:\" in\n")
        append("  *\":\$PREFIX/bin:\"*) ;;\n")
        append("  *) PATH=\"\$PREFIX/bin\${PATH:+:\$PATH}\"; export PATH ;;\n")
        append("esac\n")
        append("TMPDIR=\"\${TMPDIR:-\$PREFIX/tmp}\"; export TMPDIR\n")
        append("[ -d \"\$TMPDIR\" ] || mkdir -p \"\$TMPDIR\" 2>/dev/null\n")
        append("case \":\${LD_LIBRARY_PATH:-}:\" in\n")
        append("  *\":\$PREFIX/lib:\"*) ;;\n")
        append("  *) LD_LIBRARY_PATH=\"\$PREFIX/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"; export LD_LIBRARY_PATH ;;\n")
        append("esac\n")
        // The app owns the installed binary, and the bionic preload of Termux must never be
        // loaded into a glibc process.
        append("OPENCODE_DISABLE_AUTOUPDATE=1; export OPENCODE_DISABLE_AUTOUPDATE\n")
        append("unset LD_PRELOAD\n")
        append("[ -n \"\${SHELL:-}\" ] || { SHELL=\"\$PREFIX/bin/bash\"; export SHELL; }\n")
        // The JDK links its own libraries relative to JAVA_HOME, but its RUNPATH still points at
        // /data/data/com.termux. Point the loader at the relocated JDK as well.
        jdkHome()?.let { jdk ->
            append("JAVA_HOME=\"\${JAVA_HOME:-").append(jdk.absolutePath).append("}\"; export JAVA_HOME\n")
            append("case \":\${LD_LIBRARY_PATH:-}:\" in\n")
            append("  *\":\$JAVA_HOME/lib:\"*) ;;\n")
            append("  *) LD_LIBRARY_PATH=\"\$JAVA_HOME/lib:\$JAVA_HOME/lib/server:\$LD_LIBRARY_PATH\"; export LD_LIBRARY_PATH ;;\n")
            append("esac\n")
            append("case \":\${PATH:-}:\" in\n")
            append("  *\":\$JAVA_HOME/bin:\"*) ;;\n")
            append("  *) PATH=\"\$JAVA_HOME/bin:\$PATH\"; export PATH ;;\n")
            append("esac\n")
        }
        append("# npm and pip keep their caches in the app HOME, the prefix may be read only for them.\n")
        append("npm_config_cache=\"\${npm_config_cache:-\${HOME:-\$PREFIX}/.npm}\"; export npm_config_cache\n")
        append("PIP_CACHE_DIR=\"\${PIP_CACHE_DIR:-\${HOME:-\$PREFIX}/.cache/pip}\"; export PIP_CACHE_DIR\n")
        append("# git was built for /data/data/com.termux/files/usr: its helpers would be looked up\n")
        append("# there, which belongs to another app.\n")
        append("if [ -d \"\$PREFIX/libexec/git-core\" ]; then\n")
        append("  GIT_EXEC_PATH=\"\$PREFIX/libexec/git-core\"; export GIT_EXEC_PATH\n")
        append("fi\n")
        append("if [ -d \"\$PREFIX/share/git-core/templates\" ]; then\n")
        append("  GIT_TEMPLATE_DIR=\"\$PREFIX/share/git-core/templates\"; export GIT_TEMPLATE_DIR\n")
        append("fi\n")
        append("if [ -f \"\$PREFIX/etc/gitconfig\" ]; then\n")
        append("  GIT_CONFIG_SYSTEM=\"\$PREFIX/etc/gitconfig\"; export GIT_CONFIG_SYSTEM\n")
        append("  unset GIT_CONFIG_NOSYSTEM\n")
        append("else\n")
        append("  GIT_CONFIG_NOSYSTEM=1; export GIT_CONFIG_NOSYSTEM\n")
        append("fi\n")
        append("GIT_ATTR_NOSYSTEM=1; export GIT_ATTR_NOSYSTEM\n")
        append("if [ -f \"\$PREFIX/etc/openssl/openssl.cnf\" ]; then\n")
        append("  OPENSSL_CONF=\"\$PREFIX/etc/openssl/openssl.cnf\"; export OPENSSL_CONF\n")
        append("fi\n")
        append("if [ -f \"\$PREFIX/etc/tls/cert.pem\" ]; then\n")
        append("  SSL_CERT_FILE=\"\$PREFIX/etc/tls/cert.pem\"; export SSL_CERT_FILE\n")
        append("  CURL_CA_BUNDLE=\"\$SSL_CERT_FILE\"; export CURL_CA_BUNDLE\n")
        append("  NODE_EXTRA_CA_CERTS=\"\$SSL_CERT_FILE\"; export NODE_EXTRA_CA_CERTS\n")
        append("fi\n")
        // The WebView and the terminal use the same server password, so a server started from
        // the terminal and the one of the app cannot disagree about the credentials.
        append("if [ -z \"\${OPENCODE_SERVER_PASSWORD:-}\" ] && [ -f \"")
        append(SERVER_PASSWORD_PATH)
        append("\" ]; then\n")
        append("  IFS= read -r OPENCODE_SERVER_PASSWORD < \"")
        append(SERVER_PASSWORD_PATH)
        append("\" 2>/dev/null\n")
        append("  if [ -n \"\$OPENCODE_SERVER_PASSWORD\" ]; then export OPENCODE_SERVER_PASSWORD; fi\n")
        append("fi\n")
        append(":\n")
    }

    /** Newest `lib/jvm/java-<version>-openjdk` of the prefix, or null when no JDK is installed. */
    fun javaHome(): String? = jdkHome()?.absolutePath

    /**
     * Newest `lib/jvm/java-<version>-openjdk` of the prefix, or null when no JDK is installed.
     * Kept in sync with the symlink logic of [OpenCodePackages].
     */
    private fun jdkHome(): File? =
        File(PREFIX, "lib/jvm").listFiles()
            ?.filter {
                it.isDirectory &&
                    it.name.startsWith("java-") &&
                    it.name.endsWith("-openjdk")
            }
            ?.maxByOrNull {
                it.name.removePrefix("java-").substringBefore('-').toIntOrNull() ?: 0
            }

    /** Write the managed login profile, unless the user replaced it with one of their own. */
    private fun ensureLoginProfile() {
        val profile = File("$PREFIX/etc/profile")
        val current = readTextFileQuietly(profile)
        val foreign = current.contains(PREFIX_TERMUX) || current.contains(HOME_TERMUX)
        val ours = current.contains("Managed by the OpenCode for Android app.")
        // A profile without our marker and without Termux paths belongs to the user.
        if (profile.isFile && !foreign && !ours) return

        val content = buildString {
            append(PROFILE_MARKER).append('\n')
            append("export HOME=\"\${HOME:-").append(HOME).append("}\"\n")
            append("export PREFIX=\"\${PREFIX:-").append(PREFIX).append("}\"\n")
            append("export SHELL=\"\${SHELL:-\$PREFIX/bin/bash}\"\n")
            append("export PATH=\"\$PREFIX/bin:/system/bin\"\n")
            append("export TMPDIR=\"\${TMPDIR:-\$PREFIX/tmp}\"\n")
            append("export TERM=\"\${TERM:-xterm-256color}\"\n")
            append("export LANG=\"\${LANG:-en_US.UTF-8}\"\n")
            append("if [ -r \"\$PREFIX/etc/opencode-env.sh\" ]; then . \"\$PREFIX/etc/opencode-env.sh\"; fi\n")
            append("if [ -r \"\$HOME/.bashrc\" ]; then . \"\$HOME/.bashrc\"; fi\n")
        }
        writeTextFileQuietly(profile, content)
    }

    /**
     * Hook the package environment into etc/bash.bashrc, which the interactive non-login shells of
     * the terminal read. The block of an older app version is replaced, anything the user added
     * above it is kept.
     */
    private fun hookBashrc() {
        val bashrc = File("$PREFIX/etc/bash.bashrc")
        if (!bashrc.isFile) return

        val current = readTextFileQuietly(bashrc)
        val start = current.indexOf(BASHRC_BLOCK_BEGIN)
        var base = if (start >= 0) current.substring(0, start) else current
        // Trim the trailing whitespace of the package file so the block starts on a clean line.
        while (base.endsWith("\n") || base.endsWith("\r") || base.endsWith(" ")) {
            base = base.dropLast(1)
        }

        val updated = buildString {
            append(base)
            if (isNotEmpty()) append("\n\n")
            append(BASHRC_BLOCK_BEGIN).append('\n')
            append("if [ -r \"").append(ENV_SCRIPT_PATH).append("\" ]; then\n")
            append("  . \"").append(ENV_SCRIPT_PATH).append("\"\n")
            append("fi\n")
            append(BASHRC_BLOCK_END).append('\n')
        }
        if (updated != current) writeTextFileQuietly(bashrc, updated)
    }

    /* ------------------------------------------------------------------ installer */

    private class Installer(context: Context?, private val forceUpdate: Boolean) {

        private val context: Context? = context?.applicationContext
        private val env = HashMap<String, String>()
        private var lastOutput = ""
        var logOnly = false

        init {
            // Environment of the spawned processes: own toolchain prefix, no bionic preload for
            // glibc processes.
            env["HOME"] = HOME
            env["PREFIX"] = PREFIX
            env["PATH"] = "$PREFIX/bin"
            env["TMPDIR"] = "$PREFIX/tmp"
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"
            env["COLORTERM"] = "truecolor"
            env["DEBIAN_FRONTEND"] = "noninteractive"
            env["TERMUX_PKG_NO_MIRROR_SELECT"] = "1"
            // The Termux binaries link their libraries with a hardcoded RUNPATH that points at the
            // com.termux prefix, so the library search path has to be redirected explicitly. The
            // glibc directory is NOT added here: it contains a 'libc.so' linker script that the
            // bionic linker would try to load as an ELF file. glibc binaries are pointed at their
            // libraries with an RPATH instead.
            env["LD_LIBRARY_PATH"] = "$PREFIX/lib"
            env.remove("LD_PRELOAD")
        }

        fun start() {
            Thread({ run() }, "opencode-setup").start()
        }

        private fun run() {
            var success = false
            var errorMessage: String? = null
            var failure: Throwable? = null
            try {
                if (forceUpdate) {
                    setStage(OpenCodeInstallState.STAGE_PREPARE, "Updating OpenCode...")
                    doInstall()
                } else if (runtimeProblems().isNotEmpty()) {
                    setStage(OpenCodeInstallState.STAGE_PREPARE, "Preparing the OpenCode runtime...")
                    doInstall()
                } else {
                    setStage(OpenCodeInstallState.STAGE_PREPARE, "Checking the OpenCode runtime...")
                    doMaintenance()
                }
                success = true
            } catch (e: InstallException) {
                failure = e
                errorMessage = e.message
            } catch (e: Exception) {
                Log.e(LOG_TAG, "The OpenCode installation failed", e)
                failure = e
                errorMessage = "The OpenCode installation failed: $e"
            }

            if (success) {
                setStageDone()
                notifyFinished("OpenCode is ready", "Version ${getInstalledVersion()} is installed.")
            } else {
                handleFailure(errorMessage ?: "Unknown error", failure)
            }

            releaseWaiters()
        }

        /* -------------------------------------------------------------- install pipeline */

        private fun doInstall() {
            ensureRuntimeDirectories()

            setStage(OpenCodeInstallState.STAGE_APT_INSTALL, "Installing the Linux toolchain...")
            installPackages(CORE_PACKAGES, "Could not install the Linux toolchain")

            // The prefix may have been wiped above, recreate the writable directories and the
            // resolver configuration of the toolchain.
            ensureRuntimeDirectories()
            ensureResolvConf()

            // 'patchelf' is needed to patch the OpenCode binary, it links against libc++_shared.so,
            // an old libc++ makes it die with "cannot locate symbol __hash_memory". Verify it
            // before downloading ~100 MB.
            if (!patchelfWorks()) {
                setStage(OpenCodeInstallState.STAGE_APT_INSTALL, "Repairing the patchelf tool...")
                try {
                    installPackages(listOf("patchelf", "libc++"), "Could not reinstall patchelf")
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Could not reinstall patchelf", e)
                }
                if (!patchelfWorks()) {
                    throw InstallException(
                        "The 'patchelf' tool cannot run on this device: ${tail(lastOutput, 400)}",
                    )
                }
            }

            // The packages are built for the com.termux prefix and carry its absolute RUNPATH.
            // Repoint the tools the app itself spawns, so they also work without LD_LIBRARY_PATH
            // (eg. when the user runs them from a terminal).
            patchRpath(File("$PREFIX/bin/bash"), "$PREFIX/lib")
            patchRpath(File("$PREFIX/bin/patchelf"), "$PREFIX/lib")

            // The login shell of the terminal and the glibc runtime both contain the com.termux
            // prefix in their read-only data. Move it to the sandbox root, which has the same
            // length, so no offset of any ELF moves.
            ensurePathAliases()
            patchLoginShellPaths()

            val loader = findGlibcLoader()
                ?: throw InstallException(
                    "The glibc loader (ld-linux-*.so.*) was not found in $GLIBC_LIB_DIR." +
                        " Install the 'glibc-runner' package and try again.",
                )

            val arch = getOpenCodeArch()
                ?: throw InstallException(
                    "The CPU architecture \"${Build.SUPPORTED_ABIS[0]}\" has no official OpenCode build.",
                )

            val version = fetchLatestVersion()

            setStage(OpenCodeInstallState.STAGE_DOWNLOAD, "Downloading OpenCode $version...")
            val tarball = downloadTarball(arch, version)
            try {
                setStage(OpenCodeInstallState.STAGE_EXTRACT, "Extracting the OpenCode package...")
                val binary = extractBinary(tarball)
                val installed = installBinary(binary, version, loader)
                if (installed == null) {
                    // The new build does not run, keep the old one if there is one.
                    if (!File(LINK_PATH).exists()) {
                        throw InstallException("The downloaded OpenCode build does not run on this device.")
                    }
                    status("WARNING: the downloaded build does not run on this device, keeping the installed one.")
                } else {
                    linkTo(installed)
                    try {
                        writeTextFile(File(READY_MARKER_PATH), version)
                    } catch (e: IOException) {
                        throw InstallException("Could not write the installation markers: ${e.message}")
                    }
                }
                installEntrypoints()
            } finally {
                deleteQuietly(tarball)
            }

            // The old installer script must not stay around, it would fight with the app.
            deleteQuietly(File(LEGACY_SETUP_SCRIPT_PATH))
        }

        fun doMaintenance() {
            deleteQuietly(File(LEGACY_SETUP_SCRIPT_PATH))
            // Repair a prefix that was installed by an older build: dangling Termux symlinks,
            // dead Termux shebangs and the git ELF shell path. Then reinstall packages whose
            // marker entry exists but whose files are gone (an interrupted extraction).
            try {
                val repaired = OpenCodePackages.repair(File(PREFIX))
                if (repaired != null) Log.i(LOG_TAG, "Repaired the package environment: $repaired")
                val broken = OpenCodePackages.brokenPackages(File(PREFIX))
                // A prefix installed by an older build predates coreutils: install it, the
                // `#!/…/bin/env node` launchers of npm need bin/env.
                val needsCoreutils = !File("$PREFIX/bin/coreutils").exists()
                if (broken.isNotEmpty() || needsCoreutils) {
                    val repair = (CORE_PACKAGES + broken).distinct()
                    Log.i(LOG_TAG, "Reinstalling broken packages: $repair")
                    installPackages(repair, "Could not repair the Linux toolchain")
                    // A reinstalled bash/patchelf carries the com.termux RUNPATH again; the tools
                    // the app spawns directly must start without LD_LIBRARY_PATH.
                    patchRpath(File("$PREFIX/bin/bash"), "$PREFIX/lib")
                    patchRpath(File("$PREFIX/bin/patchelf"), "$PREFIX/lib")
                }
            } catch (e: Exception) {
                // Offline is not a reason to fail the maintenance of an otherwise ready runtime.
                Log.w(LOG_TAG, "Could not repair the package environment", e)
            }
            // Repair the parts that a reinstall of a package or an OpenCode update may have
            // replaced: the aliases, the relocated runtime, the login profile and the marker.
            ensurePathAliases()
            val loader = findGlibcLoader()
            // The whole glibc runtime is relocated, not only the loader: libc.so.6 otherwise looks
            // for resolv.conf in the unreadable prefix of the Termux app and every host name
            // lookup of the server fails with "getaddrinfo".
            if (loader != null) relocateGlibc(loader)
            refreshResolvConf(context)
            patchLoginShellPaths()
            repairNativeInterp()
            refreshNativeMarker()
            installEntrypoints()
        }

        private fun installPackages(packages: List<String>, errorPrefix: String) {
            try {
                OpenCodePackages.install(
                    File(PREFIX),
                    packages,
                    object : OpenCodePackages.Progress {
                        override fun onStatus(message: String) = status(message)
                        override fun onPercent(percent: Int, detail: String) = setProgress(percent, detail)
                    },
                )
            } catch (e: Exception) {
                Log.e(LOG_TAG, errorPrefix, e)
                throw InstallException("$errorPrefix: ${e.message}")
            }
        }

        /* -------------------------------------------------------------- download */

        private fun fetchLatestVersion(): String {
            // The npm platform package is authoritative: it is the archive that is downloaded, so
            // its version always matches the tarball. The update API sometimes announces a version
            // of a package that is not published for every platform yet.
            getOpenCodeArch()?.let { arch ->
                resolveNpmRelease(arch)?.let { return it.version }
            }

            extractVersion(httpGetString(VERSION_API_URL))?.let { return it }

            throw InstallException("Could not determine the OpenCode version (check the network).")
        }

        private fun extractVersion(json: String?): String? {
            if (json == null) return null
            val matcher = VERSION_PATTERN.matcher(json)
            return if (matcher.find()) matcher.group(1) else null
        }

        /**
         * Ask the npm registry for the newest platform build. The package was renamed when the
         * 2.x line was published, so both names are tried. The tarball URL is taken from the
         * metadata instead of being assembled by hand, that way a rename or a different file
         * layout cannot produce a 404.
         */
        private fun resolveNpmRelease(arch: String): NpmRelease? {
            for (prefix in PLATFORM_PACKAGE_PREFIXES) {
                val name = prefix + arch
                try {
                    val meta = httpGetString("$NPM_REGISTRY_URL/$name/latest") ?: continue
                    val json = JSONObject(meta)
                    val dist = json.optJSONObject("dist") ?: continue
                    val release = NpmRelease().apply {
                        this.name = name
                        version = json.optString("version", "")
                        tarball = dist.optString("tarball", "")
                        integrity = dist.optString("integrity", "")
                    }
                    if (release.version.isNotEmpty() && release.tarball.isNotEmpty()) return release
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Could not read the npm metadata of $name: ${e.message}")
                }
            }
            return null
        }

        private fun downloadTarball(arch: String, version: String): File {
            val tmpDir = File(TMP_DIR)
            if (!tmpDir.isDirectory && !tmpDir.mkdirs()) {
                throw InstallException("Could not create the temporary directory $TMP_DIR")
            }

            val release = resolveNpmRelease(arch)
            var primaryError: String? = null
            if (release != null) {
                val target = File(tmpDir, "cli-linux-$arch-${release.version}.tgz")
                setStage(
                    OpenCodeInstallState.STAGE_DOWNLOAD,
                    "Downloading OpenCode ${release.version}...",
                )
                try {
                    downloadTarballTo(release.tarball, target, release.integrity)
                    return target
                } catch (e: IOException) {
                    primaryError = e.message
                    Log.w(LOG_TAG, "Download of ${release.tarball} failed: ${e.message}")
                    // The fallback below would build exactly the same URL again, which means a
                    // second (and very likely equally broken) transfer. Only the other package
                    // name is worth trying. The partial file is kept, the next run resumes it.
                }
            }

            // Last resort: build the URLs from the version of the update API.
            val fileName = "cli-linux-$arch-$version.tgz"
            var lastError: IOException? = null
            for (prefix in PLATFORM_PACKAGE_PREFIXES) {
                // Never download the same file twice: the metadata already pointed at this
                // package and it failed (eg. wrong checksum or an interrupted transfer).
                if (release != null && release.name == prefix + arch) continue

                val url = "$NPM_REGISTRY_URL/$prefix$arch/-/$fileName"
                // One file per package, so a partial file of one mirror can never be resumed
                // with the content of another one.
                val target = File(tmpDir, prefix.replace(Regex("[^A-Za-z0-9_.-]"), "_") + fileName)
                try {
                    setStage(OpenCodeInstallState.STAGE_DOWNLOAD, "Downloading OpenCode $version...")
                    downloadTarballTo(url, target, null)
                    return target
                } catch (e: IOException) {
                    lastError = e
                    Log.w(LOG_TAG, "Download of $url failed: ${e.message}")
                    deleteQuietly(target)
                }
            }

            val reason = lastError?.message ?: primaryError ?: "unknown error"
            throw InstallException("Could not download the OpenCode package: $reason")
        }

        /**
         * Download the tarball of OpenCode. A file that was downloaded before is continued where
         * it stopped, so that the progress is kept when the app is closed in the middle of the
         * download.
         */
        private fun downloadTarballTo(url: String, target: File, expectedIntegrity: String?) {
            // An empty value means that the registry did not publish a checksum.
            val integrity = expectedIntegrity?.trim()?.takeIf { it.isNotEmpty() }
            // The file may have been downloaded completely before the app was closed.
            if (integrity != null && target.isFile && verifyIntegrity(target, integrity)) return

            try {
                downloadFile(url, target, true, ::reportDownloadProgress)
            } catch (e: IOException) {
                // The partial file may be useless, download it again from the beginning.
                Log.w(LOG_TAG, "Could not continue the download of $url: ${e.message}")
                deleteQuietly(target)
                downloadFile(url, target, false, ::reportDownloadProgress)
            }

            if (integrity != null && !verifyIntegrity(target, integrity)) {
                deleteQuietly(target)
                downloadFile(url, target, false, ::reportDownloadProgress)
                if (!verifyIntegrity(target, integrity)) {
                    throw IOException(
                        "The checksum of the downloaded file does not match the one of the npm registry.",
                    )
                }
            }
        }

        /**
         * Verify a downloaded file against the Subresource Integrity value of the npm registry.
         *
         * npm stores the digest as `sha512-<base64>` (sometimes several space separated values).
         * The algorithm prefix must be removed before the comparison, otherwise the digest never
         * matches, the file is thrown away and the whole archive is downloaded again.
         */
        private fun verifyIntegrity(file: File, integrity: String): Boolean {
            for (entry in integrity.trim().split(Regex("\\s+"))) {
                val dash = entry.indexOf('-')
                if (dash <= 0) continue
                val expectedBase64 = entry.substring(dash + 1)
                val javaName = when (entry.substring(0, dash).lowercase(Locale.US)) {
                    "sha512" -> "SHA-512"
                    "sha384" -> "SHA-384"
                    "sha256" -> "SHA-256"
                    "sha1" -> "SHA-1"
                    else -> continue
                }
                try {
                    val digest = MessageDigest.getInstance(javaName)
                    val buffer = ByteArray(64 * 1024)
                    FileInputStream(file).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    val actual = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
                    if (actual == expectedBase64) return true
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Could not verify the checksum of $file", e)
                    return false
                }
            }
            return false
        }

        /** Download a file, resume a partial one and report the progress. */
        private fun downloadFile(
            url: String,
            target: File,
            resume: Boolean,
            listener: DownloadListener?,
        ): Long {
            var alreadyHave = if (resume && target.isFile) target.length() else 0L

            val connection = openConnection(url, alreadyHave)
            try {
                connection.connect()
                val contentLength = connection.contentLength.toLong()
                val total: Long
                val append: Boolean

                if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    append = true
                    total = alreadyHave + if (contentLength > 0) contentLength else 0L
                } else {
                    // The server ignored the range request, the file is downloaded again.
                    append = false
                    alreadyHave = 0
                    total = contentLength
                }

                val startedAt = SystemClock.uptimeMillis()
                var lastReportAt = startedAt
                var lastPercent = -1
                var read = alreadyHave

                connection.inputStream.use { input ->
                    FileOutputStream(target, append).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            read += count

                            val percent = if (total > 0) (read * 100 / total).toInt() else 0
                            val now = SystemClock.uptimeMillis()
                            val step = percent >= lastPercent + 2 || percent == 100
                            if (percent != lastPercent && (step || now - lastReportAt >= 3000)) {
                                lastPercent = percent
                                lastReportAt = now
                                val elapsedMs = maxOf(1L, now - startedAt)
                                val rate = read / 1024.0 / 1024.0 / (elapsedMs / 1000.0)
                                val eta =
                                    if (rate > 0.01) ((total - read) / (rate * 1024 * 1024)).toLong() else 0L
                                listener?.onDownloadProgress(
                                    read,
                                    total,
                                    formatTransferDetail(read, total, rate, eta),
                                )
                            }
                        }
                        out.flush()
                    }
                }

                return read - alreadyHave
            } finally {
                connection.disconnect()
            }
        }

        /** Forward the progress of the download to the state of the installation. */
        private fun reportDownloadProgress(bytesDone: Long, bytesTotal: Long, detail: String) {
            setBytes(bytesDone, bytesTotal, detail)
        }

        /**
         * Open a connection and follow redirects by hand: HttpURLConnection drops the Range header
         * on a redirect, which would restart a resumed download from the beginning.
         */
        private fun openConnection(url: String, rangeStart: Long = 0): HttpURLConnection {
            var current = url
            for (hop in 0 until 6) {
                val connection = URL(current).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS.toInt()
                connection.readTimeout = HTTP_READ_TIMEOUT_MS.toInt()
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "*/*")
                if (rangeStart > 0) connection.setRequestProperty("Range", "bytes=$rangeStart-")
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 ||
                    code == 308
                ) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null) throw IOException("Missing Location header for $current")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code < 200 || code >= 300) {
                    connection.disconnect()
                    throw IOException("The server answered with HTTP $code for $current")
                }
                return connection
            }
            throw IOException("Too many redirects for $url")
        }

        private fun httpGetString(url: String): String? {
            val connection = try {
                openConnection(url)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The request to $url failed: ${e.message}")
                return null
            }
            return try {
                val sb = StringBuilder()
                connection.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        sb.append(line)
                        if (sb.length > 1024 * 1024) break
                    }
                }
                sb.toString()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The request to $url failed: ${e.message}")
                null
            } finally {
                connection.disconnect()
            }
        }

        /* -------------------------------------------------------------- extraction & patching */

        private fun extractBinary(tarball: File): File {
            val tmpDir = File(TMP_DIR)
            val target = File(tmpDir, "package/bin/opencode")

            status("Extracting the OpenCode package...")
            deleteQuietly(File(tmpDir, "package"))

            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                throw InstallException("Could not create the directory $parent")
            }

            // The package is a plain gzipped tar archive, so it is unpacked here. This avoids
            // depending on the Termux 'tar' package and its own dependencies at this early stage.
            var found = false
            try {
                BufferedInputStream(FileInputStream(tarball)).use { raw ->
                    GzipCompressorInputStream(raw).use { gzip ->
                        TarArchiveInputStream(gzip).use { tar ->
                            while (true) {
                                val entry = tar.nextEntry ?: break
                                var name = entry.name
                                if (name.startsWith("./")) name = name.substring(2)
                                if (!name.endsWith("bin/opencode")) continue
                                if (entry.isDirectory || entry.isSymbolicLink || entry.isLink) {
                                    throw InstallException(
                                        "The OpenCode package does not contain a regular binary.",
                                    )
                                }
                                FileOutputStream(target).use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val read = tar.read(buffer)
                                        if (read < 0) break
                                        out.write(buffer, 0, read)
                                    }
                                    out.flush()
                                }
                                found = true
                                break
                            }
                        }
                    }
                }
            } catch (e: InstallException) {
                deleteQuietly(target)
                throw e
            } catch (e: IOException) {
                deleteQuietly(target)
                throw InstallException("Extracting the tarball failed: ${e.message}")
            }

            if (!found || !target.isFile || target.length() == 0L) {
                deleteQuietly(target)
                throw InstallException("The downloaded package does not contain the expected binary.")
            }
            return target
        }

        /**
         * Patch, verify and install the binary.
         *
         * @return the installed binary, or null when the downloaded build does not run but an
         *     older version is already installed.
         */
        private fun installBinary(binary: File, version: String, loader: String): File? {
            val installDir = File(INSTALL_DIR)
            if (!installDir.isDirectory && !installDir.mkdirs()) {
                throw InstallException("Could not create the directory $INSTALL_DIR")
            }

            val target = File(installDir, "opencode-v$version")
            val staged = File(installDir, ".opencode-v$version.new")
            deleteQuietly(staged)

            // Move the file to the staging area of the same file system so that it can be replaced
            // atomically later on.
            if (!binary.renameTo(staged)) {
                if (!copyFile(binary, staged)) {
                    deleteQuietly(staged)
                    throw InstallException("Could not stage the OpenCode binary.")
                }
                deleteQuietly(binary)
            }

            setStage(OpenCodeInstallState.STAGE_PATCH, "Preparing the OpenCode binary...")
            // Patch PT_INTERP so that the binary starts directly. This is not cosmetic: OpenCode
            // spawns its own background server through /proc/self/exe, and with the explicit
            // loader that path is ld-linux, so the child would run "ld-linux serve" and die with
            // "serve: cannot open shared object file". When the patch cannot be applied the loader
            // fallback still works for the foreground TUI, so it is not fatal.
            ensurePathAliases()
            var nativeInterp = false
            if (relocateGlibc(loader)) {
                status("Pointing the binary at the glibc runtime...")
                val patch = run(
                    listOf(
                        "$PREFIX/bin/patchelf", "--set-interpreter", loader, staged.absolutePath,
                    ),
                    PATCH_TIMEOUT_MS,
                    false,
                )
                nativeInterp = patch.exitCode == 0
                if (!nativeInterp) {
                    Log.w(
                        LOG_TAG,
                        "Could not set the interpreter of the OpenCode binary: ${tail(patch.output, 200)}",
                    )
                }
            }

            try {
                Os.chmod(staged.absolutePath, MODE_755)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not make the OpenCode binary executable", e)
            }

            setStage(OpenCodeInstallState.STAGE_VERIFY, "Verifying the OpenCode binary...")
            var verify: CmdResult
            if (nativeInterp) {
                // Same environment as the direct executable: no bionic LD_LIBRARY_PATH/LD_PRELOAD.
                verify = runNative(listOf(staged.absolutePath, "--version"), VERIFY_TIMEOUT_MS)
                if (!looksLikeAVersion(verify)) {
                    // The interpreter patch alone is not enough on this build, keep the binary and
                    // let the service use the explicit loader instead. The terminal entry point is
                    // a direct symlink, so a build that cannot start directly only works through
                    // the app until the next successful repair.
                    status("WARNING: this build does not start directly, falling back to the glibc loader.")
                    verify = run(
                        listOf(loader, "--library-path", GLIBC_LIB_DIR, staged.absolutePath, "--version"),
                        VERIFY_TIMEOUT_MS,
                        false,
                    )
                    nativeInterp = false
                }
            } else {
                verify = run(
                    listOf(loader, "--library-path", GLIBC_LIB_DIR, staged.absolutePath, "--version"),
                    VERIFY_TIMEOUT_MS,
                    false,
                )
            }
            if (!looksLikeAVersion(verify)) {
                deleteQuietly(staged)
                if (target.isFile) {
                    status("WARNING: the downloaded build does not run, keeping the installed one.")
                    return null
                }
                throw InstallException(
                    "The downloaded OpenCode build does not run on this device: " +
                        tail(verify.output, 400),
                )
            }

            // Atomic replace of the installed binary.
            deleteQuietly(target)
            if (!staged.renameTo(target)) {
                if (!copyFile(staged, target)) {
                    deleteQuietly(staged)
                    throw InstallException("Could not install the OpenCode binary.")
                }
                deleteQuietly(staged)
            }

            try {
                Os.chmod(target.absolutePath, MODE_755)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not make the OpenCode binary executable", e)
            }

            writeNativeMarker(target, nativeInterp)

            // Every build is ~130 MB, keep only the one that is linked.
            pruneOldBuilds(target)

            return target
        }

        /**
         * Record whether the installed binary can be started directly. The service compares the
         * fingerprint before it skips the explicit loader.
         */
        private fun writeNativeMarker(binary: File, nativeInterp: Boolean) {
            val marker = File(NATIVE_INTERP_MARKER_PATH)
            if (!nativeInterp) {
                deleteQuietly(marker)
                return
            }
            try {
                writeTextFile(marker, binaryFingerprint(binary) + "\n")
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Could not write $marker", e)
            }
        }

        /**
         * Drop the marker when the binary behind the symlink changed (eg. the auto updater
         * replaced it), so the service uses the explicit loader again.
         */
        private fun refreshNativeMarker() {
            val marker = File(NATIVE_INTERP_MARKER_PATH)
            val stamp = readTrimmed(marker)
            if (stamp.isEmpty()) return
            val binary = resolveLink(File(LINK_PATH))
            if (!binary.isFile || stamp != binaryFingerprint(binary)) {
                status("The OpenCode binary changed, using the glibc loader again.")
                deleteQuietly(marker)
            }
        }

        /**
         * Patch the interpreter of an already installed binary (an installation from an older
         * version of the app). The binary is copied first; the original is only replaced after the
         * copy starts natively, so a failure can never break a working installation.
         */
        private fun repairNativeInterp() {
            val binary = resolveLink(File(LINK_PATH))
            if (!binary.isFile || isNativeBinary(binary)) return

            val attempt = "$NATIVE_REPAIR_VERSION ${binaryFingerprint(binary)}"
            val repairMarker = File(NATIVE_REPAIR_MARKER_PATH)
            if (attempt == readTrimmed(repairMarker)) return

            val loader = findGlibcLoader()
            if (loader == null || !relocateGlibc(loader) || !patchelfWorks()) return

            status("Preparing the installed OpenCode binary for a direct start...")
            val temp = File(binary.absolutePath + ".native-new")
            deleteQuietly(temp)
            if (!copyFile(binary, temp)) {
                Log.w(LOG_TAG, "Could not copy the OpenCode binary, keeping the glibc loader path.")
                deleteQuietly(temp)
                writeTextFileQuietly(repairMarker, attempt + "\n")
                return
            }
            try {
                Os.chmod(temp.absolutePath, MODE_755)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not make $temp executable", e)
            }

            val patch = run(
                listOf("$PREFIX/bin/patchelf", "--set-interpreter", loader, temp.absolutePath),
                PATCH_TIMEOUT_MS,
                false,
            )
            val verify = if (patch.exitCode == 0) {
                runNative(listOf(temp.absolutePath, "--version"), VERIFY_TIMEOUT_MS)
            } else {
                null
            }

            if (verify == null || !looksLikeAVersion(verify) || !temp.renameTo(binary)) {
                Log.w(LOG_TAG, "Could not prepare the installed binary, keeping the glibc loader path.")
                deleteQuietly(temp)
                writeTextFileQuietly(repairMarker, attempt + "\n")
                return
            }

            writeNativeMarker(binary, true)
            deleteQuietly(repairMarker)
        }

        /** Delete the binaries of earlier versions, they waste a lot of app storage. */
        private fun pruneOldBuilds(keep: File) {
            val files = File(INSTALL_DIR).listFiles() ?: return
            for (file in files) {
                val name = file.name
                if (!name.startsWith("opencode-v") && !name.startsWith(".opencode-v")) continue
                if (file.absolutePath == keep.absolutePath) continue
                deleteQuietly(file)
            }
        }

        /** Point the RUNPATH of a Termux ELF at the libraries of the app-private prefix. */
        private fun patchRpath(file: File, rpath: String) {
            if (!file.isFile) return
            val result = run(
                listOf("$PREFIX/bin/patchelf", "--set-rpath", rpath, file.absolutePath),
                PATCH_TIMEOUT_MS,
                false,
            )
            if (result.exitCode != 0) {
                Log.w(LOG_TAG, "Could not set the RUNPATH of $file: ${tail(result.output, 200)}")
            }
        }

        private fun patchelfWorks(): Boolean = run(
            listOf("$PREFIX/bin/patchelf", "--version"),
            VERIFY_TIMEOUT_MS,
            false,
        ).exitCode == 0

        /**
         * Move the compiled-in login paths of bash to the sandbox root. Without this a login shell
         * (OpenCode starts the terminal as `bash -l`) tries to read
         * `/data/data/com.termux/files/usr/etc/profile`, which belongs to another app and fails
         * with "Permission denied".
         */
        private fun patchLoginShellPaths() {
            val bash = File("$PREFIX/bin/bash")
            if (!bash.isFile) return
            val hits = patchFileStrings(
                bash,
                "$PREFIX_TERMUX/etc/profile", "$ETC_ALIAS_DIR/profile",
                "$PREFIX_TERMUX/etc/bash.bashrc", "$ETC_ALIAS_DIR/bash.bashrc",
                "$PREFIX_TERMUX/etc/hosts", "$ETC_ALIAS_DIR/hosts",
            )
            if (hits > 0) status("Moved the login profile of bash to the app prefix.")

            // The profile and the bashrc are plain text files from the bash package, they are not
            // touched by the ELF rewrite and still point at the real Termux app.
            rewriteTextPaths(File("$PREFIX/etc/bash.bashrc"))
            writePackageEnvironment()
        }

        /** Rewrite the com.termux paths of a text file to this app's prefix and home. */
        private fun rewriteTextPaths(file: File) {
            if (!file.isFile) return
            try {
                val text = readTextFile(file) ?: return
                val rewritten = text.replace(PREFIX_TERMUX, PREFIX).replace(HOME_TERMUX, HOME)
                if (rewritten != text) writeTextFile(file, rewritten)
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Could not rewrite the paths of $file", e)
            }
        }

        /** Run a byte-patched binary without the bionic library paths, exactly like the terminal. */
        private fun runNative(cmd: List<String>, timeoutMs: Long): CmdResult {
            val libraryPath = env.remove("LD_LIBRARY_PATH")
            val preload = env.remove("LD_PRELOAD")
            try {
                return run(cmd, timeoutMs, false)
            } finally {
                if (libraryPath != null) env["LD_LIBRARY_PATH"] = libraryPath
                if (preload != null) env["LD_PRELOAD"] = preload
            }
        }

        private fun looksLikeAVersion(result: CmdResult): Boolean =
            result.exitCode == 0 && VERSION_OUTPUT_PATTERN.matcher(result.output).matches()

        private fun linkTo(target: File) {
            try {
                val linkDir = File(LINK_DIR)
                if (!linkDir.isDirectory && !linkDir.mkdirs()) {
                    throw IOException("Could not create the directory $LINK_DIR")
                }
                deleteQuietly(File(LINK_PATH))
                Os.symlink(target.absolutePath, LINK_PATH)
            } catch (e: Exception) {
                throw InstallException("Could not create the opencode symlink: ${e.message}")
            }
        }

        /* -------------------------------------------------------------- entry points */

        /**
         * Install the terminal entry points: `opencode` is a symlink to the managed binary and
         * `pkg`/`oc-install` are symlinks to the Kotlin/Native [BRIDGE_PATH]. Files left behind by
         * the old shell launchers are replaced in place, so an app update migrates a device
         * without a reinstall.
         */
        private fun installEntrypoints() {
            val bridge = installBridge()
            // The terminal entry point is a direct symlink, so it only works for a binary whose
            // interpreter is the glibc loader. A build that failed the patch is still usable by the
            // app through the explicit loader; keep the working launcher of an older build (if
            // any) instead of replacing it with a symlink that cannot start. When nothing exists
            // yet the symlink is created anyway, so runtimeProblems() does not trigger a full
            // reinstall on every start.
            if (isNativeBinary(resolveLink(File(LINK_PATH))) || !File(OPENCODE_BIN_PATH).isFile) {
                replaceSymlink(File(OPENCODE_BIN_PATH), LINK_PATH)
            }

            if (bridge) {
                replaceSymlink(File(PKG_PATH), BRIDGE_PATH)
                replaceSymlink(File(OC_INSTALL_PATH), BRIDGE_PATH)
            } else {
                // Keep a regular file of an older build, it still works until the next successful
                // extraction. The same holds for a symlink whose bridge is still executable; only
                // a link that would already fail is removed.
                deleteIfBrokenSymlink(File(PKG_PATH))
                deleteIfBrokenSymlink(File(OC_INSTALL_PATH))
            }
        }

        /** Delete [file] when it is a symlink that no longer points at an executable. */
        private fun deleteIfBrokenSymlink(file: File) {
            try {
                if (!OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)) return
                if (!isExecutable(file)) deleteQuietly(file)
            } catch (e: Exception) {
                // The file does not exist.
            }
        }

        /**
         * Extract the bridge of the current ABI from the APK. The marker contains the app version
         * and the stamp of the APK, so a new APK always replaces a bridge from an older build,
         * even when the version code did not change (debug builds).
         */
        private fun installBridge(): Boolean {
            val abi = when (getOpenCodeArch()) {
                "arm64" -> "arm64-v8a"
                "x64" -> "x86_64"
                else -> return false
            }
            val target = File(BRIDGE_PATH)
            val marker = File(BRIDGE_MARKER_PATH)
            val stamp = "$BRIDGE_VERSION ${BuildConfig.VERSION_CODE} $abi ${apkStamp()}"
            if (readTrimmed(marker) == stamp && isExecutable(target)) return true

            val assets = context?.assets ?: return false
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                Log.w(LOG_TAG, "Could not create the directory $parent")
                return false
            }

            var temp: File? = null
            try {
                val created = File.createTempFile(target.name, ".tmp", parent)
                temp = created
                assets.open("cli/$abi/oc-bridge").use { input ->
                    created.outputStream().use { output -> input.copyTo(output) }
                }
                Os.chmod(created.absolutePath, MODE_700)

                if (!created.renameTo(target)) {
                    deleteQuietly(target)
                    if (!created.renameTo(target)) {
                        throw IOException("Could not replace $target")
                    }
                }
                temp = null
                writeTextFileQuietly(marker, stamp + "\n")
                return true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not install the CLI bridge for $abi", e)
                // Keep a bridge of an older build when the new one cannot be extracted; the next
                // maintenance pass retries and the entry points keep working until then.
                return isExecutable(target)
            } finally {
                temp?.let { deleteQuietly(it) }
            }
        }

        /** Stamp of the installed APK, it changes on every update even with the same version code. */
        private fun apkStamp(): String {
            val apk = try {
                context?.applicationInfo?.sourceDir?.let { File(it) }
            } catch (e: Exception) {
                null
            }
            return if (apk != null && apk.isFile) "${apk.length()}-${apk.lastModified()}" else ""
        }

        /**
         * Replace [link] with a symlink to [target]. `rename` is atomic on the app file system, a
         * running `pkg` therefore keeps its old inode and the next invocation sees the new link.
         */
        private fun replaceSymlink(link: File, target: String) {
            val parent = link.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                Log.w(LOG_TAG, "Could not create the directory $parent")
                return
            }

            var temp: File? = null
            try {
                val created = File.createTempFile(".${link.name}", ".link", parent)
                temp = created
                // createTempFile creates a regular file; symlink() refuses to overwrite it.
                deleteQuietly(created)
                Os.symlink(target, created.absolutePath)

                if (!created.renameTo(link)) {
                    deleteQuietly(link)
                    if (!created.renameTo(link)) {
                        throw IOException("Could not replace $link")
                    }
                }
                temp = null
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not create the symlink $link -> $target", e)
            } finally {
                temp?.let { deleteQuietly(it) }
            }
        }

        /* -------------------------------------------------------------- environment */

        /** Create the writable directories the runtime expects, they are not part of the packages. */
        private fun ensureRuntimeDirectories() {
            val dirs = listOf(
                File(HOME),
                File(PREFIX, "tmp"),
                File(INSTALL_DIR),
                File(HOME, ".opencode"),
                File(HOME, ".cache"),
                File(HOME, ".config"),
                File(HOME, ".local/share"),
                File(HOME, ".local/state"),
            )
            for (dir in dirs) {
                if (!dir.isDirectory && !dir.mkdirs()) {
                    Log.w(LOG_TAG, "Could not create the directory $dir")
                }
            }
            try {
                Os.chmod(File(PREFIX, "tmp").absolutePath, MODE_700)
            } catch (e: Exception) {
                // Not fatal, the directory is already private to the app.
            }

            // The environment exports this password, so the terminal and the app always use the same
            // credentials for the OpenCode server.
            try {
                val passwordFile = File(SERVER_PASSWORD_PATH)
                writeTextFile(passwordFile, getServerPassword(context) + "\n")
                Os.chmod(passwordFile.absolutePath, MODE_600)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not write the server password file", e)
            }
        }

        private fun ensureResolvConf() {
            refreshResolvConf(context)
        }

        /* -------------------------------------------------------------- process handling */

        private fun run(
            cmd: List<String>,
            timeoutMs: Long,
            stream: Boolean,
            listener: LineListener? = null,
        ): CmdResult {
            val output = StringBuilder()
            try {
                val builder = ProcessBuilder(cmd)
                val processEnv = builder.environment()
                processEnv.clear()
                processEnv.putAll(env)
                builder.directory(File(HOME))
                builder.redirectErrorStream(true)

                val process = builder.start()

                val reader = Thread({
                    try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { input ->
                            while (true) {
                                val line = input.readLine() ?: break
                                synchronized(output) {
                                    if (output.length < MAX_OUTPUT_CHARS) {
                                        output.append(line).append('\n')
                                    }
                                }
                                if (listener != null) {
                                    try {
                                        listener.onLine(line)
                                    } catch (e: Exception) {
                                        // The listener must never break the installation.
                                    }
                                }
                                if (stream) Log.i(LOG_TAG, line)
                            }
                        }
                    } catch (e: Exception) {
                        // The process was killed, ignore.
                    }
                }, "opencode-cmd-reader")
                reader.isDaemon = true
                reader.start()

                val deadline = SystemClock.uptimeMillis() + timeoutMs
                var exitCode: Int? = null
                while (exitCode == null) {
                    try {
                        exitCode = process.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        if (SystemClock.uptimeMillis() > deadline) {
                            process.destroy()
                            try {
                                process.waitFor()
                            } catch (interrupted: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            return CmdResult(-1, synchronized(output) { output.toString() }, "timeout")
                        }
                        try {
                            Thread.sleep(PROCESS_POLL_INTERVAL_MS)
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return CmdResult(-1, synchronized(output) { output.toString() }, "interrupted")
                        }
                    }
                }
                val text = synchronized(output) { output.toString() }
                lastOutput = text
                return CmdResult(exitCode ?: -1, text, null)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to run $cmd", e)
                return CmdResult(-1, output.toString(), e.message)
            }
        }

        /* -------------------------------------------------------------- reporting */

        private fun status(message: String) {
            Log.i(LOG_TAG, message)
            if (logOnly) return

            synchronized(sLock) {
                sState.message = message
                sState.updatedAt = SystemClock.uptimeMillis()
                sState.addStep(message)
            }

            publish()
        }

        /** Set the stage of the installation and the message that describes it. */
        private fun setStage(stage: String, message: String) {
            if (logOnly) return

            Log.i(LOG_TAG, message)

            synchronized(sLock) {
                sState.stage = stage
                sState.stagePercent = 0
                sState.overallPercent = 0
                sState.message = message
                sState.detail = ""
                sState.error = ""
                sState.bytesDone = 0
                sState.bytesTotal = 0
                sState.running = true
                sState.done = false
                sState.failed = false
                sState.updatedAt = SystemClock.uptimeMillis()
                sState.recomputeOverallPercent()
                sState.addStep(message)
            }

            publish()
        }

        /** Set the percentage of the current stage. */
        private fun setProgress(stagePercent: Int, detail: String) {
            if (logOnly) return

            synchronized(sLock) {
                sState.stagePercent = stagePercent.coerceIn(0, 100)
                if (detail.isNotEmpty()) sState.detail = detail
                sState.updatedAt = SystemClock.uptimeMillis()
                sState.recomputeOverallPercent()
            }

            publish()
        }

        /** Set the number of bytes that were downloaded and the total size. */
        private fun setBytes(bytesDone: Long, bytesTotal: Long, detail: String) {
            if (logOnly) return

            synchronized(sLock) {
                sState.bytesDone = bytesDone
                sState.bytesTotal = bytesTotal
                if (bytesTotal > 0) {
                    sState.stagePercent = (bytesDone * 100 / bytesTotal).coerceIn(0, 100).toInt()
                }
                if (detail.isNotEmpty()) sState.detail = detail
                sState.updatedAt = SystemClock.uptimeMillis()
                sState.recomputeOverallPercent()
            }

            publish()
        }

        /** The installation finished successfully. */
        private fun setStageDone() {
            if (logOnly) return

            synchronized(sLock) {
                sState.stage = OpenCodeInstallState.STAGE_DONE
                sState.stagePercent = 100
                sState.overallPercent = 100
                sState.message = "OpenCode is ready."
                sState.detail = "Version ${getInstalledVersion()} is installed."
                sState.running = false
                sState.done = true
                sState.failed = false
                sState.updatedAt = SystemClock.uptimeMillis()
                sState.addStep(sState.message)
            }

            publish()
        }

        private fun notificationManager(): NotificationManager? {
            val ctx = context ?: return null
            return ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }

        private fun ensureNotificationChannel(manager: NotificationManager) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }

        private fun showProgressNotification(message: String) {
            if (logOnly) return
            val ctx = context ?: return
            val manager = notificationManager() ?: return
            ensureNotificationChannel(manager)

            val contentIntent = PendingIntent.getActivity(
                ctx,
                0,
                Intent(ctx, OpenCodeWebActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setOngoing(true)
                .setContentTitle("OpenCode")
                .setContentText(message)
                .setContentIntent(contentIntent)
                .setProgress(100, synchronized(sLock) { sState.overallProgress() }, false)
                .build()
            synchronized(sLock) { manager.notify(NOTIFICATION_ID, notification) }
        }

        private fun notifyFinished(title: String, text: String) {
            val ctx = context ?: return
            val manager = notificationManager() ?: return
            ensureNotificationChannel(manager)

            val contentIntent = PendingIntent.getActivity(
                ctx,
                0,
                Intent(ctx, OpenCodeWebActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun publish() {
            publishState()

            val message = synchronized(sLock) { sState.message }
            showProgressNotification(message)
        }

        private fun handleFailure(message: String?, failure: Throwable?) {
            Log.e(LOG_TAG, "The OpenCode installation failed: $message", failure)

            notifyFinished("OpenCode: installation failed", tail(message ?: "", MAX_DETAIL_CHARS))

            if (logOnly) return

            synchronized(sLock) {
                sState.stage = OpenCodeInstallState.STAGE_FAILED
                sState.error = message ?: "Unknown error"
                sState.running = false
                sState.done = false
                sState.failed = true
                sState.updatedAt = SystemClock.uptimeMillis()
                sState.addStep("FAILED: ${sState.error}")
            }

            publish()
        }

        /* -------------------------------------------------------------- lifecycle */

        private fun releaseWaiters() {
            val waiters: List<() -> Unit>
            synchronized(sLock) {
                waiters = ArrayList(sInstallWaiters)
                sInstallWaiters.clear()
                sInstaller = null
            }
            for (waiter in waiters) runOnUiThread(context, waiter)
        }
    }

    /* ======================================================================================
     * Listeners and small value holders
     * ====================================================================================== */

    /** Called for every line of the output of a process. */
    private fun interface LineListener {
        fun onLine(line: String)
    }

    /** Called while a file is downloaded. */
    private fun interface DownloadListener {
        fun onDownloadProgress(bytesDone: Long, bytesTotal: Long, detail: String)
    }

    /** The result of a child process. */
    private class CmdResult(val exitCode: Int, output: String?, val error: String?) {
        val output: String = output ?: ""
    }

    /** One published platform build of OpenCode, as described by the npm registry. */
    private class NpmRelease {
        var name: String = ""
        var version: String = ""
        var tarball: String = ""
        var integrity: String = ""
    }

    /* ======================================================================================
     * Helpers
     * ====================================================================================== */

    /** Permission bits, Kotlin has no octal literals. */
    private const val MODE_600 = 384 // 0600
    private const val MODE_700 = 448 // 0700
    private const val MODE_755 = 493 // 0755

    /** @return the npm architecture of the OpenCode build for this device, or null. */
    fun getOpenCodeArch(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.US) ?: return null
        return when {
            abi.startsWith("arm64") -> "arm64"
            abi == "x86_64" || abi == "amd64" -> "x64"
            else -> null
        }
    }

    /** @return the glibc loader of the toolchain, or null if the glibc runtime is not installed. */
    fun findGlibcLoader(): String? {
        val files = File(GLIBC_LIB_DIR).listFiles() ?: return null
        for (file in files) {
            val name = file.name
            if (name.startsWith("ld-linux") && name.contains(".so.") && file.canExecute()) {
                return file.absolutePath
            }
        }
        return null
    }

    /**
     * @return the fingerprint of an installed binary, it matches the `stat -L -c '%s %Y'` output
     *     that the service and the native marker use.
     */
    fun binaryFingerprint(binary: File?): String {
        if (binary == null || !binary.isFile) return ""
        // lastModified() is in milliseconds, %Y of stat prints seconds.
        return "${binary.length()} ${binary.lastModified() / 1000L}"
    }

    /**
     * @return true when the binary itself is patched to be started directly, ie. its ELF
     *     interpreter is the glibc loader. Only then the process that OpenCode spawns for the
     *     background server sees the right `/proc/self/exe`.
     */
    fun isNativeBinary(binary: File?): Boolean {
        if (binary == null || !binary.isFile) return false
        val stamp = readTrimmed(File(NATIVE_INTERP_MARKER_PATH))
        return stamp.isNotEmpty() && stamp == binaryFingerprint(binary)
    }

    /**
     * Create the same-length aliases the relocated binaries look for. The Termux packages contain
     * `/data/data/com.termux/files/usr/...` in their rodata; the app rewrites that prefix to
     * [DATA_DIR] (both are 31 characters), so the aliases must exist at the sandbox root.
     */
    private fun ensurePathAliases() {
        linkAlias(File(GLIBC_ALIAS_DIR), File(PREFIX, "glibc"))
        linkAlias(File(ETC_ALIAS_DIR), File(PREFIX, "etc"))
    }

    private fun linkAlias(link: File, target: File) {
        if (!target.exists()) return
        try {
            Os.lstat(link.absolutePath)
            return // Already there, do not touch what the user may have made.
        } catch (e: Exception) {
            // Does not exist yet.
        }
        try {
            Os.symlink(target.absolutePath, link.absolutePath)
            Log.i(LOG_TAG, "Created the path alias $link -> $target")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not create the path alias $link", e)
        }
    }

    /**
     * Rewrite the compiled-in Termux prefix of the glibc loader to the sandbox root. The bytes are
     * replaced one to one, so the size and every offset of the ELF stay identical. Without this the
     * loader would look for libc in `/data/data/com.termux/...`, a directory this app cannot read,
     * and a binary with a patched PT_INTERP would not start.
     *
     * @return true when the loader can be used for a direct (native) start.
     */
    private fun relocateLoader(loaderPath: String?): Boolean {
        if (loaderPath == null) return false
        val oldPrefix = PREFIX_TERMUX.toByteArray(StandardCharsets.US_ASCII)
        val newPrefix = DATA_DIR.toByteArray(StandardCharsets.US_ASCII)
        if (oldPrefix.size != newPrefix.size) {
            Log.w(LOG_TAG, "Cannot relocate the glibc loader: ${oldPrefix.size} != ${newPrefix.size}")
            return false
        }

        val loader = File(loaderPath)
        if (!loader.isFile) return false
        return try {
            val data = readAllBytes(loader)
            val hits = replaceBytes(data, oldPrefix, newPrefix)
            if (hits == 0) return true // Already relocated (or nothing to rewrite).

            val temp = File(loader.absolutePath + ".reloc")
            writeAllBytes(temp, data)
            try {
                Os.chmod(temp.absolutePath, MODE_755)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not make the relocated loader executable", e)
            }
            if (!temp.renameTo(loader)) {
                writeAllBytes(loader, data)
                try {
                    Os.chmod(loader.absolutePath, MODE_755)
                } catch (e: Exception) {
                    // Not fatal.
                }
                deleteQuietly(temp)
            }
            Log.i(LOG_TAG, "Relocated $hits path(s) of the glibc loader to $DATA_DIR")
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not relocate the glibc loader $loaderPath", e)
            false
        }
    }

    /** Incremented whenever the shared objects of the glibc runtime are rewritten. */
    @Volatile
    private var sGlibcPatchGeneration = 0

    /**
     * Make sure that the glibc runtime of the prefix is relocated to this sandbox. Called
     * synchronously by the service before the server is spawned, so a server can never start with
     * a libc that looks for its resolver configuration in the unreadable Termux prefix.
     *
     * @return true when this call rewrote the runtime, ie. a server that is still running uses the
     *     old copy and has to be replaced
     */
    fun ensureGlibcRelocated(): Boolean {
        ensurePathAliases()
        val loader = findGlibcLoader() ?: return false
        val generation = sGlibcPatchGeneration
        relocateGlibc(loader)
        return sGlibcPatchGeneration != generation
    }

    /**
     * Rewrite the compiled-in Termux prefix of the glibc runtime itself (libc.so.6 and the other
     * shared objects), not only of the loader.
     *
     * Relocating the loader alone is enough to start a binary, but libc.so.6 keeps the paths of the
     * Termux prefix compiled in: `/data/data/com.termux/files/usr/glibc/etc/resolv.conf`,
     * `.../glibc/etc/hosts`, `.../glibc/lib/gconv/...`. Those files belong to the Termux app and
     * cannot be read from this sandbox, so the resolver would start without a single nameserver:
     * the server runs, the web UI opens, but every request for a model or the model list dies with
     * `getaddrinfo`. The same one-to-one replacement as for the loader points them at the
     * `<dataDir>/glibc` and `<dataDir>/etc` aliases instead.
     *
     * @return true when the loader was relocated too, ie. a direct start can work.
     */
    private fun relocateGlibc(loaderPath: String?): Boolean {
        val loaderOk = loaderPath != null && relocateLoader(loaderPath)

        val libDir = File(GLIBC_LIB_DIR)
        val libc = File(libDir, "libc.so.6")
        if (!libc.isFile) return loaderOk

        // A package update replaces libc.so.6 and invalidates the marker, everything else is a
        // no-op then (files without the prefix are not rewritten).
        val stamp = "$DATA_DIR|${libc.length()}|${libc.lastModified()}"
        val marker = File(GLIBC_RELOCATED_PATH)
        if (stamp == readTrimmed(marker)) return loaderOk

        val files = libDir.listFiles()
        var patchedFiles = 0
        var patchedPaths = 0
        if (files != null) {
            for (file in files) {
                // Only runtime objects are mapped into the process, and only those can carry paths
                // that are read while OpenCode runs. Static archives and the huge development
                // objects are skipped, they are never loaded.
                if (!file.isFile || file.length() > MAX_RELOCATED_FILE_SIZE) continue
                if (!file.name.contains(".so") && !file.name.startsWith("ld-")) continue
                try {
                    // Do not replace a symlink such as libc.so -> libc.so.6 with a copy of its
                    // target, the linker scripts and the aliases of the packages must survive.
                    if (file.canonicalPath != file.absolutePath) continue
                } catch (e: IOException) {
                    continue
                }
                val hits = patchFileStrings(file, PREFIX_TERMUX, DATA_DIR)
                if (hits > 0) {
                    patchedFiles++
                    patchedPaths += hits
                }
            }
        }
        Log.i(
            LOG_TAG,
            "Relocated $patchedPaths path(s) of $patchedFiles glibc runtime file(s) to $DATA_DIR",
        )
        if (patchedFiles > 0) sGlibcPatchGeneration++
        writeTextFileQuietly(marker, stamp + "\n")
        return loaderOk
    }

    /**
     * Replace same-length strings inside a binary in place. Used for the login shell paths of bash,
     * which are compiled against the com.termux prefix and are unreadable from this app.
     */
    private fun patchFileStrings(file: File?, vararg replacements: String): Int {
        if (file == null || !file.isFile) return 0
        return try {
            val data = readAllBytes(file)
            var total = 0
            var i = 0
            while (i + 1 < replacements.size) {
                val oldBytes = replacements[i].toByteArray(StandardCharsets.US_ASCII)
                val newBytes = replacements[i + 1].toByteArray(StandardCharsets.US_ASCII)
                if (oldBytes.size != newBytes.size) {
                    Log.w(
                        LOG_TAG,
                        "Cannot rewrite ${replacements[i]}: the replacement differs in length",
                    )
                } else {
                    total += replaceBytes(data, oldBytes, newBytes)
                }
                i += 2
            }
            if (total == 0) return 0

            val temp = File(file.absolutePath + ".reloc")
            writeAllBytes(temp, data)
            try {
                Os.chmod(temp.absolutePath, MODE_755)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not make $temp executable", e)
            }
            if (!temp.renameTo(file)) {
                writeAllBytes(file, data)
                try {
                    Os.chmod(file.absolutePath, MODE_755)
                } catch (e: Exception) {
                    // Not fatal.
                }
                deleteQuietly(temp)
            }
            Log.i(LOG_TAG, "Patched $total path(s) of $file")
            total
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not patch the strings of $file", e)
            0
        }
    }

    /** @return the number of replaced occurrences. */
    private fun replaceBytes(data: ByteArray, oldBytes: ByteArray, newBytes: ByteArray): Int {
        if (oldBytes.isEmpty() || oldBytes.size != newBytes.size) return 0
        var count = 0
        var i = 0
        while (i <= data.size - oldBytes.size) {
            var match = true
            for (j in oldBytes.indices) {
                if (data[i + j] != oldBytes[j]) {
                    match = false
                    break
                }
            }
            if (!match) {
                i++
                continue
            }
            System.arraycopy(newBytes, 0, data, i, newBytes.size)
            count++
            i += oldBytes.size
        }
        return count
    }

    private fun readAllBytes(file: File): ByteArray {
        val length = file.length()
        if (length <= 0 || length > Int.MAX_VALUE) {
            throw IOException("Unexpected size $length of $file")
        }
        val data = ByteArray(length.toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < data.size) {
                val read = input.read(data, offset, data.size - offset)
                if (read < 0) break
                offset += read
            }
            if (offset != data.size) throw IOException("Could not read all of $file")
        }
        return data
    }

    private fun writeAllBytes(file: File, data: ByteArray) {
        FileOutputStream(file).use { it.write(data) }
    }

    /** @return the file the opencode symlink points to. */
    fun resolveLink(link: File): File {
        return try {
            val target = Os.readlink(link.absolutePath)
            val file = File(target)
            if (file.isAbsolute) file else File(link.parentFile, target)
        } catch (e: Exception) {
            link
        }
    }

    /** @return true when the file exists and can be executed by the app. */
    fun isExecutable(file: File?): Boolean = file != null && file.exists() && file.canExecute()

    private fun runOnUiThread(context: Context?, runnable: (() -> Unit)?) {
        if (runnable == null) return
        if (context == null) {
            runnable()
            return
        }
        sMainHandler.post { runnable() }
    }

    /** Save the state on the disk and notify the listeners about it. */
    private fun publishState() {
        val snapshot: OpenCodeInstallState
        synchronized(sLock) {
            sState.updatedAt = SystemClock.uptimeMillis()
            snapshot = copyOf(sState)
        }

        OpenCodeInstallState.save(snapshot)

        for (listener in sListeners) {
            sMainHandler.post {
                try {
                    listener.onOpenCodeInstallStateChanged(snapshot)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "An install listener failed", e)
                }
            }
        }
    }

    /** @return a copy of the given state, the copy is safe to hand out. */
    private fun copyOf(state: OpenCodeInstallState): OpenCodeInstallState =
        OpenCodeInstallState().apply {
            stage = state.stage
            stagePercent = state.stagePercent
            overallPercent = state.overallPercent
            message = state.message
            detail = state.detail
            error = state.error
            bytesDone = state.bytesDone
            bytesTotal = state.bytesTotal
            running = state.running
            done = state.done
            failed = state.failed
            updatedAt = state.updatedAt
            steps.addAll(state.steps)
        }

    private fun writeTextFile(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create the directory $parent")
        }
        FileOutputStream(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    private fun writeTextFileQuietly(file: File, content: String) {
        try {
            writeTextFile(file, content)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Could not write $file", e)
        }
    }

    /** @return the content of the file, or null when it does not exist. */
    private fun readTextFile(file: File): String? {
        if (!file.isFile) return null
        val sb = StringBuilder()
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }

    private fun readTextFileQuietly(file: File): String = try {
        readTextFile(file) ?: ""
    } catch (e: IOException) {
        ""
    }

    private fun readTrimmed(file: File): String = readTextFileQuietly(file).trim()

    private fun copyFile(source: File, target: File): Boolean {
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            true
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Could not copy $source to $target", e)
            false
        }
    }

    private fun deleteQuietly(file: File?) {
        if (file == null) return
        try {
            // File.exists() follows a symlink, so a dangling link looks absent. lstat() tells the
            // difference; a link is removed itself, its target is never touched.
            val isLink = try {
                OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
            } catch (e: Exception) {
                false
            }
            if (!isLink) {
                if (!file.exists()) return
                if (file.isDirectory) {
                    file.listFiles()?.forEach { deleteQuietly(it) }
                }
            }
            if (!file.delete()) Log.w(LOG_TAG, "Could not delete $file")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not delete $file", e)
        }
    }

    /** The detail line of a download, eg. "OpenCode: 12.3 MB / 98.7 MB - 1.4 MB/s - 1m20s left". */
    private fun formatTransferDetail(
        done: Long,
        total: Long,
        rate: Double,
        etaSeconds: Long,
    ): String = buildString {
        append("OpenCode: ").append(formatSize(done))
        if (total > 0) append(" / ").append(formatSize(total))
        if (rate > 0.01) append(" - ").append(String.format(Locale.US, "%.2f MB/s", rate))
        if (etaSeconds > 0) append(" - ").append(formatEta(etaSeconds)).append(" left")
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return "?"
        val minutes = seconds / 60
        val rest = seconds % 60
        return if (minutes <= 0) "${rest}s" else "${minutes}m${rest}s"
    }

    private fun formatSize(bytes: Long): String =
        if (bytes <= 0) "0 B" else "${bytes / (1024.0 * 1024.0)} MB"

    /** @return the last lines of a text, used for the notifications. */
    private fun tail(text: String?, count: Int): String {
        if (text == null) return ""
        val split = text.split("\n")
        val from = maxOf(0, split.size - count)
        val sb = StringBuilder()
        for (i in from until split.size) sb.append(split[i]).append('\n')
        return sb.toString()
    }

    /** Thrown when the installation cannot continue. */
    private class InstallException(message: String) : Exception(message)
}

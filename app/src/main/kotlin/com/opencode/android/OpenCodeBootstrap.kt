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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
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
 * bionic. The binary runs through the glibc loader with `liboc-pathmap.so` preloaded, which
 * translates the missing FHS paths and makes `/proc/self/exe` resolve to the real binary, the way
 * a normal Linux program does:
 *
 * ```
 * download the official npm tarball @opencode/cli-linux-<arch>
 *   -> unpack package/bin/opencode in Kotlin (no tar binary needed)
 *   -> relocate the glibc runtime and make the launcher run it through ld-linux + liboc-pathmap
 *   -> start the binary through the loader: /proc/self/exe is the binary, so OpenCode can spawn
 *      its own background `serve` process
 * ```
 *
 * Everything is done from this single object so that the installation keeps its progress,
 * survives the app being killed mid download and is able to repair itself afterwards. No root,
 * no proot and no chroot is needed.
 */
object OpenCodeBootstrap {

    private const val LOG_TAG = "OpenCodeBootstrap"

    /* ------------------------------------------------------------------ public API */

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

    /** Fixed bionic launcher that runs the installed OpenCode build through the glibc loader. */
    const val LAUNCHER_PATH = "$PREFIX/libexec/oc-launcher"

    /** Records which app build extracted the current OpenCode launcher. */
    private const val LAUNCHER_MARKER_PATH = "$OC_HOME/.oc-launcher"

    /**
     * The `opencode-pty` helper that the web terminal spawns. It is built from pure C by this
     * project and shipped as a bionic asset, so it no longer depends on the version-specific
     * glibc executable embedded in a downloaded OpenCode CLI.
     */
    const val PTY_BIN_PATH = "$OC_HOME/bin/opencode-pty"

    /**
     * Directory below which the `opencode-pty` daemon creates its unix socket. It falls back to
     * `/tmp`, which does not exist on Android, so without this the daemon dies with EACCES and the
     * terminal never opens. The path must stay short: a unix socket path is limited to 107 bytes
     * and `<dir>/opencode-pty-<uid>/<32 hex>.sock` already uses 57 of them, so neither [OC_HOME]
     * nor `$PREFIX/tmp/opencode` fits.
     */
    const val PTY_SOCKET_DIR = "$PREFIX/run"

    /** Records which app build extracted the C terminal helper. */
    private const val PTY_MARKER_PATH = "$OC_HOME/.terminal-runtime-c"

    /** Timeout for `opencode-pty --version`, also the sanity check of the helper. */
    private const val PTY_VERIFY_TIMEOUT_MS = 20_000L

    /** Timeout for `rg --version` through the cache wrapper. */
    private const val RIPGREP_VERIFY_TIMEOUT_MS = 10_000L

    /**
     * Directory in which OpenCode caches helper binaries that it downloads itself (`rg`, the pty
     * helper, …). It belongs to OpenCode, so the app only seeds entries it can provide in a form
     * that Android can execute.
     */
    private const val OPENCODE_CACHE_BIN_DIR = "$HOME/.cache/opencode/bin"

    /**
     * Cached ripgrep of OpenCode. OpenCode downloads ripgrep 15.1.0 for `arm64-linux` here and
     * reuses the file on every start. That build is a glibc executable with
     * `PT_INTERP=/lib/ld-linux-aarch64.so.1`, a path that does not exist on Android, so the
     * built-in `grep`/`glob` tools fail with "ripgrep execution failed". The app replaces the
     * cache entry with a wrapper around the bionic ripgrep of the prefix.
     */
    private const val RIPGREP_CACHE_PATH = "$OPENCODE_CACHE_BIN_DIR/rg"

    /** Directory used while downloading and patching. */
    const val TMP_DIR = "$OC_HOME/tmp"

    /** Directory holding the glibc loader shipped by `glibc-runner`. */
    const val GLIBC_LIB_DIR = "$PREFIX/glibc/lib"

    /** Symlink to the currently installed OpenCode binary, `opencode`. */
    const val OPENCODE_BIN_PATH = "$PREFIX/bin/opencode"

    /** Symlink to [BRIDGE_PATH], `pkg`. */
    const val PKG_PATH = "$PREFIX/bin/pkg"

    /** Kotlin/Native executable behind `pkg`. */
    const val BRIDGE_PATH = "$PREFIX/libexec/oc-bridge"

    /** glibc preload library that maps the Termux roots into this prefix. */
    const val PATHMAP_PATH = "$PREFIX/lib/liboc-pathmap.so"

    /** Static helper that patches and starts downloaded programs. */
    const val GOPATCH_PATH = "$PREFIX/libexec/oc-gopatch"

    /** Entry point for downloaded programs, `oc-run`. */
    const val OCRUN_PATH = "$PREFIX/bin/oc-run"

    /** Records which app build extracted the current bridge. */
    const val BRIDGE_MARKER_PATH = "$OC_HOME/.cli-bridge"

    /** Records which app build extracted the native helpers (`oc-gopatch`, `liboc-pathmap.so`). */
    private const val NATIVE_TOOLS_MARKER_PATH = "$OC_HOME/.native-tools"

    /** Environment snippet that the shell startup files of the runtime source. */
    const val ENV_SCRIPT_PATH = "$PREFIX/etc/opencode-env.sh"

    /**
     * Gradle user home. Gradle normally derives it from the JVM `user.home`, which the Termux JDK
     * resolves to the home of the Termux app. Exporting it explicitly keeps the build cache,
     * `gradle.properties` and the wrapper distribution inside this app's sandbox.
     */
    const val GRADLE_USER_HOME = "$HOME/.gradle"

    /** Managed file below [GRADLE_USER_HOME] that points AGP at the native `aapt2`. */
    private const val GRADLE_PROPERTIES_PATH = "$GRADLE_USER_HOME/gradle.properties"

    /** Android user home used by the SDK command-line tools (`sdkmanager`, `avdmanager`). */
    const val ANDROID_USER_HOME = "$HOME/.android"

    /** Markers of the block that the app manages in [GRADLE_PROPERTIES_PATH]. */
    private const val GRADLE_BLOCK_BEGIN = "# >>> OpenCode for Android Android SDK >>>"
    private const val GRADLE_BLOCK_END = "# <<< OpenCode for Android Android SDK <<<"

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
     * State of the prefix that the last full maintenance pass repaired. When the app, the APK,
     * the package marker and the package directories are unchanged, the next start only runs the
     * cheap pass instead of scanning bin/, lib/ and the node tree again.
     */
    private const val MAINTENANCE_STAMP_PATH = "$OC_HOME/.maintenance"

    /** Bumped when the maintenance stamp has to be invalidated for a new app build. */
    private const val MAINTENANCE_VERSION = 1

    /**
     * Configuration file of the OpenSSL package. Termux installs it below `etc/tls`, because
     * the package also keeps its certificate store there. The compiled-in default still points
     * at `/data/data/com.termux/files/usr/etc/tls/openssl.cnf`, which belongs to the Termux app
     * and is unreadable here, so the variable has to be exported explicitly.
     */
    const val OPENSSL_CONF_PATH = "$PREFIX/etc/tls/openssl.cnf"

    /**
     * Provider modules of OpenSSL 3 (`legacy.so`, …). Like the configuration file, the compiled
     * default points into the Termux app, so a program that loads a provider other than the
     * built-in default one would fail with "module not found".
     */
    const val OPENSSL_MODULES_PATH = "$PREFIX/lib/ossl-modules"

    /** Engines of OpenSSL 3 (`capi.so`, …), also looked up in the unreadable Termux prefix. */
    const val OPENSSL_ENGINES_PATH = "$PREFIX/lib/engines-3"

    /**
     * CA bundle that every TLS stack of the runtime uses. It is seeded from the APK when the
     * `ca-certificates` package is missing or left a truncated file behind, see [ensureCaBundle].
     */
    const val CA_BUNDLE_PATH = "$PREFIX/etc/tls/cert.pem"

    /** Mozilla CA bundle shipped in the APK, the asset that seeds [CA_BUNDLE_PATH]. */
    private const val CA_BUNDLE_ASSET = "net/mozilla-ca-bundle.pem"

    /**
     * SHA-256 of [CA_BUNDLE_ASSET]. The asset is verified before it is materialized, so a broken
     * APK cannot silently install a corrupt trust store.
     */
    private const val CA_BUNDLE_SHA256 =
        "796f1389bfea13c0ed78f663b765baaff54a508e5483786bd6ece73c5e3e3cbe"

    /** A real bundle is ~200 KB; anything below this is the truncated file seen after a crash. */
    private const val MIN_CA_BUNDLE_SIZE = 1024L

    /**
     * Upper bound of a glibc object that is relocated. libstdc++ (22 MB), libasan (11 MB) and the
     * sanitizer runtimes carry only compiler paths, while everything that the resolver and the
     * runtime read at run time stays well below this limit.
     */
    private const val MAX_RELOCATED_FILE_SIZE = 8L * 1024 * 1024

    /* ------------------------------------------------------------------ constants */

    /** Bumped when the bridge binary has to be extracted again, even for the same app build. */
    private const val BRIDGE_VERSION = 1

    /** Bumped when the OpenCode launcher has to be extracted again. */
    private const val LAUNCHER_VERSION = 1

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

    private const val PROCESS_POLL_INTERVAL_MS = 100L

    /** Maximum number of characters of a single command output kept in memory. */
    private const val MAX_OUTPUT_CHARS = 256 * 1024

    /** Maximum length of the detail part of a notification. */
    private const val MAX_DETAIL_CHARS = 900

    /** Prefix that the Termux packages are built for, it has the same length as [DATA_DIR]. */
    private const val PREFIX_TERMUX = "/data/data/com.termux/files/usr"

    /** Home directory that is baked into the Termux profile scripts. */
    private const val HOME_TERMUX = "/data/data/com.termux/files/home"

    /**
     * Termux packages that are extracted into the app-private prefix before OpenCode is
     * downloaded: a shell, the core utilities (they provide `bin/env`, which the `#!/…/bin/env`
     * launchers of npm and pip need), the OpenSSL libraries used by git/gh/libcurl, the
     * certificate store and the glibc runtime (the loader and libraries that run OpenCode).
     *
     * The GNU text tools and ripgrep are part of the base toolchain as well. Android only ships
     * toybox versions of grep/sed/find/awk: they reject common GNU syntax (`grep -P`, the BRE
     * `\|`, `sed \s`/`\b`) and the built-in search tools of OpenCode need a bionic ripgrep. The
     * prefix has to come with working versions, otherwise a code-reading command fails with a
     * syntax error that looks like a bug in the file being read.
     *
     * `coreutils` currently pulls `openssl` in as a dependency, but that is an implementation
     * detail of the repository. Listing it here keeps the TLS libraries installed even if the
     * dependency graph changes.
     */
    private val CORE_PACKAGES =
        listOf(
            "bash", "coreutils", "openssl", "ca-certificates", "glibc",
            "grep", "sed", "gawk", "findutils", "diffutils", "ripgrep",
        )

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

    /** An update request that arrived while maintenance held the runtime. */
    private var sUpdatePending = false

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

    /** Last application context, used for best-effort native helper extraction. */
    @Volatile private var appContext: Context? = null

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
        appContext = context.applicationContext

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
        appContext = context.applicationContext

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
            if (sMaintenanceRunning) {
                // A deep maintenance pass may be repairing the same files; run the update right
                // after it instead of racing it.
                sUpdatePending = true
                Log.i(LOG_TAG, "Maintenance is running, queueing the update request.")
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
        appContext = context.applicationContext
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
                // Housekeeping must never compete with the server start or the WebView with a
                // normal priority, so a deep pass does not make the app feel slow on a cold start.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                Installer(context, false).apply {
                    logOnly = true
                    doMaintenance()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to maintain the OpenCode runtime", e)
            } finally {
                var updateQueued = false
                synchronized(sLock) {
                    sMaintenanceRunning = false
                    updateQueued = sUpdatePending
                    sUpdatePending = false
                }
                if (updateQueued) requestUpdate(context)
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
     * time the network changes and the server starts. Public resolvers are only used when the
     * active network reports no resolver of its own.
     *
     * The order matters. Android reports the IPv6 DNS server first on many carriers, and glibc
     * tries the servers in the order they are listed. When the device has no IPv6 connectivity,
     * each lookup waits for the IPv6 timeout before it falls back to IPv4, and on a network whose
     * only resolver is IPv6-only every lookup fails with `EAI_AGAIN` even though the IPv4 side of
     * the connection works. IPv4 servers are therefore written first, and an IPv6 server is only
     * kept when this device actually has a global IPv6 address to reach it.
     */
    fun refreshResolvConf(context: Context?) {
        val content = buildResolvConf(context)
        if (content == null) {
            // The prefix is not unpacked yet, there is nothing to configure.
            return
        }

        // resolv.conf of the prefix and the one the glibc libraries read. In the Termux packages
        // the second path is a symlink to the first one, writing both also repairs a missing link.
        for (path in listOf(RESOLV_CONF_PATH, "$GLIBC_ETC_DIR/resolv.conf")) {
            val file = File(path)
            if (file.parentFile?.isDirectory != true) continue
            // A default-network callback fires on every handover, including ones that keep the
            // same resolvers. Rewriting the file would wake the server for nothing.
            if (readTextFileQuietly(file) == content) continue
            try {
                // The glibc copy is usually a symlink to the prefix copy; write the real target so
                // the link itself survives the atomic rename.
                OpenCodeFiles.writeTextAtomically(file.canonicalFile, content)
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Could not write $path", e)
            }
        }
        val nameservers = content.lineSequence().filter { it.startsWith("nameserver") }.joinToString()
        Log.i(LOG_TAG, "Resolver configuration: $nameservers")
    }

    /** Servers that resolve names on networks which report no resolver of their own. */
    private val PUBLIC_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")

    /**
     * glibc reads at most three nameservers (`MAXNS`); anything beyond the third line is ignored,
     * so the list is capped here instead of writing lines that would never be used.
     */
    private const val MAX_RESOLV_SERVERS = 3

    /**
     * Build the content of `resolv.conf` for the current network, or null when the prefix itself is
     * not unpacked yet.
     */
    private fun buildResolvConf(context: Context?): String? {
        val prefixReady = File(RESOLV_CONF_PATH).parentFile?.isDirectory == true ||
            File(GLIBC_ETC_DIR).isDirectory
        if (!prefixReady) return null

        val servers = selectResolvServers(context)
        return buildString {
            servers.forEach { append("nameserver ").append(it).append('\n') }
            // One round of one second is enough for a resolver that answers, and it keeps a broken
            // server from stalling every lookup for four seconds (timeout x attempts).
            append("options timeout:1 attempts:1\n")
        }
    }

    /**
     * Pick the nameservers for `resolv.conf`: the IPv4 servers of the active network first, then
     * an IPv6 server only when the device has a global IPv6 address.
     *
     * The network is authoritative. A resolver handed out by Wi-Fi, mobile data or a VPN can be
     * split-horizon (captive portal, corporate or VPN-only names) or enforce the filtering of the
     * provider; replacing it with a public resolver would bypass that. Public resolvers are
     * therefore used only when the active network reports no resolver at all.
     */
    private fun selectResolvServers(context: Context?): List<String> {
        val network4 = ArrayList<String>()
        val network6 = ArrayList<String>()
        if (context != null) {
            try {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network: Network? = manager?.activeNetwork
                val properties = if (network == null) null else manager.getLinkProperties(network)
                properties?.dnsServers?.forEach { address: InetAddress ->
                    if (address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
                        return@forEach
                    }
                    val host = address.hostAddress?.substringBefore('%')
                    if (host.isNullOrEmpty()) return@forEach
                    val target = if (address is Inet6Address) network6 else network4
                    if (host !in target) target.add(host)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not read the DNS servers of the active network", e)
            }
        }

        val servers = ArrayList<String>()
        // IPv4 first: it is reachable on every network and answers without waiting for an IPv6
        // route that this device may not have.
        network4.take(MAX_RESOLV_SERVERS).forEach { if (servers.size < MAX_RESOLV_SERVERS) servers.add(it) }
        if (servers.size < MAX_RESOLV_SERVERS && network6.isNotEmpty() && hasGlobalIpv6Address()) {
            network6.take(MAX_RESOLV_SERVERS - servers.size).forEach { servers.add(it) }
        }
        // Only a network without any resolver of its own gets the public fallback.
        if (servers.isEmpty()) {
            PUBLIC_DNS_SERVERS.forEach {
                if (servers.size < MAX_RESOLV_SERVERS && it !in servers) servers.add(it)
            }
        }
        return servers
    }

    /**
     * Whether any interface of this device owns a global IPv6 address.
     *
     * Android happily reports the IPv6 resolver of the carrier on an IPv4-only connection (the
     * router advertises it, the host route is missing). Writing that server first is what makes
     * `getaddrinfo` wait for the unreachable address and eventually fail with `EAI_AGAIN`.
     */
    private fun hasGlobalIpv6Address(): Boolean {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val nic = interfaces.nextElement()
                if (!nic.isUp) continue
                val addresses = nic.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet6Address && address.isGlobalIpv6Address()) return true
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not inspect the IPv6 addresses of this device", e)
        }
        return false
    }

    /**
     * A routable IPv6 address. `isSiteLocalAddress` also covers the deprecated `fec0::/10` range,
     * which no carrier routes; `isLoopbackAddress` and `isLinkLocalAddress` cover the rest.
     */
    private fun Inet6Address.isGlobalIpv6Address(): Boolean =
        !isAnyLocalAddress && !isLoopbackAddress && !isLinkLocalAddress && !isSiteLocalAddress &&
            !isMulticastAddress

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
        if (!File(LINK_PATH).exists()) problems.add("the opencode symlink ($LINK_PATH)")
        if (!isExecutable(File(OPENCODE_BIN_PATH))) problems.add("the opencode command ($OPENCODE_BIN_PATH)")
        if (!isExecutable(File(BRIDGE_PATH))) problems.add("the CLI bridge ($BRIDGE_PATH)")
        if (!File(PKG_PATH).exists()) problems.add("the pkg symlink ($PKG_PATH)")
        if (findGlibcLoader() == null) problems.add("the glibc loader ($GLIBC_LIB_DIR/ld-linux-*.so.*)")

        return problems
    }

    /** True for a problem that the maintenance pass repairs without downloading anything. */
    private fun isEntryPointProblem(problem: String): Boolean =
        problem == "the opencode command ($OPENCODE_BIN_PATH)" ||
            problem == "the opencode symlink ($LINK_PATH)" ||
            problem == "the CLI bridge ($BRIDGE_PATH)" ||
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
    fun ensurePackageEnvironment(context: Context? = null) {
        writePackageEnvironment(context)
    }

    /**
     * Write `etc/opencode-env.sh` and hook it into the shell startup files. The profile is only
     * replaced when this app wrote it or when it still points at the Termux app; a profile edited
     * by the user is left untouched.
     */
    private fun writePackageEnvironment(context: Context?) {
        val etc = File(PREFIX, "etc")
        if (!etc.isDirectory && !etc.mkdirs()) {
            Log.w(LOG_TAG, "Could not create $etc")
            return
        }
        // Seed the trust store and the git config first: the contract reads both files when it
        // renders the environment script, so it must see the state the shells will run with.
        ensureCaBundle(context)
        writeGitConfig()
        writeTextFileIfChanged(File(ENV_SCRIPT_PATH), buildPackageEnvironment(context))
        ensureGradleProperties()
        ensureLoginProfile()
        hookBashrc()
    }

    /**
     * Point the Android Gradle Plugin at the native `aapt2` of this prefix.
     *
     * AGP downloads its own `aapt2` from Maven, and that artifact is an x86_64 glibc executable
     * even on an ARM64 device. The Termux package installs a real AArch64 build as
     * `$PREFIX/bin/aapt2`; the project property below is the supported way to select it without
     * patching every project. The property lives in the Gradle user home instead of a project so
     * it applies to any Android project the user builds in this sandbox.
     *
     * A value that the user already wrote is left alone, and the block is removed again when
     * `aapt2` is not installed.
     */
    private fun ensureGradleProperties() {
        val file = File(GRADLE_PROPERTIES_PATH)
        val current = readTextFileQuietly(file)
        val hasBlock = current.contains(GRADLE_BLOCK_BEGIN)
        val aapt2 = File(PREFIX, "bin/aapt2").takeIf { it.isFile && it.canExecute() }
        // Nothing to add and no managed block to remove: leave a user file byte for byte alone.
        if (!hasBlock && aapt2 == null) return

        val base = stripManagedBlock(current).trimEnd()
        val hasUserValue = base.lineSequence().any { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("#") &&
                trimmed.substringBefore('=').trim() == "android.aapt2FromMavenOverride"
        }
        val block = if (aapt2 != null && !hasUserValue) {
            buildString {
                append(GRADLE_BLOCK_BEGIN).append('\n')
                append("# The Maven artifact is x86_64 and cannot run on this device.\n")
                append("android.aapt2FromMavenOverride=").append(aapt2.absolutePath).append('\n')
                append(GRADLE_BLOCK_END).append('\n')
            }
        } else {
            null
        }

        val updated = buildString {
            append(base)
            if (block != null) {
                if (isNotEmpty()) append("\n\n")
                append(block)
            } else if (isNotEmpty()) {
                append('\n')
            }
        }
        if (updated != current) writeTextFileQuietly(file, updated)
    }

    /** Remove the block between the Gradle markers, leaving the rest of the file untouched. */
    private fun stripManagedBlock(text: String): String {
        val start = text.indexOf(GRADLE_BLOCK_BEGIN)
        if (start < 0) return text
        val end = text.indexOf(GRADLE_BLOCK_END, start)
        if (end < 0) return text.substring(0, start)
        return text.substring(0, start) + text.substring(end + GRADLE_BLOCK_END.length)
    }

    /**
     * Materialize the Mozilla bundle of the APK into `etc/tls/cert.pem` when the prefix has no
     * usable bundle. This keeps every HTTPS request working on the first start, even when the
     * `ca-certificates` package has not been downloaded yet or a crash left a truncated file
     * behind.
     *
     * An existing bundle is kept when it is complete. The `ca-certificates` package ships a newer
     * Mozilla snapshot than the APK, so its file must not be replaced just because the hashes
     * differ; the SHA-256 of the asset is only used to validate what is about to be written and to
     * make writing it idempotent.
     */
    private fun ensureCaBundle(context: Context?) {
        val target = File(CA_BUNDLE_PATH)
        if (isUsableCaBundle(target)) return

        val assets = context?.assets ?: return
        val data = try {
            assets.open(CA_BUNDLE_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not read $CA_BUNDLE_ASSET from the APK", e)
            return
        }
        val digest = try {
            toHex(MessageDigest.getInstance("SHA-256").digest(data))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not hash $CA_BUNDLE_ASSET", e)
            return
        }
        if (digest != CA_BUNDLE_SHA256) {
            Log.e(LOG_TAG, "The CA bundle asset does not match its SHA-256, ignoring it.")
            return
        }

        val parent = target.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) {
            Log.w(LOG_TAG, "Could not create the directory $parent")
            return
        }
        var temp: File? = null
        try {
            val created = File.createTempFile("cert", ".pem", parent)
            temp = created
            FileOutputStream(created).use { output ->
                output.write(data)
                output.fd.sync()
            }
            try {
                Os.chmod(created.absolutePath, MODE_644)
            } catch (e: Exception) {
                // The prefix is private to the app; the mode is not critical.
            }
            if (!created.renameTo(target)) {
                // Some file systems refuse a rename over an existing file.
                deleteQuietly(target)
                if (!created.renameTo(target)) {
                    throw IOException("Could not replace $target")
                }
            }
            temp = null
            Log.i(LOG_TAG, "Installed the CA bundle of the app at $target")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not install the CA bundle", e)
        } finally {
            temp?.let { deleteQuietly(it) }
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xff
            hex[i * 2] = digits[value ushr 4]
            hex[i * 2 + 1] = digits[value and 0x0f]
        }
        return String(hex)
    }

    /** True when [file] is a complete PEM bundle, not a missing or truncated file. */
    private fun isUsableCaBundle(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_CA_BUNDLE_SIZE) return false
        return try {
            val text = file.readText(StandardCharsets.US_ASCII)
            text.contains("-----BEGIN CERTIFICATE-----") &&
                text.contains("-----END CERTIFICATE-----")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not read $file", e)
            false
        }
    }

    /**
     * Write `etc/gitconfig` with the CA bundle of this prefix. Termux's libcurl was built with
     * `/data/data/com.termux/files/usr/etc/tls/cert.pem` as its default CA file, a path that does
     * not exist inside this app, and OpenSSL ignores `SSL_CERT_FILE` when curl passes that
     * explicit file. `http.sslCAInfo` makes git hand our bundle to libcurl instead, for the app's
     * clone as well as for a clone from the terminal.
     */
    private fun writeGitConfig() {
        val cert = File(CA_BUNDLE_PATH)
        if (!cert.isFile) return
        val text = buildString {
            append("# Managed by the OpenCode for Android app.\n")
            append("[http]\n")
            append("\tsslCAInfo = ").append(cert.absolutePath).append('\n')
        }
        writeTextFileIfChanged(File(PREFIX, "etc/gitconfig"), text)
    }

    /**
     * The generated package environment. All exported variables come from the runtime contract;
     * only the shell-only helpers live in [RuntimeShellScript].
     */
    private fun buildPackageEnvironment(context: Context? = null): String =
        RuntimeShellScript.render(RuntimeEnv.build(runtimeInputs(context = context)))

    /** Newest `lib/jvm/java-<version>-openjdk` of the prefix, or null when no JDK is installed. */
    fun javaHome(): String? = jdkHome()?.absolutePath

    /**
     * The Android/filesystem half of [RuntimeInputs]. This is the only adapter that resolves the
     * sandbox paths: the service builds the server contract from it and the generated shell script
     * renders from the same result, so the two layers cannot drift apart.
     */
    fun runtimeInputs(
        port: Int = RuntimeContract.DEFAULT_PORT,
        apkStamp: String = "",
        packagesStamp: String = "",
        nativeStamp: String = "",
        context: Context? = null,
        /**
         * False for commands that prepare the helper itself: calling [ensureTerminalRuntime] there
         * would recurse into [runCommand]. The already installed path is enough for those inputs.
         */
        resolvePty: Boolean = true,
    ): RuntimeInputs = RuntimeInputs(
        abi = getOpenCodeArch().orEmpty(),
        prefix = PREFIX,
        home = HOME,
        ocHome = OC_HOME,
        port = port,
        binaryPath = installedBinary()?.absolutePath.orEmpty(),
        // The prefix loader is app_data_file and cannot be exec'd with targetSdk 36.
        // The relocated copy in nativeLibraryDir is apk_data_file and stays executable.
        loader = nativeGlibcLoader(context) ?: findGlibcLoader().orEmpty(),
        preload = File(PATHMAP_PATH).takeIf { it.isFile }?.absolutePath,
        glibcLibDir = GLIBC_LIB_DIR,
        nativeLibraryDir = nativeLibraryDir(context).orEmpty(),
        execShim = execShim(context),
        ptyBin = if (resolvePty) ensureTerminalRuntime(context) else terminalRuntimePath(context),
        ptySocketDir = PTY_SOCKET_DIR,
        javaHome = javaHome(),
        javaToolOptions = javaToolOptions(),
        gradleUserHome = GRADLE_USER_HOME,
        androidUserHome = ANDROID_USER_HOME,
        androidSdkHome = androidSdkHome(),
        bashPath = File(PREFIX, "bin/bash").takeIf { it.isFile }?.absolutePath,
        envScriptPath = ENV_SCRIPT_PATH,
        caBundlePath = File(CA_BUNDLE_PATH).takeIf { it.isFile }?.absolutePath,
        opensslConfPath = File(OPENSSL_CONF_PATH).takeIf { it.isFile }?.absolutePath,
        opensslModulesPath = File(OPENSSL_MODULES_PATH).takeIf { it.isDirectory }?.absolutePath,
        opensslEnginesPath = File(OPENSSL_ENGINES_PATH).takeIf { it.isDirectory }?.absolutePath,
        gitExecPath = File(PREFIX, "libexec/git-core").takeIf { it.isDirectory }?.absolutePath,
        gitTemplateDir = File(PREFIX, "share/git-core/templates").takeIf { it.isDirectory }?.absolutePath,
        gitSystemConfigPath = File(PREFIX, "etc/gitconfig").takeIf { it.isFile }?.absolutePath,
        apkStamp = apkStamp,
        packagesStamp = packagesStamp,
        nativeStamp = nativeStamp,
    )

    /**
     * Contract inputs for commands the JVM starts directly. They must not install the PTY helper:
     * the helper installer itself calls [runCommand], so resolving it here would recurse forever.
     */
    internal fun commandInputs(context: Context? = null): RuntimeInputs =
        runtimeInputs(context = context, resolvePty = false)

    /** The installed OpenCode binary, following the version symlink when one exists. */
    fun installedBinary(): File? {
        val link = File(LINK_PATH)
        if (!link.exists()) {
            return File(INSTALL_DIR).listFiles()
                ?.firstOrNull { it.isFile && it.name.startsWith("opencode-v") }
        }
        return resolveLink(link)
    }

    /**
     * Options that every JVM in this sandbox needs.
     *
     * The Termux JDK derives `user.home` from the compile time Termux home, so Gradle writes its
     * metrics below `/data/data/com.termux`, which belongs to the other app, and Robolectric
     * cannot create its download lock. `java.io.tmpdir` is pointed at the prefix because Android
     * has no `/tmp`. Robolectric's Conscrypt provider is only published for x86_64, so its JNI
     * lookup fails on an ARM64 device; disabling that provider is the documented workaround.
     *
     * Options that are already present (for example from the user's shell) are kept unchanged.
     */
    fun javaToolOptions(): String {
        val configured = try {
            System.getenv("JAVA_TOOL_OPTIONS").orEmpty().trim()
        } catch (e: Exception) {
            ""
        }
        val defaults = listOf(
            "-Duser.home=$HOME",
            "-Djava.io.tmpdir=$PREFIX/tmp",
            "-Drobolectric.conscryptMode=OFF",
        )
        val missing = defaults.filterNot { option ->
            configured.contains(option.substringBefore('=') + "=")
        }
        return (missing + listOfNotNull(configured.takeIf { it.isNotEmpty() })).joinToString(" ")
    }

    /**
     * Android SDK directories below the sandbox that this app knows about, most specific first.
     * The environment script checks the same locations, so a shell and the app agree on the SDK
     * that `sdkmanager`, Gradle and `pkg repair` use.
     */
    fun androidSdkRoots(): List<File> {
        val candidates = LinkedHashSet<File>()
        for (name in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            try {
                System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(File(it)) }
            } catch (e: Exception) {
                // A restricted environment is not a reason to skip the fixed locations below.
            }
        }
        for (relative in listOf("Android/Sdk", "android-sdk", "Android/sdk", "sdk")) {
            candidates.add(File(HOME, relative))
        }
        candidates.add(File(PREFIX, "opt/android-sdk"))
        candidates.add(File(PREFIX, "share/android-sdk"))
        return candidates.filter { isAndroidSdk(it) }
    }

    /** First usable Android SDK of [androidSdkRoots], or null. */
    fun androidSdkHome(): File? = androidSdkRoots().firstOrNull()

    private fun isAndroidSdk(dir: File): Boolean =
        File(dir, "cmdline-tools").isDirectory ||
            File(dir, "platform-tools").isDirectory ||
            File(dir, "build-tools").isDirectory

    /**
     * Newest `lib/jvm/java-<version>-openjdk` of the prefix, or null when no JDK is installed.
     * Kept in sync with the symlink logic of [OpenCodePackages].
     */
    private fun jdkHome(): File? =
        File(PREFIX, "lib/jvm").listFiles()
            ?.filter {
                it.isDirectory &&
                    it.name.startsWith("java-") &&
                    it.name.endsWith("-openjdk") &&
                    File(it, "bin/java").isFile
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
            append("export LANG=\"\${LANG:-C.UTF-8}\"\n")
            append("if [ -r \"\$PREFIX/etc/opencode-env.sh\" ]; then . \"\$PREFIX/etc/opencode-env.sh\"; fi\n")
            append("if [ -r \"\$HOME/.bashrc\" ]; then . \"\$HOME/.bashrc\"; fi\n")
        }
        if (content != current) writeTextFileQuietly(profile, content)
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
        // The paths and OS hints are the same contract values the terminal and the server use.
        // bootstrapEnvironment() intentionally omits LD_LIBRARY_PATH/LD_PRELOAD: the verification
        // process is started through an explicit glibc loader, and a bionic child must not pick up
        // a prefix library path that would shadow Android's own libraries.
        private val env: MutableMap<String, String> =
            RuntimeEnv.build(commandInputs(context)).bootstrapEnvironment().toMutableMap().apply {
                put("TERMUX_PKG_NO_MIRROR_SELECT", "1")
            }
        private var lastOutput = ""
        var logOnly = false

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

            // The OpenCode binary and its helpers run through the glibc loader of the prefix with
            // path translation (liboc-pathmap), so they do not need a patched PT_INTERP or RUNPATH.

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

            // Relocate the glibc runtime up front so the loader and libc read paths of this
            // sandbox; the OpenCode binary is verified through that loader below.
            relocateGlibc(loader)

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
                    if (!resolveLink(File(LINK_PATH)).isFile) {
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
                // The web terminal helper comes from this APK, so it stays independent of the
                // version of the CLI that is installed here.
                if (ensureTerminalRuntime() == null) {
                    status("WARNING: the terminal helper could not be prepared, the terminal may not start.")
                }
                ensureRipgrepRuntime(context)
                installEntrypoints()
            } finally {
                deleteQuietly(tarball)
            }

            // The old installer script must not stay around, it would fight with the app.
            deleteQuietly(File(LEGACY_SETUP_SCRIPT_PATH))
        }

        fun doMaintenance() {
            deleteQuietly(File(LEGACY_SETUP_SCRIPT_PATH))
            val started = SystemClock.uptimeMillis()
            val current = isMaintenanceCurrent()
            if (current) {
                doQuickMaintenance()
            } else {
                doDeepMaintenance()
            }
            // Write the stamp at the end: a full pass changes files in bin/ and etc/, so only the
            // state after the pass describes a repaired prefix. Writing it only when it changed
            // keeps the lock file untouched when a quick pass repairs nothing.
            writeTextFileIfChanged(File(MAINTENANCE_STAMP_PATH), maintenanceStamp() + "\n")
            Log.i(
                LOG_TAG,
                "Maintenance finished in ${SystemClock.uptimeMillis() - started} ms " +
                    "(${if (current) "quick" else "full"} pass).",
            )
        }

        /**
         * The parts that have to run on every start even when the prefix did not change. They are
         * cheap (they read small markers instead of scanning the package trees) and keep an entry
         * point, the resolver or the terminal helper working after a crash.
         */
        private fun doQuickMaintenance() {
            ensurePathAliases()
            ensureTerminalRuntime()
            ensureRipgrepRuntime(context)
            // The env script is rewritten when its content changed, so a fix in it reaches an
            // install that predates it without reinstalling a package first (the `pkg` wrapper).
            ensurePackageEnvironment(context)
            refreshResolvConf(context)
            installEntrypoints()
        }

        /**
         * Full self-heal of a prefix that changed: a package was installed, updated or removed,
         * the app was updated, or a file vanished. Only this pass scans the package trees and
         * rewrites binaries that a package update may have replaced with the Termux paths.
         */
        private fun doDeepMaintenance() {
            // Repair a prefix that was installed by an older build: dangling Termux symlinks,
            // dead Termux shebangs and the git ELF shell path. Then reinstall packages whose
            // marker entry exists but whose files are gone (an interrupted extraction).
            try {
                val repaired = OpenCodePackages.repair(File(PREFIX))
                if (repaired != null) Log.i(LOG_TAG, "Repaired the package environment: $repaired")
                val broken = OpenCodePackages.brokenPackages(File(PREFIX))
                val recorded = OpenCodePackages.installed(File(PREFIX))
                // A prefix installed by an older build predates coreutils: install it, the
                // `#!/…/bin/env node` launchers of npm need bin/env. The same check makes an
                // older prefix gain the OpenSSL libraries that are part of the toolchain now.
                val missingCore = CORE_PACKAGES.any { it !in recorded }
                if (broken.isNotEmpty() || missingCore) {
                    val repair = (CORE_PACKAGES + broken).distinct()
                    Log.i(LOG_TAG, "Reinstalling broken packages: $repair")
                    installPackages(repair, "Could not repair the Linux toolchain")
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
            ensureTerminalRuntime()
            // The package repair above installs ripgrep on an existing prefix; seed the cache
            // entry that OpenCode reads right after it, before the server is started.
            ensureRipgrepRuntime(context)
            ensurePackageEnvironment(context)
            refreshResolvConf(context)
            patchLoginShellPaths()
            installEntrypoints()
        }

        /**
         * True when the last full pass describes the current prefix. The package marker and the
         * directory times catch an install, an update, an interrupted extraction or a removed
         * file; the APK stamp catches an app update.
         */
        private fun isMaintenanceCurrent(): Boolean {
            if (readTrimmed(File(MAINTENANCE_STAMP_PATH)) != maintenanceStamp()) return false
            // The directory time does not change when only the content of a file changes, so
            // prove the packages with a few stats before skipping the repair scan.
            return !packagesNeedRepair()
        }

        private fun packagesNeedRepair(): Boolean {
            val prefix = File(PREFIX)
            if (OpenCodePackages.brokenPackages(prefix).isNotEmpty()) return true
            val recorded = OpenCodePackages.installed(prefix)
            return CORE_PACKAGES.any { it !in recorded }
        }

        /** Fingerprint of the prefix and the app build that the last full pass repaired. */
        private fun maintenanceStamp(): String {
            val packages = File(PREFIX, ".packages-installed")
            return buildString {
                append(MAINTENANCE_VERSION).append('|')
                append(BuildConfig.VERSION_CODE).append('|')
                append(apkStamp()).append('|')
                append(packages.length()).append(':').append(packages.lastModified()).append('|')
                // An install, update or removal changes the time of these directories.
                append(File(PREFIX, "bin").lastModified()).append('|')
                append(File(PREFIX, "lib").lastModified()).append('|')
                append(File(GLIBC_LIB_DIR).lastModified())
            }
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
         * Install the downloaded OpenCode binary and verify that it runs in this environment.
         *
         * The binary is always started through the glibc loader of the prefix with
         * liboc-pathmap.so preloaded (when available), so it does not need its PT_INTERP patched
         * and an update or downgrade never depends on per-binary surgery.
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

            try {
                Os.chmod(staged.absolutePath, MODE_755)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not make the OpenCode binary executable", e)
            }

            setStage(OpenCodeInstallState.STAGE_PATCH, "Preparing the OpenCode runtime...")
            // The loader and libc are used at run time, so they must read the paths of this
            // sandbox (resolver, etc.). Aliases make the relocated strings resolvable.
            ensurePathAliases()
            relocateGlibc(loader)
            // Make sure the path-map helper is available for the verification and for runtime.
            installNativeTools()

            setStage(OpenCodeInstallState.STAGE_VERIFY, "Verifying the OpenCode binary...")
            val verify = runOpenCodeBinary(listOf(staged.absolutePath, "--version"), VERIFY_TIMEOUT_MS)
            if (!looksLikeAVersion(verify)) {
                deleteQuietly(staged)
                if (target.isFile || resolveLink(File(LINK_PATH)).isFile) {
                    status("WARNING: the downloaded build does not run, keeping the installed one.")
                    Log.w(LOG_TAG, "New OpenCode build failed verification: ${tail(verify.output, 400)}")
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

            // Every build is ~130 MB, keep only the one that is linked.
            pruneOldBuilds(target)

            return target
        }

        /**
         * Run an OpenCode binary through the glibc loader with the path-map preload, the same way
         * the fixed launcher does. This keeps the binary usable without patching its PT_INTERP.
         */
        private fun runOpenCodeBinary(cmd: List<String>, timeoutMs: Long): CmdResult {
            if (cmd.isEmpty()) return CmdResult(-1, "", "empty command")
            val loader = nativeGlibcLoader(context) ?: findGlibcLoader()
                ?: return CmdResult(-1, "", "glibc loader is missing")
            val binary = cmd[0]
            val args = ArrayList<String>()
            args.add(loader)
            val pathmap = File(PATHMAP_PATH)
            if (pathmap.isFile) {
                args.add("--preload")
                args.add(pathmap.absolutePath)
            }
            args.add("--library-path")
            args.add(GLIBC_LIB_DIR)
            args.addAll(cmd)

            // The loader must not pick up bionic libraries; OC_REAL_EXE lets the preload report the
            // real program as /proc/self/exe.
            val prevLib = env.remove("LD_LIBRARY_PATH")
            val prevPreload = env.remove("LD_PRELOAD")
            val prevReal = env["OC_REAL_EXE"]
            env["OC_REAL_EXE"] = binary
            try {
                return run(args, timeoutMs, false)
            } finally {
                if (prevLib != null) env["LD_LIBRARY_PATH"] = prevLib else env.remove("LD_LIBRARY_PATH")
                if (prevPreload != null) env["LD_PRELOAD"] = prevPreload else env.remove("LD_PRELOAD")
                if (prevReal != null) env["OC_REAL_EXE"] = prevReal else env.remove("OC_REAL_EXE")
            }
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
            writePackageEnvironment(context)
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
         * Install the terminal entry points.
         *
         * `$PREFIX/bin/opencode` is a fixed launcher that always runs the installed build through
         * the glibc loader with liboc-pathmap.so preloaded when available. The downloaded binary
         * itself stays unmodified, so updating or downgrading is only a matter of changing the
         * symlink in [LINK_DIR]; `$PREFIX/bin/pkg` points at the Kotlin/Native bridge. Files left
         * behind by older builds are replaced in place.
         */
        private fun installEntrypoints() {
            installNativeTools()
            val launcher = installLauncher()
            val bridge = installBridge()

            // Replace a stale symlink (older builds pointed bin/opencode at the versioned build).
            deleteIfSymlink(File(OPENCODE_BIN_PATH))
            if (launcher || File(LINK_PATH).exists() || File(LAUNCHER_PATH).isFile) {
                installLauncherScript(File(OPENCODE_BIN_PATH))
            } else if (!File(OPENCODE_BIN_PATH).isFile) {
                Log.w(LOG_TAG, "The opencode launcher is not available yet.")
            }

            if (bridge) {
                replaceSymlink(File(PKG_PATH), BRIDGE_PATH)
            } else {
                // Keep a regular file of an older build, it still works until the next successful
                // extraction. The same holds for a symlink whose bridge is still executable; only
                // a link that would already fail is removed.
                deleteIfBrokenSymlink(File(PKG_PATH))
            }
            // Older builds also shipped an `oc-install` entry point. The app updates from its own
            // settings now, so remove the stale symlink instead of leaving a command that starts
            // the activity with an intent the app no longer handles.
            deleteIfSymlink(File("$PREFIX/bin/oc-install"))
        }

        /**
         * Write a bionic shell launcher for `opencode`. It prefers the fixed native launcher
         * binary (extracted from the APK) and otherwise falls back to an inline shell
         * implementation that runs the installed build through the glibc loader with path-map
         * preload. In all cases the managed binary is resolved from [LINK_PATH] at run time, so it
         * survives updates and downgrades without per-binary patching.
         */
        private fun installLauncherScript(file: File) {
            val script = buildString {
                append("#!/system/bin/sh\n")
                append("# Managed by the OpenCode for Android app. Runs the installed OpenCode build\n")
                append("# through the glibc loader with path translation; safe to update/downgrade.\n")
                append("set -e\n")
                append("LAUNCHER=\"").append(LAUNCHER_PATH).append("\"\n")
                append("if [ -x \"\$LAUNCHER\" ]; then exec \"\$LAUNCHER\" \"\$@\"; fi\n")
                append("PREFIX=\"\${PREFIX:-").append(PREFIX).append("}\"\n")
                append("HOME_DIR=\"\${HOME:-").append(HOME).append("}\"\n")
                append("LINK=\"").append(LINK_PATH).append("\"\n")
                append("LOADER=\"").append(nativeGlibcLoader(context).orEmpty()).append("\"\n")
                append("if [ -z \"\$LOADER\" ]; then for f in \"\$PREFIX\"/glibc/lib/ld-linux*.so.*; do if [ -x \"\$f\" ]; then LOADER=\"\$f\"; break; fi; done; fi\n")
                append("if [ -z \"\$LOADER\" ]; then echo \"opencode: glibc loader not found in \$PREFIX/glibc/lib\" >&2; exit 127; fi\n")
                append("REAL=\"\$(/system/bin/readlink -f \"\$LINK\" 2>/dev/null || true)\"\n")
                append("if [ -z \"\$REAL\" ] || [ ! -f \"\$REAL\" ]; then echo \"opencode: not installed (\$LINK missing). Open the app to install it.\" >&2; exit 127; fi\n")
                append("export OC_REAL_EXE=\"\$REAL\"\n")
                append("export OC_PREFIX=\"\${OC_PREFIX:-\$PREFIX}\"\n")
                append("export OC_USER_HOME=\"\${OC_USER_HOME:-\$HOME_DIR}\"\n")
                append("if [ -f \"\$PREFIX/lib/liboc-pathmap.so\" ]; then\n")
                append("  exec \"\$LOADER\" --preload \"\$PREFIX/lib/liboc-pathmap.so\" --library-path \"\$PREFIX/lib:\$PREFIX/glibc/lib\" \"\$REAL\" \"\$@\"\n")
                append("else\n")
                append("  exec \"\$LOADER\" --library-path \"\$PREFIX/lib:\$PREFIX/glibc/lib\" \"\$REAL\" \"\$@\"\n")
                append("fi\n")
            }
            if (readTextFileQuietly(file) == script && file.canExecute()) return
            // If it is currently a symlink, replace it atomically with a regular script.
            deleteIfSymlink(file)
            writeTextFileQuietly(file, script)
            try {
                Os.chmod(file.absolutePath, MODE_755)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not make $file executable", e)
            }
        }

        /**
         * Extract the fixed OpenCode launcher of the current ABI from the APK. It is a bionic
         * executable (not the downloaded glibc binary), so it keeps working across every
         * update/downgrade of OpenCode.
         */
        private fun installLauncher(): Boolean {
            val abi = when (getOpenCodeArch()) {
                "arm64" -> "arm64-v8a"
                "x64" -> "x86_64"
                else -> return false
            }
            val target = File(LAUNCHER_PATH)
            val marker = File(LAUNCHER_MARKER_PATH)
            val stamp = "$LAUNCHER_VERSION ${BuildConfig.VERSION_CODE} $abi ${apkStamp()}"
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
                assets.open("native/$abi/oc-launcher").use { input ->
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
                Log.i(LOG_TAG, "Installed the OpenCode launcher for $abi")
                return true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not install the OpenCode launcher for $abi", e)
                return isExecutable(target)
            } finally {
                temp?.let { deleteQuietly(it) }
            }
        }

        /** Delete [file] when it is a symlink, so a stale entry point of an older build disappears. */
        private fun deleteIfSymlink(file: File) {
            try {
                if (OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)) deleteQuietly(file)
            } catch (e: Exception) {
                // The file does not exist.
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

        /**
         * Extract the native helpers of the current ABI: `liboc-pathmap.so`, which translates the
         * Termux roots for glibc programs, and `oc-gopatch`, which does the same for static Go
         * programs. Both are optional: an APK built without the native job keeps starting
         * downloaded programs unmodified, and a helper of an older APK stays in place.
         */
        private fun installNativeTools(): Boolean = OpenCodeBootstrap.installNativeTools(context)

        /** Stamp of the installed APK, it changes on every update even with the same version code. */
        private fun apkStamp(): String = OpenCodeBootstrap.apkStamp(context)

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
            try {
                // The link is already correct: replacing it would create a new inode on every
                // start for no reason.
                if (Os.readlink(link.absolutePath) == target) return
            } catch (e: Exception) {
                // Not a symlink yet (or it vanished), replace it below.
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
            // The trust store has to exist before the first download (the version lookup and the
            // OpenCode tarball use the platform TLS stack, but git/gh/curl do not).
            ensureCaBundle(context)
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
            // Maintenance runs silently on every start; it must never post a result notification.
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
    private const val MODE_644 = 420 // 0644
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

    /* -------------------------------------------------------------- APK native libraries */

    private const val NATIVE_EXEC_LIB = "liboc_exec.so"
    private const val NATIVE_GLIBC_LOADER_LIB = "liboc_ld.so"
    private const val NATIVE_GOPATCH_LIB = "liboc_gopatch.so"
    private const val NATIVE_PTY_LIB = "liboc_pty.so"
    private const val NATIVE_OCRUN_LIB = "liboc_ocrun.so"
    private const val NATIVE_RIPGREP_LIB = "liboc_rg.so"

    /** @return `applicationInfo.nativeLibraryDir`, where files stay executable with targetSdk 36. */
    fun nativeLibraryDir(context: Context? = appContext): String? = try {
        (context ?: appContext)?.applicationInfo?.nativeLibraryDir
            ?.takeIf { it.isNotEmpty() && File(it).isDirectory }
    } catch (e: Exception) {
        null
    }

    private fun nativeFile(name: String, context: Context? = appContext): File? =
        nativeLibraryDir(context)?.let { File(it, name).takeIf { file -> file.isFile } }

    /** Bionic W^X exec shim, always loaded from the APK and never from the writable prefix. */
    fun execShim(context: Context? = appContext): String? =
        nativeFile(NATIVE_EXEC_LIB, context)?.absolutePath

    /** Relocated glibc loader packaged in the APK (the prefix copy is no longer executable). */
    fun nativeGlibcLoader(context: Context? = appContext): String? =
        nativeFile(NATIVE_GLIBC_LOADER_LIB, context)?.takeIf { it.canExecute() }?.absolutePath

    /** Static Go patcher packaged in the APK; ET_EXEC cannot go through Android's linker. */
    fun nativeGopatch(context: Context? = appContext): String? =
        nativeFile(NATIVE_GOPATCH_LIB, context)?.takeIf { it.canExecute() }?.absolutePath

    /** Bionic PIE in the APK that exports the shim and calls [nativeGopatch]. */
    fun nativeOcRun(context: Context? = appContext): String? =
        nativeFile(NATIVE_OCRUN_LIB, context)?.takeIf { it.canExecute() }?.absolutePath

    /** Bionic terminal daemon packaged in the APK (spawned directly by the glibc server). */
    fun nativePty(context: Context? = appContext): String? =
        nativeFile(NATIVE_PTY_LIB, context)?.takeIf { it.canExecute() }?.absolutePath

    /** Bionic PIE in the APK that starts the prefix ripgrep through the system linker. */
    fun nativeRipgrep(context: Context? = appContext): String? =
        nativeFile(NATIVE_RIPGREP_LIB, context)?.takeIf { it.canExecute() }?.absolutePath

    /**
     * Wrap a command that Java starts directly (ProcessBuilder inherits zygote's missing shim).
     * A dynamic bionic ELF in app data is loaded by the system linker; a glibc ELF is loaded by
     * the relocated loader from nativeLibraryDir. Static ET_EXEC files cannot use either and
     * must be called through the APK's `oc-run` wrapper.
     */
    fun wrapExecutable(command: Array<String>): Array<String> {
        val executable = command.firstOrNull()?.takeIf { it.startsWith("/") } ?: return command
        val file = File(executable)
        if (!file.isFile || !file.absolutePath.startsWith(DATA_DIR)) return command
        val rest = command.drop(1)
        return when (inspectExecutable(file)) {
            ExecKind.BIONIC -> (listOf(SYSTEM_LINKER, executable) + rest).toTypedArray()
            ExecKind.GLIBC -> {
                val loader = nativeGlibcLoader() ?: return command
                val args = ArrayList<String>()
                args.add(loader)
                args.add("--library-path")
                args.add(GLIBC_LIB_DIR)
                val pathmap = File(PATHMAP_PATH)
                if (pathmap.isFile) {
                    args.add("--preload")
                    args.add(pathmap.absolutePath)
                }
                args.add(executable)
                args.addAll(rest)
                args.toTypedArray()
            }
            // A static ET_EXEC or a shebang script in app data cannot be exec'd at all.
            // The APK's oc-run wrapper calls the patcher and exports the shim for children.
            ExecKind.STATIC, ExecKind.NONE ->
                nativeOcRun()?.let { (listOf(it, executable) + rest).toTypedArray() } ?: command
        }
    }

    private enum class ExecKind { NONE, BIONIC, GLIBC, STATIC }

    private const val SYSTEM_LINKER = "/system/bin/linker64"

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun inspectExecutable(file: File): ExecKind {
        try {
            java.nio.channels.FileChannel.open(
                file.toPath(),
                java.nio.file.StandardOpenOption.READ,
            ).use { channel ->
                val header = ByteArray(64)
                if (!readFully(channel, header, 0)) return ExecKind.NONE
                if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
                    header[4] != 2.toByte()
                ) return ExecKind.NONE

                val eType = u16(header, 16)
                val phoff = u64(header, 32)
                val phentsize = u16(header, 54)
                val phnum = u16(header, 56)
                if (phoff > 0 && phentsize >= 56 && phnum > 0 && phnum < 1024) {
                    val entry = ByteArray(phentsize)
                    for (i in 0 until phnum) {
                        if (!readFully(channel, entry, phoff + i.toLong() * phentsize)) {
                            return ExecKind.NONE
                        }
                        if (u32(entry, 0) != 3L) continue // PT_INTERP
                        val offset = u64(entry, 8)
                        val size = u64(entry, 32)
                        if (size <= 0 || size > 512) break
                        val interp = ByteArray(size.toInt())
                        if (!readFully(channel, interp, offset)) break
                        val text = String(interp, Charsets.UTF_8)
                        return if (text.contains("ld-linux") || text.contains("glibc")) {
                            ExecKind.GLIBC
                        } else {
                            ExecKind.BIONIC
                        }
                    }
                }
                return if (eType == 3) ExecKind.BIONIC else ExecKind.STATIC // ET_DYN vs ET_EXEC
            }
        } catch (e: Exception) {
            return ExecKind.NONE
        }
    }

    private fun readFully(
        channel: java.nio.channels.FileChannel,
        bytes: ByteArray,
        position: Long,
    ): Boolean {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        var offset = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, offset)
            if (read <= 0) return false
            offset += read.toLong()
        }
        return true
    }

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

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
     * The OpenCode binary is always started through the glibc loader with path translation
     * (fixed launcher / service exec), so it must not be treated as a self-contained native
     * executable that can be run without the loader and preload environment.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isNativeBinary(binary: File?): Boolean = false

    /* -------------------------------------------------------------- native helpers */

    /** Replace [target] with the asset [name], atomically and with the given mode. */
    private fun extractAsset(assets: android.content.res.AssetManager, name: String, target: File, mode: Int) {
        val parent = target.parentFile ?: throw IOException("No parent directory for $target")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create $parent")
        val temp = File.createTempFile(".${target.name}", ".tmp", parent)
        try {
            assets.open(name).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            Os.chmod(temp.absolutePath, mode)
            if (!temp.renameTo(target)) {
                deleteQuietly(target)
                if (!temp.renameTo(target)) throw IOException("Could not replace $target")
            }
        } finally {
            deleteQuietly(temp)
        }
    }

    /**
     * Install `oc-run`, the entry point for programs that were downloaded later. It uses the
     * patcher when this APK shipped one and starts the command unchanged otherwise.
     */
    private fun installOcRun(context: Context? = appContext) {
        val file = File(OCRUN_PATH)
        // targetSdk 36 cannot exec a script that lives in app data. The APK ships a tiny
        // bionic PIE in nativeLibraryDir: it exports the shim and calls the static Go
        // patcher, both siblings in the same (executable) directory.
        val native = nativeOcRun(context)
        if (native != null) {
            replaceManagedSymlink(file, native)
            if (file.canExecute()) return
        }

        val gopatch = nativeGopatch(context) ?: "$PREFIX/libexec/oc-gopatch"
        val script = buildString {
            append("#!/system/bin/sh\n")
            append("# Managed by the OpenCode for Android app.\n")
            append("# Start a downloaded program with the private prefix of this app.\n")
            append("prefix=\"\${PREFIX:-").append(PREFIX).append("}\"\n")
            append("helper=\"").append(gopatch).append("\"\n")
            append("if [ -x \"\$helper\" ]; then\n")
            append("  OC_PREFIX=\"\$prefix\" OC_USER_HOME=\"\${OC_USER_HOME:-").append(HOME).append("}\"")
            append(" exec \"\$helper\" run -- \"\$@\"\n")
            append("fi\n")
            append("helper=\"\$prefix/libexec/oc-gopatch\"\n")
            append("if [ -x \"\$helper\" ]; then\n")
            append("  OC_PREFIX=\"\$prefix\" OC_USER_HOME=\"\${OC_USER_HOME:-").append(HOME).append("}\"")
            append(" exec \"\$helper\" run -- \"\$@\"\n")
            append("fi\n")
            append("exec \"\$@\"\n")
        }
        deleteIfManagedSymlink(file)
        if (readTextFileQuietly(file) == script && file.canExecute()) return
        writeTextFileQuietly(file, script)
        try {
            Os.chmod(file.absolutePath, MODE_700)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not make $OCRUN_PATH executable", e)
        }
    }

    /** Replace a managed symlink atomically, leaving an already correct link untouched. */
    private fun replaceManagedSymlink(link: File, target: String) {
        try {
            if (Os.readlink(link.absolutePath) == target) return
        } catch (e: Exception) {
            // Not a symlink yet.
        }
        deleteQuietly(link)
        val parent = link.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) return
        val temporary = File(parent, ".${link.name}.new")
        deleteQuietly(temporary)
        try {
            Os.symlink(target, temporary.absolutePath)
            if (!temporary.renameTo(link)) deleteQuietly(temporary)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not link $link to $target", e)
            deleteQuietly(temporary)
        }
    }

    private fun deleteIfManagedSymlink(file: File) {
        try {
            if (OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)) deleteQuietly(file)
        } catch (e: Exception) {
            // Nothing there.
        }
    }

    /** Stamp of the installed APK, it changes on every update even with the same version code. */
    private fun apkStamp(context: Context?): String {
        val apk = try {
            (context ?: appContext)?.applicationInfo?.sourceDir?.let { File(it) }
        } catch (e: Exception) {
            null
        }
        return if (apk != null && apk.isFile) "${apk.length()}-${apk.lastModified()}" else ""
    }

    /**
     * Extract the native helpers of the current ABI: `liboc-pathmap.so`, which translates the
     * Termux roots for glibc programs, and `oc-gopatch`, which does the same for static Go
     * programs. Both are optional: an APK built without the native job keeps starting downloaded
     * programs unmodified, and a helper of an older APK stays in place.
     */
    fun installNativeTools(context: Context? = appContext): Boolean {
        installOcRun(context)
        val abi = when (getOpenCodeArch()) {
            "arm64" -> "arm64-v8a"
            "x64" -> "x86_64"
            else -> return false
        }
        val gopatch = File(GOPATCH_PATH)
        val pathmap = File(PATHMAP_PATH)
        val nativeGopatch = nativeGopatch(context)
        val installed = (nativeGopatch != null || isExecutable(gopatch)) && pathmap.isFile
        val stamp = "2 ${BuildConfig.VERSION_CODE} $abi ${apkStamp(context)}"
        val state = if (installed) "ok" else "absent"
        val marker = File(NATIVE_TOOLS_MARKER_PATH)
        if (readTrimmed(marker) == "$stamp $state") return installed

        val assets = (context ?: appContext)?.assets
        try {
            if (assets == null) throw IOException("No app assets")
            // The APK copy is preferred: a static ET_EXEC in app data cannot be executed
            // any more with targetSdk 36, even through the W^X linker trampoline.
            if (nativeGopatch == null) {
                extractAsset(assets, "native/$abi/oc-gopatch", gopatch, MODE_700)
            }
            extractAsset(assets, "native/$abi/liboc-pathmap.so", pathmap, MODE_600)
        } catch (e: Exception) {
            Log.i(LOG_TAG, "This APK has no native helpers for $abi; oc-run starts programs unpatched", e)
        }
        val nowInstalled = (nativeGopatch(context) != null || isExecutable(gopatch)) && pathmap.isFile
        writeTextFileQuietly(marker, "$stamp ${if (nowInstalled) "ok" else "absent"}\n")
        return nowInstalled
    }

    /* -------------------------------------------------------------- terminal helper */

    private val sPtyLock = Any()

    /** The prepared terminal helper, without doing any work, or null when it is missing. */
    fun terminalRuntimePath(context: Context? = appContext): String? {
        nativePty(context)?.let { return it }
        val binary = File(PTY_BIN_PATH)
        return if (binary.isFile && binary.canExecute()) binary.absolutePath else null
    }

    /**
     * Make sure the app's pure-C `opencode-pty` helper exists and can be spawned.
     *
     * The helper is packaged in nativeLibraryDir (apk_data_file, executable with targetSdk 36)
     * and comes from an older APK asset only as a fallback. It speaks the same protocol as the
     * CLI's embedded helper, so the OpenCode server can keep using `OPENCODE_PTY_BIN` unchanged.
     * Unlike the carved glibc binary it does not depend on the installed CLI version.
     *
     * @return the absolute path of the helper, or null when it could not be prepared.
     */
    fun ensureTerminalRuntime(context: Context? = appContext): String? =
        synchronized(sPtyLock) { prepareTerminalRuntime(context) }

    private fun prepareTerminalRuntime(context: Context? = appContext): String? {
        // The daemon creates its unix socket below this directory and dies with EACCES when it is
        // missing. It cannot live in $PREFIX/tmp either: `pkg install` empties it.
        val socketDir = File(PTY_SOCKET_DIR)
        if (!socketDir.isDirectory && !socketDir.mkdirs()) {
            Log.w(LOG_TAG, "Could not create the terminal socket directory $socketDir")
        }

        val target = File(PTY_BIN_PATH)
        val abi = when (getOpenCodeArch()) {
            "arm64" -> "arm64-v8a"
            "x64" -> "x86_64"
            else -> {
                Log.w(LOG_TAG, "Cannot prepare the terminal helper for an unknown ABI")
                deleteQuietly(target)
                return null
            }
        }
        val installed = terminalRuntimePath(context)
        val stamp = "3 ${BuildConfig.VERSION_CODE} $abi ${apkStamp(context)}"
        if (installed != null && readTrimmed(File(PTY_MARKER_PATH)) == stamp) return installed

        // The server spawns this daemon itself. A copy in nativeLibraryDir is executable even
        // with targetSdk 36; the daemon re-execs itself once with LD_PRELOAD=liboc_exec.so so
        // the shells it starts can run prefix programs through the W^X shim.
        val native = nativePty(context)
        if (native != null) {
            writeTextFileQuietly(File(PTY_MARKER_PATH), "$stamp\n")
            return native
        }

        val assets = (context ?: appContext)?.assets
        if (assets == null) {
            Log.w(LOG_TAG, "Cannot prepare the terminal helper: no app assets")
            deleteQuietly(target)
            return null
        }

        val staged = File("$OC_HOME/bin/.opencode-pty.new")
        deleteQuietly(staged)
        val started = SystemClock.uptimeMillis()
        try {
            extractAsset(assets, "native/$abi/opencode-pty", staged, MODE_700)
            val verify = runCommand(listOf(staged.absolutePath, "--version"), PTY_VERIFY_TIMEOUT_MS)
            if (verify.first != 0 || !verify.second.contains("opencode-pty")) {
                Log.w(LOG_TAG, "The opencode-pty helper does not start: ${verify.second.takeLast(300)}")
                deleteQuietly(target)
                return null
            }
            if (!staged.renameTo(target)) {
                deleteQuietly(target)
                if (!staged.renameTo(target)) {
                    Log.w(LOG_TAG, "Could not install $PTY_BIN_PATH")
                    return null
                }
            }
            writeTextFileQuietly(File(PTY_MARKER_PATH), "$stamp\n")
            Log.i(LOG_TAG, "Prepared the C opencode-pty helper in ${SystemClock.uptimeMillis() - started} ms")
            return target.absolutePath
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not install the C opencode-pty helper", e)
            deleteQuietly(target)
            return null
        } finally {
            deleteQuietly(staged)
        }
    }

    /**
     * Make sure the built-in `grep` and `glob` tools of OpenCode can run.
     *
     * OpenCode resolves ripgrep in this order: `rg` from `PATH`, then its cached download in
     * `~/.cache/opencode/bin/rg`, then a fresh download. The cached file is a glibc build whose
     * interpreter does not exist on Android, so the tool fails with "ripgrep execution failed".
     * The prefix now ships the bionic `ripgrep` package, but OpenCode may still pick the broken
     * cache entry (and it rewrites the entry after a cache wipe), so the app puts a wrapper at
     * that path on every maintenance pass.
     *
     * The wrapper sets `LD_LIBRARY_PATH` itself: the Termux ripgrep needs `libpcre2-8.so` from
     * the prefix and must also work when OpenCode starts it without the environment of the
     * terminal.
     */
    private fun ensureRipgrepRuntime(context: Context? = appContext) {
        val binary = File("$PREFIX/bin/rg")
        if (!isExecutable(binary)) {
            Log.w(LOG_TAG, "Cannot prepare the ripgrep cache: $binary is not executable")
            return
        }

        val target = File(RIPGREP_CACHE_PATH)
        // OpenCode (a glibc process) execs this cache entry itself; with targetSdk 36 a
        // script below app data is denied, so point it at the APK's executable wrapper.
        val native = nativeRipgrep(context)
        if (native != null) {
            replaceManagedSymlink(target, native)
            if (target.canExecute()) return
        }
        val wrapper = buildString {
            append("#!/system/bin/sh\n")
            append("# Generated by OpenCode for Android. OpenCode downloads a glibc ripgrep that\n")
            append("# Android cannot execute; use the bionic ripgrep of the app prefix instead.\n")
            append("LD_LIBRARY_PATH=\"").append(PREFIX)
            append("/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"\n")
            append("export LD_LIBRARY_PATH\n")
            append("exec \"").append(binary.absolutePath).append("\" \"\$@\"\n")
        }

        if (target.isFile && target.canExecute() && readTextFileQuietly(target) == wrapper) return

        try {
            OpenCodeFiles.writeTextAtomically(target, wrapper)
            Os.chmod(target.absolutePath, MODE_755)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not install the ripgrep wrapper at $target", e)
            return
        }

        val verify = runCommand(listOf(target.absolutePath, "--version"), RIPGREP_VERIFY_TIMEOUT_MS)
        if (verify.first != 0 || !verify.second.contains("ripgrep")) {
            Log.w(LOG_TAG, "The ripgrep wrapper does not start: ${verify.second.takeLast(300)}")
        } else {
            Log.i(LOG_TAG, "Prepared the ripgrep cache at $target")
        }
    }


    /**
     * Run a short command with a timeout and return its exit code and combined output.
     *
     * The environment comes from the same contract as the terminal, minus the loader variables:
     * these helpers are bionic (or carry their own wrapper), so they must not inherit the prefix's
     * `LD_LIBRARY_PATH`. Only TMPDIR differs: helper staging stays below [TMP_DIR].
     */
    private fun runCommand(cmd: List<String>, timeoutMs: Long): Pair<Int, String> {
        val output = StringBuilder()
        var process: Process? = null
        try {
            val builder = ProcessBuilder(*wrapExecutable(cmd.toTypedArray()))
            builder.directory(File(HOME))
            builder.redirectErrorStream(true)
            val commandEnv = RuntimeEnv.build(commandInputs()).bootstrapEnvironment(
                mapOf("TMPDIR" to TMP_DIR),
            )
            builder.environment().apply {
                clear()
                putAll(commandEnv)
            }
            val started = builder.start()
            process = started
            val reader = Thread({
                try {
                    BufferedReader(InputStreamReader(started.inputStream)).use { input ->
                        while (true) {
                            val line = input.readLine() ?: break
                            synchronized(output) {
                                if (output.length < MAX_OUTPUT_CHARS) output.append(line).append('\n')
                            }
                        }
                    }
                } catch (e: Exception) {
                    // The process was killed, ignore.
                }
            }, "opencode-pty-command")
            reader.isDaemon = true
            reader.start()

            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (true) {
                try {
                    val exit = started.exitValue()
                    return Pair(exit, synchronized(output) { output.toString() })
                } catch (e: IllegalThreadStateException) {
                    if (SystemClock.uptimeMillis() > deadline) {
                        started.destroy()
                        return Pair(-1, synchronized(output) { output.toString() })
                    }
                    try {
                        Thread.sleep(PROCESS_POLL_INTERVAL_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return Pair(-1, synchronized(output) { output.toString() })
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not run $cmd", e)
            return Pair(-1, synchronized(output) { output.toString() })
        } finally {
            process?.let { if (it.isAlive) it.destroy() }
        }
    }


    /** Entries of the sandbox root that belong to Android, they must never become an alias. */
    private val SANDBOX_RESERVED_ENTRIES = setOf(
        "cache", "code_cache", "databases", "files", "no_backup", "shared_prefs",
    )

    /**
     * Create the same-length aliases the relocated binaries look for. The Termux packages contain
     * `/data/data/com.termux/files/usr/...` in their rodata; the app rewrites that prefix to
     * [DATA_DIR] (both are 31 characters), so the directory part of every compiled-in path lands at
     * the sandbox root: `…/files/usr/bin/bash` becomes `<dataDir>/bin/bash`. [DATA_DIR] is exactly
     * as long as the Termux prefix, so a single alias for the prefix is impossible: every top level
     * directory of the prefix needs its own alias.
     */
    private fun ensurePathAliases() {
        val prefix = File(PREFIX)
        val dirs = prefix.listFiles()
        if (dirs == null) {
            // Keep the two aliases the loader needs even when the listing fails.
            linkAlias(File(GLIBC_ALIAS_DIR), File(prefix, "glibc"))
            linkAlias(File(ETC_ALIAS_DIR), File(prefix, "etc"))
            return
        }
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val name = dir.name
            if (name in SANDBOX_RESERVED_ENTRIES || name.startsWith("app_")) continue
            linkAlias(File(DATA_DIR, name), dir)
        }
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
        if (newPrefix.size > oldPrefix.size) {
            // The build enforces equal lengths (see app/build.gradle.kts); a longer sandbox path
            // would have to move every offset of the binary, so it is refused instead.
            Log.e(
                LOG_TAG,
                "Cannot relocate the glibc loader: $DATA_DIR is longer than $PREFIX_TERMUX",
            )
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
        val libDir = File(GLIBC_LIB_DIR)
        val libc = File(libDir, "libc.so.6")
        val marker = File(GLIBC_RELOCATED_PATH)

        // The stamp covers the loader as well as the shared objects. A matching marker therefore
        // proves that both are already relocated, so the loader does not have to be read (and
        // scanned for the Termux prefix) on every start.
        val current = if (libc.isFile) glibcStamp(libc, libDir, loaderPath) else null
        val stored = readTrimmed(marker)
        if (current != null && current == stored) {
            return loaderPath != null && File(loaderPath).isFile
        }
        // Markers of older builds did not include the loader. They were still written only after
        // the loader and every object had been rewritten, so upgrade them in place instead of
        // scanning the whole glibc runtime again on the first start after an app update.
        if (current != null) {
            val legacy = "$DATA_DIR|${libc.length()}|${libc.lastModified()}|${libDir.lastModified()}"
            if (stored == legacy) {
                writeTextFileQuietly(marker, current + "\n")
                return loaderPath != null && File(loaderPath).isFile
            }
        }

        val loaderOk = loaderPath != null && relocateLoader(loaderPath)
        if (!libc.isFile) return loaderOk

        // A package update replaces libc.so.6 and invalidates the marker, everything else is a
        // no-op then (files without the prefix are not rewritten). The directory time is part of
        // the stamp too: an update that only replaces another object of the runtime would
        // otherwise leave that object with the paths of the Termux app.
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
        // Stamped after the rewrite: replacing an object updates the time of the directory, and the
        // marker must describe the runtime as it is now, not as it was before.
        writeTextFileQuietly(marker, glibcStamp(libc, libDir, loaderPath) + "\n")
        return loaderOk
    }

    /**
     * Fingerprint of the glibc runtime: sandbox path, libc.so.6, the directory that holds it and
     * the loader whose interpreter path is rewritten too.
     */
    private fun glibcStamp(libc: File, libDir: File, loaderPath: String?): String {
        val loader = loaderPath?.let { File(it) }
        return "$DATA_DIR|${libc.length()}|${libc.lastModified()}|${libDir.lastModified()}|" +
            "${loader?.length() ?: 0}|${loader?.lastModified() ?: 0}"
    }

    /**
     * Replace strings inside the C strings of a binary in place. Used for the login shell paths of
     * bash, which are compiled against the com.termux prefix and are unreadable from this app.
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
                if (newBytes.size > oldBytes.size) {
                    Log.w(
                        LOG_TAG,
                        "Cannot rewrite ${replacements[i]}: the replacement is longer",
                    )
                } else {
                    total += ElfStrings.replace(data, oldBytes, newBytes)
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
    private fun replaceBytes(data: ByteArray, oldBytes: ByteArray, newBytes: ByteArray): Int =
        ElfStrings.replace(data, oldBytes, newBytes)

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

    /**
     * Write [content] only when the file does not have it yet. The maintenance pass runs on every
     * start, so rewriting unchanged shell files would touch the prefix and wake up watchers for
     * nothing.
     */
    private fun writeTextFileIfChanged(file: File, content: String) {
        if (file.isFile && readTextFileQuietly(file) == content) return
        writeTextFileQuietly(file, content)
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

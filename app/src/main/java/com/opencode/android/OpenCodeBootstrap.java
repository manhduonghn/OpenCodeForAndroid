package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bootstraps the official OpenCode CLI natively inside Android and keeps it runnable.
 *
 * <p>The official OpenCode CLI is only published as a <b>glibc</b> ELF binary, while Android only
 * ships <b>bionic</b>. The binary therefore gets the glibc loader as its ELF interpreter and runs
 * directly, the way a normal Linux program does:</p>
 *
 * <pre>
 *   download the official npm tarball @opencode/cli-linux-&lt;arch&gt;
 *     -&gt; unpack package/bin/opencode in Java (no tar binary needed)
 *     -&gt; relocate the loader and set the binary's PT_INTERP to it
 *     -&gt; start the binary directly: /proc/self/exe is the binary, so OpenCode can spawn
 *        its own background `serve` process (that was broken with an explicit loader)
 *     -&gt; fall back to "$PREFIX/glibc/lib/ld-linux-*.so.* --library-path" if the patch fails
 * </pre>
 *
 * <p>Everything is done from this single class so that the installation keeps its progress,
 * survives the app being killed mid download and is able to repair itself afterwards. No root, no
 * proot and no chroot is needed.</p>
 */
public final class OpenCodeBootstrap {

    private static final String LOG_TAG = "OpenCodeBootstrap";

    /* ==========================================================================================
     * Public API
     * ========================================================================================== */

    /** Intent action asking the app to (re)install the OpenCode runtime. */
    public static final String ACTION_FORCE_UPDATE = "com.opencode.android.opencode.FORCE_UPDATE";

    /** The command the user types inside the terminal to start the OpenCode TUI. */
    public static final String OPENCODE_COMMAND = "opencode";

    /**
     * A listener that is notified every time the state of the installation changes. The callback
     * is always invoked on the UI thread.
     */
    public interface InstallListener {
        void onOpenCodeInstallStateChanged(OpenCodeInstallState state);
    }

    /* ==========================================================================================
     * Paths
     * ========================================================================================== */

    /** App-private Termux-like prefix, eg. {@code /data/data/com.opencode.android/files/usr}. */
    public static final String PREFIX = "/data/data/com.opencode.android/files/usr";

    /**
     * Root of the sandbox ({@code context.getApplicationInfo().dataDir}). It has exactly the same
     * length as the prefix that is baked into the Termux packages
     * ({@code /data/data/com.termux/files/usr}), which makes it possible to relocate compiled-in
     * paths in place: every byte is replaced one to one, so no offset moves.
     */
    public static final String DATA_DIR = "/data/data/com.opencode.android";

    /** Alias of {@link #PREFIX}/glibc, target of the relocated loader search path. */
    public static final String GLIBC_ALIAS_DIR = DATA_DIR + "/glibc";
    /** Alias of {@link #PREFIX}/etc, target of the relocated login profile of bash. */
    public static final String ETC_ALIAS_DIR = DATA_DIR + "/etc";

    /** App-private Termux-like home, eg. {@code /data/data/com.opencode.android/files/home}. */
    public static final String HOME = "/data/data/com.opencode.android/files/home";

    /** State directory of the installer. */
    public static final String OC_HOME = HOME + "/.opencode";
    /** Directory holding the installed OpenCode builds. */
    public static final String INSTALL_DIR = OC_HOME + "/installed";
    /** Directory holding the {@code opencode} symlink. */
    public static final String LINK_DIR = OC_HOME + "/bin";
    /** Symlink pointing to the currently installed binary. */
    public static final String LINK_PATH = LINK_DIR + "/opencode";
    /** Contains the version that is currently installed. */
    public static final String READY_MARKER_PATH = OC_HOME + "/.native-ready";
    /**
     * Fingerprint ("&lt;size&gt; &lt;mtime&gt;") of the installed binary whose ELF interpreter was
     * pointed at the glibc loader. Present only when the binary really starts directly.
     */
    public static final String NATIVE_INTERP_MARKER_PATH = OC_HOME + "/.native-interp";
    /** Remembers a failed attempt, so a 240 MB copy is not retried on every app start. */
    private static final String NATIVE_REPAIR_MARKER_PATH = OC_HOME + "/.native-repair-tried";
    /** Full log of every installer run. */
    public static final String SETUP_LOG_PATH = OC_HOME + "/setup.log";
    /** Human readable report of the last failure. */
    public static final String ERROR_DETAIL_PATH = OC_HOME + "/last-error.txt";
    /** Snapshot of the environment, written on every run. */
    public static final String DIAGNOSTICS_PATH = OC_HOME + "/diagnostics.txt";
    /** Directory used for the temporary {@code sources.list} of the fallback mirrors. */
    public static final String APT_TMP_DIR = OC_HOME + "/apt";
    /** Scratch directory used while downloading and patching. */
    public static final String TMP_DIR = OC_HOME + "/tmp";
    /** Directory holding the glibc loader shipped by {@code glibc-runner}. */
    public static final String GLIBC_LIB_DIR = PREFIX + "/glibc/lib";
    /** Cache directory of apt, the downloaded packages are put there. */
    public static final String APT_ARCHIVES_DIR = PREFIX + "/var/cache/apt/archives";
    /** Launcher script, {@code opencode}. */
    public static final String OPENCODE_BIN_PATH = PREFIX + "/bin/opencode";
    /** Launcher script, {@code oc-install}. */
    public static final String OC_INSTALL_PATH = PREFIX + "/bin/oc-install";
    /** Explains that this prefix has no apt/dpkg, so {@code pkg} cannot be used. */
    public static final String PKG_PATH = PREFIX + "/bin/pkg";

    /** Environment snippet that the shell startup files of the runtime source. */
    public static final String ENV_SCRIPT_PATH = PREFIX + "/etc/opencode-env.sh";
    /** HTTP Basic user name the {@code opencode serve} of the 2.x line expects. */
    public static final String SERVER_USER = "opencode";
    /** Password of the server, exported by the launchers and used by the WebView. */
    public static final String SERVER_PASSWORD_PATH = OC_HOME + "/server-password";
    /** Installer script of older versions, removed on sight. */
    public static final String LEGACY_SETUP_SCRIPT_PATH = OC_HOME + "/setup.sh";
    /** Directory holding the second stage script of the Termux bootstrap. */
    public static final String SECOND_STAGE_DIR = PREFIX + "/etc/termux/termux-bootstrap/second-stage";
    /** Second stage script of the Termux bootstrap. */
    public static final String SECOND_STAGE_SCRIPT_PATH = SECOND_STAGE_DIR + "/termux-bootstrap-second-stage.sh";
    /** Second stage lock file of the Termux bootstrap. */
    public static final String SECOND_STAGE_LOCK_PATH = SECOND_STAGE_DIR + "/termux-bootstrap-second-stage.sh.lock";
    /** DNS configuration of Termux. */
    public static final String RESOLV_CONF_PATH = PREFIX + "/etc/resolv.conf";
    /** APT configuration of the user. */
    public static final String APT_SOURCES_LIST_PATH = PREFIX + "/etc/apt/sources.list";
    /** APT configuration fragments of the user. */
    public static final String APT_SOURCEPARTS_DIR = PREFIX + "/etc/apt/sources.list.d";

    /* ==========================================================================================
     * Constants
     * ========================================================================================== */

    /** Bumped whenever the launcher scripts change so that they get rewritten. */
    private static final int LAUNCHER_VERSION = 7;

    private static final String LAUNCHER_MARKER = "Managed by the OpenCode for Android app. opencode-launcher-version: " + LAUNCHER_VERSION;
    /** First line of the profile this app writes, a profile without it is left alone. */
    private static final String PROFILE_MARKER = "# Managed by the OpenCode for Android app. opencode-profile-version: 2";
    /** Markers of the block that hooks the package environment into etc/bash.bashrc. */
    private static final String BASHRC_BLOCK_BEGIN = "# >>> OpenCode for Android package environment >>>";
    private static final String BASHRC_BLOCK_END = "# <<< OpenCode for Android package environment <<<";

    private static final String USER_AGENT = "OpenCodeForAndroid";

    private static final long HTTP_CONNECT_TIMEOUT_MS = 20_000L;
    private static final long HTTP_READ_TIMEOUT_MS = 60_000L;
    private static final long APT_UPDATE_TIMEOUT_MS = 240_000L;
    private static final long APT_INSTALL_TIMEOUT_MS = 900_000L;
    private static final long SECOND_STAGE_TIMEOUT_MS = 240_000L;
    private static final long VERIFY_TIMEOUT_MS = 90_000L;
    private static final long PATCH_TIMEOUT_MS = 300_000L;
    private static final long EXTRACT_TIMEOUT_MS = 180_000L;
    private static final long PROCESS_POLL_INTERVAL_MS = 100L;

    /** Maximum number of characters of a single command output kept in memory. */
    private static final int MAX_OUTPUT_CHARS = 256 * 1024;
    /** Number of steps kept for the error report. */
    private static final int MAX_STEPS = 40;
    /** Number of log lines appended to the error report. */
    private static final int FAILURE_LOG_LINES = 14;
    /** Maximum length of the detail part of a notification. */
    private static final int MAX_DETAIL_CHARS = 900;

    /** Prefix that the Termux packages are built for, it has the same length as {@link #DATA_DIR}. */
    private static final String PREFIX_TERMUX = "/data/data/com.termux/files/usr";
    /** Home directory that is baked into the Termux profile scripts. */
    private static final String HOME_TERMUX = "/data/data/com.termux/files/home";

    /** Mirrors of the main Termux repository, tried in order. */
    private static final String[] MAIN_MIRRORS = {
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
        "https://grimler.se/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
    };

    /** Mirrors of the Termux glibc repository, tried in order. */
    private static final String[] GLIBC_MIRRORS = {
        "https://packages-cf.termux.dev/apt/termux-glibc",
        "https://packages.termux.dev/apt/termux-glibc"
    };

    /** Packages that provide the glibc runtime (only used by the legacy installer paths). */
    private static final List<String> GLIBC_PACKAGES = Arrays.asList("glibc-repo", "glibc-runner", "patchelf", "tar");

    /**
     * Termux packages that are extracted into the app-private prefix before OpenCode is downloaded:
     * a shell, the core utilities, the certificate store, the glibc runtime (the loader that runs
     * OpenCode) and patchelf (it rewrites the RUNPATH of the Termux binaries).
     */
    private static final List<String> CORE_PACKAGES = Arrays.asList(
        "bash", "ca-certificates", "patchelf", "glibc");

    /** Endpoint telling which OpenCode version is the latest one. */
    private static final String VERSION_API_URL = "https://opencode.ai/update/api/latest/cli/npm";
    /** npm registry used as fallback for the version lookup and for the download. */
    private static final String NPM_REGISTRY_URL = "https://registry.npmjs.org";

    /** The npm packages that carry the platform build, the 2.x line renamed the scope. */
    private static final String[] PLATFORM_PACKAGE_PREFIXES = {
        "@opencode/cli-linux-", "@opencode-ai/cli-linux-"
    };

    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_OUTPUT_PATTERN = Pattern.compile("(?s).*\\d+\\.\\d+.*");
    /** The uri of a package, printed by {@code apt-get install --print-uris}. */
    private static final Pattern APT_URI_PATTERN = Pattern.compile("^'([^']+)'\\s+(\\S+)\\s+(\\d+)\\s+(?:(\\w+):)?([0-9a-fA-F]+)");
    /** The size of a package, printed by apt at the end of a line. */
    private static final Pattern APT_SIZE_PATTERN = Pattern.compile("\\[([0-9][0-9.,]*)\\s*([kKmMgG]?B)?\\]");

    /** Notification channel used for the progress and the result of an installation. */
    public static final String NOTIFICATION_CHANNEL_ID = "opencode_notification_channel";
    /** Name of the notification channel. */
    public static final String NOTIFICATION_CHANNEL_NAME = "OpenCode";
    /** Id of the notification used for the progress and the result of an installation. */
    public static final int NOTIFICATION_ID = 1338;

    private static final Object sLock = new Object();
    /** The installer that is currently running, null when nothing is being installed. */
    private static Installer sInstaller = null;
    /** True while the silent maintenance of the runtime runs in the background. */
    private static boolean sMaintenanceRunning = false;
    /** The listeners that want to know about the progress of the installation. */
    private static final List<InstallListener> sListeners = new CopyOnWriteArrayList<>();
    /** The callbacks that are waiting for the installation to finish. */
    private static final List<Runnable> sInstallWaiters = new ArrayList<>();
    /** The state of the installation, it is mirrored on the disk. */
    private static OpenCodeInstallState sState = new OpenCodeInstallState();
    /** Name of the shared preferences file of the app. */
    private static final String PREFERENCES_NAME = "opencode";
    /** Key of the server password in the preferences. */
    private static final String PREF_SERVER_PASSWORD = "server_password";
    /** Cached server password, see {@link #getServerPassword(Context)}. */
    private static String sServerPassword = null;
    /** The handler of the UI thread, used to notify the listeners. */
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private OpenCodeBootstrap() {
    }

    /* ==========================================================================================
     * Public API
     * ========================================================================================== */

    /**
     * Make sure that the OpenCode runtime is complete and call {@code onReady} afterwards.
     *
     * <p>If something is missing, the installer is started and {@code onReady} is called once the
     * installation finished (successfully or not). The callback is always invoked on the UI thread.
     * When an installation is already running, the request is queued and the progress is
     * shown by the listeners.</p>
     */
    public static void setupIfNeeded(final Context context, final Runnable onReady) {
        if (context == null) return;

        // Nothing has to be downloaded. Refresh the launchers in the
        // background without showing any progress UI, otherwise the installation dialog would
        // flash on every cold start even though OpenCode is already installed.
        if (!isInstallRunning() && runtimeProblems().isEmpty()) {
            clearFinishedState();
            maintainInBackground(context);
            runOnUiThread(context, onReady);
            return;
        }

        final Installer installer;
        synchronized (sLock) {
            if (sInstaller != null) {
                Log.i(LOG_TAG, "An installation is already running, queueing the request.");
                if (onReady != null) sInstallWaiters.add(onReady);
                return;
            }
            installer = new Installer(context, false);
            sInstaller = installer;
            if (onReady != null) sInstallWaiters.add(onReady);
        }

        installer.start();
    }

    /**
     * Force a (re)installation of the OpenCode runtime. The user is asked through the
     * notification, the installation itself runs in the background.
     */
    public static void requestUpdate(final Context context) {
        if (context == null) return;

        if (!isPrefixReady()) {
            Log.w(LOG_TAG, "The Linux toolchain prefix is not ready yet, cannot update OpenCode.");
            return;
        }

        final Installer installer;
        synchronized (sLock) {
            if (sInstaller != null) {
                Log.i(LOG_TAG, "An installation is already running, ignoring the update request.");
                return;
            }
            installer = new Installer(context, true);
            sInstaller = installer;
        }

        installer.start();
    }

    /**
     * Cheap check run whenever the app comes to the foreground: if the runtime is broken it is
     * repaired, otherwise nothing happens.
     */
    public static void checkRuntime(final Context context) {
        if (context == null) return;
        if (!isPrefixReady()) return;

        final List<String> problems = runtimeProblems();
        if (problems.isEmpty()) {
            // Everything is fine, but the launchers may belong to an older build of the
            // app, so refresh them in the background.
            clearFinishedState();
            maintainInBackground(context);
        } else {
            Log.i(LOG_TAG, "The OpenCode runtime is incomplete: " + TextUtils.join(", ", problems));
            setupIfNeeded(context, null);
        }
    }

    /** Drop the state of an installation that finished (successfully or not) when the runtime is
     * complete, so that a stale result is not shown again on the next start. */
    private static void clearFinishedState() {
        synchronized (sLock) {
            if (sInstaller != null) return;
            sState = new OpenCodeInstallState();
        }
        OpenCodeInstallState.clear();
    }

    /** Refresh the launchers in the background. No progress is
     * published and no dialog is shown, so this is safe to call on every start of the app. */
    private static void maintainInBackground(final Context context) {
        synchronized (sLock) {
            // Never touch the runtime while an installation is replacing it.
            if (sInstaller != null || sMaintenanceRunning) return;
            sMaintenanceRunning = true;
        }

        new Thread(() -> {
            try {
                Installer installer = new Installer(context, false);
                installer.logOnly = true;
                installer.doMaintenance();
            } catch (Exception e) {
                Log.e(LOG_TAG, "Failed to maintain the OpenCode runtime", e);
            } finally {
                synchronized (sLock) {
                    sMaintenanceRunning = false;
                }
            }
        }, "opencode-maintenance").start();
    }

    /**
     * Read the state of the last installation from the disk. It has to be called once when the
     * process of the app starts, so that the screen of the app shows the real progress even when
     * Android killed the app while it was downloading something.
     */
    public static void restoreState() {
        synchronized (sLock) {
            // An installation that is still running has a state that is up to date.
            if (sInstaller != null) return;

            OpenCodeInstallState saved = OpenCodeInstallState.load();
            if (saved == null) return;

            // A succeeded or failed installation is stale when the runtime is complete, the app
            // must not show the result of an earlier run again on every start.
            if (!saved.running && (saved.done || saved.failed) && runtimeProblems().isEmpty()) {
                OpenCodeInstallState.clear();
                return;
            }

            if (saved.running) {
                // The process was killed, the installation is not running anymore.
                saved.running = false;
                saved.message = saved.message == null ? "" : saved.message;
                saved.detail = "The app was closed, the installation was interrupted.";
                saved.addStep("The previous installation was interrupted, it is resumed automatically.");
            }

            sState = saved;
        }
    }

    /** @return the current state of the installation, never null. */
    public static OpenCodeInstallState getInstallState() {
        synchronized (sLock) {
            return copyOf(sState);
        }
    }

    /**
     * The password of the local OpenCode server.
     *
     * <p>The 2.x server requires HTTP Basic authentication and otherwise prints a random password
     * on every start. The app generates one on the first use and keeps it, so the WebView and the
     * terminal launcher always know it.</p>
     */
    public static String getServerPassword(Context context) {
        synchronized (sLock) {
            if (sServerPassword != null) return sServerPassword;

            String password = null;
            if (context != null) {
                try {
                    password = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .getString(PREF_SERVER_PASSWORD, null);
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Could not read the server password", e);
                }
            }

            if (password == null || password.length() < 16) {
                byte[] random = new byte[24];
                new java.security.SecureRandom().nextBytes(random);
                StringBuilder sb = new StringBuilder(random.length * 2);
                for (byte b : random) sb.append(String.format(Locale.US, "%02x", b));
                password = sb.toString();
                if (context != null) {
                    try {
                        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                            .edit().putString(PREF_SERVER_PASSWORD, password).apply();
                    } catch (Exception e) {
                        Log.w(LOG_TAG, "Could not store the server password", e);
                    }
                }
            }

            sServerPassword = password;
            return password;
        }
    }

    /** @return true while an installation is running. */
    public static boolean isInstallRunning() {
        synchronized (sLock) {
            return sInstaller != null;
        }
    }

    /** Register a listener that is notified about every change of the state. */
    public static void addInstallListener(InstallListener listener) {
        if (listener == null) return;
        sListeners.add(listener);
    }

    /** Unregister a listener. */
    public static void removeInstallListener(InstallListener listener) {
        if (listener == null) return;
        sListeners.remove(listener);
    }

    /**
     * @return the list of things that are missing for OpenCode to run. An empty list means that
     * the runtime is complete.
     */
    public static List<String> runtimeProblems() {
        List<String> problems = new ArrayList<>();

        if (!isPrefixReady())
            problems.add("the Linux toolchain (" + PREFIX + "/bin/bash)");

        if (!new File(PREFIX + "/bin/sh").exists())
            problems.add("the shell link (" + PREFIX + "/bin/sh)");

        if (!new File(READY_MARKER_PATH).isFile())
            problems.add("the installed version marker (" + READY_MARKER_PATH + ")");

        if (!new File(OPENCODE_BIN_PATH).exists())
            problems.add("the opencode launcher (" + OPENCODE_BIN_PATH + ")");

        if (!new File(OC_INSTALL_PATH).exists())
            problems.add("the oc-install launcher (" + OC_INSTALL_PATH + ")");

        if (!new File(LINK_PATH).exists())
            problems.add("the opencode symlink (" + LINK_PATH + ")");

        if (findGlibcLoader() == null)
            problems.add("the glibc loader (" + GLIBC_LIB_DIR + "/ld-linux-*.so.*)");

        if (!isExecutable(new File(PREFIX + "/bin/patchelf")))
            problems.add("the patchelf tool (" + PREFIX + "/bin/patchelf" + ")");

        return problems;
    }

    /** @return true if the OpenCode runtime is complete and can be started. */
    public static boolean isRuntimeComplete() {
        return runtimeProblems().isEmpty();
    }

    /** The version of OpenCode that is currently installed, or an empty string. */
    public static String getInstalledVersion() {
        return readTrimmed(new File(READY_MARKER_PATH));
    }

    /** @return true if the app-private Linux toolchain prefix is usable. */
    public static boolean isPrefixReady() {
        File bash = new File(PREFIX + "/bin/bash");
        return bash.isFile() && bash.canExecute();
    }

    /**
     * Write the environment of the installed Termux packages and hook it into the shell startup
     * files of the prefix. The package service calls this after every installation: the Termux
     * binaries link against {@code $PREFIX/lib} and the git build has its helper programs below
     * the old Termux prefix. Idempotent, safe to call at any time.
     */
    public static void ensurePackageEnvironment() {
        writePackageEnvironment();
    }

    /**
     * Write {@code etc/opencode-env.sh} and hook it into the shell startup files. The profile is
     * only replaced when this app wrote it or when it still points at the Termux app; a profile
     * edited by the user is left untouched.
     */
    private static void writePackageEnvironment() {
        File etc = new File(PREFIX, "etc");
        if (!etc.isDirectory() && !etc.mkdirs()) {
            Log.w(LOG_TAG, "Could not create " + etc);
            return;
        }
        writeTextFileQuietly(new File(ENV_SCRIPT_PATH), buildPackageEnvironment());
        ensureLoginProfile();
        hookBashrc();
    }

    /**
     * The Termux packages link against {@code $PREFIX/lib} with an absolute RUNPATH that points at
     * the com.termux prefix. The git build additionally has its helper programs and its template
     * directory compiled in below the old prefix. Neither is readable from this app, so both are
     * re-rooted here. The file is sourced by the login profile and by etc/bash.bashrc.
     */
    private static String buildPackageEnvironment() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Managed by the OpenCode for Android app (package environment v1).\n");
        sb.append("# Sourced by etc/profile and etc/bash.bashrc of the runtime.\n");
        sb.append("PREFIX=\"${PREFIX:-").append(PREFIX).append("}\"\n");
        sb.append("export PREFIX\n");
        sb.append("case \":${LD_LIBRARY_PATH:-}:\" in\n");
        sb.append("  *\":$PREFIX/lib:\"*) ;;\n");
        sb.append("  *) LD_LIBRARY_PATH=\"$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"; export LD_LIBRARY_PATH ;;\n");
        sb.append("esac\n");
        sb.append("# git was built for /data/data/com.termux/files/usr: its helpers would be looked up\n");
        sb.append("# there, which belongs to another app.\n");
        sb.append("if [ -d \"$PREFIX/libexec/git-core\" ]; then\n");
        sb.append("  GIT_EXEC_PATH=\"$PREFIX/libexec/git-core\"; export GIT_EXEC_PATH\n");
        sb.append("fi\n");
        sb.append("if [ -d \"$PREFIX/share/git-core/templates\" ]; then\n");
        sb.append("  GIT_TEMPLATE_DIR=\"$PREFIX/share/git-core/templates\"; export GIT_TEMPLATE_DIR\n");
        sb.append("fi\n");
        sb.append("if [ -f \"$PREFIX/etc/gitconfig\" ]; then\n");
        sb.append("  GIT_CONFIG_SYSTEM=\"$PREFIX/etc/gitconfig\"; export GIT_CONFIG_SYSTEM\n");
        sb.append("  unset GIT_CONFIG_NOSYSTEM\n");
        sb.append("else\n");
        sb.append("  GIT_CONFIG_NOSYSTEM=1; export GIT_CONFIG_NOSYSTEM\n");
        sb.append("fi\n");
        sb.append("GIT_ATTR_NOSYSTEM=1; export GIT_ATTR_NOSYSTEM\n");
        sb.append("if [ -f \"$PREFIX/etc/openssl/openssl.cnf\" ]; then\n");
        sb.append("  OPENSSL_CONF=\"$PREFIX/etc/openssl/openssl.cnf\"; export OPENSSL_CONF\n");
        sb.append("fi\n");
        sb.append("if [ -f \"$PREFIX/etc/tls/cert.pem\" ]; then\n");
        sb.append("  SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"; export SSL_CERT_FILE\n");
        sb.append("  CURL_CA_BUNDLE=\"$SSL_CERT_FILE\"; export CURL_CA_BUNDLE\n");
        sb.append("fi\n");
        sb.append(":\n");
        return sb.toString();
    }

    /** Write the managed login profile, unless the user replaced it with one of their own. */
    private static void ensureLoginProfile() {
        File profile = new File(PREFIX + "/etc/profile");
        String current = readTextFileQuietly(profile);
        boolean foreign = current.contains(PREFIX_TERMUX) || current.contains(HOME_TERMUX);
        boolean ours = current.contains("Managed by the OpenCode for Android app.");
        // A profile without our marker and without Termux paths belongs to the user.
        if (profile.isFile() && !foreign && !ours) return;

        StringBuilder sb = new StringBuilder();
        sb.append(PROFILE_MARKER).append('\n');
        sb.append("export HOME=\"${HOME:-").append(HOME).append("}\"\n");
        sb.append("export PREFIX=\"${PREFIX:-").append(PREFIX).append("}\"\n");
        sb.append("export SHELL=\"${SHELL:-$PREFIX/bin/bash}\"\n");
        sb.append("export PATH=\"$PREFIX/bin:/system/bin\"\n");
        sb.append("export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"\n");
        sb.append("export TERM=\"${TERM:-xterm-256color}\"\n");
        sb.append("export LANG=\"${LANG:-en_US.UTF-8}\"\n");
        sb.append("if [ -r \"$PREFIX/etc/opencode-env.sh\" ]; then . \"$PREFIX/etc/opencode-env.sh\"; fi\n");
        sb.append("if [ -r \"$HOME/.bashrc\" ]; then . \"$HOME/.bashrc\"; fi\n");
        writeTextFileQuietly(profile, sb.toString());
    }

    /**
     * Hook the package environment into etc/bash.bashrc, which the interactive non-login shells of
     * the terminal read. The block of an older app version is replaced, anything the user added
     * above it is kept.
     */
    private static void hookBashrc() {
        File bashrc = new File(PREFIX + "/etc/bash.bashrc");
        if (!bashrc.isFile()) return;

        String current = readTextFileQuietly(bashrc);
        int start = current.indexOf(BASHRC_BLOCK_BEGIN);
        String base = start >= 0 ? current.substring(0, start) : current;
        // Trim the trailing whitespace of the package file so the block starts on a clean line.
        while (base.endsWith("\n") || base.endsWith("\r") || base.endsWith(" ")) {
            base = base.substring(0, base.length() - 1);
        }

        StringBuilder sb = new StringBuilder(base);
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(BASHRC_BLOCK_BEGIN).append('\n');
        sb.append("if [ -r \"").append(ENV_SCRIPT_PATH).append("\" ]; then\n");
        sb.append("  . \"").append(ENV_SCRIPT_PATH).append("\"\n");
        sb.append("fi\n");
        sb.append(BASHRC_BLOCK_END).append('\n');

        String updated = sb.toString();
        if (!updated.equals(current)) writeTextFileQuietly(bashrc, updated);
    }

    /* ==========================================================================================
     * Installer
     * ========================================================================================== */

    private static final class Installer {

        private final Context context;
        private final boolean forceUpdate;
        private final Map<String, String> env = new HashMap<>();
        private final List<String> steps = new ArrayList<>();

        private String lastOutput = "";
        private boolean logOnly = false;
        /** apt options pointing at the temporary sources.list, null when the user config works. */
        private List<String> mTempSourcesOptions = null;

        Installer(Context context, boolean forceUpdate) {
            this.context = context == null ? null : context.getApplicationContext();
            this.forceUpdate = forceUpdate;

            // Environment of the spawned processes: own toolchain prefix, no bionic preload for
            // glibc processes.
            env.put("HOME", HOME);
            env.put("PREFIX", PREFIX);
            env.put("PATH", PREFIX + "/bin");
            env.put("TMPDIR", PREFIX + "/tmp");
            env.put("TERM", "xterm-256color");
            env.put("LANG", "en_US.UTF-8");
            env.put("COLORTERM", "truecolor");
            env.put("DEBIAN_FRONTEND", "noninteractive");
            env.put("TERMUX_PKG_NO_MIRROR_SELECT", "1");
            // The Termux binaries link their libraries with a hardcoded RUNPATH that points at the
            // com.termux prefix, so the library search path has to be redirected explicitly.
            // The glibc directory is NOT added here: it contains a 'libc.so' linker script that
            // the bionic linker would try to load as an ELF file. glibc binaries are pointed at
            // their libraries with an RPATH instead.
            env.put("LD_LIBRARY_PATH", PREFIX + "/lib");
            env.remove("LD_PRELOAD");
        }

        void start() {
            new Thread(this::run, "opencode-setup").start();
        }

        private void run() {
            boolean success = false;
            String errorMessage = null;
            Throwable failure = null;
            try {
                writeDiagnostics();
                if (!logOnly && context != null) {
                    // Leave a trace in the app-private diagnostics folder at the beginning of
                    // every run, so a report of "it does not get past X" can be checked even if
                    // the process is killed before the failure handler runs.
                    OpenCodeCrashLog.writeReport(context, "install-start",
                        "forceUpdate=" + forceUpdate
                            + "\nproblems=" + runtimeProblems()
                            + "\nabis=" + String.join(",", Build.SUPPORTED_ABIS)
                            + "\nprefix=" + PREFIX);
                }
                if (forceUpdate) {
                    setStage(OpenCodeInstallState.STAGE_PREPARE, "Updating OpenCode...");
                    doInstall();
                } else if (!runtimeProblems().isEmpty()) {
                    setStage(OpenCodeInstallState.STAGE_PREPARE, "Preparing the OpenCode runtime...");
                    doInstall();
                } else {
                    setStage(OpenCodeInstallState.STAGE_PREPARE, "Checking the OpenCode runtime...");
                    doMaintenance();
                }
                success = true;
            } catch (InstallException e) {
                failure = e;
                errorMessage = e.getMessage();
            } catch (Exception e) {
                Log.e(LOG_TAG, "The OpenCode installation failed", e);
                failure = e;
                errorMessage = "The OpenCode installation failed: " + e;
            }

            if (success) {
                setStageDone();
                notifyFinished("OpenCode is ready", "Version " + getInstalledVersion() + " is installed.", false);
            } else {
                handleFailure(errorMessage == null ? "Unknown error" : errorMessage, failure);
            }

            releaseWaiters();
        }

        /* ------------------------------------------------------------------ install pipeline */

        private void doInstall() {
            ensureRuntimeDirectories();

            setStage(OpenCodeInstallState.STAGE_APT_INSTALL, "Installing the Linux toolchain...");
            try {
                OpenCodePackages.install(new File(PREFIX), CORE_PACKAGES, new OpenCodePackages.Progress() {
                    @Override
                    public void onStatus(String message) {
                        status(message);
                    }

                    @Override
                    public void onPercent(int percent, String detail) {
                        setProgress(percent, detail);
                    }
                });
            } catch (Exception e) {
                Log.e(LOG_TAG, "The Linux toolchain could not be installed", e);
                throw new InstallException("Could not install the Linux toolchain: " + e.getMessage());
            }

            // The prefix may have been wiped above, recreate the writable directories and the
            // resolver configuration of the toolchain.
            ensureRuntimeDirectories();
            ensureResolvConf();

            // 'patchelf' is needed to patch the OpenCode binary, it links against libc++_shared.so,
            // an old libc++ makes it die with "cannot locate symbol __hash_memory". Verify it before
            // downloading ~100 MB.
            if (!patchelfWorks()) {
                setStage(OpenCodeInstallState.STAGE_APT_INSTALL, "Repairing the patchelf tool...");
                try {
                    OpenCodePackages.install(new File(PREFIX), Arrays.asList("patchelf", "libc++"),
                        new OpenCodePackages.Progress() {
                            @Override
                            public void onStatus(String message) {
                                status(message);
                            }

                            @Override
                            public void onPercent(int percent, String detail) {
                                setProgress(percent, detail);
                            }
                        });
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Could not reinstall patchelf", e);
                }
                if (!patchelfWorks())
                    throw new InstallException("The 'patchelf' tool cannot run on this device: " + tail(lastOutput, 400));
            }

            // The packages are built for the com.termux prefix and carry its absolute RUNPATH.
            // Repoint the tools the app itself spawns, so they also work without LD_LIBRARY_PATH
            // (eg. when the user runs them from a terminal).
            patchRpath(new File(PREFIX + "/bin/bash"), PREFIX + "/lib");
            patchRpath(new File(PREFIX + "/bin/patchelf"), PREFIX + "/lib");

            // The login shell of the terminal and the glibc loader both contain the com.termux
            // prefix in their read-only data. Move it to the sandbox root, which has the same
            // length, so no offset of either ELF moves.
            ensurePathAliases();
            patchLoginShellPaths();

            String loader = findGlibcLoader();
            if (loader == null)
                throw new InstallException("The glibc loader (ld-linux-*.so.*) was not found in " + GLIBC_LIB_DIR + ". Install the 'glibc-runner' package and try again.");

            String arch = getOpenCodeArch();
            if (arch == null)
                throw new InstallException("The CPU architecture \"" + Build.SUPPORTED_ABIS[0] + "\" has no official OpenCode build.");

            String version = fetchLatestVersion();

            setStage(OpenCodeInstallState.STAGE_DOWNLOAD, "Downloading OpenCode " + version + "...");
            File tarball = downloadTarball(arch, version);
            try {
                setStage(OpenCodeInstallState.STAGE_EXTRACT, "Extracting the OpenCode package...");
                File binary = extractBinary(tarball);
                File installed = installBinary(binary, version, loader);
                if (installed == null) {
                    // The new build does not run, keep the old one if there is one.
                    if (!new File(LINK_PATH).exists())
                        throw new InstallException("The downloaded OpenCode build does not run on this device.");
                    status("WARNING: the downloaded build does not run on this device, keeping the installed one.");
                } else {
                    linkTo(installed);
                    try {
                        writeTextFile(new File(READY_MARKER_PATH), version);
                    } catch (IOException e) {
                        throw new InstallException("Could not write the installation markers: " + e.getMessage());
                    }
                }
                writeLaunchers();
            } finally {
                deleteQuietly(tarball);
            }

            // The old installer script must not stay around, it would fight with the app.
            deleteQuietly(new File(LEGACY_SETUP_SCRIPT_PATH));
            deleteQuietly(new File(APT_TMP_DIR));
        }

        private void doMaintenance() {
            deleteQuietly(new File(LEGACY_SETUP_SCRIPT_PATH));
            // Repair the parts that a reinstall of a package or an OpenCode update may have
            // replaced: the aliases, the relocated loader, the login profile and the marker.
            ensurePathAliases();
            String loader = findGlibcLoader();
            if (loader != null) relocateLoader(loader);
            patchLoginShellPaths();
            repairNativeInterp();
            refreshNativeMarker();
            restoreLauncher(new File(OPENCODE_BIN_PATH), buildOpenCodeLauncher());
            restoreLauncher(new File(OC_INSTALL_PATH), buildOcInstallLauncher());
            restoreLauncher(new File(PKG_PATH), buildPkgLauncher());
        }

        /* ------------------------------------------------------------------ apt */

        private boolean aptUpdate(boolean withGlibc) {
            if (apt(Arrays.asList("update"), APT_UPDATE_TIMEOUT_MS, true, new AptUpdateListener()))
                return true;

            // The configuration of the user may be broken, try the known mirrors with a
            // temporary sources.list so that nothing of the user is touched.
            for (String main : MAIN_MIRRORS) {
                for (String glibc : (withGlibc ? GLIBC_MIRRORS : new String[]{null})) {
                    status("Trying the mirror " + main + "...");
                    writeTempSources(main, glibc);
                    List<String> args = new ArrayList<>();
                    args.add("update");
                    if (apt(args, APT_UPDATE_TIMEOUT_MS, true, new AptUpdateListener())) {
                        // Use the working mirrors for the installs as well, the configuration
                        // of the user is most likely broken.
                        mTempSourcesOptions = tempSourcesOptions();
                        return true;
                    }
                }
            }

            deleteQuietly(new File(APT_TMP_DIR));
            mTempSourcesOptions = null;
            return false;
        }

        private boolean aptInstall(List<String> packages) {
            List<String> args = new ArrayList<>();
            args.add("install");
            args.add("-y");
            args.addAll(packages);

            boolean downloaded = preDownloadPackages(packages);
            if (downloaded)
                setProgress(100, "");

            if (apt(args, APT_INSTALL_TIMEOUT_MS, true, downloaded ? null : new AptInstallListener()))
                return true;

            // The package database may be broken if the app was killed during an install.
            status("Repairing the package database...");
            List<String> dpkgFixArgs = new ArrayList<>();
            dpkgFixArgs.add(PREFIX + "/bin/dpkg");
            dpkgFixArgs.add("--configure");
            dpkgFixArgs.add("-a");
            run(dpkgFixArgs, APT_INSTALL_TIMEOUT_MS, true, null);
            apt(Arrays.asList("-f", "install", "-y"), APT_INSTALL_TIMEOUT_MS, true, null);
            return apt(args, APT_INSTALL_TIMEOUT_MS, true, null);
        }

        private boolean aptReinstall(List<String> packages) {
            List<String> args = new ArrayList<>();
            args.add("install");
            args.add("--reinstall");
            args.add("-y");
            args.addAll(packages);
            return apt(args, APT_INSTALL_TIMEOUT_MS, true, null);
        }

        private boolean apt(List<String> extraArgs, long timeoutMs, boolean stream, LineListener listener) {
            List<String> cmd = new ArrayList<>();
            cmd.add(PREFIX + "/bin/apt-get");
            cmd.add("-o");
            cmd.add("Acquire::Retries=3");
            cmd.add("-o");
            cmd.add("Acquire::http::Timeout=30");
            cmd.add("-o");
            cmd.add("Acquire::https::Timeout=30");
            if (mTempSourcesOptions != null)
                cmd.addAll(mTempSourcesOptions);
            cmd.addAll(extraArgs);

            return run(cmd, timeoutMs, stream, listener).exitCode == 0;
        }

        /** @return the apt options that make apt use the temporary sources.list. */
        private static List<String> tempSourcesOptions() {
            List<String> options = new ArrayList<>();
            options.add("-o");
            options.add("Dir::Etc::sourcelist=" + new File(APT_TMP_DIR, "sources.list").getAbsolutePath());
            options.add("-o");
            options.add("Dir::Etc::sourceparts=-");
            return options;
        }

        private void writeTempSources(String mainMirror, String glibcMirror) {
            StringBuilder sb = new StringBuilder();
            sb.append("deb ").append(mainMirror).append(" stable main\n");
            if (glibcMirror != null)
                sb.append("deb ").append(glibcMirror).append(" glibc stable\n");
            try {
                writeTextFile(new File(APT_TMP_DIR, "sources.list"), sb.toString());
            } catch (IOException e) {
                Log.w(LOG_TAG, "Failed to write the temporary sources.list", e);
            }
        }

        /**
         * Download the packages of an apt transaction with a progress bar, so that the user knows
         * how much has been downloaded already.
         */
        private boolean preDownloadPackages(List<String> packages) {
            List<String> cmd = new ArrayList<>();
            cmd.add(PREFIX + "/bin/apt-get");
            cmd.add("-o");
            cmd.add("Acquire::Retries=3");
            cmd.add("-o");
            cmd.add("Acquire::http::Timeout=30");
            cmd.add("-o");
            cmd.add("Acquire::https::Timeout=30");
            if (mTempSourcesOptions != null)
                cmd.addAll(mTempSourcesOptions);
            cmd.add("install");
            cmd.add("-y");
            cmd.add("--print-uris");
            cmd.addAll(packages);

            CmdResult result = run(cmd, APT_UPDATE_TIMEOUT_MS, false, null);
            if (result.exitCode != 0)
                return false;

            List<PackageUri> uris = parsePackageUris(result.output);
            if (uris.isEmpty())
                return false;

            long total = 0;
            for (PackageUri uri : uris) {
                if (uri.size > 0)
                    total += uri.size;
            }
            if (total <= 0)
                return false;

            File archivesDir = new File(APT_ARCHIVES_DIR);
            File partialDir = new File(archivesDir, "partial");
            if (!partialDir.isDirectory() && !partialDir.mkdirs())
                return false;

            long done = 0;
            int index = 0;
            for (final PackageUri uri : uris) {
                index++;
                File target = new File(archivesDir, uri.fileName);
                if (target.isFile() && uri.matches(target)) {
                    done += uri.size;
                    continue;
                }

                File temp = new File(partialDir, uri.fileName);
                final long base = done;
                final long totalBytes = total;
                final int number = index;
                final int count = uris.size();

                try {
                    downloadFile(uri.url, temp, true, (bytesDone, bytesTotal, detail) -> {
                        long overall = base + bytesDone;
                        setBytes(overall, totalBytes, "Package " + number + "/" + count + ": " + uri.fileName + " - " + detail);
                    });
                } catch (IOException e) {
                    Log.w(LOG_TAG, "Could not download " + uri.url + ": " + e.getMessage());
                    deleteQuietly(temp);
                    return false;
                }

                if (!uri.matches(temp)) {
                    Log.w(LOG_TAG, "The checksum of " + uri.fileName + " does not match, letting apt download it.");
                    deleteQuietly(temp);
                    return false;
                }

                deleteQuietly(target);
                if (!temp.renameTo(target)) {
                    if (!copyFile(temp, target)) {
                        deleteQuietly(temp);
                        return false;
                    }
                    deleteQuietly(temp);
                }

                done += uri.size;
                setBytes(done, total, "Package " + number + "/" + count + ": " + uri.fileName + " - done");
            }

            return true;
        }

        /** @return the packages that apt would download for the given transaction. */
        private static List<PackageUri> parsePackageUris(String output) {
            List<PackageUri> uris = new ArrayList<>();
            if (output == null) return uris;

            for (String line : output.split("\n")) {
                line = line.trim();
                if (!line.startsWith("'")) continue;

                Matcher matcher = APT_URI_PATTERN.matcher(line);
                if (!matcher.find()) continue;

                PackageUri uri = new PackageUri();
                uri.url = matcher.group(1);
                uri.fileName = matcher.group(2);
                uri.size = Long.parseLong(matcher.group(3));
                uri.hashAlgorithm = matcher.group(4);
                uri.hash = matcher.group(5);
                if (uri.url == null || uri.fileName == null) continue;

                uris.add(uri);
            }

            return uris;
        }

        /* ------------------------------------------------------------------ version & download */

        private String fetchLatestVersion() {
            // The npm platform package is authoritative: it is the archive that is downloaded, so
            // its version always matches the tarball. The update API sometimes announces a version
            // of a package that is not published for every platform yet.
            String arch = getOpenCodeArch();
            if (arch != null) {
                NpmRelease release = resolveNpmRelease(arch);
                if (release != null) return release.version;
            }

            String version = httpGetString(VERSION_API_URL);
            String match = extractVersion(version);
            if (match != null) return match;

            throw new InstallException("Could not determine the OpenCode version (check the network).");
        }

        private static String extractVersion(String json) {
            if (json == null) return null;
            Matcher matcher = VERSION_PATTERN.matcher(json);
            if (matcher.find()) return matcher.group(1);
            return null;
        }

        /** One published platform build of OpenCode, as described by the npm registry. */
        private static final class NpmRelease {
            String version = "";
            String tarball = "";
            String integrity = "";
        }

        /**
         * Ask the npm registry for the newest platform build. The package was renamed when the 2.x
         * line was published, so both names are tried. The tarball URL is taken from the metadata
         * instead of being assembled by hand, that way a rename or a different file layout cannot
         * produce a 404.
         */
        private NpmRelease resolveNpmRelease(String arch) {
            for (String prefix : PLATFORM_PACKAGE_PREFIXES) {
                String name = prefix + arch;
                try {
                    String meta = httpGetString(NPM_REGISTRY_URL + "/" + name + "/latest");
                    if (meta == null) continue;
                    JSONObject json = new JSONObject(meta);
                    JSONObject dist = json.optJSONObject("dist");
                    if (dist == null) continue;
                    NpmRelease release = new NpmRelease();
                    release.version = json.optString("version", "");
                    release.tarball = dist.optString("tarball", "");
                    release.integrity = dist.optString("integrity", "");
                    if (!release.version.isEmpty() && !release.tarball.isEmpty())
                        return release;
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Could not read the npm metadata of " + name + ": " + e.getMessage());
                }
            }
            return null;
        }

        private File downloadTarball(String arch, String version) {
            File tmpDir = new File(TMP_DIR);
            if (!tmpDir.isDirectory() && !tmpDir.mkdirs())
                throw new InstallException("Could not create the temporary directory " + TMP_DIR);

            NpmRelease release = resolveNpmRelease(arch);
            if (release != null) {
                File target = new File(tmpDir, "cli-linux-" + arch + "-" + release.version + ".tgz");
                setStage(OpenCodeInstallState.STAGE_DOWNLOAD, "Downloading OpenCode " + release.version + "...");
                try {
                    downloadTarballTo(release.tarball, target, release.integrity);
                    return target;
                } catch (IOException e) {
                    Log.w(LOG_TAG, "Download of " + release.tarball + " failed: " + e.getMessage());
                }
            }

            // Last resort: build the URLs from the version of the update API.
            String fileName = "cli-linux-" + arch + "-" + version + ".tgz";
            IOException lastError = null;
            for (String prefix : PLATFORM_PACKAGE_PREFIXES) {
                String url = NPM_REGISTRY_URL + "/" + prefix + arch + "/-/" + fileName;
                File target = new File(tmpDir, fileName);
                try {
                    setStage(OpenCodeInstallState.STAGE_DOWNLOAD, "Downloading OpenCode " + version + "...");
                    downloadTarballTo(url, target, null);
                    return target;
                } catch (IOException e) {
                    lastError = e;
                    Log.w(LOG_TAG, "Download of " + url + " failed: " + e.getMessage());
                }
            }

            throw new InstallException("Could not download the OpenCode package: " + (lastError == null ? "unknown error" : lastError.getMessage()));
        }

        /**
         * Download the tarball of OpenCode. A file that was downloaded before is continued where
         * it stopped, so that the progress is kept when the app is closed in the middle of the
         * download.
         */
        private void downloadTarballTo(String url, File target, String expectedIntegrity) throws IOException {
            // The file may have been downloaded completely before the app was closed.
            if (expectedIntegrity != null && target.isFile() && verifySha512(target, expectedIntegrity))
                return;

            try {
                downloadFile(url, target, true, this::reportDownloadProgress);
            } catch (IOException e) {
                // The partial file may be useless, download it again from the beginning.
                Log.w(LOG_TAG, "Could not continue the download of " + url + ": " + e.getMessage());
                deleteQuietly(target);
                downloadFile(url, target, false, this::reportDownloadProgress);
            }

            if (expectedIntegrity != null && !verifySha512(target, expectedIntegrity)) {
                deleteQuietly(target);
                downloadFile(url, target, false, this::reportDownloadProgress);
                if (!verifySha512(target, expectedIntegrity))
                    throw new IOException("The checksum of the downloaded file does not match the one of the npm registry.");
            }
        }

        private static boolean verifySha512(File file, String expectedBase64) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-512");
                byte[] buffer = new byte[64 * 1024];
                try (InputStream in = new FileInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1)
                        digest.update(buffer, 0, read);
                }
                String actual = Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
                return actual.equals(expectedBase64);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Could not verify the checksum of " + file, e);
                return false;
            }
        }

        /**
         * Download a file and report the progress.
         */
        private long downloadFile(String url, File target, boolean resume, DownloadListener listener) throws IOException {
            long alreadyHave = 0;
            if (resume && target.isFile())
                alreadyHave = target.length();

            HttpURLConnection connection = openConnection(url, alreadyHave);
            try {
                connection.connect();
                long contentLength = connection.getContentLength();
                long total;
                boolean append;

                if (connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL) {
                    append = true;
                    total = alreadyHave + (contentLength > 0 ? contentLength : 0);
                } else {
                    // The server ignored the range request, the file is downloaded again.
                    append = false;
                    alreadyHave = 0;
                    total = contentLength;
                }

                long startedAt = SystemClock.uptimeMillis();
                long lastReportAt = startedAt;
                int lastPercent = -1;
                long read = alreadyHave;

                try (InputStream in = connection.getInputStream();
                     OutputStream out = new FileOutputStream(target, append)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                        read += count;

                        int percent = total > 0 ? (int) (read * 100 / total) : 0;
                        long now = SystemClock.uptimeMillis();
                        boolean step = percent >= lastPercent + 2 || percent == 100;
                        if (percent != lastPercent && (step || now - lastReportAt >= 3000)) {
                            lastPercent = percent;
                            lastReportAt = now;
                            long elapsedMs = Math.max(1, now - startedAt);
                            double rate = (read / 1024.0 / 1024.0) / (elapsedMs / 1000.0);
                            long eta = rate > 0.01 ? (long) ((total - read) / (rate * 1024 * 1024)) : 0;
                            if (listener != null)
                                listener.onDownloadProgress(read, total, formatTransferDetail(read, total, rate, eta));
                        }
                    }
                    out.flush();
                }

                return read - alreadyHave;
            } finally {
                connection.disconnect();
            }
        }

        /** Report the progress of the current stage, used by the listeners of apt. */
        private void reportDownloadProgress(long bytesDone, long bytesTotal, String detail) {
            setBytes(bytesDone, bytesTotal, detail);
        }

        private static String formatTransferDetail(long done, long total, double rate, long etaSeconds) {
            StringBuilder sb = new StringBuilder();
            sb.append("OpenCode: ");
            sb.append(formatSize(done));
            if (total > 0)
                sb.append(" / ").append(formatSize(total));
            if (rate > 0.01)
                sb.append(" - ").append(String.format(Locale.US, "%.2f MB/s", rate));
            if (etaSeconds > 0)
                sb.append(" - ").append(formatEta(etaSeconds)).append(" left");
            return sb.toString();
        }

        private static String formatEta(long seconds) {
            if (seconds <= 0) return "?";
            long minutes = seconds / 60;
            long rest = seconds % 60;
            if (minutes <= 0) return rest + "s";
            return minutes + "m" + rest + "s";
        }

        private static String formatSize(long bytes) {
            if (bytes <= 0) return "0 B";
            return (bytes / (1024.0 * 1024.0)) + " MB";
        }

        private HttpURLConnection openConnection(String url) throws IOException {
            return openConnection(url, 0);
        }

        private HttpURLConnection openConnection(String url, long rangeStart) throws IOException {
            String current = url;
            HttpURLConnection connection = null;
            for (int hop = 0; hop < 6; hop++) {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout((int) HTTP_CONNECT_TIMEOUT_MS);
                connection.setReadTimeout((int) HTTP_READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "*/*");
                if (rangeStart > 0)
                    connection.setRequestProperty("Range", "bytes=" + rangeStart + "-");
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 ||
                    code == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null) throw new IOException("Missing Location header for " + current);
                    current = new URL(new URL(current), location).toString();
                    connection = null;
                    continue;
                }
                if (code < 200 || code >= 300)
                    throw new IOException("The server answered with HTTP " + code + " for " + current);
                return connection;
            }
            throw new IOException("Too many redirects for " + url);
        }

        private String httpGetString(String url) {
            HttpURLConnection connection = null;
            try {
                connection = openConnection(url);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                        if (sb.length() > 1024 * 1024) break;
                    }
                }
                return sb.toString();
            } catch (Exception e) {
                Log.w(LOG_TAG, "The request to " + url + " failed: " + e.getMessage());
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        /* ------------------------------------------------------------------ extraction & patching */

        private File extractBinary(File tarball) {
            File tmpDir = new File(TMP_DIR);
            File target = new File(tmpDir, "package/bin/opencode");

            status("Extracting the OpenCode package...");
            deleteQuietly(new File(tmpDir, "package"));

            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new InstallException("Could not create the directory " + parent);

            // The package is a plain gzipped tar archive, so it is unpacked in Java. This avoids
            // depending on the Termux 'tar' package and its own dependencies at this early stage.
            boolean found = false;
            try (InputStream raw = new BufferedInputStream(new FileInputStream(tarball));
                 GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
                 TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.startsWith("./")) name = name.substring(2);
                    if (!name.endsWith("bin/opencode")) continue;
                    if (entry.isDirectory() || entry.isSymbolicLink() || entry.isLink())
                        throw new InstallException("The OpenCode package does not contain a regular binary.");
                    try (OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = tar.read(buffer)) != -1) out.write(buffer, 0, read);
                        out.flush();
                    }
                    found = true;
                    break;
                }
            } catch (IOException e) {
                deleteQuietly(target);
                throw new InstallException("Extracting the tarball failed: " + e.getMessage());
            }

            if (!found || !target.isFile() || target.length() == 0) {
                deleteQuietly(target);
                throw new InstallException("The downloaded package does not contain the expected binary.");
            }
            return target;
        }

        /**
         * Patch, verify and install the binary.
         *
         * @return the installed binary or null if the downloaded build does not run and an older
         * version is already installed.
         */
        private File installBinary(File binary, String version, String loader) {
            File installDir = new File(INSTALL_DIR);
            if (!installDir.isDirectory() && !installDir.mkdirs())
                throw new InstallException("Could not create the directory " + INSTALL_DIR);

            File target = new File(installDir, "opencode-v" + version);
            File staged = new File(installDir, ".opencode-v" + version + ".new");
            deleteQuietly(staged);

            // Move the file to the staging area of the same filesystem so that it can be
            // replaced atomically later on.
            if (!binary.renameTo(staged)) {
                if (!copyFile(binary, staged)) {
                    deleteQuietly(staged);
                    throw new InstallException("Could not stage the OpenCode binary.");
                }
                deleteQuietly(binary);
            }

            setStage(OpenCodeInstallState.STAGE_PATCH, "Preparing the OpenCode binary...");
            // Patch PT_INTERP so that the binary starts directly. This is not cosmetic: OpenCode
            // spawns its own background server through /proc/self/exe, and with the explicit
            // loader that path is ld-linux, so the child would run "ld-linux serve" and die with
            // "serve: cannot open shared object file". When the patch cannot be applied the
            // loader fallback still works for the foreground TUI, so it is not fatal.
            ensurePathAliases();
            boolean nativeInterp = false;
            if (relocateLoader(loader)) {
                status("Pointing the binary at the glibc runtime...");
                CmdResult patch = run(Arrays.asList(
                    PREFIX + "/bin/patchelf", "--set-interpreter", loader, staged.getAbsolutePath()
                ), PATCH_TIMEOUT_MS, false, null);
                nativeInterp = patch.exitCode == 0;
                if (!nativeInterp)
                    Log.w(LOG_TAG, "Could not set the interpreter of the OpenCode binary: " + tail(patch.output, 200));
            }

            try {
                Os.chmod(staged.getAbsolutePath(), 0755);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not make the OpenCode binary executable", e);
            }

            setStage(OpenCodeInstallState.STAGE_VERIFY, "Verifying the OpenCode binary...");
            CmdResult verify;
            if (nativeInterp) {
                // Same environment as the launcher: no bionic LD_LIBRARY_PATH/LD_PRELOAD.
                verify = runNative(Arrays.asList(staged.getAbsolutePath(), "--version"), VERIFY_TIMEOUT_MS);
                if (!looksLikeAVersion(verify)) {
                    // The interpreter patch alone is not enough on this build, keep the binary
                    // and let the launcher/service use the explicit loader instead.
                    status("WARNING: this build does not start directly, falling back to the glibc loader.");
                    verify = run(Arrays.asList(
                        loader, "--library-path", GLIBC_LIB_DIR, staged.getAbsolutePath(), "--version"
                    ), VERIFY_TIMEOUT_MS, false, null);
                    nativeInterp = false;
                }
            } else {
                verify = run(Arrays.asList(
                    loader, "--library-path", GLIBC_LIB_DIR, staged.getAbsolutePath(), "--version"
                ), VERIFY_TIMEOUT_MS, false, null);
            }
            if (!looksLikeAVersion(verify)) {
                deleteQuietly(staged);
                if (target.isFile()) {
                    status("WARNING: the downloaded build does not run on this device, keeping the installed one.");
                    return null;
                }
                throw new InstallException("The downloaded OpenCode build does not run on this device: " + tail(verify.output, 400));
            }

            // Atomic replace of the installed binary.
            deleteQuietly(target);
            if (!staged.renameTo(target)) {
                if (!copyFile(staged, target)) {
                    deleteQuietly(staged);
                    throw new InstallException("Could not install the OpenCode binary.");
                }
                deleteQuietly(staged);
            }

            try {
                Os.chmod(target.getAbsolutePath(), 0755);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not make the OpenCode binary executable", e);
            }

            writeNativeMarker(target, nativeInterp);

            // Every build is ~130 MB, keep only the one that is linked.
            pruneOldBuilds(target);

            return target;
        }

        /**
         * Record whether the installed binary can be started directly. The launcher and the
         * service compare the fingerprint before they skip the explicit loader.
         */
        private void writeNativeMarker(File binary, boolean nativeInterp) {
            File marker = new File(NATIVE_INTERP_MARKER_PATH);
            if (!nativeInterp) {
                deleteQuietly(marker);
                return;
            }
            try {
                writeTextFile(marker, binaryFingerprint(binary) + "\n");
            } catch (IOException e) {
                Log.w(LOG_TAG, "Could not write " + marker, e);
            }
        }

        /**
         * Drop the marker when the binary behind the symlink changed (eg. the auto updater
         * replaced it), so the launcher and the service use the explicit loader again.
         */
        private void refreshNativeMarker() {
            String stamp = readTrimmed(new File(NATIVE_INTERP_MARKER_PATH));
            if (stamp.isEmpty()) return;
            File binary = resolveLink(new File(LINK_PATH));
            if (!binary.isFile() || !stamp.equals(binaryFingerprint(binary))) {
                status("The OpenCode binary changed, using the glibc loader again.");
                deleteQuietly(new File(NATIVE_INTERP_MARKER_PATH));
            }
        }

        /**
         * Patch the interpreter of an already installed binary (an installation from an older
         * version of the app). The binary is copied first; the original is only replaced after the
         * copy starts natively, so a failure can never break a working installation.
         */
        private void repairNativeInterp() {
            File binary = resolveLink(new File(LINK_PATH));
            if (!binary.isFile() || isNativeBinary(binary)) return;

            String attempt = LAUNCHER_VERSION + " " + binaryFingerprint(binary);
            if (attempt.equals(readTrimmed(new File(NATIVE_REPAIR_MARKER_PATH)))) return;

            String loader = findGlibcLoader();
            if (loader == null || !relocateLoader(loader) || !patchelfWorks()) return;

            status("Preparing the installed OpenCode binary for a direct start...");
            File temp = new File(binary.getAbsolutePath() + ".native-new");
            deleteQuietly(temp);
            if (!copyFile(binary, temp)) {
                Log.w(LOG_TAG, "Could not copy the OpenCode binary, keeping the glibc loader path.");
                deleteQuietly(temp);
                writeTextFileQuietly(new File(NATIVE_REPAIR_MARKER_PATH), attempt + "\n");
                return;
            }
            try {
                Os.chmod(temp.getAbsolutePath(), 0755);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not make " + temp + " executable", e);
            }

            CmdResult patch = run(Arrays.asList(
                PREFIX + "/bin/patchelf", "--set-interpreter", loader, temp.getAbsolutePath()
            ), PATCH_TIMEOUT_MS, false, null);
            CmdResult verify = patch.exitCode == 0
                ? runNative(Arrays.asList(temp.getAbsolutePath(), "--version"), VERIFY_TIMEOUT_MS)
                : null;

            if (verify == null || !looksLikeAVersion(verify) || !temp.renameTo(binary)) {
                Log.w(LOG_TAG, "Could not prepare the installed binary, keeping the glibc loader path.");
                deleteQuietly(temp);
                writeTextFileQuietly(new File(NATIVE_REPAIR_MARKER_PATH), attempt + "\n");
                return;
            }

            writeNativeMarker(binary, true);
            deleteQuietly(new File(NATIVE_REPAIR_MARKER_PATH));
        }

        /** Delete the binaries of earlier versions, they waste a lot of app storage. */
        private void pruneOldBuilds(File keep) {
            File[] files = new File(INSTALL_DIR).listFiles();
            if (files == null) return;
            for (File file : files) {
                String name = file.getName();
                if (!name.startsWith("opencode-v") && !name.startsWith(".opencode-v")) continue;
                if (file.getAbsolutePath().equals(keep.getAbsolutePath())) continue;
                deleteQuietly(file);
            }
        }

        /** Point the RUNPATH of a Termux ELF at the libraries of the app-private prefix. */
        private void patchRpath(File file, String rpath) {
            if (!file.isFile()) return;
            List<String> args = new ArrayList<>();
            args.add(PREFIX + "/bin/patchelf");
            args.add("--set-rpath");
            args.add(rpath);
            args.add(file.getAbsolutePath());
            CmdResult result = run(args, PATCH_TIMEOUT_MS, false, null);
            if (result.exitCode != 0)
                Log.w(LOG_TAG, "Could not set the RUNPATH of " + file + ": " + tail(result.output, 200));
        }

        private boolean patchelfWorks() {
            List<String> ppArgs = new ArrayList<>();
            ppArgs.add(PREFIX + "/bin/patchelf");
            ppArgs.add("--version");
            return run(ppArgs, VERIFY_TIMEOUT_MS, false, null).exitCode == 0;
        }

        /**
         * Move the compiled-in login paths of bash to the sandbox root. Without this a login shell
         * (OpenCode starts the terminal as {@code bash -l}) tries to read
         * {@code /data/data/com.termux/files/usr/etc/profile}, which belongs to another app and
         * fails with "Permission denied".
         */
        private void patchLoginShellPaths() {
            File bash = new File(PREFIX + "/bin/bash");
            if (!bash.isFile()) return;
            int hits = patchFileStrings(bash,
                PREFIX_TERMUX + "/etc/profile", ETC_ALIAS_DIR + "/profile",
                PREFIX_TERMUX + "/etc/bash.bashrc", ETC_ALIAS_DIR + "/bash.bashrc",
                PREFIX_TERMUX + "/etc/hosts", ETC_ALIAS_DIR + "/hosts");
            if (hits > 0) status("Moved the login profile of bash to the app prefix.");

            // The profile and the bashrc are plain text files from the bash package, they are not
            // touched by the ELF rewrite and still point at the real Termux app.
            rewriteTextPaths(new File(PREFIX + "/etc/bash.bashrc"));
            writePackageEnvironment();
        }

        /** Rewrite the com.termux paths of a text file to this app's prefix and home. */
        private void rewriteTextPaths(File file) {
            if (!file.isFile()) return;
            try {
                String text = readTextFile(file);
                if (text == null) return;
                String rewritten = text
                    .replace(PREFIX_TERMUX, PREFIX)
                    .replace(HOME_TERMUX, HOME);
                if (!rewritten.equals(text)) writeTextFile(file, rewritten);
            } catch (IOException e) {
                Log.w(LOG_TAG, "Could not rewrite the paths of " + file, e);
            }
        }

        /** Run a byte-patched binary without the bionic library paths, exactly like the launcher. */
        private CmdResult runNative(List<String> cmd, long timeoutMs) {
            String libraryPath = env.remove("LD_LIBRARY_PATH");
            String preload = env.remove("LD_PRELOAD");
            try {
                return run(cmd, timeoutMs, false, null);
            } finally {
                if (libraryPath != null) env.put("LD_LIBRARY_PATH", libraryPath);
                if (preload != null) env.put("LD_PRELOAD", preload);
            }
        }

        private static boolean looksLikeAVersion(CmdResult result) {
            return result.exitCode == 0 && VERSION_OUTPUT_PATTERN.matcher(result.output == null ? "" : result.output).matches();
        }

        private void linkTo(File target) {
            try {
                File linkDir = new File(LINK_DIR);
                if (!linkDir.isDirectory() && !linkDir.mkdirs())
                    throw new IOException("Could not create the directory " + LINK_DIR);
                deleteQuietly(new File(LINK_PATH));
                Os.symlink(target.getAbsolutePath(), LINK_PATH);
            } catch (Exception e) {
                throw new InstallException("Could not create the opencode symlink: " + e.getMessage());
            }
        }

        /* ------------------------------------------------------------------ launchers */

        private void writeLaunchers() {
            restoreLauncher(new File(OPENCODE_BIN_PATH), buildOpenCodeLauncher());
            restoreLauncher(new File(OC_INSTALL_PATH), buildOcInstallLauncher());
            restoreLauncher(new File(PKG_PATH), buildPkgLauncher());
        }

        /**
         * Write the launcher, but only if the file on disk is not the launcher of this app
         * already. A launcher written by an older build is replaced, a correct one is kept.
         */
        private void restoreLauncher(File file, String content) {
            File temp = null;
            try {
                String current = readTextFile(file);
                if (current != null && current.contains(LAUNCHER_MARKER))
                    return;

                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Could not create the directory " + parent);

                // Write to a temporary file and rename it, so that a process that starts the
                // launcher while the maintenance is running never sees a half written script.
                temp = File.createTempFile(file.getName(), ".tmp", parent);
                writeTextFile(temp, content);
                try {
                    Os.chmod(temp.getAbsolutePath(), 0755);
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Could not make the " + temp.getAbsolutePath() + " executable", e);
                }

                if (!temp.renameTo(file)) {
                    // Some file systems cannot replace an existing file, write it directly.
                    writeTextFile(file, content);
                    try {
                        Os.chmod(file.getAbsolutePath(), 0755);
                    } catch (Exception e) {
                        Log.w(LOG_TAG, "Could not make the " + file.getAbsolutePath() + " executable", e);
                    }
                    deleteQuietly(temp);
                    temp = null;
                }
            } catch (IOException e) {
                Log.w(LOG_TAG, "Could not write the launcher " + file, e);
            } finally {
                if (temp != null)
                    deleteQuietly(temp);
            }
        }

        private String buildOpenCodeLauncher() {
            StringBuilder sb = new StringBuilder();
            // /system/bin/sh always exists, a Termux shell would need the prefix libraries.
            sb.append("#!").append("/system/bin/sh\n");
            sb.append("# ").append(LAUNCHER_MARKER).append("\n");
            sb.append("P=\"${PREFIX:-").append(PREFIX).append("}\"\n");
            sb.append("H=\"${HOME:-").append(HOME).append("}\"\n");
            sb.append("BIN=\"$H/.opencode/bin/opencode\"\n");
            sb.append("GLIBCLIB=\"$P/glibc/lib\"\n");
            sb.append("\n");
            sb.append("case \"${1:-}\" in\n");
            sb.append("  upgrade|update) exec \"$P/bin/oc-install\" ;;\n");
            sb.append("esac\n");
            sb.append("\n");
            sb.append("[ -x \"$BIN\" ] || { echo \"opencode: not installed yet. Open the app to install it.\" >&2; exit 127; }\n");
            sb.append("LOADER=\"\"; for c in \"$GLIBCLIB\"/ld-linux*.so.*; do [ -x \"$c\" ] && { LOADER=\"$c\"; break; }; done\n");
            sb.append("[ -z \"$LOADER\" ] && { echo \"opencode: the glibc runtime is missing. Open the app and run the installation again.\" >&2; exit 127; }\n");
            sb.append("\n");
            sb.append("# The auto updater of OpenCode would replace the binary that the launcher knows.\n");
            sb.append("export OPENCODE_DISABLE_AUTOUPDATE=1\n");
            sb.append("# Never load the bionic preload of Termux into a glibc process.\n");
            sb.append("unset LD_PRELOAD\n");
            sb.append("# The tools OpenCode spawns (git, …) link against $P/lib. The glibc binary itself\n");
            sb.append("# only needs libc.so.6/libm.so.6/libdl.so.2/libpthread.so.0, names that no bionic\n");
            sb.append("# library provides, so this directory cannot shadow its own libraries.\n");
            sb.append("case \":${LD_LIBRARY_PATH:-}:\" in\n");
            sb.append("  *\":$P/lib:\"*) ;;\n");
            sb.append("  *) LD_LIBRARY_PATH=\"$P/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"; export LD_LIBRARY_PATH ;;\n");
            sb.append("esac\n");
            sb.append("[ -n \"${TMPDIR:-}\" ] || { TMPDIR=\"$P/tmp\"; export TMPDIR; }\n");
            sb.append("[ -n \"${SHELL:-}\" ] || { SHELL=\"$P/bin/bash\"; export SHELL; }\n");
            sb.append("[ -f \"$P/etc/tls/cert.pem\" ] && { SSL_CERT_FILE=\"$P/etc/tls/cert.pem\"; export SSL_CERT_FILE; }\n");
            sb.append("# The terminal uses the same server password as the app, so a server that is\n");
            sb.append("# started here and the one of the app cannot disagree about the credentials.\n");
            sb.append("if [ -z \"${OPENCODE_SERVER_PASSWORD:-}\" ] && [ -f \"$H/.opencode/server-password\" ]; then\n");
            sb.append("  IFS= read -r OPENCODE_SERVER_PASSWORD < \"$H/.opencode/server-password\" 2>/dev/null\n");
            sb.append("  [ -n \"$OPENCODE_SERVER_PASSWORD\" ] && export OPENCODE_SERVER_PASSWORD\n");
            sb.append("fi\n");
            sb.append("\n");
            sb.append("# When the binary is patched, start it directly: OpenCode spawns its background\n");
            sb.append("# server through /proc/self/exe, which would be the loader (and 'serve' would fail)\n");
            sb.append("# if the loader was passed explicitly.\n");
            sb.append("MARK=$(cat \"$H/.opencode/.native-interp\" 2>/dev/null)\n");
            sb.append("NOW=$(/system/bin/stat -L -c '%s %Y' \"$BIN\" 2>/dev/null)\n");
            sb.append("if [ -n \"$MARK\" ] && [ \"$MARK\" = \"$NOW\" ]; then\n");
            sb.append("  exec \"$BIN\" \"$@\"\n");
            sb.append("fi\n");
            sb.append("\n");
            sb.append("echo \"opencode: this build is not patched; using the glibc loader.\" >&2\n");
            sb.append("exec \"$LOADER\" --library-path \"$GLIBCLIB\" \"$BIN\" \"$@\"\n");
            return sb.toString();
        }

        /**
         * The 'pkg' of the terminal. There is no apt/dpkg in the prefix, the app downloads the
         * archives of the Termux repository itself. The script starts OpenCodePkgService through
         * the activity manager (same UID, the service is not exported) and follows the status
         * file of the request.
         */
        private String buildPkgLauncher() {
            String script = """
                #!/system/bin/sh
                # @MARKER@
                #
                # pkg - install Termux packages into the app-private prefix.
                #
                # The prefix has no apt/dpkg. The app downloads the .deb archives of the Termux
                # repository, verifies their SHA-256 and extracts them itself. This script only
                # talks to the app: it starts OpenCodePkgService through 'am' and follows the
                # status file that the service writes.

                P="${PREFIX:-@PREFIX@}"
                H="${HOME:-@HOME@}"
                case ":$PATH:" in
                    *:/system/bin:*) ;;
                    *) PATH="${PATH:+$PATH:}/system/bin"; export PATH ;;
                esac
                APP="com.opencode.android"
                USER_ID=@USER_ID@
                SVC="$APP/.OpenCodePkgService"
                DIR="$H/.opencode"
                SESSION="pkg-$$"
                STATUS="$DIR/$SESSION.status"
                RESULT="$DIR/$SESSION.out"
                MARK="$P/.packages-installed"

                usage() {
                    cat <<'USAGE'
                Usage: pkg <command> [arguments]

                Commands:
                  install <package...>   Install Termux packages and their dependencies
                  search <text>          Search the Termux package repository
                  list-installed         List the packages of this prefix
                  update, upgrade        Refresh the index (every install uses the live index)
                  help                   Show this help

                Examples:
                  pkg install git gh
                  pkg search editor
                  pkg list-installed

                The packages are extracted into the app-private prefix by the app itself, so apt,
                dpkg and the Termux app are not needed.
                USAGE
                }

                fail() {
                    echo "pkg: $*" >&2
                    exit 1
                }

                pause() {
                    sleep 0.2 2>/dev/null || sleep 1
                }

                valid_name() {
                    case "$1" in
                        ''|*[!a-zA-Z0-9+._-]*) return 1 ;;
                    esac
                    return 0
                }

                field() {
                    # field <file> <key>
                    [ -f "$1" ] || return 0
                    sed -n "s/^$2=//p" "$1" | head -n 1
                }

                start_service() {
                    # start_service <action> [extra 'am' arguments...]
                    action="$1"
                    shift
                    rm -f "$STATUS" "$RESULT"
                    output=$(/system/bin/am start-foreground-service --user "$USER_ID" -n "$SVC" --es action "$action" --es session "$SESSION" "$@" 2>&1)
                    status=$?
                    case "$output" in
                        *Error*|*Exception*|*SecurityException*)
                            fail "the app refused the request: $output" ;;
                    esac
                    [ "$status" -eq 0 ] || fail "the activity manager failed: $output"
                }

                wait_for_service() {
                    waited=0
                    while [ ! -f "$STATUS" ]; do
                        pause
                        waited=$((waited + 1))
                        [ "$waited" -lt 50 ] || fail "the app did not answer. Bring OpenCode to the foreground and try again."
                    done

                    last=""
                    idle=0
                    while :; do
                        state=$(field "$STATUS" state)
                        message=$(field "$STATUS" message)
                        percent=$(field "$STATUS" percent)
                        if [ -n "$message" ] && [ "$message" != "$last" ]; then
                            case "$percent" in
                                ''|0) echo "$message" ;;
                                *) echo "[$percent%] $message" ;;
                            esac
                            last="$message"
                            idle=0
                        else
                            idle=$((idle + 1))
                        fi
                        case "$state" in
                            done) return 0 ;;
                            error) echo "pkg: $message" >&2; return 1 ;;
                        esac
                        [ "$idle" -lt 7200 ] || fail "no progress for 30 minutes, giving up. Check the notification of the app."
                        pause
                    done
                }

                pkg_install() {
                    packages=""
                    for argument in "$@"; do
                        case "$argument" in
                            -y|--yes|-f|--fix-broken) continue ;;
                            -*) fail "install: unsupported option '$argument'" ;;
                        esac
                        valid_name "$argument" || fail "install: '$argument' is not a valid package name"
                        packages="$packages $argument"
                    done
                    [ -n "$packages" ] || fail "install: no package name given. Try: pkg install git gh"
                    [ -x "$P/bin/bash" ] || fail "the Linux runtime is not installed yet. Open the app first."

                    echo "Installing$packages ..."
                    start_service install --es packages "$packages"
                    wait_for_service || return 1
                    echo
                    echo "Installed in $P."
                    echo "For git in this shell run: . \"$P/etc/opencode-env.sh\""
                }

                pkg_search() {
                    [ "$#" -ge 1 ] || fail "search: no search text given. Try: pkg search git"
                    query="$*"
                    echo "Searching for '$query' ..."
                    start_service search --es query "$query"
                    wait_for_service || return 1
                    echo
                    if [ -s "$RESULT" ]; then
                        cat "$RESULT"
                    else
                        echo "No package matched '$query'."
                    fi
                }

                pkg_list() {
                    if [ ! -s "$MARK" ]; then
                        echo "No package is installed yet."
                        return 0
                    fi
                    sort -u "$MARK"
                }

                pkg_update() {
                    echo "Every installation downloads the live package index, there is nothing to refresh."
                    echo "Run 'pkg install <package>' to install or upgrade a package."
                }

                umask 077
                mkdir -p "$DIR" 2>/dev/null
                trap 'rm -f "$STATUS" "$RESULT"' EXIT

                command="${1:-help}"
                [ "$#" -gt 0 ] && shift

                case "$command" in
                    install|add) pkg_install "$@" ;;
                    search|find) pkg_search "$@" ;;
                    list-installed|list|installed) pkg_list ;;
                    update|upgrade) pkg_update ;;
                    remove|uninstall|delete)
                        fail "'$command' is not supported: the app installs packages without dpkg, so there is no package database to remove from. Clear the app data to start over." ;;
                    files|show)
                        fail "'$command' is not supported: the packages are extracted directly into $P, without a dpkg database." ;;
                    help|-h|--help|'') usage ;;
                    *) echo "pkg: unknown command '$command'" >&2; usage >&2; exit 2 ;;
                esac
                """;
            int userId;
            try {
                userId = android.os.Process.myUserHandle().getIdentifier();
            } catch (Throwable t) {
                // myUserHandle() is API 24, the uid layout is a stable fallback.
                userId = android.os.Process.myUid() / 100000;
            }
            return script
                .replace("@MARKER@", LAUNCHER_MARKER)
                .replace("@PREFIX@", PREFIX)
                .replace("@HOME@", HOME)
                .replace("@USER_ID@", String.valueOf(userId));
        }

        private String buildOcInstallLauncher() {
            StringBuilder sb = new StringBuilder();
            sb.append("#!").append("/system/bin/sh\n");
            sb.append("# ").append(LAUNCHER_MARKER).append("\n");
            sb.append("echo \"oc-install: asking the OpenCode app to install/update the runtime (watch the notification)\"\n");
            sb.append("exec /system/bin/am start -a android.intent.action.VIEW \\\n");
            sb.append("  -n com.opencode.android/.OpenCodeWebActivity \\\n");
            sb.append("  --ez com.opencode.android.opencode.FORCE_UPDATE true\n");
            return sb.toString();
        }

        /* ------------------------------------------------------------------ repair */

        /* ------------------------------------------------------------------ environment */

        /** Create the writable directories the runtime expects, they are not part of the packages. */
        private void ensureRuntimeDirectories() {
            File[] dirs = {
                new File(HOME),
                new File(PREFIX, "tmp"),
                new File(INSTALL_DIR),
                new File(HOME, ".opencode"),
                new File(HOME, ".cache"),
                new File(HOME, ".config"),
                new File(HOME, ".local/share"),
                new File(HOME, ".local/state"),
            };
            for (File dir : dirs) {
                if (!dir.isDirectory() && !dir.mkdirs())
                    Log.w(LOG_TAG, "Could not create the directory " + dir);
            }
            try {
                Os.chmod(new File(PREFIX, "tmp").getAbsolutePath(), 0700);
            } catch (Exception ignored) {
                // Not fatal, the directory is already private to the app.
            }

            // The launchers export this password, so the terminal and the app always use the same
            // credentials for the OpenCode server.
            try {
                File passwordFile = new File(SERVER_PASSWORD_PATH);
                writeTextFile(passwordFile, getServerPassword(context) + "\n");
                Os.chmod(passwordFile.getAbsolutePath(), 0600);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not write the server password file", e);
            }
        }

        private void ensureResolvConf() {
            File resolvConf = new File(RESOLV_CONF_PATH);
            if (resolvConf.exists()) return;
            if (!resolvConf.getParentFile().isDirectory()) return;

            try {
                writeTextFile(resolvConf, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
            } catch (IOException e) {
                Log.w(LOG_TAG, "Failed to write " + RESOLV_CONF_PATH, e);
            }
        }

        private void writeDiagnostics() {
            StringBuilder sb = new StringBuilder();
            sb.append("app version: OpenCode for Android 1.0.0\n");
            sb.append("package: com.opencode.android\n");
            sb.append("abi: ").append(Build.SUPPORTED_ABIS == null ? "" : Build.SUPPORTED_ABIS[0]).append("\n");
            sb.append("sdk: ").append(Build.VERSION.SDK_INT).append("\n");
            sb.append("device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("prefix: ").append(PREFIX).append("\n");
            sb.append("home: ").append(HOME).append("\n");
            sb.append("installed version: ").append(getInstalledVersion()).append("\n");
            sb.append("glibc loader: ").append(String.valueOf(findGlibcLoader())).append("\n");
            sb.append("native interpreter: ").append(isNativeBinary(resolveLink(new File(LINK_PATH)))).append("\n");

            writeTextFileQuietly(new File(DIAGNOSTICS_PATH), sb.toString());
        }

        /* ------------------------------------------------------------------ process handling */

        private CmdResult run(List<String> cmd, long timeoutMs, boolean stream) {
            return run(cmd, timeoutMs, stream, null);
        }

        private CmdResult run(List<String> cmd, long timeoutMs, boolean stream, LineListener listener) {
            StringBuilder output = new StringBuilder();
            try {
                ProcessBuilder builder = new ProcessBuilder(cmd);
                Map<String, String> processEnv = builder.environment();
                processEnv.clear();
                processEnv.putAll(env);
                builder.directory(new File(HOME));
                builder.redirectErrorStream(true);

                final Process process = builder.start();

                final StringBuilder sink = output;
                Thread reader = new Thread(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            synchronized (sink) {
                                if (sink.length() < MAX_OUTPUT_CHARS)
                                    sink.append(line).append('\n');
                            }
                            if (listener != null) {
                                try {
                                    listener.onLine(line);
                                } catch (Exception e) {
                                    // The listener must never break the installation.
                                }
                            }
                            if (stream)
                                Log.i(LOG_TAG, line);
                        }
                    } catch (Exception e) {
                        // The process was killed, ignore.
                    }
                }, "opencode-cmd-reader");
                reader.setDaemon(true);
                reader.start();

                long deadline = SystemClock.uptimeMillis() + timeoutMs;
                Integer exitCode = null;
                while (exitCode == null) {
                    try {
                        exitCode = process.exitValue();
                    } catch (IllegalThreadStateException e) {
                        if (SystemClock.uptimeMillis() > deadline) {
                            process.destroy();
                            try {
                                process.waitFor();
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            return new CmdResult(-1, output.toString(), "timeout");
                        }
                        try {
                            Thread.sleep(PROCESS_POLL_INTERVAL_MS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return new CmdResult(-1, output.toString(), "interrupted");
                        }
                    }
                }

                String text;
                synchronized (output) {
                    text = output.toString();
                }
                lastOutput = text;
                return new CmdResult(exitCode, text, null);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Failed to run " + cmd, e);
                return new CmdResult(-1, output.toString(), e.getMessage());
            }
        }

        /* ------------------------------------------------------------------ reporting */

        private void status(final String message) {
            steps.add(message);
            while (steps.size() > MAX_STEPS)
                steps.remove(0);

            Log.i(LOG_TAG, message);
            appendLog("== " + message);

            if (logOnly) return;

            synchronized (sLock) {
                sState.message = message;
                sState.updatedAt = SystemClock.uptimeMillis();
                sState.addStep(message);
            }

            publish();
        }

        /** Set the stage of the installation and the message that describes it. */
        private void setStage(String stage, String message) {
            if (logOnly) return;

            Log.i(LOG_TAG, message);
            appendLog("== " + message);

            steps.add(message);
            while (steps.size() > MAX_STEPS)
                steps.remove(0);

            synchronized (sLock) {
                sState.stage = stage;
                sState.stagePercent = 0;
                sState.overallPercent = 0;
                sState.message = message;
                sState.detail = "";
                sState.error = "";
                sState.bytesDone = 0;
                sState.bytesTotal = 0;
                sState.running = true;
                sState.done = false;
                sState.failed = false;
                sState.updatedAt = SystemClock.uptimeMillis();
                sState.recomputeOverallPercent();
                sState.addStep(message);
            }

            publish();
        }

        /** Set the percentage of the current stage. */
        private void setProgress(int stagePercent, String detail) {
            if (logOnly) return;

            synchronized (sLock) {
                sState.stagePercent = Math.max(0, Math.min(100, stagePercent));
                if (detail != null)
                    sState.detail = detail;
                sState.updatedAt = SystemClock.uptimeMillis();
                sState.recomputeOverallPercent();
            }

            publish();
        }

        /** Set the number of bytes that were downloaded and the total size. */
        private void setBytes(long bytesDone, long bytesTotal, String detail) {
            if (logOnly) return;

            synchronized (sLock) {
                sState.bytesDone = bytesDone;
                sState.bytesTotal = bytesTotal;
                if (bytesTotal > 0)
                    sState.stagePercent = (int) Math.max(0, Math.min(100, bytesDone * 100 / bytesTotal));
                if (detail != null)
                    sState.detail = detail;
                sState.updatedAt = SystemClock.uptimeMillis();
                sState.recomputeOverallPercent();
            }

            publish();
        }

        /** The installation finished successfully. */
        private void setStageDone() {
            if (logOnly) return;

            synchronized (sLock) {
                sState.stage = OpenCodeInstallState.STAGE_DONE;
                sState.stagePercent = 100;
                sState.overallPercent = 100;
                sState.message = "OpenCode is ready.";
                sState.detail = "Version " + getInstalledVersion() + " is installed.";
                sState.running = false;
                sState.done = true;
                sState.failed = false;
                sState.updatedAt = SystemClock.uptimeMillis();
                sState.addStep(sState.message);
            }

            publish();
        }

        private void appendLog(String line) {
            try {
                File logFile = new File(SETUP_LOG_PATH);
                if (logFile.getParentFile() != null && !logFile.getParentFile().isDirectory() && !logFile.getParentFile().mkdirs())
                    return;
                try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileOutputStream(logFile, true))) {
                    writer.println(SystemClock.uptimeMillis() + " " + line);
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not write the setup log", e);
            }
        }

        private void showProgressNotification(String message) {
            if (logOnly) return;
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);

            PendingIntent contentIntent = PendingIntent.getActivity(context, 0, new Intent(context, OpenCodeWebActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setOngoing(true)
                .setContentTitle("OpenCode")
                .setContentText(message)
                .setContentIntent(contentIntent);
            if (builder == null) return;
            builder.setProgress(100, sState.getOverallPercent(), false);

            synchronized (sLock) {
                manager.notify(NOTIFICATION_ID, builder.build());
            }
        }

        private void notifyFinished(String title, String text, boolean isError) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);

            PendingIntent contentIntent = PendingIntent.getActivity(context, 0, new Intent(context, OpenCodeWebActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent);
            if (builder == null) return;
            builder.setAutoCancel(true);

            manager.notify(NOTIFICATION_ID, builder.build());
        }

        private void publish() {
            publishState();

            String message;
            synchronized (sLock) {
                message = sState.message;
            }
            if (message != null)
                showProgressNotification(message);
        }

        private void handleFailure(String message, Throwable failure) {
            Log.e(LOG_TAG, "The OpenCode installation failed: " + message, failure);

            StringBuilder report = new StringBuilder();
            report.append("OpenCode installation failed\n\n");
            report.append(message).append("\n\n");
            if (failure != null) {
                report.append("Stack trace:\n");
                report.append(Log.getStackTraceString(failure)).append("\n");
            }
            report.append("Last steps:\n");
            for (String step : steps)
                report.append("  - ").append(step).append('\n');
            report.append("\nOpen problems:\n");
            for (String problem : runtimeProblems())
                report.append("  - ").append(problem).append('\n');
            report.append("\n");
            report.append("Log tail (").append(SETUP_LOG_PATH).append("):\n");
            report.append(tail(readTextFileQuietly(new File(SETUP_LOG_PATH)), FAILURE_LOG_LINES));
            report.append("\nDiagnostics (").append(DIAGNOSTICS_PATH).append("):\n");
            report.append(readTextFileQuietly(new File(DIAGNOSTICS_PATH)));

            String text = report.toString();
            writeTextFileQuietly(new File(ERROR_DETAIL_PATH), text);
            // The app-private file is invisible for the user, mirror the report into the Download
            // folder so a failed first installation can be diagnosed without a computer.
            try {
                OpenCodeCrashLog.writeReport(context, "install", text);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not export the failure report", e);
            }

            notifyFinished("OpenCode: installation failed", tail(message, MAX_DETAIL_CHARS), true);

            if (logOnly) return;

            synchronized (sLock) {
                sState.stage = OpenCodeInstallState.STAGE_FAILED;
                sState.error = message == null ? "Unknown error" : message;
                sState.running = false;
                sState.done = false;
                sState.failed = true;
                sState.updatedAt = SystemClock.uptimeMillis();
                sState.addStep("FAILED: " + sState.error);
            }

            publish();
        }

        /* ------------------------------------------------------------------ lifecycle */

        private void releaseWaiters() {
            List<Runnable> waiters;
            synchronized (sLock) {
                waiters = new ArrayList<>(sInstallWaiters);
                sInstallWaiters.clear();
                sInstaller = null;
            }
            for (Runnable waiter : waiters)
                runOnUiThread(context, waiter);
        }
    }

    /* ==========================================================================================
     * Listeners of the output of a process
     * ========================================================================================== */

    /** Called for every line of the output of a process. */
    private interface LineListener {
        void onLine(String line);
    }

    /** Called while a file is downloaded. */
    private interface DownloadListener {
        void onDownloadProgress(long bytesDone, long bytesTotal, String detail);
    }

    /** Guesses the progress of {@code apt-get update} from its output. Apt does not tell how much
     * has been downloaded already, but it prints the size of every file it fetches. */
    private static final class AptUpdateListener implements LineListener {

        private long knownTotal = 0;
        private long doneBytes = 0;
        private long currentSize = 0;

        @Override
        public void onLine(String line) {
            if (line == null) return;

            if (line.startsWith("Get:")) {
                // The file that was fetched before is complete now.
                if (currentSize > 0)
                    doneBytes += currentSize;
                currentSize = Math.max(0, parseAptSize(line));
                if (currentSize > 0)
                    knownTotal += currentSize;
                report(line);
                return;
            }

            if (line.startsWith("Fetched ") || line.startsWith("Ign:") || line.startsWith("Hit:")) {
                report(line);
            }
        }

        private void report(String line) {
            long total = Math.max(knownTotal, doneBytes + currentSize);
            int percent = total > 0 ? (int) Math.min(100, doneBytes * 100 / total) : 0;
            setStageProgress(percent, doneBytes, total, line.trim());
        }
    }

    /**
     * Guesses the progress of {@code apt-get install} from its output, used when the packages
     * could not be downloaded by the app itself.
     */
    private static final class AptInstallListener implements LineListener {

        private long knownTotal = 0;
        private long doneBytes = 0;
        private long currentSize = 0;

        @Override
        public void onLine(String line) {
            if (line == null) return;

            if (line.startsWith("Get:")) {
                if (currentSize > 0)
                    doneBytes += currentSize;
                currentSize = Math.max(0, parseAptSize(line));
                if (currentSize > 0)
                    knownTotal += currentSize;
            } else if (line.startsWith("Fetched ")) {
                if (currentSize > 0)
                    doneBytes += currentSize;
                currentSize = 0;
            }

            if (line.startsWith("Get:") || line.startsWith("Fetched ") || line.startsWith("Unpacking ") ||
                line.startsWith("Setting up ")) {
                long total = Math.max(knownTotal, doneBytes + currentSize);
                int percent = total > 0 ? (int) Math.min(100, doneBytes * 100 / total) : 0;
                setStageProgress(percent, doneBytes, total, line.trim());
            }
        }
    }

    /** @return the size of a package in bytes, -1 when it cannot be parsed. */
    private static long parseAptSize(String line) {
        if (line == null) return -1;
        Matcher matcher = APT_SIZE_PATTERN.matcher(line);
        if (!matcher.find()) return -1;

        try {
            String number = matcher.group(1).replace(',', '.');
            double value = Double.parseDouble(number);
            String unit = matcher.group(2) == null ? "b" : matcher.group(2).toLowerCase(Locale.US);
            switch (unit) {
                case "kb":
                    return (long) (value * 1024);
                case "mb":
                    return (long) (value * 1024 * 1024);
                case "gb":
                    return (long) (value * 1024 * 1024 * 1024);
                default:
                    return (long) value;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** Report the progress of the current stage, used by the listeners of apt. */
    private static void setStageProgress(int percent, long bytesDone, long bytesTotal, String detail) {
        synchronized (sLock) {
            if (!sState.running) return;
            sState.stagePercent = Math.max(0, Math.min(100, percent));
            sState.bytesDone = bytesDone;
            sState.bytesTotal = bytesTotal;
            sState.detail = detail;
            sState.updatedAt = SystemClock.uptimeMillis();
            sState.recomputeOverallPercent();
            sState.addStep(detail);
        }
        publishState();
    }

    /* ==========================================================================================
     * Helpers
     * ========================================================================================== */

    /** The result of a child process. */
    private static final class CmdResult {
        final int exitCode;
        final String output;
        final String error;

        CmdResult(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.error = error;
        }
    }

    /** A package that apt would download. */
    private static final class PackageUri {
        String url;
        String fileName;
        long size;
        String hashAlgorithm;
        String hash;

        /** @return true when the file on disk has the checksum that apt expects. */
        boolean matches(File file) {
            if (hash == null || hash.isEmpty()) return file.isFile() && file.length() == size;
            if (!file.isFile()) return false;

            try {
                MessageDigest digest;
                String algorithm = hashAlgorithm == null ? "MD5" : hashAlgorithm;
                if ("sha256".equalsIgnoreCase(algorithm))
                    digest = MessageDigest.getInstance("SHA-256");
                else if ("sha512".equalsIgnoreCase(algorithm))
                    digest = MessageDigest.getInstance("SHA-512");
                else
                    digest = MessageDigest.getInstance("MD5");

                byte[] buffer = new byte[64 * 1024];
                try (InputStream in = new FileInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1)
                        digest.update(buffer, 0, read);
                }

                StringBuilder sb = new StringBuilder();
                for (byte b : digest.digest())
                    sb.append(String.format(Locale.US, "%02x", b));
                return sb.toString().equalsIgnoreCase(hash);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not check the checksum of " + file, e);
                return false;
            }
        }
    }

    /** Thrown when the installation cannot continue. */
    private static final class InstallException extends RuntimeException {
        InstallException(String message) {
            super(message);
        }
    }

    /** @return the npm architecture of the OpenCode build for this device, or null. */
    public static String getOpenCodeArch() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) return null;

        String abi = abis[0].toLowerCase();
        if (abi.startsWith("arm64")) return "arm64";
        if (abi.equals("x86_64") || abi.equals("amd64")) return "x64";
        return null;
    }

    /** @return the glibc loader of the toolchain, or null if the glibc runtime is not installed. */
    public static String findGlibcLoader() {
        File dir = new File(GLIBC_LIB_DIR);
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("ld-linux") && name.contains(".so.") && file.canExecute())
                return file.getAbsolutePath();
        }
        return null;
    }

    /**
     * @return the fingerprint of an installed binary, it matches the
     * {@code stat -L -c '%s %Y'} output that the launcher scripts compare.
     */
    public static String binaryFingerprint(File binary) {
        if (binary == null || !binary.isFile()) return "";
        // lastModified() is in milliseconds, %Y of stat prints seconds.
        return binary.length() + " " + (binary.lastModified() / 1000L);
    }

    /**
     * @return true when the binary itself is patched to be started directly, ie. its ELF
     * interpreter is the glibc loader. Only then the process that OpenCode spawns for the
     * background server sees the right {@code /proc/self/exe}.
     */
    public static boolean isNativeBinary(File binary) {
        if (binary == null || !binary.isFile()) return false;
        String stamp = readTrimmed(new File(NATIVE_INTERP_MARKER_PATH));
        return !stamp.isEmpty() && stamp.equals(binaryFingerprint(binary));
    }

    /**
     * Create the same-length aliases the relocated binaries look for. The Termux packages contain
     * {@code /data/data/com.termux/files/usr/...} in their rodata; the app rewrites that prefix to
     * {@link #DATA_DIR} (both are 31 characters), so the aliases must exist at the sandbox root.
     */
    private static void ensurePathAliases() {
        linkAlias(new File(GLIBC_ALIAS_DIR), new File(PREFIX, "glibc"));
        linkAlias(new File(ETC_ALIAS_DIR), new File(PREFIX, "etc"));
    }

    private static void linkAlias(File link, File target) {
        if (!target.exists()) return;
        try {
            Os.lstat(link.getAbsolutePath());
            return; // Already there, do not touch what the user may have made.
        } catch (Exception ignored) {
            // Does not exist yet.
        }
        try {
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            Log.i(LOG_TAG, "Created the path alias " + link + " -> " + target);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Could not create the path alias " + link, e);
        }
    }

    /**
     * Rewrite the compiled-in Termux prefix of the glibc loader to the sandbox root. The bytes are
     * replaced one to one, so the size and every offset of the ELF stay identical. Without this
     * the loader would look for libc in {@code /data/data/com.termux/...}, a directory this app
     * cannot read, and a binary with a patched PT_INTERP would not start.
     *
     * @return true when the loader can be used for a direct (native) start.
     */
    private static boolean relocateLoader(String loaderPath) {
        if (loaderPath == null) return false;
        byte[] oldPrefix = PREFIX_TERMUX.getBytes(StandardCharsets.US_ASCII);
        byte[] newPrefix = DATA_DIR.getBytes(StandardCharsets.US_ASCII);
        if (oldPrefix.length != newPrefix.length) {
            Log.w(LOG_TAG, "Cannot relocate the glibc loader: " + oldPrefix.length + " != " + newPrefix.length);
            return false;
        }

        File loader = new File(loaderPath);
        if (!loader.isFile()) return false;
        try {
            byte[] data = readAllBytes(loader);
            int hits = replaceBytes(data, oldPrefix, newPrefix);
            if (hits == 0) return true; // Already relocated (or nothing to rewrite).

            File temp = new File(loader.getAbsolutePath() + ".reloc");
            writeAllBytes(temp, data);
            try {
                Os.chmod(temp.getAbsolutePath(), 0755);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not make the relocated loader executable", e);
            }
            if (!temp.renameTo(loader)) {
                writeAllBytes(loader, data);
                try {
                    Os.chmod(loader.getAbsolutePath(), 0755);
                } catch (Exception ignored) {
                    // Not fatal.
                }
                deleteQuietly(temp);
            }
            Log.i(LOG_TAG, "Relocated " + hits + " path(s) of the glibc loader to " + DATA_DIR);
            return true;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Could not relocate the glibc loader " + loaderPath, e);
            return false;
        }
    }

    /**
     * Replace same-length strings inside a binary in place. Used for the login shell paths of bash,
     * which are compiled against the com.termux prefix and are unreadable from this app.
     */
    private static int patchFileStrings(File file, String... replacements) {
        if (file == null || !file.isFile()) return 0;
        try {
            byte[] data = readAllBytes(file);
            int total = 0;
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                byte[] oldBytes = replacements[i].getBytes(StandardCharsets.US_ASCII);
                byte[] newBytes = replacements[i + 1].getBytes(StandardCharsets.US_ASCII);
                if (oldBytes.length != newBytes.length) {
                    Log.w(LOG_TAG, "Cannot rewrite " + replacements[i] + ": the replacement differs in length");
                    continue;
                }
                total += replaceBytes(data, oldBytes, newBytes);
            }
            if (total == 0) return 0;

            File temp = new File(file.getAbsolutePath() + ".reloc");
            writeAllBytes(temp, data);
            try {
                Os.chmod(temp.getAbsolutePath(), 0755);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not make " + temp + " executable", e);
            }
            if (!temp.renameTo(file)) {
                writeAllBytes(file, data);
                try {
                    Os.chmod(file.getAbsolutePath(), 0755);
                } catch (Exception ignored) {
                    // Not fatal.
                }
                deleteQuietly(temp);
            }
            Log.i(LOG_TAG, "Patched " + total + " path(s) of " + file);
            return total;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Could not patch the strings of " + file, e);
            return 0;
        }
    }

    /** @return the number of replaced occurrences. */
    private static int replaceBytes(byte[] data, byte[] oldBytes, byte[] newBytes) {
        if (oldBytes.length == 0 || oldBytes.length != newBytes.length) return 0;
        int count = 0;
        for (int i = 0; i <= data.length - oldBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < oldBytes.length; j++) {
                if (data[i + j] != oldBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            System.arraycopy(newBytes, 0, data, i, newBytes.length);
            count++;
            i += oldBytes.length - 1;
        }
        return count;
    }

    private static byte[] readAllBytes(File file) throws IOException {
        long length = file.length();
        if (length <= 0 || length > Integer.MAX_VALUE)
            throw new IOException("Unexpected size " + length + " of " + file);
        byte[] data = new byte[(int) length];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != data.length)
                throw new IOException("Could not read all of " + file);
        }
        return data;
    }

    private static void writeAllBytes(File file, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    /** @return the file the opencode symlink points to. */
    public static File resolveLink(File link) {
        try {
            String target = android.system.Os.readlink(link.getAbsolutePath());
            File file = new File(target);
            if (!file.isAbsolute())
                file = new File(link.getParentFile(), target);
            return file;
        } catch (Exception e) {
            return link;
        }
    }

    /** @return true when the file exists and can be executed by the app. */
    public static boolean isExecutable(File file) {
        return file.exists() && file.canExecute();
    }

    private static void runOnUiThread(Context context, final Runnable runnable) {
        if (runnable == null) return;
        if (context == null) {
            runnable.run();
            return;
        }
        sMainHandler.post(runnable);
    }

    /** Save the state on the disk and notify the listeners about it. */
    private static void publishState() {
        final OpenCodeInstallState snapshot;
        synchronized (sLock) {
            sState.updatedAt = SystemClock.uptimeMillis();
            snapshot = copyOf(sState);
        }

        OpenCodeInstallState.save(snapshot);

        for (InstallListener listener : sListeners) {
            final InstallListener current = listener;
            sMainHandler.post(() -> {
                try {
                    current.onOpenCodeInstallStateChanged(snapshot);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "An install listener failed", e);
                }
            });
        }
    }

    /** @return a copy of the given state, the copy is safe to hand out. */
    private static OpenCodeInstallState copyOf(OpenCodeInstallState state) {
        OpenCodeInstallState copy = new OpenCodeInstallState();
        copy.stage = state.stage;
        copy.stagePercent = state.stagePercent;
        copy.overallPercent = state.overallPercent;
        copy.message = state.message;
        copy.detail = state.detail;
        copy.error = state.error;
        copy.bytesDone = state.bytesDone;
        copy.bytesTotal = state.bytesTotal;
        copy.running = state.running;
        copy.done = state.done;
        copy.failed = state.failed;
        copy.updatedAt = state.updatedAt;
        for (String step : state.getSteps())
            copy.steps.add(step);
        return copy;
    }

    private static void writeTextFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Could not create the directory " + parent);

        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
        }
    }

    private static void writeTextFileQuietly(File file, String content) {
        try {
            writeTextFile(file, content);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Could not write " + file, e);
        }
    }

    private static String readTextFile(File file) throws IOException {
        if (!file.isFile()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String readTextFileQuietly(File file) {
        try {
            String text = readTextFile(file);
            return text == null ? "" : text;
        } catch (IOException e) {
            return "";
        }
    }

    private static String readTrimmed(File file) {
        String text = readTextFileQuietly(file);
        return text.trim();
    }

    private static boolean copyFile(File source, File target) {
        try {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1)
                    out.write(buffer, 0, read);
            }
            return true;
        } catch (IOException e) {
            Log.w(LOG_TAG, "Could not copy " + source + " to " + target, e);
            return false;
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children)
                        deleteQuietly(child);
                }
            }
            file.delete();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Could not delete " + file, e);
        }
    }

    /** @return the last lines of a text, used for the error reports. */
    private static String tail(String text, int lines) {
        if (text == null) return "";
        String[] split = text.split("\n");
        int from = Math.max(0, split.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < split.length; i++)
            sb.append(split[i]).append('\n');
        return sb.toString();
    }
}

package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.util.Log;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Foreground service that hosts the OpenCode server natively on Android.
 *
 * <p>The official OpenCode CLI is a glibc binary. {@link OpenCodeBootstrap} installs the glibc
 * runtime into the app-private toolchain prefix and patches the binary's interpreter, so it can be
 * executed directly; if that patch is unavailable it is executed through the loader instead. The
 * server binds to {@code 127.0.0.1:<port>} and the {@link OpenCodeWebActivity} renders it inside a
 * WebView.</p>
 */
public final class OpenCodeService extends Service {

    private static final String TAG = "OpenCodeService";

    public static final String NOTIFICATION_CHANNEL_ID = "opencode_service_channel";
    public static final int NOTIFICATION_ID = 1340;

    public static final String ACTION_START = "com.opencode.android.opencode.START";
    public static final String ACTION_STOP = "com.opencode.android.opencode.STOP";

    private static volatile OpenCodeService sInstance;

    private volatile java.lang.Process process;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int port = 49374;
    /** True once the bootstrap reports that the toolchain and the binary are installed. */
    private volatile boolean bootstrapReady = false;
    /** True while the server is being stopped on purpose, its exit is not an error then. */
    private volatile boolean stopping;
    /** Path of the binary the running server was started from. */
    private volatile String runningBinaryPath;
    /** Serialises start/stop calls, the service can be asked to start from several places. */
    private static final Object SERVER_LOCK = new Object();
    /**
     * All socket work runs here: connecting to 127.0.0.1 on the main thread throws
     * {@link android.os.NetworkOnMainThreadException}, which would make every port check lie.
     * A single static worker is enough, there is only one service instance at a time.
     */
    private static final java.util.concurrent.ExecutorService WORKER =
        java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "opencode-service");
            thread.setDaemon(true);
            return thread;
        });
    /** Pid of the running server, also when this process inherited it from an older app run. */
    private volatile int serverPid = -1;
    /** True when the server was already running when this service was created. */
    private volatile boolean adopted;
    /** Remembers that we already tried to recover from a busy port, avoids endless retries. */
    private volatile boolean recoveredFromBusyPort;
    /** Pid of the server process, it survives app restarts so a stale server can be reused. */
    private static final String PID_FILE = OpenCodeBootstrap.HOME + "/.opencode/server.pid";

    /** The parameters of the last start request, they are passed through the intent. */
    public static final class Params {
        public int port = 49374;
    }

    private Params params = new Params();

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting the OpenCode server..."));
        // Make sure the Linux toolchain and OpenCode itself are installed first, then start the
        // server. The callback runs on the UI thread, the installation itself runs in the
        // background thread of the bootstrap.
        bootstrapReady = false;
        OpenCodeBootstrap.setupIfNeeded(this, () -> {
            bootstrapReady = OpenCodeBootstrap.isRuntimeComplete();
            if (!bootstrapReady) {
                Log.e(TAG, "The OpenCode runtime is still incomplete: "
                    + OpenCodeBootstrap.runtimeProblems());
                updateNotification("The installation did not finish, open the app for details.");
                return;
            }
            startServer();
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getStringExtra("action");
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        port = intent.getIntExtra("port", params.port);
        params.port = port;
        // Never start before the bootstrap finished, otherwise the missing glibc loader or the
        // missing binary would stop the service while the installation is still running. The
        // callback of setupIfNeeded starts the server as soon as the runtime is complete.
        if (bootstrapReady) {
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
        sInstance = null;
    }

    @Override
    public void onTaskRemoved(Intent intent) {
        super.onTaskRemoved(intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopSelf();
        }
    }

    /* ==========================================================================================
     * Server lifecycle
     * ========================================================================================== */

    /** @return true while the OpenCode server process is running. */
    public static boolean isRunning() {
        return sInstance != null;
    }

    /** Start the OpenCode server, it is a no-op when a server is already running. */
    public void startServer() {
        WORKER.execute(this::startServerBlocking);
    }

    /** The actual start procedure, it runs on {@link #WORKER} and never on the main thread. */
    private void startServerBlocking() {
        synchronized (SERVER_LOCK) {
            if (sInstance != this) {
                Log.i(TAG, "Ignoring a start request of a destroyed service instance.");
                return;
            }
            restartIfNewBinary();
            if (adopted && isPortOpen(port)) {
                Log.i(TAG, "The adopted OpenCode server is still running.");
                updateNotification("Running on http://127.0.0.1:" + port);
                return;
            }
            if (process != null) {
                if (process.isAlive()) {
                    Log.i(TAG, "The OpenCode server is already running.");
                    return;
                }
                // The reader thread has not noticed the exit yet, do not reuse the dead handle.
                process = null;
            }

            String loader = OpenCodeBootstrap.findGlibcLoader();
            File binary = resolveBinary();
            if (loader == null || binary == null) {
                Log.w(TAG, "The OpenCode server cannot be started: glibc runtime or binary is missing.");
                OpenCodeCrashLog.writeReport(this, "opencode-start",
                    "loader=" + loader + "\nbinary=" + binary
                        + "\nproblems=" + OpenCodeBootstrap.runtimeProblems());
                stopSelf();
                return;
            }

            // A server started by an earlier app process can still own the port: Android does not
            // kill the children of a dead app process. Reuse it when it runs the current binary,
            // stop it when it is an older version, so 'serve' never fails with EADDRINUSE.
            //
            // An update of the runtime also forces a fresh server: the running one mapped the
            // glibc of the old installation, which cannot resolve a single host name (see
            // ensureGlibcRelocated), and adopting it would keep the broken resolver alive.
            boolean glibcRewritten = OpenCodeBootstrap.ensureGlibcRelocated();
            int inherited = findServerPid(binary);
            if (inherited > 0 && isPortOpen(port)) {
                if (!glibcRewritten && acceptsCurrentPassword()) {
                    Log.i(TAG, "Reusing the OpenCode server that is already listening (pid "
                        + inherited + ").");
                    serverPid = inherited;
                    adopted = true;
                    recoveredFromBusyPort = false;
                    runningBinaryPath = binary.getAbsolutePath();
                    writePidFile(inherited);
                    updateNotification("Running on http://127.0.0.1:" + port);
                    return;
                }
                // A server of an earlier installation was started with another password, or it
                // still runs with the glibc of the previous installation (no DNS). The WebView
                // could never pair with the first one and never resolve a host with the second
                // one, so replace it with a fresh server.
                Log.w(TAG, "The inherited server was started with another password or an outdated"
                    + " runtime" + (glibcRewritten ? " (glibc was just relocated)" : "")
                    + ", restarting it.");
                killIfOurServer(inherited);
                waitForPortFree(4000);
                clearPidFile();
            }

            adopted = false;
            stopping = false;

            if (isPortOpen(port)) {
                Log.w(TAG, "The port " + port + " is used by a process that is not ours.");
                OpenCodeCrashLog.writeReport(this, "opencode-port",
                    "Port " + port + " is already in use and no OpenCode server process was"
                        + " found for it.\npid file: " + readPidFile()
                        + "\nbinary: " + binary.getAbsolutePath()
                        + "\nprocesses:\n" + describeOpenCodeProcesses());
            }

            startProcess(loader, binary);
        }
    }

    /** Spawn the server, the caller holds {@link #SERVER_LOCK}. */
    private void startProcess(String loader, File binary) {
        // Android's java.lang.Process has no pid(), so /system/bin/sh records its own pid and
        // then exec()s the server: the pid stays valid for the server and survives app restarts
        // through the pid file. exec keeps a single process, no extra shell is left behind.
        //
        // A binary whose PT_INTERP was patched is exec'ed directly. That matters: OpenCode spawns
        // its own `serve` child through /proc/self/exe, and when the loader is passed explicitly
        // that path points at ld-linux, so the child would run "ld-linux serve" and exit 127.
        boolean nativeBinary = OpenCodeBootstrap.isNativeBinary(binary);
        String exec;
        if (nativeBinary) {
            exec = "exec '" + binary.getAbsolutePath() + "' serve"
                + " --hostname 127.0.0.1 --port " + port;
        } else {
            exec = "exec '" + loader + "' --library-path '"
                + OpenCodeBootstrap.GLIBC_LIB_DIR + "' '" + binary.getAbsolutePath()
                + "' serve --hostname 127.0.0.1 --port " + port;
        }
        String command = "echo $$ > '" + PID_FILE + "'; " + exec;
        clearPidFile();
        // The glibc runtime of the prefix resolves names itself and cannot use the resolver of
        // Android: without resolv.conf every host lookup of the server fails with
        // "getaddrinfo", and the UI shows no models and no answers.
        OpenCodeBootstrap.refreshResolvConf(this);
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command);
        builder.directory(new File(OpenCodeBootstrap.HOME));
        builder.redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("HOME", OpenCodeBootstrap.HOME);
        builder.environment().put("PREFIX", OpenCodeBootstrap.PREFIX);
        builder.environment().put("PATH", OpenCodeBootstrap.PREFIX + "/bin:/system/bin");
        builder.environment().put("TMPDIR", OpenCodeBootstrap.PREFIX + "/tmp");
        builder.environment().put("TERM", "xterm-256color");
        builder.environment().put("LANG", "en_US.UTF-8");
        builder.environment().put("COLORTERM", "truecolor");
        // The pty of OpenCode starts "$SHELL -l"; make sure it is the patched bash of this prefix
        // and not the bionic /system/bin/sh, whose login startup files are different.
        builder.environment().put("SHELL", OpenCodeBootstrap.PREFIX + "/bin/bash");
        builder.environment().put("DEBIAN_FRONTEND", "noninteractive");
        builder.environment().put("OPENCODE_DISABLE_AUTOUPDATE", "1");
        // The 2.x server protects its API (/api, /api/session, …) with HTTP Basic auth. A fixed
        // password from the app preferences is used instead of the random one the server would
        // print, so the WebView can answer the authentication challenge itself.
        builder.environment().put("OPENCODE_SERVER_PASSWORD", OpenCodeBootstrap.getServerPassword(this));
        // The CA bundle of the ca-certificates package, Bun/Node would otherwise have no trust
        // store on Android and every HTTPS request of OpenCode would fail.
        builder.environment().put("SSL_CERT_FILE",
            OpenCodeBootstrap.PREFIX + "/etc/tls/cert.pem");
        builder.environment().put("NODE_EXTRA_CA_CERTS",
            OpenCodeBootstrap.PREFIX + "/etc/tls/cert.pem");
        // The tools OpenCode spawns (bash, rg, …) are Termux binaries whose RUNPATH points at the
        // com.termux prefix. The glibc directory is deliberately not part of this variable: it
        // contains a 'libc.so' linker script that the bionic linker cannot load as an ELF file.
        // For a native (patched) binary the loader still finds libc.so.6 and friends through its
        // own relocated tree; this directory only contains the bionic names.
        builder.environment().put("LD_LIBRARY_PATH", OpenCodeBootstrap.PREFIX + "/lib");
        // git from the Termux repository was built for the com.termux prefix: its helper programs
        // (git-remote-https, git-upload-pack, …) and its template directory live below the old
        // prefix, which is unreadable for this app. OpenCode runs git itself for the workspace
        // snapshots, so it needs these even without a shell.
        builder.environment().put("GIT_EXEC_PATH",
            OpenCodeBootstrap.PREFIX + "/libexec/git-core");
        File gitTemplates = new File(OpenCodeBootstrap.PREFIX, "share/git-core/templates");
        if (gitTemplates.isDirectory()) {
            builder.environment().put("GIT_TEMPLATE_DIR", gitTemplates.getAbsolutePath());
        }
        File gitSystemConfig = new File(OpenCodeBootstrap.PREFIX, "etc/gitconfig");
        if (gitSystemConfig.isFile()) {
            builder.environment().put("GIT_CONFIG_SYSTEM", gitSystemConfig.getAbsolutePath());
        } else {
            // Without this git exits with 128 on every call: it tries to read the system config
            // of the com.termux prefix, which belongs to another app.
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        }
        builder.environment().put("GIT_ATTR_NOSYSTEM", "1");
        builder.environment().put("CURL_CA_BUNDLE",
            OpenCodeBootstrap.PREFIX + "/etc/tls/cert.pem");
        File opensslConf = new File(OpenCodeBootstrap.PREFIX, "etc/openssl/openssl.cnf");
        if (opensslConf.isFile()) {
            builder.environment().put("OPENSSL_CONF", opensslConf.getAbsolutePath());
        }
        String ghToken = System.getenv("GH_TOKEN");
        if (ghToken != null && !ghToken.isEmpty()) {
            builder.environment().put("GH_TOKEN", ghToken);
        }
        builder.environment().put("OPENCODE_PORT", String.valueOf(port));

        try {
            process = builder.start();
        } catch (IOException e) {
            Log.e(TAG, "Could not start the OpenCode server process", e);
            stopSelf();
            return;
        }
        final Process started = process;
        final int startedPid = waitForPidFile(2000);
        serverPid = startedPid;
        adopted = false;
        recoveredFromBusyPort = false;
        runningBinaryPath = binary.getAbsolutePath();
        Log.i(TAG, "The OpenCode server process was started: " + port + " (pid " + startedPid + ")");

        Thread reader = new Thread(() -> {
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    Log.i(TAG, "opencode: " + line);
                    synchronized (tail) {
                        tail.addLast(line);
                        while (tail.size() > 300) tail.removeFirst();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "The OpenCode server reader stopped", e);
            }

            int exitCode = -1;
            try {
                exitCode = started.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            boolean current = process == started;
            if (current) {
                process = null;
                serverPid = -1;
                adopted = false;
                clearPidFile(startedPid);
            }
            Log.w(TAG, "The OpenCode server exited with " + exitCode);
            boolean intentional = stopping || !current;
            boolean portBusy = false;
            synchronized (tail) {
                for (String line : tail) {
                    if (line.contains("Is port") && line.contains("in use")) {
                        portBusy = true;
                        break;
                    }
                }
            }
            // A server that dies right away usually means a missing library or a broken patch, the
            // output is unreadable for the user in logcat, so keep it in the app-private
            // diagnostics folder.
            if (exitCode != 0 && !intentional) {
                StringBuilder sb = new StringBuilder();
                sb.append("OpenCode server exited with ").append(exitCode).append('\n');
                sb.append("port: ").append(port).append('\n');
                sb.append("loader: ").append(loader).append('\n');
                sb.append("binary: ").append(binary.getAbsolutePath()).append('\n');
                sb.append("command: ").append(loader).append(" --library-path ")
                    .append(OpenCodeBootstrap.GLIBC_LIB_DIR).append(' ')
                    .append(binary.getAbsolutePath()).append(" serve --port ").append(port).append('\n');
                sb.append("---- output ----\n");
                synchronized (tail) {
                    for (String line : tail) sb.append(line).append('\n');
                }
                OpenCodeCrashLog.writeReport(OpenCodeService.this, "opencode-server", sb.toString());
                updateNotification("The OpenCode server stopped (exit " + exitCode
                    + "), the log is in Android/data/com.opencode.android/files/OpenCodeForAndroid");
            }
            // Another instance can win the race for the port. The winner is usually our own
            // server from a previous app process, so adopt it instead of staying dead.
            if (!intentional && portBusy && !recoveredFromBusyPort) {
                recoveredFromBusyPort = true;
                Log.i(TAG, "The port was busy, retrying with the server that holds it.");
                handler.postDelayed(() -> {
                    // The service may have been destroyed in the meantime.
                    if (sInstance == this) startServer();
                }, 1500);
            }
        }, "opencode-service-reader");
        reader.setDaemon(true);
        reader.start();

        updateNotification("Running on http://127.0.0.1:" + port);
    }

    /** Kill the OpenCode server process, the next start command starts it again. */
    public void stopServer() {
        WORKER.execute(() -> {
            synchronized (SERVER_LOCK) {
                if (process == null && !adopted) {
                    stopSelf();
                    return;
                }
                stopping = true;
                if (process != null) {
                    terminate(process);
                    process = null;
                } else if (serverPid > 0) {
                    killIfOurServer(serverPid);
                }
                serverPid = -1;
                adopted = false;
                clearPidFile();
                waitForPortFree(3000);
                stopSelf();
            }
        });
    }

    /** Stop the server without stopping the service, the caller holds {@link #SERVER_LOCK}. */
    private void stopExisting() {
        if (process == null && !adopted) return;
        stopping = true;
        if (process != null) {
            terminate(process);
            process = null;
        }
        if (adopted && serverPid > 0) killIfOurServer(serverPid);
        serverPid = -1;
        adopted = false;
        clearPidFile();
        waitForPortFree(4000);
        stopping = false;
    }

    /* ---------------------------------------------------------------------------------------
     * Stale process handling
     *
     * The OpenCode server is a child of the app process. Android does not always kill it when
     * the app process dies, so a later launch can find the port occupied by its own server from
     * an earlier run. Adoption is preferred over starting a second server: the orphan keeps the
     * WebView working and no state (sessions) is lost.
     * --------------------------------------------------------------------------------------- */

    private static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check with the Basic credentials of this app that the server that already holds the port
     * belongs to us. Retries once, a freshly started server may need a moment before it answers.
     */
    private boolean acceptsCurrentPassword() {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://127.0.0.1:" + port + "/api/info");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(2500);
                connection.setUseCaches(false);
                String credentials = OpenCodeBootstrap.SERVER_USER + ":"
                    + OpenCodeBootstrap.getServerPassword(this);
                connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) return true;
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED
                    || code == HttpURLConnection.HTTP_FORBIDDEN) return false;
            } catch (Exception e) {
                Log.w(TAG, "Could not probe the OpenCode server on port " + port, e);
            } finally {
                if (connection != null) connection.disconnect();
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void waitForPortFree(long timeoutMs) {        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (isPortOpen(port) && SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * @return the pid of a server that runs the given binary and can be reused, or -1. A server
     *         of an older binary is stopped, because it would serve an outdated runtime.
     */
    private int findServerPid(File binary) {
        int pid = readPidFile();
        if (isOurServer(pid, binary)) return pid;
        if (pid > 0 && isOurCmdline(readCmdline(pid))) {
            Log.i(TAG, "Stopping the stale OpenCode server " + pid + " (another binary).");
            killPid(pid);
            waitForPortFree(4000);
            clearPidFile();
        }

        // Older app versions did not write the pid file, fall back to a /proc scan.
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return -1;
        for (File entry : entries) {
            if (!entry.getName().matches("[0-9]+")) continue;
            int candidate = Integer.parseInt(entry.getName());
            if (candidate == android.os.Process.myPid()) continue;
            String cmdline = readCmdline(candidate);
            if (!isOurCmdline(cmdline)) continue;
            if (isOurServer(candidate, binary)) return candidate;
            Log.i(TAG, "Stopping the stale OpenCode server " + candidate + " (another binary).");
            killPid(candidate);
            waitForPortFree(4000);
            clearPidFile();
        }
        return -1;
    }

    /** @return true when the command line belongs to the server we start. */
    private static boolean isOurCmdline(String cmdline) {
        // The loader may keep its own command line or replace it with the binary and its
        // arguments, the installed path is the part that is always present.
        return cmdline != null && cmdline.contains("/.opencode/installed/opencode-v");
    }

    private static boolean isOurServer(int pid, File binary) {
        String cmdline = readCmdline(pid);
        return isOurCmdline(cmdline) && cmdline.contains(binary.getAbsolutePath());
    }

    /** @return the space separated command line of a process, null when it is not readable. */
    private static String readCmdline(int pid) {
        if (pid <= 0) return null;
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] buffer = new byte[8192];
            int read = in.read(buffer);
            if (read <= 0) return null;
            return new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8)
                .replace('\0', ' ').trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static int readPidFile() {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(PID_FILE)))) {
            String line = reader.readLine();
            return line == null ? -1 : Integer.parseInt(line.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void writePidFile(int pid) {
        File file = new File(PID_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (java.io.FileWriter writer = new java.io.FileWriter(file, false)) {
            writer.write(Integer.toString(pid));
        } catch (Exception e) {
            Log.w(TAG, "Could not write the server pid file", e);
        }
    }

    /** Wait until the shell of the freshly spawned server recorded its pid. */
    private int waitForPidFile(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            int pid = readPidFile();
            if (pid > 0) return pid;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return readPidFile();
    }

    private void clearPidFile() {
        try {
            new File(PID_FILE).delete();
        } catch (Exception ignored) {
            // Not important, the pid is validated against /proc before it is used.
        }
    }

    /** Remove a pid file that still points at the given, already exited process. */
    private void clearPidFile(int pid) {
        if (readPidFile() == pid) clearPidFile();
    }

    private static void killPid(int pid) {
        if (pid <= 0) return;
        try {
            android.os.Process.sendSignal(pid, 9);
        } catch (Exception e) {
            Log.w(TAG, "Could not kill the process " + pid, e);
        }
    }

    /** Kill a pid only when it still belongs to an OpenCode server of this app. */
    private static void killIfOurServer(int pid) {
        if (!isOurCmdline(readCmdline(pid))) return;
        killPid(pid);
    }

    /** Diagnostic list of the OpenCode server processes of this app, used in port conflicts. */
    private static String describeOpenCodeProcesses() {
        StringBuilder out = new StringBuilder();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return "  (no access to /proc)";
        for (File entry : entries) {
            if (!entry.getName().matches("[0-9]+")) continue;
            String cmdline = readCmdline(Integer.parseInt(entry.getName()));
            if (isOurCmdline(cmdline)) {
                out.append("  ").append(entry.getName()).append(": ").append(cmdline).append('\n');
            }
        }
        return out.length() == 0 ? "  (none)" : out.toString();
    }

    /** Stop a child process without blocking the caller for longer than a few seconds. */
    private static void terminate(java.lang.Process target) {
        try {
            target.destroy();
            if (!target.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                target.destroyForcibly();
        } catch (Exception ignored) {
            // The process is already gone.
        }
    }

    /**
     * Stop the running server when the installed binary is not the one it was started from, so a
     * freshly downloaded update is picked up without killing the app.
     */
    private void restartIfNewBinary() {
        if (process == null && !adopted) return;
        File binary = resolveBinary();
        if (binary == null) return;
        if (binary.getAbsolutePath().equals(runningBinaryPath)) return;
        Log.i(TAG, "The OpenCode binary changed, restarting the server.");
        stopExisting();
    }

    private File resolveBinary() {
        File link = new File(OpenCodeBootstrap.LINK_PATH);
        if (!link.exists()) {
            // The installed binary lives in the install directory.
            File dir = new File(OpenCodeBootstrap.INSTALL_DIR);
            File[] binaries = dir.listFiles();
            if (binaries == null) return null;
            for (File binary : binaries) {
                if (binary.isFile() && binary.getName().startsWith("opencode-v")) {
                    return binary;
                }
            }
            return null;
        }
        return OpenCodeBootstrap.resolveLink(link);
    }

    /* ==========================================================================================
     * Notification
     * ========================================================================================== */

    /** @return true when the FOREGROUND_SERVICE_SPECIAL_USE permission was granted. */
    private boolean hasSpecialUsePermission() {
        return checkSelfPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE") == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "OpenCode server",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("The OpenCode server that runs natively on Android.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "OpenCode server",
            NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);

        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            new Intent(this, OpenCodeWebActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("OpenCode server")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasSpecialUsePermission()) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return notification;
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("OpenCode server")
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(
                this,
                0,
                new Intent(this, OpenCodeWebActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            ))
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasSpecialUsePermission()) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        manager.notify(NOTIFICATION_ID, notification);
    }
}

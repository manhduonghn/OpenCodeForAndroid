package com.opencode.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service that hosts the OpenCode server natively on Android.
 *
 * The official OpenCode CLI is a glibc binary. [OpenCodeBootstrap] installs the glibc runtime into
 * the app-private toolchain prefix and patches the binary's interpreter, so it can be executed
 * directly; if that patch is unavailable it is executed through the loader instead. The server
 * binds to `127.0.0.1:<port>` and the [OpenCodeWebActivity] renders it inside a WebView.
 */
class OpenCodeService : Service() {

    /** The parameters of the last start request, they are passed through the intent. */
    class Params {
        var port: Int = OpenCodeService.DEFAULT_PORT
    }

    private var process: Process? = null
    private val handler = Handler(Looper.getMainLooper())
    private var port: Int = DEFAULT_PORT

    /** True once the bootstrap reports that the toolchain and the binary are installed. */
    @Volatile
    private var bootstrapReady = false

    /** True while the server is being stopped on purpose, its exit is not an error then. */
    @Volatile
    private var stopping = false

    /** Path of the binary the running server was started from. */
    @Volatile
    private var runningBinaryPath: String? = null

    /** Pid of the running server, also when this process inherited it from an older app run. */
    @Volatile
    private var serverPid = -1

    /** True when the server was already running when this service was created. */
    @Volatile
    private var adopted = false

    /** Remembers that we already tried to recover from a busy port, avoids endless retries. */
    @Volatile
    private var recoveredFromBusyPort = false

    private var params = Params()

    /** PARTIAL_WAKE_LOCK that keeps the server alive while the screen is off. */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Wi-Fi lock that keeps the radio awake, like Termux does for `termux-wake-lock`. Without it
     * the Wi-Fi stack can go into power save after a while even when the CPU stays awake.
     */
    private var wifiLock: WifiManager.WifiLock? = null

    /** Whether [networkCallback] is registered, so onDestroy only unregisters a live callback. */
    private var networkCallbackRegistered = false

    /**
     * The resolver configuration follows the default network: Wi-Fi, mobile data and a VPN each
     * report different DNS servers. A network handover while the screen is off used to leave the
     * glibc runtime of the prefix with the dead servers of the previous network, and every host
     * lookup of the running server failed with "fetch failed".
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshResolvConfInBackground()

        override fun onLost(network: Network) = refreshResolvConfInBackground()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            refreshResolvConfInBackground()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            refreshResolvConfInBackground()
    }

    private fun refreshResolvConfInBackground() {
        if (sInstance !== this) return
        try {
            WORKER.execute { OpenCodeBootstrap.refreshResolvConf(applicationContext) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the resolver refresh", e)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val manager = getSystemService(ConnectivityManager::class.java) ?: return
            manager.registerDefaultNetworkCallback(networkCallback, handler)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the default network", e)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        networkCallbackRegistered = false
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop watching the default network", e)
        }
    }

    /**
     * How often the server was restarted after an unexpected exit, used for the exponential
     * backoff. It is reset when the server ran long enough to count as healthy again.
     */
    @Volatile
    private var restartAttempts = 0

    /** Last status line of the server, shown when no chat session is open. */
    @Volatile
    private var serverStatusText = "Starting the OpenCode server..."

    /** The session the notification is currently about. */
    @Volatile
    private var activeSession: OpenCodeApi.Session? = null

    private var sessionPolling = false

    private val sessionPoll = object : Runnable {
        override fun run() {
            if (!sessionPolling) return
            WORKER.execute { refreshActiveSession() }
            handler.postDelayed(this, SESSION_POLL_INTERVAL_MS)
        }
    }

    private var heartbeatRunning = false

    /**
     * Periodic self-check of the server. The reader thread reports the exit of a spawned server,
     * but an adopted server has no reader, and an exit notification can be missed when Android
     * freezes the app process. This catches both without waiting for the 15 minute watchdog.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!heartbeatRunning) return
            WORKER.execute { checkServerAlive() }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        createNotificationChannel()
        applyWakeLock()
        startForegroundCompat(buildNotification())
        startSessionPolling()
        startHeartbeat()
        registerNetworkCallback()
        // The watchdog is persisted, but an update of the app or a disabled job can lose it.
        OpenCodeWatchdog.ensureScheduled(this)
        // Make sure the Linux toolchain and OpenCode itself are installed first, then start the
        // server. The callback runs on the UI thread, the installation itself runs in the
        // background thread of the bootstrap.
        bootstrapReady = false
        OpenCodeBootstrap.setupIfNeeded(this) {
            bootstrapReady = OpenCodeBootstrap.isRuntimeComplete()
            if (!bootstrapReady) {
                Log.e(TAG, "The OpenCode runtime is still incomplete: ${OpenCodeBootstrap.runtimeProblems()}")
                updateNotification("The installation did not finish, open the app for details.")
                return@setupIfNeeded
            }
            startServer()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Android restarted the service after it killed the app process, so there is no
            // intent to repeat. Returning START_STICKY without starting anything left the port
            // dead until the 15 minute watchdog; resume the server right here instead.
            Log.i(TAG, "The service was restarted without an intent, resuming the server.")
            if (OpenCodeSettings.stoppedByUser(this)) {
                // A Stop that raced with the process death must win.
                stopSelf()
                return START_NOT_STICKY
            }
            OpenCodeWatchdog.ensureScheduled(this)
            // onCreate may still be installing the runtime; its callback starts the server then.
            if (bootstrapReady) startServer()
            return START_STICKY
        }

        val action = intent.getStringExtra("action")
        if (action == ACTION_STOP) {
            // Remember the explicit stop: otherwise the watchdog would start the server again a
            // few minutes later and the Stop action would look broken.
            OpenCodeSettings.setStoppedByUser(this, true)
            OpenCodeWatchdog.cancel(this)
            // stopServer() kills the child first and calls stopSelf() when it is gone, so the
            // server cannot survive as an orphan of a service that was destroyed immediately.
            stopServer()
            return START_NOT_STICKY
        }

        // Any real start (app, notification, watchdog, boot) overrides an earlier Stop.
        OpenCodeSettings.setStoppedByUser(this, false)
        OpenCodeWatchdog.ensureScheduled(this)

        if (action == ACTION_RESTART) {
            restartAttempts = 0
            restartServer()
            return START_STICKY
        }

        port = intent.getIntExtra(EXTRA_PORT, params.port)
        params.port = port
        // Never start before the bootstrap finished, otherwise the missing glibc loader or the
        // missing binary would stop the service while the installation is still running. The
        // callback of setupIfNeeded starts the server as soon as the runtime is complete.
        if (bootstrapReady) startServer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSessionPolling()
        stopHeartbeat()
        unregisterNetworkCallback()
        handler.removeCallbacksAndMessages(null)
        stopServer()
        releaseWakeLock()
        getSystemService(NotificationManager::class.java)?.cancel(PHANTOM_NOTIFICATION_ID)
        super.onDestroy()
        sInstance = null
    }

    override fun onTaskRemoved(intent: Intent?) {
        super.onTaskRemoved(intent)
        // Swiping the app away only removes the task, not the server: the foreground service must
        // keep running so a running chat is not interrupted. The old code called stopSelf() here,
        // which also killed the server and was the main reason a swipe ended the session.
        Log.i(TAG, "The task was removed from the recents list, keeping the server alive.")
        OpenCodeWatchdog.ensureScheduled(this)
        postNotification()
    }

    /* ------------------------------------------------------------------ server lifecycle */

    /** Start the OpenCode server, it is a no-op when a server is already running. */
    fun startServer() {
        WORKER.execute { startServerBlocking() }
    }

    /** The actual start procedure, it runs on [WORKER] and never on the main thread. */
    private fun startServerBlocking() {
        synchronized(SERVER_LOCK) {
            if (sInstance !== this) {
                Log.i(TAG, "Ignoring a start request of a destroyed service instance.")
                return
            }
            restartIfNewBinary()
            if (adopted && isPortOpen(port)) {
                Log.i(TAG, "The adopted OpenCode server is still running.")
                markServerRunning()
                return
            }
            process?.let { running ->
                if (running.isAlive) {
                    Log.i(TAG, "The OpenCode server is already running.")
                    return
                }
                // The reader thread has not noticed the exit yet, do not reuse the dead handle.
                process = null
            }

            val loader = OpenCodeBootstrap.findGlibcLoader()
            val binary = resolveBinary()
            if (loader == null || binary == null) {
                Log.w(TAG, "The OpenCode server cannot be started: glibc runtime or binary is missing.")
                stopSelf()
                return
            }

            // A server started by an earlier app process can still own the port: Android does not
            // kill the children of a dead app process. Reuse it when it runs the current binary,
            // stop it when it is an older version, so 'serve' never fails with EADDRINUSE.
            //
            // An update of the runtime also forces a fresh server: the running one mapped the
            // glibc of the old installation, which cannot resolve a single host name (see
            // ensureGlibcRelocated), and adopting it would keep the broken resolver alive.
            val glibcRewritten = OpenCodeBootstrap.ensureGlibcRelocated()
            val inherited = findServerPid(binary)
            if (inherited > 0 && isPortOpen(port)) {
                if (!glibcRewritten && acceptsCurrentPassword()) {
                    Log.i(TAG, "Reusing the OpenCode server that is already listening (pid $inherited).")
                    serverPid = inherited
                    adopted = true
                    recoveredFromBusyPort = false
                    runningBinaryPath = binary.absolutePath
                    writePidFile(inherited)
                    markServerRunning()
                    return
                }
                // A server of an earlier installation was started with another password, or it
                // still runs with the glibc of the previous installation (no DNS). The WebView
                // could never pair with the first one and never resolve a host with the second
                // one, so replace it with a fresh server.
                Log.w(
                    TAG,
                    "The inherited server was started with another password or an outdated runtime" +
                        (if (glibcRewritten) " (glibc was just relocated)" else "") + ", restarting it.",
                )
                killIfOurServer(inherited)
                waitForPortFree(4000)
                clearPidFile()
            }

            adopted = false
            stopping = false

            if (isPortOpen(port)) {
                Log.w(TAG, "The port $port is used by a process that is not ours.")
            }

            startProcess(loader, binary)
        }
    }

    /** Spawn the server, the caller holds [SERVER_LOCK]. */
    private fun startProcess(loader: String, binary: File) {
        // Android's java.lang.Process has no pid(), so /system/bin/sh records its own pid and
        // then exec()s the server: the pid stays valid for the server and survives app restarts
        // through the pid file. exec keeps a single process, no extra shell is left behind.
        //
        // A binary whose PT_INTERP was patched is exec'ed directly. That matters: OpenCode spawns
        // its own `serve` child through /proc/self/exe, and when the loader is passed explicitly
        // that path points at ld-linux, so the child would run "ld-linux serve" and exit 127.
        val exec = if (OpenCodeBootstrap.isNativeBinary(binary)) {
            "exec '${binary.absolutePath}' serve --hostname 127.0.0.1 --port $port"
        } else {
            "exec '$loader' --library-path '${OpenCodeBootstrap.GLIBC_LIB_DIR}'" +
                " '${binary.absolutePath}' serve --hostname 127.0.0.1 --port $port"
        }
        val command = "echo $$ > '$PID_FILE'; $exec"
        clearPidFile()
        // The glibc runtime of the prefix resolves names itself and cannot use the resolver of
        // Android: without resolv.conf every host lookup of the server fails with
        // "getaddrinfo", and the UI shows no models and no answers.
        OpenCodeBootstrap.refreshResolvConf(this)

        val builder = ProcessBuilder("/system/bin/sh", "-c", command)
        builder.directory(File(OpenCodeBootstrap.HOME))
        builder.redirectErrorStream(true)
        val service = this
        builder.environment().apply {
            clear()
            // A JDK installed with 'pkg install openjdk' is only usable when the loader and PATH
            // know about it: the Termux RUNPATH points at the com.termux prefix, and the package
            // relies on update-alternatives for bin/java.
            val javaHome = OpenCodeBootstrap.javaHome()
            put("HOME", OpenCodeBootstrap.HOME)
            put("PREFIX", OpenCodeBootstrap.PREFIX)
            put(
                "PATH",
                if (javaHome == null) {
                    "${OpenCodeBootstrap.PREFIX}/bin:/system/bin"
                } else {
                    "${OpenCodeBootstrap.PREFIX}/bin:$javaHome/bin:/system/bin"
                },
            )
            if (javaHome != null) put("JAVA_HOME", javaHome)
            put("TMPDIR", "${OpenCodeBootstrap.PREFIX}/tmp")
            put("TERM", "xterm-256color")
            put("LANG", "en_US.UTF-8")
            put("COLORTERM", "truecolor")
            // The pty of OpenCode starts "$SHELL -l"; make sure it is the patched bash of this
            // prefix and not the bionic /system/bin/sh, whose login startup files are different.
            put("SHELL", "${OpenCodeBootstrap.PREFIX}/bin/bash")
            put("DEBIAN_FRONTEND", "noninteractive")
            put("OPENCODE_DISABLE_AUTOUPDATE", "1")
            // The 2.x server protects its API (/api, /api/session, …) with HTTP Basic auth. A
            // fixed password from the app preferences is used instead of the random one the
            // server would print, so the WebView can answer the authentication challenge itself.
            put("OPENCODE_SERVER_PASSWORD", OpenCodeBootstrap.getServerPassword(service))
            // The CA bundle of the ca-certificates package, Bun/Node would otherwise have no
            // trust store on Android and every HTTPS request of OpenCode would fail.
            put("SSL_CERT_FILE", "${OpenCodeBootstrap.PREFIX}/etc/tls/cert.pem")
            put("NODE_EXTRA_CA_CERTS", "${OpenCodeBootstrap.PREFIX}/etc/tls/cert.pem")
            // The tools OpenCode spawns (bash, rg, …) are Termux binaries whose RUNPATH points at
            // the com.termux prefix. The glibc directory is deliberately not part of this
            // variable: it contains a 'libc.so' linker script that the bionic linker cannot load
            // as an ELF file. For a native (patched) binary the loader still finds libc.so.6 and
            // friends through its own relocated tree; this directory only contains the bionic
            // names.
            put(
                "LD_LIBRARY_PATH",
                buildString {
                    append(OpenCodeBootstrap.PREFIX).append("/lib")
                    if (javaHome != null) {
                        // The JDK was built for /data/data/com.termux: its RUNPATH is unusable,
                        // the loader finds libjli/libjvm and their dependencies here instead.
                        append(':').append(javaHome).append("/lib")
                        append(':').append(javaHome).append("/lib/server")
                    }
                },
            )
            // git from the Termux repository was built for the com.termux prefix: its helper
            // programs (git-remote-https, git-upload-pack, …) and its template directory live
            // below the old prefix, which is unreadable for this app. OpenCode runs git itself
            // for the workspace snapshots, so it needs these even without a shell.
            put("GIT_EXEC_PATH", "${OpenCodeBootstrap.PREFIX}/libexec/git-core")
            File(OpenCodeBootstrap.PREFIX, "share/git-core/templates")
                .takeIf { it.isDirectory }
                ?.let { put("GIT_TEMPLATE_DIR", it.absolutePath) }
            val gitSystemConfig = File(OpenCodeBootstrap.PREFIX, "etc/gitconfig")
            if (gitSystemConfig.isFile) {
                put("GIT_CONFIG_SYSTEM", gitSystemConfig.absolutePath)
            } else {
                // Without this git exits with 128 on every call: it tries to read the system
                // config of the com.termux prefix, which belongs to another app.
                put("GIT_CONFIG_NOSYSTEM", "1")
            }
            put("GIT_ATTR_NOSYSTEM", "1")
            put("CURL_CA_BUNDLE", "${OpenCodeBootstrap.PREFIX}/etc/tls/cert.pem")
            File(OpenCodeBootstrap.PREFIX, "etc/openssl/openssl.cnf")
                .takeIf { it.isFile }
                ?.let { put("OPENSSL_CONF", it.absolutePath) }
            val gitHubToken = System.getenv("GH_TOKEN")?.takeIf { it.isNotEmpty() }
                ?: OpenCodeGitHub.storedToken()
            gitHubToken?.let { put("GH_TOKEN", it) }
            put("OPENCODE_PORT", port.toString())
        }

        val started = try {
            builder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the OpenCode server process", e)
            stopSelf()
            return
        }
        process = started
        val startedPid = waitForPidFile(2000)
        serverPid = startedPid
        adopted = false
        recoveredFromBusyPort = false
        runningBinaryPath = binary.absolutePath
        Log.i(TAG, "The OpenCode server process was started: $port (pid $startedPid)")
        markServerRunning()

        Thread({ watchServer(started, startedPid) }, "opencode-service-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** Clear the explanation of an earlier phantom kill and publish the new server state. */
    private fun markServerRunning() {
        // The WebView pairs with the server once. When the server is replaced in the background
        // (a restart, or a second start after the app process was killed), the session cookie and
        // the basic credentials of the old pairing are stale; this counter tells the UI to pair
        // again instead of showing a dead page.
        sServerGeneration++
        getSystemService(NotificationManager::class.java)?.cancel(PHANTOM_NOTIFICATION_ID)
        // Register the server in the state file that the TUI and the CLI of OpenCode discover.
        // Without it `opencode` starts a second server of its own, which then fights for the
        // port that this service already owns.
        writeServiceRegistration()
        updateNotification("Running on http://127.0.0.1:$port")
    }

    /**
     * Drain the output of the server (a full pipe would block it) and react to its exit.
     */
    private fun watchServer(started: Process, startedPid: Int) {
        val startedAt = SystemClock.uptimeMillis()
        var portBusy = false
        try {
            BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    Log.i(TAG, "opencode: $line")
                    if (line.contains("Is port") && line.contains("in use")) portBusy = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "The OpenCode server reader stopped", e)
        }

        var exitCode = -1
        try {
            exitCode = started.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val ranFor = SystemClock.uptimeMillis() - startedAt
        val current = process === started
        if (current) {
            process = null
            serverPid = -1
            adopted = false
            clearPidFile(startedPid)
            clearServiceRegistration(startedPid)
        }
        Log.w(TAG, "The OpenCode server exited with $exitCode after ${ranFor}ms")

        val intentional = stopping || !current
        if (intentional) return

        // A server that stayed up long enough is healthy again: its next death starts a fresh
        // backoff instead of continuing the delay of an earlier crash loop.
        if (ranFor >= HEALTHY_UPTIME_MS) restartAttempts = 0

        // `opencode service stop` sends SIGTERM to the registered server. That is an explicit
        // request of the user, exactly like the Stop action of the notification: the watchdog
        // must not start the server again a few minutes later.
        if (exitCode == 143) {
            Log.i(TAG, "The OpenCode server was stopped by 'opencode service stop'.")
            handleUserStop()
            return
        }
        // Android's phantom process killer (and the low memory killer) sends SIGKILL, which the
        // JVM reports as 9 or 137. A foreground service and a wake lock cannot prevent that, so
        // explain it instead of showing a generic crash and retry with a backoff.
        if (exitCode == 9 || exitCode == 137) {
            Log.w(TAG, "The OpenCode server was killed by Android (exit $exitCode).")
            handlePhantomKill()
            return
        }
        // Another instance can win the race for the port. The winner is usually our own server
        // from a previous app process, so adopt it instead of staying dead.
        if (portBusy && !recoveredFromBusyPort) {
            recoveredFromBusyPort = true
            Log.i(TAG, "The port was busy, retrying with the server that holds it.")
            handler.postDelayed({
                // The service may have been destroyed in the meantime.
                if (sInstance === this) startServer()
            }, 1500)
            return
        }
        // Every other exit (a crash, a missing library, an OOM kill of the whole cgroup) used to
        // leave the port dead until the 15 minute watchdog ran. Restart it with the same backoff.
        Log.w(TAG, "The OpenCode server stopped unexpectedly (exit $exitCode), restarting it.")
        updateNotification("The OpenCode server stopped (exit $exitCode), restarting it...")
        restartAfterExit()
    }

    /**
     * Explain a SIGKILL of the server and start it again with an exponential backoff. The delay
     * avoids a tight loop when the phantom process killer is still deciding to kill every new
     * child of the app.
     */
    private fun handlePhantomKill() {
        val explanation = getString(R.string.opencode_notification_phantom_text)
        updateNotification(explanation)
        postPhantomKillNotification()
        restartAfterExit()
    }

    /**
     * Start the server again after it died on its own, with an exponential backoff. The delay
     * avoids a tight loop when the phantom process killer is still deciding to kill every new
     * child of the app, or when a broken installation makes the binary exit right after start.
     */
    private fun restartAfterExit() {
        if (sInstance !== this || stopping) return
        val attempt = restartAttempts.coerceAtMost(6)
        val delay = (5_000L shl attempt).coerceAtMost(5 * 60_000L)
        restartAttempts++
        Log.i(TAG, "Restarting the server in ${delay}ms (attempt $restartAttempts).")
        handler.postDelayed({
            if (sInstance !== this || stopping) return@postDelayed
            if (process?.isAlive == true) return@postDelayed
            startServer()
        }, delay)
    }

    /**
     * Runs on [WORKER]. Restart the server when it should be running but nothing listens on the
     * port. The reader thread of a spawned server reports its own exit, so this mainly covers an
     * adopted server (which has no reader) and an exit that was missed while the app was frozen.
     */
    private fun checkServerAlive() {
        if (sInstance !== this || stopping || !bootstrapReady) return
        if (OpenCodeSettings.stoppedByUser(this)) return
        if (process?.isAlive == true) return
        if (isPortOpen(port)) return
        Log.w(TAG, "The heartbeat found no server on port $port, starting it again.")
        startServerBlocking()
    }

    /**
     * Handle a stop that was requested outside the app (`opencode service stop`). It is treated
     * like the Stop action: remember the explicit stop, cancel the watchdog and remove the
     * foreground service so its notification does not claim a dead server is running.
     */
    private fun handleUserStop() {
        OpenCodeSettings.setStoppedByUser(this, true)
        OpenCodeWatchdog.cancel(this)
        updateNotification("The OpenCode server was stopped.")
        handler.post {
            if (sInstance === this) stopSelf()
        }
    }

    /**
     * A separate, dismissible notification with the phantom process killer commands. The ongoing
     * notification cannot be dismissed, so the instructions would otherwise stay forever.
     */
    private fun postPhantomKillNotification() {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, OpenCodeWebActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restartIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, OpenCodeService::class.java)
                .putExtra("action", ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = getString(R.string.opencode_notification_phantom_big)
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(getString(R.string.opencode_notification_phantom_title))
            .setContentText(getString(R.string.opencode_notification_phantom_text))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .addAction(
                R.drawable.ic_refresh,
                getString(R.string.opencode_notification_action_restart),
                restartIntent,
            )
            .build()
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(PHANTOM_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not post the phantom killer notification", e)
        }
    }

    /** Kill the OpenCode server process, the next start command starts it again. */
    fun stopServer() {
        WORKER.execute {
            synchronized(SERVER_LOCK) {
                restartAttempts = 0
                if (process == null && !adopted) {
                    clearServiceRegistration(serverPid)
                    clearPidFile()
                    stopSelf()
                    return@synchronized
                }
                stopping = true
                val stoppedPid = serverPid
                val running = process
                if (running != null) {
                    terminate(running)
                    process = null
                } else if (serverPid > 0) {
                    killIfOurServer(serverPid)
                }
                serverPid = -1
                adopted = false
                clearPidFile()
                clearServiceRegistration(stoppedPid)
                waitForPortFree(3000)
                stopSelf()
            }
        }
    }

    /** Restart the server in place, for example after the GitHub token changed. */
    fun restartServer() {
        WORKER.execute {
            synchronized(SERVER_LOCK) {
                if (sInstance !== this) return@synchronized
                restartAttempts = 0
                stopExisting()
                if (bootstrapReady) startServerBlocking()
            }
        }
    }

    /** Stop the server without stopping the service, the caller holds [SERVER_LOCK]. */
    private fun stopExisting() {
        if (process == null && !adopted) return
        stopping = true
        val stoppedPid = serverPid
        process?.let {
            terminate(it)
            process = null
        }
        if (adopted && serverPid > 0) killIfOurServer(serverPid)
        serverPid = -1
        adopted = false
        clearPidFile()
        clearServiceRegistration(stoppedPid)
        waitForPortFree(4000)
        stopping = false
    }

    /* ------------------------------------------------------------------ stale process handling
     *
     * The OpenCode server is a child of the app process. Android does not always kill it when the
     * app process dies, so a later launch can find the port occupied by its own server from an
     * earlier run. Adoption is preferred over starting a second server: the orphan keeps the
     * WebView working and no state (sessions) is lost.
     */

    /**
     * Check with the Basic credentials of this app that the server that already holds the port
     * belongs to us. Retries once, a freshly started server may need a moment before it answers.
     */
    private fun acceptsCurrentPassword(): Boolean {
        for (attempt in 0 until 3) {
            val connection = try {
                URL("http://127.0.0.1:$port/api/info").openConnection() as HttpURLConnection
            } catch (e: Exception) {
                Log.w(TAG, "Could not probe the OpenCode server on port $port", e)
                null
            }
            if (connection != null) {
                try {
                    connection.connectTimeout = 1500
                    connection.readTimeout = 2500
                    connection.useCaches = false
                    val credentials = "${OpenCodeBootstrap.SERVER_USER}:${OpenCodeBootstrap.getServerPassword(this)}"
                    connection.setRequestProperty(
                        "Authorization",
                        "Basic " + Base64.encodeToString(credentials.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP),
                    )
                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> return true
                        HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> return false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not probe the OpenCode server on port $port", e)
                } finally {
                    connection.disconnect()
                }
            }
            try {
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun waitForPortFree(timeoutMs: Long) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (isPortOpen(port) && SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * The pid of a server that runs the given binary and can be reused, or -1. A server of an
     * older binary is stopped, because it would serve an outdated runtime.
     */
    private fun findServerPid(binary: File): Int {
        val pid = readPidFile()
        if (isOurServer(pid, binary)) return pid
        if (pid > 0 && isOurCmdline(readCmdline(pid))) {
            Log.i(TAG, "Stopping the stale OpenCode server $pid (another binary).")
            killPid(pid)
            waitForPortFree(4000)
            clearPidFile()
        }

        // Older app versions did not write the pid file, fall back to a /proc scan.
        val entries = File("/proc").listFiles() ?: return -1
        for (entry in entries) {
            if (!entry.name.matches(Regex("[0-9]+"))) continue
            val candidate = entry.name.toInt()
            if (candidate == android.os.Process.myPid()) continue
            val cmdline = readCmdline(candidate)
            if (!isOurCmdline(cmdline)) continue
            if (isOurServer(candidate, binary)) return candidate
            Log.i(TAG, "Stopping the stale OpenCode server $candidate (another binary).")
            killPid(candidate)
            waitForPortFree(4000)
            clearPidFile()
        }
        return -1
    }

    /**
     * Stop the running server when the installed binary is not the one it was started from, so a
     * freshly downloaded update is picked up without killing the app.
     */
    private fun restartIfNewBinary() {
        if (process == null && !adopted) return
        val binary = resolveBinary() ?: return
        if (binary.absolutePath == runningBinaryPath) return
        Log.i(TAG, "The OpenCode binary changed, restarting the server.")
        stopExisting()
    }

    private fun resolveBinary(): File? {
        val link = File(OpenCodeBootstrap.LINK_PATH)
        if (!link.exists()) {
            // The installed binary lives in the install directory.
            val binaries = File(OpenCodeBootstrap.INSTALL_DIR).listFiles() ?: return null
            return binaries.firstOrNull { it.isFile && it.name.startsWith("opencode-v") }
        }
        return OpenCodeBootstrap.resolveLink(link)
    }

    /* ------------------------------------------------------------------ notification */

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.opencode_server_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "The OpenCode server that runs natively on Android."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * The notification identifies the chat session that is open in the WebView, not only the
     * server URL. [OpenCodeSessionTracker] is fed by the injected route watcher, and the service
     * polls `/api/session/<id>` for the title and the project directory.
     */
    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, OpenCodeWebActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OpenCodeService::class.java).putExtra("action", ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restartIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OpenCodeService::class.java).putExtra("action", ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val session = activeSession
        val title: String
        val text: String
        if (session != null) {
            title = session.title
            val directory = session.directory.ifEmpty { OpenCodeBootstrap.HOME }
            val age = OpenCodeApi.relativeAge(session.updated)
            text = if (age.isEmpty()) directory else "$directory · $age"
        } else {
            title = getString(R.string.opencode_notification_title)
            text = serverStatusText
        }

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_service_notification,
                getString(R.string.opencode_notification_action_open),
                openIntent,
            )
            .addAction(
                R.drawable.ic_refresh,
                getString(R.string.opencode_notification_action_restart),
                restartIntent,
            )
            .addAction(
                R.drawable.ic_close,
                getString(R.string.opencode_notification_action_stop),
                stopIntent,
            )
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasSpecialUsePermission()) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** @return true when the FOREGROUND_SERVICE_SPECIAL_USE permission was granted. */
    private fun hasSpecialUsePermission(): Boolean =
        checkSelfPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE") == PackageManager.PERMISSION_GRANTED

    private fun updateNotification(text: String) {
        serverStatusText = text
        postNotification()
    }

    private fun postNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Could not update the notification", e)
        }
    }

    /* ------------------------------------------------------------------ wake lock and session */

    /** The wake lock follows the user preference and is released when the service stops. */
    private fun applyWakeLock() {
        if (OpenCodeSettings.wakeLockEnabled(this)) {
            acquireWakeLock()
            acquireWifiLock()
        } else {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        try {
            val held = wakeLock
            if (held != null && held.isHeld) return
            val manager = getSystemService(PowerManager::class.java) ?: return
            wakeLock = manager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:foreground")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
            Log.i(TAG, "Partial wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire the wake lock", e)
            wakeLock = null
        }
    }

    /**
     * Keep the Wi-Fi radio out of power save while the server runs. The CPU wake lock alone does
     * not do that, so a long answer could stall when the screen turns off.
     *
     * `WIFI_MODE_FULL_LOW_LATENCY` would look like the modern replacement, but the Wi-Fi stack
     * only honours it while the app is in the foreground **and the screen is on**; with the screen
     * locked it does nothing, which is exactly the situation this lock exists for. The high
     * performance mode stays effective when the screen is off (Termux uses it for the same
     * reason), so it is kept even though API 34 deprecated it.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            val held = wifiLock
            if (held != null && held.isHeld) return
            val manager = getSystemService(WifiManager::class.java) ?: return
            wifiLock = manager
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "opencode:foreground:wifi")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
            Log.i(TAG, "High performance Wi-Fi lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire the Wi-Fi lock", e)
            wifiLock = null
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the wake lock", e)
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the Wi-Fi lock", e)
        }
        wifiLock = null
    }

    private fun startSessionPolling() {
        if (sessionPolling) return
        sessionPolling = true
        handler.removeCallbacks(sessionPoll)
        handler.post(sessionPoll)
    }

    private fun stopSessionPolling() {
        sessionPolling = false
        handler.removeCallbacks(sessionPoll)
    }

    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        handler.removeCallbacks(heartbeat)
        // The first check is delayed: the bootstrap or a start command is already starting the
        // server, and the heartbeat must not race it at service creation time.
        handler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunning = false
        handler.removeCallbacks(heartbeat)
    }

    /**
     * Resolve the session of the notification: the route reported by the WebView when the app is
     * open, otherwise the most recently updated session of the server. Runs on [WORKER].
     */
    private fun refreshActiveSession() {
        if (!bootstrapReady) return
        val currentPort = port
        val secret = OpenCodeBootstrap.getServerPassword(this)
        val reported = OpenCodeSessionTracker.activeSessionId
        var resolved: OpenCodeApi.Session? = null
        if (reported != null) resolved = OpenCodeApi.session(currentPort, secret, reported, 3500)
        if (resolved == null && reported == null) {
            val sessions = OpenCodeApi.sessionList(currentPort, secret, 3500)
            resolved = sessions?.firstOrNull()
        }
        val previous = activeSession
        if (resolved == null) {
            if (reported == null && previous != null) {
                activeSession = null
                postNotification()
            }
            return
        }
        val changed = previous == null ||
            previous.id != resolved.id ||
            previous.title != resolved.title ||
            previous.updated != resolved.updated ||
            previous.directory != resolved.directory
        if (changed) {
            activeSession = resolved
            postNotification()
        }
    }

    companion object {
        private const val TAG = "OpenCodeService"

        /** Default port of `opencode serve`, shared with the activity and the watchdog. */
        const val DEFAULT_PORT = 49374

        const val NOTIFICATION_CHANNEL_ID = "opencode_service_channel"
        const val NOTIFICATION_ID = 1340

        /** Dismissible notification that explains an Android SIGKILL of the server. */
        const val PHANTOM_NOTIFICATION_ID = 1341

        const val ACTION_START = "com.opencode.android.opencode.START"
        const val ACTION_STOP = "com.opencode.android.opencode.STOP"
        const val ACTION_RESTART = "com.opencode.android.opencode.RESTART"
        const val EXTRA_PORT = "port"

        /** How often the service refreshes the session shown in the notification. */
        private const val SESSION_POLL_INTERVAL_MS = 4000L

        /** How often the service checks that the server it should run is still listening. */
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        /**
         * A server that ran at least this long counts as healthy, so its next unexpected exit
         * starts the restart backoff from scratch instead of continuing an earlier crash loop.
         */
        private const val HEALTHY_UPTIME_MS = 60_000L

        @Volatile
        private var sInstance: OpenCodeService? = null

        /** Incremented every time a server becomes reachable, shared with the WebView. */
        @Volatile
        private var sServerGeneration = 0

        /** The generation of the server that is listening right now, 0 when none ever started. */
        @JvmStatic
        fun serverGeneration(): Int = sServerGeneration

        /**
         * Apply the wake lock preference to the running service immediately. Preferences are
         * stored either way, so the next service start uses the same value.
         */
        @JvmStatic
        fun setWakeLockEnabled(context: Context, enabled: Boolean) {
            OpenCodeSettings.setWakeLockEnabled(context, enabled)
            sInstance?.applyWakeLock()
        }

        /** True while the service holds its partial wake lock. */
        @JvmStatic
        fun isWakeLockHeld(): Boolean = sInstance?.wakeLock?.isHeld == true

        /** Serialises start/stop calls, the service can be asked to start from several places. */
        private val SERVER_LOCK = Any()

        /**
         * All socket work runs here: connecting to 127.0.0.1 on the main thread throws
         * [android.os.NetworkOnMainThreadException], which would make every port check lie.
         * A single worker is enough, there is only one service instance at a time.
         */
        private val WORKER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "opencode-service").apply { isDaemon = true }
        }

        /** Pid of the server process, it survives app restarts so a stale server can be reused. */
        private val PID_FILE = OpenCodeBootstrap.HOME + "/.opencode/server.pid"

        /** State file with which the TUI discovers the server, see `writeServiceRegistration`. */
        private val SERVICE_STATE_DIR = OpenCodeBootstrap.HOME + "/.local/state/opencode"
        private val SERVICE_FILE = "$SERVICE_STATE_DIR/service.json"

        /** Release channel of the installed build: `latest` uses the plain `service.json`. */
        private const val SERVICE_CHANNEL = "latest"

        /** 0600: the registration contains the server password. */
        private const val SERVICE_FILE_MODE = 384

        /** True while the OpenCode server process is running. */
        @JvmStatic
        fun isRunning(): Boolean = sInstance != null

        private fun isPortOpen(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
            true
        } catch (e: Exception) {
            false
        }

        /** True when the command line belongs to the server we start. */
        private fun isOurCmdline(cmdline: String?): Boolean =
            // The loader may keep its own command line or replace it with the binary and its
            // arguments, the installed path is the part that is always present.
            cmdline != null && cmdline.contains("/.opencode/installed/opencode-v")

        private fun isOurServer(pid: Int, binary: File): Boolean {
            val cmdline = readCmdline(pid) ?: return false
            return isOurCmdline(cmdline) && cmdline.contains(binary.absolutePath)
        }

        /** The space separated command line of a process, null when it is not readable. */
        private fun readCmdline(pid: Int): String? {
            if (pid <= 0) return null
            return try {
                FileInputStream("/proc/$pid/cmdline").use { input ->
                    val buffer = ByteArray(8192)
                    val read = input.read(buffer)
                    if (read <= 0) return null
                    String(buffer, 0, read, StandardCharsets.UTF_8).replace('\u0000', ' ').trim()
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun readPidFile(): Int {
            return try {
                FileInputStream(PID_FILE).bufferedReader().use { reader ->
                    val line = reader.readLine() ?: return -1
                    line.trim().toInt()
                }
            } catch (e: Exception) {
                -1
            }
        }

        private fun killPid(pid: Int) {
            if (pid <= 0) return
            try {
                android.os.Process.sendSignal(pid, 9)
            } catch (e: Exception) {
                Log.w(TAG, "Could not kill the process $pid", e)
            }
        }

        /** Kill a pid only when it still belongs to an OpenCode server of this app. */
        private fun killIfOurServer(pid: Int) {
            if (!isOurCmdline(readCmdline(pid))) return
            killPid(pid)
        }

        /** Stop a child process without blocking the caller for longer than a few seconds. */
        private fun terminate(target: Process) {
            try {
                target.destroy()
                if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly()
            } catch (e: Exception) {
                // The process is already gone.
            }
        }
    }

    private fun writePidFile(pid: Int) {
        val file = File(PID_FILE)
        file.parentFile?.mkdirs()
        try {
            file.writeText(pid.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Could not write the server pid file", e)
        }
    }

    /**
     * Write `$XDG_STATE_HOME/opencode/service.json`, the registration that the OpenCode TUI and
     * CLI use to find a background server. `opencode` probes the URL with the password, checks
     * the pid and the version, and then attaches instead of spawning its own `serve`.
     *
     * The format is the one of the upstream service manager:
     * `{id, version, url, pid, password}`. The channel of this build is `latest`, so the file is
     * `service.json` (other channels use `service-<channel>.json`).
     */
    private fun writeServiceRegistration() {
        val pid = serverPid
        if (pid <= 0) return
        val version = OpenCodeBootstrap.getInstalledVersion()
        if (version.isEmpty()) return

        val directory = File(SERVICE_STATE_DIR)
        try {
            directory.mkdirs()
            val json = JSONObject()
                .put("id", SERVICE_CHANNEL)
                .put("version", version)
                .put("url", "http://127.0.0.1:$port")
                .put("pid", pid)
                .put("password", OpenCodeBootstrap.getServerPassword(this))
                .toString()
            // Write next to the target and rename, so a reader never sees a half written file.
            val temporary = File.createTempFile("service", ".json", directory)
            temporary.writeText(json)
            Os.chmod(temporary.absolutePath, SERVICE_FILE_MODE)
            val target = File(SERVICE_FILE)
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    Log.w(TAG, "Could not move the service registration to $SERVICE_FILE")
                    return
                }
            }
            Os.chmod(target.absolutePath, SERVICE_FILE_MODE)
            Log.i(TAG, "Registered the server for the terminal in $SERVICE_FILE")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write the service registration", e)
        }
    }

    /**
     * Remove the service registration, but only when it still points at the server that is being
     * stopped. A file written by another `opencode serve --service` must survive.
     */
    private fun clearServiceRegistration(expectedPid: Int = -1) {
        val file = File(SERVICE_FILE)
        if (!file.isFile) return
        if (expectedPid > 0) {
            val registered = try {
                JSONObject(file.readText()).optInt("pid", -1)
            } catch (e: Exception) {
                -1
            }
            if (registered != expectedPid) return
        }
        try {
            if (file.delete()) Log.i(TAG, "Removed the service registration $SERVICE_FILE")
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the service registration", e)
        }
    }

    /** Wait until the shell of the freshly spawned server recorded its pid. */
    private fun waitForPidFile(timeoutMs: Long): Int {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val pid = readPidFile()
            if (pid > 0) return pid
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return readPidFile()
    }

    private fun clearPidFile() {
        try {
            File(PID_FILE).delete()
        } catch (e: Exception) {
            // Not important, the pid is validated against /proc before it is used.
        }
    }

    /** Remove a pid file that still points at the given, already exited process. */
    private fun clearPidFile(pid: Int) {
        if (readPidFile() == pid) clearPidFile()
    }
}

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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
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
        var port: Int = 49374
    }

    private var process: Process? = null
    private val handler = Handler(Looper.getMainLooper())
    private var port: Int = 49374

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

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        createNotificationChannel()
        applyWakeLock()
        startForegroundCompat(buildNotification())
        startSessionPolling()
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
        if (intent == null) return START_STICKY

        if (intent.getStringExtra("action") == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.getStringExtra("action") == ACTION_RESTART) {
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
        stopServer()
        releaseWakeLock()
        super.onDestroy()
        sInstance = null
    }

    override fun onTaskRemoved(intent: Intent?) {
        super.onTaskRemoved(intent)
        stopSelf()
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
                updateNotification("Running on http://127.0.0.1:$port")
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
                    updateNotification("Running on http://127.0.0.1:$port")
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
            put("HOME", OpenCodeBootstrap.HOME)
            put("PREFIX", OpenCodeBootstrap.PREFIX)
            put("PATH", "${OpenCodeBootstrap.PREFIX}/bin:/system/bin")
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
            put("LD_LIBRARY_PATH", "${OpenCodeBootstrap.PREFIX}/lib")
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

        Thread({ watchServer(started, startedPid) }, "opencode-service-reader").apply {
            isDaemon = true
            start()
        }

        updateNotification("Running on http://127.0.0.1:$port")
    }

    /**
     * Drain the output of the server (a full pipe would block it) and react to its exit.
     */
    private fun watchServer(started: Process, startedPid: Int) {
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
        val current = process === started
        if (current) {
            process = null
            serverPid = -1
            adopted = false
            clearPidFile(startedPid)
        }
        Log.w(TAG, "The OpenCode server exited with $exitCode")

        val intentional = stopping || !current
        if (exitCode != 0 && !intentional) {
            updateNotification("The OpenCode server stopped (exit $exitCode).")
        }
        // Another instance can win the race for the port. The winner is usually our own server
        // from a previous app process, so adopt it instead of staying dead.
        if (!intentional && portBusy && !recoveredFromBusyPort) {
            recoveredFromBusyPort = true
            Log.i(TAG, "The port was busy, retrying with the server that holds it.")
            handler.postDelayed({
                // The service may have been destroyed in the meantime.
                if (sInstance === this) startServer()
            }, 1500)
        }
    }

    /** Kill the OpenCode server process, the next start command starts it again. */
    fun stopServer() {
        WORKER.execute {
            synchronized(SERVER_LOCK) {
                if (process == null && !adopted) {
                    stopSelf()
                    return@synchronized
                }
                stopping = true
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
                stopExisting()
                if (bootstrapReady) startServerBlocking()
            }
        }
    }

    /** Stop the server without stopping the service, the caller holds [SERVER_LOCK]. */
    private fun stopExisting() {
        if (process == null && !adopted) return
        stopping = true
        process?.let {
            terminate(it)
            process = null
        }
        if (adopted && serverPid > 0) killIfOurServer(serverPid)
        serverPid = -1
        adopted = false
        clearPidFile()
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

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the wake lock", e)
        }
        wakeLock = null
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

        const val NOTIFICATION_CHANNEL_ID = "opencode_service_channel"
        const val NOTIFICATION_ID = 1340

        const val ACTION_START = "com.opencode.android.opencode.START"
        const val ACTION_STOP = "com.opencode.android.opencode.STOP"
        const val ACTION_RESTART = "com.opencode.android.opencode.RESTART"
        const val EXTRA_PORT = "port"

        /** How often the service refreshes the session shown in the notification. */
        private const val SESSION_POLL_INTERVAL_MS = 4000L

        @Volatile
        private var sInstance: OpenCodeService? = null

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

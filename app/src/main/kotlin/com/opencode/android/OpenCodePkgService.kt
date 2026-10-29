package com.opencode.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that installs Termux packages into the app-private prefix.
 *
 * There is no `apt`, `dpkg` or `pkg` in this prefix: the app installs the Debian archives itself
 * with [OpenCodePackages]. The `pkg` shell script of the terminal has no way to call into the
 * app, so it sends an ordered broadcast that [OpenCodePkgReceiver] turns into a start of this
 * service; the script then follows the result through a small status file. The service is not
 * exported, the request therefore comes from the same UID as the app.
 *
 * Every request carries a session name. The service writes
 * `$HOME/.opencode/<session>.status` (key=value lines) and, for a search,
 * `$HOME/.opencode/<session>.out`. Using a session per request keeps two terminals that run
 * `pkg` at the same time from overwriting each other's progress.
 */
class OpenCodePkgService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // A service started with 'am start-foreground-service' must call startForeground quickly.
        startForegroundCompat(buildNotification("Preparing the package installation..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(EXTRA_ACTION) ?: intent?.action
        if (intent == null || requested == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val job = Job()
        job.action = requested
        job.packages = splitPackages(intent.getStringExtra(EXTRA_PACKAGES))
        job.query = intent.getStringExtra(EXTRA_QUERY)
        job.session = sanitizeSession(intent.getStringExtra(EXTRA_SESSION))
        job.statusPath = "$STATUS_DIR/${job.session}.status"
        job.outPath = "$STATUS_DIR/${job.session}.out"
        job.message = "Preparing..."

        if (!BUSY.compareAndSet(false, true)) {
            writeStatus(job, "error", "Another package operation is already running, wait for it to finish.", -1)
            return START_NOT_STICKY
        }

        WORKER.execute {
            try {
                run(job)
            } finally {
                BUSY.set(false)
            }
        }
        return START_NOT_STICKY
    }

    private class Job {
        lateinit var action: String
        var packages: List<String> = emptyList()
        var query: String? = null
        lateinit var session: String
        lateinit var statusPath: String
        lateinit var outPath: String
        var message: String = ""
        var percent: Int = 0
    }

    private fun run(job: Job) {
        try {
            if (isSearch(job.action)) search(job) else install(job)
        } catch (t: Throwable) {
            Log.e(TAG, "The package operation failed", t)
            writeStatus(job, "error", describe(t), -1)
        } finally {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun install(job: Job) {
        if (job.packages.isEmpty()) {
            writeStatus(job, "error", "No package name was given.", -1)
            return
        }
        for (name in job.packages) {
            if (name in RUNTIME_PACKAGES) {
                writeStatus(
                    job, "error",
                    "'$name' is part of the OpenCode runtime, run 'oc-install' to update it.", -1,
                )
                return
            }
        }
        if (OpenCodeBootstrap.isInstallRunning()) {
            writeStatus(
                job, "error",
                "The app is still installing its runtime. Wait for it to finish, then try again.", -1,
            )
            return
        }
        if (!OpenCodeBootstrap.isPrefixReady()) {
            writeStatus(
                job, "error",
                "The Linux runtime is not installed yet. Open the app once and let it finish.", -1,
            )
            return
        }

        writeStatus(job, "running", "Preparing to install ${join(job.packages)}...", 0)
        updateNotification("Installing ${join(job.packages)}...")

        try {
            OpenCodePackages.install(
                File(OpenCodeBootstrap.PREFIX),
                job.packages,
                object : OpenCodePackages.Progress {
                    override fun onStatus(message: String) {
                        job.message = message
                        writeStatus(job, "running", message, job.percent)
                        updateNotification(message)
                    }

                    override fun onPercent(percent: Int, detail: String) {
                        job.percent = percent
                        writeStatus(job, "running", job.message, percent)
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "The package installation failed", e)
            writeStatus(job, "error", describe(e), -1)
            updateNotification("The installation failed")
            return
        }

        // The profile and the shell startup file of the runtime are written by the app; make sure
        // a command installed right now is found and the shared libraries of the prefix are used.
        // The repair also fixes a prefix of an older build (Termux symlinks/shebangs, git's
        // embedded shell path) when the requested package was already installed.
        try {
            OpenCodePackages.repair(File(OpenCodeBootstrap.PREFIX))
        } catch (e: Exception) {
            Log.w(TAG, "Could not repair the package environment", e)
        }
        try {
            OpenCodeBootstrap.ensurePackageEnvironment()
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh the shell environment", e)
        }

        writeStatus(job, "done", "Installed ${join(job.packages)}.", 100)
        updateNotification("Installed ${join(job.packages)}.")
    }

    private fun search(job: Job) {
        val query = job.query ?: ""
        writeStatus(job, "running", "Searching the Termux repository...", 0)
        updateNotification("Searching the Termux repository...")

        val results = try {
            OpenCodePackages.search(query)
        } catch (e: Exception) {
            Log.e(TAG, "The package search failed", e)
            writeStatus(job, "error", describe(e), -1)
            updateNotification("The search failed")
            return
        }

        writeText(File(job.outPath), results.joinToString(separator = "\n", postfix = "\n"))
        if (results.isEmpty()) {
            writeStatus(job, "done", "No package matched \"$query\".", 100)
        } else {
            writeStatus(job, "done", "${results.size} package(s) found.", 100)
        }
        updateNotification("Package search finished.")
    }

    /* ------------------------------------------------------------------ notification */

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenCode packages",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Installing Termux packages into the OpenCode runtime."
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, OpenCodeWebActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("OpenCode packages")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasSpecialUsePermission()) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun hasSpecialUsePermission(): Boolean =
        checkSelfPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE") ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "OpenCodePkgService"

        /** Extra accepted from the `pkg` script: install, search. */
        const val EXTRA_ACTION = "action"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_QUERY = "query"
        const val EXTRA_SESSION = "session"

        const val ACTION_INSTALL = "com.opencode.android.pkg.INSTALL"
        const val ACTION_SEARCH = "com.opencode.android.pkg.SEARCH"

        const val CHANNEL_ID = "opencode_pkg_channel"
        const val NOTIFICATION_ID = 1342

        /** Directory of the status files, inside the home of the runtime. */
        const val STATUS_DIR = OpenCodeBootstrap.OC_HOME

        private val WORKER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "opencode-pkg").apply { isDaemon = true }
        }

        /** Only one download/extraction runs at a time, they all write into the same prefix. */
        private val BUSY = AtomicBoolean(false)

        /**
         * Packages that belong to the runtime itself. Reinstalling them with pkg would replace the
         * patched binaries (the interpreter of bash, the relocated glibc loader) and break the
         * terminal, so they are updated through the app instead.
         */
        private val RUNTIME_PACKAGES = setOf(
            "bash", "coreutils", "glibc", "glibc-runner", "patchelf", "ca-certificates",
            "termux-tools", "termux-am", "termux-am-socket", "termux-core", "termux-exec",
        )

        /** True when a package operation is currently running in this process. */
        @JvmStatic
        fun isBusy(): Boolean = BUSY.get()

        /** The terminal sends `search`, an explicit intent may send the full action. */
        private fun isSearch(action: String): Boolean = action == "search" || action == ACTION_SEARCH

        private fun writeStatus(job: Job, state: String, message: String, percent: Int) {
            val sb = StringBuilder()
            sb.append("state=").append(state).append('\n')
            sb.append("message=").append(oneLine(message)).append('\n')
            if (percent >= 0) sb.append("percent=").append(percent).append('\n')
            sb.append("packages=").append(join(job.packages)).append('\n')
            sb.append("session=").append(job.session).append('\n')
            writeText(File(job.statusPath), sb.toString())
        }

        /** Write a small text file atomically, the shell polls it while the service fills it. */
        private fun writeText(file: File, text: String) {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                Log.w(TAG, "Could not create $parent")
                return
            }
            val temp = File(file.absolutePath + ".tmp")
            try {
                temp.outputStream().use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                    it.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not write $file", e)
                return
            }
            if (!temp.renameTo(file)) {
                if (!file.delete() || !temp.renameTo(file)) {
                    // A failed rename must not leave a half written status behind.
                    temp.delete()
                }
            }
        }

        private fun splitPackages(value: String?): List<String> {
            if (value == null) return emptyList()
            val out = LinkedHashSet<String>()
            for (part in value.split(Regex("[,\\s]+"))) {
                val name = part.trim()
                if (name.isNotEmpty()) out.add(name)
            }
            return out.toList()
        }

        /** Keep the session name a plain file name, it comes from the terminal. */
        private fun sanitizeSession(value: String?): String {
            if (value == null) return "pkg"
            val sb = StringBuilder()
            for (c in value) {
                if (sb.length >= 64) break
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') {
                    sb.append(c)
                }
            }
            return if (sb.isEmpty()) "pkg" else sb.toString()
        }

        private fun join(values: List<String>): String = values.joinToString(" ")

        private fun oneLine(value: String?): String =
            value?.replace('\r', ' ')?.replace('\n', ' ')?.trim() ?: ""

        private fun describe(throwable: Throwable): String {
            var message = throwable.message
            if (message.isNullOrBlank()) message = throwable.javaClass.simpleName
            // The error is printed by the shell, a stack trace of a wrapped exception does not help.
            val newline = message.indexOf('\n')
            if (newline >= 0) message = message.substring(0, newline)
            return message.trim()
        }
    }
}

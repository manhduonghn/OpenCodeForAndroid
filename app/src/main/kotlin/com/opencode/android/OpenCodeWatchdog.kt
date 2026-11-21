package com.opencode.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Periodic keep-alive for the OpenCode server.
 *
 * A foreground service is not a guarantee: OEM task killers, the Android phantom process killer
 * and plain process deaths can still stop it. The watchdog is a persisted [JobScheduler] job that
 * wakes up every 15 minutes and starts [OpenCodeService] again when nothing listens on the server
 * port. Android re-schedules a persisted job after a reboot, which is why the app asks for
 * `RECEIVE_BOOT_COMPLETED`.
 *
 * The job itself is cheap: it only opens a local socket, and it never starts a second server when
 * the first one is still answering.
 */
object OpenCodeWatchdog {

    private const val TAG = "OpenCodeWatchdog"
    private const val JOB_ID = 1740

    /** JobScheduler allows 15 minutes as the shortest periodic interval. */
    private const val INTERVAL_MS = 15 * 60 * 1000L

    /**
     * Independent, Doze-tolerant tick. OEM battery managers delay periodic jobs far beyond 15
     * minutes; a while-idle alarm is delivered even when the device is dozing and the app process
     * was frozen, which is the state a backgrounded chat was stuck in on Honor/MagicOS.
     */
    private const val KEEP_ALIVE_REQUEST_CODE = 1741
    private const val KEEP_ALIVE_INTERVAL_MS = 2 * 60 * 1000L
    private const val KEEP_ALIVE_ACTION = "com.opencode.android.KEEP_ALIVE"

    /**
     * Schedule the watchdog when the user enabled it (the default), cancel it otherwise.
     * Safe to call from the activity, the service and the boot receiver.
     */
    @JvmStatic
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        if (!OpenCodeSettings.watchdogEnabled(app) || OpenCodeSettings.stoppedByUser(app)) {
            cancel(app)
            return
        }
        // Arm the faster alarm first: even when JobScheduler is unavailable or later throttled by
        // the ROM, the server still gets an independent chance to be revived every two minutes.
        scheduleKeepAlive(app)
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler == null) {
            Log.w(TAG, "JobScheduler is not available, only the keep-alive alarm stays active.")
            return
        }
        val component = ComponentName(app, OpenCodeWatchdogJobService::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(INTERVAL_MS)
            // Persisted jobs survive a reboot, this needs RECEIVE_BOOT_COMPLETED.
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .build()
        try {
            val result = scheduler.schedule(info)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "Watchdog scheduled every 15 minutes.")
            } else {
                Log.w(TAG, "The watchdog could not be scheduled (result $result).")
            }
        } catch (e: Exception) {
            // A SecurityException here means the job was declared persisted without the boot
            // permission; the app must keep working without the watchdog.
            Log.w(TAG, "Could not schedule the watchdog", e)
        }
    }

    /**
     * Arm the exact alarm that is allowed while the device is idle. Every call moves the tick two
     * minutes out; the receiver re-arms it after each run. Cancelling is handled by [cancel].
     */
    @JvmStatic
    fun scheduleKeepAlive(context: Context) {
        val app = context.applicationContext
        if (!OpenCodeSettings.watchdogEnabled(app) || OpenCodeSettings.stoppedByUser(app)) return
        val alarm = app.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + KEEP_ALIVE_INTERVAL_MS,
                keepAliveIntent(app),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not arm the keep-alive alarm", e)
        }
    }

    private fun keepAliveIntent(context: Context): PendingIntent {
        val intent = Intent(context, OpenCodeKeepAliveReceiver::class.java).apply {
            action = KEEP_ALIVE_ACTION
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, KEEP_ALIVE_REQUEST_CODE, intent, flags)
    }

    /** Apply a change of the watchdog preference immediately. */
    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        OpenCodeSettings.setWatchdogEnabled(context, enabled)
        if (enabled) OpenCodeSettings.setStoppedByUser(context, false)
        ensureScheduled(context)
        if (enabled) requestImmediateCheck(context)
    }

    @JvmStatic
    fun cancel(context: Context) {
        val app = context.applicationContext
        try {
            app.getSystemService(JobScheduler::class.java)?.let { scheduler ->
                scheduler.cancel(JOB_ID)
                scheduler.cancel(JOB_ID + 1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel the watchdog", e)
        }
        try {
            app.getSystemService(AlarmManager::class.java)?.cancel(keepAliveIntent(app))
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel the keep-alive alarm", e)
        }
    }

    /**
     * Ask the system for an immediate run, for example when the user enables the watchdog.
     * A periodic job cannot run more often than every 15 minutes, an extra one-shot job can.
     */
    @JvmStatic
    fun requestImmediateCheck(context: Context) {
        if (!OpenCodeSettings.watchdogEnabled(context) || OpenCodeSettings.stoppedByUser(context)) return
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val info = JobInfo.Builder(
            JOB_ID + 1,
            ComponentName(app, OpenCodeWatchdogJobService::class.java),
        )
            .setMinimumLatency(5_000L)
            .setOverrideDeadline(30_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        try {
            scheduler.schedule(info)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request an immediate watchdog check", e)
        }
    }

    /** True when something accepts TCP connections on the server port. */
    @JvmStatic
    fun isPortOpen(port: Int): Boolean = OpenCodeHttp.isPortOpen(port, 800)

    /** Start the foreground service, the caller must be allowed to do so (target SDK 28). */
    @JvmStatic
    fun startService(context: Context) {
        // Never fight an explicit Stop; the boot receiver clears the flag before calling this.
        if (OpenCodeSettings.stoppedByUser(context)) return
        try {
            val intent = Intent(context, OpenCodeService::class.java)
                .putExtra(OpenCodeService.EXTRA_PORT, OpenCodeService.DEFAULT_PORT)
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the OpenCode service from the watchdog", e)
        }
    }
}

/**
 * The job that [OpenCodeWatchdog] schedules. Socket checks run on their own thread because
 * [JobService.onStartJob] is called on the main thread and Android forbids network there.
 */
class OpenCodeWatchdogJobService : JobService() {

    @Volatile
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        if (!OpenCodeSettings.watchdogEnabled(app) || OpenCodeSettings.stoppedByUser(app)) {
            jobFinished(params, false)
            return false
        }
        val thread = Thread({
            val port = OpenCodeService.DEFAULT_PORT
            // Readiness, not a socket: a server can keep the port open while /api/info is hung.
            val healthy = OpenCodeService.ensureHealthy(app)
            if (healthy) {
                Log.i(TAG, "The OpenCode server is still healthy on $port.")
            } else {
                Log.i(TAG, "The OpenCode server is not healthy on $port, starting the service again.")
                OpenCodeWatchdog.startService(app)
            }
            jobFinished(params, false)
        }, "opencode-watchdog")
        thread.isDaemon = true
        worker = thread
        thread.start()
        // Keep the job alive until the thread reports back.
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        worker?.interrupt()
        worker = null
        // The check was interrupted before it could finish, ask for another attempt.
        return true
    }

    companion object {
        private const val TAG = "OpenCodeWatchdogJob"
    }
}

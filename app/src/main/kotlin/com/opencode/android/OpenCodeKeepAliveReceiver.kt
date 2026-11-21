package com.opencode.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Doze-tolerant tick of the background keep-alive.
 *
 * [OpenCodeWatchdog] arms an exact alarm that is allowed to run while the device is idle. Periodic
 * JobScheduler jobs are delayed much longer by OEM battery managers (notably Honor/MagicOS), which
 * is why a freshly installed build could leave the server dead until the user opened the app. This
 * explicit broadcast wakes the app process on its own, checks `/api/info` and re-arms both
 * watchdogs. The receiver is not exported: only this app's own alarm can send the broadcast.
 */
class OpenCodeKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        // Re-arm first so a failure in the health check cannot leave the app without a next tick.
        OpenCodeWatchdog.ensureScheduled(app)
        if (OpenCodeSettings.stoppedByUser(app)) return
        // onReceive runs on the main thread and ensureHealthy performs a network request.
        val pending = goAsync()
        Thread({
            try {
                val healthy = OpenCodeService.ensureHealthy(app)
                if (!healthy) {
                    Log.i(TAG, "Keep-alive found no healthy server, starting the service.")
                    OpenCodeWatchdog.startService(app)
                }
            } finally {
                pending.finish()
            }
        }, "opencode-keep-alive").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        private const val TAG = "OpenCodeKeepAlive"
    }
}

package com.opencode.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the server after a reboot (or after an app update) when the user asked for it, and
 * restores the watchdog job.
 *
 * The receiver is deliberately quiet by default: [OpenCodeSettings.bootStartEnabled] is false, so
 * a device that reboots does not get a foreground service it did not ask for. The periodic
 * watchdog is independent of this setting.
 */
class OpenCodeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        // A persisted job normally survives a reboot, scheduling it again is idempotent and
        // covers an OEM that dropped it during the update.
        OpenCodeWatchdog.ensureScheduled(context)

        if (!OpenCodeSettings.bootStartEnabled(context)) return
        if (!OpenCodeBootstrap.isRuntimeComplete()) {
            // The first install is not finished yet; there is nothing to serve after a reboot.
            Log.i(TAG, "Not starting after boot: the runtime is not installed yet.")
            return
        }
        // An explicit stop from the previous session must not block the requested boot start.
        OpenCodeSettings.setStoppedByUser(context, false)
        Log.i(TAG, "Starting the OpenCode server after $action.")
        OpenCodeWatchdog.startService(context)
    }

    companion object {
        private const val TAG = "OpenCodeBootReceiver"
    }
}

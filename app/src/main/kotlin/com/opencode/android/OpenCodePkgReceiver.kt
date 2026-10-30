package com.opencode.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Entry point of the `pkg` command of the terminal.
 *
 * `am start-service` and `am start-foreground-service` cannot be used here: the shell command of
 * Android always sends `com.android.shell` as the calling package, while the real caller is this
 * app, and the activity manager rejects that mismatch with
 * `SecurityException: Can't start from invalid source: <uid>`. A broadcast keeps the real calling
 * UID, so the receiver below is allowed to start [OpenCodePkgService] from inside the app.
 *
 * The receiver is protected by a signature permission: only this app (same UID and certificate)
 * can ask for a package operation. The request is forwarded as is, the service owns the progress
 * file, the notification and the installation itself.
 */
class OpenCodePkgReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION) return
        val action = intent.getStringExtra(OpenCodePkgService.EXTRA_ACTION) ?: return

        val request = Intent(context, OpenCodePkgService::class.java)
            .putExtra(OpenCodePkgService.EXTRA_ACTION, action)
            .putExtra(
                OpenCodePkgService.EXTRA_SESSION,
                intent.getStringExtra(OpenCodePkgService.EXTRA_SESSION),
            )
            .putExtra(
                OpenCodePkgService.EXTRA_PACKAGES,
                intent.getStringExtra(OpenCodePkgService.EXTRA_PACKAGES),
            )
            .putExtra(
                OpenCodePkgService.EXTRA_QUERY,
                intent.getStringExtra(OpenCodePkgService.EXTRA_QUERY),
            )

        try {
            // The service calls startForeground() in onCreate(), which is what the contract of
            // startForegroundService() requires.
            context.startForegroundService(request)
            // The broadcast is ordered (am sends it with serialized=true), so the bridge can tell
            // a refused request from an accepted one.
            setResultCode(RESULT_ACCEPTED)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the package service", e)
            setResultCode(RESULT_REFUSED)
        }
    }

    companion object {
        private const val TAG = "OpenCodePkgReceiver"

        /** Action of the ordered broadcast that the `pkg` bridge sends. */
        const val ACTION = "com.opencode.android.pkg.REQUEST"

        const val RESULT_ACCEPTED = 1
        const val RESULT_REFUSED = 2
    }
}

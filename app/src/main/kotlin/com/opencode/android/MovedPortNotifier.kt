package com.opencode.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reports the ports that the preload library moved away from the privileged
 * range, see [MovedPorts].
 *
 * The notification is posted on the same channel as the foreground service, so
 * it does not need a second user-visible switch. A successful post is the only
 * moment a marker is removed: a failure or a disabled channel leaves it in
 * place for the next scan instead of silently losing the information.
 */
object MovedPortNotifier {

    private const val TAG = "MovedPortNotifier"

    /** Dismissible notification, separate from the ongoing server and phantom ones. */
    private const val NOTIFICATION_ID = 1342

    private const val CHANNEL_ID = OpenCodeService.NOTIFICATION_CHANNEL_ID

    private val lock = Any()

    /**
     * Read the pending markers, post one notification for all of them and
     * remove the markers that were reported. Call it on a worker thread; the
     * service heartbeat and the activity resume path both do.
     */
    fun report(context: Context) {
        val app = context.applicationContext ?: context
        synchronized(lock) {
            val directory = MovedPorts.directory(OpenCodeBootstrap.PREFIX)
            val moved = MovedPorts.read(directory)
            if (moved.isEmpty()) return
            if (!post(app, moved)) return
            for (port in moved) MovedPorts.discard(directory, port)
        }
    }

    private fun post(context: Context, moved: List<MovedPorts.MovedPort>): Boolean {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        // notify() silently succeeds when the user disabled notifications or the channel. Keep
        // the markers instead of consuming information the user never saw.
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        val openIntent = PendingIntent.getActivity(
            context,
            12,
            Intent(context, OpenCodeWebActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(R.string.opencode_moved_ports_title)
        val text = if (moved.size == 1) {
            val port = moved[0]
            context.getString(R.string.opencode_moved_port_text, port.original, port.moved)
        } else {
            context.getString(R.string.opencode_moved_ports_text, moved.size)
        }
        val style = Notification.InboxStyle()
            .setBigContentTitle(title)
            .setSummaryText(context.getString(R.string.opencode_moved_ports_big))
        for (port in moved) {
            style.addLine(
                context.getString(
                    R.string.opencode_moved_port_line,
                    port.original,
                    port.moved,
                ),
            )
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not post the moved-port notification", e)
            false
        }
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.opencode_server_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "The OpenCode server that runs natively on Android."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}

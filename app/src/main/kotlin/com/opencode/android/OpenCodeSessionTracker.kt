package com.opencode.android

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The chat session that is currently open in the WebView.
 *
 * The route of the single page app (`/session/<id>`) is the only source of truth about which
 * session the user is chatting in; the HTTP API does not expose it. [OpenCodeWebViewModel]
 * injects a small script into the page that reports the route here through a JavaScript bridge
 * and [OpenCodeWebActivity] reports it again from `doUpdateVisitedHistory`. The foreground
 * service reads the value when it polls the session for the notification, even while the
 * activity is destroyed, because this object lives in the app process.
 */
object OpenCodeSessionTracker {

    fun interface Listener {
        fun onActiveSessionChanged(id: String?)
    }

    @Volatile
    var activeSessionId: String? = null
        private set

    /** Title of the session the last time the UI or the service saw it. */
    @Volatile
    var activeSessionTitle: String = ""
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * Record the session of the current route. A null or empty id means that no chat session is
     * open (for example the start page or the settings of the web UI).
     */
    fun setActiveSession(id: String?, title: String? = null) {
        val normalized = id?.trim()?.takeIf { it.isNotEmpty() && it.startsWith("ses_") }
        val resolvedTitle = title?.trim().orEmpty()
        val changed = normalized != activeSessionId
        if (resolvedTitle.isNotEmpty()) activeSessionTitle = resolvedTitle
        if (normalized == null && activeSessionId == null) return
        activeSessionId = normalized
        if (normalized == null) activeSessionTitle = ""
        if (changed) {
            for (listener in listeners) {
                try {
                    listener.onActiveSessionChanged(normalized)
                } catch (e: Exception) {
                    // A listener must never break the WebView bridge.
                }
            }
        }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
}

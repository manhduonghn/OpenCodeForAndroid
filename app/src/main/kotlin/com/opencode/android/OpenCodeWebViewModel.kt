package com.opencode.android

import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.lifecycle.ViewModel

/**
 * Holds the WebView of the OpenCode UI across the recreation of [OpenCodeWebActivity].
 *
 * Rotating the phone, changing the dark mode or the font size recreates the activity. The
 * WebView is expensive to build and, more important, it owns the whole state of the web app
 * (DOM, scroll position, the open session, a half written message), so it must not be thrown
 * away: it lives here, in a [ViewModel] that survives the configuration change, and every new
 * activity instance re-attaches the very same view.
 *
 * The view is created with the *application* context, so this object can never keep a destroyed
 * activity alive; JavaScript dialogs are forwarded to whichever activity currently shows it.
 *
 * The view also owns the JavaScript bridge that reports the `/session/<id>` route of the single
 * page app, see [OpenCodeSessionTracker]. That is how the foreground notification knows which
 * chat is on screen without guessing from the most recently updated session.
 */
class OpenCodeWebViewModel : ViewModel() {

    /** The activity that currently shows the WebView, null while none is attached. */
    interface Ui {
        /** Called for `alert()` of the web app. */
        fun onJsAlert(view: WebView, message: String?, result: JsResult)

        /** Called for `confirm()` of the web app. */
        fun onJsConfirm(view: WebView, message: String?, result: JsResult)
    }

    /** True once the web UI of the server was loaded, it survives a configuration change. */
    var pageLoaded = false

    /**
     * Session that has to be opened after the start page finished loading. It lives here, not in
     * the activity, so a rotation during the load does not lose the session the user picked.
     */
    var pendingSessionId: String? = null

    /** HTTP Basic password of the local server, the WebView answers its challenges with it. */
    var serverPassword = ""

    /**
     * [OpenCodeService.serverGeneration] of the server the page was paired with. When the server
     * is replaced while the app is in the background (watchdog, restart, another app start), the
     * token and the cookie of the old pairing are stale and the page has to be paired again.
     */
    var pairedServerGeneration = 0

    /** The activity of the moment, used for the JavaScript dialogs only. */
    var ui: Ui? = null

    private var webViewInstance: WebView? = null

    /** @return the single WebView of this state, created on the first call. */
    fun webView(context: Context): WebView {
        webViewInstance?.let { return it }

        val view = WebView(context.applicationContext)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Local files and content URIs are not needed, the UI is served over loopback.
            allowFileAccess = false
            allowContentAccess = false
        }
        // The page is served by our own loopback server. The bridge only reports the route.
        view.addJavascriptInterface(SessionBridge(), "OpenCodeAndroidSession")
        // The default handler needs a window of an activity, which an application context cannot
        // provide, so the dialogs of the web app are shown by the activity that is on screen.
        view.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (view == null || result == null) return true
                val callback = ui
                if (callback == null) {
                    // No activity is showing the page: never block the web app.
                    result.cancel()
                    return true
                }
                callback.onJsAlert(view, message, result)
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (view == null || result == null) return true
                val callback = ui
                if (callback == null) {
                    result.cancel()
                    return true
                }
                callback.onJsConfirm(view, message, result)
                return true
            }
        }

        webViewInstance = view
        return view
    }

    /**
     * Install the route watcher of the single page app. Safe to call after every page load: the
     * script guards itself against double installation.
     */
    fun installSessionWatcher() {
        val view = webViewInstance ?: return
        view.evaluateJavascript(WATCHER_SCRIPT, null)
    }

    /**
     * Resume timers and rendering of the retained WebView. The activity is stopped and started
     * again when the screen is locked and unlocked; without this the timers of the web app can
     * stay paused after the resume and the UI looks frozen until a manual reload.
     */
    fun resumeWebView() {
        val view = webViewInstance ?: return
        try {
            view.onResume()
            view.resumeTimers()
        } catch (e: Exception) {
            // A destroyed WebView throws here; the ViewModel is about to be cleared anyway.
        }
    }

    /** Report a session from a native URL callback, before the injected script runs. */
    fun reportSessionFromUrl(url: String?) {
        OpenCodeSessionTracker.setActiveSession(sessionIdOf(url))
    }

    private class SessionBridge {
        @JavascriptInterface
        fun setSession(id: String?) {
            OpenCodeSessionTracker.setActiveSession(id)
        }
    }

    override fun onCleared() {
        ui = null
        OpenCodeSessionTracker.setActiveSession(null)
        val view = webViewInstance
        webViewInstance = null
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    companion object {
        /** Extract `ses_…` from a URL such as `http://127.0.0.1:49374/session/ses_abc`. */
        fun sessionIdOf(url: String?): String? {
            if (url == null) return null
            val marker = "/session/"
            val index = url.indexOf(marker)
            if (index < 0) return null
            var id = url.substring(index + marker.length)
            val cut = id.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (cut >= 0) id = id.substring(0, cut)
            return id.takeIf { it.startsWith("ses_") }
        }

        /**
         * Watches the history API of the single page app and reports `/session/<id>` whenever the
         * route changes: a session is opened, a new one is created, the user goes back or the
         * web app replaces the address without a full page load.
         */
        private val WATCHER_SCRIPT = """
            (function () {
              if (window.__ocAndroidSessionWatcher) { window.__ocAndroidSessionWatcher(); return; }
              function report() {
                try {
                  var match = location.pathname.match(/\/session\/([^\/?#]+)/);
                  var id = match ? decodeURIComponent(match[1]) : '';
                  if (window.OpenCodeAndroidSession && window.OpenCodeAndroidSession.setSession) {
                    window.OpenCodeAndroidSession.setSession(id);
                  }
                } catch (e) {}
              }
              window.__ocAndroidSessionWatcher = report;
              try {
                var push = history.pushState;
                history.pushState = function () {
                  var result = push.apply(this, arguments);
                  setTimeout(report, 0);
                  return result;
                };
                var replace = history.replaceState;
                history.replaceState = function () {
                  var result = replace.apply(this, arguments);
                  setTimeout(report, 0);
                  return result;
                };
              } catch (e) {}
              window.addEventListener('popstate', report);
              window.addEventListener('hashchange', report);
              document.addEventListener('click', function () { setTimeout(report, 40); }, true);
              setInterval(report, 2500);
              report();
            })();
        """.trimIndent()
    }
}

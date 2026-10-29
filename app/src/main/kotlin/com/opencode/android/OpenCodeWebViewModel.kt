package com.opencode.android

import android.content.Context
import android.view.ViewGroup
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

    /** HTTP Basic password of the local server, the WebView answers its challenges with it. */
    var serverPassword = ""

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

    override fun onCleared() {
        ui = null
        val view = webViewInstance
        webViewInstance = null
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }
}

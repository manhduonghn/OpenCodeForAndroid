package com.opencode.android

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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

        /**
         * The web app wants to open the files of an `<input type="file">` (the attach button of
         * the composer). The activity starts the system picker and reports the selection through
         * [onFileChooserResult].
         */
        fun onShowFileChooser(params: WebChromeClient.FileChooserParams?)

        /**
         * The web app completed its first successful API call. Only now is it safe to show the
         * WebView: before that point its session list can still be loading (or retrying), and the
         * web UI would paint its own "Transport: Failed to fetch" error over the boot screen.
         */
        fun onWebBootReady()

        /**
         * The transport guard ran out of retries: the server is gone or the loopback request
         * failed. The activity shows the native reconnect screen instead of the web app's error.
         */
        fun onWebTransportLost(detail: String)

        /** API traffic works again, the reconnect screen can be hidden. */
        fun onWebTransportRestored()

        /** The server rejected the credential of the page, it has to be paired again. */
        fun onWebAuthFailed()

        /**
         * The page reported its `/session/<id>` route. This is the last barrier of the reveal
         * rule: the native boot panel stays up until health, generation, credential, a successful
         * API call and a reported route all agree.
         */
        fun onWebRouteReported()

        /**
         * The page asked for a second window (`target="_blank"` or `window.open`). The shell has
         * only one window: the activity resolves the popup URL and opens it outside the WebView.
         */
        fun onNewWindow(view: WebView, resultMsg: Message)

        /**
         * The chat refers to an absolute file path (a link or an inline code path). The web UI
         * would open it in its own viewer; the activity opens it in the native file manager
         * instead, so a path of the OpenCode installation is reachable like any other file.
         */
        fun onOpenLocalFile(path: String)
    }

    /**
     * True once the web app has talked to the server successfully. It lives in the ViewModel so a
     * rotation does not hide the page again, and it is reset whenever the page is (re)loaded.
     */
    @Volatile
    var webReady = false

    /** True between a transport failure reported by the guard and the next successful call. */
    @Volatile
    var transportDown = false

    /**
     * False when this WebView does not support document-start scripts. The activity then falls
     * back to a native health check before it reveals the page.
     */
    @Volatile
    var bootGuardInstalled = false

    /**
     * True once the page reported its `/session/<id>` route. Part of the reveal barrier: the
     * native boot panel stays up until health, generation, credential, first API call and route
     * all agree, so a SolidJS error boundary never gets an unguarded frame.
     */
    @Volatile
    var routeReported = false

    /** True once the web UI of the server was loaded, it survives a configuration change. */
    var pageLoaded = false

    /**
     * True while the terminal panel is open. Keeping it here means a day/night switch, which
     * recreates the activity, reopens the same terminal instead of dropping the user back into
     * the chat, and the new palette is picked up by the fresh [TerminalScreen].
     */
    var terminalVisible = false

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

    /**
     * Contract of the shell that the document-start script reads synchronously from the bridge.
     * A global set with `evaluateJavascript` would be wiped by the next navigation, so native keeps
     * these values here and [HostBridge.contract] hands them to every new document.
     */
    @Volatile
    private var shellGeneration = 0

    @Volatile
    private var shellAllowRetry = false

    /** The activity of the moment, used for the JavaScript dialogs and the file picker only. */
    var ui: Ui? = null

    /**
     * Pending callback of an `<input type="file">` of the web app. The system picker runs in an
     * activity, so the callback has to survive the recreation of this activity (rotation) while
     * the picker is open.
     */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private var webViewInstance: WebView? = null

    /** Registered document-start script of the Material You stylesheet, replaced on each attach. */
    private var themeHandler: ScriptHandler? = null

    /** Registered document-start script of the transport/boot guard. */
    private var bootHandler: ScriptHandler? = null

    /** Last stylesheet built by [OpenCodeWebTheme], kept for the live document fallback. */
    private var themeScript: String? = null

    /** JavaScript bridges are called on a private WebView thread, UI callbacks go through here. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** @return the single WebView of this state, created on the first call. */
    fun webView(context: Context): WebView {
        webViewInstance?.let { return it }
        return createWebView(context)
    }

    /**
     * Throw away a WebView whose renderer process was killed and return a fresh one.
     *
     * Android may kill the renderer of a backgrounded WebView under memory pressure. The old view
     * then only shows an empty surface and cannot be reused, so the activity swaps it for the view
     * this method creates and loads the UI again. The page state is lost, but the session itself
     * lives in the server, so reopening its route restores the conversation.
     */
    fun replaceWebView(context: Context): WebView {
        val old = webViewInstance
        webViewInstance = null
        themeHandler = null
        bootHandler = null
        bootGuardInstalled = false
        webReady = false
        transportDown = false
        pageLoaded = false
        pairedServerGeneration = 0
        cancelFileChooser()
        if (old != null) {
            (old.parent as? ViewGroup)?.removeView(old)
            try {
                old.destroy()
            } catch (e: Exception) {
                // The renderer is already gone, there is nothing left to release.
            }
        }
        return createWebView(context)
    }

    private fun createWebView(context: Context): WebView {
        val view = WebView(context.applicationContext)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The attach button of the composer reads the files returned by the system picker as
            // content URIs, so content access has to stay enabled. Local file URLs are not needed:
            // the UI is served over loopback.
            allowFileAccess = false
            allowContentAccess = true
            // The web UI renders every link of a message with target="_blank", which the WebView
            // drops while multiple windows are unsupported. The popup request is forwarded to the
            // activity instead (see Ui.onNewWindow), so those links open outside the shell.
            setSupportMultipleWindows(true)
        }
        // The Material You stylesheet decides every color. The WebView's own algorithmic
        // darkening (API 33+) and the View-level force dark (API 29+) would process those colors
        // a second time and tint them unpredictably, so both are turned off.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.isForceDarkAllowed = false
        }
        // One versioned bridge for the whole shell: boot state, route, local links and fatal
        // errors. The old three interfaces are gone, so there is no longer a document that only
        // some of the guards can report from.
        view.addJavascriptInterface(HostBridge(), HOST_BRIDGE)
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

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null) return false
                // A second request while the picker is open (or a request left behind by a
                // destroyed activity) would otherwise wait forever.
                cancelFileChooser()
                fileChooserCallback = filePathCallback
                val callback = ui
                if (callback == null) {
                    cancelFileChooser()
                    return true
                }
                callback.onShowFileChooser(fileChooserParams)
                return true
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                if (view == null || resultMsg == null) return false
                val callback = ui ?: return false
                callback.onNewWindow(view, resultMsg)
                return true
            }
        }

        webViewInstance = view
        installWebShell(view)
        // Fallback for WebViews without document-start support: re-evaluate the idempotent
        // combined script on the live document later (see installSessionWatcher).
        return view
    }

    /**
     * Deliver the selection of the system file picker to the web app. `null` means that the
     * picker was cancelled or could not be opened; the web app clears its hidden input either way.
     */
    fun onFileChooserResult(uris: Array<Uri>?) {
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        callback.onReceiveValue(uris)
    }

    /** Release a pending `<input type="file">` callback without a selection. */
    private fun cancelFileChooser() {
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        callback.onReceiveValue(null)
    }

    /**
     * Install the single document-start shell before the web app bundle runs.
     *
     * The script wraps `fetch` with at most one cold-start retry, reports its state to [HostBridge],
     * watches the `/session/<id>` route and intercepts local file paths. Error events are reported
     * and neutralised: a failed infrastructure request must never reach the SolidJS error boundary.
     */
    private fun installWebShell(view: WebView) {
        bootGuardInstalled = false
        routeReported = false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        bootHandler = try {
            WebViewCompat.addDocumentStartJavaScript(view, WEB_SHELL_SCRIPT, setOf("*")).also {
                bootGuardInstalled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not install the web shell", e)
            null
        }
    }

    /**
     * Give the injected shell the current supervisor generation and whether a cold-start retry is
     * allowed. Native, not JavaScript, decides when retrying still makes sense.
     */
    fun configureShell(generation: Int, allowRetry: Boolean) {
        shellGeneration = generation
        shellAllowRetry = allowRetry
        // If a document is already live, tell it to re-read the contract; the next navigation gets
        // it from HostBridge.contract() at document-start, so no global has to survive a reload.
        val view = webViewInstance ?: return
        try {
            view.evaluateJavascript("window.__ocAndroidShell && window.__ocAndroidShell.rebind();", null)
        } catch (e: Exception) {
            // The view is being replaced; the next document start reads the fields above.
        }
    }

    /**
     * Install the Material You stylesheet on the retained WebView.
     *
     * It is registered as a document-start script so the first paint already has the right
     * colors, and evaluated on the current document so a rotation (or a dark-mode change while the
     * ViewModel keeps the page) restyles the UI that is already loaded. The previous registration
     * is removed first: otherwise every attach would add another copy to the same WebView.
     */
    fun installWebTheme(script: String) {
        themeScript = script
        val view = webViewInstance ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            themeHandler?.let { handler ->
                try {
                    handler.remove()
                } catch (e: Exception) {
                    // The old handler belongs to a WebView that is going away.
                }
            }
            themeHandler = try {
                WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
            } catch (e: Exception) {
                null
            }
        }
        applyWebTheme()
    }

    /**
     * Re-apply the last stylesheet to the live document. [WebViewClient.onPageFinished] calls
     * this as a fallback: a page that started loading before the registration was replaced would
     * otherwise keep the colors of its first paint.
     */
    fun applyWebTheme() {
        val view = webViewInstance ?: return
        val script = themeScript ?: return
        try {
            view.evaluateJavascript(script, null)
        } catch (e: Exception) {
            // The view is being destroyed; nothing to restyle.
        }
    }

    /**
     * Re-evaluate the idempotent combined shell on the live document. Needed for WebViews without
     * document-start support and after a rotation that kept the page alive.
     */
    fun installSessionWatcher() {
        val view = webViewInstance ?: return
        try {
            view.evaluateJavascript(WEB_SHELL_SCRIPT, null)
        } catch (e: Exception) {
            // The view is being replaced; nothing to install.
        }
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

    /**
     * Tell the single page app that the activity is visible again.
     *
     * While the app is in the background Android can drop the event streams (SSE/WebSocket) of the
     * web UI without the page noticing. The server is still fine, so the page does not have to be
     * reloaded and lose its scroll position: dispatching the lifecycle events makes the app
     * reconnect its streams and refetch the state it missed.
     */
    fun nudgeWebApp() {
        val view = webViewInstance ?: return
        try {
            view.evaluateJavascript(NUDGE_SCRIPT, null)
        } catch (e: Exception) {
            // A destroyed WebView throws here, the next resume creates a new one.
        }
    }

    /** Report a session from a native URL callback, before the injected script runs. */
    fun reportSessionFromUrl(url: String?) {
        OpenCodeSessionTracker.setActiveSession(sessionIdOf(url))
    }

    /**
     * The single versioned bridge of the shell. All methods run on the WebView's JavaScript
     * thread: fields are volatile and every UI callback is posted to the main thread.
     */
    private inner class HostBridge {
        /**
         * Synchronous contract read at document-start. A `@JavascriptInterface` call can return a
         * value, so the shell never depends on a global that a navigation would erase.
         */
        @JavascriptInterface
        fun contract(): String = org.json.JSONObject()
            .put("generation", shellGeneration)
            .put("allowRetry", shellAllowRetry)
            .toString()

        @JavascriptInterface
        fun ready(source: String?) {
            webReady = true
            transportDown = false
            mainHandler.post { ui?.onWebBootReady() }
        }

        @JavascriptInterface
        fun transportLost(detail: String?) {
            transportDown = true
            mainHandler.post { ui?.onWebTransportLost(detail.orEmpty()) }
        }

        @JavascriptInterface
        fun transportOk(value: String?) {
            transportDown = false
            mainHandler.post { ui?.onWebTransportRestored() }
        }

        @JavascriptInterface
        fun authFailed(value: String?) {
            mainHandler.post { ui?.onWebAuthFailed() }
        }

        @JavascriptInterface
        fun setSession(id: String?) {
            val firstReport = !routeReported
            routeReported = true
            OpenCodeSessionTracker.setActiveSession(id)
            if (firstReport) mainHandler.post { ui?.onWebRouteReported() }
        }

        @JavascriptInterface
        fun openLocalFile(path: String?) {
            val value = path?.trim().orEmpty()
            if (value.isEmpty()) return
            mainHandler.post { ui?.onOpenLocalFile(value) }
        }

        @JavascriptInterface
        fun reportFatal(kind: String?) {
            transportDown = true
            mainHandler.post { ui?.onWebTransportLost("fatal:${kind.orEmpty()}") }
        }
    }

    override fun onCleared() {
        ui = null
        webReady = false
        transportDown = false
        bootGuardInstalled = false
        routeReported = false
        cancelFileChooser()
        OpenCodeSessionTracker.setActiveSession(null)
        val view = webViewInstance
        webViewInstance = null
        themeHandler = null
        bootHandler = null
        themeScript = null
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    companion object {
        private const val TAG = "OpenCodeWebViewModel"
        /** The single versioned bridge. Dots are not valid JS property names, hence "V1". */
        private const val HOST_BRIDGE = "OpenCodeHostV1"

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
         * The one document-start script of the shell, versioned through [HOST_BRIDGE].
         *
         * It replaces the old BOOT_SCRIPT + WATCHER_SCRIPT + LINK_SCRIPT trio. JavaScript no
         * longer decides when to stop retrying: native passes a generation and a cold-start
         * deadline, the script performs at most one short retry, reports failures to the bridge
         * and leaves a pending promise instead of throwing. That is what removes the SolidJS
         * `ClientError: Transport: Failed to fetch` stack: the app never receives a rejection it
         * could render, and the native boot panel covers the page until every barrier is met.
         */
        private val WEB_SHELL_SCRIPT = """
            (function () {
              if (window.__ocAndroidShell) { window.__ocAndroidShell.rebind(); return; }
              var bridge = window.OpenCodeHostV1;
              // The native bridge is installed once per WebView and survives navigation, unlike a
              // global set by evaluateJavascript before loadUrl(). Read the contract at document
              // start so the very first API call is guarded with the current generation.
              function readContract() {
                try {
                  var raw = bridge && typeof bridge.contract === 'function' ? bridge.contract() : '{}';
                  return JSON.parse(raw) || {};
                } catch (e) {
                  return {};
                }
              }
              var contract = readContract();
              var state = window.__ocAndroidShell = {
                ready: false,
                failed: false,
                lost: false,
                authFailed: false,
                pending: 0,
                generation: contract.generation || 0,
                allowRetry: contract.allowRetry === true,
                rebind: function () {
                  var next = readContract();
                  state.generation = next.generation || 0;
                  state.allowRetry = next.allowRetry === true;
                  reportRoute();
                }
              };
              function call(name, value) {
                try {
                  if (bridge && typeof bridge[name] === 'function') {
                    bridge[name](value == null ? '' : String(value));
                  }
                } catch (e) {}
              }
              function apiPath(input) {
                try {
                  var raw = typeof input === 'string' ? input : (input && input.url) || '';
                  if (!raw) return '';
                  var url = new URL(raw, window.location.href);
                  if (url.origin !== window.location.origin) return '';
                  return url.pathname.indexOf('/api/') === 0 ? url.pathname : '';
                } catch (e) {
                  return '';
                }
              }
              function methodOf(input, init) {
                if (init && init.method) return String(init.method).toUpperCase();
                if (input && typeof input !== 'string' && input.method) {
                  return String(input.method).toUpperCase();
                }
                return 'GET';
              }
              function retryable(method) {
                return method === 'GET' || method === 'HEAD';
              }
              function delay(ms) {
                return new Promise(function (resolve) { setTimeout(resolve, ms); });
              }
              // Never settle: the native shell is already showing the reconnect screen and will
              // reload the page with a fresh credential. A rejection here is what reaches the
              // SolidJS error boundary and paints the stack trace.
              function suspended() {
                return new Promise(function () {});
              }
              function markReady() {
                if (state.ready) return;
                state.ready = true;
                call('ready', 'api');
              }
              function finish() {
                state.pending = Math.max(0, state.pending - 1);
              }
              function fail(error, path) {
                if (!state.ready) state.failed = true;
                if (path && !state.lost) {
                  state.lost = true;
                  call('transportLost',
                    String((error && error.message) || error || 'Failed to fetch'));
                }
                return suspended();
              }
              function fetchWithRetry(input, init, attempt) {
                return nativeFetch.call(window, input, init).then(function (response) {
                  var path = apiPath(input);
                  if (path && response && response.status >= 500 &&
                      retryable(methodOf(input, init)) && state.allowRetry && attempt < 1) {
                    try {
                      if (response.body && response.body.cancel) {
                        var discarded = response.body.cancel();
                        if (discarded && discarded.catch) discarded.catch(function () {});
                      }
                    } catch (e) {}
                    return delay(200).then(function () {
                      return fetchWithRetry(input, init, attempt + 1);
                    });
                  }
                  if (path) {
                    if (response && (response.status === 401 || response.status === 403)) {
                      if (!state.authFailed) {
                        state.authFailed = true;
                        call('authFailed', String(response.status));
                      }
                      return suspended();
                    }
                    if (response && response.ok) {
                      markReady();
                      if (state.lost) {
                        state.lost = false;
                        call('transportOk', '');
                      }
                    }
                  }
                  return response;
                }, function (error) {
                  var path = apiPath(input);
                  if (!path) throw error;
                  // A request the app cancelled on purpose (leaving a screen, replacing a stale
                  // session list) is not a dead server: retrying and reporting it would flash the
                  // native reconnect screen for no reason.
                  var signal = (init && init.signal) ||
                    (input && typeof input !== 'string' && input.signal) || null;
                  if ((signal && signal.aborted) ||
                      (error && (error.name === 'AbortError' || error.name === 'TimeoutError'))) {
                    throw error;
                  }
                  if (retryable(methodOf(input, init)) && state.allowRetry && attempt < 1) {
                    return delay(200).then(function () {
                      return fetchWithRetry(input, init, attempt + 1);
                    });
                  }
                  return fail(error, path);
                });
              }
              var nativeFetch = window.fetch;
              if (typeof nativeFetch !== 'function') return;
              window.fetch = function (input, init) {
                var path = apiPath(input);
                if (path) state.pending++;
                var request;
                try {
                  request = fetchWithRetry(input, init, 0);
                } catch (error) {
                  if (path) finish();
                  if (path) return fail(error, path);
                  throw error;
                }
                if (path) request.then(finish, finish);
                return request;
              };
              // Keep non-enumerable properties that the app or its HTTP client attached to the
              // original fetch (for example a preconnect helper), so wrapping it is transparent.
              try {
                Object.getOwnPropertyNames(nativeFetch).forEach(function (key) {
                  if (key === 'length' || key === 'name' || key === 'prototype') return;
                  if (window.fetch[key] === undefined) window.fetch[key] = nativeFetch[key];
                });
              } catch (e) {}
              // Chunk loads, event streams and PTY sockets do not go through window.fetch. Report
              // them so native can hide the page instead of letting the error boundary paint.
              window.addEventListener('error', function (event) {
                var src = String((event && (event.filename ||
                  (event.target && (event.target.src || event.target.href)))) || '');
                if (!src || apiPath(src) || /\.js($|\?)/.test(src)) {
                  call('reportFatal', 'error');
                }
              }, true);
              window.addEventListener('unhandledrejection', function (event) {
                var reason = event && event.reason;
                var text = String((reason && (reason.message || reason)) || '');
                if (text.indexOf('fetch') >= 0 || text.indexOf('Transport') >= 0) {
                  call('reportFatal', 'rejection');
                }
              });
              // Single page app route: one bridge method, no second watcher script.
              function reportRoute() {
                try {
                  var match = location.pathname.match(/\/session\/([^\/?#]+)/);
                  var id = match ? decodeURIComponent(match[1]) : '';
                  if (bridge && typeof bridge.setSession === 'function') bridge.setSession(id);
                } catch (e) {}
              }
              try {
                var push = history.pushState;
                history.pushState = function () {
                  var result = push.apply(this, arguments);
                  setTimeout(reportRoute, 0);
                  return result;
                };
                var replace = history.replaceState;
                history.replaceState = function () {
                  var result = replace.apply(this, arguments);
                  setTimeout(reportRoute, 0);
                  return result;
                };
              } catch (e) {}
              window.addEventListener('popstate', reportRoute);
              window.addEventListener('hashchange', reportRoute);
              document.addEventListener('click', function () { setTimeout(reportRoute, 40); }, true);
              setInterval(reportRoute, 2500);
              reportRoute();
              // Absolute local paths of the chat go to the native file manager.
              function localPath(target) {
                try {
                  if (!(target instanceof Element)) return '';
                  var link = target.closest('a[data-local-link]');
                  if (link) {
                    var href = (link.dataset.localLink || '').trim();
                    return href.charAt(0) === '/' && href.charAt(1) !== '/' ? href : '';
                  }
                  var code = target.closest(':not(pre) > code[data-inline-code-kind="path"]');
                  if (code && !code.closest('a')) {
                    var text = (code.textContent || '').trim();
                    return text.charAt(0) === '/' && text.charAt(1) !== '/' ? text : '';
                  }
                } catch (e) {}
                return '';
              }
              function openLocal(event, target) {
                if (event.defaultPrevented) return;
                var path = localPath(target);
                if (!path) return;
                if (!bridge || typeof bridge.openLocalFile !== 'function') return;
                try {
                  bridge.openLocalFile(path);
                } catch (e) {
                  return;
                }
                event.preventDefault();
                event.stopPropagation();
              }
              document.addEventListener('click', function (event) {
                if (event.button !== 0) return;
                openLocal(event, event.target);
              }, true);
              document.addEventListener('keydown', function (event) {
                if (event.key !== 'Enter') return;
                openLocal(event, event.target);
              }, true);
              // A cached page that never touches the API still has to leave the boot screen.
              window.addEventListener('load', function () {
                setTimeout(function () {
                  if (state.ready || state.failed || state.pending > 0) return;
                  markReady();
                }, 1500);
              });
            })();
        """.trimIndent()

        /**
         * Events that wake the event streams of the web UI without reloading the page. The
         * visibility state of a resumed WebView is already `visible`, so the synthetic event makes
         * the app run the same reconnect path it uses when a tab becomes visible again.
         */
        private val NUDGE_SCRIPT = """
            (function () {
              try {
                document.dispatchEvent(new Event('visibilitychange'));
                window.dispatchEvent(new Event('focus'));
                window.dispatchEvent(new Event('online'));
                window.dispatchEvent(new Event('pageshow'));
                window.dispatchEvent(new CustomEvent('opencode:resume'));
              } catch (e) {}
            })();
        """.trimIndent()
    }
}

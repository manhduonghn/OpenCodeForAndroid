package com.opencode.android

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * CodeMirror 6 renderer.
 *
 * The page is a fully local asset (`https://appassets.androidplatform.net/editor/index.html`); it
 * never loads a CDN and has no network access. Kotlin sends commands through
 * [EditorBridge], JavaScript reports dirty/cursor/snapshot events back.
 */
@SuppressLint("SetJavaScriptEnabled")
class CodeMirrorEditorRenderer(
    private val activity: Activity,
    private val host: ViewGroup,
) : EditorRenderer {

    override var listener: EditorRenderer.Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private val requestIds = AtomicLong(0)

    private val assets = WebViewAssetLoader.AssetsPathHandler(activity)

    /**
     * [WebViewAssetLoader] strips the registered prefix before invoking a
     * [WebViewAssetLoader.PathHandler], so a handler registered on `/editor/` receives
     * `index.html` for `/editor/index.html`. The handler has to put the `editor/` folder back;
     * otherwise the page resolves to the non-existent `assets/index.html` and every editor
     * request is a 404.
     */
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler(
            EDITOR_PATH,
            WebViewAssetLoader.PathHandler { path -> assets.handle("editor/$path") },
        )
        .build()

    private var webView: WebView? = null
    private var ready = false
    private var disposed = false
    private var protocolOk = false
    private var pendingLoad: PendingLoad? = null
    private var theme: EditorTheme = EditorThemeBuilder.fallback()
    private var settings: EditorSettings? = null
    private var readOnly = false
    private var readOnlyReason: Int? = null
    private var language = EditorLanguageRegistry.byId("plaintext")
    private var document: TextDocument? = null
    private var currentSnapshotId = 0L
    private var currentSnapshotCallback: ((EditorSnapshot) -> Unit)? = null
    private var savedVersion = 0L
    private var canUndo = false
    private var canRedo = false
    private var readyTimeout: Runnable? = null

    override val view: View get() = webView ?: host

    private data class PendingLoad(
        val document: TextDocument,
        val language: EditorLanguage,
        val state: EditorViewState?,
        val readOnly: Boolean,
        val draftText: String?,
    )

    override fun load(
        document: TextDocument,
        language: EditorLanguage,
        state: EditorViewState?,
        readOnly: Boolean,
        draftText: String?,
    ) {
        this.document = document
        this.language = language
        this.readOnly = readOnly || document.readOnlyReason != null
        this.readOnlyReason = document.readOnlyReason
        this.savedVersion = 0
        ensureWebView()
        if (ready && protocolOk) {
            sendInit()
            sendLoad(document, language, state, this.readOnly, draftText)
        } else {
            pendingLoad = PendingLoad(document, language, state, this.readOnly, draftText)
        }
    }

    override fun snapshot(reason: SnapshotReason, callback: (EditorSnapshot) -> Unit) {
        if (!ready) {
            callback(emptySnapshot(reason))
            return
        }
        val id = requestIds.incrementAndGet()
        currentSnapshotId = id
        currentSnapshotCallback = callback
        evaluate(
            "requestSnapshot",
            JSONObject().put("reason", reason.wire).put("requestId", id),
        )
    }

    override fun confirmSaved(version: Long, hash: String) {
        evaluate("confirmSaved", JSONObject().put("version", version).put("hash", hash))
    }

    override fun saveFailed(version: Long, reason: String) {
        evaluate("saveFailed", JSONObject().put("version", version).put("reason", reason))
    }

    override fun setReadOnly(reason: Int?) {
        readOnlyReason = reason
        readOnly = reason != null
        evaluate("setReadOnly", JSONObject().put("readOnly", readOnly).put("reason", reason ?: 0))
    }

    override fun setTheme(theme: EditorTheme) {
        this.theme = theme
        evaluate("setTheme", theme.toJson())
    }

    override fun setSettings(settings: EditorSettings) {
        this.settings = settings
        evaluate("setSettings", settings.toJson())
    }

    override fun setLanguage(language: EditorLanguage) {
        this.language = language
        evaluate("setLanguage", JSONObject().put("id", language.id))
    }

    override fun find(options: FindOptions) {
        evaluate(
            "find",
            JSONObject()
                .put("query", options.query)
                .put("caseSensitive", options.caseSensitive)
                .put("regexp", options.regexp)
                .put("wholeWord", options.wholeWord),
        )
    }

    override fun findNext(forward: Boolean) {
        evaluate("findNext", JSONObject().put("forward", forward))
    }

    override fun clearFind() {
        evaluate("clearFind", JSONObject())
    }

    override fun undo() {
        evaluate("undo", JSONObject())
    }

    override fun redo() {
        evaluate("redo", JSONObject())
    }

    override fun gotoLine(line: Int) {
        evaluate("gotoLine", JSONObject().put("line", line))
    }

    override fun canUndo(): Boolean = canUndo
    override fun canRedo(): Boolean = canRedo

    override fun focus() {
        webView?.requestFocus()
        evaluate("focus", JSONObject())
    }

    override fun dispose() {
        disposed = true
        ready = false
        clearReadyTimeout()
        pendingLoad = null
        currentSnapshotCallback = null
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
        webView = null
    }

    private fun ensureWebView() {
        if (webView != null || disposed) return
        if (WebViewCompat.getCurrentWebViewPackage(activity) == null) {
            listener?.onRendererError("no-webview", "Android System WebView is not installed")
            return
        }
        val view = WebView(activity)
        view.setBackgroundColor(0xFF000000.toInt())
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Keep the editor offline through the page CSP and the scoped interception below.
            // blockNetworkLoads is intentionally not used: the editor page is served from an
            // https origin, and on some WebView versions blocking network loads also blocks the
            // intercepted main-frame request before WebViewAssetLoader can answer it.
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        view.addJavascriptInterface(bridge, BRIDGE_NAME)
        view.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                // Let WebView handle about:/data:/blob: itself; every http(s) request either
                // resolves to a bundled editor asset or is answered with an empty 404 so it can
                // never reach the network.
                if (scheme != "http" && scheme != "https") return null
                val editorAsset = scheme == "https" &&
                    url.host == ASSET_HOST &&
                    url.path.orEmpty().startsWith(EDITOR_PATH)
                return if (editorAsset) {
                    assetLoader.shouldInterceptRequest(url)
                } else {
                    WebResourceResponse(null, null, null)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = !request.url.toString().startsWith(ASSET_URL)

            override fun onPageFinished(view: WebView, url: String) {
                sendInit()
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !url.startsWith(ASSET_URL)

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                handleProcessGone()
                return true
            }
        }
        webView = view
        host.addView(view)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }
        scheduleReadyTimeout()
        view.loadUrl("$ASSET_URL/index.html")
    }

    /**
     * A WebView that never reports the protocol is useless, but it must not leave a blank
     * editor forever on a slow or broken device. The native renderer is always available.
     */
    private fun scheduleReadyTimeout() {
        clearReadyTimeout()
        val timeout = Runnable {
            readyTimeout = null
            if (disposed || ready) return@Runnable
            listener?.onRendererError(
                "ready-timeout",
                "The editor page did not become ready in time",
            )
        }
        readyTimeout = timeout
        handler.postDelayed(timeout, READY_TIMEOUT_MS)
    }

    private fun clearReadyTimeout() {
        readyTimeout?.let { handler.removeCallbacks(it) }
        readyTimeout = null
    }

    private fun handleProcessGone() {
        handler.post {
            if (disposed) return@post
            val view = webView
            webView = null
            ready = false
            clearReadyTimeout()
            pendingLoad = null
            view?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            listener?.onRendererError("render-process-gone", "The editor WebView crashed")
        }
    }

    private fun sendInit() {
        if (webView == null || disposed) return
        val payload = JSONObject()
            .put("protocol", EditorTheme.PROTOCOL)
            .put("settings", (settings ?: defaultSettings()).toJson())
            .put("theme", theme.toJson())
            .put("language", language.id)
            .put("readOnly", readOnly)
        evaluate("init", payload)
    }

    private fun sendLoad(
        document: TextDocument,
        language: EditorLanguage,
        state: EditorViewState?,
        readOnly: Boolean,
        draftText: String?,
    ) {
        val baseText = normalizeForEditor(document.text)
        val text = draftText?.let { normalizeForEditor(it) } ?: baseText
        val payload = JSONObject()
            .put("protocol", EditorTheme.PROTOCOL)
            .put("path", document.file.absolutePath)
            .put("name", document.file.name)
            .put("text", text)
            .put("baseText", baseText)
            .put("version", if (draftText != null && text != baseText) 1 else 0)
            .put("baseHash", document.hash)
            .put("language", language.id)
            .put("languageSource", "filename")
            .put("readOnly", readOnly)
        if (state != null) {
            payload.put(
                "selection",
                JSONObject().put("anchor", state.anchor.coerceIn(0, text.length))
                    .put("head", state.head.coerceIn(0, text.length)),
            )
            payload.put(
                "scroll",
                JSONObject().put("top", state.scrollTop).put("left", state.scrollLeft),
            )
        }
        evaluate("loadDocument", payload)
    }

    private fun evaluate(method: String, payload: JSONObject) {
        val view = webView ?: return
        if (disposed) return
        val script = "window.OpenCodeEditor && window.OpenCodeEditor.$method($payload)"
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.evaluateJavascript(script, null)
        } else {
            handler.post { if (!disposed) view.evaluateJavascript(script, null) }
        }
    }

    private fun defaultSettings(): EditorSettings = EditorSettings(
        fontSize = 14,
        lineHeight = 1.45f,
        fontFamily = "monospace",
        tabSize = 4,
        softTab = true,
        wordWrap = false,
        lineNumbers = true,
        relativeLineNumbers = false,
        highlightActiveLine = true,
        bracketMatching = true,
        autoCloseBrackets = true,
        autoIndent = true,
        foldGutter = true,
        indentGuides = true,
        rainbowBrackets = false,
        autocomplete = "local",
        showWhitespace = false,
    )

    private fun normalizeForEditor(text: String): String {
        val lf = text.replace("\r\n", "\n").replace('\r', '\n')
        return if (lf.startsWith('\uFEFF')) lf.substring(1) else lf
    }

    private fun emptySnapshot(reason: SnapshotReason): EditorSnapshot {
        val current = document
        return EditorSnapshot(
            reason = reason,
            requestId = 0,
            text = current?.text.orEmpty(),
            version = savedVersion,
            baseHash = current?.hash.orEmpty(),
            anchor = 0,
            head = 0,
            scrollTop = 0,
            scrollLeft = 0,
            dirty = false,
            languageId = language.id,
        )
    }

    private val bridge = EditorBridge(object : EditorBridge.Callbacks {
        override fun onReady(protocol: Int, capabilities: JSONObject) {
            handler.post {
                if (disposed) return@post
                if (protocol != EditorTheme.PROTOCOL) {
                    protocolOk = false
                    clearReadyTimeout()
                    listener?.onRendererError(
                        "protocol",
                        "Editor protocol $protocol is not supported",
                    )
                    return@post
                }
                protocolOk = true
                ready = true
                clearReadyTimeout()
                sendInit()
                pendingLoad?.let {
                    pendingLoad = null
                    sendLoad(it.document, it.language, it.state, it.readOnly, it.draftText)
                }
                listener?.onReady()
            }
        }

        override fun onDirtyChanged(dirty: Boolean, length: Int) {
            handler.post { if (!disposed) listener?.onDirtyChanged(dirty, length) }
        }

        override fun onCursorChanged(line: Int, column: Int, selectedChars: Int) {
            handler.post {
                if (!disposed) listener?.onCursorChanged(line, column, selectedChars)
            }
        }

        override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
            handler.post {
                if (disposed) return@post
                this@CodeMirrorEditorRenderer.canUndo = canUndo
                this@CodeMirrorEditorRenderer.canRedo = canRedo
                listener?.onHistoryChanged(canUndo, canRedo)
            }
        }

        override fun onSearchResult(count: Int, index: Int) {
            handler.post { if (!disposed) listener?.onSearchResult(count, index) }
        }

        override fun onSnapshot(snapshot: EditorSnapshot) {
            handler.post {
                if (disposed) return@post
                savedVersion = snapshot.version
                if (snapshot.requestId != currentSnapshotId) return@post
                val callback = currentSnapshotCallback
                currentSnapshotCallback = null
                callback?.invoke(snapshot)
            }
        }

        override fun onError(code: String, message: String) {
            handler.post {
                if (!disposed) listener?.onRendererError(code, message)
            }
        }

        override fun onLink(url: String) {
            handler.post { if (!disposed) listener?.onLink(url) }
        }

        override fun onLanguageResolved(id: String) {
            // The native registry is the source of truth; the page only confirms the load.
        }

        override fun onFocusChanged(focused: Boolean) {
            handler.post { if (!disposed) listener?.onFocusChanged(focused) }
        }

        override fun onMetrics(payload: JSONObject) = Unit
    })

    private companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val EDITOR_PATH = "/editor/"
        const val ASSET_URL = "https://" + ASSET_HOST + "/editor"
        const val BRIDGE_NAME = "OpenCodeEditorBridge"

        /** Long enough for a cold start on an old device, short enough to avoid a blank screen. */
        const val READY_TIMEOUT_MS = 8_000L
    }
}


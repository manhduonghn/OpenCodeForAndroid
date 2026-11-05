package com.opencode.android

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Validating endpoint of the JavaScript -> Kotlin half of the editor bridge.
 *
 * The WebView calls these methods on its own thread; the renderer moves every callback to the UI
 * thread before touching a view. Nothing here exposes a File, Context or WebView to JavaScript.
 */
class EditorBridge(private val callbacks: Callbacks) {

    interface Callbacks {
        fun onReady(protocol: Int, capabilities: JSONObject)
        fun onDirtyChanged(dirty: Boolean, length: Int)
        fun onCursorChanged(line: Int, column: Int, selectedChars: Int)
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean)
        fun onSearchResult(count: Int, index: Int)
        fun onSnapshot(snapshot: EditorSnapshot)
        fun onError(code: String, message: String)
        fun onLink(url: String)
        fun onLanguageResolved(id: String)
        fun onFocusChanged(focused: Boolean)
        fun onMetrics(payload: JSONObject)
    }

    @JavascriptInterface
    fun onReady(payload: String) {
        val json = parse(payload) ?: return
        callbacks.onReady(json.optInt("protocol", 0), json.optJSONObject("capabilities") ?: JSONObject())
    }

    @JavascriptInterface
    fun onDirtyChanged(payload: String) {
        val json = parse(payload) ?: return
        callbacks.onDirtyChanged(json.optBoolean("dirty", false), json.optInt("length", 0))
    }

    @JavascriptInterface
    fun onCursorChanged(payload: String) {
        val json = parse(payload) ?: return
        callbacks.onCursorChanged(
            json.optInt("line", 1),
            json.optInt("column", 1),
            json.optInt("selectedChars", 0),
        )
    }

    @JavascriptInterface
    fun onHistoryChanged(payload: String) {
        val json = parse(payload) ?: return
        callbacks.onHistoryChanged(json.optBoolean("canUndo", false), json.optBoolean("canRedo", false))
    }

    @JavascriptInterface
    fun onSearchResult(payload: String) {
        val json = parse(payload) ?: return
        callbacks.onSearchResult(json.optInt("count", 0), json.optInt("index", -1))
    }

    @JavascriptInterface
    fun onSnapshot(payload: String) {
        if (payload.length > EditorProtocol.MAX_MESSAGE_CHARS) {
            callbacks.onError("oversize", "Snapshot is larger than the bridge limit")
            return
        }
        val json = parse(payload) ?: return
        val text = json.optString("text", "")
        if (!EditorProtocol.withinTextLimit(text.length)) {
            callbacks.onError("oversize", "The document is too large for the bridge")
            return
        }
        val selection = json.optJSONObject("selection")
        val scroll = json.optJSONObject("scroll")
        callbacks.onSnapshot(
            EditorSnapshot(
                reason = SnapshotReason.entries.firstOrNull { it.wire == json.optString("reason") }
                    ?: SnapshotReason.SAVE,
                requestId = json.optLong("requestId", 0),
                text = text,
                version = json.optLong("version", 0),
                baseHash = json.optString("baseHash", ""),
                anchor = selection?.optInt("anchor", 0) ?: 0,
                head = selection?.optInt("head", 0) ?: 0,
                scrollTop = scroll?.optInt("top", 0) ?: 0,
                scrollLeft = scroll?.optInt("left", 0) ?: 0,
                dirty = json.optBoolean("dirty", true),
                languageId = json.optString("language", "plaintext"),
            ),
        )
    }

    @JavascriptInterface
    fun onError(payload: String) {
        val json = parse(payload) ?: return
        callbacks.onError(json.optString("code", "error"), json.optString("message", ""))
    }

    @JavascriptInterface
    fun onLink(payload: String) {
        val json = parse(payload) ?: return
        val url = json.optString("url", "")
        if (!EditorProtocol.isSafeLink(url)) return
        callbacks.onLink(url)
    }

    @JavascriptInterface
    fun onLanguageResolved(payload: String) {
        val json = parse(payload) ?: return
        callbacks.onLanguageResolved(json.optString("id", "plaintext"))
    }

    @JavascriptInterface
    fun onFocusChanged(payload: String) {
        val json = parse(payload) ?: return
        callbacks.onFocusChanged(json.optBoolean("focused", false))
    }

    @JavascriptInterface
    fun onMetrics(payload: String) {
        val json = parse(payload) ?: return
        callbacks.onMetrics(json)
    }

    private fun parse(payload: String): JSONObject? {
        if (!EditorProtocol.withinMessageLimit(payload.length)) return null
        return try {
            JSONObject(payload)
        } catch (e: Exception) {
            null
        }
    }
}

package com.opencode.android

import java.net.URI

/**
 * Pure validation rules shared by the Kotlin and JavaScript halves of the editor bridge.
 *
 * Keeping them here (instead of inline in [EditorBridge]) makes the limits testable without a
 * WebView and gives the renderer a single place to reject malformed payloads.
 */
object EditorProtocol {

    /** Keep in sync with `PROTOCOL` in `tools/editor/src/bridge.ts`. */
    const val VERSION = 1

    /**
     * Largest document the bridge round-trips. This is a hard ceiling, well above
     * `TextFileIO.MAX_EDITABLE_BYTES` (2 MiB of bytes); the WebView is only used for files
     * below `EditorScreen.MAX_WEB_EDITOR_BYTES`, and native rendering covers the rest.
     */
    const val MAX_TEXT_CHARS = 4 * 1024 * 1024

    /** JSON envelope overhead on top of the text field. */
    const val MAX_MESSAGE_CHARS = MAX_TEXT_CHARS + 64 * 1024

    const val MAX_URL_CHARS = 2048

    fun supports(version: Int): Boolean = version == VERSION

    fun withinTextLimit(length: Int): Boolean = length in 0..MAX_TEXT_CHARS

    fun withinMessageLimit(length: Int): Boolean = length in 0..MAX_MESSAGE_CHARS

    /**
     * Links from the editor page are opened by the system browser. Only the three schemes the
     * app intends to hand off are accepted; `javascript:`, `file:`, `content:` and custom
     * intents are rejected.
     */
    fun isSafeLink(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_CHARS) return false
        val scheme = try {
            URI(url).scheme?.lowercase()
        } catch (e: Exception) {
            null
        }
        return scheme == "http" || scheme == "https" || scheme == "mailto"
    }
}

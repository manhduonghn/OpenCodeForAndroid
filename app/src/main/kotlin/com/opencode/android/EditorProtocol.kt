package com.opencode.android

import java.net.URI

/**
 * Small validation helpers shared by the in-app editor features.
 *
 * They live here so link handling and recovery limits stay testable without an Activity.
 */
object EditorProtocol {

    /**
     * Largest text buffer the editor will persist. This is a hard ceiling, well above
     * `TextFileIO.MAX_EDITABLE_BYTES` (2 MiB of bytes).
     */
    const val MAX_TEXT_CHARS = 4 * 1024 * 1024

    const val MAX_URL_CHARS = 2048

    /**
     * Links from Markdown preview are opened by the system browser. Only the three schemes the
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

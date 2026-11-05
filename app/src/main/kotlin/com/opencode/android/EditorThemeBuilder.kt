package com.opencode.android

import android.content.Context
import org.json.JSONObject

/**
 * Serializes the palette resolved by [OpenCodeWebTheme] into the JSON contract understood by
 * `app/src/main/assets/editor/editor.js`.
 *
 * The editor page has no hard-coded theme: changing the wallpaper, dark mode or a custom palette
 * only requires sending a new payload.
 */
data class EditorTheme(
    val dark: Boolean,
    val colors: Map<String, String>,
    val syntax: Map<String, String>,
    val markdown: Map<String, String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", PROTOCOL)
        put("dark", dark)
        put("colors", JSONObject(colors as Map<*, *>))
        put("syntax", JSONObject(syntax as Map<*, *>))
        put("markdown", JSONObject(markdown as Map<*, *>))
    }

    companion object {
        const val PROTOCOL = EditorProtocol.VERSION
    }
}

object EditorThemeBuilder {

    fun build(context: Context): EditorTheme {
        val tokens = OpenCodeWebTheme.tokens(context)
        return EditorTheme(
            dark = tokens.dark,
            colors = tokens.editor,
            syntax = tokens.syntax,
            markdown = tokens.markdown,
        )
    }

    /** A palette for tests or for a renderer created before the activity theme is ready. */
    fun fallback(dark: Boolean = false): EditorTheme {
        val background = if (dark) "#111318" else "#fffbfe"
        val foreground = if (dark) "#e6e1e5" else "#1c1b1f"
        return EditorTheme(
            dark = dark,
            colors = mapOf(
                "background" to background,
                "foreground" to foreground,
                "surface" to background,
                "gutter" to if (dark) "#16181e" else "#f3edf7",
                "gutterText" to if (dark) "#8a8f9a" else "#49454f",
                "caret" to if (dark) "#d0bcff" else "#6750a4",
                "selection" to "rgba(103, 80, 164, 0.35)",
                "activeLine" to "rgba(28, 27, 31, 0.05)",
                "searchMatch" to if (dark) "#633b48" else "#ffd8e4",
                "searchMatchSelected" to if (dark) "#7d5260" else "#eaddff",
                "bracketMatch" to if (dark) "#938f99" else "#79747e",
                "indentGuide" to if (dark) "#49454f" else "#cac4d0",
                "invalid" to if (dark) "#f2b8b5" else "#b3261e",
                "foldPlaceholder" to if (dark) "#2b2930" else "#e7e0ec",
            ),
            syntax = mapOf(
                "comment" to if (dark) "#8a8f9a" else "#79747e",
                "keyword" to if (dark) "#d0bcff" else "#6750a4",
                "string" to if (dark) "#efb8c8" else "#7d5260",
                "primitive" to if (dark) "#d0bcff" else "#6750a4",
                "property" to if (dark) "#ccc2dc" else "#625b71",
                "type" to if (dark) "#efb8c8" else "#7d5260",
                "function" to if (dark) "#efb8c8" else "#7d5260",
                "constant" to if (dark) "#ccc2dc" else "#625b71",
                "operator" to if (dark) "#cac4d0" else "#49454f",
                "punctuation" to if (dark) "#cac4d0" else "#49454f",
                "variable" to foreground,
                "invalid" to if (dark) "#f2b8b5" else "#b3261e",
            ),
            markdown = mapOf(
                "heading" to if (dark) "#d0bcff" else "#6750a4",
                "text" to foreground,
                "link" to if (dark) "#d0bcff" else "#6750a4",
                "code" to if (dark) "#efb8c8" else "#7d5260",
                "quote" to if (dark) "#938f99" else "#79747e",
                "emph" to if (dark) "#ccc2dc" else "#625b71",
                "strong" to if (dark) "#d0bcff" else "#6750a4",
                "rule" to if (dark) "#49454f" else "#cac4d0",
                "listItem" to if (dark) "#d0bcff" else "#6750a4",
                "listEnumeration" to if (dark) "#ccc2dc" else "#625b71",
                "image" to if (dark) "#d0bcff" else "#6750a4",
            ),
        )
    }
}

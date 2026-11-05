package com.opencode.android

import android.view.View
import org.json.JSONObject

/** What caused a snapshot request; the JavaScript side echoes it back. */
enum class SnapshotReason(val wire: String) {
    SAVE("save"),
    CLOSE("close"),
    BACKGROUND("background"),
    RECOVERY("recovery"),
    TAB_SWITCH("tab-switch"),
}

/** Caret, selection and scroll position restored when a document is opened again. */
data class EditorViewState(
    val anchor: Int,
    val head: Int,
    val scrollTop: Int,
    val scrollLeft: Int,
)

data class EditorSnapshot(
    val reason: SnapshotReason,
    val requestId: Long,
    val text: String,
    val version: Long,
    val baseHash: String,
    val anchor: Int,
    val head: Int,
    val scrollTop: Int,
    val scrollLeft: Int,
    val dirty: Boolean,
    val languageId: String,
)

data class FindOptions(
    val query: String,
    val caseSensitive: Boolean = false,
    val regexp: Boolean = false,
    val wholeWord: Boolean = false,
)

/**
 * Renderer contract of the in-app editor.
 *
 * A renderer owns display and editing only. It never reads or writes a file; [TextFileIO] stays
 * the single owner of encoding, BOM, newline normalization, hashes and atomic saves.
 */
interface EditorRenderer {

    val view: View
    var listener: Listener?

    fun load(
        document: TextDocument,
        language: EditorLanguage,
        state: EditorViewState? = null,
        readOnly: Boolean = document.readOnlyReason != null,
    )

    fun snapshot(reason: SnapshotReason, callback: (EditorSnapshot) -> Unit)
    fun confirmSaved(version: Long, hash: String)
    fun saveFailed(version: Long, reason: String)
    fun setReadOnly(reason: Int?)
    fun setTheme(theme: EditorTheme)
    fun setSettings(settings: EditorSettings)
    fun setLanguage(language: EditorLanguage)
    fun find(options: FindOptions)
    fun findNext(forward: Boolean)
    fun clearFind()
    fun undo()
    fun redo()
    fun gotoLine(line: Int)
    fun canUndo(): Boolean
    fun canRedo(): Boolean
    fun focus()
    fun dispose()

    interface Listener {
        fun onReady() = Unit
        fun onDirtyChanged(dirty: Boolean, length: Int) = Unit
        fun onCursorChanged(line: Int, column: Int, selectedChars: Int) = Unit
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) = Unit
        fun onSearchResult(count: Int, index: Int) = Unit
        fun onFocusChanged(focused: Boolean) = Unit

        /** A renderer could not start or crashed; the controller switches to the native view. */
        fun onRendererError(code: String, message: String) = Unit

        fun onLink(url: String) = Unit

        /** The JavaScript text was larger than the bridge allows. */
        fun onOversizeSnapshot(requestId: Long, length: Int) = Unit
    }
}

/** Serializable view of the editor settings; shared by both renderers. */
data class EditorSettings(
    val fontSize: Int,
    val lineHeight: Float,
    val fontFamily: String,
    val tabSize: Int,
    val softTab: Boolean,
    val wordWrap: Boolean,
    val lineNumbers: Boolean,
    val relativeLineNumbers: Boolean,
    val highlightActiveLine: Boolean,
    val bracketMatching: Boolean,
    val autoCloseBrackets: Boolean,
    val autoIndent: Boolean,
    val foldGutter: Boolean,
    val indentGuides: Boolean,
    val rainbowBrackets: Boolean,
    val autocomplete: String,
    val showWhitespace: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fontSize", fontSize)
        put("lineHeight", lineHeight.toDouble())
        put("fontFamily", fontFamily)
        put("tabSize", tabSize)
        put("softTab", softTab)
        put("wordWrap", wordWrap)
        put("lineNumbers", lineNumbers)
        put("relativeLineNumbers", relativeLineNumbers)
        put("highlightActiveLine", highlightActiveLine)
        put("bracketMatching", bracketMatching)
        put("autoCloseBrackets", autoCloseBrackets)
        put("autoIndent", autoIndent)
        put("foldGutter", foldGutter)
        put("indentGuides", indentGuides)
        put("rainbowBrackets", rainbowBrackets)
        put("autocomplete", autocomplete)
        put("showWhitespace", showWhitespace)
    }
}

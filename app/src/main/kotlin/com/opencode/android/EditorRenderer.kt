package com.opencode.android

import android.view.View

/** What caused a snapshot request. */
enum class SnapshotReason {
    SAVE,
    CLOSE,
    BACKGROUND,
    RECOVERY,
    TAB_SWITCH,
    PREVIEW,
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

    /**
     * Show [document].
     *
     * [draftText] is an unsaved recovery buffer: it becomes the visible text while
     * `document.text` stays the last saved baseline, so the dirty flag and the conflict check
     * keep working exactly as after a normal edit.
     */
    fun load(
        document: TextDocument,
        language: EditorLanguage,
        state: EditorViewState? = null,
        readOnly: Boolean = document.readOnlyReason != null,
        draftText: String? = null,
    )

    fun snapshot(reason: SnapshotReason, callback: (EditorSnapshot) -> Unit)
    fun confirmSaved(version: Long, hash: String)
    fun saveFailed(version: Long, reason: String)
    fun setReadOnly(reason: Int?)
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
        fun onDirtyChanged(dirty: Boolean, length: Int) = Unit
        fun onCursorChanged(line: Int, column: Int, selectedChars: Int) = Unit
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) = Unit
        fun onSearchResult(count: Int, index: Int) = Unit
        fun onFocusChanged(focused: Boolean) = Unit
    }
}

/** Display settings supported by the native editor. */
data class EditorSettings(
    val fontSize: Int,
    val lineHeight: Float,
    val wordWrap: Boolean,
    val lineNumbers: Boolean,
)

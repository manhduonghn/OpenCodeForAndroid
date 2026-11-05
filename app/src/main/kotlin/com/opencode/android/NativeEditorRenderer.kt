package com.opencode.android

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import java.util.ArrayDeque

/**
 * The original `EditText` + gutter editor, kept as the fallback for old WebViews, accessibility
 * mode, very large files and the explicit "simple editor" setting.
 *
 * It owns only display and editing. Saving, hashes, encoding and dialogs stay in
 * [EditorScreen]/[TextFileIO].
 */
class NativeEditorRenderer(
    private val activity: android.app.Activity,
    private val body: View,
    private val editor: EditText,
    private val gutter: TextView,
) : EditorRenderer {

    override val view: View get() = body
    override var listener: EditorRenderer.Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var undoStack = ArrayDeque<Snapshot>()
    private var redoStack = ArrayDeque<Snapshot>()
    private var undoChars = 0
    private var suppressWatcher = false
    private var beforeChange: Snapshot? = null
    private var savedText = ""
    private var version = 0L
    private var dirty = false
    private var readOnly = false
    private var document: TextDocument? = null
    private var matches: List<IntRange> = emptyList()
    private var matchIndex = -1
    private var lastSnapshotText = ""

    private val gutterJob = Runnable { renderGutter() }

    init {
        editor.includeFontPadding = false
        gutter.includeFontPadding = false
        editor.setOnScrollChangeListener { view, _, _, _, _ -> gutter.scrollTo(0, view.scrollY) }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (suppressWatcher || s == null) return
                beforeChange = Snapshot(s.toString(), start, start + count)
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher || s == null) return
                val previous = beforeChange
                beforeChange = null
                if (previous != null && previous.text != s.toString()) {
                    pushUndo(previous)
                    redoStack.clear()
                }
                if (document != null) {
                    version++
                    updateDirty()
                    notifyCursor()
                    notifyHistory()
                }
                scheduleGutter()
                if (matches.isNotEmpty() || lastQueryMatches()) refreshMatches()
            }
        })
        editor.setOnClickListener { notifyCursor() }
        editor.setOnKeyListener { _, _, _ ->
            handler.post { notifyCursor() }
            false
        }
        editor.setOnFocusChangeListener { _, focused -> listener?.onFocusChanged(focused) }
    }

    override fun load(
        document: TextDocument,
        language: EditorLanguage,
        state: EditorViewState?,
        readOnly: Boolean,
    ) {
        this.document = document
        this.readOnly = readOnly || document.readOnlyReason != null
        savedText = document.text
        version = 0
        dirty = false
        lastSnapshotText = document.text
        resetHistory()
        suppressWatcher = true
        editor.setText(document.text)
        val length = editor.text?.length ?: 0
        if (state != null) {
            editor.setSelection(
                state.anchor.coerceIn(0, length),
                state.head.coerceIn(0, length),
            )
            handler.post {
                editor.scrollTo(state.scrollLeft, state.scrollTop)
                gutter.scrollTo(0, state.scrollTop)
            }
        } else {
            editor.setSelection(0)
        }
        suppressWatcher = false
        editor.isEnabled = !this.readOnly
        renderGutter()
        notifyCursor()
        notifyHistory()
        listener?.onDirtyChanged(false, length)
    }

    override fun snapshot(reason: SnapshotReason, callback: (EditorSnapshot) -> Unit) {
        val text = editor.text?.toString().orEmpty()
        lastSnapshotText = text
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        callback(
            EditorSnapshot(
                reason = reason,
                requestId = System.nanoTime(),
                text = text,
                version = version,
                baseHash = document?.hash.orEmpty(),
                anchor = start,
                head = end,
                scrollTop = editor.scrollY,
                scrollLeft = editor.scrollX,
                dirty = text != savedText,
                languageId = "plaintext",
            ),
        )
    }

    override fun confirmSaved(version: Long, hash: String) {
        savedText = lastSnapshotText
        updateDirty()
    }

    override fun saveFailed(version: Long, reason: String) {
        updateDirty()
    }

    override fun setReadOnly(reason: Int?) {
        readOnly = reason != null
        editor.isEnabled = !readOnly
    }

    override fun setTheme(theme: EditorTheme) = Unit

    override fun setSettings(settings: EditorSettings) {
        editor.textSize = settings.fontSize.toFloat()
        gutter.textSize = settings.fontSize.toFloat()
        editor.setLineSpacing(0f, settings.lineHeight)
        gutter.setLineSpacing(0f, settings.lineHeight)
        editor.isHorizontalScrollBarEnabled = !settings.wordWrap
        editor.setSingleLine(false)
        if (settings.wordWrap) {
            editor.setHorizontallyScrolling(false)
            editor.maxLines = Int.MAX_VALUE
            gutter.visibility = if (settings.lineNumbers) View.VISIBLE else View.GONE
        } else {
            editor.setHorizontallyScrolling(true)
            gutter.visibility = if (settings.lineNumbers) View.VISIBLE else View.GONE
        }
        scheduleGutter()
    }

    override fun setLanguage(language: EditorLanguage) = Unit

    override fun find(options: FindOptions) {
        lastQuery = options
        if (options.query.isEmpty()) {
            clearFind()
            return
        }
        val text = editor.text?.toString().orEmpty()
        val found = ArrayList<IntRange>()
        if (options.regexp) {
            try {
                val regex = Regex(
                    options.query,
                    if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
                )
                for (match in regex.findAll(text)) {
                    if (match.value.isEmpty()) continue
                    if (options.wholeWord && !isWholeWord(text, match.range.first, match.range.last + 1)) {
                        continue
                    }
                    found.add(match.range.first until match.range.last + 1)
                    if (found.size >= 20000) break
                }
            } catch (e: Exception) {
                matches = emptyList()
                matchIndex = -1
                listener?.onSearchResult(0, -1)
                return
            }
        } else {
            var index = text.indexOf(options.query, 0, ignoreCase = !options.caseSensitive)
            while (index >= 0) {
                if (!options.wholeWord || isWholeWord(text, index, index + options.query.length)) {
                    found.add(index until index + options.query.length)
                }
                index = text.indexOf(options.query, index + 1, ignoreCase = !options.caseSensitive)
                if (found.size >= 20000) break
            }
        }
        matches = found
        matchIndex = if (found.isEmpty()) -1 else 0
        if (found.isNotEmpty()) selectMatch()
        listener?.onSearchResult(found.size, matchIndex)
    }

    override fun findNext(forward: Boolean) {
        if (matches.isEmpty()) return
        matchIndex = (matchIndex + if (forward) 1 else -1 + matches.size) % matches.size
        selectMatch()
        listener?.onSearchResult(matches.size, matchIndex)
    }

    override fun clearFind() {
        matches = emptyList()
        matchIndex = -1
        listener?.onSearchResult(0, -1)
    }

    override fun undo() {
        if (undoStack.isEmpty() || readOnly) return
        val snapshot = undoStack.pollLast() ?: return
        undoChars -= snapshot.text.length
        redoStack.addLast(currentSnapshot())
        applySnapshot(snapshot)
    }

    override fun redo() {
        if (redoStack.isEmpty() || readOnly) return
        val snapshot = redoStack.pollLast() ?: return
        pushUndo(currentSnapshot())
        applySnapshot(snapshot)
    }

    override fun gotoLine(line: Int) {
        val text = editor.text ?: return
        var currentLine = 1
        var index = 0
        while (index < text.length && currentLine < line) {
            if (text[index] == '\n') currentLine++
            index++
        }
        val end = (index + lineLength(text, index)).coerceAtMost(text.length)
        editor.setSelection(index.coerceAtMost(end), end)
        editor.requestFocus()
    }

    override fun canUndo(): Boolean = undoStack.isNotEmpty()
    override fun canRedo(): Boolean = redoStack.isNotEmpty()

    override fun focus() {
        editor.requestFocus()
        val manager = activity.getSystemService(android.app.Activity.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        manager?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun dispose() {
        handler.removeCallbacksAndMessages(null)
        editor.setText("")
        resetHistory()
    }

    private fun lastQueryMatches(): Boolean = lastQuery != null

    private fun updateDirty() {
        val next = (editor.text?.toString().orEmpty()) != savedText
        if (next == dirty) return
        dirty = next
        listener?.onDirtyChanged(next, editor.text?.length ?: 0)
    }

    private fun notifyCursor() {
        val (line, column) = cursorPosition()
        val selected = kotlin.math.abs(editor.selectionEnd - editor.selectionStart)
        listener?.onCursorChanged(line, column, selected)
    }

    private fun notifyHistory() =
        listener?.onHistoryChanged(undoStack.isNotEmpty(), redoStack.isNotEmpty())

    private fun resetHistory() {
        undoStack.clear()
        redoStack.clear()
        undoChars = 0
        beforeChange = null
    }

    private fun pushUndo(snapshot: Snapshot) {
        undoStack.addLast(snapshot)
        undoChars += snapshot.text.length
        while (undoStack.size > MAX_UNDO || undoChars > MAX_UNDO_CHARS) {
            val removed = undoStack.pollFirst() ?: break
            undoChars -= removed.text.length
        }
    }

    private fun currentSnapshot(): Snapshot = Snapshot(
        editor.text.toString(),
        editor.selectionStart.coerceAtLeast(0),
        editor.selectionEnd.coerceAtLeast(0),
    )

    private fun applySnapshot(snapshot: Snapshot) {
        suppressWatcher = true
        editor.setText(snapshot.text)
        val length = editor.text?.length ?: 0
        editor.setSelection(snapshot.start.coerceIn(0, length), snapshot.end.coerceIn(0, length))
        suppressWatcher = false
        version++
        updateDirty()
        scheduleGutter()
        notifyCursor()
        notifyHistory()
    }

    private fun selectMatch() {
        val match = matches.getOrNull(matchIndex) ?: return
        val length = editor.text?.length ?: 0
        val end = (match.last + 1).coerceAtMost(length)
        val start = match.first.coerceIn(0, end)
        editor.setSelection(start, end)
    }

    private fun refreshMatches() {
        // The controller re-sends the query when a search is active; this is only a guard for
        // text changes while the search bar is open.
        val query = lastQuery ?: return
        find(query)
    }

    private var lastQuery: FindOptions? = null

    fun rememberFind(options: FindOptions) {
        lastQuery = options
    }

    private fun cursorPosition(): Pair<Int, Int> {
        val text = editor.text ?: return 1 to 1
        val cursor = editor.selectionStart.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (index in 0 until cursor) {
            if (text[index] == '\n') {
                line++
                lineStart = index + 1
            }
        }
        return line to (cursor - lineStart + 1)
    }

    private fun lineLength(text: CharSequence, start: Int): Int {
        var index = start
        while (index < text.length && text[index] != '\n') index++
        return index - start
    }

    private fun isWholeWord(text: String, start: Int, end: Int): Boolean {
        val before = if (start > 0) text[start - 1] else ' '
        val after = if (end < text.length) text[end] else ' '
        return !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
    }

    private fun scheduleGutter() {
        handler.removeCallbacks(gutterJob)
        handler.postDelayed(gutterJob, GUTTER_DELAY_MS)
    }

    private fun renderGutter() {
        if (gutter.visibility != View.VISIBLE) return
        val text = editor.text ?: return
        val lines = text.count { it == '\n' } + 1
        if (lines > MAX_GUTTER_LINES) {
            gutter.text = ""
            return
        }
        val builder = StringBuilder(lines * 5)
        for (line in 1..lines) {
            if (line > 1) builder.append('\n')
            builder.append(line)
        }
        gutter.text = builder.toString()
        gutter.scrollTo(0, editor.scrollY)
    }

    private data class Snapshot(val text: String, val start: Int, val end: Int)

    private companion object {
        const val MAX_UNDO = 40
        const val MAX_UNDO_CHARS = 8_000_000
        const val GUTTER_DELAY_MS = 120L
        const val MAX_GUTTER_LINES = 20_000
    }
}

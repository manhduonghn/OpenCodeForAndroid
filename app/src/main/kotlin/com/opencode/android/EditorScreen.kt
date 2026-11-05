package com.opencode.android

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-app editor for text files.
 *
 * The buffer lives in this activity-scoped controller, not in a Bundle, so a rotation (the
 * activity declares configChanges for orientation and screen size) keeps the caret and the
 * unsaved text. Saves are atomic and verify the SHA-256 of the file on disk first; a change made
 * by git or the web UI therefore opens a conflict dialog instead of being overwritten silently.
 */
class EditorScreen(
    private val activity: Activity,
    private val listener: Listener,
) {

    interface Listener {
        /** The editor is gone; the screen below it becomes interactive again. */
        fun onEditorClosed()

        /** A file was written to disk, either manually or through the auto-commit flow. */
        fun onFileSaved(file: File)

        /** The user asked to inspect the repository of [root]. */
        fun onOpenGitChanges(root: File)
    }

    private val panel: View = activity.findViewById(R.id.editor_panel)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.editor_toolbar)
    private val body: View = activity.findViewById(R.id.editor_body)
    private val editor: EditText = activity.findViewById(R.id.editor_text)
    private val gutter: TextView = activity.findViewById(R.id.editor_gutter)
    private val placeholder: View = activity.findViewById(R.id.editor_placeholder)
    private val placeholderText: TextView = activity.findViewById(R.id.editor_placeholder_text)
    private val placeholderExternal: MaterialButton =
        activity.findViewById(R.id.editor_placeholder_external)
    private val status: TextView = activity.findViewById(R.id.editor_status)
    private val readOnlyChip: TextView = activity.findViewById(R.id.editor_readonly)
    private val searchBar: View = activity.findViewById(R.id.editor_search_bar)
    private val searchLayout: TextInputLayout = activity.findViewById(R.id.editor_search_layout)
    private val search: TextInputEditText = activity.findViewById(R.id.editor_search)
    private val searchCount: TextView = activity.findViewById(R.id.editor_search_count)
    private val searchPrev: MaterialButton = activity.findViewById(R.id.editor_search_prev)
    private val searchNext: MaterialButton = activity.findViewById(R.id.editor_search_next)
    private val searchClose: ImageButton = activity.findViewById(R.id.editor_search_close)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-editor").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())

    private var document: TextDocument? = null
    private var repoRoot: File? = null
    private var dirty = false
    private var loading = false
    private var saving = false
    private var generation = 0

    @Volatile
    private var released = false

    private var suppressWatcher = false
    private var beforeChange: Snapshot? = null
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var undoChars = 0

    private var matches: List<IntRange> = emptyList()
    private var matchIndex = -1
    private var pendingAfterLoad: (() -> Unit)? = null

    /** Set when the current file was handed to another app, so returning can auto-commit it. */
    private var externalEditPending = false

    private val gutterJob = Runnable { renderGutter() }

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    init {
        configureEditor()
        toolbar.setNavigationOnClickListener { goBack() }
        toolbar.setOnMenuItemClickListener(::onMenuItem)
        placeholderExternal.setOnClickListener { currentFile()?.let { openExternal(it) } }
        searchPrev.setOnClickListener { stepMatch(-1) }
        searchNext.setOnClickListener { stepMatch(1) }
        searchClose.setOnClickListener { closeSearch() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshMatches()
        })
        search.setOnEditorActionListener { _, _, _ ->
            stepMatch(1)
            true
        }
    }

    fun show(file: File, after: (() -> Unit)? = null, force: Boolean = false) {
        if (!file.isFile) {
            snack(activity.getString(R.string.opencode_files_open_failed))
            return
        }
        if (!force && isVisible && currentFile()?.absolutePath == file.absolutePath) return
        pendingAfterLoad = after
        externalEditPending = false
        OverlayMotion.reveal(panel)
        hideSearch()
        suppressWatcher = true
        editor.setText("")
        suppressWatcher = false
        resetHistory()
        document = null
        repoRoot = null
        dirty = false
        loading = true
        saving = false
        toolbar.title = file.name
        toolbar.subtitle = file.parentFile?.absolutePath.orEmpty()
        placeholder.visibility = View.GONE
        body.visibility = View.VISIBLE
        readOnlyChip.visibility = View.GONE
        editor.isEnabled = true
        status.text = activity.getString(R.string.opencode_files_scanning)
        updateMenu()

        val request = ++generation
        executor.execute {
            val loaded = TextFileIO.load(file)
            val root = OpenCodeGitHub.findRepoRoot(file)
            activity.runOnUiThread {
                if (released || request != generation) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                loading = false
                when (loaded) {
                    is TextLoad.Ok -> applyDocument(loaded.document, root)
                    is TextLoad.Failure -> showLoadFailure(loaded.message, root)
                }
            }
        }
    }

    /** Back button: close the editor unless a dialog or the search bar has priority. */
    fun goBack() {
        if (searchBar.visibility == View.VISIBLE) {
            closeSearch()
            return
        }
        if (saving) return
        if (dirty) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.opencode_editor_unsaved_title)
                .setMessage(
                    activity.getString(
                        R.string.opencode_editor_unsaved_message,
                        currentFile()?.name ?: "",
                    ),
                )
                .setPositiveButton(R.string.opencode_editor_save_close) { _, _ ->
                    save(after = { close() })
                }
                .setNeutralButton(R.string.opencode_editor_discard) { _, _ -> close() }
                .setNegativeButton(R.string.opencode_editor_keep_editing, null)
                .show()
            return
        }
        close()
    }

    fun release() {
        released = true
        generation++
        externalEditPending = false
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        editor.setText("")
    }

    /**
     * Called from `Activity.onResume` after the user returns from "Open in another app".
     *
     * The other app may have written the file, so the editor reloads it before the optional local
     * commit. A buffer with unsaved edits is never discarded: the conflict dialog protects it when
     * the user saves.
     */
    fun onActivityResumed() {
        if (!externalEditPending) return
        externalEditPending = false
        if (released || !isVisible) return
        val file = currentFile() ?: return
        val root = repoRoot ?: OpenCodeGitHub.findRepoRoot(file)
        val autoCommit = root != null &&
            OpenCodeSettings.gitAutoCommit(activity) &&
            OpenCodeGitHub.gitAvailable()
        if (dirty) {
            // The unsaved buffer wins; the hash check on save still protects the file on disk.
            if (autoCommit) commitLocally(root!!, file)
            return
        }
        // Reload so the buffer shows what the other app wrote, then optionally commit it locally.
        if (autoCommit) {
            show(file, after = { commitLocally(root!!, file) }, force = true)
        } else {
            show(file, force = true)
        }
    }

    private fun close() {
        generation++
        loading = false
        saving = false
        externalEditPending = false
        document = null
        dirty = false
        resetHistory()
        hideSearch()
        suppressWatcher = true
        editor.setText("")
        suppressWatcher = false
        listener.onEditorClosed()
        OverlayMotion.conceal(panel)
    }

    private fun configureEditor() {
        editor.includeFontPadding = false
        gutter.includeFontPadding = false
        editor.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            gutter.scrollTo(0, view.scrollY)
        }
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
                    dirty = true
                    refreshState()
                }
                scheduleGutter()
                if (searchBar.visibility == View.VISIBLE) refreshMatches()
            }
        })
        editor.setOnClickListener { refreshState() }
        editor.setOnKeyListener { _, _, _ ->
            handler.post { refreshState() }
            false
        }
    }

    private fun applyDocument(loaded: TextDocument, root: File?) {
        document = loaded
        repoRoot = root
        dirty = false
        resetHistory()
        toolbar.title = loaded.file.name
        toolbar.subtitle = loaded.file.parentFile?.absolutePath.orEmpty()
        val reason = loaded.readOnlyReason
        val hidden = reason == R.string.opencode_editor_readonly_binary ||
            reason == R.string.opencode_editor_readonly_large
        if (hidden) {
            suppressWatcher = true
            editor.setText("")
            suppressWatcher = false
            body.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            placeholderText.setText(reason ?: R.string.opencode_editor_readonly_binary)
            readOnlyChip.visibility = View.GONE
        } else {
            suppressWatcher = true
            editor.setText(loaded.text)
            editor.setSelection(0)
            suppressWatcher = false
            body.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
            editor.isEnabled = loaded.readOnlyReason == null
            readOnlyChip.visibility = if (loaded.readOnlyReason == null) View.GONE else View.VISIBLE
        }
        renderGutter()
        refreshState()
        updateMenu()
        val after = pendingAfterLoad
        pendingAfterLoad = null
        after?.invoke()
    }

    private fun showLoadFailure(message: String, root: File?) {
        document = null
        repoRoot = root
        dirty = false
        body.visibility = View.GONE
        placeholder.visibility = View.VISIBLE
        placeholderText.text = activity.getString(R.string.opencode_editor_load_failed, message)
        readOnlyChip.visibility = View.GONE
        status.text = message
        updateMenu()
        val after = pendingAfterLoad
        pendingAfterLoad = null
        after?.invoke()
    }

    /* ------------------------------------------------------------------ save */

    private fun save(after: (() -> Unit)? = null, force: Boolean = false) {
        val document = document ?: return
        if (document.readOnlyReason != null || saving) return
        val file = document.file
        val text = editor.text.toString()
        saving = true
        updateMenu()
        val request = ++generation
        executor.execute {
            val result = TextFileIO.save(document, text, force)
            activity.runOnUiThread {
                if (released || request != generation) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                saving = false
                when (result) {
                    is TextSave.Ok -> {
                        this@EditorScreen.document = document.copy(
                            text = text,
                            hash = result.hash,
                            size = file.length(),
                        )
                        dirty = false
                        refreshState()
                        updateMenu()
                        listener.onFileSaved(file)
                        if (after != null) {
                            after()
                        } else {
                            afterSaveMessage(file)
                        }
                    }
                    is TextSave.Conflict -> {
                        updateMenu()
                        showConflict(after)
                    }
                    is TextSave.Failure -> {
                        updateMenu()
                        snack(
                            activity.getString(
                                R.string.opencode_editor_save_failed,
                                result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun afterSaveMessage(file: File) {
        val root = repoRoot ?: OpenCodeGitHub.findRepoRoot(file)
        if (root == null) {
            snack(activity.getString(R.string.opencode_editor_saved, file.name))
            return
        }
        if (OpenCodeSettings.gitAutoCommit(activity) && OpenCodeGitHub.gitAvailable()) {
            snack(activity.getString(R.string.opencode_editor_saved, file.name))
            commitLocally(root, file)
            return
        }
        Snackbar.make(
            panel,
            activity.getString(R.string.opencode_editor_saved, file.name),
            Snackbar.LENGTH_LONG,
        ).setAction(R.string.opencode_git_title) {
            close()
            listener.onOpenGitChanges(root)
        }.show()
    }

    /**
     * The auto-commit option commits every change locally. It never pushes: pushing stays an
     * explicit action on the Git changes screen.
     */
    private fun commitLocally(root: File, file: File) {
        executor.execute {
            val current = OpenCodeGitHub.status(root)
            if (current == null || current.isClean) {
                activity.runOnUiThread {
                    if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    listener.onFileSaved(file)
                }
                return@execute
            }
            val message = if (current.changes.size == 1) {
                activity.getString(
                    R.string.opencode_files_git_auto_one,
                    current.changes.first().path,
                )
            } else {
                activity.getString(R.string.opencode_files_git_auto_many, current.changes.size)
            }
            val result = OpenCodeGitHub.commit(root, message)
            activity.runOnUiThread {
                if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (result.ok) {
                    snack(
                        activity.getString(
                            R.string.opencode_files_git_auto_done,
                            current.changes.size,
                        ),
                    )
                } else {
                    snack(
                        activity.getString(
                            R.string.opencode_files_git_auto_failed,
                            failureDetail(result.output),
                        ),
                    )
                }
                listener.onFileSaved(file)
            }
        }
    }

    private fun showConflict(after: (() -> Unit)?) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_editor_conflict_title)
            .setMessage(R.string.opencode_editor_conflict_message)
            .setPositiveButton(R.string.opencode_editor_conflict_reload) { _, _ ->
                reloadFromDisk(after)
            }
            .setNeutralButton(R.string.opencode_editor_conflict_overwrite) { _, _ ->
                save(after, force = true)
            }
            .setNegativeButton(R.string.opencode_editor_conflict_cancel, null)
            .show()
    }

    private fun reloadFromDisk(after: (() -> Unit)?) {
        val file = currentFile() ?: return
        show(file, after = after, force = true)
    }

    /* ------------------------------------------------------------------ history */

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

    private fun undo() {
        if (undoStack.isEmpty() || saving) return
        val snapshot = undoStack.pollLast() ?: return
        undoChars -= snapshot.text.length
        redoStack.addLast(currentSnapshot())
        applySnapshot(snapshot)
    }

    private fun redo() {
        if (redoStack.isEmpty() || saving) return
        val snapshot = redoStack.pollLast() ?: return
        pushUndo(currentSnapshot())
        applySnapshot(snapshot)
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
        dirty = true
        scheduleGutter()
        refreshState()
        updateMenu()
        if (searchBar.visibility == View.VISIBLE) refreshMatches()
    }

    /* ------------------------------------------------------------------ search */

    private fun openSearch() {
        OverlayMotion.reveal(searchBar)
        search.requestFocus()
        search.setText("")
        refreshMatches()
        val manager = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        hideSearch()
        search.setText("")
        editor.requestFocus()
    }

    private fun hideSearch() {
        searchBar.visibility = View.GONE
        matches = emptyList()
        matchIndex = -1
        searchCount.text = ""
    }

    private fun refreshMatches() {
        val query = search.text?.toString().orEmpty()
        if (query.isEmpty()) {
            matches = emptyList()
            matchIndex = -1
            searchCount.text = ""
            return
        }
        val text = editor.text?.toString().orEmpty()
        val found = ArrayList<IntRange>()
        var index = text.indexOf(query, 0, ignoreCase = true)
        while (index >= 0) {
            found.add(index until index + query.length)
            index = text.indexOf(query, index + 1, ignoreCase = true)
        }
        matches = found
        matchIndex = if (found.isEmpty()) -1 else 0
        if (found.isEmpty()) {
            searchCount.text = activity.getString(
                R.string.opencode_editor_search_not_found,
                query,
            )
        } else {
            searchCount.text = activity.getString(
                R.string.opencode_editor_search_count,
                matchIndex + 1,
                found.size,
            )
            selectMatch()
        }
    }

    private fun stepMatch(direction: Int) {
        if (matches.isEmpty()) return
        matchIndex = (matchIndex + direction + matches.size) % matches.size
        searchCount.text = activity.getString(
            R.string.opencode_editor_search_count,
            matchIndex + 1,
            matches.size,
        )
        selectMatch()
    }

    private fun selectMatch() {
        val match = matches.getOrNull(matchIndex) ?: return
        val length = editor.text?.length ?: 0
        val end = (match.last + 1).coerceAtMost(length)
        val start = match.first.coerceIn(0, end)
        editor.setSelection(start, end)
    }

    /* ------------------------------------------------------------------ menu */

    private fun updateMenu() {
        val menu = toolbar.menu
        val loaded = document
        val editable = loaded != null && loaded.readOnlyReason == null && !loading
        menu.findItem(R.id.action_editor_save)?.isEnabled = editable && !saving
        menu.findItem(R.id.action_editor_undo)?.isEnabled =
            editable && !saving && undoStack.isNotEmpty()
        menu.findItem(R.id.action_editor_redo)?.isEnabled =
            editable && !saving && redoStack.isNotEmpty()
        menu.findItem(R.id.action_editor_search)?.isEnabled = loaded != null
        menu.findItem(R.id.action_editor_external)?.isEnabled = currentFile() != null
        menu.findItem(R.id.action_editor_properties)?.isEnabled = currentFile() != null
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_editor_save -> save()
            R.id.action_editor_search -> openSearch()
            R.id.action_editor_undo -> undo()
            R.id.action_editor_redo -> redo()
            R.id.action_editor_external -> currentFile()?.let { openExternal(it) }
            R.id.action_editor_properties -> showProperties()
            else -> return false
        }
        return true
    }

    private fun openExternal(file: File) {
        if (FileActions.openExternal(activity, file, writable = true)) {
            externalEditPending = true
        } else {
            snack(activity.getString(R.string.opencode_files_open_failed))
        }
    }

    private fun showProperties() {
        val file = currentFile() ?: return
        val loaded = document
        val extra = ArrayList<Pair<String, String>>()
        if (loaded != null) {
            extra.add(
                activity.getString(R.string.opencode_editor_encoding) to
                    loaded.charset.name().uppercase(Locale.ROOT),
            )
            extra.add(
                activity.getString(R.string.opencode_editor_newline) to newlineLabel(loaded.newline),
            )
            loaded.readOnlyReason?.let {
                extra.add(activity.getString(R.string.opencode_editor_readonly) to activity.getString(it))
            }
        }
        FileActions.showProperties(activity, file, extra)
    }

    /* ------------------------------------------------------------------ status */

    private fun refreshState() {
        val file = currentFile() ?: return
        val loaded = document
        toolbar.title = (if (dirty) "• " else "") + file.name
        val position = cursorPosition()
        val state = activity.getString(
            if (dirty) R.string.opencode_editor_dirty else R.string.opencode_editor_clean,
        )
        val place = repoRoot?.name ?: file.parentFile?.name.orEmpty()
        val meta = buildString {
            if (loaded != null) {
                append(loaded.charset.name().uppercase(Locale.ROOT))
                append(" · ")
                append(newlineLabel(loaded.newline))
                append(" · ")
                append(OpenCodeStorage.formatBytes(loaded.size))
            }
        }
        status.text = activity.getString(
            R.string.opencode_editor_status,
            state,
            place,
            position.first,
            position.second,
            meta,
        )
        readOnlyChip.visibility = if (loaded?.readOnlyReason == null) View.GONE else View.VISIBLE
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

    private fun newlineLabel(newline: String): String = when (newline) {
        "\r\n" -> "CRLF"
        "\r" -> "CR"
        else -> "LF"
    }

    private fun scheduleGutter() {
        handler.removeCallbacks(gutterJob)
        handler.postDelayed(gutterJob, GUTTER_DELAY_MS)
    }

    private fun renderGutter() {
        val text = editor.text ?: return
        val lines = text.count { it == '\n' } + 1
        if (lines > MAX_GUTTER_LINES) {
            // A pathological single-line-per-character file would spend more memory on the line
            // numbers than on the text itself; the status bar still shows the caret position.
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

    private fun currentFile(): File? = document?.file

    private fun failureDetail(output: String): String {
        val line = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: return "?"
        return if (line.length > 220) line.take(220) + "…" else line
    }

    private fun snack(message: String) {
        Snackbar.make(panel, message, Snackbar.LENGTH_LONG).show()
    }

    private data class Snapshot(val text: String, val start: Int, val end: Int)

    private companion object {
        const val MAX_UNDO = 40
        const val MAX_UNDO_CHARS = 8_000_000
        const val GUTTER_DELAY_MS = 120L
        const val MAX_GUTTER_LINES = 20_000
    }
}

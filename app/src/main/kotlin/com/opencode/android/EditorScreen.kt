package com.opencode.android

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.webkit.WebViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-app editor controller.
 *
 * Editing and display live in an [EditorRenderer] (CodeMirror in a local WebView, or the native
 * [EditText] fallback). This controller owns file I/O through [TextFileIO], dirty/conflict/save
 * dialogs, auto-commit, search UI and the activity lifecycle hooks. The renderer never reads or
 * writes a file.
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
    private val host: ViewGroup = activity.findViewById(R.id.editor_host)
    private val webHost: ViewGroup = activity.findViewById(R.id.editor_web_host)
    private val nativeBody: View = activity.findViewById(R.id.editor_native_body)
    private val editor: EditText = activity.findViewById(R.id.editor_text)
    private val gutter: TextView = activity.findViewById(R.id.editor_gutter)
    private val placeholder: View = activity.findViewById(R.id.editor_placeholder)
    private val placeholderText: TextView = activity.findViewById(R.id.editor_placeholder_text)
    private val placeholderExternal: MaterialButton =
        activity.findViewById(R.id.editor_placeholder_external)
    private val status: TextView = activity.findViewById(R.id.editor_status)
    private val readOnlyChip: TextView = activity.findViewById(R.id.editor_readonly)
    private val languageChip: TextView = activity.findViewById(R.id.editor_language)
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

    private val nativeRenderer = NativeEditorRenderer(activity, nativeBody, editor, gutter)
    private var webRenderer: CodeMirrorEditorRenderer? = null
    private var renderer: EditorRenderer = nativeRenderer
    private var webFallbackUsed = false

    private var document: TextDocument? = null
    private var repoRoot: File? = null
    private var language: EditorLanguage = EditorLanguageRegistry.byId("plaintext")
    private var dirty = false
    private var loading = false
    private var saving = false
    private var generation = 0
    private var canUndo = false
    private var canRedo = false
    private var cursorLine = 1
    private var cursorColumn = 1
    private var selectedChars = 0
    private var pendingAfterLoad: (() -> Unit)? = null
    private var lastViewState: EditorViewState? = null

    @Volatile
    private var released = false

    /** Set when the current file was handed to another app, so returning can auto-commit it. */
    private var externalEditPending = false

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    init {
        toolbar.setNavigationOnClickListener { goBack() }
        toolbar.setOnMenuItemClickListener(::onMenuItem)
        placeholderExternal.setOnClickListener { currentFile()?.let { openExternal(it) } }
        languageChip.setOnClickListener { showLanguagePicker() }
        searchPrev.setOnClickListener {
            renderer.findNext(false)
            if (renderer === nativeRenderer) {
                // Native renderer updates count via callback; nothing else needed.
            }
        }
        searchNext.setOnClickListener { renderer.findNext(true) }
        searchClose.setOnClickListener { closeSearch() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applySearch()
        })
        search.setOnEditorActionListener { _, _, _ ->
            renderer.findNext(true)
            true
        }
        nativeRenderer.listener = rendererListener(nativeRenderer)
        applySettingsToRenderers()
    }

    fun show(file: File, after: (() -> Unit)? = null, force: Boolean = false) {
        if (!file.isFile) {
            snack(activity.getString(R.string.opencode_files_open_failed))
            return
        }
        if (!force && isVisible && currentFile()?.absolutePath == file.absolutePath) return
        pendingAfterLoad = after
        externalEditPending = false
        lastViewState = null
        OverlayMotion.reveal(panel)
        hideSearch()
        document = null
        repoRoot = null
        dirty = false
        loading = true
        saving = false
        canUndo = false
        canRedo = false
        toolbar.title = file.name
        toolbar.subtitle = file.parentFile?.absolutePath.orEmpty()
        placeholder.visibility = View.GONE
        host.visibility = View.VISIBLE
        readOnlyChip.visibility = View.GONE
        languageChip.visibility = View.GONE
        status.text = activity.getString(R.string.opencode_files_scanning)
        updateMenu()

        val request = ++generation
        executor.execute {
            val loaded = TextFileIO.load(file)
            val root = OpenCodeGitHub.findRepoRoot(file)
            val overrideId = OpenCodeSettings.editorLanguageOverride(activity, file.absolutePath)
            val resolved = if (overrideId != null) {
                EditorLanguageRegistry.byId(overrideId)
            } else {
                EditorLanguageRegistry.resolve(file)
            }
            activity.runOnUiThread {
                if (released || request != generation) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                loading = false
                language = resolved
                when (loaded) {
                    is TextLoad.Ok -> applyDocument(loaded.document, root, resolved)
                    is TextLoad.Failure -> showLoadFailure(file, loaded.message, root)
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
        nativeRenderer.dispose()
        webRenderer?.dispose()
        webRenderer = null
    }

    fun onConfigurationChanged() {
        // The renderer keeps its own state; push the current theme in case colors changed.
        pushTheme()
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
            if (autoCommit) commitLocally(root!!, file)
            return
        }
        captureViewState { state ->
            if (autoCommit) {
                show(file, after = { commitLocally(root!!, file) }, force = true)
            } else {
                show(file, force = true)
            }
            lastViewState = state
        }
    }

    private fun close() {
        captureViewState { _ ->
            generation++
            loading = false
            saving = false
            externalEditPending = false
            document = null
            dirty = false
            hideSearch()
            listener.onEditorClosed()
            OverlayMotion.conceal(panel)
        }
    }

    private fun applyDocument(loaded: TextDocument, root: File?, resolved: EditorLanguage) {
        document = loaded
        repoRoot = root
        language = resolved
        dirty = false
        toolbar.title = loaded.file.name
        toolbar.subtitle = loaded.file.parentFile?.absolutePath.orEmpty()
        OpenCodeSettings.addEditorRecentFile(activity, loaded.file.absolutePath)
        val reason = loaded.readOnlyReason
        val hidden = reason == R.string.opencode_editor_readonly_binary ||
            reason == R.string.opencode_editor_readonly_large
        if (hidden) {
            host.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            placeholderText.setText(reason ?: R.string.opencode_editor_readonly_binary)
            readOnlyChip.visibility = View.GONE
            languageChip.visibility = View.GONE
            refreshStatus(loaded)
            updateMenu()
            val after = pendingAfterLoad
            pendingAfterLoad = null
            after?.invoke()
            return
        }
        placeholder.visibility = View.GONE
        host.visibility = View.VISIBLE
        readOnlyChip.visibility = if (reason == null) View.GONE else View.VISIBLE
        languageChip.text = resolved.displayName
        languageChip.visibility = if (resolved.id == "plaintext") View.GONE else View.VISIBLE

        val target = chooseRenderer(loaded)
        activateRenderer(target)
        val state = lastViewState
        lastViewState = null
        renderer.setSettings(currentSettings())
        renderer.setTheme(EditorThemeBuilder.build(activity))
        renderer.load(loaded, resolved, state, readOnly = reason != null)
        refreshStatus(loaded)
        updateMenu()
        val after = pendingAfterLoad
        pendingAfterLoad = null
        after?.invoke()
    }

    private fun showLoadFailure(file: File, message: String, root: File?) {
        document = null
        repoRoot = root
        dirty = false
        host.visibility = View.GONE
        placeholder.visibility = View.VISIBLE
        placeholderText.text = activity.getString(R.string.opencode_editor_load_failed, message)
        readOnlyChip.visibility = View.GONE
        languageChip.visibility = View.GONE
        status.text = message
        updateMenu()
        val after = pendingAfterLoad
        pendingAfterLoad = null
        after?.invoke()
    }

    private fun chooseRenderer(loaded: TextDocument): EditorRenderer {
        if (OpenCodeSettings.editorSimpleMode(activity)) return nativeRenderer
        val preference = OpenCodeSettings.editorRenderer(activity)
        if (preference == "native") return nativeRenderer
        if (preference == "web") {
            return ensureWebRenderer() ?: nativeRenderer
        }
        // auto: prefer CodeMirror for normal files; keep native for very large or read-only text
        // that the bridge cannot safely round-trip yet.
        if (loaded.size > MAX_WEB_EDITOR_BYTES) return nativeRenderer
        if (loaded.readOnlyReason == R.string.opencode_editor_readonly_encoding) return nativeRenderer
        return if (hasSystemWebView()) ensureWebRenderer() ?: nativeRenderer else nativeRenderer
    }

    private fun hasSystemWebView(): Boolean = try {
        WebViewCompat.getCurrentWebViewPackage(activity) != null
    } catch (e: Exception) {
        false
    }

    private fun ensureWebRenderer(): CodeMirrorEditorRenderer? {
        if (webFallbackUsed) return null
        if (webRenderer == null) {
            webRenderer = CodeMirrorEditorRenderer(activity, webHost)
            webRenderer?.listener = rendererListener(webRenderer!!)
        }
        return webRenderer
    }

    private fun activateRenderer(target: EditorRenderer) {
        renderer = target
        val useWeb = target is CodeMirrorEditorRenderer
        webHost.visibility = if (useWeb) View.VISIBLE else View.GONE
        nativeBody.visibility = if (useWeb) View.GONE else View.VISIBLE
        target.setSettings(currentSettings())
        target.setTheme(EditorThemeBuilder.build(activity))
    }

    private fun fallbackToNative(code: String, message: String) {
        if (renderer === nativeRenderer) return
        webFallbackUsed = true
        val current = document ?: return
        activity.runOnUiThread {
            if (released) return@runOnUiThread
            snack(activity.getString(R.string.opencode_editor_fallback))
            renderer = nativeRenderer
            webHost.visibility = View.GONE
            nativeBody.visibility = View.VISIBLE
            nativeRenderer.setSettings(currentSettings())
            nativeRenderer.load(current, language, lastViewState, readOnly = current.readOnlyReason != null)
            updateMenu()
        }
    }

    private fun rendererListener(source: EditorRenderer): EditorRenderer.Listener =
        object : EditorRenderer.Listener {
            override fun onDirtyChanged(dirty: Boolean, length: Int) {
                if (released || source !== renderer) return
                this@EditorScreen.dirty = dirty
                refreshStatus(document)
                updateMenu()
            }

            override fun onCursorChanged(line: Int, column: Int, selectedChars: Int) {
                if (released || source !== renderer) return
                cursorLine = line
                cursorColumn = column
                this@EditorScreen.selectedChars = selectedChars
                refreshStatus(document)
            }

            override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
                if (released || source !== renderer) return
                this@EditorScreen.canUndo = canUndo
                this@EditorScreen.canRedo = canRedo
                updateMenu()
            }

            override fun onSearchResult(count: Int, index: Int) {
                if (released || source !== renderer) return
                searchCount.text = when {
                    search.text.isNullOrEmpty() -> ""
                    count <= 0 -> activity.getString(
                        R.string.opencode_editor_search_not_found,
                        search.text?.toString().orEmpty(),
                    )
                    else -> activity.getString(
                        R.string.opencode_editor_search_count,
                        (index + 1).coerceAtLeast(1),
                        count,
                    )
                }
            }

            override fun onRendererError(code: String, message: String) {
                if (released) return
                fallbackToNative(code, message)
            }

            override fun onLink(url: String) {
                if (released) return
                try {
                    activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    // A broken link must not crash the editor.
                }
            }
        }

    /* ------------------------------------------------------------------ save */

    private fun save(after: (() -> Unit)? = null, force: Boolean = false) {
        val document = document ?: return
        if (document.readOnlyReason != null || saving) return
        val file = document.file
        saving = true
        updateMenu()
        renderer.snapshot(SnapshotReason.SAVE) { snapshot ->
            val request = ++generation
            executor.execute {
                val result = TextFileIO.save(document, snapshot.text, force)
                activity.runOnUiThread {
                    if (released || request != generation) return@runOnUiThread
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    saving = false
                    when (result) {
                        is TextSave.Ok -> {
                            this@EditorScreen.document = document.copy(
                                text = snapshot.text.replace("\r\n", "\n").replace('\r', '\n'),
                                hash = result.hash,
                                size = file.length(),
                            )
                            dirty = false
                            renderer.confirmSaved(snapshot.version, result.hash)
                            refreshStatus(this@EditorScreen.document)
                            updateMenu()
                            listener.onFileSaved(file)
                            if (after != null) after() else afterSaveMessage(file)
                        }
                        is TextSave.Conflict -> {
                            renderer.saveFailed(snapshot.version, "conflict")
                            updateMenu()
                            showConflict(after)
                        }
                        is TextSave.Failure -> {
                            renderer.saveFailed(snapshot.version, result.message)
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
    }

    private fun normalizeToDocument(text: String, newline: String): String {
        val lf = text.replace("\r\n", "\n").replace('\r', '\n')
        return if (newline == "\n") lf else lf.replace("\n", newline)
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
            captureViewState { _ ->
                closeNow()
                listener.onOpenGitChanges(root)
            }
        }.show()
    }

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

    /* ------------------------------------------------------------------ search */

    private fun openSearch() {
        OverlayMotion.reveal(searchBar)
        search.requestFocus()
        search.setText("")
        applySearch()
        val manager = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        hideSearch()
        search.setText("")
        renderer.clearFind()
        renderer.focus()
    }

    private fun hideSearch() {
        searchBar.visibility = View.GONE
        searchCount.text = ""
    }

    private fun applySearch() {
        val query = search.text?.toString().orEmpty()
        if (query.isEmpty()) {
            renderer.clearFind()
            searchCount.text = ""
            return
        }
        renderer.find(FindOptions(query = query))
    }

    /* ------------------------------------------------------------------ menu */

    private fun updateMenu() {
        val menu = toolbar.menu
        val loaded = document
        val editable = loaded != null && loaded.readOnlyReason == null && !loading
        menu.findItem(R.id.action_editor_save)?.isEnabled = editable && dirty && !saving
        menu.findItem(R.id.action_editor_undo)?.isEnabled = editable && !saving && canUndo
        menu.findItem(R.id.action_editor_redo)?.isEnabled = editable && !saving && canRedo
        menu.findItem(R.id.action_editor_search)?.isEnabled = loaded != null
        menu.findItem(R.id.action_editor_goto)?.isEnabled = loaded != null
        menu.findItem(R.id.action_editor_language)?.isEnabled = loaded != null
        menu.findItem(R.id.action_editor_external)?.isEnabled = currentFile() != null
        menu.findItem(R.id.action_editor_properties)?.isEnabled = currentFile() != null
        menu.findItem(R.id.action_editor_recent)?.isEnabled = true
        menu.findItem(R.id.action_editor_word_wrap)?.let { item ->
            item.isChecked = OpenCodeSettings.editorWordWrap(activity)
            item.isEnabled = loaded != null
        }
        menu.findItem(R.id.action_editor_simple)?.let { item ->
            item.isChecked = OpenCodeSettings.editorSimpleMode(activity)
        }
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_editor_save -> save()
            R.id.action_editor_search -> openSearch()
            R.id.action_editor_undo -> renderer.undo()
            R.id.action_editor_redo -> renderer.redo()
            R.id.action_editor_goto -> showGotoLine()
            R.id.action_editor_language -> showLanguagePicker()
            R.id.action_editor_word_wrap -> toggleWordWrap(item)
            R.id.action_editor_simple -> toggleSimpleMode(item)
            R.id.action_editor_recent -> showRecentFiles()
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
        extra.add(activity.getString(R.string.opencode_editor_language) to language.displayName)
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
        extra.add(
            activity.getString(R.string.opencode_editor_simple_mode) to
                (if (renderer is CodeMirrorEditorRenderer)
                    activity.getString(R.string.opencode_editor_renderer_web)
                else
                    activity.getString(R.string.opencode_editor_renderer_native)),
        )
        FileActions.showProperties(activity, file, extra)
    }

    private fun showGotoLine() {
        val input = com.google.android.material.textfield.TextInputEditText(activity).apply {
            hint = activity.getString(R.string.opencode_editor_goto_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_editor_goto)
            .setView(input)
            .setPositiveButton(R.string.opencode_ok) { _, _ ->
                val line = input.text?.toString()?.toIntOrNull()
                if (line == null || line <= 0) {
                    snack(activity.getString(R.string.opencode_editor_goto_invalid))
                } else {
                    renderer.gotoLine(line)
                }
            }
            .setNegativeButton(R.string.opencode_cancel, null)
            .show()
    }

    private fun showLanguagePicker() {
        val file = currentFile() ?: return
        val languages = EditorLanguageRegistry.all()
        val names = ArrayList<String>(languages.size + 2)
        val ids = ArrayList<String?>(languages.size + 2)
        val detected = EditorLanguageRegistry.resolve(file)
        names.add(activity.getString(R.string.opencode_editor_language_auto, detected.displayName))
        ids.add(null)
        names.add("Plain text")
        ids.add("plaintext")
        var checked = 0
        val override = OpenCodeSettings.editorLanguageOverride(activity, file.absolutePath)
        for ((index, item) in languages.withIndex()) {
            names.add(item.displayName)
            ids.add(item.id)
            if (override != null && override == item.id) checked = index + 2
        }
        if (override == "plaintext") checked = 1
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_editor_language)
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                val selected = ids.getOrNull(which)
                OpenCodeSettings.setEditorLanguageOverride(activity, file.absolutePath, selected)
                val resolved = selected?.let { EditorLanguageRegistry.byId(it) } ?: detected
                language = resolved
                languageChip.text = resolved.displayName
                languageChip.visibility = if (resolved.id == "plaintext") View.GONE else View.VISIBLE
                renderer.setLanguage(resolved)
                refreshStatus(document)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.opencode_cancel, null)
            .show()
    }

    private fun toggleWordWrap(item: MenuItem) {
        val enabled = !item.isChecked
        OpenCodeSettings.setEditorWordWrap(activity, enabled)
        item.isChecked = enabled
        applySettingsToRenderers()
    }

    private fun toggleSimpleMode(item: MenuItem) {
        val enabled = !item.isChecked
        OpenCodeSettings.setEditorSimpleMode(activity, enabled)
        item.isChecked = enabled
        val file = currentFile() ?: return
        captureViewState { state ->
            lastViewState = state
            show(file, force = true)
        }
    }

    private fun showRecentFiles() {
        val recent = OpenCodeSettings.editorRecentFiles(activity)
        if (recent.isEmpty()) {
            snack(activity.getString(R.string.opencode_editor_recent_empty))
            return
        }
        val names = recent.map { path -> File(path).name + "\n" + path }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_editor_recent)
            .setItems(names) { _, which ->
                val path = recent.getOrNull(which) ?: return@setItems
                val file = File(path)
                if (file.isFile) show(file) else snack(activity.getString(R.string.opencode_files_open_failed))
            }
            .setNeutralButton(R.string.opencode_editor_recent_clear) { _, _ ->
                OpenCodeSettings.clearEditorRecentFiles(activity)
            }
            .setNegativeButton(R.string.opencode_cancel, null)
            .show()
    }

    /* ------------------------------------------------------------------ status */

    private fun refreshStatus(loaded: TextDocument?) {
        val file = currentFile() ?: loaded?.file ?: return
        toolbar.title = (if (dirty) "• " else "") + file.name
        val state = activity.getString(
            if (dirty) R.string.opencode_editor_dirty else R.string.opencode_editor_clean,
        )
        val place = repoRoot?.name ?: file.parentFile?.name.orEmpty()
        val meta = buildString {
            append(language.displayName)
            if (loaded != null) {
                append(" · ")
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
            cursorLine,
            cursorColumn,
            meta,
        )
        readOnlyChip.visibility = if (loaded?.readOnlyReason == null) View.GONE else View.VISIBLE
    }

    private fun newlineLabel(newline: String): String = when (newline) {
        "\r\n" -> "CRLF"
        "\r" -> "CR"
        else -> "LF"
    }

    private fun captureViewState(after: (EditorViewState?) -> Unit) {
        if (document == null) {
            after(null)
            return
        }
        try {
            renderer.snapshot(SnapshotReason.BACKGROUND) { snapshot ->
                after(
                    EditorViewState(
                        anchor = snapshot.anchor,
                        head = snapshot.head,
                        scrollTop = snapshot.scrollTop,
                        scrollLeft = snapshot.scrollLeft,
                    ),
                )
            }
        } catch (e: Exception) {
            after(null)
        }
    }

    private fun closeNow() {
        generation++
        loading = false
        saving = false
        externalEditPending = false
        document = null
        dirty = false
        hideSearch()
        listener.onEditorClosed()
        OverlayMotion.conceal(panel)
    }

    private fun currentSettings(): EditorSettings = EditorSettings(
        fontSize = OpenCodeSettings.editorFontSize(activity),
        lineHeight = 1.45f,
        fontFamily = "monospace",
        tabSize = OpenCodeSettings.editorTabSize(activity),
        softTab = OpenCodeSettings.editorSoftTab(activity),
        wordWrap = OpenCodeSettings.editorWordWrap(activity),
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

    private fun applySettingsToRenderers() {
        val settings = currentSettings()
        nativeRenderer.setSettings(settings)
        webRenderer?.setSettings(settings)
        pushTheme()
    }

    private fun pushTheme() {
        val theme = EditorThemeBuilder.build(activity)
        nativeRenderer.setTheme(theme)
        webRenderer?.setTheme(theme)
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

    private companion object {
        // The JS bridge serializes the full buffer as a JSON string; keep it conservative until
        // a chunked/streaming bridge exists.
        const val MAX_WEB_EDITOR_BYTES = 1_500_000L
    }
}

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
import android.view.accessibility.AccessibilityManager
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

    /** Visible unsaved text that is not [document].text, e.g. a restored recovery draft. */
    private var activeDraft: String? = null

    /** True while a recovery file the user chose to keep for later still belongs to this file. */
    private var deferredRecovery = false

    /** Guards against showing the TalkBack suggestion twice while a load is in flight. */
    private var a11yPromptShowing = false

    @Volatile
    private var released = false

    /** Set when the current file was handed to another app, so returning can auto-commit it. */
    private var externalEditPending = false

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    init {
        toolbar.setNavigationOnClickListener { goBack() }
        toolbar.tintActionIcons()
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

    fun show(
        file: File,
        after: (() -> Unit)? = null,
        force: Boolean = false,
        skipRecovery: Boolean = false,
    ) {
        if (!file.isFile) {
            snack(activity.getString(R.string.opencode_files_open_failed))
            return
        }
        if (!force && isVisible && currentFile()?.absolutePath == file.absolutePath) return
        pendingAfterLoad = after
        externalEditPending = false
        lastViewState = null
        activeDraft = null
        deferredRecovery = false
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
        maybeOfferSimpleMode()

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
            val recovery = if (
                !skipRecovery &&
                loaded is TextLoad.Ok &&
                loaded.document.readOnlyReason == null
            ) {
                EditorRecoveryStore.load(activity, file)
            } else {
                null
            }
            activity.runOnUiThread {
                if (released || request != generation) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                loading = false
                language = resolved
                when (loaded) {
                    is TextLoad.Ok -> deliverLoaded(
                        request,
                        loaded.document,
                        root,
                        resolved,
                        recovery,
                    )
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
        clearRecoveryAsync(file)
        captureViewState { state ->
            if (autoCommit) {
                show(file, after = { commitLocally(root!!, file) }, force = true, skipRecovery = true)
            } else {
                show(file, force = true, skipRecovery = true)
            }
            lastViewState = state
        }
    }

    /**
     * Persist an unsaved buffer before the activity stops.
     *
     * The process can be killed while the app is in the background, and Android does not promise
     * another callback before that. The snapshot is written off the main thread; a failure only
     * loses the recovery copy, never the file or the live buffer.
     */
    fun onBackground() {
        if (released || !isVisible || saving) return
        val loaded = document ?: return
        if (!dirty) {
            // "Later" deliberately keeps an older recovery file next to a clean buffer. Do not
            // erase it just because the activity stopped before the user came back to it.
            if (!deferredRecovery) clearRecoveryAsync(loaded.file)
            return
        }
        try {
            renderer.snapshot(SnapshotReason.RECOVERY) { snapshot ->
                if (released) return@snapshot
                // A not-ready WebView returns requestId 0 with the saved baseline. It cannot
                // provide the live buffer yet, so keep an existing recovery file untouched.
                if (snapshot.requestId == 0L) return@snapshot
                // The new snapshot (or the clear below) supersedes any "later" decision.
                deferredRecovery = false
                try {
                    executor.execute {
                        if (released) return@execute
                        if (snapshot.dirty && snapshot.text != loaded.text) {
                            EditorRecoveryStore.save(activity, loaded.file, snapshot)
                        } else {
                            EditorRecoveryStore.clear(activity, loaded.file)
                        }
                    }
                } catch (e: Exception) {
                    // The editor is being released; there is nothing left to recover.
                }
            }
        } catch (e: Exception) {
            // A renderer that is not ready has no buffer to persist.
        }
    }

    private fun close() {
        // Closing a dirty buffer is an explicit discard, and a restored draft that is now back to
        // the saved text is no longer worth recovering. A clean buffer may still have a snapshot
        // the user chose to leave for later, so keep that one.
        if (dirty || activeDraft != null) currentFile()?.let { clearRecoveryAsync(it) }
        captureViewState { _ ->
            generation++
            loading = false
            saving = false
            externalEditPending = false
            document = null
            dirty = false
            activeDraft = null
            deferredRecovery = false
            hideSearch()
            listener.onEditorClosed()
            OverlayMotion.conceal(panel)
        }
    }

    /**
     * Decide whether a disk document can be opened directly or an unsaved recovery copy has to be
     * offered first. Everything here runs on the UI thread; the recovery file was read already.
     */
    private fun deliverLoaded(
        request: Int,
        loaded: TextDocument,
        root: File?,
        resolved: EditorLanguage,
        recovery: EditorRecovery?,
    ) {
        if (recovery == null) {
            applyDocument(loaded, root, resolved)
            return
        }
        if (loaded.readOnlyReason != null ||
            normalizeToDocument(recovery.text, "\n") == normalizeToDocument(loaded.text, "\n")
        ) {
            clearRecoveryAsync(loaded.file)
            applyDocument(loaded, root, resolved)
            return
        }
        val changedOnDisk = recovery.baseHash.isNotEmpty() && recovery.baseHash != loaded.hash
        val whenText = android.text.format.DateUtils.getRelativeTimeSpanString(
            recovery.savedAt,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        var message = activity.getString(
            R.string.opencode_editor_recovery_message,
            loaded.file.name,
            whenText,
        )
        if (changedOnDisk) {
            message += activity.getString(R.string.opencode_editor_recovery_changed)
        }
        val applyRecovery = {
            if (!released && request == generation) {
                deferredRecovery = false
                applyDocument(loaded, root, resolved, recovery)
            }
        }
        val applyDisk = {
            if (!released && request == generation) {
                applyDocument(loaded, root, resolved)
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_editor_recovery_title)
            .setMessage(message)
            .setPositiveButton(R.string.opencode_editor_recovery_restore) { _, _ -> applyRecovery() }
            .setNegativeButton(R.string.opencode_editor_recovery_discard) { _, _ ->
                deferredRecovery = false
                clearRecoveryAsync(loaded.file)
                applyDisk()
            }
            .setNeutralButton(R.string.opencode_editor_recovery_later) { _, _ ->
                // Show the file from disk but keep the snapshot until the user asks for it again,
                // saves, reloads or explicitly discards it.
                deferredRecovery = true
                applyDisk()
            }
            .setOnCancelListener {
                // Dismissing the dialog must not throw the draft away.
                deferredRecovery = true
                applyDisk()
            }
            .show()
    }

    private fun applyDocument(
        loaded: TextDocument,
        root: File?,
        resolved: EditorLanguage,
        recovery: EditorRecovery? = null,
    ) {
        // A draft whose base hash is old must still fail the normal save conflict check instead of
        // silently replacing changes made by another process while the app was dead.
        val effective = if (recovery != null && recovery.baseHash.isNotEmpty()) {
            loaded.copy(hash = recovery.baseHash)
        } else {
            loaded
        }
        document = effective
        repoRoot = root
        language = if (recovery != null && recovery.languageId != "plaintext") {
            EditorLanguageRegistry.byId(recovery.languageId)
        } else {
            resolved
        }
        activeDraft = recovery?.text
        dirty = recovery != null && recovery.text != loaded.text
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
            refreshStatus(effective)
            updateMenu()
            val after = pendingAfterLoad
            pendingAfterLoad = null
            after?.invoke()
            return
        }
        placeholder.visibility = View.GONE
        host.visibility = View.VISIBLE
        readOnlyChip.visibility = if (reason == null) View.GONE else View.VISIBLE
        languageChip.text = language.displayName
        languageChip.visibility = if (language.id == "plaintext") View.GONE else View.VISIBLE

        val target = chooseRenderer(loaded)
        activateRenderer(target)
        val state = recovery?.viewState() ?: lastViewState
        lastViewState = null
        renderer.setSettings(settingsFor(effective))
        renderer.setTheme(EditorThemeBuilder.build(activity))
        renderer.load(
            effective,
            languageForRenderer(effective),
            state,
            readOnly = reason != null,
            draftText = recovery?.text,
        )
        refreshStatus(effective)
        updateMenu()
        if (recovery != null) {
            snack(activity.getString(R.string.opencode_editor_recovery_restored))
        }
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
        target.setSettings(settingsFor(document))
        target.setTheme(EditorThemeBuilder.build(activity))
    }

    private fun fallbackToNative(code: String, message: String) {
        if (renderer === nativeRenderer) return
        webFallbackUsed = true
        val current = document ?: return
        activity.runOnUiThread {
            if (released) return@runOnUiThread
            snack(activity.getString(R.string.opencode_editor_fallback))
            val failed = webRenderer
            renderer = nativeRenderer
            webRenderer = null
            webHost.visibility = View.GONE
            nativeBody.visibility = View.VISIBLE
            nativeRenderer.setSettings(settingsFor(current))
            // Reuse the restored recovery draft instead of silently falling back to the saved
            // bytes on disk. If there is none, the last committed document is the safe source.
            nativeRenderer.load(
                current,
                languageForRenderer(current),
                lastViewState,
                readOnly = current.readOnlyReason != null,
                draftText = activeDraft,
            )
            failed?.dispose()
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
            if (snapshot.requestId == 0L) {
                // A not-ready CodeMirror would return the saved baseline, which would silently
                // replace a restored recovery draft. Let the readiness timeout fall back first.
                saving = false
                updateMenu()
                snack(activity.getString(R.string.opencode_editor_not_ready))
                return@snapshot
            }
            val request = ++generation
            executor.execute {
                val result = TextFileIO.save(document, snapshot.text, force)
                if (result is TextSave.Ok) {
                    // The draft is now the file on disk; a recovery copy would only ask again.
                    EditorRecoveryStore.clear(activity, file)
                }
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
                            activeDraft = null
                            deferredRecovery = false
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
        clearRecoveryAsync(file)
        show(file, after = after, force = true, skipRecovery = true)
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
        menu.findItem(R.id.action_editor_preview)?.isEnabled =
            loaded != null && language.id == "markdown"
        menu.findItem(R.id.action_editor_lint)?.isEnabled =
            loaded != null && EditorSyntaxCheck.supports(language.id)
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
            R.id.action_editor_preview -> showMarkdownPreview()
            R.id.action_editor_lint -> runSyntaxCheck()
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
                renderer.setLanguage(languageForRenderer(document))
                refreshStatus(document)
                updateMenu()
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

    private fun showMarkdownPreview() {
        if (language.id != "markdown") return
        val file = currentFile() ?: return
        withCurrentBuffer { text ->
            MarkdownPreviewDialog.show(
                activity,
                file.name,
                text,
                EditorThemeBuilder.build(activity),
            )
        }
    }

    private fun runSyntaxCheck() {
        val languageId = language.id
        if (!EditorSyntaxCheck.supports(languageId)) return
        withCurrentBuffer { text ->
            // Parsing happens off the UI thread: a 1.5 MB JSON document would otherwise freeze
            // the editor while the dialog is being prepared.
            try {
                executor.execute {
                    val result = EditorSyntaxCheck.check(languageId, text)
                    activity.runOnUiThread {
                        if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        showSyntaxResult(result)
                    }
                }
            } catch (e: Exception) {
                showSyntaxResult(EditorSyntaxCheck.check(languageId, text))
            }
        }
    }

    private fun showSyntaxResult(result: EditorSyntaxCheck.Result) {
        val message = if (result.ok) {
            activity.getString(R.string.opencode_editor_lint_ok)
        } else {
            result.message
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(
                if (result.ok) R.string.opencode_editor_lint_title
                else R.string.opencode_editor_lint_failed_title,
            )
            .setMessage(message)
            .setPositiveButton(R.string.opencode_ok, null)
            .show()
    }

    /**
     * The preview and the syntax check must see the live buffer, not the last saved document.
     * A renderer that is not ready returns `requestId == 0`; in that case the recovery draft or
     * the disk text is the best available copy.
     */
    private fun withCurrentBuffer(after: (String) -> Unit) {
        val loaded = document ?: return
        try {
            renderer.snapshot(SnapshotReason.PREVIEW) { snapshot ->
                if (released) return@snapshot
                val text = if (snapshot.requestId == 0L) {
                    activeDraft ?: loaded.text
                } else {
                    snapshot.text
                }
                after(text)
            }
        } catch (e: Exception) {
            after(activeDraft ?: loaded.text)
        }
    }

    /**
     * Offer the native editor once to a TalkBack user.
     *
     * The suggestion is deliberately not automatic: CodeMirror still works with TalkBack, and
     * forcing the simple renderer would silently remove syntax highlighting and folding. The
     * explicit switch in Settings keeps the decision in the user's hands afterwards.
     */
    private fun maybeOfferSimpleMode() {
        if (a11yPromptShowing) return
        if (OpenCodeSettings.editorSimpleMode(activity)) return
        if (OpenCodeSettings.editorA11yPromptShown(activity)) return
        val manager = activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return
        if (!manager.isTouchExplorationEnabled) return
        a11yPromptShowing = true
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_editor_a11y_title)
            .setMessage(R.string.opencode_editor_a11y_message)
            .setPositiveButton(R.string.opencode_editor_a11y_use_simple) { _, _ ->
                OpenCodeSettings.setEditorSimpleMode(activity, true)
                updateMenu()
            }
            .setNegativeButton(R.string.opencode_editor_a11y_keep, null)
            .setOnDismissListener {
                a11yPromptShowing = false
                OpenCodeSettings.setEditorA11yPromptShown(activity, true)
            }
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
        if (dirty || activeDraft != null) currentFile()?.let { clearRecoveryAsync(it) }
        generation++
        loading = false
        saving = false
        externalEditPending = false
        document = null
        dirty = false
        activeDraft = null
        deferredRecovery = false
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

    /**
     * Settings adjusted for the current document.
     *
     * Highlighting, folding, bracket matching and autocomplete are recomputed by the JS page on
     * every keystroke; above the thresholds in the proposal they make a large file unusable while
     * adding little value. Editing itself stays enabled at full speed by degrading to plain text.
     */
    private fun settingsFor(document: TextDocument?): EditorSettings {
        val base = currentSettings()
        if (document == null) return base
        val large = isLargeDocument(document)
        val huge = document.size >= MAX_ACTIVE_LINE_BYTES
        if (!large && !huge) return base
        return base.copy(
            highlightActiveLine = base.highlightActiveLine && !huge,
            bracketMatching = base.bracketMatching && !large,
            autoCloseBrackets = base.autoCloseBrackets && !large,
            autoIndent = base.autoIndent && !large,
            foldGutter = base.foldGutter && !large,
            indentGuides = base.indentGuides && !large,
            autocomplete = if (large) "off" else base.autocomplete,
        )
    }

    /**
     * Language handed to the renderer. Large files fall back to `plaintext` even though the chip
     * still shows the resolved language, so the user knows what the file is without paying the
     * cost of parsing it.
     */
    private fun languageForRenderer(document: TextDocument?): EditorLanguage =
        if (document != null && isLargeDocument(document)) {
            EditorLanguageRegistry.byId("plaintext")
        } else {
            language
        }

    private fun isLargeDocument(document: TextDocument): Boolean {
        if (document.size > MAX_HIGHLIGHT_BYTES) return true
        var lines = 1
        for (char in document.text) {
            if (char == '\n') {
                lines++
                if (lines > MAX_HIGHLIGHT_LINES) return true
            }
        }
        return false
    }

    private fun applySettingsToRenderers() {
        val settings = settingsFor(document)
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

    private fun clearRecoveryAsync(file: File) {
        try {
            executor.execute { EditorRecoveryStore.clear(activity, file) }
        } catch (e: Exception) {
            // The executor is already shutting down; the snapshot is harmless and expires with
            // the app data.
        }
    }

    private fun snack(message: String) {
        Snackbar.make(panel, message, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        // The JS bridge serializes the full buffer as a JSON string; keep it conservative until
        // a chunked/streaming bridge exists.
        const val MAX_WEB_EDITOR_BYTES = 1_500_000L

        // Above these sizes the editor keeps editing but drops highlight/fold/indent/autocomplete.
        const val MAX_HIGHLIGHT_BYTES = 512L * 1024L
        const val MAX_HIGHLIGHT_LINES = 20_000

        // Active-line highlighting is the last decoration to go for very large buffers.
        const val MAX_ACTIVE_LINE_BYTES = 1_000_000L
    }
}

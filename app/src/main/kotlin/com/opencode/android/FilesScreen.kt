package com.opencode.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A real file browser over the app HOME, the shared storage and the SD cards.
 *
 * The top bar holds a breadcrumb and volume shortcuts, the list shows folders and files with
 * sorting and hidden-file support, and a long press starts multi-selection. Copy and move use a
 * clipboard bar so the user can navigate freely before pasting. All recursive work runs on one
 * background thread; symbolic links are handled by [FileBrowser] and never followed by a delete.
 */
class FilesScreen(
    private val activity: Activity,
    private val listener: Listener,
    private val onRequestStorage: () -> Unit,
) {

    fun interface Listener {
        fun onOpenProject(directory: File)
    }

    private val panel: View = activity.findViewById(R.id.files_panel)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.files_toolbar)
    private val summary: TextView = activity.findViewById(R.id.files_summary)
    private val breadcrumb: LinearLayout = activity.findViewById(R.id.files_breadcrumb)
    private val shortcuts: LinearLayout = activity.findViewById(R.id.files_shortcuts)
    private val clipboardBar: View = activity.findViewById(R.id.files_clipboard)
    private val clipboardText: TextView = activity.findViewById(R.id.files_clipboard_text)
    private val clipboardPaste: MaterialButton = activity.findViewById(R.id.files_clipboard_paste)
    private val clipboardCancel: MaterialButton = activity.findViewById(R.id.files_clipboard_cancel)
    private val recycler: RecyclerView = activity.findViewById(R.id.files_list)
    private val empty: View = activity.findViewById(R.id.files_empty)
    private val emptyText: TextView = activity.findViewById(R.id.files_empty_text)
    private val emptyAction: MaterialButton = activity.findViewById(R.id.files_empty_action)
    private val progress: ProgressBar = activity.findViewById(R.id.files_progress)
    private val adapter =
        Adapter(::onEntryPressed, ::onEntryLongPressed, ::showActions, ::metaText)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-files").apply { isDaemon = true }
    }

    @Volatile
    private var released = false
    private var generation = 0

    private var current: File = initialDirectory()
    private var entries: List<FileEntry> = emptyList()
    private var showHidden = false
    private var sort = FileBrowser.Sort()

    private var selectionMode = false
    private val selected = LinkedHashSet<String>()

    private var clipboard: List<File> = emptyList()
    private var clipboardMove = false
    private var clipboardInFlight = false

    private var storagePrompted = false

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        toolbar.setNavigationOnClickListener { goBack() }
        toolbar.setOnMenuItemClickListener { item -> onMenuItem(item) }
        emptyAction.setOnClickListener { createFolder() }
        clipboardPaste.setOnClickListener { pasteClipboard() }
        clipboardCancel.setOnClickListener { clearClipboard() }
        renderMenu()
        renderShortcuts()
    }

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    fun show() {
        OverlayMotion.reveal(panel)
        storagePrompted = false
        val remembered = OpenCodeSettings.lastFilesDir(activity)?.let(::File)
        val target = when {
            remembered != null && remembered.isDirectory && remembered.canRead() -> remembered
            current.isDirectory && current.canRead() -> current
            else -> initialDirectory()
        }
        navigate(target)
    }

    fun hide() {
        clearSelection()
        OverlayMotion.conceal(panel)
    }

    /** Back button: leave selection, go to the parent folder, then close the screen. */
    fun goBack() {
        if (selectionMode) {
            clearSelection()
            return
        }
        val parent = current.parentFile
        if (parent != null) {
            navigate(parent)
        } else {
            hide()
        }
    }

    /** Called when the activity comes back from the storage permission screen. */
    fun onStoragePermissionResult() {
        storagePrompted = false
        if (isVisible) {
            renderShortcuts()
            load()
        }
    }

    fun release() {
        released = true
        executor.shutdownNow()
    }

    private fun initialDirectory(): File {
        val remembered = OpenCodeSettings.lastFilesDir(activity)?.let(::File)
        if (remembered != null && remembered.isDirectory && remembered.canRead()) return remembered
        val home = File(OpenCodeBootstrap.HOME)
        if (home.isDirectory) return home
        return OpenCodeStorage.primaryRoot()
    }

    /* ------------------------------------------------------------------ navigation */

    private fun navigate(directory: File) {
        val target = directory.absoluteFile
        if (!target.isDirectory) {
            snack(activity.getString(R.string.opencode_files_path_not_dir))
            return
        }
        current = target
        OpenCodeSettings.setLastFilesDir(activity, target.absolutePath)
        clearSelection()
        renderHeader()
        // The prompt is shown by ensureStorageAccess; the list also renders an unreadable
        // placeholder, so cancelling the prompt leaves a usable screen instead of stale rows.
        ensureStorageAccess(target)
        load()
    }

    private fun ensureStorageAccess(directory: File): Boolean {
        if (hasStorageAccess(directory)) return true
        if (!storagePrompted) {
            storagePrompted = true
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.opencode_files_permission_title)
                .setMessage(R.string.opencode_files_permission_message)
                .setPositiveButton(R.string.opencode_files_permission_grant) { _, _ ->
                    onRequestStorage()
                }
                .setNegativeButton(R.string.opencode_files_cancel, null)
                .show()
        }
        return false
    }

    private fun needsStoragePermission(file: File): Boolean {
        // Resolve links: browsing ~/storage/shared must still offer the storage permission even
        // though the link itself lives in the app HOME.
        val path = canonicalPath(file)
        val roots = ArrayList<File>()
        roots.add(OpenCodeStorage.primaryRoot())
        roots.addAll(OpenCodeStorage.volumeRoots(activity))
        for (root in roots) {
            val rootPath = canonicalPath(root)
            if (path == rootPath || path.startsWith(rootPath + File.separator)) return true
        }
        // /storage itself is the parent of every volume, and some devices expose volumes below
        // paths that are not returned by getExternalFilesDirs.
        return path == "/storage" || path.startsWith("/storage/")
    }

    /**
     * Legacy storage access is enough for the primary volume, but an SD card on Android 11+
     * needs "All files access" even when the legacy permission is already granted.
     */
    private fun hasStorageAccess(file: File): Boolean {
        if (!needsStoragePermission(file)) return true
        if (OpenCodeStorage.hasAllFilesAccess()) return true
        if (!OpenCodeStorage.hasLegacyPermission(activity)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val path = canonicalPath(file)
        return OpenCodeStorage.volumeRoots(activity)
            .filterNot(OpenCodeStorage::isPrimaryRoot)
            .none { root ->
                val rootPath = canonicalPath(root)
                path == rootPath || path.startsWith(rootPath + File.separator)
            }
    }

    private fun canonicalPath(file: File): String = try {
        file.canonicalPath
    } catch (e: Exception) {
        file.absolutePath
    }

    private fun renderHeader() {
        if (selectionMode) return
        val home = File(OpenCodeBootstrap.HOME)
        toolbar.title = when {
            current.absolutePath == home.absolutePath ->
                activity.getString(R.string.opencode_picker_home)
            current.parentFile == null -> activity.getString(R.string.opencode_files_root)
            else -> current.name
        }
        toolbar.subtitle = current.absolutePath
        renderBreadcrumb()
        renderShortcuts()
    }

    private fun renderBreadcrumb() {
        breadcrumb.removeAllViews()
        val segments = ArrayList<File>()
        var file: File? = current
        while (file != null) {
            segments.add(file)
            file = file.parentFile
        }
        segments.reverse()

        // Deep paths keep the root, an ellipsis and the last three segments so the row stays short.
        val shown: List<File?> = if (segments.size <= 4) {
            segments
        } else {
            listOf(
                segments.first(),
                null,
                segments[segments.size - 3],
                segments[segments.size - 2],
                segments.last(),
            )
        }

        for ((index, segment) in shown.withIndex()) {
            if (index > 0) breadcrumb.addView(breadcrumbSeparator())
            if (segment == null) {
                breadcrumb.addView(breadcrumbSegment("…"))
                continue
            }
            val entry: File = segment
            val label = breadcrumbLabel(entry)
            val isCurrent = entry.absolutePath == current.absolutePath
            if (isCurrent) {
                breadcrumb.addView(breadcrumbSegment(label, isCurrent = true))
            } else {
                breadcrumb.addView(breadcrumbSegment(label) { navigate(entry) })
            }
        }
    }

    private fun breadcrumbLabel(file: File): String = when {
        file.parentFile == null -> activity.getString(R.string.opencode_files_root)
        file.absolutePath == File(OpenCodeBootstrap.HOME).absolutePath ->
            activity.getString(R.string.opencode_picker_home)
        else -> file.name
    }

    private fun breadcrumbSegment(
        label: String,
        isCurrent: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): TextView = TextView(activity).apply {
        text = label
        maxLines = 1
        setTextAppearance(R.style.TextAppearance_OpenCode_LabelLarge)
        setTextColor(
            when {
                onClick == null -> MaterialColors.getColor(
                    activity,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ContextCompat.getColor(activity, R.color.oc_text_faint),
                )
                isCurrent -> MaterialColors.getColor(
                    activity,
                    com.google.android.material.R.attr.colorOnSurface,
                    ContextCompat.getColor(activity, R.color.oc_text),
                )
                else -> MaterialColors.getColor(
                    activity,
                    androidx.appcompat.R.attr.colorPrimary,
                    ContextCompat.getColor(activity, R.color.oc_primary),
                )
            },
        )
        setPadding(dp(4), dp(8), dp(4), dp(8))
        minHeight = dp(40)
        gravity = Gravity.CENTER_VERTICAL
        if (onClick != null) {
            contentDescription = activity.getString(R.string.opencode_picker_goto, label)
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(activity, android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }
    }

    private fun breadcrumbSeparator(): ImageView = ImageView(activity).apply {
        setImageResource(R.drawable.ic_chevron_right)
        setColorFilter(
            MaterialColors.getColor(
                activity,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(activity, R.color.oc_text_faint),
            ),
        )
        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun renderShortcuts() {
        shortcuts.removeAllViews()
        val roots = LinkedHashMap<String, File>()
        val home = File(OpenCodeBootstrap.HOME)
        if (home.isDirectory) roots[home.absolutePath] = home
        for (root in OpenCodeStorage.volumeRoots(activity)) {
            roots.putIfAbsent(root.absolutePath, root)
        }
        roots.putIfAbsent("/", File("/"))

        for ((path, root) in roots) {
            val label = when {
                path == home.absolutePath -> activity.getString(R.string.opencode_picker_home)
                OpenCodeStorage.isPrimaryRoot(root) -> activity.getString(R.string.opencode_picker_internal)
                root.parentFile == null -> activity.getString(R.string.opencode_files_root)
                else -> activity.getString(R.string.opencode_picker_sd, root.name)
            }
            val chip = MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = label
                setTextAppearance(R.style.TextAppearance_OpenCode_LabelLarge)
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setSingleLine(true)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { navigate(root) }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = dp(8)
            shortcuts.addView(chip, params)
        }
    }

    /* ------------------------------------------------------------------ listing */

    private fun load() {
        if (released) return
        val target = current
        val ticket = ++generation
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        summary.setText(R.string.opencode_files_scanning)
        executor.execute {
            val listed = FileBrowser.entries(target, showHidden, sort)
            val free = OpenCodeStorage.freeBytes(target)
            activity.runOnUiThread {
                if (released || ticket != generation) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                if (listed == null) {
                    entries = emptyList()
                    adapter.submitList(emptyList())
                    emptyText.setText(R.string.opencode_files_unreadable)
                    if (!hasStorageAccess(target)) {
                        emptyAction.visibility = View.VISIBLE
                        emptyAction.setText(R.string.opencode_files_permission_grant)
                        emptyAction.setOnClickListener { onRequestStorage() }
                    } else {
                        emptyAction.visibility = View.GONE
                    }
                    empty.visibility = View.VISIBLE
                    summary.text = activity.getString(R.string.opencode_files_unreadable)
                } else {
                    entries = listed
                    adapter.submitList(listed)
                    emptyText.setText(R.string.opencode_files_empty)
                    emptyAction.visibility = if (listed.isEmpty()) View.VISIBLE else View.GONE
                    emptyAction.setText(R.string.opencode_files_empty_action)
                    emptyAction.setOnClickListener { createFolder() }
                    empty.visibility = if (listed.isEmpty()) View.VISIBLE else View.GONE
                    summary.text = summaryText(listed.size, free)
                    pruneSelection()
                }
            }
        }
    }

    private fun summaryText(count: Int, free: Long): String = if (free >= 0) {
        activity.getString(
            R.string.opencode_files_summary_free,
            count,
            OpenCodeStorage.formatBytes(free),
        )
    } else {
        activity.getString(R.string.opencode_files_summary_count, count)
    }

    /* ------------------------------------------------------------------ menu */

    private fun onMenuItem(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_files_new_folder -> createFolder()
            R.id.action_files_new_file -> createFile()
            R.id.action_files_sort -> showSortDialog()
            R.id.action_files_hidden -> {
                showHidden = !showHidden
                item.isChecked = showHidden
                load()
            }
            R.id.action_files_refresh -> load()
            R.id.action_files_go_to -> showGoToDialog()
            R.id.action_files_open_project -> {
                listener.onOpenProject(current)
                hide()
            }
            R.id.action_selection_copy -> copySelection(move = false)
            R.id.action_selection_move -> copySelection(move = true)
            R.id.action_selection_delete -> confirmDelete(selectedEntries().map { it.file })
            R.id.action_selection_share -> shareSelection()
            R.id.action_selection_select_all -> selectAll()
            else -> return false
        }
        return true
    }

    private fun renderMenu() {
        if (selectionMode) return
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.menu_files)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationContentDescription(R.string.opencode_files_back)
        toolbar.menu.findItem(R.id.action_files_hidden)?.isChecked = showHidden
        toolbar.tintActionIcons()
    }

    /* ------------------------------------------------------------------ selection */

    private fun onEntryPressed(entry: FileEntry) {
        if (selectionMode) {
            toggleSelection(entry)
            return
        }
        if (entry.isDirectory) {
            navigate(entry.file)
        } else {
            openFile(entry.file)
        }
    }

    private fun onEntryLongPressed(entry: FileEntry): Boolean {
        if (!selectionMode) {
            selectionMode = true
            selected.clear()
        }
        selected.add(entry.file.absolutePath)
        applySelectionUi()
        return true
    }

    private fun toggleSelection(entry: FileEntry) {
        if (!selectionMode) {
            selectionMode = true
            selected.clear()
        }
        val path = entry.file.absolutePath
        if (!selected.remove(path)) selected.add(path)
        if (selected.isEmpty()) {
            clearSelection()
        } else {
            applySelectionUi()
        }
    }

    private fun selectedEntries(): List<FileEntry> =
        entries.filter { selected.contains(it.file.absolutePath) }

    private fun selectAll() {
        if (entries.isEmpty()) return
        if (selected.size == entries.size) {
            selected.clear()
            clearSelection()
            return
        }
        selectionMode = true
        selected.clear()
        for (entry in entries) selected.add(entry.file.absolutePath)
        applySelectionUi()
    }

    private fun pruneSelection() {
        if (!selectionMode) return
        val paths = entries.mapTo(HashSet()) { it.file.absolutePath }
        selected.retainAll(paths)
        if (selected.isEmpty()) clearSelection() else applySelectionUi()
    }

    private fun applySelectionUi() {
        adapter.selectionMode = selectionMode
        adapter.selected = selected.toSet()
        adapter.notifyDataSetChanged()
        if (!selectionMode) {
            renderMenu()
            renderHeader()
            return
        }
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.menu_files_selection)
        toolbar.setNavigationIcon(R.drawable.ic_close)
        toolbar.setNavigationContentDescription(R.string.opencode_files_clear_selection)
        toolbar.title = activity.getString(R.string.opencode_files_selection_title, selected.size)
        toolbar.subtitle = activity.getString(R.string.opencode_files_selection_hint)
        toolbar.tintActionIcons()
    }

    private fun clearSelection() {
        val wasSelecting = selectionMode
        selectionMode = false
        selected.clear()
        adapter.selectionMode = false
        adapter.selected = emptySet()
        adapter.notifyDataSetChanged()
        if (wasSelecting) {
            renderMenu()
            renderHeader()
        }
    }

    /* ------------------------------------------------------------------ actions */

    private fun showActions(entry: FileEntry) {
        val sheet = BottomSheetDialog(activity)
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.sheet_file_actions, null, false)
        content.findViewById<TextView>(R.id.sheet_file_title).text = entry.file.name
        content.findViewById<TextView>(R.id.sheet_file_meta).text = metaText(entry)

        val open = content.findViewById<MaterialButton>(R.id.sheet_action_open)
        open.setText(
            if (entry.isDirectory) R.string.opencode_files_open_folder
            else R.string.opencode_files_open_file,
        )
        open.setOnClickListener {
            sheet.dismiss()
            if (entry.isDirectory) navigate(entry.file) else openFile(entry.file)
        }

        val project = content.findViewById<MaterialButton>(R.id.sheet_action_project)
        project.visibility = if (entry.isDirectory) View.VISIBLE else View.GONE
        project.setOnClickListener {
            sheet.dismiss()
            hide()
            listener.onOpenProject(entry.file)
        }

        content.findViewById<MaterialButton>(R.id.sheet_action_rename).setOnClickListener {
            sheet.dismiss()
            rename(entry)
        }
        content.findViewById<MaterialButton>(R.id.sheet_action_copy).setOnClickListener {
            sheet.dismiss()
            setClipboard(listOf(entry.file), move = false)
        }
        content.findViewById<MaterialButton>(R.id.sheet_action_move).setOnClickListener {
            sheet.dismiss()
            setClipboard(listOf(entry.file), move = true)
        }
        content.findViewById<MaterialButton>(R.id.sheet_action_share).setOnClickListener {
            sheet.dismiss()
            shareItem(entry.file)
        }
        content.findViewById<MaterialButton>(R.id.sheet_action_copy_path).setOnClickListener {
            sheet.dismiss()
            copyPath(entry.file)
        }
        content.findViewById<MaterialButton>(R.id.sheet_action_properties).setOnClickListener {
            sheet.dismiss()
            showProperties(entry)
        }
        content.findViewById<MaterialButton>(R.id.sheet_action_delete).setOnClickListener {
            sheet.dismiss()
            confirmDelete(listOf(entry.file))
        }

        sheet.setContentView(content)
        sheet.show()
    }

    private fun createFolder() {
        promptName(
            title = activity.getString(R.string.opencode_files_new_folder_title),
            initial = "",
            original = null,
        ) { name ->
            val target = File(current, name)
            executor.execute {
                val created = FileBrowser.createDirectory(target)
                activity.runOnUiThread {
                    if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    snack(
                        if (created) {
                            activity.getString(R.string.opencode_files_created, name)
                        } else {
                            activity.getString(R.string.opencode_files_create_failed, name)
                        },
                    )
                    if (created) load()
                }
            }
        }
    }

    private fun createFile() {
        promptName(
            title = activity.getString(R.string.opencode_files_new_file_title),
            initial = "",
            original = null,
        ) { name ->
            val target = File(current, name)
            executor.execute {
                val created = FileBrowser.createFile(target)
                activity.runOnUiThread {
                    if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    snack(
                        if (created) {
                            activity.getString(R.string.opencode_files_created, name)
                        } else {
                            activity.getString(R.string.opencode_files_create_failed, name)
                        },
                    )
                    if (created) load()
                }
            }
        }
    }

    private fun rename(entry: FileEntry) {
        promptName(
            title = activity.getString(R.string.opencode_files_rename_title, entry.file.name),
            initial = entry.file.name,
            original = entry.file,
        ) { name ->
            executor.execute {
                val renamed = FileBrowser.rename(entry.file, name)
                activity.runOnUiThread {
                    if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    snack(
                        if (renamed != null) {
                            activity.getString(R.string.opencode_files_renamed, name)
                        } else {
                            activity.getString(R.string.opencode_files_rename_failed, entry.file.name)
                        },
                    )
                    if (renamed != null) load()
                }
            }
        }
    }

    private fun promptName(
        title: String,
        initial: String,
        original: File?,
        onConfirm: (String) -> Unit,
    ) {
        promptText(
            title = title,
            hint = activity.getString(R.string.opencode_files_name_hint),
            initial = initial,
            inputType = InputType.TYPE_CLASS_TEXT,
            validate = { name ->
                when {
                    name.isEmpty() -> activity.getString(R.string.opencode_files_name_required)
                    !FileBrowser.isValidName(name) ->
                        activity.getString(R.string.opencode_files_name_invalid)
                    original != null && name == original.name -> null
                    FileBrowser.exists(File(original?.parentFile ?: current, name)) ->
                        activity.getString(R.string.opencode_files_name_exists, name)
                    else -> null
                }
            },
            onConfirm = onConfirm,
        )
    }

    private fun showGoToDialog() {
        promptText(
            title = activity.getString(R.string.opencode_files_go_to_title),
            hint = activity.getString(R.string.opencode_files_path_hint),
            initial = current.absolutePath,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { raw ->
                val target = expandPath(raw)
                when {
                    !target.exists() -> activity.getString(R.string.opencode_files_path_missing)
                    !target.isDirectory -> activity.getString(R.string.opencode_files_path_not_dir)
                    !target.canRead() -> activity.getString(R.string.opencode_files_path_unreadable)
                    else -> null
                }
            },
            onConfirm = { raw -> navigate(expandPath(raw)) },
        )
    }

    private fun promptText(
        title: String,
        hint: String,
        initial: String,
        inputType: Int,
        validate: (String) -> String?,
        onConfirm: (String) -> Unit,
    ) {
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_text_input, null, false)
        val layout = content.findViewById<TextInputLayout>(R.id.dialog_input_layout)
        val edit = content.findViewById<TextInputEditText>(R.id.dialog_input)
        layout.hint = hint
        edit.inputType = inputType
        edit.setText(initial)
        edit.setSelection(edit.length())

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(R.string.opencode_files_ok, null)
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = edit.text?.toString()?.trim().orEmpty()
                val error = validate(value)
                if (error != null) {
                    layout.error = error
                    return@setOnClickListener
                }
                dialog.dismiss()
                onConfirm(value)
            }
            edit.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun expandPath(raw: String): File = when {
        raw == "~" -> File(OpenCodeBootstrap.HOME)
        raw.startsWith("~/") -> File(OpenCodeBootstrap.HOME, raw.substring(2))
        else -> File(raw).let { if (it.isAbsolute) it else File(current, raw) }
    }

    private fun showSortDialog() {
        val options = listOf(
            FileBrowser.Sort(FileBrowser.SortKey.NAME, true) to R.string.opencode_files_sort_name_asc,
            FileBrowser.Sort(FileBrowser.SortKey.NAME, false) to R.string.opencode_files_sort_name_desc,
            FileBrowser.Sort(FileBrowser.SortKey.SIZE, true) to R.string.opencode_files_sort_size_asc,
            FileBrowser.Sort(FileBrowser.SortKey.SIZE, false) to R.string.opencode_files_sort_size_desc,
            FileBrowser.Sort(FileBrowser.SortKey.MODIFIED, true) to R.string.opencode_files_sort_time_asc,
            FileBrowser.Sort(FileBrowser.SortKey.MODIFIED, false) to R.string.opencode_files_sort_time_desc,
            FileBrowser.Sort(FileBrowser.SortKey.TYPE, true) to R.string.opencode_files_sort_type_asc,
        )
        val labels = options.map { activity.getString(it.second) }.toTypedArray()
        val index = options.indexOfFirst { it.first == sort }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_files_sort)
            .setSingleChoiceItems(labels, index) { dialog, which ->
                sort = options[which].first
                dialog.dismiss()
                clearSelection()
                load()
            }
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .show()
    }

    /* ------------------------------------------------------------------ clipboard */

    private fun setClipboard(items: List<File>, move: Boolean) {
        clipboard = items.distinctBy { it.absolutePath }
        clipboardMove = move
        renderClipboard()
    }

    private fun renderClipboard() {
        if (clipboard.isEmpty()) {
            clipboardBar.visibility = View.GONE
            return
        }
        clipboardBar.visibility = View.VISIBLE
        clipboardText.text = activity.getString(
            if (clipboardMove) R.string.opencode_files_clipboard_move
            else R.string.opencode_files_clipboard_copy,
            clipboard.size,
        )
    }

    private fun clearClipboard() {
        if (clipboardInFlight) return
        clipboard = emptyList()
        clipboardMove = false
        renderClipboard()
    }

    private fun copySelection(move: Boolean) {
        val files = selectedEntries().map { it.file }
        if (files.isEmpty()) return
        setClipboard(files, move)
        clearSelection()
    }

    private fun pasteClipboard() {
        if (clipboardInFlight || clipboard.isEmpty()) return
        val sources = clipboard.filter { FileBrowser.exists(it) }
        if (sources.isEmpty()) {
            clearClipboard()
            snack(activity.getString(R.string.opencode_files_paste_empty))
            return
        }
        // A copy into the same folder is always stored under a free name and a move there is a
        // no-op, so neither one needs the conflict prompt.
        val conflicts = sources.count { source ->
            !FileBrowser.sameParent(source, current) &&
                FileBrowser.exists(File(current, source.name))
        }
        if (conflicts > 0) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.opencode_files_conflict_title)
                .setMessage(activity.getString(R.string.opencode_files_conflict_message, conflicts))
                .setItems(
                    arrayOf(
                        activity.getString(R.string.opencode_files_conflict_replace),
                        activity.getString(R.string.opencode_files_conflict_keep_both),
                        activity.getString(R.string.opencode_files_conflict_skip),
                    ),
                ) { _, which ->
                    val policy = when (which) {
                        0 -> FileBrowser.Conflict.REPLACE
                        1 -> FileBrowser.Conflict.KEEP_BOTH
                        else -> FileBrowser.Conflict.SKIP
                    }
                    executePaste(sources, policy)
                }
                .setNegativeButton(R.string.opencode_files_cancel, null)
                .show()
        } else {
            executePaste(sources, FileBrowser.Conflict.SKIP)
        }
    }

    private fun executePaste(sources: List<File>, conflict: FileBrowser.Conflict) {
        val destination = current
        val moving = clipboardMove
        clipboardInFlight = true
        progress.visibility = View.VISIBLE
        executor.execute {
            var succeeded = 0
            var skipped = 0
            val movedPaths = HashSet<String>()
            for (source in sources) {
                if (source.isDirectory && FileBrowser.isSameOrInside(destination, source)) {
                    skipped++
                    continue
                }
                if (moving && FileBrowser.sameParent(source, destination)) {
                    skipped++
                    continue
                }
                val target = if (!moving && FileBrowser.sameParent(source, destination)) {
                    FileBrowser.uniqueName(destination, source.name, source.isDirectory)
                } else {
                    FileBrowser.destination(source, destination, conflict)
                }
                if (target == null) {
                    skipped++
                    continue
                }
                val replace = conflict == FileBrowser.Conflict.REPLACE
                val ok = if (moving) {
                    FileBrowser.move(source, target, replace)
                } else {
                    FileBrowser.copy(source, target, replace)
                }
                if (ok) {
                    succeeded++
                    if (moving) movedPaths.add(source.absolutePath)
                } else {
                    skipped++
                }
            }
            activity.runOnUiThread {
                if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                clipboardInFlight = false
                progress.visibility = View.GONE
                if (moving) {
                    clipboard = clipboard.filterNot { movedPaths.contains(it.absolutePath) }
                    if (clipboard.isEmpty()) clipboardMove = false
                    renderClipboard()
                }
                snack(
                    when {
                        succeeded == 0 -> activity.getString(R.string.opencode_files_paste_failed)
                        skipped == 0 -> activity.getString(R.string.opencode_files_paste_done, succeeded)
                        else -> activity.getString(
                            R.string.opencode_files_paste_partial,
                            succeeded,
                            skipped,
                        )
                    },
                )
                load()
            }
        }
    }

    /* ------------------------------------------------------------------ delete */

    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty()) return
        val title = if (files.size == 1) {
            activity.getString(R.string.opencode_files_delete_title, files.first().name)
        } else {
            activity.getString(R.string.opencode_files_delete_many_title, files.size)
        }
        val message = when {
            files.size > 1 -> activity.getString(R.string.opencode_files_delete_many_message)
            files.first().isDirectory -> activity.getString(
                R.string.opencode_files_delete_message_dir,
                files.first().name,
            )
            else -> activity.getString(
                R.string.opencode_files_delete_message_file,
                OpenCodeStorage.formatBytes(files.first().length()),
            )
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.opencode_files_delete, null)
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(
                    MaterialColors.getColor(
                        activity,
                        androidx.appcompat.R.attr.colorError,
                        ContextCompat.getColor(activity, R.color.oc_error),
                    ),
                )
                setOnClickListener {
                    dialog.dismiss()
                    deleteFiles(files)
                }
            }
        }
        dialog.show()
    }

    private fun deleteFiles(files: List<File>) {
        progress.visibility = View.VISIBLE
        executor.execute {
            var succeeded = 0
            var failed = 0
            val deletedPaths = HashSet<String>()
            for (file in files) {
                if (FileBrowser.delete(file)) {
                    succeeded++
                    deletedPaths.add(file.absolutePath)
                } else {
                    failed++
                }
            }
            activity.runOnUiThread {
                if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                snack(
                    when {
                        failed == 0 -> activity.getString(
                            R.string.opencode_files_deleted_many,
                            succeeded,
                        )
                        succeeded == 0 -> activity.getString(
                            R.string.opencode_files_delete_failed,
                            files.first().name,
                        )
                        else -> activity.getString(
                            R.string.opencode_files_delete_partial,
                            succeeded,
                            failed,
                        )
                    },
                )
                if (deletedPaths.isNotEmpty()) {
                    clipboard = clipboard.filterNot { deletedPaths.contains(it.absolutePath) }
                    if (clipboard.isEmpty()) clipboardMove = false
                    renderClipboard()
                }
                clearSelection()
                load()
            }
        }
    }

    /* ------------------------------------------------------------------ open and share */

    private fun openFile(file: File) {
        val uri = uriFor(file) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launchChooser(intent, activity.getString(R.string.opencode_files_open_with))
    }

    private fun shareItem(file: File) {
        if (!file.isFile) {
            shareText(file.absolutePath)
            return
        }
        val uri = uriFor(file) ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType(file))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launchChooser(intent, activity.getString(R.string.opencode_files_share_title, file.name))
    }

    private fun shareSelection() {
        val files = selectedEntries().map { it.file }
        if (files.isEmpty()) return
        if (files.size == 1) {
            shareItem(files.first())
            return
        }
        val uris = ArrayList<Uri>()
        for (file in files) {
            if (file.isFile) {
                uriFor(file)?.let(uris::add)
            }
        }
        if (uris.isEmpty()) {
            shareText(files.joinToString("\n") { it.absolutePath })
            return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launchChooser(intent, activity.getString(R.string.opencode_files_share_many))
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        launchChooser(intent, activity.getString(R.string.opencode_files_share_many))
    }

    /** The chooser needs the read grant as well, otherwise some receivers get a denied URI. */
    private fun launchChooser(intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(chooser)
        } catch (e: Exception) {
            snack(activity.getString(R.string.opencode_files_open_failed))
        }
    }

    private fun uriFor(file: File): Uri? = try {
        FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
    } catch (e: Exception) {
        snack(activity.getString(R.string.opencode_files_open_failed))
        null
    }

    private fun mimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun copyPath(file: File) {
        val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("path", file.absolutePath))
        snack(activity.getString(R.string.opencode_files_copied))
    }

    /* ------------------------------------------------------------------ properties */

    private fun showProperties(entry: FileEntry) {
        progress.visibility = View.VISIBLE
        executor.execute {
            val file = entry.file
            val size = FileBrowser.sizeOf(file)
            val permissions = FileBrowser.permissions(file)
            val target = FileBrowser.symlinkTarget(file)
            val type = when {
                target != null -> activity.getString(R.string.opencode_files_type_link)
                entry.isDirectory -> activity.getString(R.string.opencode_files_type_directory)
                else -> mimeType(file)
            }
            val modified = try {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(entry.lastModified))
            } catch (e: Exception) {
                activity.getString(R.string.opencode_files_time_unknown)
            }
            val message = buildString {
                append(activity.getString(R.string.opencode_files_property_path))
                append(": ")
                append(file.absolutePath)
                append("\n\n")
                append(activity.getString(R.string.opencode_files_property_type))
                append(": ")
                append(type)
                append("\n\n")
                append(activity.getString(R.string.opencode_files_property_size))
                append(": ")
                append(OpenCodeStorage.formatBytes(size))
                append("\n\n")
                append(activity.getString(R.string.opencode_files_property_modified))
                append(": ")
                append(modified)
                if (permissions != null) {
                    append("\n\n")
                    append(activity.getString(R.string.opencode_files_property_permissions))
                    append(": ")
                    append(permissions)
                }
                if (target != null) {
                    append("\n\n")
                    append(activity.getString(R.string.opencode_files_property_target))
                    append(": ")
                    append(target)
                }
            }
            activity.runOnUiThread {
                if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                MaterialAlertDialogBuilder(activity)
                    .setTitle(
                        activity.getString(
                            R.string.opencode_files_properties_title,
                            file.name,
                        ),
                    )
                    .setMessage(message)
                    .setPositiveButton(R.string.opencode_files_close, null)
                    .show()
            }
        }
    }

    /* ------------------------------------------------------------------ rows */

    private fun metaText(entry: FileEntry): String {
        val kind = when (entry.kind) {
            FileEntry.Kind.REPO -> activity.getString(R.string.opencode_files_kind_repo)
            FileEntry.Kind.DIRECTORY -> activity.getString(R.string.opencode_files_kind_dir)
            FileEntry.Kind.FILE -> activity.getString(R.string.opencode_files_kind_file)
        }
        val label = if (entry.isSymlink) {
            activity.getString(R.string.opencode_files_kind_symlink, kind)
        } else {
            kind
        }
        val age = ageText(entry.lastModified)
        return if (entry.isDirectory) {
            activity.getString(R.string.opencode_files_meta_dir, label, age)
        } else {
            activity.getString(
                R.string.opencode_files_meta,
                label,
                OpenCodeStorage.formatBytes(entry.size),
                age,
            )
        }
    }

    private fun ageText(modified: Long): String {
        if (modified <= 0L) return activity.getString(R.string.opencode_files_time_unknown)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(
            (System.currentTimeMillis() - modified).coerceAtLeast(0L),
        )
        return when {
            minutes < 1 -> activity.getString(R.string.opencode_files_time_now)
            minutes < 60 -> activity.getString(R.string.opencode_files_time_minutes, minutes)
            minutes < 1440 -> activity.getString(R.string.opencode_files_time_hours, minutes / 60)
            else -> activity.getString(R.string.opencode_files_time_days, minutes / 1440)
        }
    }

    private fun snack(message: String) {
        Snackbar.make(panel, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private class Adapter(
        private val onOpen: (FileEntry) -> Unit,
        private val onLongPress: (FileEntry) -> Boolean,
        private val onMore: (FileEntry) -> Unit,
        private val describe: (FileEntry) -> String,
    ) : ListAdapter<FileEntry, Adapter.Holder>(Diff) {

        var selectionMode: Boolean = false
        var selected: Set<String> = emptySet()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = getItem(position)
            val context = holder.itemView.context
            val path = entry.file.absolutePath
            holder.icon.setImageResource(
                when (entry.kind) {
                    FileEntry.Kind.REPO -> R.drawable.ic_git_repo
                    FileEntry.Kind.DIRECTORY -> R.drawable.ic_folder
                    FileEntry.Kind.FILE -> R.drawable.ic_file
                },
            )
            holder.name.text = entry.file.name
            holder.meta.text = describe(entry)
            holder.more.contentDescription = context.getString(
                R.string.opencode_files_options_for,
                entry.file.name,
            )
            holder.more.visibility = if (selectionMode) View.GONE else View.VISIBLE
            holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            if (selectionMode) {
                val checked = selected.contains(path)
                holder.check.setImageResource(
                    if (checked) R.drawable.ic_check_circle else R.drawable.ic_radio_unchecked,
                )
                holder.check.contentDescription = context.getString(
                    if (checked) R.string.opencode_files_selected
                    else R.string.opencode_files_not_selected,
                    entry.file.name,
                )
            }
            holder.itemView.setOnClickListener { onOpen(entry) }
            holder.itemView.setOnLongClickListener { onLongPress(entry) }
            holder.more.setOnClickListener { onMore(entry) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.file_icon)
            val name: TextView = view.findViewById(R.id.file_name)
            val meta: TextView = view.findViewById(R.id.file_meta)
            val check: ImageView = view.findViewById(R.id.file_check)
            val more: ImageButton = view.findViewById(R.id.file_more)
        }

        private object Diff : DiffUtil.ItemCallback<FileEntry>() {
            override fun areItemsTheSame(oldItem: FileEntry, newItem: FileEntry): Boolean =
                oldItem.file.absolutePath == newItem.file.absolutePath

            override fun areContentsTheSame(oldItem: FileEntry, newItem: FileEntry): Boolean =
                oldItem == newItem
        }
    }
}

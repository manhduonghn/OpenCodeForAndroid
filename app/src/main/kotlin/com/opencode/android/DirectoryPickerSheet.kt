package com.opencode.android

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A folder browser in a bottom sheet for choosing a project directory.
 *
 * The breadcrumb jumps to any parent, the shortcut chips jump to the app HOME, the internal
 * storage and each SD card, and the list only shows directories this app can actually read. `~`
 * in the typed path is resolved against the app HOME, so `~/storage/shared/project` works like it
 * does in the terminal.
 */
class DirectoryPickerSheet(
    private val activity: Activity,
    private val listener: Listener,
) {

    fun interface Listener {
        fun onDirectorySelected(directory: File)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-directory-picker").apply { isDaemon = true }
    }

    @Volatile
    private var released = false
    private var generation = 0
    private var current: File = initialDirectory()
    private var dialog: BottomSheetDialog? = null

    private lateinit var pathLayout: TextInputLayout
    private lateinit var path: TextInputEditText
    private lateinit var breadcrumb: LinearLayout
    private lateinit var shortcuts: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar

    private val adapter = FolderAdapter(::load)

    fun show(start: File? = null) {
        if (start != null && start.isDirectory && start.canRead()) current = start

        val content = LayoutInflater.from(activity)
            .inflate(R.layout.sheet_directory_picker, null, false)
        pathLayout = content.findViewById(R.id.picker_path_layout)
        path = content.findViewById(R.id.picker_path)
        breadcrumb = content.findViewById(R.id.picker_breadcrumb)
        shortcuts = content.findViewById(R.id.picker_shortcuts)
        list = content.findViewById(R.id.picker_list)
        empty = content.findViewById(R.id.picker_empty)
        progress = content.findViewById(R.id.picker_progress)

        path.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter

        content.findViewById<MaterialButton>(R.id.picker_go).setOnClickListener {
            openTypedPath()
        }
        content.findViewById<MaterialButton>(R.id.picker_cancel).setOnClickListener {
            dialog?.dismiss()
        }
        content.findViewById<MaterialButton>(R.id.picker_choose).setOnClickListener {
            listener.onDirectorySelected(current)
            dialog?.dismiss()
        }
        path.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                openTypedPath()
                true
            } else {
                false
            }
        }

        renderShortcuts()

        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(content)
        sheet.setOnDismissListener {
            released = true
            dialog = null
            executor.shutdownNow()
        }
        dialog = sheet
        sheet.show()
        load(current)
    }

    private fun initialDirectory(): File = when {
        File(OpenCodeBootstrap.HOME).isDirectory -> File(OpenCodeBootstrap.HOME)
        OpenCodeStorage.primaryRoot().isDirectory -> OpenCodeStorage.primaryRoot()
        else -> File("/")
    }

    private fun renderShortcuts() {
        shortcuts.removeAllViews()
        val roots = ArrayList<File>()
        val home = File(OpenCodeBootstrap.HOME)
        if (home.isDirectory) roots.add(home)
        for (root in OpenCodeStorage.volumeRoots(activity)) {
            if (roots.none { it.absolutePath == root.absolutePath }) roots.add(root)
        }
        for (root in roots) {
            val chip = MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = shortcutLabel(root)
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setSingleLine(true)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { load(root) }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = dp(8)
            shortcuts.addView(chip, params)
        }
    }

    private fun shortcutLabel(root: File): String = when {
        root.absolutePath == OpenCodeBootstrap.HOME ->
            activity.getString(R.string.opencode_picker_home)
        OpenCodeStorage.isPrimaryRoot(root) ->
            activity.getString(R.string.opencode_picker_internal)
        else -> activity.getString(R.string.opencode_picker_sd, root.name)
    }

    /** List [directory]'s readable child directories and refresh the breadcrumb. */
    private fun load(directory: File) {
        if (released) return
        val target = try {
            directory.canonicalFile
        } catch (e: Exception) {
            directory
        }
        if (!target.isDirectory) {
            pathLayout.error = activity.getString(R.string.opencode_picker_not_directory)
            return
        }
        if (!target.canRead()) {
            pathLayout.error = activity.getString(R.string.opencode_picker_unreadable)
            return
        }
        pathLayout.error = null
        current = target
        path.setText(target.absolutePath)
        path.setSelection(path.length())
        renderBreadcrumb(target)

        val ticket = ++generation
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        adapter.submitList(emptyList())
        executor.execute {
            val entries = try {
                target.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase(Locale.US) }
                    ?: emptyList()
            } catch (e: SecurityException) {
                null
            }
            activity.runOnUiThread {
                if (released || ticket != generation) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                if (entries == null) {
                    pathLayout.error = activity.getString(R.string.opencode_picker_unreadable)
                    empty.visibility = View.GONE
                } else {
                    adapter.submitList(entries)
                    empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /**
     * Draw the path from the filesystem root to [directory]. Very deep paths keep the root and
     * the last segments, with an ellipsis for the skipped middle, so the row stays short.
     */
    private fun renderBreadcrumb(directory: File) {
        breadcrumb.removeAllViews()
        val segments = ArrayList<File>()
        var file: File? = directory
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
            if (index > 0) breadcrumb.addView(separator())
            if (segment == null) {
                breadcrumb.addView(segmentView("…"))
                continue
            }
            val entry: File = segment
            val label = when {
                entry.parentFile == null -> activity.getString(R.string.opencode_picker_root)
                entry.absolutePath == OpenCodeBootstrap.HOME -> activity.getString(R.string.opencode_picker_home)
                else -> entry.name
            }
            val isCurrent = entry.absolutePath == directory.absolutePath
            if (isCurrent) {
                breadcrumb.addView(segmentView(label, isCurrent = true))
            } else {
                breadcrumb.addView(segmentView(label) { load(entry) })
            }
        }
    }

    private fun segmentView(
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
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        if (onClick != null) {
            contentDescription = activity.getString(R.string.opencode_picker_goto, label)
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(activity, android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }
    }

    private fun separator(): ImageView = ImageView(activity).apply {
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

    private fun openTypedPath() {
        val raw = path.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) return
        load(expandPath(raw))
    }

    private fun expandPath(raw: String): File = when {
        raw == "~" -> File(OpenCodeBootstrap.HOME)
        raw.startsWith("~/") -> File(OpenCodeBootstrap.HOME, raw.substring(2))
        else -> File(raw)
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private class FolderAdapter(
        private val onOpen: (File) -> Unit,
    ) : ListAdapter<File, FolderAdapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_folder, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val folder = getItem(position)
            holder.name.text = folder.name
            holder.itemView.setOnClickListener { onOpen(folder) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.folder_name)
        }

        private object Diff : DiffUtil.ItemCallback<File>() {
            override fun areItemsTheSame(oldItem: File, newItem: File): Boolean =
                oldItem.absolutePath == newItem.absolutePath

            override fun areContentsTheSame(oldItem: File, newItem: File): Boolean =
                oldItem.absolutePath == newItem.absolutePath
        }
    }
}

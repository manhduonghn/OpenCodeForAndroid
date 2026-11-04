package com.opencode.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
import java.io.File
import java.util.concurrent.Executors

/**
 * The Files screen: top-level entries of the app HOME, shown full screen over the retained
 * WebView. The list itself is a [RecyclerView] with a [ListAdapter], the actions live in a
 * bottom sheet, and destructive actions ask for confirmation with an error-colored button.
 */
class FilesScreen(
    private val activity: Activity,
    private val listener: Listener,
    private val onPickDirectory: () -> Unit,
) {

    fun interface Listener {
        fun onOpenProject(directory: File)
    }

    private data class Entry(
        val file: File,
        val kind: Kind,
        val size: Long,
    ) {
        enum class Kind { DIRECTORY, REPO, FILE }
    }

    private val panel: View = activity.findViewById(R.id.files_panel)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.files_toolbar)
    private val summary: TextView = activity.findViewById(R.id.files_summary)
    private val recycler: RecyclerView = activity.findViewById(R.id.files_list)
    private val empty: View = activity.findViewById(R.id.files_empty)
    private val emptyPick: MaterialButton = activity.findViewById(R.id.files_empty_pick)
    private val progress: ProgressBar = activity.findViewById(R.id.files_progress)
    private val adapter = Adapter(::showActions)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-files").apply { isDaemon = true }
    }

    @Volatile
    private var released = false
    private var generation = 0

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        toolbar.inflateMenu(R.menu.menu_files)
        toolbar.setNavigationOnClickListener { hide() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_files_refresh) {
                scan()
                true
            } else {
                false
            }
        }
        emptyPick.setOnClickListener {
            hide()
            onPickDirectory()
        }
    }

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    fun show() {
        panel.visibility = View.VISIBLE
        scan()
    }

    fun hide() {
        panel.visibility = View.GONE
    }

    fun release() {
        released = true
        executor.shutdownNow()
    }

    /** Recompute the entries and their sizes off the main thread, then submit a fresh list. */
    fun scan() {
        if (released) return
        val ticket = ++generation
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        summary.setText(R.string.opencode_files_scanning)
        executor.execute {
            val found = try {
                OpenCodeStorage.homeEntries()
            } catch (e: Exception) {
                emptyList()
            }
            var total = 0L
            val built = ArrayList<Entry>(found.size)
            for (file in found) {
                val size = try {
                    if (file.isDirectory) OpenCodeStorage.sizeOf(file) else file.length()
                } catch (e: Exception) {
                    0L
                }
                total += size
                val kind = when {
                    file.isDirectory && File(file, ".git").isDirectory -> Entry.Kind.REPO
                    file.isDirectory -> Entry.Kind.DIRECTORY
                    else -> Entry.Kind.FILE
                }
                built.add(Entry(file, kind, size))
            }
            val free = try {
                OpenCodeStorage.freeBytes(File(OpenCodeBootstrap.HOME))
            } catch (e: Exception) {
                0L
            }
            val summaryText = if (free > 0) {
                activity.getString(
                    R.string.opencode_files_summary,
                    built.size,
                    OpenCodeStorage.formatBytes(total),
                    OpenCodeStorage.formatBytes(free),
                )
            } else {
                activity.getString(
                    R.string.opencode_files_summary_no_free,
                    built.size,
                    OpenCodeStorage.formatBytes(total),
                )
            }
            activity.runOnUiThread {
                if (released || ticket != generation) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                adapter.submitList(built)
                progress.visibility = View.GONE
                empty.visibility = if (built.isEmpty()) View.VISIBLE else View.GONE
                summary.text = summaryText
            }
        }
    }

    private fun showActions(entry: Entry) {
        val sheet = BottomSheetDialog(activity)
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.sheet_file_actions, null, false)
        content.findViewById<TextView>(R.id.sheet_file_title).text = entry.file.name

        val open = content.findViewById<MaterialButton>(R.id.sheet_action_open)
        open.visibility = if (entry.file.isDirectory) View.VISIBLE else View.GONE
        open.setOnClickListener {
            sheet.dismiss()
            hide()
            listener.onOpenProject(entry.file)
        }

        content.findViewById<MaterialButton>(R.id.sheet_action_copy).setOnClickListener {
            sheet.dismiss()
            copyPath(entry.file)
        }

        content.findViewById<MaterialButton>(R.id.sheet_action_delete).setOnClickListener {
            sheet.dismiss()
            confirmDelete(entry)
        }

        sheet.setContentView(content)
        sheet.show()
    }

    private fun copyPath(file: File) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("path", file.absolutePath))
        snack(activity.getString(R.string.opencode_files_copied))
    }

    private fun confirmDelete(entry: Entry) {
        val message = if (entry.file.isDirectory) {
            activity.getString(
                R.string.opencode_files_delete_message_dir,
                OpenCodeStorage.formatBytes(entry.size),
            )
        } else {
            activity.getString(
                R.string.opencode_files_delete_message_file,
                OpenCodeStorage.formatBytes(entry.size),
            )
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.opencode_files_delete_title, entry.file.name))
            .setMessage(message)
            .setPositiveButton(R.string.opencode_files_delete) { _, _ -> delete(entry.file) }
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                MaterialColors.getColor(
                    activity,
                    androidx.appcompat.R.attr.colorError,
                    ContextCompat.getColor(activity, R.color.oc_error),
                ),
            )
        }
        dialog.show()
    }

    private fun delete(file: File) {
        executor.execute {
            val deleted = OpenCodeStorage.deleteSafely(file)
            activity.runOnUiThread {
                if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                snack(
                    activity.getString(
                        if (deleted) R.string.opencode_files_deleted
                        else R.string.opencode_files_delete_failed,
                        file.name,
                    ),
                )
                if (deleted) scan()
            }
        }
    }

    private fun snack(message: String) {
        Snackbar.make(panel, message, Snackbar.LENGTH_SHORT).show()
    }

    private class Adapter(
        private val onAction: (Entry) -> Unit,
    ) : ListAdapter<Entry, Adapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = getItem(position)
            val context = holder.itemView.context
            val kindLabel = context.getString(
                when (entry.kind) {
                    Entry.Kind.REPO -> R.string.opencode_files_kind_repo
                    Entry.Kind.DIRECTORY -> R.string.opencode_files_kind_dir
                    Entry.Kind.FILE -> R.string.opencode_files_kind_file
                },
            )
            val sizeLabel = if (entry.size > 0) {
                OpenCodeStorage.formatBytes(entry.size)
            } else {
                context.getString(R.string.opencode_files_size_unknown)
            }
            val age = OpenCodeApi.relativeAge(entry.file.lastModified())
                .ifEmpty { context.getString(R.string.opencode_files_time_unknown) }

            holder.icon.setImageResource(
                when (entry.kind) {
                    Entry.Kind.REPO -> R.drawable.ic_git_repo
                    Entry.Kind.DIRECTORY -> R.drawable.ic_folder
                    Entry.Kind.FILE -> R.drawable.ic_file
                },
            )
            holder.name.text = entry.file.name
            holder.meta.text = context.getString(
                R.string.opencode_files_meta,
                kindLabel,
                sizeLabel,
                age,
            )
            holder.more.contentDescription = context.getString(
                R.string.opencode_files_options_for,
                entry.file.name,
            )
            holder.itemView.setOnClickListener { onAction(entry) }
            holder.more.setOnClickListener { onAction(entry) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.file_icon)
            val name: TextView = view.findViewById(R.id.file_name)
            val meta: TextView = view.findViewById(R.id.file_meta)
            val more: ImageButton = view.findViewById(R.id.file_more)
        }

        private object Diff : DiffUtil.ItemCallback<Entry>() {
            override fun areItemsTheSame(oldItem: Entry, newItem: Entry): Boolean =
                oldItem.file.absolutePath == newItem.file.absolutePath

            override fun areContentsTheSame(oldItem: Entry, newItem: Entry): Boolean =
                oldItem == newItem
        }
    }
}

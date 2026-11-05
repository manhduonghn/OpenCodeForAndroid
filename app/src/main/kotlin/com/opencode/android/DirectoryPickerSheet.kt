package com.opencode.android

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.concurrent.Executors

/**
 * Folder chooser shared by clone destinations and local repository mapping.
 *
 * It browses only directories, keeps the last location as the starting point and reports the
 * selected folder through [onPick]. The caller owns the follow-up dialog (for example the clone
 * name prompt), so the sheet never starts work on its own.
 */
class DirectoryPickerSheet(
    private val activity: Activity,
    start: File?,
    title: String? = null,
    private val onPick: (File) -> Unit,
) {

    private val dialog = BottomSheetDialog(activity)
    private val content: View =
        LayoutInflater.from(activity).inflate(R.layout.sheet_directory_picker, null, false)
    private val titleView: TextView = content.findViewById(R.id.picker_title)
    private val pathView: TextView = content.findViewById(R.id.picker_path)
    private val freeView: TextView = content.findViewById(R.id.picker_free)
    private val emptyView: TextView = content.findViewById(R.id.picker_empty)
    private val errorView: TextView = content.findViewById(R.id.picker_error)
    private val list: RecyclerView = content.findViewById(R.id.picker_list)
    private val up: MaterialButton = content.findViewById(R.id.picker_up)
    private val cancel: MaterialButton = content.findViewById(R.id.picker_cancel)
    private val choose: MaterialButton = content.findViewById(R.id.picker_choose)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-directory-picker").apply { isDaemon = true }
    }

    private val adapter = Adapter(::open)

    private var current: File = initialDirectory(start)
    private var ticket = 0

    @Volatile
    private var released = false

    init {
        if (!title.isNullOrBlank()) titleView.text = title
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        up.setOnClickListener { goUp() }
        cancel.setOnClickListener { dismiss() }
        choose.setOnClickListener {
            if (!current.isDirectory || !current.canRead()) return@setOnClickListener
            val picked = current
            dismiss()
            onPick(picked)
        }
        dialog.setContentView(content)
        dialog.setOnDismissListener {
            released = true
            executor.shutdownNow()
        }
        render()
    }

    fun show() {
        if (dialog.isShowing) return
        dialog.show()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun initialDirectory(start: File?): File {
        val remembered = start ?: OpenCodeSettings.lastCloneDir(activity)?.let(::File)
        val candidate = when {
            remembered == null -> null
            remembered.isDirectory -> remembered
            remembered.parentFile?.isDirectory == true -> remembered.parentFile
            else -> null
        }
        if (candidate != null && candidate.canRead()) return candidate
        val home = File(OpenCodeBootstrap.HOME)
        if (home.isDirectory && home.canRead()) return home
        return OpenCodeStorage.primaryRoot()
    }

    private fun open(directory: File) {
        if (!directory.isDirectory) return
        current = directory
        render()
    }

    private fun goUp() {
        val parent = current.parentFile ?: return
        current = parent
        render()
    }

    private fun render() {
        pathView.text = current.absolutePath
        choose.isEnabled = current.isDirectory && current.canRead()
        up.isEnabled = current.parentFile != null
        val free = OpenCodeStorage.freeBytes(current)
        if (free >= 0) {
            freeView.visibility = View.VISIBLE
            freeView.text = activity.getString(
                R.string.opencode_directory_picker_free,
                OpenCodeStorage.formatBytes(free),
            )
        } else {
            freeView.visibility = View.GONE
        }
        load()
    }

    private fun load() {
        val target = current
        val request = ++ticket
        errorView.visibility = View.GONE
        emptyView.visibility = View.GONE
        executor.execute {
            val listed = FileBrowser.entries(target, showHidden = false, sort = FileBrowser.Sort())
                ?.filter { it.isDirectory }
                .orEmpty()
            activity.runOnUiThread {
                if (released || request != ticket) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (listed.isEmpty()) {
                    adapter.submit(emptyList())
                    emptyView.visibility = View.VISIBLE
                } else {
                    adapter.submit(listed)
                    emptyView.visibility = View.GONE
                }
                if (!target.canRead()) errorView.visibility = View.VISIBLE
            }
        }
    }

    private class Adapter(
        private val onOpen: (File) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        private var entries: List<FileEntry> = emptyList()

        fun submit(next: List<FileEntry>) {
            entries = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_picker_entry, parent, false)
            return Holder(view, onOpen)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(entries[position])
        }

        class Holder(
            view: View,
            private val onOpen: (File) -> Unit,
        ) : RecyclerView.ViewHolder(view) {

            private val name: TextView = view.findViewById(R.id.picker_name)

            fun bind(entry: FileEntry) {
                name.text = entry.file.name
                itemView.setOnClickListener { onOpen(entry.file) }
            }
        }
    }
}

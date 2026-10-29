package com.opencode.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Lists the downloaded files and cloned repositories in the app HOME and lets the user open a
 * directory as a project, copy its path or delete it.
 */
object FileManagerDialog {

    fun interface Listener {
        fun onOpenProject(directory: File)
    }

    fun show(activity: Activity, listener: Listener) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val entries = ArrayList<File>()
        val labels = ArrayList<String>()
        val sizes = HashMap<File, Long>()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val summary = TextView(activity).apply {
            textSize = 12f
            setText(R.string.opencode_files_scanning)
            setTextColor(Color.parseColor("#FFA5A5B4"))
        }
        val refresh = Button(activity).apply {
            text = activity.getString(R.string.opencode_files_refresh)
            textSize = 11f
            minWidth = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        header.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(refresh)

        val progress = ProgressBar(activity).apply {
            visibility = View.VISIBLE
        }
        val list = ListView(activity).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
        }
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, labels)
        list.adapter = adapter

        content.addView(header)
        content.addView(progress)
        content.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.opencode_files_dialog_title)
            .setView(content)
            .setNegativeButton(R.string.opencode_files_cancel, null)
            .create()
        dialog.show()

        fun render() {
            labels.clear()
            for (entry in entries) {
                val kind = when {
                    entry.isDirectory && File(entry, ".git").isDirectory ->
                        activity.getString(R.string.opencode_files_kind_repo)
                    entry.isDirectory -> activity.getString(R.string.opencode_files_kind_dir)
                    else -> activity.getString(R.string.opencode_files_kind_file)
                }
                val size = sizes[entry] ?: if (entry.isDirectory) 0L else entry.length()
                val sizeText = if (size > 0) OpenCodeStorage.formatBytes(size)
                else activity.getString(R.string.opencode_files_size_unknown)
                val age = OpenCodeApi.relativeAge(entry.lastModified())
                    .ifEmpty { activity.getString(R.string.opencode_files_time_unknown) }
                labels.add(
                    "${entry.name}\n$kind · $sizeText · $age",
                )
            }
            if (labels.isEmpty()) labels.add(activity.getString(R.string.opencode_files_empty))
            adapter.notifyDataSetChanged()
        }

        fun scan() {
            progress.visibility = View.VISIBLE
            list.visibility = View.INVISIBLE
            summary.setText(R.string.opencode_files_scanning)
            Thread({
                val found = try {
                    OpenCodeStorage.homeEntries()
                } catch (e: Exception) {
                    emptyList()
                }
                var total = 0L
                val foundSizes = HashMap<File, Long>()
                for (entry in found) {
                    val size = if (entry.isDirectory) OpenCodeStorage.sizeOf(entry) else entry.length()
                    foundSizes[entry] = size
                    total += size
                }
                val free = OpenCodeStorage.freeBytes(File(OpenCodeBootstrap.HOME))
                val summaryText = if (free > 0) {
                    activity.getString(
                        R.string.opencode_files_summary,
                        found.size,
                        OpenCodeStorage.formatBytes(total),
                        OpenCodeStorage.formatBytes(free),
                    )
                } else {
                    activity.getString(
                        R.string.opencode_files_summary_no_free,
                        found.size,
                        OpenCodeStorage.formatBytes(total),
                    )
                }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    entries.clear()
                    entries.addAll(found)
                    sizes.clear()
                    sizes.putAll(foundSizes)
                    summary.text = summaryText
                    progress.visibility = View.GONE
                    list.visibility = View.VISIBLE
                    render()
                }
            }, "opencode-files-scan").start()
        }

        fun copyPath(file: File) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("path", file.absolutePath))
            Toast.makeText(activity, R.string.opencode_files_copied, Toast.LENGTH_SHORT).show()
        }

        fun confirmDelete(file: File) {
            val message = if (file.isDirectory) {
                activity.getString(
                    R.string.opencode_files_delete_message_dir,
                    OpenCodeStorage.formatBytes(sizes[file] ?: 0L),
                )
            } else {
                activity.getString(
                    R.string.opencode_files_delete_message_file,
                    OpenCodeStorage.formatBytes(file.length()),
                )
            }
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.opencode_files_delete_title, file.name))
                .setMessage(message)
                .setPositiveButton(R.string.opencode_files_delete) { _, _ ->
                    Thread({
                        val deleted = OpenCodeStorage.deleteSafely(file)
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            if (deleted) {
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.opencode_files_deleted, file.name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.opencode_files_delete_failed, file.name),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            scan()
                        }
                    }, "opencode-files-delete").start()
                }
                .setNegativeButton(R.string.opencode_files_cancel, null)
                .show()
        }

        fun showActions(file: File) {
            val actions = ArrayList<String>()
            val ids = ArrayList<Int>()
            if (file.isDirectory) {
                actions.add(activity.getString(R.string.opencode_files_open))
                ids.add(ACTION_OPEN)
            }
            actions.add(activity.getString(R.string.opencode_files_copy_path))
            ids.add(ACTION_COPY)
            actions.add(activity.getString(R.string.opencode_files_delete))
            ids.add(ACTION_DELETE)
            AlertDialog.Builder(activity)
                .setTitle(file.name)
                .setItems(actions.toTypedArray()) { _, which ->
                    when (ids[which]) {
                        ACTION_OPEN -> {
                            dialog.dismiss()
                            listener.onOpenProject(file)
                        }
                        ACTION_COPY -> copyPath(file)
                        ACTION_DELETE -> confirmDelete(file)
                    }
                }
                .setNegativeButton(R.string.opencode_files_cancel, null)
                .show()
        }

        list.setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { showActions(it) }
        }
        refresh.setOnClickListener { scan() }
        scan()
    }

    private const val ACTION_OPEN = 1
    private const val ACTION_COPY = 2
    private const val ACTION_DELETE = 3
}

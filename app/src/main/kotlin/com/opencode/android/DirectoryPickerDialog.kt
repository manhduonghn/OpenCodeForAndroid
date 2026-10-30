package com.opencode.android

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * A small folder browser for choosing a project directory, without typing its path.
 *
 * Only directories that this app can actually list are shown. `~` in the typed path is resolved
 * against the app HOME, so `~/storage/shared/project` works like it does in the terminal.
 */
object DirectoryPickerDialog {

    fun interface Listener {
        fun onDirectorySelected(directory: File)
    }

    fun show(activity: Activity, start: File?, listener: Listener) {
        val initial = when {
            start != null && start.isDirectory && start.canRead() -> start
            File(OpenCodeBootstrap.HOME).isDirectory -> File(OpenCodeBootstrap.HOME)
            else -> OpenCodeStorage.primaryRoot()
        }

        var current = initial
        val children = ArrayList<File>()
        val labels = ArrayList<String>()

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(4))
        }

        val pathRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val path = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 13f
            setText(initial.absolutePath)
            setSelection(text.length)
        }
        val go = MaterialButton(activity).apply {
            text = activity.getString(R.string.opencode_picker_go)
            textSize = 12f
            minWidth = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        pathRow.addView(
            path,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        pathRow.addView(go)

        val up = MaterialButton(activity).apply {
            text = activity.getString(R.string.opencode_picker_up)
            textSize = 12f
            minWidth = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        val list = ListView(activity).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
        }
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, labels)
        list.adapter = adapter

        content.addView(pathRow)
        content.addView(up)
        content.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        fun load(directory: File) {
            if (!directory.isDirectory) {
                Toast.makeText(activity, R.string.opencode_picker_not_directory, Toast.LENGTH_SHORT).show()
                return
            }
            if (!directory.canRead()) {
                Toast.makeText(activity, R.string.opencode_picker_unreadable, Toast.LENGTH_SHORT).show()
                return
            }
            current = directory
            path.setText(directory.absolutePath)
            path.setSelection(path.text.length)
            children.clear()
            labels.clear()
            val entries = try {
                directory.listFiles()
            } catch (e: SecurityException) {
                null
            }
            entries
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach {
                    children.add(it)
                    labels.add("📁  " + it.name)
                }
            if (labels.isEmpty()) labels.add(activity.getString(R.string.opencode_picker_empty))
            adapter.notifyDataSetChanged()
        }

        list.setOnItemClickListener { _, _, position, _ ->
            children.getOrNull(position)?.let { load(it) }
        }
        up.setOnClickListener {
            val parent = current.parentFile
            if (parent != null) load(parent) else load(OpenCodeStorage.primaryRoot())
        }
        go.setOnClickListener { openTypedPath(activity, path.text.toString()) { load(it) } }
        path.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openTypedPath(activity, path.text.toString()) { load(it) }
                true
            } else {
                false
            }
        }

        load(initial)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_picker_title)
            .setView(content)
            .setPositiveButton(R.string.opencode_picker_choose) { _, _ ->
                listener.onDirectorySelected(current)
            }
            .setNegativeButton(R.string.opencode_picker_cancel, null)
            .show()
    }

    private fun openTypedPath(activity: Activity, raw: String, load: (File) -> Unit) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val expanded = when {
            trimmed == "~" -> OpenCodeBootstrap.HOME
            trimmed.startsWith("~/") -> OpenCodeBootstrap.HOME + trimmed.substring(1)
            else -> trimmed
        }
        val target = File(expanded)
        if (target.isDirectory) {
            load(target)
        } else {
            Toast.makeText(activity, R.string.opencode_picker_not_directory, Toast.LENGTH_SHORT).show()
        }
    }
}

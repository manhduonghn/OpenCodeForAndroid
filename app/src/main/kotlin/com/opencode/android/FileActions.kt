package com.opencode.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Shared file affordances of the in-app viewers.
 *
 * The Files screen, the editor and the image preview must all agree on what can be edited in the
 * app and what is better handed to another application, so the extension lists live here.
 */
object FileActions {

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "rst", "log",
        "json", "jsonc", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
        "kt", "kts", "java", "js", "jsx", "ts", "tsx", "html", "htm", "css", "scss", "less",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs", "php", "swift", "lua",
        "sh", "bash", "zsh", "fish", "bat", "ps1", "sql", "gradle", "groovy", "dart",
        "vue", "svelte", "env", "gitignore", "gitattributes", "editorconfig",
        "lock", "diff", "patch", "csv", "tsv", "tex", "proto", "graphql", "gql", "makefile",
    )

    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico",
    )

    fun mimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    /** True when the in-app editor can be offered before falling back to an external viewer. */
    fun isTextFile(file: File): Boolean {
        if (file.name.equals("Makefile", ignoreCase = true)) return true
        if (mimeType(file).startsWith("text/")) return true
        return file.extension.lowercase(Locale.ROOT) in TEXT_EXTENSIONS
    }

    fun isImageFile(file: File): Boolean {
        if (mimeType(file).startsWith("image/")) return true
        return file.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
    }

    /**
     * Hand [file] to another application. The chooser carries the read grant so receivers that
     * only look at the intent flags still get access to the content URI. [writable] adds the
     * write grant for the editor's "open in another app" flow, which reloads and optionally
     * commits the file when the user comes back.
     */
    fun openExternal(activity: Activity, file: File, writable: Boolean = false): Boolean {
        val uri = uriFor(activity, file) ?: return false
        val flags = if (writable) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(file))
            .addFlags(flags)
        val chooser = Intent.createChooser(intent, activity.getString(R.string.opencode_files_open_with))
            .addFlags(flags)
        return try {
            activity.startActivity(chooser)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun uriFor(activity: Activity, file: File): Uri? = try {
        FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
    } catch (e: Exception) {
        null
    }

    /**
     * Show the standard properties dialog. [extra] entries are appended after size and modified
     * time, which lets the editor add encoding and line-ending details without its own dialog.
     */
    fun showProperties(
        activity: Activity,
        file: File,
        extra: List<Pair<String, String>> = emptyList(),
    ) {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "opencode-file-info").apply { isDaemon = true }
        }
        executor.execute {
            val size = FileBrowser.sizeOf(file)
            val permissions = FileBrowser.permissions(file)
            val target = FileBrowser.symlinkTarget(file)
            val type = when {
                target != null -> activity.getString(R.string.opencode_files_type_link)
                file.isDirectory -> activity.getString(R.string.opencode_files_type_directory)
                else -> mimeType(file)
            }
            val modified = try {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(file.lastModified()))
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
                for ((label, value) in extra) {
                    if (value.isEmpty()) continue
                    append("\n\n")
                    append(label)
                    append(": ")
                    append(value)
                }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                MaterialAlertDialogBuilder(activity)
                    .setTitle(
                        activity.getString(R.string.opencode_files_properties_title, file.name),
                    )
                    .setMessage(message)
                    .setPositiveButton(R.string.opencode_files_close, null)
                    .show()
            }
        }
        executor.shutdown()
    }
}

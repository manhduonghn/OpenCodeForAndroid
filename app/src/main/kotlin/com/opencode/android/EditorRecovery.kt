package com.opencode.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * A durable copy of an unsaved editor buffer.
 *
 * The snapshot is written to the app-private `filesDir`, never next to the edited file, so it
 * survives a process death without touching the project or the Git worktree. It stores only the
 * text and the metadata needed to put the caret back; [TextFileIO] remains the owner of on-disk
 * encoding, BOM and newlines.
 */
data class EditorRecovery(
    val text: String,
    val baseHash: String,
    val languageId: String,
    val anchor: Int,
    val head: Int,
    val scrollTop: Int,
    val scrollLeft: Int,
    val savedAt: Long,
) {
    fun viewState(): EditorViewState = EditorViewState(
        anchor = anchor,
        head = head,
        scrollTop = scrollTop,
        scrollLeft = scrollLeft,
    )
}

/**
 * Reads and writes [EditorRecovery] snapshots.
 *
 * A snapshot consists of two files per source path, keyed by the SHA-256 of the absolute path:
 * a raw UTF-8 text file and a small JSON metadata file. The metadata is written last, so an
 * interrupted write is ignored instead of restoring half a document.
 */
object EditorRecoveryStore {

    private const val DIRECTORY = "editor-recovery"
    private const val META_SUFFIX = ".json"
    private const val TEXT_SUFFIX = ".txt"

    /** Keep the same ceiling as the bridge; a recovery that cannot be loaded is useless. */
    private const val MAX_TEXT_CHARS = EditorProtocol.MAX_TEXT_CHARS

    fun load(context: Context, file: File): EditorRecovery? {
        val key = key(file)
        val directory = directory(context)
        val metaFile = File(directory, key + META_SUFFIX)
        val textFile = File(directory, key + TEXT_SUFFIX)
        if (!metaFile.isFile || !textFile.isFile) return null

        val meta = try {
            JSONObject(metaFile.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        if (meta.optString("path", "") != file.absolutePath) return null
        val expectedLength = meta.optInt("length", -1)
        if (expectedLength < 0 || expectedLength > MAX_TEXT_CHARS) return null

        val text = try {
            textFile.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        if (text.length != expectedLength) {
            // A truncated text file is not recoverable; keep the metadata out of the way.
            clear(context, file)
            return null
        }
        return EditorRecovery(
            text = text,
            baseHash = meta.optString("baseHash", ""),
            languageId = meta.optString("language", "plaintext"),
            anchor = meta.optInt("anchor", 0),
            head = meta.optInt("head", 0),
            scrollTop = meta.optInt("scrollTop", 0),
            scrollLeft = meta.optInt("scrollLeft", 0),
            savedAt = meta.optLong("savedAt", 0L),
        )
    }

    /**
     * Persist [snapshot] for [file]. Returns false when the snapshot is too large or the private
     * directory cannot be written; callers treat recovery as best effort and never block a save.
     */
    fun save(context: Context, file: File, snapshot: EditorSnapshot): Boolean {
        if (snapshot.text.length > MAX_TEXT_CHARS) return false
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val key = key(file)
        val textFile = File(directory, key + TEXT_SUFFIX)
        val metaFile = File(directory, key + META_SUFFIX)
        val meta = JSONObject()
            .put("path", file.absolutePath)
            .put("baseHash", snapshot.baseHash)
            .put("language", snapshot.languageId)
            .put("anchor", snapshot.anchor)
            .put("head", snapshot.head)
            .put("scrollTop", snapshot.scrollTop)
            .put("scrollLeft", snapshot.scrollLeft)
            .put("length", snapshot.text.length)
            .put("savedAt", System.currentTimeMillis())
        return try {
            writeAtomic(textFile, snapshot.text)
            writeAtomic(metaFile, meta.toString())
            true
        } catch (e: Exception) {
            // Do not leave a text file without metadata behind.
            try {
                textFile.delete()
                metaFile.delete()
            } catch (ignored: Exception) {
                // Best effort.
            }
            false
        }
    }

    fun clear(context: Context, file: File) {
        val key = key(file)
        val directory = directory(context)
        try {
            File(directory, key + TEXT_SUFFIX).delete()
            File(directory, key + META_SUFFIX).delete()
        } catch (e: Exception) {
            // Recovery must never break opening or closing a file.
        }
    }

    private fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    private fun key(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) append(String.format(Locale.US, "%02x", byte.toInt() and 0xFF))
        }
    }

    private fun writeAtomic(target: File, content: String) {
        val parent = target.parentFile ?: throw IllegalStateException("No recovery directory")
        val temp = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(content.toByteArray(StandardCharsets.UTF_8))
                stream.flush()
                try {
                    stream.fd.sync()
                } catch (e: Exception) {
                    // Not every filesystem exposes fsync; the rename is still atomic.
                }
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IllegalStateException("Cannot replace $target")
            }
        } finally {
            try {
                temp.delete()
            } catch (e: Exception) {
                // Nothing else to clean up.
            }
        }
    }
}

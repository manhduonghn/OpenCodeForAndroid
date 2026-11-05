package com.opencode.android

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * A text file opened by the in-app editor.
 *
 * [hash] is the SHA-256 of the bytes on disk at load time. Before a save it is compared again, so
 * a second process (git, the web UI, another editor) cannot be silently overwritten. The BOM and
 * the dominant newline are remembered so a save does not rewrite a file with a different encoding
 * or line ending style.
 */
data class TextDocument(
    val file: File,
    val text: String,
    val charset: Charset,
    val bom: ByteArray,
    val newline: String,
    val hash: String,
    val size: Long,
    /** String resource of the reason the document is read-only, or null when it is editable. */
    val readOnlyReason: Int?,
)

sealed class TextLoad {
    data class Ok(val document: TextDocument) : TextLoad()

    data class Failure(val message: String) : TextLoad()
}

sealed class TextSave {
    data class Ok(val hash: String) : TextSave()

    /** The bytes on disk are no longer the ones the editor loaded. */
    object Conflict : TextSave()

    data class Failure(val message: String) : TextSave()
}

/**
 * Encoding, newline and atomic-write support of the in-app editor.
 *
 * Only encodings that can round-trip safely are editable: UTF-8 (with or without BOM) and UTF-16
 * with a BOM. A file without a BOM that is not valid UTF-8 is decoded as Windows-1252 so it can be
 * read, but the editor keeps it read-only to avoid corrupting bytes it does not understand.
 */
object TextFileIO {

    /** Files larger than this are opened read-only; keeping a multi-megabyte buffer in an EditText is unsafe. */
    const val MAX_EDITABLE_BYTES = 2L * 1024L * 1024L

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    fun load(file: File): TextLoad {
        if (!file.isFile) {
            return TextLoad.Failure("Không tìm thấy tệp.")
        }
        val size = file.length()
        val hash = hashFile(file)
            ?: return TextLoad.Failure("Không đọc được tệp.")

        if (size > MAX_EDITABLE_BYTES) {
            return TextLoad.Ok(
                TextDocument(
                    file = file,
                    text = "",
                    charset = StandardCharsets.UTF_8,
                    bom = ByteArray(0),
                    newline = "\n",
                    hash = hash,
                    size = size,
                    readOnlyReason = R.string.opencode_editor_readonly_large,
                ),
            )
        }

        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return TextLoad.Failure(e.message ?: "Không đọc được tệp.")
        }

        val decoded = decode(bytes)
        if (decoded == null) {
            return TextLoad.Ok(
                TextDocument(
                    file = file,
                    text = "",
                    charset = StandardCharsets.UTF_8,
                    bom = ByteArray(0),
                    newline = "\n",
                    hash = hash,
                    size = size,
                    readOnlyReason = R.string.opencode_editor_readonly_binary,
                ),
            )
        }

        val newline = dominantNewline(decoded.text)
        val readOnly = when {
            !decoded.safe -> R.string.opencode_editor_readonly_encoding
            !file.canWrite() -> R.string.opencode_editor_readonly_permission
            else -> null
        }
        return TextLoad.Ok(
            TextDocument(
                file = file,
                text = decoded.text,
                charset = decoded.charset,
                bom = decoded.bom,
                newline = newline,
                hash = hash,
                size = size,
                readOnlyReason = readOnly,
            ),
        )
    }

    /**
     * Save [newText] atomically. When [force] is false the file on disk must still have the hash
     * that [document] was loaded with; otherwise [TextSave.Conflict] is returned and nothing is
     * written.
     */
    fun save(document: TextDocument, newText: String, force: Boolean = false): TextSave {
        if (!force) {
            val current = hashFile(document.file)
            if (current != null && current != document.hash) return TextSave.Conflict
        }
        val normalized = normalizeNewlines(newText, document.newline)
        val body = try {
            normalized.toByteArray(document.charset)
        } catch (e: Exception) {
            return TextSave.Failure(e.message ?: "Không mã hoá được nội dung.")
        }
        val output = if (document.bom.isEmpty()) body else document.bom + body
        val parent = document.file.parentFile
            ?: return TextSave.Failure("Tệp không có thư mục cha.")
        var temp: File? = null
        return try {
            temp = File.createTempFile(".${document.file.name}.", ".tmp", parent)
            FileOutputStream(temp).use { stream ->
                stream.write(output)
                stream.flush()
                try {
                    stream.fd.sync()
                } catch (e: Exception) {
                    // Not every filesystem supports fsync through this descriptor.
                }
            }
            copyPermissions(document.file, temp)
            moveReplacing(temp, document.file)
            TextSave.Ok(sha256(output))
        } catch (e: Exception) {
            TextSave.Failure(e.message ?: "Không ghi được tệp.")
        } finally {
            try {
                temp?.delete()
            } catch (e: Exception) {
                // Nothing else to clean up.
            }
        }
    }

    fun hashFile(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        hex(digest.digest())
    } catch (e: Exception) {
        null
    }

    private fun decode(bytes: ByteArray): Decoded? {
        when {
            bytes.startsWith(UTF8_BOM) -> {
                val body = bytes.copyOfRange(UTF8_BOM.size, bytes.size)
                return Decoded(
                    text = String(body, StandardCharsets.UTF_8),
                    charset = StandardCharsets.UTF_8,
                    bom = UTF8_BOM,
                    safe = true,
                )
            }
            bytes.startsWith(UTF16_LE_BOM) -> {
                val body = bytes.copyOfRange(UTF16_LE_BOM.size, bytes.size)
                return Decoded(
                    text = String(body, StandardCharsets.UTF_16LE),
                    charset = StandardCharsets.UTF_16LE,
                    bom = UTF16_LE_BOM,
                    safe = true,
                )
            }
            bytes.startsWith(UTF16_BE_BOM) -> {
                val body = bytes.copyOfRange(UTF16_BE_BOM.size, bytes.size)
                return Decoded(
                    text = String(body, StandardCharsets.UTF_16BE),
                    charset = StandardCharsets.UTF_16BE,
                    bom = UTF16_BE_BOM,
                    safe = true,
                )
            }
            isBinary(bytes) -> return null
        }
        val strict = strictUtf8(bytes)
        if (strict != null) {
            return Decoded(
                text = strict,
                charset = StandardCharsets.UTF_8,
                bom = ByteArray(0),
                safe = true,
            )
        }
        return Decoded(
            text = String(bytes, WINDOWS_1252),
            charset = WINDOWS_1252,
            bom = ByteArray(0),
            safe = false,
        )
    }

    private fun isBinary(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 8192)
        for (index in 0 until limit) {
            if (bytes[index].toInt() == 0) return true
        }
        return false
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun dominantNewline(text: String): String {
        var crlf = 0
        var lf = 0
        var cr = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> {
                    crlf++
                    index++
                }
                char == '\n' -> lf++
                char == '\r' -> cr++
            }
            index++
        }
        return when {
            crlf >= lf && crlf >= cr && crlf > 0 -> "\r\n"
            cr > lf -> "\r"
            else -> "\n"
        }
    }

    private fun normalizeNewlines(text: String, newline: String): String {
        val lf = text.replace("\r\n", "\n").replace('\r', '\n')
        return if (newline == "\n") lf else lf.replace("\n", newline)
    }

    private fun copyPermissions(source: File, target: File) {
        try {
            val permissions = Files.getPosixFilePermissions(
                source.toPath(),
                LinkOption.NOFOLLOW_LINKS,
            )
            Files.setPosixFilePermissions(target.toPath(), permissions)
        } catch (e: Exception) {
            // Best effort; the rename still preserves the content.
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (e: Exception) {
            // Some mounted filesystems reject ATOMIC_MOVE; fall back to a normal replace.
        }
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            if (!source.renameTo(target)) throw e
        }
    }

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            append(String.format(Locale.US, "%02x", byte.toInt() and 0xFF))
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private class Decoded(
        val text: String,
        val charset: Charset,
        val bom: ByteArray,
        /** False when the file had no BOM and was not valid UTF-8. */
        val safe: Boolean,
    )
}

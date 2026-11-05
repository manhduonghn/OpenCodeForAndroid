package com.opencode.android

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextFileIOTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun savePreservesUtf8BomAndCrlf() {
        val file = temporary.newFile("sample.txt")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val original = "một\r\nhai"
        file.writeBytes(bom + original.toByteArray(StandardCharsets.UTF_8))

        val loaded = TextFileIO.load(file)
        assertTrue(loaded is TextLoad.Ok)
        val document = (loaded as TextLoad.Ok).document
        assertEquals("\r\n", document.newline)
        assertArrayEquals(bom, document.bom)
        assertEquals("một\r\nhai", document.text)

        val result = TextFileIO.save(document, "ba\nbon")
        assertTrue(result is TextSave.Ok)
        assertArrayEquals(
            bom + "ba\r\nbon".toByteArray(StandardCharsets.UTF_8),
            file.readBytes(),
        )
    }

    @Test
    fun saveDetectsAChangedFileUnlessForced() {
        val file = temporary.newFile("conflict.txt")
        file.writeText("before", StandardCharsets.UTF_8)
        val loaded = TextFileIO.load(file)
        assertTrue(loaded is TextLoad.Ok)
        val document = (loaded as TextLoad.Ok).document

        file.writeText("changed by another process", StandardCharsets.UTF_8)
        assertEquals(TextSave.Conflict, TextFileIO.save(document, "editor text"))

        assertTrue(TextFileIO.save(document, "editor text", force = true) is TextSave.Ok)
        assertEquals("editor text", file.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun utf16WithBomRoundTrips() {
        val file = temporary.newFile("utf16.txt")
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        file.writeBytes(bom + "xin chào".toByteArray(StandardCharsets.UTF_16LE))

        val loaded = TextFileIO.load(file)
        assertTrue(loaded is TextLoad.Ok)
        val document = (loaded as TextLoad.Ok).document
        assertEquals(StandardCharsets.UTF_16LE, document.charset)
        assertTrue(TextFileIO.save(document, "tạm biệt") is TextSave.Ok)
        assertArrayEquals(
            bom + "tạm biệt".toByteArray(StandardCharsets.UTF_16LE),
            file.readBytes(),
        )
    }

    @Test
    fun binaryFilesOpenReadOnly() {
        val file = temporary.newFile("binary.bin")
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))

        val loaded = TextFileIO.load(file)
        assertTrue(loaded is TextLoad.Ok)
        val document = (loaded as TextLoad.Ok).document
        assertEquals(R.string.opencode_editor_readonly_binary, document.readOnlyReason)
        assertEquals("", document.text)
    }

    @Test
    fun invalidUtf8IsReadableButNotEditable() {
        val file = temporary.newFile("legacy.txt")
        file.writeBytes(byteArrayOf(0xE9.toByte(), 0x74, 0x65))

        val loaded = TextFileIO.load(file)
        assertTrue(loaded is TextLoad.Ok)
        val document = (loaded as TextLoad.Ok).document
        assertEquals(R.string.opencode_editor_readonly_encoding, document.readOnlyReason)
        assertEquals("éte", document.text)
    }

    @Test
    fun largeFilesAreNotLoadedIntoTheEditor() {
        val file = temporary.newFile("large.txt")
        file.writeBytes(ByteArray((TextFileIO.MAX_EDITABLE_BYTES + 1).toInt()) { 'a'.code.toByte() })

        val loaded = TextFileIO.load(file)
        assertTrue(loaded is TextLoad.Ok)
        val document = (loaded as TextLoad.Ok).document
        assertEquals(R.string.opencode_editor_readonly_large, document.readOnlyReason)
        assertEquals("", document.text)
        assertNotNull(TextFileIO.hashFile(file))
    }
}

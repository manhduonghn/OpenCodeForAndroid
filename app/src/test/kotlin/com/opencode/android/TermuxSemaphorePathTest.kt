package com.opencode.android

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TermuxSemaphorePathTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val releasePrefix = File("/data/data/com.opencode.android/files/usr")

    @Test
    fun replacementFitsTheCompiledInPath() {
        val replacement = TermuxSemaphorePath.replacement(releasePrefix)
        assertEquals("/data/data/com.opencode.android/tmp/sem.", replacement)
        assertEquals(TermuxSemaphorePath.TERMUX_TMP.length, replacement.length)
    }

    @Test
    fun onlyTheSemaphoreLibraryApplies() {
        assertTrue(TermuxSemaphorePath.appliesTo("lib/libandroid-posix-semaphore.so"))
        assertFalse(TermuxSemaphorePath.appliesTo("lib/libandroid-posix-semaphore.so.1"))
        assertFalse(TermuxSemaphorePath.appliesTo("bin/libandroid-posix-semaphore.so"))
        assertFalse(TermuxSemaphorePath.appliesTo("lib/libandroid-support.so"))
    }

    @Test
    fun patchRewritesThePathInPlaceAndIsIdempotent() {
        val prefix = temporary.newFolder("files", "usr")
        val lib = File(prefix, "lib").apply { mkdirs() }
        val library = File(lib, "libandroid-posix-semaphore.so")
        val header = "ELF header\u0000".toByteArray(StandardCharsets.ISO_8859_1)
        val tail = "\u0000trailing string".toByteArray(StandardCharsets.ISO_8859_1)
        val original = header +
            TermuxSemaphorePath.TERMUX_TMP.toByteArray(StandardCharsets.ISO_8859_1) +
            tail
        library.writeBytes(original)

        assertEquals(1, TermuxSemaphorePath.patch(library, prefix))

        val patched = library.readBytes()
        assertEquals(original.size, patched.size)
        val text = String(patched, StandardCharsets.ISO_8859_1)
        assertFalse(text.contains(TermuxSemaphorePath.TERMUX_TMP))
        assertTrue(text.contains(TermuxSemaphorePath.replacement(prefix)))
        // The bytes after the rewritten string are still there: only the string changed.
        assertTrue(text.endsWith("\u0000trailing string"))

        assertEquals(0, TermuxSemaphorePath.patch(library, prefix))
        assertEquals(patched.toList(), library.readBytes().toList())
    }

    @Test
    fun patchIgnoresFilesWithoutTheTermuxPath() {
        val prefix = temporary.newFolder("files", "usr")
        val file = temporary.newFile("notes.txt")
        val original = "nothing to relocate here"
        file.writeText(original, StandardCharsets.UTF_8)

        assertEquals(0, TermuxSemaphorePath.patch(file, prefix))
        assertEquals(original, file.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun ensureDirectoryCreatesTheParentOfTheSemaphorePath() {
        val prefix = temporary.newFolder("files", "usr")
        val directory = TermuxSemaphorePath.directory(prefix)
        assertFalse(directory.exists())

        assertTrue(TermuxSemaphorePath.ensureDirectory(prefix))
        assertTrue(directory.isDirectory)

        // A second pass reports that nothing had to be created.
        assertFalse(TermuxSemaphorePath.ensureDirectory(prefix))
    }
}

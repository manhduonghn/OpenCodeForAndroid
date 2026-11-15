package com.opencode.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyEncoderTest {
    private val escape = "\u001b"

    private fun key(
        value: TermKey,
        modifiers: KeyModifiers = KeyModifiers.NONE,
        applicationCursor: Boolean = false,
    ): String = KeyEncoder.keyText(value, modifiers, applicationCursor)

    private fun character(value: Int, modifiers: KeyModifiers = KeyModifiers.NONE): String =
        String(KeyEncoder.char(value, modifiers), Charsets.UTF_8)

    @Test
    fun cursorKeysUseCsiOrSs3AccordingToDecCkm() {
        assertEquals("$escape[A", key(TermKey.UP))
        assertEquals("$escape[B", key(TermKey.DOWN))
        assertEquals("$escape[C", key(TermKey.RIGHT))
        assertEquals("$escape[D", key(TermKey.LEFT))
        assertEquals("${escape}OA", key(TermKey.UP, applicationCursor = true))
        assertEquals("${escape}OD", key(TermKey.LEFT, applicationCursor = true))
    }

    @Test
    fun modifiedCursorKeysAlwaysUseTheCsiParameterForm() {
        assertEquals("$escape[1;5A", key(TermKey.UP, KeyModifiers(ctrl = true)))
        assertEquals("$escape[1;3C", key(TermKey.RIGHT, KeyModifiers(alt = true)))
        assertEquals("$escape[1;2D", key(TermKey.LEFT, KeyModifiers(shift = true)))
        assertEquals(
            "$escape[1;8B",
            key(TermKey.DOWN, KeyModifiers(ctrl = true, alt = true, shift = true)),
        )
        // Like xterm, a modifier wins over application cursor mode.
        assertEquals("$escape[1;5A", key(TermKey.UP, KeyModifiers(ctrl = true), true))
    }

    @Test
    fun homeEndEditingAndPagingKeys() {
        assertEquals("$escape[H", key(TermKey.HOME))
        assertEquals("$escape[F", key(TermKey.END))
        assertEquals("${escape}OH", key(TermKey.HOME, applicationCursor = true))
        assertEquals("$escape[2~", key(TermKey.INSERT))
        assertEquals("$escape[3~", key(TermKey.DELETE))
        assertEquals("$escape[5~", key(TermKey.PAGE_UP))
        assertEquals("$escape[6~", key(TermKey.PAGE_DOWN))
        assertEquals("$escape[3;5~", key(TermKey.DELETE, KeyModifiers(ctrl = true)))
        assertEquals("$escape[5;2~", key(TermKey.PAGE_UP, KeyModifiers(shift = true)))
    }

    @Test
    fun functionKeysUseTheDocumentedXtermSequences() {
        assertEquals("${escape}OP", key(TermKey.F1))
        assertEquals("${escape}OS", key(TermKey.F4))
        assertEquals("$escape[15~", key(TermKey.F5))
        assertEquals("$escape[17~", key(TermKey.F6))
        assertEquals("$escape[21~", key(TermKey.F10))
        assertEquals("$escape[24~", key(TermKey.F12))
        assertEquals("$escape[1;5P", key(TermKey.F1, KeyModifiers(ctrl = true)))
        assertEquals("$escape[15;2~", key(TermKey.F5, KeyModifiers(shift = true)))
    }

    @Test
    fun enterTabEscapeAndBackspace() {
        assertEquals("\r", key(TermKey.ENTER))
        assertEquals("$escape\r", key(TermKey.ENTER, KeyModifiers(alt = true)))
        assertEquals("\t", key(TermKey.TAB))
        assertEquals("$escape[Z", key(TermKey.TAB, KeyModifiers(shift = true)))
        assertEquals(escape, key(TermKey.ESCAPE))
        assertEquals("\u007f", key(TermKey.BACKSPACE))
        assertEquals("\b", key(TermKey.BACKSPACE, KeyModifiers(ctrl = true)))
        assertEquals("$escape\u007f", key(TermKey.BACKSPACE, KeyModifiers(alt = true)))
    }

    @Test
    fun controlFoldLettersAndSymbols() {
        assertEquals("\u0003", character('c'.code, KeyModifiers(ctrl = true)))
        assertEquals("\u0003", character('C'.code, KeyModifiers(ctrl = true)))
        assertEquals("\u0001", character('a'.code, KeyModifiers(ctrl = true)))
        assertEquals("\u001a", character('z'.code, KeyModifiers(ctrl = true)))
        assertEquals("\u0004", character('d'.code, KeyModifiers(ctrl = true)))
        assertEquals("\u0000", character(' '.code, KeyModifiers(ctrl = true)))
        assertEquals("\u0000", character('@'.code, KeyModifiers(ctrl = true)))
        assertEquals("\u001b", character('['.code, KeyModifiers(ctrl = true)))
        assertEquals("\u001c", character('\\'.code, KeyModifiers(ctrl = true)))
        assertEquals("\u001d", character(']'.code, KeyModifiers(ctrl = true)))
        assertEquals("\u001f", character('_'.code, KeyModifiers(ctrl = true)))
        assertEquals("\u007f", character('?'.code, KeyModifiers(ctrl = true)))
        // Ctrl has no meaning for these characters, so they pass through.
        assertEquals("1", character('1'.code, KeyModifiers(ctrl = true)))
    }

    @Test
    fun altAddsAnEscapePrefix() {
        assertEquals("${escape}x", character('x'.code, KeyModifiers(alt = true)))
        assertEquals("$escape\u0018", character('x'.code, KeyModifiers(ctrl = true, alt = true)))
    }

    @Test
    fun nonAsciiInputIsEncodedAsUtf8() {
        assertEquals("é", character('é'.code))
        assertEquals("é", character('é'.code, KeyModifiers(ctrl = true)))
        assertEquals("😀", String(KeyEncoder.char(0x1F600), Charsets.UTF_8))
    }

    @Test
    fun pasteUsesCarriageReturnsAndBracketedMarkers() {
        assertEquals("a\rb\rc", String(KeyEncoder.paste("a\nb\r\nc", bracketed = false)))
        assertEquals(
            "$escape[200~ls\r$escape[201~",
            String(KeyEncoder.paste("ls\n", bracketed = true)),
        )
        assertEquals(
            "$escape[200~xy$escape[201~",
            String(KeyEncoder.paste("x$escape[201~y", bracketed = true)),
        )
    }

    @Test
    fun modifierParameterMatchesXterm() {
        assertEquals(1, KeyModifiers.NONE.xtermParam)
        assertEquals(2, KeyModifiers(shift = true).xtermParam)
        assertEquals(3, KeyModifiers(alt = true).xtermParam)
        assertEquals(5, KeyModifiers(ctrl = true).xtermParam)
        assertEquals(7, KeyModifiers(ctrl = true, alt = true).xtermParam)
        assertEquals(8, KeyModifiers(ctrl = true, alt = true, shift = true).xtermParam)
    }
}

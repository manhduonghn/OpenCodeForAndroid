package com.opencode.android.terminal

/** A key that has a fixed xterm escape sequence. */
enum class TermKey {
    UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    ENTER, TAB, ESCAPE, BACKSPACE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/** The modifiers held while a key is pressed. */
data class KeyModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    val any: Boolean get() = ctrl || alt || shift

    /** xterm's modifier parameter: 1 + Shift(1) + Alt(2) + Ctrl(4). */
    val xtermParam: Int get() =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    companion object {
        val NONE = KeyModifiers()
    }
}

/**
 * Translates key presses into the bytes an xterm sends.
 *
 * The sequences are the documented xterm defaults. Arrow and Home/End keys
 * have two forms: the normal CSI form and the SS3 form a program selects with
 * DECCKM (application cursor keys). A modifier always uses the CSI form with
 * the modifier parameter, never SS3.
 */
object KeyEncoder {
    private const val ESC = "\u001b"

    /** Final byte of the cursor/home/end keys, in CSI and SS3 forms. */
    private val cursorFinal = mapOf(
        TermKey.UP to 'A',
        TermKey.DOWN to 'B',
        TermKey.RIGHT to 'C',
        TermKey.LEFT to 'D',
        TermKey.HOME to 'H',
        TermKey.END to 'F',
    )

    /** Numeric parameter of the keys that use the `CSI n ~` form. */
    private val tildeCode = mapOf(
        TermKey.INSERT to 2,
        TermKey.DELETE to 3,
        TermKey.PAGE_UP to 5,
        TermKey.PAGE_DOWN to 6,
        TermKey.F5 to 15,
        TermKey.F6 to 17,
        TermKey.F7 to 18,
        TermKey.F8 to 19,
        TermKey.F9 to 20,
        TermKey.F10 to 21,
        TermKey.F11 to 23,
        TermKey.F12 to 24,
    )

    /** Final byte of F1-F4, which use SS3 rather than a numeric parameter. */
    private val functionFinal = mapOf(
        TermKey.F1 to 'P',
        TermKey.F2 to 'Q',
        TermKey.F3 to 'R',
        TermKey.F4 to 'S',
    )

    fun key(
        key: TermKey,
        mods: KeyModifiers = KeyModifiers.NONE,
        applicationCursor: Boolean = false,
    ): ByteArray = keyText(key, mods, applicationCursor).toByteArray(Charsets.UTF_8)

    fun keyText(
        key: TermKey,
        mods: KeyModifiers = KeyModifiers.NONE,
        applicationCursor: Boolean = false,
    ): String {
        cursorFinal[key]?.let { final ->
            return when {
                mods.any -> "$ESC[1;${mods.xtermParam}$final"
                applicationCursor -> "${ESC}O$final"
                else -> "$ESC[$final"
            }
        }
        tildeCode[key]?.let { code ->
            return if (mods.any) "$ESC[$code;${mods.xtermParam}~" else "$ESC[$code~"
        }
        functionFinal[key]?.let { final ->
            return if (mods.any) "$ESC[1;${mods.xtermParam}$final" else "${ESC}O$final"
        }
        return when (key) {
            TermKey.ENTER -> if (mods.alt) "$ESC\r" else "\r"
            TermKey.TAB -> when {
                mods.shift -> "$ESC[Z"
                mods.alt -> "$ESC\t"
                else -> "\t"
            }
            TermKey.ESCAPE -> if (mods.alt) "$ESC$ESC" else ESC
            TermKey.BACKSPACE -> when {
                mods.ctrl && mods.alt -> "$ESC\b"
                mods.ctrl -> "\b"
                mods.alt -> "$ESC\u007f"
                else -> "\u007f"
            }
            else -> ""
        }
    }

    /**
     * A typed character. Ctrl folds the character to the matching C0 control
     * code when one exists; Alt adds an Escape prefix. Other modifiers leave
     * the character unchanged, as a terminal has no encoding for them.
     */
    fun char(codePoint: Int, mods: KeyModifiers = KeyModifiers.NONE): ByteArray {
        val folded = if (mods.ctrl) controlCode(codePoint) ?: codePoint else codePoint
        val text = String(Character.toChars(folded))
        return (if (mods.alt) ESC + text else text).toByteArray(Charsets.UTF_8)
    }

    /** The C0 code Ctrl+[codePoint] sends, or null when Ctrl has no effect. */
    fun controlCode(codePoint: Int): Int? {
        if (codePoint !in 0x00..0x7F) return null
        val c = codePoint.toChar()
        return when {
            c in 'a'..'z' -> c - 'a' + 1
            c in 'A'..'Z' -> c - 'A' + 1
            c in charArrayOf(' ', '@', '2') -> 0
            c in charArrayOf('[', '3') -> 27
            c in charArrayOf('\\', '4') -> 28
            c in charArrayOf(']', '5') -> 29
            c in charArrayOf('^', '6', '~') -> 30
            c in charArrayOf('_', '-', '7', '/') -> 31
            c in charArrayOf('?', '8') -> 127
            else -> null
        }
    }

    /**
     * Clipboard text as terminal input. Newlines become carriage returns,
     * which is what the line discipline expects from the Enter key. With
     * bracketed paste enabled the text is wrapped in the begin/end markers,
     * and an end marker inside the text is removed so it cannot close the
     * paste early.
     */
    fun paste(text: String, bracketed: Boolean): ByteArray {
        val body = text.replace("\r\n", "\r").replace('\n', '\r')
        if (!bracketed) return body.toByteArray(Charsets.UTF_8)
        val safe = body.replace("$ESC[201~", "")
        return "$ESC[200~$safe$ESC[201~".toByteArray(Charsets.UTF_8)
    }
}

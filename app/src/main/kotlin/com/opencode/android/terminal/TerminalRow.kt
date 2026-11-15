package com.opencode.android.terminal

/**
 * One line of the terminal grid.
 *
 * Glyphs are Unicode code points rather than UTF-16 units, so a supplementary
 * character occupies one cell and its width is decided once, when it is
 * printed. The right half of a double-width glyph is [TerminalEmulator.WIDE_TAIL];
 * a zero glyph is an empty cell.
 *
 * Styles live in a parallel array of packed [CellStyle] words. Keeping the two
 * arrays instead of one object per cell makes copying and resizing a line a
 * pair of array operations.
 */
class TerminalRow internal constructor(
    width: Int,
    blank: Long,
) {
    internal var glyphs = IntArray(width)
        private set
    internal var styles = LongArray(width) { blank }
        private set

    /**
     * True when the following row continues this one because printing reached
     * the right margin. Copying the transcript uses it to join soft-wrapped
     * lines; scrolling keeps the flag with the row.
     */
    var wrapped: Boolean = false
        internal set

    val size: Int get() = glyphs.size

    /** Replaces the range `[from, to)` with empty cells that use [blank]. */
    internal fun clear(from: Int, to: Int, blank: Long) {
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(glyphs.size)
        if (start >= end) return
        java.util.Arrays.fill(glyphs, start, end, 0)
        java.util.Arrays.fill(styles, start, end, blank)
    }

    /**
     * Changes the line width. New columns are empty and use [blank]; when the
     * line shrinks in the middle of a double-width glyph, the orphaned right
     * half is removed as well.
     */
    internal fun resize(width: Int, blank: Long) {
        if (width == glyphs.size) return
        val oldGlyphs = glyphs
        val oldStyles = styles
        if (width < oldGlyphs.size && width > 0 && oldGlyphs[width] == TerminalEmulator.WIDE_TAIL) {
            oldGlyphs[width - 1] = 0
        }
        glyphs = oldGlyphs.copyOf(width)
        styles = LongArray(width) { index ->
            if (index < oldStyles.size) oldStyles[index] else blank
        }
    }

    internal fun isBlank(): Boolean = glyphs.all { it == 0 || it == ' '.code }

    /** The visible text of the line, with trailing empty cells removed. */
    fun text(): String {
        var end = glyphs.size
        while (end > 0 && (glyphs[end - 1] == 0 || glyphs[end - 1] == ' '.code)) end--
        if (end == 0) return ""
        val out = StringBuilder(end)
        for (index in 0 until end) {
            when (val glyph = glyphs[index]) {
                TerminalEmulator.WIDE_TAIL -> Unit
                0 -> out.append(' ')
                else -> out.appendCodePoint(glyph)
            }
        }
        return out.toString()
    }
}

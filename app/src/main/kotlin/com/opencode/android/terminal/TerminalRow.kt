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
        return text(0, end)
    }

    /**
     * The text of the cells `[from, to)`. Empty cells become spaces, while the
     * right half of a double-width glyph is skipped so it is not copied twice.
     * The range is clamped to the row, which makes it safe to pass a selection
     * reported by a view whose grid is one resize behind the emulator.
     */
    internal fun text(from: Int, to: Int): String {
        val start = from.coerceIn(0, glyphs.size)
        val end = to.coerceIn(start, glyphs.size)
        if (start >= end) return ""
        val out = StringBuilder(end - start)
        for (index in start until end) {
            when (val glyph = glyphs[index]) {
                TerminalEmulator.WIDE_TAIL -> Unit
                0 -> out.append(' ')
                else -> out.appendCodePoint(glyph)
            }
        }
        return out.toString()
    }

    /**
     * Columns of the last non-blank cell of the line, or null for a blank row.
     * A long press on empty space selects the text of that line instead.
     */
    internal fun textBounds(): IntRange? {
        var end = glyphs.size
        while (end > 0 && (glyphs[end - 1] == 0 || glyphs[end - 1] == ' '.code)) end--
        if (end == 0) return null
        return 0..(end - 1)
    }

    /** True when [column] holds printable text rather than a blank cell. */
    internal fun hasTextAt(column: Int): Boolean {
        if (column !in glyphs.indices) return false
        val glyph = glyphs[column]
        return glyph != 0 && glyph != ' '.code
    }

    /**
     * Columns of the whitespace-delimited word that contains [column]. A blank
     * cell is its own one-cell word; a tap on the right half of a double-width
     * glyph selects the glyph. Used by the long-press selection.
     */
    internal fun wordBounds(column: Int): IntRange {
        if (glyphs.isEmpty()) return 0..0
        var start = column.coerceIn(0, glyphs.size - 1)
        if (glyphs[start] == TerminalEmulator.WIDE_TAIL && start > 0) start--
        fun word(index: Int) = glyphs[index] != 0 && glyphs[index] != ' '.code
        if (!word(start)) return start..start
        var first = start
        while (first > 0 && word(first - 1)) first--
        var last = start
        while (last + 1 < glyphs.size && word(last + 1)) last++
        return first..last
    }
}

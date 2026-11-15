package com.opencode.android.terminal

/**
 * One cell's appearance, packed into a single `Long`.
 *
 * A screen row keeps its glyphs and its styles in two parallel arrays. Packing
 * the style avoids an object allocation per cell, which matters because a
 * scrollback of a few thousand rows is rewritten on every resize.
 *
 * Layout of the bits, from the least significant end:
 *
 * | bits  | meaning                               |
 * | ----- | ------------------------------------- |
 * | 0-24  | foreground colour                     |
 * | 25-49 | background colour                     |
 * | 50-58 | attribute flags                       |
 *
 * A colour is either an index into the 256 colour palette, one of the two
 * sentinels [DEFAULT_FG] / [DEFAULT_BG], or an RGB value with [TRUECOLOR] set.
 */
object CellStyle {
    /** Use the colour of the terminal theme. */
    const val DEFAULT_FG = 256
    const val DEFAULT_BG = 257

    /** Marks a 24-bit RGB colour; the low 24 bits hold `0xRRGGBB`. */
    const val TRUECOLOR = 0x1000000

    const val BOLD = 1 shl 0
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINE = 1 shl 3
    const val BLINK = 1 shl 4
    const val INVERSE = 1 shl 5
    const val INVISIBLE = 1 shl 6
    const val STRIKE = 1 shl 7

    private const val COLOUR_BITS = 25
    private const val COLOUR_MASK = 0x1FFFFFFL
    private const val ATTR_SHIFT = COLOUR_BITS * 2
    private const val ATTR_MASK = 0x1FFL

    /** The style every cell starts with. */
    val NORMAL: Long = pack(DEFAULT_FG, DEFAULT_BG, 0)

    fun encode(fg: Int, bg: Int, attrs: Int): Long = pack(fg, bg, attrs)

    fun fg(style: Long): Int = (style and COLOUR_MASK).toInt()

    fun bg(style: Long): Int = ((style ushr COLOUR_BITS) and COLOUR_MASK).toInt()

    fun attrs(style: Long): Int = ((style ushr ATTR_SHIFT) and ATTR_MASK).toInt()

    /** An RGB colour value ready to be stored as foreground or background. */
    fun rgb(red: Int, green: Int, blue: Int): Int =
        TRUECOLOR or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)

    fun isTrueColor(colour: Int): Boolean = colour and TRUECOLOR != 0

    private fun pack(fg: Int, bg: Int, attrs: Int): Long =
        (fg.toLong() and COLOUR_MASK) or
            ((bg.toLong() and COLOUR_MASK) shl COLOUR_BITS) or
            ((attrs.toLong() and ATTR_MASK) shl ATTR_SHIFT)
}

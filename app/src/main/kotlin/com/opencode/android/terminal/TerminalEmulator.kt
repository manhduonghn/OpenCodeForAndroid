package com.opencode.android.terminal

import java.io.ByteArrayOutputStream

/** Shape the program asked the cursor to have (DECSCUSR). */
enum class CursorShape { BLOCK, UNDERLINE, BAR }

/** Mouse tracking mode selected by DEC private modes 9, 1000, 1002 and 1003. */
enum class MouseMode { NONE, CLICK, DRAG, ANY }

/**
 * The screen model of an xterm-compatible terminal.
 *
 * [append] feeds output bytes through [TerminalParser]; the parser calls back
 * into this class only for things that change visible state. The model owns
 * the primary and alternate grids, scrollback, cursor, margins, tab stops and
 * the modes a full-screen program can switch.
 *
 * The class is not thread-safe. Its owner (a [TerminalSession]) serialises
 * [append], [resize] and the reads performed by the renderer.
 */
class TerminalEmulator(
    rows: Int,
    cols: Int,
    private val maxHistory: Int = 2_000,
) {
    private class CursorState(
        var row: Int,
        var col: Int,
        val style: Long,
        val pendingWrap: Boolean,
        val originMode: Boolean,
        val autoWrap: Boolean,
        val charsets: CharArray,
        val gl: Int,
    )

    private class Surface(val keepsHistory: Boolean) {
        val lines = ArrayList<TerminalRow>()
        var saved: CursorState? = null
    }

    var onReply: (ByteArray) -> Unit = {}
    var onBell: () -> Unit = {}

    var rows = rows.coerceAtLeast(1)
        private set
    var cols = cols.coerceAtLeast(1)
        private set

    private val main = Surface(keepsHistory = true)
    private val alternate = Surface(keepsHistory = false)
    private var surface = main
    private val scrollback = ArrayDeque<TerminalRow>()

    init {
        repeat(this.rows) {
            main.lines.add(TerminalRow(this.cols, CellStyle.NORMAL))
            alternate.lines.add(TerminalRow(this.cols, CellStyle.NORMAL))
        }
    }

    val isAltScreen: Boolean get() = surface === alternate
    val historySize: Int get() = if (isAltScreen) 0 else scrollback.size

    /** Number of rows ever pushed into scrollback; the view uses it to hold a scroll position. */
    var historyPushes = 0L
        private set

    /** Increases whenever visible cells may have changed. */
    var revision = 0L
        private set

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set

    var cursorVisible = true
        private set
    var cursorShape = CursorShape.BLOCK
        private set
    var cursorBlink = false
        private set
    var applicationCursorKeys = false
        private set
    var applicationKeypad = false
        private set
    var bracketedPaste = false
        private set
    var reverseVideo = false
        private set
    var mouseMode = MouseMode.NONE
        private set
    var mouseSgr = false
        private set
    var focusReporting = false
        private set

    var title = ""
        private set

    /** Path from the last OSC 7 report, when the shell sends one. */
    var workingDirectory: String? = null
        private set

    /** Colours answered to OSC 10 and 11, as `0xRRGGBB`. */
    var defaultForegroundRgb = 0xE6E1E5
    var defaultBackgroundRgb = 0x1C1B1F

    private var pendingWrap = false
    private var style = CellStyle.NORMAL
    private var marginTop = 0
    private var marginBottom = this.rows - 1
    private var tabStops = defaultTabs(this.cols)
    private var charsets = charArrayOf('B', 'B', 'B', 'B')
    private var gl = 0
    private var singleShift = -1
    private var lastCodePoint = ' '.code
    private var insertMode = false
    private var autoWrap = true
    private var originMode = false
    private var newLineMode = false

    private val parser = TerminalParser(object : TerminalSink {
        override fun output(codePoint: Int) = draw(codePoint)
        override fun control(codePoint: Int) = applyControl(codePoint)
        override fun escape(intermediates: String, finalByte: Int) = applyEscape(intermediates, finalByte)
        override fun controlSequence(
            prefix: Char,
            parameters: List<IntArray>,
            intermediates: String,
            finalByte: Int,
        ) = applyControlSequence(prefix, parameters, intermediates, finalByte)
        override fun operatingSystemCommand(text: String) = applyOperatingSystemCommand(text)
    })

    // ---------------------------------------------------------------- reading

    /**
     * Row [index] for rendering. Non-negative indexes are the visible screen;
     * negative values reach into scrollback (`-1` is the newest history row).
     */
    fun lineAt(index: Int): TerminalRow? = when {
        index >= 0 -> surface.lines.getOrNull(index)
        isAltScreen -> null
        else -> scrollback.getOrNull(scrollback.size + index)
    }

    /** Visible rows as text, trailing blanks removed and one line per row. */
    fun screenText(): String = buildString {
        surface.lines.forEachIndexed { index, row ->
            if (index > 0) append('\n')
            append(row.text())
        }
    }

    /**
     * What the user can actually see: the visible rows without the blank rows
     * below the last line. [scrollOffset] moves the window into scrollback, so a
     * long-press copies exactly what is painted, not the live screen behind it.
     * Rows that only wrapped at the margin are joined, like [transcriptText].
     */
    fun visibleText(scrollOffset: Int = 0): String {
        val offset = scrollOffset.coerceIn(0, historySize)
        return buildString {
            var started = false
            var previousWrapped = false
            for (index in 0 until rows) {
                val row = lineAt(index - offset) ?: continue
                if (started && !previousWrapped) append('\n')
                append(row.text())
                previousWrapped = row.wrapped
                started = true
            }
        }.trimEnd()
    }

    /** Scrollback and visible screen, with soft-wrapped lines joined. */
    fun transcriptText(): String {
        val rowsToWrite = ArrayList<TerminalRow>(historySize + surface.lines.size)
        if (!isAltScreen) rowsToWrite.addAll(scrollback)
        rowsToWrite.addAll(surface.lines)
        return buildString {
            rowsToWrite.forEachIndexed { index, row ->
                append(row.text())
                if (!row.wrapped && index != rowsToWrite.lastIndex) append('\n')
            }
        }.trimEnd()
    }

    // ---------------------------------------------------------------- input

    fun append(text: String) {
        parser.feed(text)
        revision++
    }

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        parser.feed(bytes, offset, length)
        revision++
    }

    private fun currentLine(): TerminalRow = surface.lines[cursorRow]

    private fun emptyStyle(): Long = CellStyle.encode(CellStyle.DEFAULT_FG, CellStyle.bg(style), 0)

    private fun blankLine(): TerminalRow = TerminalRow(cols, emptyStyle())

    // ---------------------------------------------------------------- printing

    private fun draw(codePoint: Int) {
        var glyph = codePoint
        val slot = if (singleShift >= 0) singleShift.also { singleShift = -1 } else gl
        if (charsets[slot] == '0' && glyph in 0x5F..0x7E) glyph = DEC_GRAPHICS[glyph - 0x5F]

        var width = WcWidth.of(glyph)
        if (width == 0) return
        if (width == 2 && cols < 2) width = 1

        if (pendingWrap) {
            pendingWrap = false
            if (autoWrap) wrapToNextLine()
        }
        if (width == 2 && cursorCol == cols - 1) {
            if (!autoWrap) return
            currentLine().clear(cursorCol, cols, emptyStyle())
            wrapToNextLine()
        }

        val line = currentLine()
        if (insertMode) insertCells(line, cursorCol, width)
        clearWideAt(line, cursorCol)
        if (width == 2) clearWideAt(line, cursorCol + 1)
        line.glyphs[cursorCol] = glyph
        line.styles[cursorCol] = style
        if (width == 2) {
            line.glyphs[cursorCol + 1] = WIDE_TAIL
            line.styles[cursorCol + 1] = style
        }
        lastCodePoint = glyph

        val next = cursorCol + width
        if (next >= cols) {
            cursorCol = cols - 1
            pendingWrap = autoWrap
        } else {
            cursorCol = next
        }
    }

    private fun wrapToNextLine() {
        currentLine().wrapped = true
        cursorCol = 0
        index()
    }

    private fun clearWideAt(line: TerminalRow, column: Int) {
        if (column < 0 || column >= line.size) return
        if (line.glyphs[column] == WIDE_TAIL && column > 0) line.glyphs[column - 1] = 0
        if (column + 1 < line.size && line.glyphs[column + 1] == WIDE_TAIL) line.glyphs[column + 1] = 0
    }

    private fun insertCells(line: TerminalRow, at: Int, count: Int) {
        val amount = count.coerceAtMost(cols - at)
        if (amount <= 0) return
        clearWideAt(line, at)
        System.arraycopy(line.glyphs, at, line.glyphs, at + amount, cols - at - amount)
        System.arraycopy(line.styles, at, line.styles, at + amount, cols - at - amount)
        line.clear(at, at + amount, emptyStyle())
        if (cols >= 2 && line.glyphs[cols - 1] != WIDE_TAIL && WcWidth.of(line.glyphs[cols - 1]) == 2) {
            line.glyphs[cols - 1] = 0
        }
    }

    private fun deleteCells(line: TerminalRow, at: Int, count: Int) {
        val amount = count.coerceAtMost(cols - at)
        if (amount <= 0) return
        clearWideAt(line, at)
        clearWideAt(line, at + amount - 1)
        System.arraycopy(line.glyphs, at + amount, line.glyphs, at, cols - at - amount)
        System.arraycopy(line.styles, at + amount, line.styles, at, cols - at - amount)
        line.clear(cols - amount, cols, emptyStyle())
        if (line.glyphs[at] == WIDE_TAIL) line.glyphs[at] = 0
    }

    // ---------------------------------------------------------------- scrolling

    private fun index() {
        if (cursorRow == marginBottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
    }

    private fun reverseIndex() {
        if (cursorRow == marginTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
    }

    private fun scrollUp(count: Int) {
        repeat(count.coerceIn(0, marginBottom - marginTop + 1)) {
            val removed = surface.lines.removeAt(marginTop)
            if (surface.keepsHistory && marginTop == 0) pushHistory(removed)
            surface.lines.add(marginBottom, blankLine())
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count.coerceIn(0, marginBottom - marginTop + 1)) {
            surface.lines.removeAt(marginBottom)
            surface.lines.add(marginTop, blankLine())
        }
    }

    private fun pushHistory(row: TerminalRow) {
        if (maxHistory <= 0) return
        scrollback.addLast(row)
        historyPushes++
        while (scrollback.size > maxHistory) scrollback.removeFirst()
    }

    // ---------------------------------------------------------------- cursor

    private fun setCursor(row: Int, column: Int) {
        pendingWrap = false
        cursorRow = if (originMode) {
            (marginTop + row).coerceIn(marginTop, marginBottom)
        } else {
            row.coerceIn(0, rows - 1)
        }
        cursorCol = column.coerceIn(0, cols - 1)
    }

    private fun cursorUp(count: Int) {
        pendingWrap = false
        val lowest = if (cursorRow >= marginTop) marginTop else 0
        cursorRow = (cursorRow - count).coerceAtLeast(lowest)
    }

    private fun cursorDown(count: Int) {
        pendingWrap = false
        val highest = if (cursorRow <= marginBottom) marginBottom else rows - 1
        cursorRow = (cursorRow + count).coerceAtMost(highest)
    }

    private fun tabForward(count: Int) {
        pendingWrap = false
        repeat(count) {
            var column = cursorCol + 1
            while (column < cols && !tabStops[column]) column++
            cursorCol = column.coerceAtMost(cols - 1)
        }
    }

    private fun tabBackward(count: Int) {
        pendingWrap = false
        repeat(count) {
            var column = cursorCol - 1
            while (column > 0 && !tabStops[column]) column--
            cursorCol = column.coerceAtLeast(0)
        }
    }

    private fun saveCursor() {
        surface.saved = CursorState(
            row = cursorRow,
            col = cursorCol,
            style = style,
            pendingWrap = pendingWrap,
            originMode = originMode,
            autoWrap = autoWrap,
            charsets = charsets.copyOf(),
            gl = gl,
        )
    }

    private fun restoreCursor() {
        val saved = surface.saved
        if (saved == null) {
            cursorRow = 0
            cursorCol = 0
            style = CellStyle.NORMAL
            pendingWrap = false
            originMode = false
            charsets = charArrayOf('B', 'B', 'B', 'B')
            gl = 0
            return
        }
        cursorRow = saved.row.coerceIn(0, rows - 1)
        cursorCol = saved.col.coerceIn(0, cols - 1)
        style = saved.style
        pendingWrap = saved.pendingWrap && cursorCol == cols - 1
        originMode = saved.originMode
        autoWrap = saved.autoWrap
        charsets = saved.charsets.copyOf()
        gl = saved.gl
    }

    private fun clearSurface(target: Surface) {
        val blank = emptyStyle()
        target.lines.forEach { line ->
            line.clear(0, line.size, blank)
            line.wrapped = false
        }
    }

    private fun switchSurface(showAlternate: Boolean) {
        val target = if (showAlternate) alternate else main
        if (surface === target) return
        surface = target
        if (surface.lines.size != rows || surface.lines.any { it.size != cols }) {
            cursorRow = fitSurface(surface, cursorRow)
            cursorCol = cursorCol.coerceIn(0, cols - 1)
        }
    }

    // ---------------------------------------------------------------- escape dispatch

    private fun applyControl(codePoint: Int) {
        when (codePoint) {
            0x07 -> onBell()
            0x08 -> {
                pendingWrap = false
                if (cursorCol > 0) cursorCol--
            }
            0x09 -> tabForward(1)
            0x0A, 0x0B, 0x0C -> {
                pendingWrap = false
                index()
                if (newLineMode) cursorCol = 0
            }
            0x0D -> {
                pendingWrap = false
                cursorCol = 0
            }
            0x0E -> gl = 1
            0x0F -> gl = 0
        }
    }

    private fun applyEscape(intermediates: String, finalByte: Int) {
        if (intermediates.isNotEmpty()) {
            when (intermediates[0]) {
                '(' -> charsets[0] = finalByte.toChar()
                ')', '-' -> charsets[1] = finalByte.toChar()
                '*', '.' -> charsets[2] = finalByte.toChar()
                '+', '/' -> charsets[3] = finalByte.toChar()
                '#' -> if (finalByte == '8'.code) alignmentPattern()
            }
            return
        }
        when (finalByte.toChar()) {
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> {
                pendingWrap = false
                index()
            }
            'E' -> {
                pendingWrap = false
                cursorCol = 0
                index()
            }
            'H' -> tabStops[cursorCol] = true
            'M' -> {
                pendingWrap = false
                reverseIndex()
            }
            'c' -> fullReset()
            '=' -> applicationKeypad = true
            '>' -> applicationKeypad = false
            'N' -> singleShift = 2
            'O' -> singleShift = 3
            'n' -> gl = 2
            'o' -> gl = 3
        }
    }

    private fun alignmentPattern() {
        surface.lines.forEach { line ->
            for (column in 0 until line.size) {
                line.glyphs[column] = 'E'.code
                line.styles[column] = CellStyle.NORMAL
            }
        }
        setCursor(0, 0)
    }

    private fun applyControlSequence(
        prefix: Char,
        parameters: List<IntArray>,
        intermediates: String,
        finalByte: Int,
    ) {
        val marker = prefix
        fun value(index: Int, fallback: Int): Int {
            val raw = parameters.getOrNull(index)?.firstOrNull() ?: -1
            return if (raw <= 0) fallback else raw
        }
        fun raw(index: Int): Int =
            parameters.getOrNull(index)?.firstOrNull()?.takeIf { it >= 0 } ?: 0

        if (intermediates.isNotEmpty()) {
            when {
                intermediates == " " && finalByte == 'q'.code -> setCursorStyle(raw(0))
                intermediates == "!" && finalByte == 'p'.code -> softReset()
                intermediates == "$" && finalByte == 'p'.code -> reportMode(raw(0), marker == '?')
            }
            return
        }

        when (finalByte.toChar()) {
            '@' -> if (marker == '\u0000') insertCells(currentLine(), cursorCol, value(0, 1))
            'A' -> cursorUp(value(0, 1))
            'B', 'e' -> cursorDown(value(0, 1))
            'C', 'a' -> {
                pendingWrap = false
                cursorCol = (cursorCol + value(0, 1)).coerceAtMost(cols - 1)
            }
            'D' -> {
                pendingWrap = false
                cursorCol = (cursorCol - value(0, 1)).coerceAtLeast(0)
            }
            'E' -> {
                cursorDown(value(0, 1))
                cursorCol = 0
            }
            'F' -> {
                cursorUp(value(0, 1))
                cursorCol = 0
            }
            'G', '`' -> {
                pendingWrap = false
                cursorCol = (value(0, 1) - 1).coerceIn(0, cols - 1)
            }
            'H', 'f' -> setCursor(value(0, 1) - 1, value(1, 1) - 1)
            'I' -> tabForward(value(0, 1))
            'J' -> eraseInDisplay(raw(0))
            'K' -> eraseInLine(raw(0))
            'L' -> insertLines(value(0, 1))
            'M' -> deleteLines(value(0, 1))
            'P' -> deleteCells(currentLine(), cursorCol, value(0, 1))
            'S' -> if (marker == '\u0000') scrollUp(value(0, 1))
            'T' -> if (marker == '\u0000' && parameters.size <= 1) scrollDown(value(0, 1))
            'X' -> eraseCharacters(value(0, 1))
            'Z' -> tabBackward(value(0, 1))
            'b' -> {
                val count = value(0, 1).coerceAtMost(rows * cols)
                repeat(count) { draw(lastCodePoint) }
            }
            'c' -> when (marker) {
                '\u0000' -> if (raw(0) == 0) reply("\u001b[?62;22c")
                '>' -> reply("\u001b[>41;320;0c")
            }
            'd' -> {
                pendingWrap = false
                setCursor(value(0, 1) - 1, cursorCol)
            }
            'g' -> when (raw(0)) {
                0 -> tabStops[cursorCol] = false
                3 -> tabStops.fill(false)
            }
            'h' -> parameters.forEach { setMode(it.firstOrNull() ?: 0, marker == '?', true) }
            'l' -> parameters.forEach { setMode(it.firstOrNull() ?: 0, marker == '?', false) }
            'm' -> if (marker == '\u0000') selectGraphicRendition(parameters)
            'n' -> if (marker == '\u0000' || marker == '?') {
                when (raw(0)) {
                    5 -> reply("\u001b[0n")
                    6 -> {
                        val row = if (originMode) cursorRow - marginTop + 1 else cursorRow + 1
                        reply("\u001b[${if (marker != '\u0000') "?" else ""}$row;${cursorCol + 1}R")
                    }
                }
            }
            'r' -> if (marker == '\u0000') setMargins(value(0, 1) - 1, raw(1), rows)
            's' -> if (marker == '\u0000') saveCursor()
            'u' -> if (marker == '\u0000') restoreCursor()
            't' -> if (raw(0) == 18) reply("\u001b[8;$rows;${cols}t")
        }
    }

    private fun setMargins(top: Int, bottomRaw: Int, height: Int) {
        val bottom = (if (bottomRaw <= 0) height else bottomRaw).coerceAtMost(height) - 1
        if (top < bottom) {
            marginTop = top
            marginBottom = bottom
            setCursor(0, 0)
        }
    }

    private fun eraseCharacters(count: Int) {
        pendingWrap = false
        val line = currentLine()
        val end = (cursorCol + count).coerceAtMost(cols)
        clearWideAt(line, cursorCol)
        clearWideAt(line, end - 1)
        line.clear(cursorCol, end, emptyStyle())
    }

    private fun eraseInLine(mode: Int) {
        pendingWrap = false
        val line = currentLine()
        val blank = emptyStyle()
        when (mode) {
            0 -> {
                clearWideAt(line, cursorCol)
                line.clear(cursorCol, cols, blank)
                line.wrapped = false
            }
            1 -> {
                clearWideAt(line, cursorCol)
                line.clear(0, cursorCol + 1, blank)
            }
            2 -> {
                line.clear(0, cols, blank)
                line.wrapped = false
            }
        }
    }

    private fun eraseInDisplay(mode: Int) {
        pendingWrap = false
        val blank = emptyStyle()
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (row in cursorRow + 1 until rows) {
                    surface.lines[row].apply {
                        clear(0, size, blank)
                        wrapped = false
                    }
                }
            }
            1 -> {
                eraseInLine(1)
                for (row in 0 until cursorRow) {
                    surface.lines[row].apply {
                        clear(0, size, blank)
                        wrapped = false
                    }
                }
            }
            2 -> clearSurface(surface)
            3 -> if (surface.keepsHistory) scrollback.clear()
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in marginTop..marginBottom) return
        pendingWrap = false
        repeat(count.coerceAtMost(marginBottom - cursorRow + 1)) {
            surface.lines.removeAt(marginBottom)
            surface.lines.add(cursorRow, blankLine())
        }
        cursorCol = 0
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in marginTop..marginBottom) return
        pendingWrap = false
        repeat(count.coerceAtMost(marginBottom - cursorRow + 1)) {
            surface.lines.removeAt(cursorRow)
            surface.lines.add(marginBottom, blankLine())
        }
        cursorCol = 0
    }

    private fun setCursorStyle(parameter: Int) {
        cursorShape = when (parameter) {
            3, 4 -> CursorShape.UNDERLINE
            5, 6 -> CursorShape.BAR
            else -> CursorShape.BLOCK
        }
        cursorBlink = parameter == 0 || parameter == 1 || parameter == 3 || parameter == 5
    }

    private fun setMode(mode: Int, privateMode: Boolean, enabled: Boolean) {
        if (!privateMode) {
            when (mode) {
                4 -> insertMode = enabled
                20 -> newLineMode = enabled
            }
            return
        }
        when (mode) {
            1 -> applicationCursorKeys = enabled
            5 -> reverseVideo = enabled
            6 -> {
                originMode = enabled
                setCursor(0, 0)
            }
            7 -> autoWrap = enabled
            12 -> cursorBlink = enabled
            25 -> cursorVisible = enabled
            9, 1000 -> mouseMode = if (enabled) MouseMode.CLICK else MouseMode.NONE
            1002 -> mouseMode = if (enabled) MouseMode.DRAG else MouseMode.NONE
            1003 -> mouseMode = if (enabled) MouseMode.ANY else MouseMode.NONE
            1004 -> focusReporting = enabled
            1006 -> mouseSgr = enabled
            2004 -> bracketedPaste = enabled
            47 -> switchSurface(enabled)
            1047 -> {
                if (!enabled && isAltScreen) clearSurface(alternate)
                switchSurface(enabled)
            }
            1048 -> if (enabled) saveCursor() else restoreCursor()
            1049 -> when {
                enabled && !isAltScreen -> {
                    saveCursor()
                    switchSurface(true)
                    clearSurface(alternate)
                }
                !enabled && isAltScreen -> {
                    switchSurface(false)
                    restoreCursor()
                }
            }
        }
    }

    private fun reportMode(mode: Int, privateMode: Boolean) {
        val value: Boolean? = if (privateMode) when (mode) {
            1 -> applicationCursorKeys
            5 -> reverseVideo
            6 -> originMode
            7 -> autoWrap
            12 -> cursorBlink
            25 -> cursorVisible
            1000 -> mouseMode == MouseMode.CLICK
            1002 -> mouseMode == MouseMode.DRAG
            1003 -> mouseMode == MouseMode.ANY
            1004 -> focusReporting
            1006 -> mouseSgr
            1049, 1047, 47 -> isAltScreen
            2004 -> bracketedPaste
            else -> null
        } else when (mode) {
            4 -> insertMode
            20 -> newLineMode
            else -> null
        }
        val state = when (value) {
            null -> 0
            true -> 1
            false -> 2
        }
        reply("\u001b[${if (privateMode) "?" else ""}$mode;$state\$y")
    }

    private fun selectGraphicRendition(groups: List<IntArray>) {
        if (groups.isEmpty()) {
            style = CellStyle.NORMAL
            return
        }
        var foreground = CellStyle.fg(style)
        var background = CellStyle.bg(style)
        var attributes = CellStyle.attrs(style)
        var index = 0
        while (index < groups.size) {
            val group = groups[index]
            val code = group.firstOrNull()?.coerceAtLeast(0) ?: 0
            when (code) {
                0 -> {
                    foreground = CellStyle.DEFAULT_FG
                    background = CellStyle.DEFAULT_BG
                    attributes = 0
                }
                1 -> attributes = attributes or CellStyle.BOLD
                2 -> attributes = attributes or CellStyle.DIM
                3 -> attributes = attributes or CellStyle.ITALIC
                4 -> attributes = if (group.getOrNull(1) == 0) {
                    attributes and CellStyle.UNDERLINE.inv()
                } else {
                    attributes or CellStyle.UNDERLINE
                }
                5, 6 -> attributes = attributes or CellStyle.BLINK
                7 -> attributes = attributes or CellStyle.INVERSE
                8 -> attributes = attributes or CellStyle.INVISIBLE
                9 -> attributes = attributes or CellStyle.STRIKE
                21 -> attributes = attributes or CellStyle.UNDERLINE
                22 -> attributes = attributes and (CellStyle.BOLD or CellStyle.DIM).inv()
                23 -> attributes = attributes and CellStyle.ITALIC.inv()
                24 -> attributes = attributes and CellStyle.UNDERLINE.inv()
                25 -> attributes = attributes and CellStyle.BLINK.inv()
                27 -> attributes = attributes and CellStyle.INVERSE.inv()
                28 -> attributes = attributes and CellStyle.INVISIBLE.inv()
                29 -> attributes = attributes and CellStyle.STRIKE.inv()
                in 30..37 -> foreground = code - 30
                38 -> {
                    val (color, consumed) = extendedColour(groups, index, group)
                    if (color != null) foreground = color
                    index += consumed
                }
                39 -> foreground = CellStyle.DEFAULT_FG
                in 40..47 -> background = code - 40
                48 -> {
                    val (color, consumed) = extendedColour(groups, index, group)
                    if (color != null) background = color
                    index += consumed
                }
                49 -> background = CellStyle.DEFAULT_BG
                58 -> {
                    // Underline colour is parsed and consumed, but the renderer has no slot for it.
                    val (_, consumed) = extendedColour(groups, index, group)
                    index += consumed
                }
                in 90..97 -> foreground = code - 90 + 8
                in 100..107 -> background = code - 100 + 8
            }
            index++
        }
        style = CellStyle.encode(foreground, background, attributes)
    }

    /** Returns the colour and how many following `;` groups it consumed. */
    private fun extendedColour(
        groups: List<IntArray>,
        index: Int,
        group: IntArray,
    ): Pair<Int?, Int> {
        if (group.size > 1) {
            val mode = group[1].coerceAtLeast(0)
            val color = when (mode) {
                5 -> group.getOrNull(2)?.takeIf { it in 0..255 }
                2 -> {
                    val rgb = when {
                        group.size >= 6 -> group.copyOfRange(3, 6)
                        group.size >= 5 -> group.copyOfRange(2, 5)
                        else -> null
                    }
                    rgb?.let {
                        CellStyle.rgb(
                            it[0].coerceIn(0, 255),
                            it[1].coerceIn(0, 255),
                            it[2].coerceIn(0, 255),
                        )
                    }
                }
                else -> null
            }
            return color to 0
        }

        return when (groups.getOrNull(index + 1)?.firstOrNull()) {
            5 -> {
                val color = groups.getOrNull(index + 2)?.firstOrNull()?.takeIf { it in 0..255 }
                color to 2
            }
            2 -> {
                val red = groups.getOrNull(index + 2)?.firstOrNull()?.coerceIn(0, 255) ?: 0
                val green = groups.getOrNull(index + 3)?.firstOrNull()?.coerceIn(0, 255) ?: 0
                val blue = groups.getOrNull(index + 4)?.firstOrNull()?.coerceIn(0, 255) ?: 0
                CellStyle.rgb(red, green, blue) to 4
            }
            else -> null to 0
        }
    }

    private fun applyOperatingSystemCommand(text: String) {
        val separator = text.indexOf(';')
        val code = (if (separator < 0) text else text.substring(0, separator)).toIntOrNull() ?: return
        val value = if (separator < 0) "" else text.substring(separator + 1)
        when (code) {
            0, 2 -> title = value.take(256)
            7 -> workingDirectory = value.removePrefix("file://").let { rest ->
                val slash = rest.indexOf('/')
                if (slash >= 0) percentDecode(rest.substring(slash)) else null
            }
            10, 11 -> if (value == "?") {
                val rgb = if (code == 10) defaultForegroundRgb else defaultBackgroundRgb
                fun channel(shift: Int): String {
                    val byte = (rgb shr shift) and 0xFF
                    return Integer.toHexString(byte).padStart(2, '0').repeat(2)
                }
                reply("\u001b]$code;rgb:${channel(16)}/${channel(8)}/${channel(0)}\u001b\\")
            }
        }
    }

    private fun reply(text: String) = onReply(text.toByteArray(Charsets.UTF_8))

    private fun softReset() {
        cursorVisible = true
        originMode = false
        autoWrap = true
        insertMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        marginTop = 0
        marginBottom = rows - 1
        style = CellStyle.NORMAL
        charsets = charArrayOf('B', 'B', 'B', 'B')
        gl = 0
        pendingWrap = false
        surface.saved = null
    }

    private fun fullReset() {
        softReset()
        switchSurface(false)
        main.saved = null
        alternate.saved = null
        clearSurface(main)
        clearSurface(alternate)
        cursorRow = 0
        cursorCol = 0
        reverseVideo = false
        bracketedPaste = false
        mouseMode = MouseMode.NONE
        mouseSgr = false
        focusReporting = false
        newLineMode = false
        cursorShape = CursorShape.BLOCK
        cursorBlink = false
        tabStops = defaultTabs(cols)
        title = ""
    }

    // ---------------------------------------------------------------- resizing

    /**
     * Changes the grid size. Rows that no longer fit move into scrollback
     * (blank rows below the cursor go first); growing pulls them back, so a
     * rotation that shrinks and grows again keeps the output in place.
     */
    fun resize(newRows: Int, newCols: Int) {
        val height = newRows.coerceAtLeast(1)
        val width = newCols.coerceAtLeast(1)
        if (height == rows && width == cols) return
        val widthChanged = width != cols

        rows = height
        cols = width

        // History is outside the two surfaces but is rendered with the same
        // grid width. Re-flow every stored row so a later scrollback read never
        // has to deal with a row of the previous geometry.
        if (widthChanged) {
            for (row in scrollback) row.resize(width, CellStyle.NORMAL)
        }
        cursorRow = fitSurface(surface, cursorRow)
        val other = if (surface === main) alternate else main
        val otherRow = fitSurface(other, other.saved?.row ?: lastUsedRow(other))
        other.saved?.let {
            it.row = otherRow
            it.col = it.col.coerceAtMost(width - 1)
        }
        cursorCol = cursorCol.coerceIn(0, width - 1)
        pendingWrap = false
        marginTop = 0
        marginBottom = height - 1
        tabStops = defaultTabs(width)
        revision++
    }

    private fun lastUsedRow(target: Surface): Int {
        for (index in target.lines.indices.reversed()) {
            if (!target.lines[index].isBlank()) return index
        }
        return 0
    }

    /** Fits [target] to the current grid and returns the row the cursor lands on. */
    private fun fitSurface(target: Surface, cursor: Int): Int {
        target.lines.forEach { it.resize(cols, CellStyle.NORMAL) }

        var row = cursor
        var excess = target.lines.size - rows
        if (excess > 0) {
            var last = target.lines.lastIndex
            while (excess > 0 && last > row && target.lines[last].isBlank()) {
                target.lines.removeAt(last)
                last--
                excess--
            }
            while (excess > 0 && target.lines.isNotEmpty()) {
                val dropped = target.lines.removeAt(0)
                if (target.keepsHistory) pushHistory(dropped)
                row--
                excess--
            }
        } else if (excess < 0) {
            var needed = -excess
            if (target.keepsHistory && target === surface) {
                while (needed > 0 && scrollback.isNotEmpty()) {
                    val restored = scrollback.removeLast()
                    restored.resize(cols, CellStyle.NORMAL)
                    target.lines.add(0, restored)
                    row++
                    needed--
                }
            }
            repeat(needed) { target.lines.add(TerminalRow(cols, CellStyle.NORMAL)) }
        }

        while (target.lines.size < rows) target.lines.add(TerminalRow(cols, CellStyle.NORMAL))
        while (target.lines.size > rows) target.lines.removeAt(target.lines.lastIndex)
        return row.coerceIn(0, rows - 1)
    }

    // ---------------------------------------------------------------- mouse

    /**
     * A mouse report for a click or wheel step, or null when the program did
     * not ask for mouse input. [button] is 0..2 for buttons and 64/65 for the
     * wheel.
     */
    fun mouseReport(button: Int, column: Int, row: Int, pressed: Boolean): ByteArray? {
        if (mouseMode == MouseMode.NONE) return null
        val x = column.coerceIn(0, cols - 1) + 1
        val y = row.coerceIn(0, rows - 1) + 1
        if (mouseSgr) return "\u001b[<$button;$x;$y${if (pressed) 'M' else 'm'}".toByteArray(Charsets.UTF_8)
        if (x > 223 || y > 223) return null
        val encodedButton = if (pressed || button >= 64) button else 3
        return byteArrayOf(
            0x1B,
            '['.code.toByte(),
            'M'.code.toByte(),
            (32 + encodedButton).toByte(),
            (32 + x).toByte(),
            (32 + y).toByte(),
        )
    }

    companion object {
        /** Glyph value of the right half of a double-width character. */
        const val WIDE_TAIL = -1

        /** DEC special graphics for 0x5F..0x7E, the line-drawing set. */
        private val DEC_GRAPHICS = intArrayOf(
            0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )

        private fun defaultTabs(width: Int): BooleanArray =
            BooleanArray(width) { it > 0 && it % 8 == 0 }

        private fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val out = ByteArrayOutputStream(text.length)
            var index = 0
            while (index < text.length) {
                val hex = if (text[index] == '%' && index + 2 < text.length) {
                    val high = Character.digit(text[index + 1], 16)
                    val low = Character.digit(text[index + 2], 16)
                    if (high >= 0 && low >= 0) (high shl 4) or low else -1
                } else {
                    -1
                }
                if (hex >= 0) {
                    out.write(hex)
                    index += 3
                } else {
                    out.write(text[index].toString().toByteArray(Charsets.UTF_8))
                    index++
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}

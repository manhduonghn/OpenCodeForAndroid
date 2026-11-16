package com.opencode.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    private val escape = "\u001b"

    private fun emulator(
        rows: Int = 5,
        cols: Int = 10,
        history: Int = 100,
    ): Pair<TerminalEmulator, MutableList<String>> {
        val replies = mutableListOf<String>()
        val emulator = TerminalEmulator(rows, cols, history)
        emulator.onReply = { replies += String(it, Charsets.UTF_8) }
        return emulator to replies
    }

    private fun TerminalEmulator.line(row: Int): String = lineAt(row)!!.text()

    private fun TerminalEmulator.styleAt(row: Int, col: Int): Long = lineAt(row)!!.styles[col]

    private fun TerminalEmulator.glyphAt(row: Int, col: Int): Int = lineAt(row)!!.glyphs[col]

    // ---------------------------------------------------------------- printing

    @Test
    fun printsTextAndHandlesCarriageReturnAndLineFeed() {
        val (terminal, _) = emulator()
        terminal.append("hello\r\nworld")
        assertEquals("hello", terminal.line(0))
        assertEquals("world", terminal.line(1))
        assertEquals(1, terminal.cursorRow)
        assertEquals(5, terminal.cursorCol)
    }

    @Test
    fun wrapsAtTheRightMarginOnlyWhenTheNextCharacterArrives() {
        val (terminal, _) = emulator(cols = 5)
        terminal.append("abcde")
        assertEquals(0, terminal.cursorRow)
        assertEquals(4, terminal.cursorCol)
        terminal.append("f")
        assertEquals("abcde", terminal.line(0))
        assertEquals("f", terminal.line(1))
        assertTrue(terminal.lineAt(0)!!.wrapped)
    }

    @Test
    fun disablingAutowrapOverwritesTheLastColumn() {
        val (terminal, _) = emulator(cols = 5)
        terminal.append("$escape[?7labcdefg")
        assertEquals("abcdg", terminal.line(0))
        assertEquals(0, terminal.cursorRow)
    }

    @Test
    fun cursorPositioningAndRelativeMoves() {
        val (terminal, _) = emulator()
        terminal.append("$escape[3;4HX")
        assertEquals("   X", terminal.line(2))
        terminal.append("$escape[2A$escape[2DY")
        assertEquals("  Y", terminal.line(0))
        terminal.append("$escape[10B$escape[99CZ")
        assertEquals(4, terminal.cursorRow)
        assertEquals("         Z", terminal.line(4))
        terminal.append("$escape[1G$escape[3dQ")
        assertEquals("Q  X", terminal.line(2))
    }

    @Test
    fun eraseInLineAndDisplay() {
        val (terminal, _) = emulator()
        terminal.append("aaaaa\r\nbbbbb\r\nccccc")
        terminal.append("$escape[2;3H$escape[K")
        assertEquals("bb", terminal.line(1))
        terminal.append("$escape[1K")
        assertEquals("", terminal.line(1))
        terminal.append("$escape[J")
        assertEquals("aaaaa", terminal.line(0))
        assertEquals("", terminal.line(2))
        terminal.append("$escape[2J")
        assertEquals("", terminal.screenText().trim())
    }

    @Test
    fun eraseUsesTheCurrentBackgroundColour() {
        val (terminal, _) = emulator()
        terminal.append("$escape[44m$escape[2K")
        assertEquals(4, CellStyle.bg(terminal.styleAt(0, 9)))
    }

    @Test
    fun screenEraseModesDoNotClearHistoryAccidentally() {
        val (terminal, _) = emulator(rows = 2)
        terminal.append("a\r\nb\r\nc")
        assertTrue(terminal.historySize > 0)
        val before = terminal.historySize
        terminal.append("$escape[2J")
        assertEquals(before, terminal.historySize)
        terminal.append("$escape[3J")
        assertEquals(0, terminal.historySize)
    }

    // ---------------------------------------------------------------- styles

    @Test
    fun basicAndBrightSgrColours() {
        val (terminal, _) = emulator()
        terminal.append("$escape[1;31mA$escape[0;94;42mB$escape[39;49mC")
        val first = terminal.styleAt(0, 0)
        assertEquals(1, CellStyle.fg(first))
        assertTrue(CellStyle.attrs(first) and CellStyle.BOLD != 0)
        val second = terminal.styleAt(0, 1)
        assertEquals(12, CellStyle.fg(second))
        assertEquals(2, CellStyle.bg(second))
        assertEquals(0, CellStyle.attrs(second))
        val third = terminal.styleAt(0, 2)
        assertEquals(CellStyle.DEFAULT_FG, CellStyle.fg(third))
        assertEquals(CellStyle.DEFAULT_BG, CellStyle.bg(third))
    }

    @Test
    fun indexedAndTrueColourInBothSyntaxes() {
        val (terminal, _) = emulator()
        terminal.append("$escape[38;5;208mA$escape[48;2;10;20;30mB")
        terminal.append("$escape[38:2::1:2:3mC$escape[38:5:99mD")
        assertEquals(208, CellStyle.fg(terminal.styleAt(0, 0)))
        val background = CellStyle.bg(terminal.styleAt(0, 1))
        assertTrue(CellStyle.isTrueColor(background))
        assertEquals(0x0A141E, background and 0xFFFFFF)
        val colonColour = CellStyle.fg(terminal.styleAt(0, 2))
        assertEquals(0x010203, colonColour and 0xFFFFFF)
        assertEquals(99, CellStyle.fg(terminal.styleAt(0, 3)))
    }

    @Test
    fun underlineColourIsConsumedWithoutChangingTheTextColour() {
        val (terminal, _) = emulator()
        terminal.append("$escape[58;5;196;31mA")
        assertEquals(1, CellStyle.fg(terminal.styleAt(0, 0)))
    }

    @Test
    fun attributesSetAndReset() {
        val (terminal, _) = emulator()
        terminal.append("$escape[3;4;7;9mA$escape[23;24;27;29mB")
        val attributes = CellStyle.attrs(terminal.styleAt(0, 0))
        assertEquals(
            CellStyle.ITALIC or CellStyle.UNDERLINE or CellStyle.INVERSE or CellStyle.STRIKE,
            attributes,
        )
        assertEquals(0, CellStyle.attrs(terminal.styleAt(0, 1)))
    }

    // ---------------------------------------------------------------- screens

    @Test
    fun alternateScreenKeepsTheMainScreenAndCursor() {
        val (terminal, _) = emulator()
        terminal.append("prompt$ ")
        terminal.append("$escape[?1049h")
        assertTrue(terminal.isAltScreen)
        assertEquals("", terminal.line(0))
        terminal.append("$escape[Hfull screen app")
        terminal.append("$escape[?1049l")
        assertFalse(terminal.isAltScreen)
        assertEquals("prompt$", terminal.line(0))
        assertEquals(8, terminal.cursorCol)
    }

    @Test
    fun scrollRegionScrollsOnlyInsideTheMargins() {
        val (terminal, _) = emulator(rows = 5)
        terminal.append("top\r\n1\r\n2\r\n3\r\nbottom")
        terminal.append("$escape[2;4r")
        // DECSTBM homes the cursor; move to the bottom margin and feed a line.
        terminal.append("$escape[4;1H\n")
        assertEquals("top", terminal.line(0))
        assertEquals("2", terminal.line(1))
        assertEquals("3", terminal.line(2))
        assertEquals("", terminal.line(3))
        assertEquals("bottom", terminal.line(4))
        assertEquals(0, terminal.historySize)
    }

    @Test
    fun reverseIndexAtTheTopMarginScrollsDown() {
        val (terminal, _) = emulator(rows = 4)
        terminal.append("a\r\nb\r\nc\r\nd")
        terminal.append("$escape[H${escape}M")
        assertEquals("", terminal.line(0))
        assertEquals("a", terminal.line(1))
        assertEquals("c", terminal.line(3))
    }

    @Test
    fun insertAndDeleteLinesAndCharacters() {
        val (terminal, _) = emulator(rows = 4)
        terminal.append("a\r\nb\r\nc\r\nd")
        terminal.append("$escape[2;1H$escape[L")
        assertEquals(listOf("a", "", "b", "c"), (0..3).map { terminal.line(it) })
        terminal.append("$escape[2M")
        assertEquals(listOf("a", "c", "", ""), (0..3).map { terminal.line(it) })
        terminal.append("$escape[1;1Habcdef$escape[1;2H$escape[2P")
        assertEquals("adef", terminal.line(0))
        terminal.append("$escape[2@")
        assertEquals("a  def", terminal.line(0))
        terminal.append("$escape[3X")
        assertEquals("a   ef", terminal.line(0))
    }

    @Test
    fun linesScrolledOffTheTopGoToHistory() {
        val (terminal, _) = emulator(rows = 3)
        terminal.append("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(2, terminal.historySize)
        assertEquals("1", terminal.lineAt(-2)!!.text())
        assertEquals("2", terminal.lineAt(-1)!!.text())
        assertEquals("3", terminal.line(0))
        terminal.append("$escape[3J")
        assertEquals(0, terminal.historySize)
    }

    @Test
    fun historyIsCapped() {
        val (terminal, _) = emulator(rows = 2, history = 3)
        repeat(10) { terminal.append("line $it\r\n") }
        assertEquals(3, terminal.historySize)
    }

    // ---------------------------------------------------------------- replies

    @Test
    fun answersCursorPositionAndDeviceAttributes() {
        val (terminal, replies) = emulator()
        terminal.append("$escape[3;5H$escape[6n$escape[c$escape[>c$escape[5n")
        assertEquals(
            listOf("$escape[3;5R", "$escape[?62;22c", "$escape[>41;320;0c", "$escape[0n"),
            replies,
        )
    }

    @Test
    fun answersBackgroundColourQuery() {
        val (terminal, replies) = emulator()
        terminal.defaultBackgroundRgb = 0x102030
        terminal.append("$escape]11;?\u0007")
        assertEquals("$escape]11;rgb:1010/2020/3030$escape\\", replies.single())
    }

    @Test
    fun reportsWindowSizeInCells() {
        val (terminal, replies) = emulator(rows = 24, cols = 80)
        terminal.append("$escape[18t")
        assertEquals("$escape[8;24;80t", replies.single())
    }

    // ---------------------------------------------------------------- unicode

    @Test
    fun decodesUtf8AcrossChunkBoundaries() {
        val (terminal, _) = emulator()
        val bytes = "héllo €".toByteArray(Charsets.UTF_8)
        bytes.forEach { terminal.append(byteArrayOf(it), 0, 1) }
        assertEquals("héllo €", terminal.line(0))
    }

    @Test
    fun byteChunksProduceTheSameScreenAsOneWrite() {
        val text = "héllo 中文 😀\r\nnext €"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val (whole, _) = emulator(rows = 6, cols = 20)
        whole.append(bytes, 0, bytes.size)
        val (chunked, _) = emulator(rows = 6, cols = 20)
        for (byte in bytes) chunked.append(byteArrayOf(byte), 0, 1)
        assertEquals(whole.screenText(), chunked.screenText())
        assertEquals(whole.cursorRow, chunked.cursorRow)
        assertEquals(whole.cursorCol, chunked.cursorCol)
    }

    @Test
    fun astralCharactersStayOneGlyphOnTheBytePath() {
        val (terminal, _) = emulator()
        val bytes = "a😀b".toByteArray(Charsets.UTF_8)
        terminal.append(bytes, 0, bytes.size)
        assertEquals("a😀b", terminal.line(0))
        assertEquals(0x1F600, terminal.glyphAt(0, 1))
        assertEquals(TerminalEmulator.WIDE_TAIL, terminal.glyphAt(0, 2))
        assertEquals(4, terminal.cursorCol)
    }

    @Test
    fun wideCharactersTakeTwoCells() {
        val (terminal, _) = emulator()
        terminal.append("a中b")
        assertEquals(4, terminal.cursorCol)
        assertEquals(TerminalEmulator.WIDE_TAIL, terminal.glyphAt(0, 2))
        assertEquals("a中b", terminal.line(0))
        // Overwriting the right half clears the whole wide character.
        terminal.append("$escape[1;3HX")
        assertEquals("a Xb", terminal.line(0))
    }

    @Test
    fun wideCharacterAtTheLastColumnWrapsFirst() {
        val (terminal, _) = emulator(cols = 5)
        terminal.append("abcd中")
        assertEquals("abcd", terminal.line(0))
        assertEquals("中", terminal.line(1))
    }

    @Test
    fun combiningMarksDoNotConsumeACell() {
        val (terminal, _) = emulator()
        terminal.append("e\u0301x")
        assertEquals(2, terminal.cursorCol)
    }

    @Test
    fun decLineDrawingCharset() {
        val (terminal, _) = emulator()
        terminal.append("$escape(0lqk$escape(Bq")
        assertEquals("┌─┐q", terminal.line(0))
    }

    @Test
    fun shiftOutUsesTheG1Charset() {
        val (terminal, _) = emulator()
        terminal.append("$escape)0\u000Eqx\u000Fq")
        assertEquals("─│q", terminal.line(0))
    }

    // ---------------------------------------------------------------- controls

    @Test
    fun tabsStopEveryEightColumns() {
        val (terminal, _) = emulator(cols = 20)
        terminal.append("a\tb\tc")
        assertEquals("a       b       c", terminal.line(0))
        terminal.append("$escape[Z")
        assertEquals(16, terminal.cursorCol)
        terminal.append("$escape[2Z")
        assertEquals(0, terminal.cursorCol)
    }

    @Test
    fun saveAndRestoreCursorKeepsStyle() {
        val (terminal, _) = emulator()
        terminal.append("$escape[2;3H$escape[31m${escape}7$escape[H$escape[0mX${escape}8Y")
        assertEquals("  Y", terminal.line(1))
        assertEquals(1, CellStyle.fg(terminal.styleAt(1, 2)))
    }

    @Test
    fun privateModesAreTracked() {
        val (terminal, _) = emulator()
        terminal.append("$escape[?1h$escape[?2004h$escape[?25l$escape[?1000h$escape[?1006h")
        assertTrue(terminal.applicationCursorKeys)
        assertTrue(terminal.bracketedPaste)
        assertFalse(terminal.cursorVisible)
        assertEquals(MouseMode.CLICK, terminal.mouseMode)
        assertTrue(terminal.mouseSgr)
        terminal.append("$escape[?1l$escape[?2004l$escape[?25h$escape[?1000l")
        assertFalse(terminal.applicationCursorKeys)
        assertFalse(terminal.bracketedPaste)
        assertTrue(terminal.cursorVisible)
        assertEquals(MouseMode.NONE, terminal.mouseMode)
    }

    @Test
    fun reportsDecPrivateModeState() {
        val (terminal, replies) = emulator()
        terminal.append("$escape[?2004h$escape[?2004\$p$escape[?1\$p$escape[?9999\$p")
        assertEquals(
            listOf("$escape[?2004;1\$y", "$escape[?1;2\$y", "$escape[?9999;0\$y"),
            replies,
        )
    }

    @Test
    fun cursorShapeFromDecscusr() {
        val (terminal, _) = emulator()
        terminal.append("$escape[6 q")
        assertEquals(CursorShape.BAR, terminal.cursorShape)
        terminal.append("$escape[4 q")
        assertEquals(CursorShape.UNDERLINE, terminal.cursorShape)
        terminal.append("$escape[2 q")
        assertEquals(CursorShape.BLOCK, terminal.cursorShape)
    }

    // ---------------------------------------------------------------- osc

    @Test
    fun oscSetsTitleAndWorkingDirectory() {
        val (terminal, _) = emulator()
        terminal.append("$escape]0;vim notes.txt\u0007")
        assertEquals("vim notes.txt", terminal.title)
        terminal.append("$escape]7;file://localhost/data/my%20dir$escape\\")
        assertEquals("/data/my dir", terminal.workingDirectory)
        assertEquals("", terminal.line(0))
    }

    @Test
    fun incompleteOscDoesNotLeakIntoTheScreen() {
        val (terminal, _) = emulator()
        terminal.append("$escape]0;not finished")
        assertEquals("", terminal.line(0))
        assertEquals("", terminal.title)
        terminal.append("\u0007")
        assertEquals("not finished", terminal.title)
        assertEquals("", terminal.line(0))
    }

    @Test
    fun unknownSequencesAndStringsAreSwallowed() {
        val (terminal, _) = emulator()
        terminal.append("${escape}P1\$r0m$escape\\$escape[?1;2;3zA")
        terminal.append("$escape]52;c;aGVsbG8=\u0007B")
        assertEquals("AB", terminal.line(0))
    }

    @Test
    fun repeatsTheLastCharacter() {
        val (terminal, _) = emulator()
        terminal.append("x$escape[4b")
        assertEquals("xxxxx", terminal.line(0))
    }

    @Test
    fun controlCharactersInsideCsiStillRun() {
        val (terminal, _) = emulator()
        terminal.append("ab$escape[\b2CX")
        // Backspace runs inside the sequence, then CUF moves right by two.
        assertEquals("ab X", terminal.line(0))
        assertEquals(4, terminal.cursorCol)
    }

    // ---------------------------------------------------------------- resize

    @Test
    fun resizeNarrowerTruncatesAndKeepsTheCursorInside() {
        val (terminal, _) = emulator(rows = 3, cols = 10)
        terminal.append("0123456789")
        terminal.resize(3, 4)
        assertEquals("0123", terminal.line(0))
        assertEquals(3, terminal.cursorCol)
        assertEquals(4, terminal.cols)
    }

    @Test
    fun resizeShorterMovesRowsToHistoryAndBack() {
        val (terminal, _) = emulator(rows = 5, cols = 10)
        terminal.append("a\r\nb\r\nc\r\nd\r\ne")
        terminal.resize(3, 10)
        assertEquals(listOf("c", "d", "e"), (0..2).map { terminal.line(it) })
        assertEquals(2, terminal.cursorRow)
        assertEquals(2, terminal.historySize)
        terminal.resize(5, 10)
        assertEquals(listOf("a", "b", "c", "d", "e"), (0..4).map { terminal.line(it) })
        assertEquals(4, terminal.cursorRow)
        assertEquals(0, terminal.historySize)
    }

    @Test
    fun resizeShorterDropsBlankRowsBelowTheCursorFirst() {
        val (terminal, _) = emulator(rows = 5, cols = 10)
        terminal.append("a\r\nb")
        terminal.resize(3, 10)
        assertEquals(listOf("a", "b", ""), (0..2).map { terminal.line(it) })
        assertEquals(0, terminal.historySize)
        assertEquals(1, terminal.cursorRow)
    }

    @Test
    fun resizeWhileOnTheAlternateScreenRestoresTheMainCursor() {
        val (terminal, _) = emulator(rows = 5, cols = 10)
        terminal.append("a\r\nb\r\nc\r\nd\r\n$ ")
        terminal.append("$escape[?1049h")
        terminal.resize(3, 10)
        terminal.append("$escape[?1049l")
        assertFalse(terminal.isAltScreen)
        assertTrue(terminal.cursorRow in 0..2)
        assertEquals("$", terminal.line(terminal.cursorRow))
    }

    @Test
    fun everyRowMatchesTheGridAfterRepeatedResizes() {
        val (terminal, _) = emulator(rows = 6, cols = 12)
        repeat(40) { terminal.append("row $it with some text\r\n") }
        for ((height, width) in listOf(3 to 20, 8 to 5, 4 to 12, 2 to 7)) {
            terminal.resize(height, width)
            assertEquals(height, terminal.rows)
            assertEquals(width, terminal.cols)
            for (row in 0 until terminal.rows) {
                assertEquals(width, terminal.lineAt(row)!!.size)
            }
            for (index in -terminal.historySize until 0) {
                assertEquals(width, terminal.lineAt(index)!!.size)
            }
        }
    }

    @Test
    fun originModeMakesPositionsRelativeToTheScrollRegion() {
        val (terminal, replies) = emulator(rows = 6)
        terminal.append("$escape[3;5r$escape[?6h$escape[1;1HX$escape[6n")
        assertEquals("X", terminal.line(2))
        assertEquals("$escape[1;2R", replies.single())
        terminal.append("$escape[99;1HY")
        assertEquals("Y", terminal.line(4))
    }

    @Test
    fun backspaceAfterPendingWrapStaysOnTheLine() {
        val (terminal, _) = emulator(cols = 5)
        terminal.append("abcde\bX")
        assertEquals("abcXe", terminal.line(0))
        assertEquals(0, terminal.cursorRow)
    }

    @Test
    fun fullResetClearsTheScreenAndModes() {
        val (terminal, _) = emulator()
        terminal.append("text$escape[?1h$escape[31m${escape}c")
        assertEquals("", terminal.screenText().trim())
        assertFalse(terminal.applicationCursorKeys)
        assertEquals(0, terminal.cursorCol)
        terminal.append("Z")
        assertEquals(CellStyle.DEFAULT_FG, CellStyle.fg(terminal.styleAt(0, 0)))
    }

    // ---------------------------------------------------------------- mouse

    @Test
    fun mouseReportsInSgrAndLegacyEncodings() {
        val (terminal, _) = emulator(rows = 24, cols = 80)
        assertNull(terminal.mouseReport(0, 1, 1, true))
        terminal.append("$escape[?1000h$escape[?1006h")
        assertEquals("$escape[<0;3;4M", String(terminal.mouseReport(0, 2, 3, true)!!))
        assertEquals("$escape[<0;3;4m", String(terminal.mouseReport(0, 2, 3, false)!!))
        terminal.append("$escape[?1006l")
        val legacy = terminal.mouseReport(0, 2, 3, true)!!
        assertEquals(listOf(27, '['.code, 'M'.code, 32, 35, 36), legacy.map { it.toInt() and 0xFF })
    }

    // ---------------------------------------------------------------- text output

    @Test
    fun transcriptJoinsWrappedRows() {
        val (terminal, _) = emulator(rows = 3, cols = 4)
        terminal.append("abcdefgh\r\nnext")
        assertEquals("abcdefgh\nnext", terminal.transcriptText())
    }

    @Test
    fun selectionExtractsCellsFromOneRow() {
        val (terminal, _) = emulator(rows = 3, cols = 20)
        terminal.append("hello world")
        assertEquals("hello", terminal.textInRange(0, 0, 0, 4))
        assertEquals("world", terminal.textInRange(0, 6, 0, 10))
        assertEquals("", terminal.textInRange(0, 5, 0, 4))
    }

    @Test
    fun selectionFollowsReadingOrderAcrossRows() {
        val (terminal, _) = emulator(rows = 3, cols = 10)
        terminal.append("alpha\r\nbeta\r\ngamma")
        assertEquals("pha\nbeta\nga", terminal.textInRange(0, 2, 2, 1))
        assertEquals("", terminal.textInRange(2, 0, 1, 0))
    }

    @Test
    fun selectionReachesIntoScrollback() {
        val (terminal, _) = emulator(rows = 2, cols = 10, history = 10)
        terminal.append("one\r\ntwo\r\nthree")
        assertEquals("one", terminal.textInRange(-1, 0, -1, 2))
        assertEquals("two\nthree", terminal.textInRange(0, 0, 1, 4))
    }

    @Test
    fun selectionSkipsTheTailOfWideGlyphs() {
        val (terminal, _) = emulator(rows = 2, cols = 10)
        terminal.append("\u4f60\u597d")
        assertEquals("你好", terminal.textInRange(0, 0, 0, 3))
        assertEquals("好", terminal.textInRange(0, 2, 0, 2))
    }

    @Test
    fun selectionJoinsRowsWrappedAtTheMargin() {
        val (terminal, _) = emulator(rows = 3, cols = 4)
        terminal.append("abcdefgh\r\nnext")
        assertEquals("abcdefgh", terminal.textInRange(0, 0, 1, 3))
        assertEquals("cdefgh\nnext", terminal.textInRange(0, 2, 2, 3))
    }

    @Test
    fun wordBoundsStopAtWhitespace() {
        val (terminal, _) = emulator(rows = 2, cols = 20)
        terminal.append("git status --short")
        val row = terminal.lineAt(0)!!
        assertEquals(0..2, row.wordBounds(1))
        assertEquals(4..9, row.wordBounds(7))
        assertEquals(11..17, row.wordBounds(15))
        assertEquals(10..10, row.wordBounds(10))
    }

    @Test
    fun wordBoundsIncludeBothCellsOfAWideGlyph() {
        val (terminal, _) = emulator(rows = 2, cols = 10)
        terminal.append("你 ok")
        val row = terminal.lineAt(0)!!
        assertEquals(0..1, row.wordBounds(0))
        assertEquals(0..1, row.wordBounds(1))
        assertEquals(3..4, row.wordBounds(4))
    }

    @Test
    fun textBoundsCoverTheWrittenPartOfALine() {
        val (terminal, _) = emulator(rows = 2, cols = 10)
        terminal.append("  a b  ")
        val row = terminal.lineAt(0)!!
        assertEquals(0..4, row.textBounds())
        assertTrue(row.hasTextAt(2))
        assertFalse(row.hasTextAt(6))
        terminal.append("$escape[2J")
        assertNull(terminal.lineAt(0)!!.textBounds())
    }

    @Test
    fun wcwidthClassifiesCommonCharacters() {
        assertEquals(1, WcWidth.of('a'.code))
        assertEquals(2, WcWidth.of('中'.code))
        assertEquals(2, WcWidth.of(0x1F600))
        assertEquals(0, WcWidth.of(0x0301))
        assertEquals(1, WcWidth.of('─'.code))
        assertEquals(2, WcWidth.of(0xAC00))
    }
}

package com.opencode.android.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.opencode.android.R
import kotlin.math.abs
import kotlin.math.floor

/** Colours for one terminal theme, as ARGB ints. [ansi] holds the 16 base colours. */
data class TerminalColors(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val cursorText: Int,
    val ansi: IntArray,
) {
    /** The full 256-colour palette: the 16 theme colours, the 6x6x6 cube, then 24 greys. */
    val palette: IntArray = IntArray(256) { index ->
        when {
            index < 16 -> ansi[index]
            index < 232 -> {
                val cube = index - 16
                fun channel(value: Int) = if (value == 0) 0 else 55 + value * 40
                argb(channel(cube / 36), channel((cube / 6) % 6), channel(cube % 6))
            }
            else -> (8 + (index - 232) * 10).let { argb(it, it, it) }
        }
    }

    override fun equals(other: Any?): Boolean = other is TerminalColors &&
        background == other.background &&
        foreground == other.foreground &&
        cursor == other.cursor &&
        cursorText == other.cursorText &&
        ansi.contentEquals(other.ansi)

    override fun hashCode(): Int = (background * 31 + foreground) * 31 + ansi.contentHashCode()

    companion object {
        fun argb(red: Int, green: Int, blue: Int): Int =
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

/**
 * Draws a [TerminalSession] and turns touches, the soft keyboard and hardware
 * keys into input for it.
 *
 * The view never owns the shell: it attaches to a session, listens for repaints
 * and detaches without changing the process. Drawing happens under the
 * emulator lock because the reader thread appends output at the same time; the
 * lock is never held while writing to the PTY, so a full input buffer cannot
 * freeze the renderer.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    var session: TerminalSession? = null
        private set

    var colors: TerminalColors = TerminalColors(
        background = 0xFF1C1B1F.toInt(),
        foreground = 0xFFE6E1E5.toInt(),
        cursor = 0xFFD0BCFF.toInt(),
        cursorText = 0xFF381E72.toInt(),
        ansi = IntArray(16),
    )
        set(value) {
            if (field == value) return
            field = value
            session?.let { active ->
                synchronized(active.emulator) { applyQueryColors(active.emulator) }
            }
            invalidate()
        }

    /** Grid size in cells and pixels, reported whenever the view or the font size changes. */
    var onGridSize: ((rows: Int, cols: Int, widthPx: Int, heightPx: Int) -> Unit)? = null

    /** Modifiers latched on the extra-keys row; cleared through [onModifiersUsed] after one key. */
    var latchedModifiers: () -> KeyModifiers = { KeyModifiers.NONE }
    var onModifiersUsed: () -> Unit = {}
    var onFontSizeChange: ((Float) -> Unit)? = null
    var onPasteRequest: (() -> Unit)? = null
    var onCopyRequest: (() -> Unit)? = null

    /**
     * Long-press on the screen. It receives the text currently painted, including
     * scrollback when the user scrolled up; the menu keeps the action that copies
     * the whole transcript.
     */
    var onCopyScreenRequest: ((String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val baseTypeface: Typeface = Typeface.MONOSPACE
    private val boldTypeface: Typeface = if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(baseTypeface, 700, false)
    } else {
        Typeface.create(baseTypeface, Typeface.BOLD)
    }
    private val italicTypeface: Typeface = if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(baseTypeface, 400, true)
    } else {
        Typeface.create(baseTypeface, Typeface.ITALIC)
    }
    private val boldItalicTypeface: Typeface = if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(baseTypeface, 700, true)
    } else {
        Typeface.create(baseTypeface, Typeface.BOLD_ITALIC)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = baseTypeface }
    private val fillPaint = Paint()

    var fontSizeSp = 13f
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (abs(field - clamped) < 0.01f) return
            field = clamped
            measureGlyphs()
            reportGrid()
            invalidate()
        }

    private var cellWidth = 0f
    private var lineHeight = 0
    private var baselineOffset = 0
    private var gridRows = 0
    private var gridCols = 0
    private val padding = (4 * density).toInt()

    /** Rows scrolled back into history; 0 shows the live screen. */
    private var scrollOffset = 0
    private var seenHistoryPushes = 0L
    private var scrollRemainder = 0f

    /** True while this view is registered with [session]. */
    private var listening = false

    private val repaint: () -> Unit = { postInvalidateOnAnimation() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureGlyphs()
        contentDescription = context.getString(R.string.opencode_terminal_view_description)
    }

    fun attach(next: TerminalSession?) {
        if (session === next) return
        stopListening()
        session = next
        scrollOffset = 0
        if (next != null) {
            startListening(next)
            synchronized(next.emulator) {
                seenHistoryPushes = next.emulator.historyPushes
                applyQueryColors(next.emulator)
            }
            if (gridRows > 0) reportGrid()
        }
        invalidate()
    }

    private fun startListening(target: TerminalSession) {
        target.addListener(repaint)
        target.onBell = { post { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
        listening = true
    }

    private fun stopListening() {
        if (!listening) return
        session?.removeListener(repaint)
        session?.onBell = null
        listening = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val current = session
        if (current != null && !listening) {
            startListening(current)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        stopListening()
        super.onDetachedFromWindow()
    }

    private fun applyQueryColors(emulator: TerminalEmulator) {
        emulator.defaultForegroundRgb = colors.foreground and 0xFFFFFF
        emulator.defaultBackgroundRgb = colors.background and 0xFFFFFF
    }

    private fun measureGlyphs() {
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp,
            resources.displayMetrics,
        )
        textPaint.typeface = baseTypeface
        cellWidth = textPaint.measureText("M")
        val metrics = textPaint.fontMetricsInt
        lineHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1)
        baselineOffset = -metrics.ascent
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        reportGrid()
    }

    private fun reportGrid() {
        if (width <= 0 || height <= 0 || cellWidth <= 0f) return
        val columns = floor((width - 2 * padding) / cellWidth).toInt().coerceAtLeast(4)
        val lines = ((height - 2 * padding) / lineHeight).coerceAtLeast(2)
        gridRows = lines
        gridCols = columns
        onGridSize?.invoke(lines, columns, width, height)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val theme = colors
        val active = session
        if (active == null) {
            canvas.drawColor(theme.background)
            return
        }
        synchronized(active.emulator) {
            drawScreen(canvas, active, theme)
        }
    }

    private fun drawScreen(canvas: Canvas, active: TerminalSession, theme: TerminalColors) {
        val emulator = active.emulator
        val inverse = emulator.reverseVideo
        canvas.drawColor(if (inverse) theme.foreground else theme.background)

        // Keep the visible text still while output continues below the fold.
        if (scrollOffset > 0) {
            scrollOffset += (emulator.historyPushes - seenHistoryPushes).toInt()
        }
        seenHistoryPushes = emulator.historyPushes
        scrollOffset = scrollOffset.coerceIn(0, emulator.historySize)

        val showCursor = scrollOffset == 0 && emulator.cursorVisible && active.running
        for (line in 0 until emulator.rows) {
            val row = emulator.lineAt(line - scrollOffset) ?: continue
            val top = padding + line * lineHeight
            val cursor = if (showCursor && line == emulator.cursorRow) emulator.cursorCol else NO_CURSOR
            drawLine(canvas, row, top, emulator.cols, inverse, cursor, emulator.cursorShape)
        }
        if (scrollOffset > 0) drawScrollIndicator(canvas, emulator)
    }

    private fun resolveColor(index: Int, foreground: Boolean, inverse: Boolean): Int {
        val theme = colors
        return when {
            index == CellStyle.DEFAULT_FG -> if (inverse) theme.background else theme.foreground
            index == CellStyle.DEFAULT_BG -> if (inverse) theme.foreground else theme.background
            CellStyle.isTrueColor(index) -> (0xFF shl 24) or (index and 0xFFFFFF)
            index in 0..255 -> theme.palette[index]
            else -> if (foreground) theme.foreground else theme.background
        }
    }

    private val run = CharArray(1024)

    private fun drawLine(
        canvas: Canvas,
        row: TerminalRow,
        top: Int,
        columns: Int,
        inverse: Boolean,
        cursorColumn: Int,
        cursorShape: CursorShape,
    ) {
        val width = minOf(columns, row.size)
        val baseline = (top + baselineOffset).toFloat()
        var column = 0
        while (column < width) {
            val style = row.styles[column]
            val attributes = CellStyle.attrs(style)
            var foreground = resolveColor(CellStyle.fg(style), foreground = true, inverse = inverse)
            var background = resolveColor(CellStyle.bg(style), foreground = false, inverse = inverse)
            if (attributes and CellStyle.INVERSE != 0) {
                val swap = foreground
                foreground = background
                background = swap
            }
            if (attributes and CellStyle.DIM != 0) {
                foreground = (foreground and 0x00FFFFFF) or (0x99 shl 24)
            }

            // One paint run shares a style. The cursor cell is always its own run
            // because its background and text colour can differ from the cell style.
            var end = column + 1
            if (column != cursorColumn) {
                while (
                    end < width &&
                    end != cursorColumn &&
                    row.styles[end] == style &&
                    end - column < run.size
                ) {
                    end++
                }
            }

            var plainAscii = true
            for (index in column until end) {
                val glyph = row.glyphs[index]
                if (glyph > 0x7E || glyph == TerminalEmulator.WIDE_TAIL) {
                    plainAscii = false
                    break
                }
            }

            val left = padding + column * cellWidth
            val right = padding + end * cellWidth
            val themeBackground = if (inverse) colors.foreground else colors.background
            if (background != themeBackground) {
                fillPaint.color = background
                canvas.drawRect(left, top.toFloat(), right, (top + lineHeight).toFloat(), fillPaint)
            }

            val cursorInRun = cursorColumn in column until end
            if (cursorInRun) {
                drawCursor(canvas, top, cursorColumn, cursorShape)
                if (hasFocus() && cursorShape == CursorShape.BLOCK) {
                    foreground = colors.cursorText
                }
            }

            if (attributes and CellStyle.INVISIBLE == 0) {
                textPaint.color = foreground
                textPaint.typeface = typefaceFor(attributes)
                textPaint.isUnderlineText = attributes and CellStyle.UNDERLINE != 0
                textPaint.isStrikeThruText = attributes and CellStyle.STRIKE != 0
                if (plainAscii) {
                    drawAsciiRun(canvas, row, column, end, left, baseline)
                } else {
                    drawGlyphRun(canvas, row, column, end, baseline)
                }
                textPaint.isUnderlineText = false
                textPaint.isStrikeThruText = false
            }
            column = end
        }
    }

    private fun typefaceFor(attributes: Int): Typeface {
        val bold = attributes and CellStyle.BOLD != 0
        val italic = attributes and CellStyle.ITALIC != 0
        return when {
            bold && italic -> boldItalicTypeface
            bold -> boldTypeface
            italic -> italicTypeface
            else -> baseTypeface
        }
    }

    private fun drawAsciiRun(
        canvas: Canvas,
        row: TerminalRow,
        from: Int,
        to: Int,
        left: Float,
        baseline: Float,
    ) {
        var count = 0
        for (index in from until to) {
            val glyph = row.glyphs[index]
            run[count++] = if (glyph == 0) ' ' else glyph.toChar()
        }
        // Underlines and strike-throughs are meaningful on blank cells, text is not.
        if (textPaint.isUnderlineText || textPaint.isStrikeThruText || run.any { it != ' ' }) {
            canvas.drawText(run, 0, count, left, baseline, textPaint)
        }
    }

    private fun drawGlyphRun(
        canvas: Canvas,
        row: TerminalRow,
        from: Int,
        to: Int,
        baseline: Float,
    ) {
        for (index in from until to) {
            val glyph = row.glyphs[index]
            if (glyph == 0 || glyph == TerminalEmulator.WIDE_TAIL || glyph == ' '.code) continue
            val text = String(Character.toChars(glyph))
            val cells = if (index + 1 < row.size && row.glyphs[index + 1] == TerminalEmulator.WIDE_TAIL) 2 else 1
            val left = padding + index * cellWidth
            val room = cells * cellWidth
            val glyphWidth = textPaint.measureText(text)
            if (glyphWidth > room * 1.05f) {
                // A fallback font can be wider than the cells it owns. Squeeze it
                // rather than letting one glyph push the rest of the line aside.
                canvas.save()
                canvas.scale(room / glyphWidth, 1f, left, baseline)
                canvas.drawText(text, left, baseline, textPaint)
                canvas.restore()
            } else {
                canvas.drawText(text, left + (room - glyphWidth) / 2, baseline, textPaint)
            }
        }
    }

    private fun drawCursor(canvas: Canvas, top: Int, column: Int, shape: CursorShape) {
        val left = padding + column * cellWidth
        val right = left + cellWidth
        val bottom = (top + lineHeight).toFloat()
        fillPaint.color = colors.cursor
        when {
            !hasFocus() -> {
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = density
                canvas.drawRect(
                    left + density / 2,
                    top + density / 2,
                    right - density / 2,
                    bottom - density / 2,
                    fillPaint,
                )
                fillPaint.style = Paint.Style.FILL
            }
            shape == CursorShape.BLOCK -> canvas.drawRect(left, top.toFloat(), right, bottom, fillPaint)
            shape == CursorShape.UNDERLINE -> {
                canvas.drawRect(left, top + lineHeight - 2 * density, right, bottom, fillPaint)
            }
            else -> canvas.drawRect(left, top.toFloat(), left + 2 * density, bottom, fillPaint)
        }
    }

    private fun drawScrollIndicator(canvas: Canvas, emulator: TerminalEmulator) {
        val total = emulator.historySize + emulator.rows
        val track = height - 2 * padding
        val thumb = (track * emulator.rows.toFloat() / total).coerceAtLeast(24 * density)
        val progress = (emulator.historySize - scrollOffset).toFloat() /
            emulator.historySize.coerceAtLeast(1)
        val y = padding + (track - thumb) * progress
        fillPaint.color = (colors.cursor and 0x00FFFFFF) or (0xAA shl 24)
        val right = width - 3 * density
        canvas.drawRoundRect(
            right - 3 * density,
            y,
            right,
            y + thumb,
            2 * density,
            2 * density,
            fillPaint,
        )
    }

    // ---------------------------------------------------------------- touch

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            showKeyboard()
            sendClick(event)
            return true
        }

        override fun onScroll(
            first: MotionEvent?,
            last: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (scaling) return true
            scrollRemainder += distanceY
            val steps = (scrollRemainder / lineHeight).toInt()
            if (steps != 0) {
                scrollRemainder -= steps * lineHeight
                scroll(steps, last)
            }
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onCopyScreenRequest?.invoke(visibleText())
        }
    })

    private var scaling = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                fontSizeSp *= detector.scaleFactor
                onFontSizeChange?.invoke(fontSizeSp)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaling = false
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scrollRemainder = 0f
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val pointer = event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)
        if (pointer && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (wheel != 0f) {
                scroll(if (wheel > 0) -3 else 3, event)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** Positive [steps] scrolls towards newer output. */
    private fun scroll(steps: Int, event: MotionEvent) {
        val active = session ?: return
        val emulator = active.emulator
        val (alternate, mouse, cursorKeys) = synchronized(emulator) {
            Triple(
                emulator.isAltScreen,
                emulator.mouseMode != MouseMode.NONE,
                emulator.applicationCursorKeys,
            )
        }
        when {
            mouse -> {
                val (column, row) = cellAt(event)
                val button = if (steps < 0) 64 else 65
                repeat(abs(steps)) {
                    val report = synchronized(emulator) { emulator.mouseReport(button, column, row, true) }
                    report?.let(active::write)
                }
            }
            alternate -> {
                // A full-screen program without mouse support gets arrow keys,
                // which is what a wheel does in most terminal emulators.
                val key = if (steps < 0) TermKey.UP else TermKey.DOWN
                repeat(abs(steps)) {
                    active.write(KeyEncoder.key(key, KeyModifiers.NONE, cursorKeys))
                }
            }
            else -> {
                val maximum = synchronized(emulator) { emulator.historySize }
                scrollOffset = (scrollOffset - steps).coerceIn(0, maximum)
                invalidate()
            }
        }
    }

    private fun cellAt(event: MotionEvent): Pair<Int, Int> {
        val column = ((event.x - padding) / cellWidth).toInt()
            .coerceIn(0, (gridCols - 1).coerceAtLeast(0))
        val row = ((event.y - padding) / lineHeight).toInt()
            .coerceIn(0, (gridRows - 1).coerceAtLeast(0))
        return column to row
    }

    private fun sendClick(event: MotionEvent) {
        val active = session ?: return
        val (column, row) = cellAt(event)
        val emulator = active.emulator
        val press = synchronized(emulator) { emulator.mouseReport(0, column, row, true) } ?: return
        val release = synchronized(emulator) { emulator.mouseReport(0, column, row, false) }
        active.write(press)
        release?.let(active::write)
    }

    fun showKeyboard() {
        requestFocus()
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * The text currently painted on the view: the live screen, or the part of the
     * scrollback the user scrolled back to. Blank rows below the last line are
     * dropped so an empty terminal does not fill the clipboard with newlines.
     */
    fun visibleText(): String {
        val active = session ?: return ""
        return synchronized(active.emulator) { active.emulator.visibleText(scrollOffset) }
    }

    fun hideKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val active = session
        if (active != null && synchronized(active.emulator) { active.emulator.focusReporting }) {
            active.writeText(if (gainFocus) "\u001b[I" else "\u001b[O")
        }
        invalidate()
    }

    // ---------------------------------------------------------------- keyboard

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A visible-password field keeps predictive text and autocorrection away
        // from a terminal, so every keystroke reaches the program immediately.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val content = editable
                if (content != null && content.isNotEmpty()) {
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }
                // Gesture typing and some keyboards send deletions as text edits
                // instead of key events. Turn them into the keys the shell knows.
                repeat(beforeLength.coerceAtMost(64)) { sendKey(TermKey.BACKSPACE, KeyModifiers.NONE) }
                repeat(afterLength.coerceAtMost(64)) { sendKey(TermKey.DELETE, KeyModifiers.NONE) }
                return true
            }

            private fun flush() {
                val content = editable ?: return
                val text = content.toString()
                content.clear()
                if (text.isEmpty()) return
                var index = 0
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    index += Character.charCount(codePoint)
                    if (codePoint == '\n'.code) {
                        sendKey(TermKey.ENTER, KeyModifiers.NONE)
                    } else {
                        sendChar(codePoint, KeyModifiers.NONE)
                    }
                }
            }
        }
    }

    private fun sendKey(key: TermKey, base: KeyModifiers) {
        val active = session ?: return
        val latched = latchedModifiers()
        val modifiers = KeyModifiers(
            ctrl = base.ctrl || latched.ctrl,
            alt = base.alt || latched.alt,
            shift = base.shift || latched.shift,
        )
        val applicationCursor = synchronized(active.emulator) { active.emulator.applicationCursorKeys }
        active.write(KeyEncoder.key(key, modifiers, applicationCursor))
        if (latched.any) onModifiersUsed()
        jumpToLiveScreen()
    }

    private fun sendChar(codePoint: Int, base: KeyModifiers) {
        val active = session ?: return
        val latched = latchedModifiers()
        val modifiers = KeyModifiers(
            ctrl = base.ctrl || latched.ctrl,
            alt = base.alt || latched.alt,
            shift = false,
        )
        active.write(KeyEncoder.char(codePoint, modifiers))
        if (latched.any) onModifiersUsed()
        jumpToLiveScreen()
    }

    private fun jumpToLiveScreen() {
        if (scrollOffset == 0) return
        scrollOffset = 0
        invalidate()
    }

    /** Sends a key from the extra-keys row. */
    fun pressKey(key: TermKey) = sendKey(key, KeyModifiers.NONE)

    /** Sends a character from the extra-keys row, honouring latched Ctrl and Alt. */
    fun pressChar(codePoint: Int) = sendChar(codePoint, KeyModifiers.NONE)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (session == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER -> return super.onKeyDown(keyCode, event)
        }
        if (event.isCtrlPressed && event.isShiftPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_V -> {
                    onPasteRequest?.invoke()
                    return true
                }
                KeyEvent.KEYCODE_C -> {
                    onCopyRequest?.invoke()
                    return true
                }
            }
        }
        val modifiers = KeyModifiers(event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)
        val special = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> TermKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> TermKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> TermKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> TermKey.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> TermKey.HOME
            KeyEvent.KEYCODE_MOVE_END -> TermKey.END
            KeyEvent.KEYCODE_PAGE_UP -> TermKey.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> TermKey.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> TermKey.INSERT
            KeyEvent.KEYCODE_FORWARD_DEL -> TermKey.DELETE
            KeyEvent.KEYCODE_DEL -> TermKey.BACKSPACE
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> TermKey.ENTER
            KeyEvent.KEYCODE_TAB -> TermKey.TAB
            KeyEvent.KEYCODE_ESCAPE -> TermKey.ESCAPE
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                TermKey.entries[TermKey.F1.ordinal + (keyCode - KeyEvent.KEYCODE_F1)]
            else -> null
        }
        if (special != null) {
            sendKey(special, modifiers)
            return true
        }
        // Ctrl and Alt are folded into the character by the encoder, so ask for
        // the unmodified key and apply the modifiers ourselves.
        val meta = event.metaState and
            (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val codePoint = event.getUnicodeChar(meta)
        if (codePoint == 0 || codePoint and KeyCharacterMap.COMBINING_ACCENT != 0) {
            return super.onKeyDown(keyCode, event)
        }
        sendChar(codePoint, modifiers)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (session == null) return super.onKeyUp(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> super.onKeyUp(keyCode, event)
            else -> true
        }
    }

    companion object {
        const val MIN_FONT_SP = 7f
        const val MAX_FONT_SP = 30f
        private const val NO_CURSOR = -1
    }
}

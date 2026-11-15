package com.opencode.android.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Callbacks a terminal parser sends to its screen model. */
internal interface TerminalSink {
    /** A printable code point, after character set translation. */
    fun output(codePoint: Int)

    /** A C0 control character. */
    fun control(codePoint: Int)

    /** An escape sequence without a CSI introducer, e.g. `ESC 7`. */
    fun escape(intermediates: String, finalByte: Int)

    /** A complete control sequence. */
    fun controlSequence(
        prefix: Char,
        parameters: List<IntArray>,
        intermediates: String,
        finalByte: Int,
    )

    /** The body of an OSC string, without the introducer and terminator. */
    fun operatingSystemCommand(text: String)
}

/**
 * Incremental decoder for the byte stream of a pseudo-terminal.
 *
 * The parser is deliberately independent of the screen. It owns the UTF-8
 * decoder, the escape state machine and the sequence buffers; everything that
 * changes a cell goes through [TerminalSink]. That separation keeps the hot
 * path small and makes the screen model testable without feeding bytes.
 *
 * The grammar follows ECMA-48 and the xterm control sequence reference. An
 * incomplete UTF-8 sequence at the end of a read is kept for the next read,
 * which is common because a 16 KiB read can split a multi-byte character.
 */
internal class TerminalParser(private val sink: TerminalSink) {
    private enum class State {
        DATA, ESCAPE, ESCAPE_INTERMEDIATE, CSI, CSI_INTERMEDIATE, CSI_IGNORED,
        OSC, OSC_ESCAPE, STRING, STRING_ESCAPE,
    }

    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    private var state = State.DATA
    private val parameters = StringBuilder(32)
    private val intermediates = StringBuilder(4)
    private var prefix = '\u0000'
    private val osc = StringBuilder(128)

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        val combined = ByteArray(pending.size + length)
        System.arraycopy(pending, 0, combined, 0, pending.size)
        System.arraycopy(bytes, offset, combined, pending.size, length)
        val input = ByteBuffer.wrap(combined)
        val chars = CharBuffer.allocate(combined.size + 2)
        decode(input, chars)
        chars.flip()
        // Copy out of the buffer first: Character.codePointAt(CharSequence, i)
        // indexes it absolutely, but CharSequence.length() on a CharBuffer is
        // the remaining count, so a surrogate pair near the end of the buffer
        // would be split (or read past the limit). A plain array has the same
        // origin for both the index and the length.
        val decoded = CharArray(chars.remaining())
        chars.get(decoded)
        var index = 0
        while (index < decoded.size) {
            val codePoint = Character.codePointAt(decoded, index)
            index += Character.charCount(codePoint)
            consume(codePoint)
        }
        pending = ByteArray(input.remaining())
        input.get(pending)
    }

    /** Feeds characters that are already decoded, as an OSC reply or a test does. */
    fun feed(text: String) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            consume(codePoint)
        }
    }

    private fun decode(input: ByteBuffer, chars: CharBuffer) {
        while (true) {
            val result = decoder.decode(input, chars, false)
            when {
                result.isUnderflow -> return
                result.isOverflow -> return
                result.isError -> {
                    if (!input.hasRemaining()) return
                    input.get()
                    if (chars.remaining() > 0) chars.put('\uFFFD')
                }
                else -> return
            }
        }
    }

    private fun consume(codePoint: Int) {
        // CAN and SUB abort any sequence in progress, including OSC and DCS.
        if (codePoint == 0x18 || codePoint == 0x1A) {
            resetSequence()
            return
        }
        when (state) {
            State.DATA -> onData(codePoint)
            State.ESCAPE -> onEscape(codePoint)
            State.ESCAPE_INTERMEDIATE -> onEscapeIntermediate(codePoint)
            State.CSI -> onControlSequence(codePoint)
            State.CSI_INTERMEDIATE -> onControlSequenceIntermediate(codePoint)
            State.CSI_IGNORED -> if (codePoint in 0x40..0x7E) state = State.DATA
            State.OSC -> onOperatingSystemCommand(codePoint)
            State.OSC_ESCAPE -> {
                sink.operatingSystemCommand(osc.toString())
                state = State.ESCAPE
                if (codePoint == '\\'.code) state = State.DATA else consume(codePoint)
            }
            State.STRING -> if (codePoint == 0x1B) state = State.STRING_ESCAPE
            State.STRING_ESCAPE -> {
                if (codePoint == '\\'.code) state = State.DATA else {
                    state = State.ESCAPE
                    consume(codePoint)
                }
            }
        }
    }

    private fun onData(codePoint: Int) {
        when {
            codePoint == 0x1B -> resetSequence().also { state = State.ESCAPE }
            codePoint < 0x20 -> sink.control(codePoint)
            codePoint == 0x7F || codePoint in 0x80..0x9F -> Unit
            else -> sink.output(codePoint)
        }
    }

    private fun onEscape(codePoint: Int) {
        when {
            codePoint == 0x1B -> resetSequence().also { state = State.ESCAPE }
            codePoint < 0x20 -> sink.control(codePoint)
            codePoint == '['.code -> {
                parameters.setLength(0)
                intermediates.setLength(0)
                prefix = '\u0000'
                state = State.CSI
            }
            codePoint == ']'.code -> {
                osc.setLength(0)
                state = State.OSC
            }
            codePoint == 'P'.code || codePoint == 'X'.code ||
                codePoint == '^'.code || codePoint == '_'.code -> state = State.STRING
            codePoint in 0x20..0x2F -> {
                intermediates.append(codePoint.toChar())
                state = State.ESCAPE_INTERMEDIATE
            }
            else -> {
                state = State.DATA
                sink.escape("", codePoint)
            }
        }
    }

    private fun onEscapeIntermediate(codePoint: Int) {
        when {
            codePoint == 0x1B -> resetSequence().also { state = State.ESCAPE }
            codePoint < 0x20 -> sink.control(codePoint)
            codePoint in 0x20..0x2F -> if (intermediates.length < 8) intermediates.append(codePoint.toChar())
            else -> {
                val text = intermediates.toString()
                state = State.DATA
                sink.escape(text, codePoint)
            }
        }
    }

    private fun onControlSequence(codePoint: Int) {
        when {
            codePoint == 0x1B -> resetSequence().also { state = State.ESCAPE }
            codePoint < 0x20 || codePoint == 0x7F -> if (codePoint < 0x20) sink.control(codePoint)
            codePoint in '0'.code..';'.code -> if (parameters.length < 256) parameters.append(codePoint.toChar())
            codePoint in '<'.code..'?'.code -> {
                if (parameters.isEmpty() && prefix == '\u0000') prefix = codePoint.toChar()
                else state = State.CSI_IGNORED
            }
            codePoint in 0x20..0x2F -> {
                if (intermediates.length < 8) intermediates.append(codePoint.toChar())
                state = State.CSI_INTERMEDIATE
            }
            codePoint in 0x40..0x7E -> {
                state = State.DATA
                sink.controlSequence(prefix, splitParameters(), intermediates.toString(), codePoint)
            }
            else -> Unit
        }
    }

    private fun onControlSequenceIntermediate(codePoint: Int) {
        when {
            codePoint == 0x1B -> resetSequence().also { state = State.ESCAPE }
            codePoint < 0x20 -> sink.control(codePoint)
            codePoint in 0x20..0x2F -> if (intermediates.length < 8) intermediates.append(codePoint.toChar())
            codePoint in 0x40..0x7E -> {
                state = State.DATA
                sink.controlSequence(prefix, splitParameters(), intermediates.toString(), codePoint)
            }
            else -> Unit
        }
    }

    private fun onOperatingSystemCommand(codePoint: Int) {
        when {
            codePoint == 0x07 -> {
                state = State.DATA
                sink.operatingSystemCommand(osc.toString())
            }
            codePoint == 0x1B -> state = State.OSC_ESCAPE
            codePoint >= 0x20 -> if (osc.length < 4096) osc.appendCodePoint(codePoint)
        }
    }

    private fun resetSequence() {
        if (state == State.OSC || state == State.OSC_ESCAPE) osc.setLength(0)
        state = State.DATA
        parameters.setLength(0)
        intermediates.setLength(0)
        prefix = '\u0000'
    }

    /**
     * Splits the raw parameter bytes into one array per `;` separated group.
     * Colon separated sub-parameters stay in the same group, because SGR
     * colours use them (`38:2:r:g:b`).
     */
    private fun splitParameters(): List<IntArray> {
        if (parameters.isEmpty()) return emptyList()
        val groups = ArrayList<IntArray>()
        for (group in parameters.split(';')) {
            val values = group.split(':')
            groups.add(IntArray(values.size) { index ->
                values[index].toIntOrNull()?.coerceIn(0, 65_535) ?: -1
            })
        }
        return groups
    }
}

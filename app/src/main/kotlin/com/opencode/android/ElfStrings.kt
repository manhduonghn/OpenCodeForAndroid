package com.opencode.android

import java.nio.charset.StandardCharsets

/**
 * In-place rewriting of the C strings of a binary, used to move the paths that the Termux packages
 * have compiled in.
 *
 * A match is never rewritten as raw bytes: the whole string that contains it is located first, so
 * only the bytes of that string change. The replacement may be shorter than the string, the tail is
 * then filled with NULs (dead data for the C library, it ends at the first NUL). It must never be
 * longer: the size of a binary and therefore every offset in it has to stay identical.
 *
 * The same rule is used for the equal-length relocation of the glibc runtime, where the bytes that
 * are written are exactly the ones a blind search would write, and for the shorter paths such as
 * git's `SHELL_PATH`, which used to have its own copy of this loop.
 */
internal object ElfStrings {

    /**
     * Distance searched for the NUL bytes that bound a match. The paths in the rodata of the
     * packages are short; a match inside a blob that is not a C string (a JavaScript bundle of the
     * CLI, for example) is rewritten in place instead of spanning megabytes.
     */
    private const val MAX_STRING_SPAN = 8 * 1024

    /**
     * Replace every [old] inside the C strings of [data] with [new].
     *
     * @return the number of replaced occurrences, 0 when nothing matched or when [new] is longer
     *     than [old].
     */
    fun replace(data: ByteArray, old: ByteArray, new: ByteArray): Int {
        if (old.isEmpty() || new.isEmpty() || new.size > old.size) return 0
        var count = 0
        var i = 0
        val last = data.size - old.size
        while (i <= last) {
            if (!matches(data, i, old)) {
                i++
                continue
            }
            val start = stringStart(data, i)
            val end = stringEnd(data, i + old.size)
            if (start < 0 || end < 0) {
                // Not a NUL terminated string: only an equally long replacement is safe.
                if (new.size == old.size) {
                    new.copyInto(data, i)
                    count++
                }
                i += old.size
                continue
            }
            count += replaceInString(data, start, end, old, new)
            i = end
        }
        return count
    }

    private fun matches(data: ByteArray, at: Int, old: ByteArray): Boolean {
        for (j in old.indices) {
            if (data[at + j] != old[j]) return false
        }
        return true
    }

    /** First byte of the string that contains [at], or -1 when there is no NUL nearby. */
    private fun stringStart(data: ByteArray, at: Int): Int {
        var i = at
        while (i > 0 && at - i < MAX_STRING_SPAN) {
            if (data[i - 1] == 0.toByte()) return i
            i--
        }
        return if (i == 0) 0 else -1
    }

    /** The NUL that terminates the string which starts before [at], or -1 when there is none. */
    private fun stringEnd(data: ByteArray, at: Int): Int {
        var i = at
        while (i < data.size && i - at < MAX_STRING_SPAN) {
            if (data[i] == 0.toByte()) return i
            i++
        }
        return -1
    }

    /** Replace [old] with [new] inside `data[start until end]` and NUL the leftover bytes. */
    private fun replaceInString(
        data: ByteArray,
        start: Int,
        end: Int,
        old: ByteArray,
        new: ByteArray,
    ): Int {
        val length = end - start
        if (length <= 0) return 0
        val string = String(data, start, length, StandardCharsets.ISO_8859_1)
        val from = String(old, StandardCharsets.ISO_8859_1)
        if (!string.contains(from)) return 0
        val to = String(new, StandardCharsets.ISO_8859_1)
        var count = 0
        var at = 0
        while (true) {
            val found = string.indexOf(from, at)
            if (found < 0) break
            count++
            at = found + from.size
        }
        val replaced = string.replace(from, to).toByteArray(StandardCharsets.ISO_8859_1)
        replaced.copyInto(data, start)
        for (i in start + replaced.size until end) data[i] = 0
        return count
    }
}

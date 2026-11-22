package dev.zeroinput.ime.core.stego

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** GB18030 mappings from the P2 reference, independent of the device ICU version. */
internal class FrozenGb18030(data: ByteArray) {
    private val doubleWidth = CharArray(23_940)
    private val doubleForChar = IntArray(65_536)
    private val ranges: List<Range>

    init {
        verifyVerseAsset(data, 50_364, "6024035deb18bbc26f7d3a97ccca8a25b6351485e6d50985282a8c7e751828d2")
        val input = ByteBuffer.wrap(data)
        for (i in doubleWidth.indices) {
            val char = input.char
            doubleWidth[i] = char
            doubleForChar[char.code] = i + 1
        }
        ranges = List(207) { Range(input.int, input.int, input.int) }
    }

    fun encode(utf8: ByteArray): ByteArray {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8))
        } catch (_: CharacterCodingException) { throw VerseTestFormatFailure() }
        val output = ByteArray(4 * utf8.size)
        try {
            var size = 0
            while (text.hasRemaining()) {
                val first = text.get()
                val code = if (Character.isHighSurrogate(first)) Character.toCodePoint(first, text.get()) else first.code
                size += encodePoint(code, output, size)
            }
            return output.copyOf(size)
        } finally {
            output.fill(0)
            if (text.hasArray()) text.array().fill('\u0000')
        }
    }

    private fun encodePoint(code: Int, out: ByteArray, offset: Int): Int {
        if (code < 128) {
            out[offset] = code.toByte()
            return 1
        }
        val known = doubleForChar.getOrElse(code) { 0 } - 1
        if (known >= 0) {
            out[offset] = (0x81 + known / 190).toByte()
            val trail = known % 190
            out[offset + 1] = (trail + if (trail < 63) 0x40 else 0x41).toByte()
            return 2
        }
        val range = findRange(code, pointer = false)
        val value = range.pointer + code - range.first
        out[offset] = (0x81 + value / 12_600).toByte()
        out[offset + 1] = (0x30 + value / 1_260 % 10).toByte()
        out[offset + 2] = (0x81 + value / 10 % 126).toByte()
        out[offset + 3] = (0x30 + value % 10).toByte()
        return 4
    }

    fun decode(gb: ByteArray, length: Int, originalBytes: Int): ByteArray {
        val result = CharArray(length)
        var position = 0
        var chars = 0
        fun next(): Int {
            if (position == length) throw VerseTestFormatFailure()
            return gb[position++].toInt() and 255
        }
        try {
            while (position < length) {
                val lead = next()
                val code = if (lead < 128) lead else decodePoint(lead, ::next)
                chars += Character.toChars(code, result, chars)
            }
            val utf8 = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(result, 0, chars))
            return try {
                if (utf8.remaining() != originalBytes) throw VerseTestFormatFailure()
                ByteArray(originalBytes).also(utf8::get)
            } finally { if (utf8.hasArray()) utf8.array().fill(0) }
        } finally { result.fill('\u0000') }
    }

    private fun decodePoint(lead: Int, next: () -> Int): Int {
        if (lead !in 0x81..0xfe) throw VerseTestFormatFailure()
        val trail = next()
        if (trail in 0x40..0xfe && trail != 0x7f) {
            return doubleWidth[(lead - 0x81) * 190 + trail - if (trail < 0x7f) 0x40 else 0x41].code
        }
        if (trail !in 0x30..0x39) throw VerseTestFormatFailure()
        val third = next()
        val fourth = next()
        if (third !in 0x81..0xfe || fourth !in 0x30..0x39) throw VerseTestFormatFailure()
        val value = (((lead - 0x81) * 10 + trail - 0x30) * 126 + third - 0x81) * 10 + fourth - 0x30
        val range = findRange(value, pointer = true)
        return range.first + value - range.pointer
    }

    private fun findRange(value: Int, pointer: Boolean): Range {
        var low = 0
        var high = ranges.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val range = ranges[middle]
            val start = if (pointer) range.pointer else range.first
            when {
                value < start -> high = middle - 1
                value > start + range.last - range.first -> low = middle + 1
                else -> return range
            }
        }
        throw VerseTestFormatFailure()
    }

    private data class Range(val first: Int, val last: Int, val pointer: Int)
}

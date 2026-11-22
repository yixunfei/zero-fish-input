package dev.zeroinput.ime.core.stego

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

enum class StegoTransport { DENSE_UNICODE, VISIBLE_BASE64 }
class StegoFormatFailure : Exception("Unsupported or incomplete message")
data class StegoCarrier(val cover: String, val envelope: ByteArray, val transport: StegoTransport)

/** Exact code points, canonical distributed frames, no normalization or provider access. */
object StegoCarrierCodec {
    const val MAX_UNITS = 8192
    const val MAX_BYTES = 16384
    const val MAX_ENVELOPE = 2372
    const val DENSE_START = "\u2063\u2060\u2063"
    const val DENSE_END = "\u2063\u2061\u2063"
    const val VISIBLE_START = "[ZIS1:"
    private const val VISIBLE_FRAME = "${VISIBLE_START}2:"
    private const val DENSE_FRAME = DENSE_START + "\u2062"
    private const val MAX_FRAMES = 16

    fun hasMarker(value: CharSequence): Boolean = DENSE_START in value || DENSE_END in value ||
        "[ZIS1" in value

    fun ciphertext(envelope: ByteArray): String {
        if (envelope.size !in 1..MAX_ENVELOPE) throw StegoFormatFailure()
        return VISIBLE_START + base64(envelope) + "]"
    }

    fun isDetached(value: String): Boolean = value.startsWith(VISIBLE_START) && !value.startsWith(VISIBLE_FRAME)

    fun decode(value: String, exactCover: String): StegoCarrier {
        if (!isDetached(value)) return decode(value)
        checkText(value)
        checkCover(exactCover)
        if (!value.endsWith(']')) throw StegoFormatFailure()
        return StegoCarrier(exactCover, parseBase64(value.substring(VISIBLE_START.length, value.lastIndex)),
            StegoTransport.VISIBLE_BASE64)
    }

    fun inspectable(value: String): StegoCarrier = decode(value, "ZeroInput detached inspection")

    // Stable on every Android/Unicode version. Split only between adjacent simple text characters.
    // Complex runs (including emoji, CRLF, combining sequences and Indic text) stay together.
    private fun simple(c: Char) = c in ' '..'~' || c in '\u4e00'..'\u9fff' ||
        c in '\u3001'..'\u3002' || c in '\uff01'..'\uff5e'

    private fun positions(cover: String, size: Int): List<Int> {
        val gaps = (1 until cover.length).filter { simple(cover[it - 1]) && simple(cover[it]) }
        if (gaps.isEmpty()) return listOf(0)
        val count = minOf(MAX_FRAMES, gaps.size, size)
        return List(count) { gaps[(2 * it + 1) * gaps.size / (2 * count)] }
    }

    // Public ordering only. The message envelope independently authenticates the exact cover.
    private fun rotation(cover: String, size: Int): Int {
        if (size == 1) return 0
        val hash = MessageDigest.getInstance("SHA-256").digest(cover.toByteArray(Charsets.UTF_8))
        return 1 + (((hash[0].toInt() and 255) * 256 + (hash[1].toInt() and 255)) % (size - 1))
    }

    fun encode(cover: String, envelope: ByteArray, transport: StegoTransport): String {
        checkCover(cover)
        val size = envelope.size
        if (size !in 1..MAX_ENVELOPE) throw StegoFormatFailure()
        val gaps = positions(cover, size)
        val shift = rotation(cover, size)
        return buildString {
            var previous = 0
            gaps.forEachIndexed { frame, gap ->
                append(cover, previous, gap)
                val chunk = ByteArray(1 + (size - 1 - frame) / gaps.size) { row ->
                    envelope[(shift + row * gaps.size + frame) % size]
                }
                if (transport == StegoTransport.VISIBLE_BASE64) {
                    append(VISIBLE_FRAME).append(base64(chunk)).append(']')
                } else {
                    append(DENSE_FRAME)
                    for (byte in chunk) {
                        val n = byte.toInt() and 255
                        appendCodePoint(if (n < 16) 0xfe00 + n else 0xe0100 + n - 16)
                    }
                    append(DENSE_END)
                }
                previous = gap
            }
            append(cover, previous, cover.length)
        }.also(::checkText)
    }

    fun decode(value: String): StegoCarrier {
        checkText(value)
        val dense = DENSE_FRAME in value
        val start = if (dense) DENSE_FRAME else VISIBLE_FRAME
        val end = if (dense) DENSE_END else "]"
        val transport = if (dense) StegoTransport.DENSE_UNICODE else StegoTransport.VISIBLE_BASE64
        val chunks = mutableListOf<ByteArray>()
        val cover = StringBuilder()
        var cursor = 0
        var size = 0
        while (true) {
            val frame = value.indexOf(start, cursor)
            if (frame < 0) break
            if (chunks.size >= MAX_FRAMES) throw StegoFormatFailure()
            cover.append(value, cursor, frame)
            val from = frame + start.length
            val until = value.indexOf(end, from)
            if (until <= from) throw StegoFormatFailure()
            val part = value.substring(from, until)
            val bytes = if (dense) parseDense(part) else parseBase64(part)
            size += bytes.size
            if (size > MAX_ENVELOPE) throw StegoFormatFailure()
            chunks.add(bytes)
            cursor = until + end.length
        }
        if (size == 0) throw StegoFormatFailure()
        val exact = cover.append(value, cursor, value.length).toString()
        checkCover(exact)
        val shift = rotation(exact, size)
        val envelope = ByteArray(size)
        chunks.forEachIndexed { frame, chunk ->
            if (chunk.size != 1 + (size - 1 - frame) / chunks.size) throw StegoFormatFailure()
            chunk.forEachIndexed { row, byte -> envelope[(shift + row * chunks.size + frame) % size] = byte }
        }
        if (encode(exact, envelope, transport) != value) throw StegoFormatFailure()
        return StegoCarrier(exact, envelope, transport)
    }

    private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun parseBase64(text: String): ByteArray {
        if (text.length !in 1..((MAX_ENVELOPE * 4 + 2) / 3)) throw StegoFormatFailure()
        val bytes = try { Base64.getUrlDecoder().decode(text) } catch (_: IllegalArgumentException) { throw StegoFormatFailure() }
        if (base64(bytes) != text) throw StegoFormatFailure()
        return bytes
    }

    private fun parseDense(text: String): ByteArray {
        val points = text.codePoints().toArray()
        if (points.size !in 1..MAX_ENVELOPE) throw StegoFormatFailure()
        return ByteArray(points.size) { i ->
            when (val cp = points[i]) {
                in 0xfe00..0xfe0f -> (cp - 0xfe00).toByte()
                in 0xe0100..0xe01ef -> (cp - 0xe0100 + 16).toByte()
                else -> throw StegoFormatFailure()
            }
        }
    }

    fun utf8Length(value: String): Int = checkedLength(value)

    private fun checkCover(value: String) {
        if (value.isBlank() || value.length > 512 || checkedLength(value) > 1536 ||
            value.contains(DENSE_START) || value.contains(DENSE_END) || value.contains(VISIBLE_START)) throw StegoFormatFailure()
    }

    private fun checkText(value: String) {
        if (value.length !in 1..MAX_UNITS || checkedLength(value) > MAX_BYTES) throw StegoFormatFailure()
    }

    private fun checkedLength(value: String): Int {
        if (value.length > MAX_UNITS || value.contains('\u0000')) throw StegoFormatFailure()
        return try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value)).remaining()
        } catch (_: java.nio.charset.CharacterCodingException) { throw StegoFormatFailure() }
    }
}

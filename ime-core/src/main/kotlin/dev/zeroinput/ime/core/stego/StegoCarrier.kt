package dev.zeroinput.ime.core.stego

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

enum class StegoTransport { DENSE_UNICODE, VISIBLE_BASE64 }
class StegoFormatFailure : Exception("Unsupported or incomplete message")
data class StegoCarrier(val cover: String, val envelope: ByteArray, val transport: StegoTransport)

/** Exact code points, one complete terminal block, no normalization or provider access. */
object StegoCarrierCodec {
    const val MAX_UNITS = 8192
    const val MAX_BYTES = 16384
    const val MAX_ENVELOPE = 2372
    const val DENSE_START = "\u2063\u2060\u2063"
    const val DENSE_END = "\u2063\u2061\u2063"
    const val VISIBLE_START = "[ZIS1:"
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** A detached envelope uses the existing visible block, with no unauthenticated cover. */
    fun ciphertext(envelope: ByteArray): String {
        if (envelope.size !in 1..MAX_ENVELOPE) throw StegoFormatFailure()
        return VISIBLE_START + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope) + "]"
    }

    fun isDetached(value: String): Boolean = value.startsWith(VISIBLE_START)

    /** Detached messages require an explicitly supplied exact cover; complete carriers ignore it. */
    fun decode(value: String, exactCover: String): StegoCarrier {
        checkText(value)
        return decode(if (isDetached(value)) {
            checkCover(exactCover)
            exactCover + value
        } else value)
    }

    /** Inspect public requirements before a detached message's cover has been entered. */
    fun inspectable(value: String): StegoCarrier = decode(value, "ZeroInput detached inspection")

    fun encode(cover: String, envelope: ByteArray, transport: StegoTransport): String {
        checkCover(cover)
        if (envelope.size !in 1..MAX_ENVELOPE) throw StegoFormatFailure()
        val value = when (transport) {
            StegoTransport.VISIBLE_BASE64 -> cover + ciphertext(envelope)
            StegoTransport.DENSE_UNICODE -> buildString {
                append(cover); append(DENSE_START)
                envelope.forEach { byte ->
                    val value = byte.toInt() and 255
                    appendCodePoint(if (value < 16) 0xfe00 + value else 0xe0100 + value - 16)
                }
                append(DENSE_END)
            }
        }
        checkText(value)
        return value
    }

    fun decode(value: String): StegoCarrier {
        checkText(value)
        val dense = value.indexOf(DENSE_START)
        val visible = value.indexOf(VISIBLE_START)
        if ((dense >= 0) == (visible >= 0)) throw StegoFormatFailure()
        val cover = value.substring(0, if (dense >= 0) dense else visible)
        checkCover(cover)
        val bytes = if (dense >= 0) {
            if (!value.endsWith(DENSE_END) || value.indexOf(DENSE_START, dense + 1) >= 0 ||
                value.indexOf(DENSE_END) != value.length - DENSE_END.length) throw StegoFormatFailure()
            val payload = value.substring(dense + DENSE_START.length, value.length - DENSE_END.length)
            val points = payload.codePoints().toArray()
            if (points.size !in 1..MAX_ENVELOPE) throw StegoFormatFailure()
            ByteArray(points.size) { i ->
                when (val cp = points[i]) {
                    in 0xfe00..0xfe0f -> (cp - 0xfe00).toByte()
                    in 0xe0100..0xe01ef -> (cp - 0xe0100 + 16).toByte()
                    else -> throw StegoFormatFailure()
                }
            }
        } else {
            if (!value.endsWith("]") || value.indexOf(VISIBLE_START, visible + 1) >= 0 || value.contains(DENSE_END))
                throw StegoFormatFailure()
            val text = value.substring(visible + VISIBLE_START.length, value.length - 1)
            if (text.isEmpty() || text.length > 3163 || text.any { it !in alphabet }) throw StegoFormatFailure()
            val decoded = try { Base64.getUrlDecoder().decode(text) } catch (_: IllegalArgumentException) { throw StegoFormatFailure() }
            if (decoded.size > MAX_ENVELOPE || Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != text)
                throw StegoFormatFailure()
            decoded
        }
        return StegoCarrier(cover, bytes, if (dense >= 0) StegoTransport.DENSE_UNICODE else StegoTransport.VISIBLE_BASE64)
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

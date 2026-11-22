package dev.zeroinput.ime.core.stego

import java.math.BigInteger

/** A leading 1 byte makes the exact length recoverable even when the envelope starts with zeros. */
internal object WordingFrame {
    fun pack(envelope: ByteArray, maxBytes: Int): BigInteger {
        if (envelope.size !in 1..maxBytes) throw VerseTestFormatFailure()
        return BigInteger(1, envelope).setBit(envelope.size * 8)
    }

    fun unpack(number: BigInteger, maxBytes: Int): ByteArray {
        val bytes = number.toByteArray()
        return try {
            if (bytes.size !in 2..maxBytes + 1 || bytes[0] != 1.toByte()) throw VerseTestFormatFailure()
            bytes.copyOfRange(1, bytes.size)
        } finally { bytes.fill(0) }
    }

    fun fits(size: Int, maxBytes: Int, span: BigInteger) {
        if (size !in 1..maxBytes) throw VerseTestFormatFailure()
        if (span <= BigInteger.ONE.shiftLeft(size * 8)) throw VerseTestCapacityFailure()
    }
}

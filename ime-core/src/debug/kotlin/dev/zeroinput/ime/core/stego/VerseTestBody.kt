package dev.zeroinput.ime.core.stego

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Exactly the GB18030 preset-dictionary packet from the P2 scenery calibration. */
class VerseTestBody(mapping: ByteArray, dictionaryData: ByteArray) {
    private val gb = FrozenGb18030(mapping)
    private val dictionary = dictionaryData.copyOf()

    init {
        verifyVerseAsset(dictionary, 15_021, "d0b0515c17072c6fd5bd83e8a3c0fd86bc0308867a9d31e00ea38d5ed400ff1f")
    }

    fun encode(raw: ByteArray): ByteArray {
        if (raw.size !in 1..MAX_ORIGINAL) throw VerseTestCapacityFailure()
        val text = gb.encode(raw)
        val compressed = ByteArray(MAX_STREAM + 1)
        val compressor = Deflater(Deflater.BEST_COMPRESSION)
        try {
            compressor.setDictionary(dictionary)
            compressor.setInput(text)
            compressor.finish()
            var size = 0
            while (!compressor.finished()) {
                if (size == compressed.size) throw VerseTestCapacityFailure()
                val count = compressor.deflate(compressed, size, compressed.size - size)
                if (count == 0 && !compressor.finished()) throw VerseTestFormatFailure()
                size += count
            }
            if (size !in 1..MAX_STREAM) throw VerseTestCapacityFailure()
            val packet = ByteArray(size + 3)
            packet[0] = MODE_GB
            packet[1] = (raw.size ushr 8).toByte()
            packet[2] = raw.size.toByte()
            compressed.copyInto(packet, 3, endIndex = size)
            return packet
        } finally {
            compressor.end()
            text.fill(0)
            compressed.fill(0)
        }
    }

    fun decode(packet: ByteArray): ByteArray {
        if (packet.size !in 4..MAX_STREAM + 3 || packet[0] != MODE_GB) throw VerseTestFormatFailure()
        val original = ((packet[1].toInt() and 255) shl 8) or (packet[2].toInt() and 255)
        if (original !in 1..MAX_ORIGINAL) throw VerseTestFormatFailure()
        val buffer = ByteArray(4 * original + 1)
        val inflater = Inflater()
        try {
            inflater.setInput(packet, 3, packet.size - 3)
            val size = inflate(inflater, buffer)
            return gb.decode(buffer, size, original)
        } catch (_: DataFormatException) {
            throw VerseTestFormatFailure()
        } catch (_: IllegalArgumentException) {
            throw VerseTestFormatFailure()
        } finally {
            inflater.end()
            buffer.fill(0)
        }
    }

    private fun inflate(inflater: Inflater, buffer: ByteArray): Int {
        var size = 0
        while (!inflater.finished()) {
            if (size == buffer.size) throw VerseTestFormatFailure()
            val count = inflater.inflate(buffer, size, buffer.size - size)
            if (count == 0 && !inflater.finished()) {
                if (!inflater.needsDictionary()) throw VerseTestFormatFailure()
                inflater.setDictionary(dictionary)
            }
            size += count
        }
        if (size == buffer.size || inflater.remaining != 0) throw VerseTestFormatFailure()
        return size
    }

    companion object {
        const val MAX_ORIGINAL = 2048
        private const val MAX_STREAM = 2045
        private const val MODE_GB: Byte = 4

        fun envelopeBytes(packetBytes: Int): Int {
            if (packetBytes !in 4..MAX_ORIGINAL) throw VerseTestCapacityFailure()
            return 98 + 64 * ((packetBytes + 36 + 63) / 64)
        }
    }
}

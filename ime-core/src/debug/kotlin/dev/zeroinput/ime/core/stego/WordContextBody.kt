package dev.zeroinput.ime.core.stego

import java.nio.ByteBuffer
import java.nio.CharBuffer

internal const val WORD_PACKET_HEADER = 3
private const val MAX_ORIGINAL = 2048

/** These IDs permanently refer to the verified ZWC1 table and these predictor weights. */
internal enum class WordPacketMode(val id: Int) {
    HUFFMAN(5), LOCAL(6), BIGRAM_LOCAL(7),
}

/** Compression runs on the wording worker. Decode receives only an authenticated body. */
class WordContextBody(asset: ByteArray) {
    private val model = WordContextModel(asset)

    fun encode(raw: ByteArray, envelopeSize: (Int) -> Int, current: () -> Boolean): ByteArray {
        if (raw.size !in 1..MAX_ORIGINAL) throw VerseTestCapacityFailure()
        checkWordOperation(current)
        validateUtf8(raw)
        val plan = plan(raw, current)
        var best: ByteArray? = null
        try {
            var bestSize = Int.MAX_VALUE
            val minimum = envelopeSize(WORD_PACKET_HEADER + 1)
            for (mode in WordPacketMode.entries) {
                checkWordOperation(current)
                val packet = if (mode == WordPacketMode.HUFFMAN) encodeHuffman(raw, plan, current)
                    else encodeRans(raw, plan, mode, current)
                if (packet == null) continue
                var keep = false
                try {
                    val size = envelopeSize(packet.size)
                    if (size < bestSize) {
                        best?.fill(0)
                        best = packet
                        bestSize = size
                        keep = true
                    }
                } finally { if (!keep) packet.fill(0) }
                // The first mode wins ties, so the small Huffman path never warms rANS state.
                if (bestSize == minimum) break
            }
            checkWordOperation(current)
            val result = best ?: throw VerseTestCapacityFailure()
            best = null
            return result
        } finally {
            plan.close()
            best?.fill(0)
        }
    }

    private fun plan(raw: ByteArray, current: () -> Boolean): Plan {
        val costs = IntArray(raw.size + 1)
        val plan = Plan(raw.size)
        var complete = false
        try {
            for (index in raw.lastIndex downTo 0) {
                if (index % 32 == 0) checkWordOperation(current)
                var cheapest = Int.MAX_VALUE
                model.matcher.visit(raw, index) { symbol, end ->
                    val cost = model.widths[symbol] + costs[end]
                    if (cost < cheapest) {
                        cheapest = cost
                        plan.set(index, symbol, end - index)
                    }
                }
                for (length in 1..minOf(16, raw.size - index)) {
                    val cost = model.widths[0] + 4 + 8 * length + costs[index + length]
                    if (cost < cheapest) {
                        cheapest = cost
                        plan.set(index, 0, length)
                    }
                }
                costs[index] = cheapest
            }
            plan.bits = costs[0]
            complete = true
            return plan
        } finally {
            costs.fill(0)
            if (!complete) plan.close()
        }
    }

    private fun encodeHuffman(raw: ByteArray, plan: Plan, current: () -> Boolean): ByteArray? {
        val streamBytes = (plan.bits + 7) / 8
        if (WORD_PACKET_HEADER + streamBytes > MAX_PACKET_BYTES) return null
        val packet = wordPacket(WordPacketMode.HUFFMAN, raw.size, streamBytes)
        var complete = false
        try {
            val bits = WordBitWriter(packet, WORD_PACKET_HEADER)
            var index = 0
            while (index < raw.size) {
                checkWordOperation(current)
                val symbol = plan.symbols[index]
                val length = plan.lengths[index]
                bits.put(model.codes[symbol], model.widths[symbol])
                if (symbol == 0) {
                    bits.put(length - 1, 4)
                    for (position in index until index + length) bits.put(raw[position].toInt() and 255, 8)
                }
                index += length
            }
            complete = true
            return packet
        } finally { if (!complete) packet.fill(0) }
    }

    private fun encodeRans(raw: ByteArray, plan: Plan, mode: WordPacketMode, current: () -> Boolean): ByteArray? =
        WordRansWriter(3 * raw.size).use { writer ->
            WordContextCursor(model, bigram = mode == WordPacketMode.BIGRAM_LOCAL).use { cursor ->
                var index = 0
                while (index < raw.size) {
                    checkWordOperation(current)
                    val symbol = plan.symbols[index]
                    val length = plan.lengths[index]
                    val distribution = cursor.distribution()
                    val start = distribution.boundary(symbol)
                    writer.put(start, distribution.boundary(symbol + 1) - start)
                    if (symbol == 0) {
                        writer.uniform(length - 1, 16)
                        for (position in index until index + length) writer.uniform(raw[position].toInt() and 255, 256)
                    }
                    cursor.advance(symbol)
                    index += length
                }
            }
            writer.pack(mode, raw.size, current)
        }

    fun decode(packet: ByteArray, current: () -> Boolean): ByteArray {
        if (packet.size !in WORD_PACKET_HEADER + 1..MAX_PACKET_BYTES) throw VerseTestFormatFailure()
        val mode = WordPacketMode.entries.firstOrNull { it.id == packet[0].toInt() } ?: throw VerseTestFormatFailure()
        val size = ((packet[1].toInt() and 255) shl 8) or (packet[2].toInt() and 255)
        if (size !in 1..MAX_ORIGINAL) throw VerseTestFormatFailure()
        checkWordOperation(current)
        val output = ByteArray(size)
        var complete = false
        try {
            if (mode == WordPacketMode.HUFFMAN) decodeHuffman(packet, output, current)
            else decodeRans(packet, output, mode, current)
            checkWordOperation(current)
            validateUtf8(output)
            complete = true
            return output
        } finally { if (!complete) output.fill(0) }
    }

    private fun decodeHuffman(packet: ByteArray, output: ByteArray, current: () -> Boolean) {
        val input = WordBitReader(packet, WORD_PACKET_HEADER)
        var index = 0
        while (index < output.size) {
            checkWordOperation(current)
            val symbol = model.huffman.read(input)
            index = if (symbol == 0) copyLiteral(output, index, input.take(4) + 1) { input.take(8) }
                else copyToken(output, index, symbol)
        }
        input.finish()
    }

    private fun decodeRans(packet: ByteArray, output: ByteArray, mode: WordPacketMode, current: () -> Boolean) {
        val input = WordRansReader(packet)
        WordContextCursor(model, bigram = mode == WordPacketMode.BIGRAM_LOCAL).use { cursor ->
            var index = 0
            while (index < output.size) {
                checkWordOperation(current)
                val distribution = cursor.distribution()
                val symbol = distribution.symbol(input.slot)
                val start = distribution.boundary(symbol)
                input.take(start, distribution.boundary(symbol + 1) - start)
                index = if (symbol == 0) copyLiteral(output, index, input.uniform(16) + 1) { input.uniform(256) }
                    else copyToken(output, index, symbol)
                cursor.advance(symbol)
            }
        }
        input.finish()
    }

    private fun copyToken(output: ByteArray, offset: Int, symbol: Int): Int {
        val token = model.tokens[symbol]
        if (token.size > output.size - offset) throw VerseTestFormatFailure()
        token.copyInto(output, offset)
        return offset + token.size
    }

    private inline fun copyLiteral(output: ByteArray, offset: Int, length: Int, read: () -> Int): Int {
        if (length > output.size - offset) throw VerseTestFormatFailure()
        for (i in offset until offset + length) output[i] = read().toByte()
        return offset + length
    }

    private fun validateUtf8(data: ByteArray) {
        val chars = CharBuffer.allocate(data.size)
        val decoder = Charsets.UTF_8.newDecoder()
        try {
            if (!decoder.decode(ByteBuffer.wrap(data), chars, true).isUnderflow || !decoder.flush(chars).isUnderflow)
                throw VerseTestFormatFailure()
        } finally { chars.array().fill('\u0000') }
    }

    private class Plan(size: Int) : AutoCloseable {
        val symbols = IntArray(size)
        val lengths = IntArray(size)
        var bits = 0

        fun set(index: Int, symbol: Int, length: Int) {
            symbols[index] = symbol
            lengths[index] = length
        }

        override fun close() {
            symbols.fill(0)
            lengths.fill(0)
            bits = 0
        }
    }

    companion object {
        const val ASSET_BYTES = WordContextModel.ASSET_BYTES
        const val ASSET_SHA256 = WordContextModel.ASSET_SHA256
        internal const val MAX_PACKET_BYTES = 2048
    }
}

internal fun checkWordOperation(current: () -> Boolean) {
    if (!current()) throw VerseTestFormatFailure()
}

internal fun wordPacket(mode: WordPacketMode, originalSize: Int, streamSize: Int): ByteArray =
    ByteArray(WORD_PACKET_HEADER + streamSize).apply {
        this[0] = mode.id.toByte()
        this[1] = (originalSize ushr 8).toByte()
        this[2] = originalSize.toByte()
    }

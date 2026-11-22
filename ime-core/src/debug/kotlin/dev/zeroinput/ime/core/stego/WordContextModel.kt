package dev.zeroinput.ime.core.stego

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

internal const val WORD_PROBABILITY_TOTAL = 1 shl 18

/** Public immutable data. Symbol zero represents a length-prefixed literal run. */
internal class WordContextModel(asset: ByteArray) {
    val tokens: Array<ByteArray>
    val codes = IntArray(SYMBOLS)
    val widths = IntArray(SYMBOLS)
    val prefixes = IntArray(SYMBOLS + 1)
    val global = IntArray(SYMBOLS + 1)
    val rows = arrayOfNulls<SuccessorRow>(SYMBOLS + 1)
    val matcher: WordMatcher
    val huffman: WordHuffmanTree

    init {
        verifyVerseAsset(asset, ASSET_BYTES, ASSET_SHA256)
        val stream = ByteBuffer.wrap(asset)
        try {
            if (stream.int != 0x5a574331 || stream.u16() != SYMBOLS || stream.u16() != ROWS)
                throw VerseTestFormatFailure()
            tokens = Array(SYMBOLS) { index -> readToken(stream, index) }
            readRows(stream)
            if (stream.hasRemaining()) throw VerseTestFormatFailure()
        } catch (_: BufferUnderflowException) {
            throw VerseTestFormatFailure()
        }
        for (i in global.indices) global[i] = normalize(i, prefixes[i].toLong(), prefixes.last().toLong())
        matcher = WordMatcher(tokens)
        huffman = WordHuffmanTree(codes, widths)
    }

    private fun readToken(stream: ByteBuffer, index: Int): ByteArray {
        val size = stream.get().toInt() and 255
        val width = stream.get().toInt() and 255
        val code = stream.int
        val count = stream.u16()
        if ((index == 0 && size != 0) || (index != 0 && size !in 1..36) ||
            width !in 1..15 || code !in 0 until (1 shl width) || count == 0) throw VerseTestFormatFailure()
        codes[index] = code
        widths[index] = width
        prefixes[index + 1] = prefixes[index] + count
        return ByteArray(size).also(stream::get)
    }

    private fun readRows(stream: ByteBuffer) {
        var lastRow = -1
        repeat(ROWS) {
            val context = stream.u16()
            val size = stream.u16()
            if (context !in lastRow + 1..SYMBOLS || size !in 1..SYMBOLS) throw VerseTestFormatFailure()
            val ids = IntArray(size)
            val cumulative = IntArray(size + 1)
            var last = -1
            for (i in ids.indices) {
                val symbol = stream.u16()
                val count = stream.u16()
                if (symbol !in last + 1 until SYMBOLS || count == 0) throw VerseTestFormatFailure()
                ids[i] = symbol
                cumulative[i + 1] = cumulative[i] + count
                last = symbol
            }
            rows[context] = SuccessorRow(ids, cumulative)
            lastRow = context
        }
    }

    fun normalize(index: Int, weight: Long, total: Long): Int =
        index + (weight * (WORD_PROBABILITY_TOTAL - SYMBOLS) / total).toInt()

    private fun ByteBuffer.u16(): Int = short.toInt() and 65535

    companion object {
        const val ASSET_BYTES = 153_939
        const val ASSET_SHA256 = "be3ab914d4ce250a2c23db6982cb040a131d7f9ba397d648cb95c641991fc01f"
        private const val SYMBOLS = 5_410
        private const val ROWS = 2_819
    }
}

internal class SuccessorRow(private val ids: IntArray, private val cumulative: IntArray) {
    val total get() = cumulative.last()
    fun before(symbol: Int): Int {
        val index = ids.binarySearch(symbol)
        return cumulative[if (index >= 0) index else -index - 1]
    }
}

/** Match exact byte prefixes without allocating substrings of the source draft. */
internal class WordMatcher(tokens: Array<ByteArray>) {
    private val capacity = 1 + tokens.sumOf(ByteArray::size)
    private val roots = IntArray(256)
    private val child = IntArray(capacity)
    private val sibling = IntArray(capacity)
    private val keys = ByteArray(capacity)
    private val terminal = IntArray(capacity)
    private var nodes = 1

    init {
        for (symbol in 1 until tokens.size) {
            val token = tokens[symbol]
            val key = token[0].toInt() and 255
            var node = roots[key]
            if (node == 0) {
                node = nodes++
                roots[key] = node
            }
            for (index in 1 until token.size) node = append(node, token[index])
            if (terminal[node] != 0) throw VerseTestFormatFailure()
            terminal[node] = symbol
        }
    }

    private fun append(parent: Int, key: Byte): Int {
        val known = findChild(parent, key)
        if (known != 0) return known
        val next = nodes++
        keys[next] = key
        sibling[next] = child[parent]
        child[parent] = next
        return next
    }

    private fun findChild(parent: Int, key: Byte): Int {
        var node = child[parent]
        while (node != 0 && keys[node] != key) node = sibling[node]
        return node
    }

    fun visit(raw: ByteArray, offset: Int, match: (Int, Int) -> Unit) {
        var node = roots[raw[offset].toInt() and 255]
        var end = offset + 1
        while (node != 0) {
            val symbol = terminal[node]
            if (symbol != 0) match(symbol, end)
            if (end == raw.size) break
            node = findChild(node, raw[end++])
        }
    }
}

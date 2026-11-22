package dev.zeroinput.ime.core.stego

internal class WordHuffmanTree(codes: IntArray, widths: IntArray) {
    private val edges = IntArray(4 * codes.size)
    private val symbols = IntArray(2 * codes.size) { -1 }

    init {
        var nodes = 1
        for (symbol in codes.indices) {
            var node = 0
            for (shift in widths[symbol] - 1 downTo 0) {
                if (symbols[node] >= 0) throw VerseTestFormatFailure()
                val edge = 2 * node + ((codes[symbol] ushr shift) and 1)
                if (edges[edge] == 0) {
                    if (nodes == symbols.size) throw VerseTestFormatFailure()
                    edges[edge] = nodes++
                }
                node = edges[edge]
            }
            if (symbols[node] >= 0 || edges[2 * node] != 0 || edges[2 * node + 1] != 0)
                throw VerseTestFormatFailure()
            symbols[node] = symbol
        }
    }

    fun read(input: WordBitReader): Int {
        var node = 0
        while (symbols[node] < 0) {
            node = edges[2 * node + input.take(1)]
            if (node == 0) throw VerseTestFormatFailure()
        }
        return symbols[node]
    }
}

internal class WordBitReader(private val input: ByteArray, byteOffset: Int) {
    private var position = byteOffset * 8
    private val limit = input.size * 8

    fun take(width: Int): Int {
        if (width > limit - position) throw VerseTestFormatFailure()
        var value = 0
        repeat(width) {
            value = (value shl 1) or ((input[position / 8].toInt() ushr (7 - position % 8)) and 1)
            position++
        }
        return value
    }

    fun finish() {
        val remaining = limit - position
        if (remaining >= 8 || take(remaining) != 0) throw VerseTestFormatFailure()
    }
}

internal class WordBitWriter(private val output: ByteArray, byteOffset: Int) {
    private var position = byteOffset * 8

    fun put(value: Int, width: Int) {
        for (shift in width - 1 downTo 0) {
            val byte = position / 8
            val bit = ((value ushr shift) and 1) shl (7 - position % 8)
            output[byte] = (output[byte].toInt() or bit).toByte()
            position++
        }
    }
}

package dev.zeroinput.ime.core.stego

private const val LOWER_STATE = 1L shl 23
private const val BACKOFF = 16
private const val REPEAT_WEIGHT = 4
private const val STATE_BYTES = 4

/** Only previously processed symbols affect a cursor. Each call owns and wipes its cache. */
internal class WordContextCursor(private val model: WordContextModel, private val bigram: Boolean) : AutoCloseable {
    private val recent = IntArray(model.tokens.size) { -1 }
    private var previous = -1

    fun distribution(): WordDistribution =
        WordDistribution(model, if (bigram) model.rows[previous + 1] else null, recent.getOrElse(previous) { -1 })

    fun advance(symbol: Int) {
        if (previous > 0 && symbol > 0) recent[previous] = symbol
        previous = symbol
    }

    override fun close() {
        recent.fill(0)
        previous = -1
    }
}

internal class WordDistribution(
    private val model: WordContextModel,
    private val row: SuccessorRow?,
    private val recent: Int,
) {
    private val contextWeight = (row?.total ?: 0) + if (recent >= 0) REPEAT_WEIGHT else 0
    private val globalWeight = model.prefixes.last().toLong()

    fun boundary(symbol: Int): Int {
        if (contextWeight == 0) return model.global[symbol]
        val extra = (row?.before(symbol) ?: 0) + if (recent in 0 until symbol) REPEAT_WEIGHT else 0
        val weight = BACKOFF.toLong() * model.prefixes[symbol] + globalWeight * extra
        return model.normalize(symbol, weight, globalWeight * (BACKOFF + contextWeight))
    }

    fun symbol(slot: Int): Int {
        if (contextWeight == 0) {
            val index = model.global.binarySearch(slot)
            return if (index >= 0) index else -index - 2
        }
        var low = 0
        var high = model.tokens.size
        while (low + 1 < high) {
            val middle = (low + high) ushr 1
            if (boundary(middle) <= slot) low = middle else high = middle
        }
        return low
    }
}

internal class WordRansWriter(maxOperations: Int) : AutoCloseable {
    private val starts = IntArray(maxOperations)
    private val frequencies = IntArray(maxOperations)
    private var size = 0

    fun put(start: Int, frequency: Int) {
        starts[size] = start
        frequencies[size++] = frequency
    }

    fun uniform(value: Int, symbols: Int) {
        val step = WORD_PROBABILITY_TOTAL / symbols
        put(value * step, step)
    }

    fun pack(mode: WordPacketMode, originalSize: Int, current: () -> Boolean): ByteArray? {
        val scratch = ByteArray(WordContextBody.MAX_PACKET_BYTES)
        try {
            var state = LOWER_STATE
            var end = scratch.size
            for (step in size - 1 downTo 0) {
                if (step % 64 == 0) checkWordOperation(current)
                val frequency = frequencies[step]
                val limit = (LOWER_STATE / WORD_PROBABILITY_TOTAL) * 256 * frequency
                while (state >= limit) {
                    if (end == WORD_PACKET_HEADER + STATE_BYTES) return null
                    scratch[--end] = state.toByte()
                    state = state ushr 8
                }
                state = (state / frequency) * WORD_PROBABILITY_TOTAL + state % frequency + starts[step]
            }
            end -= STATE_BYTES
            repeat(STATE_BYTES) { scratch[end + it] = (state ushr (8 * it)).toByte() }
            val packet = wordPacket(mode, originalSize, scratch.size - end)
            scratch.copyInto(packet, WORD_PACKET_HEADER, startIndex = end)
            return packet
        } finally { scratch.fill(0) }
    }

    override fun close() {
        starts.fill(0)
        frequencies.fill(0)
        size = 0
    }
}

internal class WordRansReader(private val packet: ByteArray) {
    private var state = 0L
    private var offset = WORD_PACKET_HEADER + STATE_BYTES

    init {
        if (packet.size < offset) throw VerseTestFormatFailure()
        repeat(STATE_BYTES) { state = state or ((packet[WORD_PACKET_HEADER + it].toLong() and 255) shl (8 * it)) }
        if (state < LOWER_STATE || state >= LOWER_STATE * 256) throw VerseTestFormatFailure()
    }

    val slot get() = (state % WORD_PROBABILITY_TOTAL).toInt()

    fun take(start: Int, frequency: Int) {
        state = (state / WORD_PROBABILITY_TOTAL) * frequency + slot - start
        while (state < LOWER_STATE) {
            if (offset == packet.size) throw VerseTestFormatFailure()
            state = (state shl 8) or (packet[offset++].toLong() and 255)
        }
    }

    fun uniform(symbols: Int): Int {
        val step = WORD_PROBABILITY_TOTAL / symbols
        val value = slot / step
        take(value * step, step)
        return value
    }

    fun finish() {
        if (offset != packet.size || state != LOWER_STATE) throw VerseTestFormatFailure()
    }
}

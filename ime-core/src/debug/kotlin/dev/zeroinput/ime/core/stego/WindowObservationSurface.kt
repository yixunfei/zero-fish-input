package dev.zeroinput.ime.core.stego

import java.math.BigInteger

/** A worker-local encoder/decoder. It can reuse count rows for the sender's immediate readback. */
class WindowObservationSurface internal constructor(
    grammar: WindowObservationGrammar,
    private val current: () -> Boolean,
) {
    private val states = grammar.states
    private var counts: WindowObservationCounts? = null

    fun checkCapacity(size: Int) {
        ensureCurrent()
        if (size !in 1..MAX_ENVELOPE_BYTES) throw VerseTestCapacityFailure()
    }

    fun encode(envelope: ByteArray): String {
        checkCapacity(envelope.size)
        val rank = WordingFrame.pack(envelope, MAX_ENVELOPE_BYTES) - BigInteger.ONE
        val table = WindowObservationCounts(states, ::ensureCurrent)
        val text = render(rank, table.cover(rank), table)
        counts = table
        return text
    }

    fun decode(carrier: String): ByteArray {
        val prepared = counts
        counts = null
        val path = parse(carrier)
        val table = prepared ?: WindowObservationCounts(states, ::ensureCurrent)
        table.prepare(carrier.length)
        val rank = rank(path, carrier.length, table)
        val envelope = WordingFrame.unpack(rank + BigInteger.ONE, MAX_ENVELOPE_BYTES)
        try {
            if (render(rank, carrier.length, table) != carrier) throw VerseTestFormatFailure()
            return envelope
        } catch (failure: Throwable) {
            envelope.fill(0)
            throw failure
        }
    }

    private fun parse(carrier: String): List<WindowObservationGrammar.Choice> {
        ensureCurrent()
        if (carrier.length !in 1..MAX_UNITS || carrier.last() != STOP) throw VerseTestFormatFailure()
        val path = ArrayList<WindowObservationGrammar.Choice>()
        var offset = 0
        var state = states.first()
        while (offset < carrier.length) {
            ensureCurrent()
            val end = carrier.indexOf(STOP, offset)
            val choice = state.choices[carrier.substring(offset, end)] ?: throw VerseTestFormatFailure()
            path.add(choice)
            state = states[state.groups[choice.group].target]
            offset = end + 1
        }
        if (!state.ending) throw VerseTestFormatFailure()
        return path
    }

    private fun rank(path: List<WindowObservationGrammar.Choice>, length: Int, table: WindowObservationCounts): BigInteger {
        var value = table.cumulative(length - 1)
        var remaining = length
        var groups = states.first().groups
        for (choice in path) {
            ensureCurrent()
            for (index in 0 until choice.group) {
                val earlier = groups[index]
                value += table.suffix(earlier.target, remaining - earlier.units).timesCount(earlier.sentences.size)
            }
            val group = groups[choice.group]
            remaining -= group.units
            value += table.suffix(group.target, remaining).timesCount(choice.index)
            groups = states[group.target].groups
        }
        return value
    }

    private fun render(value: BigInteger, length: Int, table: WindowObservationCounts): String {
        var rank = value - table.cumulative(length - 1)
        var remaining = length
        var state = states.first()
        val result = StringBuilder(length)
        while (remaining > 0) {
            ensureCurrent()
            val groups = state.groups
            var selected: WindowObservationGrammar.Group? = null
            for (group in groups) {
                if (group.units > remaining) break
                val suffix = table.suffix(group.target, remaining - group.units)
                val block = suffix.timesCount(group.sentences.size)
                if (rank < block) {
                    val division = rank.divideAndRemainder(suffix)
                    result.append(group.sentences[division[0].toInt()]).append(STOP)
                    rank = division[1]
                    selected = group
                    break
                }
                rank -= block
            }
            val next = selected ?: throw VerseTestFormatFailure()
            remaining -= next.units
            state = states[next.target]
        }
        ensureCurrent()
        if (!state.ending || rank.signum() != 0) throw VerseTestFormatFailure()
        return result.toString()
    }

    private fun ensureCurrent() {
        if (!current()) throw VerseTestFormatFailure()
    }

    companion object {
        const val MAX_ENVELOPE_BYTES = 833
        internal const val MAX_UNITS = StegoCarrierCodec.MAX_BYTES / 3
        private const val STOP = '。'
    }
}

internal fun BigInteger.timesCount(count: Int): BigInteger = when (count) {
    0 -> BigInteger.ZERO
    1 -> this
    else -> if (signum() == 0) this else multiply(BigInteger.valueOf(count.toLong()))
}

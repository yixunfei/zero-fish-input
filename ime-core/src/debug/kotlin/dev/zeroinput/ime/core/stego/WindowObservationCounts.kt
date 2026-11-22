package dev.zeroinput.ime.core.stego

import java.math.BigInteger

/** Exact suffix counts. Folding a required follow-up affects counts only, never ranking order. */
internal class WindowObservationCounts(
    states: List<WindowObservationGrammar.State>,
    private val ensureCurrent: () -> Unit,
) {
    private data class Edge(val units: Int, val target: Int)
    private data class Term(val edge: Edge, val count: Int)
    private data class Signature(val ending: Boolean, val terms: Map<Edge, Int>)
    private class Row(signature: Signature) {
        val terms = signature.terms.map { Term(it.key, it.value) }
        val values = ArrayList<BigInteger>().apply { add(if (signature.ending) BigInteger.ONE else BigInteger.ZERO) }
    }

    private val steps = states.map { state -> state.groups.map { Term(Edge(it.units, it.target), it.sentences.size) } }
    private val folded = states.mapIndexed { index, state ->
        steps[index].takeIf { !state.ending && it.all { term -> states[term.edge.target].ending } }
    }
    private val sharedRows = mutableMapOf<Signature, Row>()
    private val rows = states.mapIndexed { index, state ->
        if (folded[index] != null) null else {
            val signature = Signature(state.ending, foldSteps(steps[index]))
            sharedRows.getOrPut(signature) { Row(signature) }
        }
    }
    private val totals = arrayListOf(BigInteger.ZERO)

    private fun foldSteps(terms: List<Term>): Map<Edge, Int> = buildMap {
        for (term in terms) {
            val edge = term.edge
            val follow = folded[edge.target]
            if (follow == null) {
                put(edge, getOrDefault(edge, 0) + term.count)
            } else {
                for ((tail, choices) in follow) {
                    val combined = Edge(edge.units + tail.units, tail.target)
                    put(combined, getOrDefault(combined, 0) + term.count * choices)
                }
            }
        }
    }

    fun cover(rank: BigInteger): Int {
        val limit = WindowObservationSurface.MAX_UNITS
        while (totals.last() <= rank && totals.size <= limit) nextLength()
        if (totals.last() <= rank) throw VerseTestCapacityFailure()
        return totals.lastIndex
    }

    fun prepare(length: Int) {
        while (totals.size <= length) nextLength()
    }

    private fun nextLength() {
        val length = totals.size
        if (length % 32 == 1) ensureCurrent()
        for (row in sharedRows.values) row.values.add(count(row.terms, length))
        totals.add(totals.last() + suffix(0, length))
    }

    fun cumulative(length: Int): BigInteger = totals[length]

    fun suffix(state: Int, length: Int): BigInteger {
        if (length < 0) return BigInteger.ZERO
        return rows[state]?.values?.get(length) ?: count(requireNotNull(folded[state]), length)
    }

    private fun count(terms: List<Term>, length: Int): BigInteger {
        var total = BigInteger.ZERO
        for ((edge, count) in terms) {
            val remaining = length - edge.units
            if (remaining >= 0) total += requireNotNull(rows[edge.target]).values[remaining].timesCount(count)
        }
        return total
    }
}

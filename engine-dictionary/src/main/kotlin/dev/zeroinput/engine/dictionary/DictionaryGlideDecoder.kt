package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.GlideCandidate
import dev.zeroinput.engine.api.GlideDecoder
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlideLexiconEntry
import dev.zeroinput.engine.api.GlideRequest
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln

/**
 * Public lexicon index prepared on a worker. Each decode owns all mutable search state.
 * No editor, personal dictionary, I/O, platform service or background executor is accessed.
 */
class DictionaryGlideDecoder(
    entries: List<GlideLexiconEntry>,
    isCancelled: () -> Boolean = { false },
) : GlideDecoder {
    private data class Endpoint(val layout: GlideLayout, val first: Char, val last: Char)
    private data class IndexedEntry(val value: GlideLexiconEntry, val pathCode: String, val prior: Float)
    private data class Match(val entry: IndexedEntry, val path: GlidePath, val cost: Float)

    private val index: Map<Endpoint, List<IndexedEntry>>

    init {
        require(entries.size in 1..MAX_ENTRIES) { "Invalid public glide lexicon size" }
        val unique = HashMap<GlideLayout, MutableMap<String, GlideLexiconEntry>>()
        entries.forEachIndexed { index, entry ->
            if (index % CANCELLATION_INTERVAL == 0 && cancelled(isCancelled)) {
                throw CancellationException("Glide index cancelled")
            }
            val codes = unique.getOrPut(entry.layout) { HashMap() }
            if (codes[entry.inputCode]?.frequency?.let { it >= entry.frequency } != true) codes[entry.inputCode] = entry
        }
        index = unique.values.asSequence().flatMap { it.values }.map { entry ->
            val code = physicalCode(entry.inputCode)
            val prior = (ln(1f + entry.frequency) / 16f).coerceIn(0f, 1f)
            IndexedEntry(entry, code, prior)
        }.groupBy { entry -> Endpoint(entry.value.layout, entry.pathCode.first(), entry.pathCode.last()) }
            .mapValues { (_, values) -> values.sortedByDescending { it.value.frequency } }
        if (cancelled(isCancelled)) throw CancellationException("Glide index cancelled")
    }

    override fun decode(request: GlideRequest, isCancelled: () -> Boolean): List<GlideCandidate> {
        if (cancelled(isCancelled)) return emptyList()
        val geometry = GlideGeometry(request)
        val starts = geometry.endpointKeys(start = true)
        val ends = geometry.endpointKeys(start = false)
        if (starts.isEmpty() || ends.isEmpty()) return emptyList()
        val shortlist = shortlist(request.layout, starts, ends, geometry, isCancelled) ?: return emptyList()
        val ranked = ArrayList<Pair<IndexedEntry, Float>>(shortlist.size)
        for (match in shortlist) {
            if (cancelled(isCancelled)) return emptyList()
            val trace = geometry.trace
            val shape = trace.warpedDistance(match.path) * 0.7f + trace.alignedDistance(match.path) * 0.3f
            val cost = shape + trace.endpointDistance(match.path) * 0.45f +
                lengthPenalty(trace, match.path) + (1f - match.entry.prior) * 0.18f
            if (cost <= MAX_ACCEPTED_COST) ranked += match.entry to cost
        }
        if (cancelled(isCancelled)) return emptyList()
        return ranked.sortedWith(compareBy<Pair<IndexedEntry, Float>> { it.second }
            .thenByDescending { it.first.value.frequency }.thenBy { it.first.value.inputCode })
            .take(request.maxCandidates).map { (entry, cost) ->
                GlideCandidate(entry.value.inputCode, entry.value.displayText, -cost)
            }
    }

    private fun shortlist(
        layout: GlideLayout,
        starts: List<Char>,
        ends: List<Char>,
        geometry: GlideGeometry,
        isCancelled: () -> Boolean,
    ): List<Match>? {
        val heap = PriorityQueue<Match>(compareByDescending<Match> { it.cost }
            .thenBy { it.entry.value.frequency })
        var inspected = 0
        // Each endpoint bucket has its own allowance; exact keys never starve an adjacent endpoint.
        for (start in starts) for (end in ends) {
            for (entry in index[Endpoint(layout, start, end)].orEmpty().take(MAX_BUCKET_SCAN)) {
                if (inspected++ % CANCELLATION_INTERVAL == 0 && cancelled(isCancelled)) return null
                val path = geometry.template(entry.pathCode) ?: continue
                val cost = geometry.trace.alignedDistance(path) + lengthPenalty(geometry.trace, path) +
                    geometry.trace.endpointDistance(path) * 0.45f + (1f - entry.prior) * 0.18f
                if (heap.size < MAX_SHORTLIST) heap.add(Match(entry, path, cost))
                else if (cost < heap.peek().cost) {
                    heap.remove()
                    heap.add(Match(entry, path, cost))
                }
            }
        }
        return heap.toList()
    }

    private fun lengthPenalty(first: GlidePath, second: GlidePath): Float =
        abs(ln((first.length + 1f) / (second.length + 1f))) * 0.15f

    private fun cancelled(check: () -> Boolean) = Thread.currentThread().isInterrupted || check()

    companion object {
        const val MAX_ENTRIES = 500_000
        const val MAX_BUCKET_SCAN = 4_096
        private const val MAX_SHORTLIST = 96
        private const val CANCELLATION_INTERVAL = 32
        private const val MAX_ACCEPTED_COST = 3.5f

        private fun physicalCode(code: String): String {
            if (code.indices.none { code[it] == '\'' || (it > 0 && code[it] == code[it - 1]) }) return code
            return buildString {
                // Doubled letters/digits can share one dwell; keep distinct dictionary alternatives.
                for (character in code) if (character != '\'' && lastOrNull() != character) append(character)
            }
        }
    }
}

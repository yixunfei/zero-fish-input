package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.Candidate
import kotlin.math.ln

/** Engine scores use incompatible units; use stable rank priors and a bounded personal bonus. */
internal object PersonalCandidateRanking {
    fun merge(native: List<Candidate>, personal: List<Candidate>, input: String, threshold: Int,
        frequencies: Map<String, Int> = emptyMap()): List<Candidate> {
        val learned = personal.groupBy(Candidate::text).mapValues { (_, rows) ->
            rows.maxWith(compareBy<Candidate> { it.input == input }.thenBy { it.score })
        }
        val nativeRanks = native.mapIndexed { index, candidate -> candidate.text to index }.toMap()
        return (native + personal).distinctBy(Candidate::text).map { learned[it.text] ?: it }
            .sortedByDescending { candidate ->
                val prior = nativeRanks[candidate.text]?.let { 1.0 / (it + 1) } ?: 0.0
                val learnedCandidate = learned[candidate.text]
                // A native match already validates the spelling. Reuse the word's existing
                // frequency across full/abbreviated input without inventing a personal route.
                val wordCount = if (candidate.text in nativeRanks) frequencies[candidate.text] ?: 0 else 0
                val count = maxOf(learnedCandidate?.score ?: 0, wordCount)
                val bonus = if (count < threshold) 0.0 else {
                    val match = if (wordCount >= threshold || learnedCandidate?.input == input) 1.0 else 0.55
                    match * (1.1 + ln(count.coerceAtMost(1_000_000).toDouble() / threshold))
                }
                prior + bonus
            }
    }
}

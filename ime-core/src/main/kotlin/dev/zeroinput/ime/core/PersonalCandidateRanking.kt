package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.Candidate
import kotlin.math.ln

/** Engine scores use incompatible units; use stable rank priors and a bounded personal bonus. */
internal object PersonalCandidateRanking {
    fun merge(native: List<Candidate>, personal: List<Candidate>, input: String, threshold: Int): List<Candidate> {
        val learned = personal.groupBy(Candidate::text).mapValues { (_, rows) ->
            rows.maxWith(compareBy<Candidate> { it.input == input }.thenBy { it.score })
        }
        val nativeRanks = native.mapIndexed { index, candidate -> candidate.text to index }.toMap()
        return (native + personal).distinctBy(Candidate::text).map { learned[it.text] ?: it }
            .sortedByDescending { candidate ->
                val prior = nativeRanks[candidate.text]?.let { 1.0 / (it + 1) } ?: 0.0
                val learnedCandidate = learned[candidate.text]
                val count = learnedCandidate?.score ?: 0
                val bonus = if (count < threshold) 0.0 else {
                    val match = if (learnedCandidate?.input == input) 1.0 else 0.55
                    match * (1.1 + ln(count.coerceAtMost(1_000_000).toDouble() / threshold))
                }
                prior + bonus
            }
    }
}

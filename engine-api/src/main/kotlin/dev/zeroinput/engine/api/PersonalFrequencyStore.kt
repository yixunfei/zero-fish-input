package dev.zeroinput.engine.api

/**
 * Optional read-only lookup of learned frequencies by word value.
 *
 * Callers supply the values to inspect; the result cannot enumerate the
 * store because unknown words are simply absent.  It is used to rerank the
 * bounded next-word association strip and may be invoked on the IME input
 * thread, so implementations must answer from prepared in-memory state and
 * never touch disk, keystores or decryption.  Missing data, failures and
 * unready stores all return an empty map, preserving editorial order.
 */
interface PersonalFrequencyStore : PersonalizationStore {
    /**
     * Returns an immutable, bounded lookup prepared on a background worker.
     * The snapshot accepts only caller-supplied values and cannot enumerate
     * the personal store. A null result means frequency ranking is unavailable.
     */
    fun frequencyLookupSnapshot(): PersonalFrequencyLookup? = null

    /**
     * Returns the learned frequency of each known value in [words] for
     * [language], summing entries that share the same value. English values
     * are compared case-insensitively; other languages use exact matching.
     * At most [MAX_LOOKUP] non-blank distinct values are considered. No prefix
     * or shortcut semantics apply. Implementations used on the IME thread must
     * answer from a prepared in-memory snapshot.
     */
    fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int>

    companion object {
        /** Display bound of the association strip; also the lookup bound. */
        const val MAX_LOOKUP = 8
    }
}

/** Exact lookup capability over a prepared personal-frequency snapshot. */
fun interface PersonalFrequencyLookup {
    fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int>
}

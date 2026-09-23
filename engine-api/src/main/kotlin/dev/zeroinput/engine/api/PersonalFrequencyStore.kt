package dev.zeroinput.engine.api

/**
 * Optional read-only lookup of learned frequencies by exact word value.
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
     * Returns the learned frequency of each known value in [words] for
     * [language], summing entries that share the same value.  At most
     * [MAX_LOOKUP] non-blank distinct values are considered.  Values are
     * matched exactly; no prefix or shortcut semantics apply.
     */
    fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int>

    companion object {
        /** Display bound of the association strip; also the lookup bound. */
        const val MAX_LOOKUP = 8
    }
}

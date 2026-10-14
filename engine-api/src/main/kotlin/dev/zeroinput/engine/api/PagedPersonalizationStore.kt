package dev.zeroinput.engine.api

data class PersonalSuggestionPage(
    val items: List<PersonalSuggestion> = emptyList(),
    val hasMore: Boolean = false,
    val ready: Boolean = true,
    val revision: Long = 0,
    /** Local dictionary edits for this reading; never supplied in a private editor. */
    val dictionaryEditsApplied: Boolean = false,
    val suppressedDictionaryTexts: Set<String> = emptySet(),
    /** Canonical text + tab + reading, prepared on a worker for native candidate readings. */
    val suppressedDictionaryEntries: Set<String> = emptySet(),
)

/** Offset is relative to one stable, deterministically ranked query revision. */
interface PagedPersonalizationStore : PersonalizationStore {
    fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int): PersonalSuggestionPage
}

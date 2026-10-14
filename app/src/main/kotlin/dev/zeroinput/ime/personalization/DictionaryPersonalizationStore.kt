package dev.zeroinput.ime.personalization

import dev.zeroinput.engine.api.*
import dev.zeroinput.engine.rime.DictionaryEditReading
import dev.zeroinput.userdata.DictionaryEditRepository
import dev.zeroinput.userdata.UserLexiconRepository

/** Invoked only by QueuedPersonalizationStore, after the editor's privacy gate. */
internal class DictionaryPersonalizationStore(
    private val lexicon: UserLexiconRepository,
    private val edits: DictionaryEditRepository,
    private val activeIds: () -> Set<String>,
    private val options: () -> ChineseInputOptions,
) : PagedPersonalizationStore, PersonalFrequencyStore {
    @Volatile var requiresFiltering: Boolean = true
        private set
    private val preparationLock = Any()
    private var preparationRevision = 0L

    fun prepare() {
        val revision = synchronized(preparationLock) { preparationRevision }
        val active = activeIds()
        val present = edits.read().entries.any { it.dictionaryId in active }
        synchronized(preparationLock) { if (revision == preparationRevision) requiresFiltering = present }
    }

    fun invalidate() = synchronized(preparationLock) { preparationRevision++; requiresFiltering = true }

    override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int): PersonalSuggestionPage {
        if (language != InputLanguage.CHINESE || !requiresFiltering) return lexicon.suggestionPage(prefix, language, offset, limit)
        val configuration = options()
        val active = activeIds()
        val selected = edits.read().entries.filter { it.dictionaryId in active }
        if (selected.isEmpty()) return lexicon.suggestionPage(prefix, language, offset, limit)
        val originals = selected.mapNotNull { it.original }.map {
            it to DictionaryEditReading.displayText(it.text, configuration.script)
        }
        val hidden = originals.filter { (word, _) -> word.reading.isEmpty() || DictionaryEditReading.matches(word.reading, prefix, configuration) }
            .mapTo(HashSet()) { it.second }
        val hiddenEntries = originals.mapTo(HashSet()) { (word, text) -> "$text\t${word.reading.replace(" ", "")}" }
        val replacements = selected.mapNotNull { entry ->
            val word = entry.replacement ?: return@mapNotNull null
            if (!DictionaryEditReading.matches(word.reading, prefix, configuration)) return@mapNotNull null
            PersonalSuggestion("dictionary:${entry.dictionaryId}:${entry.key}",
                DictionaryEditReading.displayText(word.text, configuration.script), word.weight, word.reading.replace(" ", ""))
        }
        val learned = lexicon.list(language).filter { it.shortcut.startsWith(prefix) }.mapNotNull { term ->
            val text = DictionaryEditReading.displayText(term.value, configuration.script)
            if (text in hidden) null else PersonalSuggestion(term.id, text, term.frequency, term.shortcut)
        }
        val values = (replacements.sortedWith(compareByDescending<PersonalSuggestion> { it.frequency }.thenBy { it.id }) +
            learned).distinctBy { it.text }.take(20000)
        return PersonalSuggestionPage(values.drop(offset).take(limit), values.size > offset + limit,
            suppressedDictionaryTexts = hidden, suppressedDictionaryEntries = hiddenEntries, dictionaryEditsApplied = selected.isNotEmpty())
    }

    override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = suggestionPage(prefix, language, 0, limit).items
    override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) =
        lexicon.learn(shortcut, value, language, learningAllowed)
    override fun recordUse(id: String, learningAllowed: Boolean) {
        if (!id.startsWith("dictionary:")) lexicon.recordUse(id, learningAllowed)
    }
    override fun frequenciesFor(words: List<String>, language: InputLanguage) = lexicon.frequenciesFor(words, language)
    override fun clear() { try { lexicon.clear() } finally { edits.clear() } }
}

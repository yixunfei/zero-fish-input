package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.*

/** A bounded personal page; unresolved worker queries preserve the last visible page. */
internal class PersonalCandidatePaging {
    private var input = ""
    private var composition = ""
    private var offset = 0
    private var requested = 0
    private var page = PersonalSuggestionPage()
    private var first = PersonalSuggestionPage()
    private var revision: Long? = null
    val isBrowsing: Boolean get() = offset > 0

    fun clear() {
        input = ""
        composition = ""
        offset = 0
        requested = 0
        page = PersonalSuggestionPage()
        first = page
        revision = null
    }

    fun changePage(direction: PageDirection, native: EngineSnapshot): Boolean {
        if (direction == PageDirection.PREVIOUS && offset > 0) {
            requested = (offset - PAGE_SIZE).coerceAtLeast(0)
            return true
        }
        if (direction == PageDirection.NEXT && (offset > 0 || !native.hasNextPage) && page.hasMore) {
            requested = offset + PAGE_SIZE
            return true
        }
        return false
    }

    fun publish(native: EngineSnapshot, store: PersonalizationStore, language: InputLanguage,
        allowed: Boolean, normalize: (String) -> String?): EngineSnapshot {
        if (!allowed || native.rawInput.isBlank() || native.canUndoSelection) { clear(); return native }
        if (input != native.rawInput || composition != native.composition) {
            clear()
            input = native.rawInput
            composition = native.composition
        }
        val loaded = runCatching {
            (store as? PagedPersonalizationStore)?.suggestionPage(input, language, requested, PAGE_SIZE)
                ?: PersonalSuggestionPage(store.suggestionsFor(input, language, PAGE_SIZE))
        }.getOrDefault(PersonalSuggestionPage())
        if (revision != null && revision != loaded.revision) {
            val wasFirstPage = requested == 0
            offset = 0
            requested = 0
            page = PersonalSuggestionPage()
            first = page
            revision = loaded.revision
            if (!wasFirstPage) return native
        }
        revision = loaded.revision
        if (loaded.ready) {
            offset = requested
            page = loaded
            if (offset == 0) first = loaded
        }
        val personal = page.items.mapNotNull { term ->
            normalize(term.text)?.let { Candidate("personal:${term.id}", it, "个人词组", term.frequency, term.input) }
        }
        val items = when {
            offset > 0 -> personal
            // The personal page is empty on most keystrokes: skip the
            // concatenation and the deduplication set.  Native candidate
            // lists are text-unique by construction, so passing them through
            // unchanged matches the merged result.
            personal.isEmpty() -> native.candidates
            else -> (personal + native.candidates).distinctBy(Candidate::text)
        }
        val highlightedId = native.candidates.getOrNull(native.highlightedIndex)?.id
        return native.copy(candidates = items, highlightedIndex = if (offset > 0) 0 else
            items.indexOfFirst { it.id == highlightedId }.coerceAtLeast(0),
            hasPreviousPage = offset > 0 || native.hasPreviousPage,
            hasNextPage = if (offset > 0) page.hasMore else native.hasNextPage || first.hasMore)
    }

    companion object { const val PAGE_SIZE = 8 }
}

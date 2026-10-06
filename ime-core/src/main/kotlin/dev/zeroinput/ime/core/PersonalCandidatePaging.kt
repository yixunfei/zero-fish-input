package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.*
import java.util.TreeMap

/** A bounded personal page; unresolved worker queries preserve the last visible page. */
internal class PersonalCandidatePaging {
    private var input = ""
    private var composition = ""
    private var offset = 0
    private var requested = 0
    private var page = PersonalSuggestionPage()
    private var first = PersonalSuggestionPage()
    private val pages = TreeMap<Int, PersonalSuggestionPage>()
    private var revision: Long? = null
    val isBrowsing: Boolean get() = offset > 0

    fun clear() {
        input = ""
        composition = ""
        offset = 0
        requested = 0
        page = PersonalSuggestionPage()
        first = page
        pages.clear()
        revision = null
    }

    fun changePage(direction: PageDirection, native: EngineSnapshot): Boolean {
        val start = pages.firstKeyOrNull()
        if (direction == PageDirection.PREVIOUS && start != null && start > 0) {
            requested = (start - PAGE_SIZE).coerceAtLeast(0)
            return true
        }
        val last = pages.lastEntry()
        if (direction == PageDirection.NEXT && (offset > 0 || !native.hasNextPage) && last?.value?.hasMore == true) {
            requested = last.key + PAGE_SIZE
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
            pages.clear()
            revision = loaded.revision
            if (!wasFirstPage) return native
        }
        revision = loaded.revision
        if (loaded.ready) {
            val goingBack = requested < offset
            offset = requested
            page = loaded
            if (offset == 0) first = loaded
            pages[offset] = loaded
            while (pages.size > MAX_PAGES) {
                if (goingBack) pages.pollLastEntry() else pages.pollFirstEntry()
            }
        }
        fun rows(page: PersonalSuggestionPage) = page.items.mapNotNull { term ->
            normalize(term.text)?.let { Candidate("personal:${term.id}", it, "个人词组", term.frequency, term.input) }
        }
        val includesNative = pages.isEmpty() || pages.firstKey() == 0
        val personal = pages.entries.flatMap { (start, value) ->
            if (start == 0) rows(value) + native.candidates else rows(value)
        }
        val items = if (personal.isEmpty() && includesNative) native.candidates else personal.distinctBy(Candidate::text)
        val highlightedId = native.candidates.getOrNull(native.highlightedIndex)?.id
        return native.copy(candidates = items, highlightedIndex = if (offset > 0) 0 else
            items.indexOfFirst { it.id == highlightedId }.coerceAtLeast(0),
            hasPreviousPage = !includesNative || native.hasPreviousPage,
            hasNextPage = if (isBrowsing) pages.lastEntry()?.value?.hasMore == true else native.hasNextPage || first.hasMore)
    }

    private fun TreeMap<Int, PersonalSuggestionPage>.firstKeyOrNull(): Int? = firstEntry()?.key

    companion object { const val PAGE_SIZE = 8; const val MAX_PAGES = 6 }
}

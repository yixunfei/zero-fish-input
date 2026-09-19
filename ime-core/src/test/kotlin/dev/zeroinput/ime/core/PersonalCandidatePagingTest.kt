package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class PersonalCandidatePagingTest {
    @Test fun `all personal entries are reachable after native exhaustion`() {
        val paging = PersonalCandidatePaging()
        val native = EngineSnapshot("ni", "ni", listOf(Candidate("native", "你")))
        val store = Store()
        fun visible() = paging.publish(native, store, InputLanguage.CHINESE, true) { it }
        var result = visible()
        val seen = mutableSetOf<String>()
        do {
            seen += result.candidates.filter { it.id.startsWith("personal:") }.map { it.id }
            if (!paging.changePage(PageDirection.NEXT, native)) break
            result = visible()
        } while (true)
        assertEquals(117, seen.size)
        assertFalse(result.hasNextPage)
        assertTrue(paging.changePage(PageDirection.PREVIOUS, native))
        assertTrue(visible().hasNextPage)
    }

    @Test fun `pending queries retain the visible page and privacy removes all personal state`() {
        val paging = PersonalCandidatePaging()
        val native = EngineSnapshot("ni", "ni")
        val store = Store()
        val first = paging.publish(native, store, InputLanguage.CHINESE, true) { it }
        paging.changePage(PageDirection.NEXT, native)
        store.ready = false
        assertEquals(first, paging.publish(native, store, InputLanguage.CHINESE, true) { it })
        assertEquals(native, paging.publish(native, store, InputLanguage.CHINESE, false) { it })
        store.ready = true
        assertEquals(first, paging.publish(native, store, InputLanguage.CHINESE, true) { it })
    }

    @Test fun `a resolved empty personal page passes native candidates through`() {
        val paging = PersonalCandidatePaging()
        val native = EngineSnapshot("ni", "ni", listOf(Candidate("native", "你"), Candidate("native2", "好")))
        val store = Store().apply { empty = true }
        val result = paging.publish(native, store, InputLanguage.CHINESE, true) { it }
        assertEquals(native.candidates, result.candidates)
        assertFalse(result.hasNextPage)
    }

    private class Store : PagedPersonalizationStore {
        var ready = true
        var revision = 0L
        var empty = false
        override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int) =
            if (!ready) PersonalSuggestionPage(ready = false, revision = revision)
            else if (empty) PersonalSuggestionPage(emptyList(), hasMore = false, revision = revision)
            else PersonalSuggestionPage(
                (offset until minOf(offset + limit, 117)).map { PersonalSuggestion("$it", "词组$it", 1, "ni") },
                offset + limit < 117, revision = revision)
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = suggestionPage(prefix, language, 0, limit).items
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }

    @Test fun `a new data revision hides old pages even when the replacement query is pending`() {
        val paging = PersonalCandidatePaging()
        val store = Store()
        val native = EngineSnapshot("ni", "ni")
        paging.publish(native, store, InputLanguage.CHINESE, true) { it }
        paging.changePage(PageDirection.NEXT, native)
        assertEquals(8, paging.publish(native, store, InputLanguage.CHINESE, true) { it }.candidates.size)
        store.revision++
        store.ready = false
        val hidden = paging.publish(native, store, InputLanguage.CHINESE, true) { it }
        assertTrue(hidden.candidates.isEmpty())
        assertFalse(paging.isBrowsing)
    }
}

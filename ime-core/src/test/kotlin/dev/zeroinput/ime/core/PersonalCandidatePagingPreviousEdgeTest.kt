package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test

/** Regression tests for the page-0 previous-edge semantics (review finding #1). */
class PersonalCandidatePagingPreviousEdgeTest {
    @Test fun `previous walking back through deep personal pages restores the merged first page`() {
        val paging = PersonalCandidatePaging()
        val native = EngineSnapshot("ni", "ni", listOf(Candidate("native", "你")))
        val store = Store()
        fun visible() = paging.publish(native, store, InputLanguage.CHINESE, true) { it }

        val first = visible()
        assertFalse(first.hasPreviousPage)
        assertEquals(native.candidates.size + PersonalCandidatePaging.PAGE_SIZE, first.candidates.size)

        // Browse deep enough that the merged first page is evicted from the
        // bounded window, then walk all the way back with PREVIOUS.
        repeat(PersonalCandidatePaging.MAX_PAGES + 1) {
            assertTrue(paging.changePage(PageDirection.NEXT, native))
            visible()
        }
        assertTrue(visible().hasPreviousPage)
        repeat(PersonalCandidatePaging.MAX_PAGES + 1) {
            if (paging.changePage(PageDirection.PREVIOUS, native)) visible()
        }
        val restored = visible()
        // Walking back reached offset 0 again: the native first page is
        // re-merged and the previous edge returns to the engine.
        assertFalse(paging.isBrowsing)
        assertEquals(first.candidates, restored.candidates.take(first.candidates.size))
        assertFalse(restored.hasPreviousPage)
        assertFalse(paging.changePage(PageDirection.PREVIOUS, native))
    }

    @Test fun `previous is advertised only while the merged first page is evicted`() {
        val paging = PersonalCandidatePaging()
        val native = EngineSnapshot("ni", "ni", listOf(Candidate("native", "你")))
        val store = Store()
        fun visible() = paging.publish(native, store, InputLanguage.CHINESE, true) { it }

        // While the merged first page is still cached, PREVIOUS belongs to the
        // engine and the personal layer declines.
        visible()
        assertFalse(visible().hasPreviousPage)
        assertFalse(paging.changePage(PageDirection.PREVIOUS, native))

        // One forward step still keeps page 0 cached, so PREVIOUS stays native.
        assertTrue(paging.changePage(PageDirection.NEXT, native))
        visible()
        assertFalse(visible().hasPreviousPage)

        // After enough forward steps page 0 is evicted and the personal layer
        // owns the previous edge again.
        repeat(PersonalCandidatePaging.MAX_PAGES) {
            assertTrue(paging.changePage(PageDirection.NEXT, native))
            visible()
        }
        assertTrue(visible().hasPreviousPage)
        assertTrue(paging.changePage(PageDirection.PREVIOUS, native))
        assertTrue(visible().hasPreviousPage)
    }

    private class Store : PagedPersonalizationStore {
        var revision = 0L
        override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int) =
            PersonalSuggestionPage(
                (offset until minOf(offset + limit, 117)).map { PersonalSuggestion("$it", "词组$it", 1, "ni") },
                offset + limit < 117, revision = revision)
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) =
            suggestionPage(prefix, language, 0, limit).items
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}

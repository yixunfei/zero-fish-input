package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class DictionaryCandidatePagingTest {
    private val native = EngineSnapshot("nihao", "ni hao", listOf(Candidate("original", "你好"), Candidate("other", "拟好")))
    @Test fun canonicalCandidateReadingsFilterTyposWithoutHidingAnotherPronunciation() {
        val paging = PersonalCandidatePaging()
        val store = Store(PersonalSuggestionPage(dictionaryEditsApplied = true,
            suppressedDictionaryEntries = setOf("你好\tnihao")))
        val snapshot = EngineSnapshot("nihoa", "nihoa", listOf(
            Candidate("corrected", "你好", input = "nihao"), Candidate("other_reading", "你好", input = "niha")))
        assertEquals(listOf("other_reading"), paging.publish(snapshot, store, InputLanguage.CHINESE, true) { it }.candidates.map { it.id })
    }
    @Test fun hiddenOriginalDoesNotReturnWhenEveryNativeCandidateIsFiltered() {
        val paging = PersonalCandidatePaging()
        val store = Store(PersonalSuggestionPage(suppressedDictionaryTexts = setOf("你好", "拟好"), dictionaryEditsApplied = true))
        assertTrue(paging.publish(native, store, InputLanguage.CHINESE, true) { it }.candidates.isEmpty())
        assertTrue(paging.editsApplied)
    }
    @Test fun replacementsTakeTheDefaultSelectionAndNativeRoutesKeepTheirIdentity() {
        val paging = PersonalCandidatePaging()
        val store = Store(PersonalSuggestionPage(listOf(PersonalSuggestion("replacement", "您好", 100)),
            suppressedDictionaryTexts = setOf("你好"), dictionaryEditsApplied = true))
        val result = paging.publish(native, store, InputLanguage.CHINESE, true) { it }
        assertEquals(listOf("personal:replacement", "other"), result.candidates.map { it.id })
        assertEquals(0, result.highlightedIndex)
    }
    @Test fun privateEditorsNeverQueryTheEditStoreAndPendingEditsCannotExposeOriginals() {
        val paging = PersonalCandidatePaging()
        val store = Store(PersonalSuggestionPage(ready = false, dictionaryEditsApplied = true))
        assertTrue(paging.publish(native, store, InputLanguage.CHINESE, true) { it }.candidates.isEmpty())
        assertTrue(paging.editsPending)
        val calls = store.calls
        assertEquals(native, paging.publish(native, store, InputLanguage.CHINESE, false) { it })
        assertEquals(calls, store.calls)
        assertFalse(paging.editsApplied)
    }
    private class Store(private val page: PersonalSuggestionPage) : PagedPersonalizationStore {
        var calls = 0
        override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int): PersonalSuggestionPage { calls++; return page }
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = page.items
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}

package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class PersonalCandidateFrequencyTest {
    private val native = EngineSnapshot("cs", "cs",
        listOf(Candidate("a", "产生", input = "chansheng"), Candidate("b", "测试", input = "ceshi")))

    @Test fun `full pinyin history promotes the matching initial candidate and highlights its native route`() {
        val store = Store()
        store.personal = listOf(PersonalSuggestion("term", "测试", 3, "ceshi"))
        val paging = PersonalCandidatePaging()
        val result = paging.publish(native, store, InputLanguage.CHINESE, true) { it }
        assertEquals(listOf("b", "a"), result.candidates.map { it.id })
        assertEquals(0, result.highlightedIndex)
        assertEquals("ceshi", result.candidates.first().input)
        assertEquals("产生", paging.publish(native, store, InputLanguage.CHINESE, true, 4) { it }.candidates.first().text)
    }

    @Test fun `private sessions never query word frequencies and failed lookups retain native ordering`() {
        val store = Store()
        val paging = PersonalCandidatePaging()
        assertEquals(native, paging.publish(native, store, InputLanguage.CHINESE, false) { it })
        assertEquals(0, store.reads)
        store.fail = true
        assertEquals(native.candidates, paging.publish(native, store, InputLanguage.CHINESE, true) { it }.candidates)
    }

    @Test fun `lookup covers later candidates in bounded batches`() {
        val candidates = (0 until 9).map { Candidate("fixture:$it", "公开$it") } + native.candidates.last()
        val result = PersonalCandidatePaging().publish(native.copy(candidates = candidates), Store(),
            InputLanguage.CHINESE, true) { it }
        assertEquals("测试", result.candidates.first().text)
    }

    private class Store : PersonalFrequencyStore {
        var reads = 0
        var fail = false
        var personal = emptyList<PersonalSuggestion>()
        override fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int> {
            reads++
            check(!fail)
            assertTrue(words.size <= PersonalFrequencyStore.MAX_LOOKUP)
            return if ("测试" in words) mapOf("测试" to 3) else emptyMap()
        }
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = personal
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }
}

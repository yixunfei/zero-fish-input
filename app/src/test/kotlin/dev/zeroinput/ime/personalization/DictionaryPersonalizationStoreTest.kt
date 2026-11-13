package dev.zeroinput.ime.personalization

import dev.zeroinput.engine.api.*
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class DictionaryPersonalizationStoreTest {
    @Test fun learnedPrioritySurvivesMoreThanOnePageOfDictionaryReplacements() {
        val lexicon = UserLexiconRepository(MemoryStore())
        val edits = DictionaryEditRepository(MemoryStore())
        repeat(9) { edits.save(DictionaryEdit("ice_fixture", null, DictionaryWord("公开词$it", "ce shi", 1))) }
        val habit = lexicon.addPhrase("ceshi", "高频样本", InputLanguage.CHINESE, 20)
        val store = DictionaryPersonalizationStore(lexicon, edits, { setOf("ice_fixture") }, { ChineseInputOptions() })

        val first = store.suggestionPage("ceshi", InputLanguage.CHINESE, 0, 8)
        assertEquals(habit.id, first.items.first().id)
        assertTrue(first.hasMore)
        store.recordUse(habit.id, true)
        assertEquals(21, store.suggestionPage("ceshi", InputLanguage.CHINESE, 0, 8).items.first().frequency)
        val second = store.suggestionPage("ceshi", InputLanguage.CHINESE, 8, 8)
        assertFalse(second.hasMore)
        assertEquals(10, (first.items + second.items).map { it.id }.toSet().size)
        assertTrue(edits.read().entries.all { it.replacement?.weight == 1 })
    }

    @Test fun unrelatedEditsPreserveExactMatchingAndFrequencyBeforeRecency() {
        val lexicon = UserLexiconRepository(MemoryStore())
        val edits = DictionaryEditRepository(MemoryStore())
        edits.save(DictionaryEdit("ice_fixture", null, DictionaryWord("你好", "ni hao", 1)))
        val exact = lexicon.addPhrase("XZ", "限制", InputLanguage.CHINESE, 3)
        val frequent = lexicon.addPhrase("xz", "选择", InputLanguage.CHINESE, 20)
        repeat(8) { lexicon.addPhrase("xza", "公开词$it", InputLanguage.CHINESE, 100) }
        val store = DictionaryPersonalizationStore(lexicon, edits, { setOf("ice_fixture") }, { ChineseInputOptions() })

        val expected = lexicon.suggestionPage("xz", InputLanguage.CHINESE, 0, 8)
        val actual = store.suggestionPage("xz", InputLanguage.CHINESE, 0, 8)
        assertEquals(listOf(frequent.id, exact.id), actual.items.take(2).map { it.id })
        assertEquals(expected.items, actual.items)
        assertEquals(expected.hasMore, actual.hasMore)
    }

    @Test fun learnedDuplicatesKeepTheCurrentOverrideRouteAndEmptyQueriesStayEmpty() {
        val lexicon = UserLexiconRepository(MemoryStore())
        lexicon.addPhrase("ceshi", "测试", InputLanguage.CHINESE, 20)
        val edits = DictionaryEditRepository(MemoryStore())
        edits.save(DictionaryEdit("ice_fixture", null, DictionaryWord("测试", "ce shi", 1)))
        val store = DictionaryPersonalizationStore(lexicon, edits, { setOf("ice_fixture") }, { ChineseInputOptions() })

        val page = store.suggestionPage(" CESHI ", InputLanguage.CHINESE, 0, 8)
        assertTrue(page.items.single().id.startsWith("dictionary:"))
        assertEquals(20, page.items.single().frequency)
        assertTrue(store.suggestionPage(" ", InputLanguage.CHINESE, 0, 8).items.isEmpty())
        assertTrue(store.suggestionPage("ceshi", InputLanguage.CHINESE, 0, 0).items.isEmpty())
    }

    @Test fun replacementClicksLearnPersistAndPromoteWithoutChangingPublicWeights() {
        val disk = MemoryStore()
        val lexicon = UserLexiconRepository(disk)
        val edits = DictionaryEditRepository(MemoryStore())
        edits.save(DictionaryEdit("ice_fixture", null, DictionaryWord("测试", "ce shi", 1)))
        edits.save(DictionaryEdit("ice_fixture", null, DictionaryWord("测式", "ce shi", 2)))
        val store = DictionaryPersonalizationStore(lexicon, edits, { setOf("ice_fixture") }, { ChineseInputOptions() })
        val id = store.suggestionPage("ceshi", InputLanguage.CHINESE, 0, 8).items.single { it.text == "测试" }.id
        repeat(3) { store.recordUse(id, true) }
        assertEquals(mapOf("测试" to 3), UserLexiconRepository(disk).frequenciesFor(listOf("测试"), InputLanguage.CHINESE))
        val rows = store.suggestionPage("ceshi", InputLanguage.CHINESE, 0, 8).items
        assertEquals("测试", rows.first().text)
        assertEquals(3, rows.first().frequency)
        assertEquals(1, edits.read().entries.single { it.replacement?.text == "测试" }.replacement?.weight)
        store.recordUse(id, false)
        assertEquals(3, lexicon.frequenciesFor(listOf("测试"), InputLanguage.CHINESE)["测试"])
    }

    @Test fun disabledOrRemovedReplacementDoesNotLearnFromOldSelectionId() {
        val lexicon = UserLexiconRepository(MemoryStore())
        val edits = DictionaryEditRepository(MemoryStore())
        edits.save(DictionaryEdit("ice_fixture", null, DictionaryWord("测试", "ce shi", 1)))
        var active = setOf("ice_fixture")
        val store = DictionaryPersonalizationStore(lexicon, edits, { active }, { ChineseInputOptions() })
        val id = store.suggestionPage("ceshi", InputLanguage.CHINESE, 0, 8).items.single().id
        active = emptySet()
        store.recordUse(id, true)
        active = setOf("ice_fixture")
        edits.clear()
        store.recordUse(id, true)
        assertTrue(lexicon.list().isEmpty())
    }

    @Test fun disabledDictionariesContributeNeitherReplacementsNorTombstones() {
        val lexicon = UserLexiconRepository(MemoryStore())
        val edits = DictionaryEditRepository(MemoryStore())
        val original = DictionaryWord("你好", "ni hao", 1)
        edits.save(DictionaryEdit("builtin_jichu", original, original.copy(text = "您好", weight = 100)))
        var enabled = false
        val store = DictionaryPersonalizationStore(lexicon, edits,
            { if (enabled) setOf("builtin_jichu") else emptySet() }, { ChineseInputOptions() })
        assertTrue(store.suggestionPage("nihao", InputLanguage.CHINESE, 0, 8).items.isEmpty())
        assertTrue(store.suggestionPage("nihao", InputLanguage.CHINESE, 0, 8).suppressedDictionaryTexts.isEmpty())
        enabled = true
        val page = store.suggestionPage("nihao", InputLanguage.CHINESE, 0, 8)
        assertEquals(listOf("您好"), page.items.map { it.text })
        assertEquals(setOf("你好"), page.suppressedDictionaryTexts)
    }

    @Test fun deletionSuppressesLearnedDuplicatesAndExplicitWeightsRankReplacements() {
        val lexicon = UserLexiconRepository(MemoryStore())
        lexicon.addPhrase("nihao", "你好", InputLanguage.CHINESE)
        val edits = DictionaryEditRepository(MemoryStore())
        edits.save(DictionaryEdit("ice_fixture", DictionaryWord("你好", "ni hao", 1), null))
        for ((text, weight) in listOf("公开甲" to 1, "公开乙" to 90)) {
            edits.save(DictionaryEdit("ice_fixture", null, DictionaryWord(text, "ni hao", weight)))
        }
        val store = DictionaryPersonalizationStore(lexicon, edits, { setOf("ice_fixture") }, { ChineseInputOptions() })
        assertEquals(listOf("公开乙", "公开甲"), store.suggestionPage("nihao", InputLanguage.CHINESE, 0, 8).items.map { it.text })
        val first = store.suggestionPage("nihao", InputLanguage.CHINESE, 0, 1)
        assertTrue(first.hasMore)
        assertEquals("公开甲", store.suggestionPage("nihao", InputLanguage.CHINESE, 1, 1).items.single().text)
        store.recordUse(first.items.single().id, true)
        assertEquals(90, edits.read().entries.mapNotNull { it.replacement?.weight }.max())
    }

    private class MemoryStore : EncryptedStore {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); plaintext.fill(0) }
        override fun delete(deleteKey: Boolean) { bytes = null }
    }
}

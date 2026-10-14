package dev.zeroinput.ime.personalization

import dev.zeroinput.engine.api.*
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.*
import org.junit.Assert.*
import org.junit.Test

class DictionaryPersonalizationStoreTest {
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

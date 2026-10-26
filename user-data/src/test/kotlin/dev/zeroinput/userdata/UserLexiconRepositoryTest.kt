package dev.zeroinput.userdata

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.security.EncryptedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.Locale

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], manifest = org.robolectric.annotation.Config.NONE)
class UserLexiconRepositoryTest {
    @Test fun `single Chinese character selections can accumulate a short code habit`() {
        val repository = UserLexiconRepository(MemoryStore())
        repeat(3) { repository.learn("c", "测", InputLanguage.CHINESE, true) }
        assertEquals(3, repository.suggestionsFor("c", InputLanguage.CHINESE, 8).single().frequency)
    }

    @Test fun `management pages cover every matching identity without exposing the entire list`() {
        val repository = UserLexiconRepository(MemoryStore(), readingMatches = { text, query ->
            text == "测试" && query == "ceshi"
        })
        val test = repository.addPhrase("cs", "测试", InputLanguage.CHINESE)
        assertEquals(test.id, repository.search("ce shi").single().id)
        repeat(55) { repository.addPhrase("fixture$it", "词组$it", InputLanguage.CHINESE) }
        val first = repository.searchPage("fixture")
        val second = repository.searchPage("fixture", offset = 50)
        assertEquals(50, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(5, second.items.size)
        assertTrue(!second.hasMore)
        assertEquals(55, (first.items + second.items).map { it.id }.distinct().size)
    }

    @Test fun `failed edit leaves the prior stored weight intact and successful edit survives reload`() {
        val store = MemoryStore()
        val repository = UserLexiconRepository(store)
        val first = repository.addPhrase("cs", "测试", InputLanguage.CHINESE, 3)
        store.failWrite = true
        assertThrows(IOException::class.java) { repository.updatePhrase(first.id, "cs", "测试", InputLanguage.CHINESE, 9) }
        assertEquals(3, repository.list().single().frequency)
        store.failWrite = false
        repository.updatePhrase(first.id, "cs", "测试", InputLanguage.CHINESE, 9)
        assertEquals(9, UserLexiconRepository(store).list().single().frequency)
    }

    @Test fun `management search is case independent bounded and updates the same identity`() {
        val repository = UserLexiconRepository(MemoryStore())
        val first = repository.addPhrase("cs", "测试", InputLanguage.CHINESE, 3)
        repository.addPhrase("csc", "测试词", InputLanguage.CHINESE, 1)
        assertEquals(1, repository.search("CS", limit = 1).size)
        assertEquals(setOf("测试", "测试词"), repository.search("CS").map { it.value }.toSet())
        val updated = repository.updatePhrase(first.id, "ceshi", "测试", InputLanguage.CHINESE, 7)
        assertEquals(first.id, updated.id)
        assertEquals(7, repository.search("ceshi").single().frequency)
        assertEquals(listOf("测试词"), repository.search("cs").map { it.value })
        repository.remove(first.id)
        assertTrue(repository.search("ceshi").isEmpty())
    }

    @Test fun `invalid edit duplicate or removed target never changes stored values`() {
        val repository = UserLexiconRepository(MemoryStore(), TrackingExportCipher())
        val first = repository.addPhrase("cs", "测试", InputLanguage.CHINESE, 3)
        val second = repository.addPhrase("cs", "产生", InputLanguage.CHINESE, 1)
        assertThrows(IllegalArgumentException::class.java) {
            repository.updatePhrase(first.id, "cs", "测试", InputLanguage.CHINESE, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.updatePhrase(second.id, "cs", "测试", InputLanguage.CHINESE, 5)
        }
        assertEquals(3, repository.search("cs").first { it.id == first.id }.frequency)
        repository.clear()
        assertThrows(IllegalArgumentException::class.java) {
            repository.updatePhrase(first.id, "cs", "测试", InputLanguage.CHINESE, 5)
        }
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun `learning rejects malformed UTF16 before writing`() {
        val store = MemoryStore()
        val repository = UserLexiconRepository(store)
        for ((shortcut, value) in listOf("ni" to "你\uD800", "n\uDC00" to "你好")) {
            repository.learn(shortcut, value, InputLanguage.CHINESE, learningAllowed = true)
        }
        assertNull(store.bytes)
    }

    @Test
    fun `learning preserves complete supplementary Unicode pairs`() {
        val repository = UserLexiconRepository(MemoryStore())
        repository.learn("fixture", "\uD83D\uDE00", InputLanguage.ENGLISH, learningAllowed = true)
        assertEquals("\uD83D\uDE00", repository.list().single().value)
    }

    @Test fun `repeated learning survives repository reconstruction with exact frequency intact`() {
        val store = MemoryStore()
        val repository = UserLexiconRepository(store)
        repeat(3) { repository.learn("ceshi", "测试", InputLanguage.CHINESE, learningAllowed = true) }

        val reloaded = UserLexiconRepository(store)
        assertEquals(3, reloaded.suggestionsFor("ceshi", InputLanguage.CHINESE, 8).single().frequency)
        assertEquals(mapOf("测试" to 3),
            reloaded.frequencyLookupSnapshot().frequenciesFor(listOf("测试"), InputLanguage.CHINESE))
    }

    @Test
    fun `prepared frequency lookup aggregates exact values within each language and stays bounded`() {
        val repository = UserLexiconRepository(MemoryStore())
        repository.learn("ni", "世界", InputLanguage.CHINESE, learningAllowed = true)
        repository.learn("shijie", "世界", InputLanguage.CHINESE, learningAllowed = true)
        repository.learn("hello", "世界", InputLanguage.ENGLISH, learningAllowed = true)

        val lookup = repository.frequencyLookupSnapshot()

        assertEquals(mapOf("世界" to 2), lookup.frequenciesFor(listOf("世界", "界"), InputLanguage.CHINESE))
        assertEquals(mapOf("世界" to 1), lookup.frequenciesFor(listOf("世界"), InputLanguage.ENGLISH))
        val tooMany = ('a'..'j').map { "word$it" } + "世界"
        assertEquals(emptyMap<String, Int>(), lookup.frequenciesFor(tooMany, InputLanguage.CHINESE))
    }

    @Test
    fun `prepared English frequency lookup merges case variants while Chinese stays exact`() {
        val repository = UserLexiconRepository(MemoryStore())
        repository.learn("hello", "Hello", InputLanguage.ENGLISH, learningAllowed = true)
        repository.learn("hello", "hello", InputLanguage.ENGLISH, learningAllowed = true)
        repository.learn("test", "测试甲", InputLanguage.CHINESE, learningAllowed = true)
        repository.learn("test", "测试乙", InputLanguage.CHINESE, learningAllowed = true)

        val lookup = repository.frequencyLookupSnapshot()

        assertEquals(mapOf("hello" to 2, "HELLO" to 2),
            lookup.frequenciesFor(listOf("hello", "HELLO"), InputLanguage.ENGLISH))
        assertEquals(mapOf("测试甲" to 1, "测试乙" to 1),
            lookup.frequenciesFor(listOf("测试甲", "测试乙"), InputLanguage.CHINESE))
    }

    @Test
    fun `English shortcut indexing is independent of the device locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val repository = UserLexiconRepository(MemoryStore())
            repository.learn("I", "Istanbul", InputLanguage.ENGLISH, learningAllowed = true)

            assertEquals(listOf("Istanbul"),
                repository.suggestionPage("i", InputLanguage.ENGLISH, 0, 8).items.map { it.text })
            assertEquals(mapOf("istanbul" to 1),
                repository.frequencyLookupSnapshot().frequenciesFor(listOf("istanbul"), InputLanguage.ENGLISH))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `failed persistence is observable without exposing phrase data`() {
        val store = MemoryStore()
        val repository = UserLexiconRepository(store)
        var observed: UserDictionaryFailure? = null
        val registration = repository.addWriteFailureListener { observed = it }
        store.failWrite = true
        try {
            repository.addPhrase("hello", "hello", InputLanguage.ENGLISH)
        } catch (_: Exception) {
            // The caller still receives the original storage failure.
        } finally {
            registration.close()
        }
        assertEquals(UserDictionaryFailure.WRITE_FAILED, observed)
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun `failed clear still attempts export key deletion and blocks until retry`() {
        val store = MemoryStore()
        val cipher = TrackingExportCipher()
        val repository = UserLexiconRepository(store, cipher)
        repository.addPhrase("hello", "hello", InputLanguage.ENGLISH)
        store.failDelete = true

        assertThrows(IOException::class.java) { repository.clear() }
        assertEquals(1, store.deleteAttempts)
        assertEquals(1, cipher.deleteAttempts)
        assertThrows(IllegalStateException::class.java) { repository.list() }

        store.failDelete = false
        repository.clear()
        assertEquals(2, store.deleteAttempts)
        assertEquals(2, cipher.deleteAttempts)
        assertTrue(repository.list().isEmpty())
    }

    @Test fun `export key query failure makes clear fail closed until explicit retry`() {
        val cipher = TrackingExportCipher().apply { failQuery = true }
        val repository = UserLexiconRepository(MemoryStore(), cipher)
        assertThrows(IOException::class.java) { repository.clear() }
        assertThrows(IllegalStateException::class.java) { repository.list() }
        cipher.failQuery = false
        repository.clear()
        assertEquals(1, cipher.deleteAttempts)
        assertTrue(repository.list().isEmpty())
    }

    private class MemoryStore : EncryptedStore {
        var bytes: ByteArray? = null
        var failWrite = false
        var failDelete = false
        var deleteAttempts = 0
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw java.io.IOException("write failed")
            bytes = plaintext.copyOf()
        }
        override fun delete(deleteKey: Boolean) {
            deleteAttempts += 1
            if (failDelete) throw java.io.IOException("delete failed")
            bytes = null
        }
    }

    private class TrackingExportCipher : EncryptedExportCipher {
        var deleteAttempts = 0
        var failQuery = false
        override fun hasKey(): Boolean {
            if (failQuery) throw IOException("Fixture provider unavailable")
            return true
        }
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext.copyOf()
        override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray = payload.copyOf()
        override fun deleteKey() { deleteAttempts += 1 }
    }
}

package dev.zeroinput.userdata

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.security.EncryptedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserLexiconRepositoryTest {
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

    private class MemoryStore : EncryptedStore {
        var bytes: ByteArray? = null
        var failWrite = false
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw java.io.IOException("write failed")
            bytes = plaintext.copyOf()
        }
        override fun delete(deleteKey: Boolean) { bytes = null }
    }
}

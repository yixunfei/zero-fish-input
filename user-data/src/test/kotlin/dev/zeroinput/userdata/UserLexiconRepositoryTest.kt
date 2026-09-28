package dev.zeroinput.userdata

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.security.EncryptedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private class MemoryStore : EncryptedStore {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            bytes = plaintext.copyOf()
        }
        override fun delete(deleteKey: Boolean) { bytes = null }
    }
}

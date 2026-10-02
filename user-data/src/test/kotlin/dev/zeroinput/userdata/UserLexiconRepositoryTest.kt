package dev.zeroinput.userdata

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.security.EncryptedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

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
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext.copyOf()
        override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray = payload.copyOf()
        override fun deleteKey() { deleteAttempts += 1 }
    }
}

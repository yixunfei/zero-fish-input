package dev.zeroinput.userdata

import dev.zeroinput.ai.api.*
import dev.zeroinput.security.EncryptedStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiContextLibraryRepositoryTest {
    private val role = AiPersona("role", "Public", "Fixture", "Be concise")
    private val value = AiContextLibrary(personas = listOf(role), memories = listOf(AiMemory("memory", role.id, "Public fact")))

    @Test fun roundTripClearsBuffersAndStaleEditsCannotOverwrite() {
        val store = Store(); val repo = AiContextLibraryRepository(store)
        val saved = repo.write(0, value) { true }
        assertEquals(1L, saved.revision)
        val loaded = repo.read()
        assertEquals(value.personas, loaded.personas); assertEquals(value.memories, loaded.memories)
        assertTrue(checkNotNull(store.lastRead).all { it == 0.toByte() })
        assertTrue(checkNotNull(store.lastWrite).all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { repo.write(0, AiContextLibrary()) { true } }
        assertEquals(value.personas, repo.read().personas)
    }

    @Test fun invalidFormatsDuplicateFieldsAndTruncatedBytesFailWithoutContentInErrors() {
        val store = Store(); val repo = AiContextLibraryRepository(store)
        for (invalid in listOf("{", "{\"format\":1,\"format\":1}",
            "{\"format\":9,\"revision\":0,\"personas\":[],\"memories\":[]}",
            "{\"format\":1,\"revision\":0,\"personas\":[],\"memories\":[],\"extra\":\"private fixture\"}")) {
            store.bytes = invalid.toByteArray()
            val error = assertThrows(IllegalStateException::class.java) { repo.read() }
            assertEquals("Context library is invalid", error.message)
            assertTrue(checkNotNull(store.lastRead).all { it == 0.toByte() })
        }
        store.bytes = byteArrayOf(0xc3.toByte(), 0x28)
        assertThrows(IllegalStateException::class.java) { repo.read() }
    }

    @Test fun writeFailureAndRevocationPreserveThePreviousVersion() {
        val store = Store(); val repo = AiContextLibraryRepository(store)
        repo.write(0, value) { true }
        store.failWrite = true
        assertThrows(IllegalStateException::class.java) { repo.write(1, AiContextLibrary()) { true } }
        store.failWrite = false
        assertThrows(IllegalStateException::class.java) { repo.write(1, AiContextLibrary()) { false } }
        assertEquals(value.personas, repo.read().personas)
    }

    @Test fun roleAndScopedMemoryDeletionIsAtomicAndFailedClearRemainsClosed() {
        val store = Store(); val repo = AiContextLibraryRepository(store)
        repo.write(0, value) { true }
        repo.write(1, AiContextLibrary()) { true }
        assertTrue(repo.read().memories.isEmpty())
        store.failDelete = true
        assertThrows(IllegalStateException::class.java) { repo.clear() }
        assertThrows(IllegalStateException::class.java) { repo.read() }
        assertThrows(IllegalStateException::class.java) { repo.write(2, value) { true } }
        store.failDelete = false; repo.clear()
        assertTrue(store.deletedKey); assertNull(store.bytes); assertTrue(repo.read().personas.isEmpty())
    }

    private class Store : EncryptedStore {
        var bytes: ByteArray? = null
        var lastRead: ByteArray? = null
        var lastWrite: ByteArray? = null
        var failWrite = false
        var failDelete = false
        var deletedKey = false
        override fun read() = bytes?.copyOf()?.also { lastRead = it }
        override fun write(plaintext: ByteArray) {
            lastWrite = plaintext
            check(!failWrite)
            bytes = plaintext.copyOf()
        }
        override fun delete(deleteKey: Boolean) { check(!failDelete); bytes = null; deletedKey = deleteKey }
    }
}

package dev.zeroinput.userdata

import dev.zeroinput.security.EncryptedStore
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DictionaryEditRepositoryTest {
    private val original = DictionaryWord("公开测试", "gong kai ce shi", 20)
    private val entry = DictionaryEdit("builtin_jichu", original, original.copy(weight = 42))

    @Test fun oversizedEditsAreRejectedBeforeWritingTheEncryptedStore() {
        val reading = List(85) { "abcdefghijk" }.joinToString(" ")
        val entries = (0 until 2000).map { index ->
            val word = DictionaryWord((index.toString() + "词".repeat(256)).take(256), reading, 1)
            DictionaryEdit("fixture", word, word.copy(weight = 2))
        }
        assertThrows(IllegalArgumentException::class.java) { DictionaryEditFormat.encode(DictionaryEdits(entries = entries)) }
    }

    @Test fun editsNamesAndTombstonesRoundTripAndBuffersAreCleared() {
        val store = MemoryStore()
        val repository = DictionaryEditRepository(store)
        repository.save(entry)
        repository.rename(entry.dictionaryId, "Fixture")
        assertEquals(listOf(entry), repository.read().entries)
        assertEquals("Fixture", repository.read().names[entry.dictionaryId])
        repository.save(entry.copy(replacement = null))
        assertNull(repository.read().entries.single().replacement)
        assertTrue(requireNotNull(store.lastRead).all { it == 0.toByte() })
        assertTrue(requireNotNull(store.lastWrite).all { it == 0.toByte() })
        repository.undo(entry.dictionaryId, entry.key)
        assertTrue(repository.read().entries.isEmpty())
    }

    @Test fun resetRevokesOldSavesAndPreservesOtherDictionaries() {
        val repository = DictionaryEditRepository(MemoryStore())
        repository.save(entry)
        val other = entry.copy(dictionaryId = "ice_fixture")
        repository.save(other)
        val generation = repository.generation()
        repository.reset(entry.dictionaryId)
        assertThrows(CancellationException::class.java) { repository.save(entry, generation) }
        assertEquals(listOf(other), repository.read().entries)
    }

    @Test fun failedPersistencePreservesPreviousDataAndFailedClearBlocksReads() {
        val store = MemoryStore()
        val repository = DictionaryEditRepository(store)
        repository.save(entry)
        store.failWrite = true
        assertThrows(IOException::class.java) { repository.save(entry.copy(replacement = null)) }
        assertEquals(listOf(entry), repository.read().entries)
        assertTrue(requireNotNull(store.lastWrite).all { it == 0.toByte() })
        store.failDelete = true
        assertThrows(IOException::class.java) { repository.clear() }
        assertThrows(IllegalStateException::class.java) { repository.read() }
        store.failDelete = false
        repository.clear()
        assertTrue(store.deletedKey)
        assertEquals(DictionaryEdits(), repository.read())
    }

    @Test fun truncatedTrailingMalformedAndDuplicatePayloadsAreRejected() {
        val bytes = DictionaryEditFormat.encode(DictionaryEdits(entries = listOf(entry)))
        for (length in bytes.indices) assertThrows(Exception::class.java) { DictionaryEditFormat.decode(bytes.copyOf(length)) }
        assertThrows(IllegalArgumentException::class.java) { DictionaryEditFormat.decode(bytes + 0) }
        assertThrows(IllegalArgumentException::class.java) { DictionaryEditFormat.encode(DictionaryEdits(entries = listOf(entry, entry))) }
        for (word in listOf("\uD800", "a\u202Eb", "x".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) { DictionaryWord(word, "a", 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { DictionaryWord("word", "../../", 1) }
        assertThrows(IllegalArgumentException::class.java) { DictionaryEditFormat.decode(ByteArray(DictionaryEditLimits.BYTES + 1)) }
    }

    @Test fun cancellationDuringReadPreventsPublication() {
        val store = MemoryStore()
        val repository = DictionaryEditRepository(store)
        repository.save(entry)
        var current = true
        store.onRead = { current = false }
        assertThrows(CancellationException::class.java) { repository.rename(entry.dictionaryId, "Other", isCurrent = { current }) }
        assertTrue(repository.read().names.isEmpty())
    }

    @Test fun clearDuringWriteCannotRevivePreviouslyQueuedEdits() {
        val store = MemoryStore()
        val repository = DictionaryEditRepository(store)
        val expected = repository.generation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.onWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val writer = Thread { repository.save(entry, expected) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val clearer = Thread { repository.clear() }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (repository.generation() == expected && System.nanoTime() < deadline) Thread.yield()
        assertNotEquals(expected, repository.generation())
        release.countDown(); writer.join(5000); clearer.join(5000)
        assertFalse(writer.isAlive); assertFalse(clearer.isAlive)
        assertThrows(CancellationException::class.java) { repository.save(entry, expected) }
        assertTrue(repository.read().entries.isEmpty())
    }

    private class MemoryStore : EncryptedStore {
        var bytes: ByteArray? = null
        var lastRead: ByteArray? = null
        var lastWrite: ByteArray? = null
        var failWrite = false
        var failDelete = false
        var deletedKey = false
        var onRead: () -> Unit = {}
        var onWrite: () -> Unit = {}
        override fun read(): ByteArray? { onRead(); return bytes?.copyOf().also { lastRead = it } }
        override fun write(plaintext: ByteArray) {
            lastWrite = plaintext; onWrite()
            if (failWrite) throw IOException()
            bytes = plaintext.copyOf()
        }
        override fun delete(deleteKey: Boolean) {
            if (failDelete) throw IOException()
            deletedKey = deleteKey; bytes = null
        }
    }
}

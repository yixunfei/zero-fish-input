package dev.zeroinput.userdata

import dev.zeroinput.security.EncryptedStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class PersonalExpressionRepositoryTest {
    @Test fun customTextAndFavoritesSurviveReloadWithoutChangingSpacesOrUnicode() {
        val store = MemoryStore()
        val repo = PersonalExpressionRepository(store)
        val value = "( ^_^ ) " + "\uD83D\uDC4D\uD83C\uDFFD"
        val entry = repo.save(null, value, "Greeting", "hello nihao", "greetings")
        repo.favorite(value, true)
        val loaded = PersonalExpressionRepository(store).read()
        assertEquals(entry, loaded.custom.single())
        assertEquals(setOf(value), loaded.favorites)
        assertTrue(checkNotNull(store.lastWrite).all { it == 0.toByte() })
        assertTrue(checkNotNull(store.lastRead).all { it == 0.toByte() })
    }

    @Test fun editsMoveFavoritesAndDeletionRemovesTheFavorite() {
        val repo = PersonalExpressionRepository(MemoryStore())
        val old = repo.save(null, "(^_^)", "Happy", "happy", "happy")
        repo.favorite(old.value, true)
        repo.save(old.id, "(^o^)", "Joy", "joy", "happy")
        assertEquals(setOf("(^o^)"), repo.read().favorites)
        repo.remove(old.id)
        assertEquals(PersonalExpressions(), repo.read())
    }

    @Test fun duplicateValuesAndMissingEditIdsAreRejectedWithoutMutation() {
        val repo = PersonalExpressionRepository(MemoryStore())
        val entry = repo.save(null, "(^_^)", "Happy", "", "happy")
        failure(ExpressionFailure.DUPLICATE) { repo.save(null, entry.value, "Other", "", "happy") }
        failure(ExpressionFailure.CANCELLED) {
            repo.save("00000000-0000-0000-0000-000000000000", "other", "Other", "", "happy")
        }
        assertEquals(listOf(entry), repo.read().custom)
    }

    @Test fun invalidUnicodeControlsAndOversizedFieldsCannotBeStored() {
        val repo = PersonalExpressionRepository(MemoryStore())
        for (value in listOf("", "  ", "a\u0000b", "a\nb", "\uD800", "\uDC00", "\u202eabc", "x".repeat(129))) {
            failure(ExpressionFailure.INVALID) { repo.save(null, value, "Fixture", "", "happy") }
        }
        failure(ExpressionFailure.INVALID) { repo.save(null, "a", "n".repeat(49), "", "happy") }
        failure(ExpressionFailure.INVALID) { repo.save(null, "a", "name", "k".repeat(257), "happy") }
        failure(ExpressionFailure.INVALID) { repo.save(null, "a", "name", "", "../happy") }
        assertEquals(PersonalExpressions(), repo.read())
    }

    @Test fun failedWritesKeepThePreviouslyPersistedStateAndClearTemporaryBytes() {
        val store = MemoryStore()
        val repo = PersonalExpressionRepository(store)
        val old = repo.save(null, "(^_^)", "Happy", "", "happy")
        store.failWrite = true
        failure(ExpressionFailure.STORAGE) { repo.favorite(old.value, true) }
        failure(ExpressionFailure.STORAGE) { repo.remove(old.id) }
        assertEquals(PersonalExpressions(listOf(old)), repo.read())
        assertTrue(checkNotNull(store.lastWrite).all { it == 0.toByte() })
    }

    @Test fun clearDeletesTheDedicatedKeyAndRejectsPreviouslyQueuedOperations() {
        val store = MemoryStore()
        val repo = PersonalExpressionRepository(store)
        val generation = repo.generation()
        repo.favorite("(^_^)", true)
        repo.clear()
        failure(ExpressionFailure.CANCELLED) { repo.favorite("(^_^)", true, generation) }
        assertTrue(store.deletedKey)
        assertNull(store.bytes)
        assertEquals(PersonalExpressions(), repo.read())
    }

    @Test fun failedDeletionBlocksReadsAndUpdatesUntilClearSucceeds() {
        val store = MemoryStore()
        val repo = PersonalExpressionRepository(store)
        repo.favorite("(^_^)", true)
        store.failDelete = true
        failure(ExpressionFailure.STORAGE) { repo.clear() }
        failure(ExpressionFailure.STORAGE) { repo.read() }
        failure(ExpressionFailure.STORAGE) { repo.favorite("other", true) }
        store.failDelete = false
        repo.clear()
        assertEquals(PersonalExpressions(), repo.read())
    }

    @Test fun cancellationDuringReadIsRecheckedBeforeEncryptedWrite() {
        val store = MemoryStore()
        val repo = PersonalExpressionRepository(store)
        var current = true
        store.onRead = { current = false }
        failure(ExpressionFailure.CANCELLED) { repo.favorite("(^_^)", true, isCurrent = { current }) }
        assertNull(store.bytes)
    }

    @Test fun clearDuringAnActiveWriteErasesTheResultAndRejectsOldWaitingWrites() {
        val store = MemoryStore()
        val repo = PersonalExpressionRepository(store)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = repo.generation()
        store.onWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val writer = thread { repo.favorite("(^_^)", true, old) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val clearer = thread { repo.clear() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (repo.generation() == old && System.nanoTime() < deadline) Thread.yield()
            assertNotEquals(old, repo.generation())
        } finally { release.countDown(); writer.join(5000); clearer.join(5000) }
        assertFalse(writer.isAlive)
        assertFalse(clearer.isAlive)
        failure(ExpressionFailure.CANCELLED) { repo.favorite("old", true, old) }
        assertNull(store.bytes)
    }

    @Test fun corruptVersionsTruncationAndTrailingDataAreNeverOverwritten() {
        val store = MemoryStore()
        PersonalExpressionRepository(store).favorite("(^_^)", true)
        val original = checkNotNull(store.bytes)
        val malformed = listOf(original.copyOf(5), original + byteArrayOf(0),
            original.copyOf().apply { this[7] = 2 }, original.copyOf().apply { this[11] = 127 })
        for (bytes in malformed) {
            store.bytes = bytes
            val repo = PersonalExpressionRepository(store)
            failure(ExpressionFailure.CORRUPT) { repo.read() }
            failure(ExpressionFailure.CORRUPT) { repo.favorite("extra", true) }
            assertArrayEquals(bytes, store.bytes)
        }
    }

    @Test fun capacityRejectsTheWholeUpdateAndAllowsExistingFavorites() {
        val store = MemoryStore()
        store.bytes = PersonalExpressionFormat.encode(PersonalExpressions(
            favorites = (0 until ExpressionLimits.FAVORITES).map { "fixture-$it" }.toSet()))
        val repo = PersonalExpressionRepository(store)
        repo.favorite("fixture-0", true)
        failure(ExpressionFailure.CAPACITY) { repo.favorite("extra", true) }
        assertEquals(ExpressionLimits.FAVORITES, repo.read().favorites.size)
    }

    @Test fun encryptedExportRoundTripsAndRejectsTampering() {
        val cipher = TestExportCipher()
        val source = PersonalExpressionRepository(MemoryStore(), cipher)
        val entry = source.save(null, "(^_^)", "Happy", "happy", "happy")
        source.favorite(entry.value, true)
        val payload = source.exportEncrypted()
        val target = PersonalExpressionRepository(MemoryStore(), cipher)
        assertEquals(1, target.importEncrypted(payload))
        assertEquals(source.read().custom.map { it.copy(id = "") }, target.read().custom.map { it.copy(id = "") })
        assertEquals(source.read().favorites, target.read().favorites)
        payload[0] = 0
        assertThrows(ExpressionException::class.java) { target.importEncrypted(payload) }
    }

    private fun failure(expected: ExpressionFailure, block: () -> Unit) {
        assertEquals(expected, assertThrows(ExpressionException::class.java, block).failure)
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
        override fun read(): ByteArray? { onRead(); return bytes?.copyOf()?.also { lastRead = it } }
        override fun write(plaintext: ByteArray) {
            lastWrite = plaintext
            onWrite()
            if (failWrite) throw IOException("Fixture failure")
            bytes = plaintext.copyOf()
        }
        override fun delete(deleteKey: Boolean) {
            if (failDelete) throw IOException("Fixture failure")
            deletedKey = deleteKey
            bytes = null
        }
    }

    private class TestExportCipher : EncryptedExportCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            byteArrayOf(0x24) + plaintext

        override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray {
            if (payload.firstOrNull() != 0x24.toByte()) error("tampered")
            return payload.copyOfRange(1, payload.size)
        }
    }
}

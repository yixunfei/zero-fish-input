package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.UserLexiconRepository
import dev.zeroinput.userdata.UserDictionaryException
import dev.zeroinput.userdata.UserDictionaryFailure
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserLexiconBoundaryTest {
    @Test fun completedUnknownPhraseIsLearnedWithItsWholeReadingAndAvailableAfterReload() {
        val store = MemoryStore()
        val repository = UserLexiconRepository(store)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val factory = dev.zeroinput.engine.rime.RimeEngineFactory(
            context,
            java.util.concurrent.Executors.newSingleThreadExecutor(),
        )
        val engine = factory.createFallback()
        val connection = object : dev.zeroinput.ime.core.EditorConnection {
            override fun setComposingText(text: String) = Unit
            override fun finishComposingText() = Unit
            override fun commitText(text: String) = true
            override fun deleteBeforeCursor() = Unit
            override fun performEditorAction(actionId: Int) = false
            override fun sendEnterKey() = Unit
        }
        val controller = dev.zeroinput.ime.core.InputSessionController(connection, { engine }, repository)
        val info = android.view.inputmethod.EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
        controller.start(info, InputLanguage.CHINESE, dev.zeroinput.ime.core.privacy.PrivacyConfiguration())
        try {
            "niaihao".forEach { controller.handle(dev.zeroinput.ime.core.InputCommand.Text(it.toString())) }
            for (word in listOf("你", "爱", "浩")) {
                val index = controller.state.snapshot.candidates.indexOfFirst { it.text == word }
                assertTrue(index >= 0)
                controller.handle(dev.zeroinput.ime.core.InputCommand.SelectCandidate(index))
                if (word != "浩") assertEquals(0, store.writes)
            }
            val reloaded = UserLexiconRepository(store)
            assertEquals("你爱浩", reloaded.suggestionsFor("niaihao", InputLanguage.CHINESE, 8).single().text)
            assertTrue(reloaded.suggestionsFor("hao", InputLanguage.CHINESE, 8).isEmpty())
            controller.start(info, InputLanguage.CHINESE, dev.zeroinput.ime.core.privacy.PrivacyConfiguration())
            "niaihao".forEach { controller.handle(dev.zeroinput.ime.core.InputCommand.Text(it.toString())) }
            assertTrue(controller.state.snapshot.candidates.any { it.text == "你爱浩" && it.id.startsWith("personal:") })
        } finally { controller.close() }
    }

    @Test fun pagingReachesMoreThanFiftyLearnedWordsWithoutDuplicateOrMissingIdentities() {
        val store = MemoryStore(document(117).toByteArray())
        val repository = UserLexiconRepository(store)
        val seen = mutableSetOf<String>()
        var offset = 0
        do {
            val page = repository.suggestionPage("word", InputLanguage.ENGLISH, offset, 8)
            page.items.forEach { assertTrue(seen.add(it.id)) }
            offset += page.items.size
        } while (page.hasMore)
        assertEquals(117, seen.size)
    }

    @Test
    fun addingBeyondCapacityFailsWithoutMakingThePersistedDictionaryUnreadable() {
        val store = MemoryStore(document(20_000).toByteArray())
        val repository = UserLexiconRepository(store)

        assertThrows(IllegalArgumentException::class.java) {
            repository.addPhrase("extra", "extra", InputLanguage.ENGLISH)
        }

        assertEquals(0, store.writes)
        assertEquals(20_000, repository.list().size)
        assertEquals(20_000, UserLexiconRepository(store).list().size)
        repository.addPhrase("word0", "word0", InputLanguage.ENGLISH)
        assertEquals(20_000, UserLexiconRepository(store).list().size)
    }

    @Test
    fun nestedUnknownFieldsAreRejectedBeforeAnyMutation() {
        val store = MemoryStore(document(1).toByteArray())
        val repository = UserLexiconRepository(store)
        val nested = "[".repeat(64) + "0" + "]".repeat(64)
        val invalid = "{\"format\":1,\"terms\":[],\"unknown\":$nested}"

        assertThrows(IllegalArgumentException::class.java) {
            repository.importJson(invalid.toByteArray(), replace = true)
        }

        assertEquals(0, store.writes)
        assertEquals(1, repository.list().size)
    }

    @Test
    fun invalidImportsNeverExposeSourceTextThroughExceptions() {
        val marker = "public-invalid-fixture"
        val invalid = "{\"format\":1,\"terms\":[{\"shortcut\":\"word\",\"value\":\"word\",\"language\":\"$marker\"}]}"
        val error = assertThrows(IllegalArgumentException::class.java) {
            UserLexiconRepository(MemoryStore()).importJson(invalid.toByteArray(), replace = false)
        }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.none {
            it.message.orEmpty().contains(marker)
        })
    }

    @Test
    fun malformedImportsPreserveBothMemoryAndStoredData() {
        val valid = document(1)
        val invalid = listOf(
            valid.replace("\"format\":1", "\"format\":2"),
            valid.replace("\"format\":1", "\"format\":1,\"format\":1"),
            valid.replace("\"frequency\":1", "\"frequency\":\"1\""),
            valid.replace("\"frequency\":1", "\"frequency\":1.5"),
            valid.replace("\"frequency\":1", "\"frequency\":-1"),
            valid.replace("\"lastUsed\":0", "\"lastUsed\":-1"),
            valid.replace("\"shortcut\":\"word0\"", "\"shortcut\":{}"),
            valid.replace("\"value\":\"word0\"", "\"value\":\"${"x".repeat(129)}\""),
            valid.replace("\"value\":\"word0\"", "\"value\":\"\\uD800\""),
            valid.replace("\"value\":\"word0\"", "\"value\":\"line\\nline\""),
            valid.replace("\"language\":\"ENGLISH\"", "\"language\":\"OTHER\""),
            document(2).replace("000000000001", "000000000000"),
            document(2).replace("word1", "word0"),
            valid + "{}",
            valid.dropLast(1),
            document(20_001),
        ).map(String::toByteArray) + listOf(byteArrayOf(0xc3.toByte(), 0x28))
        for (bytes in invalid) {
            val store = MemoryStore(valid.toByteArray())
            val repository = UserLexiconRepository(store)
            val original = repository.list()
            val error = assertThrows(UserDictionaryException::class.java) { repository.importJson(bytes, true) }
            assertEquals(UserDictionaryFailure.INVALID_FORMAT, error.failure)
            assertNull(error.cause)
            assertEquals(original, repository.list())
            assertEquals(original, UserLexiconRepository(store).list())
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun oversizedImportsAndMergesFailWithoutDroppingExistingPhrases() {
        val store = MemoryStore(document(20_000).toByteArray())
        val repository = UserLexiconRepository(store)
        val oversize = assertThrows(UserDictionaryException::class.java) {
            repository.importJson(ByteArray(5 * 1024 * 1024 + 1), false)
        }
        assertEquals(UserDictionaryFailure.IMPORT_TOO_LARGE, oversize.failure)
        val overflow = assertThrows(UserDictionaryException::class.java) {
            repository.importJson(document(1).replace("word0", "extra").toByteArray(), false)
        }
        assertEquals(UserDictionaryFailure.CAPACITY_EXCEEDED, overflow.failure)
        assertEquals(0, store.writes)
        assertEquals(20_000, UserLexiconRepository(store).list().size)
    }

    @Test
    fun importedIdentifiersCannotAliasAnotherLocalPhrase() {
        val store = MemoryStore(document(1).toByteArray())
        val repository = UserLexiconRepository(store)
        val originalId = repository.list().single().id
        repository.importJson(document(1).replace("word0", "second").toByteArray(), false)
        assertEquals(2, repository.list().map { it.id }.distinct().size)
        repository.importJson(document(1).replace("\"frequency\":1", "\"frequency\":9").toByteArray(), false)
        val matching = repository.list().single { it.shortcut == "word0" }
        assertEquals(originalId, matching.id)
        assertEquals(9, matching.frequency)
    }

    @Test
    fun failedWritesNeverPublishUnpersistedChangesAndAlwaysClearBuffers() {
        val store = MemoryStore(document(1).toByteArray())
        val repository = UserLexiconRepository(store)
        val original = repository.list()
        store.failWrite = true
        val operations = listOf<() -> Unit>(
            { repository.addPhrase("extra", "extra", InputLanguage.ENGLISH) },
            { repository.remove(original.single().id) },
            { repository.learn("word0", "word0", InputLanguage.ENGLISH, true) },
            { repository.recordUse(original.single().id, true) },
            { repository.importJson(document(1).replace("word0", "extra").toByteArray(), true) },
        )
        for (operation in operations) {
            assertThrows(IOException::class.java) { operation() }
            assertEquals(original, repository.list())
            assertEquals(original, UserLexiconRepository(store).list())
            assertTrue(checkNotNull(store.lastWrite).all { it == 0.toByte() })
        }
        assertTrue(checkNotNull(store.lastRead).all { it == 0.toByte() })
    }

    @Test
    fun clearingWaitsForAnInflightWriteAndErasesItsResultAndKey() {
        val store = MemoryStore(document(1).toByteArray())
        val repository = UserLexiconRepository(store)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clearing = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Executors.newFixedThreadPool(2)
        store.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            val write = worker.submit { repository.addPhrase("extra", "extra", InputLanguage.ENGLISH) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val clear = worker.submit { clearing.countDown(); repository.clear(); finished.countDown() }
            assertTrue(clearing.await(5, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            write.get(5, TimeUnit.SECONDS)
            clear.get(5, TimeUnit.SECONDS)
            assertTrue(repository.list().isEmpty())
            assertTrue(UserLexiconRepository(store).list().isEmpty())
            assertTrue(store.deletedKey)
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun validUnicodeAndBomRoundTripWithoutChangingTheFormat() {
        val store = MemoryStore()
        val repository = UserLexiconRepository(store)
        repository.addPhrase("nihao", "\u4f60\u597d", InputLanguage.CHINESE)
        repository.addPhrase("hello", "hello", InputLanguage.ENGLISH)
        val bytes = repository.exportJson()
        val restored = UserLexiconRepository(MemoryStore())
        restored.importJson(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes, true)
        assertEquals(repository.list().map { it.value }.toSet(), restored.list().map { it.value }.toSet())
        assertEquals(2, UserLexiconRepository(store).list().size)
    }

    @Test
    fun failedDeletionKeepsTheRepositoryUnavailableUntilDeletionSucceeds() {
        val store = MemoryStore(document(1).toByteArray())
        val repository = UserLexiconRepository(store)
        store.failDelete = true
        assertThrows(IOException::class.java) { repository.clear() }
        assertThrows(IllegalStateException::class.java) { repository.list() }
        assertThrows(IllegalStateException::class.java) { repository.exportJson() }
        assertThrows(IllegalStateException::class.java) {
            repository.importJson(document(0).toByteArray(), true)
        }
        assertEquals(0, store.writes)
        store.failDelete = false
        repository.clear()
        assertTrue(repository.list().isEmpty())
        repository.addPhrase("new", "new", InputLanguage.ENGLISH)
        assertEquals(1, UserLexiconRepository(store).list().size)
    }

    @Test
    fun aMergeBeyondTheByteLimitDoesNotPartiallyUpdateTheDictionary() {
        val expanded = document(9_000).replace(Regex("\"value\":\"word[0-9]+\"")) {
            "\"value\":\"${"\u4e2d".repeat(128)}\""
        }
        val store = MemoryStore(expanded.toByteArray())
        val repository = UserLexiconRepository(store)
        val incoming = expanded.replace("word", "next")
        assertTrue(incoming.toByteArray().size < 5 * 1024 * 1024)
        val error = assertThrows(UserDictionaryException::class.java) {
            repository.importJson(incoming.toByteArray(), false)
        }
        assertEquals(UserDictionaryFailure.CAPACITY_EXCEEDED, error.failure)
        assertEquals(0, store.writes)
        assertEquals(9_000, repository.list().size)
        assertEquals(9_000, UserLexiconRepository(store).list().size)
    }

    private fun document(count: Int): String = buildString {
        append("{\"format\":1,\"terms\":[")
        repeat(count) { index ->
            if (index > 0) append(',')
            append("{\"id\":\"00000000-0000-0000-0000-")
            append(index.toString().padStart(12, '0'))
            append("\",\"shortcut\":\"word$index\",\"value\":\"word$index\",")
            append("\"language\":\"ENGLISH\",\"frequency\":1,\"lastUsed\":0}")
        }
        append("]}")
    }

    private class MemoryStore(var bytes: ByteArray? = null) : EncryptedStore {
        var writes = 0
        var failWrite = false
        var failDelete = false
        var deletedKey = false
        var lastRead: ByteArray? = null
        var lastWrite: ByteArray? = null
        var beforeWrite: () -> Unit = {}
        override fun read(): ByteArray? = bytes?.copyOf().also { lastRead = it }
        override fun write(plaintext: ByteArray) {
            lastWrite = plaintext
            beforeWrite()
            if (failWrite) throw IOException("Fixture write failure")
            bytes = plaintext.copyOf()
            plaintext.fill(0)
            writes++
        }
        override fun delete(deleteKey: Boolean) {
            if (failDelete) throw IOException("Fixture deletion failure")
            deletedKey = deleteKey
            bytes?.fill(0)
            bytes = null
        }
    }
}

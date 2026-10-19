package dev.zeroinput.userdata

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.security.EncryptedStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UserLexiconCapacityTest {
    @Test fun serializedCapacityFailureDoesNotBecomeAStorageFailureOrOverwriteData() {
        val terms = List(12_000) { index ->
            UserTerm("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
                "fixture$index", "界".repeat(UserLexiconFormat.MAX_VALUE_LENGTH), InputLanguage.CHINESE, 1, 1L)
        }
        val original = UserLexiconFormat.serialize(terms).toString().toByteArray()
        assertTrue(original.size > UserLexiconFormat.MAX_IMPORT_BYTES)
        val store = object : EncryptedStore {
            override fun read() = original.copyOf()
            override fun write(plaintext: ByteArray) { error("Capacity rejection must happen before storage") }
            override fun delete(deleteKey: Boolean) = Unit
        }
        val repository = UserLexiconRepository(store)
        var failures = 0
        repository.addWriteFailureListener { failures++ }.use {
            val error = assertThrows(UserDictionaryException::class.java) {
                repository.addPhrase("new", "公开样例", InputLanguage.CHINESE)
            }
            assertEquals(UserDictionaryFailure.CAPACITY_EXCEEDED, error.failure)
        }
        assertNull(repository.lastWriteFailure())
        assertEquals(0, failures)
        assertEquals(terms.size, repository.list().size)
    }
}

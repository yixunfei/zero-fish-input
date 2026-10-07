package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiConversation
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiConfigurationRepository
import dev.zeroinput.userdata.AiConversationRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiConfigurationStorageTest {
    @Test fun disablingPersistenceDeletesHistoryAndItsKeyWithoutOpeningTheWorkbench() {
        val f = Fixture()
        f.storage.write(AiConfiguration(saveConversations = false))
        assertTrue(f.conversations.list().isEmpty())
        assertTrue(f.history.keyDeleted)
        assertFalse(f.configuration.read().saveConversations)
    }

    @Test fun loadingDisabledConfigurationPurgesHistoryLeftByAnInterruptedSave() {
        val f = Fixture()
        f.configuration.write(AiConfiguration(saveConversations = false))
        assertFalse(f.storage.read().saveConversations)
        assertTrue(f.conversations.list().isEmpty())
        assertTrue(f.history.keyDeleted)
    }

    @Test fun failedPurgeRejectsSaveAndReadUntilExplicitRetrySucceeds() {
        val f = Fixture()
        f.history.failDelete = true
        assertTrue(runCatching { f.storage.write(AiConfiguration()) }.isFailure)
        assertTrue(runCatching { f.storage.read() }.isFailure)
        assertTrue(runCatching { f.conversations.list() }.isFailure)
        f.history.failDelete = false
        f.storage.write(AiConfiguration())
        assertTrue(f.conversations.list().isEmpty())
        assertFalse(f.storage.read().saveConversations)
    }

    @Test fun enabledPersistencePreservesExistingHistory() {
        val f = Fixture()
        f.storage.write(AiConfiguration(saveConversations = true))
        assertTrue(f.storage.read().saveConversations)
        assertEquals("saved", f.conversations.list().single().id)
        assertFalse(f.history.keyDeleted)
    }

    @Test fun rejectedConfigurationDoesNotDeleteHistory() {
        val f = Fixture()
        assertTrue(runCatching { f.storage.write(AiConfiguration(timeoutMs = 0)) }.isFailure)
        assertEquals("saved", f.conversations.list().single().id)
        assertFalse(f.history.keyDeleted)
    }

    @Test fun clearAttemptsHistoryDeletionEvenWhenConfigurationDeletionFails() {
        val f = Fixture()
        f.config.failDelete = true
        assertTrue(runCatching { f.storage.clear() }.isFailure)
        assertTrue(f.history.keyDeleted)
        assertTrue(f.conversations.list().isEmpty())
    }

    private class Fixture {
        val config = MemoryStore()
        val history = MemoryStore()
        val configuration = AiConfigurationRepository(config)
        val conversations = AiConversationRepository(history)
        val storage = AiConfigurationStorage(configuration, conversations)
        init {
            configuration.write(AiConfiguration(saveConversations = true))
            conversations.upsert(AiConversation(id = "saved", title = "public fixture"))
        }
    }

    private class MemoryStore : EncryptedStore {
        private var bytes: ByteArray? = null
        var failDelete = false
        var keyDeleted = false
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
        override fun delete(deleteKey: Boolean) {
            check(!failDelete) { "Fixture delete failure" }
            bytes?.fill(0)
            bytes = null
            keyDeleted = deleteKey
        }
    }
}

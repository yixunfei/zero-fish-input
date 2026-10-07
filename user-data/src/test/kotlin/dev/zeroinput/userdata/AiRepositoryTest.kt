package dev.zeroinput.userdata

import dev.zeroinput.ai.api.AiConversation
import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiMessage
import dev.zeroinput.ai.api.AiRole
import dev.zeroinput.security.EncryptedStore
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiRepositoryTest {
    @Test
    fun configurationRoundTripWipesBuffersAndKeepsApiKeyOutOfTheMemoryContract() {
        val store = MemoryStore()
        val profile = providerProfile()
        val value = AiConfiguration(
            enabled = true,
            networkAllowed = true,
            timeoutMs = 30_000L,
            saveConversations = true,
            providers = listOf(profile),
            selectedProviderId = profile.id,
        )

        AiConfigurationRepository(store).write(value)
        val loaded = AiConfigurationRepository(store).read()

        assertEquals(value, loaded)
        assertTrue(checkNotNull(store.lastWrite).all { it == 0.toByte() })
        assertTrue(checkNotNull(store.lastRead).all { it == 0.toByte() })
    }

    @Test
    fun failedConfigurationWriteDoesNotPublishTheNewCache() {
        val store = MemoryStore()
        val repository = AiConfigurationRepository(store)
        val original = AiConfiguration(
            providers = listOf(providerProfile(apiKey = "original")),
            selectedProviderId = "fixture",
        )
        repository.write(original)
        store.failWrite = true

        assertThrows(IOException::class.java) {
            repository.write(original.copy(providers = listOf(providerProfile(selectedModel = "new-model"))))
        }
        assertEquals(original, repository.read())
    }

    @Test
    fun multipleProvidersAndPerModelCapabilitiesRoundTrip() {
        val store = MemoryStore()
        val first = AiProviderProfile("one", "First", "https://first.example/v1/chat/completions",
            "key-one", listOf("text", "vision"), "vision", imageModels = setOf("vision"))
        val second = AiProviderProfile("two", "Second", "https://second.example/v1/chat/completions",
            "key-two", listOf("audio"), "audio", audioModels = setOf("audio"))
        val value = AiConfiguration(providers = listOf(first, second), selectedProviderId = second.id,
            saveConversations = true)

        AiConfigurationRepository(store).write(value)
        val restored = AiConfigurationRepository(store).read()

        assertEquals(value, restored)
        assertEquals("key-two", restored.activeKey())
        assertTrue(restored.activeProvider()?.supportsAudio == true)
        assertTrue(restored.copy(selectedProviderId = first.id).activeProvider()?.supportsImages == true)
        assertTrue(restored.copy(selectedProviderId = first.id,
            providers = listOf(first.copy(selectedModel = "text"), second)).activeProvider()?.supportsImages == false)
    }

    @Test
    fun providerWithoutSelectionOrInvalidModelCapabilityIsRejected() {
        val repository = AiConfigurationRepository(MemoryStore())
        val profile = AiProviderProfile("one", "First", "https://example.test/v1/chat/completions",
            "key", listOf("text"), "text")
        assertThrows(IllegalArgumentException::class.java) {
            repository.write(AiConfiguration(providers = listOf(profile)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.write(AiConfiguration(providers = listOf(profile.copy(imageModels = setOf("missing"))),
                selectedProviderId = profile.id))
        }
    }

    @Test
    fun configurationRejectsInvalidPortsAndHeaderControlsBeforePersisting() {
        val store = MemoryStore()
        val repository = AiConfigurationRepository(store)
        val originalProfile = providerProfile(apiKey = "original")
        val original = AiConfiguration(providers = listOf(originalProfile), selectedProviderId = originalProfile.id)
        repository.write(original)
        val originalBytes = checkNotNull(store.bytes).copyOf()
        val invalid = listOf(
            original.copy(providers = listOf(originalProfile.copy(endpoint = "https://provider.example:65536/v1/chat/completions"))),
            original.copy(providers = listOf(originalProfile.copy(endpoint = "https://provider.example:0/v1/chat/completions"))),
            original.copy(providers = listOf(originalProfile.copy(apiKey = "fixture\r\nheader"))),
            original.copy(providers = listOf(originalProfile.copy(apiKey = "fixture\u0000key"))),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { repository.write(value) }
            assertEquals(original, repository.read())
            assertArrayEquals(originalBytes, store.bytes)
        }
    }

    @Test
    fun malformedConfigurationIsRejectedWithoutReplacingTheCiphertext() {
        val store = MemoryStore()
        store.bytes = "{\"format\":1,\"apiKey\":\"unterminated}".toByteArray()
        val original = store.bytes!!.copyOf()
        val repository = AiConfigurationRepository(store)

        assertThrows(IllegalStateException::class.java) { repository.read() }
        assertArrayEquals(original, store.bytes)
    }

    @Test
    fun legacyConfigurationIsClearedWithoutCompatibilityMigration() {
        for (format in 1..3) {
            val store = MemoryStore()
            store.bytes = JSONObject()
                .put("format", format)
                .put("enabled", true)
                .put("networkAllowed", true)
                .put("saveConversations", true)
                .put("endpoint", "https://legacy.example/v1/chat/completions")
                .put("model", "legacy-model")
                .put("apiKey", "legacy-key")
                .toString()
                .toByteArray()
            val repository = AiConfigurationRepository(store)

            assertEquals(AiConfiguration(), repository.read())
            assertTrue(store.deletedKey)
            assertNull(store.bytes)
            assertTrue(checkNotNull(store.lastRead).all { it == 0.toByte() })
        }
    }

    @Test
    fun currentFormatRejectsLegacyProviderWideCapabilities() {
        val store = MemoryStore()
        val repository = AiConfigurationRepository(store)
        repository.write(AiConfiguration(providers = listOf(providerProfile()), selectedProviderId = "fixture"))
        val root = JSONObject(String(checkNotNull(store.bytes), Charsets.UTF_8))
        val profile = root.getJSONArray("providers").getJSONObject(0)
        profile.remove("imageModels")
        profile.remove("audioModels")
        profile.put("supportsImages", true)
        profile.put("supportsAudio", true)
        store.bytes = root.toString().toByteArray(Charsets.UTF_8)
        val original = checkNotNull(store.bytes).copyOf()

        assertThrows(IllegalStateException::class.java) { AiConfigurationRepository(store).read() }
        assertArrayEquals(original, store.bytes)
        assertTrue(checkNotNull(store.lastRead).all { it == 0.toByte() })
    }

    @Test
    fun failedLegacyConfigurationPurgeLocksReadsAndWritesUntilClearSucceeds() {
        val store = MemoryStore().apply {
            bytes = JSONObject().put("format", 2).toString().toByteArray()
            failDelete = true
        }
        val repository = AiConfigurationRepository(store)

        assertThrows(IllegalStateException::class.java) { repository.read() }
        store.failDelete = false
        assertThrows(IllegalStateException::class.java) { repository.read() }
        assertThrows(IllegalStateException::class.java) { repository.write(AiConfiguration()) }
        repository.clear()
        assertEquals(AiConfiguration(), repository.read())
        assertTrue(store.deletedKey)
        assertNull(store.bytes)
        repository.write(AiConfiguration())
        assertEquals(AiConfiguration(), AiConfigurationRepository(store).read())
    }

    @Test
    fun conversationRoundTripReplacesDuplicateIdsAndWipesBuffers() {
        val store = MemoryStore()
        val repository = AiConversationRepository(store)
        val original = AiConversation(
            id = "conversation-1",
            title = "First",
            messages = listOf(AiMessage(AiRole.USER, "hello")),
            updatedAtEpochMillis = 1L,
        )
        val replacement = original.copy(
            title = "Updated",
            messages = listOf(
                AiMessage(AiRole.USER, "hello"),
                AiMessage(AiRole.ASSISTANT, "world"),
            ),
            updatedAtEpochMillis = 2L,
        )

        repository.upsert(original)
        repository.upsert(replacement)
        val loaded = AiConversationRepository(store).list()

        assertEquals(listOf(replacement), loaded)
        assertTrue(checkNotNull(store.lastWrite).all { it == 0.toByte() })
        assertTrue(checkNotNull(store.lastRead).all { it == 0.toByte() })
    }

    @Test
    fun failedConversationWriteDoesNotPublishTheNewCache() {
        val store = MemoryStore()
        val repository = AiConversationRepository(store)
        val original = AiConversation(id = "one", title = "Original")
        repository.upsert(original)
        store.failWrite = true

        assertThrows(IOException::class.java) {
            repository.upsert(original.copy(title = "Changed"))
        }
        assertEquals(listOf(original), repository.list())
    }

    @Test
    fun malformedConversationJsonAndOversizedHistoryAreRejected() {
        val store = MemoryStore()
        store.bytes = JSONObject().put("format", 1).toString().toByteArray()
        assertThrows(Exception::class.java) { AiConversationRepository(store).list() }

        val messages = JSONArray().apply {
            repeat(AiLimits.MAX_HISTORY_MESSAGES + 1) {
                put(JSONObject().put("role", "USER").put("content", "fixture"))
            }
        }
        store.bytes = JSONObject()
            .put("format", 1)
            .put("conversations", JSONArray().put(
                JSONObject()
                    .put("id", "one")
                    .put("title", "Fixture")
                    .put("messages", messages),
            ))
            .toString()
            .toByteArray()
        assertThrows(Exception::class.java) { AiConversationRepository(store).list() }
    }

    @Test
    fun duplicatePersistedConversationIdsAreRejected() {
        val item = JSONObject().put("id", "same").put("title", "Fixture").put("messages", JSONArray())
        val store = MemoryStore()
        store.bytes = JSONObject()
            .put("format", 1)
            .put("conversations", JSONArray().put(item).put(item))
            .toString()
            .toByteArray()

        assertThrows(Exception::class.java) { AiConversationRepository(store).list() }
    }

    @Test
    fun clearDeletesDedicatedKeyAndEmptiesConversationCache() {
        val store = MemoryStore()
        val repository = AiConversationRepository(store)
        repository.upsert(AiConversation(id = "one", title = "Fixture"))

        repository.clear()

        assertTrue(store.deletedKey)
        assertNull(store.bytes)
        assertEquals(emptyList<AiConversation>(), repository.list())
    }

    @Test
    fun summariesDoNotExposeMessagesAndSingleDeleteIsAtomic() {
        val store = MemoryStore()
        val repository = AiConversationRepository(store)
        repository.upsert(AiConversation(id = "one", title = "First", messages = listOf(AiMessage(AiRole.USER, "secret"))))
        repository.upsert(AiConversation(id = "two", title = "Second"))

        val summaries = repository.listSummaries()
        assertEquals(setOf("one", "two"), summaries.map { it.id }.toSet())
        assertEquals("First", summaries.first { it.id == "one" }.title)
        repository.delete("one")

        assertNull(repository.find("one"))
        assertEquals("two", repository.find("two")?.id)
    }

    @Test
    fun cancelledWriteAfterLoadingCannotPublishOrOverwriteStoredConversation() {
        val store = MemoryStore()
        val repository = AiConversationRepository(store)
        repository.upsert(AiConversation(id = "one", title = "original"))
        var current = true
        store.afterRead = { current = false }
        assertThrows(IllegalStateException::class.java) {
            repository.upsert(AiConversation(id = "one", title = "late")) { current }
        }
        store.afterRead = {}
        assertEquals("original", repository.list().single().title)
    }

    @Test
    fun failedDeletionBlocksConfigurationAndHistoryUntilExplicitRetry() {
        val store = MemoryStore()
        val history = AiConversationRepository(store)
        history.upsert(AiConversation(id = "one", title = "fixture"))
        store.failDelete = true
        assertThrows(IOException::class.java) { history.clear() }
        assertThrows(IllegalStateException::class.java) { history.list() }
        assertThrows(IllegalStateException::class.java) { history.upsert(AiConversation(title = "late")) }
        store.failDelete = false
        history.clear()
        assertTrue(history.list().isEmpty())

        val configuration = AiConfigurationRepository(store)
        val profile = providerProfile(apiKey = "fixture")
        configuration.write(AiConfiguration(providers = listOf(profile), selectedProviderId = profile.id))
        store.failDelete = true
        assertThrows(IOException::class.java) { configuration.clear() }
        assertThrows(IllegalStateException::class.java) { configuration.read() }
        store.failDelete = false
        configuration.clear()
        assertEquals("", configuration.read().activeKey())
    }

    private fun providerProfile(
        endpoint: String = "https://provider.example/v1/chat/completions",
        apiKey: String = "fixture-api-key",
        selectedModel: String = "local-compatible",
    ) = AiProviderProfile(
        id = "fixture",
        name = "Fixture",
        endpoint = endpoint,
        apiKey = apiKey,
        models = listOf("local-compatible", "new-model"),
        selectedModel = selectedModel,
    )

    private class MemoryStore : EncryptedStore {
        var bytes: ByteArray? = null
        var lastRead: ByteArray? = null
        var lastWrite: ByteArray? = null
        var failWrite = false
        var deletedKey = false
        var failDelete = false
        var afterRead: () -> Unit = {}

        override fun read(): ByteArray? = bytes?.copyOf()?.also { lastRead = it; afterRead() }

        override fun write(plaintext: ByteArray) {
            lastWrite = plaintext
            if (failWrite) throw IOException("fixture failure")
            bytes = plaintext.copyOf()
        }

        override fun delete(deleteKey: Boolean) {
            if (failDelete) throw IOException("fixture failure")
            deletedKey = deleteKey
            bytes = null
        }
    }
}

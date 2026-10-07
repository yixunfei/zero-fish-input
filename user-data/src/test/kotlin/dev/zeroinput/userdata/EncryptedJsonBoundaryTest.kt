package dev.zeroinput.userdata

import dev.zeroinput.security.EncryptedStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EncryptedJsonBoundaryTest {
    @Test fun malformedUtf8AfterAValidPrefixFailsWithoutRewritingTheStore() {
        val store = Store("""{"format":4,"providers":[],"apiKey":"public fixture""".toByteArray() + byteArrayOf(0xC3.toByte()))
        val failure = assertThrows(IllegalStateException::class.java) { AiConfigurationRepository(store).read() }
        assertEquals("AI configuration is invalid", failure.message)
        assertNull(failure.cause)
        store.assertUntouchedAndWiped()
    }

    @Test fun unicodeConversationTextSurvivesStrictUtf8Decoding() {
        val store = Store("""{"format":1,"conversations":[{"id":"fixture","title":"公开样例",
            "messages":[{"role":"USER","content":"你好 🌏"}],"updatedAt":1}]}""")
        val value = AiConversationRepository(store).list().single()
        assertEquals("你好 🌏", value.messages.single().content)
        store.assertUntouchedAndWiped()
    }

    @Test fun incompleteCurrentConfigurationCannotSilentlyBecomeDefaults() {
        val store = Store("""{"format":4}""")
        assertThrows(IllegalStateException::class.java) { AiConfigurationRepository(store).read() }
        store.assertUntouchedAndWiped()
    }

    @Test fun duplicateConfigurationFieldsCannotOverridePrivacySettings() {
        val store = Store("""{"format":4,"enabled":false,"enabled":true,"networkAllowed":true,
            "timeoutMs":60000,"saveConversations":false,"providers":[]}""")
        assertThrows(IllegalStateException::class.java) { AiConfigurationRepository(store).read() }
        store.assertUntouchedAndWiped()
    }

    @Test fun missingConversationMessagesCannotBeReadAsAnEmptyHistory() {
        val store = Store("""{"format":1,"conversations":[{"id":"fixture","title":"Fixture","updatedAt":1}]}""")
        assertThrows(IllegalStateException::class.java) { AiConversationRepository(store).list() }
        store.assertUntouchedAndWiped()
    }

    @Test fun duplicateConversationFieldsCannotReplaceStoredMessages() {
        val store = Store("""{"format":1,"conversations":[{"id":"fixture","title":"Fixture",
            "messages":[{"role":"USER","content":"public fixture"}],"messages":[],"updatedAt":1}]}""")
        assertThrows(IllegalStateException::class.java) { AiConversationRepository(store).list() }
        store.assertUntouchedAndWiped()
    }

    private class Store(private val original: ByteArray) : EncryptedStore {
        constructor(json: String) : this(json.toByteArray())
        private var readBuffer: ByteArray? = null
        private var changed = false
        override fun read() = original.copyOf().also { readBuffer = it }
        override fun write(plaintext: ByteArray) { changed = true }
        override fun delete(deleteKey: Boolean) { changed = true }
        fun assertUntouchedAndWiped() {
            assertFalse(changed)
            assertTrue(checkNotNull(readBuffer).all { it == 0.toByte() })
        }
    }
}

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
class AiConnectionRepositoryTest {
    private val value = AiConnectionLibrary(connections = listOf(AiConnection("public", "Fixture", AiConnectionKind.MCP, "https://example.com/mcp", "fixture-token")))
    @Test fun independentCredentialsRoundTripWithoutLeakingBuffersOrAcceptingStaleEdits() {
        val store = Store(); val repo = AiConnectionRepository(store)
        assertEquals(1, repo.write(0, value) { true }.revision.toInt())
        assertEquals("fixture-token", repo.read().connections.single().token)
        assertTrue(store.readBuffer!!.all { it == 0.toByte() }); assertTrue(store.writeBuffer!!.all { it == 0.toByte() })
        assertFalse(value.connections.single().toString().contains("fixture-token"))
        assertThrows(IllegalStateException::class.java) { repo.write(0, value) { true } }
        assertThrows(IllegalStateException::class.java) { repo.write(1, value) { false } }
    }
    @Test fun invalidJsonCorruptionBoundsAndUnknownFieldsFailClosed() {
        val store = Store(); val repo = AiConnectionRepository(store)
        for (text in listOf("{", """{"format":1,"format":1,"revision":0,"connections":[]}""",
            """{"format":2,"revision":0,"connections":[]}""", """{"format":1,"revision":"0","connections":[]}""",
            """{"format":1,"revision":0,"connections":[],"secret":"fixture-token"}""", "x".repeat(131_073))) {
            store.bytes = text.toByteArray()
            assertEquals("Connections unavailable", assertThrows(IllegalStateException::class.java) { repo.read() }.message)
            assertTrue(store.readBuffer!!.all { it == 0.toByte() })
        }
        store.bytes = byteArrayOf(0xc3.toByte(), 0x28)
        assertThrows(IllegalStateException::class.java) { repo.read() }
    }
    @Test fun failedDeletionCannotRestoreCredentialsUntilClearIsRetried() {
        val store = Store(); val repo = AiConnectionRepository(store)
        repo.write(0, value) { true }; store.failDelete = true
        assertThrows(IllegalStateException::class.java) { repo.clear() }
        assertThrows(IllegalStateException::class.java) { repo.read() }
        assertThrows(IllegalStateException::class.java) { repo.write(1, value) { true } }
        store.failDelete = false; repo.clear()
        assertTrue(store.keyDeleted); assertTrue(repo.read().connections.isEmpty())
    }
    private class Store : EncryptedStore {
        var bytes: ByteArray? = null; var readBuffer: ByteArray? = null; var writeBuffer: ByteArray? = null
        var failDelete = false; var keyDeleted = false
        override fun read() = bytes?.copyOf()?.also { readBuffer = it }
        override fun write(plaintext: ByteArray) { writeBuffer = plaintext; bytes = plaintext.copyOf() }
        override fun delete(deleteKey: Boolean) { check(!failDelete); bytes = null; keyDeleted = deleteKey }
    }
}

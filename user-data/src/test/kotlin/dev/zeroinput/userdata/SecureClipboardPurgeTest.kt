package dev.zeroinput.userdata

import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.security.EncryptedStore
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SecureClipboardPurgeTest {
    @Test fun purgeDeletesBothStoresAndKeysWithoutReadingOrAuthenticating() {
        val body = Store()
        val index = Store()
        val vault = SecureClipboardVault(body, index)
        val generation = vault.captureGeneration()
        vault.purge()
        assertEquals(listOf(true), body.deletes)
        assertEquals(listOf(true), index.deletes)
        assertTrue(vault.captureGeneration() > generation)
        assertTrue(vault.summaries().isEmpty())
        assertEquals(0, body.reads)
        assertEquals(0, index.reads)
    }

    @Test fun oldQueuedReadsAndAdditionsCannotRevivePurgedData() {
        val vault = SecureClipboardVault(Store(), Store())
        val old = vault.captureGeneration()
        vault.purge()
        assertThrows(CancellationException::class.java) {
            vault.add("fixture", "public", AuthenticationGrant.afterSuccessfulSystemAuthentication(), old)
        }
        assertThrows(CancellationException::class.java) {
            vault.read("fixture", AuthenticationGrant.afterSuccessfulSystemAuthentication(), old)
        }
    }

    @Test fun failureStillAttemptsBothDeletesAndBlocksAccessUntilSuccessfulRetry() {
        for (failBody in listOf(true, false)) {
            val body = Store().apply { failDelete = failBody }
            val index = Store().apply { failDelete = !failBody }
            val vault = SecureClipboardVault(body, index)
            assertThrows(IOException::class.java) { vault.purge() }
            assertEquals(listOf(true), body.deletes)
            assertEquals(listOf(true), index.deletes)
            assertThrows(IOException::class.java) { vault.summaries() }
            assertThrows(IOException::class.java) {
                vault.add("fixture", "public", AuthenticationGrant.afterSuccessfulSystemAuthentication())
            }
            body.failDelete = false
            index.failDelete = false
            vault.purge()
            assertTrue(vault.summaries().isEmpty())
        }
    }

    private class Store : EncryptedStore {
        var reads = 0
        var failDelete = false
        val deletes = mutableListOf<Boolean>()
        override fun read(): ByteArray? { reads++; error("Deletion must not decrypt") }
        override fun write(plaintext: ByteArray) { error("Old writes must be rejected") }
        override fun delete(deleteKey: Boolean) {
            deletes += deleteKey
            if (failDelete) throw IOException("Fixture deletion failure")
        }
    }
}

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
class SecureClipboardTransactionTest {
    @Test fun indexFailureRollsBackWithoutSavingTheAddition() {
        val body = Store()
        val index = Store()
        val vault = SecureClipboardVault(body, index)
        val original = vault.add("fixture", "original", grant())
        index.failures = 1

        assertThrows(IOException::class.java) { vault.add("fixture", "new", grant()) }

        assertEquals(original.id, vault.summaries().single().id)
        assertEquals(original.id, SecureClipboardVault(body, index).metadata(grant()).single().id)
    }

    @Test fun bodyFailureAfterWritingRestoresBothFiles() {
        val body = Store()
        val index = Store()
        val vault = SecureClipboardVault(body, index)
        val original = vault.add("fixture", "original", grant())
        body.failures = 1

        assertThrows(IOException::class.java) { vault.remove(original.id, grant()) }

        val restarted = SecureClipboardVault(body, index)
        assertEquals(original.id, restarted.summaries().single().id)
        assertEquals("original", restarted.read(original.id, grant()))
    }

    @Test fun cancellationBeforeBodyCommitRestoresTheOldIndex() {
        val body = Store()
        val index = Store()
        val vault = SecureClipboardVault(body, index)
        val original = vault.add("fixture", "original", grant())
        var active = true
        index.afterWrite = { active = false }

        assertThrows(CancellationException::class.java) {
            vault.add("fixture", "cancelled", grant(), isActive = { active })
        }

        assertEquals(original.id, vault.summaries().single().id)
        assertEquals(original.id, SecureClipboardVault(body, index).metadata(grant()).single().id)
    }

    @Test fun cancellationAfterBodyCommitReturnsTheSavedItem() {
        val body = Store()
        val index = Store()
        val vault = SecureClipboardVault(body, index)
        var active = true
        body.afterWrite = { active = false }

        val saved = vault.add("fixture", "committed", grant(), isActive = { active })

        assertFalse(active)
        assertEquals(saved.id, vault.summaries().single().id)
        assertEquals("committed", SecureClipboardVault(body, index).read(saved.id, grant()))
    }

    @Test fun clearDuringFailedCommitCannotRestoreOldFiles() {
        val body = Store()
        val index = Store()
        val vault = SecureClipboardVault(body, index)
        vault.add("fixture", "original", grant())
        body.failures = 1
        body.afterWrite = { body.afterWrite = {}; vault.purge() }

        assertThrows(IOException::class.java) { vault.add("fixture", "new", grant()) }

        assertNull(body.bytes)
        assertNull(index.bytes)
        assertTrue(vault.summaries().isEmpty())
    }

    @Test fun failedBodyRollbackBlocksAccessUntilExplicitPurge() {
        val body = Store()
        val index = Store()
        val vault = SecureClipboardVault(body, index)
        vault.add("fixture", "original", grant())
        body.failures = 2

        assertThrows(IOException::class.java) { vault.add("fixture", "new", grant()) }
        assertThrows(IOException::class.java) { vault.summaries() }
        assertThrows(IOException::class.java) { vault.metadata(grant()) }
        assertThrows(IOException::class.java) { vault.read("fixture", grant()) }
        vault.purge()
        assertTrue(vault.summaries().isEmpty())
    }

    private fun grant() = AuthenticationGrant.afterSuccessfulSystemAuthentication()

    private class Store : EncryptedStore {
        var bytes: ByteArray? = null
        var failures = 0
        var afterWrite: () -> Unit = {}
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            bytes = plaintext.copyOf()
            afterWrite()
            if (failures > 0) {
                failures--
                throw IOException("Fixture write failure")
            }
        }
        override fun delete(deleteKey: Boolean) { bytes = null }
    }
}

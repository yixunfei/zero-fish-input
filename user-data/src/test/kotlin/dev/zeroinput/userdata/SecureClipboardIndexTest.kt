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
class SecureClipboardIndexTest {
    @Test fun healthyMetadataReadsDoNotRewriteIndexEvenAfterRestart() {
        val body = Store()
        val index = Store()
        val vault = SecureClipboardVault(body, index)
        val item = vault.add("public label", "public text", grant())
        val writes = index.writes
        repeat(2) { assertEquals(item.id, vault.metadata(grant()).single().id) }
        assertEquals(item.id, SecureClipboardVault(body, index).metadata(grant()).single().id)
        assertEquals(writes, index.writes)
    }

    @Test fun transientIndexFailureIsReportedWithoutReadingBodyAndCanRetry() {
        val body = Store()
        val index = Store()
        SecureClipboardVault(body, index).add("public label", "public text", grant())
        val vault = SecureClipboardVault(body, index)
        val reads = body.reads
        index.failRead = true
        val failure = assertThrows(IOException::class.java) { vault.summaries() }
        assertFalse(failure.toString().contains("fixture marker"))
        assertNull(failure.cause)
        assertEquals(reads, body.reads)
        index.failRead = false
        assertEquals(1, vault.summaries().size)
        assertEquals(reads, body.reads)
    }

    @Test fun authenticatedMetadataRepairsMissingCorruptOrStaleIndexOnlyOnce() {
        for (damage in listOf<ByteArray?>(null, "broken".toByteArray(), "{\"format\":1,\"entries\":[]}".toByteArray())) {
            val body = Store()
            val index = Store()
            val item = SecureClipboardVault(body, index).add("public label", "public text", grant())
            index.bytes = damage
            val vault = SecureClipboardVault(body, index)
            val writes = index.writes
            assertEquals(item.id, vault.metadata(grant()).single().id)
            assertEquals(item.id, vault.summaries().single().id)
            vault.metadata(grant())
            assertEquals(writes + 1, index.writes)
            assertTrue(checkNotNull(body.lastRead).all { it == 0.toByte() })
        }
    }

    @Test fun failedIndexWriteHidesOldRowsUntilAuthenticatedRepair() {
        val body = Store()
        val index = Store()
        val vault = SecureClipboardVault(body, index)
        val item = vault.add("", "public text", grant())
        index.failWrite = true
        assertThrows(IOException::class.java) { vault.remove(item.id, grant()) }
        assertThrows(IOException::class.java) { vault.summaries() }
        assertThrows(IOException::class.java) { vault.metadata(grant()) }
        index.failWrite = false
        assertTrue(vault.metadata(grant()).isEmpty())
        assertTrue(vault.summaries().isEmpty())
    }

    @Test fun clearDuringIndexCheckCannotRecreateDeletedData() {
        val body = Store()
        val index = Store()
        SecureClipboardVault(body, index).add("", "public text", grant())
        val vault = SecureClipboardVault(body, index)
        index.afterRead = { vault.purge() }
        assertThrows(CancellationException::class.java) { vault.metadata(grant()) }
        assertNull(body.bytes)
        assertNull(index.bytes)
    }

    @Test fun consumedGrantCannotReadBodyOrRepairIndex() {
        val body = Store()
        val index = Store().apply { bytes = "broken".toByteArray() }
        val authorization = grant()
        assertTrue(authorization.consume())
        assertThrows(IllegalArgumentException::class.java) {
            SecureClipboardVault(body, index).metadata(authorization)
        }
        assertEquals(0, body.reads)
        assertEquals(0, index.writes)
    }

    @Test fun cancelledIndexReadCannotBeConvertedIntoARepairWrite() {
        val body = Store()
        val index = Store()
        SecureClipboardVault(body, index).add("", "public text", grant())
        val vault = SecureClipboardVault(body, index)
        val writes = index.writes
        index.afterRead = { throw CancellationException("fixture cancellation") }
        assertThrows(CancellationException::class.java) { vault.metadata(grant()) }
        assertEquals(writes, index.writes)
    }

    private fun grant() = AuthenticationGrant.afterSuccessfulSystemAuthentication()

    private class Store : EncryptedStore {
        var bytes: ByteArray? = null
        var lastRead: ByteArray? = null
        var reads = 0
        var writes = 0
        var failRead = false
        var failWrite = false
        var afterRead: () -> Unit = {}
        override fun read(): ByteArray? {
            reads++
            if (failRead) throw IOException("fixture marker")
            return bytes?.copyOf().also { lastRead = it; afterRead() }
        }
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw IOException("fixture write failure")
            bytes = plaintext.copyOf()
            writes++
        }
        override fun delete(deleteKey: Boolean) { bytes = null }
    }
}

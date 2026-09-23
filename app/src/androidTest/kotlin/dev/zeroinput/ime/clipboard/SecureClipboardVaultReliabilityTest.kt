package dev.zeroinput.ime.clipboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.SecureClipboardVault
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureClipboardVaultReliabilityTest {
    @Test fun queuedManagementOperationsCannotUseAGenerationFromBeforeClear() {
        val body = FaultStore()
        val vault = SecureClipboardVault(body, FaultStore())
        val item = vault.add("", "public fixture", grant())
        val generation = vault.captureGeneration()
        vault.clear(grant())
        body.afterRead = { fail("An expired management request must not read storage") }
        assertThrows(CancellationException::class.java) { vault.metadata(grant(), generation) }
        assertThrows(CancellationException::class.java) { vault.remove(item.id, grant(), generation) }
    }

    @Test fun concurrentClearRevokesBlockedMetadataAndRemovalOperations() {
        for (remove in listOf(false, true)) {
            val body = FaultStore()
            val index = FaultStore()
            val vault = SecureClipboardVault(body, index)
            val item = vault.add("", "public fixture", grant())
            val generation = vault.captureGeneration()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            body.afterRead = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val operation = pool.submit {
                    assertThrows(CancellationException::class.java) {
                        if (remove) vault.remove(item.id, grant(), generation) else vault.metadata(grant(), generation)
                    }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val clear = pool.submit { vault.clear(grant()) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (vault.captureGeneration() == generation && System.nanoTime() < deadline) Thread.yield()
                assertNotEquals(generation, vault.captureGeneration())
                release.countDown()
                operation.get(5, TimeUnit.SECONDS)
                clear.get(5, TimeUnit.SECONDS)
                assertNull(body.bytes)
                assertNull(index.bytes)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun failedBodyOrIndexDeletionBlocksEveryAccessUntilRetrySucceeds() {
        for (failBody in listOf(true, false)) {
            val body = FaultStore()
            val index = FaultStore()
            val vault = SecureClipboardVault(body, index)
            val item = vault.add("label fixture", "public fixture", grant())
            assertEquals(1, vault.count())
            val failing = if (failBody) body else index
            failing.failDelete = true
            assertThrows(IOException::class.java) { vault.clear(grant()) }
            assertEquals(1, body.deletes)
            assertEquals(1, index.deletes)
            assertThrows(IOException::class.java) { vault.count() }
            assertThrows(IOException::class.java) { vault.summaries() }
            assertThrows(IOException::class.java) { vault.read(item.id, grant()) }
            assertThrows(IOException::class.java) { vault.metadata(grant()) }
            assertThrows(IOException::class.java) { vault.add("", "next fixture", grant()) }
            assertThrows(IOException::class.java) { vault.remove(item.id, grant()) }
            failing.failDelete = false
            vault.clear(grant())
            assertNull(body.bytes)
            assertNull(index.bytes)
            assertEquals(0, vault.count())
            assertEquals("new fixture", vault.read(vault.add("", "new fixture", grant()).id, grant()))
        }
    }

    @Test fun malformedBodyErrorsContainNoInputAndCannotOverwriteTheOriginal() {
        val marker = "public sensitive fixture"
        val malformed = "{\"format\":1,\"entries\":[{\"id\":\"$ID\",\"value\":\"$marker\",\"label\":\"label\",\"updatedAt\":\"$marker\"}]}"
        val body = FaultStore().apply { bytes = malformed.toByteArray() }
        val vault = SecureClipboardVault(body, FaultStore())
        val failure = assertThrows(Exception::class.java) { vault.metadata(grant()) }
        assertFalse(failure.toString().contains(marker))
        assertNull(failure.cause)
        assertThrows(Exception::class.java) { vault.add("", "next fixture", grant()) }
        assertArrayEquals(malformed.toByteArray(), body.bytes)
        assertEquals(0, body.writes)
        assertTrue(checkNotNull(body.lastRead).all { it == 0.toByte() })
    }

    @Test fun clearingDuringMetadataLoadCannotReturnLabelsOrRecreateTheIndex() {
        val body = FaultStore()
        val index = FaultStore()
        val vault = SecureClipboardVault(body, index)
        vault.add("label fixture", "public fixture", grant())
        body.afterRead = { vault.clear(grant()) }
        assertThrows(CancellationException::class.java) { vault.metadata(grant()) }
        assertNull(index.bytes)
        assertNull(body.bytes)
    }

    @Test fun clearingDuringRemovalLoadCannotRestoreOtherDeletedEntries() {
        val body = FaultStore()
        val index = FaultStore()
        val vault = SecureClipboardVault(body, index)
        val first = vault.add("", "first fixture", grant())
        vault.add("", "second fixture", grant())
        body.afterRead = { vault.clear(grant()) }
        assertThrows(CancellationException::class.java) { vault.remove(first.id, grant()) }
        assertNull(body.bytes)
        assertNull(index.bytes)
    }

    @Test fun anIndexRefreshFailureDoesNotLeaveCachedDeletedRowsVisible() {
        val body = FaultStore()
        val index = FaultStore()
        val vault = SecureClipboardVault(body, index)
        val item = vault.add("", "public fixture", grant())
        assertEquals(1, vault.count())
        index.failWrite = true
        assertThrows(IOException::class.java) { vault.remove(item.id, grant()) }
        assertTrue(vault.summaries().isEmpty())
        index.failWrite = false
        assertTrue(vault.metadata(grant()).isEmpty())
        assertEquals(0, vault.count())
    }

    private fun grant() = AuthenticationGrant.afterSuccessfulSystemAuthentication()

    private class FaultStore : EncryptedStore {
        var bytes: ByteArray? = null
        var lastRead: ByteArray? = null
        var deletes = 0
        var writes = 0
        var failDelete = false
        var failWrite = false
        var afterRead: () -> Unit = {}
        override fun read(): ByteArray? = bytes?.copyOf().also { lastRead = it; afterRead() }
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw IOException("Fixture write failure")
            bytes = plaintext.copyOf()
            writes++
        }
        override fun delete(deleteKey: Boolean) {
            deletes++
            if (failDelete) throw IOException("Fixture deletion failure")
            bytes = null
        }
    }

    private companion object { const val ID = "00000000-0000-0000-0000-000000000001" }
}

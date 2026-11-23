package dev.zeroinput.userdata

import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import dev.zeroinput.security.AuthenticationGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivateDraftIndexRecoveryTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun unreadableDraftRemainsIndividuallyDeletableAfterAuthentication() {
        val directory = files.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext() = this
        }
        val vault = SecureClipboardVault(context)
        val id = "ab12abcd-1234-1234-1234-0123456789ab"
        val body = File(directory, "encrypted/private-drafts/$id.bin").apply {
            parentFile?.mkdirs()
            writeText("public corrupt envelope fixture")
        }
        val grant = AuthenticationGrant.afterSuccessfulSystemAuthentication()
        val item = vault.metadata(grant).single()
        assertEquals(id, item.id)
        assertNull(item.valueLength)
        assertThrows(IllegalArgumentException::class.java) { vault.remove(id, grant) }
        assertTrue(body.exists())
        assertTrue(vault.remove(id, AuthenticationGrant.afterSuccessfulSystemAuthentication()))
        assertFalse(body.exists())
        assertTrue(vault.metadata(AuthenticationGrant.afterSuccessfulSystemAuthentication()).isEmpty())
    }

    @Test fun missingLegacyIndexBlocksPrivateAppendBeforeAnyBodyOrKeyAccess() {
        val directory = files.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext() = this
        }
        val vault = SecureClipboardVault(context) { true }
        // Prime the cache as empty before simulating an existing body with a lost index.
        org.junit.Assert.assertEquals(0, vault.count())
        File(directory, "encrypted/secure-clipboard.bin").apply {
            parentFile?.mkdirs()
            writeText("public corrupt envelope fixture")
        }
        assertThrows(IOException::class.java) {
            vault.addPrivateDraft("public draft fixture".toCharArray(), vault.captureGeneration()) { true }
        }
        assertFalse(File(directory, "encrypted/private-drafts").exists())
    }
}

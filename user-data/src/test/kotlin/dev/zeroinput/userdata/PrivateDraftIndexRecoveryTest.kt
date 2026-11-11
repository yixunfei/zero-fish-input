package dev.zeroinput.userdata

import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
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

package dev.zeroinput.ime

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.security.AesGcmKeyStore
import dev.zeroinput.security.EncryptedFileStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityStorageBoundaryTest {
    @Test fun vaultPurgeDeletesBothEncryptedFilesAndKeysWithoutParsingTheirContents() = withStore { body, bodyFile, bodyAlias ->
        withStore { index, indexFile, indexAlias ->
            body.write("unparseable public body fixture".toByteArray())
            index.write("unparseable public index fixture".toByteArray())
            assertTrue(keys().containsAlias(bodyAlias))
            assertTrue(keys().containsAlias(indexAlias))
            dev.zeroinput.userdata.SecureClipboardVault(body, index).purge()
            assertFalse(bodyFile.exists())
            assertFalse(indexFile.exists())
            assertFalse(keys().containsAlias(bodyAlias))
            assertFalse(keys().containsAlias(indexAlias))
        }
    }

    @Test fun anUnremovedBackupCannotReportASuccessfulWrite() = withStore { store, file, _ ->
        store.write("public fixture".toByteArray())
        File(file.path + ".bak").apply { mkdir(); resolve("public-fixture").writeText("fixture") }
        val plaintext = "updated fixture".toByteArray()
        assertThrows(IOException::class.java) { store.write(plaintext) }
        assertTrue(plaintext.all { it == 0.toByte() })
    }

    @Test fun failedAtomicPromotionCannotReportASuccessfulWrite() = withStore { store, file, _ ->
        assertTrue(file.mkdirs())
        File(file, "public-fixture").writeText("fixture")
        val plaintext = "public fixture".toByteArray()
        assertThrows(IOException::class.java) { store.write(plaintext) }
        assertTrue(plaintext.all { it == 0.toByte() })
    }

    @Test fun lostKeyReadsNeverCreateAReplacementKey() = withStore { store, file, alias ->
        store.write("public fixture".toByteArray())
        val envelope = file.readBytes()
        AesGcmKeyStore(alias).deleteKey()
        repeat(2) {
            assertThrows(GeneralSecurityException::class.java) { store.read() }
            assertFalse(keys().containsAlias(alias))
            assertArrayEquals(envelope, file.readBytes())
        }
    }

    @Test fun missingFilesAreEmptyButExistingUnreadableFilesFailClosed() = withStore { store, file, _ ->
        assertNull(store.read())
        assertTrue(file.mkdirs())
        File(file, "public-fixture").writeText("fixture")
        assertThrows(IOException::class.java) { store.read() }
    }

    @Test fun incompleteAtomicDeletionCannotReportSuccess() = withStore { store, file, alias ->
        store.write("public fixture".toByteArray())
        // AtomicFile.delete() silently ignores a failed File.delete().
        val backup = File(file.path + ".bak")
        assertTrue(backup.mkdir())
        val child = File(backup, "public-fixture").apply { writeText("fixture") }
        assertThrows(IOException::class.java) { store.delete(deleteKey = true) }
        assertFalse(keys().containsAlias(alias))
        assertTrue(child.delete())
        store.delete(deleteKey = true)
        assertFalse(backup.exists())
        assertNull(store.read())
    }

    @Test fun failedWritesStillWipeTheCallersPlaintext() = withStore { store, file, _ ->
        val parent = checkNotNull(file.parentFile)
        assertTrue(checkNotNull(parent.parentFile).mkdirs())
        parent.writeText("public fixture")
        val bytes = "public fixture".toByteArray()
        assertThrows(IOException::class.java) { store.write(bytes) }
        assertTrue(bytes.all { it == 0.toByte() })
    }

    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun withStore(action: (EncryptedFileStore, File, String) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.noBackupFilesDir, "test-security-${UUID.randomUUID()}")
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = directory }
        val alias = "dev.zeroinput.test.security.${UUID.randomUUID()}"
        try {
            action(EncryptedFileStore(context, "fixture.bin", alias), File(directory, "encrypted/fixture.bin"), alias)
        } finally {
            AesGcmKeyStore(alias).deleteKey()
            check(directory.canonicalFile.parentFile == target.noBackupFilesDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}

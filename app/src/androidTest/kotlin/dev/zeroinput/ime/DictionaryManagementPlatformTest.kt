package dev.zeroinput.ime

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import dev.zeroinput.languagepack.BuiltInDictionaryStore
import dev.zeroinput.languagepack.PublicDictionaryStore
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.userdata.DictionaryEdit
import dev.zeroinput.userdata.DictionaryEditRepository
import dev.zeroinput.userdata.DictionaryWord
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DictionaryManagementPlatformTest {
    @Test fun builtInDeletionKeepsBaseCharactersAndRestoresOfflineWhileDownloadsRetainEditsAndDisabledState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "dictionary-management-test")
        check(!root.exists())
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val downloads = PublicDictionaryStore(isolated)
        val bundled = BuiltInDictionaryStore(isolated, downloads)
        val edits = DictionaryEditRepository(EncryptedFileStore(isolated, "fixture.bin", "zeroinput.test.dictionary-edits"))
        val word = DictionaryWord("公开测试", "gong kai ce shi", 42)
        fun install(id: String) = downloads.install(id, "Fixture", "WANXIANG", "fixture",
            "---\nname: fixture\n...\n公开测试\tgong kai ce shi\t1\n".byteInputStream(), RimeTextDictionaryParser())
        try {
            assertEquals(16, bundled.list().size)
            assertThrows(IllegalArgumentException::class.java) { bundled.remove("zi") }
            assertThrows(IllegalArgumentException::class.java) { bundled.setEnabled("zi", false) }
            bundled.remove("jichu")
            bundled.withSnapshot { assertTrue("jichu" in it.disabledBuiltInTables); assertFalse("zi" in it.disabledBuiltInTables) }
            bundled.restore("jichu")
            assertTrue(bundled.list().single { it.table == "jichu" }.enabled)
            install("fixture")
            downloads.setEnabled("fixture", false)
            edits.save(DictionaryEdit("fixture", null, word))
            install("fixture")
            assertFalse(downloads.list().single().enabled)
            assertEquals(word, edits.read().entries.single().replacement)
            install("builtin_jichu")
            bundled.withSnapshot { assertEquals(setOf("jichu"), it.builtInReplacements.keys); assertTrue(it.extraFiles.isEmpty()) }
            bundled.remove("jichu")
            assertFalse(downloads.list().any { it.id == "builtin_jichu" })
        } finally { edits.clear(); root.deleteRecursively() }
    }

    @Test fun encryptedEditsRejectTamperingAndLostKeysWithoutRewritingData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "dictionary-encryption-test")
        check(!root.exists())
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val alias = "zeroinput.test.dictionary-integrity"
        val edits = DictionaryEditRepository(EncryptedFileStore(isolated, "fixture.bin", alias))
        try {
            edits.save(DictionaryEdit("fixture", null, DictionaryWord("公开测试", "gong kai ce shi", 1)))
            val file = File(root, "encrypted/fixture.bin")
            val original = file.readBytes()
            assertFalse(String(original, Charsets.UTF_8).contains("公开测试"))
            file.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertThrows(Exception::class.java) { edits.read() }
            file.writeBytes(original)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            assertThrows(Exception::class.java) { edits.read() }
        } finally { edits.clear(); root.deleteRecursively() }
    }
}

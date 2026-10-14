package dev.zeroinput.ime.dictionaries

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReviewedDictionaryArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val revision = "a".repeat(40)
    @Test fun onlyTheRequestedReviewedTableIsReadAndNoArchivePathsAreExtracted() {
        val archive = archive("rime-wanxiang-$revision/dicts/zi.dict.yaml" to "public fixture",
            "../../ignored.txt" to "not extracted")
        val value = DictionaryDownloads.withVerifiedEntry(archive, hash(archive), revision, "zi") { it.reader().readText() }
        assertEquals("public fixture", value)
        assertEquals(1, temporary.root.listFiles()?.size)
    }
    @Test fun wrongHashMissingTableAndTraversalAreRejectedBeforeConsumption() {
        val archive = archive("rime-wanxiang-$revision/dicts/zi.dict.yaml" to "public fixture")
        var consumed = false
        assertThrows(IllegalArgumentException::class.java) {
            DictionaryDownloads.withVerifiedEntry(archive, "0".repeat(64), revision, "zi") { consumed = true }
        }
        assertThrows(NoSuchElementException::class.java) {
            DictionaryDownloads.withVerifiedEntry(archive, hash(archive), revision, "jichu") { consumed = true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DictionaryDownloads.withVerifiedEntry(archive, hash(archive), revision, "../zi") { consumed = true }
        }
        assertFalse(consumed)
    }
    private fun archive(vararg entries: Pair<String, String>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

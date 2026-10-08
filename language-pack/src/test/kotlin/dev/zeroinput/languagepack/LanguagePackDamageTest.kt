package dev.zeroinput.languagepack

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Test

class LanguagePackDamageTest {
    @Test fun missingDeclaredTextFileRejectsTheWholeDictionary() = damaged { it.delete() }

    @Test fun truncatedTextRejectsTheWholeDictionary() = damaged { it.writeText("ni\t你") }

    @Test fun sameSizeModifiedTextRejectsTheWholeDictionary() = damaged { it.writeText("hao\t坏\n") }

    @Test fun malformedUtf8AfterValidTextRejectsTheWholeDictionary() = damaged(redeclare = true) {
        it.appendBytes(byteArrayOf(0xc3.toByte()))
    }

    private fun damaged(redeclare: Boolean = false, change: (File) -> Unit) {
        val directory = Files.createTempDirectory("pack-damage").toFile()
        try {
            val good = File(directory, "good.txt").apply { writeText("ni\t你\n") }
            val bad = File(directory, "bad.txt").apply { writeText("hao\t好\n") }
            val original = listOf(good, bad).map(::declared)
            change(bad)
            val files = if (redeclare) listOf(good, bad).map(::declared) else original
            val manifest = LanguagePackManifest(1, "damage", "Public fixture", "zh", "1", "damage", files)
            assertFalse(LanguagePackEngineFactory(InstalledLanguagePack(manifest, directory)).isAvailable())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun declared(file: File) = LanguagePackFile(file.name,
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }, file.length())
}

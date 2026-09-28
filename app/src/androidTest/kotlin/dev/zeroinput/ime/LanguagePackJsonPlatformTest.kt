package dev.zeroinput.ime

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.languagepack.LanguagePackEngineFactory
import dev.zeroinput.languagepack.LanguagePackInstaller
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguagePackJsonPlatformTest {
    @Test fun androidParserRejectsDepthAndUnquotedTokenBypasses() = withInstaller { installer ->
        val nested = "[".repeat(32) + "0" + "]".repeat(32)
        val invalid = listOf(
            "{\"extra\":$nested}",
            "{x\":$nested,y\":0}",
            manifest() + " {}",
            manifest() + "\u0000{}",
            "/* \" */" + manifest(),
            manifest().replace("\"formatVersion\"", "formatVersion"),
        )
        invalid.forEach { document ->
            assertThrows(IllegalArgumentException::class.java) { install(installer, document) }
            assertTrue(installer.listInstalled().isEmpty())
        }
        val original = install(installer, manifest())
        invalid.forEach { document ->
            assertThrows(IllegalArgumentException::class.java) { install(installer, document) }
            assertEquals(listOf(original), installer.listInstalled())
            assertArrayEquals(DICTIONARY, File(original.directory, DICTIONARY_PATH).readBytes())
        }
    }

    @Test fun androidParserRejectsMalformedAndCoercedManifestWithoutReplacingPack() = withInstaller { installer ->
        val original = install(installer, manifest())
        val invalid = listOf(
            manifest().replace("\"formatVersion\":1", "\"formatVersion\":1.9"),
            manifest().replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            manifest().replace("\"id\":\"fixture\"", "\"id\":true"),
            manifest().replace("\"id\":\"fixture\"", "\"id\":\"other\",\"id\":\"fixture\""),
            manifest().replace("\"files\":", "\"extra\":[1,],\"files\":"),
            manifest().replace("\"files\":", "\"extra\":[1,,2],\"files\":"),
        )
        invalid.forEach { document ->
            assertThrows(IllegalArgumentException::class.java) { install(installer, document) }
            assertEquals(listOf(original), installer.listInstalled())
            assertArrayEquals(DICTIONARY, File(original.directory, DICTIONARY_PATH).readBytes())
        }
    }

    @Test fun androidParserPreservesValidQuotedBracketsAndEscapes() = withInstaller { installer ->
        val original = install(installer, manifest())
        assertEquals("Fixture", original.manifest.displayName)
        assertTrue(LanguagePackEngineFactory(original).isAvailable())
        val escaped = manifest().replace("Fixture", "[ \\\" { \\\" ]")
        val replacement = install(installer, escaped)
        assertEquals("[ \" { \" ]", replacement.manifest.displayName)
        assertEquals(original.key, replacement.key)
        assertEquals(listOf(replacement), installer.listInstalled())
        assertArrayEquals(DICTIONARY, File(replacement.directory, DICTIONARY_PATH).readBytes())
        assertTrue(LanguagePackEngineFactory(replacement).isAvailable())
    }

    private fun withInstaller(block: (LanguagePackInstaller) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = target.cacheDir.canonicalFile
        val fixture = File(parent, "language-pack-json-fixture-${UUID.randomUUID()}").canonicalFile
        check(fixture.parentFile == parent && fixture.mkdir())
        try {
            val storage = File(fixture, "no-backup").apply { check(mkdir()) }
            val cache = File(fixture, "cache").apply { check(mkdir()) }
            val context = object : ContextWrapper(target) {
                override fun getNoBackupFilesDir(): File = storage
                override fun getCacheDir(): File = cache
            }
            block(LanguagePackInstaller(context))
        } finally {
            check(fixture.deleteRecursively())
        }
    }

    private fun install(installer: LanguagePackInstaller, document: String) =
        archive(document).inputStream().use(installer::install)

    private fun archive(document: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(document.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DICTIONARY_PATH))
            zip.write(DICTIONARY)
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private fun manifest(): String {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(DICTIONARY)
            .joinToString("") { "%02x".format(it) }
        return """
            {
              "formatVersion":1,"id":"fixture","displayName":"Fixture","languageTag":"zh",
              "version":"1","engineId":"fixture",
              "files":[{"path":"$DICTIONARY_PATH","sha256":"$sha256","size":${DICTIONARY.size}}]
            }
        """.trimIndent()
    }

    private companion object {
        const val DICTIONARY_PATH = "dictionary.txt"
        val DICTIONARY = "ni\t你\n".toByteArray(Charsets.UTF_8)
    }
}

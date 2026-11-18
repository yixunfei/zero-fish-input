package dev.zeroinput.ime

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.languagepack.LanguagePackEngineFactory
import dev.zeroinput.languagepack.LanguagePackFile
import dev.zeroinput.languagepack.LanguagePackInstaller
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguagePackJsonPlatformTest {
    @Test fun corruptedEnableStateCannotEnableANewlyInstalledPack() = withInstaller { installer ->
        val original = install(installer, manifest())
        val state = File(requireNotNull(requireNotNull(requireNotNull(original.directory.parentFile).parentFile).parentFile), "enabled.json")
        state.writeText("{ invalid public fixture")
        val added = install(installer, manifest().replace("\"id\":\"fixture\"", "\"id\":\"second\""))
        assertFalse(added.enabled)
        assertTrue(installer.listInstalled().none { it.enabled })
    }

    @Test fun symlinkMarkedPayloadIsRejectedBeforeReplacingAnInstalledPack() = withInstaller { installer ->
        val original = install(installer, manifest())
        val bytes = archive(manifest())
        val headers = (0 until bytes.size - 46).filter { offset ->
            bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4b.toByte() &&
                bytes[offset + 2] == 1.toByte() && bytes[offset + 3] == 2.toByte()
        }
        bytes[headers.last() + 5] = 3
        bytes[headers.last() + 41] = 0xa0.toByte()
        assertThrows(IllegalArgumentException::class.java) { bytes.inputStream().use(installer::install) }
        assertEquals(listOf(original), installer.listInstalled())
    }

    @Test fun rapidSerialTogglesUseRequestedValueEvenWithTheOriginalPackSnapshot() = withInstaller { installer ->
        val original = install(installer, manifest())
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val tasks = (0 until 21).map { index -> worker.submit {
                val enabled = index % 2 != 0
                assertTrue(installer.setEnabled(original, enabled))
                assertEquals(enabled, installer.installedSnapshot().single().enabled)
            } }
            tasks.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertFalse(installer.listInstalled().single().enabled)
            assertTrue(original.enabled)
        } finally { worker.shutdownNow() }
    }

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

    @Test fun streamingDictionaryPreservesRecordPrecedenceAndMappedValuesOnAndroid() = withInstaller { installer ->
        val original = install(installer, manifest())
        val file = File(original.directory, "stream.json").apply {
            writeText("""[{"ni":["你","妮"]},{"extra":"ignored","value":"好","shortcut":"hao"}]""")
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        val pack = original.copy(manifest = original.manifest.copy(
            files = listOf(LanguagePackFile(file.name, sha256, file.length()))))
        val factory = LanguagePackEngineFactory(pack)
        assertTrue(factory.isAvailable())
        val engine = factory.create()
        try {
            engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
            "ni".forEach { engine.handle(EngineKey.Character(it.toString())) }
            assertEquals(listOf("你", "妮"), engine.snapshot.candidates.map { it.text })
            engine.reset()
            "hao".forEach { engine.handle(EngineKey.Character(it.toString())) }
            assertEquals(listOf("好"), engine.snapshot.candidates.map { it.text })
            engine.reset()
            "extra".forEach { engine.handle(EngineKey.Character(it.toString())) }
            assertTrue(engine.snapshot.candidates.isEmpty())
        } finally { engine.close() }
    }

    @Test fun invalidStreamingDictionaryRejectsTheWholePackWithCorrectHashes() = withInstaller { installer ->
        val original = install(installer, manifest())
        val file = File(original.directory, "stream.json")
        for (document in listOf("""{"hao":"好"} {}""", """{"hao":"好","\u0068ao":"坏"}""",
            """{"hao":"\u１２３４"}""", "{\"hao\":\"${"a".repeat(4097)}\"}")) {
            file.writeText(document)
            val sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            val pack = original.copy(manifest = original.manifest.copy(files = original.manifest.files +
                LanguagePackFile(file.name, sha256, file.length())))
            assertFalse(LanguagePackEngineFactory(pack).isAvailable())
        }
    }

    @Test fun signedStagingNamesAreRecoveredWithoutTouchingUnrelatedDirectories() = withInstaller { installer ->
        // The installer fixture uses a dedicated noBackup root; resolve its owned
        // staging directory through the installed package, never app-wide storage.
        val installed = install(installer, manifest())
        val root = checkNotNull(installed.directory.parentFile?.parentFile?.parentFile)
        val staging = File(root, ".staging").apply { mkdirs() }
        val owned = listOf("pack2--1", "pack---9223372036854775808", "${"x".repeat(64)}-0").map {
            File(staging, it).apply { check(mkdir()) }
        }
        val unrelated = File(staging, "unrelated").apply { check(mkdir()) }
        installer.refreshInstalled()
        assertTrue(owned.none(File::exists))
        assertTrue(unrelated.isDirectory)
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

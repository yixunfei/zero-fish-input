package dev.zeroinput.languagepack

import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LanguagePackEngineTest {
    @Test fun `composition remains bounded when a pack receives excessive input`() {
        val directory = Files.createTempDirectory("zeroinput-pack-bound").toFile()
        try {
            val dictionary = File(directory, "dictionary.txt").apply { writeText("a\tA\n") }
            val manifest = LanguagePackManifest(1, "bounded", "Bounded", "en", "1", "bounded",
                listOf(LanguagePackFile(dictionary.name, sha256(dictionary), dictionary.length())))
            val engine = LanguagePackEngineFactory(InstalledLanguagePack(manifest, directory)).create()
            engine.start(EditorContext(InputLanguage.ENGLISH, false, false, "fixture", predictionsAllowed = true))
            repeat(128) { engine.handle(EngineKey.Character("a")) }
            val rejected = engine.handle(EngineKey.Character("a"))
            assertFalse(rejected.consumed)
            assertEquals(128, engine.snapshot.rawInput.length)
        } finally { directory.deleteRecursively() }
    }
    @Test fun `no predictions disables English expansion but retains Chinese conversion`() {
        val directory = Files.createTempDirectory("zeroinput-pack-predictions").toFile()
        try {
            val dictionary = File(directory, "dictionary.txt").apply { writeText("hel\thello\nni\t你\n") }
            for (language in InputLanguage.entries) {
                val chinese = language == InputLanguage.CHINESE
                val manifest = LanguagePackManifest(1, "test-predictions", "Test predictions", if (chinese) "zh" else "en",
                    "1", "zeroinput.dictionary.predictions",
                    listOf(LanguagePackFile(dictionary.name, sha256(dictionary), dictionary.length())))
                val engine = LanguagePackEngineFactory(InstalledLanguagePack(manifest, directory)).create()
                engine.start(EditorContext(language, false, false, null, predictionsAllowed = false))
                (if (chinese) "ni" else "hel").forEach { engine.handle(EngineKey.Character(it.toString())) }
                assertEquals(!chinese, engine.snapshot.candidates.isEmpty())
                assertEquals(if (chinese) "你 " else "hel ", engine.handle(EngineKey.Space).committedText)
                engine.close()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `enabled text dictionary becomes an input engine`() {
        val directory = Files.createTempDirectory("zeroinput-pack").toFile()
        try {
            val dictionary = File(directory, "dictionary.txt").apply {
                writeText("ni\t你\nnihao\t你好\n")
            }
            val manifest = LanguagePackManifest(
                formatVersion = 1,
                id = "test-pack",
                displayName = "测试语言包",
                languageTag = "zh-CN",
                version = "1",
                engineId = "zeroinput.dictionary",
                files = listOf(
                    LanguagePackFile(
                        path = dictionary.name,
                        sha256 = sha256(dictionary),
                        size = dictionary.length(),
                    ),
                ),
            )
            val factory = LanguagePackEngineFactory(InstalledLanguagePack(manifest, directory))

            assertTrue(factory.isAvailable())
            val engine = factory.create()
            engine.start(EditorContext(InputLanguage.CHINESE, false, true, "test"))
            engine.handle(EngineKey.Character("n"))
            val update = engine.handle(EngineKey.Character("i"))

            assertEquals(listOf("你", "你好"), update.snapshot.candidates.map { it.text })
            assertEquals("ni", engine.handle(EngineKey.Enter).committedText)
            assertFalse(engine.snapshot.isComposing)
            assertFalse(engine.handle(EngineKey.Enter).consumed)
            engine.handle(EngineKey.Character("q"))
            assertEquals("q", engine.handle(EngineKey.Enter).committedText)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unsupported symbols are returned to the editor instead of being composed`() {
        val directory = Files.createTempDirectory("zeroinput-pack-symbol").toFile()
        try {
            val dictionary = File(directory, "dictionary.txt").apply { writeText("ni\t你\n") }
            val manifest = LanguagePackManifest(
                formatVersion = 1,
                id = "test-pack-symbol",
                displayName = "测试语言包",
                languageTag = "zh-CN",
                version = "1",
                engineId = "zeroinput.dictionary.symbol",
                files = listOf(
                    LanguagePackFile(dictionary.name, sha256(dictionary), dictionary.length()),
                ),
            )
            val engine = LanguagePackEngineFactory(InstalledLanguagePack(manifest, directory)).create()
            engine.start(EditorContext(InputLanguage.CHINESE, false, true, "test"))

            val update = engine.handle(EngineKey.Character("¥"))

            assertEquals("¥", update.committedText)
            assertTrue(update.snapshot.candidates.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `top level JSON array dictionary becomes candidates`() {
        val directory = Files.createTempDirectory("zeroinput-pack-json-array").toFile()
        try {
            val dictionary = File(directory, "dictionary.json").apply {
                writeText(
                    """
                    [
                      {"shortcut":"ni","value":"你"},
                      {"shortcut":"nihao","value":"你好"}
                    ]
                    """.trimIndent(),
                )
            }
            val manifest = LanguagePackManifest(
                formatVersion = 1,
                id = "test-pack-json-array",
                displayName = "JSON 数组语言包",
                languageTag = "zh-CN",
                version = "1",
                engineId = "zeroinput.dictionary.json-array",
                files = listOf(
                    LanguagePackFile(dictionary.name, sha256(dictionary), dictionary.length()),
                ),
            )
            val engine = LanguagePackEngineFactory(InstalledLanguagePack(manifest, directory)).create()
            engine.start(EditorContext(InputLanguage.CHINESE, false, true, "test"))

            engine.handle(EngineKey.Character("n"))
            val update = engine.handle(EngineKey.Character("i"))

            assertEquals(listOf("你", "你好"), update.snapshot.candidates.map { it.text })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unsupported language tag is not registered as an English engine`() {
        val directory = Files.createTempDirectory("zeroinput-pack-unsupported").toFile()
        try {
            val dictionary = File(directory, "dictionary.txt").apply { writeText("kana\tかな\n") }
            val manifest = LanguagePackManifest(
                formatVersion = 1,
                id = "test-pack-unsupported",
                displayName = "Unsupported pack",
                languageTag = "ja-JP",
                version = "1",
                engineId = "zeroinput.dictionary.unsupported",
                files = listOf(
                    LanguagePackFile(dictionary.name, sha256(dictionary), dictionary.length()),
                ),
            )
            val factory = LanguagePackEngineFactory(InstalledLanguagePack(manifest, directory))

            assertFalse(factory.isAvailable())
            assertTrue(factory.descriptor.languages.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}

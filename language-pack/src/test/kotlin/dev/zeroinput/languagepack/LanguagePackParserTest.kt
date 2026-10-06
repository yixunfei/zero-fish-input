package dev.zeroinput.languagepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import dev.zeroinput.engine.api.InputLanguage

class LanguagePackParserTest {
    @Test
    fun `accepts a UTF-8 BOM before the manifest object`() {
        val manifest = LanguagePackParser.parse(
            "\uFEFF" +
                """
                {
                  "formatVersion": 1,
                  "id": "bom-pack",
                  "displayName": "BOM pack",
                  "languageTag": "zh-CN",
                  "version": "1",
                  "engineId": "zeroinput.bom",
                  "files": [{"path":"dictionary.txt","sha256":"${"0".repeat(64)}","size":0}]
                }
                """.trimIndent(),
        )

        assertEquals("bom-pack", manifest.id)
        assertEquals("zh-CN", manifest.languageTag)
    }

    @Test
    fun `accepts an integral floating point format version`() {
        val manifest = LanguagePackParser.parse(
            """
            {
              "formatVersion": 1.0,
              "id": "float-pack",
              "displayName": "Float pack",
              "languageTag": "en-US",
              "version": "1",
              "engineId": "zeroinput.float",
              "files": [{"path":"dictionary.txt","sha256":"${"0".repeat(64)}","size":0}]
            }
            """.trimIndent(),
        )
        assertEquals(1, manifest.formatVersion)
    }

    @Test
    fun `language tags are mapped only to implemented languages`() {
        assertEquals(InputLanguage.CHINESE, LanguagePackLanguage.fromTag("zh-Hant"))
        assertEquals(InputLanguage.ENGLISH, LanguagePackLanguage.fromTag("EN-us"))
        assertNull(LanguagePackLanguage.fromTag("ja-JP"))
    }

    @Test
    fun `rejects a language that cannot be selected by the app`() {
        assertThrows(IllegalArgumentException::class.java) {
            LanguagePackParser.parse(
                """
                {
                  "formatVersion": 1,
                  "id": "unsupported-pack",
                  "displayName": "Unsupported pack",
                  "languageTag": "ja-JP",
                  "version": "1",
                  "engineId": "zeroinput.unsupported",
                  "files": [{"path":"dictionary.txt","sha256":"${"0".repeat(64)}","size":0}]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `manifest reader rejects bytes beyond the archive limit`() {
        val input = ByteArrayInputStream(ByteArray(9) { 'x'.code.toByte() })

        assertThrows(IllegalArgumentException::class.java) {
            LanguagePackManifestReader.read(input, maxBytes = 8)
        }
    }

    @Test
    fun `manifest reader rejects malformed UTF-8 instead of replacing it`() {
        val input = ByteArrayInputStream(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28))

        assertThrows(CharacterCodingException::class.java) {
            LanguagePackManifestReader.read(input, maxBytes = 32)
        }
    }
}

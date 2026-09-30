package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.DoublePinyinScheme
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DoublePinyinTest {
    @Test
    fun `microsoft and ziranma encode public syllables`() {
        assertEquals("nihk", listOf("ni", "hao").joinToString("") {
            DoublePinyin.encode(it, DoublePinyinScheme.MICROSOFT).orEmpty()
        })
        assertEquals("m;", DoublePinyin.encode("ming", DoublePinyinScheme.MICROSOFT))
        assertEquals("my", DoublePinyin.encode("ming", DoublePinyinScheme.ZIRANMA))
        assertEquals("vs", DoublePinyin.encode("zhong", DoublePinyinScheme.ZIRANMA))
        assertEquals("xx", DoublePinyin.encode("xie", DoublePinyinScheme.MICROSOFT))
        assertEquals("ol", DoublePinyin.encode("ai", DoublePinyinScheme.MICROSOFT))
        assertEquals("al", DoublePinyin.encode("ai", DoublePinyinScheme.ZIRANMA))
        assertEquals("or", DoublePinyin.encode("er", DoublePinyinScheme.MICROSOFT))
        assertEquals("er", DoublePinyin.encode("er", DoublePinyinScheme.ZIRANMA))
    }

    @Test
    fun `both schemes encode every ordinary dictionary syllable`() {
        val syllables = File("src/main/assets/rime/luna_pinyin.dict.yaml").useLines { lines ->
            lines.dropWhile { it != "..." }.drop(1).flatMap { line ->
                line.split('\t').getOrNull(1).orEmpty().split(' ').asSequence()
            }.filter { it.length in 1..8 && it.all { character -> character in 'a'..'z' } }.distinct().toList()
        }
        assertTrue(syllables.size >= 400)
        for (scheme in listOf(DoublePinyinScheme.MICROSOFT, DoublePinyinScheme.ZIRANMA)) {
            val missing = syllables.filter { DoublePinyin.encode(it, scheme) == null }
            assertEquals(emptyList<String>(), missing)
        }
    }

    @Test
    fun `generated rules isolate replacements before lowering codes`() {
        val rules = DoublePinyin.rules(listOf("ni", "hao", "ming"), DoublePinyinScheme.MICROSOFT)
        assertTrue(rules.contains("xform/^ming\u0024/M;/"))
        assertTrue(rules.last().startsWith("xlit/"))
        assertTrue(PinyinAlgebra.schemaId(ChineseInputOptions(doublePinyinScheme = DoublePinyinScheme.MICROSOFT)) !=
            PinyinAlgebra.schemaId(ChineseInputOptions(doublePinyinScheme = DoublePinyinScheme.ZIRANMA)))
    }

    @Test
    fun `fallback converts a two syllable phrase with either scheme`() {
        for (scheme in listOf(DoublePinyinScheme.MICROSOFT, DoublePinyinScheme.ZIRANMA)) {
            FallbackPinyinEngine(ChineseInputOptions(doublePinyinScheme = scheme)).use { engine ->
                engine.start(EditorContext(InputLanguage.CHINESE, false, false, null))
                for (key in "nihk") engine.handle(EngineKey.Character(key.toString()))
                assertTrue(engine.snapshot.candidates.any { it.text == "你好" })
            }
        }
    }
}

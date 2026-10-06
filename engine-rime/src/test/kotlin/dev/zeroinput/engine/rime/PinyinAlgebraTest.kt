package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseScript
import dev.zeroinput.engine.api.FuzzyPinyinPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinAlgebraTest {
    @Test
    fun `default enables abbreviations without widening exact syllables`() {
        val rules = PinyinAlgebra.rules(ChineseInputOptions())
        assertTrue(rules.isNotEmpty())
        assertTrue(rules.all { it.startsWith("abbrev/") })
    }

    @Test
    fun `each fuzzy pair is independently reversible`() {
        val initial = ChineseInputOptions(abbreviatedPinyin = false)
        for (pair in FuzzyPinyinPair.entries) {
            val enabled = initial.withFuzzy(pair, true)
            assertEquals(2, PinyinAlgebra.rules(enabled).size)
            assertTrue(enabled.isFuzzyEnabled(pair))
            assertFalse(initial.isFuzzyEnabled(pair))
            assertEquals(initial.withAllFuzzy(false), enabled.withFuzzy(pair, false))
            assertNotEquals(PinyinAlgebra.schemaId(initial), PinyinAlgebra.schemaId(enabled))
        }
    }

    @Test
    fun `display switches do not rebuild the syllable index`() {
        val initial = ChineseInputOptions()
        val switched = initial.copy(script = ChineseScript.TRADITIONAL, chinesePunctuation = false)
        assertEquals(PinyinAlgebra.schemaId(initial), PinyinAlgebra.schemaId(switched))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown fuzzy bits fail closed`() {
        ChineseInputOptions(fuzzyPinyinMask = ChineseInputOptions.MAX_FUZZY_PINYIN_MASK + 1)
    }

    @Test
    fun `extended initial and nasal pairs compile reversible rules`() {
        val options = ChineseInputOptions(abbreviatedPinyin = false)
            .withFuzzy(FuzzyPinyinPair.R_L, true)
            .withFuzzy(FuzzyPinyinPair.H_F, true)
            .withFuzzy(FuzzyPinyinPair.IAN_IANG, true)
            .withFuzzy(FuzzyPinyinPair.UAN_UANG, true)
            .withFuzzy(FuzzyPinyinPair.ON_ONG, true)
        val rules = PinyinAlgebra.rules(options)
        assertTrue(rules.contains("derive/^r/l/"))
        assertTrue(rules.contains("derive/^h/f/"))
        assertTrue(rules.contains("derive/ian\u0024/iang/"))
        assertTrue(rules.contains("derive/uan\u0024/uang/"))
        assertTrue(rules.contains("derive/on\u0024/ong/"))
    }

    @Test
    fun `shared sibilant initials keep the h boundary when deriving longer spellings`() {
        val options = ChineseInputOptions(abbreviatedPinyin = false)
            .withFuzzy(FuzzyPinyinPair.Z_ZH, true)
            .withFuzzy(FuzzyPinyinPair.C_CH, true)
            .withFuzzy(FuzzyPinyinPair.S_SH, true)
        val rules = PinyinAlgebra.rules(options)

        assertTrue(rules.contains("derive/^zh/z/"))
        assertTrue(rules.contains("derive/^z([^h])/zh\u00241/"))
        assertTrue(rules.contains("derive/^ch/c/"))
        assertTrue(rules.contains("derive/^c([^h])/ch\u00241/"))
        assertTrue(rules.contains("derive/^sh/s/"))
        assertTrue(rules.contains("derive/^s([^h])/sh\u00241/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unbounded candidate pages are rejected`() {
        ChineseInputOptions(candidatePageSize = 1000)
    }
}

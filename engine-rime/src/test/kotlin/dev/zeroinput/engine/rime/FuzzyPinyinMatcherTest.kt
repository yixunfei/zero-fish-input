package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.FuzzyPinyinPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyPinyinMatcherTest {
    @Test
    fun `every enabled pair matches both directions while disabled pairs leave readings unchanged`() {
        val examples = listOf(
            Triple(FuzzyPinyinPair.Z_ZH, "zi", "zhi"),
            Triple(FuzzyPinyinPair.C_CH, "ci", "chi"),
            Triple(FuzzyPinyinPair.S_SH, "si", "shi"),
            Triple(FuzzyPinyinPair.N_L, "ni", "li"),
            Triple(FuzzyPinyinPair.HU_FU, "hu", "fu"),
            Triple(FuzzyPinyinPair.AN_ANG, "ban", "bang"),
            Triple(FuzzyPinyinPair.EN_ENG, "ben", "beng"),
            Triple(FuzzyPinyinPair.IN_ING, "bin", "bing"),
            Triple(FuzzyPinyinPair.R_L, "ren", "len"),
            Triple(FuzzyPinyinPair.H_F, "han", "fan"),
            Triple(FuzzyPinyinPair.IAN_IANG, "jian", "jiang"),
            Triple(FuzzyPinyinPair.UAN_UANG, "guan", "guang"),
            Triple(FuzzyPinyinPair.ON_ONG, "gon", "gong"),
        )
        assertEquals(FuzzyPinyinPair.entries.toSet(), examples.map { it.first }.toSet())
        for ((pair, left, right) in examples) {
            val options = ChineseInputOptions().withFuzzy(pair, true)
            assertEquals(listOf(left, right), FuzzyPinyinMatcher.variants(left, options))
            assertEquals(listOf(right, left), FuzzyPinyinMatcher.variants(right, options))
            assertEquals(listOf(left), FuzzyPinyinMatcher.variants(left, ChineseInputOptions()))
        }
    }

    @Test
    fun `exact hu fu rules do not alter longer syllables`() {
        val options = ChineseInputOptions().withFuzzy(FuzzyPinyinPair.HU_FU, true)
        assertEquals(listOf("huan"), FuzzyPinyinMatcher.variants("huan", options))
        assertEquals(listOf("fuhao", "huhao"),
            FuzzyPinyinMatcher.variants("fuhao", options, setOf("fu", "hao")))
    }

    @Test
    fun `phrase matching preserves unchanged syllables and canonical reading first`() {
        val options = ChineseInputOptions().withFuzzy(FuzzyPinyinPair.N_L, true)
        val variants = FuzzyPinyinMatcher.variants("nihao", options, setOf("ni", "hao"))
        assertEquals(listOf("nihao", "lihao"), variants)
        assertFalse("nihlao" in variants)
    }

    @Test
    fun `ambiguous segmentation with an unmatched suffix has a total search budget`() {
        var probes = 0
        val syllables = object : Set<String> by setOf("a", "aa", "aaa", "aaaa", "aaaaa", "aaaaaa") {
            override fun contains(element: String): Boolean {
                probes++
                return element.length in 1..6 && element.all { it == 'a' }
            }
        }
        val reading = "a".repeat(24) + "b"
        val options = ChineseInputOptions().withFuzzy(FuzzyPinyinPair.N_L, true)
        assertEquals(listOf(reading), FuzzyPinyinMatcher.variants(reading, options, syllables))
        assertTrue(probes in 1..3072)
    }

    @Test
    fun `long or invalid input never expands and valid results remain bounded`() {
        val options = ChineseInputOptions(fuzzyPinyinMask = ChineseInputOptions.MAX_FUZZY_PINYIN_MASK)
        for (reading in listOf("n".repeat(129), "ni3", "NI", "")) {
            assertEquals(listOf(reading), FuzzyPinyinMatcher.variants(reading, options))
        }
        val variants = FuzzyPinyinMatcher.variants("ni".repeat(32), options, setOf("ni"))
        assertTrue(variants.size <= 64)
        assertEquals(variants.distinct(), variants)
    }
}

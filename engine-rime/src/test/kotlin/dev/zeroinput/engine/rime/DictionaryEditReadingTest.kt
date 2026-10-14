package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class DictionaryEditReadingTest {
    @Test fun spellingSupportsFullMixedAbbreviationsAndExplicitBoundaries() {
        for (code in listOf("nihao", "ni'hao", "nh", "nhao", "nih", "zhg")) {
            assertTrue(code, DictionaryEditReading.matches(if (code == "zhg") "zhong guo" else "ni hao", code, ChineseInputOptions()))
        }
        assertFalse(DictionaryEditReading.matches("ni hao", "nhao", ChineseInputOptions(abbreviatedPinyin = false)))
        assertFalse(DictionaryEditReading.matches("ni hao", "nihk", ChineseInputOptions()))
    }
    @Test fun nineKeyRespectsSelectedSyllablesAndDoublePinyinUsesConfiguredScheme() {
        for (code in listOf("64426", "ni'426", "64'hao")) assertTrue(DictionaryEditReading.matches("ni hao", code,
            ChineseInputOptions(keyboardLayout = ChineseKeyboardLayout.NINE_KEY)))
        for (scheme in listOf(DoublePinyinScheme.MICROSOFT, DoublePinyinScheme.ZIRANMA)) {
            assertTrue(DictionaryEditReading.matches("ni hao", "nihk", ChineseInputOptions(doublePinyinScheme = scheme)))
        }
    }
    @Test fun fuzzyMatchingFollowsEnabledPairs() {
        assertFalse(DictionaryEditReading.matches("ni hao", "lihao", ChineseInputOptions()))
        assertTrue(DictionaryEditReading.matches("ni hao", "lihao", ChineseInputOptions().withFuzzy(FuzzyPinyinPair.N_L, true)))
        assertFalse(DictionaryEditReading.matches("", "n", ChineseInputOptions()))
    }
}

package dev.zeroinput.engine.api

import org.junit.Assert.*
import org.junit.Test

class ChineseInputOptionsTest {
    @Test fun masterSwitchSelectsAllRulesAndClearsEverySelection() {
        val selected = ChineseInputOptions().withFuzzy(FuzzyPinyinPair.N_L, true).withAllFuzzy(true)
        assertEquals(ChineseInputOptions.MAX_FUZZY_PINYIN_MASK, selected.effectiveFuzzyPinyinMask)
        assertTrue(FuzzyPinyinPair.entries.all(selected::isFuzzyEnabled))
        val cleared = selected.withAllFuzzy(false)
        assertEquals(0, cleared.fuzzyPinyinMask)
        assertFalse(cleared.fuzzyPinyinEnabled)
        assertTrue(FuzzyPinyinPair.entries.none(cleared::isFuzzySelected))
        assertEquals(selected, cleared.withAllFuzzy(true))
    }

    @Test fun individualRuleCanBeEnabledAfterTurningEverythingOff() {
        val selected = ChineseInputOptions().withAllFuzzy(false).withFuzzy(FuzzyPinyinPair.N_L, true)
        assertTrue(selected.isFuzzyEnabled(FuzzyPinyinPair.N_L))
        assertEquals(1, FuzzyPinyinPair.entries.count(selected::isFuzzyEnabled))
        assertFalse(selected.withFuzzy(FuzzyPinyinPair.N_L, false).fuzzyPinyinEnabled)
    }
}

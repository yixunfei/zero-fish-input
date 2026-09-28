package dev.zeroinput.engine.api

import org.junit.Assert.*
import org.junit.Test

class ChineseInputOptionsTest {
    @Test fun masterSwitchSuspendsRulesWithoutDiscardingSelections() {
        val selected = ChineseInputOptions().withFuzzy(FuzzyPinyinPair.N_L, true)
            .withFuzzy(FuzzyPinyinPair.AN_ANG, true)
        val paused = selected.copy(fuzzyPinyinEnabled = false)
        assertEquals(selected.fuzzyPinyinMask, paused.fuzzyPinyinMask)
        assertTrue(paused.isFuzzySelected(FuzzyPinyinPair.N_L))
        assertTrue(FuzzyPinyinPair.entries.none(paused::isFuzzyEnabled))
        assertEquals(0, paused.effectiveFuzzyPinyinMask)
        assertEquals(selected, paused.copy(fuzzyPinyinEnabled = true))
    }

    @Test fun changingSelectedRulesWhilePausedDoesNotEnableThem() {
        val paused = ChineseInputOptions(fuzzyPinyinEnabled = false).withFuzzy(FuzzyPinyinPair.N_L, true)
        assertTrue(paused.isFuzzySelected(FuzzyPinyinPair.N_L))
        assertFalse(paused.isFuzzyEnabled(FuzzyPinyinPair.N_L))
        assertTrue(paused.copy(fuzzyPinyinEnabled = true).isFuzzyEnabled(FuzzyPinyinPair.N_L))
    }
}

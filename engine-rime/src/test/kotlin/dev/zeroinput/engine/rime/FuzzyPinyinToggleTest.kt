package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.FuzzyPinyinPair
import org.junit.Assert.*
import org.junit.Test

class FuzzyPinyinToggleTest {
    @Test fun disabledRulesUseThePlainSchemaAndReturnWhenReenabled() {
        val enabled = ChineseInputOptions().withFuzzy(FuzzyPinyinPair.N_L, true)
        val disabled = enabled.copy(fuzzyPinyinEnabled = false)
        assertEquals(PinyinAlgebra.schemaId(ChineseInputOptions()), PinyinAlgebra.schemaId(disabled))
        assertEquals(PinyinAlgebra.rules(ChineseInputOptions()), PinyinAlgebra.rules(disabled))
        assertNotEquals(PinyinAlgebra.schemaId(enabled), PinyinAlgebra.schemaId(disabled))
        assertEquals(PinyinAlgebra.rules(enabled), PinyinAlgebra.rules(disabled.copy(fuzzyPinyinEnabled = true)))
    }

    @Test fun fallbackHonorsTheMasterSwitch() {
        val enabled = ChineseInputOptions().withFuzzy(FuzzyPinyinPair.N_L, true)
        assertTrue(FallbackPinyinEngine(enabled).restoreComposition("lihao").snapshot.candidates.any { it.text == "你好" })
        assertFalse(FallbackPinyinEngine(enabled.copy(fuzzyPinyinEnabled = false))
            .restoreComposition("lihao").snapshot.candidates.any { it.text == "你好" })
    }
}

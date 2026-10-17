package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class RimeLearningInputTest {
    @Test fun abbreviationKeepsTheCodeTheUserActuallySelected() {
        val options = ChineseInputOptions()
        assertEquals("cs", RimeLearningInput.choose("cs", "ce shi", options))
        assertEquals("ceshi", RimeLearningInput.choose("ceshi", "ce shi", options))
    }

    @Test fun correctionOnlySpellingFallsBackToCanonicalReading() {
        val options = ChineseInputOptions()
        assertEquals("ceshi", RimeLearningInput.choose("cxshi", "ce shi", options))
        assertEquals("ceshi", RimeLearningInput.choose("cs", "ce shi",
            ChineseInputOptions(abbreviatedPinyin = false)))
    }
}

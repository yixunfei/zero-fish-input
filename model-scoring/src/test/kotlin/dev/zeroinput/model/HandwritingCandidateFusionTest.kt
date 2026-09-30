package dev.zeroinput.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingCandidateFusionTest {
    @Test fun strokeAndImageAlternativesRemainSelectableWithoutDuplicates() {
        val result = HandwritingCandidateFusion.combine(listOf(
            listOf(HandwritingStrokeCandidate("学", .8f), HandwritingStrokeCandidate("字", .3f)),
            listOf(HandwritingStrokeCandidate("學", 1.2f), HandwritingStrokeCandidate("字", .4f)),
        ), listOf("学", "字", "子"))
        assertEquals(listOf("學", "学", "字", "子"), result)
    }

    @Test fun invalidCandidatesAreExcludedAndResultsRemainBounded() {
        val models = List(3) { offset -> List(30) { HandwritingStrokeCandidate((0x4e00 + offset * 100 + it).toChar().toString(), it.toFloat()) } }
        val result = HandwritingCandidateFusion.combine(models, List(30) { (0x5e00 + it).toChar().toString() })
        assertEquals(16, result.size)
        assertTrue(result.none { it.first().code in 0x4e00 + 200..0x4e00 + 230 })
        assertTrue(HandwritingCandidateFusion.combine(listOf(listOf(
            HandwritingStrokeCandidate("中", Float.NaN), HandwritingStrokeCandidate("a", 1f),
        )), listOf("abc", "", "中文")).isEmpty())
    }
}

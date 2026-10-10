package dev.zeroinput.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateRowPackingTest {
    @Test fun shortWordsShareARowAndUseItsRemainingSpace() {
        assertEquals(listOf(60, 60, 60, 60, 60), CandidateRowPacking.spans(List(5) { 48 }, 300))
    }

    @Test fun mixedLengthsPreserveOrderAndFillEachRow() {
        assertEquals(listOf(60, 140, 100, 300), CandidateRowPacking.spans(listOf(48, 128, 88, 200), 300))
    }

    @Test fun oversizedWordsAndNarrowViewportsRemainBounded() {
        assertEquals(listOf(32, 32), CandidateRowPacking.spans(listOf(1000, 48), 32))
        assertEquals(listOf(1), CandidateRowPacking.spans(listOf(48), 0))
        assertEquals(emptyList<Int>(), CandidateRowPacking.spans(emptyList(), 300))
    }
}

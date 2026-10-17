package dev.zeroinput.ime.ui

import org.junit.Assert.*
import org.junit.Test

class CandidateRowContinuityTest {
    @Test fun evictionDoesNotChangeTheNeighboursOfRetainedRows() {
        val layout = CandidateRowContinuity()
        assertEquals(List(12) { 100 }, pack(layout, 0, 12))
        assertEquals(listOf(300) + List(9) { 100 } + listOf(150, 150), pack(layout, 2, 12))
    }

    @Test fun repeatedShiftsAndReverseLoadingKeepTheSameMiddleRows() {
        val layout = CandidateRowContinuity()
        pack(layout, 0, 18)
        pack(layout, 2, 18)
        val forward = pack(layout, 4, 18)
        assertEquals(listOf(150, 150) + List(12) { 100 } + listOf(150, 150, 150, 150), forward)
        val backward = pack(layout, 2, 18)
        assertEquals(listOf(150, 150, 150, 150) + List(12) { 100 } + listOf(150, 150), backward)
    }

    @Test fun appendingDoesNotResizeThePreviousLastRow() {
        val layout = CandidateRowContinuity()
        assertEquals(listOf(100, 100, 100, 300), pack(layout, 0, 4))
        assertEquals(listOf(100, 100, 100, 300, 150, 150), pack(layout, 0, 6))
    }

    @Test fun changedInputWidthOrMeasurementsRepackInsteadOfReusingOldRows() {
        val layout = CandidateRowContinuity()
        pack(layout, 0, 4)
        assertEquals(listOf(100, 100, 100, 100, 100, 100), pack(layout, 0, 6, preserve = false))
        assertEquals(List(5) { 90 } + 450, pack(layout, 0, 6, width = 450))
        assertEquals(List(6) { 225 }, layout.pack(ids(0, 6), List(6) { 200 }, 450, true))
        assertEquals(emptyList<Int>(), layout.pack(emptyList(), emptyList(), 300, true))
    }

    private fun ids(start: Int, count: Int) = List(count) { "candidate:${start + it}" }
    private fun pack(layout: CandidateRowContinuity, start: Int, count: Int, width: Int = 300, preserve: Boolean = true) =
        layout.pack(ids(start, count), List(count) { 90 }, width, preserve)
}

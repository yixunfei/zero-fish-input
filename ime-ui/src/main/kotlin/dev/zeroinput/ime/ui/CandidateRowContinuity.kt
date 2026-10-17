package dev.zeroinput.ime.ui

/** Keeps complete retained rows fixed when a bounded candidate window shifts. */
internal class CandidateRowContinuity {
    private var ids: List<String> = emptyList()
    private var widths: List<Int> = emptyList()
    private var spans: List<Int> = emptyList()
    private var rowWidth = 0

    fun pack(nextIds: List<String>, nextWidths: List<Int>, available: Int, preserve: Boolean): List<Int> {
        require(nextIds.size == nextWidths.size)
        val width = available.coerceAtLeast(1)
        val retained = if (preserve && width == rowWidth) retainedRows(nextIds, nextWidths) else null
        val result = if (retained == null) CandidateRowPacking.spans(nextWidths, width) else {
            val (oldStart, newStart, count) = retained
            CandidateRowPacking.spans(nextWidths.take(newStart), width) +
                spans.subList(oldStart, oldStart + count) +
                CandidateRowPacking.spans(nextWidths.drop(newStart + count), width)
        }
        ids = nextIds.toList()
        widths = nextWidths.toList()
        spans = result
        rowWidth = width
        return result
    }

    private fun retainedRows(nextIds: List<String>, nextWidths: List<Int>): RetainedRows? {
        if (ids.isEmpty() || nextIds.isEmpty()) return null
        val oldStart = ids.indexOf(nextIds.first()).coerceAtLeast(0)
        val newStart = nextIds.indexOf(ids[oldStart])
        if (newStart < 0) return null
        val count = minOf(ids.size - oldStart, nextIds.size - newStart)
        if ((0 until count).any { ids[oldStart + it] != nextIds[newStart + it] ||
                widths[oldStart + it] != nextWidths[newStart + it] }) return null

        // Partial rows at an evicted edge may be repacked. Complete overlapping
        // rows keep their widths, wrapping and neighbours in both directions.
        var used = 0
        var first = -1
        var end = -1
        for (index in spans.indices) {
            if (used == 0 && index >= oldStart && first < 0) first = index
            used = (used + spans[index]) % rowWidth
            if (used == 0 && index + 1 <= oldStart + count) end = index + 1
        }
        if (first < 0 || end <= first) return null
        return RetainedRows(first, newStart + first - oldStart, end - first)
    }

    private data class RetainedRows(val oldStart: Int, val newStart: Int, val count: Int)
}

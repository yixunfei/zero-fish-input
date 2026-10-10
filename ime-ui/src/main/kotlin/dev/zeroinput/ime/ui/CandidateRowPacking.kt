package dev.zeroinput.ime.ui

/** Packs consecutive candidates and shares leftover space within each row. */
internal object CandidateRowPacking {
    fun spans(widths: List<Int>, rowWidth: Int): List<Int> {
        val available = rowWidth.coerceAtLeast(1)
        val result = widths.map { it.coerceIn(1, available) }.toMutableList()
        var start = 0
        while (start < result.size) {
            var end = start
            var used = 0
            while (end < result.size && used + result[end] <= available) {
                used += result[end++]
            }
            val count = end - start
            val extra = available - used
            for (index in start until end) {
                result[index] += extra / count + if (index - start < extra % count) 1 else 0
            }
            start = end
        }
        return result
    }
}

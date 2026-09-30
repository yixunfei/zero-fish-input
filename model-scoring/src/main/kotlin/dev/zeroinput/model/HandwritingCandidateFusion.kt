package dev.zeroinput.model

/** Keep both shape and stroke-order evidence visible; no frequency or personal ranking. */
internal object HandwritingCandidateFusion {
    const val LIMIT = 16

    fun combine(strokeResults: List<List<HandwritingStrokeCandidate>>, imageResults: List<String>): List<String> {
        val stroke = strokeResults.asSequence().take(2).flatMap { it.asSequence().take(8) }
            .filter { it.score.isFinite() && isHan(it.text) }
            .sortedByDescending { it.score }.map { it.text }.distinct().take(8).toList()
        val image = imageResults.asSequence().take(8).filter(::isHan).distinct().toList()
        val result = LinkedHashSet<String>(LIMIT)
        for (index in 0 until 8) {
            stroke.getOrNull(index)?.let(result::add)
            image.getOrNull(index)?.let(result::add)
        }
        return result.take(LIMIT)
    }

    private fun isHan(value: String): Boolean = value.isNotEmpty() &&
        value.codePointCount(0, value.length) == 1 &&
        Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN
}

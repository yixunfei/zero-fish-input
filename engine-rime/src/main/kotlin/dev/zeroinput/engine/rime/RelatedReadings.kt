package dev.zeroinput.engine.rime

/** Bounded public-syllable search, used only when exact candidate pages end. */
internal class RelatedReadings(syllables: List<String>) {
    private val syllables = syllables.toHashSet()

    fun alternatives(raw: String): List<String> {
        if (raw.length !in 2..64 || raw.any { it !in 'a'..'z' && it != '\'' }) return emptyList()
        val compact = raw.replace("'", "")
        val segmented = ArrayList<List<String>>()
        segment(compact, 0, ArrayList(), segmented, intArrayOf(512))
        val results = LinkedHashSet<String>()
        for (parts in segmented.drop(1)) {
            val reading = parts.joinToString("'")
            if (reading != raw && parts.size > 1) results += reading
        }
        for (parts in segmented) for (index in parts.indices) {
            for (replacement in neighbors(parts[index])) {
                results += parts.mapIndexed { i, value -> if (i == index) replacement else value }.joinToString("'")
                if (results.size >= MAX_READINGS) return results.toList()
            }
        }
        return results.toList()
    }

    private fun segment(raw: String, start: Int, parts: MutableList<String>, result: MutableList<List<String>>, work: IntArray) {
        if (work[0]-- <= 0 || result.size >= 8 || parts.size > 16) return
        if (start == raw.length) { result += parts.toList(); return }
        for (end in minOf(raw.length, start + 6) downTo start + 1) {
            val part = raw.substring(start, end)
            if (part !in syllables) continue
            parts += part
            segment(raw, end, parts, result, work)
            parts.removeAt(parts.lastIndex)
        }
    }

    private fun neighbors(value: String): List<String> = buildList {
        for ((left, right) in INITIALS) {
            if (value.startsWith(left)) add(right + value.drop(left.length))
            if (value.startsWith(right)) add(left + value.drop(right.length))
        }
        for ((left, right) in FINALS) {
            if (value.endsWith(left)) add(value.dropLast(left.length) + right)
            if (value.endsWith(right)) add(value.dropLast(right.length) + left)
        }
    }.filter { it != value && it in syllables }.distinct()

    companion object {
        private const val MAX_READINGS = 32
        private val INITIALS = listOf(
            "z" to "zh", "c" to "ch", "s" to "sh", "n" to "l", "r" to "l", "h" to "f",
        )
        private val FINALS = listOf(
            "an" to "ang", "en" to "eng", "in" to "ing",
            "ian" to "iang", "uan" to "uang", "on" to "ong",
        )
    }
}

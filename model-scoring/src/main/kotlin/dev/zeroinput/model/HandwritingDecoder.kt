package dev.zeroinput.model

import java.nio.FloatBuffer

/** Scores exactly one Han character through the entire CTC sequence. */
internal object HandwritingDecoder {
    private const val CLASS_COUNT = 18_385

    fun decode(probabilities: FloatBuffer, steps: Int, characters: List<String>, limit: Int = 8): List<String> {
        if (steps !in 1..256 || characters.size != CLASS_COUNT || limit !in 1..8 ||
            probabilities.remaining() != steps * CLASS_COUNT) return emptyList()
        val inside = DoubleArray(CLASS_COUNT)
        val after = DoubleArray(CLASS_COUNT)
        try {
            val blank = score(probabilities, steps, inside, after) ?: return emptyList()
            val ranked = ArrayList<Int>(limit)
            var bestClass = 0
            for (index in 1 until CLASS_COUNT) {
                inside[index] += after[index]
                if (inside[index] > inside[bestClass]) bestClass = index
                if (inside[index] <= 0.0 || !isHan(characters[index])) continue
                val position = ranked.indexOfFirst { inside[index] > inside[it] }
                    .let { if (it < 0) ranked.size else it }
                if (position < limit) ranked.add(position, index)
                if (ranked.size > limit) ranked.removeAt(limit)
            }
            // An empty/non-Han result must not be turned into arbitrary Han alternatives.
            val bestHan = ranked.firstOrNull() ?: return emptyList()
            if (inside[bestHan] <= blank || !isHan(characters[bestClass])) return emptyList()
            return ranked.map { characters[it] }.distinct()
        } finally {
            inside.fill(0.0)
            after.fill(0.0)
        }
    }

    private fun score(values: FloatBuffer, steps: Int, inside: DoubleArray, after: DoubleArray): Double? {
        var before = 1.0
        repeat(steps) {
            val blank = values.get().toDouble()
            if (!validProbability(blank)) return null
            var rowSum = blank
            var scale = before * blank
            for (index in 1 until CLASS_COUNT) {
                val probability = values.get().toDouble()
                if (!validProbability(probability)) return null
                rowSum += probability
                // A second character after a separating blank would decode as two characters.
                after[index] = (after[index] + inside[index]) * blank
                inside[index] = (inside[index] + before) * probability
                scale = maxOf(scale, inside[index], after[index])
            }
            if (rowSum !in 0.98..1.02 || scale <= 0.0) return null
            before = before * blank / scale
            for (index in 1 until CLASS_COUNT) {
                inside[index] /= scale
                after[index] /= scale
            }
        }
        return before
    }

    private fun validProbability(value: Double): Boolean = value.isFinite() && value in 0.0..1.0

    private fun isHan(value: String): Boolean = value.isNotEmpty() &&
        value.codePointCount(0, value.length) == 1 &&
        Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN
}

package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions

/**
 * Produces bounded, one-edit reading variants for the in-memory fallback.
 * Rime remains the source of truth for native matching; this small matcher
 * only keeps the immediate engine useful while the native session warms up.
 */
internal object FuzzyPinyinMatcher {
    fun variants(
        reading: String,
        options: ChineseInputOptions,
        syllables: Set<String> = emptySet(),
    ): List<String> {
        if (options.effectiveFuzzyPinyinMask == 0 || reading.length !in 1..MAX_READING_LENGTH ||
            reading.any { it !in 'a'..'z' }) return listOf(reading)
        val result = LinkedHashSet<String>()
        result += reading
        for (parts in segmentations(reading, syllables)) {
            for (index in parts.indices) {
                val part = parts[index]
                for (rule in FuzzyPinyinRules.all) {
                    if (!options.isFuzzyEnabled(rule.pair)) continue
                    val replacement = replacement(part, rule) ?: continue
                    result += parts.mapIndexed { partIndex, value ->
                        if (partIndex == index) replacement else value
                    }.joinToString("")
                    if (result.size >= MAX_VARIANTS) return result.toList()
                }
            }
        }
        return result.toList()
    }

    private fun replacement(value: String, rule: FuzzyPinyinRules.Rule): String? {
        if (rule.scope == FuzzyPinyinRules.Scope.EXACT) {
            return when (value) {
                rule.left -> rule.right
                rule.right -> rule.left
                else -> null
            }
        }
        return when (rule.scope) {
            FuzzyPinyinRules.Scope.INITIAL -> when {
                value.startsWith(rule.right) -> rule.left + value.drop(rule.right.length)
                value.startsWith(rule.left) && !hasSharedInitialSuffix(value, rule) ->
                    rule.right + value.drop(rule.left.length)
                else -> null
            }
            FuzzyPinyinRules.Scope.FINAL -> when {
                value.endsWith(rule.left) -> value.dropLast(rule.left.length) + rule.right
                value.endsWith(rule.right) -> value.dropLast(rule.right.length) + rule.left
                else -> null
            }
            FuzzyPinyinRules.Scope.EXACT -> null
        }
    }

    private fun hasSharedInitialSuffix(value: String, rule: FuzzyPinyinRules.Rule): Boolean =
        rule.sharedInitialSuffix != null && value.startsWith(rule.left + rule.sharedInitialSuffix)

    private fun segmentations(reading: String, syllables: Set<String>): List<List<String>> {
        if (syllables.isEmpty()) return listOf(listOf(reading))
        val result = ArrayList<List<String>>()
        segment(reading, 0, syllables, ArrayList(), result, intArrayOf(MAX_SEGMENT_WORK))
        return result.ifEmpty { listOf(listOf(reading)) }.take(MAX_SEGMENTATIONS)
    }

    private fun segment(
        reading: String,
        start: Int,
        syllables: Set<String>,
        parts: MutableList<String>,
        result: MutableList<List<String>>,
        work: IntArray,
    ) {
        if (result.size >= MAX_SEGMENTATIONS || work[0]-- <= 0) return
        if (start == reading.length) {
            result += parts.toList()
            return
        }
        if (parts.size >= MAX_PARTS) return
        for (end in minOf(reading.length, start + MAX_SYLLABLE_LENGTH) downTo start + 1) {
            if (work[0] <= 0 || result.size >= MAX_SEGMENTATIONS) return
            val part = reading.substring(start, end)
            if (part !in syllables) continue
            parts += part
            segment(reading, end, syllables, parts, result, work)
            parts.removeAt(parts.lastIndex)
        }
    }

    private const val MAX_SYLLABLE_LENGTH = 6
    private const val MAX_SEGMENTATIONS = 8
    private const val MAX_SEGMENT_WORK = 512
    private const val MAX_PARTS = 32
    private const val MAX_READING_LENGTH = 128
    private const val MAX_VARIANTS = 64
}
